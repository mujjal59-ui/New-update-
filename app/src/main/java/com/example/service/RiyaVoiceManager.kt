package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.data.RiyaState
import com.example.data.Store
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ElevenLabsErrorInfo(
  val httpCode: Int,
  val detailStatus: String,
  val detailMessage: String,
  val isOutputFormatTierBlocked: Boolean,
  val friendlyMessageBn: String,
)

data class DecodedTtsPcm(
  val pcmBytes: ByteArray,
  val sampleRate: Int,
  val channelCount: Int,
)

object RiyaVoiceManager {
  private const val TAG = "LxrVoiceManager"
  const val ALLOW_ANDROID_SYSTEM_TTS_FALLBACK = false
  private const val SECONDARY_CLOUD_TTS_MODEL_ID = "gemini-2.5-flash-preview-tts"

  val ELEVENLABS_FORMAT_PRIORITY = listOf("pcm_24000", "pcm_16000", "mp3_44100_128")

  @Volatile private var appCtx: Context? = null
  @Volatile private var currentRate = Store.DEFAULT_VOICE_SPEED
  @Volatile private var voiceEnabled = true
  @Volatile private var activeAudioTrack: AudioTrack? = null
  @Volatile private var cachedElevenPreferredFormat: String? = null
  @Volatile var onBeforeLocalTtsPlay: (() -> Unit)? = null
  @Volatile var isLiveAudioActivelyPlaying: (() -> Boolean)? = null

  private val cloudExec = Executors.newSingleThreadExecutor()
  private val speechSeq = AtomicInteger(0)
  private val trackLock = Any()

  private val ttsHttpClient =
    OkHttpClient.Builder()
      .connectionPool(okhttp3.ConnectionPool(4, 2, TimeUnit.MINUTES))
      .connectTimeout(6000, TimeUnit.MILLISECONDS)
      .readTimeout(25000, TimeUnit.MILLISECONDS)
      .writeTimeout(6000, TimeUnit.MILLISECONDS)
      .callTimeout(35000, TimeUnit.MILLISECONDS)
      .retryOnConnectionFailure(true)
      .build()

  @Volatile private var speakingStartedAtMs = 0L

  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

  fun isActivelySpeaking(): Boolean {
    if (!_isSpeaking.value) return false
    val elapsed = System.currentTimeMillis() - speakingStartedAtMs
    if (speakingStartedAtMs > 0L && elapsed > 30_000L) {
      _isSpeaking.value = false
      return false
    }
    return true
  }

  private val _lastSpoken = MutableStateFlow("")
  val lastSpoken: StateFlow<String> = _lastSpoken.asStateFlow()

  private val _ttsStatusLabel = MutableStateFlow("LXR ভয়েস প্রস্তুত...")
  val ttsStatusLabel: StateFlow<String> = _ttsStatusLabel.asStateFlow()

  private val _lastTtsError = MutableStateFlow<String?>(null)
  val lastTtsError: StateFlow<String?> = _lastTtsError.asStateFlow()

  fun initialize(context: Context) {
    val ctx = context.applicationContext
    appCtx = ctx
    currentRate = Store.voiceSpeed(ctx)
    updateActiveStatusLabel(ctx)
  }

  fun isElevenLabsConfigured(ctx: Context): Boolean =
    Store.elevenLabsEnabled(ctx) && Store.elevenLabsApiKey(ctx).isNotBlank()

  fun resolveElevenLabsFormatOrder(cachedPreferred: String? = cachedElevenPreferredFormat): List<String> {
    if (cachedPreferred.isNullOrBlank() || cachedPreferred !in ELEVENLABS_FORMAT_PRIORITY) {
      return ELEVENLABS_FORMAT_PRIORITY
    }
    return listOf(cachedPreferred) + ELEVENLABS_FORMAT_PRIORITY.filter { it != cachedPreferred }
  }

  fun parseElevenLabsError(httpCode: Int, rawBody: String): ElevenLabsErrorInfo {
    var detailStatus = ""
    var detailMsg = ""
    try {
      val root = JSONObject(rawBody)
      val detailObj = root.optJSONObject("detail")
      if (detailObj != null) {
        detailStatus = detailObj.optString("status").trim()
        detailMsg = detailObj.optString("message").trim()
      } else {
        detailMsg = root.optString("detail").ifBlank { root.optString("message") }.trim()
      }
    } catch (_: Throwable) {
      detailMsg = rawBody.take(180).trim()
    }
    val combinedLower = "$detailStatus $detailMsg".lowercase()
    val formatBlocked =
      (httpCode == 400 || httpCode == 403 || httpCode == 422) &&
        (combinedLower.contains("output_format") ||
          combinedLower.contains("pcm") ||
          combinedLower.contains("only_for_creator") ||
          combinedLower.contains("tier") ||
          combinedLower.contains("subscription"))
    val redactedMsg = LxrSecretRedactor.redactSecrets(detailMsg.take(140))
    val friendlyBn =
      when {
        formatBlocked -> "ElevenLabs অডিও ফরম্যাট ফলব্যাক চলছে ($detailStatus)"
        combinedLower.contains("missing_permissions") || combinedLower.contains("permission") ->
          "ElevenLabs কী-তে Text-to-Speech পারমিশন নেই (HTTP $httpCode)"
        httpCode == 401 -> "ElevenLabs API Key সঠিক নয় বা মেয়াদ শেষ (HTTP 401)"
        httpCode == 403 -> "ElevenLabs অনুমতি বা টায়ার সীমাবদ্ধতা (HTTP 403${if (redactedMsg.isNotBlank()) ": $redactedMsg" else ""})"
        httpCode == 404 || combinedLower.contains("voice_not_found") ->
          "ElevenLabs Voice ID খুঁজে পাওয়া যায়নি (HTTP $httpCode) — Voice ID যাচাই করো বস"
        httpCode == 402 || httpCode == 429 || combinedLower.contains("quota") ->
          "ElevenLabs ক্রেডিট শেষ বা রেট লিমিট (HTTP $httpCode)"
        else ->
          "ElevenLabs সার্ভার ত্রুটি (HTTP $httpCode${if (redactedMsg.isNotBlank()) ": $redactedMsg" else ""})"
      }
    return ElevenLabsErrorInfo(
      httpCode = httpCode,
      detailStatus = detailStatus,
      detailMessage = redactedMsg,
      isOutputFormatTierBlocked = formatBlocked,
      friendlyMessageBn = friendlyBn,
    )
  }

  private fun updateActiveStatusLabel(ctx: Context) {
    val voiceOpt = Store.voiceOption(ctx)
    val elevenConfigured = isElevenLabsConfigured(ctx)
    val apiKeyPresent = Store.apiKey(ctx).isNotBlank()
    val useCloud = Store.ttsCloudEnabled(ctx) && apiKeyPresent
    _ttsStatusLabel.value =
      when {
        elevenConfigured ->
          "ElevenLabs (${Store.DEFAULT_ELEVENLABS_MODEL_ID}) • Sweet Female (bn)"
        useCloud ->
          "${Store.TTS_MODEL_LABEL} • ${voiceOpt.displayName} (${voiceOpt.gender.labelBn}, bn-BD)"
        else ->
          "শুধু LXR ভয়েস (${voiceOpt.displayName}) — ফোন TTS বন্ধ ✓"
      }
  }

  fun refreshVoicePreferences(context: Context) {
    val ctx = context.applicationContext
    appCtx = ctx
    currentRate = Store.voiceSpeed(ctx)
    updateActiveStatusLabel(ctx)
    synchronized(trackLock) {
      val t = activeAudioTrack
      if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
        try {
          t.playbackParams = PlaybackParams().setSpeed(currentRate).setPitch(1.0f)
        } catch (_: Throwable) {}
      }
    }
  }

  fun setVoiceEnabled(enabled: Boolean) {
    voiceEnabled = enabled
    if (!enabled) stopSpeaking()
  }

  fun setSpeechRate(rate: Float) {
    currentRate = rate.coerceIn(Store.MIN_VOICE_SPEED, Store.MAX_VOICE_SPEED)
    synchronized(trackLock) {
      val t = activeAudioTrack
      if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
        try {
          t.playbackParams = PlaybackParams().setSpeed(currentRate).setPitch(1.0f)
        } catch (_: Throwable) {}
      }
    }
  }

  fun speakUrgent(
    context: Context,
    rawText: String,
    allowWhenMicMuted: Boolean = false,
  ) {
    initialize(context)
    speak(
      rawText = rawText,
      interrupt = true,
      forceSpeak = true,
      allowWhenMicMuted = allowWhenMicMuted,
    )
  }

  fun speak(
    rawText: String,
    interrupt: Boolean = true,
    forceSpeak: Boolean = false,
    forceElevenLabsOnly: Boolean = false,
    allowWhenMicMuted: Boolean = false,
  ) {
    if (RiyaState.micMuted.value && !allowWhenMicMuted) {
      stopSpeaking()
      return
    }
    if (!voiceEnabled && !forceSpeak) return
    val cleanText = sanitizeForClearBengaliSpeech(rawText)
    if (cleanText.isBlank()) return
    _lastSpoken.value = cleanText
    _lastTtsError.value = null

    val ctx = appCtx ?: return
    val elevenKey =
      if (forceElevenLabsOnly || Store.elevenLabsEnabled(ctx)) Store.elevenLabsApiKey(ctx).trim() else ""
    val useElevenLabs = elevenKey.isNotBlank()
    val apiKey = Store.apiKey(ctx).trim()
    val useGeminiTts = !forceElevenLabsOnly && Store.ttsCloudEnabled(ctx) && apiKey.isNotBlank()
    if (forceElevenLabsOnly && elevenKey.isBlank()) {
      _lastTtsError.value = "ElevenLabs API Key ফাঁকা আছে বস — Menu > Key পেজে API Key সেভ করো।"
      _ttsStatusLabel.value = "ElevenLabs API Key নেই"
      return
    }
    if (!useElevenLabs && !useGeminiTts) {
      return
    }
    if (!forceSpeak && isLiveAudioActivelyPlaying?.invoke() == true) {
      return
    }

    val seq = speechSeq.incrementAndGet()
    if (interrupt) {
      stopActivePlaybackOnly()
    }
    if (interrupt || forceSpeak) {
      try {
        onBeforeLocalTtsPlay?.invoke()
      } catch (_: Throwable) {}
    }

    currentRate = Store.voiceSpeed(ctx)
    val voiceName = Store.voice(ctx)
    val elevenVoiceId = Store.elevenLabsVoiceId(ctx)
    val stylePrompt = Store.ttsStyleInstruction(ctx)
    cloudExec.execute {
      if (seq != speechSeq.get() || (RiyaState.micMuted.value && !allowWhenMicMuted)) return@execute
      var played = false
      if (useElevenLabs) {
        played = trySpeakWithElevenLabs(ctx, elevenKey, elevenVoiceId, cleanText, seq, allowWhenMicMuted)
      }
      if (!played && !forceElevenLabsOnly && useGeminiTts && seq == speechSeq.get() && (!RiyaState.micMuted.value || allowWhenMicMuted)) {
        played = trySpeakWithGeminiFlashTts(apiKey, voiceName, stylePrompt, cleanText, seq, allowWhenMicMuted)
      }
      if (!played && seq == speechSeq.get()) {
        _isSpeaking.value = false
      }
    }
  }

  private fun trySpeakWithElevenLabs(
    ctx: Context,
    elevenApiKey: String,
    voiceId: String,
    cleanText: String,
    seq: Int,
    allowWhenMicMuted: Boolean,
  ): Boolean {
    if (elevenApiKey.isBlank() || seq != speechSeq.get()) return false
    val cleanVoiceId = voiceId.trim().ifBlank { Store.DEFAULT_ELEVENLABS_VOICE_ID }
    val encodedVoiceId = Uri.encode(cleanVoiceId)
    val speedVal = currentRate.coerceIn(0.75f, 1.20f)
    val modelsToTry = listOf(Store.DEFAULT_ELEVENLABS_MODEL_ID, Store.ELEVENLABS_FALLBACK_MODEL_ID).distinct()
    val formatsToTry = resolveElevenLabsFormatOrder()
    var lastErrMsg: String? = null

    for (modelId in modelsToTry) {
      var modelRejected = false
      for (outputFmt in formatsToTry) {
        if (seq != speechSeq.get() || (RiyaState.micMuted.value && !allowWhenMicMuted)) return false
        try {
          val payload =
            JSONObject()
              .put("text", cleanText)
              .put("model_id", modelId)
              .put(
                "voice_settings",
                JSONObject()
                  .put("stability", 0.50)
                  .put("similarity_boost", 0.80)
                  .put("style", 0.35)
                  .put("use_speaker_boost", true)
                  .put("speed", speedVal.toDouble()),
              )
          val req =
            Request.Builder()
              .url("https://api.elevenlabs.io/v1/text-to-speech/$encodedVoiceId?output_format=$outputFmt")
              .header("xi-api-key", elevenApiKey)
              .header("Accept", if (outputFmt.startsWith("mp3")) "audio/mpeg" else "application/octet-stream")
              .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
              .build()

          ttsHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
              val rawErr = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
              val parsed = parseElevenLabsError(resp.code, rawErr)
              lastErrMsg = parsed.friendlyMessageBn
              Log.w(TAG, "ElevenLabs TTS ($modelId / $outputFmt) HTTP ${resp.code}: ${parsed.detailStatus}")
              if (parsed.isOutputFormatTierBlocked) {
                if (cachedElevenPreferredFormat == outputFmt) cachedElevenPreferredFormat = null
                return@use // Try next format (pcm_16000 -> mp3_44100_128) immediately
              }
              if (resp.code == 400 || resp.code == 422) {
                modelRejected = true
                return@use
              }
              // Auth / quota / voice_not_found errors apply across all formats -> surface immediately
              _lastTtsError.value = parsed.friendlyMessageBn
              _ttsStatusLabel.value = parsed.friendlyMessageBn
              return false
            }

            val rawBytes = resp.body?.bytes() ?: return@use
            if (rawBytes.size < 64 || seq != speechSeq.get()) return@use
            val decoded =
              when {
                outputFmt == "pcm_24000" -> DecodedTtsPcm(rawBytes, 24000, 1)
                outputFmt == "pcm_16000" -> DecodedTtsPcm(rawBytes, 16000, 1)
                else -> decodeCompressedAudioToPcm(ctx, rawBytes, seq)
              }
            if (decoded == null || decoded.pcmBytes.size < 64) {
              lastErrMsg = "ElevenLabs অডিও ডিকোড করা যায়নি ($outputFmt)"
              return@use
            }

            cachedElevenPreferredFormat = outputFmt
            _lastTtsError.value = null
            _ttsStatusLabel.value = "ElevenLabs ($modelId • $outputFmt) • Sweet Female (bn)"
            return playPcmAudio(
              pcm = decoded.pcmBytes,
              sampleRate = decoded.sampleRate,
              channelCount = decoded.channelCount,
              seq = seq,
              allowWhenMicMuted = allowWhenMicMuted,
            )
          }
          if (modelRejected) break
        } catch (e: Exception) {
          val safeMsg = LxrSecretRedactor.redactSecrets(e.message ?: "network error")
          Log.w(TAG, "ElevenLabs TTS ($modelId/$outputFmt) error: $safeMsg")
          lastErrMsg = "ElevenLabs নেটওয়ার্ক ত্রুটি: $safeMsg"
        }
      }
    }

    if (!lastErrMsg.isNullOrBlank()) {
      _lastTtsError.value = lastErrMsg
      _ttsStatusLabel.value = lastErrMsg!!
    }
    return false
  }

  private fun decodeCompressedAudioToPcm(ctx: Context, encodedBytes: ByteArray, seq: Int): DecodedTtsPcm? {
    val tmpFile = File(ctx.cacheDir, "lxr_el_${seq}_${System.currentTimeMillis()}.mp3")
    var extractor: MediaExtractor? = null
    var codec: MediaCodec? = null
    return try {
      tmpFile.writeBytes(encodedBytes)
      extractor = MediaExtractor().apply { setDataSource(tmpFile.absolutePath) }
      var trackIdx = -1
      var trackFormat: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val fmt = extractor.getTrackFormat(i)
        val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("audio/")) {
          trackIdx = i
          trackFormat = fmt
          break
        }
      }
      if (trackIdx < 0 || trackFormat == null) return null
      extractor.selectTrack(trackIdx)
      val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
      var sampleRate =
        if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        else 44100
      var channels =
        if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        else 1

      codec = MediaCodec.createDecoderByType(mime)
      codec.configure(trackFormat, null, null, 0)
      codec.start()

      val pcmOut = ByteArrayOutputStream(encodedBytes.size * 4)
      val info = MediaCodec.BufferInfo()
      var inputDone = false
      var outputDone = false
      val deadlineMs = System.currentTimeMillis() + 12_000L

      while (!outputDone && System.currentTimeMillis() < deadlineMs && seq == speechSeq.get()) {
        if (!inputDone) {
          val inIdx = codec.dequeueInputBuffer(10_000L)
          if (inIdx >= 0) {
            val inBuf = codec.getInputBuffer(inIdx)
            val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
            if (sampleSize < 0) {
              codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }
        val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
        when {
          outIdx >= 0 -> {
            val outBuf = codec.getOutputBuffer(outIdx)
            if (outBuf != null && info.size > 0) {
              outBuf.position(info.offset)
              outBuf.limit(info.offset + info.size)
              outBuf.order(ByteOrder.LITTLE_ENDIAN)
              val chunk = ByteArray(info.size)
              outBuf.get(chunk)
              pcmOut.write(chunk)
            }
            codec.releaseOutputBuffer(outIdx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              outputDone = true
            }
          }
          outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val outFmt = codec.outputFormat
            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
              sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
              channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
          }
        }
      }
      val bytes = pcmOut.toByteArray()
      if (bytes.size < 64) null
      else DecodedTtsPcm(bytes, sampleRate.coerceIn(8000, 48000), channels.coerceIn(1, 2))
    } catch (e: Throwable) {
      Log.w(TAG, "MP3 decode fallback failed: ${e.message}")
      null
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { extractor?.release() }
      runCatching { tmpFile.delete() }
    }
  }

  private fun trySpeakWithGeminiFlashTts(
    apiKey: String,
    voiceName: String,
    styleInstruction: String,
    cleanText: String,
    seq: Int,
    allowWhenMicMuted: Boolean,
  ): Boolean {
    val modelsToTry = listOf(Store.TTS_MODEL_ID, SECONDARY_CLOUD_TTS_MODEL_ID).distinct()
    val paceHint =
      when {
        currentRate <= 0.92f -> "Deliver in a calm, measured, slightly unhurried manner."
        currentRate >= 1.12f -> "Deliver briskly yet clearly."
        else -> "Deliver in a balanced, warm cadence."
      }
    val promptText =
      "$styleInstruction $paceHint Match the emotional tone of the content naturally (warm joy and light smile for happy topics, tender caring softness for sad stories, respectful empathy when comforting Boss, expressive rhythm for poems or jokes). Speak in natural Bengali (Bangladesh, bn-BD): $cleanText"

    val payload =
      JSONObject()
        .put(
          "contents",
          JSONArray()
            .put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", promptText)))),
        )
        .put(
          "generationConfig",
          JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
              "speechConfig",
              JSONObject()
                .put(
                  "voiceConfig",
                  JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName)),
                ),
            ),
        )

    for (modelId in modelsToTry) {
      if (seq != speechSeq.get() || (RiyaState.micMuted.value && !allowWhenMicMuted)) return false
      try {
        val req =
          Request.Builder()
            .url(
              "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey"
            )
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        ttsHttpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) {
            Log.w(TAG, "LXR Cloud TTS ($modelId) HTTP ${resp.code}")
            return@use
          }
          val bodyStr = resp.body?.string() ?: return@use
          val root = JSONObject(bodyStr)
          val parts =
            root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
              ?: return@use

          var b64Audio: String? = null
          var mimeType = "audio/L16;rate=24000"
          for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val data = inline.optString("data")
            if (data.isNotBlank()) {
              b64Audio = data
              mimeType = inline.optString("mimeType", mimeType)
              break
            }
          }
          if (b64Audio.isNullOrBlank() || seq != speechSeq.get()) return@use

          val pcmBytes = Base64.decode(b64Audio, Base64.DEFAULT)
          if (pcmBytes.isEmpty()) return@use
          val sampleRate = parseSampleRate(mimeType)
          _ttsStatusLabel.value = "${Store.TTS_MODEL_LABEL} • $voiceName (bn-BD)"
          return playPcmAudio(pcmBytes, sampleRate, channelCount = 1, seq = seq, allowWhenMicMuted = allowWhenMicMuted)
        }
      } catch (e: Exception) {
        Log.w(TAG, "LXR Cloud TTS ($modelId) attempt error: ${LxrSecretRedactor.redactSecrets(e.message ?: "")}")
      }
    }
    return false
  }

  private fun parseSampleRate(mimeType: String): Int {
    val m = Regex("rate=(\\d+)").find(mimeType)
    return m?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8000, 48000) ?: 24000
  }

  fun selectOutputAudioUsage(
    isBluetoothMicActive: Boolean,
    isBtCommOutputVerified: Boolean,
  ): Int =
    if (isBluetoothMicActive && isBtCommOutputVerified) AudioAttributes.USAGE_VOICE_COMMUNICATION
    else AudioAttributes.USAGE_MEDIA

  private fun playPcmAudio(
    pcm: ByteArray,
    sampleRate: Int,
    channelCount: Int = 1,
    seq: Int,
    allowWhenMicMuted: Boolean = false,
  ): Boolean {
    if (seq != speechSeq.get()) return false
    if (RiyaState.micMuted.value && !allowWhenMicMuted) return false
    val ctx = appCtx
    val audioManager = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val usage =
      selectOutputAudioUsage(
        isBluetoothMicActive = RiyaState.isBluetoothMicActive.value,
        isBtCommOutputVerified = RiyaState.isBtCommOutputVerified.value,
      )
    val streamType =
      if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) AudioManager.STREAM_VOICE_CALL
      else AudioManager.STREAM_MUSIC
    val streamVol = runCatching { audioManager?.getStreamVolume(streamType) ?: 1 }.getOrDefault(1)
    if (streamVol == 0) {
      _lastTtsError.value = "ফোনের ভলিউম মিউট (০) আছে বস — ভলিউম বাড়িয়ে নাও!"
      RiyaState.addLog("⚠️ সতর্কতা: অডিও স্ট্রিম ভলিউম ০ — সাউন্ড বাড়িয়ে নাও বস")
    }
    val channelMask =
      if (channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

    val attrs =
      AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    var focusRequest: AudioFocusRequest? = null
    try {
      if (audioManager != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          val req =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
              .setAudioAttributes(attrs)
              .build()
          focusRequest = req
          audioManager.requestAudioFocus(req)
        } else {
          @Suppress("DEPRECATION")
          audioManager.requestAudioFocus(null, streamType, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
      }
    } catch (_: Throwable) {}

    var track: AudioTrack? = null
    var wroteAny = false
    try {
      val minBuf =
        AudioTrack.getMinBufferSize(
          sampleRate,
          channelMask,
          AudioFormat.ENCODING_PCM_16BIT,
        )
      if (minBuf <= 0) {
        _lastTtsError.value = "অডিও হার্ডওয়্যার বাফার প্রস্তুত নয়"
        _isSpeaking.value = false
        return false
      }
      track =
        AudioTrack.Builder()
          .setAudioAttributes(attrs)
          .setAudioFormat(
            AudioFormat.Builder()
              .setSampleRate(sampleRate)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setChannelMask(channelMask)
              .build()
          )
          .setBufferSizeInBytes(maxOf(minBuf * 2, 16384))
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build()
      if (track.state != AudioTrack.STATE_INITIALIZED) {
        _lastTtsError.value = "AudioTrack চালু করা যায়নি"
        try { track.release() } catch (_: Throwable) {}
        _isSpeaking.value = false
        return false
      }

      synchronized(trackLock) {
        activeAudioTrack = track
        try {
          track.setVolume(AudioTrack.getMaxVolume())
        } catch (_: Throwable) {}
        try {
          val speed = (ctx?.let { Store.voiceSpeed(it) } ?: currentRate)
            .coerceIn(Store.MIN_VOICE_SPEED, Store.MAX_VOICE_SPEED)
          track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f)
        } catch (_: Throwable) {}
        track.play()
      }

      if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
        _lastTtsError.value = "AudioTrack প্লেব্যাক শুরু করতে পারেনি"
        _isSpeaking.value = false
        return false
      }

      speakingStartedAtMs = System.currentTimeMillis()
      _isSpeaking.value = true

      var offset = 0
      val chunk = 4096
      while (offset < pcm.size && seq == speechSeq.get() && (!RiyaState.micMuted.value || allowWhenMicMuted)) {
        val len = minOf(chunk, pcm.size - offset)
        val written =
          synchronized(trackLock) {
            if (seq != speechSeq.get() || activeAudioTrack !== track || (RiyaState.micMuted.value && !allowWhenMicMuted)) -1
            else {
              try {
                track.write(pcm, offset, len)
              } catch (_: Throwable) {
                -1
              }
            }
          }
        if (written <= 0) break
        wroteAny = true
        offset += written
      }
      return wroteAny
    } catch (e: Throwable) {
      Log.e(TAG, "Error playing LXR Cloud voice PCM audio", e)
      _lastTtsError.value = "ভয়েস প্লেব্যাক ত্রুটি: ${e.message}"
      return false
    } finally {
      synchronized(trackLock) {
        if (activeAudioTrack === track) activeAudioTrack = null
        try {
          track?.pause()
          track?.flush()
          track?.stop()
          track?.release()
        } catch (_: Throwable) {}
      }
      try {
        if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
          audioManager.abandonAudioFocusRequest(focusRequest)
        }
      } catch (_: Throwable) {}
      _isSpeaking.value = false
    }
  }

  private fun stopActivePlaybackOnly() {
    synchronized(trackLock) {
      try {
        activeAudioTrack?.pause()
        activeAudioTrack?.flush()
      } catch (_: Throwable) {}
      activeAudioTrack = null
    }
  }

  fun stopSpeaking() {
    speechSeq.incrementAndGet()
    stopActivePlaybackOnly()
    _isSpeaking.value = false
  }

  fun shutdown() {
    speechSeq.incrementAndGet()
    stopActivePlaybackOnly()
    _isSpeaking.value = false
  }

  /**
   * Ensures LXR never identifies itself as an AI, removes robotic artifacts ("হিহি"),
   * removes accidental "Slide again to unlock" text, and enforces natural complete output.
   */
  fun validateAndNormalizeSpeechOutput(input: String): String {
    var text = input
    text = text.replace(Regex("Slide\\s+again\\s+to\\s+unlock", RegexOption.IGNORE_CASE), "")
    text = text.replace(Regex("আমি\\s+(একটি|একজন)\\s+(কৃত্রিম\\s+বুদ্ধিমত্তা|এআই|AI(\\s+অ্যাসিস্ট্যান্ট|\\s+সহকারী)?)", RegexOption.IGNORE_CASE), "আমি LxR")
    text = text.replace(Regex("(?:I\\s+am|I'm)\\s+(?:an?\\s+)?(?:(?:large\\s+)?language\\s+model|AI(?:\\s+(?:assistant|language\\s+model))?|artificial\\s+intelligence)", RegexOption.IGNORE_CASE), "I am LxR")
    text = text.replace(Regex("(?:large\\s+)?language\\s+model", RegexOption.IGNORE_CASE), "LxR")
    text = text.replace(Regex("(হিহি+|হি\\s+হি+|hehe+|he\\s+he+)", RegexOption.IGNORE_CASE), "হাহা")
    text = text.replace(Regex("\\bPriya\\b", RegexOption.IGNORE_CASE), "LXR--AI")
    text = text.replace(Regex("\\bRiya\\s*AI\\b", RegexOption.IGNORE_CASE), "LXR--AI")
    text = text.replace(Regex("\\bRiya\\b", RegexOption.IGNORE_CASE), "LXR--AI")
    text = text.replace("প্রিয়া", "এল এক্স আর")
    text = text.replace("রিয়া এআই", "এল এক্স আর")
    text = text.replace("রিয়া", "এল এক্স আর")
    text = text.replace(Regex("[*#_`~>|\\[\\]{}]"), " ")
    return text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
  }

  /**
   * Normalizes text so Bengali TTS (ElevenLabs, Gemini 3.1 Flash TTS, and Voice Studio) articulates every word clearly.
   */
  fun sanitizeForClearBengaliSpeech(input: String): String {
    var text = validateAndNormalizeSpeechOutput(input)
    text = text.replace(Regex("\\bPriya\\b", RegexOption.IGNORE_CASE), "এল এক্স আর এ আই")
    text = text.replace(Regex("\\bRiya\\s*AI\\b", RegexOption.IGNORE_CASE), "এল এক্স আর এ আই")
    text = text.replace(Regex("\\bRiya\\b", RegexOption.IGNORE_CASE), "এল এক্স আর এ আই")
    text = text.replace("প্রিয়া", "এল এক্স আর এ আই")
    text = text.replace("রিয়া এআই", "এল এক্স আর এ আই")
    text = text.replace("রিয়া", "এল এক্স আর এ আই")

    text = text.replace(Regex("https?://\\S+"), "")
    text = text.replace(Regex("[*#_`~>|\\[\\]{}]"), " ")

    text =
      text.replace(
        Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF]"),
        " ",
      )

    val replacements =
      listOf(
        Regex("LXR[-\\s]*AI", RegexOption.IGNORE_CASE) to "এল এক্স আর এ আই",
        Regex("\\bLxR\\b", RegexOption.IGNORE_CASE) to "এল এক্স আর",
        Regex("\\bWhatsApp\\b", RegexOption.IGNORE_CASE) to "হোয়াটসঅ্যাপ",
        Regex("\\bimo\\b", RegexOption.IGNORE_CASE) to "ইমো",
        Regex("\\bMessenger\\b", RegexOption.IGNORE_CASE) to "মেসেঞ্জার",
        Regex("\\bInfinix Hot 60 Pro\\+?\\b", RegexOption.IGNORE_CASE) to "ইনফিনিক্স হট সিক্সটি প্রো প্লাস",
        Regex("\\bInfinix\\b", RegexOption.IGNORE_CASE) to "ইনফিনিক্স",
        Regex("\\bAndroid 16\\b", RegexOption.IGNORE_CASE) to "অ্যান্ড্রয়েড ষোলো",
        Regex("\\bAccessibility\\b", RegexOption.IGNORE_CASE) to "অ্যাক্সেসিবিলিটি",
        Regex("\\bNotification\\b", RegexOption.IGNORE_CASE) to "নোটিফিকেশন",
        Regex("\\bAuto[- ]?Reply\\b", RegexOption.IGNORE_CASE) to "অটো রিপ্লাই",
        Regex("\\bTasks?\\b", RegexOption.IGNORE_CASE) to "টাস্ক",
        Regex("\\bSettings\\b", RegexOption.IGNORE_CASE) to "সেটিংস",
        Regex("\\bSearch\\b", RegexOption.IGNORE_CASE) to "সার্চ",
        Regex("\\bSend\\b", RegexOption.IGNORE_CASE) to "সেন্ড",
        Regex("\\bEmoji\\b", RegexOption.IGNORE_CASE) to "ইমোজি",
        Regex("\\bBluetooth\\b", RegexOption.IGNORE_CASE) to "ব্লুটুথ",
        Regex("\\bBoss\\b", RegexOption.IGNORE_CASE) to "বস",
      )
    for ((pattern, replacement) in replacements) {
      text = text.replace(pattern, replacement)
    }

    text = text.replace(Regex("\\s+"), " ").trim()
    return text
  }
}
