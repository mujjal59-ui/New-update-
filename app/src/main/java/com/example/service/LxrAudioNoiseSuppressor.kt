package com.example.service

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time 16 kHz PCM-16 software Noise Cancellation & Speech Enhancement DSP
 * engineered for BOTH Wireless Bluetooth Headset/TWS and Built-in Phone Mic.
 *
 * v2.29.0 Acoustic Upgrades:
 *  1. 1st-order 95 Hz High-Pass Filter -> removes AC/fan rumble & traffic bass.
 *  2. Tonal horn / whistle / steady TV tone rejection -> rejects sustained pure sinusoids & vehicle horns
 *     (abnormal narrow periodicity + low crest factor).
 *  3. Impulsive bark / bird chirp / clap transient rejection -> single isolated high-frequency or extreme-crest spikes
 *     are suppressed without falsely triggering speech turns.
 *  4. Bluetooth Whisper Boost clarity -> soft-spoken Boss whispers on Bluetooth SCO/BLE (`bluetoothMode && whisperBoostOn`)
 *     are detected cleanly down to subtle voice RMS and gently amplified without background static pumping.
 */
class LxrAudioNoiseSuppressor(
  private val sampleRate: Int = 16000,
) {
  data class FrameResult(
    val cleanRms: Double,
    val noiseFloorRms: Double,
    val isSpeechActive: Boolean,
  )

  private val hpAlpha: Double = run {
    val dt = 1.0 / sampleRate.coerceAtLeast(8000)
    val rc = 1.0 / (2.0 * Math.PI * 95.0)
    rc / (rc + dt)
  }

  private var prevRaw = 0.0
  private var prevHp = 0.0
  private var noiseFloorRms = 45.0
  private var currentGain = 1.0
  private var speechHangFrames = 0
  private var consecutiveSpeechCandidates = 0
  private var consecutiveTonalFrames = 0
  private var initializedFloor = false

  fun reset(initialFloor: Double = 45.0) {
    prevRaw = 0.0
    prevHp = 0.0
    noiseFloorRms = initialFloor
    currentGain = 1.0
    speechHangFrames = 0
    consecutiveSpeechCandidates = 0
    consecutiveTonalFrames = 0
    initializedFloor = false
  }

  fun processFrameInPlace(
    pcm: ByteArray,
    len: Int,
    bluetoothMode: Boolean,
    whisperBoostOn: Boolean,
  ): FrameResult {
    val sampleCount = len / 2
    if (sampleCount <= 0) return FrameResult(0.0, noiseFloorRms, false)

    val work = DoubleArray(sampleCount)
    var sumSq = 0.0
    var diffSqSum = 0.0
    var zeroCrossings = 0
    var prevSign = 0
    var peakAbs = 0.0
    var prevWork = 0.0

    var idx = 0
    for (s in 0 until sampleCount) {
      val raw = ((pcm[idx + 1].toInt() shl 8) or (pcm[idx].toInt() and 0xFF)).toShort().toDouble()
      val hp = hpAlpha * (prevHp + raw - prevRaw)
      prevRaw = raw
      prevHp = hp
      work[s] = hp
      val a = abs(hp)
      if (a > peakAbs) peakAbs = a
      sumSq += hp * hp
      if (s > 0) {
        val d = hp - prevWork
        diffSqSum += d * d
      }
      prevWork = hp
      val sign = if (hp >= 0.0) 1 else -1
      if (prevSign != 0 && sign != prevSign) zeroCrossings++
      prevSign = sign
      idx += 2
    }

    val rms = sqrt(sumSq / sampleCount)
    val crestFactor = if (rms > 1.0) peakAbs / rms else 1.0
    val zcrRate = zeroCrossings.toDouble() / sampleCount
    val diffRms = if (sampleCount > 1) sqrt(diffSqSum / (sampleCount - 1)) else 0.0
    val spectralRoughness = if (rms > 1.0) diffRms / rms else 1.0

    if (!initializedFloor) {
      noiseFloorRms =
        if (bluetoothMode && whisperBoostOn) rms.coerceIn(14.0, 24.0)
        else rms.coerceIn(14.0, 140.0)
      initializedFloor = true
    }

    // 1. Vehicle horn / high-SPL steady siren / continuous tonal alarm detector:
    // Horns exhibit very steady near-sinusoid/square crest (<= 1.62) paired with elevated high frequencies (ZCR > 0.055)
    // and sustained duration, unlike organic formant-modulated speech (~180-350 Hz fundamental + varying harmonics).
    val looksLikeContinuousHorn =
      crestFactor < 1.56 && zcrRate > 0.055 && rms > 500.0
    if (looksLikeContinuousHorn) {
      consecutiveTonalFrames++
    } else {
      consecutiveTonalFrames = 0
    }
    val rejectedTonalHorn = consecutiveTonalFrames >= 2

    // 2. Sharp bird chirp / shrill whistle / dog bark transient detector:
    // Very high ZCR (> 0.44, e.g. >3500 Hz chirps/squeaks) or explosive impulsive bark spike (crest > 6.8 without sustained formants)
    val looksLikeChirpOrHiss = zcrRate > 0.44 && !bluetoothMode
    val looksLikeBtChirpOrHiss = zcrRate > 0.48 && bluetoothMode
    val looksLikeSuddenBarkOrClap = crestFactor > 6.8 && zcrRate > 0.28 && consecutiveSpeechCandidates == 0

    // Speech thresholds: ultra-sensitive for soft Bluetooth whispers when whisperBoostOn is active
    val baseMinSpeech =
      when {
        bluetoothMode && whisperBoostOn -> 34.0
        bluetoothMode -> 58.0
        whisperBoostOn -> 46.0
        else -> 82.0
      }
    val requiredSnr =
      when {
        bluetoothMode && whisperBoostOn -> 1.55
        whisperBoostOn -> 1.72
        else -> 2.15
      }
    val snrThreshold = max(baseMinSpeech, noiseFloorRms * requiredSnr)

    val speechBandOk =
      if (bluetoothMode) zcrRate in 0.014..0.46 else zcrRate in 0.015..0.43
    val acousticNotNonHuman =
      !rejectedTonalHorn &&
        !looksLikeChirpOrHiss &&
        !looksLikeBtChirpOrHiss &&
        !looksLikeSuddenBarkOrClap &&
        spectralRoughness in 0.04..1.85

    val candidate = rms >= snrThreshold && speechBandOk && acousticNotNonHuman
    val strongSpeech =
      rms >= snrThreshold * 1.40 &&
        (speechBandOk || (rms > snrThreshold * 2.2 && zcrRate <= 0.46)) &&
        acousticNotNonHuman

    if (candidate) {
      consecutiveSpeechCandidates++
    } else {
      consecutiveSpeechCandidates = 0
    }

    val rawSpeechDetected =
      strongSpeech ||
        (candidate && (speechHangFrames > 0 || consecutiveSpeechCandidates >= 2))

    if (rawSpeechDetected) {
      speechHangFrames = 1
    } else if (speechHangFrames > 0) {
      speechHangFrames--
    }
    val isSpeechActive = rawSpeechDetected || speechHangFrames > 0

    if (!rawSpeechDetected) {
      noiseFloorRms =
        if (rms < noiseFloorRms) {
          noiseFloorRms * 0.84 + rms * 0.16
        } else {
          val steadyStep = if (crestFactor < 3.6 || rejectedTonalHorn) 0.10 else 0.03
          noiseFloorRms * (1.0 - steadyStep) + rms * steadyStep
        }.coerceIn(14.0, 750.0)
    }

    val desiredGain =
      if (!isSpeechActive) {
        when {
          rejectedTonalHorn || looksLikeChirpOrHiss || looksLikeBtChirpOrHiss -> 0.06
          rms < noiseFloorRms * 1.35 -> 0.18
          else -> 0.45
        }
      } else {
        val subtractRatio =
          ((rms - 0.35 * noiseFloorRms) / max(rms, 1.0)).coerceIn(0.75, 1.0)
        val speechBoost =
          if (!whisperBoostOn) 1.02
          else {
            val btWhisperFactor = if (bluetoothMode) 1.14 else 1.06
            when {
              rms < 120.0 -> 1.65 * btWhisperFactor
              rms < 280.0 -> 1.40 * btWhisperFactor
              rms < 650.0 -> 1.18 * btWhisperFactor
              else -> 1.0
            }
          }
        (subtractRatio * speechBoost).coerceIn(0.75, 1.95)
      }

    val attackReleaseAlpha =
      when {
        isSpeechActive && desiredGain > currentGain -> 0.88
        desiredGain > currentGain -> 0.62
        else -> 0.30
      }
    val startGain =
      if (isSpeechActive && currentGain < 0.55) 0.55 else currentGain
    val endGain = startGain * (1.0 - attackReleaseAlpha) + desiredGain * attackReleaseAlpha
    currentGain = endGain

    val invCount = 1.0 / sampleCount
    var outIdx = 0
    for (s in 0 until sampleCount) {
      val g = startGain + (endGain - startGain) * (s * invCount)
      val scaled = work[s] * g
      val softLimited =
        if (abs(scaled) > 26000.0) (tanh(scaled / 32767.0) * 32767.0)
        else scaled
      val pcmSample = softLimited.toInt().coerceIn(-32768, 32767)
      pcm[outIdx] = (pcmSample and 0xFF).toByte()
      pcm[outIdx + 1] = ((pcmSample ushr 8) and 0xFF).toByte()
      outIdx += 2
    }

    val effectiveOutRms = rms * endGain
    return FrameResult(
      cleanRms = effectiveOutRms,
      noiseFloorRms = noiseFloorRms,
      isSpeechActive = isSpeechActive,
    )
  }
}
