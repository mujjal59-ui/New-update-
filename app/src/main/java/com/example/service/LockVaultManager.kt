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

data class LockSwipeVector(
  val startX: Float,
  val startY: Float,
  val endX: Float,
  val endY: Float,
  val durationMs: Long,
)

enum class LivePhoneLockPhase {
  SCREEN_OFF,
  KEYGUARD_LOCKED,
  APP_LOCKED,
  UNLOCKED,
}

data class LivePhoneStateReport(
  val phase: LivePhoneLockPhase,
  val isLocked: Boolean,
  val activePackage: String,
  val summaryBn: String,
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
      "com.huawei.systemmanager",
      "com.vivo.appfilter",
      "com.domobile.applockwatcher",
      "com.sp.protector.free",
    )

  private val appLockTitleHints =
    listOf(
      "app lock",
      "applock",
      "অ্যাপ লক",
      "অ্যাপটি লক করা",
      "draw pattern",
      "draw unlock pattern",
      "enter password",
      "enter pin",
      "enter privacy password",
      "verify password",
      "verify privacy",
      "confirm pattern",
      "use password",
      "পাসওয়ার্ড দিন",
      "প্যাটার্ন আঁকুন",
      "প্যাটার্ন দিন",
      "পিন দিন",
      "privacy password",
    )

  fun matchesAppLockHint(rawScreenText: String): Boolean {
    val lower = rawScreenText.lowercase()
    return appLockTitleHints.any { lower.contains(it) }
  }

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

  fun evaluateMainScreenLocked(
    screenInteractive: Boolean,
    keyguardLocked: Boolean,
    deviceLocked: Boolean,
    activeWindowPkg: String?,
    lockScreenVisibleInUi: Boolean,
  ): Boolean {
    if (!screenInteractive) return true
    val pkg = activeWindowPkg?.trim()?.lowercase().orEmpty()
    val unlockedAppVisible =
      pkg.isNotEmpty() &&
        pkg != "com.android.systemui" &&
        !pkg.contains("keyguard") &&
        !pkg.contains("lockscreen") &&
        !lockScreenVisibleInUi
    if (unlockedAppVisible) return false
    return keyguardLocked || deviceLocked || lockScreenVisibleInUi
  }

  fun isSystemKeyguardUiShowing(acc: RiyaAccessibilityService?): Boolean {
    if (acc == null) return false
    val roots = collectAllRoots(acc)
    val pkgs =
      roots.mapNotNull { it.packageName?.toString()?.lowercase()?.trim() }.filter { it.isNotEmpty() }
    val activePkg = acc.rootInActiveWindow?.packageName?.toString()?.lowercase()?.trim().orEmpty()
    if (activePkg.contains("keyguard") || activePkg.contains("lockscreen") ||
      pkgs.any { it.contains("keyguard") || it.contains("lockscreen") }
    ) {
      return true
    }
    // If foreground active window is already a regular user app (not systemui), do NOT mistake status bar for keyguard!
    if (activePkg.isNotEmpty() && activePkg != "com.android.systemui") return false
    val screenH = acc.resources.displayMetrics.heightPixels.coerceAtLeast(1200)
    val keyguardRoots =
      roots.filter { r ->
        val p = r.packageName?.toString()?.lowercase()?.trim().orEmpty()
        if (p != "com.android.systemui") return@filter false
        val rect = Rect()
        r.getBoundsInScreen(rect)
        rect.height() >= (screenH * 0.45f).toInt() || rect.height() == 0
      }
    if (keyguardRoots.isEmpty()) return false
    val textDump = collectCompactScreenText(keyguardRoots).lowercase()
    return textDump.contains("keyguard") ||
      textDump.contains("lock_icon") ||
      textDump.contains("pinentry") ||
      textDump.contains("lockpattern") ||
      textDump.contains("bouncer") ||
      textDump.contains("swipe up to unlock") ||
      textDump.contains("enter password") ||
      textDump.contains("enter pin") ||
      textDump.contains("wrong pin") ||
      textDump.contains("unlock with fingerprint") ||
      textDump.contains("পিন দিন") ||
      textDump.contains("প্যাটার্ন আঁকুন")
  }

  /**
   * Returns true ONLY when the phone main display/keyguard was genuinely locked at the OS level
   * BEFORE a task began (never triggered by an unlocked app's EditText or notification shade).
   */
  fun isMainScreenGenuinelyLockedBeforeTask(ctx: Context, acc: RiyaAccessibilityService?): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    if (pm != null && !pm.isInteractive) return true
    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val keyguardLocked = km?.isKeyguardLocked == true
    if (!keyguardLocked && !isSystemKeyguardUiShowing(acc)) return false
    val activePkg = acc?.rootInActiveWindow?.packageName?.toString()
    return evaluateMainScreenLocked(
      screenInteractive = true,
      keyguardLocked = keyguardLocked,
      deviceLocked = if (Build.VERSION.SDK_INT >= 22) (km?.isDeviceLocked == true) else keyguardLocked,
      activeWindowPkg = activePkg,
      lockScreenVisibleInUi = isSystemKeyguardUiShowing(acc),
    )
  }

  fun describeLivePhoneState(
    ctx: Context,
    acc: RiyaAccessibilityService?,
    lockType: ScreenLockType = Store.phoneLockType(ctx),
  ): LivePhoneStateReport {
    return try {
      val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
      val interactive = pm?.isInteractive ?: true
      val activePkg = acc?.rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
      val lowerPkg = activePkg.lowercase()
      if (!interactive) {
        return LivePhoneStateReport(
          phase = LivePhoneLockPhase.SCREEN_OFF,
          isLocked = true,
          activePackage = activePkg.ifBlank { "screen_off" },
          summaryBn = "স্ক্রিনের অবস্থা: বন্ধ ও লক করা (Screen OFF & Locked)",
        )
      }
      val keyguardLocked = km?.isKeyguardLocked == true
      val deviceLocked = if (Build.VERSION.SDK_INT >= 22) (km?.isDeviceLocked == true) else keyguardLocked
      val keyguardUi = isSystemKeyguardUiShowing(acc) || hasBouncerUiShowing(acc, lockType)
      val mainLocked =
        keyguardUi ||
          lowerPkg.contains("keyguard") ||
          lowerPkg.contains("lockscreen") ||
          (lowerPkg == "com.android.systemui" && (keyguardLocked || deviceLocked)) ||
          evaluateMainScreenLocked(
            screenInteractive = true,
            keyguardLocked = keyguardLocked,
            deviceLocked = deviceLocked,
            activeWindowPkg = lowerPkg,
            lockScreenVisibleInUi = keyguardUi,
          )
      if (mainLocked) {
        return LivePhoneStateReport(
          phase = LivePhoneLockPhase.KEYGUARD_LOCKED,
          isLocked = true,
          activePackage = activePkg.ifBlank { "com.android.systemui" },
          summaryBn = "স্ক্রিনের অবস্থা: মেইন স্ক্রিন লক অবস্থায় আছে (Phone Lock Screen — কাজ করার আগে আনলক করতে হবে)",
        )
      }
      if (acc != null && isAppLockScreenShowing(acc)) {
        return LivePhoneStateReport(
          phase = LivePhoneLockPhase.APP_LOCKED,
          isLocked = true,
          activePackage = activePkg.ifBlank { "applock" },
          summaryBn = "স্ক্রিনের অবস্থা: অ্যাপ লক স্ক্রিন খোলা আছে ($activePkg — কাজ করার আগে অ্যাপ লক খুলতে হবে)",
        )
      }
      LivePhoneStateReport(
        phase = LivePhoneLockPhase.UNLOCKED,
        isLocked = false,
        activePackage = activePkg.ifBlank { "unknown" },
        summaryBn = "স্ক্রিনের অবস্থা: খোলা ও আনলক আছে (অ্যাপ: ${activePkg.ifBlank { "Home/Unknown" }})",
      )
    } catch (_: Throwable) {
      LivePhoneStateReport(
        phase = LivePhoneLockPhase.UNLOCKED,
        isLocked = false,
        activePackage = "unknown",
        summaryBn = "স্ক্রিনের অবস্থা: খোলা",
      )
    }
  }

  fun isScreenUnlockedNow(
    ctx: Context,
    acc: RiyaAccessibilityService?,
    lockType: ScreenLockType = Store.phoneLockType(ctx),
  ): Boolean {
    return try {
      val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
      val interactive = pm?.isInteractive ?: true
      if (!interactive) return false
      val keyguardUi = isSystemKeyguardUiShowing(acc) || hasBouncerUiShowing(acc, lockType)
      if (keyguardUi) return false
      if (km != null && !km.isKeyguardLocked) return true
      val activePkg = acc?.rootInActiveWindow?.packageName?.toString()?.trim()?.lowercase().orEmpty()
      if (!evaluateMainScreenLocked(
          screenInteractive = interactive,
          keyguardLocked = km?.isKeyguardLocked == true,
          deviceLocked = if (Build.VERSION.SDK_INT >= 22) (km?.isDeviceLocked == true) else (km?.isKeyguardLocked == true),
          activeWindowPkg = activePkg,
          lockScreenVisibleInUi = keyguardUi,
        )
      ) {
        return true
      }
      // On Android 16 / XOS launcher transition, KeyguardManager flag can lag behind real Home/App screen
      activePkg.isNotEmpty() &&
        activePkg != "com.android.systemui" &&
        !activePkg.contains("keyguard") &&
        !activePkg.contains("lockscreen") &&
        !keyguardUi
    } catch (_: Throwable) {
      false
    }
  }

  fun isDeviceLocked(ctx: Context): Boolean {
    return try {
      val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
      val screenInteractive = pm?.isInteractive ?: true
      val keyguardLocked = km?.isKeyguardLocked == true
      val deviceLocked = if (Build.VERSION.SDK_INT >= 22) (km?.isDeviceLocked == true) else keyguardLocked
      val acc = RiyaAccessibilityService.instance
      val activePkg = acc?.rootInActiveWindow?.packageName?.toString()
      val lockUi = isSystemKeyguardUiShowing(acc)
      evaluateMainScreenLocked(
        screenInteractive = screenInteractive,
        keyguardLocked = keyguardLocked,
        deviceLocked = deviceLocked,
        activeWindowPkg = activePkg,
        lockScreenVisibleInUi = lockUi,
      )
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
  fun wakeScreenIfNeeded(ctx: Context, acc: RiyaAccessibilityService? = RiyaAccessibilityService.instance) {
    try {
      val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
      if (pm.isInteractive) return

      // 1. Physical Double-Tap gesture on closed/AOD screen to trigger OEM Double-Tap-to-Wake screen light
      if (acc != null) {
        try {
          val dm = acc.resources.displayMetrics
          val cx = dm.widthPixels * 0.5f
          val cy = dm.heightPixels * 0.44f
          acc.doubleTap(cx, cy, gapMs = 90L)
          Thread.sleep(360L)
        } catch (_: Throwable) {}
      }

      // 2. Acquire WakeLock with ACQUIRE_CAUSES_WAKEUP so screen illuminates cleanly on all Android devices
      if (!pm.isInteractive) {
        val wl =
          pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
              PowerManager.ACQUIRE_CAUSES_WAKEUP or
              PowerManager.ON_AFTER_RELEASE,
            "lxr_ai:wake_for_task",
          )
        wl.setReferenceCounted(false)
        wl.acquire(4500L)
        Thread.sleep(420L)
        try {
          if (wl.isHeld) wl.release()
        } catch (_: Throwable) {}
      }

      // 3. Second Double-Tap fallback if display still needs a wake pulse
      if (!pm.isInteractive && acc != null) {
        try {
          val dm = acc.resources.displayMetrics
          acc.doubleTap(dm.widthPixels * 0.5f, dm.heightPixels * 0.44f, gapMs = 95L)
          Thread.sleep(420L)
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
  }

  fun isBouncerInputUiVisible(nodeTextsAndIds: String, lockType: ScreenLockType): Boolean {
    val lower = nodeTextsAndIds.lowercase()
    val hasGeneral =
      lower.contains("pinentry") ||
        lower.contains("keyguard_pin") ||
        lower.contains("keyguard_password") ||
        lower.contains("keyguard_pattern") ||
        lower.contains("lockpattern") ||
        lower.contains("delete_button") ||
        lower.contains("key_enter") ||
        lower.contains("numpad")
    if (hasGeneral) return true
    return when (lockType) {
      ScreenLockType.PIN ->
        lower.contains("enter pin") ||
          lower.contains("enter password") ||
          lower.contains("passwordentry") ||
          lower.contains("key1") ||
          lower.contains("digit_1") ||
          lower.contains("pin_1") ||
          (lower.contains("1") && lower.contains("5") && lower.contains("9") && lower.contains("emergency"))
      ScreenLockType.PATTERN ->
        lower.contains("draw unlock pattern") ||
          lower.contains("draw pattern") ||
          lower.contains("pattern") && lower.contains("emergency")
      ScreenLockType.NONE -> false
    }
  }

  fun hasBouncerUiShowing(acc: RiyaAccessibilityService?, lockType: ScreenLockType): Boolean {
    if (acc == null || lockType == ScreenLockType.NONE) return false
    val activePkg = acc.rootInActiveWindow?.packageName?.toString()?.lowercase()?.trim().orEmpty()
    val km = acc.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val keyguardPkg = activePkg == "com.android.systemui" || activePkg.contains("keyguard") || activePkg.contains("lockscreen")
    if (!keyguardPkg && km?.isKeyguardLocked != true) return false

    val screenH = acc.resources.displayMetrics.heightPixels.coerceAtLeast(1200)
    val lockRoots =
      collectAllRoots(acc).filter { r ->
        val p = r.packageName?.toString()?.lowercase()?.trim().orEmpty()
        if (p.contains("keyguard") || p.contains("lockscreen")) return@filter true
        if (p == "com.android.systemui") {
          val rect = Rect()
          r.getBoundsInScreen(rect)
          return@filter rect.height() >= (screenH * 0.45f).toInt() || rect.height() == 0
        }
        false
      }
    if (lockRoots.isEmpty()) return false

    val allNodes = mutableListOf<AccessibilityNodeInfo>()
    fun collect(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > 28) return
      allNodes.add(n)
      for (i in 0 until n.childCount) collect(n.getChild(i), depth + 1)
    }
    lockRoots.forEach { collect(it, 0) }
    if (lockType == ScreenLockType.PIN) {
      if (allNodes.any { n ->
          val id = n.viewIdResourceName?.lowercase().orEmpty()
          (n.isEditable && (id.contains("pin") || id.contains("password") || id.contains("keyguard"))) ||
            id.contains("pinentry") ||
            id.contains("keyguard_pin")
        }
      ) {
        return true
      }
    }
    if (lockType == ScreenLockType.PATTERN) {
      val dm = acc.resources.displayMetrics
      val hasPatternNode =
        allNodes.any { n ->
          val cls = n.className?.toString()?.lowercase().orEmpty()
          val id = n.viewIdResourceName?.lowercase().orEmpty()
          val r = Rect()
          n.getBoundsInScreen(r)
          r.width() > dm.widthPixels * 0.35f &&
            (cls.contains("pattern") || id.contains("lockpattern") || id.contains("pattern"))
        }
      if (hasPatternNode) return true
    }
    val dump = collectCompactScreenText(lockRoots)
    return isBouncerInputUiVisible(dump, lockType)
  }

  fun buildLockscreenSwipeStrategies(widthPx: Float, heightPx: Float): List<LockSwipeVector> {
    val w = widthPx.coerceAtLeast(720f)
    val h = heightPx.coerceAtLeast(1400f)
    return listOf(
      // 1. Primary Infinix swipe matching bottom "Use Fingerprint or Swipe Up to Unlock" straight up to center-top
      LockSwipeVector(w * 0.50f, h * 0.88f, w * 0.50f, h * 0.30f, 240L),
      // 2. Brisk full-throw swipe from lower edge
      LockSwipeVector(w * 0.50f, h * 0.82f, w * 0.50f, h * 0.16f, 185L),
      // 3. Mid-screen sweep fallback when notifications cover lower area
      LockSwipeVector(w * 0.50f, h * 0.70f, w * 0.50f, h * 0.14f, 250L),
    )
  }

  fun unlockDeviceIfLocked(ctx: Context, acc: RiyaAccessibilityService?): UnlockAttemptResult {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val lockType = Store.phoneLockType(ctx)
    val initiallyLocked = (pm != null && !pm.isInteractive) || !isScreenUnlockedNow(ctx, acc, lockType)
    if (!initiallyLocked) {
      return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "ফোন আগেই আনলক আছে।")
    }
    if (!Store.autoUnlockEnabled(ctx)) {
      return UnlockAttemptResult(
        UnlockStatus.AUTO_UNLOCK_DISABLED,
        "ফোন লক অবস্থায় আছে বস।",
      )
    }
    if (!Store.hasAnyPhoneLockSaved(ctx) || lockType == ScreenLockType.NONE) {
      return UnlockAttemptResult(
        UnlockStatus.NO_CREDENTIALS_SAVED,
        "ফোন লক অবস্থায় আছে বস, এই মুহূর্তে সরাসরি আনলক করা যাচ্ছে না।",
      )
    }
    if (acc == null) {
      return UnlockAttemptResult(
        UnlockStatus.ACCESSIBILITY_OFF,
        "ফোন আনলক করতে Accessibility চালু থাকা প্রয়োজন বস।",
      )
    }

    wakeScreenIfNeeded(ctx, acc)
    // Wait until display is interactive and lockscreen keyguard animations settle on Android 16
    val wakeWaitDeadline = System.currentTimeMillis() + 850L
    while (pm?.isInteractive == false && System.currentTimeMillis() < wakeWaitDeadline) {
      Thread.sleep(60L)
    }
    Thread.sleep(280L)

    if (isScreenUnlockedNow(ctx, acc, lockType)) {
      return UnlockAttemptResult(UnlockStatus.UNLOCKED_SUCCESSFULLY, "স্ক্রিন আনলক হয়েছে ✓")
    }

    val dm = acc.resources.displayMetrics
    val strategies = buildLockscreenSwipeStrategies(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
    if (!hasBouncerUiShowing(acc, lockType)) {
      for (swipe in strategies) {
        acc.swipe(swipe.startX, swipe.startY, swipe.endX, swipe.endY, durationMs = swipe.durationMs)
        val bouncerReady =
          pollUnlocked(950L) {
            isScreenUnlockedNow(ctx, acc, lockType) || hasBouncerUiShowing(acc, lockType)
          }
        if (isScreenUnlockedNow(ctx, acc, lockType)) {
          return UnlockAttemptResult(UnlockStatus.UNLOCKED_SUCCESSFULLY, "ডাবল-ট্যাপ ও সোয়াইপে স্ক্রিন আনলক হয়েছে ✓")
        }
        if (bouncerReady) break
      }
      Thread.sleep(160L)
    }

    var unlocked =
      when (lockType) {
        ScreenLockType.PIN -> enterPinOnCurrentScreen(acc, Store.phonePin(ctx)) { isScreenUnlockedNow(ctx, acc, lockType) }
        ScreenLockType.PATTERN -> drawPatternOnCurrentScreen(acc, Store.phonePattern(ctx)) { isScreenUnlockedNow(ctx, acc, lockType) }
        ScreenLockType.NONE -> false
      }

    // Brief retry if bouncer is still visible after 1st attempt
    if (!unlocked && !isScreenUnlockedNow(ctx, acc, lockType) && hasBouncerUiShowing(acc, lockType)) {
      Thread.sleep(220L)
      unlocked =
        when (lockType) {
          ScreenLockType.PIN -> enterPinOnCurrentScreen(acc, Store.phonePin(ctx)) { isScreenUnlockedNow(ctx, acc, lockType) }
          ScreenLockType.PATTERN -> drawPatternOnCurrentScreen(acc, Store.phonePattern(ctx)) { isScreenUnlockedNow(ctx, acc, lockType) }
          ScreenLockType.NONE -> false
        }
    }

    val finalUnlocked =
      unlocked || pollUnlocked(550L) { isScreenUnlockedNow(ctx, acc, lockType) }
    return if (finalUnlocked) {
      Thread.sleep(260L)
      UnlockAttemptResult(UnlockStatus.UNLOCKED_SUCCESSFULLY, "ডাবল-ট্যাপে স্ক্রিনের আলো জ্বালিয়ে মেইন স্ক্রিন আনলক করা হয়েছে ✓")
    } else {
      UnlockAttemptResult(
        UnlockStatus.FAILED_TO_UNLOCK,
        "এই মুহূর্তে স্ক্রিন আনলক করা যায়নি বস।",
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
    val textDump = collectCompactScreenText(roots)
    return matchesAppLockHint(textDump)
  }

  fun unlockAppLockWithResult(ctx: Context, acc: RiyaAccessibilityService?): UnlockAttemptResult {
    if (acc == null) {
      return UnlockAttemptResult(UnlockStatus.ACCESSIBILITY_OFF, "অ্যাপ লক খুলতে Accessibility চালু থাকা প্রয়োজন বস।")
    }
    if (!isAppLockScreenShowing(acc)) {
      return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "এই মুহূর্তে কোনো অ্যাপ লক স্ক্রিন নেই।")
    }
    if (!Store.hasAnyAppLockSaved(ctx)) {
      return UnlockAttemptResult(
        UnlockStatus.NO_CREDENTIALS_SAVED,
        "অ্যাপটি লক অবস্থায় আছে বস, এই মুহূর্তে সরাসরি খোলা যাচ্ছে না।",
      )
    }

    val usePhoneCreds = Store.appLockSameAsPhone(ctx)
    val type = if (usePhoneCreds) Store.phoneLockType(ctx) else Store.appLockType(ctx)
    val pin = if (usePhoneCreds) Store.phonePin(ctx) else Store.appPin(ctx)
    val pattern = if (usePhoneCreds) Store.phonePattern(ctx) else Store.appPattern(ctx)

    // Autonomous App Lock unlock without asking Boss for permission
    val ok =
      when (type) {
        ScreenLockType.PIN -> enterPinOnCurrentScreen(acc, pin) { !isAppLockScreenShowing(acc) }
        ScreenLockType.PATTERN -> drawPatternOnCurrentScreen(acc, pattern) { !isAppLockScreenShowing(acc) }
        ScreenLockType.NONE -> false
      }
    return if (ok) {
      Thread.sleep(280L)
      UnlockAttemptResult(UnlockStatus.UNLOCKED_SUCCESSFULLY, "অ্যাপ লক সরাসরি খোলা হয়েছে ✓")
    } else {
      UnlockAttemptResult(UnlockStatus.FAILED_TO_UNLOCK, "অ্যাপের লক স্ক্রিন এই মুহূর্তে খোলা যায়নি বস।")
    }
  }

  fun unlockAppLockIfPresent(ctx: Context, acc: RiyaAccessibilityService?): Boolean {
    return unlockAppLockWithResult(ctx, acc).status == UnlockStatus.UNLOCKED_SUCCESSFULLY
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
      if (aliases.any { a -> id.endsWith("/$a") || id.contains("_$a") || desc == a || desc.startsWith("$a ") }) {
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
