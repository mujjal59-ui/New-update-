package com.example.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Base64
import com.example.MainActivity
import com.example.ai.RiyaPersona
import com.example.builder.AppBuilderRuntimeBridge
import com.example.data.ReplyDelayOption
import com.example.data.RiyaState
import com.example.data.Store
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

data class PendingIncomingMessage(
  val appType: String,
  val appNameBn: String,
  val senderName: String,
  val messageText: String,
  val packageName: String,
  val suggestedDraft: String = "",
)

object LiveVadConfig {
  const val START_SENSITIVITY = "START_SENSITIVITY_HIGH"
  const val END_SENSITIVITY = "END_SENSITIVITY_LOW"
  const val PREFIX_PADDING_MS = 16
  // Balanced anti-cutoff constants (verified in tests) + dynamic ReplyDelayOption speed profiles
  const val SILENCE_DURATION_MS = 700
  const val MIC_HANGOVER_MS = 280L
  const val END_OF_TURN_FLUSH_MS = 240L

  fun effectiveSilenceDurationMs(delayOption: ReplyDelayOption): Int =
    when (delayOption) {
      ReplyDelayOption.INSTANT -> 360
      ReplyDelayOption.FAST -> 540
      ReplyDelayOption.NORMAL -> SILENCE_DURATION_MS
    }

  fun effectiveEndOfTurnFlushMs(delayOption: ReplyDelayOption): Long =
    when (delayOption) {
      ReplyDelayOption.INSTANT -> 90L
      ReplyDelayOption.FAST -> 140L
      ReplyDelayOption.NORMAL -> 220L
    }

  fun effectiveHangoverMs(delayOption: ReplyDelayOption): Long =
    when (delayOption) {
      ReplyDelayOption.INSTANT -> 180L
      ReplyDelayOption.FAST -> 220L
      ReplyDelayOption.NORMAL -> 260L
    }

  fun buildRealtimeInputConfigJson(silenceMs: Int = SILENCE_DURATION_MS): JSONObject =
    JSONObject().put(
      "automaticActivityDetection",
      JSONObject()
        .put("disabled", false)
        .put("startOfSpeechSensitivity", START_SENSITIVITY)
        .put("endOfSpeechSensitivity", END_SENSITIVITY)
        .put("prefixPaddingMs", PREFIX_PADDING_MS)
        .put("silenceDurationMs", silenceMs),
    )
}

/** Compact Bengali indicator for the in-app status pill & floating LxR Orb while LXR--AI executes a task. */
object LxrToolWorkDescriber {
  private val SCREEN_READING_TOOLS =
    setOf(
      "read_screen",
      "current_screen",
      "inspect_contact_chat",
      "inspect_form_fields",
      "open_app",
      "send_message",
      "click_text",
      "find_and_open_item",
      "type_text",
      "scroll",
      "facebook_action",
      "instagram_action",
      "check_notifications",
      "global_action",
      "fill_form_fields",
    )

  fun isScreenReadingTool(toolName: String): Boolean = toolName in SCREEN_READING_TOOLS

  fun describeBn(toolName: String, args: JSONObject = JSONObject()): String {
    val task =
      when (toolName) {
        "open_app" -> "${args.optString("app_name").ifBlank { "অ্যাপ" }} খোলা হচ্ছে..."
        "send_message" -> "${args.optString("app_name").ifBlank { "মেসেজ" }} পাঠানো হচ্ছে..."
        "inspect_contact_chat" -> "${args.optString("contact_name").ifBlank { "চ্যাট" }} দেখা হচ্ছে..."
        "read_screen", "current_screen" -> "স্ক্রিন পড়া হচ্ছে..."
        "click_text", "find_and_open_item" -> "${args.optString("text", args.optString("name")).ifBlank { "বাটন" }} ট্যাপ..."
        "type_text" -> "লেখা টাইপ হচ্ছে..."
        "global_action" ->
          when (args.optString("action").lowercase()) {
            "home" -> "হোম স্ক্রিনে যাওয়া..."
            "back" -> "পেছনে ফেরা..."
            "notifications" -> "নোটিফিকেশন দেখা..."
            else -> "নেভিগেশন চলছে..."
          }
        "scroll" -> "স্ক্রিন স্ক্রল..."
        "control_device_setting" ->
          when (args.optString("setting_name").lowercase()) {
            "location", "gps", "লোকেশন", "জিপিএস" -> "GPS / Location..."
            "wifi", "ওয়াইফাই" -> "Wi-Fi..."
            "bluetooth", "ব্লুটুথ" -> "Bluetooth..."
            "flashlight", "torch" -> "ফ্ল্যাশলাইট..."
            else -> "সেটিংস নিয়ন্ত্রণ..."
          }
        "control_wifi" -> "Wi-Fi..."
        "control_bluetooth" -> "Bluetooth..."
        "toggle_flashlight" -> "ফ্ল্যাশলাইট..."
        "get_live_location", "open_navigation_directions" -> "লাইভ GPS লোকেশন..."
        "call_contact" -> "${args.optString("name").ifBlank { "কন্টাক্ট" }} কল..."
        "search_contact", "get_contacts_summary", "get_call_log_summary" -> "কন্টাক্ট ও কল লগ..."
        "check_notifications", "reply_to_incoming_message" -> "মেসেজ ও কল দেখা..."
        "diagnose_and_fix_self" -> "নিজের সমস্যা পরীক্ষা..."
        "critical_task_solve" -> "ক্রিটিক্যাল এআই চিন্তা..."
        "inspect_camera_vision" -> "ক্যামেরা ভিশন দেখা..."
        "switch_camera_lens" -> "ক্যামেরা লেন্স পরিবর্তন..."
        "build_android_app", "prepare_app_requirements", "check_termux_build_status", "run_termux_command" ->
          "অ্যাপ বিল্ড ও Termux..."
        "web_search", "deep_research" -> "ওয়েব রিসার্চ..."
        "search_memory", "remember", "delete_memory_entry" -> "স্মৃতি প্রসেস..."
        "inspect_form_fields", "fill_form_fields" -> "ফর্ম পূরণ..."
        "facebook_action" -> "Facebook কাজ..."
        "instagram_action" -> "Instagram কাজ..."
        "play_youtube", "media_control" -> "YouTube / মিডিয়া..."
        "unlock_phone", "unlock_locked_app", "lock_phone" -> "স্ক্রিন লক প্রসেস..."
        "list_installed_apps" -> "ইনস্টল করা অ্যাপ খোঁজা..."
        "schedule_exact_reminder" -> "রিমাইন্ডার সেট..."
        "get_capabilities_a_to_z" -> "সক্ষমতা ও অনুমতি যাচাই..."
        "get_activity_monitor" -> "Activity Monitor দেখা হচ্ছে..."
        "run_background_task" -> "ব্যাকগ্রাউন্ড টাস্ক কিউ..."
        else -> "${toolName.replace('_', ' ')}..."
      }
    return "কাজ করছে: $task"
  }
}

/**
 * Detects natural Bengali & English mid-speech interrupt cues ("শোনো", "থামো", "স্টপ", "বাই", "দাঁড়াও", "stop", "wait", "bye")
 * so LXR stops speaking immediately and listens to Boss with zero friction.
 */
object LxrSpeechInterruptGuard {
  private val EXACT_TOKENS =
    setOf(
      "শোনো",
      "শোন",
      "শোনেন",
      "থামো",
      "থাম",
      "থামেন",
      "চুপ",
      "দাঁড়াও",
      "দাঁড়া",
      "স্টপ",
      "বাই",
      "stop",
      "wait",
      "listen",
      "pause",
      "bye",
    )

  private val PHRASE_ROOTS =
    listOf(
      "শোনো",
      "থামো",
      "দাঁড়াও",
      "দাঁড়া",
      "একটু শোন",
      "এই শোন",
      "একটু থামো",
      "থামো তো",
      "এক মিনিট",
      "চুপ কর",
      "চুপ করো",
      "অপেক্ষা কর",
      "বাই বাই",
      "hold on",
      "shut up",
    )

  fun containsInterruptCommand(rawText: String): Boolean {
    val clean = rawText.trim().lowercase().replace(Regex("\\s+"), " ")
    if (clean.isBlank()) return false
    val tokens = clean.split(Regex("[\\s,।?!.;:\"'()\\[\\]{}]+")).filter { it.isNotBlank() }
    if (tokens.any { it in EXACT_TOKENS }) return true
    return PHRASE_ROOTS.any { phrase -> clean.contains(phrase) }
  }
}

/**
 * Proactive check-in policy: lets LXR--AI naturally ask Boss caring questions or check on Boss
 * during unlocked, quiet interactive sessions without nagging when locked, dark, or busy.
 */
object LxrProactiveCheckInPolicy {
  const val INITIAL_IDLE_MS = 35_000L
  const val COOLDOWN_MS = 120_000L
  const val MAX_PER_QUIET_STRETCH = 3

  fun shouldTriggerProactiveCheckIn(
    ready: Boolean,
    stopped: Boolean,
    isExecutingTool: Boolean,
    waitingServerReply: Boolean,
    activeTurnStarted: Boolean,
    isAiSpeaking: Boolean,
    isBossSpeaking: Boolean,
    isInteractive: Boolean,
    isPhoneLocked: Boolean,
    nowMs: Long,
    sessionStartMs: Long,
    lastBossActivityMs: Long,
    lastProactiveAtMs: Long,
    proactiveCountInStretch: Int,
  ): Boolean {
    if (!ready || stopped || isExecutingTool || waitingServerReply || activeTurnStarted) return false
    if (isAiSpeaking || isBossSpeaking || !isInteractive || isPhoneLocked) return false
    if (proactiveCountInStretch >= MAX_PER_QUIET_STRETCH) return false
    val anchorMs = maxOf(sessionStartMs, lastBossActivityMs)
    if (anchorMs <= 0L || (nowMs - anchorMs) < INITIAL_IDLE_MS) return false
    if (lastProactiveAtMs > 0L && (nowMs - lastProactiveAtMs) < COOLDOWN_MS) return false
    return true
  }
}

/**
 * Prevents LXR--AI from ever hearing its own Phone Speaker output (looping/repeating itself),
 * regardless of whether Voice Match is ON or OFF or speaker volume RMS is high.
 */
object AudioEchoGuard {
  fun shouldMuteMic(
    usingBtAudio: Boolean,
    nowMs: Long,
    micMuteUntilMs: Long,
    hasPendingSpeakerFrames: Boolean,
    ttsIsSpeaking: Boolean,
    lastSpeakerSoundingMs: Long,
    rawRms: Double,
    phoneTailHoldMs: Long = 700L,
  ): Boolean {
    if (!usingBtAudio) {
      return nowMs < micMuteUntilMs ||
        hasPendingSpeakerFrames ||
        ttsIsSpeaking ||
        (lastSpeakerSoundingMs > 0L && (nowMs - lastSpeakerSoundingMs) < phoneTailHoldMs)
    }
    return nowMs < micMuteUntilMs ||
      ttsIsSpeaking ||
      (lastSpeakerSoundingMs > 0L && (nowMs - lastSpeakerSoundingMs) < 300L && rawRms < 950.0)
  }

  /**
   * Smart Phone Speaker + Bluetooth barge-in guard:
   * - Unmutes automatically during brief silent pauses between sentences/A-to-Z list items.
   * - Allows Boss to interrupt mid-sentence via Voice Match OR close-talk speech ("শোনো", "থামো").
   */
  fun shouldMuteMicWithBargeIn(
    usingBtAudio: Boolean,
    nowMs: Long,
    micMuteUntilMs: Long,
    isSpeakerAudiblyPlayingNow: Boolean,
    ttsIsSpeaking: Boolean,
    lastAudibleStopMs: Long,
    rawRms: Double,
    lastSpeakerOutputRms: Double,
    voiceMatchBargeIn: Boolean,
    consecutiveLoudChunks: Int,
    phoneTailHoldMs: Long = 140L,
    minDirectBargeInRms: Double = 8500.0,
    directRatio: Double = 2.1,
  ): Boolean {
    if (nowMs < micMuteUntilMs) return !voiceMatchBargeIn
    if (ttsIsSpeaking) return !voiceMatchBargeIn
    if (voiceMatchBargeIn) return false
    val loudDirectBossBargeIn =
      consecutiveLoudChunks >= 2 && rawRms >= maxOf(minDirectBargeInRms, lastSpeakerOutputRms * directRatio)
    if (loudDirectBossBargeIn) return false
    if (usingBtAudio) {
      return isSpeakerAudiblyPlayingNow ||
        (lastAudibleStopMs > 0L && (nowMs - lastAudibleStopMs) < 300L && rawRms < 950.0)
    }
    return isSpeakerAudiblyPlayingNow ||
      (lastAudibleStopMs > 0L && (nowMs - lastAudibleStopMs) < phoneTailHoldMs)
  }

  /**
   * Accurate hardware playback-head guard: protects LXR's speaker output for as long as the
   * AudioTrack playback head has NOT reached [lastAudibleEndFrame] (even if network packets
   * burst ahead by several seconds), preventing premature unmuting and mid-sentence self-echo cutoffs.
   */
  fun isPlaybackHeadStillSpeaking(
    isPlaying: Boolean,
    playbackHeadFrames: Long,
    lastAudibleEndFrame: Long,
    lastSpeakerWriteMs: Long,
    nowMs: Long,
    activeTurnHasStartedOutput: Boolean,
  ): Boolean {
    if (isPlaying && lastAudibleEndFrame > 0L && playbackHeadFrames < lastAudibleEndFrame) {
      val sinceWriteMs = if (lastSpeakerWriteMs > 0L) (nowMs - lastSpeakerWriteMs).coerceAtLeast(0L) else Long.MAX_VALUE
      if (sinceWriteMs <= 12_000L) return true
    }
    return activeTurnHasStartedOutput && lastSpeakerWriteMs > 0L && (nowMs - lastSpeakerWriteMs) in 0L..900L
  }
}

@SuppressLint("MissingPermission")
class GeminiLive(private val ctx: Context) {
  private val client =
    OkHttpClient.Builder()
      .connectTimeout(16, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .pingInterval(35, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  private val main = Handler(Looper.getMainLooper())
  private val playExec = Executors.newSingleThreadExecutor()
  private val toolExec =
    Executors.newFixedThreadPool(4) { r ->
      Thread(r, "LxrToolExec").apply { isDaemon = true }
    }
  private val uiToolLock = Any()
  private val obsExec = Executors.newSingleThreadExecutor { r ->
    Thread(r, "LxrLiveScreenObs").apply { isDaemon = true }
  }
  private var ws: WebSocket? = null
  private var rec: AudioRecord? = null
  @Volatile private var track: AudioTrack? = null
  @Volatile private var audioFocusRequest: AudioFocusRequest? = null
  private val am: AudioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  @Volatile private var ready = false
  @Volatile private var stopped = false
  private val trackLock = Any()
  private val recLock = Any()
  private var recThread: Thread? = null
  private val uText = StringBuilder()
  private val pText = StringBuilder()

  @Volatile private var searchOn = false
  @Volatile private var lastErr = ""
  @Volatile private var failCount = 0
  @Volatile private var pending = false
  @Volatile private var modelOverride: String? = null
  @Volatile private var isTorchOn = false
  @Volatile private var greetedThisSession = false
  @Volatile private var micMuteUntilMs = 0L
  @Volatile private var transcriptionCompatFallback = false
  @Volatile private var vadConfigCompatFallback = false
  @Volatile private var thinkingConfigCompatFallback = false
  @Volatile private var slowNetworkCached = false
  @Volatile private var summonedOrbToolOnly = false

  fun isReady(): Boolean = ready && !stopped

  fun switchModelAndReconnect(newModelId: String) {
    Store.setModel(ctx, newModelId)
    modelOverride = null
    failCount = 0
    ready = false
    pending = false
    waitingServerReply = false
    activeTurnHasStartedOutput = false
    thinkingConfigCompatFallback = false
    vadConfigCompatFallback = false
    transcriptionCompatFallback = false
    try {
      ws?.cancel()
    } catch (_: Exception) {}
    ws = null
    if (!stopped) {
      main.post { if (!stopped) connect() }
    }
  }

  private fun toolsJson() =
    (if (searchOn) """[{"googleSearch":{}},""" else "[") +
      """{"functionDeclarations":[
      {"name":"open_app","description":"ফোনে ঠিক যে অ্যাপের নাম বলা হয়েছে সেটি সরাসরি ওপেন করে (imo বললে imo, WhatsApp বললে WhatsApp, Messenger বললে Messenger — কখনোই এলোমেলো বা অন্য অ্যাপ খোলে না, এবং অ্যাপ লক থাকলে খুলে দেয়)।","parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING"}},"required":["app_name"]}},
      {"name":"send_message","description":"যেকোনো মেসেজিং অ্যাপে (WhatsApp, imo, Messenger) নামের চ্যাট খুলে মেসেজ পাঠাও, অথবা Gemini/ChatGPT-তে লেখো। বস যে অ্যাপের নাম বলেছেন হুবহু সেটি দাও।","parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING","enum":["WhatsApp","imo","Messenger","Gemini","ChatGPT"]},"contact_name":{"type":"STRING","description":"চ্যাট বা প্রেরকের নাম (Gemini/ChatGPT এর জন্য ফাঁকা)"},"message":{"type":"STRING","description":"কী মেসেজ লিখবে"}},"required":["app_name","message"]}},
      {"name":"inspect_contact_chat","description":"সঠিক মেসেজিং অ্যাপ (WhatsApp, imo বা Messenger) খুলে নির্দিষ্ট কন্টাক্টের চ্যাট থ্রেডে ঢুকে উপরের চ্যাট হেডার যাচাই করে বাম পাশের প্রাপ্ত মেসেজ, ডান পাশের নিজের পাঠানো মেসেজ এবং ইনকামিং বা মিসড ভয়েস/ভিডিও কল আছে কিনা সত্য তথ্য বের করো।","parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING","enum":["WhatsApp","imo","Messenger"]},"contact_name":{"type":"STRING","description":"যে কন্টাক্টের চ্যাট বা কল যাচাই করতে হবে"}},"required":["app_name"]}},
      {"name":"unlock_phone","description":"যদি বস সরাসরি 'ফোন বা স্ক্রিন আনলক করো' বলেন তখন explicit=true দিয়ে আনলক করো। আর স্ক্রিন লক অবস্থায় থাকা কোনো কমান্ড (যেমন WhatsApp/imo/Messenger মেসেজ চেক, স্ক্রিন পড়া বা অ্যাপ খোলা) এলে সিস্টেম নিজেই নীরবে আনলক করে কাজ শেষে রিলক করবে।","parameters":{"type":"OBJECT","properties":{"explicit":{"type":"BOOLEAN","description":"বস সরাসরি আনলক করতে বললে true"}},"required":["explicit"]}},
      {"name":"lock_phone","description":"বস স্পষ্টভাবে স্ক্রিন বা ফোন লক করতে বললে মেইন স্ক্রিন লক করো। বসের অনুমতি ছাড়া আনলক ফোন লক করবে না।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"unlock_locked_app","description":"যেকোনো অ্যাপে অ্যাপ লক স্ক্রিন দেখা গেলে সরাসরি অ্যাপ লক খুলে দাও। পিন বা পাসওয়ার্ডের কথা মুখে বলবে না।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"show_wake_orb","description":"বস 'LxR', 'L.x.R' বা 'LxR অরব' বলে ডাকলে যেকোনো অ্যাপ বা স্ক্রিনের ওপর ৫০% স্বচ্ছ ঘূর্ণায়মান LxR অরব দেখাও।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"get_capabilities_a_to_z","description":"বস যখন জিজ্ঞেস করেন 'তুমি কী কী করতে পারো?', 'কোন কোন অ্যাপ পরিচালনা করতে পারো?' বা সক্ষমতার তালিকা চান, তখন এই মুহূর্তের আসল ফোনের অনুমতি, ইনস্টলকৃত অ্যাপ, সার্ভিস ও সংযোগ পরীক্ষা করে ৪টি সত্য শ্রেণিতে (সম্পূর্ণ সক্রিয়, আংশিক, অনুমতি প্রয়োজন, অনুপলব্ধ) প্রকৃত সক্ষমতা ফেরত নাও।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"get_activity_monitor","description":"বস জিজ্ঞাসা করলে 'What did you do?', 'কী করেছো?', 'তথ্য দেখাও' বা চলমান/সম্পন্ন কাজের হিসাব চাইলে LXR Activity Monitor থেকে যাচাইকৃত স্ট্যাটাস, সংগৃহীত তথ্য ও তৈরি ফাইলের বিবরণ ফেরত নাও।","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"ঐচ্ছিক ফিল্টার (যেমন অ্যাপ বা কাজের নাম)"}},"required":[]}},
      {"name":"run_background_task","description":"ব্যাকগ্রাউন্ডে JobScheduler ও LXR Activity Monitor দিয়ে স্বাধীন টাস্ক (যেমন system_audit, deep_research, check_environment) শুরু ও যাচাই করো।","parameters":{"type":"OBJECT","properties":{"command":{"type":"STRING","description":"কাজের ধরন: system_audit, deep_research অথবা universal_app_check"},"target_app":{"type":"STRING","description":"টার্গেট অ্যাপ বা মডিউল"},"query":{"type":"STRING","description":"প্রাসঙ্গিক সার্চ বা অ্যাপের নাম"}},"required":["command"]}},
      {"name":"prepare_app_requirements","description":"বস নতুন কোনো Android অ্যাপ বা APK বানাতে চাইলে কোড লেখা ও Termux বিল্ড শুরু করার আগে ছোট আকারে অ্যাপের নাম, থিম এবং প্রধান ফিচারগুলো যাচাই করে সম্মতি নেওয়ার প্রশ্নমালা তৈরি করো।","parameters":{"type":"OBJECT","properties":{"idea":{"type":"STRING","description":"অ্যাপের মূল ধারণা"},"preferred_name":{"type":"STRING"},"theme":{"type":"STRING"},"features":{"type":"STRING"}},"required":["idea"]}},
      {"name":"build_android_app","description":"বস যেকোনো অ্যান্ড্রয়েড অ্যাপ বানাতে সম্মতি দিলে সাথে সাথে Kotlin/Compose/Java সোর্স কোড তৈরি করে LXR স্ক্রিনে লাইভ প্রিভিউ খুলে Download/LxR এবং Termux-এ APK বিল্ড শুরু করো।","parameters":{"type":"OBJECT","properties":{"prompt":{"type":"STRING","description":"বস যে অ্যাপটি বানাতে বলেছেন তার সম্পূর্ণ বর্ণনা"},"app_name":{"type":"STRING","description":"অ্যাপের সংক্ষিপ্ত নাম (ঐচ্ছিক)"},"category":{"type":"STRING","description":"CALCULATOR, MINI_GAME, AI_ASSISTANT, NOTES, PRODUCTIVITY, CUSTOM (ঐচ্ছিক)"},"auto_build_termux":{"type":"BOOLEAN","description":"Termux-এ স্বয়ংক্রিয় APK বিল্ড শুরু করতে true (ডিফল্ট true)"}},"required":["prompt"]}},
      {"name":"check_termux_build_status","description":"Termux-এ চলমান বা সম্পন্ন APK বিল্ডের বর্তমান লাইভ ধাপ, Console লগ, এরর অটো-ফিক্স এবং Download/LxR ফোল্ডারের ফাইল অবস্থা পরীক্ষা করো।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"run_termux_command","description":"Termux অ্যাপ খুলে বা সক্রিয় রেখে সরাসরি টার্মিনাল স্ক্রিনে ৩ সেকেন্ড ধরে রেখে, ২ সেকেন্ড অপেক্ষা করে Paste ক্লিক করে এবং Google Gboard-এর এন্টার বাটন চেপে যেকোনো কমান্ড সরাসরি রান করো।","parameters":{"type":"OBJECT","properties":{"command":{"type":"STRING","description":"Termux টার্মিনালে যে কমান্ড বা কমান্ড লাইন চালাতে হবে"}},"required":["command"]}},
      {"name":"find_and_open_item","description":"স্ক্রিনে বা অ্যাপে যেকোনো আইটেম নাম দিয়ে খুঁজে ট্যাপ করো, না পেলে Infinix Android 16 সার্চ বার দিয়ে সার্চ করো।","parameters":{"type":"OBJECT","properties":{"name":{"type":"STRING"}},"required":["name"]}},
      {"name":"current_screen","description":"বস এখন কোন অ্যাপ, চ্যাট বা স্ক্রিনে আছেন এবং লাইভ ০.১ সেকেন্ড স্ট্যাটাস কী তা জানো"},
      {"name":"read_screen","description":"এখন সম্পূর্ণ স্ক্রিনে কী কী লেখা, বাটন, সুইচ ও লেখার বক্স আছে তা পড়ো"},
      {"name":"click_text","description":"স্ক্রিনের এই টেক্সট/বাটনে ট্যাপ করো","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING"}},"required":["text"]}},
      {"name":"type_text","description":"স্ক্রিনের লেখার বক্সে টাইপ করো। নোটপ্যাড বা Notes অ্যাপে মূল নোট লিখতে target='body' দাও। শুধু শিরোনামের জন্য target='title'।","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING"},"target":{"type":"STRING","enum":["auto","body","title"],"description":"auto (ডিফল্ট body অগ্রাধিকার), body (নোট বা মূল লেখা), title (কেবল শিরোনাম)"}},"required":["text"]}},
      {"name":"global_action","description":"home, back, recents, notifications বা lock","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING"}},"required":["action"]}},
      {"name":"scroll","description":"স্ক্রিন স্ক্রল করো","parameters":{"type":"OBJECT","properties":{"direction":{"type":"STRING","description":"up বা down"}},"required":["direction"]}},
      {"name":"call_contact","description":"১ম ধাপে confirmed=false দিয়ে কন্টাক্টের প্রথম শব্দ অনুযায়ী আসল নাম ও নম্বর বের করে বসকে পড়ে শোনাও; বস 'হ্যাঁ / ঠিক আছে' বলার পর confirmed=true দিয়ে প্রকৃত কল দাও।","parameters":{"type":"OBJECT","properties":{"name":{"type":"STRING"},"confirmed":{"type":"BOOLEAN","description":"বস নাম ও নম্বর শুনে 'হ্যাঁ / ঠিক আছে' বললে true, নইলে false"}},"required":["name"]}},
      {"name":"search_contact","description":"কন্টাক্ট লিস্টের প্রথম শব্দ অনুযায়ী খুঁজে আসল নম্বর ও নাম চেক করো (অনুমান বা মিথ্যা বলবে না)","parameters":{"type":"OBJECT","properties":{"name":{"type":"STRING"}},"required":["name"]}},
      {"name":"play_youtube","description":"YouTube-এ গান বা ভিডিও প্লে করো","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"গান বা ভিডিওর নাম"}},"required":["query"]}},
      {"name":"media_control","description":"মিডিয়া pause, play, next বা previous করো","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING","description":"pause, play, next, previous"}},"required":["action"]}},
      {"name":"toggle_flashlight","description":"ফ্ল্যাশলাইট চালু বা বন্ধ করো","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN"}},"required":["enabled"]}},
      {"name":"control_wifi","description":"WiFi চালু বা বন্ধ করো","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN"}},"required":["enabled"]}},
      {"name":"control_bluetooth","description":"Bluetooth চালু বা বন্ধ করো","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN"}},"required":["enabled"]}},
      {"name":"get_battery_level","description":"ফোনের ব্যাটারি চার্জ শতাংশ জানো"},
      {"name":"set_sound_mode","description":"ফোন silent, vibrate বা normal করো","parameters":{"type":"OBJECT","properties":{"mode":{"type":"STRING","description":"silent, vibrate বা normal"}},"required":["mode"]}},
      {"name":"check_notifications","description":"নোটিফিকেশন বারের নতুন মেসেজ, ভয়েস/ভিডিও কল ও আপডেট চেক করো"},
      {"name":"reply_to_incoming_message","description":"বস নতুন আসা WhatsApp/imo/Messenger মেসেজের রিপ্লাই দিতে বললে বা বাতিল করতে বললে এটি চালাও।","parameters":{"type":"OBJECT","properties":{"approve":{"type":"BOOLEAN","description":"রিপ্লাই পাঠাতে true, বাতিল করতে false"},"reply_text":{"type":"STRING","description":"বস নির্দিষ্ট উত্তর বললে সেটা, না বললে ফাঁকা রাখো"}},"required":["approve"]}},
      {"name":"current_time","description":"বাংলাদেশের বর্তমান সময় (Asia/Dhaka), তারিখ ও বার জানো"},
      {"name":"web_search","description":"Google ও DuckDuckGo সার্চ করে যাচাইকৃত সঠিক তথ্য ও লিংক খুঁজে আনো","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING"}},"required":["query"]}},
      {"name":"deep_research","description":"Google Search, Web Search, Wikipedia এবং কনফিগার করা থাকলে Perplexity ও You.com ব্যবহার করে আসল URL তথ্যসূত্রসহ গভীর রিসার্চ করো।","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING"},"engine":{"type":"STRING","description":"auto, google, perplexity, you"}},"required":["query"]}},
      {"name":"search_memory","description":"গত ১৮০ দিনের (৬ মাসের) সংরক্ষিত স্মৃতি ও কথোপকথন থেকে ছোট্ট সূত্র, মানুষের নাম, তারিখ বা বিষয় খুঁজে আসল ঘটনা বের করো।","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"সূত্র, মানুষের নাম, তারিখ বা মূল শব্দ"}},"required":["query"]}},
      {"name":"delete_memory_entry","description":"বস নির্দিষ্ট কোনো বিষয়, নাম বা কথোপকথনের স্মৃতি মেমোরি থেকে মুছে ফেলতে বললে সরাসরি সেটি মুছে দাও।","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"যে বিষয় বা কথা মুছতে হবে"}},"required":["query"]}},
      {"name":"remember","description":"বসের জরুরি কথা মেমোরিতে সংরক্ষণ করো","parameters":{"type":"OBJECT","properties":{"fact":{"type":"STRING"}},"required":["fact"]}},
      {"name":"control_device_setting","description":"ফোনের বিস্তারিত হার্ডওয়্যার ও সেটিংস (mobile_data, hotspot, brightness, auto_rotate, dnd, location, volume, wifi, bluetooth, flashlight) নিয়ন্ত্রণ করো।","parameters":{"type":"OBJECT","properties":{"setting_name":{"type":"STRING","description":"wifi, bluetooth, mobile_data, hotspot, flashlight, auto_rotate, dnd, location, brightness, volume, ring_volume"},"enabled":{"type":"BOOLEAN"},"value_percent":{"type":"INTEGER","description":"ব্রাইটনেস বা ভলিউমের জন্য ০-১০০ শতাংশ"}},"required":["setting_name"]}},
      {"name":"get_device_hardware_info","description":"ফোনের সম্পূর্ণ হার্ডওয়্যার, মডেল, প্রসেসর/CPU, RAM, ইন্টারনাল স্টোরেজ, ব্যাটারি হেলথ/তাপমাত্রা, Android OS ভার্সন, ডিসপ্লে ও সেন্সর তথ্য পড়ো।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"get_live_location","description":"GPS থেকে বসের বর্তমান লাইভ ঠিকানা, স্থানাঙ্ক ও ম্যাপ লিংক বের করো।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"open_navigation_directions","description":"Google Maps-এ নির্দিষ্ট গন্তব্যের দিকে যাওয়ার লাইভ নেভিগেশন ও দিকনির্দেশনা চালু করো।","parameters":{"type":"OBJECT","properties":{"destination":{"type":"STRING","description":"যে জায়গার দিকে যেতে হবে"},"travel_mode":{"type":"STRING","description":"driving, walking, bicycling বা two_wheeler"}},"required":["destination"]}},
      {"name":"get_contacts_summary","description":"ফোনে মোট কতটি কন্টাক্ট এবং কতটি নম্বর সেভ করা আছে তার সঠিক হিসাব ও নামের উদাহরণ বের করো।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"get_call_log_summary","description":"ফোনের ডায়ালারের সাম্প্রতিক আসা (incoming), করা (outgoing) ও মিসড (missed) কল লগ ও সংখ্যা পড়ো।","parameters":{"type":"OBJECT","properties":{"filter_type":{"type":"STRING","description":"all, missed, incoming অথবা outgoing"},"limit":{"type":"INTEGER","description":"কতগুলো কল দেখাবে (ডিফল্ট ১০)"}},"required":[]}},
      {"name":"inspect_form_fields","description":"Chrome ব্রাউজার বা বর্তমান অ্যাপের স্ক্রিনে খোলা ওয়েবপেজ ও ফর্মের সব ইনপুট ফিল্ড, লেবেল ও খালি ঘর পড়ে বের করো।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"fill_form_fields","description":"ফর্মের ইনপুট ফিল্ডে বসের দেওয়া তথ্য বসাও এবং কোন কোন ঘর এখনো ফাঁকা আছে জানাও।","parameters":{"type":"OBJECT","properties":{"fields_json":{"type":"STRING","description":"যেমন '{\"Name\":\"Md Uzzal\",\"Phone\":\"017...\"}' অথবা 'Name=Md Uzzal;Email=abc@gmail.com'"}},"required":["fields_json"]}},
      {"name":"facebook_action","description":"Facebook অ্যাপ খুলে পোস্ট পড়া, সার্চ করা, রিয়্যাক্ট/লাইক করা, কমেন্ট পড়া ও রিপ্লাই দেওয়া, শেয়ার বা স্ট্যাটাস/ফটো/রিল পোস্ট করা (পাবলিক কাজের আগে confirmed=true)।","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING","description":"open, read, search, post_status, post_photo, post_reel, reply_comment, react, share"},"text":{"type":"STRING"},"query":{"type":"STRING"},"confirmed":{"type":"BOOLEAN","description":"পাবলিক পোস্ট/রিল/কমেন্ট/শেয়ার করার আগে বস সম্মতি দিলে true"}},"required":["action"]}},
      {"name":"instagram_action","description":"Instagram অ্যাপ খুলে পোস্ট/রিল পড়া, সার্চ করা, লাইক করা, কমেন্ট পড়া ও রিপ্লাই দেওয়া, শেয়ার বা ফটো/রিল পোস্ট করা।","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING","description":"open, read, search, post_photo, post_reel, reply_comment, react, share"},"text":{"type":"STRING"},"query":{"type":"STRING"},"confirmed":{"type":"BOOLEAN","description":"পাবলিক পোস্ট/রিল/কমেন্ট/শেয়ার করার আগে বস সম্মতি দিলে true"}},"required":["action"]}},
      {"name":"diagnose_and_fix_self","description":"LXR--AI নিজের সিস্টেম ও অনুমতি (Accessibility, Notification, Modify System Settings / Restricted Settings, Default Digital Assistant, GPS, Battery) পরীক্ষা করে ('diagnose') সমস্যা চিহ্নিত করে, এবং বস স্পষ্ট অনুমতি দিলে তবেই নির্দিষ্ট সমস্যার সেটিংস খুলে ঠিক করে ('fix')।","parameters":{"type":"OBJECT","properties":{"mode":{"type":"STRING","enum":["diagnose","fix"],"description":"আগে সমস্যা খুঁজতে 'diagnose'; বসের অনুমতি পাওয়ার পর ঠিক করতে 'fix'"},"issue_key":{"type":"STRING","description":"যেমন accessibility, notification, overlay, write_settings, assistant, battery, location, app_info"}},"required":["mode"]}},
      {"name":"critical_task_solve","description":"অত্যন্ত জটিল বিশ্লেষণ, কোডিং, লজিক, পরিকল্পনা বা কঠিন কাজের জন্য বসের নির্বাচিত এক্সট্রা ক্রিটিক্যাল এআই মডেল (Gemini 2.5 Pro / Flash) ব্যবহার করে গভীর সমাধান বের করো।","parameters":{"type":"OBJECT","properties":{"task_prompt":{"type":"STRING","description":"কঠিন কাজ বা প্রশ্নের বিস্তারিত বর্ণনা"},"include_screen":{"type":"BOOLEAN","description":"বর্তমান স্ক্রিনের তথ্য যোগ করতে চাইলে true"}},"required":["task_prompt"]}},
      {"name":"inspect_camera_vision","description":"বস ফ্রন্ট বা ব্যাক ক্যামেরায় কী দেখা যাচ্ছে তা দেখতে বা 'আমাকে দেখো / সামনে কী আছে' জিজ্ঞাসা করলে ফোরগ্রাউন্ড বা ব্যাকগ্রাউন্ড ক্যামেরা দিয়ে আসল লাইভ ছবি (image/jpeg) তুলে যাচাই করে সত্য বর্ণনা দাও। ছবি ছাড়া কখনো অনুমান করবে না।","parameters":{"type":"OBJECT","properties":{"lens":{"type":"STRING","enum":["front","back","current"],"description":"front (বসকে দেখতে), back (সামনের দৃশ্য) বা current"},"question":{"type":"STRING","description":"বস কী জানতে চেয়েছেন"}},"required":[]}},
      {"name":"switch_camera_lens","description":"লাইভ ভিশনের ফ্রন্ট (সামনের সেলফি) এবং ব্যাক (পিছনের) ক্যামেরার মধ্যে বদল করো এবং প্রয়োজনে ব্যাকগ্রাউন্ড বা ফোরগ্রাউন্ডে ক্যামেরা চালু/বন্ধ করো।","parameters":{"type":"OBJECT","properties":{"lens":{"type":"STRING","enum":["front","back","toggle"],"description":"front, back বা toggle"},"turn_on":{"type":"BOOLEAN","description":"ক্যামেরা চালু করতে চাইলে true, বন্ধ করতে চাইলে false (ঐচ্ছিক)"}},"required":["lens"]}},
      {"name":"list_installed_apps","description":"ফোনে সত্যিই কী কী লঞ্চযোগ্য অ্যাপস ইনস্টল করা আছে তা প্যাকেজ ম্যানেজার দিয়ে সম্পূর্ণ পরীক্ষা করো (কখনোই এই টুল বা open_app না চালিয়ে বলবে না যে কোনো অ্যাপ ইনস্টল করা নেই)।","parameters":{"type":"OBJECT","properties":{"filter":{"type":"STRING","description":"নির্দিষ্ট অ্যাপের নাম বা অংশ দিয়ে খুঁজতে চাইলে লিখো, না চাইলে ফাঁকা রাখো"}},"required":[]}},
      {"name":"schedule_exact_reminder","description":"বসের নির্ধারিত সময়ে (Alarms & reminders ব্যবহার করে) সঠিক রিমাইন্ডার বা অ্যালার্ম অ্যালার্ট সেট করো।","parameters":{"type":"OBJECT","properties":{"title":{"type":"STRING","description":"রিমাইন্ডারের কথা বা কাজের নাম"},"delay_seconds":{"type":"INTEGER","description":"কত সেকেন্ড পরে অ্যালার্ট বাজবে (যেমন 60, 300, 1800)"}},"required":["title","delay_seconds"]}}
    ]}]"""

  private val devCb =
    object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>) {
        routeAudio()
      }

      override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>) {
        routeAudio()
      }
    }

  private fun hasBtPerm() =
    Build.VERSION.SDK_INT < 31 ||
      ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

  @Volatile private var usingBtAudio = false
  @Volatile private var hasHwAec = false
  @Volatile private var lastSpeakerWriteMs = 0L
  @Volatile private var lastSpeakerSoundingMs = 0L
  @Volatile private var totalSpeakerFramesWritten = 0L
  @Volatile private var speakerHeadBaseFrames = 0L
  @Volatile private var lastAudibleEndFrame = 0L
  @Volatile private var lastAudibleStopMs = 0L
  @Volatile private var lastSpeakerOutputRms = 0.0
  @Volatile private var hadCommandOrToolThisTurn = false
  @Volatile private var lastWakeOrMicPopupMs = 0L
  @Volatile private var turnUnlockedMainScreenByLxr = false
  @Volatile private var pendingVoiceDelayUntilMs = 0L
  @Volatile private var activeTurnProducedVoiceReply = false
  @Volatile private var activeTurnBlockedByLimit = false
  @Volatile private var notifiedReplyLimitExhausted = false
  @Volatile private var currentTurnReceivedFirstChunk = false
  @Volatile private var currentTurnEntry: VoiceCommandEntry? = null
  @Volatile private var playbackSpeed: Float = Store.voiceSpeed(ctx)
  private var audioRouteThread: HandlerThread? = null
  private var audioRouteHandler: Handler? = null
  @Volatile private var netCallback: NetworkCallback? = null
  @Volatile private var pendingNetworkLostRunnable: Runnable? = null
  @Volatile private var lastServerActivityMs = 0L
  @Volatile private var lastBossTurnAtMs = 0L
  @Volatile private var lastTurnSpeechChunks = 0
  @Volatile private var waitingServerReply = false
  @Volatile private var suppressStaleUntilMs = 0L
  @Volatile private var activeTurnHasStartedOutput = false
  @Volatile private var activeWorkStartedAtMs = 0L
  @Volatile private var lastBossSpeechDetectedMs = 0L
  @Volatile private var sessionStartedAtMs = 0L
  @Volatile private var lastProactiveCheckInAtMs = 0L
  @Volatile private var proactiveCountInStretch = 0
  private val audioPlaybackEpoch = java.util.concurrent.atomic.AtomicLong(0L)
  private val pendingAudioChunks = java.util.concurrent.atomic.AtomicInteger(0)
  private val micNoiseSuppressor = LxrAudioNoiseSuppressor(16000)
  private val voiceQueue = LxrVoiceCommandQueue()

  private fun isSuppressingStaleAudio(): Boolean = System.currentTimeMillis() < suppressStaleUntilMs

  private val audioFocusListener =
    AudioManager.OnAudioFocusChangeListener { /* Keep live voice session active across brief transient hints */ }

  fun onReplyDelayOptionChanged() {
    // Preserve any already-queued/in-flight command's captured delay; new setting applies only to future commands.
  }

  fun onReplyLimitChanged() {
    notifiedReplyLimitExhausted = false
  }

  fun updatePlaybackSpeed(speed: Float) {
    val clamped = speed.coerceIn(Store.MIN_VOICE_SPEED, Store.MAX_VOICE_SPEED)
    playbackSpeed = clamped
    synchronized(trackLock) {
      val t = track
      if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
        try {
          t.playbackParams = PlaybackParams().setSpeed(clamped).setPitch(1.0f)
        } catch (_: Throwable) {}
      }
    }
  }

  private fun findBuiltInMicDevice(): AudioDeviceInfo? {
    val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
    return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
  }

  private fun routeAudio(allowRestart: Boolean = true) {
    try {
      val btInput = findBluetoothInputDevice()
      val btLabel = btInput?.productName?.toString()?.takeIf { it.isNotBlank() }

      val btConnected: Boolean
      if (Build.VERSION.SDK_INT >= 31) {
        val devs = am.availableCommunicationDevices
        val btOut =
          if (hasBtPerm()) {
            devs.firstOrNull {
              val n = it.productName?.toString().orEmpty()
              (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) && n.contains("lxr", ignoreCase = true)
            } ?: devs.firstOrNull {
              it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
          } else null
        btConnected = btOut != null || btInput != null
        if (btConnected) {
          am.mode = AudioManager.MODE_IN_COMMUNICATION
          if (btOut != null) {
            am.setCommunicationDevice(btOut)
          }
        } else {
          am.clearCommunicationDevice()
          am.mode = AudioManager.MODE_NORMAL
          @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = true }
        }
        val activeName = btLabel ?: btOut?.productName?.toString()?.takeIf { it.isNotBlank() }
        RiyaState.isBluetoothMicActive.value = btConnected
        RiyaState.connectedAudioDevice.value =
          if (btConnected) (activeName ?: "LxR Wireless Bluetooth")
          else "Phone Mic (Direct Clear)"
      } else {
        @Suppress("DEPRECATION")
        val btOn =
          hasBtPerm() &&
            am.isBluetoothScoAvailableOffCall &&
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
              it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        btConnected = btOn || btInput != null
        @Suppress("DEPRECATION")
        if (btConnected) {
          am.mode = AudioManager.MODE_IN_COMMUNICATION
          am.startBluetoothSco()
          am.isBluetoothScoOn = true
          am.isSpeakerphoneOn = false
          RiyaState.isBluetoothMicActive.value = true
          RiyaState.connectedAudioDevice.value = btLabel ?: "LxR Wireless Bluetooth"
        } else {
          am.stopBluetoothSco()
          am.isBluetoothScoOn = false
          am.mode = AudioManager.MODE_NORMAL
          RiyaState.isBluetoothMicActive.value = false
          RiyaState.connectedAudioDevice.value = "Phone Mic (Direct Clear)"
        }
      }

      if (btConnected && btInput != null) {
        rec?.setPreferredDevice(btInput)
      } else {
        findBuiltInMicDevice()?.let { rec?.setPreferredDevice(it) }
      }

      if (allowRestart && btConnected != usingBtAudio && rec != null && !stopped) {
        usingBtAudio = btConnected
        restartAudioForRouteChange()
      } else {
        usingBtAudio = btConnected
      }
    } catch (_: Exception) {}
  }

  private fun restartAudioForRouteChange() {
    synchronized(recLock) {
      rec?.let { r ->
        try {
          if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
        } catch (_: Throwable) {}
      }
    }
    try {
      recThread?.join(180)
    } catch (_: Throwable) {}
    recThread = null
    synchronized(recLock) {
      try {
        rec?.release()
      } catch (_: Throwable) {}
      rec = null
    }
    synchronized(trackLock) {
      track?.let { t ->
        try {
          t.pause()
          t.flush()
          t.stop()
          t.release()
        } catch (_: Throwable) {}
      }
      track = null
    }
    if (!stopped) {
      initTrack()
      startMic()
    }
  }

  private fun findBluetoothInputDevice(): AudioDeviceInfo? {
    if (!hasBtPerm()) return null
    val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
    return inputs.firstOrNull {
      val name = it.productName?.toString().orEmpty()
      (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) && name.contains("lxr", ignoreCase = true)
    } ?: inputs.firstOrNull {
      it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }
  }

  private fun refreshSlowNetworkState(): Boolean {
    return try {
      val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      val net = cm.activeNetwork
      val caps = if (net != null) cm.getNetworkCapabilities(net) else null
      val slow =
        if (caps == null) false
        else {
          val down = caps.linkDownstreamBandwidthKbps
          val up = caps.linkUpstreamBandwidthKbps
          (down in 1..1500) || (up in 1..400)
        }
      slowNetworkCached = slow
      RiyaState.networkSpeedLabel.value = if (slow) "Low-Net Turbo" else "Fast"
      slow
    } catch (_: Exception) {
      slowNetworkCached = false
      false
    }
  }

  private fun isSlowNetwork(): Boolean = slowNetworkCached

  private var lastShot = 0
  private var watchdogTick = 0
  @Volatile private var lastObservedPhoneStateKey = ""
  @Volatile private var liveWatchTick = 0
  private val screenLoop =
    object : Runnable {
      override fun run() {
        if (stopped) return
        liveWatchTick++
        if (!obsExec.isShutdown) {
          obsExec.execute {
            if (stopped) return@execute
            try {
              observeLiveLockAndScreen1s()
              val imageEveryTicks = if (slowNetworkCached || Store.lowNetFastMode(ctx)) 10 else 6
              if (liveWatchTick % imageEveryTicks == 0) {
                watchScreen()
              }
            } catch (_: Exception) {}
          }
        }
        main.postDelayed(this, 1000L)
      }
    }

  private val watchdogLoop =
    object : Runnable {
      override fun run() {
        if (stopped) return
        try {
          watchdogTick++
          if (watchdogTick % 5 == 0) refreshSlowNetworkState()
          val now = System.currentTimeMillis()
          if (
            waitingServerReply &&
              (lastSpeakerWriteMs > lastBossTurnAtMs ||
                activeTurnHasStartedOutput ||
                isAiSpeakingRecently() ||
                lastServerActivityMs > lastBossTurnAtMs + 1500L)
          ) {
            waitingServerReply = false
          }
          if (!isExecutingTool || (activeWorkStartedAtMs > 0L && (now - activeWorkStartedAtMs) > 15_000L)) {
            if (RiyaState.activeWorkLabel.value != null || LxrFloatingOverlayManager.orbBadgeState.value.taskLabel != null || LxrFloatingOverlayManager.orbBadgeState.value.isReadingScreen) {
              activeWorkStartedAtMs = 0L
              RiyaState.activeWorkLabel.value = null
              LxrFloatingOverlayManager.completeTaskAndScheduleHide(
                ctx,
                hideDelayMs = 650L,
                keepShowingForWake = false,
              )
            }
          }
          val qBytes = ws?.queueSize() ?: 0L
          val bossTurnElapsed = if (lastBossTurnAtMs > 0L) now - lastBossTurnAtMs else 0L
          val serverSilence = if (lastServerActivityMs > 0L) now - lastServerActivityMs else 0L
          // Clear waitingServerReply silently if triggered by a brief ambient blip rather than killing a healthy socket
          if (waitingServerReply && lastTurnSpeechChunks < 6 && bossTurnElapsed > 16_000L && qBytes == 0L) {
            waitingServerReply = false
          }
          if (ready && !isExecutingTool) {
            if (
              NetworkStabilityGuard.shouldWatchdogReconnect(
                waitingServerReply = waitingServerReply,
                bossTurnElapsedMs = bossTurnElapsed,
                serverSilenceMs = serverSilence,
                queueBytes = qBytes,
              )
            ) {
              val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
              if (!NetworkStabilityGuard.hasValidatedInternet(cm)) {
                RiyaState.status.value = "ইন্টারনেট সংযোগের অপেক্ষায়…"
              } else {
                waitingServerReply = false
                fail("সার্ভারের উত্তর বিলম্বিত বা বাফার জমে গেছে — পুনরায় সংযোগ হচ্ছে…")
              }
            } else {
              val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
              val phoneState = LockVaultManager.describeLivePhoneState(ctx, RiyaAccessibilityService.instance)
              if (
                LxrProactiveCheckInPolicy.shouldTriggerProactiveCheckIn(
                  ready = ready,
                  stopped = stopped,
                  isExecutingTool = isExecutingTool,
                  waitingServerReply = waitingServerReply,
                  activeTurnStarted = activeTurnHasStartedOutput,
                  isAiSpeaking = isAiSpeakingRecently() || RiyaVoiceManager.isActivelySpeaking(),
                  isBossSpeaking = isBossSpeakingRecently(),
                  isInteractive = pm.isInteractive,
                  isPhoneLocked = phoneState.isLocked,
                  nowMs = now,
                  sessionStartMs = sessionStartedAtMs,
                  lastBossActivityMs = maxOf(lastBossTurnAtMs, lastBossSpeechDetectedMs),
                  lastProactiveAtMs = lastProactiveCheckInAtMs,
                  proactiveCountInStretch = proactiveCountInStretch,
                )
              ) {
                lastProactiveCheckInAtMs = now
                proactiveCountInStretch++
                val camSnap = LxrCameraVisionEngine.latestSnapshot()
                val camEval = LxrCameraVisionGuard.evaluateFrameUsability(camSnap, nowMs = now)
                if (
                  RiyaState.visionCameraActive.value &&
                    RiyaState.useFrontCamera.value &&
                    camEval.hasFreshFrame &&
                    camEval.isFaceVisibleNow &&
                    camEval.isReadableBrightness
                ) {
                  notifyCameraActive(
                    active = true,
                    isFrontCamera = true,
                    proactiveCuriosity = true,
                    faceDetected = true,
                  )
                } else {
                  val checkPrompt =
                    "[নিজস্ব কৌতূহল ও যত্নশীল প্রশ্ন]: বস কিছুক্ষণ চুপ করে আছেন এবং ফোনের স্ক্রিন খোলা আছে। এখন প্রিয় মানুষের মতো মৃদু দুষ্টুমি, মায়া বা কৌতূহল নিয়ে বসকে ছোট্ট একটা প্রশ্ন করো (যেমন: কী ভাবছো বস, নাকি আমার কোনো সাহায্য লাগবে, বা দিনটা কেমন যাচ্ছে — সর্বদা 'তুমি' বলবে, ১-২ লাইনের বেশি লম্বা করবে না)।"
                  runCatching {
                    val payload =
                      JSONObject()
                        .put(
                          "clientContent",
                          JSONObject()
                            .put(
                              "turns",
                              JSONArray()
                                .put(
                                  JSONObject()
                                    .put("role", "user")
                                    .put("parts", JSONArray().put(JSONObject().put("text", checkPrompt)))
                                ),
                            )
                            .put("turnComplete", true),
                        )
                    ws?.send(payload.toString())
                  }
                }
              }
            }
          }
        } catch (_: Throwable) {}
        if (!stopped) main.postDelayed(this, 4000L)
      }
    }

  @Volatile private var isExecutingTool = false
  @Volatile private var lastUserScreenAt = 0L

  fun onIncomingMessageReceived(
    msg: PendingIncomingMessage,
    autoSentReply: String? = null,
  ): Boolean {
    if (!ready || stopped) return false
    val draftPart =
      if (msg.suggestedDraft.isNotBlank()) " আমি উত্তর হিসেবে লিখে ঠিক করেছি: \"${msg.suggestedDraft}\"।"
      else ""
    val prompt =
      if (!autoSentReply.isNullOrBlank()) {
        "নতুন মেসেজ এসেছে: ${msg.appNameBn}-এ ${msg.senderName} লিখেছেন: \"${msg.messageText}\"। আমি অটো রিপ্লাই হিসেবে ${msg.senderName}-কে হুবহু এই লেখাটি পাঠিয়েছি: \"$autoSentReply\"। এখনই বসকে শান্ত ও স্পষ্ট বাংলায় জানিয়ে দাও যে কোন লেখাটি পাঠানো হয়েছে।"
      } else {
        "নতুন মেসেজ এসেছে: ${msg.appNameBn}-এ ${msg.senderName} মেসেজ পাঠিয়েছেন: \"${msg.messageText}\"।$draftPart এখনই বসকে শান্ত, স্পষ্ট ও বিনয়ী বাংলায় মেসেজটা পড়ে শোনাও এবং বসের অনুমতি চাও আমি কি এই উত্তরটা পাঠাবো, নাকি বসের বলা অন্য উত্তর পাঠাবো?"
      }
    val fallbackText =
      if (!autoSentReply.isNullOrBlank()) {
        "বস, ${msg.appNameBn}-এ ${msg.senderName} লিখেছেন: ${msg.messageText}। আমি অটো রিপ্লাই হিসেবে পাঠিয়েছি: \"$autoSentReply\"।"
      } else {
        val draftSpeak =
          if (msg.suggestedDraft.isNotBlank()) " আমি উত্তর লিখেছি: \"${msg.suggestedDraft}\"।"
          else ""
        "বস, ${msg.appNameBn}-এ ${msg.senderName} মেসেজ পাঠিয়েছেন: ${msg.messageText}।$draftSpeak আমি কি রিপ্লাইটা পাঠিয়ে দেবো? বলো বস।"
      }
    return try {
      val speakerMark = lastSpeakerWriteMs
      val turnEpoch = audioPlaybackEpoch.get()
      suppressStaleUntilMs = 0L
      // Briefly mute upstream mic so server VAD does not swallow incoming message prompt
      micMuteUntilMs = System.currentTimeMillis() + 700L
      val turnPayload =
        JSONObject()
          .put(
            "clientContent",
            JSONObject()
              .put(
                "turns",
                JSONArray()
                  .put(
                    JSONObject()
                      .put("role", "user")
                      .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                  ),
              )
              .put("turnComplete", true),
          )
      val sent = ws?.send(turnPayload.toString()) == true
      if (sent) {
        main.postDelayed(
          {
            if (!stopped &&
              ready &&
              lastSpeakerWriteMs == speakerMark &&
              !activeTurnHasStartedOutput &&
              !isAiSpeakingRecently() &&
              audioPlaybackEpoch.get() == turnEpoch
            ) {
              RiyaVoiceManager.speakUrgent(ctx, fallbackText)
            }
          },
          4200L,
        )
      }
      sent
    } catch (_: Throwable) {
      false
    }
  }

  fun onVoiceCallAlert(appLabel: String, callerName: String, callSummaryBn: String): Boolean {
    if (!ready || stopped) return false
    val prompt =
      "[জরুরি ভয়েস/ভিডিও কল অ্যালার্ট]: $callSummaryBn। এখনই শান্ত ও স্পষ্ট বাংলায় বসকে সংক্ষেপে জানিয়ে দাও যে $appLabel-এ $callerName-এর কাছ থেকে এই কলটি এসেছে/মিস হয়েছে।"
    val fallbackText = "বস, $callSummaryBn।"
    return try {
      val speakerMark = lastSpeakerWriteMs
      val turnEpoch = audioPlaybackEpoch.get()
      suppressStaleUntilMs = 0L
      micMuteUntilMs = System.currentTimeMillis() + 550L
      val turnPayload =
        JSONObject()
          .put(
            "clientContent",
            JSONObject()
              .put(
                "turns",
                JSONArray()
                  .put(
                    JSONObject()
                      .put("role", "user")
                      .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                  ),
              )
              .put("turnComplete", true),
          )
      val sent = ws?.send(turnPayload.toString()) == true
      if (sent) {
        main.postDelayed(
          {
            if (!stopped &&
              ready &&
              lastSpeakerWriteMs == speakerMark &&
              !activeTurnHasStartedOutput &&
              !isAiSpeakingRecently() &&
              audioPlaybackEpoch.get() == turnEpoch
            ) {
              RiyaVoiceManager.speakUrgent(ctx, fallbackText)
            }
          },
          4200L,
        )
      }
      sent
    } catch (_: Throwable) {
      false
    }
  }

  fun announceSentReply(appLabel: String, senderName: String, replyText: String) {
    if (!ready || stopped) return
    val prompt =
      "বস অনুমতি দেওয়ায় $appLabel-এ $senderName-কে ঠিক এই উত্তরটি লিখে পাঠানো সম্পন্ন হয়েছে: \"$replyText\"। এখন শান্ত ও স্পষ্ট বাংলায় বসকে জানিয়ে দাও যে কোন লেখাটি পাঠানো হয়েছে।"
    try {
      suppressStaleUntilMs = 0L
      micMuteUntilMs = System.currentTimeMillis() + 450L
      val turnPayload =
        JSONObject()
          .put(
            "clientContent",
            JSONObject()
              .put(
                "turns",
                JSONArray()
                  .put(
                    JSONObject()
                      .put("role", "user")
                      .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                  ),
              )
              .put("turnComplete", true),
          )
      ws?.send(turnPayload.toString())
    } catch (_: Throwable) {}
  }

  private fun isAiSpeakingRecently(): Boolean =
    (System.currentTimeMillis() - lastSpeakerWriteMs) < 1000L

  private fun isBossSpeakingRecently(): Boolean =
    (System.currentTimeMillis() - lastBossSpeechDetectedMs) < 3500L

  private fun sendScreenNow() {
    if (!ready || !Store.screenWatch(ctx) || RiyaState.cameraOn || isAiSpeakingRecently() || isBossSpeakingRecently() || waitingServerReply) return
    if ((ws?.queueSize() ?: 0L) > 1_500L) return
    val acc = RiyaAccessibilityService.instance ?: return
    if (acc.rootInActiveWindow?.packageName?.toString() == ctx.packageName) return
    val lowNet = Store.lowNetFastMode(ctx) && isSlowNetwork()
    acc.screenshotJpeg(maxDim = if (lowNet) 300 else 380, quality = if (lowNet) 22 else 30) { jpg ->
      if (jpg != null) {
        lastShot = jpg.contentHashCode()
        sendImage(jpg)
      }
    }
  }

  private fun observeLiveLockAndScreen1s() {
    val obs = LxrCompanionAgent.sampleNow(ctx)
    val acc = RiyaAccessibilityService.instance
    val openChat = obs.openChatName?.let { " | খোলা চ্যাট: $it" }.orEmpty()
    val stateKey = "${obs.lockPhase}:${obs.foregroundPackage}:$openChat"

    if (stateKey != lastObservedPhoneStateKey) {
      lastObservedPhoneStateKey = stateKey
    }

    // Proactively detect incoming/missed voice or video calls visible on screen inside messaging apps (e.g. imo, WhatsApp, Messenger)
    if (!obs.isLocked && acc != null && ChatScreenGeometryParser.isMessagingPackage(obs.foregroundPackage)) {
      val snapshot = RiyaRuntimeBridge.latestScreenSnapshot.value
      if (snapshot.nodes.isNotEmpty() && snapshot.packageName == obs.foregroundPackage) {
        val dm = ctx.resources.displayMetrics
        val visibleCalls =
          ChatScreenGeometryParser.extractVisibleVoiceCalls(
            nodes = snapshot.nodes,
            pkg = obs.foregroundPackage,
            screenWidth = dm.widthPixels.coerceAtLeast(720),
            screenHeight = dm.heightPixels.coerceAtLeast(1280),
            openChatName = obs.openChatName,
          )
        for (vc in visibleCalls) {
          val rec = RiyaRuntimeBridge.recordDetectedVoiceCall(vc.appLabel, vc.callerName, vc.callSummaryBn)
          if (rec != null) {
            Store.remember(ctx, "[কল অ্যালার্ট][${rec.appLabel}] ${rec.summaryBn}")
            RiyaState.addLog("📞 ${rec.summaryBn}")
            val sentToLive = ready && !stopped && onVoiceCallAlert(rec.appLabel, rec.callerName, rec.summaryBn)
            if (!sentToLive) {
              RiyaVoiceManager.speakUrgent(ctx, "বস, ${rec.summaryBn}।")
            }
          }
        }
      }
    }
  }

  private fun watchScreen() {
    if (!ready || !Store.screenWatch(ctx) || RiyaState.cameraOn || isExecutingTool || activeTurnHasStartedOutput || isAiSpeakingRecently() || isBossSpeakingRecently() || waitingServerReply) return
    if ((ws?.queueSize() ?: 0L) > 500L) return
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    if (!pm.isInteractive) return
    val acc = RiyaAccessibilityService.instance ?: return
    if (acc.rootInActiveWindow?.packageName?.toString() == ctx.packageName) return
    if (LockVaultManager.describeLivePhoneState(ctx, acc).isLocked) return
    val lowNet = Store.lowNetFastMode(ctx) && isSlowNetwork()
    acc.screenshotJpeg(maxDim = if (lowNet) 300 else 380, quality = if (lowNet) 22 else 30) { jpg ->
      if (jpg != null) {
        val h = jpg.contentHashCode()
        if (h != lastShot) {
          lastShot = h
          sendImage(jpg)
        }
      }
    }
  }

  fun start() {
    searchOn = false
    lastErr = ""
    failCount = 0
    pending = false
    modelOverride = null
    greetedThisSession = false
    micMuteUntilMs = 0L
    lastSpeakerWriteMs = 0L
    lastSpeakerSoundingMs = 0L
    totalSpeakerFramesWritten = 0L
    speakerHeadBaseFrames = 0L
    lastAudibleEndFrame = 0L
    lastAudibleStopMs = 0L
    lastSpeakerOutputRms = 0.0
    hadCommandOrToolThisTurn = false
    summonedOrbToolOnly = false
    lastWakeOrMicPopupMs = System.currentTimeMillis()
    lastServerActivityMs = System.currentTimeMillis()
    lastBossTurnAtMs = 0L
    lastBossSpeechDetectedMs = 0L
    sessionStartedAtMs = System.currentTimeMillis()
    lastProactiveCheckInAtMs = 0L
    proactiveCountInStretch = 0
    waitingServerReply = false
    suppressStaleUntilMs = 0L
    activeTurnHasStartedOutput = false
    activeTurnProducedVoiceReply = false
    activeTurnBlockedByLimit = false
    notifiedReplyLimitExhausted = false
    pendingVoiceDelayUntilMs = 0L
    activeWorkStartedAtMs = 0L
    turnUnlockedMainScreenByLxr = false
    thinkingConfigCompatFallback = false
    stopped = false
    ReplyPolicyScheduler.syncFromStore(ctx, force = true)
    RiyaState.replyDelaySeconds.value = Store.replyDelaySeconds(ctx)
    RiyaState.replyLimitMax.value = Store.replyLimitMax(ctx)
    micNoiseSuppressor.reset()
    refreshSlowNetworkState()
    requestLiveAudioFocus()
    LxrCompanionAgent.start(ctx)
    LxrFloatingOverlayManager.onInterruptSpeakRequest = { interruptSpeakerForBoss() }
    RiyaVoiceManager.onBeforeLocalTtsPlay = { interruptSpeakerForBoss() }
    RiyaVoiceManager.isLiveAudioActivelyPlaying = { isAiSpeakingRecently() || activeTurnHasStartedOutput }
    try {
      audioRouteThread?.quitSafely()
    } catch (_: Throwable) {}
    val routeThread = HandlerThread("LxrAudioRoute").apply { start() }
    audioRouteThread = routeThread
    val routeHandler = Handler(routeThread.looper)
    audioRouteHandler = routeHandler
    routeAudio(allowRestart = false)
    am.registerAudioDeviceCallback(devCb, routeHandler)
    registerNetworkCallbackSafe()
    initTrack()
    startMic()
    connect()
    main.post(screenLoop)
    main.postDelayed(watchdogLoop, 4000L)
  }

  private fun requestLiveAudioFocus() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val attrs =
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req =
          AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .setWillPauseWhenDucked(false)
            .build()
        audioFocusRequest = req
        am.requestAudioFocus(req)
      } else {
        @Suppress("DEPRECATION")
        am.requestAudioFocus(
          audioFocusListener,
          AudioManager.STREAM_MUSIC,
          AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
      }
    } catch (_: Throwable) {}
  }

  private fun abandonLiveAudioFocus() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        audioFocusRequest?.let { req -> am.abandonAudioFocusRequest(req) }
        audioFocusRequest = null
      } else {
        @Suppress("DEPRECATION")
        am.abandonAudioFocus(audioFocusListener)
      }
    } catch (_: Throwable) {}
  }

  private fun registerNetworkCallbackSafe() {
    try {
      val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      netCallback?.let { old -> runCatching { cm.unregisterNetworkCallback(old) } }
      val cb =
        object : NetworkCallback() {
          override fun onAvailable(network: Network) {
            if (stopped) return
            main.post {
              pendingNetworkLostRunnable?.let { main.removeCallbacks(it) }
              pendingNetworkLostRunnable = null
            }
            refreshSlowNetworkState()
            main.post {
              if (!stopped && !ready && !pending) {
                connect()
              }
            }
          }

          override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (!stopped) refreshSlowNetworkState()
          }

          override fun onLost(network: Network) {
            if (stopped) return
            main.post {
              pendingNetworkLostRunnable?.let { main.removeCallbacks(it) }
              val checkLost =
                Runnable {
                  pendingNetworkLostRunnable = null
                  if (!stopped && ready) {
                    val cmCheck = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    if (
                      NetworkStabilityGuard.shouldTreatNetworkLossAsOffline(
                        NetworkStabilityGuard.hasValidatedInternet(cmCheck)
                      )
                    ) {
                      fail("ইন্টারনেট সংযোগ বিচ্ছিন্ন হয়েছে — পুনরায় সংযোগ হচ্ছে...")
                    }
                  }
                }
              pendingNetworkLostRunnable = checkLost
              // 2.5s debounce absorbs brief 4G <-> 4.5G carrier handoffs without false reconnect alerts
              main.postDelayed(checkLost, 2500L)
            }
          }
        }
      netCallback = cb
      val req =
        NetworkRequest.Builder()
          .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          .build()
      cm.registerNetworkCallback(req, cb)
    } catch (_: Throwable) {}
  }

  fun stop() {
    stopped = true
    ready = false
    waitingServerReply = false
    pendingNetworkLostRunnable = null
    activeWorkStartedAtMs = 0L
    audioPlaybackEpoch.incrementAndGet()
    RiyaVoiceManager.onBeforeLocalTtsPlay = null
    RiyaVoiceManager.isLiveAudioActivelyPlaying = null
    try {
      main.removeCallbacksAndMessages(null)
    } catch (_: Throwable) {}
    try {
      netCallback?.let { cb ->
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(cb)
      }
      netCallback = null
    } catch (_: Throwable) {}
    try {
      ws?.close(1000, "bye")
    } catch (_: Throwable) {}
    try {
      ws?.cancel()
    } catch (_: Throwable) {}
    ws = null

    try {
      playExec.shutdownNow()
    } catch (_: Throwable) {}
    try {
      toolExec.shutdownNow()
    } catch (_: Throwable) {}
    try {
      obsExec.shutdownNow()
    } catch (_: Throwable) {}

    synchronized(recLock) {
      rec?.let { r ->
        try {
          if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
        } catch (_: Throwable) {}
      }
    }
    try {
      recThread?.join(180)
    } catch (_: Throwable) {}
    recThread = null
    synchronized(recLock) {
      rec?.let { r ->
        try {
          r.release()
        } catch (_: Throwable) {}
      }
      rec = null
    }

    synchronized(trackLock) {
      track?.let { t ->
        try {
          t.pause()
          t.flush()
          t.stop()
        } catch (_: Throwable) {}
        try {
          t.release()
        } catch (_: Throwable) {}
      }
      track = null
    }

    abandonLiveAudioFocus()
    try {
      am.unregisterAudioDeviceCallback(devCb)
    } catch (_: Throwable) {}
    try {
      audioRouteThread?.quitSafely()
    } catch (_: Throwable) {}
    audioRouteThread = null
    audioRouteHandler = null
    try {
      if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
      else {
        @Suppress("DEPRECATION") am.stopBluetoothSco()
      }
    } catch (_: Throwable) {}
    try {
      am.mode = AudioManager.MODE_NORMAL
      @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
    } catch (_: Throwable) {}
    LxrFloatingOverlayManager.onInterruptSpeakRequest = null
    LxrFloatingOverlayManager.setSpeakingBoost(false)
    LxrFloatingOverlayManager.hide(ctx)
    RiyaState.activeWorkLabel.value = null
    RiyaState.voiceMatchLiveLabel.value = ""
  }

  fun interruptSpeakerForBoss() {
    audioPlaybackEpoch.incrementAndGet()
    voiceQueue.cancelPendingOnInterrupt()
    currentTurnReceivedFirstChunk = false
    currentTurnEntry = null
    synchronized(trackLock) {
      val t = track
      if (!stopped && t != null && t.state == AudioTrack.STATE_INITIALIZED) {
        try {
          t.pause()
          t.flush()
          t.play()
          speakerHeadBaseFrames = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
          totalSpeakerFramesWritten = speakerHeadBaseFrames
          lastAudibleEndFrame = speakerHeadBaseFrames
          lastAudibleStopMs = 0L
          lastSpeakerOutputRms = 0.0
        } catch (_: Throwable) {}
      }
    }
    micMuteUntilMs = 0L
    LxrFloatingOverlayManager.setSpeakingBoost(false)
    if (!stopped) RiyaState.status.value = "শুনছি বস..."
  }

  private fun connect() {
    val key = Store.apiKey(ctx)
    if (key.isBlank()) {
      RiyaState.status.value = "Menu থেকে API Key দিন"
      return
    }
    waitingServerReply = false
    suppressStaleUntilMs = 0L
    activeTurnHasStartedOutput = false
    activeTurnProducedVoiceReply = false
    activeTurnBlockedByLimit = false
    audioPlaybackEpoch.incrementAndGet()
    try {
      ws?.cancel()
    } catch (_: Exception) {}
    if (failCount == 0) {
      RiyaState.status.value = "Connecting…"
    }
    val url =
      "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
    var me: WebSocket? = null
    me =
      client.newWebSocket(
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
          override fun onOpen(w: WebSocket, r: Response) {
            if (w !== ws) return
            try {
              w.send(setup().toString())
            } catch (e: Exception) {
              fail("Setup fail: ${e.message?.take(40)}")
            }
          }

          override fun onMessage(w: WebSocket, text: String) {
            if (w !== ws) return
            handle(text)
          }

          override fun onMessage(w: WebSocket, bytes: ByteString) {
            if (w !== ws) return
            handle(bytes.utf8())
          }

          override fun onClosing(w: WebSocket, code: Int, reason: String) {
            if (w !== ws) return
            w.close(1000, null)
          }

          override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
            if (w !== ws) return
            fail("Error: " + t.javaClass.simpleName + " " + (t.message ?: "").take(70))
          }

          override fun onClosed(w: WebSocket, code: Int, reason: String) {
            if (w !== ws) return
            if (!ready && code == 1007) {
              if (!thinkingConfigCompatFallback) {
                thinkingConfigCompatFallback = true
              } else if (!vadConfigCompatFallback) {
                vadConfigCompatFallback = true
              } else if (!transcriptionCompatFallback) {
                transcriptionCompatFallback = true
              }
            }
            if (
              !ready &&
                code != 1000 &&
                (code == 1011 ||
                  code == 1008 ||
                  code == 1007 ||
                  reason.contains("not found", true) ||
                  reason.contains("not supported", true) ||
                  reason.contains("quota", true))
            ) {
              val curModel = modelOverride ?: Store.model(ctx)
              modelOverride =
                when {
                  curModel != Store.DEFAULT_LIVE_MODEL && curModel.contains("3.8") -> Store.DEFAULT_LIVE_MODEL
                  curModel != "gemini-2.5-flash-native-audio-preview-12-2025" ->
                    "gemini-2.5-flash-native-audio-preview-12-2025"
                  else -> modelOverride
                }
            }
            fail("বন্ধ [" + code + "] " + reason.take(90))
          }
        },
      )
    ws = me
    main.postDelayed(
      {
        if (!stopped && !ready && ws === me) {
          me?.cancel()
          fail("সেটআপের উত্তর আসেনি (২০ সেকেন্ড)")
        }
      },
      20_000L,
    )
  }

  private fun fail(msg: String) {
    ready = false
    waitingServerReply = false
    activeTurnHasStartedOutput = false
    activeTurnProducedVoiceReply = false
    activeTurnBlockedByLimit = false
    if (LxrFloatingOverlayManager.isShowing || LxrFloatingOverlayManager.orbBadgeState.value.visible) {
      LxrFloatingOverlayManager.completeTaskAndScheduleHide(ctx, hideDelayMs = 450L, keepShowingForWake = false)
    }
    if (stopped) return
    lastErr = msg
    failCount++
    // Avoid flashing "পুনরায় সংযোগ হচ্ছে…" during brief 1-2s silent socket refreshes; show only if sustained failure or offline
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val hasNet = NetworkStabilityGuard.hasValidatedInternet(cm)
    RiyaState.status.value =
      when {
        !hasNet -> "ইন্টারনেট সংযোগের অপেক্ষায়…"
        failCount <= 2 -> "শুনছি বস..."
        else -> "পুনরায় সংযোগ হচ্ছে…"
      }
    if (pending) return
    pending = true
    val retryDelayMs = (minOf(failCount, 4) * 1000L).coerceIn(1000L, 4500L)
    main.postDelayed(
      {
        pending = false
        if (!stopped) connect()
      },
      retryDelayMs,
    )
  }

  private fun setup(): JSONObject {
    val now = nowText()
    val liveSnap = LxrCompanionAgent.sampleNow(ctx)
    val screenContext =
      RiyaAccessibilityService.currentScreenSummary.ifBlank { liveSnap.toCompactContextBn() }
    val accStatus =
      if (Keep.accessibilityOn(ctx)) "ACTIVE"
      else "OFF (সত্য বলো ও চালু করতে বলো)"

    val latestCamSnap = LxrCameraVisionEngine.latestSnapshot()
    val camUsable = LxrCameraVisionGuard.evaluateFrameUsability(latestCamSnap)
    val camDesc =
      if (RiyaState.visionCameraActive.value) {
        val modeTxt = if (LxrCameraVisionEngine.isUiPreviewActive()) "Foreground UI" else "Background FGS Camera2"
        val lensTxt = if (RiyaState.useFrontCamera.value) "ফ্রন্ট" else "ব্যাক"
        when {
          !camUsable.hasFreshFrame ->
            "ON ($lensTxt • $modeTxt — ফ্রেম সংগ্রহ চলছে; তাজা ছবি না আসা পর্যন্ত অনুমান করবে না)"
          !camUsable.isReadableBrightness ->
            "ON ($lensTxt • $modeTxt — লেন্স অন্ধকার/ঢাকা, স্পষ্ট ছবি নেই)"
          RiyaState.useFrontCamera.value && camUsable.isFaceVisibleNow ->
            "ON (ফ্রন্ট • $modeTxt — তাজা ফ্রেমে বসের মুখ শনাক্ত হয়েছে, সত্য দেখে প্রিয় মানুষের মতো কথা বলো)"
          RiyaState.useFrontCamera.value ->
            "ON (ফ্রন্ট • $modeTxt — তাজা ফ্রেম আসছে, তবে মুখ এখনো ফ্রেমে শনাক্ত হয়নি; যা দেখা যাচ্ছে শুধু তাই বলো)"
          else ->
            "ON (ব্যাক • $modeTxt — তাজা লাইভ দৃশ্য আসছে)"
        }
      } else {
        "OFF (বস ক্যামেরা দিয়ে দেখতে চাইলে `inspect_camera_vision` বা `switch_camera_lens(turn_on=true)` চালাবে)"
      }
    val writePerm = if (LxrDeviceAndHardwareEngine.isWriteSettingsGranted(ctx)) "GRANTED" else "OFF"
    val assistStatus = if (LxrDeviceAndHardwareEngine.isDefaultAssistant(ctx)) "DEFAULT" else "NOT_DEFAULT"
    val deviceAdminStatus = if (LxrDeviceAndHardwareEngine.isDeviceAdminActive(ctx)) "ACTIVE" else "OFF"
    val exactAlarmStatus = if (LxrDeviceAndHardwareEngine.canScheduleExactAlarms(ctx)) "GRANTED" else "OFF"
    val installedSummary = LxrAppDiscoveryEngine.buildInstalledAppsSummaryForPrompt(ctx, maxNames = 72)
    val extraModel = Store.extraCriticalModel(ctx)

    val delayOpt = Store.replyDelayOption(ctx)
    val replySpeedDesc =
      when (delayOpt) {
        ReplyDelayOption.INSTANT -> "Instant (0 sec — শূন্য দেরি! বসের কথা শেষ হওয়া মাত্র ০ সেকেন্ড দেরি ছাড়াই তড়িৎ ও স্পষ্ট বাক্যে উত্তর দাও)"
        ReplyDelayOption.FAST -> "Fast (8 sec — প্রতিটি কমান্ডের জন্য ৮ সেকেন্ড বিলম্ব নীতি মেনে FIFO কিউতে উত্তর দাও)"
        ReplyDelayOption.NORMAL -> "Normal (15 sec — প্রতিটি কমান্ডের জন্য ১৫ সেকেন্ড বিলম্ব নীতি মেনে FIFO কিউতে উত্তর দাও)"
      }

    val sys =
      """
      [LIVE SYSTEM RUNTIME]
      - Spoken Name: "LxR" ("আমি LxR") | App: LXR--AI (v2.39.0 — Personal AI Operating System Controller)
      - Device: ${InfinixDevicePlaybook.DEVICE_PROFILE} | Accessibility: $accStatus | ModifySystemSettings: $writePerm | DefaultAssistant: $assistStatus | DeviceAdmin: $deviceAdminStatus | ExactAlarms: $exactAlarmStatus
      - Installed Apps Snapshot: $installedSummary
      - Live Observation: EVERY 0.1s (BACKGROUND + AssistStructure Bridge + Non-Blocking FIFO Voice Queue) | Camera: $camDesc | Extra Critical AI Model: $extraModel | Reply Speed: $replySpeedDesc
      - Live State: ${liveSnap.toCompactContextBn()}
      - Boss Voice Match: ${if (VoiceMatchManager.isActiveGateReady(ctx)) "ACTIVE (শুধু বসের কণ্ঠের কমান্ড মানো)" else "OFF"}
      - DIRECT AUTONOMOUS EXECUTION: বসের কমান্ড পেলেই অযথা প্রশ্ন/সম্মতি না চেয়ে পূর্ব-অনুমোদিত ক্ষমতা ব্যবহার করে সরাসরি কাজ শুরু করো এবং বাস্তব প্রমাণ মিললে তবেই সম্পন্ন বলো।
      - MANDATORY "বস" + "তুমি" & NATURAL SPEECH DISCIPLINE: বসকে সর্বদা "বস" এবং "তুমি / তোমার / তোমাকে / করো / বলো" বলে ডাকো (কখনোই আনুষ্ঠানিক আপনা/আপনি সম্বোধন ব্যবহার করবে না)। কথা বলার মাঝখানে থেমে যেও না, প্রতিটি বাক্য পরিষ্কার ও সম্পূর্ণ করে বলো। বস "শোনো", "থামো", "স্টপ", "বাই", "stop", "wait" বা "bye" বললে সাথে সাথে থেমে নিঃশব্দে বসের কথা শোনো।
      - ANTI-LIE APP DISCOVERY RULE: কখনোই `list_installed_apps` বা `open_app` টুল না চালিয়ে অনুমান করে বলবে না যে ফোনে কোনো অ্যাপ ইনস্টল করা নেই! আগে সবসময় আসল প্যাকেজ ম্যানেজার ও লঞ্চার যাচাই করো।
      - HONEST LIVE CAPABILITY INTROSPECTION RULE: বস "তুমি কী কী করতে পারো?" বা "কোন কোন অ্যাপ পরিচালনা করতে পারো?" জিজ্ঞেস করলে `get_capabilities_a_to_z` কল করে আসল পারমিশন, সার্ভিস ও অ্যাপ যাচাইসহ ৪টি স্তরে (সম্পূর্ণ সক্রিয়, আংশিক সক্রিয়, অনুমতি প্রয়োজন, অনুপলব্ধ) সত্য রিপোর্ট বলবে — অসমর্থিত বা বন্ধ ফিচারকে কখনোই সক্রিয় বলবে না!
      - ACTIVITY MONITOR & VERIFICATION RULE: প্রতিটি কাজ LXR Activity Monitor-এ ৯টি ধাপের তথ্যসহ (প্রাপ্ত/শুরু সময়, Background/Foreground মোড, অপারেশন, অগ্রগতি, অবস্থা, ফলাফল, মোট সময়, ত্রুটি) সংরক্ষণ করা হয়। বস "কী করেছো?", "What did you do?" বা "তথ্য দেখাও" বললে `get_activity_monitor` কল করে প্রকৃত স্ট্যাটাস ও যাচাইকৃত ফলাফল জানাবে। স্বাধীন ব্যাকগ্রাউন্ড কাজের জন্য `run_background_task` ব্যবহার করো।
      - STRICT VISION & SMART GPS ZERO-HALLUCINATION RULE: কখনোই না দেখে বস বা আশেপাশের কোনো চেহারা, পোশাক বা বস্তু কল্পনা করে বর্ণনা দেবে না! ক্যামেরা দিয়ে দেখতে বললে `inspect_camera_vision` কল করবে, আর লোকেশন জানতে চাইলে `get_live_location` কল করে কেবল বাস্তব ঠিকানা ও স্থানাঙ্ক বলবে।
      - STRICT SILENT LOCK & EXECUTION DISCIPLINE:
        * স্ক্রিন আনলক অবস্থায় কখনোই "লক খোলা আছে", "স্ক্রিন লক করা আছে" বা "Slide again to unlock" বলবে না এবং আনলক হোম/অ্যাপ স্ক্রিনে কখনোই সোয়াইপ করবে না।
        * স্ক্রিন সত্যিই লক থাকাকালে ব্যাকগ্রাউন্ড API কাজ সরাসরি ব্যাকগ্রাউন্ডেই সম্পন্ন করো; শুধু অপরিহার্য UI ইন্টারঅ্যাকশন হলে বৈধ আনলক ব্যবস্থা ব্যবহার করে কাজ শেষে রিলক করবে।
      - SELF-DIAGNOSIS & SELF-REPAIR PERMISSION RULE: নিজের কোনো অনুমতি/সার্ভিসে (যার মধ্যে Device Admin ও Alarms & reminders আছে) সমস্যা বা ব্যর্থতা হলে প্রথমে `diagnose_and_fix_self(mode="diagnose")` দিয়ে কারণ বুঝে বসকে স্পষ্ট বলো এবং বসের অনুমতি নিয়ে `diagnose_and_fix_self(mode="fix", issue_key=...)` দিয়ে সমাধান করো।

      ${RiyaPersona.TEXT}

      [RUNTIME MEMORY & CLOCK]
      - Time: $now | Current Screen: [$screenContext]
      - ৬ মাসের স্মৃতি (১৮০ দিন): পুরনো কোনো কথা/সূত্র/তারিখ নিয়ে প্রশ্ন করলে অনুমান না করে `search_memory` টুল চালাবে; আর মুছতে বললে `delete_memory_entry` চালাবে।
      ${Store.build180DayDigest(ctx, 2200)}
      ${Store.recent(ctx, 4000)}
      """
        .trimIndent()

    val voiceName = Store.voice(ctx).ifBlank { "Aoede" }
    val activeModel = modelOverride ?: Store.model(ctx)
    val s = JSONObject()
    s.put("model", "models/$activeModel")

    val genConfig = JSONObject()
    genConfig.put("responseModalities", JSONArray().put("AUDIO"))
    val speechConfig =
      JSONObject()
        .put(
          "voiceConfig",
          JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName)),
        )
    genConfig.put("speechConfig", speechConfig)
    if (!thinkingConfigCompatFallback && activeModel.contains("3.1")) {
      genConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "MINIMAL"))
    }
    s.put("generationConfig", genConfig)

    if (!vadConfigCompatFallback) {
      s.put(
        "realtimeInputConfig",
        LiveVadConfig.buildRealtimeInputConfigJson(
          silenceMs = LiveVadConfig.effectiveSilenceDurationMs(delayOpt),
        ),
      )
    }
    if (!transcriptionCompatFallback) {
      s.put("inputAudioTranscription", JSONObject())
      s.put("outputAudioTranscription", JSONObject())
    }

    s.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
    s.put("tools", JSONArray(toolsJson()))
    return JSONObject().put("setup", s)
  }

  private fun handle(msg: String) {
    try {
      lastServerActivityMs = System.currentTimeMillis()
      val j = JSONObject(msg)
      j.optJSONObject("error")?.let {
        fail("সার্ভার ত্রুটি: " + it.optString("message", it.toString()).take(100))
        return
      }
      if (j.has("setupComplete")) {
        ready = true
        failCount = 0
        lastErr = ""
        RiyaState.status.value = "শুনছি বস..."
        if (!greetedThisSession) {
          greetedThisSession = true
          sendGreetingTurn()
        }
      }
      j.optJSONObject("serverContent")?.let { sc ->
        if (sc.optBoolean("interrupted")) {
          waitingServerReply = false
          suppressStaleUntilMs = 0L
          activeTurnHasStartedOutput = false
          currentTurnReceivedFirstChunk = false
          currentTurnEntry = null
          activeTurnProducedVoiceReply = false
          activeTurnBlockedByLimit = false
          interruptSpeakerForBoss()
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.let {
          lastBossSpeechDetectedMs = System.currentTimeMillis()
          proactiveCountInStretch = 0
          uText.append(it)
          val snippet = uText.toString().takeLast(64)
          val lxrSpeakingNow =
            activeTurnHasStartedOutput || isAiSpeakingRecently() || RiyaVoiceManager.isActivelySpeaking()
          if (
            lxrSpeakingNow &&
              (LxrSpeechInterruptGuard.containsInterruptCommand(it) ||
                LxrSpeechInterruptGuard.containsInterruptCommand(snippet))
          ) {
            suppressStaleUntilMs = System.currentTimeMillis() + 2600L
            activeTurnHasStartedOutput = false
            currentTurnReceivedFirstChunk = false
            activeTurnProducedVoiceReply = false
            activeTurnBlockedByLimit = false
            waitingServerReply = false
            pendingVoiceDelayUntilMs = 0L
            interruptSpeakerForBoss()
            RiyaVoiceManager.stopSpeaking()
          }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.let {
          if (!isSuppressingStaleAudio() && !activeTurnBlockedByLimit) {
            pText.append(it)
          }
        }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { ps ->
          waitingServerReply = false
          if (isSuppressingStaleAudio()) {
            // Discard stale residual audio packets briefly during active Boss barge-in
          } else {
            val isFirstChunkOfTurn = !currentTurnReceivedFirstChunk
            if (isFirstChunkOfTurn) {
              ReplyPolicyScheduler.syncFromStore(ctx)
              val limitMax = ReplyPolicyScheduler.currentLimitMax(ctx)
              if (limitMax > 0 && !ReplyPolicyScheduler.canSendReply(limitMax)) {
                activeTurnBlockedByLimit = true
                activeTurnProducedVoiceReply = false
                currentTurnReceivedFirstChunk = true
                activeTurnHasStartedOutput = false
                pendingVoiceDelayUntilMs = 0L
                RiyaState.status.value = "Reply Limit পূর্ণ ($limitMax/$limitMax) — Reset করো"
                if (!notifiedReplyLimitExhausted) {
                  notifiedReplyLimitExhausted = true
                  RiyaState.addLog("Reply Limit ($limitMax/$limitMax) পূর্ণ হয়েছে — Reset বা Unlimited না করা পর্যন্ত উত্তর বন্ধ।")
                }
                return@let
              }
              activeTurnBlockedByLimit = false
              activeTurnProducedVoiceReply = false
              currentTurnReceivedFirstChunk = true
              RiyaVoiceManager.stopSpeaking()
              val entry = voiceQueue.popNextPendingForTurn(defaultDelayMs = 0L)
              currentTurnEntry = entry
            }
            if (activeTurnBlockedByLimit) return@let

            val turnEntry = currentTurnEntry
            val remainingMs =
              if (isFirstChunkOfTurn && turnEntry != null) {
                voiceQueue.computeRemainingDelayMs(turnEntry)
              } else if (isFirstChunkOfTurn && pendingVoiceDelayUntilMs > 0L) {
                val r = (pendingVoiceDelayUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
                pendingVoiceDelayUntilMs = 0L
                r
              } else {
                0L
              }
            val holdUntilMs =
              if (remainingMs > 0L) System.currentTimeMillis() + remainingMs
              else {
                // Only set activeTurnHasStartedOutput immediately when there is zero delay wait;
                // during an active 8s/15s delay wait, the mic stays completely live and unmuted!
                activeTurnHasStartedOutput = true
                0L
              }
            val turnEpoch = audioPlaybackEpoch.get()
            for (i in 0 until ps.length()) {
              ps.getJSONObject(i).optJSONObject("inlineData")?.let {
                val pcm = Base64.decode(it.getString("data"), Base64.DEFAULT)
                if (!stopped && !activeTurnBlockedByLimit) {
                  val pcmRms = computeRms16Bit(pcm, pcm.size)
                  val applyDelay = isFirstChunkOfTurn && i == 0 && holdUntilMs > 0L
                  if (!applyDelay && holdUntilMs == 0L) {
                    RiyaState.status.value = "LXR--AI কথা বলছে..."
                    LxrFloatingOverlayManager.setSpeakingBoost(true)
                    if (LxrFloatingOverlayManager.isShowing) {
                      LxrFloatingOverlayManager.cancelPendingHide()
                    }
                  }
                  try {
                    if (!playExec.isShutdown) {
                      playExec.execute {
                        if (stopped || activeTurnBlockedByLimit || isSuppressingStaleAudio() || turnEpoch != audioPlaybackEpoch.get()) return@execute
                        if (applyDelay) {
                          RiyaState.status.value = "রিপ্লাই ডিলে (${(remainingMs / 1000L).coerceAtLeast(1)}s) — শুনছি বস..."
                          var remain = holdUntilMs - System.currentTimeMillis()
                          while (remain > 0L && !stopped && !isSuppressingStaleAudio() && turnEpoch == audioPlaybackEpoch.get()) {
                            val step = minOf(remain, 50L)
                            try {
                              Thread.sleep(step)
                            } catch (_: Throwable) {
                              break
                            }
                            remain = holdUntilMs - System.currentTimeMillis()
                          }
                          if (stopped || activeTurnBlockedByLimit || isSuppressingStaleAudio() || turnEpoch != audioPlaybackEpoch.get()) return@execute
                        }
                        if (turnEntry != null && turnEntry.playbackStartedAtMs == 0L) {
                          voiceQueue.markPlaybackStarted(turnEntry)
                        }
                        activeTurnHasStartedOutput = true
                        RiyaState.status.value = "LXR--AI কথা বলছে..."
                        LxrFloatingOverlayManager.setSpeakingBoost(true)
                        if (LxrFloatingOverlayManager.isShowing) {
                          LxrFloatingOverlayManager.cancelPendingHide()
                        }
                        pendingAudioChunks.incrementAndGet()
                        try {
                          val t = track
                          if (!stopped && t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                            try {
                              if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                t.play()
                              }
                              lastSpeakerWriteMs = System.currentTimeMillis()
                              var wrote = t.write(pcm, 0, pcm.size)
                              if (wrote <= 0 && !stopped && turnEpoch == audioPlaybackEpoch.get()) {
                                runCatching { t.play() }
                                wrote = t.write(pcm, 0, pcm.size)
                              }
                              if (wrote > 0 && turnEpoch == audioPlaybackEpoch.get()) {
                                activeTurnProducedVoiceReply = true
                                val afterWriteMs = System.currentTimeMillis()
                                lastSpeakerWriteMs = afterWriteMs
                                val frames = (wrote / 2).toLong()
                                totalSpeakerFramesWritten += frames
                                if (pcmRms >= 28.0) {
                                  lastAudibleEndFrame = maxOf(lastAudibleEndFrame, totalSpeakerFramesWritten + 4800L)
                                  lastSpeakerOutputRms = pcmRms
                                  lastSpeakerSoundingMs = afterWriteMs
                                }
                              }
                            } catch (_: Throwable) {}
                          }
                        } finally {
                          pendingAudioChunks.decrementAndGet()
                        }
                      }
                    }
                  } catch (_: Throwable) {}
                }
              }
            }
          }
        }
        if (sc.optBoolean("turnComplete")) {
          val wasBlockedByLimit = activeTurnBlockedByLimit
          val producedReplyThisTurn = !wasBlockedByLimit && (activeTurnProducedVoiceReply || pText.isNotEmpty())
          suppressStaleUntilMs = 0L
          activeTurnHasStartedOutput = false
          currentTurnReceivedFirstChunk = false
          activeTurnProducedVoiceReply = false
          activeTurnBlockedByLimit = false
          LxrFloatingOverlayManager.setSpeakingBoost(false)
          val u = uText.toString().trim()
          val p = pText.toString().trim()
          currentTurnEntry?.let { entry ->
            voiceQueue.markCompleted(entry, replyText = p)
          }
          currentTurnEntry = null
          val pureWake = u.isNotEmpty() && LxrFloatingOverlayManager.isPureWakeCall(u) && !hadCommandOrToolThisTurn
          if (u.isNotEmpty()) {
            Store.remember(ctx, "Boss: $u")
            RiyaState.addLog("বস: $u")
          }
          if (p.isNotEmpty()) {
            Store.remember(ctx, "LXR--AI: $p")
            RiyaState.addLog("LXR--AI: $p")
          }
          if (producedReplyThisTurn) {
            ReplyPolicyScheduler.tryConsumeAndPersist(ctx, ReplyPolicyScheduler.currentLimitMax(ctx))
          }

          val activeTrack = track
          val remainingAudioMs =
            if (activeTrack != null && activeTrack.state == AudioTrack.STATE_INITIALIZED) {
              val head = (activeTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
              val effectiveRate = (24000f * playbackSpeed).toLong().coerceAtLeast(12000L)
              ((totalSpeakerFramesWritten - head).coerceAtLeast(0L) * 1000L) / effectiveRate
            } else {
              0L
            }

          // Re-lock ONLY if LXR itself physically unlocked Boss's locked MAIN screen to run a background task!
          val didUnlockMainScreenThisTurn = turnUnlockedMainScreenByLxr
          val relockAfterTurn =
            LockVaultManager.shouldAutoRelockAfterTask(
              startedWithLockedScreen = didUnlockMainScreenThisTurn,
              autoUnlockEnabled = Store.autoUnlockEnabled(ctx),
            )
          if (relockAfterTurn && hadCommandOrToolThisTurn) {
            val relockDelay = (remainingAudioMs + 700L).coerceIn(900L, 4500L)
            main.postDelayed(
              {
                if (!stopped && didUnlockMainScreenThisTurn) {
                  val lockedOk = LockVaultManager.relockScreen(ctx, RiyaAccessibilityService.instance)
                  if (lockedOk) {
                    RiyaState.addLog("LXR--AI: মেইন স্ক্রিন লক ছিল — কাজ শেষে আবার স্ক্রিন লক করা হয়েছে ✓")
                    if (!ready) {
                      RiyaVoiceManager.speakUrgent(
                        ctx,
                        "বস, তোমার ফোনের মেইন স্ক্রিন আগে লক অবস্থায় ছিল, তাই কাজ শেষ করে আবার লক করে দিয়েছি।",
                      )
                    }
                  }
                }
              },
              relockDelay,
            )
          }

          if (LxrFloatingOverlayManager.isShowing || LxrFloatingOverlayManager.orbBadgeState.value.visible) {
            val hideDelay =
              if (summonedOrbToolOnly) 4000L
              else (remainingAudioMs + 950L).coerceIn(850L, 4600L)
            LxrFloatingOverlayManager.completeTaskAndScheduleHide(
              ctx,
              hideDelayMs = hideDelay,
              keepShowingForWake = false,
            )
          }

          waitingServerReply = false
          hadCommandOrToolThisTurn = false
          summonedOrbToolOnly = false
          turnUnlockedMainScreenByLxr = false
          uText.setLength(0)
          pText.setLength(0)
          if (!isExecutingTool && RiyaState.activeWorkLabel.value != null) {
            RiyaState.activeWorkLabel.value = null
            LxrFloatingOverlayManager.updateActiveWorkBanner(ctx, null)
          }
          val restoreEpoch = audioPlaybackEpoch.get()
          val statusRestoreDelay = if (remainingAudioMs > 180L) (remainingAudioMs + 150L).coerceIn(180L, 5000L) else 0L
          if (statusRestoreDelay == 0L) {
            RiyaState.status.value = "শুনছি বস..."
          } else {
            main.postDelayed(
              {
                if (!stopped && ready && !activeTurnHasStartedOutput && audioPlaybackEpoch.get() == restoreEpoch) {
                  RiyaState.status.value = "শুনছি বস..."
                }
              },
              statusRestoreDelay,
            )
          }
        }
      }
      j.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { fc ->
        hadCommandOrToolThisTurn = true
        waitingServerReply = false
        suppressStaleUntilMs = 0L
        summonedOrbToolOnly =
          fc.length() == 1 && fc.getJSONObject(0).optString("name") == "show_wake_orb"
        LxrFloatingOverlayManager.cancelPendingHide()
        toolExec.execute {
          try {
            isExecutingTool = true
            activeWorkStartedAtMs = System.currentTimeMillis()
            val out = JSONArray()
            for (i in 0 until fc.length()) {
              val c = fc.getJSONObject(i)
              val callId = c.optString("id")
              val nm = c.optString("name")
              val argsObj = c.optJSONObject("args") ?: JSONObject()
              val workBanner = LxrToolWorkDescriber.describeBn(nm, argsObj)
              val isReadingScreen = LxrToolWorkDescriber.isScreenReadingTool(nm)
              RiyaState.activeWorkLabel.value = workBanner
              LxrFloatingOverlayManager.showForTask(ctx, workBanner, isReadingScreen = isReadingScreen)
              val r =
                try {
                  runTool(nm, argsObj)
                } catch (e: Throwable) {
                  "টুল ত্রুটি: ${e.message ?: "unknown error"}"
                }
              out.put(
                JSONObject()
                  .put("id", callId)
                  .put("name", nm)
                  .put("response", JSONObject().put("result", r))
              )
            }
            ws?.send(
              JSONObject().put("toolResponse", JSONObject().put("functionResponses", out)).toString()
            )
            lastServerActivityMs = System.currentTimeMillis()
          } catch (_: Throwable) {
          } finally {
            isExecutingTool = false
            activeWorkStartedAtMs = 0L
            RiyaState.activeWorkLabel.value = null
            LxrFloatingOverlayManager.setScreenReading(ctx, false, taskLabelOverride = null)
            if (!stopped && !summonedOrbToolOnly) {
              LxrFloatingOverlayManager.scheduleIdleFailsafeHide(ctx, delayMs = 4500L)
            }
            if (!stopped && ready && !activeTurnHasStartedOutput) {
              RiyaState.status.value = "শুনছি বস..."
            }
          }
        }
      }
    } catch (_: Exception) {}
  }

  private val actionTools =
    setOf(
      "open_app",
      "find_and_open_item",
      "click_text",
      "type_text",
      "global_action",
      "scroll",
      "play_youtube",
      "call_contact",
      "control_wifi",
      "control_bluetooth",
      "control_device_setting",
      "fill_form_fields",
      "facebook_action",
      "instagram_action",
      "open_navigation_directions",
    )
  private val needsFind = setOf("find_and_open_item", "click_text")

  private fun screenSnapshot(): String {
    val acc = RiyaAccessibilityService.instance ?: return ""
    LxrFloatingOverlayManager.setScreenReading(ctx, true)
    val s = acc.readScreen()
    val openChat = MessageAgent.extractOpenChatName(acc)?.let { " | খোলা চ্যাট: $it" }.orEmpty()
    RiyaAccessibilityService.currentScreenSummary =
      "বর্তমান অ্যাপ: ${RiyaAccessibilityService.currentApp}$openChat | স্ক্রিন দৃশ্য: " +
        s.replace('\n', ',').take(300)
    return s.take(2500)
  }

  private fun runTool(name: String, a: JSONObject): String {
    val isBackgroundIndependent =
      name in
        setOf(
          "current_time",
          "remember",
          "search_memory",
          "delete_memory_entry",
          "web_search",
          "deep_research",
          "get_battery_level",
          "get_capabilities_a_to_z",
          "get_activity_monitor",
          "run_background_task",
          "get_live_location",
          "get_contacts_summary",
          "get_call_log_summary",
          "get_device_hardware_info",
          "list_installed_apps",
          "schedule_exact_reminder",
          "critical_task_solve",
          "prepare_app_requirements",
          "check_termux_build_status",
        )
    if (isBackgroundIndependent) {
      return LxrBackgroundTaskManager.recordAndVerifyToolExecution(
        ctx = ctx,
        toolName = name,
        args = a,
        executionMode = "BACKGROUND",
      ) {
        runToolRaw(name, a)
      }
    }
    return synchronized(uiToolLock) {
      runToolUiSynchronized(name, a)
    }
  }

  private fun runToolUiSynchronized(name: String, a: JSONObject): String {
    var acc = RiyaAccessibilityService.instance
    val skipAutoUnlockForGlobalNav =
      name == "global_action" &&
        a.optString("action").trim().lowercase() in setOf("lock", "home", "back", "recents", "notifications")
    val genuinelyLocked =
      if (skipAutoUnlockForGlobalNav) false
      else LockVaultManager.isMainScreenGenuinelyLockedBeforeTask(ctx, acc)
    val explicitUnlock = a.optBoolean("explicit", false)
    val shouldAutoUnlock =
      UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool(
        toolName = name,
        genuinelyLocked = genuinelyLocked,
        explicitUnlockArg = explicitUnlock,
      )
    if (shouldAutoUnlock) {
      val unlockRes = LockVaultManager.unlockDeviceIfLocked(ctx, acc)
      if (unlockRes.status == UnlockStatus.UNLOCKED_SUCCESSFULLY) {
        turnUnlockedMainScreenByLxr = true
        // Wait briefly for keyguard dismissal and refresh screen tree so tools read live UI immediately
        val settleDeadline = System.currentTimeMillis() + 550L
        while (System.currentTimeMillis() < settleDeadline) {
          acc = RiyaAccessibilityService.instance
          if (LockVaultManager.isScreenUnlockedNow(ctx, acc)) break
          Thread.sleep(90L)
        }
        acc?.readEntireScreenTree(includeAllWindows = true)
      } else if (unlockRes.status != UnlockStatus.ALREADY_UNLOCKED) {
        return unlockRes.messageBn
      }
    }

    // Autonomous App Lock detection BEFORE running physical messaging/app tools when already unlocked
    if (
      acc != null &&
        name in setOf("open_app", "send_message", "inspect_contact_chat", "unlock_locked_app", "play_youtube") &&
        LockVaultManager.isAppLockScreenShowing(acc)
    ) {
      LockVaultManager.unlockAppLockIfPresent(ctx, acc)
      Thread.sleep(160L)
    }

    val watch = Store.screenWatch(ctx) && acc != null && Keep.accessibilityOn(ctx)
    if (!watch || name !in actionTools) {
      val r =
        LxrBackgroundTaskManager.recordAndVerifyToolExecution(
          ctx = ctx,
          toolName = name,
          args = a,
          executionMode = if (acc != null) "FOREGROUND" else "BACKGROUND",
        ) { runToolRaw(name, a) }
      if (
        acc != null &&
          (name == "open_app" || name == "send_message" || name == "inspect_contact_chat") &&
          LockVaultManager.isAppLockScreenShowing(acc)
      ) {
        LockVaultManager.unlockAppLockIfPresent(ctx, acc)
        Thread.sleep(180L)
      }
      return if (watch && name == "current_screen") r + "\n[স্ক্রিন এখন]:\n" + screenSnapshot() else r
    }
    RiyaState.status.value = "স্ক্রিন দেখছি বস…"
    val before = screenSnapshot()
    if (name in needsFind) {
      val target = a.optString(if (name == "click_text") "text" else "name")
      try {
        acc?.scrollUntilFound(target)
      } catch (_: Exception) {}
    }
    val r =
      LxrBackgroundTaskManager.recordAndVerifyToolExecution(
        ctx = ctx,
        toolName = name,
        args = a,
        executionMode = "FOREGROUND",
      ) { runToolRaw(name, a) }
    Thread.sleep(if (name == "open_app" || name == "play_youtube") 260L else 150L)
    if (
      (name == "open_app" || name == "send_message" || name == "inspect_contact_chat" || name == "play_youtube") &&
        LockVaultManager.isAppLockScreenShowing(acc)
    ) {
      LockVaultManager.unlockAppLockIfPresent(ctx, acc)
      Thread.sleep(220L)
    }
    var after = screenSnapshot()
    if (name in needsFind && (r.contains("খুঁজে পাওয়া যায়নি") || r.contains("সার্চ বাটন চাপতে পারিনি"))) {
      val r2 =
        LxrBackgroundTaskManager.recordAndVerifyToolExecution(
          ctx = ctx,
          toolName = name,
          args = a,
          executionMode = "FOREGROUND",
        ) { runToolRaw(name, a) }
      Thread.sleep(160)
      after = screenSnapshot()
      return "$r2\n[আগের স্ক্রিন]:\n${before.take(800)}\n[এখনকার স্ক্রিন]:\n$after"
    }
    return "$r\n[এখনকার স্ক্রিন]:\n$after"
  }

  private fun runToolRaw(name: String, a: JSONObject): String =
    try {
      val acc = if (Keep.accessibilityOn(ctx)) RiyaAccessibilityService.instance else null
      val noAcc = "Accessibility সার্ভিস বন্ধ আছে বস! দয়া করে LXR--AI Accessibility চালু করে দিন।"
      when (name) {
        "open_app" -> openApp(a.optString("app_name"))
        "send_message" -> messageSend(a)
        "inspect_contact_chat" -> {
          if (acc == null) noAcc
          else MessageAgent.inspectContactChat(ctx, acc, a.optString("app_name"), a.optString("contact_name"))
        }
        "unlock_phone" -> {
          val explicit = a.optBoolean("explicit", false)
          val genuinelyLocked = LockVaultManager.isMainScreenGenuinelyLockedBeforeTask(ctx, acc)
          val appLocked = LockVaultManager.isAppLockScreenShowing(acc)
          when {
            turnUnlockedMainScreenByLxr && !appLocked -> {
              turnUnlockedMainScreenByLxr = false
              "ফোনের স্ক্রিন আনলক সম্পন্ন হয়েছে।"
            }
            !genuinelyLocked && !appLocked -> {
              if (!turnUnlockedMainScreenByLxr) turnUnlockedMainScreenByLxr = false
              "ফোনের স্ক্রিন আগে থেকেই খোলা আছে (অযথা আনলক করার দরকার নেই)।"
            }
            !explicit ->
              "বসের স্পষ্ট 'আনলক করো' নির্দেশ না থাকায় স্বয়ংক্রিয় আনলক করা হয়নি।"
            else -> {
              turnUnlockedMainScreenByLxr = false
              val devRes = LockVaultManager.unlockDeviceIfLocked(ctx, acc)
              if (
                (devRes.status == UnlockStatus.ALREADY_UNLOCKED ||
                  devRes.status == UnlockStatus.UNLOCKED_SUCCESSFULLY) &&
                  LockVaultManager.isAppLockScreenShowing(acc)
              ) {
                val appRes = LockVaultManager.unlockAppLockWithResult(ctx, acc)
                "${devRes.messageBn} এবং ${appRes.messageBn}"
              } else {
                devRes.messageBn
              }
            }
          }
        }
        "lock_phone" -> {
          if (LockVaultManager.relockScreen(ctx, acc)) {
            turnUnlockedMainScreenByLxr = false
            "হয়েছে, বস! কাজ শেষ — ফোনের মেইন স্ক্রিন লক করে দিয়েছি।"
          } else {
            "স্ক্রিন লক করা যায়নি বস, Device Admin বা Accessibility পারমিশন যাচাই করো।"
          }
        }
        "unlock_locked_app" -> LockVaultManager.unlockAppLockWithResult(ctx, acc).messageBn
        "show_wake_orb" -> {
          lastWakeOrMicPopupMs = System.currentTimeMillis()
          val shown = LxrFloatingOverlayManager.showForWakeOrMic(ctx)
          if (shown) {
            "হয়েছে, বস! ৫০ শতাংশ স্বচ্ছ ঘূর্ণায়মান LxR অরব এখন স্ক্রিনের ওপর ভেসে উঠেছে।"
          } else if (!Store.floatingOrbEnabled(ctx)) {
            "LxR ভাসমান অরব সেটিংস থেকে বন্ধ করা আছে বস।"
          } else {
            "অন্য অ্যাপের ওপর LxR অরব দেখাতে 'Display over other apps' অনুমতি চালু করো বস।"
          }
        }
        "get_capabilities_a_to_z" -> LxrCapabilityInspector.buildLiveCapabilityReportBn(ctx)
        "get_activity_monitor" -> LxrBackgroundTaskManager.queryActivityHistory(ctx, a.optString("query"))
        "run_background_task" -> {
          val cmd = a.optString("command").ifBlank { "system_audit" }
          val target = a.optString("target_app").ifBlank { "LXR--AI" }
          val q = a.optString("query")
          val rec =
            LxrBackgroundTaskManager.enqueueBackgroundTask(
              ctx = ctx,
              command = cmd,
              targetApp = target,
              operation = "ব্যাকগ্রাউন্ড টাস্ক কিউ: $cmd",
              scheduleOsJob = true,
            ) {
              val output =
                when (cmd.lowercase()) {
                  "deep_research" ->
                    if (q.isNotBlank()) LxrResearchEngine.research(ctx, q, "auto").summaryBn
                    else "ব্যাকগ্রাউন্ড রিসার্চের জন্য কোনো বিষয় দেওয়া হয়নি।"
                  "universal_app_check" -> {
                    val plan = LxrUniversalAppController.planAction(ctx, q.ifBlank { target }, cmd)
                    "অ্যাপ: ${plan.resolvedAppLabel} | ইনস্টল: ${if (plan.isInstalled) "হ্যাঁ (${plan.resolvedPackage})" else "না"} | পথ: ${plan.recommendedMethod}" +
                      (plan.restrictionWarningBn?.let { " | নোট: $it" } ?: "")
                  }
                  else -> LxrCapabilityInspector.buildLiveCapabilityReportBn(ctx)
                }
              output to emptyList()
            }
          "হয়েছে, বস! ব্যাকগ্রাউন্ড টাস্ক '${rec.command}' কিউ করা হয়েছে (ID: ${rec.taskId}, অবস্থা: ${rec.status.labelBn})। LXR Activity Monitor-এ অগ্রগতি দেখতে পাবে।"
        }
        "prepare_app_requirements" -> {
          val idea = a.optString("idea").ifBlank { "Custom Android App" }
          val appName = a.optString("preferred_name").ifBlank { "কাস্টম নাম" }
          val theme = a.optString("theme").ifBlank { "Dark / Light / Cyber" }
          val features = a.optString("features").ifBlank { "হোম স্ক্রিন, ডেটা স্টোরেজ ও প্রধান ফিচার" }
          "নতুন অ্যাপ '$idea' বানানোর আগে বসের কাছে সংক্ষেপে এই ৩টি বিষয় নিশ্চিত করো:\n" +
            "১) অ্যাপের নাম: '$appName' নাকি অন্য কোনো নাম দেবো?\n" +
            "২) কালার থিম ও ডিজাইন: Dark Neon, Cyber Blue নাকি Light Elegant?\n" +
            "৩) মূল ফিচার: $features — এর সাথে আর কিছু যোগ করতে হবে কি?\n" +
            "বস 'হ্যাঁ / ঠিক আছে' বললেই `build_android_app` দিয়ে কোড তৈরি ও Termux APK বিল্ড শুরু করো।"
        }
        "build_android_app" ->
          AppBuilderRuntimeBridge.startVoiceAppBuild(
            context = ctx,
            prompt = a.optString("prompt").ifBlank { "Smart Android App" },
            requestedName = a.optString("app_name").ifBlank { null },
            requestedCategory = a.optString("category").ifBlank { null },
            shouldAutoBuildTermux = a.optBoolean("auto_build_termux", true),
          )
        "check_termux_build_status" -> AppBuilderRuntimeBridge.readLiveStatusSummaryForAi(ctx)
        "run_termux_command" ->
          acc?.executeTermuxCommandViaUi(ctx, a.optString("command"))
            ?: noAcc
        "find_and_open_item" -> acc?.clickText(a.optString("name")) ?: noAcc
        "current_screen" -> {
          LxrFloatingOverlayManager.setScreenReading(ctx, true)
          val liveSnap = LxrCompanionAgent.sampleNow(ctx)
          if (RiyaState.visionCameraActive.value) {
            val camLabel = if (RiyaState.useFrontCamera.value) "ফ্রন্ট" else "ব্যাক"
            val snap = LxrCameraVisionEngine.latestSnapshot()
            val check = LxrCameraVisionGuard.evaluateFrameUsability(snap)
            "লাইভ $camLabel ক্যামেরা চালু (${check.statusReasonBn})। ${liveSnap.toCompactContextBn()}"
          } else {
            liveSnap.toCompactContextBn()
          }
        }
        "read_screen" -> {
          val wantCamera = a.optString("source").equals("camera", ignoreCase = true)
          if (wantCamera) {
            inspectCameraVision(requestedLens = "current", userQuestion = a.optString("question"))
          } else {
            LxrFloatingOverlayManager.setScreenReading(ctx, true)
            acc?.readScreen()
              ?: LxrAssistScreenBridge.buildFallbackScreenReport(
                snapshotLines = emptyList(),
                currentScreenSummary = RiyaAccessibilityService.currentScreenSummary,
                activePkgHint = LxrCompanionAgent.sampleNow(ctx).foregroundPackage,
              )
          }
        }
        "click_text" -> acc?.clickText(a.optString("text")) ?: noAcc
        "type_text" ->
          acc?.typeText(
            t = a.optString("text"),
            target = a.optString("target", "auto").ifBlank { "auto" },
          ) ?: noAcc
        "global_action" -> acc?.global(a.optString("action")) ?: noAcc
        "scroll" -> acc?.scroll(a.optString("direction")) ?: noAcc
        "call_contact" -> callContact(a.optString("name"), a.optBoolean("confirmed", false))
        "search_contact" -> searchContact(a.optString("name"))
        "play_youtube" -> playYoutube(a.optString("query"))
        "media_control" -> mediaControl(a.optString("action"))
        "toggle_flashlight" -> setFlashlight(a.optBoolean("enabled"))
        "control_wifi" -> toggleWifi(a.optBoolean("enabled"))
        "control_bluetooth" -> toggleBluetooth(a.optBoolean("enabled"))
        "get_battery_level" -> getBatteryLevel()
        "set_sound_mode" -> setSoundMode(a.optString("mode"))
        "check_notifications" -> checkNotificationBar()
        "reply_to_incoming_message" ->
          replyToIncomingMessage(
            approve = a.optBoolean("approve", true),
            customReply = a.optString("reply_text").ifBlank { null },
          )
        "current_time" -> nowText()
        "web_search" -> webSearch(a.optString("query"))
        "deep_research" -> {
          val report =
            LxrResearchEngine.research(
              context = ctx,
              query = a.optString("query"),
              preferredEngine = a.optString("engine", "auto").ifBlank { "auto" },
            )
          "${report.summaryBn}\n[ইঞ্জিন স্ট্যাটাস]: ${report.engineStatuses.joinToString(", ")}"
        }
        "search_memory" -> Store.searchMemory(ctx, a.optString("query"), maxDays = Store.RETENTION_DAYS)
        "delete_memory_entry" -> {
          val q = a.optString("query").trim()
          val (removed, _) = Store.deleteMemoryEntries(ctx, q)
          if (removed > 0) {
            "হয়েছে, বস! '$q' সম্পর্কিত $removed টি স্মৃতি এন্ট্রি মেমোরি থেকে চিরতরে মুছে ফেলেছি।"
          } else {
            "বস, '$q' সম্পর্কিত কোনো পুরনো সংরক্ষিত স্মৃতি মেমোরিতে পাওয়া যায়নি (তবে ভবিষ্যতেও মনে রাখবো না)।"
          }
        }
        "remember" -> {
          Store.remember(ctx, "FACT: " + a.optString("fact"))
          "হয়েছে, বস! কথাটা মেমোরিতে সেভ করে রেখেছি।"
        }
        "control_device_setting" -> {
          val setting = a.optString("setting_name")
          val sLow = setting.trim().lowercase()
          when {
            sLow == "wifi" -> toggleWifi(a.optBoolean("enabled", true))
            sLow == "bluetooth" -> toggleBluetooth(a.optBoolean("enabled", true))
            sLow == "flashlight" -> setFlashlight(a.optBoolean("enabled", true))
            else ->
              LxrDeviceAndHardwareEngine.controlDeviceSetting(
                ctx = ctx,
                acc = acc,
                settingName = setting,
                enabled = if (a.has("enabled")) a.optBoolean("enabled") else null,
                valueInt = if (a.has("value_percent")) a.optInt("value_percent") else null,
              )
          }
        }
        "get_device_hardware_info" -> LxrDeviceAndHardwareEngine.getHardwareAndSystemDiagnostics(ctx)
        "get_live_location" -> LxrDeviceAndHardwareEngine.getLiveLocationReport(ctx)
        "open_navigation_directions" ->
          LxrDeviceAndHardwareEngine.openNavigationDirections(
            ctx = ctx,
            destination = a.optString("destination"),
            travelMode = a.optString("travel_mode", "driving"),
          )
        "get_contacts_summary" -> LxrDeviceAndHardwareEngine.getContactsSummary(ctx)
        "get_call_log_summary" ->
          LxrDeviceAndHardwareEngine.getCallLogSummary(
            ctx = ctx,
            filterType = a.optString("filter_type", "all"),
            limit = a.optInt("limit", 10).coerceIn(1, 25),
          )
        "inspect_form_fields" -> {
          if (acc == null) noAcc
          else LxrSocialAndBrowserAgent.collectFormFields(acc).second
        }
        "fill_form_fields" -> {
          if (acc == null) noAcc
          else LxrSocialAndBrowserAgent.fillFormFields(acc, parseFieldPairs(a.optString("fields_json")))
        }
        "facebook_action" ->
          LxrSocialAndBrowserAgent.performFacebookAction(
            ctx = ctx,
            acc = acc,
            action = a.optString("action"),
            text = a.optString("text"),
            query = a.optString("query"),
            confirmed = a.optBoolean("confirmed", false),
          )
        "instagram_action" ->
          LxrSocialAndBrowserAgent.performInstagramAction(
            ctx = ctx,
            acc = acc,
            action = a.optString("action"),
            text = a.optString("text"),
            query = a.optString("query"),
            confirmed = a.optBoolean("confirmed", false),
          )
        "diagnose_and_fix_self" -> {
          val mode = a.optString("mode", "diagnose").trim().lowercase()
          val issueKey = a.optString("issue_key").trim()
          if (mode == "fix" && issueKey.isNotBlank()) {
            LxrDeviceAndHardwareEngine.repairIssueWithBossPermission(ctx, issueKey)
          } else {
            LxrDeviceAndHardwareEngine.diagnoseSelfHealth(ctx).summaryBn
          }
        }
        "critical_task_solve" -> {
          val taskPrompt = a.optString("task_prompt").trim()
          val includeScreen = a.optBoolean("include_screen", true)
          val scr = if (includeScreen) screenSnapshot() else ""
          LxrDeviceAndHardwareEngine.solveCriticalTaskWithExtraModel(ctx, taskPrompt, scr)
        }
        "inspect_camera_vision" ->
          inspectCameraVision(
            requestedLens = a.optString("lens", "current"),
            userQuestion = a.optString("question"),
          )
        "switch_camera_lens" -> {
          val lens = a.optString("lens", "toggle").trim().lowercase()
          val turnOnArg = if (a.has("turn_on")) a.optBoolean("turn_on", true) else null
          val nextFront =
            when (lens) {
              "front", "selfie", "সামনে" -> true
              "back", "rear", "পিছনে" -> false
              else -> !RiyaState.useFrontCamera.value
            }
          val lensLabel = if (nextFront) "ফ্রন্ট (সামনের)" else "ব্যাক (পিছনের)"
          if (turnOnArg == false) {
            RiyaState.useFrontCamera.value = nextFront
            RiyaState.setVisionCameraActive(false)
            LxrCameraVisionEngine.clearSnapshot()
            RiyaService.syncCameraState(ctx)
            notifyCameraActive(false, isFrontCamera = nextFront)
            "হয়েছে, বস! ক্যামেরা বন্ধ করা হয়েছে।"
          } else if (turnOnArg == true || RiyaState.visionCameraActive.value) {
            if (!LxrCameraVisionEngine.hasCameraPermission(ctx)) {
              "ক্যামেরা পারমিশন (CAMERA) চালু নেই বস! অনুমতি দিলে $lensLabel ক্যামেরা চালু করতে পারবো।"
            } else {
              RiyaState.useFrontCamera.value = nextFront
              RiyaState.setVisionCameraActive(true)
              LxrCameraVisionEngine.clearSnapshot()
              RiyaService.syncCameraState(ctx)
              val snap =
                LxrCameraVisionEngine.captureOrAwaitFreshFrame(
                  ctx = ctx,
                  requestedFrontLens = nextFront,
                  timeoutMs = 1900L,
                )
              val ver = LxrCameraVisionGuard.evaluateFrameUsability(snap)
              val jpeg = snap?.jpegBytes
              if (ver.hasFreshFrame && jpeg != null && jpeg.isNotEmpty()) {
                val prompt = LxrCameraVisionGuard.buildHonestVisionPrompt(snap, userQuestion = null)
                sendCameraInlineSnapshot(jpeg, prompt, triggerReply = false)
                "হয়েছে, বস! $lensLabel ক্যামেরা চালু হয়েছে এবং তাজা ছবি পাওয়া গেছে (${ver.statusReasonBn})। ছবিতে যা স্পষ্ট দেখছো কেবল সেটাই বলো (না দেখলে বানিয়ে বলবে না)।"
              } else {
                val err = LxrCameraVisionEngine.lastErrorMessageBn()?.let { " ($it)" }.orEmpty()
                "বস, $lensLabel ক্যামেরা চালু করা হয়েছে কিন্তু এখনো পরিষ্কার লাইভ ফ্রেম আসেনি$err — তাই না দেখে কিছু অনুমান করবে না।"
              }
            }
          } else {
            RiyaState.useFrontCamera.value = nextFront
            "হয়েছে, বস! ক্যামেরা লেন্স $lensLabel সেট করেছি। চাইলে এখনই ক্যামেরা চালু করে দেখে দিতে পারি।"
          }
        }
        "list_installed_apps" ->
          LxrAppDiscoveryEngine.listInstalledAppsReport(ctx, a.optString("filter"))
        "schedule_exact_reminder" ->
          LxrDeviceAndHardwareEngine.scheduleExactReminder(
            ctx = ctx,
            title = a.optString("title"),
            delaySeconds = a.optLong("delay_seconds", 60L),
          )
        else -> "unknown tool"
      }
    } catch (e: Exception) {
      "error: ${e.message}"
    }

  private fun inspectCameraVision(requestedLens: String, userQuestion: String?): String {
    if (!LxrCameraVisionEngine.hasCameraPermission(ctx)) {
      return "ক্যামেরা পারমিশন (CAMERA) বন্ধ আছে বস! অনুমতি ছাড়া আমি ক্যামেরা দিয়ে দেখতে পারছি না (অনুমতি চাইলে `diagnose_and_fix_self` ব্যবহার করো)।"
    }
    val wantFront: Boolean? =
      when (requestedLens.trim().lowercase()) {
        "front", "selfie", "সামনে" -> true
        "back", "rear", "পিছনে" -> false
        else -> null
      }
    val snap =
      LxrCameraVisionEngine.captureOrAwaitFreshFrame(
        ctx = ctx,
        requestedFrontLens = wantFront,
        timeoutMs = 2000L,
      )
    val ver = LxrCameraVisionGuard.evaluateFrameUsability(snap)
    val jpeg = snap?.jpegBytes
    if (!ver.hasFreshFrame || jpeg == null || jpeg.isEmpty()) {
      val err = LxrCameraVisionEngine.lastErrorMessageBn() ?: ver.statusReasonBn
      return "বসকে সত্য বলো: এই মুহূর্তে ক্যামেরা থেকে তাজা লাইভ ছবি পাওয়া যাচ্ছে না ($err) — তাই না দেখে কোনো মিথ্যা বর্ণনা দেবে না।"
    }
    val honestPrompt = LxrCameraVisionGuard.buildHonestVisionPrompt(snap, userQuestion)
    sendCameraInlineSnapshot(jpeg, honestPrompt, triggerReply = false)
    return if (!ver.isReadableBrightness) {
      "সতর্কতা: ক্যামেরার ফ্রেম অত্যন্ত অন্ধকার বা ঢাকা (${ver.averageLuma}/255)। সংযুক্ত ছবিটি দেখে সত্য বলো যে পরিষ্কার দেখা যাচ্ছে না।"
    } else {
      val modeTxt = if (snap.sourceMode == "background") "ব্যাকগ্রাউন্ড ক্যামেরা" else "লাইভ ক্যামেরা"
      "$modeTxt থেকে এই মুহূর্তের আসল image/jpeg ফ্রেম পাঠানো হয়েছে (${ver.statusReasonBn})। $honestPrompt"
    }
  }

  private fun goHomeAndVerify(acc: RiyaAccessibilityService?) {
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    if (acc != null) {
      val res = acc.global("home")
      val launchers = acc.resolveHomeLauncherPackages()
      val cur = LockVaultManager.resolveBestForegroundPackage(acc)
      if (res == "সম্পন্ন" || cur in launchers || cur.contains("launcher", true)) return
    }
    if (LockVaultManager.isMainScreenGenuinelyLockedBeforeTask(ctx, acc)) return
    try {
      val fallbackIntent =
        Intent(homeIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
      acc?.resolveHomeLauncherPackages()?.firstOrNull()?.let { fallbackIntent.`package` = it }
      LxrAppDiscoveryEngine.launchActivitySafely(ctx, acc, fallbackIntent)
      Thread.sleep(320)
    } catch (_: Throwable) {}
  }

  private fun sendGreetingTurn() {
    val prevWriteMs = lastSpeakerWriteMs
    // Pause upstream mic while Live API opens its greeting speech so VAD won't cut it
    micMuteUntilMs = System.currentTimeMillis() + 1200L
    val greeting = Store.MIC_TAP_GREETING_BN
    val prompt =
      "[SESSION START GREETING - MANDATORY] বস এইমাত্র মাইক অন করেছেন। সাথে সাথে কোনো দেরি না করে ঠিক এই ৩ ধাপের মধুর অভ্যর্থনাটি বাংলায় স্পষ্টভাবে বলো (অন্য কোনো অতিরিক্ত কথা যোগ করবে না এবং সেশনের মাঝখানে আর কখনো পুনরাবৃত্তি করবে না): \"$greeting\""
    try {
      val turnPayload =
        JSONObject()
          .put(
            "clientContent",
            JSONObject()
              .put(
                "turns",
                JSONArray()
                  .put(
                    JSONObject()
                      .put("role", "user")
                      .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                  ),
              )
              .put("turnComplete", true),
          )
      val ok = ws?.send(turnPayload.toString()) == true
      if (!ok) {
        RiyaVoiceManager.speakUrgent(ctx, greeting)
      } else {
        main.postDelayed(
          {
            if (!stopped && !ready && lastSpeakerWriteMs == prevWriteMs) {
              RiyaVoiceManager.speakUrgent(ctx, greeting)
            }
          },
          4800L,
        )
      }
    } catch (_: Throwable) {
      RiyaVoiceManager.speakUrgent(ctx, greeting)
    }
  }

  private fun resolveKnownPackages(rawName: String): Pair<String, List<String>>? {
    MessageAgent.find(rawName)?.let { spec ->
      return spec.label to (listOf(spec.pkg) + spec.fallbackPkgs)
    }
    val cleaned =
      LxrAppDiscoveryEngine.normalizeUnicode(rawName)
        .lowercase()
        .replace(
          Regex("(অ্যাপস|অ্যাপ্স|অ্যাপটি|অ্যাপ|সফটওয়্যার|সফ্টওয়্যার|খোলো|খুলে|ওপেন|করো|চালু|দাও|\\bapps\\b|\\bapp\\b|\\bopen\\b)"),
          " ",
        )
        .replace(Regex("\\s+"), " ")
        .trim()
    val tokens = cleaned.split(Regex("[\\s.,:/\\-_]+")).filter { it.isNotEmpty() }
    val known =
      listOf(
        Triple("YouTube", listOf("youtube", "ইউটিউব"), listOf("com.google.android.youtube")),
        Triple("Settings", listOf("settings", "সেটিংস", "সেটিং"), listOf("com.android.settings")),
        Triple("Facebook", listOf("facebook", "ফেসবুক", "fb"), listOf("com.facebook.katana", "com.facebook.lite")),
        Triple("Chrome", listOf("chrome", "ক্রোম"), listOf("com.android.chrome")),
        Triple("Play Store", listOf("play store", "playstore", "প্লে স্টোর"), listOf("com.android.vending")),
        Triple(
          "Contacts",
          listOf("contacts", "contact", "কন্টাক্ট", "কনট্যাক্ট", "কন্টাক্টস", "ফোনবুক"),
          listOf("com.google.android.contacts", "com.transsion.contacts", "com.infinix.contacts", "com.android.contacts"),
        ),
        Triple(
          "Phone",
          listOf("phone", "dialer", "ডায়ালার", "ফোন", "কল"),
          listOf("com.google.android.dialer", "com.transsion.dialer", "com.sh.smart.caller", "com.android.dialer"),
        ),
        Triple(
          "Messages",
          listOf("messages", "messaging", "sms", "মেসেজ", "মেসেজেস", "এসএমএস"),
          listOf("com.google.android.apps.messaging", "com.android.mms"),
        ),
        Triple(
          "Camera",
          listOf("camera", "ক্যামেরা"),
          listOf("com.transsion.camera", "com.android.camera", "com.google.android.GoogleCamera"),
        ),
        Triple(
          "Gallery",
          listOf("gallery", "photos", "গ্যালারি", "ছবি"),
          listOf("com.transsion.camera.gallery", "com.transsion.gallery", "com.google.android.apps.photos"),
        ),
        Triple(
          "Calculator",
          listOf("calculator", "calc", "ক্যালকুলেটর"),
          listOf("com.transsion.calculator", "com.google.android.calculator", "com.android.calculator2"),
        ),
        Triple(
          "Notes",
          listOf("notes", "notepad", "keep", "নোট", "নোটস"),
          listOf("com.transsion.notepad", "com.google.android.keep"),
        ),
        Triple("bKash", listOf("bkash", "বিকাশ"), listOf("com.bKash.customerapp")),
        Triple("Nagad", listOf("nagad", "নগদ"), listOf("com.konasl.nagad")),
        Triple("TikTok", listOf("tiktok", "টিকটক"), listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
      )
    known.firstOrNull { (_, aliases, _) ->
      aliases.any { al -> cleaned == al || al in tokens || (al.length >= 2 && cleaned.contains(al)) }
    }?.let { (label, _, pkgs) ->
      return label to pkgs
    }
    return null
  }

  private fun openApp(n: String): String {
    val acc = RiyaAccessibilityService.instance
    val query = LxrAppDiscoveryEngine.normalizeUnicode(n).trim()
    if (query.isBlank()) return "কোন অ্যাপ খুলবো বলোনি বস।"
    val pm = ctx.packageManager
    val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    var targetLabel = query
    var targetPkgs: List<String> = emptyList()
    var launchIntent: Intent? = null

    // 1. Strict alias & known-app package resolution (prevents WhatsApp <-> imo <-> Messenger mixups!)
    val msgSpec = MessageAgent.find(query)
    val known = resolveKnownPackages(query)
    if (msgSpec != null) {
      targetLabel = msgSpec.label
      targetPkgs = listOf(msgSpec.pkg) + msgSpec.fallbackPkgs
      launchIntent = MessageAgent.launchIntentFor(ctx, msgSpec)
    } else if (known != null) {
      targetLabel = known.first
      targetPkgs = known.second
      launchIntent =
        targetPkgs.firstNotNullOfOrNull { pkg -> runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() }
          ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
      if (launchIntent == null) {
        val allowedSet = targetPkgs.map { it.lowercase() }.toSet()
        val queriedActs =
          runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
              pm.queryIntentActivities(li, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
              @Suppress("DEPRECATION")
              pm.queryIntentActivities(li, PackageManager.MATCH_ALL)
            }
          }.getOrDefault(emptyList())
        val matchAct = queriedActs.firstOrNull { it.activityInfo.packageName.lowercase() in allowedSet }
        if (matchAct != null) {
          launchIntent =
            Intent(li)
              .setClassName(matchAct.activityInfo.packageName, matchAct.activityInfo.name)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
      }
    }

    // 2. Full-device PackageManager discovery (runs EVEN if known != null had OEM package difference, e.g. Transsion/Infinix!)
    if (launchIntent == null) {
      val allInstalled = LxrAppDiscoveryEngine.queryAllLaunchableApps(ctx)
      val matchedEntry = LxrAppDiscoveryEngine.matchInstalledEntry(query, allInstalled)
      if (matchedEntry != null) {
        targetLabel = matchedEntry.label
        targetPkgs = listOf(matchedEntry.packageName)
        launchIntent = LxrAppDiscoveryEngine.buildLaunchIntent(ctx, matchedEntry)
      }
    }

    // 2.5 Standard Android System Category Intent resolver (Dialer / Contacts / Messages / Gallery / Calculator / Calendar)
    if (launchIntent == null) {
      LxrAppDiscoveryEngine.resolveSystemCategoryIntent(ctx, query)?.let { (sysLabel, sysIntent) ->
        targetLabel = sysLabel
        targetPkgs = listOfNotNull(sysIntent.`package` ?: sysIntent.component?.packageName)
        launchIntent = sysIntent
      }
    }

    if (launchIntent != null) {
      val curBefore = acc?.rootInActiveWindow?.packageName?.toString().orEmpty()
      val alreadyOpen = curBefore.isNotBlank() && curBefore in targetPkgs
      if (!alreadyOpen) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        LxrAppDiscoveryEngine.launchActivitySafely(ctx, acc, launchIntent)
        val deadline = System.currentTimeMillis() + 4500L
        while (System.currentTimeMillis() < deadline) {
          Thread.sleep(200)
          if (LockVaultManager.isAppLockScreenShowing(acc)) {
            LockVaultManager.unlockAppLockIfPresent(ctx, acc)
            Thread.sleep(360)
          }
          val curPkg = acc?.rootInActiveWindow?.packageName?.toString().orEmpty()
          if (curPkg in targetPkgs || RiyaAccessibilityService.currentApp in targetPkgs) {
            Thread.sleep(360)
            break
          }
        }
      } else if (LockVaultManager.isAppLockScreenShowing(acc)) {
        LockVaultManager.unlockAppLockIfPresent(ctx, acc)
        Thread.sleep(320)
      }
      val fullScreen = acc?.readScreen().orEmpty()
      val activePkg = acc?.rootInActiveWindow?.packageName?.toString().orEmpty()
      val verifiedOpen =
        targetPkgs.isEmpty() || activePkg in targetPkgs || RiyaAccessibilityService.currentApp in targetPkgs
      RiyaState.markTaskCompleted(
        "$targetLabel অ্যাপ খোলা হয়েছে।",
        "",
      )
      return buildString {
        append(
          if (verifiedOpen) "$targetLabel ($activePkg) অ্যাপটি সঠিকভাবে ওপেন হয়েছে।"
          else "$targetLabel ওপেন করা হয়েছে (বর্তমান উইন্ডো: $activePkg)।"
        )
        if (fullScreen.isNotBlank()) {
          append("\n[সম্পূর্ণ স্ক্রিন ১০১% রিড]:\n")
          append(fullScreen.take(2200))
        }
      }
    }

    // 3. Exhaustive smart launcher + home screen + app drawer + Infinix search bar discovery (always runs before ever concluding missing!)
    return LxrSocialAndBrowserAgent.discoverAndLaunchFromLauncher(
      ctx = ctx,
      acc = acc,
      rawQuery = query,
      goHomeFn = { goHomeAndVerify(acc) },
    )
  }

  private fun parseFieldPairs(raw: String): List<Pair<String, String>> {
    val clean = raw.trim()
    if (clean.isBlank()) return emptyList()
    if (clean.startsWith("{")) {
      runCatching {
        val obj = JSONObject(clean)
        val out = mutableListOf<Pair<String, String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          out.add(k to obj.optString(k))
        }
        return out
      }
    }
    if (clean.startsWith("[")) {
      runCatching {
        val arr = JSONArray(clean)
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val k = o.optString("field").ifBlank { o.optString("name") }
          val v = o.optString("value")
          if (k.isNotBlank()) out.add(k to v)
        }
        return out
      }
    }
    return clean.split(';', '\n').mapNotNull { part ->
      val idx = part.indexOf('=')
      if (idx <= 0) null
      else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
    }
  }

  private fun messageSend(a: JSONObject): String {
    if (!Keep.accessibilityOn(ctx)) {
      return "Accessibility সার্ভিস বন্ধ আছে বস! মেসেজ পাঠাতে অ্যাক্সেসিবিলিটি চালু করো।"
    }
    val acc =
      RiyaAccessibilityService.instance
        ?: return "Accessibility সার্ভিস সংযুক্ত নয় বস, দয়া করে চালু করো।"
    return messageWithAgent(acc, a.optString("app_name"), a.optString("contact_name"), a.optString("message"))
  }

  private fun bringRiyaBack() {
    try {
      LxrAppDiscoveryEngine.launchActivitySafely(
        ctx,
        RiyaAccessibilityService.instance,
        Intent(ctx, MainActivity::class.java)
          .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
              Intent.FLAG_ACTIVITY_SINGLE_TOP
          ),
      )
    } catch (_: Exception) {}
  }

  private fun messageWithAgent(
    acc: RiyaAccessibilityService,
    app: String,
    contact: String,
    msg: String,
  ): String {
    val watching = AtomicBoolean(true)
    val watcher =
      Thread {
        while (watching.get() && !stopped) {
          try {
            if (LockVaultManager.isAppLockScreenShowing(acc)) {
              LockVaultManager.unlockAppLockIfPresent(ctx, acc)
            }
            Thread.sleep(240L)
          } catch (_: InterruptedException) {
            break
          } catch (_: Throwable) {}
        }
      }.apply {
        isDaemon = true
        start()
      }
    return try {
      MessageAgent.run(ctx, acc, app, contact, msg)
    } finally {
      watching.set(false)
      try {
        watcher.interrupt()
      } catch (_: Throwable) {}
      bringRiyaBack()
    }
  }

  private fun loadPhoneContactEntries(): Pair<Int, List<Pair<String, String>>> {
    val entries = mutableListOf<Pair<String, String>>()
    var scanned = 0
    try {
      val cursor =
        ctx.contentResolver.query(
          ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
          arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
          ),
          null,
          null,
          null,
        )
      cursor?.use { c ->
        while (c.moveToNext()) {
          scanned++
          val name = c.getString(0)?.trim() ?: continue
          val num = c.getString(1)?.trim().orEmpty()
          if (name.isNotBlank() && num.isNotBlank()) {
            entries.add(name to num)
          }
        }
      }
    } catch (_: Exception) {}
    return scanned to entries
  }

  private fun searchContact(n: String): String {
    val q = n.trim()
    if (q.isBlank()) return "কোন কন্টাক্ট চেক করবো বলোনি বস।"
    val (scanned, entries) = loadPhoneContactEntries()
    val ranked = ContactFirstWordMatcher.rankMatches(q, entries, maxResults = 5)
    return if (ranked.isNotEmpty()) {
      "কন্টাক্ট তালিকার $scanned টি নম্বর থেকে প্রথম শব্দ অনুযায়ী পাওয়া গেছে:\n" +
        ranked.mapIndexed { idx, m -> "${idx + 1}. ${m.displayName}: ${m.phoneNumber}" }.joinToString("\n")
    } else {
      "কন্টাক্ট তালিকার $scanned টি নম্বর পুরোটা চেক করেছি বস, '$q' নামে বা নম্বরে কিছু পাইনি।"
    }
  }

  private fun callContact(n: String, confirmed: Boolean = false): String {
    val clean = n.trim()
    if (clean.isBlank()) return "কাকে কল দেবো বলোনি বস।"
    val (_, entries) = loadPhoneContactEntries()
    val matches = ContactFirstWordMatcher.rankMatches(clean, entries, maxResults = 5)
    if (matches.isEmpty()) {
      return "কন্টাক্ট তালিকায় '$clean' নামের প্রথম অংশ দিয়ে খুঁজেও কোনো কল নম্বর পাইনি বস।"
    }
    if (!confirmed) {
      return ContactFirstWordMatcher.buildReadBackConfirmationPrompt(clean, matches, "কল")
    }
    val chosen = matches.first()
    LxrAppDiscoveryEngine.launchActivitySafely(
      ctx,
      RiyaAccessibilityService.instance,
      Intent(Intent.ACTION_CALL, Uri.parse("tel:${chosen.phoneNumber}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    RiyaState.markTaskCompleted(
      "${chosen.displayName} (${chosen.phoneNumber}) কে কল লাগানো হয়েছে।",
      "",
    )
    return "${chosen.displayName} (${chosen.phoneNumber})-কে কল লাগিয়েছি বস।"
  }

  private fun setFlashlight(on: Boolean): String {
    if (RiyaState.cameraOn) {
      return "ক্যামেরা ভিউ চালু আছে বলে ফ্ল্যাশলাইট জ্বালানো যাচ্ছে না বস। আগে ক্যামেরা বন্ধ করো।"
    }
    var hardwareSucceeded = false
    try {
      val cam = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val flashIds =
        cam.cameraIdList
          .filter { cid ->
            try {
              cam.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } catch (_: Exception) {
              false
            }
          }
          .sortedByDescending { cid ->
            try {
              cam.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
            } catch (_: Exception) {
              false
            }
          }

      var anyNoThrow = false
      for (cid in flashIds) {
        val latch = CountDownLatch(1)
        val confirmed = AtomicBoolean(false)
        val cb =
          object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
              if (cameraId == cid && enabled == on) {
                confirmed.set(true)
                latch.countDown()
              }
            }
          }
        try {
          cam.registerTorchCallback(cb, main)
          cam.setTorchMode(cid, on)
          anyNoThrow = true
          latch.await(420L, TimeUnit.MILLISECONDS)
          if (confirmed.get()) {
            hardwareSucceeded = true
            break
          }
        } catch (_: Exception) {
          // Try next camera ID with flash
        } finally {
          try {
            cam.unregisterTorchCallback(cb)
          } catch (_: Exception) {}
        }
      }
      if (!hardwareSucceeded && anyNoThrow) {
        hardwareSucceeded = true
      }
    } catch (_: Exception) {}

    if (!hardwareSucceeded) {
      val acc = RiyaAccessibilityService.instance
      val tileRes =
        acc?.toggleQuickSettingTile(
          enable = on,
          labels = listOf("flashlight", "torch", "ফ্ল্যাশলাইট", "টর্চ"),
          hardwareStateKnown = isTorchOn,
        )
      if (tileRes == "OK" || tileRes == "ALREADY") {
        hardwareSucceeded = true
      }
    }

    if (hardwareSucceeded) {
      isTorchOn = on
      LxrCompanionAgent.notifyTorchState(on)
      val msg =
        if (on) "ফ্ল্যাশলাইট জ্বালিয়ে দিয়েছি বস।"
        else "ফ্ল্যাশলাইট বন্ধ করে দিয়েছি বস।"
      RiyaState.markTaskCompleted(msg, "")
      return msg
    }
    return "এই ফোনে ফ্ল্যাশলাইট চালু/বন্ধ করা গেল না বস।"
  }

  private fun getBatteryLevel(): String {
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val bat = ctx.registerReceiver(null, filter)
    val lvl = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val pct = if (lvl >= 0 && scale > 0) (lvl * 100 / scale) else -1
    val st = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val tail =
      when {
        st == BatteryManager.BATTERY_STATUS_FULL -> ", চার্জ পুরো ফুল।"
        st == BatteryManager.BATTERY_STATUS_CHARGING || plugged -> ", আর চার্জ হচ্ছে।"
        else -> "।"
      }
    return "ফোনে এখন $pct% চার্জ আছে বস$tail"
  }

  private fun setSoundMode(mode: String): String {
    return try {
      val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val m = mode.lowercase()
      val wantSilent = m == "silent" || m == "সাইলেন্ট"
      if (wantSilent && !nm.isNotificationPolicyAccessGranted) {
        LxrAppDiscoveryEngine.launchActivitySafely(
          ctx,
          RiyaAccessibilityService.instance,
          Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return "সাইলেন্ট করতে Do Not Disturb অ্যাক্সেস লাগবে। সেটিংস খুলেছি বস, LXR--AI কে অনুমতি দিয়ে আবার বলো।"
      }
      when {
        wantSilent -> {
          am.ringerMode = AudioManager.RINGER_MODE_SILENT
          "হয়েছে, বস! কাজ শেষ — ফোন সাইলেন্ট করে দিয়েছি।"
        }
        m == "vibrate" || m == "ভাইব্রেট" -> {
          am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
          "হয়েছে, বস! কাজ শেষ — ফোন ভাইব্রেট মোডে দিয়েছি।"
        }
        else -> {
          am.ringerMode = AudioManager.RINGER_MODE_NORMAL
          "হয়েছে, বস! কাজ শেষ — ফোন নরমাল রিং মোডে দিয়েছি।"
        }
      }
    } catch (_: SecurityException) {
      LxrAppDiscoveryEngine.launchActivitySafely(
        ctx,
        RiyaAccessibilityService.instance,
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
      "সাউন্ড বদলাতে Do Not Disturb অ্যাক্সেস লাগবে বস, সেটিংস খুলেছি।"
    } catch (e: Exception) {
      "সাউন্ড মোড বদলানো গেল না: ${e.message}"
    }
  }

  private fun waitForSettingsPackage(acc: RiyaAccessibilityService?, timeoutMs: Long = 3400L) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val pkg = acc?.rootInActiveWindow?.packageName?.toString().orEmpty()
      if (pkg == "com.android.settings" || pkg == "com.android.systemui") {
        Thread.sleep(240)
        return
      }
      Thread.sleep(180)
    }
  }

  private fun pollCondition(timeoutMs: Long = 1400L, predicate: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (predicate()) return true
      Thread.sleep(160)
    }
    return predicate()
  }

  @Suppress("DEPRECATION")
  private fun toggleWifi(enable: Boolean): String {
    val word = if (enable) "চালু" else "বন্ধ"
    return try {
      val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      if (wm.isWifiEnabled == enable) return "ওয়াইফাই আগেই $word আছে বস।"
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wm.setWifiEnabled(enable)) {
        RiyaState.markTaskCompleted("ওয়াইফাই $word করেছি।", "")
        return "ওয়াইফাই $word করে দিয়েছি বস।"
      }

      val acc = if (Keep.accessibilityOn(ctx)) RiyaAccessibilityService.instance else null
      if (acc != null) {
        // 1. Primary path on Android 12–16 / Infinix XOS: Quick Settings tile (doesn't mis-click Mobile Data!)
        val qsRes =
          acc.toggleQuickSettingTile(
            enable = enable,
            labels = listOf("wi-fi", "wifi", "wlan", "internet", "ওয়াইফাই"),
            excludeLabels = listOf("calling", "hotspot", "tethering", "scanning"),
            hardwareStateKnown = wm.isWifiEnabled,
          )
        if ((qsRes == "OK" || qsRes == "ALREADY") && pollCondition(1400L) { wm.isWifiEnabled == enable }) {
          RiyaState.markTaskCompleted("ওয়াইফাই $word করা হয়েছে।", "")
          return "ওয়াইফাই $word করে দিয়েছি বস।"
        }
      }

      // 2. Fallback: open full Wi-Fi Settings screen and toggle the exact Wi-Fi switch
      LxrAppDiscoveryEngine.launchActivitySafely(
        ctx,
        acc,
        Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
      waitForSettingsPackage(acc)
      val res =
        acc?.setSwitch(
          enable = enable,
          labels = listOf("use wi-fi", "wi-fi", "wifi", "wlan", "ওয়াই"),
          excludeLabels = listOf("calling", "hotspot", "tethering", "scanning", "assistant", "notify"),
        )
      if ((res == "OK" || res == "CLICKED") && pollCondition(1200L) { wm.isWifiEnabled == enable }) {
        acc.global("back")
        RiyaState.markTaskCompleted("ওয়াইফাই $word করা হয়েছে।", "")
        return "ওয়াইফাই $word করে দিয়েছি বস।"
      }
      finishSwitch(res, "ওয়াইফাই", word)
    } catch (e: Exception) {
      "ওয়াইফাই টগল করা যায়নি: ${e.message}"
    }
  }

  @Suppress("DEPRECATION")
  private fun toggleBluetooth(enable: Boolean): String {
    val word = if (enable) "চালু" else "বন্ধ"
    return try {
      val bt =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
          ?: return "ফোনে ব্লুটুথ পাওয়া যায়নি"
      if (enable == bt.isEnabled) return "ব্লুটুথ আগেই $word আছে বস।"
      if (Build.VERSION.SDK_INT < 33 && hasBtPerm()) {
        val ok = if (enable) bt.enable() else bt.disable()
        if (ok && pollCondition(1400L) { bt.isEnabled == enable }) {
          RiyaState.markTaskCompleted("ব্লুটুথ $word করা হয়েছে।", "")
          return "ব্লুটুথ $word করে দিয়েছি বস।"
        }
      }

      val acc = if (Keep.accessibilityOn(ctx)) RiyaAccessibilityService.instance else null
      if (acc != null) {
        // 1. Primary path on Android 13–16 / Infinix XOS: Quick Settings Bluetooth tile
        val qsRes =
          acc.toggleQuickSettingTile(
            enable = enable,
            labels = listOf("bluetooth", "ব্লুটুথ"),
            excludeLabels = listOf("tethering", "scanning"),
            hardwareStateKnown = bt.isEnabled,
          )
        if ((qsRes == "OK" || qsRes == "ALREADY") && pollCondition(1500L) { bt.isEnabled == enable }) {
          RiyaState.markTaskCompleted("ব্লুটুথ $word করা হয়েছে।", "")
          return "ব্লুটুথ $word করে দিয়েছি বস।"
        }
      }

      // 2. Fallback: open full Bluetooth Settings screen and toggle Bluetooth switch
      LxrAppDiscoveryEngine.launchActivitySafely(
        ctx,
        acc,
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
      waitForSettingsPackage(acc)
      val res =
        acc?.setSwitch(
          enable = enable,
          labels = listOf("use bluetooth", "bluetooth", "ব্লুটুথ"),
          excludeLabels = listOf("tethering", "scanning"),
        )
      if ((res == "OK" || res == "CLICKED") && pollCondition(1200L) { bt.isEnabled == enable }) {
        acc.global("back")
        RiyaState.markTaskCompleted("ব্লুটুথ $word করা হয়েছে।", "")
        return "ব্লুটুথ $word করে দিয়েছি বস।"
      }
      finishSwitch(res, "ব্লুটুথ", word)
    } catch (e: Exception) {
      "ব্লুটুথ বদলানো গেল না: ${e.message}"
    }
  }

  private fun finishSwitch(res: String?, name: String, word: String): String {
    val acc = RiyaAccessibilityService.instance
    return when (res) {
      null ->
        "$name স্ক্রিন খুলেছি বস, কিন্তু Accessibility চালু নেই বলে ট্যাপ করতে পারিনি। দয়া করে LXR--AI Accessibility চালু করুন।"
      "OK" -> {
        acc?.global("back")
        RiyaState.markTaskCompleted("$name $word করা হয়েছে।", "")
        "$name $word করে দিয়েছি বস।"
      }
      "ALREADY" -> {
        acc?.global("back")
        "$name আগেই $word ছিল বস।"
      }
      "CLICKED" ->
        "$name এর সুইচে ট্যাপ করেছি বস, অনুগ্রহ করে এক নজর দেখে নিন।"
      else -> "$name এর সুইচ খুঁজে পাইনি বস, স্ক্রিন খোলা আছে।"
    }
  }

  private fun checkNotificationBar(): String {
    val pendingList =
      RiyaRuntimeBridge.pendingMessages.value.filter {
        it.status == IncomingReplyStatus.PENDING_PERMISSION
      }
    val recentCalls = RiyaRuntimeBridge.getRecentVoiceCalls(withinMs = 15 * 60_000L).take(3)
    val callsNote =
      if (recentCalls.isNotEmpty()) {
        "\n[ভয়েস/ভিডিও কল অ্যালার্ট (বসকে অবশ্যই জানাও)]:\n" +
          recentCalls.joinToString("\n") { "- ${it.summaryBn}" }
      } else ""
    val recentSent = RiyaRuntimeBridge.getRecentOutgoingMessages(withinMs = 15 * 60_000L).take(3)
    val sentNote =
      if (recentSent.isNotEmpty()) {
        "\n[আমাদের পাঠানো সর্বশেষ উত্তর (এগুলো অপর প্রান্ত থেকে আসা মেসেজ নয় — আমি/বস পাঠিয়েছি)]:\n" +
          recentSent.joinToString("\n") { "- ${it.appLabel} (${it.recipientName}-কে): \"${it.messageText}\"" }
      } else ""

    if (pendingList.isNotEmpty()) {
      val incomingText =
        pendingList.take(4).joinToString("\n") {
          "- ${it.appLabel}-এ ${it.senderName} লিখেছেন: \"${it.messageText}\""
        }
      return "[নতুন প্রাপ্ত মেসেজ / RECEIVED (শুধু এগুলোই অপর প্রান্ত থেকে এসেছে)]:\n$incomingText$callsNote$sentNote"
    }
    val acc = RiyaAccessibilityService.instance
      ?: return if (callsNote.isNotBlank() || sentNote.isNotBlank()) "কোনো নতুন ইনকামিং মেসেজ নেই বস।$callsNote$sentNote" else "Accessibility চালু নেই বস"
    acc.global("notifications")
    Thread.sleep(240)
    val notes = acc.readScreen()
    acc.global("back")
    return if (notes.isNotBlank() && !notes.startsWith("স্ক্রিনে পড়ার মতো")) {
      "নোটিফিকেশন বারে যা পাওয়া গেছে:$callsNote\n$notes$sentNote"
    } else {
      "এই মুহূর্তে অপর প্রান্ত থেকে কোনো নতুন মেসেজ বা নোটিফিকেশন নেই বস।$callsNote$sentNote"
    }
  }

  private fun replyToIncomingMessage(approve: Boolean, customReply: String?): String {
    val pending =
      RiyaRuntimeBridge.pendingMessages.value.firstOrNull {
        it.status == IncomingReplyStatus.PENDING_PERMISSION
      } ?: return "এই মুহূর্তে রিপ্লাই দেওয়ার মতো কোনো অপেক্ষমাণ মেসেজ নেই বস।"

    if (!approve) {
      RiyaRuntimeBridge.updateIncomingMessageStatus(pending.eventKey, IncomingReplyStatus.DISMISSED)
      return "ঠিক আছে বস, ${pending.appLabel}-এর মেসেজটির রিপ্লাই বাতিল করা হয়েছে।"
    }
    ReplyPolicyScheduler.syncFromStore(ctx)
    val limitMax = ReplyPolicyScheduler.currentLimitMax(ctx)
    if (!ReplyPolicyScheduler.canSendReply(limitMax)) {
      return "বস, তোমার নির্ধারিত Reply Limit ($limitMax/$limitMax) পূর্ণ হয়েছে — Reset বা Unlimited না করলে উত্তর পাঠানো যাবে না।"
    }
    val sentText = (customReply ?: pending.suggestedReply).trim()
    val ok =
      RiyaNotificationListenerService.sendApprovedReply(
        ctx,
        pending.eventKey,
        sentText,
      )
    if (ok) {
      ReplyPolicyScheduler.tryConsumeAndPersist(ctx, limitMax)
    }
    return if (ok) {
      "${pending.appLabel}-এ ${pending.senderName}-কে আমি ঠিক এই মেসেজটি লিখে পাঠিয়েছি: \"$sentText\"। বসকে হুবহু বলে দাও যে কী লিখে পাঠিয়েছো।"
    } else {
      "${pending.appLabel}-এ ${pending.senderName}-কে উত্তর পাঠানো যায়নি বস, অ্যাক্সেসিবিলিটি বা অ্যাপ স্ক্রিন পরীক্ষা করো।"
    }
  }

  private fun nowText(): String = LxrCompanionAgent.formatBangladeshFullClock()

  private fun webSearch(q: String): String {
    if (q.isBlank()) return "কী সার্চ করবো বুঝলাম না"
    RiyaState.status.value = "সার্চ করছি বস…"
    val report = LxrResearchEngine.research(ctx, q, preferredEngine = "auto")
    return if (report.sources.isNotEmpty() || !report.summaryBn.startsWith("দুঃখিত")) {
      report.summaryBn
    } else {
      RiyaState.addLog("সার্চ ব্যর্থ: ${report.engineStatuses.joinToString(", ")}")
      report.summaryBn
    }
  }

  private fun ddgSearch(q: String): String {
    return try {
      val req =
        Request.Builder()
          .url("https://html.duckduckgo.com/html/?q=" + Uri.encode(q))
          .header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
          )
          .build()
      val http = client.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
      val html = http.newCall(req).execute().use { it.body?.string() ?: "" }
      val re =
        Regex(
          """class="result__a"[^>]*>(.*?)</a>[\s\S]*?class="result__snippet"[^>]*>(.*?)</a>""",
          RegexOption.DOT_MATCHES_ALL,
        )
      fun clean(x: String) =
        x.replace(Regex("<[^>]+>"), "")
          .replace("&amp;", "&")
          .replace("&quot;", "\"")
          .replace("&#x27;", "'")
          .replace("&nbsp;", " ")
          .trim()
      val items =
        re.findAll(html).take(5).map { clean(it.groupValues[1]) + " — " + clean(it.groupValues[2]) }.toList()
      if (items.isEmpty()) "" else items.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
    } catch (_: Exception) {
      ""
    }
  }

  private fun playYoutube(q: String): String {
    val acc = RiyaAccessibilityService.instance ?: return "Accessibility চালু নেই বস"
    val i =
      Intent(Intent.ACTION_SEARCH)
        .setPackage("com.google.android.youtube")
        .putExtra("query", q)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launchedNative = LxrAppDiscoveryEngine.launchActivitySafely(ctx, acc, i)
    if (!launchedNative) {
      LxrAppDiscoveryEngine.launchActivitySafely(
        ctx,
        acc,
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    }
    Thread.sleep(900)
    if (LockVaultManager.isAppLockScreenShowing(acc)) {
      LockVaultManager.unlockAppLockIfPresent(ctx, acc)
      Thread.sleep(450)
    }
    repeat(5) {
      if (acc.clickFirstVideo()) {
        RiyaState.markTaskCompleted(
          "YouTube-এ '$q' চালু করা হয়েছে।",
          "",
        )
        return "YouTube-এ '$q' প্লে করে দিয়েছি বস।"
      }
      Thread.sleep(280)
    }
    acc.tapFallback()
    return "'$q' চালানো হচ্ছে বস"
  }

  private fun mediaControl(a: String): String {
    val code =
      when (a) {
        "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
        "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> return "unknown"
      }
    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    return "হয়েছে, বস! মিডিয়া কমান্ড সম্পন্ন।"
  }

  private fun initTrack() {
    val usage =
      if (usingBtAudio) AudioAttributes.USAGE_VOICE_COMMUNICATION
      else AudioAttributes.USAGE_MEDIA
    val minAudioBuf =
      AudioTrack.getMinBufferSize(
        24000,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    playbackSpeed = Store.voiceSpeed(ctx)
    val t =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setSampleRate(24000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(maxOf(minAudioBuf * 6, 49_152))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also {
          try {
            it.playbackParams = PlaybackParams().setSpeed(playbackSpeed).setPitch(1.0f)
          } catch (_: Throwable) {}
          it.play()
        }
    synchronized(trackLock) {
      totalSpeakerFramesWritten = 0L
      speakerHeadBaseFrames = 0L
      lastAudibleEndFrame = 0L
      track = t
    }
  }

  private fun createBestAudioRecord(minBuf: Int): AudioRecord {
    val sources =
      if (usingBtAudio) {
        listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
      } else {
        // VOICE_RECOGNITION disables Infinix/MediaTek VoIP close-talk attenuation on built-in Phone Mic!
        listOf(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          MediaRecorder.AudioSource.MIC,
          MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
      }
    var lastCreated: AudioRecord? = null
    for (src in sources) {
      try {
        val candidate =
          AudioRecord(
            src,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 2560),
          )
        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
          return candidate
        }
        candidate.release()
      } catch (_: Throwable) {}
    }
    return lastCreated
      ?: AudioRecord(
        MediaRecorder.AudioSource.MIC,
        16000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuf, 2560),
      )
  }

  private fun startMic() {
    val min =
      AudioRecord.getMinBufferSize(
        16000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    val r = createBestAudioRecord(min)
    if (usingBtAudio) {
      findBluetoothInputDevice()?.let { r.setPreferredDevice(it) }
    } else {
      findBuiltInMicDevice()?.let { r.setPreferredDevice(it) }
    }
    hasHwAec = false
    if (AcousticEchoCanceler.isAvailable()) {
      try {
        val aec = AcousticEchoCanceler.create(r.audioSessionId)
        if (aec != null) {
          aec.enabled = true
          hasHwAec = aec.enabled
        }
      } catch (_: Throwable) {}
    }
    // Apply NoiseSuppressor only on Bluetooth SCO/BLE; skip platform AGC which pumps/distorts voices on BT TWS & MediaTek mics.
    if (usingBtAudio && NoiseSuppressor.isAvailable()) {
      try {
        NoiseSuppressor.create(r.audioSessionId)?.enabled = true
      } catch (_: Throwable) {}
    }
    synchronized(recLock) {
      rec = r
    }
    micNoiseSuppressor.reset()
    try {
      r.startRecording()
    } catch (_: Throwable) {}
    val t =
      Thread {
        try {
          val buf = ByteArray(1280) // 40ms @ 16kHz PCM-16 mono (Google Live optimal low-latency chunk)
          val zeroSilence = ByteArray(1280)
          var lastVoiceMs = 0L
          var silentFrameCounter = 0
          var consecutiveLoudMicChunks = 0
          var bossActiveSpeechThisTurn = false
          var sentSpeechChunksThisTurn = 0
          val preRollRing = ArrayDeque<ByteArray>(5)
          val bargeVerifyStream = java.io.ByteArrayOutputStream()

          // Voice Match 3-step speaker gate state: 0=IDLE, 1=COLLECTING_ONSET, 2=ALLOWED_BOSS, 3=REJECTED_OTHER
          var gateState = 0
          val onsetChunks = ArrayList<ByteArray>(24)
          val rejectedRing = ArrayDeque<ByteArray>(8)
          val verifyStream = java.io.ByteArrayOutputStream()
          var consecutiveRejects = 0
          var rejectedSpeechChunks = 0
          var lastBossVerifiedAtMs = 0L
          var lastVmRefreshAt = 0L
          var voiceMatchActive = VoiceMatchManager.isActiveGateReady(ctx)
          var vmProfile = if (voiceMatchActive) VoiceMatchManager.loadProfile(ctx) else null
          var vmSensitivity = Store.voiceMatchSensitivity(ctx)
          if (voiceMatchActive) {
            RiyaState.voiceMatchLiveLabel.value = "🔒 শুধু বসের কণ্ঠ শুনছে"
          } else {
            RiyaState.voiceMatchLiveLabel.value = ""
          }

          fun sendAudioPcm(chunk: ByteArray, len: Int, countAsSpeech: Boolean = false) {
            if (countAsSpeech) sentSpeechChunksThisTurn++
            val d = Base64.encodeToString(chunk, 0, len, Base64.NO_WRAP)
            ws?.send("""{"realtimeInput":{"audio":{"data":"$d","mimeType":"audio/pcm;rate=16000"}}}""")
          }

          // Smooth non-blocking turn enqueue: captures Reply Delay immutably per command in FIFO order
          fun markSmoothTurnBoundary(delayOpt: ReplyDelayOption) {
            val d = delayOpt.voiceDelayMs
            val nowBoundary = System.currentTimeMillis()
            pendingVoiceDelayUntilMs = if (d > 0L) nowBoundary + d else 0L
            voiceQueue.enqueueCommand(
              rawText = "[VOICE_TURN]",
              source = "VOICE",
              capturedDelayMs = d,
              speechEndedAtMs = nowBoundary,
            )
            suppressStaleUntilMs = 0L
          }

          var recReadFailures = 0
          while (!stopped) {
            val activeRec = synchronized(recLock) { rec } ?: break
            val n =
              try {
                if (activeRec.state == AudioRecord.STATE_INITIALIZED) activeRec.read(buf, 0, buf.size)
                else -1
              } catch (_: Throwable) {
                -1
              }
            if (n < 0) {
              if (stopped) break
              recReadFailures++
              if (recReadFailures > 5) break
              Thread.sleep(180L)
              try {
                val old = synchronized(recLock) { rec }
                runCatching { old?.stop() }
                runCatching { old?.release() }
                val fresh = createBestAudioRecord(min)
                if (usingBtAudio) findBluetoothInputDevice()?.let { fresh.setPreferredDevice(it) }
                else findBuiltInMicDevice()?.let { fresh.setPreferredDevice(it) }
                fresh.startRecording()
                synchronized(recLock) { rec = fresh }
              } catch (_: Throwable) {}
              continue
            }
            recReadFailures = 0
            if (n > 0 && ready && !stopped && (ws?.queueSize() ?: 0L) < 48_000L) {
              val now = System.currentTimeMillis()
              if (now - lastVmRefreshAt > 400L) {
                lastVmRefreshAt = now
                val newActive = VoiceMatchManager.isActiveGateReady(ctx)
                if (newActive != voiceMatchActive) {
                  voiceMatchActive = newActive
                  gateState = 0
                  onsetChunks.clear()
                  verifyStream.reset()
                  consecutiveRejects = 0
                  rejectedSpeechChunks = 0
                  RiyaState.voiceMatchLiveLabel.value =
                    if (newActive) "🔒 শুধু বসের কণ্ঠ শুনছে" else ""
                }
                if (voiceMatchActive) {
                  vmProfile = VoiceMatchManager.loadProfile(ctx)
                  vmSensitivity = Store.voiceMatchSensitivity(ctx)
                }
              }

              val rawRms = computeRms16Bit(buf, n)
              val whisperBoostOn = Store.whisperBoost(ctx)

              val activeTrack = track
              val head =
                if (activeTrack != null && activeTrack.state == AudioTrack.STATE_INITIALIZED) {
                  runCatching { activeTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
                } else {
                  0L
                }
              val trackPlaying =
                activeTrack != null &&
                  activeTrack.state == AudioTrack.STATE_INITIALIZED &&
                  activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING
              val speakerSpeakingGuardNow =
                AudioEchoGuard.isPlaybackHeadStillSpeaking(
                  isPlaying = trackPlaying,
                  playbackHeadFrames = head,
                  lastAudibleEndFrame = lastAudibleEndFrame,
                  lastSpeakerWriteMs = lastSpeakerWriteMs,
                  nowMs = now,
                  activeTurnHasStartedOutput = activeTurnHasStartedOutput,
                ) || pendingAudioChunks.get() > 0
              val ttsSpeakingNow = RiyaVoiceManager.isActivelySpeaking()
              if (speakerSpeakingGuardNow || ttsSpeakingNow) {
                lastAudibleStopMs = now
              } else if (rawRms < 6000.0) {
                lastSpeakerOutputRms *= 0.85
              }

              val directThreshold =
                if (usingBtAudio) maxOf(4200.0, lastSpeakerOutputRms * 1.85)
                else maxOf(7800.0, lastSpeakerOutputRms * 1.95)
              val directRatioNow = if (usingBtAudio) 1.85 else 1.95
              val requiredLoudChunks = if (usingBtAudio) 5 else 4
              if ((speakerSpeakingGuardNow || ttsSpeakingNow) && rawRms >= directThreshold) {
                consecutiveLoudMicChunks++
              } else {
                consecutiveLoudMicChunks = 0
              }

              // Fast Voice Match barge-in check so Boss's true voice interrupts cleanly while speaker echo cannot
              var voiceMatchBargeInNow = false
              if (
                (speakerSpeakingGuardNow || ttsSpeakingNow) &&
                  voiceMatchActive &&
                  vmProfile != null &&
                  vmProfile.isComplete &&
                  rawRms >= maxOf(1600.0, lastSpeakerOutputRms * 1.15)
              ) {
                bargeVerifyStream.write(buf, 0, n)
                if (bargeVerifyStream.size() >= 2560) {
                  val res = VoiceMatchEngine.verifyPcmChunk(vmProfile, bargeVerifyStream.toByteArray(), vmSensitivity)
                  if (res?.matched == true) {
                    voiceMatchBargeInNow = true
                    bargeVerifyStream.reset()
                  } else if (bargeVerifyStream.size() >= 5120) {
                    bargeVerifyStream.reset()
                  }
                }
              } else {
                if (bargeVerifyStream.size() > 0) bargeVerifyStream.reset()
              }

              val directLoudBargeIn =
                !voiceMatchActive &&
                  consecutiveLoudMicChunks >= requiredLoudChunks &&
                  rawRms >= directThreshold

              val isSoftMuted =
                AudioEchoGuard.shouldMuteMicWithBargeIn(
                  usingBtAudio = usingBtAudio,
                  nowMs = now,
                  micMuteUntilMs = micMuteUntilMs,
                  isSpeakerAudiblyPlayingNow = speakerSpeakingGuardNow,
                  ttsIsSpeaking = ttsSpeakingNow,
                  lastAudibleStopMs = lastAudibleStopMs,
                  rawRms = rawRms,
                  lastSpeakerOutputRms = lastSpeakerOutputRms,
                  voiceMatchBargeIn = voiceMatchBargeInNow,
                  consecutiveLoudChunks = if (directLoudBargeIn) consecutiveLoudMicChunks else 0,
                  phoneTailHoldMs = 360L,
                  minDirectBargeInRms = directThreshold,
                  directRatio = directRatioNow,
                )

              if (
                !isSoftMuted &&
                  (speakerSpeakingGuardNow || ttsSpeakingNow) &&
                  (voiceMatchBargeInNow || directLoudBargeIn)
              ) {
                // Genuine Boss interruption mid-speech: flush playback cleanly so LxR listens immediately
                sentSpeechChunksThisTurn = 0
                suppressStaleUntilMs = now + 2200L
                activeTurnHasStartedOutput = false
                pendingVoiceDelayUntilMs = 0L
                interruptSpeakerForBoss()
                RiyaVoiceManager.stopSpeaking()
              }

              // Capture raw voice frame BEFORE DSP filtering so VoiceMatch compares natural mic timbre
              val vmSampleChunk = if (voiceMatchActive && !isSoftMuted) buf.copyOf(n) else null

              // Apply real-time software DSP Noise Cancellation on BOTH Bluetooth and Phone Mic!
              val dspResult =
                if (isSoftMuted) {
                  buf.fill(0, 0, n)
                  lastVoiceMs = 0L
                  bossActiveSpeechThisTurn = false
                  sentSpeechChunksThisTurn = 0
                  preRollRing.clear()
                  LxrAudioNoiseSuppressor.FrameResult(0.0, 45.0, false)
                } else {
                  micNoiseSuppressor.processFrameInPlace(
                    pcm = buf,
                    len = n,
                    bluetoothMode = usingBtAudio,
                    whisperBoostOn = whisperBoostOn,
                  )
                }

              val hasSpeech = !isSoftMuted && dspResult.isSpeechActive
              val isTurnOnset = hasSpeech && !bossActiveSpeechThisTurn
              if (hasSpeech) {
                bossActiveSpeechThisTurn = true
                lastVoiceMs = now
                lastBossTurnAtMs = now
                lastBossSpeechDetectedMs = now
                proactiveCountInStretch = 0
              } else if (!isSoftMuted && !bossActiveSpeechThisTurn) {
                if (preRollRing.size >= 5) preRollRing.removeFirst()
                preRollRing.addLast(buf.copyOf(n))
              }

              val delayOpt = ReplyDelayOption.fromSeconds(RiyaState.replyDelaySeconds.value)
              val currentFlushMs = LiveVadConfig.effectiveEndOfTurnFlushMs(delayOpt)
              val currentHangoverMs = LiveVadConfig.effectiveHangoverMs(delayOpt)
              val turnSilenceWindowMs = maxOf(currentHangoverMs + 140L, currentFlushMs)
              val withinHangover = !isSoftMuted && lastVoiceMs > 0L && (now - lastVoiceMs) <= currentHangoverMs
              val withinEndOfTurnSilence =
                !isSoftMuted && bossActiveSpeechThisTurn && !withinHangover && lastVoiceMs > 0L && (now - lastVoiceMs) <= turnSilenceWindowMs
              val withinVmPauseGrace =
                voiceMatchActive && gateState == 1 && !isSoftMuted && lastVoiceMs > 0L && (now - lastVoiceMs) <= 650L
              silentFrameCounter = if (isSoftMuted || !withinHangover) (silentFrameCounter + 1) else 0
              val lowNet = Store.lowNetFastMode(ctx) && isSlowNetwork()

              // Smooth continuous 40ms PCM streaming like Riya-AI v1.0.0 (never drops clock or kills VAD with audioStreamEnd:true)
              val justEndedTurn =
                !isSoftMuted && bossActiveSpeechThisTurn && lastVoiceMs > 0L && (now - lastVoiceMs) > turnSilenceWindowMs

              val activeProfile = vmProfile
              if (!voiceMatchActive || activeProfile == null || !activeProfile.isComplete) {
                if (justEndedTurn) {
                  bossActiveSpeechThisTurn = false
                  silentFrameCounter = 1
                  lastTurnSpeechChunks = sentSpeechChunksThisTurn
                  if (!NetworkStabilityGuard.shouldIgnoreEmptyTurnFlush(sentSpeechChunksThisTurn)) {
                    markSmoothTurnBoundary(delayOpt)
                    waitingServerReply = true
                    lastBossTurnAtMs = now
                  }
                  sentSpeechChunksThisTurn = 0
                  sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                } else if (withinHangover) {
                  if (isTurnOnset && preRollRing.isNotEmpty()) {
                    for (pre in preRollRing) sendAudioPcm(pre, pre.size, countAsSpeech = true)
                    preRollRing.clear()
                  }
                  sendAudioPcm(buf, n, countAsSpeech = true)
                } else if (!lowNet || withinEndOfTurnSilence || silentFrameCounter % 3 == 0) {
                  // Stream continuous silence frames like Riya-AI v1.0.0 so server VAD clock advances smoothly at 1.0x
                  sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                }
              } else {
                if (justEndedTurn) {
                  if (gateState == 1 && onsetChunks.isNotEmpty() && verifyStream.size() >= 1920) {
                    val quickRes = VoiceMatchEngine.verifyPcmChunk(activeProfile, verifyStream.toByteArray(), vmSensitivity)
                    if (quickRes != null && quickRes.matched) {
                      lastBossVerifiedAtMs = now
                      for (pendingChunk in onsetChunks) {
                        sendAudioPcm(pendingChunk, pendingChunk.size, countAsSpeech = true)
                      }
                    }
                  }
                  bossActiveSpeechThisTurn = false
                  silentFrameCounter = 1
                  lastTurnSpeechChunks = sentSpeechChunksThisTurn
                  if (!NetworkStabilityGuard.shouldIgnoreEmptyTurnFlush(sentSpeechChunksThisTurn)) {
                    markSmoothTurnBoundary(delayOpt)
                    waitingServerReply = true
                    lastBossTurnAtMs = now
                  }
                  sentSpeechChunksThisTurn = 0
                  sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                  if (gateState != 0) {
                    gateState = 0
                    onsetChunks.clear()
                    rejectedRing.clear()
                    verifyStream.reset()
                    consecutiveRejects = 0
                    rejectedSpeechChunks = 0
                    RiyaState.voiceMatchLiveLabel.value = "🔒 শুধু বসের কণ্ঠ শুনছে"
                  }
                } else if (isSoftMuted || (!withinHangover && !withinVmPauseGrace)) {
                  if (gateState == 1 && !isSoftMuted && onsetChunks.isNotEmpty() && verifyStream.size() >= 1920) {
                    val quickRes = VoiceMatchEngine.verifyPcmChunk(activeProfile, verifyStream.toByteArray(), vmSensitivity)
                    if (quickRes != null && quickRes.matched) {
                      lastBossVerifiedAtMs = now
                      for (pendingChunk in onsetChunks) {
                        sendAudioPcm(pendingChunk, pendingChunk.size, countAsSpeech = true)
                      }
                    }
                  }
                  if (gateState != 0) {
                    gateState = 0
                    onsetChunks.clear()
                    rejectedRing.clear()
                    verifyStream.reset()
                    consecutiveRejects = 0
                    rejectedSpeechChunks = 0
                    RiyaState.voiceMatchLiveLabel.value = "🔒 শুধু বসের কণ্ঠ শুনছে"
                  }
                  if (!lowNet || withinEndOfTurnSilence || silentFrameCounter % 3 == 0) {
                    sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                  }
                } else if (!withinHangover && withinVmPauseGrace) {
                  // Keep gateState == 1 intact across short intra-word pauses so slow speech isn't dropped
                  if (!lowNet || silentFrameCounter % 2 == 0) {
                    sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                  }
                } else {
                  val sendChunk = buf.copyOf(n)
                  val minOnset =
                    if (delayOpt == ReplyDelayOption.INSTANT) 2
                    else vmSensitivity.onsetMinChunks
                  val rejectAt = vmSensitivity.rejectAfterChunks
                  val bossGraceMs =
                    if (delayOpt == ReplyDelayOption.INSTANT) 20_000L
                    else 12_000L
                  // Boss session grace window: follow-up words pass smoothly without re-stalling
                  if (gateState == 0 && lastBossVerifiedAtMs > 0L && (now - lastBossVerifiedAtMs) <= bossGraceMs) {
                    gateState = 2
                    consecutiveRejects = 0
                    verifyStream.reset()
                  }
                  when (gateState) {
                    0, 1 -> {
                      gateState = 1
                      onsetChunks.add(sendChunk)
                      if (vmSampleChunk != null && hasSpeech) {
                        verifyStream.write(vmSampleChunk)
                      }
                      if (onsetChunks.size >= minOnset && verifyStream.size() >= 2400) {
                        val res =
                          VoiceMatchEngine.verifyPcmChunk(
                            activeProfile,
                            verifyStream.toByteArray(),
                            vmSensitivity,
                          )
                        if (res != null && res.matched) {
                          gateState = 2
                          lastBossVerifiedAtMs = now
                          consecutiveRejects = 0
                          verifyStream.reset()
                          RiyaState.voiceMatchLiveLabel.value =
                            "🔓 বস ভয়েস মিলেছে (${res.confidencePercent}%)"
                          for (pendingChunk in onsetChunks) {
                            sendAudioPcm(pendingChunk, pendingChunk.size, countAsSpeech = true)
                          }
                          onsetChunks.clear()
                        } else if (res != null && !res.matched && onsetChunks.size >= rejectAt) {
                          gateState = 3
                          lastBossVerifiedAtMs = 0L
                          rejectedSpeechChunks = 0
                          rejectedRing.clear()
                          onsetChunks.clear()
                          verifyStream.reset()
                          RiyaState.voiceMatchLiveLabel.value =
                            "🔇 অন্য কণ্ঠ এভোয়েড (${res.confidencePercent}%)"
                        } else if (onsetChunks.size >= 24) {
                          gateState = 0
                          onsetChunks.clear()
                          verifyStream.reset()
                        }
                      } else if (onsetChunks.size >= 24) {
                        gateState = 0
                        onsetChunks.clear()
                        verifyStream.reset()
                      }
                    }
                    2 -> {
                      lastBossVerifiedAtMs = now
                      sendAudioPcm(sendChunk, sendChunk.size, countAsSpeech = true)
                      if (vmSampleChunk != null && hasSpeech) {
                        verifyStream.write(vmSampleChunk)
                      }
                      if (verifyStream.size() >= 25600) {
                        val res =
                          VoiceMatchEngine.verifyPcmChunk(
                            activeProfile,
                            verifyStream.toByteArray(),
                            vmSensitivity,
                          )
                        verifyStream.reset()
                        if (res != null && !res.matched) {
                          consecutiveRejects++
                          if (consecutiveRejects >= 3) {
                            gateState = 3
                            lastBossVerifiedAtMs = 0L
                            rejectedSpeechChunks = 0
                            rejectedRing.clear()
                            RiyaState.voiceMatchLiveLabel.value =
                              "🔇 অন্য কণ্ঠ এভোয়েড (${res.confidencePercent}%)"
                          }
                        } else if (res != null && res.matched) {
                          consecutiveRejects = 0
                          RiyaState.voiceMatchLiveLabel.value =
                            "🔓 বস ভয়েস মিলেছে (${res.confidencePercent}%)"
                        }
                      }
                    }
                    else -> {
                      if (silentFrameCounter % 10 == 0) {
                        sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                      }
                      if (hasSpeech) {
                        if (rejectedRing.size >= 6) rejectedRing.removeFirst()
                        rejectedRing.addLast(sendChunk)
                      }
                      if (vmSampleChunk != null && hasSpeech) {
                        verifyStream.write(vmSampleChunk)
                        rejectedSpeechChunks++
                      }
                      val retryChunks = (rejectAt - 2).coerceIn(4, 8)
                      if (rejectedSpeechChunks >= retryChunks) {
                        val res =
                          VoiceMatchEngine.verifyPcmChunk(
                            activeProfile,
                            verifyStream.toByteArray(),
                            vmSensitivity,
                          )
                        verifyStream.reset()
                        rejectedSpeechChunks = 0
                        if (res != null && res.matched) {
                          gateState = 2
                          lastBossVerifiedAtMs = now
                          consecutiveRejects = 0
                          RiyaState.voiceMatchLiveLabel.value =
                            "🔓 বস ভয়েস মিলেছে (${res.confidencePercent}%)"
                          for (buffered in rejectedRing) {
                            sendAudioPcm(buffered, buffered.size, countAsSpeech = true)
                          }
                          rejectedRing.clear()
                        } else {
                          rejectedRing.clear()
                          if (res != null) {
                            RiyaState.voiceMatchLiveLabel.value =
                              "🔇 অন্য কণ্ঠ এভোয়েড (${res.confidencePercent}%)"
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        } catch (_: Throwable) {}
      }
    recThread = t
    t.start()
  }

  private fun computeRms16Bit(buf: ByteArray, len: Int): Double {
    var sumSq = 0.0
    var count = 0
    var i = 0
    while (i + 1 < len) {
      val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
      sumSq += sample * sample
      count++
      i += 2
    }
    return if (count > 0) sqrt(sumSq / count) else 0.0
  }

  private fun scaleBuffer(buf: ByteArray, len: Int, factor: Double) {
    var i = 0
    while (i + 1 < len) {
      val raw = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
      val scaled = (raw * factor).toInt().coerceIn(-32768, 32767)
      buf[i] = (scaled and 0xFF).toByte()
      buf[i + 1] = ((scaled ushr 8) and 0xFF).toByte()
      i += 2
    }
  }


  fun sendText(t: String) {
    if (stopped) return
    val delayMs = ReplyDelayOption.fromSeconds(RiyaState.replyDelaySeconds.value).voiceDelayMs
    val nowMs = System.currentTimeMillis()
    val enq =
      voiceQueue.enqueueCommand(
        rawText = t,
        source = "TEXT",
        capturedDelayMs = delayMs,
        speechEndedAtMs = nowMs,
      )
    if (!enq.accepted) return
    suppressStaleUntilMs = 0L
    lastBossTurnAtMs = nowMs
    proactiveCountInStretch = 0
    pendingVoiceDelayUntilMs = if (delayMs > 0L) nowMs + delayMs else 0L
    RiyaState.addLog("বস: $t")
    Store.remember(ctx, "Boss(text): $t")
    try {
      val payload =
        JSONObject()
          .put(
            "clientContent",
            JSONObject()
              .put(
                "turns",
                JSONArray()
                  .put(
                    JSONObject()
                      .put("role", "user")
                      .put("parts", JSONArray().put(JSONObject().put("text", t)))
                  ),
              )
              .put("turnComplete", true),
          )
      ws?.send(payload.toString())
    } catch (_: Throwable) {}
  }

  fun sendCameraInlineSnapshot(
    jpeg: ByteArray,
    instructionPrompt: String,
    triggerReply: Boolean = false,
  ): Boolean {
    if (!ready || stopped || jpeg.isEmpty()) return false
    return try {
      val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
      val parts =
        JSONArray()
          .put(
            JSONObject()
              .put(
                "inlineData",
                JSONObject()
                  .put("mimeType", "image/jpeg")
                  .put("data", b64),
              )
          )
          .put(JSONObject().put("text", instructionPrompt))
      val payload =
        JSONObject()
          .put(
            "clientContent",
            JSONObject()
              .put(
                "turns",
                JSONArray()
                  .put(
                    JSONObject()
                      .put("role", "user")
                      .put("parts", parts),
                  ),
              )
              .put("turnComplete", triggerReply),
          )
      ws?.send(payload.toString()) == true
    } catch (_: Throwable) {
      false
    }
  }

  fun notifyCameraActive(
    active: Boolean,
    isFrontCamera: Boolean = RiyaState.useFrontCamera.value,
    proactiveCuriosity: Boolean = false,
    faceDetected: Boolean = false,
  ) {
    if (!ready || stopped) return
    suppressStaleUntilMs = 0L
    if (!active) {
      try {
        val payload =
          JSONObject()
            .put(
              "clientContent",
              JSONObject()
                .put(
                  "turns",
                  JSONArray()
                    .put(
                      JSONObject()
                        .put("role", "user")
                        .put(
                          "parts",
                          JSONArray()
                            .put(
                              JSONObject()
                                .put("text", "[সিস্টেম ভিশন ক্যামেরা বন্ধ]: ক্যামেরা বন্ধ করা হয়েছে, এখন ২৪/৭ স্ক্রিন পর্যবেক্ষণ মোডে থাকো।")
                            ),
                        ),
                    ),
                )
                .put("turnComplete", false),
            )
        ws?.send(payload.toString())
      } catch (_: Throwable) {}
      return
    }

    val snap = LxrCameraVisionEngine.latestSnapshot()
    val ver = LxrCameraVisionGuard.evaluateFrameUsability(snap)
    val jpeg = snap?.jpegBytes
    if (!ver.hasFreshFrame || jpeg == null || jpeg.isEmpty()) {
      // Never tell Gemini "you see Boss" when no verified real JPEG frame has arrived yet!
      return
    }

    val realFace = isFrontCamera && faceDetected && ver.isFaceVisibleNow && ver.isReadableBrightness
    val hint =
      when {
        !ver.isReadableBrightness ->
          "[সিস্টেম ক্যামেরা — অন্ধকার/ঢাকা ফ্রেম]: সংযুক্ত image/jpeg ছবিটি খুব অন্ধকার (${ver.averageLuma}/255)। পরিষ্কার কিছু না দেখলে অনুমান করে কিছু বানাবে না, সত্য বলো।"
        realFace && proactiveCuriosity ->
          "[সিস্টেম ফ্রন্ট ক্যামেরা আসল ছবি নিশ্চিত]: সংযুক্ত image/jpeg ফ্রেমে সত্যিই বসের মুখ শনাক্ত হয়েছে। ছবিটি সরাসরি দেখে চিনে প্রিয় মানুষের মতো মায়া ও কৌতূহল নিয়ে বসের মুড বা ভালো-মন্দ নিয়ে একটি ছোট, আন্তরিক প্রশ্ন করো! ছবিতে যা সত্যিই দেখা যাচ্ছে কেবল সেটাই বলো।"
        isFrontCamera ->
          "[সিস্টেম ফ্রন্ট ক্যামেরা আসল ছবি]: ফ্রন্ট ক্যামেরার তাজা image/jpeg ছবি পাঠানো হলো, তবে স্পষ্ট মুখ ফ্রেমে নিশ্চিত হয়নি। কেবল ছবিতে যা সত্যিই দেখা যাচ্ছে তাই বলো — না দেখে বানিয়ে কিছু বলবে না।"
        else ->
          "[সিস্টেম ব্যাক ক্যামেরা আসল ছবি]: ব্যাক ক্যামেরার তাজা image/jpeg ছবি পাঠানো হলো। কেবল ছবিতে যা সত্যিই দেখা যাচ্ছে তাই বলো।"
      }
    sendCameraInlineSnapshot(
      jpeg = jpeg,
      instructionPrompt = hint,
      triggerReply = realFace && proactiveCuriosity,
    )
  }

  fun sendCameraFrame(jpeg: ByteArray) {
    if (!ready || stopped || jpeg.isEmpty()) return
    if ((ws?.queueSize() ?: 0) > 220_000) return
    try {
      val activeModel = modelOverride ?: Store.model(ctx)
      ws?.send(LxrCameraVisionGuard.buildRealtimeVideoChunkJson(activeModel, jpeg))
    } catch (_: Throwable) {}
  }

  fun sendImage(jpeg: ByteArray) {
    if (!ready || stopped || jpeg.isEmpty()) return
    if ((ws?.queueSize() ?: 0L) > 12_000L) return
    try {
      val activeModel = modelOverride ?: Store.model(ctx)
      ws?.send(LxrCameraVisionGuard.buildRealtimeVideoChunkJson(activeModel, jpeg))
    } catch (_: Throwable) {}
  }
}
