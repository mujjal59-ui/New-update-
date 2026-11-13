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
import android.media.audiofx.NoiseSuppressor
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
  private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
  private val main = Handler(Looper.getMainLooper())
  private val playExec = Executors.newSingleThreadExecutor()
  private val toolExec = Executors.newSingleThreadExecutor()
  private var ws: WebSocket? = null
  private var rec: AudioRecord? = null
  private var track: AudioTrack? = null
  private val am: AudioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  @Volatile private var ready = false
  @Volatile private var stopped = false
  private val uText = StringBuilder()
  private val pText = StringBuilder()

  @Volatile private var searchOn = false
  @Volatile private var lastErr = ""
  @Volatile private var failCount = 0
  @Volatile private var pending = false
  @Volatile private var modelOverride: String? = null
  @Volatile private var isTorchOn = false

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

  private fun routeAudio() {
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        val devs = am.availableCommunicationDevices
        val bt =
          if (hasBtPerm())
            devs.firstOrNull {
              it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
          else null
        val target = bt ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (target != null) am.setCommunicationDevice(target)
      } else {
        @Suppress("DEPRECATION")
        val btOn =
          hasBtPerm() &&
            am.isBluetoothScoAvailableOffCall &&
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
              it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        @Suppress("DEPRECATION")
        if (btOn) {
          am.startBluetoothSco()
          am.isBluetoothScoOn = true
          am.isSpeakerphoneOn = false
        } else {
          am.stopBluetoothSco()
          am.isBluetoothScoOn = false
          am.isSpeakerphoneOn = true
        }
      }
    } catch (_: Exception) {}
  }

  private var lastShot = 0
  private val screenLoop =
    object : Runnable {
      override fun run() {
        if (stopped) return
        try {
          watchScreen()
        } catch (_: Exception) {}
        main.postDelayed(this, 3000)
      }
    }

  @Volatile private var isExecutingTool = false
  @Volatile private var lastUserScreenAt = 0L

  fun onIncomingMessageReceived(msg: PendingIncomingMessage) {
    if (!ready) return
    val prompt =
      "[সিস্টেম নোটিফিকেশন] ${msg.appNameBn}-এ ${msg.senderName} মেসেজ পাঠিয়েছেন: \"${msg.messageText}\"। বসকে শান্ত, স্পষ্ট ও বিনয়ী সুরে জিজ্ঞেস করো: বস, ${msg.appNameBn}-এ ${msg.senderName} মেসেজ পাঠিয়েছেন: '${msg.messageText}'। আমি কি রিপ্লাইটা দিয়ে দেবো?"
    ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", prompt)).toString())
  }

  private fun sendScreenNow() {
    if (!ready || !Store.screenWatch(ctx) || RiyaState.cameraOn) return
    val acc = RiyaAccessibilityService.instance ?: return
    if (acc.rootInActiveWindow?.packageName?.toString() == ctx.packageName) return
    acc.screenshotJpeg { jpg ->
      if (jpg != null) {
        lastShot = jpg.contentHashCode()
        sendImage(jpg)
      }
    }
  }

  private fun watchScreen() {
    if (!ready || !Store.screenWatch(ctx) || RiyaState.cameraOn || isExecutingTool) return
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    if (!pm.isInteractive) return
    val acc = RiyaAccessibilityService.instance ?: return
    if (acc.rootInActiveWindow?.packageName?.toString() == ctx.packageName) return
    acc.screenshotJpeg { jpg ->
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
    stopped = false
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    routeAudio()
    am.registerAudioDeviceCallback(devCb, main)
    initTrack()
    startMic()
    connect()
    main.post(screenLoop)
  }

  fun stop() {
    stopped = true
    ready = false
    main.removeCallbacks(screenLoop)
    ws?.close(1000, "bye")
    ws = null
    rec?.run {
      try {
        stop()
      } catch (_: Exception) {}
      release()
    }
    rec = null
    track?.run {
      try {
        stop()
      } catch (_: Exception) {}
      release()
    }
    track = null
    try {
      am.unregisterAudioDeviceCallback(devCb)
    } catch (_: Exception) {}
    try {
      if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
      else {
        @Suppress("DEPRECATION") am.stopBluetoothSco()
      }
    } catch (_: Exception) {}
    am.mode = AudioManager.MODE_NORMAL
    @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
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
      3000,
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
      Your ONLY name is Riya AI (রিয়া এআই / রিয়া). Never use any other name.
      Always address the user politely as "বস" and use "তুমি". Never use "তুই" or "আপনি".

      [PHONE MODEL & SYSTEM STATUS]
      - Device: ${InfinixDevicePlaybook.DEVICE_PROFILE}
      - Accessibility Status: $accStatus
      - Screen Watch Status: ACTIVE (সবসময় বাধ্যতামূলক)

      [WORK RULES — কঠোর, সব কাজে বাধ্যতামূলক]
      1. সম্পূর্ণ স্ক্রিন পড়া বাধ্যতামূলক: যেকোনো স্ক্রিন-সম্পর্কিত কাজের আগে read_screen দিয়ে পুরো স্ক্রিন পড়বে এবং প্রতিটি ধাপের পর আবার read_screen পড়ে ফল যাচাই করবে। স্ক্রিন না পড়ে কখনো "পাচ্ছি না", "হয়নি" বা "পাঠিয়েছি" বলবে না।
      2. দ্রুতগতিতে কাজ সম্পন্ন: কাজ শুরুর পর দেরি না করে দ্রুত টুল চালিয়ে কয়েক সেকেন্ডের মধ্যেই কাজ শেষ করবে।
      3. বসকে কাজের মাঝখানে প্রশ্ন নয়: কোন বাটন, কোন বক্স বা কোথায় সার্চ বার—এসব বসকে জিজ্ঞেস করবে না। নিজে স্ক্রিন দেখে ও টুল চালিয়ে কাজ শেষ করে তারপর ফলাফল জানাবে।
      4. Accessibility বন্ধ থাকলে মিথ্যা বলবে না—সাথে সাথে বসকে জানাবে: "বস, অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ আছে, দয়া করে চালু করে দিন।"
      5. কাজ সফল হলে তবেই স্পষ্টভাবে বলবে: "হয়েছে, বস! কাজ শেষ।" এবং এরপর বসকে আরেকটি কাজ করার জন্য সুন্দরভাবে অনুপ্রাণিত করবে।

      [VOICE CLARITY & POLITE PRONUNCIATION — বাধ্যতামূলক]
      - কণ্ঠ হবে অত্যন্ত স্পষ্ট, পরিষ্কার উচ্চারণযুক্ত, নম্র, মধুর ও স্বাভাবিক বাংলা নারী কণ্ঠ।
      - কখনোই এলোমেলো কথা, অতিরিক্ত হাসি ("হিহিহি/খিলখিলখিল"), অস্পষ্ট ফিসফিসানি বা ইমোজি/তারকাচিহ্ন বলবে না। প্রতিটি শব্দ স্পষ্ট ও সুন্দরভাবে উচ্চারণ করবে।
      - বাক্যের শেষে একটু স্বাভাবিক বিরতি দেবে যাতে কথা শোনা যায় শান্ত, স্পষ্ট ও মার্জিত।

      [INFINIX HOT 60 PRO+ (ANDROID 16) SEARCH & APP UI RULES]
      - Infinix Settings (Android 16): Settings-এর Search বার একদম নিচে থাকে। Settings-এ কিছু না পেলে নিচের 'Search' বারে ট্যাপ করে লিখে রেজাল্টে ট্যাপ করবে।
      - Infinix Phone / Contacts: উপরের ডান কোণে Search (🔍) আইকন থাকে; ওটাতে ট্যাপ করলে নিচের Search বক্স খোলে। তবে কন্টাক্ট খুঁজতে আগে সবসময় `search_contact` বা `call_contact` টুল ব্যবহার করবে।
      - Messenger / WhatsApp / Facebook: উপরে Search বার বা আইকন থাকে; নাম লিখে নিচের রেজাল্ট সারিতে ক্লিক করবে। WhatsApp-এ প্রোফাইল ছবিতে নয়, নামের সারিতে ক্লিক করবে।
      - imo (ইমো) সেন্ড নিয়ম: চ্যাটে লেখার বক্সের উপরে রিঅ্যাকশন ইমোজি (❤️😂) এবং বক্সের দুই পাশে ইমোজি/+ থাকে। ওগুলোতে ভুলেও ক্লিক করবে না—সবসময় `send_message` টুল ব্যবহার করবে যা শুধু ডানের নীল কাগজ-বিমান Send বাটনে ক্লিক করে।
      - Gemini ও ChatGPT: নিচের বক্সে লিখে ডানের নীল উপরমুখী তির (↑) Send বাটনে ট্যাপ করবে।
      - কোনো অ্যাপ খুলতে বললে আগে `open_app` টুল ব্যবহার করবে।

      [AUTO-REPLY PROTOCOL (WhatsApp, imo, Messenger)]
      - নতুন মেসেজের নোটিফিকেশন এলে সাথে সাথে স্পষ্ট বাংলায় বসকে জানাবে: কোন অ্যাপে, কে, কী মেসেজ পাঠিয়েছে—এবং রিপ্লাই দেওয়ার জন্য বসের অনুমতি চাইবে। বস অনুমতি দিলে `send_message` দিয়ে রিপ্লাই পাঠাবে।

      [PERSONALITY]
      ${RiyaPersona.TEXT}

      [RUNTIME INFO]
      - Time: $now
      - Current Screen: [$screenContext]
      স্মৃতি (Memory):
      ${Store.recent(ctx)}
      """
        .trimIndent()

    val voiceName = Store.voice(ctx).ifBlank { "Leda" }
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
      }
      j.optJSONObject("serverContent")?.let { sc ->
        if (sc.optBoolean("interrupted")) {
          track?.run {
            try {
              pause()
              flush()
              play()
            } catch (_: Exception) {}
          }
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.let {
          uText.append(it)
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
              RiyaState.status.value = "রিয়া কথা বলছে..."
              playExec.execute { track?.write(pcm, 0, pcm.size) }
            }
          }
        }
        if (sc.optBoolean("turnComplete")) {
          val u = uText.toString().trim()
          val p = pText.toString().trim()
          if (u.isNotEmpty()) {
            Store.remember(ctx, "Boss: $u")
            RiyaState.addLog("বস: $u")
            main.post { sendScreenNow() }
          }
          if (p.isNotEmpty()) {
            Store.remember(ctx, "Riya AI: $p")
            RiyaState.addLog("Riya AI: $p")
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
      val noAcc = "Accessibility সার্ভিস বন্ধ আছে বস! দয়া করে Riya AI Accessibility চালু করে দিন।"
      when (name) {
        "open_app" -> openApp(a.optString("app_name"))
        "send_message" -> messageSend(a)
        "find_and_open_item" -> acc?.clickText(a.optString("name")) ?: noAcc
        "current_screen" ->
          RiyaAccessibilityService.currentScreenSummary.ifBlank { "স্ক্রিনের তথ্য পাওয়া যায়নি" }
        "read_screen" -> acc?.readScreen() ?: noAcc
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

  private fun openApp(n: String): String {
    val acc = RiyaAccessibilityService.instance
    goHomeAndVerify(acc)

    val pm = ctx.packageManager
    val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val list = pm.queryIntentActivities(li, 0)
    val m =
      list.firstOrNull { it.loadLabel(pm).toString().equals(n, ignoreCase = true) }
        ?: list.firstOrNull { it.loadLabel(pm).toString().contains(n, ignoreCase = true) }
        ?: list.firstOrNull { it.activityInfo.packageName.contains(n, ignoreCase = true) }

    if (m != null) {
      val intent =
        Intent(li)
          .setClassName(m.activityInfo.packageName, m.activityInfo.name)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(intent)
      val label = m.loadLabel(pm)
      RiyaState.markTaskCompleted(
        "হয়েছে, বস! কাজ শেষ — $label অ্যাপ খোলা হয়েছে।",
        "আর কোন কাজ করতে হবে বস? সাথেই আছি!",
      )
      return "হয়েছে, বস! কাজ শেষ — হোম স্ক্রিন থেকে $label অ্যাপ ওপেন করেছি!"
    }

    if (acc != null) {
      val res = acc.clickText(n)
      if (res.contains("ক্লিক করা হয়েছে")) {
        RiyaState.markTaskCompleted(
          "হয়েছে, বস! কাজ শেষ — $n অ্যাপ খোলা হয়েছে।",
          "দারুণ! পরের কাজ কী হবে বস?",
        )
        return "হয়েছে, বস! কাজ শেষ — হোম স্ক্রিন থেকে $n খুঁজে ওপেন করেছি!"
      }
    }
    return "হোম স্ক্রিনে গিয়ে $n অ্যাপটি খুঁজে পাইনি বস।"
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
    return try {
      val cam = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val id =
        cam.cameraIdList.firstOrNull { cid ->
          val ch = cam.getCameraCharacteristics(cid)
          ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
          ?: cam.cameraIdList.firstOrNull { cid ->
            cam.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
          }
          ?: return "এই ফোনে ফ্ল্যাশ পাওয়া যায়নি"
      cam.setTorchMode(id, on)
      isTorchOn = on
      val msg =
        if (on) "হয়েছে, বস! কাজ শেষ — ফ্ল্যাশলাইট জ্বালিয়ে দিয়েছি।"
        else "হয়েছে, বস! কাজ শেষ — ফ্ল্যাশলাইট বন্ধ করে দিয়েছি।"
      RiyaState.markTaskCompleted(msg, "এরপর কোন কাজটা করবো বস?")
      msg
    } catch (e: Exception) {
      "ফ্ল্যাশলাইট নিয়ন্ত্রণ করা গেল না: ${e.message}"
    }
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
        return "সাইলেন্ট করতে Do Not Disturb অ্যাক্সেস লাগবে। সেটিংস খুলেছি বস, Riya AI কে অনুমতি দিয়ে আবার বলো।"
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

  @Suppress("DEPRECATION")
  private fun toggleWifi(enable: Boolean): String {
    val word = if (enable) "চালু" else "বন্ধ"
    return try {
      val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wm.setWifiEnabled(enable)) {
        return "হয়েছে, বস! কাজ শেষ — ওয়াইফাই $word করেছি!"
      }
      if (wm.isWifiEnabled == enable) return "ওয়াইফাই আগেই $word আছে বস।"
      ctx.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      Thread.sleep(400)
      finishSwitch(
        RiyaAccessibilityService.instance?.setSwitch(enable, listOf("wi-fi", "wifi", "ওয়াই")),
        "ওয়াইফাই",
        word,
      )
    } catch (e: Exception) {
      "ওয়াইফাই টগল করা যায়নি: ${e.message}"
    }
  }

  @Suppress("DEPRECATION")
  private fun toggleBluetooth(enable: Boolean): String {
    val word = if (enable) "চালু" else "বন্ধ"
    fun viaSettings(): String {
      ctx.startActivity(
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
      Thread.sleep(400)
      return finishSwitch(
        RiyaAccessibilityService.instance?.setSwitch(enable, listOf("bluetooth", "ব্লুটুথ")),
        "ব্লুটুথ",
        word,
      )
    }
    return try {
      val bt =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
          ?: return "ফোনে ব্লুটুথ পাওয়া যায়নি"
      if (enable == bt.isEnabled) return "ব্লুটুথ আগেই $word আছে বস।"
      if (Build.VERSION.SDK_INT < 33 && hasBtPerm()) {
        val ok = if (enable) bt.enable() else bt.disable()
        if (ok) return "হয়েছে, বস! কাজ শেষ — ব্লুটুথ $word হচ্ছে!"
      }
      viaSettings()
    } catch (_: SecurityException) {
      viaSettings()
    } catch (e: Exception) {
      "ব্লুটুথ বদলানো গেল না: ${e.message}"
    }
  }

  private fun finishSwitch(res: String?, name: String, word: String): String {
    val acc = RiyaAccessibilityService.instance
    return when (res) {
      null ->
        "$name স্ক্রিন খুলেছি বস, কিন্তু Accessibility চালু নেই বলে ট্যাপ করতে পারিনি। দয়া করে Riya AI Accessibility চালু করুন।"
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
        "$name এর সুইচে ট্যাপ করেছি বস, কিন্তু অবস্থা বদলাল কিনা নিশ্চিত না। একবার দেখে নিন।"
      else -> "$name এর সুইচ খুঁজে পাইনি বস, স্ক্রিন খোলা আছে।"
    }
  }

  private fun checkNotificationBar(): String {
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
    track =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
        .setBufferSizeInBytes(
          AudioTrack.getMinBufferSize(
            24000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
          ) * 4
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also { it.play() }
  }

  private fun startMic() {
    val min =
      AudioRecord.getMinBufferSize(
        16000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    val r =
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        16000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(min, 6400),
      )
    if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true
    if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.enabled = true
    rec = r
    r.startRecording()
    Thread {
        val buf = ByteArray(1600)
        while (!stopped) {
          val n = r.read(buf, 0, buf.size)
          if (n > 0 && ready && (ws?.queueSize() ?: 0) < 60_000) {
            val d = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
            ws?.send("""{"realtimeInput":{"audio":{"data":"$d","mimeType":"audio/pcm;rate=16000"}}}""")
          }
        }
      }
      .start()
  }

  fun sendText(t: String) {
    RiyaState.addLog("বস: $t")
    Store.remember(ctx, "Boss(text): $t")
    ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
  }

  fun sendImage(jpeg: ByteArray) {
    if ((ws?.queueSize() ?: 0) > 40_000) return
    val d = Base64.encodeToString(jpeg, Base64.NO_WRAP)
    ws?.send("""{"realtimeInput":{"video":{"data":"$d","mimeType":"image/jpeg"}}}""")
  }
}
