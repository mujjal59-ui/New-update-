package com.example.service

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.DeviceBrandProfile
import com.example.data.ScreenLockType
import com.example.data.Store

enum class UnlockStatus {
  ALREADY_UNLOCKED,
  UNLOCKED_SUCCESSFULLY,
  NO_CREDENTIALS_SAVED,
  ACCESSIBILITY_OFF,
  AUTO_UNLOCK_DISABLED,
  FAILED_TO_UNLOCK,
}

data class UnlockAttemptResult(
  val status: UnlockStatus,
  val messageBn: String,
)

/**
 * Manages stored Phone Screen Lock & App Lock credentials (up to 8-digit PIN/Password or 3x3 Pattern)
 * across Infinix, Xiaomi, Redmi, Realme, Tecno, itel & Android devices.
 *
 * Strictly enforces:
 * - Only auto-unlocks & re-locks when the device was ALREADY LOCKED at the start of Boss's command.
 * - NEVER locks an unlocked screen automatically without Boss's explicit permission.
 */
object LockVaultManager {
  private const val TAG = "LxrLockVault"

  private val appLockPackages =
    setOf(
      "com.transsion.applock",
      "com.transsion.phonemaster",
      "com.miui.securitycenter",
      "com.oplus.safecenter",
      "com.coloros.safecenter",
      "com.itel.applock",
      "com.tecno.applock",
      "com.infinix.applock",
    )

  private val appLockTitleHints =
    listOf(
      "app lock",
      "applock",
      "অ্যাপ লক",
      "draw pattern",
      "draw unlock pattern",
      "enter password",
      "enter pin",
      "verify password",
      "পাসওয়ার্ড দিন",
      "প্যাটার্ন আঁকুন",
      "পিন দিন",
      "privacy password",
    )

  fun detectDeviceBrand(): DeviceBrandProfile {
    val mfg = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()
    return when {
      mfg.contains("infinix") || brand.contains("infinix") -> DeviceBrandProfile.INFINIX
      mfg.contains("xiaomi") ||
        mfg.contains("redmi") ||
        mfg.contains("poco") ||
        brand.contains("xiaomi") ||
        brand.contains("redmi") ||
        brand.contains("poco") -> DeviceBrandProfile.XIAOMI_REDMI
      mfg.contains("realme") || brand.contains("realme") -> DeviceBrandProfile.REALME
      mfg.contains("tecno") || brand.contains("tecno") -> DeviceBrandProfile.TECNO
      mfg.contains("itel") || brand.contains("itel") -> DeviceBrandProfile.ITEL
      else -> DeviceBrandProfile.OTHER
    }
  }

  fun isDeviceLocked(ctx: Context): Boolean {
    return try {
      val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
      val screenOff = pm != null && !pm.isInteractive
      val keyguardLocked = km?.isKeyguardLocked == true
      screenOff || keyguardLocked
    } catch (_: Throwable) {
      false
    }
  }

  /** Pure helper: decides whether auto-relock after task is permitted. */
  fun shouldAutoRelockAfterTask(startedWithLockedScreen: Boolean, autoUnlockEnabled: Boolean): Boolean {
    return startedWithLockedScreen && autoUnlockEnabled
  }

  /**
   * Computes exact 3x3 center coordinates for Pattern Lock dots 1..9 given LockPatternView bounds:
   *  1  2  3
   *  4  5  6
   *  7  8  9
   */
  fun computePatternDotCenters(bounds: Rect): List<Pair<Float, Float>> {
    val w = bounds.width().toFloat()
    val h = bounds.height().toFloat()
    // Keep square proportion centered inside container if bounds are tall/wide
    val side = minOf(w, h)
    val originX = bounds.left + (w - side) * 0.5f
    val originY = bounds.top + (h - side) * 0.5f
    val cell = side / 3f
    val centers = ArrayList<Pair<Float, Float>>(9)
    for (row in 0 until 3) {
      for (col in 0 until 3) {
        centers.add(
          Pair(
            originX + cell * (col + 0.5f),
            originY + cell * (row + 0.5f),
          )
        )
      }
    }
    return centers
  }

  fun patternDigitsToPoints(patternDigits: String, dotCenters: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
    if (dotCenters.size < 9) return emptyList()
    return patternDigits
      .filter { it in '1'..'9' }
      .mapNotNull { ch -> dotCenters.getOrNull(ch - '1') }
  }

  @Suppress("DEPRECATION")
  fun wakeScreenIfNeeded(ctx: Context) {
    try {
      val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
      if (pm.isInteractive) return
      val wl =
        pm.newWakeLock(
          PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
          "lxr_ai:wake_for_task",
        )
      wl.setReferenceCounted(false)
      wl.acquire(4000L)
      Thread.sleep(360)
      try {
        if (wl.isHeld) wl.release()
      } catch (_: Throwable) {}
    } catch (_: Throwable) {}
  }

  fun unlockDeviceIfLocked(ctx: Context, acc: RiyaAccessibilityService?): UnlockAttemptResult {
    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val initiallyLocked = (pm != null && !pm.isInteractive) || (km?.isKeyguardLocked == true)
    if (!initiallyLocked) {
      return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "ফোন আগেই আনলক আছে।")
    }
    if (!Store.autoUnlockEnabled(ctx)) {
      return UnlockAttemptResult(
        UnlockStatus.AUTO_UNLOCK_DISABLED,
        "ফোন লক অবস্থায় আছে এবং অটো-আনলক বন্ধ করা আছে বস।",
      )
    }
    val lockType = Store.phoneLockType(ctx)
    if (!Store.hasAnyPhoneLockSaved(ctx) || lockType == ScreenLockType.NONE) {
      return UnlockAttemptResult(
        UnlockStatus.NO_CREDENTIALS_SAVED,
        "ফোন লক আছে বস, কিন্তু LXR--AI অ্যাপের 'ফোন ও অ্যাপ লক' সেকশনে আপনার পাসওয়ার্ড/প্যাটার্ন সেভ করা নেই।",
      )
    }
    if (acc == null) {
      return UnlockAttemptResult(
        UnlockStatus.ACCESSIBILITY_OFF,
        "ফোন আনলক করতে Accessibility চালু থাকা প্রয়োজন বস।",
      )
    }

    wakeScreenIfNeeded(ctx)
    val dm = acc.resources.displayMetrics
    val w = dm.widthPixels.toFloat().coerceAtLeast(720f)
    val h = dm.heightPixels.toFloat().coerceAtLeast(1400f)

    // Swipe up from bottom lockscreen clock to open PIN / Pattern prompt
    acc.swipe(w * 0.50f, h * 0.80f, w * 0.50f, h * 0.24f, durationMs = 300L)
    Thread.sleep(520L)

    // Swipe-only unlock (if keyguard dismissed just by swiping)
    if (km?.isKeyguardLocked == false) {
      return UnlockAttemptResult(UnlockStatus.UNLOCKED_SUCCESSFULLY, "স্ক্রিন আনলক হয়েছে ✓")
    }

    val unlocked =
      when (lockType) {
        ScreenLockType.PIN -> enterPinOnCurrentScreen(acc, Store.phonePin(ctx)) { km?.isKeyguardLocked == false }
        ScreenLockType.PATTERN -> drawPatternOnCurrentScreen(acc, Store.phonePattern(ctx)) { km?.isKeyguardLocked == false }
        ScreenLockType.NONE -> false
      }

    return if (unlocked || km?.isKeyguardLocked == false) {
      Thread.sleep(320L)
      UnlockAttemptResult(UnlockStatus.UNLOCKED_SUCCESSFULLY, "স্ক্রিন স্বয়ংক্রিয়ভাবে আনলক হয়েছে ✓")
    } else {
      UnlockAttemptResult(
        UnlockStatus.FAILED_TO_UNLOCK,
        "লক স্ক্রিনে পাসওয়ার্ড/প্যাটার্ন মেলানো যায়নি বস — সেভ করা লকটি সঠিক কিনা যাচাই করুন।",
      )
    }
  }

  fun isAppLockScreenShowing(acc: RiyaAccessibilityService?): Boolean {
    if (acc == null) return false
    val roots = collectAllRoots(acc)
    for (root in roots) {
      val pkg = root.packageName?.toString()?.lowercase().orEmpty()
      if (pkg in appLockPackages) return true
    }
    val textDump = collectCompactScreenText(roots).lowercase()
    return appLockTitleHints.any { textDump.contains(it) }
  }

  fun unlockAppLockIfPresent(ctx: Context, acc: RiyaAccessibilityService?): Boolean {
    if (acc == null || !Store.autoUnlockEnabled(ctx) || !Store.hasAnyAppLockSaved(ctx)) return false
    if (!isAppLockScreenShowing(acc)) return false

    val usePhoneCreds = Store.appLockSameAsPhone(ctx)
    val type = if (usePhoneCreds) Store.phoneLockType(ctx) else Store.appLockType(ctx)
    val pin = if (usePhoneCreds) Store.phonePin(ctx) else Store.appPin(ctx)
    val pattern = if (usePhoneCreds) Store.phonePattern(ctx) else Store.appPattern(ctx)

    return when (type) {
      ScreenLockType.PIN -> enterPinOnCurrentScreen(acc, pin) { !isAppLockScreenShowing(acc) }
      ScreenLockType.PATTERN -> drawPatternOnCurrentScreen(acc, pattern) { !isAppLockScreenShowing(acc) }
      ScreenLockType.NONE -> false
    }
  }

  fun relockScreen(acc: RiyaAccessibilityService?): Boolean {
    return try {
      acc?.global("lock") == "সম্পন্ন"
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to lock screen", t)
      false
    }
  }

  private fun collectAllRoots(acc: RiyaAccessibilityService): List<AccessibilityNodeInfo> {
    val roots = mutableListOf<AccessibilityNodeInfo>()
    try {
      acc.windows?.forEach { w -> w.root?.let { roots.add(it) } }
    } catch (_: Throwable) {}
    acc.rootInActiveWindow?.let { if (it !in roots) roots.add(it) }
    return roots
  }

  private fun collectCompactScreenText(roots: List<AccessibilityNodeInfo>): String {
    val sb = StringBuilder()
    fun walk(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > 20 || sb.length > 1500) return
      n.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
      n.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
      n.viewIdResourceName?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
      for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
    }
    roots.forEach { walk(it, 0) }
    return sb.toString()
  }

  private fun enterPinOnCurrentScreen(
    acc: RiyaAccessibilityService,
    pin: String,
    isUnlockedNow: () -> Boolean,
  ): Boolean {
    if (pin.isBlank()) return false
    val roots = collectAllRoots(acc)
    val allNodes = mutableListOf<AccessibilityNodeInfo>()
    fun collect(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > 28) return
      allNodes.add(n)
      for (i in 0 until n.childCount) collect(n.getChild(i), depth + 1)
    }
    roots.forEach { collect(it, 0) }

    // 1. Direct alphanumeric/password EditText input (e.g. password lock screen)
    val editBox = allNodes.firstOrNull { it.isEditable }
    if (editBox != null && !pin.all { it.isDigit() }) {
      val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin) }
      editBox.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
      if (editBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
        Thread.sleep(200)
        findAndClickConfirmButton(acc, allNodes)
        if (pollUnlocked(1500L, isUnlockedNow)) return true
      }
    }

    // 2. Numeric keypad node lookup (Infinix XOS / Xiaomi / Realme / Tecno / itel / AOSP)
    val digitWords =
      mapOf(
        '0' to listOf("0", "zero", "key0", "digit_0", "pin_0", "num_0"),
        '1' to listOf("1", "one", "key1", "digit_1", "pin_1", "num_1"),
        '2' to listOf("2", "two", "key2", "digit_2", "pin_2", "num_2"),
        '3' to listOf("3", "three", "key3", "digit_3", "pin_3", "num_3"),
        '4' to listOf("4", "four", "key4", "digit_4", "pin_4", "num_4"),
        '5' to listOf("5", "five", "key5", "digit_5", "pin_5", "num_5"),
        '6' to listOf("6", "six", "key6", "digit_6", "pin_6", "num_6"),
        '7' to listOf("7", "seven", "key7", "digit_7", "pin_7", "num_7"),
        '8' to listOf("8", "eight", "key8", "digit_8", "pin_8", "num_8"),
        '9' to listOf("9", "nine", "key9", "digit_9", "pin_9", "num_9"),
      )

    fun nodeMatchesDigit(node: AccessibilityNodeInfo, digit: Char): Boolean {
      val text = node.text?.toString()?.trim().orEmpty()
      val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
      val id = node.viewIdResourceName?.lowercase().orEmpty()
      if (desc.contains("emergency") || id.contains("emergency") || text.contains("emergency", true)) {
        return false
      }
      val digitStr = digit.toString()
      val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
      if (text == digitStr || firstLine == digitStr || desc == digitStr) return true
      val aliases = digitWords[digit].orEmpty()
      if (aliases.any { a -> id.endsWith("/$a") || id.contains("_$a") || desc.startsWith("$a ") }) {
        return true
      }
      return false
    }

    var tappedCount = 0
    for (ch in pin) {
      if (!ch.isDigit()) continue
      val keyNode =
        allNodes.firstOrNull { (it.isClickable || it.parent?.isClickable == true) && nodeMatchesDigit(it, ch) }
          ?: allNodes.firstOrNull { nodeMatchesDigit(it, ch) }
      if (keyNode != null) {
        val r = Rect()
        keyNode.getBoundsInScreen(r)
        val ok =
          keyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
            (r.width() > 0 && r.height() > 0 && acc.tapAt(r.exactCenterX(), r.exactCenterY()))
        if (ok) tappedCount++
        Thread.sleep(115L)
      }
    }

    if (tappedCount == 0 && editBox != null) {
      val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin) }
      editBox.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
      editBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // Many devices auto-unlock on exact PIN length; wait briefly, then tap OK/Enter if still locked
    if (pollUnlocked(650L, isUnlockedNow)) return true
    val refreshedNodes = mutableListOf<AccessibilityNodeInfo>()
    collectAllRoots(acc).forEach { r ->
      fun walk(n: AccessibilityNodeInfo?, d: Int) {
        if (n == null || d > 28) return
        refreshedNodes.add(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
      }
      walk(r, 0)
    }
    findAndClickConfirmButton(acc, refreshedNodes)
    return pollUnlocked(1600L, isUnlockedNow)
  }

  private fun findAndClickConfirmButton(
    acc: RiyaAccessibilityService,
    nodes: List<AccessibilityNodeInfo>,
  ) {
    val keys = listOf("ok", "enter", "confirm", "done", "key_enter", "next", "unlock", "submit")
    val btn =
      nodes.firstOrNull { n ->
        val txt = n.text?.toString()?.lowercase().orEmpty()
        val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
        val id = n.viewIdResourceName?.lowercase().orEmpty()
        !desc.contains("emergency") &&
          !id.contains("emergency") &&
          n.isClickable &&
          keys.any { k -> txt == k || desc.contains(k) || id.contains(k) }
      }
    if (btn != null) {
      val r = Rect()
      btn.getBoundsInScreen(r)
      if (!btn.performAction(AccessibilityNodeInfo.ACTION_CLICK) && r.width() > 0) {
        acc.tapAt(r.exactCenterX(), r.exactCenterY())
      }
    }
  }

  private fun drawPatternOnCurrentScreen(
    acc: RiyaAccessibilityService,
    patternDigits: String,
    isUnlockedNow: () -> Boolean,
  ): Boolean {
    if (patternDigits.length < 4) return false
    val roots = collectAllRoots(acc)
    val allNodes = mutableListOf<AccessibilityNodeInfo>()
    fun collect(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > 28) return
      allNodes.add(n)
      for (i in 0 until n.childCount) collect(n.getChild(i), depth + 1)
    }
    roots.forEach { collect(it, 0) }

    // Check if individual 9 dot nodes are exposed (some OEMs expose Accessibility children "1".."9")
    val dm = acc.resources.displayMetrics
    val patternView =
      allNodes.firstOrNull { n ->
        val cls = n.className?.toString()?.lowercase().orEmpty()
        val id = n.viewIdResourceName?.lowercase().orEmpty()
        val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
        val r = Rect()
        n.getBoundsInScreen(r)
        r.width() > dm.widthPixels * 0.35f &&
          r.height() > dm.widthPixels * 0.35f &&
          (cls.contains("pattern") || id.contains("lockpattern") || id.contains("pattern") || desc.contains("pattern"))
      }

    val bounds =
      if (patternView != null) {
        Rect().also { patternView.getBoundsInScreen(it) }
      } else {
        // Fallback standard Android Pattern Grid region centered on lower-middle screen
        val w = dm.widthPixels
        val h = dm.heightPixels
        val gridSize = (w * 0.72f).toInt()
        val left = (w - gridSize) / 2
        val top = (h * 0.44f).toInt()
        Rect(left, top, left + gridSize, top + gridSize)
      }

    val centers = computePatternDotCenters(bounds)
    val points = patternDigitsToPoints(patternDigits, centers)
    if (points.size < 4) return false

    acc.drawPatternPath(points, durationMs = (points.size * 145L).coerceIn(580L, 1450L))
    return pollUnlocked(1800L, isUnlockedNow)
  }

  private fun pollUnlocked(timeoutMs: Long, predicate: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (predicate()) return true
      Thread.sleep(150L)
    }
    return predicate()
  }
}
