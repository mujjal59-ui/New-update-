package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.data.RiyaState
import com.example.data.Store
import com.example.data.VoiceMatchSensitivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
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
    if (peakRms < 14f) return null
    val adaptiveGate = maxOf(14f, minOf(peakRms * 0.20f, noiseFloor * 1.48f + 12f))

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
    val meanActiveRms = (activeRmsSum / activeFrames).toFloat().coerceAtLeast(14f)
    val normGain = (2200f / meanActiveRms).coerceIn(1f, 16f)

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
    val maxStepCos =
      profile.stepSignatures.values.maxOfOrNull { cosineSimilarity(candidate.mfcc, it.mfcc) }
        ?: cosCentroid
    val mfccSim = ((0.60f * cosCentroid) + (0.40f * maxStepCos)).coerceIn(-1f, 1f)
    val mfccUnit = mfccSim.coerceIn(0f, 1f)

    val pitchScore: Float
    val rawScore: Float
    if (candidate.pitchHz > 0f && profile.meanPitchHz > 0f) {
      val directDiff = abs(candidate.pitchHz - profile.meanPitchHz)
      // Only forgive autocorrelation octave jumps when the vocal-tract MFCC timbre already closely matches Boss
      val bestDiff =
        if (mfccUnit >= 0.72f) {
          minOf(
            directDiff,
            abs(candidate.pitchHz * 2f - profile.meanPitchHz) * 1.35f,
            abs(candidate.pitchHz * 0.5f - profile.meanPitchHz) * 1.35f,
          )
        } else {
          directDiff
        }
      val relDiff = bestDiff / maxOf(profile.meanPitchHz, 100f)
      pitchScore = exp(-(relDiff * relDiff) / (2f * 0.28f * 0.28f)).coerceIn(0f, 1f)
      val centroidDiff = abs(candidate.spectralCentroid - profile.meanCentroid)
      val centroidScore = (1f - centroidDiff * 1.25f).coerceIn(0f, 1f)
      rawScore = (0.80f * mfccUnit + 0.12f * pitchScore + 0.08f * centroidScore).coerceIn(0f, 1f)
    } else {
      pitchScore = mfccUnit
      rawScore = mfccUnit
    }

    // Never allow a stranger with unrelated vocal-tract timbre to pass on pitch/centroid alone
    val finalScore = if (mfccSim < 0.45f) minOf(rawScore, 0.42f) else rawScore

    val pct = (finalScore * 100f).toInt().coerceIn(0, 100)
    return VoiceMatchResult(
      matched = finalScore >= sensitivity.threshold,
      score = finalScore,
      confidencePercent = pct,
      mfccSimilarity = mfccSim,
      pitchScore = pitchScore,
      voicedFrames = candidate.voicedFrames,
    )
  }
}

object VoiceMatchManager {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val captureActive = AtomicBoolean(false)
  private val recLock = Any()

  @Volatile private var activeRecord: AudioRecord? = null
  @Volatile private var cachedProfile: VoiceMatchProfile? = null

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
    Store.clearVoiceMatch(ctx)
    cachedProfile = VoiceMatchEngine.buildProfile(emptyMap())
    RiyaState.voiceMatchLiveLabel.value = ""
  }

  fun cancelActiveCapture() {
    captureActive.set(false)
    synchronized(recLock) {
      activeRecord?.let { r ->
        try {
          if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
        } catch (_: Throwable) {}
        try {
          r.release()
        } catch (_: Throwable) {}
      }
      activeRecord = null
    }
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

    // Pause active GeminiLive session cleanly so AudioRecord never conflicts on Android
    if (RiyaState.running.value) {
      try {
        appCtx.startService(Intent(appCtx, RiyaService::class.java).setAction("STOP"))
      } catch (_: Throwable) {}
    }

    cancelActiveCapture()
    captureActive.set(true)

    Thread {
        var record: AudioRecord? = null
        try {
          // Small yield if live service just began releasing AudioRecord
          Thread.sleep(160)
          val minBuf =
            AudioRecord.getMinBufferSize(
              VoiceMatchEngine.SAMPLE_RATE,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
            )
          record = createSafeAudioRecord(maxOf(minBuf * 2, 6400))
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

          while (captureActive.get()) {
            val now = System.currentTimeMillis()
            val elapsed = now - startedAt
            if (elapsed >= 4000L) break
            // Once user finishes speaking clearly (>=1.6s and at least 0.6s after voice paused), auto-finish smoothly
            if (elapsed >= 1700L && voicedChunks >= 12 && lastVoiceAt > 0L && (now - lastVoiceAt) > 650L) {
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
              continue
            }
            out.write(buf, 0, n)
            val rms = computeRms(buf, n)
            val normLevel = (rms / 1600f).coerceIn(0f, 1f)
            if (rms > 32f) {
              voicedChunks++
              lastVoiceAt = now
            }
            mainHandler.post { onLevel(normLevel) }
          }

          if (!captureActive.get()) {
            postError(onDone, "ভয়েস সনাক্ত বাতিল করা হয়েছে।")
            return@Thread
          }

          val rawBytes = out.toByteArray()
          val signature = VoiceMatchEngine.extractSignature(rawBytes)
          if (signature == null || signature.voicedFrames < 6) {
            postError(
              onDone,
              "স্পষ্ট কণ্ঠ শোনা যায়নি বস। স্বাভাবিক স্বরে বাক্যটি বলুন।",
            )
            return@Thread
          }

          Store.setVoiceMatchStepJson(appCtx, step, signature.toJsonString())
          val profile = loadProfile(appCtx, forceReload = true)
          if (profile.isComplete) {
            Store.setVoiceMatchEnabled(appCtx, true)
            RiyaState.voiceMatchLiveLabel.value = "🔒 শুধু বসের কণ্ঠ শুনছে"
            mainHandler.post {
              onDone(true, "হয়েছে, বস! ৩ ধাপেই আপনার কণ্ঠ সনাক্ত সম্পন্ন। এখন থেকে অন্যদের কথা এভোয়েড করবে।")
            }
          } else {
            mainHandler.post {
              onDone(true, "ধাপ $step সম্পন্ন ✓ — এবার পরের ধাপটি বলুন।")
            }
          }
        } catch (_: Throwable) {
          postError(onDone, "ভয়েস রেকর্ড করতে সমস্যা হয়েছে, আবার চেষ্টা করুন বস।")
        } finally {
          captureActive.set(false)
          synchronized(recLock) {
            try {
              if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: Throwable) {}
            try {
              record?.release()
            } catch (_: Throwable) {}
            if (activeRecord === record) activeRecord = null
          }
          mainHandler.post { onLevel(0f) }
        }
      }
      .apply { name = "LxrVoiceMatchEnroll" }
      .start()
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
    if (RiyaState.running.value) {
      try {
        appCtx.startService(Intent(appCtx, RiyaService::class.java).setAction("STOP"))
      } catch (_: Throwable) {}
    }

    cancelActiveCapture()
    captureActive.set(true)

    Thread {
        var record: AudioRecord? = null
        try {
          Thread.sleep(150)
          val minBuf =
            AudioRecord.getMinBufferSize(
              VoiceMatchEngine.SAMPLE_RATE,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
            )
          record = createSafeAudioRecord(maxOf(minBuf * 2, 6400))
          if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            mainHandler.post { onDone(false, "মাইক্রোফোন চালু করা যায়নি।") }
            return@Thread
          }
          synchronized(recLock) { activeRecord = record }
          record.startRecording()

          val out = ByteArrayOutputStream()
          val buf = ByteArray(1600)
          val startedAt = System.currentTimeMillis()
          while (captureActive.get() && System.currentTimeMillis() - startedAt < 2500L) {
            val n =
              try {
                record.read(buf, 0, buf.size)
              } catch (_: Throwable) {
                -1
              }
            if (n <= 0) break
            out.write(buf, 0, n)
            val level = (computeRms(buf, n) / 2200f).coerceIn(0f, 1f)
            mainHandler.post { onLevel(level) }
          }

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
          synchronized(recLock) {
            try {
              if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: Throwable) {}
            try {
              record?.release()
            } catch (_: Throwable) {}
            if (activeRecord === record) activeRecord = null
          }
          mainHandler.post { onLevel(0f) }
        }
      }
      .apply { name = "LxrVoiceMatchTest" }
      .start()
  }

  @SuppressLint("MissingPermission")
  private fun createSafeAudioRecord(bufSize: Int): AudioRecord? {
    val sources =
      listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
      )
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
