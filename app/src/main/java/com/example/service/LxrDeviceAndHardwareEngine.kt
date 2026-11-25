package com.example.service

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.MainActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ScheduledPhoneAlarm(
  val id: String,
  val reqCode: Int,
  val title: String,
  val mode: String,
  val triggerAtEpochMs: Long,
  val hour: Int,
  val minute: Int,
  val delaySeconds: Long,
  val exactScheduled: Boolean,
  val alarmClockRegistered: Boolean,
  val phoneClockDispatched: Boolean,
  val phoneClockPackage: String,
  val status: String,
  val createdAtMs: Long,
)

/**
 * Full device hardware diagnostics, expanded phone settings toggles/sliders,
 * live GPS location + turn-by-turn navigation directions, contact counts, and dialer call-log summary.
 */
object LxrDeviceAndHardwareEngine {

  fun computeScaledVolumeIndex(percent: Int, maxVolume: Int): Int {
    val safeMax = maxVolume.coerceAtLeast(1)
    val clampedPct = percent.coerceIn(0, 100)
    return ((clampedPct / 100.0) * safeMax).roundToInt().coerceIn(0, safeMax)
  }

  fun computeBrightnessValue(percentOrRaw: Int): Int {
    return if (percentOrRaw <= 100) {
      ((percentOrRaw.coerceIn(1, 100) / 100.0) * 255.0).roundToInt().coerceIn(5, 255)
    } else {
      percentOrRaw.coerceIn(5, 255)
    }
  }

  fun buildNavigationUri(destination: String, travelMode: String = "driving"): Uri {
    val modeCode =
      when (travelMode.lowercase()) {
        "walk", "walking", "হাঁটা" -> "w"
        "bike", "bicycling", "cycle", "সাইকেল" -> "b"
        "two_wheeler", "motorcycle", "বাইক" -> "l"
        else -> "d"
      }
    return Uri.parse("google.navigation:q=${Uri.encode(destination.trim())}&mode=$modeCode")
  }

  fun hasLocationPermission(ctx: Context): Boolean {
    val fine =
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse =
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  fun isLocationEnabledNow(ctx: Context): Boolean {
    return try {
      val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
      if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
      else lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (_: Throwable) {
      false
    }
  }

  fun isWriteSettingsGranted(ctx: Context): Boolean =
    try {
      Settings.System.canWrite(ctx)
    } catch (_: Throwable) {
      false
    }

  fun isDefaultAssistant(ctx: Context): Boolean {
    return try {
      val cur = Settings.Secure.getString(ctx.contentResolver, "assistant").orEmpty()
      if (cur.contains(ctx.packageName, ignoreCase = true)) return true
      val v = Settings.Secure.getString(ctx.contentResolver, "voice_interaction_service").orEmpty()
      v.contains(ctx.packageName, ignoreCase = true)
    } catch (_: Throwable) {
      false
    }
  }

  @Suppress("DEPRECATION")
  fun getHardwareAndSystemDiagnostics(ctx: Context): String {
    val sb = StringBuilder()
    sb.appendLine("📱 ডিভাইস ও হার্ডওয়্যার বিস্তারিত তথ্য:")
    sb.appendLine("• মডেল: ${Build.MANUFACTURER} ${Build.MODEL} (Brand: ${Build.BRAND}, Board: ${Build.BOARD})")
    sb.appendLine("• অপারেটিং সিস্টেম: Android ${Build.VERSION.RELEASE} (API/SDK ${Build.VERSION.SDK_INT}, Security Patch: ${if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "N/A"})")
    val abis = Build.SUPPORTED_ABIS?.joinToString(", ").orEmpty().ifBlank { "arm64" }
    val cores = Runtime.getRuntime().availableProcessors()
    sb.appendLine("• প্রসেসর/আর্কিটেকচার: $cores CPU Cores | ABI: $abis")

    runCatching {
      val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      val mi = ActivityManager.MemoryInfo()
      am.getMemoryInfo(mi)
      val totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
      val availGb = mi.availMem / (1024.0 * 1024.0 * 1024.0)
      sb.appendLine(String.format(Locale.US, "• RAM: মোট %.2f GB | ফ্রি %.2f GB", totalGb, availGb))
    }

    runCatching {
      val stat = StatFs(Environment.getDataDirectory().path)
      val totalGb = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)
      val availGb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
      sb.appendLine(String.format(Locale.US, "• ইন্টারনাল স্টোরেজ: মোট %.1f GB | ফ্রি %.1f GB", totalGb, availGb))
    }

    runCatching {
      val dm = ctx.resources.displayMetrics
      sb.appendLine("• ডিসপ্লে: ${dm.widthPixels}x${dm.heightPixels} px (${dm.densityDpi} dpi)")
    }

    runCatching {
      val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      val lvl = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
      val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
      val pct = if (lvl >= 0 && scale > 0) (lvl * 100) / scale else -1
      val tempTenths = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
      val voltMv = bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
      val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
      val health =
        when (bat?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
          BatteryManager.BATTERY_HEALTH_GOOD -> "Good (ভালো)"
          BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat (গরম)"
          BatteryManager.BATTERY_HEALTH_DEAD -> "Weak/Dead"
          else -> "Normal"
        }
      sb.appendLine(
        String.format(
          Locale.US,
          "• ব্যাটারি: %d%% %s | তাপমাত্রা %.1f°C | ভোল্টেজ %.2fV | স্বাস্থ্য: %s",
          pct,
          if (plugged) "(চার্জ হচ্ছে)" else "",
          tempTenths / 10.0,
          voltMv / 1000.0,
          health,
        )
      )
    }

    runCatching {
      val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      val caps = cm.getNetworkCapabilities(cm.activeNetwork)
      val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
      val netDesc =
        when {
          caps == null -> "Offline"
          caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi (সক্রিয়)"
          caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data (সক্রিয়)"
          else -> "Connected"
        }
      sb.appendLine("• নেটওয়ার্ক: $netDesc | Wi-Fi: ${if (wm?.isWifiEnabled == true) "ON" else "OFF"} | Bluetooth: ${if (bt?.isEnabled == true) "ON" else "OFF"}")
    }

    runCatching {
      val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
      val sensors = sm.getSensorList(Sensor.TYPE_ALL)
      val names = sensors.take(8).joinToString(", ") { it.name }
      sb.appendLine("• হার্ডওয়্যার সেন্সর (${sensors.size}টি): $names")
    }

    return sb.toString().trim()
  }

  @Suppress("DEPRECATION")
  fun controlDeviceSetting(
    ctx: Context,
    acc: RiyaAccessibilityService?,
    settingName: String,
    enabled: Boolean? = null,
    valueInt: Int? = null,
  ): String {
    val s = settingName.trim().lowercase()
    return when {
      s in setOf("volume", "media_volume", "ring_volume", "alarm_volume", "ভলিউম", "সাউন্ড_ভলিউম") -> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream =
          when {
            s.contains("ring") || s.contains("কল") -> AudioManager.STREAM_RING
            s.contains("alarm") -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
          }
        val maxVol = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val curVol = am.getStreamVolume(stream)
        val targetVol =
          when {
            valueInt != null -> computeScaledVolumeIndex(valueInt, maxVol)
            enabled == true -> (curVol + maxOf(1, maxVol / 5)).coerceAtMost(maxVol)
            enabled == false -> (curVol - maxOf(1, maxVol / 5)).coerceAtLeast(0)
            else -> curVol
          }
        am.setStreamVolume(stream, targetVol, AudioManager.FLAG_SHOW_UI)
        val pct = (targetVol * 100.0 / maxVol).roundToInt()
        "হয়েছে, বস! ভলিউম এখন প্রায় $pct% ($targetVol/$maxVol) করা হয়েছে।"
      }

      s in setOf("brightness", "ব্রাইটনেস", "আলো", "display_brightness") -> {
        val targetPct =
          valueInt ?: when (enabled) {
            true -> 85
            false -> 25
            else -> 60
          }
        val briVal = computeBrightnessValue(targetPct)
        if (Settings.System.canWrite(ctx)) {
          Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
          Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, briVal)
          val pct = ((briVal / 255.0) * 100).roundToInt()
          "হয়েছে, বস! স্ক্রিনের ব্রাইটনেস $pct% সেট করেছি।"
        } else {
          val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          ctx.startActivity(i)
          "ব্রাইটনেস সরাসরি বদলাতে 'Modify system settings' অনুমতি প্রয়োজন বস — সেটিংস স্ক্রিন খুলেছি, অনুমতি দিলে এখনই সেট করে দেবো।"
        }
      }

      s in setOf("auto_rotate", "rotation", "অটো_রোটেট", "রোটেট") -> {
        val want = enabled ?: true
        val word = if (want) "চালু" else "বন্ধ"
        if (Settings.System.canWrite(ctx)) {
          Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (want) 1 else 0)
          "হয়েছে, বস! Auto-Rotate $word করেছি।"
        } else if (acc != null) {
          val qs = acc.toggleQuickSettingTile(want, listOf("auto-rotate", "auto rotate", "rotation", "portrait", "রোটেট"))
          if (qs == "OK" || qs == "ALREADY") "হয়েছে, বস! Quick Settings থেকে Auto-Rotate $word করেছি।"
          else "Auto-Rotate সুইচটি Quick Settings-এ খুঁজে পাইনি বস।"
        } else {
          "Auto-Rotate বদলাতে Modify System Settings বা Accessibility চালু করুন বস।"
        }
      }

      s in setOf("mobile_data", "data", "cellular", "মোবাইল_ডাটা", "ডাটা", "ইন্টারনেট") -> {
        val want = enabled ?: true
        val word = if (want) "চালু" else "বন্ধ"
        if (acc == null) {
          ctx.startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          return "মোবাইল ডাটা সেটিংস খুলেছি বস (স্বয়ংক্রিয় ট্যাপের জন্য Accessibility চালু রাখুন)।"
        }
        val qs = acc.toggleQuickSettingTile(
          enable = want,
          labels = listOf("mobile data", "cellular", "data connection", "5g", "4g", "sim", "মোবাইল ডাটা"),
          excludeLabels = listOf("wi-fi", "wifi", "hotspot", "saver"),
        )
        if (qs == "OK" || qs == "ALREADY") {
          "হয়েছে, বস! মোবাইল ডাটা $word করে দিয়েছি।"
        } else {
          ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          "Quick Settings-এ মোবাইল ডাটা সরাসরি টগল করা যায়নি, নেটওয়ার্ক সেটিংস খুলে দিয়েছি বস।"
        }
      }

      s in setOf("hotspot", "tethering", "হটস্পট") -> {
        val want = enabled ?: true
        val word = if (want) "চালু" else "বন্ধ"
        if (acc != null) {
          val qs = acc.toggleQuickSettingTile(want, listOf("hotspot", "tethering", "হটস্পট"))
          if (qs == "OK" || qs == "ALREADY") {
            return "হয়েছে, বস! Wi-Fi Hotspot $word করে দিয়েছি।"
          }
        }
        ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "হটস্পট সেটিংস স্ক্রিন খুলে দিয়েছি বস।"
      }

      s in setOf("dnd", "do_not_disturb", "ডু_নট_ডিস্টার্ব") -> {
        val want = enabled ?: true
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
          nm.setInterruptionFilter(
            if (want) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL
          )
          "হয়েছে, বস! Do Not Disturb ${if (want) "চালু" else "বন্ধ"} করেছি।"
        } else if (acc != null) {
          val qs = acc.toggleQuickSettingTile(want, listOf("do not disturb", "dnd"))
          if (qs == "OK" || qs == "ALREADY") "হয়েছে, বস! Do Not Disturb ${if (want) "চালু" else "বন্ধ"} করেছি।"
          else {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Do Not Disturb অ্যাক্সেস অনুমতি দিন বস — সেটিংস খুলেছি।"
          }
        } else {
          ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          "Do Not Disturb সেটিংস খুলেছি বস।"
        }
      }

      s in setOf("location", "gps", "লোকেশন", "জিপিএস") -> {
        val want = enabled ?: !isLocationEnabledNow(ctx)
        val curOn = isLocationEnabledNow(ctx)
        if (curOn == want) return "ফোনের Location/GPS আগেই ${if (want) "চালু" else "বন্ধ"} আছে বস।"

        // 0. Fast-path via WRITE_SECURE_SETTINGS (if granted via ADB/device privilege)
        runCatching {
          val targetMode =
            if (want) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
            else Settings.Secure.LOCATION_MODE_OFF
          if (Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, targetMode)) {
            Thread.sleep(220)
            if (isLocationEnabledNow(ctx) == want) {
              LxrCompanionAgent.sampleNow(ctx)
              return "হয়েছে, বস! ফোনের Location/GPS ${if (want) "চালু" else "বন্ধ"} করেছি।"
            }
          }
        }

        if (acc != null) {
          // 1. Try Quick Settings / Control Center tile AND verify real LocationManager state (never fake-succeed on OK!)
          acc.toggleQuickSettingTile(
            enable = want,
            labels = listOf("location", "gps", "লোকেশন", "অবস্থান"),
            excludeLabels = listOf("scanning", "emergency"),
            hardwareStateKnown = curOn,
          )
          val qsConfirmDeadline = System.currentTimeMillis() + 1150L
          while (System.currentTimeMillis() < qsConfirmDeadline) {
            if (isLocationEnabledNow(ctx) == want) {
              LxrCompanionAgent.sampleNow(ctx)
              return "হয়েছে, বস! ফোনের Location/GPS ${if (want) "চালু" else "বন্ধ"} করেছি।"
            }
            Thread.sleep(160)
          }

          // 2. Reliable Settings UI fallback: launch Location Settings and toggle master switch
          ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          val waitDeadline = System.currentTimeMillis() + 3000L
          while (System.currentTimeMillis() < waitDeadline) {
            val curPkg = acc.rootInActiveWindow?.packageName?.toString().orEmpty()
            val hasSettingsWin = acc.windows.orEmpty().any { it.root?.packageName?.toString() == "com.android.settings" }
            if (curPkg == "com.android.settings" || hasSettingsWin) {
              Thread.sleep(280)
              break
            }
            Thread.sleep(160)
          }
          acc.setSwitch(
            enable = want,
            labels = listOf("use location", "location access", "access to my location", "location", "লোকেশন", "অবস্থান"),
            excludeLabels = listOf("wi-fi scanning", "bluetooth scanning", "emergency location", "location history", "timeline", "accuracy"),
          )
          // Check confirmation dialog if Android / GMS asks "Improve location accuracy?" / "Agree"
          if (isLocationEnabledNow(ctx) != want) {
            val consentLabels = listOf("Turn on", "Agree", "Allow", "Enable", "চালু করুন", "হ্যাঁ")
            for (lbl in consentLabels) {
              if (acc.clickText(lbl).contains("ক্লিক করা হয়েছে")) {
                Thread.sleep(280)
                break
              }
            }
          }
          val confirmDeadline = System.currentTimeMillis() + 1300L
          while (isLocationEnabledNow(ctx) != want && System.currentTimeMillis() < confirmDeadline) {
            Thread.sleep(160)
          }
          if (isLocationEnabledNow(ctx) != want) {
            // Second attempt on Settings row label / toggle text directly
            for (lbl in listOf("Use location", "লোকেশন ব্যবহার করুন")) {
              if (acc.clickText(lbl).contains("ক্লিক করা হয়েছে")) {
                Thread.sleep(450)
                break
              }
            }
          }
          LxrCompanionAgent.sampleNow(ctx)
          if (isLocationEnabledNow(ctx) == want) {
            val fgNow = acc.rootInActiveWindow?.packageName?.toString().orEmpty()
            if (fgNow == "com.android.settings") {
              acc.global("back")
              Thread.sleep(180)
            }
            return "হয়েছে, বস! লোকেশন সেটিংস থেকে ফোনের GPS/Location ${if (want) "চালু" else "বন্ধ"} করে দিয়েছি।"
          }
          return "বস, Location সেটিংস খুলেছি — অনুগ্রহ করে এক নজর 'Use location' সুইচটি দেখে নিন।"
        }
        ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Location সেটিংস স্ক্রিন খুলেছি বস, সুইচটি ${if (want) "চালু" else "বন্ধ"} করে নিন (সরাসরি অটো-টগলের জন্য Accessibility চালু রাখুন)।"
      }

      else -> "বস, '$settingName' সেটিংসটি সিস্টেম সেটিংস থেকে দেখে নিচ্ছি।"
    }
  }

  data class SelfHealthIssue(
    val key: String,
    val titleBn: String,
    val detailBn: String,
  )

  data class SelfDiagnosisReport(
    val isHealthy: Boolean,
    val summaryBn: String,
    val issues: List<SelfHealthIssue>,
  )

  fun diagnoseSelfHealth(ctx: Context): SelfDiagnosisReport {
    val issues = mutableListOf<SelfHealthIssue>()
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    if (!Keep.accessibilityOn(ctx)) {
      issues.add(
        SelfHealthIssue(
          key = "accessibility",
          titleBn = "Accessibility সার্ভিস বন্ধ",
          detailBn = "পূর্ণ স্ক্রিন রিডিং, হোম/ব্যাক ও অটো-টাস্কের জন্য LXR--AI Accessibility চালু থাকা জরুরি।",
        )
      )
    }
    if (!RiyaRuntimeBridge.isNotificationListenerConnected.value) {
      issues.add(
        SelfHealthIssue(
          key = "notification",
          titleBn = "Notification Access বন্ধ",
          detailBn = "WhatsApp, imo ও Messenger-এর নতুন মেসেজ সাথে সাথে পড়তে Notification Access দরকার।",
        )
      )
    }
    if (!Settings.canDrawOverlays(ctx)) {
      issues.add(
        SelfHealthIssue(
          key = "overlay",
          titleBn = "Display Over Other Apps বন্ধ",
          detailBn = "ভাসমান LxR অরব দেখাতে এই অনুমতি প্রয়োজন।",
        )
      )
    }
    if (!isWriteSettingsGranted(ctx)) {
      issues.add(
        SelfHealthIssue(
          key = "write_settings",
          titleBn = "Modify System Settings বন্ধ",
          detailBn = "ব্রাইটনেস, অটো-রোটেট ও সিস্টেম সেটিংস সরাসরি নিয়ন্ত্রণের জন্য এই অনুমতি দরকার (ধূসর থাকলে App Info > ⋮ > Allow restricted settings চালু করতে হয়)।",
        )
      )
    }
    if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
      issues.add(
        SelfHealthIssue(
          key = "battery",
          titleBn = "Battery Optimization অন আছে",
          detailBn = "২৪/৭ ব্যাকগ্রাউন্ড সচল রাখতে Battery Unrestricted করা উচিত।",
        )
      )
    }
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      issues.add(
        SelfHealthIssue(
          key = "microphone",
          titleBn = "Microphone (মাইক্রোফোন) অনুমতি বন্ধ",
          detailBn = "তোমার কথা ও কমান্ড শোনার জন্য মাইক্রোফোন অনুমতি চালু থাকা জরুরি।",
        )
      )
    }
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      issues.add(
        SelfHealthIssue(
          key = "camera",
          titleBn = "Camera (ক্যামেরা) অনুমতি বন্ধ",
          detailBn = "ফ্রন্ট ক্যামেরায় তোমাকে চিনতে ও লাইভ দৃশ্য দেখতে ক্যামেরা অনুমতি দরকার।",
        )
      )
    }
    if (!isDefaultAssistant(ctx)) {
      issues.add(
        SelfHealthIssue(
          key = "assistant",
          titleBn = "Default Digital Assistant হিসেবে সেট করা নেই",
          detailBn = "সিস্টেম অ্যাসিস্ট্যান্ট বাটন ও গভীর স্ক্রিন-রিডিংয়ের জন্য LXR--AI-কে Default digital assistant app হিসেবে সেট করা যায়।",
        )
      )
    }
    if (!hasLocationPermission(ctx) || !isLocationEnabledNow(ctx)) {
      issues.add(
        SelfHealthIssue(
          key = "location",
          titleBn = "GPS / Location অনুমতি বা সুইচ বন্ধ",
          detailBn = "লাইভ লোকেশন ও ম্যাপ নেভিগেশনের জন্য Location চালু থাকা দরকার।",
        )
      )
    }
    if (!isDeviceAdminActive(ctx)) {
      issues.add(
        SelfHealthIssue(
          key = "device_admin",
          titleBn = "Device Admin App সক্রিয় নয়",
          detailBn = "বসের নির্দেশে দ্রুত স্ক্রিন লক ও ২৪/৭ ব্যাকগ্রাউন্ড স্থায়িত্বের জন্য Device Admin চালু রাখা যায় (Controlled by Restricted Setting হলে App info > ⋮ > Allow restricted settings চালু করতে হয়)।",
        )
      )
    }
    if (!canScheduleExactAlarms(ctx)) {
      issues.add(
        SelfHealthIssue(
          key = "exact_alarm",
          titleBn = "Alarms & reminders অনুমতি বন্ধ",
          detailBn = "সঠিক সময়ে রিমাইন্ডার, অ্যালার্ম ও ২৪/৭ Doze রিস্টার্টের জন্য Alarms & reminders চালু থাকা দরকার।",
        )
      )
    }
    if (com.example.data.Store.apiKey(ctx).isBlank()) {
      issues.add(
        SelfHealthIssue(
          key = "api_key",
          titleBn = "Gemini API Key সেট করা নেই",
          detailBn = "কণ্ঠ ও বুদ্ধিমত্তা সচল রাখতে API Key প্রয়োজন।",
        )
      )
    }
    val summary =
      if (issues.isEmpty()) {
        "বস, আমি নিজের সম্পূর্ণ সিস্টেম পরীক্ষা করেছি — সব অনুমতি (Accessibility, Notification, Microphone, Camera, Device Admin, Alarms & reminders, Modify System Settings, Location, Battery) ও ইঞ্জিন ১০০% সুস্থ আছে।"
      } else {
        buildString {
          appendLine("বস, আমি নিজের সিস্টেম পরীক্ষা করে ${issues.size}টি সমস্যা পেয়েছি:")
          issues.forEachIndexed { i, iss ->
            appendLine("${i + 1}. ${iss.titleBn} (key=${iss.key}) — ${iss.detailBn}")
          }
          append("বস, তোমার অনুমতি পেলে আমি এখনই এই সমস্যার সেটিংস খুলে ঠিক করে দিতে পারি।")
        }.trim()
      }
    return SelfDiagnosisReport(isHealthy = issues.isEmpty(), summaryBn = summary, issues = issues)
  }

  fun repairIssueWithBossPermission(ctx: Context, issueKey: String): String {
    val k = issueKey.trim().lowercase()
    return when {
      k.contains("access") -> {
        if (Keep.ensureAccessibility(ctx)) "হয়েছে বস! তোমার অনুমতি নিয়ে Accessibility স্বয়ংক্রিয়ভাবে চালু করেছি ✓"
        else {
          Keep.openAccessibility(ctx)
          "বস, তোমার অনুমতি নিয়ে Accessibility সেটিংস খুলেছি — দয়া করে LXR--AI অন করে দাও।"
        }
      }
      k.contains("notif") -> {
        Keep.openNotificationAccess(ctx)
        "বস, তোমার অনুমতি নিয়ে Notification Access সেটিংস খুলেছি।"
      }
      k.contains("mic") || k.contains("cam") -> {
        Keep.openAppInfo(ctx)
        "বস, তোমার অনুমতি নিয়ে App Permissions পেজ খুলেছি — এখান থেকে Microphone ও Camera অনুমতি Allow করে দাও।"
      }
      k.contains("overlay") -> {
        ctx.startActivity(
          Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        "বস, Display over other apps সেটিংস খুলেছি।"
      }
      k.contains("write") || k.contains("restrict") -> {
        try {
          ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          )
          "বস, Modify System Settings খুলেছি। সুইচ ধূসর থাকলে App info > উপরে ডানে ⋮ মেনু থেকে Allow restricted settings চালু করে দাও।"
        } catch (_: Throwable) {
          Keep.openAppInfo(ctx)
          "বস, App info খুলেছি — সেখান থেকে Restricted settings অনুমতি দাও।"
        }
      }
      k.contains("assist") -> {
        try {
          ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
          ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        "বস, Default digital assistant সেটিংস খুলেছি — এখান থেকে LXR--AI নির্বাচন করে দাও।"
      }
      k.contains("bat") -> {
        Keep.openBattery(ctx)
        "বস, Battery Optimization সেটিংস খুলেছি।"
      }
      k.contains("loc") || k.contains("gps") -> {
        controlDeviceSetting(ctx, RiyaAccessibilityService.instance, "location", enabled = true)
      }
      k.contains("admin") -> {
        openDeviceAdminSettings(ctx)
        "বস, Device Admin Apps সেটিংস খুলেছি — এখান থেকে LXR--AI Activate করে দাও। 'Controlled by Restricted Setting' দেখালে App info > উপরে ডানে ⋮ > Allow restricted settings চালু করে আবার চেষ্টা করো।"
      }
      k.contains("alarm") || k.contains("remind") -> {
        if (canScheduleExactAlarms(ctx)) {
          "হয়েছে বস! LXR--AI এর Alarms & reminders অনুমতি আগে থেকেই চালু (Allowed) আছে ✓"
        } else {
          val opened = openExactAlarmSettings(ctx)
          if (opened) {
            "বস, ফোনের Alarms & reminders অনুমতি পেজ খুলেছি — তালিকা থেকে LXR--AI এর সুইচ Allow করে দাও। যদি সুইচ ধূসর বা না দেখা যায়, App info > উপরে ডানে ⋮ > Allow restricted settings চালু করে দিও।"
          } else {
            "বস, সরাসরি Alarms & reminders পেজ খোলার চেষ্টা করেছি — না খুললে Settings > Apps > Special app access > Alarms & reminders থেকে LXR--AI Allow করে দাও।"
          }
        }
      }
      k.contains("app_info") -> {
        Keep.openAppInfo(ctx)
        "বস, LXR--AI App info পেজ খুলেছি।"
      }
      else -> diagnoseSelfHealth(ctx).summaryBn
    }
  }

  fun isDeviceAdminActive(ctx: Context): Boolean {
    return try {
      val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
      dpm?.isAdminActive(ComponentName(ctx, LxrDeviceAdminReceiver::class.java)) == true
    } catch (_: Throwable) {
      false
    }
  }

  fun openDeviceAdminSettings(ctx: Context) {
    val adminComp = ComponentName(ctx, LxrDeviceAdminReceiver::class.java)
    val addIntent =
      Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
        .putExtra(
          DevicePolicyManager.EXTRA_ADD_EXPLANATION,
          "LXR--AI-কে তাৎক্ষণিক স্ক্রিন লক ও ২৪/৭ ব্যাকগ্রাউন্ড সুরক্ষা দিতে Activate করুন।",
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      ctx.startActivity(addIntent)
    } catch (_: Throwable) {
      try {
        ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      } catch (_: Throwable) {
        Keep.openAppInfo(ctx)
      }
    }
  }

  fun canScheduleExactAlarms(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return try {
      val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
      am?.canScheduleExactAlarms() == true
    } catch (_: Throwable) {
      false
    }
  }

  fun openExactAlarmSettings(ctx: Context): Boolean {
    val acc = RiyaAccessibilityService.instance
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val appExactIntent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      if (LxrAppDiscoveryEngine.launchActivitySafely(ctx, acc, appExactIntent)) {
        return true
      }
      val listExactIntent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      if (LxrAppDiscoveryEngine.launchActivitySafely(ctx, acc, listExactIntent)) {
        return true
      }
    }
    val appDetailsIntent =
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (LxrAppDiscoveryEngine.launchActivitySafely(ctx, acc, appDetailsIntent)) {
      return true
    }
    Keep.openAppInfo(ctx)
    return false
  }

  private const val ALARM_PREFS_NAME = "lxr_alarm_prefs"
  private const val KEY_SCHEDULED_ALARMS = "scheduled_alarms_v1"

  fun computeTargetAlarmTrigger(
    delaySeconds: Long,
    hour: Int?,
    minute: Int?,
    nowMs: Long = System.currentTimeMillis(),
  ): Triple<Long, Int, Int> {
    val tz = TimeZone.getTimeZone("Asia/Dhaka")
    val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
    if (hour != null && hour in 0..23) {
      val safeMin = (minute ?: 0).coerceIn(0, 59)
      cal.set(Calendar.HOUR_OF_DAY, hour)
      cal.set(Calendar.MINUTE, safeMin)
      cal.set(Calendar.SECOND, 0)
      cal.set(Calendar.MILLISECOND, 0)
      if (cal.timeInMillis <= nowMs + 2_000L) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
      }
      return Triple(cal.timeInMillis, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
    val clampedSec = delaySeconds.coerceIn(5L, 86_400L * 7L)
    val triggerMs = nowMs + clampedSec * 1000L
    cal.timeInMillis = triggerMs
    return Triple(triggerMs, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
  }

  fun resolvePhoneClockPackage(ctx: Context, action: String = AlarmClock.ACTION_SET_ALARM): String? {
    return try {
      val intent = Intent(action)
      val preferred = listOf("com.transsion.deskclock", "com.google.android.deskclock", "com.android.deskclock")
      val pm = ctx.packageManager
      val matches =
        if (Build.VERSION.SDK_INT >= 33) {
          pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
          @Suppress("DEPRECATION")
          pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.mapNotNull { it.activityInfo?.packageName?.trim() }
          .filter { it.isNotBlank() && it != ctx.packageName }
      preferred.firstOrNull { it in matches }
        ?: matches.firstOrNull()
        ?: preferred.firstOrNull { pkg -> runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false) }
    } catch (_: Throwable) {
      null
    }
  }

  private fun readPersistedAlarmsLocked(ctx: Context): MutableList<ScheduledPhoneAlarm> {
    val sp = ctx.applicationContext.getSharedPreferences(ALARM_PREFS_NAME, Context.MODE_PRIVATE)
    val raw = sp.getString(KEY_SCHEDULED_ALARMS, "[]") ?: "[]"
    val list = mutableListOf<ScheduledPhoneAlarm>()
    runCatching {
      val arr = JSONArray(raw)
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        list.add(
          ScheduledPhoneAlarm(
            id = o.optString("id"),
            reqCode = o.optInt("reqCode", 7000 + i),
            title = o.optString("title"),
            mode = o.optString("mode", "auto"),
            triggerAtEpochMs = o.optLong("triggerAtEpochMs", 0L),
            hour = o.optInt("hour", 0),
            minute = o.optInt("minute", 0),
            delaySeconds = o.optLong("delaySeconds", 0L),
            exactScheduled = o.optBoolean("exactScheduled", false),
            alarmClockRegistered = o.optBoolean("alarmClockRegistered", false),
            phoneClockDispatched = o.optBoolean("phoneClockDispatched", false),
            phoneClockPackage = o.optString("phoneClockPackage"),
            status = o.optString("status", "SCHEDULED"),
            createdAtMs = o.optLong("createdAtMs", 0L),
          )
        )
      }
    }
    return list
  }

  private fun writePersistedAlarmsLocked(ctx: Context, alarms: List<ScheduledPhoneAlarm>) {
    val trimmed = alarms.sortedByDescending { it.createdAtMs }.take(40)
    val arr = JSONArray()
    trimmed.forEach { a ->
      arr.put(
        JSONObject()
          .put("id", a.id)
          .put("reqCode", a.reqCode)
          .put("title", a.title)
          .put("mode", a.mode)
          .put("triggerAtEpochMs", a.triggerAtEpochMs)
          .put("hour", a.hour)
          .put("minute", a.minute)
          .put("delaySeconds", a.delaySeconds)
          .put("exactScheduled", a.exactScheduled)
          .put("alarmClockRegistered", a.alarmClockRegistered)
          .put("phoneClockDispatched", a.phoneClockDispatched)
          .put("phoneClockPackage", a.phoneClockPackage)
          .put("status", a.status)
          .put("createdAtMs", a.createdAtMs)
      )
    }
    ctx.applicationContext
      .getSharedPreferences(ALARM_PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_SCHEDULED_ALARMS, arr.toString())
      .commit()
  }

  @Synchronized
  fun markAlarmStatus(ctx: Context, alarmId: String, newStatus: String) {
    if (alarmId.isBlank()) return
    val list = readPersistedAlarmsLocked(ctx)
    var changed = false
    val updated =
      list.map { item ->
        if (item.id == alarmId) {
          changed = true
          item.copy(status = newStatus)
        } else {
          item
        }
      }
    if (changed) writePersistedAlarmsLocked(ctx, updated)
  }

  @Synchronized
  fun listPersistedAlarms(ctx: Context): List<ScheduledPhoneAlarm> = readPersistedAlarmsLocked(ctx)

  private fun scheduleSystemAlarmClockOrExact(
    ctx: Context,
    alarmMgr: AlarmManager,
    alarmId: String,
    reqCode: Int,
    title: String,
    triggerAtEpochMs: Long,
    isExactAllowed: Boolean,
  ): Boolean {
    val triggerIntent =
      Intent(ctx.applicationContext, LxrReminderReceiver::class.java)
        .setAction(LxrReminderReceiver.ACTION_ALARM_TRIGGER)
        .putExtra(LxrReminderReceiver.EXTRA_REMINDER_TEXT, title)
        .putExtra(LxrReminderReceiver.EXTRA_ALARM_ID, alarmId)
        .putExtra(LxrReminderReceiver.EXTRA_NOTIF_ID, reqCode)
    val triggerPi =
      PendingIntent.getBroadcast(
        ctx.applicationContext,
        reqCode,
        triggerIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val showIntent =
      Intent(ctx.applicationContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val showPi =
      PendingIntent.getActivity(
        ctx.applicationContext,
        reqCode + 10_000,
        showIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    return if (isExactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      runCatching {
        alarmMgr.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtEpochMs, showPi), triggerPi)
        true
      }.getOrElse {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, triggerPi)
        } else {
          alarmMgr.setExact(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, triggerPi)
        }
        true
      }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, triggerPi)
      false
    } else {
      alarmMgr.set(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, triggerPi)
      false
    }
  }

  @Synchronized
  fun reschedulePersistedAlarms(ctx: Context): Int {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return 0
    val nowMs = System.currentTimeMillis()
    val exact = canScheduleExactAlarms(ctx)
    val list = readPersistedAlarmsLocked(ctx)
    var count = 0
    val updated =
      list.map { item ->
        if (item.status == "SCHEDULED" && item.triggerAtEpochMs > nowMs + 2_000L) {
          val okClock =
            scheduleSystemAlarmClockOrExact(
              ctx = ctx,
              alarmMgr = am,
              alarmId = item.id,
              reqCode = item.reqCode,
              title = item.title,
              triggerAtEpochMs = item.triggerAtEpochMs,
              isExactAllowed = exact,
            )
          count++
          item.copy(exactScheduled = exact, alarmClockRegistered = okClock)
        } else {
          item
        }
      }
    writePersistedAlarmsLocked(ctx, updated)
    return count
  }

  @JvmOverloads
  fun scheduleExactReminder(
    ctx: Context,
    title: String,
    delaySeconds: Long = 60L,
    hour: Int? = null,
    minute: Int? = null,
    mode: String = "auto",
  ): String {
    val cleanTitle = title.trim().ifBlank { "বসের নির্ধারিত অ্যালার্ম" }
    val cleanMode = mode.trim().lowercase().ifBlank { "auto" }
    val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
      ?: return "অ্যালার্ম সার্ভিস পাওয়া যায়নি বস।"

    val nowMs = System.currentTimeMillis()
    val effectiveHour = hour?.takeIf { it in 0..23 }
    val effectiveMin = minute?.takeIf { it in 0..59 }
    val (triggerAtEpochMs, targetHour, targetMinute) =
      computeTargetAlarmTrigger(
        delaySeconds = delaySeconds,
        hour = effectiveHour,
        minute = effectiveMin,
        nowMs = nowMs,
      )
    val computedDelaySec = ((triggerAtEpochMs - nowMs) / 1000L).coerceAtLeast(1L)
    val reqCode = ((nowMs % 40_000L).toInt() + 7100)
    val alarmId = "lxr_alm_${nowMs}_$reqCode"
    val isExact = canScheduleExactAlarms(ctx)

    // 1. Register genuine OS AlarmClock + WakeLock receiver (fires Alarm ringtone, vibration & full-screen alert)
    val alarmClockRegistered =
      scheduleSystemAlarmClockOrExact(
        ctx = ctx,
        alarmMgr = alarm,
        alarmId = alarmId,
        reqCode = reqCode,
        title = cleanTitle,
        triggerAtEpochMs = triggerAtEpochMs,
        isExactAllowed = isExact,
      )

    val verifiedNextOsAlarmMs =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        runCatching { alarm.nextAlarmClock?.triggerTime ?: 0L }.getOrDefault(0L)
      } else 0L

    // 2. Dispatch directly to phone's native Clock app (AlarmClock.ACTION_SET_ALARM / ACTION_SET_TIMER) when applicable
    val useTimerAction = cleanMode == "timer"
    val useClockAlarmAction =
      cleanMode == "alarm" ||
        effectiveHour != null ||
        (cleanMode == "auto" && computedDelaySec >= 60L)

    var clockDispatched = false
    var clockPackage = ""
    if (useTimerAction || useClockAlarmAction) {
      val clockAction = if (useTimerAction) AlarmClock.ACTION_SET_TIMER else AlarmClock.ACTION_SET_ALARM
      clockPackage = resolvePhoneClockPackage(ctx, clockAction).orEmpty()
      val clockIntent =
        if (useTimerAction) {
          Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, computedDelaySec.toInt().coerceIn(5, 86_400))
            .putExtra(AlarmClock.EXTRA_MESSAGE, cleanTitle)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        } else {
          Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, targetHour)
            .putExtra(AlarmClock.EXTRA_MINUTES, targetMinute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, cleanTitle)
            .putExtra(AlarmClock.EXTRA_VIBRATE, true)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }.apply {
          if (clockPackage.isNotBlank()) `package` = clockPackage
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      val hasHandler = clockIntent.resolveActivity(ctx.packageManager) != null
      if (hasHandler || clockPackage.isNotBlank()) {
        clockDispatched =
          LxrAppDiscoveryEngine.launchActivitySafely(
            ctx,
            RiyaAccessibilityService.instance,
            clockIntent,
          )
      }
    }

    val record =
      ScheduledPhoneAlarm(
        id = alarmId,
        reqCode = reqCode,
        title = cleanTitle,
        mode = cleanMode,
        triggerAtEpochMs = triggerAtEpochMs,
        hour = targetHour,
        minute = targetMinute,
        delaySeconds = computedDelaySec,
        exactScheduled = isExact,
        alarmClockRegistered = alarmClockRegistered && isExact,
        phoneClockDispatched = clockDispatched,
        phoneClockPackage = clockPackage,
        status = "SCHEDULED",
        createdAtMs = nowMs,
      )
    val existing = readPersistedAlarmsLocked(ctx)
    existing.add(0, record)
    writePersistedAlarmsLocked(ctx, existing)

    val formattedTime =
      SimpleDateFormat("dd MMM, hh:mm:ss a", Locale("bn", "BD")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Dhaka")
      }.format(Date(triggerAtEpochMs))

    com.example.data.Store.remember(
      ctx,
      "[ফোন অ্যালার্ম/রিমাইন্ডার সেট]: '$cleanTitle' -> $formattedTime (Clock=$clockDispatched:$clockPackage, OSNextAlarm=${verifiedNextOsAlarmMs > 0L}, Exact=$isExact)",
    )

    return buildString {
      if (isExact && alarmClockRegistered) {
        append("হয়েছে, বস! '$cleanTitle' সরাসরি ফোনের সিস্টেম অ্যালার্ম হিসেবে সেট করেছি ✓\n")
      } else {
        append("'$cleanTitle' অ্যালার্ম/রিমাইন্ডার কিউ করেছি বস, তবে LXR--AI এর 'Alarms & reminders' অনুমতি এখন OFF থাকায় OS-level exact wake পূর্ণ করতে সেটিংস Allow করা দরকার!\n")
      }
      append("• বাজবে: $formattedTime (${computedDelaySec} সেকেন্ড পর)\n")
      append("• Android AlarmClock নিবন্ধন: ${if (verifiedNextOsAlarmMs > 0L) "নিশ্চিত ✓ (OS সিস্টেম অ্যালার্ম আইকন সক্রিয়)" else if (alarmClockRegistered) "সিডিউলড ✓" else "অপেক্ষমাণ"}\n")
      if (useTimerAction || useClockAlarmAction) {
        append(
          if (clockDispatched) "• ফোনের Clock অ্যাপ ($clockPackage): সরাসরি ${String.format(Locale.US, "%02d:%02d", targetHour, targetMinute)}-এ পাঠানো হয়েছে ✓"
          else "• ফোনের Clock অ্যাপ: আলাদা ইনটেন্ট হ্যান্ডলার না মেলায় LXR অ্যালার্ম ইঞ্জিন নিজে রিংটোন+ভাইব্রেশন+লকস্ক্রিন অ্যালার্ট বাজাবে।"
        )
      } else {
        append("• অ্যালার্ট মাধ্যম: নির্দিষ্ট সময়ে ফোনের রিংটোন, ভাইব্রেশন, লকস্ক্রিন ফুল-অ্যালার্ট ও ভয়েস ঘোষণা সরাসরি বাজবে।")
      }
    }.trim()
  }

  fun listScheduledAlarmsReport(ctx: Context): String {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    val nextClock =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        runCatching { am?.nextAlarmClock?.triggerTime }.getOrNull()
      } else null
    val exactOn = canScheduleExactAlarms(ctx)
    val clockPkg = resolvePhoneClockPackage(ctx, AlarmClock.ACTION_SET_ALARM) ?: "পাওয়া যায়নি"
    val df =
      SimpleDateFormat("dd MMM, hh:mm:ss a", Locale("bn", "BD")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Dhaka")
      }
    val items = listPersistedAlarms(ctx)
    return buildString {
      appendLine("⏰ ফোনের আসল অ্যালার্ম ও রিমাইন্ডার যাচাই রিপোর্ট:")
      appendLine("• Alarms & reminders (Exact Alarm) অনুমতি: ${if (exactOn) "চালু ✓" else "বন্ধ (সেটিংসে Allow দিন)"}")
      appendLine("• ফোনের Clock অ্যাপ: $clockPkg")
      appendLine("• Android OS AlarmManager nextAlarmClock: ${if (nextClock != null && nextClock > 0L) df.format(Date(nextClock)) + " ✓" else "কোনো সিস্টেম অ্যালার্ম নির্ধারিত নেই"}")
      if (items.isEmpty()) {
        append("• LXR সংরক্ষিত অ্যালার্ম তালিকা: এখনো কোনো অ্যালার্ম সেট করা হয়নি।")
      } else {
        appendLine("• সাম্প্রতিক অ্যালার্মসমূহ (${items.size}টি):")
        items.take(8).forEachIndexed { idx, a ->
          val clockTag = if (a.phoneClockDispatched) "Clock+OS" else "LXR AlarmClock"
          appendLine("  ${idx + 1}. ${a.title} | ${df.format(Date(a.triggerAtEpochMs))} | $clockTag | অবস্থা: ${a.status}")
        }
      }
    }.trim()
  }

  @Synchronized
  @JvmOverloads
  fun dismissOrCancelAlarm(
    ctx: Context,
    acc: RiyaAccessibilityService? = RiyaAccessibilityService.instance,
    titleQuery: String = "",
    hour: Int? = null,
    minute: Int? = null,
    dismissAll: Boolean = false,
  ): String {
    val cleanQuery = titleQuery.trim()
    val validHour = hour?.takeIf { it in 0..23 }
    val validMinute = minute?.takeIf { it in 0..59 }
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager

    // 1. Stop any active ringing alarm tone, vibration, or urgent voice announcement immediately
    LxrAlarmAlertActivity.stopActiveRingtone()
    RiyaVoiceManager.stopSpeaking()

    val persisted = readPersistedAlarmsLocked(ctx)
    val activeStatuses = setOf("SCHEDULED", "FIRED")
    val matchedIds = mutableSetOf<String>()
    val cancelledDescriptions = mutableListOf<String>()

    val candidateItems = persisted.filter { it.status in activeStatuses }
    val targetItems =
      when {
        dismissAll -> candidateItems
        validHour != null ->
          candidateItems.filter { item ->
            item.hour == validHour && (validMinute == null || item.minute == validMinute)
          }
        cleanQuery.isNotBlank() ->
          candidateItems.filter { item ->
            item.title.contains(cleanQuery, ignoreCase = true)
          }
        else -> {
          val nextOrFired =
            candidateItems
              .sortedBy { it.triggerAtEpochMs }
              .firstOrNull()
          listOfNotNull(nextOrFired)
        }
      }

    for (item in targetItems) {
      matchedIds.add(item.id)
      val triggerIntent =
        Intent(ctx.applicationContext, LxrReminderReceiver::class.java)
          .setAction(LxrReminderReceiver.ACTION_ALARM_TRIGGER)
      val existingPi =
        PendingIntent.getBroadcast(
          ctx.applicationContext,
          item.reqCode,
          triggerIntent,
          PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
          ?: PendingIntent.getBroadcast(
            ctx.applicationContext,
            item.reqCode,
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
      if (am != null) {
        runCatching { am.cancel(existingPi) }
      }
      runCatching { existingPi.cancel() }
      runCatching { nm?.cancel(item.reqCode) }
      cancelledDescriptions.add(
        "${item.title} (${String.format(Locale.US, "%02d:%02d", item.hour, item.minute)})"
      )
    }

    if (matchedIds.isNotEmpty()) {
      val updated =
        persisted.map { item ->
          if (item.id in matchedIds) item.copy(status = "DISMISSED") else item
        }
      writePersistedAlarmsLocked(ctx, updated)
    }

    // 2. Also send AlarmClock.ACTION_DISMISS_ALARM to phone's native Clock app
    val clockPkg = resolvePhoneClockPackage(ctx, AlarmClock.ACTION_DISMISS_ALARM).orEmpty()
    var clockDismissDispatched = false
    val dismissIntent =
      Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
        if (clockPkg.isNotBlank()) `package` = clockPkg
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        when {
          dismissAll -> putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
          validHour != null -> {
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            putExtra(AlarmClock.EXTRA_HOUR, validHour)
            putExtra(AlarmClock.EXTRA_MINUTES, validMinute ?: 0)
          }
          cleanQuery.isNotBlank() -> {
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
            putExtra(AlarmClock.EXTRA_MESSAGE, cleanQuery)
          }
          else -> putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    val hasClockDismissHandler = dismissIntent.resolveActivity(ctx.packageManager) != null
    if (hasClockDismissHandler || clockPkg.isNotBlank()) {
      clockDismissDispatched =
        LxrAppDiscoveryEngine.launchActivitySafely(
          ctx,
          acc ?: RiyaAccessibilityService.instance,
          dismissIntent,
        )
    }

    val remainingNextOsAlarmMs =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        runCatching { am?.nextAlarmClock?.triggerTime ?: 0L }.getOrDefault(0L)
      } else 0L

    com.example.data.Store.remember(
      ctx,
      "[ফোন অ্যালার্ম বন্ধ]: count=${matchedIds.size}, query='$cleanQuery', time=${validHour ?: -1}:${validMinute ?: -1}, ClockDismiss=$clockDismissDispatched:$clockPkg",
    )

    return buildString {
      if (matchedIds.isNotEmpty() || clockDismissDispatched) {
        append("হয়েছে, বস! সরাসরি ফোনের অ্যালার্ম বন্ধ/বাতিল করেছি ✓\n")
      } else {
        append("বস, সক্রিয় রিংটোন থামিয়েছি এবং অ্যালার্ম তালিকা পরীক্ষা করেছি।\n")
      }
      if (cancelledDescriptions.isNotEmpty()) {
        append("• বন্ধ করা LXR/OS অ্যালার্ম (${cancelledDescriptions.size}টি): ${cancelledDescriptions.joinToString(", ")}\n")
      } else {
        append("• LXR শিডিউলার তালিকায় মিল থাকা সক্রিয় অ্যালার্ম ছিল না।\n")
      }
      append(
        if (clockDismissDispatched) "• ফোনের Clock অ্যাপ ($clockPkg): সরাসরি ACTION_DISMISS_ALARM পাঠানো হয়েছে ✓\n"
        else "• ফোনের Clock অ্যাপ: আলাদা ডিসমিস হ্যান্ডলার না মেলায় সিস্টেম অ্যালার্ম/রিংটোন সরাসরি বন্ধ করা হয়েছে।\n"
      )
      append(
        if (remainingNextOsAlarmMs > 0L) "• পরবর্তী সক্রিয় OS AlarmClock: চালু আছে"
        else "• Android OS AlarmClock: এই মুহূর্তে আর কোনো নির্ধারিত সিস্টেম অ্যালার্ম নেই ✓"
      )
    }.trim()
  }

  fun solveCriticalTaskWithExtraModel(ctx: Context, taskPrompt: String, screenContext: String = ""): String {
    val apiKey = com.example.data.Store.apiKey(ctx).trim()
    if (apiKey.isBlank()) {
      return "এক্সট্রা এআই মডেল চালানোর জন্য Gemini API Key প্রয়োজন বস।"
    }
    val preferred = com.example.data.Store.extraCriticalModel(ctx)
    val candidates =
      listOf(preferred, "gemini-2.5-flash", "gemini-2.0-flash").distinct()
    val client =
      okhttp3.OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(28, TimeUnit.SECONDS)
        .build()
    val promptBody =
      buildString {
        appendLine("তুমি LXR--AI-এর এক্সট্রা ক্রিটিক্যাল রিজনিং ও টাস্ক ইঞ্জিন। বসের কঠিন বা জটিল কাজের জন্য পরিষ্কার, সঠিক এবং কার্যকর বাংলা সমাধান ও ধাপ দাও।")
        if (screenContext.isNotBlank()) {
          appendLine("বর্তমান স্ক্রিন/সিস্টেম তথ্য: $screenContext")
        }
        appendLine("কাজ/প্রশ্ন: $taskPrompt")
      }
    for (modelId in candidates) {
      try {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey"
        val bodyJson =
          org.json.JSONObject()
            .put(
              "contents",
              org.json.JSONArray()
                .put(
                  org.json.JSONObject()
                    .put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", promptBody)))
                ),
            )
        val req =
          okhttp3.Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
          val raw = resp.body?.string().orEmpty()
          if (resp.isSuccessful && raw.isNotBlank()) {
            val json = org.json.JSONObject(raw)
            val txt =
              json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
                .trim()
            if (txt.isNotBlank()) {
              return "[$modelId ক্রিটিক্যাল মডেল]:\n$txt"
            }
          }
        }
      } catch (_: Throwable) {}
    }
    return "এক্সট্রা ক্রিটিক্যাল মডেল এই মুহূর্তে নেটওয়ার্ক থেকে সাড়া দিতে পারেনি বস।"
  }

  fun selectBestLocation(candidates: List<Location>): Location? {
    var best: Location? = null
    for (cand in candidates) {
      val cur = best
      if (cur == null) {
        best = cand
        continue
      }
      val timeDelta = cand.time - cur.time
      val significantlyNewer = timeDelta > 120_000L
      val significantlyOlder = timeDelta < -120_000L
      if (significantlyNewer) {
        best = cand
      } else if (!significantlyOlder) {
        val accBetter =
          if (cand.hasAccuracy() && cur.hasAccuracy()) cand.accuracy < cur.accuracy
          else cand.hasAccuracy() && !cur.hasAccuracy()
        if (accBetter || (cand.accuracy == cur.accuracy && cand.time > cur.time)) {
          best = cand
        }
      }
    }
    return best
  }

  @Suppress("MissingPermission", "DEPRECATION")
  fun getLiveLocationReport(ctx: Context): String {
    if (!hasLocationPermission(ctx)) {
      return "বস, ফোনের লাইভ লোকেশন দেখতে Location (ACCESS_FINE_LOCATION) পারমিশন প্রয়োজন। Menu > Permissions থেকে Location চালু করে দাও, অথবা অনুমতি পপ-আপে Allow দাও।"
    }
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val locEnabled = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    if (!locEnabled) {
      return "ফোনের GPS / Location সুইচ বন্ধ আছে বস! বললে আমি এখনই Location চালু করে তোমার বর্তমান ঠিকানা বের করে দিচ্ছি।"
    }

    val candidates = mutableListOf<Location>()
    val enabledProviders = lm.getProviders(true)
    for (provider in enabledProviders) {
      runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.let { candidates.add(it) }
    }
    var best = selectBestLocation(candidates)
    val now = System.currentTimeMillis()
    val needsFreshFix = best == null || (best.time > 0L && (now - best.time) > 120_000L)

    if (Build.VERSION.SDK_INT >= 30 && needsFreshFix && !Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
      val preferredProvider =
        listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
          .firstOrNull { it in enabledProviders }
      if (preferredProvider != null) {
        runCatching {
          val latch = CountDownLatch(1)
          val cancelSignal = CancellationSignal()
          lm.getCurrentLocation(preferredProvider, cancelSignal, ctx.mainExecutor) { fresh ->
            if (fresh != null) candidates.add(fresh)
            latch.countDown()
          }
          if (!latch.await(2600L, TimeUnit.MILLISECONDS)) {
            cancelSignal.cancel()
          }
        }
        best = selectBestLocation(candidates)
      }
    }

    if (best == null) {
      return "GPS সংযোগ নিচ্ছে বস — খোলা জায়গা বা জানালার পাশে কয়েক সেকেন্ড ধরে থাকলে তোমার সঠিক লাইভ লোকেশন পাওয়া যাবে (Google Maps দিয়েও চেক করতে পারো)।"
    }

    val addr = reverseGeocodeAddressObject(ctx, best.latitude, best.longitude)
    val addressLine = addr?.let { formatAddress(it) }
    val areaDistrict =
      addr?.let { a ->
        listOfNotNull(
          a.subLocality?.takeIf { it.isNotBlank() },
          a.locality?.takeIf { it.isNotBlank() },
          a.subAdminArea?.takeIf { it.isNotBlank() },
          a.adminArea?.takeIf { it.isNotBlank() },
        ).distinct().joinToString(", ").ifBlank { null }
      }
    val country = addr?.countryName?.takeIf { it.isNotBlank() }
    val streetPlace =
      addr?.let { a ->
        listOfNotNull(
          a.featureName?.takeIf { it.isNotBlank() && it != a.locality },
          a.thoroughfare?.takeIf { it.isNotBlank() },
        ).distinct().joinToString(", ").ifBlank { null }
      }
    val capturedAt =
      SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale("bn", "BD")).format(Date(best.time.takeIf { it > 0L } ?: System.currentTimeMillis()))
    val mapsUrl = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", best.latitude, best.longitude)
    val accM = best.accuracy.roundToInt()
    return buildString {
      appendLine("📍 তোমার বর্তমান লাইভ লোকেশন:")
      if (!addressLine.isNullOrBlank()) {
        appendLine("• পূর্ণ ঠিকানা: $addressLine")
      }
      if (!streetPlace.isNullOrBlank()) {
        appendLine("• স্থান ও সড়ক: $streetPlace")
      }
      if (!areaDistrict.isNullOrBlank()) {
        appendLine("• এলাকা / জেলা: $areaDistrict")
      }
      if (!country.isNullOrBlank()) {
        appendLine("• দেশ: $country")
      }
      appendLine(String.format(Locale.US, "• স্থানাঙ্ক: %.6f, %.6f (নির্ভুলতা ~%d মিটার)", best.latitude, best.longitude, accM))
      appendLine("• লোকেশন সময়: $capturedAt")
      appendLine("• ম্যাপ লিংক: $mapsUrl")
    }.trim()
  }

  @Suppress("DEPRECATION")
  private fun reverseGeocodeAddressObject(ctx: Context, lat: Double, lon: Double): Address? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(ctx, Locale("bn", "BD"))
    return runCatching {
      if (Build.VERSION.SDK_INT >= 33) {
        var result: List<Address>? = null
        val latch = CountDownLatch(1)
        geocoder.getFromLocation(lat, lon, 1) { list ->
          result = list
          latch.countDown()
        }
        latch.await(2200L, TimeUnit.MILLISECONDS)
        result?.firstOrNull()
      } else {
        geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
      }
    }.getOrNull()
  }

  private fun formatAddress(a: Address): String {
    val line0 = a.getAddressLine(0)
    if (!line0.isNullOrBlank()) return line0
    return listOfNotNull(a.featureName, a.thoroughfare, a.subLocality, a.locality, a.subAdminArea, a.adminArea, a.countryName)
      .distinct()
      .joinToString(", ")
  }

  fun openNavigationDirections(ctx: Context, destination: String, travelMode: String = "driving"): String {
    val cleanDest = destination.trim()
    if (cleanDest.isBlank()) return "কোন জায়গায় যেতে চান বলোনি বস।"
    val navIntent =
      Intent(Intent.ACTION_VIEW, buildNavigationUri(cleanDest, travelMode)).apply {
        setPackage("com.google.android.apps.maps")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return try {
      ctx.startActivity(navIntent)
      "হয়েছে, বস! '$cleanDest'-এর দিকে যাওয়ার লাইভ Google Maps নেভিগেশন ও দিকনির্দেশনা চালু করেছি।"
    } catch (_: Exception) {
      val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(cleanDest)}")
      ctx.startActivity(Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      "'$cleanDest'-এর দিকনির্দেশনা ব্রাউজার ম্যাপে খুলে দিয়েছি বস।"
    }
  }

  fun getContactsSummary(ctx: Context, sampleLimit: Int = 8): String {
    val hasPerm =
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPerm) {
      return "বস, কন্টাক্ট তালিকা পড়ার অনুমতি (READ_CONTACTS) চালু নেই।"
    }
    var distinctContacts = 0
    runCatching {
      ctx.contentResolver.query(
        ContactsContract.Contacts.CONTENT_URI,
        arrayOf(ContactsContract.Contacts._ID),
        null,
        null,
        null,
      )?.use { c -> distinctContacts = c.count }
    }
    val numbers = mutableListOf<Pair<String, String>>()
    var totalPhoneRows = 0
    runCatching {
      ctx.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
          ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
          ContactsContract.CommonDataKinds.Phone.NUMBER,
        ),
        null,
        null,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
      )?.use { c ->
        totalPhoneRows = c.count
        while (c.moveToNext() && numbers.size < sampleLimit.coerceAtLeast(1)) {
          val n = c.getString(0)?.trim().orEmpty()
          val p = c.getString(1)?.trim().orEmpty()
          if (n.isNotBlank() && p.isNotBlank()) {
            numbers.add(n to p)
          }
        }
      }
    }
    return buildString {
      appendLine("📇 ফোনের কন্টাক্ট পরিসংখ্যান:")
      appendLine("• মোট সেভ করা কন্টাক্ট (নাম): $distinctContacts টি")
      appendLine("• মোট ফোন নম্বর এন্ট্রি: $totalPhoneRows টি")
      if (numbers.isNotEmpty()) {
        appendLine("• উদাহরণ (প্রথম ${numbers.size}টি):")
        numbers.forEachIndexed { idx, (name, phone) ->
          appendLine("  ${idx + 1}. $name: $phone")
        }
      }
    }.trim()
  }

  @Suppress("MissingPermission")
  fun getCallLogSummary(ctx: Context, filterType: String = "all", limit: Int = 10): String {
    val hasPerm =
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPerm) {
      return "বস, ফোনের কল লগ (Missed / Incoming / Outgoing) পড়তে READ_CALL_LOG পারমিশন প্রয়োজন। Menu > Permissions থেকে Call Logs অনুমতি চালু করে দিন।"
    }
    val fLow = filterType.trim().lowercase()
    val wantType: Int? =
      when {
        fLow.contains("miss") || fLow.contains("মিসড") -> CallLog.Calls.MISSED_TYPE
        fLow.contains("in") || fLow.contains("আসা") -> CallLog.Calls.INCOMING_TYPE
        fLow.contains("out") || fLow.contains("করা") -> CallLog.Calls.OUTGOING_TYPE
        else -> null
      }
    val sel = if (wantType != null) "${CallLog.Calls.TYPE} = ?" else null
    val selArgs = if (wantType != null) arrayOf(wantType.toString()) else null
    val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale("bn", "BD"))

    val rows = mutableListOf<String>()
    var incomingCnt = 0
    var outgoingCnt = 0
    var missedCnt = 0

    runCatching {
      ctx.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(
          CallLog.Calls.CACHED_NAME,
          CallLog.Calls.NUMBER,
          CallLog.Calls.TYPE,
          CallLog.Calls.DATE,
          CallLog.Calls.DURATION,
        ),
        sel,
        selArgs,
        "${CallLog.Calls.DATE} DESC",
      )?.use { c ->
        while (c.moveToNext()) {
          val name = c.getString(0)?.trim().orEmpty()
          val num = c.getString(1)?.trim().orEmpty().ifBlank { "অজানা নম্বর" }
          val type = c.getInt(2)
          val dateMs = c.getLong(3)
          val durSec = c.getLong(4)

          val typeLabel =
            when (type) {
              CallLog.Calls.INCOMING_TYPE -> {
                incomingCnt++
                "আসা কল (Incoming)"
              }
              CallLog.Calls.OUTGOING_TYPE -> {
                outgoingCnt++
                "করা কল (Outgoing)"
              }
              CallLog.Calls.MISSED_TYPE -> {
                missedCnt++
                "মিসড কল (Missed)"
              }
              CallLog.Calls.REJECTED_TYPE -> "রিজেক্টেড কল"
              else -> "কল"
            }
          if (rows.size < limit.coerceIn(1, 25)) {
            val who = if (name.isNotBlank()) "$name ($num)" else num
            val whenStr = fmt.format(Date(dateMs))
            val durStr = if (durSec > 0) " • ${durSec}s" else ""
            rows.add("${rows.size + 1}. [$typeLabel] $who — $whenStr$durStr")
          } else if (rows.size >= 50) {
            break
          }
        }
      }
    }

    if (rows.isEmpty()) {
      return "ফোনের কল লগে এই ক্যাটাগরিতে কোনো সাম্প্রতিক কল পাওয়া যায়নি বস।"
    }
    return buildString {
      appendLine("📞 ফোনের সাম্প্রতিক কল লগ:")
      appendLine("• হিসাব (সাম্প্রতিক): আসা কল $incomingCnt | করা কল $outgoingCnt | মিসড কল $missedCnt")
      rows.forEach { appendLine(it) }
    }.trim()
  }
}
