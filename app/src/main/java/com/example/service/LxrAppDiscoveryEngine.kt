package com.example.service

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import java.text.Normalizer

data class InstalledAppEntry(
  val label: String,
  val packageName: String,
  val activityName: String?,
)

/**
 * Exhaustive, package-visibility-aware App Discovery & Launch Resolver (Android 7..16).
 * Resolves Bengali (NFC/NFD) + English + OEM (Infinix/Transsion) app names and system category
 * intents without falsely reporting installed apps as missing, and launches safely past Android 14-16 BAL.
 */
object LxrAppDiscoveryEngine {
  private val ALIAS_TO_KEYWORDS: Map<String, List<String>> =
    mapOf(
      "whatsapp" to listOf("whatsapp", "wa business", "whatsapp business", "w4b"),
      "হোয়াটসঅ্যাপ" to listOf("whatsapp"),
      "ওয়াটসঅ্যাপ" to listOf("whatsapp"),
      "হোয়াটস্যাপ" to listOf("whatsapp"),
      "imo" to listOf("imo", "imoim", "imo hd", "imo lite"),
      "ইমো" to listOf("imo", "imoim"),
      "ইমু" to listOf("imo", "imoim"),
      "messenger" to listOf("messenger", "orca", "mlite"),
      "মেসেঞ্জার" to listOf("messenger", "orca"),
      "মেসেনজার" to listOf("messenger", "orca"),
      "ম্যাসেঞ্জার" to listOf("messenger", "orca"),
      "facebook" to listOf("facebook", "katana", "fb lite", "facebook lite"),
      "ফেসবুক" to listOf("facebook", "katana"),
      "fb" to listOf("facebook", "katana"),
      "instagram" to listOf("instagram"),
      "ইনস্টাগ্রাম" to listOf("instagram"),
      "youtube" to listOf("youtube"),
      "ইউটিউব" to listOf("youtube"),
      "chrome" to listOf("chrome"),
      "ক্রোম" to listOf("chrome"),
      "play store" to listOf("play store", "google play", "vending"),
      "প্লে স্টোর" to listOf("play store", "vending"),
      "camera" to listOf("camera", "cam", "photo"),
      "ক্যামেরা" to listOf("camera"),
      "gallery" to listOf("gallery", "photos", "ai gallery", "album"),
      "গ্যালারি" to listOf("gallery", "photos", "ai gallery"),
      "ছবি" to listOf("gallery", "photos", "ai gallery"),
      "photos" to listOf("photos", "gallery", "ai gallery"),
      "calculator" to listOf("calculator", "calc"),
      "ক্যালকুলেটর" to listOf("calculator", "calc"),
      "settings" to listOf("settings", "setting"),
      "সেটিংস" to listOf("settings"),
      "সেটিং" to listOf("settings"),
      "contacts" to listOf("contacts", "contact", "people", "phonebook"),
      "কন্টাক্ট" to listOf("contacts", "contact"),
      "কনট্যাক্ট" to listOf("contacts", "contact"),
      "কন্টাক্টস" to listOf("contacts", "contact"),
      "ফোনবুক" to listOf("contacts", "contact"),
      "phone" to listOf("phone", "dialer", "caller", "smart.caller", "telecom"),
      "dialer" to listOf("phone", "dialer", "caller", "smart.caller", "telecom"),
      "ডায়ালার" to listOf("phone", "dialer", "caller", "smart.caller"),
      "ফোন" to listOf("phone", "dialer", "caller", "smart.caller"),
      "কল" to listOf("phone", "dialer", "caller"),
      "messages" to listOf("messages", "messaging", "sms", "mms"),
      "মেসেজ" to listOf("messages", "messaging", "sms"),
      "মেসেজেস" to listOf("messages", "messaging", "sms"),
      "এসএমএস" to listOf("messages", "messaging", "sms"),
      "clock" to listOf("clock", "deskclock", "alarm"),
      "ঘড়ি" to listOf("clock", "deskclock"),
      "অ্যালার্ম" to listOf("clock", "deskclock"),
      "calendar" to listOf("calendar"),
      "ক্যালেন্ডার" to listOf("calendar"),
      "files" to listOf("files", "file manager", "filemanager", "my files", "nbu.files"),
      "ফাইল" to listOf("files", "file manager", "filemanager"),
      "gmail" to listOf("gmail", "gm"),
      "জিমেইল" to listOf("gmail"),
      "maps" to listOf("maps", "google maps"),
      "ম্যাপ" to listOf("maps"),
      "ম্যাপস" to listOf("maps"),
      "tiktok" to listOf("tiktok", "musically", "trill"),
      "টিকটক" to listOf("tiktok", "musically", "trill"),
      "telegram" to listOf("telegram"),
      "টেলিগ্রাম" to listOf("telegram"),
      "snapchat" to listOf("snapchat"),
      "স্ন্যাপচ্যাট" to listOf("snapchat"),
      "bkash" to listOf("bkash"),
      "বিকাশ" to listOf("bkash"),
      "nagad" to listOf("nagad"),
      "নগদ" to listOf("nagad"),
      "rocket" to listOf("rocket", "nexus"),
      "রকেট" to listOf("rocket", "nexus"),
      "notes" to listOf("notes", "notepad", "transsion.notepad"),
      "notepad" to listOf("notes", "notepad", "transsion.notepad"),
      "নোটস" to listOf("notes", "notepad", "transsion.notepad"),
      "নোটপ্যাড" to listOf("notes", "notepad", "transsion.notepad"),
      "নোট" to listOf("notes", "notepad", "transsion.notepad"),
      "keep" to listOf("keep", "google.android.keep"),
      "keep notes" to listOf("keep", "google.android.keep"),
      "কিপ" to listOf("keep", "google.android.keep"),
      "কিপ নোটস" to listOf("keep", "google.android.keep"),
      "termux" to listOf("termux"),
      "টারমাক্স" to listOf("termux"),
      "capcut" to listOf("capcut", "lemon.lvoverseas"),
      "ক্যাপকাট" to listOf("capcut"),
      "gemini" to listOf("gemini", "bard"),
      "জেমিনি" to listOf("gemini", "bard"),
      "chatgpt" to listOf("chatgpt", "openai"),
      "চ্যাটজিপিটি" to listOf("chatgpt", "openai"),
      "spotify" to listOf("spotify"),
      "mx player" to listOf("mx player", "mxtech"),
      "recorder" to listOf("recorder", "sound recorder"),
      "রেকর্ডার" to listOf("recorder"),
      "music" to listOf("music", "boomplay", "yt music"),
      "মিউজিক" to listOf("music"),
    )

  fun normalizeUnicode(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFC)
      .replace("\u200B", "")
      .replace("\u200C", "")
      .replace("\u200D", "")
      .replace("\uFEFF", "")

  fun normalizeQuery(raw: String): String =
    normalizeUnicode(raw)
      .lowercase()
      .replace(
        Regex("(?:^|[\\s,।!?])(?:অ্যাপস|অ্যাপ্স|অ্যাপটি|অ্যাপ|সফটওয়্যার|সফ্টওয়্যার|খোলো|খুলে|ওপেন|করো|চালু|দাও|apps|app|open|launch)(?=[\\s,।!?]|$)"),
        " ",
      )
      .replace(Regex("\\s+"), " ")
      .trim()

  fun expandSearchTerms(rawQuery: String): List<String> {
    val clean = normalizeQuery(rawQuery)
    if (clean.isBlank()) return emptyList()
    val out = linkedSetOf(clean)
    val words = clean.split(Regex("[\\s.,:/\\-_]+")).filter { it.isNotBlank() }
    out.addAll(words)
    ALIAS_TO_KEYWORDS.forEach { (key, targets) ->
      val normKey = normalizeUnicode(key).lowercase()
      if (clean == normKey || words.any { it == normKey } || (normKey.length >= 2 && clean.contains(normKey))) {
        out.addAll(targets)
      }
    }
    return out.toList()
  }

  /**
   * Resolves core system app intents (Dialer, Contacts, Messages, Camera, Gallery, Calculator, Calendar, Settings)
   * directly via Android PackageManager so OEM package differences never fail.
   */
  fun resolveSystemCategoryIntent(ctx: Context, rawQuery: String): Pair<String, Intent>? {
    val terms = expandSearchTerms(rawQuery)
    if (terms.isEmpty()) return null
    val pm = ctx.packageManager

    fun pickResolved(intent: Intent): Intent? {
      val resolved =
        runCatching {
          if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(
              intent,
              PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
          } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
          }
        }.getOrDefault(emptyList())
      val candidate = resolved.firstOrNull { it.activityInfo != null && it.activityInfo.packageName != ctx.packageName }
      if (candidate != null) {
        val pkg = candidate.activityInfo.packageName
        pm.getLaunchIntentForPackage(pkg)?.let {
          return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        return Intent(intent)
          .setClassName(pkg, candidate.activityInfo.name)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
      }
      return null
    }

    val checks =
      listOf(
        Triple(listOf("phone", "dialer", "caller", "smart.caller", "ডায়ালার", "ফোন", "কল"), "Phone") {
          Intent(Intent.ACTION_DIAL)
        },
        Triple(listOf("contacts", "contact", "people", "phonebook", "কন্টাক্ট", "কনট্যাক্ট", "কন্টাক্টস", "ফোনবুক"), "Contacts") {
          Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        },
        Triple(listOf("messages", "messaging", "sms", "mms", "মেসেজ", "মেসেজেস", "এসএমএস"), "Messages") {
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
        },
        Triple(listOf("camera", "ক্যামেরা"), "Camera") {
          Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        },
        Triple(listOf("gallery", "photos", "ai gallery", "গ্যালারি", "ছবি"), "Gallery") {
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
        },
        Triple(listOf("calculator", "calc", "ক্যালকুলেটর"), "Calculator") {
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR)
        },
        Triple(listOf("calendar", "ক্যালেন্ডার"), "Calendar") {
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
        },
        Triple(listOf("settings", "সেটিংস", "সেটিং"), "Settings") {
          Intent(Settings.ACTION_SETTINGS)
        },
      )

    for ((keys, label, intentFactory) in checks) {
      if (terms.any { t -> t in keys }) {
        val intent = intentFactory()
        pickResolved(intent)?.let { return label to it }
        if (label == "Contacts") {
          val catIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)
          pickResolved(catIntent)?.let { return label to it }
        }
      }
    }
    return null
  }

  /**
   * Launches an Activity safely from background Service context even under Android 14/15/16 BAL rules,
   * prioritizing AccessibilityService context and ActivityOptions BAL opt-in before standard fallback.
   */
  fun launchActivitySafely(
    ctx: Context,
    acc: RiyaAccessibilityService?,
    intent: Intent,
  ): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (acc != null) {
      val accOk =
        runCatching {
          acc.startActivity(intent)
          true
        }.getOrDefault(false)
      if (accOk) return true
    }
    if (Build.VERSION.SDK_INT >= 34) {
      val balOk =
        runCatching {
          val opts =
            ActivityOptions.makeBasic()
              .setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
              )
          ctx.startActivity(intent, opts.toBundle())
          true
        }.getOrDefault(false)
      if (balOk) return true
    }
    return runCatching {
      ctx.startActivity(intent)
      true
    }.getOrDefault(false)
  }

  fun queryAllLaunchableApps(ctx: Context): List<InstalledAppEntry> {
    val pm = ctx.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val seen = linkedSetOf<String>()
    val entries = mutableListOf<InstalledAppEntry>()

    val activities =
      runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
          pm.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
          )
        } else {
          @Suppress("DEPRECATION")
          pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }
      }.getOrElse {
        runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())
      }

    for (ri in activities) {
      val pkg = ri.activityInfo?.packageName ?: continue
      val act = ri.activityInfo?.name
      val dedupKey = "$pkg/$act"
      if (!seen.add(dedupKey)) continue
      val label = normalizeUnicode(runCatching { ri.loadLabel(pm).toString().trim() }.getOrDefault(pkg)).ifBlank { pkg }
      entries.add(InstalledAppEntry(label = label, packageName = pkg, activityName = act))
    }

    // Fallback 1: query core system category intents so OEM Dialer / Contacts / SMS / Gallery are always included
    val categoryIntents =
      listOf(
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
      )
    for (ci in categoryIntents) {
      val resolved =
        runCatching {
          if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(ci, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
          } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(ci, PackageManager.MATCH_ALL)
          }
        }.getOrDefault(emptyList())
      for (ri in resolved) {
        val pkg = ri.activityInfo?.packageName ?: continue
        val act = ri.activityInfo?.name
        if (pkg == ctx.packageName) continue
        val dedupKey = "$pkg/$act"
        if (!seen.add(dedupKey)) continue
        val label = normalizeUnicode(runCatching { ri.loadLabel(pm).toString().trim() }.getOrDefault(pkg)).ifBlank { pkg }
        entries.add(InstalledAppEntry(label = label, packageName = pkg, activityName = act))
      }
    }

    // Fallback 2: include any installed launchable apps returned by getInstalledApplications
    val installedApps =
      runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
          pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
          @Suppress("DEPRECATION")
          pm.getInstalledApplications(0)
        }
      }.getOrDefault(emptyList())

    val existingPkgs = entries.map { it.packageName }.toSet()
    for (ai in installedApps) {
      val pkg = ai.packageName ?: continue
      if (pkg in existingPkgs) continue
      val li = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
      val label = normalizeUnicode(runCatching { pm.getApplicationLabel(ai).toString().trim() }.getOrDefault(pkg)).ifBlank { pkg }
      entries.add(InstalledAppEntry(label = label, packageName = pkg, activityName = li.component?.className))
    }

    return entries.sortedBy { it.label.lowercase() }
  }

  fun matchInstalledEntry(
    rawQuery: String,
    installed: List<InstalledAppEntry>,
  ): InstalledAppEntry? {
    val terms = expandSearchTerms(rawQuery)
    if (terms.isEmpty() || installed.isEmpty()) return null
    val cleanQuery = normalizeQuery(rawQuery)

    fun compact(s: String) = normalizeUnicode(s).lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    // Strict guard against Messenger <-> Facebook and Notes <-> Keep Notes mixups
    val wantsMessenger = "messenger" in terms || "মেসেঞ্জার" in terms
    val wantsFacebook = ("facebook" in terms || "fb" in terms || "ফেসবুক" in terms) && !wantsMessenger
    val wantsKeep =
      cleanQuery.contains("keep") ||
        cleanQuery.contains("কিপ") ||
        "google.android.keep" in terms
    val wantsNotes =
      !wantsKeep &&
        ("notes" in terms || "notepad" in terms || "নোট" in terms || "নোটস" in terms || "নোটপ্যাড" in terms)
    val hasDeviceNotepad =
      installed.any { e ->
        val ep = e.packageName.lowercase()
        val el = normalizeUnicode(e.label).lowercase()
        ep.contains("notepad") || (!ep.contains("keep") && (el == "notes" || el == "notepad"))
      }

    fun isBlockedCrossMatch(entry: InstalledAppEntry): Boolean {
      val p = entry.packageName.lowercase()
      val l = normalizeUnicode(entry.label).lowercase()
      if (wantsMessenger && (p == "com.facebook.katana" || p == "com.facebook.lite")) return true
      if (wantsFacebook && (p.contains("orca") || p.contains("mlite") || l == "messenger")) return true
      if (wantsKeep && p.contains("notepad") && !p.contains("keep")) return true
      if (wantsNotes && hasDeviceNotepad && (p == "com.google.android.keep" || l.contains("keep"))) return true
      return false
    }

    var best: InstalledAppEntry? = null
    var bestScore = 0
    for (entry in installed) {
      if (isBlockedCrossMatch(entry)) continue
      val lblLow = normalizeUnicode(entry.label).lowercase().trim()
      val lblComp = compact(lblLow)
      val pkgLow = entry.packageName.lowercase()
      var score = 0

      if (lblLow == cleanQuery || lblComp == compact(cleanQuery)) score = maxOf(score, 120)
      for (t in terms) {
        val tLow = normalizeUnicode(t).lowercase().trim()
        val tComp = compact(tLow)
        if (tLow.isBlank()) continue
        when {
          lblLow == tLow || (tComp.length >= 2 && lblComp == tComp) -> score = maxOf(score, 110)
          lblLow.startsWith("$tLow ") || lblLow == tLow -> score = maxOf(score, 95)
          tLow.length >= 3 && lblLow.contains(tLow) -> score = maxOf(score, 85)
          tComp.length >= 3 && lblComp.contains(tComp) -> score = maxOf(score, 80)
          tComp.length >= 3 && pkgLow.contains(tComp) -> score = maxOf(score, 72)
        }
      }
      if (score > bestScore) {
        bestScore = score
        best = entry
      }
    }
    return if (bestScore >= 72) best else null
  }

  fun buildLaunchIntent(ctx: Context, entry: InstalledAppEntry): Intent? {
    val pm = ctx.packageManager
    val direct = runCatching { pm.getLaunchIntentForPackage(entry.packageName) }.getOrNull()
    if (direct != null) {
      return direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val act = entry.activityName
    if (!act.isNullOrBlank()) {
      return Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(entry.packageName, act)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return null
  }

  fun listInstalledAppsReport(ctx: Context, filterQuery: String = ""): String {
    val all = queryAllLaunchableApps(ctx)
    if (all.isEmpty()) {
      return "ফোনে লঞ্চার অ্যাপ তালিকা পাওয়া যাচ্ছে না বস — `open_app` দিয়ে সরাসরি হোম ও অ্যাপ ড্রয়ার স্ক্যান করা যাবে।"
    }
    val cleanFilter = normalizeQuery(filterQuery)
    if (cleanFilter.isNotBlank()) {
      val matched = matchInstalledEntry(cleanFilter, all)
      if (matched != null) {
        return "হ্যাঁ বস! '${matched.label}' (${matched.packageName}) অ্যাপটি ফোনে ইনস্টল করা আছে ✓"
      }
      val terms = expandSearchTerms(cleanFilter)
      val partial =
        all.filter { e ->
          val hay = "${e.label} ${e.packageName}".lowercase()
          terms.any { t -> t.length >= 2 && hay.contains(t) }
        }
      if (partial.isNotEmpty()) {
        return "ফোনে এই মিল থাকা অ্যাপগুলো পাওয়া গেছে (${partial.size}টি):\n" +
          partial.take(15).joinToString("\n") { "• ${it.label} (${it.packageName})" }
      }
    }
    return buildString {
      appendLine("📱 এই ফোনে মোট ${all.size}টি লঞ্চযোগ্য অ্যাপ ইনস্টল করা আছে:")
      all.take(60).forEach { appendLine("• ${it.label} (${it.packageName})") }
      if (all.size > 60) appendLine("...এবং আরও ${all.size - 60}টি অ্যাপ রয়েছে।")
    }.trim()
  }

  fun buildInstalledAppsSummaryForPrompt(ctx: Context, maxNames: Int = 72): String {
    val all = queryAllLaunchableApps(ctx)
    if (all.isEmpty()) return "Check with open_app / list_installed_apps"
    val coreHints =
      listOf(
        "whatsapp",
        "imo",
        "messenger",
        "facebook",
        "phone",
        "dialer",
        "contacts",
        "messages",
        "youtube",
        "chrome",
        "camera",
        "gallery",
        "settings",
        "bkash",
        "nagad",
        "notes",
        "calculator",
        "gmail",
        "maps",
      )
    val prioritized =
      all.sortedByDescending { entry ->
        val hay = "${entry.label} ${entry.packageName}".lowercase()
        if (coreHints.any { hay.contains(it) }) 1 else 0
      }
    val labels = prioritized.map { it.label }.distinct()
    val shown = labels.take(maxNames).joinToString(", ")
    return "Total ${labels.size} apps installed (key & installed: $shown)"
  }
}
