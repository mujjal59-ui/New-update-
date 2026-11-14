package com.example.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SystemHealthSnapshot(
  val isAccessibilityEnabledInSettings: Boolean = false,
  val isAccessibilityServiceBound: Boolean = false,
  val isAccessibilityTrulyActive: Boolean = false,
  val isNotificationListenerActive: Boolean = false,
  val accessibilityJustTurnedOff: Boolean = false,
  val lastCheckedAt: Long = System.currentTimeMillis(),
)

object AccessibilityStatusMonitor {
  private val _healthState = MutableStateFlow(SystemHealthSnapshot())
  val healthState: StateFlow<SystemHealthSnapshot> = _healthState.asStateFlow()

  private var wasPreviouslyActive = false
  private var lastDisconnectedAtMs = 0L
  private var lastAlertSpokenAtMs = 0L
  private var stateListener: AccessibilityManager.AccessibilityStateChangeListener? = null

  fun startListening(context: Context) {
    val appCtx = context.applicationContext
    val am = appCtx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (am != null && stateListener == null) {
      val listener =
        AccessibilityManager.AccessibilityStateChangeListener {
          try {
            Keep.ensureAccessibility(appCtx)
          } catch (_: Exception) {}
          refreshNow(appCtx, speakIfTurnedOff = true)
        }
      am.addAccessibilityStateChangeListener(listener)
      stateListener = listener
    }
    refreshNow(appCtx, speakIfTurnedOff = false)
  }

  /**
   * Accurate live verification:
   * - If RiyaAccessibilityService.instance != null, OS is actively bound right now -> ALWAYS active.
   * - Otherwise checks AccessibilityManager + Settings.Secure (long and short component names).
   * - Debounces transient OS rebinds before speaking a disconnect alarm.
   */
  fun refreshNow(context: Context, speakIfTurnedOff: Boolean = true): SystemHealthSnapshot {
    val appCtx = context.applicationContext
    val serviceComponent = ComponentName(appCtx, RiyaAccessibilityService::class.java)
    val expectedLong = "${appCtx.packageName}/${RiyaAccessibilityService::class.java.name}"
    val expectedFlatten = serviceComponent.flattenToString()
    val expectedShort = serviceComponent.flattenToShortString()

    // Layer 1: Live bound service instance (authoritative when alive)
    val liveSingletonAlive = RiyaAccessibilityService.instance != null
    if (liveSingletonAlive && !RiyaRuntimeBridge.isAccessibilityServiceConnected.value) {
      RiyaRuntimeBridge.setServiceConnected(true)
    }

    // Layer 2: AccessibilityManager enabled services list
    var enabledByAm = checkEnabledInAm(appCtx, expectedLong, expectedFlatten, expectedShort)

    // Layer 3: Settings.Secure check
    var enabledInSecureSettings =
      checkEnabledInSecureSettings(appCtx, expectedLong, expectedFlatten, expectedShort)

    // Auto-restore immediately if WRITE_SECURE_SETTINGS is available and OS disabled it
    if (!liveSingletonAlive && !(enabledByAm || enabledInSecureSettings) && Keep.canSelfEnable(appCtx)) {
      if (Keep.ensureAccessibility(appCtx)) {
        enabledByAm = checkEnabledInAm(appCtx, expectedLong, expectedFlatten, expectedShort)
        enabledInSecureSettings =
          checkEnabledInSecureSettings(appCtx, expectedLong, expectedFlatten, expectedShort)
      }
    }

    var settingsActive = liveSingletonAlive || enabledByAm || enabledInSecureSettings
    var isBound = liveSingletonAlive || RiyaRuntimeBridge.isAccessibilityServiceConnected.value

    // If Settings shows ON but Android 16 / XOS left service unbound (zombie state) for >3s, auto-rebind if allowed
    val nowCheck = System.currentTimeMillis()
    if (settingsActive && !isBound && Keep.canSelfEnable(appCtx)) {
      if (lastDisconnectedAtMs > 0L && (nowCheck - lastDisconnectedAtMs) > 3000L) {
        if (Keep.forceRebindAccessibility(appCtx)) {
          enabledByAm = checkEnabledInAm(appCtx, expectedLong, expectedFlatten, expectedShort)
          enabledInSecureSettings =
            checkEnabledInSecureSettings(appCtx, expectedLong, expectedFlatten, expectedShort)
          settingsActive = enabledByAm || enabledInSecureSettings
          isBound =
            RiyaAccessibilityService.instance != null ||
              RiyaRuntimeBridge.isAccessibilityServiceConnected.value
        }
      }
    }

    if (!settingsActive && isBound && RiyaAccessibilityService.instance == null) {
      RiyaRuntimeBridge.setServiceConnected(false)
      isBound = false
    }

    // Truly active whenever service is bound OR listed running by AM OR enabled in Settings + bound
    val trulyActive = liveSingletonAlive || (settingsActive && isBound) || enabledByAm
    val now = System.currentTimeMillis()
    val turnedOffNow = wasPreviouslyActive && !trulyActive && !settingsActive

    val notifActive =
      RiyaRuntimeBridge.isNotificationListenerConnected.value ||
        try {
          NotificationManagerCompat.getEnabledListenerPackages(appCtx).contains(appCtx.packageName)
        } catch (_: Exception) {
          false
        }

    if (trulyActive) {
      wasPreviouslyActive = true
      lastDisconnectedAtMs = 0L
      RiyaNotificationHelper.cancelAccessibilityDisabledAlert(appCtx)
    } else if (turnedOffNow) {
      if (lastDisconnectedAtMs == 0L) lastDisconnectedAtMs = now
      // Debounce 2.5s so transient OS task switches/rebinds never scream a false voice alert
      if (now - lastDisconnectedAtMs >= 2500L) {
        wasPreviouslyActive = false
        onAccessibilityTurnedOff(appCtx, speakIfTurnedOff)
      }
    }

    val snapshot =
      SystemHealthSnapshot(
        isAccessibilityEnabledInSettings = settingsActive,
        isAccessibilityServiceBound = isBound,
        isAccessibilityTrulyActive = trulyActive,
        isNotificationListenerActive = notifActive,
        accessibilityJustTurnedOff =
          (!trulyActive && !settingsActive && _healthState.value.accessibilityJustTurnedOff) ||
            (turnedOffNow && now - lastDisconnectedAtMs >= 2500L),
        lastCheckedAt = now,
      )
    _healthState.value = snapshot
    return snapshot
  }

  private fun checkEnabledInAm(
    appCtx: Context,
    expectedLong: String,
    expectedFlatten: String,
    expectedShort: String,
  ): Boolean {
    return try {
      val am = appCtx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
      if (am != null) {
        val enabledList =
          am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        enabledList.any { info ->
          val id = info.id.orEmpty()
          val svcInfo = info.resolveInfo?.serviceInfo
          id.equals(expectedLong, ignoreCase = true) ||
            id.equals(expectedFlatten, ignoreCase = true) ||
            id.equals(expectedShort, ignoreCase = true) ||
            (id.contains(appCtx.packageName, ignoreCase = true) &&
              id.contains("RiyaAccessibilityService", ignoreCase = true)) ||
            (svcInfo?.packageName == appCtx.packageName &&
              svcInfo?.name == RiyaAccessibilityService::class.java.name)
        }
      } else {
        false
      }
    } catch (_: Exception) {
      false
    }
  }

  private fun checkEnabledInSecureSettings(
    appCtx: Context,
    expectedLong: String,
    expectedFlatten: String,
    expectedShort: String,
  ): Boolean {
    return try {
      val accessibilityEnabled =
        Settings.Secure.getInt(appCtx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
      if (accessibilityEnabled == 1) {
        val enabledServices =
          Settings.Secure.getString(
            appCtx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
          ).orEmpty()
        enabledServices
          .split(':')
          .any { entry ->
            entry.equals(expectedLong, ignoreCase = true) ||
              entry.equals(expectedFlatten, ignoreCase = true) ||
              entry.equals(expectedShort, ignoreCase = true) ||
              (entry.contains(appCtx.packageName, ignoreCase = true) &&
                entry.contains(RiyaAccessibilityService::class.java.simpleName, ignoreCase = true))
          }
      } else {
        false
      }
    } catch (_: Exception) {
      false
    }
  }

  fun isEnabledInOsSettings(context: Context): Boolean {
    val appCtx = context.applicationContext
    val serviceComponent = ComponentName(appCtx, RiyaAccessibilityService::class.java)
    val expectedLong = "${appCtx.packageName}/${RiyaAccessibilityService::class.java.name}"
    val expectedFlatten = serviceComponent.flattenToString()
    val expectedShort = serviceComponent.flattenToShortString()
    return checkEnabledInAm(appCtx, expectedLong, expectedFlatten, expectedShort) ||
      checkEnabledInSecureSettings(appCtx, expectedLong, expectedFlatten, expectedShort)
  }

  fun onServiceDisconnectedFromOs(context: Context) {
    val appCtx = context.applicationContext
    if (Keep.canSelfEnable(appCtx) && Keep.ensureAccessibility(appCtx)) {
      refreshNow(appCtx, speakIfTurnedOff = false)
      return
    }
    RiyaRuntimeBridge.setServiceConnected(false)
    // Do NOT scream immediately if Settings still shows ON (Android 16 / XOS may just be rebinding)
    refreshNow(appCtx, speakIfTurnedOff = false)
  }

  fun acknowledgeAccessibilityAlert() {
    _healthState.value = _healthState.value.copy(accessibilityJustTurnedOff = false)
  }

  private fun onAccessibilityTurnedOff(context: Context, speakAlert: Boolean) {
    val now = System.currentTimeMillis()
    RiyaNotificationHelper.notifyAccessibilityDisabled(context)
    RiyaRuntimeBridge.postAutomationBanner(
      "সতর্কতা: বস, LXR--AI অ্যাক্সেসিবিলিটি বন্ধ হয়ে গেছে! দয়া করে চালু করুন।"
    )
    if (speakAlert && now - lastAlertSpokenAtMs > 25_000L) {
      lastAlertSpokenAtMs = now
      RiyaVoiceManager.speakUrgent(
        context,
        "বস, LXR--AI-এর অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ হয়ে গেছে। দয়া করে সেটিংস থেকে চালু করে দিন।",
      )
    }
  }
}
