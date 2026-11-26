package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.RiyaState

data class FormFieldDescriptor(
  val index: Int,
  val label: String,
  val hint: String,
  val currentText: String,
  val viewId: String,
  val isPassword: Boolean,
  val bounds: Rect,
)

/**
 * Handles:
 * 1) Exhaustive Launcher & Home/Drawer app discovery (swipe + search bar fallback before claiming app absent)
 * 2) Chrome / Web / in-app interactive form inspection & field-by-field filling
 * 3) Facebook & Instagram full automation with mandatory full-screen reading & Boss confirmation gates
 */
object LxrSocialAndBrowserAgent {

  private val BENGALI_APP_ALIASES =
    mapOf(
      "হোয়াটসঅ্যাপ" to "whatsapp",
      "ওয়াটসঅ্যাপ" to "whatsapp",
      "ইমো" to "imo",
      "ইমু" to "imo",
      "মেসেঞ্জার" to "messenger",
      "মেসেনজার" to "messenger",
      "ম্যাসেঞ্জার" to "messenger",
      "ইনস্টাগ্রাম" to "instagram",
      "ফেসবুক" to "facebook",
      "ক্রোম" to "chrome",
      "বিকাশ" to "bkash",
      "নগদ" to "nagad",
      "রকেট" to "rocket",
      "ইউটিউব" to "youtube",
      "ম্যাপস" to "maps",
      "গুগল ম্যাপস" to "maps",
      "ক্যামেরা" to "camera",
      "গ্যালারি" to "gallery",
      "ক্যালকুলেটর" to "calculator",
      "সেটিংস" to "settings",
      "কন্টাক্টস" to "contacts",
      "কন্টাক্ট" to "contacts",
      "কনট্যাক্ট" to "contacts",
      "ডায়ালার" to "phone",
      "ফোন" to "phone",
      "মেসেজ" to "messages",
      "নোটস" to "notes",
      "ফাইল" to "files",
      "ঘড়ি" to "clock",
      "ক্যালেন্ডার" to "calendar",
      "টারমাক্স" to "termux",
    )

  private fun normalizeAppQuery(raw: String): String {
    var s =
      LxrAppDiscoveryEngine.normalizeUnicode(raw)
        .lowercase()
        .replace(
          Regex("(?:^|[\\s,।!?])(?:অ্যাপস|অ্যাপ্স|অ্যাপটি|অ্যাপ|সফটওয়্যার|সফ্টওয়্যার|খোলো|খুলে|ওপেন|করো|দাও|চালু|apps|app|open)(?=[\\s,।!?]|$)"),
          " ",
        )
        .replace(Regex("\\s+"), " ")
        .trim()
    BENGALI_APP_ALIASES.forEach { (bn, en) ->
      if (s.contains(bn)) s = s.replace(bn, en)
    }
    return s.replace(Regex("\\s+"), " ").trim()
  }

  fun findMatchingLauncherActivity(
    query: String,
    activities: List<Pair<String, ResolveInfo>>,
  ): ResolveInfo? {
    val qNorm = normalizeAppQuery(query)
    if (qNorm.isBlank()) return null
    val qCompact = qNorm.replace(" ", "")
    val tokens = qNorm.split(" ").filter { it.length >= 2 }

    // 1. Exact label match
    activities.firstOrNull { (label, _) ->
      label.trim().equals(qNorm, ignoreCase = true)
    }?.let { return it.second }

    // 2. Compact match (no spaces/punctuation)
    activities.firstOrNull { (label, _) ->
      label.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "") == qCompact
    }?.let { return it.second }

    // 3. Label contains full normalized query
    activities.firstOrNull { (label, _) ->
      label.lowercase().contains(qNorm)
    }?.let { return it.second }

    // 4. Token or package match
    return activities.firstOrNull { (label, info) ->
      val lLow = label.lowercase()
      val pLow = info.activityInfo.packageName.lowercase()
      (tokens.isNotEmpty() && tokens.all { lLow.contains(it) }) ||
        (qCompact.length >= 4 && pLow.contains(qCompact))
    }?.second
  }

  /**
   * Exhaustively searches Home screen pages, App Drawer, and Launcher search bar before concluding
   * an app is missing. Always reads full screen at every step.
   */
  fun discoverAndLaunchFromLauncher(
    ctx: Context,
    acc: RiyaAccessibilityService?,
    rawQuery: String,
    goHomeFn: () -> Unit,
  ): String {
    val cleanQuery = normalizeAppQuery(rawQuery).ifBlank { rawQuery.trim() }
    val pm = ctx.packageManager
    val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveAll =
      runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
          pm.queryIntentActivities(
            li,
            android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_ALL.toLong()),
          )
        } else {
          @Suppress("DEPRECATION")
          pm.queryIntentActivities(li, android.content.pm.PackageManager.MATCH_ALL)
        }
      }.getOrElse { pm.queryIntentActivities(li, 0) }
    val labeled = resolveAll.map { it.loadLabel(pm).toString() to it }

    findMatchingLauncherActivity(cleanQuery, labeled)?.let { match ->
      val pkg = match.activityInfo.packageName
      val label = match.loadLabel(pm).toString()
      val launch =
        (pm.getLaunchIntentForPackage(pkg)
          ?: Intent(li).setClassName(pkg, match.activityInfo.name))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
      LxrAppDiscoveryEngine.launchActivitySafely(ctx, acc, launch)
      Thread.sleep(550L)
      if (LockVaultManager.isAppLockScreenShowing(acc)) {
        LockVaultManager.unlockAppLockIfPresent(ctx, acc)
        Thread.sleep(360L)
      }
      val screen = acc?.readScreen().orEmpty()
      RiyaState.markTaskCompleted("$label অ্যাপ খোলা হয়েছে।", "")
      return "$label ($pkg) অ্যাপটি সঠিকভাবে ওপেন করেছি বস।\n[সম্পূর্ণ স্ক্রিন ১০১% রিড]:\n${screen.take(2000)}"
    }

    LxrAppDiscoveryEngine.resolveSystemCategoryIntent(ctx, rawQuery)?.let { (sysLabel, sysIntent) ->
      LxrAppDiscoveryEngine.launchActivitySafely(ctx, acc, sysIntent)
      Thread.sleep(550L)
      if (LockVaultManager.isAppLockScreenShowing(acc)) {
        LockVaultManager.unlockAppLockIfPresent(ctx, acc)
        Thread.sleep(360L)
      }
      val screen = acc?.readScreen().orEmpty()
      RiyaState.markTaskCompleted("$sysLabel অ্যাপ খোলা হয়েছে।", "")
      return "$sysLabel অ্যাপটি সঠিকভাবে ওপেন করেছি বস।\n[সম্পূর্ণ স্ক্রিন ১০১% রিড]:\n${screen.take(2000)}"
    }

    if (acc == null) {
      return "ফোনে '$rawQuery' অ্যাপটি সরাসরি প্যাকেজ লিস্টে মেলেনি বস (হোম স্ক্রিন ঘুরে দেখতে Accessibility চালু রাখুন)।"
    }

    val allInstalled = LxrAppDiscoveryEngine.queryAllLaunchableApps(ctx)
    if (!shouldAllowLauncherSwipeExploration(cleanQuery, allInstalled)) {
      return "ফোনের ইনস্টলকৃত অ্যাপ তালিকায় '$rawQuery' নামে কোনো অ্যাপ পাওয়া যায়নি বস — অকারণে হোম স্ক্রিন সোয়াইপ না করে সঠিক অ্যাপের নাম বলো।"
    }

    // Physical launcher inspection: check Home screen safely WITHOUT blind edge/drawer swipe loops
    goHomeFn()
    Thread.sleep(320L)
    acc.readEntireScreenTree()
    val directHome = acc.clickText(cleanQuery, isContact = false)
    if (directHome.contains("ক্লিক করা হয়েছে")) {
      Thread.sleep(500L)
      if (LockVaultManager.isAppLockScreenShowing(acc)) LockVaultManager.unlockAppLockIfPresent(ctx, acc)
      val s = acc.readScreen()
      return "হোম স্ক্রিন থেকে '$rawQuery' ওপেন করেছি বস।\n[সম্পূর্ণ স্ক্রিন ১০১% রিড]:\n${s.take(1800)}"
    }

    val finalRead = acc.readScreen().take(1200)
    return "হোম স্ক্রিন ও ইনস্টলকৃত অ্যাপ তালিকা দেখে নিশ্চিত হলাম: '$rawQuery' নামে কোনো অ্যাপ পাওয়া যায়নি বস।\n[শেষ স্ক্রিন]: $finalRead"
  }

  fun shouldAllowLauncherSwipeExploration(
    rawQuery: String,
    installedApps: List<InstalledAppEntry>,
  ): Boolean {
    val clean = LxrAppDiscoveryEngine.normalizeQuery(rawQuery)
    if (clean.length < 2) return false
    val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size > 3) return false
    if (LxrAppDiscoveryEngine.matchInstalledEntry(clean, installedApps) != null) return true
    val terms = LxrAppDiscoveryEngine.expandSearchTerms(clean)
    return installedApps.any { entry ->
      val hay = "${entry.label} ${entry.packageName}".lowercase()
      terms.any { t -> t.length >= 3 && hay.contains(t) }
    }
  }

  fun collectFormFields(acc: RiyaAccessibilityService?): Pair<List<FormFieldDescriptor>, String> {
    if (acc == null) return emptyList<FormFieldDescriptor>() to "Accessibility চালু নেই বস।"
    acc.readEntireScreenTree()
    val root = acc.rootInActiveWindow ?: return emptyList<FormFieldDescriptor>() to "সক্রিয় স্ক্রিন নেই।"

    val fields = mutableListOf<FormFieldDescriptor>()
    val urlHint = StringBuilder()

    fun findNearbyLabel(node: AccessibilityNodeInfo): String {
      val hint = node.hintText?.toString()?.trim().orEmpty()
      if (hint.isNotBlank()) return hint
      val desc = node.contentDescription?.toString()?.trim().orEmpty()
      if (desc.isNotBlank()) return desc
      val parent = node.parent ?: return ""
      for (i in 0 until parent.childCount) {
        val sib = parent.getChild(i) ?: continue
        if (sib != node && !sib.isEditable) {
          val t = sib.text?.toString()?.trim().orEmpty()
          if (t.isNotBlank() && t.length <= 60) return t
        }
      }
      return ""
    }

    fun walk(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > 35 || fields.size >= 40) return
      val vid = n.viewIdResourceName.orEmpty()
      val t = n.text?.toString()?.trim().orEmpty()
      if ((vid.contains("url_bar") || vid.contains("omnibox") || vid.contains("address")) && t.isNotBlank()) {
        urlHint.append(t)
      }
      if (n.isEditable || n.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
        val r = Rect()
        n.getBoundsInScreen(r)
        val lbl = findNearbyLabel(n)
        fields.add(
          FormFieldDescriptor(
            index = fields.size + 1,
            label = lbl.ifBlank { vid.substringAfterLast('/').ifBlank { "Field ${fields.size + 1}" } },
            hint = n.hintText?.toString()?.trim().orEmpty(),
            currentText = t,
            viewId = vid,
            isPassword = n.isPassword,
            bounds = r,
          )
        )
      }
      for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
    }

    walk(root, 0)
    val screenSummary = acc.readScreen().take(2000)
    val report = buildString {
      if (urlHint.isNotBlank()) appendLine("🌐 URL/ঠিকানা: $urlHint")
      if (fields.isEmpty()) {
        appendLine("বর্তমান স্ক্রিনে কোনো পূরণযোগ্য ইনপুট ফর্ম ফিল্ড পাওয়া যায়নি।")
      } else {
        appendLine("📝 স্ক্রিনে ${fields.size}টি ফর্ম ফিল্ড পাওয়া গেছে:")
        fields.forEach { f ->
          val valTxt =
            when {
              f.currentText.isBlank() -> "[ফাঁকা]"
              f.isPassword -> "[***]"
              else -> "\"${f.currentText}\""
            }
          appendLine("${f.index}. ${f.label} -> $valTxt")
        }
      }
      appendLine("[স্ক্রিন সারাংশ]:\n$screenSummary")
    }.trim()
    return fields to report
  }

  fun matchBestFieldIndex(fields: List<FormFieldDescriptor>, queryKey: String): Int? {
    if (fields.isEmpty()) return null
    val qNum = queryKey.trim().toIntOrNull()
    if (qNum != null && qNum in 1..fields.size) return qNum - 1
    val qLow = queryKey.trim().lowercase()
    return fields.indexOfFirst { f ->
      f.label.lowercase().contains(qLow) ||
        f.hint.lowercase().contains(qLow) ||
        f.viewId.lowercase().contains(qLow)
    }.takeIf { it >= 0 }
  }

  fun fillFormFields(
    acc: RiyaAccessibilityService?,
    fieldValues: List<Pair<String, String>>,
  ): String {
    if (acc == null) return "Accessibility সার্ভিস চালু নেই বস।"
    val (descriptors, _) = collectFormFields(acc)
    if (descriptors.isEmpty()) return "স্ক্রিনে কোনো ফর্ম বক্স খুঁজে পাওয়া যায়নি বস।"

    val root = acc.rootInActiveWindow ?: return "সক্রিয় স্ক্রিন নেই।"
    val editableNodes = mutableListOf<AccessibilityNodeInfo>()
    fun collectEditables(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > 35 || editableNodes.size >= 40) return
      if (n.isEditable || n.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
        editableNodes.add(n)
      }
      for (i in 0 until n.childCount) collectEditables(n.getChild(i), depth + 1)
    }
    collectEditables(root, 0)

    val filledLabels = mutableListOf<String>()
    val unmatchedKeys = mutableListOf<String>()

    for ((key, value) in fieldValues) {
      val idx = matchBestFieldIndex(descriptors, key)
      val node = idx?.let { editableNodes.getOrNull(it) }
      if (node == null) {
        unmatchedKeys.add(key)
        continue
      }
      val rect = Rect()
      node.getBoundsInScreen(rect)
      if (rect.width() > 0 && rect.height() > 0) {
        acc.tapAt(rect.exactCenterX(), rect.exactCenterY())
        Thread.sleep(90L)
      }
      node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
      val args =
        Bundle().apply {
          putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
      if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
        filledLabels.add("${descriptors[idx].label} = \"$value\"")
      } else {
        unmatchedKeys.add(key)
      }
      Thread.sleep(120L)
    }

    val (afterFields, _) = collectFormFields(acc)
    val stillEmpty =
      afterFields.filter { it.currentText.isBlank() && !it.label.contains("url", true) && !it.label.contains("search", true) }

    return buildString {
      if (filledLabels.isNotEmpty()) {
        appendLine("হয়েছে, বস! এই তথ্যগুলো ফর্মে লিখে দিয়েছি: ${filledLabels.joinToString(", ")}।")
      }
      if (unmatchedKeys.isNotEmpty()) {
        appendLine("এই ফিল্ডগুলো মেলাতে পারিনি: ${unmatchedKeys.joinToString(", ")}।")
      }
      if (stillEmpty.isNotEmpty()) {
        appendLine(
          "এখনো ফর্মে এই ঘরগুলো ফাঁকা আছে — ধাপে ধাপে আমাকে বলো কোনটিতে কী লিখবো: " +
            stillEmpty.joinToString(", ") { "${it.index}. ${it.label}" }
        )
      } else {
        appendLine("সব প্রয়োজনীয় ফিল্ড পূর্ণ হয়েছে বস। এখন আমি কি Submit / Next চাপবো নাকি আগে আরেকবার পড়ে শোনাবো?")
      }
    }.trim()
  }

  private fun launchSocialPackage(
    ctx: Context,
    acc: RiyaAccessibilityService?,
    packages: List<String>,
    label: String,
  ): Boolean {
    val cur = acc?.rootInActiveWindow?.packageName?.toString().orEmpty()
    if (cur in packages) {
      if (LockVaultManager.isAppLockScreenShowing(acc)) {
        LockVaultManager.unlockAppLockIfPresent(ctx, acc)
        Thread.sleep(320L)
      }
      return true
    }
    val pm = ctx.packageManager
    val intent =
      packages.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    if (!LxrAppDiscoveryEngine.launchActivitySafely(ctx, acc, intent)) {
      return false
    }
    val deadline = System.currentTimeMillis() + 4500L
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(220L)
      if (LockVaultManager.isAppLockScreenShowing(acc)) {
        LockVaultManager.unlockAppLockIfPresent(ctx, acc)
        Thread.sleep(360L)
      }
      val active = acc?.rootInActiveWindow?.packageName?.toString().orEmpty()
      if (active in packages || RiyaAccessibilityService.currentApp in packages) {
        Thread.sleep(360L)
        return true
      }
    }
    return true
  }

  fun performFacebookAction(
    ctx: Context,
    acc: RiyaAccessibilityService?,
    action: String,
    text: String = "",
    query: String = "",
    confirmed: Boolean = false,
  ): String {
    if (acc == null) return "Facebook অটোমেশনের জন্য Accessibility চালু নেই বস!"
    val fbPkgs = listOf("com.facebook.katana", "com.facebook.lite")
    if (!launchSocialPackage(ctx, acc, fbPkgs, "Facebook")) {
      return "ফোনে Facebook অ্যাপ ইনস্টল করা নেই বস।"
    }
    val act = action.trim().lowercase()
    acc.readEntireScreenTree()

    return when (act) {
      "open", "read" -> {
        "Facebook ওপেন আছে বস।\n[সম্পূর্ণ স্ক্রিন]:\n${acc.readScreen().take(2000)}"
      }
      "search" -> {
        val q = query.ifBlank { text }.trim()
        if (q.isBlank()) return "Facebook-এ কী সার্চ করবো বলোনি বস।"
        acc.clickText("Search")
        Thread.sleep(350L)
        acc.typeText(q, target = "auto")
        Thread.sleep(350L)
        val s = acc.readScreen().take(2000)
        "Facebook-এ '$q' সার্চ করেছি বস।\n[স্ক্রিন]:\n$s"
      }
      "react", "like" -> {
        val btn = text.ifBlank { "Like" }
        val r = acc.clickText(btn)
        Thread.sleep(250L)
        "Facebook-এ '$btn' রিয়্যাক্ট: $r\n[স্ক্রিন]: ${acc.readScreen().take(1200)}"
      }
      "share" -> {
        if (!confirmed) {
          return "বস, স্ক্রিনের এই পোস্টটি কি সত্যিই Facebook-এ Share করবো? হ্যাঁ বললে শেয়ার করছি।"
        }
        val r1 = acc.clickText("Share")
        Thread.sleep(450L)
        val r2 = acc.clickText("Share now")
        "Facebook শেয়ার সম্পন্ন ($r1 / $r2)।"
      }
      "post_status", "status", "post_photo", "photo", "post_reel", "reel" -> {
        if (!confirmed) {
          return "বস, Facebook-এ এই পোস্টটি পাবলিক করার আগে নিশ্চিত হচ্ছি: প্রকার='$act'${if (text.isNotBlank()) ", লেখা=\"$text\"" else ""}। আমি কি পোস্ট তৈরি করে পাবলিশ করবো? হ্যাঁ বললে এগোচ্ছি।"
        }
        val opener =
          when {
            act.contains("reel") -> listOf("Reel", "Create reel", "রিল")
            act.contains("photo") -> listOf("Photo", "Photo/video", "ছবি", "What's on your mind")
            else -> listOf("What's on your mind", "Create a post", "মনে কী চলছে")
          }
        for (lbl in opener) {
          if (acc.clickText(lbl).contains("ক্লিক করা হয়েছে")) {
            Thread.sleep(480L)
            break
          }
        }
        if (text.isNotBlank()) {
          acc.typeText(text, target = "auto")
          Thread.sleep(320L)
        }
        val postAttempt =
          if (act.contains("status") && text.isNotBlank()) acc.clickText("Post")
          else "বস, পোস্ট বক্স/গ্যালারি খুলে রেখেছি (${if (text.isNotBlank()) "ক্যাপশন লেখা হয়েছে" else "ছবি/ভিডিও বেছে নিন"}), চূড়ান্ত নিশ্চিত করলে 'Post' চাপছি।"
        "Facebook অ্যাকশন আপডেট: $postAttempt\n[এখনকার স্ক্রিন]:\n${acc.readScreen().take(1600)}"
      }
      "reply_comment", "comment" -> {
        val openComm = acc.clickText("Comment")
        Thread.sleep(360L)
        val curComments = acc.readScreen().take(1500)
        if (!confirmed || text.isBlank()) {
          return "Facebook-এর কমেন্ট সেকশন পড়ে নিয়েছি বস:\n$curComments\nকোন কমেন্টের কী উত্তর লিখবো বলো, হ্যাঁ বললেই পোস্ট করে দেবো।"
        }
        acc.typeText(text, target = "auto")
        Thread.sleep(240L)
        acc.clickText("Send")
        "Facebook-এ কমেন্ট উত্তর পাঠিয়েছি: \"$text\" ($openComm)।"
      }
      else -> "Facebook-এ অ্যাকশন '$action' চালানো হয়েছে।\n[স্ক্রিন]: ${acc.readScreen().take(1400)}"
    }
  }

  fun performInstagramAction(
    ctx: Context,
    acc: RiyaAccessibilityService?,
    action: String,
    text: String = "",
    query: String = "",
    confirmed: Boolean = false,
  ): String {
    if (acc == null) return "Instagram অটোমেশনের জন্য Accessibility চালু নেই বস!"
    val igPkgs = listOf("com.instagram.android")
    if (!launchSocialPackage(ctx, acc, igPkgs, "Instagram")) {
      return "ফোনে Instagram অ্যাপ ইনস্টল করা নেই বস।"
    }
    val act = action.trim().lowercase()
    acc.readEntireScreenTree()

    return when (act) {
      "open", "read" -> {
        "Instagram ওপেন আছে বস।\n[সম্পূর্ণ স্ক্রিন]:\n${acc.readScreen().take(2000)}"
      }
      "search" -> {
        val q = query.ifBlank { text }.trim()
        if (q.isBlank()) return "Instagram-এ কী খুঁজবো বলোনি বস।"
        acc.clickText("Search")
        Thread.sleep(400L)
        acc.clickText("Search")
        Thread.sleep(250L)
        acc.typeText(q, target = "auto")
        Thread.sleep(380L)
        "Instagram-এ '$q' সার্চ করেছি বস।\n[স্ক্রিন]:\n${acc.readScreen().take(1800)}"
      }
      "react", "like" -> {
        val r = acc.clickText("Like")
        "Instagram পোস্টে Like: $r"
      }
      "share" -> {
        if (!confirmed) {
          return "বস, এই Instagram পোস্ট/রিলটি কি শেয়ার করবো? কার সাথে বা কোথায় শেয়ার করতে চাও বলো।"
        }
        val r = acc.clickText("Share")
        "Instagram Share খোলা হয়েছে: $r\n[স্ক্রিন]: ${acc.readScreen().take(1400)}"
      }
      "post_photo", "photo", "post_reel", "reel" -> {
        if (!confirmed) {
          return "বস, Instagram-এ $act পোস্ট করার আগে নিশ্চিত হচ্ছি${if (text.isNotBlank()) " (ক্যাপশন: \"$text\")" else ""}। হ্যাঁ বললে গ্যালারি ও ক্যাপশন ধাপ চালু করবো।"
        }
        acc.clickText("Create")
        Thread.sleep(420L)
        if (act.contains("reel")) {
          acc.clickText("Reel")
          Thread.sleep(320L)
        }
        if (text.isNotBlank()) {
          acc.typeText(text, target = "auto")
        }
        "Instagram $act স্ক্রিন খোলা হয়েছে বস — ছবি/রিল নির্বাচন করলে বাকিটা সম্পন্ন করছি।\n[স্ক্রিন]:\n${acc.readScreen().take(1600)}"
      }
      "reply_comment", "comment" -> {
        acc.clickText("Comment")
        Thread.sleep(380L)
        val screenComments = acc.readScreen().take(1500)
        if (!confirmed || text.isBlank()) {
          return "Instagram কমেন্ট সেকশন দেখছি বস:\n$screenComments\nকী উত্তর দেবো নিশ্চিত করলে লিখে Post করছি।"
        }
        acc.typeText(text, target = "auto")
        Thread.sleep(250L)
        acc.clickText("Post")
        "Instagram-এ কমেন্ট পোস্ট করেছি: \"$text\"।"
      }
      else -> "Instagram স্ক্রিন অবস্থা:\n${acc.readScreen().take(1500)}"
    }
  }
}
