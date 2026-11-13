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

    acc.global("home")
    Thread.sleep(260)
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

  fun openChat(acc: RiyaAccessibilityService, contact: String): Boolean {
    val target = norm(contact)
    if (target.isEmpty()) return false
    for (attempt in 0 until 4) {
      acc.readEntireScreenTree()
      if (clickRowFor(acc, target)) return true
      if (attempt == 0) searchInApp(acc, contact)
      acc.scroll("down")
      Thread.sleep(260)
    }
    return false
  }

  private fun clickRowFor(acc: RiyaAccessibilityService, target: String): Boolean {
    val h = screenH(acc)
    val candidates =
      all(acc)
        .filter { n ->
          val t = norm(n.text?.toString() ?: n.contentDescription?.toString() ?: "")
          t.isNotEmpty() &&
            (t == target || (target.length >= 3 && t.contains(target))) &&
            bounds(n).top > h * 0.12
        }
        .sortedByDescending { n -> if (norm(n.text?.toString() ?: "") == target) 1 else 0 }
    for (n in candidates) {
      val row = clickableAncestor(n) ?: continue
      if (row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
    }
    return false
  }

  private fun searchInApp(acc: RiyaAccessibilityService, query: String) {
    val searchNodes =
      all(acc).filter { n ->
        val l = (label(n) + " " + (n.viewIdResourceName ?: "")).lowercase()
        InfinixDevicePlaybook.searchButtonKeywords.any { l.contains(it) } &&
          (n.isEditable || clickableAncestor(n) != null)
      }
    val s = searchNodes.firstOrNull { it.isEditable } ?: searchNodes.firstOrNull() ?: return
    if (s.isEditable) {
      setText(s, query)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        s.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
      }
      Thread.sleep(450)
      return
    }
    clickableAncestor(s)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    Thread.sleep(350)
    val box = all(acc).firstOrNull { isInput(it) } ?: return
    setText(box, query)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }
    Thread.sleep(450)
  }

  fun headerHas(acc: RiyaAccessibilityService, contact: String): Boolean {
    val target = norm(contact)
    val h = screenH(acc)
    return all(acc).any { n -> bounds(n).top < h * 0.22 && norm(label(n)).contains(target) }
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
      cm.setPrimaryClip(ClipData.newPlainText("riya_ai", msg))
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
