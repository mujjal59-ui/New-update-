package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RiyaAccessibilityService : AccessibilityService() {
  companion object {
    private const val TAG = "RiyaAccessibilitySvc"

    @Volatile var instance: RiyaAccessibilityService? = null
      private set

    @Volatile var currentScreenSummary: String = ""
    @Volatile var currentApp: String = ""
  }

  private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.e(TAG, "Recovered from coroutine error in AccessibilityService", throwable)
  }

  private val serviceScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)

  private val shotExec = Executors.newSingleThreadExecutor()
  private val snapshotExec = Executors.newSingleThreadExecutor()

  @Volatile private var lastScan = 0L
  @Volatile private var scanScheduled = false

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    RiyaRuntimeBridge.setServiceConnected(true)
    AccessibilityStatusMonitor.refreshNow(this, speakIfTurnedOff = false)
    RiyaVoiceManager.initialize(this)
    com.example.data.RiyaState.resume.value++
    Log.d(TAG, "LXR--AI AccessibilityService connected")
    snapshotExec.execute {
      try {
        readEntireScreenTree()
      } catch (_: Throwable) {}
    }
  }

  override fun onRebind(intent: Intent?) {
    super.onRebind(intent)
    instance = this
    RiyaRuntimeBridge.setServiceConnected(true)
    AccessibilityStatusMonitor.refreshNow(this, speakIfTurnedOff = false)
    Log.d(TAG, "LXR--AI AccessibilityService rebound cleanly")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    try {
      if (instance !== this) {
        instance = this
        RiyaRuntimeBridge.setServiceConnected(true)
      }
      val pkg = event.packageName?.toString().orEmpty()
      if (pkg.isNotEmpty() && pkg != packageName) {
        currentApp = pkg
      }
      // Do not burn binder/CPU scanning our own UI or rapid content ticks
      if (pkg == packageName) return
      val now = System.currentTimeMillis()
      val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
      val minGap = if (isStateChange) 700L else 1800L
      if ((isStateChange || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) &&
        now - lastScan >= minGap &&
        !scanScheduled
      ) {
        lastScan = now
        scanScheduled = true
        snapshotExec.execute {
          try {
            readEntireScreenTree(includeAllWindows = false)
          } catch (_: Throwable) {
          } finally {
            scanScheduled = false
          }
        }
      }
    } catch (e: Throwable) {
      scanScheduled = false
      Log.e(TAG, "Safe screen snapshot error", e)
    }
  }

  override fun onInterrupt() {
    Log.w(TAG, "LXR--AI AccessibilityService interrupted")
    AccessibilityStatusMonitor.refreshNow(this, speakIfTurnedOff = true)
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    try {
      Keep.ensureAccessibility(this)
    } catch (_: Throwable) {}
    super.onTaskRemoved(rootIntent)
  }

  override fun onUnbind(intent: Intent?): Boolean {
    if (instance === this) {
      instance = null
      AccessibilityStatusMonitor.onServiceDisconnectedFromOs(this)
    }
    Log.w(TAG, "LXR--AI AccessibilityService unbound by system/user")
    return true
  }

  override fun onDestroy() {
    if (instance === this) {
      instance = null
      AccessibilityStatusMonitor.onServiceDisconnectedFromOs(this)
    }
    serviceScope.cancel()
    try {
      snapshotExec.shutdownNow()
      shotExec.shutdownNow()
    } catch (_: Throwable) {}
    super.onDestroy()
  }

  /**
   * Mandatory Full-Screen Tree Reader:
   * Inspects interactive windows + rootInActiveWindow recursively so LXR--AI reads the ENTIRE screen
   * before and during every automated task.
   */
  fun readEntireScreenTree(includeAllWindows: Boolean = true): ScreenSnapshot {
    val nodes = mutableListOf<ScreenNodeInfo>()
    val summaryLines = mutableListOf<String>()
    var activePkg = ""
    var windowCount = 0

    try {
      val rootList = mutableListOf<AccessibilityNodeInfo>()
      if (includeAllWindows) {
        val interactiveWindows = windows
        if (!interactiveWindows.isNullOrEmpty()) {
          windowCount = interactiveWindows.size
          interactiveWindows.forEach { win ->
            win.root?.let { root ->
              if (activePkg.isBlank()) {
                activePkg = root.packageName?.toString().orEmpty()
              }
              rootList.add(root)
            }
          }
        }
      }
      if (rootList.isEmpty()) {
        rootInActiveWindow?.let { root ->
          windowCount = 1
          activePkg = root.packageName?.toString().orEmpty()
          rootList.add(root)
        }
      }

      val seenLines = LinkedHashSet<String>()
      fun dfs(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 40 || nodes.size > 400) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val cls = node.className?.toString().orEmpty()

        val info =
          ScreenNodeInfo(
            text = txt,
            contentDescription = desc,
            viewId = viewId,
            className = cls,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            bounds = rect,
          )
        nodes.add(info)

        val readable = txt.ifBlank { desc }
        if (readable.isNotBlank() && seenLines.size < 60) {
          seenLines.add(readable)
        }

        for (i in 0 until node.childCount) {
          dfs(node.getChild(i), depth + 1)
        }
      }

      rootList.forEach { dfs(it, 0) }
      summaryLines.addAll(seenLines)
    } catch (e: Exception) {
      Log.e(TAG, "Error reading full screen tree", e)
    }

    val resolvedPkg = activePkg.ifBlank { currentApp.ifBlank { "unknown" } }
    if (resolvedPkg != packageName && resolvedPkg != "unknown") {
      currentApp = resolvedPkg
    }
    if (summaryLines.isNotEmpty()) {
      currentScreenSummary =
        "বর্তমান অ্যাপ: $resolvedPkg | স্ক্রিন দৃশ্য: ${summaryLines.take(15).joinToString(", ").take(350)}"
    }

    val snapshot =
      ScreenSnapshot(
        packageName = resolvedPkg,
        windowCount = windowCount,
        timestamp = System.currentTimeMillis(),
        totalNodes = nodes.size,
        clickableCount = nodes.count { it.isClickable },
        editableCount = nodes.count { it.isEditable },
        summaryLines = summaryLines,
        nodes = nodes,
      )
    RiyaRuntimeBridge.updateScreenSnapshot(snapshot)
    return snapshot
  }

  // স্ক্রিন পড়া: উপর থেকে নিচে, বাঁ থেকে ডানে সাজানো। বাটন, লেখার বক্স, সুইচ আলাদা করে চিহ্নিত।
  fun readScreen(): String {
    readEntireScreenTree()
    val root = rootInActiveWindow ?: return "স্ক্রিন পাওয়া যায়নি"
    val rows = mutableListOf<Triple<Int, Int, String>>()
    fun walk(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || rows.size > 180 || depth > 40) return
      val raw = n.text?.toString()?.trim()
      val desc = n.contentDescription?.toString()?.trim()
      val t = if (!raw.isNullOrEmpty()) raw else desc
      val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim() else null
      val r = Rect()
      n.getBoundsInScreen(r)
      val tag =
        when {
          n.isEditable -> " [লেখার বক্স]"
          n.isCheckable -> if (n.isChecked) " [সুইচ: চালু]" else " [সুইচ: বন্ধ]"
          n.isClickable -> " [বাটন]"
          else -> ""
        }
      val label: String? =
        when {
          !t.isNullOrEmpty() -> t.take(120)
          n.isEditable ->
            if (!hint.isNullOrEmpty()) "(খালি লেখার বক্স, হিন্ট: ${hint.take(40)})"
            else "(খালি লেখার বক্স)"
          else -> null
        }
      if (label != null) rows.add(Triple(r.top, r.left, label + tag))
      for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
    }
    walk(root, 0)
    val lines =
      rows
        .sortedWith(compareBy<Triple<Int, Int, String>>({ it.first / 20 }, { it.second }))
        .map { it.third }
        .distinct()
    if (lines.isEmpty()) return "স্ক্রিনে পড়ার মতো কিছু পাওয়া যায়নি"
    return "অ্যাপ: ${root.packageName ?: ""}\n" + lines.joinToString("\n").take(4000)
  }

  // চ্যাট বা লেখার বক্সে মেসেজ লেখা ও সেন্ড (WhatsApp, imo, Messenger, Gemini, ChatGPT)
  fun sendMessageInCurrentChat(msg: String): String {
    readEntireScreenTree()
    val root = rootInActiveWindow ?: return "স্ক্রিন পাওয়া যায়নি"
    val pkg = root.packageName?.toString().orEmpty()
    if (pkg in ImoSendPrecisionEngine.IMO_PACKAGES) {
      val diag =
        ImoSendPrecisionEngine.performLiveImoSend(root, msg) { x, y -> tapAt(x, y) }
      readEntireScreenTree()
      return if (diag.succeeded) {
        "হয়েছে, বস! ইমোতে ইমোজি বাটন এড়িয়ে সঠিক সেন্ড বাটনে মেসেজ পাঠিয়ে দিয়েছি।"
      } else {
        "ইমোতে মেসেজ লেখা হয়েছে কিন্তু সেন্ড বাটন নিশ্চিত করা যায়নি (${diag.strategyUsed})।"
      }
    }

    var box = findEditableNode(root)
    if (box == null) {
      findNodeByKeys(root, listOf("message", "মেসেজ", "type a message", "ask", "lekho"))?.let {
        clickNode(it)
        Thread.sleep(240)
      }
      box = findEditableNode(rootInActiveWindow)
    }
    if (box == null) return "মেসেজ লেখার বক্স খুঁজে পাইনি বস"
    box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val b =
      Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg) }
    if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return "মেসেজ লেখা যায়নি বস"
    Thread.sleep(240)
    val freshRoot = rootInActiveWindow
    val freshBox = findEditableNode(freshRoot) ?: box
    val send = findSendNode(freshRoot, freshBox)
    if (send != null) {
      val sr = Rect()
      send.getBoundsInScreen(sr)
      val ok = clickNode(send) || tapAt(sr.exactCenterX(), sr.exactCenterY())
      if (ok) {
        Thread.sleep(220)
        readEntireScreenTree()
        return "হয়েছে, বস! মেসেজ পাঠিয়ে দিয়েছি।"
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
      freshBox.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    ) {
      Thread.sleep(220)
      readEntireScreenTree()
      return "হয়েছে, বস! মেসেজ পাঠিয়ে দিয়েছি।"
    }
    return "মেসেজ লেখা হয়েছে কিন্তু সেন্ড বাটন খুঁজে পাইনি বস, একবার দেখে নিন"
  }

  private fun clickNode(n: AccessibilityNodeInfo): Boolean {
    var c: AccessibilityNodeInfo? = n
    var depth = 0
    while (c != null && !c.isClickable && depth < 5) {
      c = c.parent
      depth++
    }
    return (c ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
  }

  private fun findNodeByKeys(n: AccessibilityNodeInfo?, keys: List<String>): AccessibilityNodeInfo? {
    if (n == null) return null
    val t = (n.text?.toString() ?: "").lowercase()
    val d = (n.contentDescription?.toString() ?: "").lowercase()
    val h = (if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() ?: "" else "").lowercase()
    if (keys.any { k -> t.contains(k) || d.contains(k) || h.contains(k) }) return n
    for (i in 0 until n.childCount) findNodeByKeys(n.getChild(i), keys)?.let { return it }
    return null
  }

  private fun collectClickable(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
    if (n == null) return
    if (n.isClickable) out.add(n)
    for (i in 0 until n.childCount) collectClickable(n.getChild(i), out)
  }

  // সেন্ড বাটন: ইমোজি/রিঅ্যাকশন/ক্যামেরা/মাইক বাদ দিয়ে সঠিক সেন্ড বাটন নির্বাচন
  private fun findSendNode(root: AccessibilityNodeInfo?, box: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    val all = mutableListOf<AccessibilityNodeInfo>()
    collectClickable(root, all)
    val bad =
      listOf(
        "emoji",
        "smiley",
        "sticker",
        "gif",
        "reaction",
        "heart",
        "attach",
        "camera",
        "gallery",
        "photo",
        "mic",
        "voice",
        "plus",
        "add",
        "ইমোজি",
        "ক্যামেরা",
        "মাইক",
        "ছবি",
      )
    val words = listOf("send", "submit", "সেন্ড", "পাঠান", "btn_send", "send_btn")
    val bb = Rect()
    box.getBoundsInScreen(bb)
    val midY = bb.centerY()

    fun nodeLabel(n: AccessibilityNodeInfo): String =
      ((n.text?.toString() ?: "") +
          " " +
          (n.contentDescription?.toString() ?: "") +
          " " +
          (n.viewIdResourceName ?: ""))
        .lowercase()

    all.firstOrNull { n ->
      val s = nodeLabel(n)
      val r = Rect()
      n.getBoundsInScreen(r)
      !bad.any { s.contains(it) } &&
        words.any { s.contains(it) } &&
        r.top >= bb.top - 40
    }?.let { return it }

    return all
      .filter { n ->
        val s = nodeLabel(n)
        val r = Rect()
        n.getBoundsInScreen(r)
        !bad.any { s.contains(it) } &&
          r.width() > 0 &&
          r.top >= bb.top - 24 &&
          r.left >= bb.right - 20 &&
          kotlin.math.abs(r.centerY() - midY) < (bb.height().coerceAtLeast(60) * 0.85f)
      }
      .maxByOrNull { n ->
        val r = Rect()
        n.getBoundsInScreen(r)
        r.right
      }
  }

  fun findEditableNode(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (n == null) return null
    if (n.isEditable) return n
    for (i in 0 until n.childCount) {
      val r = findEditableNode(n.getChild(i))
      if (r != null) return r
    }
    return null
  }

  fun hasText(t: String): Boolean {
    val q = t.trim().lowercase()
    if (q.isEmpty()) return false
    fun f(n: AccessibilityNodeInfo?): Boolean {
      if (n == null) return false
      if (
        (n.text?.toString()?.lowercase() ?: "").contains(q) ||
          (n.contentDescription?.toString()?.lowercase() ?: "").contains(q)
      )
        return true
      for (i in 0 until n.childCount) if (f(n.getChild(i))) return true
      return false
    }
    return f(rootInActiveWindow)
  }

  /** স্ক্রিনে না পেলে নিচে স্ক্রল করে খোঁজে (সর্বোচ্চ [maxScrolls] বার, দ্রুত গতির) */
  fun scrollUntilFound(t: String, maxScrolls: Int = 4): Boolean {
    if (hasText(t)) return true
    repeat(maxScrolls) {
      if (!scroll("down").contains("সম্পন্ন")) return false
      Thread.sleep(240)
      readEntireScreenTree()
      if (hasText(t)) return true
    }
    return false
  }

  fun clickText(t: String, isContact: Boolean = false): String {
    readEntireScreenTree()
    val root = rootInActiveWindow ?: return "স্ক্রিন পাওয়া যায়নি"
    val query = t.trim().lowercase()
    val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1800)

    // ১. সরাসরি স্ক্রিনে ম্যাচিং টেক্সট বা বাটন ক্লিক (সার্চ বক্স ও EditText বাদ দিয়ে রেজাল্ট সারিকে অগ্রাধিকার)
    val matches = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
    fun findMatch(n: AccessibilityNodeInfo?) {
      if (n == null) return
      val txt = n.text?.toString()?.lowercase()?.trim() ?: ""
      val desc = n.contentDescription?.toString()?.lowercase()?.trim() ?: ""
      val hit = if (isContact) txt.contains(query) else (txt.contains(query) || desc.contains(query))
      if (hit && !n.isEditable) {
        val r = Rect()
        n.getBoundsInScreen(r)
        val isBelowTopSearch = r.top >= (screenH * 0.16f).toInt()
        val exactBonus = if (txt == query || desc == query) 20 else 0
        val zoneBonus = if (isBelowTopSearch) 50 else 0
        matches.add(n to (zoneBonus + exactBonus))
      }
      for (i in 0 until n.childCount) findMatch(n.getChild(i))
    }
    findMatch(root)

    val orderedMatches = matches.sortedByDescending { it.second }
    for ((node, _) in orderedMatches) {
      val r = Rect()
      node.getBoundsInScreen(r)
      val ok = clickNode(node) || (r.width() > 0 && r.height() > 0 && tapAt(r.exactCenterX(), r.exactCenterY()))
      if (ok) {
        Thread.sleep(240)
        readEntireScreenTree()
        return "'$t' ক্লিক করা হয়েছে"
      }
    }

    // ২. না পেলে Infinix Hot 60 Pro+ (Android 16) নিয়ম: উপরে বা নিচে থাকা Search বার/আইকন দিয়ে খোঁজা
    val searchNode = findSearchNode(root)
    if (searchNode != null) {
      if (searchNode.isEditable) {
        injectTextAndEnter(searchNode, t)
      } else {
        clickNode(searchNode)
        Thread.sleep(240)
        val freshRoot = rootInActiveWindow
        val focusNode =
          freshRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: freshRoot?.let { findEditableNode(it) }
        if (focusNode != null) {
          injectTextAndEnter(focusNode, t)
        } else {
          return "'$t' সার্চ বক্স খোলা যায়নি"
        }
      }
      Thread.sleep(300)
      readEntireScreenTree()
      findSubmitNode(rootInActiveWindow)?.let { clickNode(it) }
      return "'$t' সরাসরি পাওয়া যায়নি, তাই Infinix সার্চবারে লিখে সার্চ দেওয়া হয়েছে"
    }

    return "'$t' খুঁজে পাওয়া যায়নি"
  }

  private fun findSubmitNode(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (n == null) return null
    val d = (n.contentDescription?.toString() ?: "").lowercase()
    val t = (n.text?.toString() ?: "").lowercase()
    if (
      n.isClickable &&
        (d == "search" ||
          d == "go" ||
          d == "সার্চ" ||
          t == "search" ||
          t == "go" ||
          t == "সার্চ" ||
          d.contains("submit") ||
          d.contains("search button"))
    )
      return n
    for (i in 0 until n.childCount) findSubmitNode(n.getChild(i))?.let { return it }
    return null
  }

  /** Infinix Android 16: উপরের ডানের Search আইকন এবং নিচের Settings Search বার দুটোই শনাক্ত করে */
  private fun findSearchNode(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (n == null) return null
    val candidates = mutableListOf<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo?) {
      if (node == null) return
      val desc = (node.contentDescription?.toString() ?: "").lowercase()
      val text = (node.text?.toString() ?: "").lowercase()
      val id = (node.viewIdResourceName ?: "").lowercase()
      val hint = (if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() ?: "" else "").lowercase()
      val combined = "$desc $text $id $hint"
      if (InfinixDevicePlaybook.searchButtonKeywords.any { combined.contains(it) } &&
        (node.isClickable || node.isEditable)
      ) {
        candidates.add(node)
      }
      for (i in 0 until node.childCount) walk(node.getChild(i))
    }
    walk(n)
    return candidates.firstOrNull { it.isEditable } ?: candidates.firstOrNull()
  }

  /** চালু স্ক্রিনের সুইচ (Wi-Fi/Bluetooth) খুঁজে অন/অফ করে। ফল যাচাই করে। */
  fun setSwitch(enable: Boolean, labels: List<String>): String {
    fun all(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
      if (n == null) return
      if (n.isCheckable) out.add(n)
      for (i in 0 until n.childCount) all(n.getChild(i), out)
    }
    fun label(n: AccessibilityNodeInfo): String {
      val sb = StringBuilder()
      var p: AccessibilityNodeInfo? = n
      var depth = 0
      while (p != null && depth < 3) {
        sb.append(p.text ?: "").append(' ').append(p.contentDescription ?: "").append(' ')
        for (i in 0 until p.childCount) {
          val c = p.getChild(i)
          sb.append(c?.text ?: "").append(' ')
        }
        p = p.parent
        depth++
      }
      return sb.toString().lowercase()
    }
    repeat(4) {
      val list = mutableListOf<AccessibilityNodeInfo>()
      all(rootInActiveWindow, list)
      if (list.isNotEmpty()) {
        val sw = list.firstOrNull { n -> labels.any { label(n).contains(it) } } ?: list.first()
        if (sw.isChecked == enable) return "ALREADY"
        clickNode(sw)
        Thread.sleep(260)
        sw.refresh()
        readEntireScreenTree()
        return if (sw.isChecked == enable) "OK" else "CLICKED"
      }
      Thread.sleep(200)
    }
    return "NOTFOUND"
  }

  fun typeText(t: String): String {
    val root = rootInActiveWindow ?: return "স্ক্রিন নেই"
    val box = findEditableNode(root) ?: return "কোনো লেখার বক্স পাওয়া যায়নি"
    val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
    return if (box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "টাইপ সম্পন্ন"
    else "টাইপ করা যায়নি"
  }

  fun global(a: String): String {
    val act =
      when (a) {
        "home" -> GLOBAL_ACTION_HOME
        "back" -> GLOBAL_ACTION_BACK
        "recents" -> GLOBAL_ACTION_RECENTS
        "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
        "lock" -> GLOBAL_ACTION_LOCK_SCREEN
        else -> return "অজানা কমান্ড"
      }
    return if (performGlobalAction(act)) "সম্পন্ন" else "ব্যর্থ"
  }

  fun scroll(dir: String): String {
    fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
      if (n == null) return null
      if (n.isScrollable) return n
      for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
      return null
    }
    val s = find(rootInActiveWindow) ?: return "স্ক্রল করার জায়গা পাওয়া যায়নি"
    val a =
      if (dir == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
      else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    return if (s.performAction(a)) "স্ক্রল সম্পন্ন" else "স্ক্রল ব্যর্থ"
  }

  fun clickFirstVideo(): Boolean {
    val root = rootInActiveWindow ?: return false
    if (root.packageName?.toString() != "com.google.android.youtube") return false
    val dur = Regex("\\d+\\s*(minutes?|seconds?|hours?|মিনিট|সেকেন্ড|ঘণ্টা)")
    fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
      if (n == null) return null
      val d = n.contentDescription?.toString()?.lowercase()
      if (
        n.isClickable &&
          d != null &&
          !d.contains("sponsored") &&
          !d.contains("বিজ্ঞাপন") &&
          (d.contains("play video") || d.contains("ভিডিও চালান") || dur.containsMatchIn(d))
      )
        return n
      for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
      return null
    }
    return find(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
  }

  fun screenshotJpeg(maxDim: Int = 720, quality: Int = 55, cb: (ByteArray?) -> Unit) {
    if (Build.VERSION.SDK_INT < 30) {
      cb(null)
      return
    }
    try {
      takeScreenshot(
        Display.DEFAULT_DISPLAY,
        shotExec,
        object : TakeScreenshotCallback {
          override fun onSuccess(r: ScreenshotResult) {
            var hw: Bitmap? = null
            var bmp: Bitmap? = null
            var sm: Bitmap? = null
            try {
              hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
              bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
              r.hardwareBuffer.close()
              if (bmp == null) {
                cb(null)
                return
              }
              val sc = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
              sm =
                if (sc < 1f)
                  Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * sc).toInt().coerceAtLeast(64),
                    (bmp.height * sc).toInt().coerceAtLeast(64),
                    true,
                  )
                else bmp
              val o = ByteArrayOutputStream()
              sm.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(25, 85), o)
              cb(o.toByteArray())
            } catch (_: Exception) {
              cb(null)
            } finally {
              if (sm != null && sm !== bmp) {
                try { sm.recycle() } catch (_: Throwable) {}
              }
              if (bmp != null) {
                try { bmp.recycle() } catch (_: Throwable) {}
              }
              if (hw != null) {
                try { hw.recycle() } catch (_: Throwable) {}
              }
            }
          }

          override fun onFailure(errorCode: Int) {
            cb(null)
          }
        },
      )
    } catch (_: Exception) {
      cb(null)
    }
  }

  fun tapAt(x: Float, y: Float): Boolean {
    val path = Path().apply { moveTo(x, y) }
    return dispatchGesture(
      GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(),
      null,
      null,
    )
  }

  fun tapFallback() {
    val m = resources.displayMetrics
    val path = Path().apply { moveTo(m.widthPixels * 0.5f, m.heightPixels * 0.38f) }
    dispatchGesture(
      GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build(),
      null,
      null,
    )
  }

  /**
   * Executes a fast multi-step task while strictly reading the entire screen at every phase.
   * Completes within ~1 to 3 seconds using tight 220ms–300ms transitions.
   */
  fun executeFastTask(
    targetPackage: String,
    queryOrCommand: String,
    enforceFullScreenRead: Boolean,
    fastMode: Boolean,
    onCompleted: (success: Boolean, screenSummary: String, elapsedMs: Long) -> Unit,
  ) {
    val startMs = System.currentTimeMillis()
    val stepDelayMs = if (fastMode) 220L else 480L

    serviceScope.launch {
      try {
        val preSnapshot = readEntireScreenTree()
        val stepsLog = StringBuilder()
        stepsLog.append("পূর্ব স্ক্রিন: ${preSnapshot.totalNodes} এলিমেন্ট পঠিত। ")

        if (targetPackage.isNotBlank() && targetPackage != packageName) {
          val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
          if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            delay(stepDelayMs + 160L)
          }
        }

        val activeSnapshot = readEntireScreenTree()
        stepsLog.append(
          "টার্গেট স্ক্রিন (${activeSnapshot.packageName}): ${activeSnapshot.totalNodes} এলিমেন্ট। "
        )

        var actionSucceeded = true
        when {
          targetPackage in ImoSendPrecisionEngine.IMO_PACKAGES &&
            queryOrCommand.contains("send", ignoreCase = true) -> {
            val diag =
              ImoSendPrecisionEngine.performLiveImoSend(rootInActiveWindow, null) { x, y ->
                tapAt(x, y)
              }
            stepsLog.append(
              "imo সেন্ড প্রিসিশন (${diag.strategyUsed}): ${if (diag.succeeded) "সফল" else "প্রস্তুত"}। "
            )
            actionSucceeded = diag.inputFound || activeSnapshot.totalNodes > 0
          }
          queryOrCommand.isNotBlank() &&
            !queryOrCommand.equals("check_messages", ignoreCase = true) &&
            !queryOrCommand.equals("send_precision_check", ignoreCase = true) -> {
            val foundBySearch = performUniversalInfinixSearch(queryOrCommand, stepDelayMs)
            val postSearchSnap = readEntireScreenTree()
            stepsLog.append(
              "সার্চ '$queryOrCommand': ${if (foundBySearch) "খুঁজে পাওয়া গেছে" else "স্ক্রিন স্ক্যান সম্পন্ন"} (${postSearchSnap.summaryLines.take(3).joinToString(", ")})। "
            )
          }
          else -> {
            if (enforceFullScreenRead) {
              val preview = activeSnapshot.summaryLines.take(4).joinToString(" | ")
              stepsLog.append("সম্পূর্ণ স্ক্রিন রিড: $preview")
            }
          }
        }

        val finalSnapshot = readEntireScreenTree()
        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(280L)
        val summary = "$stepsLog মোট ${finalSnapshot.totalNodes}টি নোড রিড সম্পন্ন (${elapsed}ms)।"
        onCompleted(actionSucceeded, summary, elapsed)
      } catch (e: Exception) {
        val elapsed = System.currentTimeMillis() - startMs
        Log.e(TAG, "Fast task execution failed safely", e)
        onCompleted(false, "টাস্ক চালানোর সময় নিরাপদ রিকভারি: ${e.localizedMessage}", elapsed)
      }
    }
  }

  private suspend fun performUniversalInfinixSearch(query: String, stepDelayMs: Long): Boolean {
    val root = rootInActiveWindow ?: return false
    val searchNode = findSearchNode(root)
    if (searchNode != null) {
      if (searchNode.isEditable) {
        injectTextAndEnter(searchNode, query)
        delay(stepDelayMs)
        return true
      } else {
        clickNode(searchNode)
        delay(stepDelayMs)
        val refreshedRoot = rootInActiveWindow
        val editNode = refreshedRoot?.let { findEditableNode(it) }
        if (editNode != null) {
          injectTextAndEnter(editNode, query)
          delay(stepDelayMs)
          return true
        }
      }
    }

    repeat(4) {
      val curRoot = rootInActiveWindow ?: return false
      readEntireScreenTree()
      val match = findNodeByKeys(curRoot, listOf(query.lowercase()))
      if (match != null) {
        clickNode(match)
        return true
      }
      val scrollable = findFirstScrollableNode(curRoot) ?: return false
      scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
      delay(stepDelayMs)
    }
    return false
  }

  private fun injectTextAndEnter(node: AccessibilityNodeInfo, text: String) {
    val args =
      Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
      }
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }
  }

  private fun findFirstScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (root.isScrollable) return root
    for (i in 0 until root.childCount) {
      val child = root.getChild(i) ?: continue
      val found = findFirstScrollableNode(child)
      if (found != null) return found
    }
    return null
  }
}
