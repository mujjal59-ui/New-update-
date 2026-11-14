package com.example.data

import android.content.Context
import com.example.service.GeminiLive
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

enum class VoiceGender(val labelBn: String) {
  FEMALE("মেয়ে ভয়েস"),
  MALE("ছেলের ভয়েস"),
}

data class GeminiVoiceOption(
  val id: String,
  val displayName: String,
  val gender: VoiceGender,
  val toneBn: String,
)

object RiyaState {
  val running = MutableStateFlow(false)
  val resume = MutableStateFlow(0)
  val status = MutableStateFlow("Tap to speak")
  val log = MutableStateFlow<List<String>>(emptyList())
  val lastCompletedTask = MutableStateFlow<String?>(null)
  val motivationalBanner = MutableStateFlow<String?>(null)
  val connectedAudioDevice = MutableStateFlow("Phone Mic (Whisper Boost)")
  val isBluetoothMicActive = MutableStateFlow(false)
  val networkSpeedLabel = MutableStateFlow("Fast")

  @Volatile var live: GeminiLive? = null
  @Volatile var cameraOn: Boolean = false

  fun addLog(s: String) {
    val clean =
      s.replace(Regex("\\bPriya\\b", RegexOption.IGNORE_CASE), "LXR--AI")
        .replace(Regex("\\bRiya AI\\b", RegexOption.IGNORE_CASE), "LXR--AI")
        .replace(Regex("\\bRiya\\b", RegexOption.IGNORE_CASE), "LXR--AI")
        .replace("প্রিয়া", "এল এক্স আর এ আই")
        .replace("রিয়া এআই", "এল এক্স আর এ আই")
        .replace("রিয়া", "এল এক্স আর এ আই")
    log.value = (log.value + clean).takeLast(80)
  }

  fun markTaskCompleted(summary: String, motivation: String) {
    lastCompletedTask.value = summary
    motivationalBanner.value = motivation
    addLog("LXR--AI: $summary $motivation")
  }
}

object Store {
  const val CAP = 30L * 1024 * 1024 // 30 MB memory cap
  const val DEFAULT_LIVE_MODEL = "gemini-3.1-flash-live-preview"
  const val TTS_MODEL_ID = "gemini-3.1-flash-tts-preview"
  const val TTS_MODEL_LABEL = "Gemini 3.1 Flash TTS (preview)"
  const val TTS_LOCALE_TAG = "bn-BD"
  const val TTS_LOCALE_LABEL = "Bengali (Bangladesh)"
  const val DEFAULT_TTS_STYLE = "Read aloud in a warm, welcoming tone."

  val VOICE_OPTIONS =
    listOf(
      GeminiVoiceOption("Aoede", "Aoede", VoiceGender.FEMALE, "মিষ্টি ও স্বাগতম জানানো নারী কণ্ঠ"),
      GeminiVoiceOption("Leda", "Leda", VoiceGender.FEMALE, "নরম, স্পষ্ট ও শান্ত নারী কণ্ঠ"),
      GeminiVoiceOption("Achernar", "Achernar", VoiceGender.FEMALE, "কোমল ও প্রাণবন্ত নারী কণ্ঠ"),
      GeminiVoiceOption("Sulafat", "Sulafat", VoiceGender.FEMALE, "আন্তরিক ও উষ্ণ নারী কণ্ঠ"),
      GeminiVoiceOption("Iapetus", "Iapetus", VoiceGender.MALE, "স্পষ্ট ও ভদ্র পুরুষ কণ্ঠ"),
      GeminiVoiceOption("Orus", "Orus", VoiceGender.MALE, "দৃঢ় ও পরিষ্কার পুরুষ কণ্ঠ"),
      GeminiVoiceOption("Charon", "Charon", VoiceGender.MALE, "গভীর ও শান্ত পুরুষ কণ্ঠ"),
    )

  private fun p(c: Context) = c.getSharedPreferences("lxr_ai_prefs", Context.MODE_PRIVATE)

  private fun legacyPrefs(c: Context) = c.getSharedPreferences("riya_ai", Context.MODE_PRIVATE)

  fun apiKey(c: Context): String {
    val cur = p(c).getString("key", null)
    if (!cur.isNullOrBlank()) return cur
    return legacyPrefs(c).getString("key", "") ?: ""
  }

  fun setApiKey(c: Context, v: String) = p(c).edit().putString("key", v.trim()).apply()

  fun model(c: Context): String {
    val m = p(c).getString("model", null) ?: legacyPrefs(c).getString("model", DEFAULT_LIVE_MODEL) ?: DEFAULT_LIVE_MODEL
    return if (m.contains("2.0-flash")) DEFAULT_LIVE_MODEL else m
  }

  fun setModel(c: Context, v: String) =
    p(c).edit().putString("model", v.trim().ifBlank { DEFAULT_LIVE_MODEL }).apply()

  fun voice(c: Context): String {
    val raw = p(c).getString("voice", null) ?: legacyPrefs(c).getString("voice", "Aoede") ?: "Aoede"
    return if (VOICE_OPTIONS.any { it.id.equals(raw, ignoreCase = true) }) raw else "Aoede"
  }

  fun setVoice(c: Context, v: String) = p(c).edit().putString("voice", v.trim().ifBlank { "Aoede" }).apply()

  fun voiceOption(c: Context): GeminiVoiceOption {
    val id = voice(c)
    return VOICE_OPTIONS.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: VOICE_OPTIONS.first()
  }

  fun ttsCloudEnabled(c: Context): Boolean = p(c).getBoolean("ttsCloudEnabled", true)

  fun setTtsCloudEnabled(c: Context, v: Boolean) =
    p(c).edit().putBoolean("ttsCloudEnabled", v).apply()

  fun ttsStyleInstruction(c: Context): String =
    p(c).getString("ttsStyle", DEFAULT_TTS_STYLE)?.ifBlank { DEFAULT_TTS_STYLE } ?: DEFAULT_TTS_STYLE

  fun setTtsStyleInstruction(c: Context, v: String) =
    p(c).edit().putString("ttsStyle", v.trim().ifBlank { DEFAULT_TTS_STYLE }).apply()

  fun whisperBoost(c: Context): Boolean = p(c).getBoolean("whisperBoost", true)

  fun setWhisperBoost(c: Context, v: Boolean) = p(c).edit().putBoolean("whisperBoost", v).apply()

  fun lowNetFastMode(c: Context): Boolean = p(c).getBoolean("lowNetFast", true)

  fun setLowNetFastMode(c: Context, v: Boolean) = p(c).edit().putBoolean("lowNetFast", v).apply()

  // Screen Watch সবসময় চালু (বাধ্যতামূলক)
  fun screenWatch(c: Context): Boolean = true

  fun setScreenWatch(c: Context, v: Boolean) {}

  fun autoReplyMaster(c: Context): Boolean = p(c).getBoolean("arMaster", true)

  fun setAutoReplyMaster(c: Context, v: Boolean) = p(c).edit().putBoolean("arMaster", v).apply()

  fun requireBossPermission(c: Context): Boolean = p(c).getBoolean("arAskBoss", true)

  fun setRequireBossPermission(c: Context, v: Boolean) = p(c).edit().putBoolean("arAskBoss", v).apply()

  fun autoReplyWhatsApp(c: Context): Boolean = p(c).getBoolean("arWa", true)

  fun setAutoReplyWhatsApp(c: Context, v: Boolean) = p(c).edit().putBoolean("arWa", v).apply()

  fun autoReplyImo(c: Context): Boolean = p(c).getBoolean("arImo", true)

  fun setAutoReplyImo(c: Context, v: Boolean) = p(c).edit().putBoolean("arImo", v).apply()

  fun autoReplyMessenger(c: Context): Boolean = p(c).getBoolean("arMsg", true)

  fun setAutoReplyMessenger(c: Context, v: Boolean) = p(c).edit().putBoolean("arMsg", v).apply()

  fun design(c: Context): Int = p(c).getInt("design", 0)

  fun setDesign(c: Context, v: Int) = p(c).edit().putInt("design", v).apply()

  fun always(c: Context): Boolean = p(c).getBoolean("always", false)

  fun setAlways(c: Context, v: Boolean) = p(c).edit().putBoolean("always", v).apply()

  fun oemDone(c: Context): Boolean =
    p(c).getBoolean("oemDone", false) || legacyPrefs(c).getBoolean("oemDone", false)

  fun setOemDone(c: Context, v: Boolean) = p(c).edit().putBoolean("oemDone", v).apply()

  private fun f(c: Context) = File(c.filesDir, "memory.txt")

  fun size(c: Context): Long = f(c).let { if (it.exists()) it.length() else 0L }

  fun clear(c: Context) {
    f(c).delete()
  }

  @Synchronized
  fun remember(c: Context, line: String) {
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    val file = f(c)
    val sanitized =
      line
        .replace(Regex("\\bPriya\\b", RegexOption.IGNORE_CASE), "LXR--AI")
        .replace(Regex("\\bRiya AI\\b", RegexOption.IGNORE_CASE), "LXR--AI")
        .replace("প্রিয়া", "এল এক্স আর এ আই")
        .replace("রিয়া এআই", "এল এক্স আর এ আই")
        .replace("রিয়া", "এল এক্স আর এ আই")
    file.appendText("[$ts] ${sanitized.replace("\n", " ")}\n")
    if (file.length() > CAP) trim(file)
  }

  private fun trim(file: File) {
    val keep = (CAP * 0.8).toLong()
    val rest =
      RandomAccessFile(file, "r").use { r ->
        r.seek(file.length() - keep)
        r.readLine()
        ByteArray((file.length() - r.filePointer).toInt()).also { r.readFully(it) }
      }
    file.writeBytes(rest)
  }

  fun recent(c: Context, max: Int = 20000): String {
    val file = f(c)
    if (!file.exists()) return ""
    return RandomAccessFile(file, "r").use { r ->
      val start = maxOf(0L, r.length() - max)
      r.seek(start)
      ByteArray((r.length() - start).toInt()).also { r.readFully(it) }.toString(Charsets.UTF_8)
    }
  }
}
