package com.example.service

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time 16 kHz PCM-16 software Noise Cancellation & Speech Enhancement DSP
 * engineered to work identically on BOTH:
 *  - Wireless Bluetooth Headset / Earbuds (SCO / BLE)
 *  - Built-in Phone Mic (wideband close/room capture)
 *
 * Performs:
 *  1. 1st-order 95 Hz High-Pass Filter -> removes wind, AC/fan rumble, traffic bass, thuds.
 *  2. Adaptive stationary noise-floor estimation -> separates continuous background noise
 *     (fan, market/crowd chatter, street hum) from actual human speech bursts.
 *  3. Smooth noise-gate & spectral attenuation -> suppresses ambient noise toward silence so
 *     Gemini Live VAD triggers cleanly in ~1s without waiting 10-12s on noisy mic input.
 *  4. Clean speech-only clarity boost (optional whisperBoost) -> only amplifies verified speech,
 *     never ambient noise.
 */
class LxrAudioNoiseSuppressor(
  private val sampleRate: Int = 16000,
) {
  data class FrameResult(
    val cleanRms: Double,
    val noiseFloorRms: Double,
    val isSpeechActive: Boolean,
  )

  // High-pass filter state (~95 Hz @ 16 kHz -> rc = 1 / (2*pi*95) ~ 0.001675)
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
  private var initializedFloor = false

  fun reset(initialFloor: Double = 45.0) {
    prevRaw = 0.0
    prevHp = 0.0
    noiseFloorRms = initialFloor
    currentGain = 1.0
    speechHangFrames = 0
    consecutiveSpeechCandidates = 0
    initializedFloor = false
  }

  /**
   * Processes 16-bit little-endian mono PCM in-place.
   * Returns acoustic telemetry so the caller can decide VAD/hangover cleanly.
   */
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
    var zeroCrossings = 0
    var prevSign = 0
    var peakAbs = 0.0

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
      val sign = if (hp >= 0.0) 1 else -1
      if (prevSign != 0 && sign != prevSign) zeroCrossings++
      prevSign = sign
      idx += 2
    }

    val rms = sqrt(sumSq / sampleCount)
    val crestFactor = if (rms > 1.0) peakAbs / rms else 1.0
    val zcrRate = zeroCrossings.toDouble() / sampleCount

    if (!initializedFloor) {
      noiseFloorRms = rms.coerceIn(18.0, 260.0)
      initializedFloor = true
    }

    // Speech characteristics: clear energy over noise floor, non-flat crest, balanced speech ZCR
    val baseMinSpeech =
      when {
        bluetoothMode && whisperBoostOn -> 52.0
        bluetoothMode -> 72.0
        whisperBoostOn -> 64.0
        else -> 92.0
      }
    val requiredSnr = if (whisperBoostOn) 1.85 else 2.20
    val snrThreshold = max(baseMinSpeech, noiseFloorRms * requiredSnr)

    // Broadband impulsive static or ultra-high-frequency hiss check
    val speechLikeBand = zcrRate in 0.015..0.48 || rms > snrThreshold * 1.45
    val candidate = rms >= snrThreshold && speechLikeBand
    val strongSpeech = rms >= snrThreshold * 1.40 && speechLikeBand

    if (candidate) {
      consecutiveSpeechCandidates++
    } else {
      consecutiveSpeechCandidates = 0
    }

    val rawSpeechDetected =
      strongSpeech ||
        (candidate && (speechHangFrames > 0 || consecutiveSpeechCandidates >= 2))

    if (rawSpeechDetected) {
      speechHangFrames = 2 // brief ~80ms syllable bridge without dragging end-of-speech latency
    } else if (speechHangFrames > 0) {
      speechHangFrames--
    }
    val isSpeechActive = rawSpeechDetected || speechHangFrames > 0

    // Adapt stationary noise floor quickly when ambient noise rises (e.g. fan/traffic) so end-of-speech triggers cleanly
    if (!rawSpeechDetected) {
      noiseFloorRms =
        if (rms < noiseFloorRms) {
          noiseFloorRms * 0.84 + rms * 0.16
        } else {
          val steadyStep = if (crestFactor < 3.6) 0.09 else 0.03
          noiseFloorRms * (1.0 - steadyStep) + rms * steadyStep
        }.coerceIn(15.0, 750.0)
    }

    // Compute desired frame gain:
    // - Non-speech: suppress background noise cleanly to silence so Gemini VAD triggers immediately!
    // - Speech: clean spectral subtraction factor * optional clarity boost
    val desiredGain =
      if (!isSpeechActive) {
        if (rms < noiseFloorRms * 1.25) 0.0 else 0.06
      } else {
        val subtractRatio =
          ((rms - 0.60 * noiseFloorRms) / max(rms, 1.0)).coerceIn(0.45, 1.0)
        val speechBoost =
          if (!whisperBoostOn) 1.05
          else {
            val phoneMult = if (bluetoothMode) 1.0 else 1.18
            when {
              rms < 180.0 -> 2.8 * phoneMult
              rms < 480.0 -> 2.2 * phoneMult
              rms < 1200.0 -> 1.65 * phoneMult
              rms < 2400.0 -> 1.25 * phoneMult
              else -> 1.0
            }
          }
        (subtractRatio * speechBoost).coerceIn(0.45, 3.6)
      }

    // Smooth gain attack/release per frame to eliminate audible gating clicks
    val attackReleaseAlpha = if (desiredGain > currentGain) 0.55 else 0.30
    val startGain = currentGain
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
