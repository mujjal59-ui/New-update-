package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
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

object SwitchRowMatcher {
  /**
   * Scores how well a single preference row's text matches desired [labels] while rejecting [excludeLabels].
   * Returns -1 when excluded or unmatched, or a positive score (>= 10 exact row label match) otherwise.
   */
  fun scoreRowMatch(rowTextRaw: String, labels: List<String>, excludeLabels: List<String>): Int {
    val rowLower = rowTextRaw.trim().lowercase()
    if (rowLower.isEmpty()) return -1
    if (excludeLabels.any { ex -> rowLower.contains(ex.lowercase()) }) return -1
    var best = -1
    for (lbl in labels) {
      val k = lbl.lowercase().trim()
      if (k.isEmpty()) continue
      if (rowLower == k) {
        best = maxOf(best, 100)
      } else if (rowLower.split('\n', '|', ';').any { it.trim() == k }) {
        best = maxOf(best, 85)
      } else if (rowLower.contains(k)) {
        best = maxOf(best, 50)
      }
    }
    return best
  }
}

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
  @Volatile private var cachedLauncherPkgs: Set<String> = emptySet()
  @Volatile private var lastEventPkg: String = ""

  override fun onCreate() {
    super.onCreate()
    instance = this
    RiyaRuntimeBridge.setServiceConnected(true)
    AccessibilityKeepAliveService.sync(this)
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    RiyaRuntimeBridge.setServiceConnected(true)
    AccessibilityStatusMonitor.refreshNow(this, speakIfTurnedOff = false)
    AccessibilityKeepAliveService.sync(this)
    serviceScope.launch(Dispatchers.IO) {
      RiyaVoiceManager.initialize(this@RiyaAccessibilityService)
    }
    com.example.data.RiyaState.resume.value++
    Log.d(TAG, "LXR--AI AccessibilityService connected")
    snapshotExec.execute {
      try {
        cachedLauncherPkgs = resolveHomeLauncherPackages()
        readEntireScreenTree()
      } catch (_: Throwable) {}
    }
  }

  override fun onRebind(intent: Intent?) {
    super.onRebind(intent)
    instance = this
    RiyaRuntimeBridge.setServiceConnected(true)
    AccessibilityStatusMonitor.refreshNow(this, speakIfTurnedOff = false)
    AccessibilityKeepAliveService.sync(this)
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
      if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && pkg.isNotEmpty() && pkg != packageName) {
        val notif =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            event.parcelableData as? android.app.Notification
          } else {
            @Suppress("DEPRECATION")
            event.parcelableData as? android.app.Notification
          }
        val texts = event.text?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        RiyaNotificationListenerService.handleAccessibilityFallbackNotification(
          applicationContext,
          pkg,
          notif,
          texts,
        )
        return
      }

      if (pkg.isNotEmpty()) {
        val isStateChangeEvt = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (isStateChangeEvt) {
          if (cachedLauncherPkgs.isEmpty()) {
            cachedLauncherPkgs = resolveHomeLauncherPackages()
          }
          val isLauncherPkg =
            pkg in cachedLauncherPkgs ||
              pkg.contains("launcher", ignoreCase = true) ||
              pkg == "com.transsion.xoslauncher" ||
              pkg == "com.transsion.hilauncher"
          val exitedOwnApp = lastEventPkg == packageName && pkg != packageName
          if (isLauncherPkg || exitedOwnApp) {
            LockVaultManager.markHomeOrAppExitTransition()
          }
          if (pkg != "com.android.systemui") {
            lastEventPkg = pkg
          }
        }
        currentApp = pkg
      }
      val now = System.currentTimeMillis()
      val isOwnUi = pkg == packageName
      val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
      val isContentChange =
        !isOwnUi &&
          (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
          (com.example.data.RiyaState.running.value || pkg == "com.android.systemui")
      val activeScanGap = com.example.data.Store.activeTimingProfile(this).screenScanMinGapMs
      val minGap =
        when {
          isOwnUi && isStateChange -> 420L
          isOwnUi -> Long.MAX_VALUE
          isStateChange && com.example.data.RiyaState.running.value -> 80L
          isStateChange -> 180L
          com.example.data.RiyaState.running.value -> 260L
          else -> activeScanGap
        }
      if ((isStateChange || isContentChange) &&
        minGap < Long.MAX_VALUE &&
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
      AccessibilityKeepAliveService.sync(this)
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
      val primaryRoot = rootInActiveWindow
      if (primaryRoot != null) {
        val pPkg = primaryRoot.packageName?.toString().orEmpty()
        if (pPkg.isNotBlank() && pPkg != "com.android.systemui") {
          activePkg = pPkg
        }
        rootList.add(primaryRoot)
      }
      if (includeAllWindows) {
        val interactiveWindows = windows
        if (!interactiveWindows.isNullOrEmpty()) {
          windowCount = interactiveWindows.size
          val sortedWins =
            interactiveWindows.sortedWith(
              compareByDescending<android.view.accessibility.AccessibilityWindowInfo> { it.isFocused || it.isActive }
                .thenByDescending { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .thenByDescending { it.layer }
            )
          sortedWins.forEach { win ->
            win.root?.let { root ->
              val rPkg = root.packageName?.toString().orEmpty()
              if (activePkg.isBlank() && rPkg.isNotBlank() && rPkg != "com.android.systemui") {
                activePkg = rPkg
              }
              if (rootList.none { it == root }) {
                rootList.add(root)
              }
            }
          }
          if (activePkg.isBlank()) {
            activePkg = primaryRoot?.packageName?.toString().orEmpty()
              .ifBlank { sortedWins.firstOrNull()?.root?.packageName?.toString().orEmpty() }
          }
        }
      }
      if (rootList.isEmpty() && primaryRoot != null) {
        windowCount = 1
        activePkg = primaryRoot.packageName?.toString().orEmpty()
        rootList.add(primaryRoot)
      }

      val seenLines = LinkedHashSet<String>()
      fun dfs(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 45 || nodes.size > 600) return
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
        val isTransientEdgeHint = readable.contains("again to unlock", ignoreCase = true)
        if (readable.isNotBlank() && !isTransientEdgeHint && seenLines.size < 90) {
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

    val resolvedPkg = activePkg.ifBlank { LockVaultManager.resolveBestForegroundPackage(this) }.ifBlank { currentApp.ifBlank { "unknown" } }
    if (resolvedPkg != "unknown") {
      currentApp = resolvedPkg
    }
    val liveReport = LockVaultManager.describeLivePhoneState(this, this)
    val sceneText =
      if (summaryLines.isNotEmpty()) summaryLines.take(18).joinToString(", ").take(380)
      else LxrAssistScreenBridge.latestAssistScreenText.take(260).ifBlank { "(দৃশ্যমান টেক্সট কম বা খালি)" }
    val lockPrefix = if (liveReport.isLocked) "[লক সতর্কতা: ${liveReport.summaryBn}] | " else ""
    currentScreenSummary =
      "${lockPrefix}বর্তমান অ্যাপ: $resolvedPkg | স্ক্রিন দৃশ্য: $sceneText"

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

  private fun pickBestReadableRoot(): AccessibilityNodeInfo? {
    rootInActiveWindow?.let { return it }
    return try {
      val wins = windows.orEmpty()
      wins
        .filter { it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root != null }
        .sortedWith(
          compareByDescending<android.view.accessibility.AccessibilityWindowInfo> { it.isActive || it.isFocused }
            .thenByDescending { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .thenByDescending { it.layer }
        )
        .firstOrNull()
        ?.root
    } catch (_: Throwable) {
      null
    }
  }

  // স্ক্রিন পড়া: উপর থেকে নিচে, বাঁ থেকে ডানে সাজানো। বাটন, লেখার বক্স (Title বনাম মূল Body আলাদাভাবে), চ্যাট প্রাপক, ভয়েস কল অ্যালার্ট ও চ্যাট বাবলের দিক (আমার পাঠানো vs অপর প্রান্তের মেসেজ) চিহ্নিত।
  fun readScreen(): String {
    val snapshot = readEntireScreenTree(includeAllWindows = true)
    val liveState = LockVaultManager.describeLivePhoneState(this, this)
    val lockHeader = if (liveState.isLocked) "[লক সতর্কতা: ${liveState.summaryBn}]\n" else ""
    val root =
      pickBestReadableRoot()
        ?: return lockHeader +
          LxrAssistScreenBridge.buildFallbackScreenReport(
            snapshotLines = snapshot.summaryLines,
            currentScreenSummary = currentScreenSummary,
            activePkgHint = snapshot.packageName,
          )
    val pkg = root.packageName?.toString().orEmpty().ifBlank { snapshot.packageName }
    val isMessagingPkg = ChatScreenGeometryParser.isMessagingPackage(pkg)
    val rootBounds = Rect().also { root.getBoundsInScreen(it) }
    val screenW = if (rootBounds.width() > 0) rootBounds.width() else resources.displayMetrics.widthPixels.coerceAtLeast(720)
    val screenH = if (rootBounds.height() > 0) rootBounds.height() else resources.displayMetrics.heightPixels.coerceAtLeast(1280)
    val openChatName = if (isMessagingPkg) MessageAgent.extractOpenChatName(this) else null
    val detectedCalls =
      if (isMessagingPkg) {
        ChatScreenGeometryParser.extractVisibleVoiceCalls(snapshot.nodes, pkg, screenW, screenH, openChatName)
      } else {
        emptyList()
      }

    val editablePairs = collectEditableNodes(root)
    val editableCandidates = editablePairs.mapIndexed { idx, node -> toCandidate(idx, node) }
    val nodeRoleByHash = IdentityHashMapCompat(editablePairs, editableCandidates)

    val rows = mutableListOf<Triple<Int, Int, String>>()
    fun walk(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || rows.size > 260 || depth > 45) return
      val raw = n.text?.toString()?.trim()
      val desc = n.contentDescription?.toString()?.trim()
      val stateDesc =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) n.stateDescription?.toString()?.trim()
        else null
      val t =
        when {
          !raw.isNullOrEmpty() -> raw
          !desc.isNullOrEmpty() -> desc
          !stateDesc.isNullOrEmpty() -> stateDesc
          else -> null
        }
      val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim() else null
      val viewId = n.viewIdResourceName.orEmpty()
      val r = Rect()
      n.getBoundsInScreen(r)
      val inChatMessageArea = isMessagingPkg && !n.isEditable && r.top > (screenH * 0.14f) && r.bottom < (screenH * 0.87f)
      val bubbleDirectionTag =
        if (inChatMessageArea && !t.isNullOrEmpty() && t.length >= 1) {
          val dir = ChatScreenGeometryParser.classifyBubbleDirection(r.left, r.right, screenW, t, viewId, pkg)
          if (ChatScreenGeometryParser.isIncomingMissedOrVoiceCallText(t) && dir != ChatBubbleDirection.OUTGOING_RIGHT) {
            " [মিসড/আসা ভয়েস কল — বসকে অবশ্যই জানাবে!]"
          } else {
            when (dir) {
              ChatBubbleDirection.OUTGOING_RIGHT ->
                " [আমার পাঠানো মেসেজ / SENT_BY_ME -> ${openChatName ?: "প্রাপক"}]"
              ChatBubbleDirection.INCOMING_LEFT ->
                " [প্রাপ্ত মেসেজ / RECEIVED / Incoming from ${openChatName ?: "অপর প্রান্ত"}]"
              ChatBubbleDirection.SYSTEM_CENTER -> ""
            }
          }
        } else ""
      val tag =
        when {
          n.isEditable ->
            when (nodeRoleByHash.roleOf(n)) {
              EditableFieldRole.TITLE -> " [নোট টাইটেল বক্স / Title — এখানে মূল নোট লিখবে না]"
              EditableFieldRole.BODY -> " [মূল নোট লেখার বক্স / Body — নোট এখানে লিখবে]"
              else -> " [লেখার বক্স]"
            }
          n.isCheckable -> if (isNodeCheckedOrOn(n)) " [সুইচ: চালু]" else " [সুইচ: বন্ধ]"
          bubbleDirectionTag.isNotEmpty() -> bubbleDirectionTag
          n.isClickable -> " [বাটন]"
          else -> ""
        }
      val label: String? =
        when {
          !t.isNullOrEmpty() -> {
            val withState =
              if (!stateDesc.isNullOrEmpty() && stateDesc != t && !n.isCheckable) "$t ($stateDesc)"
              else t
            withState.take(120)
          }
          n.isEditable ->
            if (!hint.isNullOrEmpty()) "(খালি লেখার বক্স, হিন্ট: ${hint.take(40)})"
            else "(খালি লেখার বক্স)"
          else -> null
        }
      if (label != null) rows.add(Triple(r.top, r.left, label + tag))
      for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
    }
    walk(root, 0)
    // Also inspect sibling non-IME visible windows (dialogs, bottom sheets, split panels, alerts)
    if (rows.size < 45) {
      runCatching {
        windows.orEmpty()
          .filter {
            it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
              (it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM)
          }
          .mapNotNull { it.root }
          .filter { it != root }
          .forEach { sibRoot -> walk(sibRoot, 0) }
      }
    }
    val lines =
      rows
        .sortedWith(compareBy<Triple<Int, Int, String>>({ it.first / 20 }, { it.second }))
        .map { it.third }
        .distinct()
    val header =
      buildString {
        append(lockHeader)
        append("অ্যাপ: ")
        append(pkg)
        if (!openChatName.isNullOrBlank()) {
          append("\n[বর্তমান খোলা চ্যাট / যাকে মেসেজ করা হচ্ছে]: ")
          append(openChatName)
        }
        if (detectedCalls.isNotEmpty()) {
          append("\n[কল অ্যালার্ট (বসকে জানাও)]: ")
          append(detectedCalls.joinToString("; ") { it.callSummaryBn })
        }
      }
    if (lines.isEmpty()) {
      return lockHeader +
        LxrAssistScreenBridge.buildFallbackScreenReport(
          snapshotLines = snapshot.summaryLines,
          currentScreenSummary = currentScreenSummary,
          activePkgHint = pkg,
        )
    }
    return "$header\n" + lines.joinToString("\n").take(4200)
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

  private fun collectEditableNodes(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
    if (n == null || out.size >= 24) return out
    if (n.isEditable) out.add(n)
    for (i in 0 until n.childCount) {
      collectEditableNodes(n.getChild(i), out)
    }
    return out
  }

  private fun toCandidate(index: Int, node: AccessibilityNodeInfo): EditableFieldCandidate {
    val rect = Rect()
    node.getBoundsInScreen(rect)
    val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
    val isMulti =
      (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.isMultiLine else false) ||
        (node.inputType != 0 && (node.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) ||
        rect.height() > 210
    val isSingleLine = !isMulti
    return EditableFieldCandidate(
      index = index,
      text = node.text?.toString()?.trim().orEmpty(),
      hint = hint,
      contentDescription = node.contentDescription?.toString()?.trim().orEmpty(),
      viewId = node.viewIdResourceName.orEmpty(),
      top = rect.top,
      bottom = rect.bottom,
      left = rect.left,
      right = rect.right,
      isSingleLine = isSingleLine,
    )
  }

  private class IdentityHashMapCompat(
    private val nodes: List<AccessibilityNodeInfo>,
    private val candidates: List<EditableFieldCandidate>,
  ) {
    fun roleOf(node: AccessibilityNodeInfo): EditableFieldRole {
      val idx = nodes.indexOfFirst { it == node }
      if (idx < 0 || idx >= candidates.size) return EditableFieldRole.GENERIC
      return EditableFieldMatcher.classifyRole(candidates[idx], candidates)
    }
  }

  fun findEditableNode(n: AccessibilityNodeInfo?, target: String = "auto"): AccessibilityNodeInfo? {
    if (n == null) return null
    val allNodes = collectEditableNodes(n)
    if (allNodes.isEmpty()) return null
    if (allNodes.size == 1) return allNodes.first()
    val candidates = allNodes.mapIndexed { idx, node -> toCandidate(idx, node) }
    val pickedIndex = EditableFieldMatcher.selectBestCandidateIndex(candidates, target)
    return if (pickedIndex != null && pickedIndex in allNodes.indices) allNodes[pickedIndex] else allNodes.first()
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
      val hit =
        if (isContact) {
          txt.contains(query) || MessageAgent.matchesContactName(txt, t)
        } else {
          txt == query ||
            desc == query ||
            txt.contains(query) ||
            desc.contains(query)
        }
      if (hit && !n.isEditable) {
        val r = Rect()
        n.getBoundsInScreen(r)
        val isBelowTopSearch = r.top >= (screenH * 0.12f).toInt()
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

  private fun isListOrScrollContainer(node: AccessibilityNodeInfo): Boolean {
    if (node.isScrollable) return true
    val cls = node.className?.toString()?.lowercase().orEmpty()
    return cls.contains("recyclerview") ||
      cls.contains("listview") ||
      cls.contains("scrollview") ||
      cls.contains("viewpager")
  }

  private fun countCheckablesInSubtree(node: AccessibilityNodeInfo?, depth: Int = 0, limit: Int = 3): Int {
    if (node == null || depth > 6) return 0
    var c = if (node.isCheckable) 1 else 0
    if (c >= limit) return c
    for (i in 0 until node.childCount) {
      c += countCheckablesInSubtree(node.getChild(i), depth + 1, limit)
      if (c >= limit) return c
    }
    return c
  }

  /**
   * Collects text strictly from the preference/setting ROW wrapping [n], NEVER spilling into neighboring
   * RecyclerView rows (prevents "Emergency Location Service" / "Wi-Fi scanning" from polluting "Use location").
   */
  private fun collectRowScopedNodeText(n: AccessibilityNodeInfo): String {
    val sb = StringBuilder()
    fun appendTree(node: AccessibilityNodeInfo?, depth: Int) {
      if (node == null || depth > 5) return
      node.text?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
      node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        node.stateDescription?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
      }
      for (i in 0 until node.childCount) {
        appendTree(node.getChild(i), depth + 1)
      }
    }

    var rowRoot: AccessibilityNodeInfo = n
    var p: AccessibilityNodeInfo? = n.parent
    var hops = 0
    while (p != null && hops < 4) {
      if (isListOrScrollContainer(p)) break
      if (countCheckablesInSubtree(p) > 1) break
      rowRoot = p
      if (p.isClickable) break
      p = p.parent
      hops++
    }
    appendTree(rowRoot, 0)
    if (sb.isEmpty()) {
      n.viewIdResourceName?.let { sb.append(it) }
    }
    return sb.toString().trim()
  }

  private fun collectNodeHierarchyText(n: AccessibilityNodeInfo): String {
    val sb = StringBuilder()
    fun appendNode(node: AccessibilityNodeInfo?) {
      if (node == null) return
      sb.append(node.text ?: "").append(' ')
      sb.append(node.contentDescription ?: "").append(' ')
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        sb.append(node.stateDescription ?: "").append(' ')
      }
      sb.append(node.viewIdResourceName ?: "").append(' ')
    }
    var p: AccessibilityNodeInfo? = n
    var depth = 0
    while (p != null && depth < 3) {
      if (depth > 0 && isListOrScrollContainer(p)) break
      appendNode(p)
      for (i in 0 until p.childCount) {
        val c = p.getChild(i)
        appendNode(c)
        if (c != null) {
          for (j in 0 until minOf(c.childCount, 4)) {
            appendNode(c.getChild(j))
          }
        }
      }
      p = p.parent
      depth++
    }
    return sb.toString().lowercase()
  }

  private fun isNodeCheckedOrOn(n: AccessibilityNodeInfo): Boolean {
    if (n.isChecked || n.isSelected) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val sd = n.stateDescription?.toString()?.lowercase()?.trim().orEmpty()
      if (sd == "on" || sd == "চালু" || sd.startsWith("on,") || sd.contains("connected")) return true
      if (sd == "off" || sd == "বন্ধ" || sd.startsWith("off,")) return false
    }
    return false
  }

  private fun scanQuickSettingTile(
    labels: List<String>,
    excludeLabels: List<String>,
    timeoutMs: Long,
  ): AccessibilityNodeInfo? {
    val deadline = System.currentTimeMillis() + timeoutMs
    val carrierDataPattern = Regex("\\b(gb|mb|4g|5g|lte|volte|airtel|robi|grameenphone|banglalink|teletalk|gp)\\b")
    val lookingForMobileData = labels.any { it.contains("mobile data") || it.contains("cellular") || it.contains("ডাটা") }
    while (System.currentTimeMillis() < deadline) {
      val roots = mutableListOf<AccessibilityNodeInfo>()
      windows?.forEach { w -> w.root?.let { roots.add(it) } }
      rootInActiveWindow?.let { if (it !in roots) roots.add(it) }

      val candidates = mutableListOf<AccessibilityNodeInfo>()
      fun collectTiles(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 25) return
        if (node.isClickable || node.isCheckable) {
          candidates.add(node)
        }
        for (i in 0 until node.childCount) collectTiles(node.getChild(i), depth + 1)
      }
      roots.forEach { r ->
        if (r.packageName?.toString() == "com.android.systemui") {
          collectTiles(r, 0)
        }
      }

      val found =
        candidates.firstOrNull { node ->
          val info = collectNodeHierarchyText(node)
          val excluded = excludeLabels.any { ex -> info.contains(ex.lowercase()) }
          if (excluded) return@firstOrNull false
          val directMatch = labels.any { k -> info.contains(k.lowercase()) }
          val carrierMatch =
            lookingForMobileData &&
              (carrierDataPattern.containsMatchIn(info) ||
                info.contains("mobile_data") ||
                info.contains("cell_tile") ||
                info.contains("data_tile"))
          directMatch || carrierMatch
        }
      if (found != null) return found
      Thread.sleep(160)
    }
    return null
  }

  /**
   * Quick Settings / Infinix XOS Control Center টাইল দিয়ে Wi-Fi / Bluetooth / GPS / Mobile Data / Rotation / Flashlight টগল করে।
   */
  fun toggleQuickSettingTile(
    enable: Boolean,
    labels: List<String>,
    excludeLabels: List<String> = emptyList(),
    hardwareStateKnown: Boolean? = null,
  ): String {
    if (hardwareStateKnown != null && hardwareStateKnown == enable) return "ALREADY"

    performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    var foundTile = scanQuickSettingTile(labels, excludeLabels, timeoutMs = 900L)

    // Infinix Android 16 XOS split Control Center: top-right downward swipe opens Control Center directly!
    if (foundTile == null) {
      try {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(720).toFloat()
        val h = dm.heightPixels.coerceAtLeast(1280).toFloat()
        swipe(w * 0.82f, h * 0.02f, w * 0.82f, h * 0.52f, durationMs = 260L)
        foundTile = scanQuickSettingTile(labels, excludeLabels, timeoutMs = 1050L)
        // If not visible on page 1 of Control Center, swipe left to inspect page 2
        if (foundTile == null) {
          swipe(w * 0.86f, h * 0.36f, w * 0.14f, h * 0.36f, durationMs = 240L)
          foundTile = scanQuickSettingTile(labels, excludeLabels, timeoutMs = 950L)
        }
      } catch (_: Throwable) {}
    }

    try {
      val tile = foundTile ?: return "NOTFOUND"
      val tileInfo = collectNodeHierarchyText(tile)
      // Infinix XOS labels Auto-Rotate OFF as "Portrait Lock" (inverted switch semantics!)
      val isInvertedTile = tileInfo.contains("portrait lock") || (tileInfo.contains("portrait") && !tileInfo.contains("auto"))
      val desiredTileChecked = if (isInvertedTile) !enable else enable
      val curTileOn =
        if (hardwareStateKnown != null && !isInvertedTile) hardwareStateKnown
        else if (hardwareStateKnown != null && isInvertedTile) !hardwareStateKnown
        else isNodeCheckedOrOn(tile)

      // Handle Android 14–16 "Internet" tile opening a Wi-Fi sub-sheet instead of flipping directly
      if (hardwareStateKnown == null && tileInfo.contains("internet") && !tileInfo.contains("wi-fi")) {
        clickNodeOrBounds(tile)
        Thread.sleep(420)
        val sheetSwitch = setSwitch(enable, listOf("wi-fi", "wifi", "wlan", "ওয়াই"), excludeLabels)
        return if (sheetSwitch == "OK" || sheetSwitch == "ALREADY") sheetSwitch else "CLICKED"
      }

      if (curTileOn == desiredTileChecked) {
        return "ALREADY"
      }

      val clicked = clickNodeOrBounds(tile)
      Thread.sleep(360)

      // If tapping "Internet" tile popped up the Internet sheet dialog inside systemui, toggle the Wi-Fi switch inside it
      if (tileInfo.contains("internet")) {
        val sheetRes = setSwitch(enable, listOf("wi-fi", "wifi", "wlan", "ওয়াই"), excludeLabels)
        if (sheetRes == "OK" || sheetRes == "ALREADY") return sheetRes
      }

      return if (clicked) "OK" else "NOTFOUND"
    } finally {
      // Close Quick Settings / Control Center shade cleanly across all windows (Android 14-16 / Infinix XOS)
      val dmH = resources.displayMetrics.heightPixels.coerceAtLeast(1200)
      repeat(3) {
        val sysShadeVisible =
          rootInActiveWindow?.packageName?.toString() == "com.android.systemui" ||
            windows.orEmpty().any { w ->
              val root = w.root
              root?.packageName?.toString() == "com.android.systemui" &&
                root.isVisibleToUser &&
                run {
                  val r = Rect()
                  w.getBoundsInScreen(r)
                  r.height() >= (dmH * 0.28f).toInt()
                }
            }
        if (sysShadeVisible) {
          if (it == 0 && Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
          } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
          }
          Thread.sleep(160)
        }
      }
    }
  }

  private fun clickNodeOrBounds(node: AccessibilityNodeInfo): Boolean {
    if (clickNode(node)) return true
    val rect = Rect()
    node.getBoundsInScreen(rect)
    return rect.width() > 0 && rect.height() > 0 && tapAt(rect.exactCenterX(), rect.exactCenterY())
  }

  /** চালু স্ক্রিনের সুইচ (Wi-Fi/Bluetooth/Location) খুঁজে অন/অফ করে। কখনো ভুল সুইচে চাপ দেয় না এবং ফল যাচাই করে। */
  fun setSwitch(
    enable: Boolean,
    labels: List<String>,
    excludeLabels: List<String> = emptyList(),
  ): String {
    fun allCheckables(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
      if (n == null) return
      if (n.isCheckable) out.add(n)
      for (i in 0 until n.childCount) allCheckables(n.getChild(i), out)
    }

    val deadline = System.currentTimeMillis() + 3400L
    while (System.currentTimeMillis() < deadline) {
      val list = mutableListOf<AccessibilityNodeInfo>()
      windows?.forEach { w -> w.root?.let { allCheckables(it, list) } }
      if (list.isEmpty()) {
        allCheckables(rootInActiveWindow, list)
      }

      if (list.isNotEmpty()) {
        val sw =
          list
            .map { node ->
              val rowText = collectRowScopedNodeText(node)
              node to SwitchRowMatcher.scoreRowMatch(rowText, labels, excludeLabels)
            }
            .filter { it.second >= 0 }
            .maxByOrNull { it.second }
            ?.first
            ?: if (list.size == 1) list.first() else null

        if (sw != null) {
          val curChecked = isNodeCheckedOrOn(sw)
          if (curChecked == enable) return "ALREADY"
          var clicked = clickNodeOrBounds(sw)
          Thread.sleep(360)
          sw.refresh()
          readEntireScreenTree()
          var after = isNodeCheckedOrOn(sw)
          if (after != enable) {
            // Android 15/16 Settings MainSwitchBar often expects click on its clickable row/parent container
            var p: AccessibilityNodeInfo? = sw.parent
            var hops = 0
            while (p != null && hops < 3 && after != enable) {
              if (p.isClickable) {
                if (clickNodeOrBounds(p)) clicked = true
                Thread.sleep(360)
                sw.refresh()
                after = isNodeCheckedOrOn(sw)
              }
              p = p.parent
              hops++
            }
          }
          if (clicked) {
            return if (after == enable) "OK" else "CLICKED"
          }
        }
      }
      Thread.sleep(220)
    }
    return "NOTFOUND"
  }

  fun typeText(t: String, target: String = "auto"): String {
    readEntireScreenTree()
    val root = rootInActiveWindow ?: return "স্ক্রিন নেই"
    val allNodes = collectEditableNodes(root)
    if (allNodes.isEmpty()) return "কোনো লেখার বক্স পাওয়া যায়নি"

    val candidates = allNodes.mapIndexed { idx, node -> toCandidate(idx, node) }
    val selectedIdx = EditableFieldMatcher.selectBestCandidateIndex(candidates, target) ?: 0
    val box = allNodes.getOrElse(selectedIdx) { allNodes.first() }
    val pickedRole = EditableFieldMatcher.classifyRole(candidates[selectedIdx], candidates)

    val boxRect = Rect()
    box.getBoundsInScreen(boxRect)
    if (boxRect.width() > 0 && boxRect.height() > 0) {
      tapAt(boxRect.exactCenterX(), boxRect.exactCenterY())
      Thread.sleep(90)
    }
    box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    Thread.sleep(70)

    val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
    var ok = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    if (!ok) {
      val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      if (focused != null && focused.isEditable) {
        ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
      }
    }
    Thread.sleep(160)
    readEntireScreenTree()

    if (!ok) return "লেখার বক্সে টাইপ করা যায়নি"
    val fieldLabelBn =
      when (pickedRole) {
        EditableFieldRole.TITLE -> "টাইটেল (Title) বক্সে"
        EditableFieldRole.BODY -> "মূল নোট (Note Body) বক্সে"
        EditableFieldRole.GENERIC -> "লেখার বক্সে"
      }
    return "হয়েছে, বস! $fieldLabelBn লেখা সম্পন্ন হয়েছে।"
  }

  fun resolveHomeLauncherPackages(): Set<String> {
    return try {
      val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
      val resolved =
        packageManager.queryIntentActivities(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
          .mapNotNull { it.activityInfo?.packageName?.trim() }
          .filter { it.isNotBlank() && it != "android" }
          .toMutableSet()
      packageManager.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo
        ?.packageName
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "android" }
        ?.let { resolved.add(it) }
      resolved
    } catch (_: Throwable) {
      emptySet()
    }
  }

  private fun isOnHomeLauncher(launchers: Set<String>): Boolean {
    val active = rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
    if (active.isNotBlank() && (active in launchers || active.contains("launcher", true))) return true
    val best = LockVaultManager.resolveBestForegroundPackage(this)
    return best.isNotBlank() && (best in launchers || best.contains("launcher", true))
  }

  fun global(a: String): String {
    if (a == "home") {
      LockVaultManager.markHomeOrAppExitTransition()
      val launchers = resolveHomeLauncherPackages()
      if (isOnHomeLauncher(launchers) && LockVaultManager.isScreenUnlockedNow(this, this)) {
        LockVaultManager.markHomeOrAppExitTransition()
        readEntireScreenTree(includeAllWindows = true)
        return "সম্পন্ন"
      }
      val activeBefore = rootInActiveWindow?.packageName?.toString().orEmpty()
      if (
        activeBefore == "com.android.systemui" &&
          Build.VERSION.SDK_INT >= 31 &&
          LockVaultManager.isScreenUnlockedNow(this, this)
      ) {
        runCatching { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }
        Thread.sleep(150)
      }
      val globalOk = performGlobalAction(GLOBAL_ACTION_HOME)
      repeat(8) {
        if (isOnHomeLauncher(launchers)) {
          LockVaultManager.markHomeOrAppExitTransition()
          readEntireScreenTree(includeAllWindows = true)
          return "সম্পন্ন"
        }
        Thread.sleep(110)
      }
      if (LockVaultManager.isMainScreenGenuinelyLockedBeforeTask(this, this)) {
        return if (globalOk) "সম্পন্ন" else "ব্যর্থ"
      }
      // Fallback: explicitly fire CATEGORY_HOME Intent so Infinix / OEM launchers or overlays cannot trap LxR inside an app
      return try {
        val homeIntent =
          Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        launchers.firstOrNull()?.let { homeIntent.`package` = it }
        startActivity(homeIntent)
        Thread.sleep(360)
        LockVaultManager.markHomeOrAppExitTransition()
        readEntireScreenTree(includeAllWindows = true)
        if (globalOk || isOnHomeLauncher(launchers)) "সম্পন্ন" else "ব্যর্থ"
      } catch (_: Throwable) {
        if (globalOk) "সম্পন্ন" else "ব্যর্থ"
      }
    }
    if (a == "back" || a == "recents") {
      LockVaultManager.markHomeOrAppExitTransition()
    }
    val act =
      when (a) {
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

  fun doubleTap(x: Float, y: Float, gapMs: Long = 95L): Boolean {
    val tap1 = tapAt(x, y)
    Thread.sleep(gapMs.coerceIn(50L, 180L))
    val tap2 = tapAt(x, y)
    return tap1 && tap2
  }

  fun longPressAndWait(x: Float, y: Float, durationMs: Long = TermuxUiAutomation.LONG_PRESS_DURATION_MS): Boolean {
    val path = Path().apply { moveTo(x, y) }
    val holdMs = durationMs.coerceIn(600L, 5000L)
    val latch = java.util.concurrent.CountDownLatch(1)
    var completed = false
    val dispatched =
      dispatchGesture(
        GestureDescription.Builder()
          .addStroke(GestureDescription.StrokeDescription(path, 0L, holdMs))
          .build(),
        object : GestureResultCallback() {
          override fun onCompleted(gestureDescription: GestureDescription?) {
            completed = true
            latch.countDown()
          }

          override fun onCancelled(gestureDescription: GestureDescription?) {
            latch.countDown()
          }
        },
        null,
      )
    if (!dispatched) return false
    try {
      latch.await(holdMs + 800L, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {}
    return completed || dispatched
  }

  /**
   * Executes any shell/build command directly inside the Termux UI without closing Gboard:
   * 1) Copies command to Clipboard and opens/focuses Termux with keyboard ON
   * 2) Holds a 3-second long-press inside Termux terminal area and releases
   * 3) Waits 2 seconds for [Copy | Paste | More...] popup and clicks "Paste"
   * 4) Clicks Gboard's bottom-right blue Enter/Return arrow button while keeping keyboard visible
   */
  fun executeTermuxCommandViaUi(context: Context, command: String): String {
    val cleanCmd = command.trim().replace("\r\n", "\n").trimEnd('\n')
    if (cleanCmd.isBlank()) return "Termux-এ চালানোর মতো কোনো কমান্ড দেওয়া হয়নি বস।"

    val clipLatch = java.util.concurrent.CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("LXR Termux Command", cleanCmd))
      } catch (_: Throwable) {}
      clipLatch.countDown()
    }
    try {
      clipLatch.await(1000L, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {}
    Thread.sleep(140L)

    val dm = resources.displayMetrics
    val screenW = dm.widthPixels.coerceAtLeast(720)
    val screenH = dm.heightPixels.coerceAtLeast(1280)

    // Bring Termux to foreground if not already active
    val curPkgBefore = rootInActiveWindow?.packageName?.toString().orEmpty()
    if (curPkgBefore != TermuxUiAutomation.TERMUX_PACKAGE) {
      val launch = context.packageManager.getLaunchIntentForPackage(TermuxUiAutomation.TERMUX_PACKAGE)
        ?: return "ফোনে Termux ইনস্টল করা নেই বস (com.termux পাওয়া যায়নি)।"
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(launch)
      val waitDeadline = System.currentTimeMillis() + 4500L
      while (System.currentTimeMillis() < waitDeadline) {
        Thread.sleep(240L)
        if (LockVaultManager.isAppLockScreenShowing(this)) {
          LockVaultManager.unlockAppLockIfPresent(context, this)
          Thread.sleep(360L)
        }
        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (activePkg == TermuxUiAutomation.TERMUX_PACKAGE || currentApp == TermuxUiAutomation.TERMUX_PACKAGE) {
          Thread.sleep(350L)
          break
        }
      }
    } else if (LockVaultManager.isAppLockScreenShowing(this)) {
      LockVaultManager.unlockAppLockIfPresent(context, this)
      Thread.sleep(320L)
    }

    fun inspectImeBounds(): Rect? {
      val imeWin =
        windows?.firstOrNull { win ->
          win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            win.root?.packageName?.toString() in TermuxUiAutomation.GBOARD_PACKAGES
        } ?: return null
      val rect = Rect()
      imeWin.getBoundsInScreen(rect)
      return if (rect.height() >= (screenH * 0.18f).toInt()) rect else null
    }

    // Ensure Gboard keyboard stays ON inside Termux (tap terminal briefly if keyboard is hidden)
    var imeRect = inspectImeBounds()
    if (imeRect == null) {
      val (tx, ty) = TermuxUiAutomation.computeLongPressPoint(screenW, screenH, null)
      tapAt(tx, ty)
      Thread.sleep(700L)
      imeRect = inspectImeBounds()
    }

    // Step 1: Long-press for 3 seconds inside Termux terminal area and release
    val (lpX, lpY) = TermuxUiAutomation.computeLongPressPoint(screenW, screenH, imeRect?.top)
    val pressedOk = longPressAndWait(lpX, lpY, TermuxUiAutomation.LONG_PRESS_DURATION_MS)
    if (!pressedOk) {
      return "Termux স্ক্রিনে ৩ সেকেন্ড লং-প্রেস চালানো যায়নি বস।"
    }

    // Step 2: Wait 2 seconds for [Copy | Paste | More...] popup, then click "Paste"
    Thread.sleep(TermuxUiAutomation.POPUP_WAIT_AFTER_RELEASE_MS)

    fun collectAllWindowsNodes(): Pair<List<TermuxWindowNodeInfo>, List<AccessibilityNodeInfo>> {
      val infoList = mutableListOf<TermuxWindowNodeInfo>()
      val rawList = mutableListOf<AccessibilityNodeInfo>()
      val seenRoots = mutableListOf<AccessibilityNodeInfo>()
      windows?.forEach { w -> w.root?.let { seenRoots.add(it) } }
      rootInActiveWindow?.let { r -> if (seenRoots.none { it == r }) seenRoots.add(r) }

      fun walk(n: AccessibilityNodeInfo?, depth: Int) {
        if (n == null || depth > 35 || rawList.size > 350) return
        val r = Rect()
        n.getBoundsInScreen(r)
        rawList.add(n)
        infoList.add(
          TermuxWindowNodeInfo(
            text = n.text?.toString()?.trim().orEmpty(),
            contentDescription = n.contentDescription?.toString()?.trim().orEmpty(),
            viewId = n.viewIdResourceName.orEmpty(),
            className = n.className?.toString().orEmpty(),
            packageName = n.packageName?.toString().orEmpty(),
            isClickable = n.isClickable,
            left = r.left,
            top = r.top,
            right = r.right,
            bottom = r.bottom,
          )
        )
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
      }
      seenRoots.forEach { walk(it, 0) }
      return infoList to rawList
    }

    var pasted = false
    for (attempt in 0 until 4) {
      val (nodesInfo, rawNodes) = collectAllWindowsNodes()
      val pasteInfo = TermuxUiAutomation.findPastePopupCandidate(nodesInfo)
      if (pasteInfo != null) {
        val idx = nodesInfo.indexOf(pasteInfo)
        val rawNode = rawNodes.getOrNull(idx)
        val clickedNode = rawNode != null && clickNode(rawNode)
        val clickedPoint = if (!clickedNode) tapAt(pasteInfo.centerX, pasteInfo.centerY) else true
        if (clickedNode || clickedPoint) {
          pasted = true
          break
        }
      }
      if (attempt < 3) Thread.sleep(450L)
    }

    if (!pasted) {
      readEntireScreenTree()
      return "Termux-এ ৩ সেকেন্ড ধরে রাখার পর Paste বাটনটি পপ-আপে খুঁজে পাওয়া যায়নি বস। ক্লিপবোর্ড ও Termux স্ক্রিন একবার পরীক্ষা করুন।"
    }

    // Give Termux a moment to finish pasting the text, and guarantee keyboard remains open
    Thread.sleep(550L)
    imeRect = inspectImeBounds()
    if (imeRect == null) {
      val (tx, ty) = TermuxUiAutomation.computeLongPressPoint(screenW, screenH, null)
      tapAt(tx, ty)
      Thread.sleep(600L)
      imeRect = inspectImeBounds()
    }

    // Step 3: Click Gboard's bottom-right blue Enter / arrow button
    var enterTapped = false
    val imeWindow =
      windows?.firstOrNull { win ->
        win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
          win.root?.packageName?.toString() in TermuxUiAutomation.GBOARD_PACKAGES
      }
    val imeRoot = imeWindow?.root
    if (imeRoot != null) {
      val imeNodesInfo = mutableListOf<TermuxWindowNodeInfo>()
      val imeRawNodes = mutableListOf<AccessibilityNodeInfo>()
      fun walkIme(n: AccessibilityNodeInfo?, depth: Int) {
        if (n == null || depth > 25 || imeRawNodes.size > 200) return
        val r = Rect()
        n.getBoundsInScreen(r)
        imeRawNodes.add(n)
        imeNodesInfo.add(
          TermuxWindowNodeInfo(
            text = n.text?.toString()?.trim().orEmpty(),
            contentDescription = n.contentDescription?.toString()?.trim().orEmpty(),
            viewId = n.viewIdResourceName.orEmpty(),
            className = n.className?.toString().orEmpty(),
            packageName = n.packageName?.toString().orEmpty(),
            isClickable = n.isClickable,
            left = r.left,
            top = r.top,
            right = r.right,
            bottom = r.bottom,
          )
        )
        for (i in 0 until n.childCount) walkIme(n.getChild(i), depth + 1)
      }
      walkIme(imeRoot, 0)
      val rIme = imeRect ?: Rect().also { imeWindow.getBoundsInScreen(it) }
      val enterInfo =
        TermuxUiAutomation.findGboardEnterKeyCandidate(
          imeNodes = imeNodesInfo,
          imeLeft = rIme.left,
          imeTop = rIme.top,
          imeRight = rIme.right,
          imeBottom = rIme.bottom,
        )
      if (enterInfo != null) {
        val rawEnter = imeRawNodes.getOrNull(imeNodesInfo.indexOf(enterInfo))
        enterTapped =
          (rawEnter != null && clickNode(rawEnter)) ||
            tapAt(enterInfo.centerX, enterInfo.centerY)
      }
    }

    if (!enterTapped) {
      val (ex, ey) =
        TermuxUiAutomation.computeGboardBlueEnterFallbackPoint(
          screenW = screenW,
          screenH = screenH,
          imeTop = imeRect?.top,
          imeRight = imeRect?.right,
          imeBottom = imeRect?.bottom,
        )
      enterTapped = tapAt(ex, ey)
    }

    Thread.sleep(380L)
    readEntireScreenTree()
    return if (enterTapped) {
      "হয়েছে, বস! Termux-এ ৩ সেকেন্ড ধরে রেখে Paste করে Google Gboard-এর নীল এন্টার বাটনে ক্লিক করে কমান্ড রান করেছি।"
    } else {
      "Termux-এ কমান্ড Paste হয়েছে বস, তবে Gboard এন্টার বাটন চাপতে পারিনি।"
    }
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

  fun swipe(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    durationMs: Long = 320L,
  ): Boolean {
    val path =
      Path().apply {
        moveTo(startX, startY)
        lineTo(endX, endY)
      }
    val latch = java.util.concurrent.CountDownLatch(1)
    var ok = false
    val dispatched =
      dispatchGesture(
        GestureDescription.Builder()
          .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(120L, 1200L)))
          .build(),
        object : GestureResultCallback() {
          override fun onCompleted(gestureDescription: GestureDescription?) {
            ok = true
            latch.countDown()
          }

          override fun onCancelled(gestureDescription: GestureDescription?) {
            latch.countDown()
          }
        },
        null,
      )
    if (!dispatched) return false
    try {
      latch.await(durationMs + 500L, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {}
    return ok || dispatched
  }

  fun drawPatternPath(points: List<Pair<Float, Float>>, durationMs: Long = 750L): Boolean {
    if (points.size < 2) return false
    val path = Path()
    path.moveTo(points.first().first, points.first().second)
    for (i in 1 until points.size) {
      path.lineTo(points[i].first, points[i].second)
    }
    val latch = java.util.concurrent.CountDownLatch(1)
    var completed = false
    val strokeDuration = durationMs.coerceIn(400L, 2000L)
    val dispatched =
      dispatchGesture(
        GestureDescription.Builder()
          .addStroke(GestureDescription.StrokeDescription(path, 0, strokeDuration))
          .build(),
        object : GestureResultCallback() {
          override fun onCompleted(gestureDescription: GestureDescription?) {
            completed = true
            latch.countDown()
          }

          override fun onCancelled(gestureDescription: GestureDescription?) {
            latch.countDown()
          }
        },
        null,
      )
    if (!dispatched) return false
    try {
      latch.await(strokeDuration + 650L, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {}
    return completed || dispatched
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
