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
    APPS.firstOrNull { a ->
      a.key == n || a.label.lowercase() == n || a.aliases.any { al -> n == al.lowercase() }
    }?.let { return it }
    return APPS.firstOrNull { a -> a.aliases.any { al -> n.contains(al.lowercase()) } }
  }

  fun launchIntentFor(ctx: Context, app: AppSpec): Intent? {
    val allowedPkgs = listOf(app.pkg) + app.fallbackPkgs
    return allowedPkgs.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) }
      ?: launchByLabel(ctx, app)
  }

  private fun stripInvisible(s: String): String =
    s.replace("\u200B", "")
      .replace("\u200C", "")
      .replace("\u200D", "")
      .replace("\u200E", "")
      .replace("\u200F", "")
      .replace("\uFEFF", "")

  private fun norm(s: String) = stripInvisible(s).lowercase().replace(Regex("[\\s._\\-]+"), "")

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

    val intent = launchIntentFor(ctx, app) ?: return false
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

  private fun hasBengali(s: String): Boolean = s.any { it in '\u0980'..'\u09FF' }

  private fun bengaliWordToRoman(word: String): String {
    val normWord = java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFC)
    val sb = StringBuilder()
    var i = 0
    while (i < normWord.length) {
      val ch = normWord[i]
      // Handle hasanta / virama (্) by omitting implicit 'o' and skipping separator
      val nextIsVirama = (i + 1 < normWord.length && normWord[i + 1] == '্')
      val mapped =
        when (ch) {
          'অ' -> "o"
          'আ', 'া' -> "a"
          'ই', 'ঈ', 'ি', 'ী' -> "i"
          'উ', 'ঊ', 'ু', 'ূ' -> "u"
          'ঋ', 'ৃ' -> "ri"
          'এ', 'ঐ', 'ে', 'ৈ' -> "e"
          'ও', 'ঔ', 'ো', 'ৌ' -> "o"
          'ক', 'খ' -> "k"
          'গ', 'ঘ' -> "g"
          'ঙ', 'ং' -> "ng"
          'চ', 'ছ' -> "c"
          'জ', 'ঝ', 'য' -> "j"
          'ঞ' -> "n"
          'ট', 'ঠ', 'ত', 'থ' -> "t"
          'ড', 'ঢ', 'দ', 'ধ' -> "d"
          'ণ', 'ন' -> "n"
          'প' -> "p"
          'ফ' -> "f"
          'ব', 'ভ' -> "b"
          'ম' -> "m"
          'র', '\u09DC', '\u09DD' -> "r"
          'ল' -> "l"
          'শ', 'ষ', 'স' -> "s"
          'হ' -> "h"
          '\u09DF' -> "y"
          'ৎ' -> "t"
          '্', 'ঁ', 'ঃ', '\u09BC' -> ""
          in 'a'..'z', in 'A'..'Z', in '0'..'9' -> ch.lowercaseChar().toString()
          else -> ""
        }
      sb.append(mapped)
      if (!nextIsVirama && ch in listOf('ক','খ','গ','ঘ','চ','ছ','জ','ঝ','ট','ঠ','ড','ঢ','ণ','ত','থ','দ','ধ','ন','প','ফ','ব','ভ','ম','য','র','ল','শ','ষ','স','হ','\u09DC','\u09DD','\u09DF')) {
        val next = normWord.getOrNull(i + 1)
        val hasVowelSign = next in listOf('া','ি','ী','ু','ূ','ৃ','ে','ৈ','ো','ৌ','্')
        if (!hasVowelSign && i < normWord.length - 1) {
          sb.append('o')
        }
      }
      i++
    }
    return sb.toString()
  }

  private fun normalizeLatinPhonetic(word: String): String {
    var s = word.lowercase().replace(Regex("[^a-z0-9]"), "")
    if (s.isEmpty()) return ""
    s = s
      .replace("ph", "f")
      .replace("bh", "b")
      .replace("kh", "k")
      .replace("gh", "g")
      .replace("th", "t")
      .replace("dh", "d")
      .replace("sh", "s")
      .replace("ch", "c")
      .replace("v", "b")
      .replace("z", "j")
      .replace("q", "k")
      .replace("w", "o")
    val dedup = StringBuilder()
    for (c in s) {
      if (dedup.isEmpty() || dedup.last() != c) dedup.append(c)
    }
    return dedup.toString()
  }

  private fun consonantSkeleton(phonetic: String): String {
    if (phonetic.isEmpty()) return ""
    val sb = StringBuilder()
    for (c in phonetic) {
      if (c !in charArrayOf('a', 'e', 'i', 'o', 'u', 'y')) {
        if (sb.isEmpty() || sb.last() != c) sb.append(c)
      }
    }
    return sb.toString()
  }

  private fun splitCleanWords(s: String): List<String> =
    stripInvisible(s)
      .lowercase()
      .split(Regex("[\\s.,:/\\-_()]+"))
      .map { it.trim() }
      .filter {
        it.length >= 2 &&
          it !in setOf("ভাই", "ভাইয়া", "আপু", "স্যার", "বস", "ডাক্তার", "bhai", "vai", "md", "dr", "mr", "ms")
      }

  fun matchesContactName(haystackRaw: String, queryRaw: String): Boolean {
    if (tokensMatch(haystackRaw, queryRaw)) return true
    val hWords = splitCleanWords(haystackRaw)
    val qWords = splitCleanWords(queryRaw)
    if (hWords.isEmpty() || qWords.isEmpty()) return false

    fun wordMatches(hw: String, qw: String): Boolean {
      val hLat = normalizeLatinPhonetic(if (hasBengali(hw)) bengaliWordToRoman(hw) else hw)
      val qLat = normalizeLatinPhonetic(if (hasBengali(qw)) bengaliWordToRoman(qw) else qw)
      if (hLat.isEmpty() || qLat.isEmpty()) return false
      if (hLat == qLat) return true
      if (minOf(hLat.length, qLat.length) >= 4 && (hLat.contains(qLat) || qLat.contains(hLat))) return true
      val hSk = consonantSkeleton(hLat)
      val qSk = consonantSkeleton(qLat)
      if (hSk.length >= 3 && qSk.length >= 3) {
        if (hSk == qSk) return true
        if (hSk.first() == qSk.first() && (hSk.startsWith(qSk) || qSk.startsWith(hSk))) return true
      }
      return false
    }

    return qWords.all { qw -> hWords.any { hw -> wordMatches(hw, qw) } }
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
        .filter { it.length >= 2 && it !in setOf("ভাই", "ভাইয়া", "ভাইয়া", "আপু", "স্যার", "বস", "bhai", "vai", "md") }
    if (tokens.isNotEmpty() && tokens.all { tok -> h.contains(tok) }) return true
    return false
  }

  private fun matchScore(haystackRaw: String, queryRaw: String): Int {
    val h = norm(haystackRaw)
    val q = norm(queryRaw)
    return when {
      h == q -> 5
      h.startsWith(q) -> 4
      h.contains(q) -> 3
      tokensMatch(haystackRaw, queryRaw) -> 2
      matchesContactName(haystackRaw, queryRaw) -> 1
      else -> 0
    }
  }

  fun isLikelyChatHeaderContactText(raw: String): Boolean {
    val clean = stripInvisible(raw).trim()
    if (clean.length < 2 || clean.length > 65) return false
    val lower = clean.lowercase()
    if (lower.matches(Regex("^\\d{1,2}:\\d{2}.*")) || lower.matches(Regex("^\\d+$"))) return false
    val blocked =
      listOf(
        "last seen",
        "online",
        "typing",
        "active now",
        "active ",
        "tap here",
        "navigate up",
        "back",
        "voice call",
        "video call",
        "call",
        "more options",
        "search",
        "whatsapp",
        "messenger",
        "imo",
        "chats",
        "today",
        "yesterday",
        "মিউট",
        "কল",
        "ভিডিও",
        "খুঁজুন",
      )
    return blocked.none { lower == it || lower.startsWith(it) || (it.length >= 6 && lower.contains(it)) }
  }

  fun extractOpenChatName(acc: RiyaAccessibilityService): String? {
    val h = screenH(acc)
    if (!isChatScreenOpen(acc)) return null
    val candidates =
      all(acc)
        .mapNotNull { n ->
          if (n.isEditable) return@mapNotNull null
          val r = bounds(n)
          if (r.top < (h * 0.025f).toInt() || r.top > (h * 0.24f).toInt() || r.width() <= 0 || r.height() <= 0) {
            return@mapNotNull null
          }
          val t = stripInvisible(n.text?.toString()?.trim().orEmpty())
          if (!isLikelyChatHeaderContactText(t)) return@mapNotNull null
          Triple(t, r.top, r.left)
        }
        .sortedWith(compareBy<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
    return candidates.firstOrNull()?.first
  }

  fun resolveEffectiveContact(requestedContact: String, openChatName: String?): String {
    val q = requestedContact.trim()
    val genericRefs =
      setOf(
        "",
        "current",
        "current chat",
        "open chat",
        "খোলা চ্যাট",
        "বর্তমান চ্যাট",
        "এখানে",
        "তাকে",
        "ওকে",
        "উনাকে",
      )
    if (q.lowercase() in genericRefs && !openChatName.isNullOrBlank()) {
      return openChatName
    }
    return q
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
    // Case 1: Target contact's chat was already open (minimized in background or currently visible) -> use directly!
    if (isChatScreenOpen(acc) && (headerHas(acc, contact) || extractOpenChatName(acc)?.let { matchesContactName(it, contact) } == true)) {
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
        val relaunch = launchIntentFor(ctx, app)
        if (relaunch != null) {
          ctx.startActivity(relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          Thread.sleep(500)
          acc.readEntireScreenTree()
        }
        return rootPkg(acc) in allowedPkgs
      }
      if (isChatScreenOpen(acc) && (headerHas(acc, contact) || extractOpenChatName(acc)?.let { matchesContactName(it, contact) } == true)) {
        return true
      }
    }
    return true
  }

  fun openChat(acc: RiyaAccessibilityService, contact: String): Boolean {
    val h = screenH(acc)
    acc.readEntireScreenTree()
    // 0. If already inside target chat, done immediately
    if (bottomEditable(acc, h) != null && (headerHas(acc, contact) || extractOpenChatName(acc)?.let { matchesContactName(it, contact) } == true)) {
      return true
    }

    // 1. First look directly on the visible top chat list (WhatsApp / imo / Messenger main list)
    if (clickRowFor(acc, contact)) return true

    // 2. Not visible on initial top screen -> search autonomously WITHOUT asking Boss
    searchInApp(acc, contact)
    Thread.sleep(750)
    acc.readEntireScreenTree()
    if (clickRowFor(acc, contact)) return true

    // Retry briefly for async search result population
    Thread.sleep(550)
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
          // Chat/search result rows lie below top app/search bar (y >= 0.115*h) and above bottom nav (y <= 0.90*h)
          val inListArea = r.top >= (h * 0.115f).toInt() && r.bottom <= (h * 0.90f).toInt()
          val notLeftAvatarOnly = r.right > (w * 0.22f).toInt() && (r.left >= (w * 0.12f).toInt() || rawText.isNotEmpty() || rawDesc.isNotEmpty())
          val score = matchScore(labelStr, query)
          if (score > 0 && inListArea && notLeftAvatarOnly) Triple(n, score, r.top) else null
        }
        .sortedWith(compareByDescending<Triple<AccessibilityNodeInfo, Int, Int>> { it.second }.thenBy { it.third })

    for ((n, _, _) in candidates) {
      val nr = bounds(n)
      val row = clickableAncestor(n)
      if (row != null && !row.isEditable && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        Thread.sleep(400)
        return true
      }
      if (nr.width() > 0 && nr.height() > 0) {
        val safeTapX =
          if (nr.width() > (w * 0.35f).toInt()) maxOf(nr.exactCenterX(), w * 0.48f).coerceAtMost(w * 0.85f)
          else nr.exactCenterX()
        if (acc.tapAt(safeTapX, nr.exactCenterY())) {
          Thread.sleep(400)
          return true
        }
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
    val openName = extractOpenChatName(acc)
    if (openName != null && matchesContactName(openName, contact)) return true
    return all(acc).any { n ->
      !n.isEditable && bounds(n).top < h * 0.25f && matchesContactName(label(n), contact)
    }
  }

  private fun isInput(n: AccessibilityNodeInfo): Boolean =
    n.isEditable || (n.className?.toString()?.contains("EditText") == true)

  private fun effectiveInputText(n: AccessibilityNodeInfo?): String {
    if (n == null) return ""
    if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
    val raw = n.text?.toString()?.trim().orEmpty()
    val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
    if (raw.isEmpty() || (hint.isNotEmpty() && raw.equals(hint, ignoreCase = true))) return ""
    val lower = raw.lowercase()
    if (lower in setOf("message", "type a message", "aa", "মেসেজ লিখুন", "মেসেজ", "ask meta ai or search")) {
      return ""
    }
    return raw
  }

  private fun bottomEditable(acc: RiyaAccessibilityService, h: Int): AccessibilityNodeInfo? =
    all(acc).filter { isInput(it) && bounds(it).top > h * 0.45 }.maxByOrNull { bounds(it).top }

  private fun writeMessage(
    ctx: Context,
    acc: RiyaAccessibilityService,
    msg: String,
    h: Int,
  ): Boolean {
    val cleanMsg = msg.trim()
    if (cleanMsg.isEmpty()) return false
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
      acc.readEntireScreenTree()
      box = bottomEditable(acc, h)
    }
    if (box == null) return false

    val cleanNorm = norm(cleanMsg)
    val initialText = effectiveInputText(box)
    if (initialText.isNotEmpty() && norm(initialText) == cleanNorm) {
      return true
    }

    setText(box, cleanMsg)
    Thread.sleep(240)
    // Refresh accessibility tree BEFORE inspecting text so we never double-paste on stale node text!
    acc.readEntireScreenTree()
    val boxAfterSet = bottomEditable(acc, h)
    var now = effectiveInputText(boxAfterSet)
    val nowNorm = norm(now)
    val prefixNorm = norm(cleanMsg.take(12))

    if (nowNorm.isEmpty() || !nowNorm.contains(prefixNorm)) {
      if (boxAfterSet != null && now.isNotEmpty()) {
        setText(boxAfterSet, "")
        Thread.sleep(100)
      }
      val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      cm.setPrimaryClip(ClipData.newPlainText("lxr_ai", cleanMsg))
      acc.readEntireScreenTree()
      bottomEditable(acc, h)?.let {
        it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        it.performAction(AccessibilityNodeInfo.ACTION_PASTE)
      }
      Thread.sleep(260)
      acc.readEntireScreenTree()
      now = effectiveInputText(bottomEditable(acc, h))
    }

    // Safety dedupe guard if any Android IME concatenated cleanMsg twice inside composer
    val dedupCheckNorm = norm(now)
    if (cleanNorm.length >= 4 && dedupCheckNorm == cleanNorm + cleanNorm) {
      bottomEditable(acc, h)?.let {
        setText(it, cleanMsg)
        Thread.sleep(160)
      }
    }
    acc.readEntireScreenTree()
    return effectiveInputText(bottomEditable(acc, h)).isNotEmpty()
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
        Thread.sleep(280)
        acc.readEntireScreenTree()
        val left = effectiveInputText(bottomEditable(acc, h))
        if (left.isEmpty()) return "পাঠানো হয়েছে (ইমোজি বাটন এড়িয়ে সঠিক সেন্ড বাটনে)"
      }
    }

    val box0 = bottomEditable(acc, h) ?: return "লেখার বক্স হারিয়ে গেছে"
    val boxTop = bounds(box0).top
    val probe = msg.trim().take(20)
    val preSendBubbleCount =
      if (probe.isNotEmpty()) {
        all(acc).count { n ->
          val t = n.text?.toString() ?: ""
          val top = bounds(n).top
          t.contains(probe) && top > h * 0.14 && top < boxTop
        }
      } else 0

    var found: AccessibilityNodeInfo? = null
    for (i in 0 until 6) {
      found = findSend(acc, box0)
      if (found != null) break
      Thread.sleep(220)
      acc.readEntireScreenTree()
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

    fun hasConfirmedSendNow(): Boolean {
      val left = effectiveInputText(bottomEditable(acc, h))
      if (left.isEmpty()) return true
      if (probe.isNotEmpty()) {
        val postCount =
          all(acc).count { n ->
            val t = n.text?.toString() ?: ""
            val top = bounds(n).top
            t.contains(probe) && top > h * 0.14 && top < boxTop
          }
        if (postCount > preSendBubbleCount) return true
      }
      return false
    }

    for (i in 0 until 6) {
      Thread.sleep(220)
      acc.readEntireScreenTree()
      if (hasConfirmedSendNow()) return "পাঠানো হয়েছে"
    }

    // Only retry Send if message truly was NOT sent yet
    findSend(acc, box0)?.let { s2 ->
      val r2 = bounds(s2)
      acc.tapAt(r2.exactCenterX(), r2.exactCenterY())
    }
    Thread.sleep(450)
    acc.readEntireScreenTree()
    return if (hasConfirmedSendNow()) "পাঠানো হয়েছে" else "লেখা বক্সে আছে, পাঠানো যায়নি"
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
    val cleanMsg = msg.trim()
    if (cleanMsg.isEmpty()) return "কী লিখবো বলোনি বস"
    if (!openApp(ctx, acc, app)) return "${app.label} খুলতে পারিনি বস"

    acc.readEntireScreenTree()
    val openChatHeader = if (!app.isAi) extractOpenChatName(acc) else null
    val effectiveContact =
      if (app.isAi) "" else resolveEffectiveContact(contact, openChatHeader)

    if (!app.isAi && effectiveContact.isBlank()) {
      return "কাকে মেসেজ পাঠাবো বলোনি বস"
    }

    if (!app.isAi) {
      val alreadyInRightChat =
        isChatScreenOpen(acc) &&
          (headerHas(acc, effectiveContact) ||
            (openChatHeader != null && matchesContactName(openChatHeader, effectiveContact)))
      if (!alreadyInRightChat) {
        prepareChatSurface(ctx, acc, app, effectiveContact)
        if (!openChat(acc, effectiveContact)) {
          return "${app.label}-এ '$effectiveContact' নামের চ্যাট পাইনি বস"
        }
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until && bottomEditable(acc, screenH(acc)) == null) {
          Thread.sleep(180)
          acc.readEntireScreenTree()
        }
        Thread.sleep(220)
        acc.readEntireScreenTree()
        if (!headerHas(acc, effectiveContact)) {
          // Wait briefly for chat header text animation to settle before declaring mismatch
          val headerUntil = System.currentTimeMillis() + 1200L
          while (System.currentTimeMillis() < headerUntil && !headerHas(acc, effectiveContact)) {
            Thread.sleep(180)
            acc.readEntireScreenTree()
          }
          if (!headerHas(acc, effectiveContact)) {
            return "চ্যাট খুলেছি কিন্তু হেডারে '$effectiveContact' মিলছে না, তাই পাঠাইনি বস"
          }
        }
      }
    }
    val h = screenH(acc)
    if (!writeMessage(ctx, acc, cleanMsg, h)) return "${app.label}-এ লেখার বক্স খুঁজে পাইনি বস"
    val res = sendTyped(acc, app, h, cleanMsg)
    val elapsed = System.currentTimeMillis() - startMs
    val recipientLabel = if (app.isAi) "" else (extractOpenChatName(acc) ?: effectiveContact)
    if (res.contains("পাঠানো হয়েছে")) {
      if (!app.isAi) {
        RiyaRuntimeBridge.recordOutgoingMessage(app.label, recipientLabel, cleanMsg)
        com.example.data.Store.remember(
          ctx,
          "[আমার পাঠানো উত্তর / SENT_BY_ME][${app.label} -> $recipientLabel]: $cleanMsg",
        )
      }
      RiyaState.markTaskCompleted(
        "${app.label}${if (app.isAi) "" else " ($recipientLabel)"}-এ লেখা পাঠানো হয়েছে: \"$cleanMsg\"",
        "সফলভাবে পাঠানো সম্পন্ন হয়েছে (${elapsed}ms)",
      )
    }
    return "${app.label}${if (app.isAi) "" else " / $recipientLabel"}: $res — পাঠানো লেখা: \"$cleanMsg\" (${elapsed}ms)"
  }
}
