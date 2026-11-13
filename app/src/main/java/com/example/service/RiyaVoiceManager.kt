package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RiyaVoiceManager {
  private const val TAG = "RiyaVoiceManager"

  @Volatile private var tts: TextToSpeech? = null
  @Volatile private var isReady = false
  @Volatile private var currentRate = 0.94f
  @Volatile private var voiceEnabled = true
  @Volatile private var pendingSpeech: String? = null

  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

  private val _lastSpoken = MutableStateFlow("")
  val lastSpoken: StateFlow<String> = _lastSpoken.asStateFlow()

  private val _ttsStatusLabel = MutableStateFlow("প্রস্তুত হচ্ছে...")
  val ttsStatusLabel: StateFlow<String> = _ttsStatusLabel.asStateFlow()

  fun initialize(context: Context) {
    if (tts != null) return
    try {
      val appContext = context.applicationContext
      tts =
        TextToSpeech(appContext) { status ->
          if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return@TextToSpeech
            configureBengaliVoice(engine)
            isReady = true
            pendingSpeech?.let { queued ->
              pendingSpeech = null
              speak(queued, interrupt = true, forceSpeak = false)
            }
          } else {
            isReady = false
            _ttsStatusLabel.value = "ভয়েস ইঞ্জিন পাওয়া যায়নি"
            Log.e(TAG, "TextToSpeech init failed with status=$status")
          }
        }
    } catch (e: Exception) {
      isReady = false
      _ttsStatusLabel.value = "ভয়েস সেটআপে ত্রুটি"
      Log.e(TAG, "Error initializing TextToSpeech", e)
    }
  }

  private fun configureBengaliVoice(engine: TextToSpeech) {
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
      // Prefer highest quality offline/local Bengali voice if available
      val bestVoice =
        engine.voices?.firstOrNull { voice ->
          voice.locale.language == "bn" && !voice.isNetworkConnectionRequired
        } ?: engine.voices?.firstOrNull { it.locale.language == "bn" }
      if (bestVoice != null) {
        engine.voice = bestVoice
      }

      engine.setSpeechRate(currentRate)
      engine.setPitch(1.03f)
      engine.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
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
      _ttsStatusLabel.value =
        when (selectedLocale?.toLanguageTag()) {
          "bn-BD" -> "বাংলা (বাংলাদেশ) স্পষ্ট ভয়েস সক্রিয়"
          "bn-IN" -> "বাংলা (ভারত) স্পষ্ট ভয়েস সক্রিয়"
          "bn" -> "বাংলা ভয়েস সক্রিয়"
          else -> "ডিফল্ট ভয়েস সক্রিয় (বাংলা TTS প্যাক চেক করুন)"
        }
    } catch (e: Exception) {
      Log.e(TAG, "Error configuring Bengali voice", e)
    }
  }

  fun setVoiceEnabled(enabled: Boolean) {
    voiceEnabled = enabled
    if (!enabled) stopSpeaking()
  }

  fun setSpeechRate(rate: Float) {
    currentRate = rate.coerceIn(0.70f, 1.30f)
    try {
      tts?.setSpeechRate(currentRate)
    } catch (e: Exception) {
      Log.e(TAG, "Failed setting speech rate", e)
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

    val engine = tts
    if (engine == null || !isReady) {
      pendingSpeech = cleanText
      return
    }

    try {
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
        engine.speak(sentence, mode, params, "riya_${UUID.randomUUID()}")
        if (index < sentences.lastIndex) {
          engine.playSilentUtterance(160L, TextToSpeech.QUEUE_ADD, "pause_$index")
        }
      }
    } catch (e: Exception) {
      _isSpeaking.value = false
      Log.e(TAG, "Failed to speak text", e)
    }
  }

  fun stopSpeaking() {
    try {
      tts?.stop()
    } catch (e: Exception) {
      Log.e(TAG, "Failed stopping TTS", e)
    } finally {
      _isSpeaking.value = false
    }
  }

  fun shutdown() {
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
   * Normalizes text so Android's Bengali TTS articulates every word clearly without stumbling
   * over markdown characters, emojis, URLs, or mixed English brand names.
   */
  fun sanitizeForClearBengaliSpeech(input: String): String {
    var text = input
    // Never allow old wrong branding
    text = text.replace(Regex("\\bPriya\\b", RegexOption.IGNORE_CASE), "রিয়া এ আই")
    text = text.replace("প্রিয়া", "রিয়া")

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
        Regex("\\bRiya AI\\b", RegexOption.IGNORE_CASE) to "রিয়া এ আই",
        Regex("\\bRiya\\b", RegexOption.IGNORE_CASE) to "রিয়া",
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
        Regex("\\bBoss\\b", RegexOption.IGNORE_CASE) to "বস",
      )
    for ((pattern, replacement) in replacements) {
      text = text.replace(pattern, replacement)
    }

    // Collapse multiple spaces & punctuation runs
    text = text.replace(Regex("\\s+"), " ").trim()
    return text
  }
}
