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

enum class HomeTheme(
  val id: Int,
  val titleBn: String,
  val subtitleBn: String,
) {
  LXR_STUDIO(
    id = 0,
    titleBn = "LXR AI মাস্টার স্টুডিও",
    subtitleBn = "আসল LXR--AI UI স্টাইল, চোখের পলক, L X.R গ্লোব ও ফুলের পাপড়ি",
  ),
  ARIA_CYBER_ROOM(
    id = 1,
    titleBn = "Aria সাইবার AI রুম",
    subtitleBn = "Aria অ্যানিমেটেড চরিত্র, চোখের পলক, L X.R গ্লোব ও ফুলের পাপড়ি",
  ),
  MITALI_CYBER_STUDIO(
    id = 2,
    titleBn = "মীতালী AI স্টুডিও",
    subtitleBn = "মীতালী অ্যানিমেটেড চরিত্র, চোখের পলক, সাইবার রুম ও ফুলের পাপড়ি",
  ),
  ;

  companion object {
    fun fromId(id: Int): HomeTheme = entries.firstOrNull { it.id == id } ?: LXR_STUDIO
  }
}

enum class ReplyDelayOption(
  val seconds: Int,
  val optionLabel: String,
  val selectedLabel: String,
) {
  INSTANT(0, "Instant — 00.0 sec (Default)", "Selected: Instant (00.0 sec)"),
  FAST(5, "Fast — 00.5 sec", "Selected: Fast (00.5 sec)"),
  NORMAL(10, "Normal — 00.10 sec", "Selected: Normal (00.10 sec)"),
  ;

  companion object {
    fun fromSeconds(sec: Int): ReplyDelayOption =
      when (sec) {
        0 -> INSTANT
        5, 8 -> FAST
        10, 15 -> NORMAL
        else -> entries.firstOrNull { it.seconds == sec } ?: INSTANT
      }
  }
}

enum class ReplyLimitOption(
  val maxReplies: Int,
  val optionLabel: String,
  val selectedLabel: String,
) {
  UNLIMITED(-1, "Unlimited — Default", "Selected: Unlimited"),
  LIMIT_20(20, "20 Replies", "Selected: 20 Replies"),
  LIMIT_50(50, "50 Replies", "Selected: 50 Replies"),
  ;

  companion object {
    fun fromMaxReplies(limit: Int): ReplyLimitOption =
      entries.firstOrNull { it.maxReplies == limit } ?: UNLIMITED
  }
}

data class LiveVoiceModelOption(
  val id: String,
  val title: String,
  val modelId: String,
  val badge: String,
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
  val homeThemeIndex = MutableStateFlow(0)
  val liveModelId = MutableStateFlow(Store.DEFAULT_LIVE_MODEL)
  val replyDelaySeconds = MutableStateFlow(0)
  val replyLimitMax = MutableStateFlow(-1)

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

  val LIVE_VOICE_MODEL_OPTIONS =
    listOf(
      LiveVoiceModelOption(
        id = "gemini_3_8_live",
        title = "Gemini 3.8 Live",
        modelId = "gemini-3.8-live",
        badge = "New",
      ),
      LiveVoiceModelOption(
        id = "gemini_3_1_flash_live",
        title = "Gemini 3.1 Flash Live (preview)",
        modelId = "gemini-3.1-flash-live-preview",
        badge = "Recommended",
      ),
      LiveVoiceModelOption(
        id = "gemini_2_5_flash_native",
        title = "Gemini 2.5 Flash Native Audio (Dec 2025)",
        modelId = "gemini-2.5-flash-native-audio-preview-12-2025",
        badge = "Stable",
      ),
    )

  fun liveVoiceModelById(modelId: String): LiveVoiceModelOption {
    val clean = modelId.trim()
    return LIVE_VOICE_MODEL_OPTIONS.firstOrNull {
      it.modelId.equals(clean, ignoreCase = true) || it.title.equals(clean, ignoreCase = true)
    } ?: when {
      clean.contains("3.8") -> LIVE_VOICE_MODEL_OPTIONS[0]
      clean.contains("2.5") -> LIVE_VOICE_MODEL_OPTIONS[2]
      else -> LIVE_VOICE_MODEL_OPTIONS[1]
    }
  }

  private fun p(c: Context) = c.getSharedPreferences("lxr_ai_prefs", Context.MODE_PRIVATE)

  private fun legacyPrefs(c: Context) = c.getSharedPreferences("riya_ai", Context.MODE_PRIVATE)

  fun apiKey(c: Context): String {
    val cur = p(c).getString("key", null)
    if (!cur.isNullOrBlank()) return cur
    return legacyPrefs(c).getString("key", "") ?: ""
  }

  fun setApiKey(c: Context, v: String) = p(c).edit().putString("key", v.trim()).apply()

  fun perplexityApiKey(c: Context): String = p(c).getString("perplexityKey", "") ?: ""

  fun setPerplexityApiKey(c: Context, v: String) = p(c).edit().putString("perplexityKey", v.trim()).apply()

  fun youApiKey(c: Context): String = p(c).getString("youKey", "") ?: ""

  fun setYouApiKey(c: Context, v: String) = p(c).edit().putString("youKey", v.trim()).apply()

  fun model(c: Context): String {
    val m = p(c).getString("model", null) ?: legacyPrefs(c).getString("model", DEFAULT_LIVE_MODEL) ?: DEFAULT_LIVE_MODEL
    return if (m.contains("2.0-flash")) DEFAULT_LIVE_MODEL else m
  }

  fun setModel(c: Context, v: String) {
    val clean = v.trim().ifBlank { DEFAULT_LIVE_MODEL }
    p(c).edit().putString("model", clean).commit()
    RiyaState.liveModelId.value = clean
  }

  fun liveVoiceModelOption(c: Context): LiveVoiceModelOption = liveVoiceModelById(model(c))

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

  fun design(c: Context): Int = p(c).getInt("design", 0).coerceIn(0, HomeTheme.entries.lastIndex)

  fun setDesign(c: Context, v: Int) {
    val clamped = v.coerceIn(0, HomeTheme.entries.lastIndex)
    p(c).edit().putInt("design", clamped).commit()
    RiyaState.homeThemeIndex.value = clamped
  }

  fun homeTheme(c: Context): HomeTheme = HomeTheme.fromId(design(c))

  fun setHomeTheme(c: Context, theme: HomeTheme) = setDesign(c, theme.id)

  fun replyDelaySeconds(c: Context): Int =
    ReplyDelayOption.fromSeconds(p(c).getInt("replyDelaySec", 0)).seconds

  fun replyDelayOption(c: Context): ReplyDelayOption =
    ReplyDelayOption.fromSeconds(replyDelaySeconds(c))

  fun setReplyDelaySeconds(c: Context, seconds: Int) {
    val valid = ReplyDelayOption.fromSeconds(seconds).seconds
    p(c).edit().putInt("replyDelaySec", valid).commit()
    RiyaState.replyDelaySeconds.value = valid
  }

  fun replyLimitMax(c: Context): Int =
    ReplyLimitOption.fromMaxReplies(p(c).getInt("replyLimitMax", -1)).maxReplies

  fun replyLimitOption(c: Context): ReplyLimitOption =
    ReplyLimitOption.fromMaxReplies(replyLimitMax(c))

  fun setReplyLimitMax(c: Context, limit: Int) {
    val valid = ReplyLimitOption.fromMaxReplies(limit).maxReplies
    p(c).edit().putInt("replyLimitMax", valid).commit()
    RiyaState.replyLimitMax.value = valid
  }

  fun replyDispatchedCount(c: Context): Int =
    p(c).getInt("replyDispatchedCount", 0).coerceAtLeast(0)

  fun setReplyDispatchedCount(c: Context, count: Int) {
    p(c).edit().putInt("replyDispatchedCount", count.coerceAtLeast(0)).commit()
  }

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

  const val RETENTION_DAYS = 30
  private const val MILLIS_PER_DAY = 86_400_000L
  private val TS_LINE_REGEX = Regex("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})]\\s*(.*)$")
  @Volatile private var writeCountSincePrune = 0

  private fun f(c: Context) = File(c.filesDir, "memory.txt")

  fun size(c: Context): Long = f(c).let { if (it.exists()) it.length() else 0L }

  fun clear(c: Context) {
    f(c).delete()
  }

  private fun sanitizeLine(line: String): String =
    line
      .replace(Regex("\\bPriya\\b", RegexOption.IGNORE_CASE), "LXR--AI")
      .replace(Regex("\\bRiya AI\\b", RegexOption.IGNORE_CASE), "LXR--AI")
      .replace("প্রিয়া", "এল এক্স আর এ আই")
      .replace("রিয়া এআই", "এল এক্স আর এ আই")
      .replace("রিয়া", "এল এক্স আর এ আই")
      .replace("\n", " ")
      .trim()

  @Synchronized
  fun remember(c: Context, line: String) {
    rememberAt(c, line, System.currentTimeMillis())
  }

  @Synchronized
  fun rememberAt(c: Context, line: String, timestampMs: Long) {
    val clean = sanitizeLine(line)
    if (clean.isBlank()) return
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestampMs))
    val file = f(c)
    file.appendText("[$ts] $clean\n")
    writeCountSincePrune++
    if (writeCountSincePrune >= 50) {
      writeCountSincePrune = 0
      pruneOlderThanDays(c, RETENTION_DAYS, timestampMs)
    }
    if (file.length() > CAP) trim(file)
  }

  private fun parseLineTimestampMs(line: String): Long? {
    val m = TS_LINE_REGEX.matchEntire(line) ?: return null
    return try {
      SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(m.groupValues[1])?.time
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Prunes conversation entries older than [days] days (default 30 days) while keeping the
   * entire rolling 1-month history intact on disk.
   */
  @Synchronized
  fun pruneOlderThanDays(
    c: Context,
    days: Int = RETENTION_DAYS,
    nowMs: Long = System.currentTimeMillis(),
  ): Int {
    val file = f(c)
    if (!file.exists()) return 0
    val cutoffMs = nowMs - (days.coerceAtLeast(1).toLong() * MILLIS_PER_DAY)
    val allLines = file.readLines(Charsets.UTF_8)
    val kept = ArrayList<String>(allLines.size)
    var removed = 0
    for (line in allLines) {
      if (line.isBlank()) continue
      val tsMs = parseLineTimestampMs(line)
      if (tsMs != null && tsMs < cutoffMs) {
        removed++
      } else {
        kept.add(line)
      }
    }
    if (removed > 0) {
      file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n", Charsets.UTF_8)
    }
    return removed
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

  @Synchronized
  fun recent(c: Context, max: Int = 20000): String {
    val file = f(c)
    if (!file.exists()) return ""
    return RandomAccessFile(file, "r").use { r ->
      val start = maxOf(0L, r.length() - max)
      r.seek(start)
      if (start > 0L) {
        // Advance past partial UTF-8/line fragment so Bengali characters never tear
        r.readLine()
      }
      val rem = (r.length() - r.filePointer).coerceAtLeast(0L).toInt()
      ByteArray(rem).also { r.readFully(it) }.toString(Charsets.UTF_8)
    }
  }

  /**
   * Builds a compact day-by-day awareness digest across the past [days] days so LXR--AI knows
   * events from 1 day to 30 days ago right at session startup.
   */
  @Synchronized
  fun build30DayDigest(
    c: Context,
    maxChars: Int = 4500,
    days: Int = RETENTION_DAYS,
    nowMs: Long = System.currentTimeMillis(),
  ): String {
    val file = f(c)
    if (!file.exists()) return ""
    val cutoffMs = nowMs - (days.toLong() * MILLIS_PER_DAY)
    val lines = file.readLines(Charsets.UTF_8).filter { line ->
      if (line.isBlank()) false
      else {
        val ts = parseLineTimestampMs(line)
        ts == null || ts >= cutoffMs
      }
    }
    if (lines.isEmpty()) return ""

    // Pick FACT lines + representative per-day highlights prior to the latest tail
    val groupedByDay = LinkedHashMap<String, MutableList<String>>()
    for (line in lines) {
      val day =
        if (line.length >= 11 && line.startsWith("[")) line.substring(1, 11) else "তারিখ-অজানা"
      groupedByDay.getOrPut(day) { mutableListOf() }.add(line)
    }
    val sb = StringBuilder()
    val dayKeys = groupedByDay.keys.toList()
    for (day in dayKeys) {
      val dayLines = groupedByDay[day].orEmpty()
      val factOrBossLines =
        dayLines.filter { it.contains("FACT:") || it.contains("Boss") || it.contains("[WhatsApp]") || it.contains("[imo]") || it.contains("[Messenger]") }
      val chosen =
        if (factOrBossLines.isNotEmpty()) {
          listOfNotNull(factOrBossLines.firstOrNull(), factOrBossLines.lastOrNull()).distinct()
        } else {
          listOfNotNull(dayLines.firstOrNull())
        }
      for (entry in chosen) {
        val compact = entry.take(150)
        if (sb.length + compact.length + 2 > maxChars) break
        sb.appendLine(compact)
      }
      if (sb.length >= maxChars) break
    }
    return sb.toString().trim()
  }

  /**
   * Searches the entire rolling 30-day memory log using small clues, keywords, names, or dates.
   * Returns matched events with +/- 2 surrounding conversation lines for full context.
   */
  @Synchronized
  fun searchMemory(
    c: Context,
    query: String,
    maxDays: Int = RETENTION_DAYS,
    maxMatchedCenters: Int = 18,
    nowMs: Long = System.currentTimeMillis(),
  ): String {
    val file = f(c)
    if (!file.exists()) return "মেমোরিতে এখনো কোনো তথ্য সংরক্ষিত নেই বস।"
    val qClean = query.trim().lowercase()
    val cutoffMs = nowMs - (maxDays.coerceAtLeast(1).toLong() * MILLIS_PER_DAY)
    val allLines =
      file.readLines(Charsets.UTF_8).filter { line ->
        if (line.isBlank()) false
        else {
          val ts = parseLineTimestampMs(line)
          ts == null || ts >= cutoffMs
        }
      }
    if (allLines.isEmpty()) return "গত $maxDays দিনের মধ্যে মেমোরিতে কোনো কথোপকথন পাওয়া যায়নি বস।"

    if (qClean.isBlank()) {
      return allLines.takeLast(25).joinToString("\n")
    }

    val stopWords =
      setOf(
        "কি", "কী", "করে", "বলেছিলাম", "বলেছ", "আগে", "দিন", "আগেকার", "মনে", "আছে",
        "কথা", "বিষয়ে", "সম্পর্কে", "ওই", "সেই", "একটা", "কিছু", "ছিল", "বস", "তুমি",
        "আমি", "একটু", "বলো", "the", "a", "an", "is", "was", "what", "when", "about", "remember",
      )
    val rawTokens =
      qClean
        .split(Regex("[\\s,।?!.;:\"'()\\[\\]{}/\\\\]+"))
        .map { it.trim() }
        .filter { it.length >= 2 }
    val clueTokens = rawTokens.filterNot { it in stopWords }.ifEmpty { rawTokens }

    // Support relative date clues ("গতকাল", "এক সপ্তাহ", "কয়দিন")
    val targetDaysBack =
      when {
        qClean.contains("গতকাল") || qClean.contains("yesterday") -> 1L..2L
        qClean.contains("পরশু") -> 2L..3L
        qClean.contains("সপ্তাহ") || qClean.contains("week") -> 4L..10L
        qClean.contains("১৫ দিন") || qClean.contains("দুই সপ্তাহ") -> 10L..20L
        qClean.contains("মাস") || qClean.contains("month") -> 0L..30L
        else -> null
      }

    data class ScoredIdx(val idx: Int, val score: Int)
    val scored = mutableListOf<ScoredIdx>()
    for (i in allLines.indices) {
      val line = allLines[i]
      val lower = line.lowercase()
      var score = 0
      if (lower.contains(qClean)) score += 12
      for (tok in clueTokens) {
        if (lower.contains(tok)) score += if (tok.length >= 4) 5 else 3
      }
      if (line.contains("FACT:")) score += 1
      if (targetDaysBack != null) {
        val ts = parseLineTimestampMs(line)
        if (ts != null) {
          val ageDays = (nowMs - ts) / MILLIS_PER_DAY
          if (ageDays in targetDaysBack) score += 4
        }
      }
      if (score >= 3) {
        scored.add(ScoredIdx(i, score))
      }
    }

    if (scored.isEmpty()) {
      val fallbackTail = allLines.takeLast(12).joinToString("\n")
      return "গত $maxDays দিনের মেমোরিতে '$query' সম্পর্কিত সরাসরি মিল পাওয়া যায়নি। সাম্প্রতিক তথ্য:\n$fallbackTail"
    }

    val topCenters =
      scored
        .sortedWith(compareByDescending<ScoredIdx> { it.score }.thenByDescending { it.idx })
        .take(maxMatchedCenters)
        .map { it.idx }

    val selectedIndices = sortedSetOf<Int>()
    for (center in topCenters) {
      val start = (center - 1).coerceAtLeast(0)
      val end = (center + 2).coerceAtMost(allLines.lastIndex)
      for (j in start..end) selectedIndices.add(j)
    }

    val sb = StringBuilder()
    sb.appendLine("গত $maxDays দিনের মেমোরি থেকে '$query' সূত্রের ফলাফল:")
    for (idx in selectedIndices) {
      val line = allLines[idx]
      if (sb.length + line.length + 2 > 5500) break
      sb.appendLine(line)
    }
    return sb.toString().trim()
  }
}
