package com.example.service

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
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
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

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
        val want = enabled ?: true
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val curOn = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (curOn == want) return "ফোনের Location/GPS আগেই ${if (want) "চালু" else "বন্ধ"} আছে বস।"
        if (acc != null) {
          val qs = acc.toggleQuickSettingTile(want, listOf("location", "gps", "লোকেশন"), hardwareStateKnown = curOn)
          if (qs == "OK" || qs == "ALREADY") {
            return "হয়েছে, বস! ফোনের Location/GPS ${if (want) "চালু" else "বন্ধ"} করেছি।"
          }
        }
        ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Location সেটিংস স্ক্রিন খুলেছি বস, সুইচটি চালু/বন্ধ করে নিন।"
      }

      else -> "বস, '$settingName' সেটিংসটি সিস্টেম সেটিংস থেকে দেখে নিচ্ছি।"
    }
  }

  @Suppress("MissingPermission", "DEPRECATION")
  fun getLiveLocationReport(ctx: Context): String {
    if (!hasLocationPermission(ctx)) {
      return "বস, ফোনের লাইভ লোকেশন দেখতে Location (ACCESS_FINE_LOCATION) পারমিশন প্রয়োজন। Menu > Permissions থেকে Location চালু করে দিন, অথবা অনুমতি পপ-আপে Allow দিন।"
    }
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val locEnabled = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    if (!locEnabled) {
      return "ফোনের GPS / Location সুইচ বন্ধ আছে বস! বললে আমি এখনই Location চালু করে বর্তমান ঠিকানা বের করে দিচ্ছি।"
    }

    var best: Location? = null
    for (provider in lm.getProviders(true)) {
      val cand = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
      if (best == null || cand.accuracy < best.accuracy || cand.time > best.time + 120_000L) {
        best = cand
      }
    }

    if (best == null) {
      return "GPS সংযোগ নিচ্ছে বস — খোলা জায়গা বা জানালায় কয়েক সেকেন্ড ধরে থাকলে আপনার সঠিক লাইভ লোকেশন পাওয়া যাবে (Google Maps দিয়েও চেক করতে পারেন)।"
    }

    val addressLine = reverseGeocodeAddress(ctx, best.latitude, best.longitude)
    val mapsUrl = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", best.latitude, best.longitude)
    val accM = best.accuracy.roundToInt()
    return buildString {
      appendLine("📍 আপনার বর্তমান লাইভ লোকেশন:")
      if (!addressLine.isNullOrBlank()) {
        appendLine("• ঠিকানা: $addressLine")
      }
      appendLine(String.format(Locale.US, "• স্থানাঙ্ক: %.6f, %.6f (নির্ভুলতা ~%d মিটার)", best.latitude, best.longitude, accM))
      appendLine("• ম্যাপ লিংক: $mapsUrl")
    }.trim()
  }

  @Suppress("DEPRECATION")
  private fun reverseGeocodeAddress(ctx: Context, lat: Double, lon: Double): String? {
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
        result?.firstOrNull()?.let { formatAddress(it) }
      } else {
        geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()?.let { formatAddress(it) }
      }
    }.getOrNull()
  }

  private fun formatAddress(a: Address): String {
    val line0 = a.getAddressLine(0)
    if (!line0.isNullOrBlank()) return line0
    return listOfNotNull(a.subLocality, a.locality, a.subAdminArea, a.adminArea, a.countryName)
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
