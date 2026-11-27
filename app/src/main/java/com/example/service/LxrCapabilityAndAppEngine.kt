package com.example.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.Store
import java.util.Locale

enum class LxrCapabilityAvailability(val titleBn: String) {
  FULLY_OPERATIONAL("সম্পূর্ণ সক্রিয় (Fully Operational)"),
  PARTIALLY_SUPPORTED("আংশিক সক্রিয় (Partially Supported)"),
  REQUIRES_EXTERNAL_CONNECTION("অনুমতি বা সংযোগ প্রয়োজন (Permission / Connection Needed)"),
  UNAVAILABLE("এই মুহূর্তে অনুপলব্ধ (Unavailable)"),
}

enum class LxrCapabilityCategory(val titleBn: String) {
  APPLICATION_CONTROL("A. অ্যাপ্লিকেশন কন্ট্রোল (Application Control)"),
  COMMUNICATION("B. কমিউনিকেশন ও মেসেজিং (Communication)"),
  PHONE_INTELLIGENCE("C. ফোন ইন্টেলিজেন্স (Phone Intelligence)"),
  AI_INTELLIGENCE("D. AI ইন্টেলিজেন্স ও স্টুডিও (AI Intelligence)"),
  BACKGROUND_OPERATIONS("E. ব্যাকগ্রাউন্ড অপারেশন (Background Operations)"),
}

data class LxrCapabilityItem(
  val id: String,
  val nameBn: String,
  val category: LxrCapabilityCategory,
  val availability: LxrCapabilityAvailability,
  val detailBn: String,
)

data class LxrCapabilityReport(
  val generatedAtMs: Long,
  val items: List<LxrCapabilityItem>,
  val installedAppsCount: Int,
  val summaryBn: String,
)

data class UniversalAppProfile(
  val key: String,
  val displayName: String,
  val category: String,
  val candidatePackages: List<String>,
  val safestNonUiPath: String,
  val supportsUiAutomation: Boolean,
  val osRestrictionNote: String? = null,
)

data class UniversalAppExecutionPlan(
  val query: String,
  val resolvedAppLabel: String,
  val resolvedPackage: String?,
  val isInstalled: Boolean,
  val recommendedMethod: String,
  val supportsBackgroundDataPath: Boolean,
  val requiresAccessibilityUi: Boolean,
  val restrictionWarningBn: String?,
)

/**
 * Dynamically inspects LXR--AI's true runtime capabilities across permissions, services,
 * installed apps, and API configuration — never claiming unavailable capabilities as active.
 */
object LxrCapabilityInspector {
  private fun hasPermission(ctx: Context, perm: String): Boolean =
    runCatching {
      ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

  fun inspectLive(ctx: Context): LxrCapabilityReport {
    val appCtx = ctx.applicationContext
    val installedApps = runCatching { LxrAppDiscoveryEngine.queryAllLaunchableApps(appCtx) }.getOrDefault(emptyList())
    val accOn = runCatching { Keep.accessibilityOn(appCtx) }.getOrDefault(false)
    val notifOn = RiyaRuntimeBridge.isNotificationListenerConnected.value
    val overlayOn = runCatching { Settings.canDrawOverlays(appCtx) }.getOrDefault(false)
    val writeSettingsOn = runCatching { LxrDeviceAndHardwareEngine.isWriteSettingsGranted(appCtx) }.getOrDefault(false)
    val deviceAdminOn = runCatching { LxrDeviceAndHardwareEngine.isDeviceAdminActive(appCtx) }.getOrDefault(false)
    val exactAlarmOn = runCatching { LxrDeviceAndHardwareEngine.canScheduleExactAlarms(appCtx) }.getOrDefault(false)
    val defaultAssistantOn = runCatching { LxrDeviceAndHardwareEngine.isDefaultAssistant(appCtx) }.getOrDefault(false)

    val hasMic = hasPermission(appCtx, Manifest.permission.RECORD_AUDIO)
    val hasCam = hasPermission(appCtx, Manifest.permission.CAMERA)
    val hasLoc =
      hasPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(appCtx, Manifest.permission.ACCESS_COARSE_LOCATION)
    val hasContacts = hasPermission(appCtx, Manifest.permission.READ_CONTACTS)
    val hasCallLog = hasPermission(appCtx, Manifest.permission.READ_CALL_LOG)
    val hasCallPhone = hasPermission(appCtx, Manifest.permission.CALL_PHONE)
    val gpsEnabled = runCatching { LxrDeviceAndHardwareEngine.isLocationEnabledNow(appCtx) }.getOrDefault(false)
    val geminiKeySet = runCatching { Store.apiKey(appCtx).isNotBlank() }.getOrDefault(false)
    val extraSearchKeys =
      runCatching {
        Store.perplexityApiKey(appCtx).isNotBlank() || Store.youApiKey(appCtx).isNotBlank()
      }.getOrDefault(false)

    val hasMessagingApps =
      installedApps.any { e ->
        val p = e.packageName.lowercase()
        p.contains("whatsapp") || p.contains("imo") || p.contains("orca") || p.contains("telegram")
      }

    val elevenEnabled = runCatching { Store.elevenLabsEnabled(appCtx) && Store.elevenLabsApiKey(appCtx).isNotBlank() }.getOrDefault(false)
    val elevenVoiceId = runCatching { Store.elevenLabsVoiceId(appCtx) }.getOrDefault("")
    val mobileDataOn = runCatching { LxrDeviceAndHardwareEngine.isMobileDataEnabledNow(appCtx) }.getOrNull()

    val items =
      listOf(
        // A. Application Control
        LxrCapabilityItem(
          id = "app_discovery_launch",
          nameBn = "অ্যাপ খোঁজা ও ওপেন (${installedApps.size}টি ইনস্টলকৃত অ্যাপ)",
          category = LxrCapabilityCategory.APPLICATION_CONTROL,
          availability =
            if (installedApps.isNotEmpty() || accOn) LxrCapabilityAvailability.FULLY_OPERATIONAL
            else LxrCapabilityAvailability.PARTIALLY_SUPPORTED,
          detailBn =
            if (accOn) "PackageManager ও Accessibility লঞ্চ সক্রিয়"
            else "অ্যাপ লঞ্চ সক্রিয়; স্ক্রিন-ইন্টারঅ্যাকশনের জন্য Accessibility প্রয়োজন",
        ),
        LxrCapabilityItem(
          id = "ui_automation_screen_read",
          nameBn = "রিয়েল-টাইম স্ক্রিন রিডিং, ট্যাপ, স্ক্রল ও ফর্ম পূরণ",
          category = LxrCapabilityCategory.APPLICATION_CONTROL,
          availability =
            if (accOn) LxrCapabilityAvailability.FULLY_OPERATIONAL
            else LxrCapabilityAvailability.REQUIRES_EXTERNAL_CONNECTION,
          detailBn =
            if (accOn) "Accessibility সার্ভিস চালু আছে"
            else "Accessibility সার্ভিস বন্ধ — সেটিংস থেকে চালু করলে কাজ করবে",
        ),
        LxrCapabilityItem(
          id = "floating_orb_overlay",
          nameBn = "ভাসমান LxR অরব ও স্ক্রিন-রিড ব্যাজ",
          category = LxrCapabilityCategory.APPLICATION_CONTROL,
          availability =
            if (overlayOn) LxrCapabilityAvailability.FULLY_OPERATIONAL
            else LxrCapabilityAvailability.REQUIRES_EXTERNAL_CONNECTION,
          detailBn =
            if (overlayOn) "Display over other apps অনুমোদিত"
            else "Display over other apps অনুমতি প্রয়োজন",
        ),
        // B. Communication
        LxrCapabilityItem(
          id = "messaging_read_reply",
          nameBn = "WhatsApp, imo, Messenger, Telegram ও SMS মেসেজ পড়া/পাঠানো",
          category = LxrCapabilityCategory.COMMUNICATION,
          availability =
            when {
              accOn && notifOn && hasMessagingApps -> LxrCapabilityAvailability.FULLY_OPERATIONAL
              accOn || notifOn -> LxrCapabilityAvailability.PARTIALLY_SUPPORTED
              else -> LxrCapabilityAvailability.REQUIRES_EXTERNAL_CONNECTION
            },
          detailBn =
            buildString {
              append("Accessibility: ${if (accOn) "ON" else "OFF"}")
              append(", Notification Access: ${if (notifOn) "ON" else "OFF"}")
            },
        ),
        LxrCapabilityItem(
          id = "contacts_and_dialer",
          nameBn = "কন্টাক্ট সার্চ, কল লগ দেখা ও ডায়ালার কল",
          category = LxrCapabilityCategory.COMMUNICATION,
          availability =
            when {
              hasContacts && hasCallLog && hasCallPhone -> LxrCapabilityAvailability.FULLY_OPERATIONAL
              hasContacts || hasCallLog || hasCallPhone -> LxrCapabilityAvailability.PARTIALLY_SUPPORTED
              else -> LxrCapabilityAvailability.REQUIRES_EXTERNAL_CONNECTION
            },
          detailBn =
            "Contacts: ${if (hasContacts) "ON" else "OFF"}, CallLog: ${if (hasCallLog) "ON" else "OFF"}, Call: ${if (hasCallPhone) "ON" else "OFF"}",
        ),
        // C. Phone Intelligence
        LxrCapabilityItem(
          id = "live_gps_location",
          nameBn = "সঠিক লাইভ GPS লোকেশন ও Google Maps নেভিগেশন",
          category = LxrCapabilityCategory.PHONE_INTELLIGENCE,
          availability =
            when {
              hasLoc && gpsEnabled -> LxrCapabilityAvailability.FULLY_OPERATIONAL
              hasLoc && !gpsEnabled -> LxrCapabilityAvailability.REQUIRES_EXTERNAL_CONNECTION
              else -> LxrCapabilityAvailability.REQUIRES_EXTERNAL_CONNECTION
            },
          detailBn =
            when {
              hasLoc && gpsEnabled -> "লোকেশন পারমিশন ও GPS উভয়ই চালু"
              hasLoc -> "GPS সুইচ বন্ধ আছে — চালু করলেই আসল ঠিকানা মিলবে"
              else -> "Location পারমিশন (ACCESS_FINE_LOCATION) প্রয়োজন"
            },
        ),
        LxrCapabilityItem(
          id = "hardware_and_lock_control",
          nameBn = "Wi-Fi, Bluetooth, মোবাইল ডাটা, GPS, ফ্ল্যাশলাইট, সাউন্ড, স্ক্রিন লক/আনলক",
          category = LxrCapabilityCategory.PHONE_INTELLIGENCE,
          availability =
            if (accOn && (writeSettingsOn || deviceAdminOn)) LxrCapabilityAvailability.FULLY_OPERATIONAL
            else LxrCapabilityAvailability.PARTIALLY_SUPPORTED,
          detailBn =
            "MobileData: ${when (mobileDataOn) { true -> "ON"; false -> "OFF"; null -> "CHECK" }}, ModifySystemSettings: ${if (writeSettingsOn) "ON" else "OFF"}, DeviceAdmin: ${if (deviceAdminOn) "ON" else "OFF"}",
        ),
        LxrCapabilityItem(
          id = "camera_vision",
          nameBn = "ফ্রন্ট ও ব্যাক ক্যামেরা লাইভ ভিশন (বসকে দেখা ও চেনা)",
          category = LxrCapabilityCategory.PHONE_INTELLIGENCE,
          availability =
            if (hasCam) LxrCapabilityAvailability.FULLY_OPERATIONAL
            else LxrCapabilityAvailability.REQUIRES_EXTERNAL_CONNECTION,
          detailBn =
            if (hasCam) "CAMERA পারমিশন সক্রিয় (ফোরগ্রাউন্ড + ব্যাকগ্রাউন্ড)"
            else "CAMERA পারমিশন বন্ধ আছে",
        ),
        // D. AI Intelligence
        LxrCapabilityItem(
          id = "live_voice_and_ai_reasoning",
          nameBn = "Gemini Live ভয়েস, ElevenLabs ভয়েস, ভয়েস ম্যাচ ও ১৮০ দিনের স্মৃতি",
          category = LxrCapabilityCategory.AI_INTELLIGENCE,
          availability =
            when {
              geminiKeySet && hasMic -> LxrCapabilityAvailability.FULLY_OPERATIONAL
              geminiKeySet || hasMic -> LxrCapabilityAvailability.PARTIALLY_SUPPORTED
              else -> LxrCapabilityAvailability.REQUIRES_EXTERNAL_CONNECTION
            },
          detailBn =
            "Mic: ${if (hasMic) "ON" else "OFF"}, Gemini Key: ${if (geminiKeySet) "সংযুক্ত" else "সেট নেই"}, ElevenLabs: ${if (elevenEnabled) "ON (${elevenVoiceId.ifBlank { "Rachel" }})" else "OFF"}, Default Assistant: ${if (defaultAssistantOn) "ON" else "OFF"}",
        ),
        LxrCapabilityItem(
          id = "deep_research_and_studio",
          nameBn = "ওয়েব রিসার্চ, Termux APK বিল্ড ও 3D অ্যানিমেশন/YouTube SEO",
          category = LxrCapabilityCategory.AI_INTELLIGENCE,
          availability =
            if (extraSearchKeys) LxrCapabilityAvailability.FULLY_OPERATIONAL
            else LxrCapabilityAvailability.PARTIALLY_SUPPORTED,
          detailBn =
            if (extraSearchKeys) "Google/DuckDuckGo/Wikipedia + Perplexity/You.com সক্রিয়"
            else "ফ্রি Google/DuckDuckGo/Wikipedia সক্রিয় (ঐচ্ছিক Perplexity/You Key ছাড়া)",
        ),
        // E. Background Operations
        LxrCapabilityItem(
          id = "background_task_queue_and_monitor",
          nameBn = "২৪/৭ ব্যাকগ্রাউন্ড টাস্ক কিউ, সরাসরি ফোনের অ্যালার্ম সেট/বন্ধ ও Activity Monitor",
          category = LxrCapabilityCategory.BACKGROUND_OPERATIONS,
          availability =
            if (exactAlarmOn) LxrCapabilityAvailability.FULLY_OPERATIONAL
            else LxrCapabilityAvailability.PARTIALLY_SUPPORTED,
          detailBn =
            "JobScheduler + Foreground Service + Disk Persistence সক্রিয়; Alarms & reminders: ${if (exactAlarmOn) "ON" else "OFF (Settings পেজ থেকে Allow দিন)"}; Clock (Set/Dismiss): ${LxrDeviceAndHardwareEngine.resolvePhoneClockPackage(appCtx) ?: "LXR AlarmClock"}",
        ),
        LxrCapabilityItem(
          id = "os_protected_root_restrictions",
          nameBn = "অ্যান্ড্রয়েড OS-সুরক্ষিত সিস্টেম ফাইল/অন্য অ্যাপের এনক্রিপ্টেড ডেটাবেস সরাসরি পড়া",
          category = LxrCapabilityCategory.BACKGROUND_OPERATIONS,
          availability = LxrCapabilityAvailability.UNAVAILABLE,
          detailBn = "Android OS নিরাপত্তা নীতিমালার কারণে রুট ছাড়া তৃতীয় পক্ষের অ্যাপের গোপন ডেটাবেস সরাসরি পড়া সম্ভব নয়",
        ),
      )

    val summary = formatReportBn(items, installedApps.size)
    return LxrCapabilityReport(
      generatedAtMs = System.currentTimeMillis(),
      items = items,
      installedAppsCount = installedApps.size,
      summaryBn = summary,
    )
  }

  private fun formatReportBn(items: List<LxrCapabilityItem>, installedCount: Int): String =
    buildString {
      appendLine("বস, আমি এই মুহূর্তের তোমার ফোনের আসল সিস্টেম ও পারমিশন পরীক্ষা করে আমার প্রকৃত সক্ষমতা বলছি (ফোনে $installedCount টি অ্যাপ পাওয়া গেছে):")
      appendLine()
      for (tier in LxrCapabilityAvailability.entries) {
        val group = items.filter { it.availability == tier }
        appendLine("【${tier.titleBn}】")
        if (group.isEmpty()) {
          appendLine("• এই ধাপে কোনো আইটেম নেই")
        } else {
          group.forEachIndexed { idx, item ->
            appendLine("${idx + 1}. [${item.category.titleBn}] ${item.nameBn} — ${item.detailBn}")
          }
        }
        appendLine()
      }
      append("তুমি যেকোনো কাজ বললে আমি ঠিক উপরের যাচাইকৃত পথ ব্যবহার করে কাজ সম্পন্ন করবো, আর যেটায় অনুমতি নেই সেটা আগে তোমাকে স্পষ্ট জানাবো বস।")
    }.trim()

  fun buildLiveCapabilityReportBn(ctx: Context): String = inspectLive(ctx).summaryBn
}

/**
 * Universal Personal AI App Controller registry and routing planner.
 * Always verifies installation via [LxrAppDiscoveryEngine] and selects the safest
 * background/system API path before UI Accessibility automation.
 */
object LxrUniversalAppController {
  val SUPPORTED_APPS: List<UniversalAppProfile> =
    listOf(
      UniversalAppProfile(
        key = "whatsapp",
        displayName = "WhatsApp",
        category = "Messaging",
        candidatePackages = listOf("com.whatsapp", "com.whatsapp.w4b"),
        safestNonUiPath = "Notification Listener রিড ও কুইক রিপ্লাই (উপলব্ধ হলে)",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "imo",
        displayName = "imo",
        category = "Messaging",
        candidatePackages = listOf("com.imo.android.imoim", "com.imo.android.imoimhd", "com.imo.android.imoimlite"),
        safestNonUiPath = "Notification Listener রিড ও imo প্রিসিশন সেন্ড",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "messenger",
        displayName = "Messenger",
        category = "Messaging",
        candidatePackages = listOf("com.facebook.orca", "com.facebook.mlite"),
        safestNonUiPath = "Notification Listener রিড ও রিপ্লাই",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "telegram",
        displayName = "Telegram",
        category = "Messaging",
        candidatePackages = listOf("org.telegram.messenger", "org.telegram.messenger.web"),
        safestNonUiPath = "Notification Listener + Accessibility UI",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "sms",
        displayName = "Messages (SMS)",
        category = "Messaging",
        candidatePackages = listOf("com.google.android.apps.messaging", "com.android.mms"),
        safestNonUiPath = "Notification Listener + System SMS Category Intent",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "contacts",
        displayName = "Contacts",
        category = "Communication",
        candidatePackages = listOf("com.google.android.contacts", "com.transsion.contacts", "com.infinix.contacts", "com.android.contacts"),
        safestNonUiPath = "ContactsContract ContentResolver (ব্যাকগ্রাউন্ড নিরাপদ পথ)",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "dialer",
        displayName = "Dialer / Phone",
        category = "Communication",
        candidatePackages = listOf("com.google.android.dialer", "com.transsion.dialer", "com.sh.smart.caller", "com.android.dialer"),
        safestNonUiPath = "CallLog.Calls ContentResolver + ACTION_CALL / ACTION_DIAL",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "maps",
        displayName = "Google Maps",
        category = "Navigation",
        candidatePackages = listOf("com.google.android.apps.maps"),
        safestNonUiPath = "LocationManager GPS + Geo/Navigation Intent",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "gmail",
        displayName = "Gmail",
        category = "Google Productivity",
        candidatePackages = listOf("com.google.android.gm"),
        safestNonUiPath = "Notification Listener + SENDTO mailto Intent + UI Screen Read",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "drive",
        displayName = "Google Drive",
        category = "Google Productivity",
        candidatePackages = listOf("com.google.android.apps.docs"),
        safestNonUiPath = "Package Launch + UI Screen Automation",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "photos",
        displayName = "Google Photos",
        category = "Media",
        candidatePackages = listOf("com.google.android.apps.photos"),
        safestNonUiPath = "Package Launch + UI Screen Automation",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "chrome",
        displayName = "Chrome / Chrome Beta",
        category = "Browser",
        candidatePackages = listOf("com.android.chrome", "com.chrome.beta"),
        safestNonUiPath = "URL View Intent + Form Auto-Fill Accessibility",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "gallery",
        displayName = "Gallery / AI Gallery",
        category = "Media",
        candidatePackages = listOf("com.transsion.camera.gallery", "com.transsion.gallery", "com.google.android.apps.photos"),
        safestNonUiPath = "CATEGORY_APP_GALLERY Intent + UI Automation",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "music",
        displayName = "Music",
        category = "Media",
        candidatePackages = listOf("com.transsion.boomplay", "com.google.android.apps.youtube.music", "com.spotify.music"),
        safestNonUiPath = "AudioManager MediaKeyEvent (Play/Pause/Next/Previous)",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "youtube",
        displayName = "YouTube",
        category = "Media",
        candidatePackages = listOf("com.google.android.youtube"),
        safestNonUiPath = "Search/View Intent + MediaKeyEvent + UI Automation",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "youtube_studio",
        displayName = "YouTube Studio",
        category = "Creator",
        candidatePackages = listOf("com.google.android.apps.youtube.creator"),
        safestNonUiPath = "Package Launch + UI Screen Read",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "chatgpt",
        displayName = "ChatGPT",
        category = "AI Assistant",
        candidatePackages = listOf("com.openai.chatgpt"),
        safestNonUiPath = "Package Launch + UI Automation",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "gemini",
        displayName = "Gemini",
        category = "AI Assistant",
        candidatePackages = listOf("com.google.android.apps.bard"),
        safestNonUiPath = "Package Launch + UI Automation",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "termux",
        displayName = "Termux",
        category = "Developer",
        candidatePackages = listOf("com.termux", "com.termux.api"),
        safestNonUiPath = "Termux RUN_COMMAND Intent + Terminal UI Executor",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "calculator",
        displayName = "Calculator",
        category = "Utility",
        candidatePackages = listOf("com.transsion.calculator", "com.google.android.calculator", "com.android.calculator2"),
        safestNonUiPath = "CATEGORY_APP_CALCULATOR + UI Automation",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "notes",
        displayName = "Notes",
        category = "Utility",
        candidatePackages = listOf("com.transsion.notepad", "com.google.android.keep"),
        safestNonUiPath = "UI Text Entry (target='body') + Screen Read",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "file_manager",
        displayName = "File Manager",
        category = "Utility",
        candidatePackages = listOf("com.transsion.filemanagerx", "com.google.android.apps.nbu.files"),
        safestNonUiPath = "Direct App Files/Downloads Storage API + UI Automation",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "play_store",
        displayName = "Play Store",
        category = "Store",
        candidatePackages = listOf("com.android.vending"),
        safestNonUiPath = "Market/Play Intent + UI Screen Read",
        supportsUiAutomation = true,
        osRestrictionNote = "Android নীতিমালা অনুযায়ী স্বয়ংক্রিয় ইনস্টলে ইনস্টল বাটন ট্যাপে ইউজার/সিস্টেম অনুমোদন লাগে।",
      ),
      UniversalAppProfile(
        key = "weather",
        displayName = "Weather",
        category = "Utility",
        candidatePackages = listOf("com.transsion.weather", "com.google.android.apps.weather"),
        safestNonUiPath = "Web Search Weather API + App Launch",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "facebook",
        displayName = "Facebook",
        category = "Social",
        candidatePackages = listOf("com.facebook.katana", "com.facebook.lite"),
        safestNonUiPath = "Accessibility UI Automation (পাবলিক অ্যাকশনে বসের সম্মতিসহ)",
        supportsUiAutomation = true,
      ),
      UniversalAppProfile(
        key = "instagram",
        displayName = "Instagram",
        category = "Social",
        candidatePackages = listOf("com.instagram.android"),
        safestNonUiPath = "Accessibility UI Automation (পাবলিক অ্যাকশনে বসের সম্মতিসহ)",
        supportsUiAutomation = true,
      ),
    )

  fun planAction(ctx: Context, rawAppQuery: String, actionDescription: String = ""): UniversalAppExecutionPlan {
    val query = LxrAppDiscoveryEngine.normalizeQuery(rawAppQuery).ifBlank { rawAppQuery.trim().lowercase(Locale.ROOT) }
    val installed = runCatching { LxrAppDiscoveryEngine.queryAllLaunchableApps(ctx) }.getOrDefault(emptyList())
    val matchedProfile =
      SUPPORTED_APPS.firstOrNull { profile ->
        query == profile.key ||
          query.contains(profile.key) ||
          query.contains(profile.displayName.lowercase(Locale.ROOT)) ||
          profile.candidatePackages.any { pkg -> pkg.equals(query, ignoreCase = true) }
      }
    val matchedInstalled = LxrAppDiscoveryEngine.matchInstalledEntry(rawAppQuery, installed)
      ?: matchedProfile?.let { prof ->
        installed.firstOrNull { inst -> prof.candidatePackages.any { it.equals(inst.packageName, ignoreCase = true) } }
      }
    val resolvedPkg = matchedInstalled?.packageName
    val resolvedLabel = matchedInstalled?.label ?: matchedProfile?.displayName ?: rawAppQuery.trim()
    val accOn = runCatching { Keep.accessibilityOn(ctx) }.getOrDefault(false)
    val prefersBackgroundApi =
      matchedProfile?.key in setOf("contacts", "dialer", "maps", "file_manager", "music", "weather") ||
        actionDescription.contains("read", true) && matchedProfile?.key in setOf("whatsapp", "imo", "messenger", "telegram", "sms")

    val method =
      when {
        matchedInstalled == null && matchedProfile == null ->
          "ফোনে ইনস্টল করা অ্যাপের তালিকায় মেলেনি — প্যাকেজ যাচাই প্রয়োজন"
        matchedInstalled == null ->
          "প্যাকেজ ফোনে সরাসরি পাওয়া যায়নি (${matchedProfile?.displayName}) — সিস্টেম ক্যাটাগরি/লঞ্চার চেক"
        prefersBackgroundApi ->
          matchedProfile?.safestNonUiPath ?: "Background ContentResolver / System Intent"
        accOn ->
          "নিরাপদ Intent + Accessibility UI অটোমেশন"
        else ->
          "সরাসরি System Intent লঞ্চ (UI কাজের জন্য Accessibility চালু করতে হবে)"
      }

    val warning =
      when {
        matchedInstalled == null ->
          "'$resolvedLabel' ফোনে ইনস্টল থাকা প্যাকেজে পাওয়া যায়নি; সত্য না জেনে কাজ সম্পন্ন বলবো না।"
        !accOn && !prefersBackgroundApi ->
          "Accessibility সার্ভিস বন্ধ থাকায় অ্যাপ খুললেও ভেতরের বাটন ট্যাপ/রিডিং সীমিত থাকবে।"
        else -> matchedProfile?.osRestrictionNote
      }

    return UniversalAppExecutionPlan(
      query = rawAppQuery,
      resolvedAppLabel = resolvedLabel,
      resolvedPackage = resolvedPkg,
      isInstalled = matchedInstalled != null,
      recommendedMethod = method,
      supportsBackgroundDataPath = prefersBackgroundApi,
      requiresAccessibilityUi = !prefersBackgroundApi,
      restrictionWarningBn = warning,
    )
  }
}
