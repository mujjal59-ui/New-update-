package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class InstalledAppEntry(
  val label: String,
  val packageName: String,
  val activityName: String?,
)

/**
 * Exhaustive, package-visibility-aware App Discovery & Launch Resolver (Android 7..16).
 * Resolves Bengali + English + OEM (Infinix/Transsion) app names without falsely reporting
 * installed apps as missing.
 */
object LxrAppDiscoveryEngine {
  private val ALIAS_TO_KEYWORDS: Map<String, List<String>> =
    mapOf(
      "whatsapp" to listOf("whatsapp", "wa business", "whatsapp business", "w4b"),
      "হোয়াটসঅ্যাপ" to listOf("whatsapp"),
      "হোয়াটসঅ্যাপ" to listOf("whatsapp"),
      "imo" to listOf("imo", "imoim", "imo hd", "imo lite"),
      "ইমো" to listOf("imo", "imoim"),
      "ইমু" to listOf("imo", "imoim"),
      "messenger" to listOf("messenger", "orca", "mlite"),
      "মেসেঞ্জার" to listOf("messenger", "orca"),
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
      "ক্যালকুলেটর" to listOf("calculator"),
      "settings" to listOf("settings", "setting"),
      "সেটিংস" to listOf("settings"),
      "সেটিং" to listOf("settings"),
      "contacts" to listOf("contacts", "contact"),
      "কন্টাক্ট" to listOf("contacts"),
      "phone" to listOf("phone", "dialer", "caller", "smart.caller"),
      "ডায়ালার" to listOf("phone", "dialer", "caller"),
      "ডায়ালার" to listOf("phone", "dialer", "caller"),
      "ফোন" to listOf("phone", "dialer", "caller"),
      "messages" to listOf("messages", "messaging", "sms"),
      "মেসেজ" to listOf("messages", "messaging"),
      "clock" to listOf("clock", "deskclock", "alarm"),
      "ঘড়ি" to listOf("clock", "deskclock"),
      "অ্যালার্ম" to listOf("clock", "deskclock"),
      "calendar" to listOf("calendar"),
      "ক্যালেন্ডার" to listOf("calendar"),
      "files" to listOf("files", "file manager", "filemanager", "my files"),
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
      "notes" to listOf("notes", "keep", "notepad"),
      "নোটস" to listOf("notes", "keep", "notepad"),
      "নোট" to listOf("notes", "keep", "notepad"),
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

  fun normalizeQuery(raw: String): String =
    raw
      .lowercase()
      .replace(Regex("(অ্যাপটি|অ্যাপস|অ্যাপ|সফটওয়্যার|সফ্টওয়্যার|খোলো|ওপেন|করো|দাও|\\bapp\\b|\\bopen\\b|\\blaunch\\b)"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()

  fun expandSearchTerms(rawQuery: String): List<String> {
    val clean = normalizeQuery(rawQuery)
    if (clean.isBlank()) return emptyList()
    val out = linkedSetOf(clean)
    val words = clean.split(Regex("[\\s.,:/\\-_]+")).filter { it.isNotBlank() }
    out.addAll(words)
    ALIAS_TO_KEYWORDS.forEach { (key, targets) ->
      if (clean == key || words.any { it == key } || (key.length >= 3 && clean.contains(key))) {
        out.addAll(targets)
      }
    }
    return out.toList()
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
      val label = runCatching { ri.loadLabel(pm).toString().trim() }.getOrDefault(pkg).ifBlank { pkg }
      entries.add(InstalledAppEntry(label = label, packageName = pkg, activityName = act))
    }

    // Fallback: include any installed launchable apps returned by getInstalledApplications
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
      val label = runCatching { pm.getApplicationLabel(ai).toString().trim() }.getOrDefault(pkg).ifBlank { pkg }
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

    fun compact(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    // Strict guard against Messenger <-> Facebook or Messages mixups
    val wantsMessenger = "messenger" in terms || "মেসেঞ্জার" in terms
    val wantsFacebook = ("facebook" in terms || "fb" in terms || "ফেসবুক" in terms) && !wantsMessenger

    fun isBlockedCrossMatch(entry: InstalledAppEntry): Boolean {
      val p = entry.packageName.lowercase()
      val l = entry.label.lowercase()
      if (wantsMessenger && (p == "com.facebook.katana" || p == "com.facebook.lite")) return true
      if (wantsFacebook && (p.contains("orca") || p.contains("mlite") || l == "messenger")) return true
      return false
    }

    var best: InstalledAppEntry? = null
    var bestScore = 0
    for (entry in installed) {
      if (isBlockedCrossMatch(entry)) continue
      val lblLow = entry.label.lowercase().trim()
      val lblComp = compact(lblLow)
      val pkgLow = entry.packageName.lowercase()
      var score = 0

      if (lblLow == cleanQuery || lblComp == compact(cleanQuery)) score = maxOf(score, 120)
      for (t in terms) {
        val tLow = t.lowercase().trim()
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
    val direct = pm.getLaunchIntentForPackage(entry.packageName)
    if (direct != null) {
      return direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
    val act = entry.activityName
    if (!act.isNullOrBlank()) {
      return Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(entry.packageName, act)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
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
      all.take(45).forEach { appendLine("• ${it.label} (${it.packageName})") }
      if (all.size > 45) appendLine("...এবং আরও ${all.size - 45}টি অ্যাপ রয়েছে।")
    }.trim()
  }

  fun buildInstalledAppsSummaryForPrompt(ctx: Context, maxNames: Int = 32): String {
    val all = queryAllLaunchableApps(ctx)
    if (all.isEmpty()) return "Check with open_app / list_installed_apps"
    val labels = all.map { it.label }.distinct()
    val shown = labels.take(maxNames).joinToString(", ")
    return "Total ${labels.size} apps installed (e.g. $shown)"
  }
}
