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

enum class SmartLockExecutionMode {
  UNLOCKED_DIRECT_EXECUTION,
  LOCKED_BACKGROUND_EXECUTION,
  LOCKED_REQUIRES_DEVICE_AUTH_OR_UNLOCK,
  LOCKED_CANNOT_BYPASS_SECURE_LOCK,
}

data class SmartLockExecutionPlan(
  val mode: SmartLockExecutionMode,
  val phoneState: LivePhoneStateReport,
  val shouldWakeScreen: Boolean,
  val shouldSwipeKeyguard: Boolean,
  val shouldRelockAfterFinish: Boolean,
  val reasonBn: String,
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

  fun isRegularAppPackage(pkgRaw: String?): Boolean {
    val pkg = pkgRaw?.trim()?.lowercase().orEmpty()
    if (pkg.isEmpty()) return false
    if (pkg == "com.android.systemui") return false
    if (pkg.contains("keyguard") || pkg.contains("lockscreen") || pkg.contains("aod") || pkg.contains("alwayson")) {
      return false
    }
    return true
  }

  fun shouldTrustSystemUiLockOverlay(keyguardLocked: Boolean, deviceLocked: Boolean): Boolean {
    return keyguardLocked || deviceLocked
  }

  @Volatile private var lastHomeOrExitTransitionMs: Long = 0L
  const val HOME_EXIT_UNLOCK_SUPPRESSION_MS = 6_000L

  fun markHomeOrAppExitTransition(nowMs: Long = System.currentTimeMillis()) {
    lastHomeOrExitTransitionMs = nowMs
  }

  fun isInHomeOrAppExitTransition(nowMs: Long = System.currentTimeMillis()): Boolean {
    val t = lastHomeOrExitTransitionMs
    return t > 0L && (nowMs - t) in 0L..HOME_EXIT_UNLOCK_SUPPRESSION_MS
  }

  fun isLauncherPackage(ctx: Context?, pkgRaw: String?): Boolean {
    val pkg = pkgRaw?.trim()?.lowercase().orEmpty()
    if (pkg.isEmpty()) return false
    if (pkg.contains("launcher") || pkg == "com.transsion.xoslauncher" || pkg == "com.transsion.hilauncher") {
      return true
    }
    if (ctx == null) return false
    return try {
      val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
      val info =
        if (Build.VERSION.SDK_INT >= 33) {
          ctx.packageManager.resolveActivity(
            homeIntent,
            android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_DEFAULT_ONLY.toLong()),
          )
        } else {
          @Suppress("DEPRECATION")
          ctx.packageManager.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }
      info?.activityInfo?.packageName?.trim()?.lowercase() == pkg
    } catch (_: Throwable) {
      false
    }
  }

  /**
   * True when any interactive regular app or Home Launcher window is visibly active to the user.
   * Prevents transient SystemUI overlays during app exit or Home navigation from false lock detection.
   */
  fun hasVisibleRegularAppOrLauncherWindow(acc: RiyaAccessibilityService?): Boolean {
    if (acc == null) return false
    return try {
      val wins = acc.windows.orEmpty()
      val hasAppWindow =
        wins.any { w ->
          val root = w.root ?: return@any false
          val p = root.packageName?.toString()?.trim().orEmpty()
          w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
            isRegularAppPackage(p) &&
            root.isVisibleToUser
        }
      if (hasAppWindow) return true
      val root = acc.rootInActiveWindow ?: return false
      val rp = root.packageName?.toString()?.trim().orEmpty()
      isRegularAppPackage(rp) && root.isVisibleToUser
    } catch (_: Throwable) {
      false
    }
  }

  /**
   * Checks whether a visible full-screen SystemUI window is sitting at or above regular app window layers.
   * Catches Infinix XOS clock-only lockscreens even when no `"swipe up"` text is visible.
   */
  fun hasFullScreenVisibleKeyguardLayer(acc: RiyaAccessibilityService?): Boolean {
    if (acc == null) return false
    return try {
      val km = acc.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      val keyguardLocked = km?.isKeyguardLocked == true
      val deviceLocked = if (Build.VERSION.SDK_INT >= 22) (km?.isDeviceLocked == true) else keyguardLocked
      if (km != null && !shouldTrustSystemUiLockOverlay(keyguardLocked, deviceLocked)) {
        return false
      }
      val dm = acc.resources.displayMetrics
      val screenH = dm.heightPixels.coerceAtLeast(1200)
      val screenW = dm.widthPixels.coerceAtLeast(720)
      val wins = acc.windows.orEmpty()
      var maxRegularAppLayer = Int.MIN_VALUE
      var hasVisibleRegularApp = false
      for (w in wins) {
        val p = w.root?.packageName?.toString()?.trim().orEmpty()
        if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
          isRegularAppPackage(p) &&
          w.root?.isVisibleToUser == true
        ) {
          hasVisibleRegularApp = true
          if (w.layer > maxRegularAppLayer) maxRegularAppLayer = w.layer
        }
      }
      wins.any { w ->
        if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@any false
        val root = w.root ?: return@any false
        val wp = root.packageName?.toString()?.lowercase()?.trim().orEmpty()
        if (wp.contains("keyguard") || wp.contains("lockscreen")) return@any root.isVisibleToUser
        if (wp != "com.android.systemui" || !root.isVisibleToUser) return@any false
        val r = Rect()
        w.getBoundsInScreen(r)
        val isFullScreen = r.height() >= (screenH * 0.70f).toInt() && r.width() >= (screenW * 0.70f).toInt()
        if (!isFullScreen) return@any false
        val txt = collectCompactScreenText(listOf(root), visibleOnly = true).lowercase()
        val isQuickSettingsOrShade =
          txt.contains("quick settings") ||
            txt.contains("clear all") ||
            txt.contains("notification shade") ||
            txt.contains("again to unlock") ||
            (txt.contains("brightness") && txt.contains("wi-fi"))
        if (isQuickSettingsOrShade) return@any false
        val keyguardCue = isKeyguardTextDump(txt)
        if (keyguardCue) return@any true
        // Never treat a transparent/edge SystemUI layer during Home or APK-update transition as lockscreen when a regular app/launcher is visible
        !hasVisibleRegularApp && (maxRegularAppLayer == Int.MIN_VALUE || w.layer >= maxRegularAppLayer) && keyguardLocked
      }
    } catch (_: Throwable) {
      false
    }
  }

  /**
   * Inspects AccessibilityService root + interactive windows to find the true foreground non-systemui
   * application package (preventing transient rootInActiveWindow==null from looking like a lockscreen).
   */
  fun resolveBestForegroundPackage(acc: RiyaAccessibilityService?): String {
    if (acc == null) return ""
    val rootPkg = acc.rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
    return try {
      val km = acc.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      val keyguardLocked = km?.isKeyguardLocked == true
      val deviceLocked = if (Build.VERSION.SDK_INT >= 22) (km?.isDeviceLocked == true) else keyguardLocked
      val trustLockOverlay = shouldTrustSystemUiLockOverlay(keyguardLocked, deviceLocked)

      val screenH = acc.resources.displayMetrics.heightPixels.coerceAtLeast(1200)
      val wins = acc.windows.orEmpty()
      if (trustLockOverlay) {
        val hasKeyguardWin =
          hasFullScreenVisibleKeyguardLayer(acc) ||
            wins.any { w ->
              val root = w.root ?: return@any false
              val wp = root.packageName?.toString()?.lowercase()?.trim().orEmpty()
              if (wp.contains("keyguard") || wp.contains("lockscreen")) return@any root.isVisibleToUser
              if (wp == "com.android.systemui" && root.isVisibleToUser) {
                val r = Rect()
                w.getBoundsInScreen(r)
                if (r.height() >= (screenH * 0.45f).toInt()) {
                  val txt = collectCompactScreenText(listOf(root), visibleOnly = true).lowercase()
                  return@any isKeyguardTextDump(txt)
                }
              }
              false
            }
        if (hasKeyguardWin) return "com.android.systemui"
      }
      if (isRegularAppPackage(rootPkg)) return rootPkg

      val topAppWin =
        wins
          .filter { w ->
            val p = w.root?.packageName?.toString()?.trim().orEmpty()
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
              isRegularAppPackage(p)
          }
          .maxWithOrNull(compareBy<android.view.accessibility.AccessibilityWindowInfo> { it.isActive || it.isFocused }.thenBy { it.layer })
      topAppWin?.root?.packageName?.toString()?.trim().orEmpty().ifBlank { rootPkg }
    } catch (_: Throwable) {
      rootPkg
    }
  }

  private fun isKeyguardTextDump(lower: String): Boolean {
    return lower.contains("keyguard") ||
      lower.contains("lock_icon") ||
      lower.contains("pinentry") ||
      lower.contains("lockpattern") ||
      lower.contains("bouncer") ||
      lower.contains("swipe up to unlock") ||
      lower.contains("use fingerprint or swipe up") ||
      lower.contains("enter password") ||
      lower.contains("enter pin") ||
      lower.contains("wrong pin") ||
      lower.contains("unlock with fingerprint") ||
      lower.contains("পিন দিন") ||
      lower.contains("প্যাটার্ন আঁকুন")
  }

  fun evaluateMainScreenLocked(
    screenInteractive: Boolean,
    keyguardLocked: Boolean,
    deviceLocked: Boolean,
    activeWindowPkg: String?,
    lockScreenVisibleInUi: Boolean,
  ): Boolean {
    if (!screenInteractive) return true
    if (lockScreenVisibleInUi) return true
    val pkg = activeWindowPkg?.trim()?.lowercase().orEmpty()
    val unlockedAppVisible = isRegularAppPackage(pkg)
    if (unlockedAppVisible) return false
    return keyguardLocked || deviceLocked
  }

  fun isSystemKeyguardUiShowing(acc: RiyaAccessibilityService?): Boolean {
    if (acc == null) return false
    val km = acc.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val keyguardLocked = km?.isKeyguardLocked == true
    val deviceLocked = if (Build.VERSION.SDK_INT >= 22) (km?.isDeviceLocked == true) else keyguardLocked
    val activePkg = acc.rootInActiveWindow?.packageName?.toString()?.lowercase()?.trim().orEmpty()
    if (activePkg.contains("keyguard") || activePkg.contains("lockscreen")) {
      return true
    }
    // Critical fix: On Infinix XOS / Android 16, SystemUI keeps hidden keyguard/lock_icon viewIds even when unlocked!
    // Never treat SystemUI as locked when OS KeyguardManager itself confirms the screen is NOT locked.
    if (km != null && !shouldTrustSystemUiLockOverlay(keyguardLocked, deviceLocked)) {
      return false
    }
    val roots = collectAllRoots(acc)
    val screenH = acc.resources.displayMetrics.heightPixels.coerceAtLeast(1200)
    val keyguardRoots =
      roots.filter { r ->
        val p = r.packageName?.toString()?.lowercase()?.trim().orEmpty()
        if (p.contains("keyguard") || p.contains("lockscreen")) return@filter r.isVisibleToUser
        if (p != "com.android.systemui" || !r.isVisibleToUser) return@filter false
        val rect = Rect()
        r.getBoundsInScreen(rect)
        rect.height() >= (screenH * 0.45f).toInt()
      }
    if (keyguardRoots.isEmpty()) return false
    val textDump = collectCompactScreenText(keyguardRoots, visibleOnly = true).lowercase()
    return isKeyguardTextDump(textDump) || hasFullScreenVisibleKeyguardLayer(acc)
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
    val deviceLocked = if (Build.VERSION.SDK_INT >= 22) (km?.isDeviceLocked == true) else keyguardLocked
    if (!keyguardLocked && !deviceLocked && isInHomeOrAppExitTransition()) {
      return false
    }
    val regularWindowVisible = hasVisibleRegularAppOrLauncherWindow(acc)
    if (km != null && !keyguardLocked && !deviceLocked) {
      if (regularWindowVisible) return false
      val activeRootPkg = acc?.rootInActiveWindow?.packageName?.toString()?.lowercase()?.trim().orEmpty()
      if (!activeRootPkg.contains("keyguard") && !activeRootPkg.contains("lockscreen")) {
        return false
      }
    }
    val activePkg = resolveBestForegroundPackage(acc)
    if ((isRegularAppPackage(activePkg) || regularWindowVisible) &&
      !hasBouncerUiShowing(acc, Store.phoneLockType(ctx)) &&
      !isSystemKeyguardUiShowing(acc) &&
      !hasFullScreenVisibleKeyguardLayer(acc)
    ) {
      return false
    }
    if (keyguardLocked && (hasFullScreenVisibleKeyguardLayer(acc) || isSystemKeyguardUiShowing(acc))) {
      return true
    }
    val lockUi = isSystemKeyguardUiShowing(acc)
    return evaluateMainScreenLocked(
      screenInteractive = true,
      keyguardLocked = keyguardLocked,
      deviceLocked = deviceLocked,
      activeWindowPkg = activePkg,
      lockScreenVisibleInUi = lockUi,
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
      val activePkg = resolveBestForegroundPackage(acc)
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
      val trustLock = km == null || shouldTrustSystemUiLockOverlay(keyguardLocked, deviceLocked)
      val keyguardLayerOnTop = trustLock && (hasFullScreenVisibleKeyguardLayer(acc) || isSystemKeyguardUiShowing(acc))
      val bouncerVisible = trustLock && hasBouncerUiShowing(acc, lockType)
      val mainLocked =
        if (!keyguardLocked && !deviceLocked && (isInHomeOrAppExitTransition() || hasVisibleRegularAppOrLauncherWindow(acc))) {
          false
        } else if (km != null && !keyguardLocked && !deviceLocked) {
          lowerPkg.contains("keyguard") || lowerPkg.contains("lockscreen")
        } else {
          keyguardLayerOnTop ||
            bouncerVisible ||
            lowerPkg.contains("keyguard") ||
            lowerPkg.contains("lockscreen") ||
            lowerPkg == "com.android.systemui" ||
            evaluateMainScreenLocked(
              screenInteractive = true,
              keyguardLocked = keyguardLocked,
              deviceLocked = deviceLocked,
              activeWindowPkg = lowerPkg,
              lockScreenVisibleInUi = keyguardLayerOnTop || bouncerVisible,
            )
        }
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
          summaryBn = "স্ক্রিনের অবস্থা: অ্যাপ লক পর্দা লক অবস্থায় আছে ($activePkg — কাজ করার আগে অ্যাপ লক খুলতে হবে)",
        )
      }
      LivePhoneStateReport(
        phase = LivePhoneLockPhase.UNLOCKED,
        isLocked = false,
        activePackage = activePkg.ifBlank { "unknown" },
        summaryBn = "স্ক্রিনের অবস্থা: স্বাভাবিক ও প্রস্তুত (অ্যাপ: ${activePkg.ifBlank { "Home/Unknown" }})",
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
      val keyguardLocked = km?.isKeyguardLocked == true
      val deviceLocked = if (Build.VERSION.SDK_INT >= 22) (km?.isDeviceLocked == true) else keyguardLocked
      if (km != null && !keyguardLocked && !deviceLocked) {
        val activeRootPkg = acc?.rootInActiveWindow?.packageName?.toString()?.lowercase()?.trim().orEmpty()
        if (!activeRootPkg.contains("keyguard") && !activeRootPkg.contains("lockscreen")) {
          return true
        }
      }
      val keyguardUi =
        hasFullScreenVisibleKeyguardLayer(acc) ||
          isSystemKeyguardUiShowing(acc) ||
          hasBouncerUiShowing(acc, lockType)
      if (keyguardUi) return false
      val activePkg = resolveBestForegroundPackage(acc).lowercase()
      if (!evaluateMainScreenLocked(
          screenInteractive = interactive,
          keyguardLocked = keyguardLocked,
          deviceLocked = deviceLocked,
          activeWindowPkg = activePkg,
          lockScreenVisibleInUi = keyguardUi,
        )
      ) {
        return true
      }
      isRegularAppPackage(activePkg) && !deviceLocked && !keyguardUi
    } catch (_: Throwable) {
      false
    }
  }

  fun isDeviceLocked(ctx: Context): Boolean {
    return describeLivePhoneState(ctx, RiyaAccessibilityService.instance).phase.let {
      it == LivePhoneLockPhase.SCREEN_OFF || it == LivePhoneLockPhase.KEYGUARD_LOCKED
    }
  }

  /** Pure helper: decides whether auto-relock after task is permitted. */
  fun shouldAutoRelockAfterTask(startedWithLockedScreen: Boolean, autoUnlockEnabled: Boolean): Boolean {
    return startedWithLockedScreen && autoUnlockEnabled
  }

  /**
   * Smart Lock-Aware Execution Decision:
   * - Unlocked -> UNLOCKED_DIRECT_EXECUTION (zero swipes, zero unlock overlays)
   * - Locked + background-capable -> LOCKED_BACKGROUND_EXECUTION (runs silently without waking screen)
   * - Locked + UI required -> validates authorized credentials/DeviceAuth; refuses illegal bypasses
   */
  fun evaluateSmartLockExecutionDecision(
    phoneState: LivePhoneStateReport,
    requiresForegroundUi: Boolean,
    autoUnlockEnabled: Boolean,
    hasSavedCredentials: Boolean,
    isDeviceSecure: Boolean,
  ): SmartLockExecutionPlan {
    if (!phoneState.isLocked) {
      return SmartLockExecutionPlan(
        mode = SmartLockExecutionMode.UNLOCKED_DIRECT_EXECUTION,
        phoneState = phoneState,
        shouldWakeScreen = false,
        shouldSwipeKeyguard = false,
        shouldRelockAfterFinish = false,
        reasonBn = "স্ক্রিন আনলক অবস্থায় আছে — সরাসরি কাজ চলবে।",
      )
    }
    if (!requiresForegroundUi) {
      return SmartLockExecutionPlan(
        mode = SmartLockExecutionMode.LOCKED_BACKGROUND_EXECUTION,
        phoneState = phoneState,
        shouldWakeScreen = false,
        shouldSwipeKeyguard = false,
        shouldRelockAfterFinish = false,
        reasonBn = "ফোন লক থাকলেও ব্যাকগ্রাউন্ড API দিয়ে স্ক্রিন না জ্বালিয়েই কাজ সম্পন্ন হবে।",
      )
    }
    if (!autoUnlockEnabled || (isDeviceSecure && !hasSavedCredentials)) {
      return SmartLockExecutionPlan(
        mode = SmartLockExecutionMode.LOCKED_CANNOT_BYPASS_SECURE_LOCK,
        phoneState = phoneState,
        shouldWakeScreen = false,
        shouldSwipeKeyguard = false,
        shouldRelockAfterFinish = false,
        reasonBn = "ফোন সুরক্ষিত লক অবস্থায় আছে — অনুমোদিত ডিভাইস আনলক বা অটো-আনলক সেটিং ছাড়া UI কাজ সম্ভব নয়।",
      )
    }
    return SmartLockExecutionPlan(
      mode = SmartLockExecutionMode.LOCKED_REQUIRES_DEVICE_AUTH_OR_UNLOCK,
      phoneState = phoneState,
      shouldWakeScreen = phoneState.phase == LivePhoneLockPhase.SCREEN_OFF,
      shouldSwipeKeyguard = phoneState.phase != LivePhoneLockPhase.APP_LOCKED,
      shouldRelockAfterFinish = true,
      reasonBn = "UI কাজের জন্য নিরাপদে আনলক করে কাজ শেষে পুনরায় লক করা হবে।",
    )
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
      if (isInHomeOrAppExitTransition()) return
      val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
      if (pm.isInteractive) return
      val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      if (km != null && !km.isKeyguardLocked && !km.isDeviceLocked) return

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
      // 1. Steady mid-lower to upper-center sweep cleanly above bottom gesture/edge guard
      LockSwipeVector(w * 0.50f, h * 0.72f, w * 0.50f, h * 0.20f, 380L),
      // 2. Smooth mid-screen sweep fallback (well clear of navigation bar)
      LockSwipeVector(w * 0.50f, h * 0.66f, w * 0.50f, h * 0.16f, 420L),
      // 3. Upper-mid sweep fallback when notifications cover lower half
      LockSwipeVector(w * 0.50f, h * 0.60f, w * 0.50f, h * 0.14f, 450L),
    )
  }

  fun unlockDeviceIfLocked(ctx: Context, acc: RiyaAccessibilityService?): UnlockAttemptResult {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val kmEarly = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val keyguardLockedEarly = kmEarly?.isKeyguardLocked == true
    val deviceLockedEarly =
      if (Build.VERSION.SDK_INT >= 22) (kmEarly?.isDeviceLocked == true) else keyguardLockedEarly
    val activeRootEarly = acc?.rootInActiveWindow?.packageName?.toString()?.lowercase()?.trim().orEmpty()
    val explicitKeyguardPkgEarly =
      activeRootEarly.contains("keyguard") || activeRootEarly.contains("lockscreen")
    if (pm?.isInteractive != false && !keyguardLockedEarly && !deviceLockedEarly &&
      ((kmEarly != null && !explicitKeyguardPkgEarly) ||
        isInHomeOrAppExitTransition() ||
        hasVisibleRegularAppOrLauncherWindow(acc) ||
        isLauncherPackage(ctx, activeRootEarly))
    ) {
      return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "ফোন আগেই আনলক আছে।")
    }
    if (pm?.isInteractive != false && isInHomeOrAppExitTransition()) {
      return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "ফোন আগেই আনলক আছে।")
    }
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
    if (isInHomeOrAppExitTransition() || (pm?.isInteractive != false && kmEarly != null && !kmEarly.isKeyguardLocked && !deviceLockedEarly)) {
      return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "ফোন আগেই আনলক আছে।")
    }

    wakeScreenIfNeeded(ctx, acc)
    // Wait until display is interactive and Smart Lock / Face Unlock keyguard animations settle cleanly on Android 16
    val wakeWaitDeadline = System.currentTimeMillis() + 850L
    while (pm?.isInteractive == false && System.currentTimeMillis() < wakeWaitDeadline) {
      Thread.sleep(60L)
    }
    Thread.sleep(320L)

    if (isScreenUnlockedNow(ctx, acc, lockType)) {
      return UnlockAttemptResult(UnlockStatus.UNLOCKED_SUCCESSFULLY, "স্ক্রিনের আলো জ্বালিয়ে আনলক করা হয়েছে ✓")
    }

    val kmCheck = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val deviceLockedCheck =
      if (Build.VERSION.SDK_INT >= 22) (kmCheck?.isDeviceLocked == true) else (kmCheck?.isKeyguardLocked == true)
    if (kmCheck != null && !kmCheck.isKeyguardLocked && !deviceLockedCheck && pm?.isInteractive != false) {
      return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "ফোন আগেই আনলক আছে।")
    }
    val fgPkgNow = resolveBestForegroundPackage(acc)
    if ((isRegularAppPackage(fgPkgNow) || hasVisibleRegularAppOrLauncherWindow(acc)) &&
      !hasFullScreenVisibleKeyguardLayer(acc) &&
      !isSystemKeyguardUiShowing(acc) &&
      !hasBouncerUiShowing(acc, lockType) &&
      !deviceLockedCheck
    ) {
      return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "ফোন আগেই আনলক আছে।")
    }
    val keyguardConfirmed =
      hasFullScreenVisibleKeyguardLayer(acc) ||
        isSystemKeyguardUiShowing(acc) ||
        hasBouncerUiShowing(acc, lockType) ||
        (kmCheck?.isKeyguardLocked == true && !isRegularAppPackage(fgPkgNow) && !hasVisibleRegularAppOrLauncherWindow(acc))
    if (!keyguardConfirmed && (pm?.isInteractive != false)) {
      return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "ফোন আগেই আনলক আছে।")
    }

    val dm = acc.resources.displayMetrics
    val strategies = buildLockscreenSwipeStrategies(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
    if (!hasBouncerUiShowing(acc, lockType)) {
      for (swipe in strategies) {
        if (isInHomeOrAppExitTransition()) {
          return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "হোম স্ক্রিন আনলক অবস্থায় আছে।")
        }
        val kmLoop = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val devLockedLoop =
          if (Build.VERSION.SDK_INT >= 22) (kmLoop?.isDeviceLocked == true) else (kmLoop?.isKeyguardLocked == true)
        val fgLoop = resolveBestForegroundPackage(acc)
        val regularForegroundActive =
          (isRegularAppPackage(fgLoop) || isLauncherPackage(ctx, fgLoop) || hasVisibleRegularAppOrLauncherWindow(acc)) &&
            !hasFullScreenVisibleKeyguardLayer(acc) &&
            !isSystemKeyguardUiShowing(acc) &&
            !hasBouncerUiShowing(acc, lockType)
        if ((kmLoop != null && !kmLoop.isKeyguardLocked && !devLockedLoop) ||
          regularForegroundActive ||
          isScreenUnlockedNow(ctx, acc, lockType)
        ) {
          return UnlockAttemptResult(UnlockStatus.ALREADY_UNLOCKED, "ফোন আগেই আনলক আছে।")
        }
        acc.swipe(swipe.startX, swipe.startY, swipe.endX, swipe.endY, durationMs = swipe.durationMs)
        val bouncerReady =
          pollUnlocked(1050L) {
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

  private val nonAppLockPackages =
    setOf(
      "com.aistudio.riyaai.krmxvp",
      "com.example",
      "com.whatsapp",
      "com.imo.android.imoim",
      "com.facebook.orca",
      "org.telegram.messenger",
      "com.android.chrome",
      "com.google.android.googlequicksearchbox",
      "com.android.settings",
      "com.termux",
    )

  fun isAppLockScreenShowing(acc: RiyaAccessibilityService?): Boolean {
    if (acc == null) return false
    val activeRoot = acc.rootInActiveWindow
    val activePkg = activeRoot?.packageName?.toString()?.lowercase()?.trim().orEmpty()
    val roots = collectAllRoots(acc)
    for (root in roots) {
      val pkg = root.packageName?.toString()?.lowercase()?.trim().orEmpty()
      if (pkg in appLockPackages) return true
    }
    if (activeRoot == null) return false
    if (activePkg.isBlank() ||
      activePkg == "com.android.systemui" ||
      activePkg in nonAppLockPackages ||
      isLauncherPackage(acc, activePkg) ||
      activePkg.contains("browser")
    ) {
      return false
    }
    val textDump = collectCompactScreenText(listOf(activeRoot))
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
    return relockScreen(acc?.applicationContext, acc)
  }

  fun relockScreen(ctx: Context?, acc: RiyaAccessibilityService?): Boolean {
    if (ctx != null && LxrDeviceAndHardwareEngine.isDeviceAdminActive(ctx)) {
      try {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        if (dpm != null) {
          dpm.lockNow()
          return true
        }
      } catch (_: Throwable) {}
    }
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

  private fun collectCompactScreenText(
    roots: List<AccessibilityNodeInfo>,
    visibleOnly: Boolean = false,
  ): String {
    val sb = StringBuilder()
    fun walk(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > 20 || sb.length > 1500) return
      if (visibleOnly && !n.isVisibleToUser) return
      n.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
      n.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
      if (!visibleOnly) {
        n.viewIdResourceName?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
      }
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
