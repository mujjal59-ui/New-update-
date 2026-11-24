package com.example

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaPersona
import com.example.data.ReplyDelayOption
import com.example.data.RiyaState
import com.example.service.LivePhoneLockPhase
import com.example.service.LiveVadConfig
import com.example.service.LockVaultManager
import com.example.service.LxrToolWorkDescriber
import com.example.service.NetworkStabilityGuard
import com.example.service.SwitchRowMatcher
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_RED
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class LxrV232UpdateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun lockVaultNeverMisreadsUnlockedDeviceAndDetectsOsLockedScreen() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val shadowKm = shadowOf(km)
    val shadowPm = shadowOf(pm)

    // Unlocked screen must NEVER trust residual SystemUI overlay hints or swipe upward
    shadowPm.setIsInteractive(true)
    shadowKm.setKeyguardLocked(false)
    shadowKm.setIsDeviceLocked(false)
    assertFalse(LockVaultManager.shouldTrustSystemUiLockOverlay(keyguardLocked = false, deviceLocked = false))
    assertFalse(LockVaultManager.isMainScreenGenuinelyLockedBeforeTask(ctx, null))
    assertTrue(LockVaultManager.isScreenUnlockedNow(ctx, null))
    val unlockedState = LockVaultManager.describeLivePhoneState(ctx, null)
    assertEquals(LivePhoneLockPhase.UNLOCKED, unlockedState.phase)
    assertFalse(unlockedState.isLocked)

    // OS-locked screen (e.g. Infinix clock-only lockscreen) MUST be detected accurately even without text prompts
    shadowKm.setKeyguardLocked(true)
    shadowKm.setIsDeviceLocked(true)
    assertTrue(LockVaultManager.shouldTrustSystemUiLockOverlay(keyguardLocked = true, deviceLocked = true))
    assertTrue(LockVaultManager.isMainScreenGenuinelyLockedBeforeTask(ctx, null))
    assertFalse(LockVaultManager.isScreenUnlockedNow(ctx, null))
    val lockedState = LockVaultManager.describeLivePhoneState(ctx, null)
    assertEquals(LivePhoneLockPhase.KEYGUARD_LOCKED, lockedState.phase)
    assertTrue(lockedState.isLocked)
  }

  @Test
  fun activeWorkDescriberAndHomeBannerReflectCurrentTaskInBengali() {
    val gpsLabel =
      LxrToolWorkDescriber.describeBn(
        "control_device_setting",
        JSONObject().put("setting_name", "location"),
      )
    assertTrue(gpsLabel.contains("কাজ করছে:"))
    assertTrue(gpsLabel.contains("GPS / Location"))

    val waLabel =
      LxrToolWorkDescriber.describeBn(
        "open_app",
        JSONObject().put("app_name", "WhatsApp"),
      )
    assertTrue(waLabel.contains("কাজ করছে:"))
    assertTrue(waLabel.contains("WhatsApp"))

    val homeLabel =
      LxrToolWorkDescriber.describeBn(
        "global_action",
        JSONObject().put("action", "home"),
      )
    assertTrue(homeLabel.contains("হোম স্ক্রিনে"))

    try {
      RiyaState.activeWorkLabel.value = gpsLabel
      composeTestRule.setContent {
        MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
          RiyaHomeScreen(
            status = "শুনছি বস...",
            running = true,
            camOn = false,
            accessibilityOn = true,
            onMenu = {},
            onMic = {},
            onCam = {},
            onChat = {},
            onSettings = {},
            onFixAccessibility = {},
          )
        }
      }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("home_status_pill").assertIsDisplayed()
      composeTestRule.onNodeWithText(gpsLabel, substring = true).assertIsDisplayed()
    } finally {
      RiyaState.activeWorkLabel.value = null
    }
  }

  @Test
  fun gpsLocationSwitchMatchingAndInstantResponseTuningWorkCleanly() {
    val score =
      SwitchRowMatcher.scoreRowMatch(
        rowTextRaw = "Use location\nAllow apps that have permission to use your location",
        labels = listOf("use location", "location access", "location"),
        excludeLabels = listOf("wi-fi scanning", "bluetooth scanning", "emergency location", "accuracy"),
      )
    assertTrue("Master 'Use location' switch row must score high", score >= 80)

    val accuracyScore =
      SwitchRowMatcher.scoreRowMatch(
        rowTextRaw = "Google Location Accuracy",
        labels = listOf("use location", "location access", "location"),
        excludeLabels = listOf("wi-fi scanning", "bluetooth scanning", "emergency location", "accuracy"),
      )
    assertTrue("Sub-setting Location Accuracy must be excluded", accuracyScore < 0)

    assertEquals(360, LiveVadConfig.effectiveSilenceDurationMs(ReplyDelayOption.INSTANT))
    assertTrue(
      LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.INSTANT) <= 120L,
    )
    assertTrue(
      LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.INSTANT) <
        LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.FAST),
    )
    assertFalse(
      NetworkStabilityGuard.shouldWatchdogReconnect(
        waitingServerReply = true,
        bossTurnElapsedMs = 15_000L,
        serverSilenceMs = 15_000L,
        queueBytes = 0L,
      ),
    )
  }

  @Test
  fun personaIncludesV232AndPreservesV231AndV230Capabilities() {
    assertTrue(RiyaPersona.TEXT.contains("v2.38.0"))
    assertTrue(RiyaPersona.TEXT.contains("3D Studio"))
    assertTrue(RiyaPersona.TEXT.contains("Slide again to unlock"))
  }
}
