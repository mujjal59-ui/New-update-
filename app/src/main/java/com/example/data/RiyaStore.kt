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

enum class VoiceMatchSensitivity(
  val labelBn: String,
  val threshold: Float,
  val mfccFloor: Float,
  val pitchWeight: Float,
  val pitchSigma: Float,
  val centroidWeight: Float,
  val minStepFloor: Float,
  val onsetMinChunks: Int,
  val rejectAfterChunks: Int,
  val descriptionBn: String,
) {
  EASY(
    labelBn = "সহজ",
    threshold = 0.44f,
    mfccFloor = 0.36f,
    pitchWeight = 0.05f,
    pitchSigma = 0.55f,
    centroidWeight = 0.03f,
    minStepFloor = 0.25f,
    onsetMinChunks = 3,
    rejectAfterChunks = 10,
    descriptionBn = "আস্তে, ফিসফিস বা যেকোনো ভঙ্গিতে বসের কণ্ঠ দ্রুত ধরবে",
  ),
  BALANCED(
    labelBn = "মাঝারি",
    threshold = 0.53f,
    mfccFloor = 0.46f,
    pitchWeight = 0.16f,
    pitchSigma = 0.30f,
    centroidWeight = 0.08f,
    minStepFloor = 0.38f,
    onsetMinChunks = 4,
    rejectAfterChunks = 8,
    descriptionBn = "স্মার্ট ভারসাম্য — আস্তে ও জোরে উভয় কণ্ঠে বসের ভয়েস চিনবে",
  ),
  STRICT(
    labelBn = "কঠোর",
    threshold = 0.63f,
    mfccFloor = 0.56f,
    pitchWeight = 0.24f,
    pitchSigma = 0.20f,
    centroidWeight = 0.12f,
    minStepFloor = 0.50f,
    onsetMinChunks = 6,
    rejectAfterChunks = 7,
    descriptionBn = "কঠোর বায়োমেট্রিক — শুধু ৩ ধাপের বসের কণ্ঠের সাথে হুবহু মিললে চলবে",
  ),
}

enum class ScreenLockType(val labelBn: String) {
  NONE("লক নেই"),
  PIN("৮ ডিজিট PIN / পাসওয়ার্ড"),
  PATTERN("প্যাটার্ন লক (Pattern)"),
}

enum class DeviceBrandProfile(val labelBn: String) {
  AUTO("অটো-ডিটেক্ট"),
  INFINIX("Infinix"),
  XIAOMI_REDMI("Xiaomi / Redmi"),
  REALME("Realme"),
  TECNO("Tecno"),
  ITEL("itel"),
  OTHER("অন্যান্য"),
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
  val voiceMatchLiveLabel = MutableStateFlow("")

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
  const val DEFAULT_TTS_STYLE =
    "Speak calmly, warmly, and at a slightly slower, unhurried pace with crystal-clear Bengali pronunciation."
  const val DEFAULT_VOICE_SPEED = 0.88f
  const val MIN_VOICE_SPEED = 0.75f
  const val MAX_VOICE_SPEED = 1.35f
  const val MIC_TAP_GREETING_BN =
    "আসসালামু ওয়ালাইকুম... কেমন আছেন আমার প্রাণ প্রিয় বস, বলুন কি করতে হবে।"
  const val WAKE_CALL_GREETING_BN = "বস ❤️ কেনো ডেকেছেন বলুন কি করতে হবে"

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

  fun voiceSpeed(c: Context): Float =
    p(c).getFloat("voiceSpeed", DEFAULT_VOICE_SPEED).coerceIn(MIN_VOICE_SPEED, MAX_VOICE_SPEED)

  fun setVoiceSpeed(c: Context, speed: Float) =
    p(c).edit().putFloat("voiceSpeed", speed.coerceIn(MIN_VOICE_SPEED, MAX_VOICE_SPEED)).apply()

  fun voiceSpeedLabelBn(speed: Float): String {
    val s = speed.coerceIn(MIN_VOICE_SPEED, MAX_VOICE_SPEED)
    val tag =
      when {
        s <= 0.82f -> "ধীর ও স্পষ্ট"
        s <= 0.93f -> "শান্ত • সামান্য ধীর (ডিফল্ট)"
        s <= 1.05f -> "স্বাভাবিক গতি"
        s <= 1.20f -> "দ্রুত"
        else -> "খুব দ্রুত"
      }
    return String.format(Locale.US, "%.2fx • %s", s, tag)
  }

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

  val VOICE_MATCH_PHRASES =
    listOf(
      "তোমার নাম LxR",
      "তুমি আমার পার্সোনাল অ্যাসিস্ট্যান্ট LxR",
      "LxR এখন থেকে আমি তোমার বস",
    )

  fun voiceMatchEnabled(c: Context): Boolean = p(c).getBoolean("vmEnabled", true)

  fun setVoiceMatchEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("vmEnabled", v).apply()

  fun voiceMatchSensitivity(c: Context): VoiceMatchSensitivity {
    val raw = p(c).getString("vmSensitivity", VoiceMatchSensitivity.BALANCED.name)
    return VoiceMatchSensitivity.entries.firstOrNull { it.name == raw } ?: VoiceMatchSensitivity.BALANCED
  }

  fun setVoiceMatchSensitivity(c: Context, s: VoiceMatchSensitivity) =
    p(c).edit().putString("vmSensitivity", s.name).apply()

  fun voiceMatchStepJson(c: Context, step: Int): String =
    p(c).getString("vmStep_$step", "") ?: ""

  fun setVoiceMatchStepJson(c: Context, step: Int, json: String) {
    p(c).edit().putString("vmStep_$step", json).commit()
  }

  fun clearVoiceMatch(c: Context) {
    p(c).edit()
      .remove("vmStep_1")
      .remove("vmStep_2")
      .remove("vmStep_3")
      .commit()
  }

  fun floatingOrbEnabled(c: Context): Boolean = p(c).getBoolean("floatingOrbEnabled", true)

  fun setFloatingOrbEnabled(c: Context, v: Boolean) =
    p(c).edit().putBoolean("floatingOrbEnabled", v).apply()

  fun deviceBrandProfile(c: Context): DeviceBrandProfile {
    val raw = p(c).getString("lockBrand", DeviceBrandProfile.AUTO.name)
    return DeviceBrandProfile.entries.firstOrNull { it.name == raw } ?: DeviceBrandProfile.AUTO
  }

  fun setDeviceBrandProfile(c: Context, brand: DeviceBrandProfile) =
    p(c).edit().putString("lockBrand", brand.name).apply()

  fun phoneLockType(c: Context): ScreenLockType {
    val raw = p(c).getString("phoneLockType", ScreenLockType.NONE.name)
    return ScreenLockType.entries.firstOrNull { it.name == raw } ?: ScreenLockType.NONE
  }

  fun setPhoneLockType(c: Context, type: ScreenLockType) =
    p(c).edit().putString("phoneLockType", type.name).apply()

  fun phonePin(c: Context): String = p(c).getString("phonePin", "") ?: ""

  fun setPhonePin(c: Context, pin: String) =
    p(c).edit().putString("phonePin", pin.trim().take(8)).apply()

  fun phonePattern(c: Context): String = p(c).getString("phonePattern", "") ?: ""

  fun setPhonePattern(c: Context, dots: String) =
    p(c).edit().putString("phonePattern", dots.filter { it in '1'..'9' }.take(9)).apply()

  fun appLockSameAsPhone(c: Context): Boolean = p(c).getBoolean("appLockSame", true)

  fun setAppLockSameAsPhone(c: Context, same: Boolean) =
    p(c).edit().putBoolean("appLockSame", same).apply()

  fun appLockType(c: Context): ScreenLockType {
    val raw = p(c).getString("appLockType", ScreenLockType.NONE.name)
    return ScreenLockType.entries.firstOrNull { it.name == raw } ?: ScreenLockType.NONE
  }

  fun setAppLockType(c: Context, type: ScreenLockType) =
    p(c).edit().putString("appLockType", type.name).apply()

  fun appPin(c: Context): String = p(c).getString("appPin", "") ?: ""

  fun setAppPin(c: Context, pin: String) =
    p(c).edit().putString("appPin", pin.trim().take(8)).apply()

  fun appPattern(c: Context): String = p(c).getString("appPattern", "") ?: ""

  fun setAppPattern(c: Context, dots: String) =
    p(c).edit().putString("appPattern", dots.filter { it in '1'..'9' }.take(9)).apply()

  fun autoUnlockEnabled(c: Context): Boolean = p(c).getBoolean("autoUnlockEnabled", true)

  fun setAutoUnlockEnabled(c: Context, enabled: Boolean) =
    p(c).edit().putBoolean("autoUnlockEnabled", enabled).apply()

  fun hasAnyPhoneLockSaved(c: Context): Boolean =
    when (phoneLockType(c)) {
      ScreenLockType.NONE -> false
      ScreenLockType.PIN -> phonePin(c).isNotBlank()
      ScreenLockType.PATTERN -> phonePattern(c).length >= 4
    }

  fun hasAnyAppLockSaved(c: Context): Boolean =
    if (appLockSameAsPhone(c)) hasAnyPhoneLockSaved(c)
    else
      when (appLockType(c)) {
        ScreenLockType.NONE -> false
        ScreenLockType.PIN -> appPin(c).isNotBlank()
        ScreenLockType.PATTERN -> appPattern(c).length >= 4
      }

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
