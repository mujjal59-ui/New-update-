package com.example.service

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import com.example.data.RiyaState
import com.example.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveObservationSnapshot(
  val foregroundPackage: String = "",
  val openChatName: String? = null,
  val lockPhase: LivePhoneLockPhase = LivePhoneLockPhase.UNLOCKED,
  val isLocked: Boolean = false,
  val lockSummaryBn: String = "",
  val accessibilityOn: Boolean = false,
  val notificationListenerOn: Boolean = false,
  val batteryPct: Int = -1,
  val isCharging: Boolean = false,
  val wifiOn: Boolean = false,
  val bluetoothOn: Boolean = false,
  val soundModeLabel: String = "NORMAL",
  val flashlightOn: Boolean = false,
  val dhakaTimeBn: String = "",
  val dhakaDateBn: String = "",
  val dhakaDayBn: String = "",
  val pendingMessagesCount: Int = 0,
  val recentVoiceCallAlert: String? = null,
  val observedAtMs: Long = 0L,
) {
  fun toCompactContextBn(): String =
    buildString {
      append("অ্যাপ: ${foregroundPackage.ifBlank { "অজানা" }}")
      if (!openChatName.isNullOrBlank()) append(" | খোলা চ্যাট: $openChatName")
      if (isLocked) append(" | লক অবস্থা: ${lockPhase.name}")
      append(" | ব্যাটারি: ${if (batteryPct >= 0) "$batteryPct%" else "N/A"}${if (isCharging) "(Charging)" else ""}")
      append(" | Wi-Fi: ${if (wifiOn) "ON" else "OFF"}")
      append(" | BT: ${if (bluetoothOn) "ON" else "OFF"}")
      append(" | Sound: $soundModeLabel")
      append(" | Accessibility: ${if (accessibilityOn) "ON" else "OFF"}")
      append(" | বাংলাদেশ সময়: $dhakaDayBn, $dhakaDateBn, $dhakaTimeBn")
      if (!recentVoiceCallAlert.isNullOrBlank()) {
        append(" | কল অ্যালার্ট: $recentVoiceCallAlert")
      }
    }
}

data class ContactLookupMatch(
  val displayName: String,
  val phoneNumber: String,
  val score: Int,
  val matchedByFirstWord: Boolean,
)

object NetworkStabilityGuard {
  fun hasValidatedInternet(cm: ConnectivityManager): Boolean {
    return try {
      val active = cm.activeNetwork ?: return false
      val caps = cm.getNetworkCapabilities(active) ?: return false
      val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      val validated =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
          true
        }
      hasInternet && validated
    } catch (_: Throwable) {
      false
    }
  }

  /** Only report offline disconnect if the phone has no other validated internet interface left. */
  fun shouldTreatNetworkLossAsOffline(hasRemainingValidatedInternet: Boolean): Boolean =
    !hasRemainingValidatedInternet

  /** Prevents false "পুনরায় সংযোগ হচ্ছে…" interruptions on healthy idle or thinking WebSockets. */
  fun shouldWatchdogReconnect(
    waitingServerReply: Boolean,
    bossTurnElapsedMs: Long,
    serverSilenceMs: Long,
    queueBytes: Long,
  ): Boolean {
    if (queueBytes > 1_200_000L) return true
    return waitingServerReply && bossTurnElapsedMs > 45_000L && serverSilenceMs > 35_000L
  }

  /** Prevents sending empty audioStreamEnd flushes when Voice Match discarded ambient noise or short blips. */
  fun shouldIgnoreEmptyTurnFlush(sentSpeechChunksThisTurn: Int): Boolean =
    sentSpeechChunksThisTurn < 2
}

object UnlockPolicyGuard {
  private val PASSIVE_OR_READONLY_TOOLS =
    setOf(
      "current_time",
      "remember",
      "search_memory",
      "web_search",
      "deep_research",
      "get_battery_level",
      "get_capabilities_a_to_z",
      "prepare_app_requirements",
      "build_android_app",
      "check_termux_build_status",
      "show_wake_orb",
      "lock_phone",
      "unlock_phone",
      "read_screen",
      "current_screen",
      "check_notifications",
      "search_contact",
      "set_sound_mode",
      "media_control",
      "toggle_flashlight",
      "reply_to_incoming_message",
    )

  /**
   * Unlocks the phone ONLY when:
   * 1) Phone main screen is genuinely locked, AND
   * 2) Either Boss gave an explicit physical UI/action command (open_app, send_message, call_contact, etc.)
   *    OR Boss explicitly called `unlock_phone` with explicit=true.
   * Casual conversation and passive inspection tools NEVER auto-unlock the phone.
   */
  fun shouldAttemptAutoUnlockBeforeTool(
    toolName: String,
    genuinelyLocked: Boolean,
    explicitUnlockArg: Boolean = false,
  ): Boolean {
    if (!genuinelyLocked) return false
    if (toolName == "unlock_phone") return explicitUnlockArg
    return toolName !in PASSIVE_OR_READONLY_TOOLS
  }
}

object ContactFirstWordMatcher {
  private val HONORIFIC_TOKENS =
    setOf("md", "md.", "mr", "mr.", "ms", "mrs", "dr", "dr.", "মোঃ", "মোহাম্মদ", "ভাই", "আপু", "স্যার", "বস")

  fun normalizeTokenText(raw: String): String =
    raw.lowercase()
      .replace(Regex("[.,_\\-]+"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()

  fun extractSearchKeywords(query: String): Pair<String, String> {
    val tokens = normalizeTokenText(query).split(" ").filter { it.isNotBlank() }
    if (tokens.isEmpty()) return "" to ""
    val firstRaw = tokens.first()
    val primaryToken =
      tokens.firstOrNull { it !in HONORIFIC_TOKENS && it.length >= 2 } ?: firstRaw
    return firstRaw to primaryToken
  }

  fun scoreCandidate(query: String, displayName: String, phoneNumber: String): Int {
    val cleanQuery = normalizeTokenText(query)
    val cleanName = normalizeTokenText(displayName)
    if (cleanQuery.isBlank() || cleanName.isBlank()) return 0

    val qCompact = cleanQuery.replace(" ", "")
    val nCompact = cleanName.replace(" ", "")
    val qDigits = query.filter { it.isDigit() }
    val nDigits = phoneNumber.filter { it.isDigit() }
    val (firstRaw, primaryFirst) = extractSearchKeywords(query)
    val nameWords = cleanName.split(" ").filter { it.isNotBlank() }
    val namePrimaryFirst = nameWords.firstOrNull { it !in HONORIFIC_TOKENS } ?: nameWords.firstOrNull().orEmpty()

    return when {
      nCompact == qCompact -> 120
      qDigits.length >= 5 && nDigits.contains(qDigits) -> 110
      cleanName.startsWith(cleanQuery) -> 100
      primaryFirst.isNotBlank() && namePrimaryFirst == primaryFirst -> 94
      primaryFirst.isNotBlank() && nameWords.firstOrNull()?.startsWith(primaryFirst) == true -> 90
      primaryFirst.isNotBlank() && namePrimaryFirst.startsWith(primaryFirst) -> 88
      firstRaw.isNotBlank() && nameWords.any { it.startsWith(firstRaw) } -> 80
      cleanName.contains(cleanQuery) -> 70
      primaryFirst.length >= 2 && cleanName.contains(primaryFirst) -> 62
      qCompact.length >= 3 && qCompact.contains(nCompact) -> 50
      else -> 0
    }
  }

  fun rankMatches(query: String, entries: List<Pair<String, String>>, maxResults: Int = 5): List<ContactLookupMatch> {
    val (_, primaryFirst) = extractSearchKeywords(query)
    return entries
      .mapNotNull { (name, num) ->
        val score = scoreCandidate(query, name, num)
        if (score > 0) {
          val nLow = normalizeTokenText(name)
          ContactLookupMatch(
            displayName = name.trim(),
            phoneNumber = num.trim(),
            score = score,
            matchedByFirstWord = primaryFirst.isNotBlank() && nLow.split(" ").any { it.startsWith(primaryFirst) },
          )
        } else {
          null
        }
      }
      .distinctBy { "${it.displayName.lowercase()}|${it.phoneNumber.filter { ch -> ch.isDigit() }}" }
      .sortedWith(compareByDescending<ContactLookupMatch> { it.score }.thenBy { it.displayName.length })
      .take(maxResults)
  }

  fun buildReadBackConfirmationPrompt(query: String, matches: List<ContactLookupMatch>, actionLabelBn: String = "কল"): String {
    if (matches.isEmpty()) {
      return "কন্টাক্ট লিস্টে '$query' নামের প্রথম অংশ দিয়ে সার্চ করেও কোনো নম্বর পাওয়া যায়নি বস।"
    }
    val (_, firstWord) = extractSearchKeywords(query)
    val lines =
      matches.mapIndexed { idx, m ->
        "${idx + 1}. ${m.displayName} (${m.phoneNumber})"
      }.joinToString("\n")
    return "সার্চ কীওয়ার্ড '${firstWord.ifBlank { query }}' অনুযায়ী কন্টাক্ট পাওয়া গেছে:\n$lines\n" +
      "বসকে সঠিক নাম ও নম্বরটি পড়ে শোনাও এবং জিজ্ঞেস করো: \"আমি ${matches.first().displayName} (${matches.first().phoneNumber})-কে $actionLabelBn করবো কি? হ্যাঁ বা ঠিক আছে বলুন।\" — বসের স্পষ্ট 'হ্যাঁ / ঠিক আছে' সম্মতি পাওয়ার পরই confirmed=true দিয়ে $actionLabelBn করবে।"
  }
}

/**
 * Background 0.1s (100ms) lightweight live state & Bangladesh time observer.
 * Runs exclusively off Android's Main UI thread so Jetpack Compose animations remain buttery smooth.
 */
object LxrCompanionAgent {
  private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "Lxr01sObserver").apply { isDaemon = true }
  }
  private val started = AtomicBoolean(false)
  private var tickerFuture: ScheduledFuture<*>? = null

  private val _snapshot = MutableStateFlow(LiveObservationSnapshot())
  val snapshot: StateFlow<LiveObservationSnapshot> = _snapshot.asStateFlow()

  @Volatile private var cachedTorchOn: Boolean = false
  @Volatile private var lastHardwarePollMs: Long = 0L
  @Volatile private var cachedBatteryPct: Int = -1
  @Volatile private var cachedCharging: Boolean = false
  @Volatile private var cachedWifiOn: Boolean = false
  @Volatile private var cachedBtOn: Boolean = false
  @Volatile private var cachedSoundLabel: String = "NORMAL"

  private val bdZone: TimeZone = TimeZone.getTimeZone("Asia/Dhaka")

  fun bangladeshNowSnapshot(nowMs: Long = System.currentTimeMillis()): Triple<String, String, String> {
    val d = Date(nowMs)
    val bnLocale = Locale("bn", "BD")
    val timeFmt = SimpleDateFormat("hh:mm:ss a", bnLocale).apply { timeZone = bdZone }
    val dateFmt = SimpleDateFormat("d MMMM yyyy", bnLocale).apply { timeZone = bdZone }
    val dayFmt = SimpleDateFormat("EEEE", bnLocale).apply { timeZone = bdZone }
    return Triple(timeFmt.format(d), dateFmt.format(d), dayFmt.format(d))
  }

  fun formatBangladeshFullClock(nowMs: Long = System.currentTimeMillis()): String {
    val d = Date(nowMs)
    val bnLocale = Locale("bn", "BD")
    val bn = SimpleDateFormat("EEEE, d MMMM yyyy, hh:mm a", bnLocale).apply { timeZone = bdZone }.format(d)
    val en = SimpleDateFormat("EEEE d MMM yyyy, hh:mm a 'BST'", Locale.US).apply { timeZone = bdZone }.format(d)
    return "বাংলাদেশের বর্তমান সময়: $bn | $en"
  }

  fun notifyTorchState(on: Boolean) {
    cachedTorchOn = on
  }

  fun start(context: Context) {
    val appCtx = context.applicationContext
    if (!started.compareAndSet(false, true)) return
    registerTorchWatcher(appCtx)
    tickerFuture =
      scheduler.scheduleAtFixedRate(
        {
          try {
            sampleOnce(appCtx)
          } catch (_: Throwable) {}
        },
        0L,
        100L, // 0.1 second continuous live background observation
        TimeUnit.MILLISECONDS,
      )
  }

  fun stop() {
    started.set(false)
    tickerFuture?.cancel(false)
    tickerFuture = null
  }

  fun sampleNow(context: Context): LiveObservationSnapshot = sampleOnce(context.applicationContext)

  private fun registerTorchWatcher(ctx: Context) {
    try {
      val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
      cm.registerTorchCallback(
        object : CameraManager.TorchCallback() {
          override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            cachedTorchOn = enabled
          }
        },
        null,
      )
    } catch (_: Throwable) {}
  }

  @Suppress("DEPRECATION")
  private fun sampleOnce(ctx: Context): LiveObservationSnapshot {
    val now = System.currentTimeMillis()
    val acc = RiyaAccessibilityService.instance
    val accOk = Keep.accessibilityOn(ctx)
    val report = LockVaultManager.describeLivePhoneState(ctx, acc)
    val pkg = report.activePackage.ifBlank { RiyaAccessibilityService.currentApp }
    val isMessaging = ChatScreenGeometryParser.isMessagingPackage(pkg)
    val openChat =
      if (!report.isLocked && acc != null && isMessaging) {
        runCatching { MessageAgent.extractOpenChatName(acc) }.getOrNull()
      } else {
        null
      }

    // Poll heavier hardware services every 1000ms while preserving 100ms (0.1s) UI/lock/chat awareness
    if (now - lastHardwarePollMs >= 1000L || lastHardwarePollMs == 0L) {
      lastHardwarePollMs = now
      runCatching {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (lvl >= 0 && scale > 0) cachedBatteryPct = (lvl * 100) / scale
        val st = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plug = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        cachedCharging = st == BatteryManager.BATTERY_STATUS_CHARGING || plug
      }
      runCatching {
        cachedWifiOn = (ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
      }
      runCatching {
        cachedBtOn = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
      }
      runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        cachedSoundLabel =
          when (am?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            else -> "NORMAL"
          }
      }
    }

    val (tBn, dBn, dayBn) = bangladeshNowSnapshot(now)
    val pendingMsgs = RiyaRuntimeBridge.pendingMessages.value.count { it.status == IncomingReplyStatus.PENDING_PERMISSION }
    val latestCall = RiyaRuntimeBridge.getRecentVoiceCalls(withinMs = 15 * 60_000L).firstOrNull()?.summaryBn

    val snap =
      LiveObservationSnapshot(
        foregroundPackage = pkg,
        openChatName = openChat,
        lockPhase = report.phase,
        isLocked = report.isLocked,
        lockSummaryBn = report.summaryBn,
        accessibilityOn = accOk,
        notificationListenerOn = RiyaRuntimeBridge.isNotificationListenerConnected.value,
        batteryPct = cachedBatteryPct,
        isCharging = cachedCharging,
        wifiOn = cachedWifiOn,
        bluetoothOn = cachedBtOn,
        soundModeLabel = cachedSoundLabel,
        flashlightOn = cachedTorchOn,
        dhakaTimeBn = tBn,
        dhakaDateBn = dBn,
        dhakaDayBn = dayBn,
        pendingMessagesCount = pendingMsgs,
        recentVoiceCallAlert = latestCall,
        observedAtMs = now,
      )
    _snapshot.value = snap

    val prevTail = RiyaAccessibilityService.currentScreenSummary.substringAfter("| স্ক্রিন দৃশ্য:", "").trim()
    val chatPart = if (!openChat.isNullOrBlank()) " | খোলা চ্যাট: $openChat" else ""
    RiyaAccessibilityService.currentScreenSummary =
      if (report.isLocked) {
        "[লক সতর্কতা: ${report.summaryBn}] | বর্তমান অ্যাপ: $pkg"
      } else if (prevTail.isNotEmpty() && RiyaAccessibilityService.currentApp == pkg) {
        "বর্তমান অ্যাপ: $pkg$chatPart | স্ক্রিন দৃশ্য: $prevTail"
      } else {
        "বর্তমান অ্যাপ: $pkg$chatPart"
      }
    return snap
  }
}
