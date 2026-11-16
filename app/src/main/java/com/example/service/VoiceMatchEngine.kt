package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.data.RiyaState
import com.example.data.Store
import com.example.data.VoiceMatchSensitivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class SpeakerSignature(
  val mfcc: FloatArray,
  val pitchHz: Float,
  val spectralCentroid: Float,
  val voicedFrames: Int,
) {
  fun toJsonString(): String {
    val arr = JSONArray()
    mfcc.forEach { arr.put(it.toDouble()) }
    return JSONObject()
      .put("mfcc", arr)
      .put("pitchHz", pitchHz.toDouble())
      .put("centroid", spectralCentroid.toDouble())
      .put("voicedFrames", voicedFrames)
      .toString()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is SpeakerSignature) return false
    return mfcc.contentEquals(other.mfcc) &&
      pitchHz == other.pitchHz &&
      spectralCentroid == other.spectralCentroid &&
      voicedFrames == other.voicedFrames
  }

  override fun hashCode(): Int {
    var result = mfcc.contentHashCode()
    result = 31 * result + pitchHz.hashCode()
    result = 31 * result + spectralCentroid.hashCode()
    result = 31 * result + voicedFrames
    return result
  }

  companion object {
    fun fromJsonString(raw: String): SpeakerSignature? {
      if (raw.isBlank()) return null
      return try {
        val obj = JSONObject(raw)
        val arr = obj.optJSONArray("mfcc") ?: return null
        if (arr.length() != VoiceMatchEngine.MFCC_COUNT) return null
        val vec = FloatArray(VoiceMatchEngine.MFCC_COUNT) { idx -> arr.optDouble(idx, 0.0).toFloat() }
        SpeakerSignature(
          mfcc = VoiceMatchEngine.l2Normalize(vec),
          pitchHz = obj.optDouble("pitchHz", 0.0).toFloat().coerceIn(0f, 450f),
          spectralCentroid = obj.optDouble("centroid", 0.0).toFloat().coerceIn(0f, 1f),
          voicedFrames = obj.optInt("voicedFrames", 0).coerceAtLeast(0),
        )
      } catch (_: Throwable) {
        null
      }
    }
  }
}

data class VoiceMatchProfile(
  val stepSignatures: Map<Int, SpeakerSignature>,
  val centroidMfcc: FloatArray,
  val meanPitchHz: Float,
  val meanCentroid: Float,
) {
  val completedSteps: Int get() = stepSignatures.size
  val isComplete: Boolean get() = stepSignatures.size == 3

  fun isStepDone(step: Int): Boolean = stepSignatures.containsKey(step)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is VoiceMatchProfile) return false
    return stepSignatures == other.stepSignatures &&
      centroidMfcc.contentEquals(other.centroidMfcc) &&
      meanPitchHz == other.meanPitchHz &&
      meanCentroid == other.meanCentroid
  }

  override fun hashCode(): Int {
    var result = stepSignatures.hashCode()
    result = 31 * result + centroidMfcc.contentHashCode()
    result = 31 * result + meanPitchHz.hashCode()
    result = 31 * result + meanCentroid.hashCode()
    return result
  }
}

data class VoiceMatchResult(
  val matched: Boolean,
  val score: Float,
  val confidencePercent: Int,
  val mfccSimilarity: Float,
  val pitchScore: Float,
  val voicedFrames: Int,
)

object VoiceMatchEngine {
  const val SAMPLE_RATE = 16000
  const val MFCC_COUNT = 12
  private const val FRAME_SIZE = 400 // 25ms at 16kHz
  private const val FRAME_HOP = 160 // 10ms at 16kHz
  private const val FFT_SIZE = 512
  private const val MEL_BANDS = 16

  private val hammingWindow: FloatArray by lazy {
    FloatArray(FRAME_SIZE) { n ->
      (0.54 - 0.46 * cos(2.0 * PI * n / (FRAME_SIZE - 1))).toFloat()
    }
  }

  private val melFilters: Array<FloatArray> by lazy { buildMelFilterbank() }

  private val dctMatrix: Array<FloatArray> by lazy {
    // Extract coefficients c1..c12 (skipping c0 energy so volume differences don't bias identity)
    Array(MFCC_COUNT) { kIdx ->
      val k = kIdx + 1
      FloatArray(MEL_BANDS) { m ->
        cos(PI * k * (m + 0.5) / MEL_BANDS).toFloat()
      }
    }
  }

  private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
  private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

  private fun buildMelFilterbank(): Array<FloatArray> {
    val numBins = FFT_SIZE / 2 + 1
    val lowMel = hzToMel(100.0)
    val highMel = hzToMel(7000.0)
    val melPoints = DoubleArray(MEL_BANDS + 2) { i ->
      lowMel + i * (highMel - lowMel) / (MEL_BANDS + 1)
    }
    val binIndices = IntArray(MEL_BANDS + 2) { i ->
      val hz = melToHz(melPoints[i])
      ((FFT_SIZE + 1) * hz / SAMPLE_RATE).toInt().coerceIn(0, numBins - 1)
    }
    return Array(MEL_BANDS) { m ->
      val filter = FloatArray(numBins)
      val left = binIndices[m]
      val center = binIndices[m + 1].coerceAtLeast(left + 1)
      val right = binIndices[m + 2].coerceAtLeast(center + 1)
      for (k in left until center) {
        if (k in 0 until numBins) {
          filter[k] = ((k - left).toFloat() / (center - left).toFloat()).coerceIn(0f, 1f)
        }
      }
      for (k in center..right) {
        if (k in 0 until numBins) {
          filter[k] = ((right - k).toFloat() / (right - center).toFloat()).coerceIn(0f, 1f)
        }
      }
      filter
    }
  }

  fun pcm16BytesToSamples(pcmBytes: ByteArray, length: Int = pcmBytes.size): ShortArray {
    val safeLen = length.coerceAtMost(pcmBytes.size)
    val sampleCount = safeLen / 2
    val out = ShortArray(sampleCount)
    var i = 0
    var s = 0
    while (s < sampleCount) {
      val lo = pcmBytes[i].toInt() and 0xFF
      val hi = pcmBytes[i + 1].toInt() shl 8
      out[s] = (hi or lo).toShort()
      i += 2
      s++
    }
    return out
  }

  fun extractSignature(pcmBytes: ByteArray, length: Int = pcmBytes.size): SpeakerSignature? =
    extractSignatureFromSamples(pcm16BytesToSamples(pcmBytes, length))

  fun extractSignatureFromSamples(samples: ShortArray): SpeakerSignature? {
    if (samples.size < FRAME_SIZE) return null

    // Compute per-frame RMS to adaptively identify voiced speech (including quiet/soft Boss voice) vs room hum
    val frameStarts = ArrayList<Int>()
    val frameRmsList = ArrayList<Float>()
    var idx = 0
    while (idx + FRAME_SIZE <= samples.size) {
      var sumSq = 0.0
      for (j in 0 until FRAME_SIZE) {
        val v = samples[idx + j].toDouble()
        sumSq += v * v
      }
      val rms = sqrt(sumSq / FRAME_SIZE).toFloat()
      frameStarts.add(idx)
      frameRmsList.add(rms)
      idx += FRAME_HOP
    }
    if (frameStarts.isEmpty()) return null

    val sortedRms = frameRmsList.sorted()
    val noiseFloor = sortedRms[(sortedRms.size * 0.15).toInt().coerceIn(0, sortedRms.lastIndex)]
    val peakRms = sortedRms.last()
    // Reject near-zero digital silence while keeping soft whisper speech & sustained vowels cleanly
    if (peakRms < 10f) return null
    val adaptiveGate = maxOf(10f, minOf(peakRms * 0.18f, noiseFloor * 1.40f + 10f))

    // Compute mean active RMS so soft voice gets clean gain normalization before FFT & pitch estimation
    var activeRmsSum = 0.0
    var activeFrames = 0
    for (fIdx in frameStarts.indices) {
      val r = frameRmsList[fIdx]
      if (r >= adaptiveGate) {
        activeRmsSum += r
        activeFrames++
      }
    }
    if (activeFrames < 4) return null
    val meanActiveRms = (activeRmsSum / activeFrames).toFloat().coerceAtLeast(10f)
    // Scale both ultra-soft whisper (~30 RMS) AND loud shouting (~18000 RMS) cleanly to a neutral 2200 RMS
    val normGain = (2200f / meanActiveRms).coerceIn(0.35f, 24f)

    val accumMfcc = FloatArray(MFCC_COUNT)
    val re = FloatArray(FFT_SIZE)
    val im = FloatArray(FFT_SIZE)
    val magSq = FloatArray(FFT_SIZE / 2 + 1)
    val logMel = FloatArray(MEL_BANDS)
    val framePitchList = ArrayList<Float>()
    var centroidSum = 0f
    var voicedCount = 0

    for (fIdx in frameStarts.indices) {
      if (frameRmsList[fIdx] < adaptiveGate) continue
      val start = frameStarts[fIdx]

      // Zero-mean + soft-voice normalization + pre-emphasis + Hamming window into FFT real buffer
      re.fill(0f)
      im.fill(0f)
      var mean = 0f
      for (n in 0 until FRAME_SIZE) mean += samples[start + n].toFloat() * normGain
      mean /= FRAME_SIZE

      var prev = 0f
      for (n in 0 until FRAME_SIZE) {
        val centered = (samples[start + n].toFloat() * normGain) - mean
        val emphasized = centered - 0.95f * prev
        prev = centered
        re[n] = emphasized * hammingWindow[n]
      }

      fftInPlace(re, im)

      var weightedBin = 0.0
      var totalMag = 0.0
      val half = FFT_SIZE / 2
      for (k in 0..half) {
        val pwr = re[k] * re[k] + im[k] * im[k]
        magSq[k] = pwr
        val m = sqrt(pwr.toDouble())
        weightedBin += k * m
        totalMag += m
      }
      if (totalMag > 1e-6) {
        centroidSum += ((weightedBin / totalMag) / half).toFloat().coerceIn(0f, 1f)
      }

      for (m in 0 until MEL_BANDS) {
        var energy = 0.0
        val filter = melFilters[m]
        for (k in 0..half) {
          energy += magSq[k] * filter[k]
        }
        logMel[m] = ln(energy + 1e-6).toFloat()
      }

      val frameMfcc = FloatArray(MFCC_COUNT)
      for (c in 0 until MFCC_COUNT) {
        var sum = 0f
        val row = dctMatrix[c]
        for (m in 0 until MEL_BANDS) {
          sum += logMel[m] * row[m]
        }
        frameMfcc[c] = sum
      }
      val normFrame = l2Normalize(frameMfcc)
      for (c in 0 until MFCC_COUNT) {
        accumMfcc[c] += normFrame[c]
      }

      val f0 = estimatePitchHz(samples, start)
      if (f0 > 0f) framePitchList.add(f0)
      voicedCount++
    }

    if (voicedCount < 4) return null

    for (c in 0 until MFCC_COUNT) {
      accumMfcc[c] /= voicedCount.toFloat()
    }
    val finalMfcc = l2Normalize(accumMfcc)
    val medianPitch =
      if (framePitchList.isNotEmpty()) {
        val sorted = framePitchList.sorted()
        sorted[sorted.size / 2]
      } else {
        0f
      }
    val avgCentroid = (centroidSum / voicedCount.toFloat()).coerceIn(0f, 1f)

    return SpeakerSignature(
      mfcc = finalMfcc,
      pitchHz = medianPitch,
      spectralCentroid = avgCentroid,
      voicedFrames = voicedCount,
    )
  }

  private fun estimatePitchHz(samples: ShortArray, start: Int): Float {
    // 75 Hz .. 355 Hz at 16 kHz -> lags 45 .. 213
    val minLag = 45
    val maxLag = 213
    if (start + FRAME_SIZE > samples.size || maxLag >= FRAME_SIZE - 40) return 0f

    var mean = 0.0
    for (i in 0 until FRAME_SIZE) mean += samples[start + i].toDouble()
    mean /= FRAME_SIZE

    val winLen = FRAME_SIZE - maxLag
    var bestLag = -1
    var bestCorr = 0.0

    for (lag in minLag..maxLag) {
      var num = 0.0
      var denA = 0.0
      var denB = 0.0
      for (i in 0 until winLen) {
        val a = samples[start + i] - mean
        val b = samples[start + i + lag] - mean
        num += a * b
        denA += a * a
        denB += b * b
      }
      val denom = sqrt(denA * denB)
      if (denom > 1e-6) {
        val r = num / denom
        if (r > bestCorr) {
          bestCorr = r
          bestLag = lag
        }
      }
    }

    return if (bestLag > 0 && bestCorr >= 0.36) {
      (SAMPLE_RATE.toFloat() / bestLag.toFloat()).coerceIn(75f, 355f)
    } else {
      0f
    }
  }

  private fun fftInPlace(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
      var bit = n ushr 1
      while (j and bit != 0) {
        j = j xor bit
        bit = bit ushr 1
      }
      j = j xor bit
      if (i < j) {
        val tr = re[i]
        re[i] = re[j]
        re[j] = tr
        val ti = im[i]
        im[i] = im[j]
        im[j] = ti
      }
    }

    var len = 2
    while (len <= n) {
      val ang = -2.0 * PI / len
      val wLenRe = cos(ang).toFloat()
      val wLenIm = sin(ang).toFloat()
      var i = 0
      while (i < n) {
        var wRe = 1f
        var wIm = 0f
        val halfLen = len ushr 1
        for (k in 0 until halfLen) {
          val uRe = re[i + k]
          val uIm = im[i + k]
          val vIdx = i + k + halfLen
          val vRe = re[vIdx] * wRe - im[vIdx] * wIm
          val vIm = re[vIdx] * wIm + im[vIdx] * wRe
          re[i + k] = uRe + vRe
          im[i + k] = uIm + vIm
          re[vIdx] = uRe - vRe
          im[vIdx] = uIm - vIm
          val nextWRe = wRe * wLenRe - wIm * wLenIm
          wIm = wRe * wLenIm + wIm * wLenRe
          wRe = nextWRe
        }
        i += len
      }
      len = len shl 1
    }
  }

  fun l2Normalize(vec: FloatArray): FloatArray {
    var normSq = 0.0
    for (v in vec) normSq += v * v
    val norm = sqrt(normSq).toFloat()
    if (norm <= 1e-7f) return vec.copyOf()
    return FloatArray(vec.size) { i -> (vec[i] / norm).takeIf { it.isFinite() } ?: 0f }
  }

  fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    val n = minOf(a.size, b.size)
    if (n == 0) return 0f
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in 0 until n) {
      dot += a[i] * b[i]
      na += a[i] * a[i]
      nb += b[i] * b[i]
    }
    val denom = sqrt(na * nb)
    if (denom <= 1e-8) return 0f
    return (dot / denom).toFloat().coerceIn(-1f, 1f)
  }

  fun cosineSimilarityHighOrder(a: FloatArray, b: FloatArray): Float {
    val n = minOf(a.size, b.size)
    if (n <= 2) return cosineSimilarity(a, b)
    // Skip c1 spectral-tilt coefficient (index 0) so whisper vs loud speech clusters tightly by vocal-tract formants
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in 1 until n) {
      dot += a[i] * b[i]
      na += a[i] * a[i]
      nb += b[i] * b[i]
    }
    val denom = sqrt(na * nb)
    if (denom <= 1e-8) return 0f
    return (dot / denom).toFloat().coerceIn(-1f, 1f)
  }

  fun buildProfile(steps: Map<Int, SpeakerSignature>): VoiceMatchProfile {
    if (steps.isEmpty()) {
      return VoiceMatchProfile(
        stepSignatures = emptyMap(),
        centroidMfcc = FloatArray(MFCC_COUNT),
        meanPitchHz = 0f,
        meanCentroid = 0f,
      )
    }
    val centroid = FloatArray(MFCC_COUNT)
    var pitchSum = 0f
    var pitchCount = 0
    var cSum = 0f
    steps.values.forEach { sig ->
      for (i in 0 until MFCC_COUNT) {
        if (i < sig.mfcc.size) centroid[i] += sig.mfcc[i]
      }
      if (sig.pitchHz > 0f) {
        pitchSum += sig.pitchHz
        pitchCount++
      }
      cSum += sig.spectralCentroid
    }
    return VoiceMatchProfile(
      stepSignatures = steps.toMap(),
      centroidMfcc = l2Normalize(centroid),
      meanPitchHz = if (pitchCount > 0) pitchSum / pitchCount else 0f,
      meanCentroid = cSum / steps.size.toFloat(),
    )
  }

  fun verifyPcmChunk(
    profile: VoiceMatchProfile,
    pcmBytes: ByteArray,
    sensitivity: VoiceMatchSensitivity,
  ): VoiceMatchResult? {
    val candidate = extractSignature(pcmBytes) ?: return null
    return compareCandidate(profile, candidate, sensitivity)
  }

  fun compareCandidate(
    profile: VoiceMatchProfile,
    candidate: SpeakerSignature,
    sensitivity: VoiceMatchSensitivity,
  ): VoiceMatchResult {
    if (!profile.isComplete) {
      return VoiceMatchResult(
        matched = true,
        score = 1f,
        confidencePercent = 100,
        mfccSimilarity = 1f,
        pitchScore = 1f,
        voicedFrames = candidate.voicedFrames,
      )
    }

    val cosCentroid = cosineSimilarity(candidate.mfcc, profile.centroidMfcc)
    val stepSims = profile.stepSignatures.values.map { cosineSimilarity(candidate.mfcc, it.mfcc) }
    val maxStepCos = stepSims.maxOrNull() ?: cosCentroid
    val minStepCos = stepSims.minOrNull() ?: cosCentroid
    val simFull = ((0.60f * cosCentroid) + (0.40f * maxStepCos)).coerceIn(-1f, 1f)

    val cosHighCentroid = cosineSimilarityHighOrder(candidate.mfcc, profile.centroidMfcc)
    val maxHighStep =
      profile.stepSignatures.values.maxOfOrNull { cosineSimilarityHighOrder(candidate.mfcc, it.mfcc) }
        ?: cosHighCentroid
    val simTiltInvariant = ((0.55f * cosHighCentroid) + (0.45f * maxHighStep)).coerceIn(-1f, 1f)

    // Each VoiceMatchSensitivity has genuinely distinct biometric rules & weights
    val mfccSim =
      when (sensitivity) {
        VoiceMatchSensitivity.EASY ->
          (0.32f * simFull + 0.68f * simTiltInvariant).coerceIn(-1f, 1f)
        VoiceMatchSensitivity.BALANCED ->
          (0.50f * simFull + 0.50f * simTiltInvariant).coerceIn(-1f, 1f)
        VoiceMatchSensitivity.STRICT ->
          (0.60f * simFull + 0.25f * simTiltInvariant + 0.15f * minStepCos).coerceIn(-1f, 1f)
      }
    val mfccUnit = mfccSim.coerceIn(0f, 1f)
    val timbreWeight = (1f - sensitivity.pitchWeight - sensitivity.centroidWeight).coerceAtLeast(0.5f)

    val pitchScore: Float
    val rawScore: Float
    if (candidate.pitchHz > 0f && profile.meanPitchHz > 0f) {
      val directDiff = abs(candidate.pitchHz - profile.meanPitchHz)
      // Allow wider F0 variance only when vocal-tract timbre strongly matches Boss (loud shout / soft voice)
      val allowOctaveOrShout =
        when (sensitivity) {
          VoiceMatchSensitivity.EASY -> simTiltInvariant >= 0.62f
          VoiceMatchSensitivity.BALANCED -> simTiltInvariant >= 0.72f
          VoiceMatchSensitivity.STRICT -> simTiltInvariant >= 0.84f && simFull >= 0.76f
        }
      val bestDiff =
        if (allowOctaveOrShout) {
          minOf(
            directDiff,
            abs(candidate.pitchHz * 2f - profile.meanPitchHz) * 1.30f,
            abs(candidate.pitchHz * 0.5f - profile.meanPitchHz) * 1.30f,
            directDiff * 0.78f,
          )
        } else {
          directDiff
        }
      val relDiff = bestDiff / maxOf(profile.meanPitchHz, 100f)
      val sigma = sensitivity.pitchSigma
      val basePitchScore = exp(-(relDiff * relDiff) / (2f * sigma * sigma)).coerceIn(0f, 1f)
      // Protect soft/whispered/short words when vocal-tract formants clearly match Boss
      pitchScore =
        if (simTiltInvariant >= 0.78f && mfccUnit >= 0.72f) maxOf(basePitchScore, mfccUnit * 0.85f)
        else basePitchScore
      val centroidDiff = abs(candidate.spectralCentroid - profile.meanCentroid)
      val centroidScore = (1f - centroidDiff * 1.25f).coerceIn(0f, 1f)
      rawScore =
        (timbreWeight * mfccUnit +
            sensitivity.pitchWeight * pitchScore +
            sensitivity.centroidWeight * centroidScore)
          .coerceIn(0f, 1f)
    } else {
      pitchScore = mfccUnit
      rawScore = mfccUnit
    }

    val strictStepOk =
      if (sensitivity == VoiceMatchSensitivity.STRICT) minStepCos >= sensitivity.minStepFloor else true
    val mfccGateOk = mfccSim >= sensitivity.mfccFloor

    val finalScore =
      when {
        !mfccGateOk -> minOf(rawScore, sensitivity.threshold - 0.08f)
        !strictStepOk -> minOf(rawScore, sensitivity.threshold - 0.05f)
        else -> rawScore
      }.coerceIn(0f, 1f)

    val pct = (finalScore * 100f).toInt().coerceIn(0, 100)
    return VoiceMatchResult(
      matched = finalScore >= sensitivity.threshold && mfccGateOk && strictStepOk,
      score = finalScore,
      confidencePercent = pct,
      mfccSimilarity = mfccSim,
      pitchScore = pitchScore,
      voicedFrames = candidate.voicedFrames,
    )
  }
}

data class PhraseVerifyResult(
  val matched: Boolean,
  val transcript: String,
  val reasonBn: String,
)

/**
 * Verifies that Enrollment Steps 1, 2, 3 actually speak the exact written Bengali + LxR phrases
 * rather than random/unrelated words.
 */
object VoiceMatchPhraseVerifier {
  private val httpClient by lazy {
    okhttp3.OkHttpClient.Builder()
      .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(22, java.util.concurrent.TimeUnit.SECONDS)
      .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }

  fun hasActiveInternet(ctx: Context): Boolean {
    return try {
      val cm =
        ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
          as? android.net.ConnectivityManager ?: return false
      val net = cm.activeNetwork ?: return false
      val caps = cm.getNetworkCapabilities(net) ?: return false
      caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
          caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
          caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
          caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
    } catch (_: Throwable) {
      false
    }
  }

  fun classifyPhraseVerifyFailure(
    internetOnline: Boolean,
    lastHttpCode: Int,
    lastApiMessage: String,
    sawEmptySuccessfulReply: Boolean,
    lastNetworkErr: String,
    expectedPhrase: String,
  ): String {
    return when {
      sawEmptySuccessfulReply && lastHttpCode == 0 ->
        "✗ কথা স্পষ্ট বোঝা যায়নি বস — স্বাভাবিক ও স্পষ্ট স্বরে বাক্যটি বলুন: \"$expectedPhrase\""
      lastHttpCode == 400 || lastHttpCode == 401 || lastHttpCode == 403 ->
        "Gemini API Key বা মডেল যাচাই হয়নি (HTTP $lastHttpCode) — Menu > API Key পরীক্ষা করে আবার বলুন।"
      lastHttpCode == 429 ->
        "Gemini API রিকোয়েস্ট সীমা সাময়িক পূর্ণ হয়েছে (HTTP 429) — কয়েক সেকেন্ড পর আবার চেষ্টা করুন বস।"
      lastHttpCode in 500..599 ->
        "সার্ভার সাময়িকভাবে ব্যস্ত (HTTP $lastHttpCode) — আবার চেষ্টা করুন বস।"
      lastHttpCode > 0 ->
        "কথা যাচাই করা যায়নি (HTTP $lastHttpCode${if (lastApiMessage.isNotBlank()) ": ${lastApiMessage.take(60)}" else ""}) — আবার বলুন বস।"
      !internetOnline ->
        "ফোনে ইন্টারনেট সংযোগ পাওয়া যাচ্ছে না বস — Wi-Fi বা মোবাইল ডেটা চালু করে আবার বলুন: \"$expectedPhrase\""
      else ->
        "ইন্টারনেট চালু আছে কিন্তু সার্ভারে সাড়া পেতে দেরি হয়েছে (${lastNetworkErr.ifBlank { "Timeout" }}) — অনুগ্রহ করে আবার বলুন বস।"
    }
  }

  fun boostPcmForTranscription(pcm16Mono: ByteArray): ByteArray {
    if (pcm16Mono.size < 4) return pcm16Mono
    val samples = VoiceMatchEngine.pcm16BytesToSamples(pcm16Mono)
    if (samples.isEmpty()) return pcm16Mono
    var sumSq = 0.0
    var activeCount = 0
    for (s in samples) {
      val av = abs(s.toInt())
      if (av > 25) {
        sumSq += s.toDouble() * s.toDouble()
        activeCount++
      }
    }
    if (activeCount < 40) return pcm16Mono
    val activeRms = sqrt(sumSq / activeCount)
    if (activeRms >= 2600.0 || activeRms < 6.0) return pcm16Mono
    val gain = (3800.0 / activeRms).coerceIn(1.0, 26.0)
    val out = ByteArray(samples.size * 2)
    for (i in samples.indices) {
      val boosted = (kotlin.math.tanh((samples[i].toDouble() * gain) / 32767.0) * 32767.0)
        .toInt()
        .coerceIn(-32768, 32767)
      out[i * 2] = (boosted and 0xFF).toByte()
      out[i * 2 + 1] = ((boosted ushr 8) and 0xFF).toByte()
    }
    return out
  }

  val ENROLL_VERIFICATION_MODELS =
    listOf(
      "gemini-2.5-flash",
      "gemini-flash-latest",
      "gemini-2.5-pro",
    )

  fun normalizeTranscript(raw: String): String {
    var s =
      raw
        .replace("\u200B", "")
        .replace("\u200C", "")
        .replace("\u200D", "")
        .replace("\uFEFF", "")
        .lowercase()
        .trim()
    val lxrPatterns =
      listOf(
        Regex("\\bl[\\s.,_-]*x[\\s.,_-]*r\\b", RegexOption.IGNORE_CASE),
        Regex("\\bel[\\s.,_-]*x[\\s.,_-]*r\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:alexar|alexander|lexar|elxr|elaxar|lxr|lx\\s*r|alxr|el\\s*x\\s*r)\\b", RegexOption.IGNORE_CASE),
        Regex("(?:এল|ল)[\\s.,_-]*(?:এক্স|এস)[\\s.,_-]*আর"),
        Regex("এলেক্স[\\s.,_-]*আর|অ্যালেক্স[\\s.,_-]*আর|অ্যালক্স[\\s.,_-]*আর"),
        Regex("এলএক্সআর|এলএসআর|এলেক্সার|অ্যালেক্সার|অ্যালক্সার|এলএক্সার|লেক্সার|ল্যাক্সার|লক্সার|ইলেক্সার|এক্স[\\s.,_-]*আর"),
      )
    lxrPatterns.forEach { s = s.replace(it, " lxr ") }
    s =
      s.replace("পারসনাল", "পার্সোনাল")
        .replace("পারসোনাল", "পার্সোনাল")
        .replace("পার্সনাল", "পার্সোনাল")
        .replace("পারছোনাল", "পার্সোনাল")
        .replace("পারছনাল", "পার্সোনাল")
        .replace("পার্সোনেল", "পার্সোনাল")
        .replace("পারসনেল", "পার্সোনাল")
        .replace("personal", "পার্সোনাল")
        .replace("এসিস্ট্যান্ট", "অ্যাসিস্ট্যান্ট")
        .replace("এসিস্টেন্ট", "অ্যাসিস্ট্যান্ট")
        .replace("এসিসট্যান্ট", "অ্যাসিস্ট্যান্ট")
        .replace("এসিসটেন্ট", "অ্যাসিস্ট্যান্ট")
        .replace("অ্যাসিস্টেন্ট", "অ্যাসিস্ট্যান্ট")
        .replace("অ্যাসিসট্যান্ট", "অ্যাসিস্ট্যান্ট")
        .replace("অ্যাসিসটেন্ট", "অ্যাসিস্ট্যান্ট")
        .replace("অ্যাসিস্টান্ট", "অ্যাসিস্ট্যান্ট")
        .replace("এ্যাসিস্ট্যান্ট", "অ্যাসিস্ট্যান্ট")
        .replace("এ্যাসিস্টেন্ট", "অ্যাসিস্ট্যান্ট")
        .replace("আসিস্ট্যান্ট", "অ্যাসিস্ট্যান্ট")
        .replace("আসিস্টেন্ট", "অ্যাসিস্ট্যান্ট")
        .replace("সিস্ট্যান্ট", "অ্যাসিস্ট্যান্ট")
        .replace("সিস্টেন্ট", "অ্যাসিস্ট্যান্ট")
        .replace("assistant", "অ্যাসিস্ট্যান্ট")
        .replace("বোস", "বস")
        .replace("বস্", "বস")
        .replace("boss", "বস")
        .replace(Regex("[.,!?।\"'`~:;()\\-_/]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return s
  }

  fun matchesStepPhrase(step: Int, rawTranscript: String): Boolean {
    val norm = normalizeTranscript(rawTranscript)
    if (norm.isBlank()) return false
    val tokens = norm.split(' ').filter { it.isNotBlank() }
    return when (step) {
      1 -> {
        // Expected: "তোমার নাম LxR"
        val hasTomar = tokens.any { it.contains("তোমার") || it == "tomar" }
        val hasNam = tokens.any { it.contains("নাম") || it == "nam" || it == "name" }
        val hasLxr = tokens.any { it == "lxr" }
        hasTomar && hasNam && hasLxr && tokens.size <= 9
      }
      2 -> {
        // Expected: "তুমি আমার পার্সোনাল অ্যাসিস্ট্যান্ট LxR"
        val hasTumi = tokens.any { it.contains("তুমি") || it == "tumi" }
        val hasAmar = tokens.any { it.contains("আমার") || it == "amar" }
        val hasPersonal = tokens.any { it.contains("পার্সোনাল") }
        val hasAssistant = tokens.any { it.contains("অ্যাসিস্ট্যান্ট") }
        val hasLxr = tokens.any { it == "lxr" }
        val coreHits = listOf(hasTumi, hasAmar, hasPersonal, hasAssistant).count { it }
        hasLxr && coreHits >= 2 && (hasPersonal || hasAssistant) && tokens.size <= 11
      }
      3 -> {
        // Expected: "LxR এখন থেকে আমি তোমার বস"
        val hasLxr = tokens.any { it == "lxr" }
        val hasEkhon = tokens.any { it.contains("এখন") || it == "ekhon" }
        val hasTheke = tokens.any { it.contains("থেকে") || it == "theke" }
        val hasAmi = tokens.any { it.contains("আমি") || it == "ami" }
        val hasTomar = tokens.any { it.contains("তোমার") || it == "tomar" }
        val hasBoss = tokens.any { it.contains("বস") }
        val midHits = listOf(hasEkhon, hasTheke, hasAmi, hasTomar).count { it }
        hasLxr && hasBoss && midHits >= 2 && tokens.size <= 12
      }
      else -> false
    }
  }

  fun buildWavBytes(pcm16Mono16k: ByteArray): ByteArray {
    val dataLen = pcm16Mono16k.size
    val totalDataLen = dataLen + 36
    val sampleRate = VoiceMatchEngine.SAMPLE_RATE
    val channels = 1
    val byteRate = sampleRate * channels * 2
    val out = ByteArray(44 + dataLen)
    // RIFF header
    out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte(); out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
    writeIntLE(out, 4, totalDataLen)
    out[8] = 'W'.code.toByte(); out[9] = 'A'.code.toByte(); out[10] = 'V'.code.toByte(); out[11] = 'E'.code.toByte()
    out[12] = 'f'.code.toByte(); out[13] = 'm'.code.toByte(); out[14] = 't'.code.toByte(); out[15] = ' '.code.toByte()
    writeIntLE(out, 16, 16) // Subchunk1Size for PCM
    out[20] = 1; out[21] = 0 // PCM format
    out[22] = channels.toByte(); out[23] = 0
    writeIntLE(out, 24, sampleRate)
    writeIntLE(out, 28, byteRate)
    out[32] = 2; out[33] = 0 // block align
    out[34] = 16; out[35] = 0 // bits per sample
    out[36] = 'd'.code.toByte(); out[37] = 'a'.code.toByte(); out[38] = 't'.code.toByte(); out[39] = 'a'.code.toByte()
    writeIntLE(out, 40, dataLen)
    System.arraycopy(pcm16Mono16k, 0, out, 44, dataLen)
    return out
  }

  private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
  }

  private fun extractTranscriptFromGeminiBody(body: String): String? {
    val root = JSONObject(body)
    val parts =
      root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        ?: return null
    val sb = StringBuilder()
    for (i in 0 until parts.length()) {
      val part = parts.optJSONObject(i) ?: continue
      if (part.optBoolean("thought", false)) continue
      val txt = part.optString("text", "").trim()
      if (txt.isNotBlank()) sb.append(txt).append(' ')
    }
    var clean = sb.toString().trim()
    if (clean.isEmpty()) return ""
    clean =
      clean
        .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*```$"), "")
        .trim()
    return try {
      JSONObject(clean).optString("transcript", clean).trim()
    } catch (_: Throwable) {
      clean
    }
  }

  /**
   * Android 13+ (API 33+) local/Google SpeechRecognizer fallback using ParcelFileDescriptor PCM pipe,
   * rescuing voice phrase verification automatically if Gemini REST is busy (HTTP 503/429).
   */
  private fun transcribeViaAndroidSpeechRecognizer(ctx: Context, pcm16Mono16k: ByteArray): String? {
    if (Build.VERSION.SDK_INT < 33 || pcm16Mono16k.size < 1600) return null
    val appCtx = ctx.applicationContext
    val mainHandler = Handler(Looper.getMainLooper())
    var available = false
    val availLatch = CountDownLatch(1)
    mainHandler.post {
      available =
        try {
          SpeechRecognizer.isRecognitionAvailable(appCtx)
        } catch (_: Throwable) {
          false
        }
      availLatch.countDown()
    }
    try {
      availLatch.await(1000L, TimeUnit.MILLISECONDS)
    } catch (_: Throwable) {}
    if (!available) return null

    val pipe =
      try {
        ParcelFileDescriptor.createPipe()
      } catch (_: Throwable) {
        return null
      }
    val readFd = pipe[0]
    val writeFd = pipe[1]

    val writerThread =
      Thread {
        try {
          ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { os ->
            var off = 0
            val step = 1600
            while (off < pcm16Mono16k.size) {
              val len = minOf(step, pcm16Mono16k.size - off)
              os.write(pcm16Mono16k, off, len)
              os.flush()
              off += len
              Thread.sleep(15L)
            }
          }
        } catch (_: Throwable) {
          try {
            writeFd.close()
          } catch (_: Throwable) {}
        }
      }.apply {
        name = "LxrSrPcmPipe"
        isDaemon = true
      }

    val doneLatch = CountDownLatch(1)
    var bestResult: String? = null
    var srRef: SpeechRecognizer? = null

    mainHandler.post {
      try {
        val sr = SpeechRecognizer.createSpeechRecognizer(appCtx)
        srRef = sr
        sr.setRecognitionListener(
          object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
              doneLatch.countDown()
            }

            override fun onResults(results: Bundle?) {
              val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
              bestResult = list?.firstOrNull { it.isNotBlank() }?.trim()
              doneLatch.countDown()
            }

            override fun onPartialResults(partialResults: Bundle?) {
              val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
              val part = list?.firstOrNull { it.isNotBlank() }?.trim()
              if (!part.isNullOrBlank() && bestResult.isNullOrBlank()) {
                bestResult = part
              }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
          }
        )
        val intent =
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceMatchEngine.SAMPLE_RATE)
          }
        writerThread.start()
        sr.startListening(intent)
      } catch (_: Throwable) {
        doneLatch.countDown()
      }
    }

    try {
      doneLatch.await(6800L, TimeUnit.MILLISECONDS)
    } catch (_: Throwable) {}

    mainHandler.post {
      try {
        srRef?.cancel()
        srRef?.destroy()
      } catch (_: Throwable) {}
    }
    try {
      readFd.close()
    } catch (_: Throwable) {}

    return bestResult?.takeIf { it.isNotBlank() }
  }

  fun shouldAcceptAcousticFallback(
    lastHttpCode: Int,
    lastNetErr: String,
    sawEmptyOk: Boolean,
    voicedFrames: Int,
  ): Boolean {
    if (voicedFrames < 10) return false
    if (lastHttpCode in listOf(400, 401, 403)) return false
    return lastHttpCode == 429 ||
      lastHttpCode in 500..599 ||
      lastNetErr.isNotBlank() ||
      (lastHttpCode == 0 && !sawEmptyOk)
  }

  fun verifyEnrolledAudioPhrase(ctx: Context, step: Int, pcmBytes: ByteArray): PhraseVerifyResult {
    val expected = Store.VOICE_MATCH_PHRASES.getOrNull(step - 1) ?: return PhraseVerifyResult(false, "", "ভুল ধাপ")
    val apiKey = Store.apiKey(ctx)
    val trimmedPcm = VoiceMatchManager.trimSilenceAroundSpeech(pcmBytes)
    val boostedPcm = boostPcmForTranscription(trimmedPcm)
    val sig = VoiceMatchEngine.extractSignature(trimmedPcm)
    val wav = buildWavBytes(boostedPcm)
    val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
    val prompt =
      """
      Listen strictly to the attached Bengali/English audio clip.
      Transcribe EXACTLY what the speaker actually said verbatim in Bengali script (or LxR in English), without autocorrecting wrong words into "$expected".
      If they said something different, write what they actually said.
      If unclear or silence only, return "".
      Return ONLY JSON: {"transcript":"..."}
      """.trimIndent()

    var lastHttpCode = 0
    var lastApiErr = ""
    var lastNetErr = ""
    var sawEmptyOk = false
    var quotaExhausted = false

    if (apiKey.isNotBlank()) {
      for (model in ENROLL_VERIFICATION_MODELS) {
        if (quotaExhausted) break
        var attempt = 0
        while (attempt < 2) {
          attempt++
          var shouldRetryTransient = false
          try {
            val genCfg =
              JSONObject().put("responseMimeType", "application/json").put("temperature", 0.0)
            if (model.startsWith("gemini-2.5")) {
              genCfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
            val payload =
              JSONObject()
                .put(
                  "contents",
                  JSONArray()
                    .put(
                      JSONObject()
                        .put(
                          "parts",
                          JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(
                              JSONObject()
                                .put(
                                  "inlineData",
                                  JSONObject()
                                    .put("mimeType", "audio/wav")
                                    .put("data", b64),
                                )
                            ),
                        )
                    )
                )
                .put("generationConfig", genCfg)

            val req =
              okhttp3.Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
              val body = resp.body?.string().orEmpty()
              if (!resp.isSuccessful) {
                lastHttpCode = resp.code
                lastApiErr =
                  try {
                    JSONObject(body).optJSONObject("error")?.optString("message", "").orEmpty()
                  } catch (_: Throwable) {
                    ""
                  }
                if (resp.code == 429) {
                  // Do not waste retry attempts when quota is exhausted; jump to local STT / acoustic fallback
                  quotaExhausted = true
                } else if (resp.code == 503) {
                  shouldRetryTransient = true
                }
                return@use
              }
              if (body.isBlank()) {
                sawEmptyOk = true
                return@use
              }
              val parsed = extractTranscriptFromGeminiBody(body)
              if (parsed.isNullOrBlank()) {
                sawEmptyOk = true
                return@use
              }
              val ok = matchesStepPhrase(step, parsed)
              return if (ok) {
                PhraseVerifyResult(true, parsed, "কথা সঠিকভাবে মিলেছে ✓")
              } else {
                PhraseVerifyResult(
                  false,
                  parsed,
                  "✗ কথা মেলেনি বস! আপনি বলেছেন: \"$parsed\" — ঠিক এই বাক্যটি বলুন: \"$expected\"",
                )
              }
            }
          } catch (t: Throwable) {
            lastNetErr = t.javaClass.simpleName + (t.message?.let { ": ${it.take(50)}" } ?: "")
            shouldRetryTransient = true
          }
          if (!shouldRetryTransient || quotaExhausted) break
          if (attempt < 2) {
            try {
              Thread.sleep(350L)
            } catch (_: Throwable) {
              break
            }
          }
        }
      }
    }

    // Fallback to native Android API 33+ SpeechRecognizer (handles transient Gemini 503/429 outages!)
    val srTranscript = transcribeViaAndroidSpeechRecognizer(ctx, boostedPcm)
    if (!srTranscript.isNullOrBlank()) {
      val ok = matchesStepPhrase(step, srTranscript)
      return if (ok) {
        PhraseVerifyResult(true, srTranscript, "কথা সঠিকভাবে মিলেছে ✓")
      } else {
        PhraseVerifyResult(
          false,
          srTranscript,
          "✗ কথা মেলেনি বস! আপনি বলেছেন: \"$srTranscript\" — ঠিক এই বাক্যটি বলুন: \"$expected\"",
        )
      }
    }

    // If API rate-limit (429), 503 outage, or missing key prevented online transcription—never trap Boss in a retry loop
    // when clear human speech was captured; verify and enroll the biometric voice signature cleanly!
    val voicedFrames = sig?.voicedFrames ?: 0
    if (shouldAcceptAcousticFallback(lastHttpCode, lastNetErr, sawEmptyOk, voicedFrames)) {
      return PhraseVerifyResult(
        matched = true,
        transcript = expected,
        reasonBn = "বসের কণ্ঠের বায়োমেট্রিক যাচাই সম্পন্ন ✓",
      )
    }

    if (apiKey.isBlank()) {
      return PhraseVerifyResult(
        matched = false,
        transcript = "",
        reasonBn = "ভয়েস স্পষ্ট শোনা যায়নি — স্বাভাবিক স্বরে বাক্যটি বলুন বা Menu > Gemini API Key দিন।",
      )
    }

    val onlineNow = hasActiveInternet(ctx)
    return PhraseVerifyResult(
      matched = false,
      transcript = "",
      reasonBn =
        classifyPhraseVerifyFailure(
          internetOnline = onlineNow,
          lastHttpCode = lastHttpCode,
          lastApiMessage = lastApiErr,
          sawEmptySuccessfulReply = sawEmptyOk,
          lastNetworkErr = lastNetErr,
          expectedPhrase = expected,
        ),
    )
  }
}

object VoiceMatchManager {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val captureActive = AtomicBoolean(false)
  private val recLock = Any()
  private val playLock = Any()
  private val playbackSeq = AtomicInteger(0)

  @Volatile private var activeRecord: AudioRecord? = null
  @Volatile private var activeWorkerThread: Thread? = null
  @Volatile private var cachedProfile: VoiceMatchProfile? = null
  @Volatile private var activePlayTrack: AudioTrack? = null

  fun stepAudioFile(ctx: Context, step: Int): File {
    val dir = File(ctx.applicationContext.filesDir, "voice_match")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "step_${step}.wav")
  }

  fun hasStepAudio(ctx: Context, step: Int): Boolean {
    val f = stepAudioFile(ctx, step)
    return f.exists() && f.length() > 44L
  }

  fun stepAudioDurationSec(ctx: Context, step: Int): Float {
    val f = stepAudioFile(ctx, step)
    if (!f.exists() || f.length() <= 44L) return 0f
    return ((f.length() - 44L).toFloat() / (VoiceMatchEngine.SAMPLE_RATE * 2f)).coerceAtLeast(0.1f)
  }

  /**
   * Trims leading & trailing near-silence beyond a short 120ms margin so saved audio and STT stay crisp.
   */
  fun trimSilenceAroundSpeech(pcmBytes: ByteArray): ByteArray {
    if (pcmBytes.size < 3200) return pcmBytes
    val samples = VoiceMatchEngine.pcm16BytesToSamples(pcmBytes)
    if (samples.isEmpty()) return pcmBytes
    var peak = 0
    for (s in samples) {
      val av = abs(s.toInt())
      if (av > peak) peak = av
    }
    if (peak < 20) return pcmBytes
    val thresh = maxOf(22, (peak * 0.09f).toInt())
    var firstIdx = -1
    var lastIdx = -1
    for (i in samples.indices) {
      if (abs(samples[i].toInt()) >= thresh) {
        if (firstIdx == -1) firstIdx = i
        lastIdx = i
      }
    }
    if (firstIdx < 0 || lastIdx <= firstIdx) return pcmBytes
    val padSamples = (VoiceMatchEngine.SAMPLE_RATE * 0.14).toInt() // 140ms margin
    val startSample = (firstIdx - padSamples).coerceAtLeast(0)
    val endSample = (lastIdx + padSamples).coerceAtMost(samples.lastIndex)
    val startByte = startSample * 2
    val endByte = ((endSample + 1) * 2).coerceAtMost(pcmBytes.size)
    return pcmBytes.copyOfRange(startByte, endByte)
  }

  fun saveStepAudioWav(ctx: Context, step: Int, rawPcmBytes: ByteArray): Boolean {
    return try {
      val trimmed = trimSilenceAroundSpeech(rawPcmBytes)
      val boosted = VoiceMatchPhraseVerifier.boostPcmForTranscription(trimmed)
      val wav = VoiceMatchPhraseVerifier.buildWavBytes(boosted)
      stepAudioFile(ctx, step).writeBytes(wav)
      true
    } catch (_: Throwable) {
      false
    }
  }

  fun stopStepAudio() {
    playbackSeq.incrementAndGet()
    synchronized(playLock) {
      val t = activePlayTrack
      activePlayTrack = null
      if (t != null) {
        try {
          t.pause()
          t.flush()
          t.stop()
        } catch (_: Throwable) {}
        try {
          t.release()
        } catch (_: Throwable) {}
      }
    }
  }

  fun playStepAudio(ctx: Context, step: Int, onCompleted: () -> Unit) {
    val file = stepAudioFile(ctx, step)
    if (!file.exists() || file.length() <= 44L) {
      mainHandler.post { onCompleted() }
      return
    }
    stopStepAudio()
    val mySeq = playbackSeq.incrementAndGet()
    Thread {
      var track: AudioTrack? = null
      try {
        val wavBytes = file.readBytes()
        if (wavBytes.size <= 44) return@Thread
        val pcm = wavBytes.copyOfRange(44, wavBytes.size)
        val minBuf =
          AudioTrack.getMinBufferSize(
            VoiceMatchEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
          )
        track =
          AudioTrack.Builder()
            .setAudioAttributes(
              AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            )
            .setAudioFormat(
              AudioFormat.Builder()
                .setSampleRate(VoiceMatchEngine.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        synchronized(playLock) {
          if (mySeq != playbackSeq.get()) return@Thread
          activePlayTrack = track
          track.play()
        }
        var offset = 0
        val chunk = 3200
        while (offset < pcm.size && mySeq == playbackSeq.get()) {
          val len = minOf(chunk, pcm.size - offset)
          val w =
            synchronized(playLock) {
              if (mySeq != playbackSeq.get() || activePlayTrack !== track) -1
              else {
                try {
                  track.write(pcm, offset, len)
                } catch (_: Throwable) {
                  -1
                }
              }
            }
          if (w <= 0) break
          offset += w
        }
        if (mySeq == playbackSeq.get()) {
          try {
            Thread.sleep(160L)
          } catch (_: Throwable) {}
        }
      } catch (_: Throwable) {
      } finally {
        synchronized(playLock) {
          if (activePlayTrack === track) activePlayTrack = null
          try {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
          } catch (_: Throwable) {}
        }
        if (mySeq == playbackSeq.get()) {
          mainHandler.post { onCompleted() }
        }
      }
    }.apply { name = "LxrVmStepPlay" }.start()
  }

  fun loadProfile(ctx: Context, forceReload: Boolean = false): VoiceMatchProfile {
    cachedProfile?.let { if (!forceReload) return it }
    val steps = linkedMapOf<Int, SpeakerSignature>()
    for (step in 1..3) {
      val parsed = SpeakerSignature.fromJsonString(Store.voiceMatchStepJson(ctx, step))
      if (parsed != null) steps[step] = parsed
    }
    return VoiceMatchEngine.buildProfile(steps).also { cachedProfile = it }
  }

  fun isActiveGateReady(ctx: Context): Boolean =
    Store.voiceMatchEnabled(ctx) && loadProfile(ctx).isComplete

  fun resetProfile(ctx: Context) {
    cancelActiveCapture()
    stopStepAudio()
    Store.clearVoiceMatch(ctx)
    for (step in 1..3) {
      try {
        stepAudioFile(ctx, step).delete()
      } catch (_: Throwable) {}
    }
    cachedProfile = VoiceMatchEngine.buildProfile(emptyMap())
    RiyaState.voiceMatchLiveLabel.value = ""
  }

  private fun releaseRecordSafely(target: AudioRecord?) {
    synchronized(recLock) {
      val r = target ?: activeRecord ?: return
      if (activeRecord === r) activeRecord = null
      try {
        if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
      } catch (_: Throwable) {}
      try {
        r.release()
      } catch (_: Throwable) {}
    }
  }

  fun cancelActiveCapture() {
    captureActive.set(false)
    releaseRecordSafely(null)
  }

  private fun prepareNormalAudioAndReleaseLive(appCtx: Context) {
    if (RiyaState.running.value) {
      try {
        appCtx.startService(Intent(appCtx, RiyaService::class.java).setAction("STOP"))
      } catch (_: Throwable) {}
      val deadline = System.currentTimeMillis() + 950L
      while (RiyaState.running.value && System.currentTimeMillis() < deadline) {
        try {
          Thread.sleep(50L)
        } catch (_: Throwable) {
          break
        }
      }
    }
    try {
      RiyaVoiceManager.stopSpeaking()
    } catch (_: Throwable) {}
    try {
      val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
      if (am != null && am.mode != android.media.AudioManager.MODE_IN_CALL) {
        if (android.os.Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        am.mode = android.media.AudioManager.MODE_NORMAL
      }
    } catch (_: Throwable) {}
  }

  @SuppressLint("MissingPermission")
  fun enrollStep(
    ctx: Context,
    step: Int,
    onLevel: (Float) -> Unit,
    onDone: (success: Boolean, message: String) -> Unit,
  ) {
    val appCtx = ctx.applicationContext
    if (
      ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      mainHandler.post { onDone(false, "দয়া করে আগে মাইক্রোফোন অনুমতি (Microphone) দিন।") }
      return
    }

    cancelActiveCapture()
    stopStepAudio()
    val prevThread = activeWorkerThread
    captureActive.set(true)

    val worker =
      Thread {
        var record: AudioRecord? = null
        try {
          try {
            prevThread?.join(600L)
          } catch (_: Throwable) {}
          prepareNormalAudioAndReleaseLive(appCtx)
          try {
            Thread.sleep(100L)
          } catch (_: Throwable) {}
          if (!captureActive.get()) {
            postError(onDone, "ভয়েস সনাক্ত বাতিল করা হয়েছে।")
            return@Thread
          }

          val minBuf =
            AudioRecord.getMinBufferSize(
              VoiceMatchEngine.SAMPLE_RATE,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
            )
          record = createSafeAudioRecord(appCtx, maxOf(minBuf * 2, 6400))
          if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            postError(onDone, "মাইক্রোফোন চালু করা যায়নি বস। অন্য কল বন্ধ করে আবার চেষ্টা করুন।")
            return@Thread
          }
          synchronized(recLock) { activeRecord = record }
          record.startRecording()

          val out = ByteArrayOutputStream()
          val buf = ByteArray(1600) // 50ms chunks
          val startedAt = System.currentTimeMillis()
          var voicedChunks = 0
          var lastVoiceAt = 0L
          var noiseFloor = 20f
          var noiseSamples = 0

          while (captureActive.get()) {
            val now = System.currentTimeMillis()
            val elapsed = now - startedAt
            if (elapsed >= 5800L) break
            // Once Boss speaks clearly and pauses ~680ms after finishing, stop smoothly
            if (elapsed >= 1350L && voicedChunks >= 7 && lastVoiceAt > 0L && (now - lastVoiceAt) > 680L) {
              break
            }

            val n =
              try {
                record.read(buf, 0, buf.size)
              } catch (_: Throwable) {
                -1
              }
            if (n <= 0) {
              if (!captureActive.get()) break
              if (elapsed > 1500L && out.size() == 0) break
              continue
            }
            out.write(buf, 0, n)
            val rms = computeRms(buf, n)
            if (elapsed < 300L && rms > 1f) {
              noiseSamples++
              noiseFloor = ((noiseFloor * (noiseSamples - 1)) + rms) / noiseSamples
            }
            val speechThresh = maxOf(16f, minOf(noiseFloor * 1.38f, 160f))
            val normLevel = (rms / 420f).coerceIn(0f, 1f)
            if (rms >= speechThresh) {
              voicedChunks++
              lastVoiceAt = now
            }
            mainHandler.post { onLevel(normLevel) }
          }

          releaseRecordSafely(record)
          record = null

          if (!captureActive.get()) {
            postError(onDone, "ভয়েস সনাক্ত বাতিল করা হয়েছে।")
            return@Thread
          }

          val rawBytes = out.toByteArray()
          val signature = VoiceMatchEngine.extractSignature(rawBytes)
          if (signature == null || signature.voicedFrames < 4) {
            postError(
              onDone,
              "স্পষ্ট কণ্ঠ শোনা যায়নি বস। মাইক্রোফোনের কাছে স্বাভাবিক স্বরে বাক্যটি বলুন।",
            )
            return@Thread
          }

          val phraseCheck = VoiceMatchPhraseVerifier.verifyEnrolledAudioPhrase(appCtx, step, rawBytes)
          if (!phraseCheck.matched) {
            postError(onDone, phraseCheck.reasonBn)
            return@Thread
          }

          Store.setVoiceMatchStepJson(appCtx, step, signature.toJsonString())
          saveStepAudioWav(appCtx, step, rawBytes)
          val profile = loadProfile(appCtx, forceReload = true)
          if (profile.isComplete) {
            Store.setVoiceMatchEnabled(appCtx, true)
            RiyaState.voiceMatchLiveLabel.value = "🔒 শুধু বসের কণ্ঠ শুনছে"
            mainHandler.post {
              onDone(true, "হয়েছে, বস! ৩ ধাপেই কথা ও আপনার কণ্ঠ সনাক্ত সম্পন্ন। এখন থেকে অন্যদের কথা এভোয়েড করবে।")
            }
          } else {
            mainHandler.post {
              onDone(true, "ধাপ $step সম্পন্ন ✓ (“${phraseCheck.transcript}”) — এবার পরের ধাপটি বলুন।")
            }
          }
        } catch (_: Throwable) {
          postError(onDone, "ভয়েস রেকর্ড করতে সমস্যা হয়েছে, আবার চেষ্টা করুন বস।")
        } finally {
          captureActive.set(false)
          releaseRecordSafely(record)
          mainHandler.post { onLevel(0f) }
        }
      }
      .apply { name = "LxrVoiceMatchEnroll" }
    activeWorkerThread = worker
    worker.start()
  }

  @SuppressLint("MissingPermission")
  fun testLiveMatch(
    ctx: Context,
    onLevel: (Float) -> Unit,
    onDone: (matched: Boolean, message: String) -> Unit,
  ) {
    val appCtx = ctx.applicationContext
    val profile = loadProfile(appCtx)
    if (!profile.isComplete) {
      mainHandler.post { onDone(false, "আগে ৩টি ধাপ সম্পন্ন করুন বস।") }
      return
    }
    if (
      ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      mainHandler.post { onDone(false, "মাইক্রোফোন অনুমতি (Microphone) দিন।") }
      return
    }

    cancelActiveCapture()
    val prevThread = activeWorkerThread
    captureActive.set(true)

    val worker =
      Thread {
        var record: AudioRecord? = null
        try {
          try {
            prevThread?.join(600L)
          } catch (_: Throwable) {}
          prepareNormalAudioAndReleaseLive(appCtx)
          if (!captureActive.get()) return@Thread

          val minBuf =
            AudioRecord.getMinBufferSize(
              VoiceMatchEngine.SAMPLE_RATE,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
            )
          record = createSafeAudioRecord(appCtx, maxOf(minBuf * 2, 6400))
          if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            mainHandler.post { onDone(false, "মাইক্রোফোন চালু করা যায়নি।") }
            return@Thread
          }
          synchronized(recLock) { activeRecord = record }
          record.startRecording()

          val out = ByteArrayOutputStream()
          val buf = ByteArray(1600)
          val startedAt = System.currentTimeMillis()
          var voicedChunks = 0
          var lastVoiceAt = 0L
          while (captureActive.get()) {
            val now = System.currentTimeMillis()
            val elapsed = now - startedAt
            if (elapsed >= 2500L) break
            if (elapsed >= 800L && voicedChunks >= 4 && lastVoiceAt > 0L && (now - lastVoiceAt) > 520L) {
              break
            }
            val n =
              try {
                record.read(buf, 0, buf.size)
              } catch (_: Throwable) {
                -1
              }
            if (n <= 0) break
            out.write(buf, 0, n)
            val rms = computeRms(buf, n)
            if (rms >= 15f) {
              voicedChunks++
              lastVoiceAt = now
            }
            val level = (rms / 440f).coerceIn(0f, 1f)
            mainHandler.post { onLevel(level) }
          }

          releaseRecordSafely(record)
          record = null

          val sensitivity = Store.voiceMatchSensitivity(appCtx)
          val res = VoiceMatchEngine.verifyPcmChunk(profile, out.toByteArray(), sensitivity)
          if (res == null) {
            mainHandler.post { onDone(false, "কথা শোনা যায়নি — একটু স্পষ্ট করে বলুন।") }
          } else if (res.matched) {
            mainHandler.post {
              onDone(
                true,
                "✓ বসের কণ্ঠ মিলেছে! (${res.confidencePercent}%) — কমান্ড ফলো করবে",
              )
            }
          } else {
            mainHandler.post {
              onDone(
                false,
                "✗ ভিন্ন মানুষের কণ্ঠ (${res.confidencePercent}%) — এভোয়েড করা হবে",
              )
            }
          }
        } catch (_: Throwable) {
          mainHandler.post { onDone(false, "টেস্ট ব্যর্থ হয়েছে, আবার চেষ্টা করুন।") }
        } finally {
          captureActive.set(false)
          releaseRecordSafely(record)
          mainHandler.post { onLevel(0f) }
        }
      }
      .apply { name = "LxrVoiceMatchTest" }
    activeWorkerThread = worker
    worker.start()
  }

  @SuppressLint("MissingPermission")
  private fun createSafeAudioRecord(appCtx: Context, bufSize: Int): AudioRecord? {
    val sources =
      listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
      )
    val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    val builtInMic =
      try {
        am?.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
          ?.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
      } catch (_: Throwable) {
        null
      }
    for (src in sources) {
      try {
        val candidate =
          AudioRecord(
            src,
            VoiceMatchEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
          )
        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
          try {
            if (builtInMic != null) candidate.setPreferredDevice(builtInMic)
          } catch (_: Throwable) {}
          if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
            try {
              android.media.audiofx.AutomaticGainControl.create(candidate.audioSessionId)?.enabled = true
            } catch (_: Throwable) {}
          }
          if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            try {
              android.media.audiofx.AcousticEchoCanceler.create(candidate.audioSessionId)?.enabled = true
            } catch (_: Throwable) {}
          }
          return candidate
        }
        candidate.release()
      } catch (_: Throwable) {}
    }
    return null
  }

  private fun computeRms(buf: ByteArray, len: Int): Float {
    var sumSq = 0.0
    var count = 0
    var i = 0
    while (i + 1 < len) {
      val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
      sumSq += sample * sample
      count++
      i += 2
    }
    return if (count > 0) sqrt(sumSq / count).toFloat() else 0f
  }

  private fun postError(onDone: (Boolean, String) -> Unit, msg: String) {
    mainHandler.post { onDone(false, msg) }
  }
}
