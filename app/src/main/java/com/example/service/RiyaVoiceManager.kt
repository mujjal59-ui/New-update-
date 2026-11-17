package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import com.example.data.Store
import com.example.data.VoiceGender
import java.util.Locale
import java.util.UUID
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

  @Volatile private var appCtx: Context? = null
  @Volatile private var tts: TextToSpeech? = null
  @Volatile private var isReady = false
  @Volatile private var currentRate = Store.DEFAULT_VOICE_SPEED
  @Volatile private var voiceEnabled = true
  @Volatile private var pendingSpeech: String? = null
  @Volatile private var activeAudioTrack: AudioTrack? = null

  private val cloudExec = Executors.newSingleThreadExecutor()
  private val speechSeq = AtomicInteger(0)
  private val trackLock = Any()

  private val ttsHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(2500, TimeUnit.MILLISECONDS)
      .readTimeout(4500, TimeUnit.MILLISECONDS)
      .writeTimeout(3000, TimeUnit.MILLISECONDS)
      .callTimeout(7000, TimeUnit.MILLISECONDS)
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

  private val _ttsStatusLabel = MutableStateFlow("প্রস্তুত হচ্ছে...")
  val ttsStatusLabel: StateFlow<String> = _ttsStatusLabel.asStateFlow()

  fun initialize(context: Context) {
    val ctx = context.applicationContext
    appCtx = ctx
    currentRate = Store.voiceSpeed(ctx)
    updateActiveStatusLabel(ctx)
    if (tts != null) return
    try {
      tts =
        TextToSpeech(ctx) { status ->
          if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return@TextToSpeech
            configureBengaliVoice(engine, ctx)
            isReady = true
            pendingSpeech?.let { queued ->
              pendingSpeech = null
              speak(queued, interrupt = true, forceSpeak = false)
            }
          } else {
            isReady = false
            _ttsStatusLabel.value = "স্থানীয় ভয়েস ইঞ্জিন পাওয়া যায়নি"
            Log.e(TAG, "TextToSpeech init failed with status=$status")
          }
        }
    } catch (e: Exception) {
      isReady = false
      _ttsStatusLabel.value = "ভয়েস সেটআপে ত্রুটি"
      Log.e(TAG, "Error initializing TextToSpeech", e)
    }
  }

  private fun updateActiveStatusLabel(ctx: Context) {
    val voiceOpt = Store.voiceOption(ctx)
    val apiKeyPresent = Store.apiKey(ctx).isNotBlank()
    val useCloud = Store.ttsCloudEnabled(ctx) && apiKeyPresent
    _ttsStatusLabel.value =
      if (useCloud) {
        "${Store.TTS_MODEL_LABEL} • ${voiceOpt.displayName} (${voiceOpt.gender.labelBn}, bn-BD)"
      } else {
        "বাংলা (বাংলাদেশ • bn-BD) অফলাইন ভয়েস (${voiceOpt.displayName})"
      }
  }

  private fun configureBengaliVoice(engine: TextToSpeech, ctx: Context) {
    try {
      engine.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      val preferredLocales =
        listOf(
          Locale.forLanguageTag("bn-BD"),
          Locale.forLanguageTag("bn-IN"),
          Locale.forLanguageTag("bn"),
          Locale.ENGLISH,
        )
      var selectedLocale: Locale? = null
      for (locale in preferredLocales) {
        val avail = engine.isLanguageAvailable(locale)
        if (avail >= TextToSpeech.LANG_AVAILABLE) {
          engine.language = locale
          selectedLocale = locale
          break
        }
      }
      val bestVoice =
        engine.voices?.firstOrNull { voice ->
          voice.locale.language == "bn" && !voice.isNetworkConnectionRequired
        } ?: engine.voices?.firstOrNull { it.locale.language == "bn" }
      if (bestVoice != null) {
        engine.voice = bestVoice
      }

      val voiceOpt = Store.voiceOption(ctx)
      engine.setSpeechRate(currentRate)
      engine.setPitch(if (voiceOpt.gender == VoiceGender.MALE) 0.86f else 1.04f)
      engine.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            speakingStartedAtMs = System.currentTimeMillis()
            _isSpeaking.value = true
          }

          override fun onDone(utteranceId: String?) {
            _isSpeaking.value = false
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            _isSpeaking.value = false
          }

          override fun onError(utteranceId: String?, errorCode: Int) {
            _isSpeaking.value = false
          }
        }
      )
      if (!Store.ttsCloudEnabled(ctx) || Store.apiKey(ctx).isBlank()) {
        _ttsStatusLabel.value =
          when (selectedLocale?.toLanguageTag()) {
            "bn-BD" -> "বাংলা (বাংলাদেশ • bn-BD) স্পষ্ট ভয়েস সক্রিয়"
            "bn-IN" -> "বাংলা (ভারত) স্পষ্ট ভয়েস সক্রিয়"
            "bn" -> "বাংলা ভয়েস সক্রিয়"
            else -> "ডিফল্ট ভয়েস সক্রিয় (বাংলা TTS প্যাক চেক করুন)"
          }
      } else {
        updateActiveStatusLabel(ctx)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error configuring Bengali voice", e)
    }
  }

  fun refreshVoicePreferences(context: Context) {
    val ctx = context.applicationContext
    appCtx = ctx
    currentRate = Store.voiceSpeed(ctx)
    updateActiveStatusLabel(ctx)
    tts?.let { configureBengaliVoice(it, ctx) }
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
    try {
      tts?.setSpeechRate(currentRate)
    } catch (e: Exception) {
      Log.e(TAG, "Failed setting speech rate", e)
    }
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

  fun speak(rawText: String, interrupt: Boolean = true, forceSpeak: Boolean = false) {
    if (!voiceEnabled && !forceSpeak) return
    val cleanText = sanitizeForClearBengaliSpeech(rawText)
    if (cleanText.isBlank()) return
    _lastSpoken.value = cleanText

    val ctx = appCtx
    val apiKey = if (ctx != null) Store.apiKey(ctx) else ""
    val useGeminiTts = ctx != null && Store.ttsCloudEnabled(ctx) && apiKey.isNotBlank()
    val seq = speechSeq.incrementAndGet()

    if (interrupt) {
      stopActivePlaybackOnly()
    }

    if (useGeminiTts && ctx != null) {
      currentRate = Store.voiceSpeed(ctx)
      val voiceName = Store.voice(ctx)
      val stylePrompt = Store.ttsStyleInstruction(ctx)
      val isSlowNet = Store.lowNetFastMode(ctx) && isNetworkVerySlow(ctx)
      if (!isSlowNet) {
        cloudExec.execute {
          if (seq != speechSeq.get()) return@execute
          val played = trySpeakWithGeminiFlashTts(ctx, apiKey, voiceName, stylePrompt, cleanText, seq)
          if (!played && seq == speechSeq.get()) {
            speakWithLocalTts(cleanText, interrupt)
          }
        }
        return
      }
    } else if (ctx != null) {
      currentRate = Store.voiceSpeed(ctx)
    }

    speakWithLocalTts(cleanText, interrupt)
  }

  private fun trySpeakWithGeminiFlashTts(
    ctx: Context,
    apiKey: String,
    voiceName: String,
    styleInstruction: String,
    cleanText: String,
    seq: Int,
  ): Boolean {
    return try {
      val paceHint =
        when {
          currentRate <= 0.92f -> "Deliver in a calm, measured, slightly unhurried manner."
          currentRate >= 1.12f -> "Deliver briskly yet clearly."
          else -> "Deliver in a balanced, warm cadence."
        }
      val promptText =
        "$styleInstruction $paceHint Match the emotional tone of the content naturally (warm joy and light smile for happy topics, tender softness for sad stories, expressive rhythm for poems or songs). Speak in natural Bengali (Bangladesh, bn-BD): $cleanText"
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

      val req =
        Request.Builder()
          .url(
            "https://generativelanguage.googleapis.com/v1beta/models/${Store.TTS_MODEL_ID}:generateContent?key=$apiKey"
          )
          .post(payload.toString().toRequestBody("application/json".toMediaType()))
          .build()

      ttsHttpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          Log.w(TAG, "Gemini 3.1 Flash TTS HTTP ${resp.code}, using instant local bn-BD voice")
          return false
        }
        val bodyStr = resp.body?.string() ?: return false
        val root = JSONObject(bodyStr)
        val parts =
          root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return false

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
        if (b64Audio.isNullOrBlank() || seq != speechSeq.get()) return false

        val pcmBytes = Base64.decode(b64Audio, Base64.DEFAULT)
        if (pcmBytes.isEmpty()) return false
        val sampleRate = parseSampleRate(mimeType)
        _ttsStatusLabel.value = "${Store.TTS_MODEL_LABEL} • $voiceName (bn-BD)"
        playPcmAudio(pcmBytes, sampleRate, seq)
        true
      }
    } catch (e: Exception) {
      Log.w(TAG, "Gemini 3.1 Flash TTS fallback to local bn-BD: ${e.message}")
      false
    }
  }

  private fun parseSampleRate(mimeType: String): Int {
    val m = Regex("rate=(\\d+)").find(mimeType)
    return m?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8000, 48000) ?: 24000
  }

  private fun playPcmAudio(pcm: ByteArray, sampleRate: Int, seq: Int) {
    if (seq != speechSeq.get()) return
    speakingStartedAtMs = System.currentTimeMillis()
    _isSpeaking.value = true
    var track: AudioTrack? = null
    try {
      val minBuf =
        AudioTrack.getMinBufferSize(
          sampleRate,
          AudioFormat.CHANNEL_OUT_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
        )
      track =
        AudioTrack.Builder()
          .setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build()
          )
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
      synchronized(trackLock) {
        activeAudioTrack = track
        try {
          val speed = (appCtx?.let { Store.voiceSpeed(it) } ?: currentRate)
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
      Log.e(TAG, "Error playing Gemini TTS PCM audio", e)
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
      _isSpeaking.value = false
    }
  }

  private fun speakWithLocalTts(cleanText: String, interrupt: Boolean) {
    val engine = tts
    if (engine == null || !isReady) {
      pendingSpeech = cleanText
      return
    }
    try {
      appCtx?.let {
        val voiceOpt = Store.voiceOption(it)
        engine.setPitch(if (voiceOpt.gender == VoiceGender.MALE) 0.86f else 1.04f)
      }
      val sentences =
        cleanText
          .split(Regex("[।.!?\\n]+"))
          .map { it.trim() }
          .filter { it.isNotEmpty() }

      if (sentences.isEmpty()) return

      val params = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
      }

      sentences.forEachIndexed { index, sentence ->
        val mode =
          if (index == 0 && interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(sentence, mode, params, "lxr_${UUID.randomUUID()}")
        if (index < sentences.lastIndex) {
          engine.playSilentUtterance(130L, TextToSpeech.QUEUE_ADD, "pause_$index")
        }
      }
    } catch (e: Exception) {
      _isSpeaking.value = false
      Log.e(TAG, "Failed to speak text", e)
    }
  }

  private fun isNetworkVerySlow(ctx: Context): Boolean {
    return try {
      val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
      val net = cm.activeNetwork ?: return true
      val caps = cm.getNetworkCapabilities(net) ?: return true
      val downKbps = caps.linkDownstreamBandwidthKbps
      downKbps in 1..250
    } catch (_: Exception) {
      false
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
    try {
      tts?.stop()
    } catch (_: Throwable) {}
  }

  fun stopSpeaking() {
    speechSeq.incrementAndGet()
    stopActivePlaybackOnly()
    _isSpeaking.value = false
  }

  fun shutdown() {
    speechSeq.incrementAndGet()
    stopActivePlaybackOnly()
    try {
      tts?.stop()
      tts?.shutdown()
    } catch (e: Exception) {
      Log.e(TAG, "Failed shutting down TTS", e)
    } finally {
      tts = null
      isReady = false
      _isSpeaking.value = false
    }
  }

  /**
   * Normalizes text so Bengali TTS (both Gemini 3.1 Flash TTS and offline bn-BD) articulates every word clearly.
   */
  fun sanitizeForClearBengaliSpeech(input: String): String {
    var text = input
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
