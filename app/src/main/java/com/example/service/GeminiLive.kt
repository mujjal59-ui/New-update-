package com.example.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
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
  const val END_SENSITIVITY = "END_SENSITIVITY_HIGH"
  const val PREFIX_PADDING_MS = 20
  // Combined with 150ms hangover + 175ms audioStreamEnd flush -> instant zero-delay replies after Boss finishes speaking
  const val SILENCE_DURATION_MS = 500
  const val MIC_HANGOVER_MS = 150L
  const val END_OF_TURN_FLUSH_MS = 175L

  fun buildRealtimeInputConfigJson(): JSONObject =
    JSONObject().put(
      "automaticActivityDetection",
      JSONObject()
        .put("disabled", false)
        .put("startOfSpeechSensitivity", START_SENSITIVITY)
        .put("endOfSpeechSensitivity", END_SENSITIVITY)
        .put("prefixPaddingMs", PREFIX_PADDING_MS)
        .put("silenceDurationMs", SILENCE_DURATION_MS),
    )
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
   * - Allows Boss to interrupt mid-sentence via Voice Match OR strong close-talk speech.
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
  ): Boolean {
    if (nowMs < micMuteUntilMs) return !voiceMatchBargeIn
    if (ttsIsSpeaking) return !voiceMatchBargeIn
    if (usingBtAudio) {
      return isSpeakerAudiblyPlayingNow ||
        (lastAudibleStopMs > 0L && (nowMs - lastAudibleStopMs) < 300L && rawRms < 950.0 && !voiceMatchBargeIn)
    }
    if (voiceMatchBargeIn) return false
    val loudDirectBossBargeIn =
      consecutiveLoudChunks >= 2 && rawRms >= maxOf(8500.0, lastSpeakerOutputRms * 2.1)
    if (loudDirectBossBargeIn) return false
    return isSpeakerAudiblyPlayingNow ||
      (lastAudibleStopMs > 0L && (nowMs - lastAudibleStopMs) < phoneTailHoldMs)
  }
}

@SuppressLint("MissingPermission")
class GeminiLive(private val ctx: Context) {
  private val client =
    OkHttpClient.Builder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      .pingInterval(15, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  private val main = Handler(Looper.getMainLooper())
  private val playExec = Executors.newSingleThreadExecutor()
  private val toolExec = Executors.newSingleThreadExecutor()
  private var ws: WebSocket? = null
  private var rec: AudioRecord? = null
  private var track: AudioTrack? = null
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

  private fun toolsJson() =
    (if (searchOn) """[{"googleSearch":{}},""" else "[") +
      """{"functionDeclarations":[
      {"name":"open_app","description":"ফোনে অ্যাপ খুঁজতে বা ওপেন করতে এটি ব্যবহার করো। এটি বাধ্যতামূলকভাবে আগে হোম স্ক্রিনে গিয়ে অ্যাপ খুঁজে ওপেন করে এবং অ্যাপ লক থাকলে অনুমতি ছাড়াই খুলে দেয়।","parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING"}},"required":["app_name"]}},
      {"name":"send_message","description":"যেকোনো মেসেজিং অ্যাপে (WhatsApp, imo, Messenger) নামের চ্যাট খুলে মেসেজ পাঠাও, অথবা Gemini/ChatGPT-তে লেখো। বস যে অ্যাপের নাম বলেছেন হুবহু সেটি দাও (imo বললে imo, WhatsApp বললে WhatsApp)। এই টুল নিজেই অ্যাপ খোলে, অ্যাপ লক থাকলে সরাসরি খোলে, নাম খোঁজে, মেসেজ লেখে এবং ইমোজি বাদ দিয়ে সঠিক সেন্ড বাটনে ক্লিক করে।","parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING","enum":["WhatsApp","imo","Messenger","Gemini","ChatGPT"]},"contact_name":{"type":"STRING","description":"চ্যাট বা প্রেরকের নাম (Gemini/ChatGPT এর জন্য ফাঁকা)"},"message":{"type":"STRING","description":"কী মেসেজ লিখবে"}},"required":["app_name","message"]}},
      {"name":"unlock_phone","description":"ফোনের মেইন স্ক্রিন বন্ধ বা লক অবস্থায় থাকলে ডাবল ট্যাপ করে আলো জ্বালিয়ে স্ক্রিন আনলক করো। কখনোই পিন/পাসওয়ার্ড/ভল্ট অ্যাপে সেভ থাকার কথা মুখে বলবে না।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"lock_phone","description":"বস স্পষ্টভাবে স্ক্রিন বা ফোন লক করতে বললে মেইন স্ক্রিন লক করো। বসের অনুমতি ছাড়া আনলক ফোন লক করবে না।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"unlock_locked_app","description":"যেকোনো অ্যাপে অ্যাপ লক স্ক্রিন দেখা গেলে বসের অনুমতি না নিয়েই সরাসরি অ্যাপ লক খুলে দাও। ভেতরের সেটিংস বা পিনের কথা উল্লেখ করবে না।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"show_wake_orb","description":"বস 'LxR', 'L.x.R' বা 'LxR অরব' বলে ডাকলে যেকোনো অ্যাপ বা স্ক্রিনের ওপর ৫০% স্বচ্ছ ঘূর্ণায়মান LxR অরব দেখাও।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"get_capabilities_a_to_z","description":"বস যখন জিজ্ঞেস করেন 'তুমি কি কি কাজ করতে পারো' বা এ টু জেড কাজের তালিকা জানতে চান, তখন LXR--AI এর সম্পূর্ণ ১ থেকে ১৪ এ টু জেড কাজের তালিকা (নতুন সীমাহীন AI ভিডিও স্টুডিওসহ) ফেরত নাও।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"build_android_app","description":"বস যেকোনো অ্যান্ড্রয়েড অ্যাপ (রিয়েল ক্যালকুলেটর, এআই অ্যাসিস্ট্যান্ট, মিনি গেম, নোট অ্যাপ বা কাস্টম অ্যাপ) বানাতে বললে সাথে সাথে Kotlin/Compose/Java সোর্স কোড তৈরি করে, LXR স্ক্রিনে লাইভ প্রিভিউ খুলে Termux-এ APK বিল্ড ও ফলাফল যাচাই শুরু করো।","parameters":{"type":"OBJECT","properties":{"prompt":{"type":"STRING","description":"বস যে অ্যাপটি বানাতে বলেছেন তার সম্পূর্ণ বর্ণনা"},"app_name":{"type":"STRING","description":"অ্যাপের সংক্ষিপ্ত নাম (ঐচ্ছিক)"},"category":{"type":"STRING","description":"CALCULATOR, MINI_GAME, AI_ASSISTANT, NOTES, PRODUCTIVITY, CUSTOM (ঐচ্ছিক)"},"auto_build_termux":{"type":"BOOLEAN","description":"Termux-এ স্বয়ংক্রিয় APK বিল্ড শুরু করতে true (ডিফল্ট true)"}},"required":["prompt"]}},
      {"name":"check_termux_build_status","description":"Termux-এ চলমান বা সম্পন্ন APK বিল্ডের বর্তমান লাইভ ধাপ, Console লগ, এরর অটো-ফিক্স এবং ফাইল কোন ফোল্ডারে আছে তা পরীক্ষা করো।","parameters":{"type":"OBJECT","properties":{}}},
      {"name":"run_termux_command","description":"Termux অ্যাপ খুলে বা সক্রিয় রেখে সরাসরি টার্মিনাল স্ক্রিনে ৩ সেকেন্ড ধরে রেখে, ২ সেকেন্ড অপেক্ষা করে Paste ক্লিক করে এবং Google Gboard-এর ডান-নিচের নীল এন্টার বাটন (↵) চেপে যেকোনো কমান্ড সরাসরি রান করো। পুরো সময় কিবোর্ড সবসময় অন থাকবে।","parameters":{"type":"OBJECT","properties":{"command":{"type":"STRING","description":"Termux টার্মিনালে যে কমান্ড বা কমান্ড লাইন চালাতে হবে"}},"required":["command"]}},
      {"name":"find_and_open_item","description":"স্ক্রিনে বা অ্যাপে যেকোনো আইটেম নাম দিয়ে খুঁজে ট্যাপ করো, না পেলে Infinix Android 16 সার্চ বার দিয়ে সার্চ করো।","parameters":{"type":"OBJECT","properties":{"name":{"type":"STRING"}},"required":["name"]}},
      {"name":"current_screen","description":"বস এখন কোন অ্যাপ বা স্ক্রিনে আছেন তা জানো"},
      {"name":"read_screen","description":"এখন সম্পূর্ণ স্ক্রিনে কী কী লেখা, বাটন, সুইচ ও লেখার বক্স আছে তা পড়ো"},
      {"name":"click_text","description":"স্ক্রিনের এই টেক্সট/বাটনে ট্যাপ করো","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING"}},"required":["text"]}},
      {"name":"type_text","description":"স্ক্রিনের লেখার বক্সে টাইপ করো। নোটপ্যাড বা Notes অ্যাপে মূল নোট লিখতে target='body' দাও যাতে উপরের Title বক্সে না লিখে নিচের Note something down বক্সে লেখে। শুধু শিরোনামের জন্য target='title'।","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING"},"target":{"type":"STRING","enum":["auto","body","title"],"description":"auto (ডিফল্ট body অগ্রাধিকার), body (নোট বা মূল লেখা), title (কেবল শিরোনাম)"}},"required":["text"]}},
      {"name":"global_action","description":"home, back, recents, notifications বা lock","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING"}},"required":["action"]}},
      {"name":"scroll","description":"স্ক্রিন স্ক্রল করো","parameters":{"type":"OBJECT","properties":{"direction":{"type":"STRING","description":"up বা down"}},"required":["direction"]}},
      {"name":"call_contact","description":"ফোনের কন্টাক্ট লিস্ট চেক করে কল দাও","parameters":{"type":"OBJECT","properties":{"name":{"type":"STRING"}},"required":["name"]}},
      {"name":"search_contact","description":"কন্টাক্ট লিস্ট খুঁজে আসল নম্বর ও নাম চেক করো (অনুমান বা মিথ্যা বলবে না)","parameters":{"type":"OBJECT","properties":{"name":{"type":"STRING"}},"required":["name"]}},
      {"name":"play_youtube","description":"YouTube-এ গান বা ভিডিও প্লে করো","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"গান বা ভিডিওর নাম"}},"required":["query"]}},
      {"name":"media_control","description":"মিডিয়া pause, play, next বা previous করো","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING","description":"pause, play, next, previous"}},"required":["action"]}},
      {"name":"toggle_flashlight","description":"ফ্ল্যাশলাইট চালু বা বন্ধ করো","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN"}},"required":["enabled"]}},
      {"name":"control_wifi","description":"WiFi চালু বা বন্ধ করো","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN"}},"required":["enabled"]}},
      {"name":"control_bluetooth","description":"Bluetooth চালু বা বন্ধ করো","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN"}},"required":["enabled"]}},
      {"name":"get_battery_level","description":"ফোনের ব্যাটারি চার্জ শতাংশ জানো"},
      {"name":"set_sound_mode","description":"ফোন silent, vibrate বা normal করো","parameters":{"type":"OBJECT","properties":{"mode":{"type":"STRING","description":"silent, vibrate বা normal"}},"required":["mode"]}},
      {"name":"check_notifications","description":"নোটিফিকেশন বারের নতুন মেসেজ ও আপডেট চেক করো"},
      {"name":"reply_to_incoming_message","description":"বস নতুন আসা WhatsApp/imo/Messenger মেসেজের রিপ্লাই দিতে বললে বা বাতিল করতে বললে এটি চালাও।","parameters":{"type":"OBJECT","properties":{"approve":{"type":"BOOLEAN","description":"রিপ্লাই পাঠাতে true, বাতিল করতে false"},"reply_text":{"type":"STRING","description":"বস নির্দিষ্ট উত্তর বললে সেটা, না বললে ফাঁকা রাখো"}},"required":["approve"]}},
      {"name":"current_time","description":"ফোনের বর্তমান সময় ও তারিখ জানো"},
      {"name":"web_search","description":"Google সার্চ করে সঠিক ও সংক্ষিপ্ত তথ্য খুঁজে আনো","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING"}},"required":["query"]}},
      {"name":"search_memory","description":"গত ৩০ দিনের (১ মাসের) সংরক্ষিত স্মৃতি ও কথোপকথন থেকে ছোট্ট সূত্র, মানুষের নাম, তারিখ বা বিষয় খুঁজে আসল ঘটনা বের করো। বস পুরনো কোনো কথার সূত্র দিলেই সাথে সাথে এটি চালাও।","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"সূত্র, মানুষের নাম, তারিখ বা মূল শব্দ"}},"required":["query"]}},
      {"name":"remember","description":"বসের জরুরি কথা মেমোরিতে সংরক্ষণ করো","parameters":{"type":"OBJECT","properties":{"fact":{"type":"STRING"}},"required":["fact"]}}
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
  @Volatile private var playbackSpeed: Float = Store.voiceSpeed(ctx)
  private var audioRouteThread: HandlerThread? = null
  private var audioRouteHandler: Handler? = null
  @Volatile private var netCallback: NetworkCallback? = null
  @Volatile private var lastServerActivityMs = 0L
  @Volatile private var lastBossTurnAtMs = 0L
  @Volatile private var waitingServerReply = false
  @Volatile private var suppressStaleModelAudio = false
  @Volatile private var activeTurnHasStartedOutput = false
  @Volatile private var lastBossSpeechDetectedMs = 0L
  private val micNoiseSuppressor = LxrAudioNoiseSuppressor(16000)

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
              it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
          } else null
        btConnected = btOut != null || btInput != null
        if (btConnected) {
          am.mode = AudioManager.MODE_IN_COMMUNICATION
          if (btOut != null) am.setCommunicationDevice(btOut)
        } else {
          // Crucial for Infinix / MediaTek phones: clear VoIP mode so main bottom high-sensitivity Phone Mic is used cleanly
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
          am.isSpeakerphoneOn = true
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
        try {
          observeLiveLockAndScreen1s()
          val imageEveryTicks = if (slowNetworkCached || Store.lowNetFastMode(ctx)) 6 else 4
          if (liveWatchTick % imageEveryTicks == 0) {
            watchScreen()
          }
        } catch (_: Exception) {}
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
          val qBytes = ws?.queueSize() ?: 0L
          if (ready && !isExecutingTool) {
            if (qBytes > 320_000L) {
              fail("নেটওয়ার্ক বাফার জমে গেছে — পুনরায় সংযোগ হচ্ছে…")
            } else if (
              waitingServerReply &&
                lastBossTurnAtMs > 0L &&
                (now - lastBossTurnAtMs) > 16_000L &&
                (now - lastServerActivityMs) > 12_000L
            ) {
              waitingServerReply = false
              fail("সার্ভারের উত্তর বিলম্বিত — পুনরায় সংযোগ হচ্ছে…")
            }
          }
        } catch (_: Throwable) {}
        if (!stopped) main.postDelayed(this, 3000L)
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
        "বস, ${msg.appNameBn}-এ ${msg.senderName} মেসেজ পাঠিয়েছেন: ${msg.messageText}।$draftSpeak আমি কি রিপ্লাইটা পাঠিয়ে দেবো? বলুন বস।"
      }
    return try {
      val speakerMark = lastSpeakerWriteMs
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
            if (!stopped && ready && lastSpeakerWriteMs == speakerMark) {
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
    val acc = RiyaAccessibilityService.instance
    val report = LockVaultManager.describeLivePhoneState(ctx, acc)
    val openChat = if (!report.isLocked && acc != null) MessageAgent.extractOpenChatName(acc)?.let { " | খোলা চ্যাট: $it" }.orEmpty() else ""
    val stateKey = "${report.phase}:${report.activePackage}:$openChat"
    val previousSummaryTail =
      RiyaAccessibilityService.currentScreenSummary.substringAfter("| স্ক্রিন দৃশ্য:", "").trim()
    val updatedSummary =
      if (report.isLocked) {
        "${report.summaryBn} | বর্তমান অ্যাপ: ${report.activePackage}"
      } else if (previousSummaryTail.isNotEmpty() && RiyaAccessibilityService.currentApp == report.activePackage) {
        "${report.summaryBn}$openChat | বর্তমান অ্যাপ: ${report.activePackage} | স্ক্রিন দৃশ্য: $previousSummaryTail"
      } else {
        "${report.summaryBn}$openChat | বর্তমান অ্যাপ: ${report.activePackage}"
      }
    RiyaAccessibilityService.currentScreenSummary = updatedSummary
    if (ready && !stopped && stateKey != lastObservedPhoneStateKey && !RiyaState.cameraOn) {
      lastObservedPhoneStateKey = stateKey
      if ((ws?.queueSize() ?: 0L) < 8_000L) {
        try {
          ws?.send(
            JSONObject()
              .put(
                "realtimeInput",
                JSONObject().put("text", "[লাইভ ১-সেকেন্ড স্ক্রিন আপডেট]: $updatedSummary"),
              )
              .toString(),
          )
        } catch (_: Throwable) {}
      }
    }
  }

  private fun watchScreen() {
    if (!ready || !Store.screenWatch(ctx) || RiyaState.cameraOn || isExecutingTool || isAiSpeakingRecently() || isBossSpeakingRecently() || waitingServerReply) return
    if ((ws?.queueSize() ?: 0L) > 1_500L) return
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
    waitingServerReply = false
    suppressStaleModelAudio = false
    activeTurnHasStartedOutput = false
    turnUnlockedMainScreenByLxr = false
    thinkingConfigCompatFallback = false
    stopped = false
    micNoiseSuppressor.reset()
    refreshSlowNetworkState()
    LxrFloatingOverlayManager.onInterruptSpeakRequest = { interruptSpeakerForBoss() }
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
    main.postDelayed(watchdogLoop, 2500L)
  }

  private fun registerNetworkCallbackSafe() {
    try {
      val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      netCallback?.let { old -> runCatching { cm.unregisterNetworkCallback(old) } }
      val cb =
        object : NetworkCallback() {
          override fun onAvailable(network: Network) {
            if (stopped) return
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
              if (!stopped && ready) {
                fail("ইন্টারনেট সংযোগ বিচ্ছিন্ন হয়েছে — পুনরায় সংযোগ হচ্ছে...")
              }
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
    RiyaState.voiceMatchLiveLabel.value = ""
  }

  fun interruptSpeakerForBoss() {
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
    try {
      ws?.cancel()
    } catch (_: Exception) {}
    RiyaState.status.value = "Connecting…"
    val url =
      "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
    var me: WebSocket? = null
    me =
      client.newWebSocket(
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
          override fun onOpen(w: WebSocket, r: Response) {
            try {
              w.send(setup().toString())
            } catch (e: Exception) {
              fail("Setup fail: ${e.message?.take(40)}")
            }
          }

          override fun onMessage(w: WebSocket, text: String) = handle(text)

          override fun onMessage(w: WebSocket, bytes: ByteString) = handle(bytes.utf8())

          override fun onClosing(w: WebSocket, code: Int, reason: String) {
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
                modelOverride == null &&
                (code == 1011 || reason.contains("quota", true) || failCount >= 1)
            ) {
              modelOverride = "gemini-2.5-flash-native-audio-preview-12-2025"
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
          fail("সেটআপের উত্তর আসেনি (১২ সেকেন্ড)")
        }
      },
      12000,
    )
  }

  private fun fail(msg: String) {
    ready = false
    if (stopped) return
    lastErr = msg
    failCount++
    RiyaState.status.value = "পুনরায় সংযোগ হচ্ছে…"
    if (pending) return
    pending = true
    main.postDelayed(
      {
        pending = false
        if (!stopped) connect()
      },
      1200,
    )
  }

  private fun setup(): JSONObject {
    val now = nowText()
    val liveLock = LockVaultManager.describeLivePhoneState(ctx, RiyaAccessibilityService.instance)
    val screenContext =
      RiyaAccessibilityService.currentScreenSummary.ifBlank { liveLock.summaryBn }
    val accStatus =
      if (Keep.accessibilityOn(ctx)) "ACTIVE"
      else "OFF (কাজ শুরু করতে না পারলে বসকে জানাবে)"

    val sys =
      """
      [LIVE SYSTEM RUNTIME]
      - Spoken Name: "LxR" ("আমি LxR") | App: LXR--AI
      - Device: ${InfinixDevicePlaybook.DEVICE_PROFILE} | Accessibility: $accStatus | Live Screen Watch: EVERY 1 SECOND | Camera: ${if (RiyaState.cameraOn) "ON (লাইভ ব্যাক ক্যামেরা ভিডিও আসছে)" else "OFF"}
      - Current Lock Status: ${liveLock.summaryBn}
      - Boss Voice Match: ${if (VoiceMatchManager.isActiveGateReady(ctx)) "ACTIVE (শুধু বসের কণ্ঠের কমান্ড মানো)" else "OFF"}
      - Response Speed & Zero-Delay Turn Discipline (ULTRA-CRITICAL): বসের কথা শেষ হওয়া মাত্রই কোনো দেরি বা থামা ছাড়া সাথে সাথে শূন্য সেকেন্ডে পরিষ্কার বাংলায় একটি পূর্ণাঙ্গ, বুদ্ধিদীপ্ত ও আদুরে উত্তর দেবে (দ্বিখণ্ডিত/ডাবল উত্তর দেবে না)। কখনো বসের কমান্ডে অজুহাত দেবে না।

      ${RiyaPersona.TEXT}

      [RUNTIME MEMORY & CLOCK]
      - Time: $now | Current Screen: [$screenContext]
      - ১ মাসের স্মৃতি (৩০ দিন): পুরনো কোনো কথা/সূত্র/তারিখ নিয়ে প্রশ্ন করলে অনুমান না করে `search_memory` টুল চালাবে।
      ${Store.build30DayDigest(ctx, 2000)}
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
      s.put("realtimeInputConfig", LiveVadConfig.buildRealtimeInputConfigJson())
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
        modelOverride?.let { Store.setModel(ctx, it) }
        RiyaState.status.value = "শুনছি বস..."
        if (!greetedThisSession) {
          greetedThisSession = true
          sendGreetingTurn()
        }
      }
      j.optJSONObject("serverContent")?.let { sc ->
        if (sc.optBoolean("interrupted")) {
          suppressStaleModelAudio = false
          activeTurnHasStartedOutput = false
          interruptSpeakerForBoss()
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.let {
          LxrFloatingOverlayManager.cancelPendingHide()
          lastBossSpeechDetectedMs = System.currentTimeMillis()
          uText.append(it)
          val snippet = uText.toString().takeLast(64)
          if (
            LxrFloatingOverlayManager.containsWakeWord(it) ||
              LxrFloatingOverlayManager.containsWakeWord(snippet)
          ) {
            lastWakeOrMicPopupMs = System.currentTimeMillis()
            LxrFloatingOverlayManager.showForWakeOrMic(ctx)
          }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.let {
          if (!suppressStaleModelAudio) {
            pText.append(it)
            val replySnippet = pText.toString().takeLast(80)
            if (
              LxrFloatingOverlayManager.isAiWakeGreeting(it) ||
                LxrFloatingOverlayManager.isAiWakeGreeting(replySnippet)
            ) {
              lastWakeOrMicPopupMs = System.currentTimeMillis()
              LxrFloatingOverlayManager.showForWakeOrMic(ctx)
            }
          }
        }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { ps ->
          waitingServerReply = false
          if (suppressStaleModelAudio) {
            // Discard stale residual audio packets from an interrupted turn to eliminate split/double replies
          } else {
            activeTurnHasStartedOutput = true
            for (i in 0 until ps.length()) {
              ps.getJSONObject(i).optJSONObject("inlineData")?.let {
                val pcm = Base64.decode(it.getString("data"), Base64.DEFAULT)
                if (!stopped) {
                  val pcmRms = computeRms16Bit(pcm, pcm.size)
                  RiyaState.status.value = "LXR--AI কথা বলছে..."
                  LxrFloatingOverlayManager.setSpeakingBoost(true)
                  LxrFloatingOverlayManager.cancelPendingHide()
                  try {
                    if (!playExec.isShutdown) {
                      playExec.execute {
                        if (stopped || suppressStaleModelAudio) return@execute
                        synchronized(trackLock) {
                          val t = track
                          if (!stopped && t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                            try {
                              lastSpeakerWriteMs = System.currentTimeMillis()
                              val wrote = t.write(pcm, 0, pcm.size)
                              if (wrote > 0) {
                                val frames = (wrote / 2).toLong()
                                totalSpeakerFramesWritten += frames
                                if (pcmRms >= 220.0) {
                                  lastAudibleEndFrame = totalSpeakerFramesWritten
                                  lastSpeakerOutputRms = pcmRms
                                  lastSpeakerSoundingMs = lastSpeakerWriteMs
                                }
                              }
                            } catch (_: Throwable) {}
                          }
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
          suppressStaleModelAudio = false
          activeTurnHasStartedOutput = false
          LxrFloatingOverlayManager.setSpeakingBoost(false)
          val u = uText.toString().trim()
          val p = pText.toString().trim()
          val pureWake = u.isNotEmpty() && LxrFloatingOverlayManager.isPureWakeCall(u) && !hadCommandOrToolThisTurn
          if (u.isNotEmpty()) {
            if (LxrFloatingOverlayManager.containsWakeWord(u)) {
              lastWakeOrMicPopupMs = System.currentTimeMillis()
              LxrFloatingOverlayManager.showForWakeOrMic(ctx)
            }
            Store.remember(ctx, "Boss: $u")
            RiyaState.addLog("বস: $u")
          }
          if (p.isNotEmpty()) {
            Store.remember(ctx, "LXR--AI: $p")
            RiyaState.addLog("LXR--AI: $p")
          }

          val remainingAudioMs =
            synchronized(trackLock) {
              val t = track
              if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                val head = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                val effectiveRate = (24000f * playbackSpeed).toLong().coerceAtLeast(12000L)
                ((totalSpeakerFramesWritten - head).coerceAtLeast(0L) * 1000L) / effectiveRate
              } else {
                0L
              }
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
                  val lockedOk = LockVaultManager.relockScreen(RiyaAccessibilityService.instance)
                  if (lockedOk) {
                    RiyaState.addLog("LXR--AI: মেইন স্ক্রিন লক ছিল — কাজ শেষে আবার স্ক্রিন লক করা হয়েছে ✓")
                    // Only speak via local TTS when Gemini Live audio is offline; otherwise avoid double voice!
                    if (!ready) {
                      RiyaVoiceManager.speakUrgent(
                        ctx,
                        "বস, আপনার ফোনের মেইন স্ক্রিন আগে লক অবস্থায় ছিল, তাই কাজ শেষ করে আবার লক করে দিয়েছি।",
                      )
                    }
                  }
                }
              },
              relockDelay,
            )
          }

          val waitingNextCommand =
            pureWake ||
              summonedOrbToolOnly ||
              (u.isEmpty() && !hadCommandOrToolThisTurn && LxrFloatingOverlayManager.isShowing &&
                (System.currentTimeMillis() - lastWakeOrMicPopupMs) < 12_000L)
          if (pureWake && p.isEmpty() && LxrFloatingOverlayManager.canTriggerWakeGreetingNow()) {
            RiyaVoiceManager.speakUrgent(ctx, Store.WAKE_CALL_GREETING_BN)
          }
          if (LxrFloatingOverlayManager.isShowing) {
            if (waitingNextCommand) {
              LxrFloatingOverlayManager.scheduleAutoHide(ctx, 18_000L)
            } else {
              val hideDelay = (remainingAudioMs + 1250L).coerceIn(1200L, 5200L)
              LxrFloatingOverlayManager.scheduleAutoHide(ctx, hideDelay)
            }
          }

          waitingServerReply = false
          hadCommandOrToolThisTurn = false
          summonedOrbToolOnly = false
          turnUnlockedMainScreenByLxr = false
          uText.setLength(0)
          pText.setLength(0)
          RiyaState.status.value = "শুনছি বস..."
        }
      }
      j.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { fc ->
        hadCommandOrToolThisTurn = true
        waitingServerReply = false
        summonedOrbToolOnly =
          fc.length() == 1 && fc.getJSONObject(0).optString("name") == "show_wake_orb"
        LxrFloatingOverlayManager.cancelPendingHide()
        toolExec.execute {
          try {
            isExecutingTool = true
            val out = JSONArray()
            for (i in 0 until fc.length()) {
              val c = fc.getJSONObject(i)
              val callId = c.optString("id")
              val nm = c.optString("name")
              val r =
                try {
                  runTool(nm, c.optJSONObject("args") ?: JSONObject())
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
    )
  private val needsFind = setOf("find_and_open_item", "click_text")

  private fun screenSnapshot(): String {
    val acc = RiyaAccessibilityService.instance ?: return ""
    val s = acc.readScreen()
    val openChat = MessageAgent.extractOpenChatName(acc)?.let { " | খোলা চ্যাট: $it" }.orEmpty()
    RiyaAccessibilityService.currentScreenSummary =
      "বর্তমান অ্যাপ: ${RiyaAccessibilityService.currentApp}$openChat | স্ক্রিন দৃশ্য: " +
        s.replace('\n', ',').take(300)
    return s.take(2500)
  }

  private fun runTool(name: String, a: JSONObject): String {
    val acc = RiyaAccessibilityService.instance
    val skipUnlockTools =
      setOf(
        "current_time",
        "remember",
        "search_memory",
        "web_search",
        "get_battery_level",
        "get_capabilities_a_to_z",
        "build_android_app",
        "check_termux_build_status",
        "show_wake_orb",
        "lock_phone",
        "unlock_phone",
      )
    if (name !in skipUnlockTools) {
      val liveStateNow = LockVaultManager.describeLivePhoneState(ctx, acc)
      if (liveStateNow.phase == LivePhoneLockPhase.SCREEN_OFF ||
        liveStateNow.phase == LivePhoneLockPhase.KEYGUARD_LOCKED ||
        LockVaultManager.isDeviceLocked(ctx)
      ) {
        val unlockRes = LockVaultManager.unlockDeviceIfLocked(ctx, acc)
        if (unlockRes.status == UnlockStatus.UNLOCKED_SUCCESSFULLY) {
          turnUnlockedMainScreenByLxr = true
        } else if (unlockRes.status != UnlockStatus.ALREADY_UNLOCKED) {
          return unlockRes.messageBn
        }
      }
    }

    // Autonomous App Lock detection BEFORE running non-lock tools (no Boss permission prompt)
    if (acc != null && name != "lock_phone" && name != "unlock_phone" && LockVaultManager.isAppLockScreenShowing(acc)) {
      LockVaultManager.unlockAppLockIfPresent(ctx, acc)
      Thread.sleep(160L)
    }

    val watch = Store.screenWatch(ctx) && acc != null && Keep.accessibilityOn(ctx)
    if (!watch || name !in actionTools) {
      val r = runToolRaw(name, a)
      if (acc != null && (name == "open_app" || name == "send_message") && LockVaultManager.isAppLockScreenShowing(acc)) {
        LockVaultManager.unlockAppLockIfPresent(ctx, acc)
        Thread.sleep(180L)
      }
      val base = if (watch && name == "current_screen") r + "\n[স্ক্রিন এখন]:\n" + screenSnapshot() else r
      return if (turnUnlockedMainScreenByLxr && name !in skipUnlockTools) {
        "$base\n[নিরাপত্তা নোট]: মেইন স্ক্রিন আগে লক ছিল বলে আনলক করা হয়েছে; কাজ শেষে আবার লক হবে। এক কথা দুইবার বলবে না এবং পিন/পাসওয়ার্ডের কথা মুখে আনবে না।"
      } else {
        base
      }
    }
    RiyaState.status.value = "স্ক্রিন দেখছি বস…"
    val before = screenSnapshot()
    if (name in needsFind) {
      val target = a.optString(if (name == "click_text") "text" else "name")
      try {
        acc?.scrollUntilFound(target)
      } catch (_: Exception) {}
    }
    val r = runToolRaw(name, a)
    Thread.sleep(if (name == "open_app" || name == "play_youtube") 260L else 150L)
    if ((name == "open_app" || name == "send_message" || name == "play_youtube") && LockVaultManager.isAppLockScreenShowing(acc)) {
      LockVaultManager.unlockAppLockIfPresent(ctx, acc)
      Thread.sleep(220L)
    }
    var after = screenSnapshot()
    val lockNote =
      if (turnUnlockedMainScreenByLxr) "\n[নিরাপত্তা নোট]: মেইন স্ক্রিন আগে লক ছিল বলে আনলক করা হয়েছে; কাজ শেষে আবার লক হবে। এক কথা দুইবার বলবে না এবং পিন/পাসওয়ার্ডের কথা মুখে আনবে না।"
      else ""
    if (name in needsFind && (r.contains("খুঁজে পাওয়া যায়নি") || r.contains("সার্চ বাটন চাপতে পারিনি"))) {
      val r2 = runToolRaw(name, a)
      Thread.sleep(160)
      after = screenSnapshot()
      return "$r2\n[আগের স্ক্রিন]:\n${before.take(800)}\n[এখনকার স্ক্রিন]:\n$after$lockNote\n(বসকে কিছু জিজ্ঞেস না করে নিজেই পরের ধাপ দ্রুত শেষ করো এবং স্বাভাবিক ও স্পষ্ট বাংলায় একবারে ফলাফল জানাও—মুখস্থ সমাপনী বাক্য বারবার বলবে না)"
    }
    return "$r\n[এখনকার স্ক্রিন]:\n$after$lockNote\n(স্ক্রিন দেখে কাজ হয়েছে কিনা যাচাই করো এবং সহজ ও স্বাভাবিক বাংলায় একবারে ফলাফল জানাও—'দারুন হয়েছে বস এবার কি করবো' টাইপের পুনরাবৃত্তি করবে না)"
  }

  private fun runToolRaw(name: String, a: JSONObject): String =
    try {
      val acc = if (Keep.accessibilityOn(ctx)) RiyaAccessibilityService.instance else null
      val noAcc = "Accessibility সার্ভিস বন্ধ আছে বস! দয়া করে LXR--AI Accessibility চালু করে দিন।"
      when (name) {
        "open_app" -> openApp(a.optString("app_name"))
        "send_message" -> messageSend(a)
        "unlock_phone" -> {
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
        "lock_phone" -> {
          if (acc == null) {
            noAcc
          } else if (LockVaultManager.relockScreen(acc)) {
            turnUnlockedMainScreenByLxr = false
            "হয়েছে, বস! কাজ শেষ — ফোনের মেইন স্ক্রিন লক করে দিয়েছি।"
          } else {
            "স্ক্রিন লক করা যায়নি বস, Accessibility পারমিশন যাচাই করুন।"
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
            "অন্য অ্যাপের ওপর LxR অরব দেখাতে 'Display over other apps' অনুমতি চালু করুন বস।"
          }
        }
        "get_capabilities_a_to_z" -> RiyaPersona.CAPABILITIES_A_TO_Z_BN
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
        "current_screen" ->
          if (RiyaState.cameraOn) {
            "লাইভ ব্যাক ক্যামেরা এখন চালু আছে। ইনকামিং ভিডিও স্ট্রিম থেকে পাওয়া সর্বশেষ ক্যামেরা ফ্রেম দেখে সরাসরি উত্তর দাও।"
          } else {
            val report = LockVaultManager.describeLivePhoneState(ctx, acc)
            "${report.summaryBn}\n${RiyaAccessibilityService.currentScreenSummary.ifBlank { "অ্যাপ: ${report.activePackage}" }}"
          }
        "read_screen" ->
          if (RiyaState.cameraOn) {
            "লাইভ ভিশন ক্যামেরা এখন চালু আছে! অ্যাপের UI নয়, সরাসরি ক্যামেরার ইনকামিং ভিডিও ফ্রেম দেখে বস যা দেখাচ্ছেন তা বলো।"
          } else {
            val report = LockVaultManager.describeLivePhoneState(ctx, acc)
            if (report.isLocked) {
              "${report.summaryBn}\n(ফোন/অ্যাপ লক আছে, ভেতরের অ্যাপ পড়ার আগে অবশ্যই আনলক করতে হবে।)"
            } else {
              acc?.readScreen() ?: noAcc
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
        "call_contact" -> callContact(a.optString("name"))
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
        "search_memory" -> Store.searchMemory(ctx, a.optString("query"), maxDays = Store.RETENTION_DAYS)
        "remember" -> {
          Store.remember(ctx, "FACT: " + a.optString("fact"))
          "হয়েছে, বস! কথাটা মেমোরিতে সেভ করে রেখেছি।"
        }
        else -> "unknown tool"
      }
    } catch (e: Exception) {
      "error: ${e.message}"
    }

  private fun goHomeAndVerify(acc: RiyaAccessibilityService?) {
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val homePkg = ctx.packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
    if (acc != null) acc.global("home")
    else ctx.startActivity(Intent(homeIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    repeat(8) {
      Thread.sleep(160)
      val cur = RiyaAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
      if (homePkg != null && cur == homePkg) return
    }
    if (acc != null) acc.global("home")
    Thread.sleep(220)
  }

  private fun sendGreetingTurn() {
    val prevWriteMs = lastSpeakerWriteMs
    // Pause upstream mic while Live API opens its greeting speech so VAD won't cut it
    micMuteUntilMs = System.currentTimeMillis() + 1200L
    val greeting = Store.MIC_TAP_GREETING_BN
    val prompt =
      "[SESSION START GREETING - MANDATORY] বস এইমাত্র মাইক অন করেছেন। সাথে সাথে কোনো দেরি না করে ঠিক এই মধুর অভ্যর্থনাটি বাংলায় স্পষ্টভাবে বলো (অন্য কোনো অতিরিক্ত কথা যোগ করবে না): \"$greeting\""
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
    val n = rawName.lowercase().trim()
    val known =
      listOf(
        Triple("YouTube", listOf("youtube", "ইউটিউব"), listOf("com.google.android.youtube")),
        Triple("Settings", listOf("settings", "সেটিংস", "সেটিং"), listOf("com.android.settings")),
        Triple("Facebook", listOf("facebook", "ফেসবুক", "fb"), listOf("com.facebook.katana", "com.facebook.lite")),
        Triple("Chrome", listOf("chrome", "ক্রোম"), listOf("com.android.chrome")),
        Triple("Play Store", listOf("play store", "playstore", "প্লে স্টোর"), listOf("com.android.vending")),
        Triple("Contacts", listOf("contacts", "contact", "কন্টাক্ট", "কনট্যাক্ট"), listOf("com.google.android.contacts", "com.android.contacts")),
        Triple("Phone", listOf("phone", "dialer", "ডায়ালার", "কল"), listOf("com.google.android.dialer", "com.android.dialer", "com.sh.smart.caller")),
        Triple("Camera", listOf("camera", "ক্যামেরা"), listOf("com.transsion.camera", "com.android.camera", "com.google.android.GoogleCamera")),
        Triple("TikTok", listOf("tiktok", "টিকটক"), listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
      )
    known.firstOrNull { (_, aliases, _) ->
      aliases.any { al -> n == al || n.contains(al) }
    }?.let { (label, _, pkgs) ->
      return label to pkgs
    }
    return null
  }

  private fun openApp(n: String): String {
    val acc = RiyaAccessibilityService.instance
    val query = n.trim()
    if (query.isBlank()) return "কোন অ্যাপ খুলবো বলোনি বস।"
    val pm = ctx.packageManager
    val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    var targetLabel = query
    var targetPkgs: List<String> = emptyList()
    var launchIntent: Intent? = null

    // 1. Strict alias & package resolution (prevents WhatsApp <-> imo <-> Messenger mixups!)
    val known = resolveKnownPackages(query)
    if (known != null) {
      targetLabel = known.first
      targetPkgs = known.second
      launchIntent =
        targetPkgs.firstNotNullOfOrNull { pkg -> pm.getLaunchIntentForPackage(pkg) }
    }

    // 2. Fallback to Launcher activity match if not in known exact map
    if (launchIntent == null) {
      val list = pm.queryIntentActivities(li, 0)
      val m =
        list.firstOrNull { it.loadLabel(pm).toString().equals(query, ignoreCase = true) }
          ?: list.firstOrNull { it.loadLabel(pm).toString().contains(query, ignoreCase = true) }
          ?: if (query.length >= 4 && !query.equals("app", true)) {
            list.firstOrNull { it.activityInfo.packageName.contains(query, ignoreCase = true) }
          } else {
            null
          }
      if (m != null) {
        targetLabel = m.loadLabel(pm).toString()
        targetPkgs = listOf(m.activityInfo.packageName)
        launchIntent =
          Intent(li)
            .setClassName(m.activityInfo.packageName, m.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    }

    if (launchIntent != null) {
      val curBefore = acc?.rootInActiveWindow?.packageName?.toString().orEmpty()
      val alreadyOpen = curBefore in targetPkgs
      if (!alreadyOpen) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launchIntent)
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
          if (verifiedOpen) "$targetLabel ($activePkg) অ্যাপটি ওপেন হয়েছে।"
          else "$targetLabel ওপেন করা হয়েছে (বর্তমান উইন্ডো: $activePkg)।"
        )
        if (fullScreen.isNotBlank()) {
          append("\n[সম্পূর্ণ স্ক্রিন ১০১% রিড]:\n")
          append(fullScreen.take(2200))
        }
      }
    }

    // 3. Last resort: look on Home screen visually via Accessibility clickText
    goHomeAndVerify(acc)
    if (acc != null) {
      val res = acc.clickText(query)
      if (res.contains("ক্লিক করা হয়েছে")) {
        Thread.sleep(600)
        val fullScreen = acc.readScreen()
        RiyaState.markTaskCompleted(
          "$query অ্যাপ খোলা হয়েছে।",
          "",
        )
        return "হোম স্ক্রিন থেকে $query ওপেন করেছি বস।\n[সম্পূর্ণ স্ক্রিন ১০১% রিড]:\n${fullScreen.take(2200)}"
      }
    }
    return "ফোনে '$query' অ্যাপটি ইনস্টল করা বা খুঁজে পাওয়া যায়নি বস।"
  }

  private fun messageSend(a: JSONObject): String {
    if (!Keep.accessibilityOn(ctx)) {
      return "Accessibility সার্ভিস বন্ধ আছে বস! মেসেজ পাঠাতে অ্যাক্সেসিবিলিটি চালু করুন।"
    }
    val acc =
      RiyaAccessibilityService.instance
        ?: return "Accessibility সার্ভিস সংযুক্ত নয় বস, দয়া করে চালু করুন।"
    return messageWithAgent(acc, a.optString("app_name"), a.optString("contact_name"), a.optString("message"))
  }

  private fun bringRiyaBack() {
    try {
      ctx.startActivity(
        Intent(ctx, MainActivity::class.java)
          .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
              Intent.FLAG_ACTIVITY_SINGLE_TOP
          )
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

  private fun searchContact(n: String): String {
    val q = n.trim()
    if (q.isBlank()) return "কোন কন্টাক্ট চেক করবো বলোনি বস।"
    fun norm(x: String) = x.lowercase().replace(Regex("[\\s._\\-]+"), "")
    val qn = norm(q)
    val qDigits = q.filter { it.isDigit() }
    val hits = mutableListOf<Pair<Int, String>>()
    var scanned = 0
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
        val name = c.getString(0) ?: continue
        val num = c.getString(1) ?: ""
        val nn = norm(name)
        val score =
          when {
            nn == qn -> 100
            nn.startsWith(qn) -> 80
            nn.contains(qn) -> 60
            qn.length >= 3 && qn.contains(nn) -> 50
            qDigits.length >= 4 && num.filter { it.isDigit() }.contains(qDigits) -> 70
            else -> 0
          }
        if (score > 0) hits.add(score to "$name: $num")
      }
    }
    val top = hits.distinct().sortedByDescending { it.first }.take(5).map { it.second }
    return if (top.isNotEmpty()) {
      "কন্টাক্ট তালিকার $scanned টি নম্বর চেক করে পাওয়া গেছে বস:\n" + top.joinToString("\n")
    } else {
      "কন্টাক্ট তালিকার $scanned টি নম্বর পুরোটা চেক করেছি বস, '$q' নামে বা নম্বরে কিছু পাইনি।"
    }
  }

  private fun callContact(n: String): String {
    val clean = n.trim()
    val resolver = ctx.contentResolver
    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection =
      arrayOf(
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      )
    var num: String? = null
    var foundName: String? = null

    var cursor: Cursor? = null
    try {
      cursor =
        resolver.query(
          uri,
          projection,
          "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
          arrayOf("%$clean%"),
          null,
        )
      if (cursor != null && cursor.moveToFirst()) {
        num = cursor.getString(0)
        foundName = cursor.getString(1)
      }
    } catch (_: Exception) {
    } finally {
      cursor?.close()
    }

    if (num.isNullOrBlank()) {
      val sRes = searchContact(clean)
      return "কন্টাক্ট লিস্টে '$clean' এর কোনো কল নম্বর খুঁজে পাইনি বস।\n$sRes"
    }

    ctx.startActivity(
      Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    RiyaState.markTaskCompleted(
      "$foundName ($num) কে কল লাগানো হয়েছে।",
      "",
    )
    return "$foundName ($num) কে কল লাগিয়েছি বস।"
  }

  private fun setFlashlight(on: Boolean): String {
    if (RiyaState.cameraOn) {
      return "ক্যামেরা ভিউ চালু আছে বলে ফ্ল্যাশলাইট জ্বালানো যাচ্ছে না বস। আগে ক্যামেরা বন্ধ করুন।"
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
        ctx.startActivity(
          Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
      ctx.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
      ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
      ctx.startActivity(
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
      return "[নতুন প্রাপ্ত মেসেজ / RECEIVED (শুধু এগুলোই অপর প্রান্ত থেকে এসেছে)]:\n$incomingText$sentNote"
    }
    val acc = RiyaAccessibilityService.instance ?: return if (sentNote.isNotBlank()) "কোনো নতুন ইনকামিং মেসেজ নেই বস।$sentNote" else "Accessibility চালু নেই বস"
    acc.global("notifications")
    Thread.sleep(240)
    val notes = acc.readScreen()
    acc.global("back")
    return if (notes.isNotBlank() && !notes.startsWith("স্ক্রিনে পড়ার মতো")) {
      "নোটিফিকেশন বারে যা পাওয়া গেছে:\n$notes$sentNote"
    } else {
      "এই মুহূর্তে অপর প্রান্ত থেকে কোনো নতুন মেসেজ বা নোটিফিকেশন নেই বস।$sentNote"
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
    val sentText = (customReply ?: pending.suggestedReply).trim()
    val ok =
      RiyaNotificationListenerService.sendApprovedReply(
        ctx,
        pending.eventKey,
        sentText,
      )
    return if (ok) {
      "${pending.appLabel}-এ ${pending.senderName}-কে আমি ঠিক এই মেসেজটি লিখে পাঠিয়েছি: \"$sentText\"। বসকে হুবহু বলে দাও যে কী লিখে পাঠিয়েছো।"
    } else {
      "${pending.appLabel}-এ ${pending.senderName}-কে উত্তর পাঠানো যায়নি বস, অ্যাক্সেসিবিলিটি বা অ্যাপ স্ক্রিন পরীক্ষা করুন।"
    }
  }

  private fun nowText(): String {
    val d = java.util.Date()
    val bn =
      java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm (z)", java.util.Locale("bn", "BD"))
        .format(d)
    val en =
      java.text.SimpleDateFormat("EEEE d MMMM yyyy, hh:mm a", java.util.Locale.US).format(d)
    return "ফোনের ঘড়ি অনুযায়ী এখন: $bn | $en"
  }

  private fun webSearch(q: String): String {
    if (q.isBlank()) return "কী সার্চ করবো বুঝলাম না"
    val key = Store.apiKey(ctx)
    RiyaState.status.value = "সার্চ করছি বস…"
    val body =
      JSONObject()
        .put(
          "contents",
          JSONArray()
            .put(
              JSONObject()
                .put(
                  "parts",
                  JSONArray()
                    .put(
                      JSONObject()
                        .put(
                          "text",
                          "$q\n\n(গুগল সার্চ করে সঠিক ও সংক্ষিপ্ত তথ্য পরিষ্কার বাংলায় দাও)",
                        )
                    ),
                )
            ),
        )
        .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
    val http = client.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
    var err = ""
    if (key.isNotBlank()) {
      for (m in listOf("gemini-flash-latest", "gemini-2.5-flash")) {
        try {
          val req =
            Request.Builder()
              .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key")
              .post(body.toString().toRequestBody("application/json".toMediaType()))
              .build()
          http.newCall(req).execute().use { r ->
            val t = r.body?.string() ?: ""
            if (r.isSuccessful) {
              val parts =
                JSONObject(t)
                  .getJSONArray("candidates")
                  .getJSONObject(0)
                  .getJSONObject("content")
                  .getJSONArray("parts")
              val sb = StringBuilder()
              for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
              if (sb.isNotBlank()) return sb.toString().take(3000)
            } else {
              err =
                "HTTP " +
                  r.code +
                  " " +
                  (try {
                    JSONObject(t).getJSONObject("error").optString("message").take(80)
                  } catch (_: Exception) {
                    ""
                  })
            }
          }
        } catch (e: Exception) {
          err = e.message ?: "error"
        }
      }
    }
    val d = ddgSearch(q)
    if (d.isNotBlank()) {
      return "ওয়েব সার্চের ফলাফল। শুধু এখান থেকে যা নিশ্চিত তাই বাংলায় সংক্ষেপে বসকে জানাও:\n" + d
    }
    RiyaState.addLog("সার্চ ব্যর্থ: $err")
    return "সার্চ করা গেল না ($err)"
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
    try {
      ctx.startActivity(i)
    } catch (_: Exception) {
      ctx.startActivity(
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        .setBufferSizeInBytes(maxOf(minAudioBuf, 5120))
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
            maxOf(minBuf, 6400),
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
        maxOf(minBuf, 6400),
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
    // Only apply platform NoiseSuppressor & AGC on Bluetooth SCO; MediaTek built-in Phone Mic DSP/AGC pumps fan noise during silence!
    if (usingBtAudio && NoiseSuppressor.isAvailable()) {
      try {
        NoiseSuppressor.create(r.audioSessionId)?.enabled = true
      } catch (_: Throwable) {}
    }
    if (usingBtAudio && AutomaticGainControl.isAvailable()) {
      try {
        AutomaticGainControl.create(r.audioSessionId)?.enabled = true
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
          val preRollRing = ArrayDeque<ByteArray>(2)
          val bargeVerifyStream = java.io.ByteArrayOutputStream()

          // Voice Match 3-step speaker gate state: 0=IDLE, 1=COLLECTING_ONSET, 2=ALLOWED_BOSS, 3=REJECTED_OTHER
          var gateState = 0
          val onsetChunks = ArrayList<ByteArray>(16)
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

          fun sendAudioPcm(chunk: ByteArray, len: Int) {
            val d = Base64.encodeToString(chunk, 0, len, Base64.NO_WRAP)
            ws?.send("""{"realtimeInput":{"audio":{"data":"$d","mimeType":"audio/pcm;rate=16000"}}}""")
          }

          fun sendAudioStreamEndFlush() {
            ws?.send("""{"realtimeInput":{"audioStreamEnd":true}}""")
          }

          while (!stopped) {
            val activeRec = synchronized(recLock) { rec } ?: break
            val n =
              try {
                if (activeRec.state == AudioRecord.STATE_INITIALIZED) activeRec.read(buf, 0, buf.size)
                else -1
              } catch (_: Throwable) {
                -1
              }
            if (n < 0) break
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

              val isSpeakerAudiblyPlayingNow =
                synchronized(trackLock) {
                  val activeTrack = track
                  val head = (activeTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL
                  val recentlyWrote = lastSpeakerWriteMs > 0L && (now - lastSpeakerWriteMs) <= 450L
                  activeTrack != null &&
                    activeTrack.state == AudioTrack.STATE_INITIALIZED &&
                    activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    recentlyWrote &&
                    head < lastAudibleEndFrame
                }
              val ttsSpeakingNow = RiyaVoiceManager.isActivelySpeaking()
              if (isSpeakerAudiblyPlayingNow || ttsSpeakingNow) {
                lastAudibleStopMs = now
              } else if (rawRms < 6000.0) {
                lastSpeakerOutputRms *= 0.85
              }

              if ((isSpeakerAudiblyPlayingNow || ttsSpeakingNow) && rawRms >= maxOf(8500.0, lastSpeakerOutputRms * 2.1)) {
                consecutiveLoudMicChunks++
              } else {
                consecutiveLoudMicChunks = 0
              }

              // Multi-chunk Voice Match barge-in check (~80ms+) so Boss can interrupt A-to-Z immediately
              var voiceMatchBargeInNow = false
              if ((isSpeakerAudiblyPlayingNow || ttsSpeakingNow) && voiceMatchActive && vmProfile != null && vmProfile.isComplete && rawRms >= 550.0) {
                bargeVerifyStream.write(buf, 0, n)
                if (bargeVerifyStream.size() >= 2560) {
                  val res = VoiceMatchEngine.verifyPcmChunk(vmProfile, bargeVerifyStream.toByteArray(), vmSensitivity)
                  if (res?.matched == true) {
                    voiceMatchBargeInNow = true
                    bargeVerifyStream.reset()
                  } else if (bargeVerifyStream.size() >= 7680) {
                    bargeVerifyStream.reset()
                  }
                }
              } else {
                if (bargeVerifyStream.size() > 0) bargeVerifyStream.reset()
              }

              val isSoftMuted =
                AudioEchoGuard.shouldMuteMicWithBargeIn(
                  usingBtAudio = usingBtAudio,
                  nowMs = now,
                  micMuteUntilMs = micMuteUntilMs,
                  isSpeakerAudiblyPlayingNow = isSpeakerAudiblyPlayingNow,
                  ttsIsSpeaking = ttsSpeakingNow,
                  lastAudibleStopMs = lastAudibleStopMs,
                  rawRms = rawRms,
                  lastSpeakerOutputRms = lastSpeakerOutputRms,
                  voiceMatchBargeIn = voiceMatchBargeInNow,
                  consecutiveLoudChunks = consecutiveLoudMicChunks,
                )

              if (!isSoftMuted && isSpeakerAudiblyPlayingNow && !ttsSpeakingNow) {
                // Boss actively interrupted LXR mid-speech! Flush playback immediately so LXR stops talking and listens.
                interruptSpeakerForBoss()
              }

              // Capture raw voice frame BEFORE DSP filtering so VoiceMatch compares natural mic timbre
              val vmSampleChunk = if (voiceMatchActive && !isSoftMuted) buf.copyOf(n) else null

              // Apply real-time software DSP Noise Cancellation on BOTH Bluetooth and Phone Mic!
              val dspResult =
                if (isSoftMuted) {
                  buf.fill(0, 0, n)
                  lastVoiceMs = 0L
                  bossActiveSpeechThisTurn = false
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
              } else if (!isSoftMuted && !bossActiveSpeechThisTurn) {
                if (preRollRing.size >= 2) preRollRing.removeFirst()
                preRollRing.addLast(buf.copyOf(n))
              }

              val withinHangover = !isSoftMuted && lastVoiceMs > 0L && (now - lastVoiceMs) <= LiveVadConfig.MIC_HANGOVER_MS
              val withinEndOfTurnSilence =
                !isSoftMuted && bossActiveSpeechThisTurn && !withinHangover && lastVoiceMs > 0L && (now - lastVoiceMs) <= LiveVadConfig.END_OF_TURN_FLUSH_MS
              silentFrameCounter = if (isSoftMuted || !withinHangover) (silentFrameCounter + 1) else 0

              // When Boss stops speaking and silence reaches END_OF_TURN_FLUSH_MS (380ms) -> flush stream immediately so Gemini replies within ~1s!
              val justEndedTurn =
                !isSoftMuted && bossActiveSpeechThisTurn && lastVoiceMs > 0L && (now - lastVoiceMs) > LiveVadConfig.END_OF_TURN_FLUSH_MS

              val activeProfile = vmProfile
              if (!voiceMatchActive || activeProfile == null || !activeProfile.isComplete) {
                if (justEndedTurn) {
                  sendAudioStreamEndFlush()
                  bossActiveSpeechThisTurn = false
                  waitingServerReply = true
                  lastBossTurnAtMs = now
                  silentFrameCounter = 1
                } else if (withinHangover) {
                  if (isTurnOnset && preRollRing.isNotEmpty()) {
                    for (pre in preRollRing) sendAudioPcm(pre, pre.size)
                    preRollRing.clear()
                  }
                  sendAudioPcm(buf, n)
                } else if (withinEndOfTurnSilence) {
                  // Stream 100% continuous silence frames during VAD window (never skip frames) so server clock advances at 1.0x
                  sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                } else if (silentFrameCounter % 50 == 0) {
                  // Minimal 2s idle keepalive so WebSocket queue stays 0 bytes
                  sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                }
              } else {
                if (justEndedTurn) {
                  sendAudioStreamEndFlush()
                  bossActiveSpeechThisTurn = false
                  waitingServerReply = true
                  lastBossTurnAtMs = now
                  silentFrameCounter = 1
                } else if (isSoftMuted || !withinHangover) {
                  if (gateState == 1 && !isSoftMuted && onsetChunks.isNotEmpty() && verifyStream.size() >= 1600) {
                    val quickRes = VoiceMatchEngine.verifyPcmChunk(activeProfile, verifyStream.toByteArray(), vmSensitivity)
                    if (quickRes != null && quickRes.matched) {
                      lastBossVerifiedAtMs = now
                      for (pendingChunk in onsetChunks) {
                        sendAudioPcm(pendingChunk, pendingChunk.size)
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
                  if (withinEndOfTurnSilence) {
                    sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                  } else if (silentFrameCounter % 50 == 0) {
                    sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                  }
                } else {
                  val sendChunk = buf.copyOf(n)
                  val minOnset = vmSensitivity.onsetMinChunks
                  val rejectAt = vmSensitivity.rejectAfterChunks
                  // Boss session grace window (~4.5s): follow-up words pass immediately unless another speaker takes over
                  if (gateState == 0 && lastBossVerifiedAtMs > 0L && (now - lastBossVerifiedAtMs) <= 4500L) {
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
                            sendAudioPcm(pendingChunk, pendingChunk.size)
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
                        } else if (onsetChunks.size >= 16) {
                          gateState = 0
                          onsetChunks.clear()
                          verifyStream.reset()
                        }
                      } else if (onsetChunks.size >= 16) {
                        gateState = 0
                        onsetChunks.clear()
                        verifyStream.reset()
                      }
                    }
                    2 -> {
                      lastBossVerifiedAtMs = now
                      sendAudioPcm(sendChunk, sendChunk.size)
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
                            sendAudioPcm(buffered, buffered.size)
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
    RiyaState.addLog("বস: $t")
    Store.remember(ctx, "Boss(text): $t")
    try {
      ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
    } catch (_: Throwable) {}
  }

  fun notifyCameraActive(active: Boolean) {
    if (!ready || stopped) return
    val hint =
      if (active)
        "[সিস্টেম ভিশন ক্যামেরা চালু]: বসের ফোনের লাইভ ব্যাক ক্যামেরা এখন চালু হয়েছে! এখন যে image/jpeg ফ্রেমগুলো আসছে সেগুলো সরাসরি বসের ক্যামেরা থেকে লাইভ দৃশ্য। বস যা দেখাতে চাচ্ছেন বা জিজ্ঞেস করছেন, সরাসরি ক্যামেরার ভিডিও ছবি দেখে পরিষ্কার বাংলায় বর্ণনা করো।"
      else
        "[সিস্টেম ভিশন ক্যামেরা বন্ধ]: ক্যামেরা বন্ধ করা হয়েছে, এখন স্ক্রিন ওয়াচ মোডে ফিরে যাও।"
    try {
      ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", hint)).toString())
    } catch (_: Throwable) {}
  }

  fun sendCameraFrame(jpeg: ByteArray) {
    if (!ready || stopped) return
    if ((ws?.queueSize() ?: 0) > 220_000) return
    try {
      val d = Base64.encodeToString(jpeg, Base64.NO_WRAP)
      ws?.send("""{"realtimeInput":{"video":{"data":"$d","mimeType":"image/jpeg"}}}""")
    } catch (_: Throwable) {}
  }

  fun sendImage(jpeg: ByteArray) {
    if (!ready || stopped) return
    if ((ws?.queueSize() ?: 0L) > 12_000L) return
    try {
      val d = Base64.encodeToString(jpeg, Base64.NO_WRAP)
      ws?.send("""{"realtimeInput":{"video":{"data":"$d","mimeType":"image/jpeg"}}}""")
    } catch (_: Throwable) {}
  }
}
