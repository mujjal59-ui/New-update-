package com.example.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.RiyaState

/**
 * Fast, full-screen-verified messaging & AI prompt automation:
 * সঠিক অ্যাপ খোলা -> সম্পূর্ণ স্ক্রিন পড়া -> নামে চ্যাট খোলা -> লেখা ->
 * ইমোজি/রিঅ্যাকশন এড়িয়ে সঠিক সেন্ড বাটনে ক্লিক -> পাঠানো যাচাই।
 */
object MessageAgent {
  class AppSpec(
    val key: String,
    val pkg: String,
    val fallbackPkgs: List<String>,
    val label: String,
    val aliases: List<String>,
    val isAi: Boolean,
  )

  private val APPS =
    listOf(
      AppSpec(
        key = "whatsapp",
        pkg = "com.whatsapp",
        fallbackPkgs = listOf("com.whatsapp.w4b"),
        label = "WhatsApp",
        aliases = listOf("whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটস্যাপ", "ওয়াটসঅ্যাপ"),
        isAi = false,
      ),
      AppSpec(
        key = "imo",
        pkg = "com.imo.android.imoim",
        fallbackPkgs = listOf("com.imo.android.imoimhd", "com.imo.android.imoimbeta"),
        label = "imo",
        aliases = listOf("imo", "ইমো", "ইমু", "আইমো"),
        isAi = false,
      ),
      AppSpec(
        key = "messenger",
        pkg = "com.facebook.orca",
        fallbackPkgs = listOf("com.facebook.mlite"),
        label = "Messenger",
        aliases = listOf("messenger", "মেসেঞ্জার", "মেসেনজার", "ম্যাসেঞ্জার"),
        isAi = false,
      ),
      AppSpec(
        key = "gemini",
        pkg = "com.google.android.apps.bard",
        fallbackPkgs = emptyList(),
        label = "Gemini",
        aliases = listOf("gemini", "জেমিনি"),
        isAi = true,
      ),
      AppSpec(
        key = "chatgpt",
        pkg = "com.openai.chatgpt",
        fallbackPkgs = emptyList(),
        label = "ChatGPT",
        aliases = listOf("chatgpt", "chat gpt", "চ্যাটজিপিটি"),
        isAi = true,
      ),
    )

  fun find(name: String): AppSpec? {
    val n = name.lowercase().trim()
    if (n.isEmpty()) return null
    return APPS.firstOrNull { a -> a.aliases.any { al -> n == al.lowercase() || n.contains(al.lowercase()) } }
  }

  private fun norm(s: String) = s.lowercase().replace(Regex("[\\s._\\-]+"), "")

  private fun rootPkg(acc: RiyaAccessibilityService) =
    acc.rootInActiveWindow?.packageName?.toString() ?: ""

  private fun screenH(acc: RiyaAccessibilityService): Int {
    val r = Rect()
    acc.rootInActiveWindow?.getBoundsInScreen(r)
    return if (r.height() > 0) r.height() else 2400
  }

  private fun screenW(acc: RiyaAccessibilityService): Int {
    val r = Rect()
    acc.rootInActiveWindow?.getBoundsInScreen(r)
    return if (r.width() > 0) r.width() else 1080
  }

  private fun bounds(n: AccessibilityNodeInfo): Rect {
    val r = Rect()
    n.getBoundsInScreen(r)
    return r
  }

  private fun label(n: AccessibilityNodeInfo): String =
    ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim()

  private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
    if (n == null) return
    out.add(n)
    for (i in 0 until n.childCount) collect(n.getChild(i), out)
  }

  private fun all(acc: RiyaAccessibilityService): MutableList<AccessibilityNodeInfo> {
    val out = mutableListOf<AccessibilityNodeInfo>()
    collect(acc.rootInActiveWindow, out)
    return out
  }

  private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var c: AccessibilityNodeInfo? = n
    var depth = 0
    while (c != null && !c.isClickable && depth < 6) {
      c = c.parent
      depth++
    }
    return if (c != null && c.isClickable) c else null
  }

  private fun setText(n: AccessibilityNodeInfo, t: String): Boolean {
    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val b =
      Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
    return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
  }

  private fun launchByLabel(ctx: Context, app: AppSpec): Intent? {
    val pm = ctx.packageManager
    val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val hit =
      pm.queryIntentActivities(li, 0).firstOrNull { ri ->
        val l = ri.loadLabel(pm).toString().lowercase()
        l == app.label.lowercase() || app.aliases.any { l == it.lowercase() }
      } ?: return null
    return Intent(li).setClassName(hit.activityInfo.packageName, hit.activityInfo.name)
  }

  fun openApp(ctx: Context, acc: RiyaAccessibilityService, app: AppSpec): Boolean {
    acc.readEntireScreenTree()
    val allowedPkgs = listOf(app.pkg) + app.fallbackPkgs
    if (rootPkg(acc) in allowedPkgs) {
      return true
    }

    val intent =
      allowedPkgs.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) }
        ?: launchByLabel(ctx, app)
        ?: return false
    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val deadline = System.currentTimeMillis() + 5500
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(200)
      if (rootPkg(acc) in allowedPkgs || RiyaAccessibilityService.currentApp in allowedPkgs) {
        Thread.sleep(320)
        acc.readEntireScreenTree()
        return true
      }
    }
    return false
  }

  private fun isChatScreenOpen(acc: RiyaAccessibilityService): Boolean {
    val h = screenH(acc)
    return bottomEditable(acc, h) != null
  }

  private fun tokensMatch(haystackRaw: String, queryRaw: String): Boolean {
    val h = norm(haystackRaw)
    val q = norm(queryRaw)
    if (h.isEmpty() || q.isEmpty()) return false
    if (h == q) return true
    if (q.length >= 2 && (h.contains(q) || (h.length >= 3 && q.contains(h)))) return true
    val tokens =
      queryRaw
        .lowercase()
        .split(Regex("[\\s.,:\\-_]+"))
        .map { norm(it) }
        .filter { it.length >= 2 && it !in setOf("ভাই", "ভাইয়া", "আপু", "স্যার", "বস", "bhai", "vai", "md") }
    if (tokens.isNotEmpty() && tokens.all { tok -> h.contains(tok) }) return true
    return false
  }

  private fun matchScore(haystackRaw: String, queryRaw: String): Int {
    val h = norm(haystackRaw)
    val q = norm(queryRaw)
    return when {
      h == q -> 4
      h.startsWith(q) -> 3
      h.contains(q) -> 2
      tokensMatch(haystackRaw, queryRaw) -> 1
      else -> 0
    }
  }

  /**
   * Ensures we are either ALREADY inside [contact]'s chat, or navigated back cleanly to the app's main chat list.
   */
  private fun prepareChatSurface(
    ctx: Context,
    acc: RiyaAccessibilityService,
    app: AppSpec,
    contact: String,
  ): Boolean {
    val allowedPkgs = listOf(app.pkg) + app.fallbackPkgs
    acc.readEntireScreenTree()
    // Case 1: Target contact's chat was already open (minimized in background) -> use directly!
    if (headerHas(acc, contact) && isChatScreenOpen(acc)) {
      return true
    }

    // Case 2: Another person's chat or a search/sub-screen is open -> step back cleanly to chat list
    repeat(3) {
      val curEditableTop = all(acc).firstOrNull { isInput(it) && bounds(it).top < screenH(acc) * 0.25f }
      if (!isChatScreenOpen(acc) && curEditableTop == null) {
        return true
      }
      acc.global("back")
      Thread.sleep(380)
      acc.readEntireScreenTree()
      if (rootPkg(acc) !in allowedPkgs) {
        val relaunch =
          allowedPkgs.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) }
            ?: launchByLabel(ctx, app)
        if (relaunch != null) {
          ctx.startActivity(relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          Thread.sleep(500)
          acc.readEntireScreenTree()
        }
        return rootPkg(acc) in allowedPkgs
      }
      if (headerHas(acc, contact) && isChatScreenOpen(acc)) {
        return true
      }
    }
    return true
  }

  fun openChat(acc: RiyaAccessibilityService, contact: String): Boolean {
    val h = screenH(acc)
    acc.readEntireScreenTree()
    // 0. If already inside target chat, done immediately
    if (headerHas(acc, contact) && bottomEditable(acc, h) != null) return true

    // 1. First look directly on the visible top chat list (WhatsApp / imo / Messenger main list)
    if (clickRowFor(acc, contact)) return true

    // 2. Not visible on initial top screen -> search autonomously WITHOUT asking Boss
    searchInApp(acc, contact)
    Thread.sleep(700)
    acc.readEntireScreenTree()
    if (clickRowFor(acc, contact)) return true

    // 3. Fallback: scroll down up to 3 times if list has more items
    repeat(3) {
      acc.scroll("down")
      Thread.sleep(320)
      acc.readEntireScreenTree()
      if (clickRowFor(acc, contact)) return true
    }
    return false
  }

  private fun clickRowFor(acc: RiyaAccessibilityService, query: String): Boolean {
    val h = screenH(acc)
    val w = screenW(acc)
    val ignoredLabels =
      setOf(
        "chats",
        "contacts",
        "channels",
        "otherpeople",
        "messages",
        "more",
        "updates",
        "communities",
        "calls",
        "story",
        "stories",
        "askmetaai",
        "askmetaaiorsearch",
        "metaai",
        "dropathought",
        "creates",
        "চ্যাট",
        "কন্টাক্ট",
        "search",
      )

    val candidates =
      all(acc)
        .mapNotNull { n ->
          if (n.isEditable) return@mapNotNull null
          val rawText = n.text?.toString()?.trim().orEmpty()
          val rawDesc = n.contentDescription?.toString()?.trim().orEmpty()
          val labelStr = rawText.ifBlank { rawDesc }
          val t = norm(labelStr)
          if (t.isEmpty() || t in ignoredLabels || t.contains("metaai")) return@mapNotNull null
          val r = bounds(n)
          // Chat list rows lie below top app/search bar (y >= 0.16*h) and above bottom navigation (y <= 0.90*h)
          val inListArea = r.top >= (h * 0.16f).toInt() && r.bottom <= (h * 0.90f).toInt()
          val notLeftAvatar = r.left >= (w * 0.12f).toInt() || rawText.isNotEmpty()
          val score = matchScore(labelStr, query)
          if (score > 0 && inListArea && notLeftAvatar) Triple(n, score, r.top) else null
        }
        .sortedWith(compareByDescending<Triple<AccessibilityNodeInfo, Int, Int>> { it.second }.thenBy { it.third })

    for ((n, _, _) in candidates) {
      val nr = bounds(n)
      val row = clickableAncestor(n)
      if (row != null && !row.isEditable && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        Thread.sleep(380)
        return true
      }
      if (nr.width() > 0 && nr.height() > 0 && acc.tapAt(nr.exactCenterX(), nr.exactCenterY())) {
        Thread.sleep(380)
        return true
      }
    }
    return false
  }

  private fun searchInApp(acc: RiyaAccessibilityService, query: String) {
    val h = screenH(acc)
    val allNodes = all(acc)
    val searchNodes =
      allNodes.filter { n ->
        val l = (label(n) + " " + (n.viewIdResourceName ?: "")).lowercase()
        val r = bounds(n)
        // WhatsApp/Messenger have top search bar; imo has search magnifying glass at bottom-right!
        val notFabPlusOrCamera =
          !l.contains("camera") &&
            !l.contains("new chat") &&
            !l.contains("add") &&
            !l.contains("invite") &&
            !l.contains("story")
        val hasSearchKeyword =
          InfinixDevicePlaybook.searchButtonKeywords.any { l.contains(it) } ||
            l.contains("ask meta ai")
        notFabPlusOrCamera &&
          hasSearchKeyword &&
          r.width() > 0 &&
          r.height() > 0 &&
          (n.isEditable || clickableAncestor(n) != null)
      }
    // Prefer editable search field near top first, otherwise top/bottom search button
    val s =
      searchNodes.firstOrNull { it.isEditable && bounds(it).top < (h * 0.35f).toInt() }
        ?: searchNodes.firstOrNull { bounds(it).top < (h * 0.35f).toInt() }
        ?: searchNodes.firstOrNull { it.isEditable }
        ?: searchNodes.firstOrNull()
        ?: return

    if (s.isEditable) {
      setText(s, query)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        s.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
      }
      Thread.sleep(500)
      return
    }
    val sRect = bounds(s)
    val clicked = clickableAncestor(s)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true ||
      (sRect.width() > 0 && acc.tapAt(sRect.exactCenterX(), sRect.exactCenterY()))
    if (!clicked) return
    Thread.sleep(450)
    acc.readEntireScreenTree()
    val box = all(acc).firstOrNull { isInput(it) && bounds(it).top < (h * 0.35f).toInt() }
      ?: all(acc).firstOrNull { isInput(it) }
      ?: return
    setText(box, query)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }
    Thread.sleep(500)
  }

  fun headerHas(acc: RiyaAccessibilityService, contact: String): Boolean {
    val h = screenH(acc)
    return all(acc).any { n ->
      !n.isEditable && bounds(n).top < h * 0.24f && tokensMatch(label(n), contact)
    }
  }

  private fun isInput(n: AccessibilityNodeInfo): Boolean =
    n.isEditable || (n.className?.toString()?.contains("EditText") == true)

  private fun bottomEditable(acc: RiyaAccessibilityService, h: Int): AccessibilityNodeInfo? =
    all(acc).filter { isInput(it) && bounds(it).top > h * 0.45 }.maxByOrNull { bounds(it).top }

  private fun writeMessage(
    ctx: Context,
    acc: RiyaAccessibilityService,
    msg: String,
    h: Int,
  ): Boolean {
    acc.readEntireScreenTree()
    var box = bottomEditable(acc, h)
    if (box == null) {
      all(acc)
        .firstOrNull { n ->
          val s = label(n).lowercase()
          (s.contains("message") || s.contains("type") || s.contains("ask") || s.contains("মেসেজ") || s.contains("লিখ")) &&
            bounds(n).top > h * 0.55
        }
        ?.let {
          clickableAncestor(it)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ?: it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
      Thread.sleep(240)
      box = bottomEditable(acc, h)
    }
    if (box == null) return false
    setText(box, msg)
    Thread.sleep(200)
    val now = bottomEditable(acc, h)?.text?.toString()?.trim() ?: ""
    if (!now.contains(msg.take(12))) {
      val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      cm.setPrimaryClip(ClipData.newPlainText("lxr_ai", msg))
      bottomEditable(acc, h)?.let {
        it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        it.performAction(AccessibilityNodeInfo.ACTION_PASTE)
      }
      Thread.sleep(240)
    }
    return (bottomEditable(acc, h)?.text?.toString() ?: "").isNotEmpty()
  }

  private fun findSend(acc: RiyaAccessibilityService, box: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    val bb = bounds(box)
    val bad =
      listOf(
        "attach",
        "emoji",
        "sticker",
        "camera",
        "mic",
        "voice",
        "plus",
        "add",
        "gif",
        "gallery",
        "photo",
        "image",
        "smiley",
        "emoticon",
        "reaction",
        "heart",
        "kiss",
        "rose",
        "like",
        "thumb",
        "ইমোজি",
        "ক্যামেরা",
        "মাইক",
        "ছবি",
        "সংযুক্ত",
        "ফাইল",
        "স্টিকার",
      )
    val rows = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
    val verticalTol = (bb.height().coerceAtLeast(60) * 0.80f).toInt()
    val minAllowedTop = bb.top - 24 // Strictly below floating reaction emoji bar in imo
    for (n in all(acc)) {
      val row = clickableAncestor(n) ?: continue
      if (row === box) continue
      val r = bounds(row)
      if (r.width() <= 0 || r.height() <= 0) continue
      if (r.top < minAllowedTop) continue
      if (kotlin.math.abs(r.centerY() - bb.centerY()) > verticalTol) continue
      if (r.left < bb.right - 20) continue
      val l =
        (label(row) + " " + label(n) + " " + (row.viewIdResourceName ?: "") + " " + (n.viewIdResourceName ?: ""))
          .lowercase()
      if (bad.any { l.contains(it) }) continue
      rows.add(row to r)
    }
    val words = listOf("send", "submit", "সেন্ড", "পাঠান", "btn_send", "send_btn", "im_send")
    rows
      .firstOrNull { (row, _) ->
        val txt = (label(row) + " " + (row.viewIdResourceName ?: "")).lowercase()
        words.any { txt.contains(it) }
      }
      ?.let { return it.first }
    return rows.maxByOrNull { it.second.right }?.first
  }

  private fun sendTyped(
    acc: RiyaAccessibilityService,
    app: AppSpec,
    h: Int,
    msg: String,
  ): String {
    if (app.key == "imo") {
      // Uses dedicated ImoSendPrecisionEngine so floating reactions and trailing emoji buttons are never tapped
      val diag =
        ImoSendPrecisionEngine.performLiveImoSend(acc.rootInActiveWindow, null) { x, y ->
          acc.tapAt(x, y)
        }
      if (diag.succeeded) {
        Thread.sleep(260)
        acc.readEntireScreenTree()
        val left = bottomEditable(acc, h)?.text?.toString()?.trim() ?: ""
        if (left.isEmpty()) return "পাঠানো হয়েছে (ইমোজি বাটন এড়িয়ে সঠিক সেন্ড বাটনে)"
      }
    }

    val box0 = bottomEditable(acc, h) ?: return "লেখার বক্স হারিয়ে গেছে"
    val boxTop = bounds(box0).top
    var found: AccessibilityNodeInfo? = null
    for (i in 0 until 6) {
      found = findSend(acc, box0)
      if (found != null) break
      Thread.sleep(220)
    }
    val sendNode = found ?: run {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        box0.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
      ) {
        Thread.sleep(260)
        acc.readEntireScreenTree()
        return "পাঠানো হয়েছে"
      }
      return "সেন্ড বাটন খুঁজে পাইনি"
    }

    val sr = bounds(sendNode)
    var clicked =
      (clickableAncestor(sendNode) ?: sendNode).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    if (!clicked) clicked = acc.tapAt(sr.exactCenterX(), sr.exactCenterY())
    if (!clicked) return "সেন্ড বাটনে ক্লিক হয়নি"

    val probe = msg.trim().take(20)
    for (i in 0 until 6) {
      Thread.sleep(220)
      acc.readEntireScreenTree()
      val left = bottomEditable(acc, h)?.text?.toString()?.trim() ?: ""
      if (left.isEmpty()) return "পাঠানো হয়েছে"
      if (
        probe.isNotEmpty() &&
          all(acc).any { n ->
            val t = n.text?.toString() ?: ""
            val top = bounds(n).top
            t.contains(probe) && top > h * 0.15 && top < boxTop
          }
      ) {
        return "পাঠানো হয়েছে"
      }
    }

    findSend(acc, box0)?.let { s2 ->
      val r2 = bounds(s2)
      acc.tapAt(r2.exactCenterX(), r2.exactCenterY())
    }
    Thread.sleep(450)
    acc.readEntireScreenTree()
    val left2 = bottomEditable(acc, h)?.text?.toString()?.trim() ?: ""
    return if (left2.isEmpty()) "পাঠানো হয়েছে" else "লেখা বক্সে আছে, পাঠানো যায়নি"
  }

  fun run(
    ctx: Context,
    acc: RiyaAccessibilityService,
    appName: String,
    contact: String,
    msg: String,
  ): String {
    val startMs = System.currentTimeMillis()
    val app = find(appName) ?: return "এই অ্যাপটি চিনতে পারিনি: $appName"
    if (msg.isBlank()) return "কী লিখবো বলোনি বস"
    if (!app.isAi && contact.isBlank()) return "কাকে মেসেজ পাঠাবো বলোনি বস"
    if (!openApp(ctx, acc, app)) return "${app.label} খুলতে পারিনি বস"
    if (!app.isAi) {
      prepareChatSurface(ctx, acc, app, contact)
      if (!openChat(acc, contact)) return "${app.label}-এ '$contact' নামের চ্যাট পাইনি বস"
      val until = System.currentTimeMillis() + 3000
      while (System.currentTimeMillis() < until && bottomEditable(acc, screenH(acc)) == null) {
        Thread.sleep(180)
      }
      Thread.sleep(200)
      acc.readEntireScreenTree()
      if (!headerHas(acc, contact)) {
        return "চ্যাট খুলেছি কিন্তু হেডারে '$contact' মিলছে না, তাই পাঠাইনি বস"
      }
    }
    val h = screenH(acc)
    if (!writeMessage(ctx, acc, msg, h)) return "${app.label}-এ লেখার বক্স খুঁজে পাইনি বস"
    val res = sendTyped(acc, app, h, msg)
    val elapsed = System.currentTimeMillis() - startMs
    if (res.contains("পাঠানো হয়েছে")) {
      RiyaState.markTaskCompleted(
        "হয়েছে, বস! কাজ শেষ — ${app.label}${if (app.isAi) "" else " ($contact)"} এ মেসেজ পাঠানো হয়েছে (${elapsed}ms)।",
        "দারুণ গতিতে কাজ শেষ! এরপর আর কোনো কাজ করবো বস?",
      )
    }
    return "${app.label}${if (app.isAi) "" else " / $contact"}: $res (${elapsed}ms)"
  }
}
