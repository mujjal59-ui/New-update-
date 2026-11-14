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
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Base64
import com.example.MainActivity
import com.example.ai.RiyaPersona
import com.example.data.RiyaState
import com.example.data.Store
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlin.math.tanh
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
)

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

  fun isReady(): Boolean = ready && !stopped

  private fun toolsJson() =
    (if (searchOn) """[{"googleSearch":{}},""" else "[") +
      """{"functionDeclarations":[
      {"name":"open_app","description":"ফোনে অ্যাপ খুঁজতে বা ওপেন করতে এটি ব্যবহার করো। এটি বাধ্যতামূলকভাবে আগে হোম স্ক্রিনে গিয়ে অ্যাপ খুঁজে ওপেন করে।","parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING"}},"required":["app_name"]}},
      {"name":"send_message","description":"যেকোনো মেসেজিং অ্যাপে (WhatsApp, imo, Messenger) নামের চ্যাট খুলে মেসেজ পাঠাও, অথবা Gemini/ChatGPT-তে লেখো। বস যে অ্যাপের নাম বলেছেন হুবহু সেটি দাও (imo বললে imo, WhatsApp বললে WhatsApp)। এই টুল নিজেই অ্যাপ খোলে, নাম খোঁজে, মেসেজ লেখে এবং ইমোজি বাদ দিয়ে সঠিক সেন্ড বাটনে ক্লিক করে।","parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING","enum":["WhatsApp","imo","Messenger","Gemini","ChatGPT"]},"contact_name":{"type":"STRING","description":"চ্যাট বা প্রেরকের নাম (Gemini/ChatGPT এর জন্য ফাঁকা)"},"message":{"type":"STRING","description":"কী মেসেজ লিখবে"}},"required":["app_name","message"]}},
      {"name":"find_and_open_item","description":"স্ক্রিনে বা অ্যাপে যেকোনো আইটেম নাম দিয়ে খুঁজে ট্যাপ করো, না পেলে Infinix Android 16 সার্চ বার দিয়ে সার্চ করো।","parameters":{"type":"OBJECT","properties":{"name":{"type":"STRING"}},"required":["name"]}},
      {"name":"current_screen","description":"বস এখন কোন অ্যাপ বা স্ক্রিনে আছেন তা জানো"},
      {"name":"read_screen","description":"এখন সম্পূর্ণ স্ক্রিনে কী কী লেখা, বাটন, সুইচ ও লেখার বক্স আছে তা পড়ো"},
      {"name":"click_text","description":"স্ক্রিনের এই টেক্সট/বাটনে ট্যাপ করো","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING"}},"required":["text"]}},
      {"name":"type_text","description":"ফোকাসড লেখার বক্সে টাইপ করো","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING"}},"required":["text"]}},
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

  private fun isSlowNetwork(): Boolean {
    return try {
      val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      val net = cm.activeNetwork ?: return true
      val caps = cm.getNetworkCapabilities(net) ?: return true
      val down = caps.linkDownstreamBandwidthKbps
      val up = caps.linkUpstreamBandwidthKbps
      val slow = (down in 1..1500) || (up in 1..400)
      RiyaState.networkSpeedLabel.value = if (slow) "Low-Net Turbo" else "Fast"
      slow
    } catch (_: Exception) {
      false
    }
  }

  private var lastShot = 0
  private val screenLoop =
    object : Runnable {
      override fun run() {
        if (stopped) return
        try {
          watchScreen()
        } catch (_: Exception) {}
        val intervalMs = if (Store.lowNetFastMode(ctx) && isSlowNetwork()) 5000L else 3000L
        main.postDelayed(this, intervalMs)
      }
    }

  @Volatile private var isExecutingTool = false
  @Volatile private var lastUserScreenAt = 0L

  fun onIncomingMessageReceived(
    msg: PendingIncomingMessage,
    autoSentReply: String? = null,
  ): Boolean {
    if (!ready || stopped) return false
    val prompt =
      if (!autoSentReply.isNullOrBlank()) {
        "নতুন মেসেজ এসেছে: ${msg.appNameBn}-এ ${msg.senderName} লিখেছেন: \"${msg.messageText}\"। আমি ইতিমধ্যেই উত্তর পাঠিয়ে দিয়েছি: \"$autoSentReply\"। এখনই বসকে শান্ত, সংক্ষিপ্ত ও স্পষ্ট বাংলায় জানিয়ে দাও।"
      } else {
        "নতুন মেসেজ এসেছে: ${msg.appNameBn}-এ ${msg.senderName} মেসেজ পাঠিয়েছেন: \"${msg.messageText}\"। এখনই বসকে শান্ত, স্পষ্ট ও বিনয়ী বাংলায় জানাও এবং জিজ্ঞেস করো: বস, ${msg.appNameBn}-এ ${msg.senderName} মেসেজ পাঠিয়েছেন: '${msg.messageText}'। আমি কি রিপ্লাইটা দিয়ে দেবো?"
      }
    val fallbackText =
      if (!autoSentReply.isNullOrBlank()) {
        "বস, ${msg.appNameBn}-এ ${msg.senderName} মেসেজ পাঠিয়েছেন: ${msg.messageText}। আমি অটো রিপ্লাই দিয়ে দিয়েছি।"
      } else {
        "বস, ${msg.appNameBn}-এ ${msg.senderName} মেসেজ পাঠিয়েছেন: ${msg.messageText}। আমি কি উত্তর পাঠাবো? বলুন বস।"
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
          2400L,
        )
      }
      sent
    } catch (_: Throwable) {
      false
    }
  }

  private fun isAiSpeakingRecently(): Boolean =
    (System.currentTimeMillis() - lastSpeakerWriteMs) < 1000L

  private fun sendScreenNow() {
    if (!ready || !Store.screenWatch(ctx) || RiyaState.cameraOn || isAiSpeakingRecently()) return
    val acc = RiyaAccessibilityService.instance ?: return
    if (acc.rootInActiveWindow?.packageName?.toString() == ctx.packageName) return
    val lowNet = Store.lowNetFastMode(ctx) && isSlowNetwork()
    acc.screenshotJpeg(maxDim = if (lowNet) 480 else 720, quality = if (lowNet) 38 else 55) { jpg ->
      if (jpg != null) {
        lastShot = jpg.contentHashCode()
        sendImage(jpg)
      }
    }
  }

  private fun watchScreen() {
    if (!ready || !Store.screenWatch(ctx) || RiyaState.cameraOn || isExecutingTool || isAiSpeakingRecently()) return
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    if (!pm.isInteractive) return
    val acc = RiyaAccessibilityService.instance ?: return
    if (acc.rootInActiveWindow?.packageName?.toString() == ctx.packageName) return
    val lowNet = Store.lowNetFastMode(ctx) && isSlowNetwork()
    acc.screenshotJpeg(maxDim = if (lowNet) 480 else 720, quality = if (lowNet) 38 else 55) { jpg ->
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
    stopped = false
    routeAudio(allowRestart = false)
    am.registerAudioDeviceCallback(devCb, main)
    initTrack()
    startMic()
    connect()
    main.post(screenLoop)
  }

  fun stop() {
    stopped = true
    ready = false
    try {
      main.removeCallbacksAndMessages(null)
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
      if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
      else {
        @Suppress("DEPRECATION") am.stopBluetoothSco()
      }
    } catch (_: Throwable) {}
    try {
      am.mode = AudioManager.MODE_NORMAL
      @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
    } catch (_: Throwable) {}
    LxrFloatingOverlayManager.setSpeakingBoost(false)
    RiyaState.voiceMatchLiveLabel.value = ""
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
    val screenContext = RiyaAccessibilityService.currentScreenSummary.ifBlank { "বস হোম স্ক্রিনে আছেন।" }
    val accStatus =
      if (Keep.accessibilityOn(ctx)) "ACTIVE (চালু আছে)"
      else "OFF (বন্ধ আছে — কোনো কাজ শুরু করতে না পারলে বসকে সাথে সাথে জানাতে হবে)"

    val sys =
      """
      [IDENTITY & BRANDING — STRICT]
      Your ONLY name is LXR--AI (এল এক্স আর এ আই). Never use any other name (never Riya or Priya).
      Always address the user politely as "বস" and use "তুমি". Never use "তুই" or "আপনি".
      Creator Rule (MANDATORY): Whenever Boss asks "তোমাকে কে তৈরি করেছে", "তোমাকে কে বানিয়েছে", "Who made/created you", ALWAYS reply in Bengali:
      "আমাকে বানিয়েছে মোঃ উজ্জল হোসেন, আমার বস, আমি উনার পার্সোনাল অ্যাসিস্ট্যান্ট। উনি একজন ভালো মনের মানুষ, আমাকে প্রতিনিয়ত নতুনভাবে আপডেট করার বিষয়ে কঠোর পরিশ্রম করেন।"

      [PHONE MODEL & SYSTEM STATUS]
      - Device: ${InfinixDevicePlaybook.DEVICE_PROFILE}
      - Live Accessibility Now: $accStatus
      - Screen Watch Status: ACTIVE (সবসময় বাধ্যতামূলক)
      - Live Vision Camera Status: ${if (RiyaState.cameraOn) "ON (লাইভ ব্যাক ক্যামেরা ভিডিও আসছে)" else "OFF"}
      - Boss Voice Match Lock: ${if (VoiceMatchManager.isActiveGateReady(ctx)) "ACTIVE (শুধু ৩ ধাপে সনাক্ত বসের কণ্ঠের কমান্ড ফলো করো, আশেপাশের অন্য মানুষের কথা সম্পূর্ণ এভোয়েড করো)" else "OFF (ভয়েস ম্যাচ বন্ধ — সব ভয়েস কমান্ড স্বাভাবিকভাবে সম্পন্ন করবে)"}

      [WORK RULES — কঠোর, সব কাজে বাধ্যতামূলক]
      1. সম্পূর্ণ স্ক্রিন পড়া বাধ্যতামূলক: যেকোনো স্ক্রিন-সম্পর্কিত কাজের আগে read_screen দিয়ে পুরো স্ক্রিন পড়বে এবং প্রতিটি ধাপের পর আবার read_screen পড়ে ফল যাচাই করবে। (তবে লাইভ ক্যামেরা চালু থাকলে এবং বস সামনে কী দেখা যাচ্ছে জিজ্ঞেস করলে read_screen না চালিয়ে সরাসরি ক্যামেরার ভিডিও ছবি দেখে বলবে।)
      2. দ্রুতগতিতে কাজ সম্পন্ন: কাজ শুরুর পর দেরি না করে দ্রুত টুল চালিয়ে কয়েক সেকেন্ডের মধ্যেই কাজ শেষ করবে।
      3. হার্ডওয়্যার টগল নিয়ম: বস ফ্ল্যাশলাইট (flashlight), ওয়াইফাই (Wi-Fi) বা ব্লুটুথ (Bluetooth) চালু/বন্ধ করতে বললে নিজে Settings না খুলে সরাসরি `toggle_flashlight`, `control_wifi`, বা `control_bluetooth` টুল কল করবে।
      4. বসকে কাজের মাঝখানে প্রশ্ন নয়: কোন বাটন, কোন বক্স, সার্চ করবো কিনা বা কোথায় সার্চ বার—এসব কখনোই বসকে জিজ্ঞেস করবে না বা অনুমতি নেবে না। নিজে স্ক্রিন দেখে, খুঁজতে হলে নিজেই সার্চ করে কাজ শেষ করে তারপর ফলাফল জানাবে।
      5. মিথ্যা বা অনুমান করে Accessibility বন্ধ বলবে না: কেবল কোনো টুল (`send_message`, `read_screen`, `click_text`) চালালে সেটি যদি স্পষ্টভাবে "Accessibility সার্ভিস বন্ধ আছে" ফেরত দেয়, কেবল তখনই বসকে জানাবে। কখনো পুরনো স্ট্যাটাস ধরে বসে থাকবে না—সরাসরি টুল চালাবে।
      6. কাজ সফল হলে তবেই স্পষ্টভাবে বলবে: "হয়েছে, বস! কাজ শেষ।" এবং এরপর বসকে আরেকটি কাজ করার জন্য সুন্দরভাবে অনুপ্রাণিত করবে।

      [VOICE CLARITY, GREETING & NO DUPLICATES — বাধ্যতামূলক]
      - কণ্ঠ হবে অত্যন্ত স্পষ্ট, পরিষ্কার উচ্চারণযুক্ত, নম্র, মধুর, স্বাগতম জানানো এবং স্বাভাবিক বাংলা (বাংলাদেশ)।
      - কখনোই এক কথার দুইবার বা ডাবল উত্তর দেবে না; কোনো প্রশ্ন/নির্দেশের উত্তর একবারেই পরিষ্কারভাবে শেষ করবে।
      - কখনোই এলোমেলো কথা, অতিরিক্ত হাসি, অস্পষ্ট ফিসফিসানি বা ইমোজি/তারকাচিহ্ন বলবে না।

      [INFINIX HOT 60 PRO+ (ANDROID 16) MESSAGING & SEARCH RULES — CRITICAL]
      - আগে থেকে খোলা চ্যাট (WhatsApp/imo/Messenger): বস যখন একই ব্যক্তিকে আবার মেসেজ পাঠাতে বলেন বা মেসেজিং অ্যাপ ব্যাকগ্রাউন্ডে ওই চ্যাটে মিনিমাইজ থাকে, `send_message` নিজেই ওই খোলা চ্যাটে লিখে সেন্ড করে বা অন্য চ্যাটে থাকলে Back গিয়ে মূল তালিকায় আসে। তুমি কোনো সন্দেহ না করে সরাসরি `send_message` টুল কল করবে!
      - টপ তালিকা প্রথম + স্বয়ংক্রিয় সার্চ: WhatsApp/imo/Messenger খুললে প্রথমে স্ক্রিনের উপরের চ্যাট তালিকায় নাম খুঁজবে; ওখানে না পেলে বসের কোনো অনুমতি ছাড়াই নিজে নিজে সার্চ বারে/সার্চ আইকনে সার্চ করে নিচের রেজাল্ট সারিতে ক্লিক করবে।
      - রেজাল্ট ক্লিক: সার্চ বক্সের ঠিক নিচে আসা নামের সারিতে ক্লিক করবে (কখনোই উপরের সার্চ বক্স বা বামদিকের প্রোফাইল ছবিতে ক্লিক করবে না)।
      - imo (ইমো) সেন্ড নিয়ম: চ্যাটে লেখার বক্সের উপরের রিঅ্যাকশন ইমোজি বা পাশের ইমোজি বাটনে নয়, সবসময় `send_message` টুল দিয়ে ডানের নীল Send বাটনে ক্লিক করবে।
      - সঠিক অ্যাপ ওপেন ও ১০১% স্ক্রিন রিড: `open_app` টুলে বস যে অ্যাপটির নাম বলেছেন ঠিক সেটিই দেবে (WhatsApp/imo/Messenger অদলবদল করবে না)। টুলটির ফলাফলে নতুন খোলা স্ক্রিনের সম্পূর্ণ তথ্য থাকবে—সেটি ১০১% পড়ে নিয়ে তবেই বসকে নিশ্চিত জানাবে যে অ্যাপটি ওপেন হয়েছে এবং স্ক্রিনে কী কী আছে।

      [AUTO-REPLY PROTOCOL (WhatsApp, imo, Messenger)]
      - নতুন মেসেজের নোটিফিকেশন এলে সাথে সাথে স্পষ্ট বাংলায় বসকে জানাবে: কোন অ্যাপে, কে, কী মেসেজ পাঠিয়েছে—এবং রিপ্লাই দেওয়ার জন্য বসের অনুমতি চাইবে।
      - বস উত্তর দিতে বললে বা হ্যাঁ বললে `reply_to_incoming_message` টুল চালিয়ে সাথে সাথে রিপ্লাই পাঠিয়ে দেবে।

      [PERSONALITY]
      ${RiyaPersona.TEXT}

      [RUNTIME INFO]
      - Time: $now
      - Current Screen: [$screenContext]
      স্মৃতি (Memory):
      ${Store.recent(ctx, 12000)}
      """
        .trimIndent()

    val voiceName = Store.voice(ctx).ifBlank { "Aoede" }
    val s = JSONObject()
    s.put("model", "models/" + (modelOverride ?: Store.model(ctx)))

    val genConfig = JSONObject()
    genConfig.put("responseModalities", JSONArray().put("AUDIO"))
    val speechConfig =
      JSONObject()
        .put(
          "voiceConfig",
          JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName)),
        )
    genConfig.put("speechConfig", speechConfig)
    s.put("generationConfig", genConfig)

    s.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
    s.put("tools", JSONArray(toolsJson()))
    return JSONObject().put("setup", s)
  }

  private fun handle(msg: String) {
    try {
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
          synchronized(trackLock) {
            val t = track
            if (!stopped && t != null && t.state == AudioTrack.STATE_INITIALIZED) {
              try {
                t.pause()
                t.flush()
                t.play()
              } catch (_: Throwable) {}
            }
          }
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.let {
          uText.append(it)
          if (
            LxrFloatingOverlayManager.containsWakeWord(it) ||
              LxrFloatingOverlayManager.containsWakeWord(uText.toString().takeLast(64))
          ) {
            LxrFloatingOverlayManager.show(ctx)
          }
          val now = System.currentTimeMillis()
          if (now - lastUserScreenAt > 1800) {
            lastUserScreenAt = now
            main.post { sendScreenNow() }
          }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.let { pText.append(it) }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { ps ->
          for (i in 0 until ps.length()) {
            ps.getJSONObject(i).optJSONObject("inlineData")?.let {
              val pcm = Base64.decode(it.getString("data"), Base64.DEFAULT)
              if (!stopped) {
                RiyaState.status.value = "LXR--AI কথা বলছে..."
                LxrFloatingOverlayManager.setSpeakingBoost(true)
                try {
                  if (!playExec.isShutdown) {
                    playExec.execute {
                      if (stopped) return@execute
                      synchronized(trackLock) {
                        val t = track
                        if (!stopped && t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                          try {
                            lastSpeakerWriteMs = System.currentTimeMillis()
                            t.write(pcm, 0, pcm.size)
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
        if (sc.optBoolean("turnComplete")) {
          LxrFloatingOverlayManager.setSpeakingBoost(false)
          val u = uText.toString().trim()
          val p = pText.toString().trim()
          if (u.isNotEmpty()) {
            if (LxrFloatingOverlayManager.containsWakeWord(u)) {
              LxrFloatingOverlayManager.show(ctx)
            }
            Store.remember(ctx, "Boss: $u")
            RiyaState.addLog("বস: $u")
            main.post { sendScreenNow() }
          }
          if (p.isNotEmpty()) {
            Store.remember(ctx, "LXR--AI: $p")
            RiyaState.addLog("LXR--AI: $p")
          }
          uText.setLength(0)
          pText.setLength(0)
          RiyaState.status.value = "শুনছি বস..."
        }
      }
      j.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { fc ->
        toolExec.execute {
          try {
            isExecutingTool = true
            val out = JSONArray()
            for (i in 0 until fc.length()) {
              val c = fc.getJSONObject(i)
              val nm = c.getString("name")
              val r = runTool(nm, c.optJSONObject("args") ?: JSONObject())
              out.put(
                JSONObject()
                  .put("id", c.getString("id"))
                  .put("name", c.getString("name"))
                  .put("response", JSONObject().put("result", r))
              )
            }
            ws?.send(
              JSONObject().put("toolResponse", JSONObject().put("functionResponses", out)).toString()
            )
            isExecutingTool = false
          } catch (_: Exception) {
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
    RiyaAccessibilityService.currentScreenSummary =
      "বর্তমান অ্যাপ: ${RiyaAccessibilityService.currentApp} | স্ক্রিন দৃশ্য: " +
        s.replace('\n', ',').take(300)
    return s.take(2500)
  }

  private fun runTool(name: String, a: JSONObject): String {
    val acc = RiyaAccessibilityService.instance
    val watch = Store.screenWatch(ctx) && acc != null && Keep.accessibilityOn(ctx)
    if (!watch || name !in actionTools) {
      val r = runToolRaw(name, a)
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
    val r = runToolRaw(name, a)
    Thread.sleep(if (name == "open_app" || name == "play_youtube") 450L else 280L)
    var after = screenSnapshot()
    if (name in needsFind && (r.contains("খুঁজে পাওয়া যায়নি") || r.contains("সার্চ বাটন চাপতে পারিনি"))) {
      val r2 = runToolRaw(name, a)
      Thread.sleep(280)
      after = screenSnapshot()
      return "$r2\n[আগের স্ক্রিন]:\n${before.take(800)}\n[এখনকার স্ক্রিন]:\n$after\n(বসকে কিছু জিজ্ঞেস না করে নিজেই পরের ধাপ দ্রুত শেষ করো এবং শেষে 'হয়েছে, বস! কাজ শেষ।' জানিয়ে পরের কাজের জন্য উৎসাহ দাও)"
    }
    return "$r\n[এখনকার স্ক্রিন]:\n$after\n(স্ক্রিন দেখে কাজ হয়েছে কিনা যাচাই করো; কাজ শেষ হলে 'হয়েছে, বস! কাজ শেষ।' বলে পরের কাজের জন্য উৎসাহ দাও)"
  }

  private fun runToolRaw(name: String, a: JSONObject): String =
    try {
      val acc = if (Keep.accessibilityOn(ctx)) RiyaAccessibilityService.instance else null
      val noAcc = "Accessibility সার্ভিস বন্ধ আছে বস! দয়া করে LXR--AI Accessibility চালু করে দিন।"
      when (name) {
        "open_app" -> openApp(a.optString("app_name"))
        "send_message" -> messageSend(a)
        "find_and_open_item" -> acc?.clickText(a.optString("name")) ?: noAcc
        "current_screen" ->
          if (RiyaState.cameraOn) {
            "লাইভ ব্যাক ক্যামেরা এখন চালু আছে। ইনকামিং ভিডিও স্ট্রিম থেকে পাওয়া সর্বশেষ ক্যামেরা ফ্রেম দেখে সরাসরি উত্তর দাও।"
          } else {
            RiyaAccessibilityService.currentScreenSummary.ifBlank { "স্ক্রিনের তথ্য পাওয়া যায়নি" }
          }
        "read_screen" ->
          if (RiyaState.cameraOn) {
            "লাইভ ভিশন ক্যামেরা এখন চালু আছে! অ্যাপের UI নয়, সরাসরি ক্যামেরার ইনকামিং ভিডিও ফ্রেম দেখে বস যা দেখাচ্ছেন তা বলো।"
          } else {
            acc?.readScreen() ?: noAcc
          }
        "click_text" -> acc?.clickText(a.optString("text")) ?: noAcc
        "type_text" -> acc?.typeText(a.optString("text")) ?: noAcc
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
      main.postDelayed(
        {
          if (!stopped && lastSpeakerWriteMs == prevWriteMs) {
            RiyaVoiceManager.speakUrgent(ctx, greeting)
          }
        },
        if (ok) 2600L else 200L,
      )
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
          val curPkg = acc?.rootInActiveWindow?.packageName?.toString().orEmpty()
          if (curPkg in targetPkgs || RiyaAccessibilityService.currentApp in targetPkgs) {
            Thread.sleep(360)
            break
          }
        }
      }
      val fullScreen = acc?.readScreen().orEmpty()
      val activePkg = acc?.rootInActiveWindow?.packageName?.toString().orEmpty()
      val verifiedOpen =
        targetPkgs.isEmpty() || activePkg in targetPkgs || RiyaAccessibilityService.currentApp in targetPkgs
      RiyaState.markTaskCompleted(
        "হয়েছে, বস! কাজ শেষ — $targetLabel অ্যাপ খোলা হয়েছে।",
        "আর কোন কাজ করতে হবে বস? সাথেই আছি!",
      )
      return buildString {
        append(
          if (verifiedOpen) "হয়েছে, বস! কাজ শেষ — $targetLabel ($activePkg) সঠিক অ্যাপটি ওপেন হয়েছে এবং সম্পূর্ণ স্ক্রিন পড়া হয়েছে।"
          else "হয়েছে, বস! $targetLabel ওপেন করা হয়েছে (বর্তমান উইন্ডো: $activePkg)।"
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
          "হয়েছে, বস! কাজ শেষ — $query অ্যাপ খোলা হয়েছে।",
          "দারুণ! পরের কাজ কী হবে বস?",
        )
        return "হয়েছে, বস! কাজ শেষ — হোম স্ক্রিন থেকে $query ওপেন করেছি!\n[সম্পূর্ণ স্ক্রিন ১০১% রিড]:\n${fullScreen.take(2200)}"
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
    return try {
      MessageAgent.run(ctx, acc, app, contact, msg)
    } finally {
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
      "হয়েছে, বস! কাজ শেষ — $foundName ($num) কে কল লাগানো হয়েছে।",
      "কল শেষ হলে অন্য কোন কাজ করবো বস?",
    )
    return "হয়েছে, বস! কাজ শেষ — $foundName ($num) কে কল দিচ্ছি!"
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
        if (on) "হয়েছে, বস! কাজ শেষ — ফ্ল্যাশলাইট জ্বালিয়ে দিয়েছি।"
        else "হয়েছে, বস! কাজ শেষ — ফ্ল্যাশলাইট বন্ধ করে দিয়েছি।"
      RiyaState.markTaskCompleted(msg, "এরপর কোন কাজটা করবো বস?")
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
        RiyaState.markTaskCompleted("হয়েছে, বস! কাজ শেষ — ওয়াইফাই $word করেছি।", "এরপর কোন কাজটা করবো বস?")
        return "হয়েছে, বস! কাজ শেষ — ওয়াইফাই $word করেছি!"
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
          RiyaState.markTaskCompleted(
            "হয়েছে, বস! কাজ শেষ — ওয়াইফাই $word করা হয়েছে।",
            "দারুণ! পরের কাজ কী হবে বস?",
          )
          return "হয়েছে, বস! কাজ শেষ — ওয়াইফাই $word করে দিয়েছি!"
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
        RiyaState.markTaskCompleted(
          "হয়েছে, বস! কাজ শেষ — ওয়াইফাই $word করা হয়েছে।",
          "দারুণ! পরের কাজ কী হবে বস?",
        )
        return "হয়েছে, বস! কাজ শেষ — ওয়াইফাই $word করে দিয়েছি!"
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
          RiyaState.markTaskCompleted("হয়েছে, বস! কাজ শেষ — ব্লুটুথ $word করা হয়েছে।", "এরপর কোন কাজটা করবো বস?")
          return "হয়েছে, বস! কাজ শেষ — ব্লুটুথ $word করেছি!"
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
          RiyaState.markTaskCompleted(
            "হয়েছে, বস! কাজ শেষ — ব্লুটুথ $word করা হয়েছে।",
            "দারুণ! পরের কাজ কী হবে বস?",
          )
          return "হয়েছে, বস! কাজ শেষ — ব্লুটুথ $word করে দিয়েছি!"
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
        RiyaState.markTaskCompleted(
          "হয়েছে, বস! কাজ শেষ — ব্লুটুথ $word করা হয়েছে।",
          "দারুণ! পরের কাজ কী হবে বস?",
        )
        return "হয়েছে, বস! কাজ শেষ — ব্লুটুথ $word করে দিয়েছি!"
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
        RiyaState.markTaskCompleted(
          "হয়েছে, বস! কাজ শেষ — $name $word করা হয়েছে।",
          "দারুণ! পরের কাজ কী হবে বস?",
        )
        "হয়েছে, বস! কাজ শেষ — $name $word করে দিয়েছি!"
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
    val pending =
      RiyaRuntimeBridge.pendingMessages.value.firstOrNull {
        it.status == IncomingReplyStatus.PENDING_PERMISSION
      }
    if (pending != null) {
      return "সর্বশেষ অপেক্ষমাণ মেসেজ: ${pending.appLabel}-এ ${pending.senderName} লিখেছেন \"${pending.messageText}\"।"
    }
    val acc = RiyaAccessibilityService.instance ?: return "Accessibility চালু নেই বস"
    acc.global("notifications")
    Thread.sleep(320)
    val notes = acc.readScreen()
    acc.global("back")
    return if (notes.isNotBlank() && !notes.startsWith("স্ক্রিনে পড়ার মতো")) {
      "নোটিফিকেশন বারে যা পাওয়া গেছে:\n$notes"
    } else {
      "এই মুহূর্তে কোনো নতুন মেসেজ বা নোটিফিকেশন নেই বস।"
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
    val ok =
      RiyaNotificationListenerService.sendApprovedReply(
        ctx,
        pending.eventKey,
        customReply ?: pending.suggestedReply,
      )
    return if (ok) {
      "হয়েছে, বস! কাজ শেষ — ${pending.appLabel}-এ ${pending.senderName}-কে উত্তর পাঠানো হয়েছে।"
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
    repeat(5) {
      if (acc.clickFirstVideo()) {
        RiyaState.markTaskCompleted(
          "হয়েছে, বস! কাজ শেষ — YouTube-এ '$q' চালু করা হয়েছে।",
          "উপভোগ করুন বস! এরপর কোন কাজ করবো?",
        )
        return "হয়েছে, বস! কাজ শেষ — '$q' বাজাচ্ছি!"
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
        .setBufferSizeInBytes(maxOf(minAudioBuf * 8, 32768))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also { it.play() }
    synchronized(trackLock) {
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
    // Only apply NoiseSuppressor on Bluetooth SCO; MediaTek NoiseSuppressor swallows quiet Phone Mic speech!
    if (usingBtAudio && NoiseSuppressor.isAvailable()) {
      try {
        NoiseSuppressor.create(r.audioSessionId)?.enabled = true
      } catch (_: Throwable) {}
    }
    if (AutomaticGainControl.isAvailable()) {
      try {
        AutomaticGainControl.create(r.audioSessionId)?.enabled = true
      } catch (_: Throwable) {}
    }
    synchronized(recLock) {
      rec = r
    }
    try {
      r.startRecording()
    } catch (_: Throwable) {}
    val t =
      Thread {
        try {
          val buf = ByteArray(1600)
          val zeroSilence = ByteArray(1600)
          var lastVoiceMs = 0L
          var silentFrameCounter = 0

          // Voice Match 3-step speaker gate state: 0=IDLE, 1=COLLECTING_ONSET, 2=ALLOWED_BOSS, 3=REJECTED_OTHER
          var gateState = 0
          val onsetChunks = ArrayList<ByteArray>(16)
          val verifyStream = java.io.ByteArrayOutputStream()
          var consecutiveRejects = 0
          var rejectedSpeechChunks = 0
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
            if (n > 0 && ready && !stopped && (ws?.queueSize() ?: 0) < 80_000) {
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
              val lowNet = Store.lowNetFastMode(ctx) && isSlowNetwork()

              // Copy clean pre-boost mic frame for speaker timbre verification
              val vmSampleChunk = if (voiceMatchActive) buf.copyOf(n) else null

              val speakerEchoWindow = !usingBtAudio && (now - lastSpeakerWriteMs) < 700L
              val btEchoWindow = usingBtAudio && (now - lastSpeakerWriteMs) < 350L
              val isSoftMuted =
                now < micMuteUntilMs ||
                  (speakerEchoWindow && rawRms < 3600.0) ||
                  (btEchoWindow && rawRms < 950.0)

              if (isSoftMuted) {
                // Zero out mic during speaker playback to eliminate mid-sentence self-interruption & double replies
                buf.fill(0, 0, n)
              } else if (whisperBoostOn && rawRms > 12.0) {
                applySoftVoiceBoost(buf, n, rawRms, isPhoneMic = !usingBtAudio)
              }

              val speechGate = if (whisperBoostOn) 14.0 else 55.0
              val hasSpeech = !isSoftMuted && rawRms >= speechGate
              if (hasSpeech) {
                lastVoiceMs = now
              }

              // Keep 1.4s hangover for smooth Bengali word endings without leaking trailing room speech
              val withinHangover = !isSoftMuted && (now - lastVoiceMs) <= 1400L
              silentFrameCounter = if (withinHangover) 0 else (silentFrameCounter + 1)
              val shouldSend = !lowNet || withinHangover || (silentFrameCounter % 4 == 0)

              val activeProfile = vmProfile
              if (!voiceMatchActive || activeProfile == null || !activeProfile.isComplete) {
                if (shouldSend) {
                  sendAudioPcm(buf, n)
                }
              } else {
                if (isSoftMuted || !withinHangover) {
                  if (gateState != 0) {
                    gateState = 0
                    onsetChunks.clear()
                    verifyStream.reset()
                    consecutiveRejects = 0
                    rejectedSpeechChunks = 0
                    RiyaState.voiceMatchLiveLabel.value = "🔒 শুধু বসের কণ্ঠ শুনছে"
                  }
                  // Strictly send silent zero frames when idle/muted so nearby friends' first syllables never leak to Gemini
                  if (silentFrameCounter % 4 == 0) {
                    sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                  }
                } else {
                  val sendChunk = buf.copyOf(n)
                  when (gateState) {
                    0, 1 -> {
                      gateState = 1
                      onsetChunks.add(sendChunk)
                      if (vmSampleChunk != null && hasSpeech) {
                        verifyStream.write(vmSampleChunk)
                      }
                      if (onsetChunks.size >= 7 && verifyStream.size() >= 3840) {
                        val res =
                          VoiceMatchEngine.verifyPcmChunk(
                            activeProfile,
                            verifyStream.toByteArray(),
                            vmSensitivity,
                          )
                        if (res != null && res.matched) {
                          gateState = 2
                          consecutiveRejects = 0
                          verifyStream.reset()
                          RiyaState.voiceMatchLiveLabel.value =
                            "🔓 বস ভয়েস মিলেছে (${res.confidencePercent}%)"
                          for (pendingChunk in onsetChunks) {
                            sendAudioPcm(pendingChunk, pendingChunk.size)
                          }
                          onsetChunks.clear()
                        } else if (res != null && !res.matched && onsetChunks.size >= 9) {
                          gateState = 3
                          rejectedSpeechChunks = 0
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
                            rejectedSpeechChunks = 0
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
                      // REJECTED_OTHER: block surrounding person's voice strictly (send zero silence only if needed)
                      if (silentFrameCounter % 4 == 0) {
                        sendAudioPcm(zeroSilence, n.coerceAtMost(zeroSilence.size))
                      }
                      if (vmSampleChunk != null && hasSpeech) {
                        verifyStream.write(vmSampleChunk)
                        rejectedSpeechChunks++
                      }
                      if (rejectedSpeechChunks >= 12) {
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
                          consecutiveRejects = 0
                          RiyaState.voiceMatchLiveLabel.value =
                            "🔓 বস ভয়েস মিলেছে (${res.confidencePercent}%)"
                          sendAudioPcm(sendChunk, sendChunk.size)
                        } else if (res != null) {
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

  private fun applySoftVoiceBoost(buf: ByteArray, len: Int, rms: Double, isPhoneMic: Boolean) {
    val mult = if (isPhoneMic) 1.35 else 1.0
    val gain =
      when {
        rms < 400.0 -> 3.8 * mult
        rms < 1200.0 -> 2.7 * mult
        rms < 2600.0 -> 1.7 * mult
        else -> 1.0
      }
    if (gain <= 1.02) return
    var i = 0
    while (i + 1 < len) {
      val raw = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
      val boosted = (tanh((raw * gain) / 32767.0) * 32767.0).toInt().coerceIn(-32768, 32767)
      buf[i] = (boosted and 0xFF).toByte()
      buf[i + 1] = ((boosted ushr 8) and 0xFF).toByte()
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
    if ((ws?.queueSize() ?: 0) > 120_000) return
    try {
      val d = Base64.encodeToString(jpeg, Base64.NO_WRAP)
      ws?.send("""{"realtimeInput":{"video":{"data":"$d","mimeType":"image/jpeg"}}}""")
    } catch (_: Throwable) {}
  }
}
