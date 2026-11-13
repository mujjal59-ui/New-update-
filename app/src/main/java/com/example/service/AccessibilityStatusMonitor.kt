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
  private var stateListener: AccessibilityManager.AccessibilityStateChangeListener? = null

  fun startListening(context: Context) {
    val appCtx = context.applicationContext
    val am = appCtx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (am != null && stateListener == null) {
      val listener =
        AccessibilityManager.AccessibilityStateChangeListener {
          refreshNow(appCtx, speakIfTurnedOff = true)
        }
      am.addAccessibilityStateChangeListener(listener)
      stateListener = listener
    }
    refreshNow(appCtx, speakIfTurnedOff = false)
  }

  /**
   * Performs strict three-layer OS verification so Riya NEVER falsely displays "Enabled"
   * when Accessibility has actually been turned off by the system or user.
   */
  fun refreshNow(context: Context, speakIfTurnedOff: Boolean = true): SystemHealthSnapshot {
    val appCtx = context.applicationContext
    val serviceComponent = ComponentName(appCtx, RiyaAccessibilityService::class.java)
    val expectedShort = "${appCtx.packageName}/${RiyaAccessibilityService::class.java.name}"
    val expectedFlatten = serviceComponent.flattenToString()

    // Layer 1: AccessibilityManager enabled services list
    var enabledByAm = false
    try {
      val am = appCtx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
      if (am != null && am.isEnabled) {
        val enabledList =
          am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        enabledByAm =
          enabledList.any { info ->
            val id = info.id.orEmpty()
            val svcInfo = info.resolveInfo?.serviceInfo
            id.equals(expectedShort, ignoreCase = true) ||
              id.equals(expectedFlatten, ignoreCase = true) ||
              (svcInfo?.packageName == appCtx.packageName &&
                svcInfo?.name == RiyaAccessibilityService::class.java.name)
          }
      }
    } catch (_: Exception) {
      enabledByAm = false
    }

    // Layer 2: Settings.Secure check
    var enabledInSecureSettings = false
    try {
      val accessibilityEnabled =
        Settings.Secure.getInt(appCtx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
      if (accessibilityEnabled == 1) {
        val enabledServices =
          Settings.Secure.getString(
            appCtx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
          ).orEmpty()
        enabledInSecureSettings =
          enabledServices
            .split(':')
            .any { entry ->
              entry.equals(expectedShort, ignoreCase = true) ||
                entry.equals(expectedFlatten, ignoreCase = true) ||
                entry.contains(RiyaAccessibilityService::class.java.simpleName)
            }
      }
    } catch (_: Exception) {
      enabledInSecureSettings = false
    }

    // Layer 3: Live bound service instance heartbeat
    val isBound = RiyaRuntimeBridge.isAccessibilityServiceConnected.value
    val settingsActive = enabledByAm || enabledInSecureSettings

    // If OS Settings says OFF, treat as strictly OFF immediately even if unbind callback lagged
    if (!settingsActive && isBound) {
      RiyaRuntimeBridge.setServiceConnected(false)
    }

    val trulyActive = settingsActive && RiyaRuntimeBridge.isAccessibilityServiceConnected.value
    val turnedOffNow = wasPreviouslyActive && !trulyActive

    val notifActive =
      try {
        NotificationManagerCompat.getEnabledListenerPackages(appCtx).contains(appCtx.packageName)
      } catch (_: Exception) {
        false
      }

    if (trulyActive) {
      wasPreviouslyActive = true
      RiyaNotificationHelper.cancelAccessibilityDisabledAlert(appCtx)
    } else if (turnedOffNow) {
      wasPreviouslyActive = false
      onAccessibilityTurnedOff(appCtx, speakIfTurnedOff)
    }

    val snapshot =
      SystemHealthSnapshot(
        isAccessibilityEnabledInSettings = settingsActive,
        isAccessibilityServiceBound = RiyaRuntimeBridge.isAccessibilityServiceConnected.value,
        isAccessibilityTrulyActive = trulyActive,
        isNotificationListenerActive = notifActive,
        accessibilityJustTurnedOff = turnedOffNow || (!trulyActive && _healthState.value.accessibilityJustTurnedOff),
        lastCheckedAt = System.currentTimeMillis(),
      )
    _healthState.value = snapshot
    return snapshot
  }

  fun onServiceDisconnectedFromOs(context: Context) {
    RiyaRuntimeBridge.setServiceConnected(false)
    refreshNow(context, speakIfTurnedOff = true)
    if (!wasPreviouslyActive) {
      onAccessibilityTurnedOff(context, speakAlert = true)
    }
  }

  fun acknowledgeAccessibilityAlert() {
    _healthState.value = _healthState.value.copy(accessibilityJustTurnedOff = false)
  }

  private fun onAccessibilityTurnedOff(context: Context, speakAlert: Boolean) {
    RiyaNotificationHelper.notifyAccessibilityDisabled(context)
    RiyaRuntimeBridge.postAutomationBanner(
      "সতর্কতা: বস, অ্যাক্সেসিবিলিটি বন্ধ হয়ে গেছে! দয়া করে চালু করুন।"
    )
    if (speakAlert) {
      RiyaVoiceManager.speakUrgent(
        context,
        "বস, জরুরি বার্তা। আমার অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ হয়ে গেছে। দয়া করে সেটিংস থেকে Riya AI অ্যাক্সেসিবিলিটি চালু করে দিন।",
      )
    }
  }
}
