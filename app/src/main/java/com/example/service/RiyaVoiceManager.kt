package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.data.RiyaState
import com.example.data.Store
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

object RiyaVoiceManager {
  private const val TAG = "LxrVoiceManager"
  const val ALLOW_ANDROID_SYSTEM_TTS_FALLBACK = false
  private const val SECONDARY_CLOUD_TTS_MODEL_ID = "gemini-2.5-flash-preview-tts"

  @Volatile private var appCtx: Context? = null
  @Volatile private var currentRate = Store.DEFAULT_VOICE_SPEED
  @Volatile private var voiceEnabled = true
  @Volatile private var activeAudioTrack: AudioTrack? = null
  @Volatile var onBeforeLocalTtsPlay: (() -> Unit)? = null
  @Volatile var isLiveAudioActivelyPlaying: (() -> Boolean)? = null

  private val cloudExec = Executors.newSingleThreadExecutor()
  private val speechSeq = AtomicInteger(0)
  private val trackLock = Any()

  private val ttsHttpClient =
    OkHttpClient.Builder()
      .connectionPool(okhttp3.ConnectionPool(4, 2, TimeUnit.MINUTES))
      .connectTimeout(2500, TimeUnit.MILLISECONDS)
      .readTimeout(6500, TimeUnit.MILLISECONDS)
      .writeTimeout(2500, TimeUnit.MILLISECONDS)
      .callTimeout(10000, TimeUnit.MILLISECONDS)
      .retryOnConnectionFailure(true)
      .build()

  @Volatile private var speakingStartedAtMs = 0L

  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

  fun isActivelySpeaking(): Boolean {
    if (!_isSpeaking.value) return false
    val elapsed = System.currentTimeMillis() - speakingStartedAtMs
    if (speakingStartedAtMs > 0L && elapsed > 18_000L) {
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

  fun speakUrgent(context: Context, rawText: String) {
    initialize(context)
    speak(rawText, interrupt = true, forceSpeak = true)
  }

  fun speak(
    rawText: String,
    interrupt: Boolean = true,
    forceSpeak: Boolean = false,
    forceElevenLabsOnly: Boolean = false,
  ) {
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
      // Strictly never fall back to the phone's default robotic Android TextToSpeech voice
      return
    }
    if (!forceSpeak && isLiveAudioActivelyPlaying?.invoke() == true) {
      // Do not layer local TTS on top of active Gemini Live speech
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
      if (seq != speechSeq.get()) return@execute
      var played = false
      if (useElevenLabs) {
        played = trySpeakWithElevenLabs(elevenKey, elevenVoiceId, cleanText, seq)
      }
      if (!played && !forceElevenLabsOnly && useGeminiTts && seq == speechSeq.get()) {
        played = trySpeakWithGeminiFlashTts(apiKey, voiceName, stylePrompt, cleanText, seq)
      }
      if (!played && seq == speechSeq.get()) {
        _isSpeaking.value = false
      }
    }
  }

  private fun trySpeakWithElevenLabs(
    elevenApiKey: String,
    voiceId: String,
    cleanText: String,
    seq: Int,
  ): Boolean {
    if (elevenApiKey.isBlank() || seq != speechSeq.get()) return false
    return try {
      val speedVal = currentRate.coerceIn(0.75f, 1.20f)
      val payload =
        JSONObject()
          .put("text", cleanText)
          .put("model_id", Store.DEFAULT_ELEVENLABS_MODEL_ID)
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
          .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=pcm_24000")
          .header("xi-api-key", elevenApiKey)
          .header("Accept", "application/octet-stream")
          .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
          .build()

      ttsHttpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          val errCode = resp.code
          Log.w(TAG, "ElevenLabs TTS HTTP $errCode")
          _lastTtsError.value =
            when (errCode) {
              401, 403 -> "ElevenLabs API Key সঠিক নয় বা মেয়াদ শেষ (HTTP $errCode)"
              402, 429 -> "ElevenLabs ক্রেডিট শেষ বা রেট লিমিট (HTTP $errCode)"
              else -> "ElevenLabs সার্ভার ত্রুটি (HTTP $errCode)"
            }
          _ttsStatusLabel.value = _lastTtsError.value ?: "ElevenLabs ত্রুটি ($errCode)"
          return false
        }
        val pcmBytes = resp.body?.bytes() ?: return false
        if (pcmBytes.size < 64 || seq != speechSeq.get()) return false
        _lastTtsError.value = null
        _ttsStatusLabel.value = "ElevenLabs (${Store.DEFAULT_ELEVENLABS_MODEL_ID}) • Sweet Female (bn)"
        playPcmAudio(pcmBytes, 24000, seq)
        true
      }
    } catch (e: Exception) {
      Log.w(TAG, "ElevenLabs TTS error: ${e.message}")
      _lastTtsError.value = "ElevenLabs নেটওয়ার্ক ত্রুটি: ${e.message}"
      false
    }
  }

  private fun trySpeakWithGeminiFlashTts(
    apiKey: String,
    voiceName: String,
    styleInstruction: String,
    cleanText: String,
    seq: Int,
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
      if (seq != speechSeq.get()) return false
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
          playPcmAudio(pcmBytes, sampleRate, seq)
          return true
        }
      } catch (e: Exception) {
        Log.w(TAG, "LXR Cloud TTS ($modelId) attempt error: ${e.message}")
      }
    }
    return false
  }

  private fun parseSampleRate(mimeType: String): Int {
    val m = Regex("rate=(\\d+)").find(mimeType)
    return m?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8000, 48000) ?: 24000
  }

  private fun playPcmAudio(pcm: ByteArray, sampleRate: Int, seq: Int) {
    if (seq != speechSeq.get()) return
    val ctx = appCtx
    val audioManager = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val usage =
      if (RiyaState.isBluetoothMicActive.value) AudioAttributes.USAGE_VOICE_COMMUNICATION
      else AudioAttributes.USAGE_MEDIA
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
          audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
      }
    } catch (_: Throwable) {}

    var track: AudioTrack? = null
    try {
      val minBuf =
        AudioTrack.getMinBufferSize(
          sampleRate,
          AudioFormat.CHANNEL_OUT_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
        )
      if (minBuf <= 0) {
        _lastTtsError.value = "অডিও হার্ডওয়্যার বাফার প্রস্তুত নয়"
        _isSpeaking.value = false
        return
      }
      track =
        AudioTrack.Builder()
          .setAudioAttributes(attrs)
          .setAudioFormat(
            AudioFormat.Builder()
              .setSampleRate(sampleRate)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
              .build()
          )
          .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build()
      if (track.state != AudioTrack.STATE_INITIALIZED) {
        _lastTtsError.value = "AudioTrack চালু করা যায়নি"
        try { track.release() } catch (_: Throwable) {}
        _isSpeaking.value = false
        return
      }

      speakingStartedAtMs = System.currentTimeMillis()
      _isSpeaking.value = true

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

      var offset = 0
      val chunk = 4096
      while (offset < pcm.size && seq == speechSeq.get()) {
        val len = minOf(chunk, pcm.size - offset)
        val written =
          synchronized(trackLock) {
            if (seq != speechSeq.get() || activeAudioTrack !== track) -1
            else {
              try {
                track.write(pcm, offset, len)
              } catch (_: Throwable) {
                -1
              }
            }
          }
        if (written <= 0) break
        offset += written
      }
    } catch (e: Throwable) {
      Log.e(TAG, "Error playing LXR Cloud voice PCM audio", e)
      _lastTtsError.value = "ভয়েস প্লেব্যাক ত্রুটি: ${e.message}"
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
    // Never allow old wrong branding
    text = text.replace(Regex("\\bPriya\\b", RegexOption.IGNORE_CASE), "এল এক্স আর এ আই")
    text = text.replace(Regex("\\bRiya\\s*AI\\b", RegexOption.IGNORE_CASE), "এল এক্স আর এ আই")
    text = text.replace(Regex("\\bRiya\\b", RegexOption.IGNORE_CASE), "এল এক্স আর এ আই")
    text = text.replace("প্রিয়া", "এল এক্স আর এ আই")
    text = text.replace("রিয়া এআই", "এল এক্স আর এ আই")
    text = text.replace("রিয়া", "এল এক্স আর এ আই")

    // Remove URLs and markdown syntax that confuse TTS
    text = text.replace(Regex("https?://\\S+"), "")
    text = text.replace(Regex("[*#_`~>|\\[\\]{}]"), " ")

    // Remove emojis and stray symbol blocks while keeping Bengali, ASCII letters/digits, and punctuation
    text =
      text.replace(
        Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF]"),
        " ",
      )

    // Phonetic replacements for crystal-clear Bengali pronunciation
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
