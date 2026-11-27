package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaPersona
import com.example.data.RiyaState
import com.example.data.Store
import com.example.service.LivePhoneLockPhase
import com.example.service.LockVaultManager
import com.example.service.LxrAssistScreenBridge
import com.example.service.LxrCompanionAgent
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.UnlockPolicyGuard
import com.example.service.UnlockStatus
import com.example.ui.KeyPage
import com.example.ui.PermPage
import com.example.ui.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class LxrV230UpdateTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun falseLockIsEliminatedOnUnlockedRegularAppOrTransientNullRoot() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()

    val unlockedAppLocked =
      LockVaultManager.evaluateMainScreenLocked(
        screenInteractive = true,
        keyguardLocked = false,
        deviceLocked = false,
        activeWindowPkg = "com.aistudio.riyaai.krmxvp",
        lockScreenVisibleInUi = false,
      )
    assertFalse("Unlocked LXR Home screen must never be marked locked", unlockedAppLocked)

    // Even if keyguard flag lingers while regular app is foreground, active regular app is UNLOCKED unless keyguard UI is actually shown
    val transientRegularAppLocked =
      LockVaultManager.evaluateMainScreenLocked(
        screenInteractive = true,
        keyguardLocked = true,
        deviceLocked = false,
        activeWindowPkg = "com.whatsapp",
        lockScreenVisibleInUi = false,
      )
    assertFalse("Foreground regular app without visible lock UI must stay UNLOCKED", transientRegularAppLocked)

    val keyguardConfirmedLocked =
      LockVaultManager.evaluateMainScreenLocked(
        screenInteractive = true,
        keyguardLocked = true,
        deviceLocked = false,
        activeWindowPkg = "com.android.systemui",
        lockScreenVisibleInUi = true,
      )
    assertTrue("Confirmed keyguard lockscreen must report locked", keyguardConfirmedLocked)

    val liveReport = LockVaultManager.describeLivePhoneState(ctx, acc = null)
    assertFalse("Robolectric interactive unlocked context must report UNLOCKED", liveReport.isLocked)
    assertEquals(LivePhoneLockPhase.UNLOCKED, liveReport.phase)

    // unlockDeviceIfLocked on already-unlocked phone must report ALREADY_UNLOCKED (never swipe)
    val res = LockVaultManager.unlockDeviceIfLocked(ctx, acc = null)
    assertEquals(UnlockStatus.ALREADY_UNLOCKED, res.status)
  }

  @Test
  fun assistScreenBridgeProvidesGuaranteedFallbackScreenReport() {
    LxrAssistScreenBridge.latestAssistAppPackage = "com.whatsapp"
    LxrAssistScreenBridge.latestAssistScreenText = "চ্যাট হেডার: আম্মু\nকেমন আছো বাবা?"
    LxrAssistScreenBridge.latestAssistUpdatedAtMs = System.currentTimeMillis()

    val report =
      LxrAssistScreenBridge.buildFallbackScreenReport(
        snapshotLines = listOf("Send button"),
        currentScreenSummary = "",
        activePkgHint = "com.whatsapp",
      )
    assertTrue(report.contains("com.whatsapp"))
    assertTrue(report.contains("Send button"))
    assertTrue(report.contains("কেমন আছো বাবা?"))
  }

  @Test
  fun extraCriticalModelAndFrontCameraStateAndUnlockGuardWork() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("gemini-2.5-pro", Store.extraCriticalModel(ctx))
    Store.setExtraCriticalModel(ctx, "gemini-2.5-flash")
    assertEquals("gemini-2.5-flash", Store.extraCriticalModel(ctx))
    assertEquals("gemini-2.5-flash", RiyaState.extraCriticalModelId.value)

    RiyaState.useFrontCamera.value = false
    RiyaState.useFrontCamera.value = true
    assertTrue(RiyaState.useFrontCamera.value)
    RiyaState.useFrontCamera.value = false

    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("diagnose_and_fix_self", true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("critical_task_solve", true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("switch_camera_lens", true))

    val diag = LxrDeviceAndHardwareEngine.diagnoseSelfHealth(ctx)
    assertFalse(diag.isHealthy)
    assertEquals(!LxrDeviceAndHardwareEngine.isWriteSettingsGranted(ctx), diag.issues.any { it.key == "write_settings" })
    assertTrue(diag.issues.any { it.key == "accessibility" })
    assertTrue(diag.issues.any { it.key == "assistant" })
  }

  @Test
  fun manifestDeclaresWriteSettingsAndDigitalAssistantServices() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val pm = ctx.packageManager
    val pkgInfo = pm.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)

    val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertTrue(Manifest.permission.WRITE_SETTINGS in requested)

    val voiceSvc =
      pm.getServiceInfo(ComponentName(ctx.packageName, "com.example.service.LxrVoiceInteractionService"), 0)
    assertNotNull(voiceSvc)
    val sessionSvc =
      pm.getServiceInfo(ComponentName(ctx.packageName, "com.example.service.LxrVoiceInteractionSessionService"), 0)
    assertNotNull(sessionSvc)
    val recSvc =
      pm.getServiceInfo(ComponentName(ctx.packageName, "com.example.service.LxrRecognitionService"), 0)
    assertNotNull(recSvc)
  }

  @Test
  fun personaIncludesHumanEmotionalTraitsSelfDiagnosisAndV230Capabilities() {
    assertTrue(RiyaPersona.TEXT.contains("v2.43.0"))
    assertTrue(RiyaPersona.TEXT.contains("diagnose_and_fix_self"))
    assertTrue(RiyaPersona.TEXT.contains("critical_task_solve"))
    assertTrue(RiyaPersona.TEXT.contains("switch_camera_lens"))
    assertTrue(RiyaPersona.TEXT.contains("মাথা নত"))
    assertTrue(RiyaPersona.TEXT.contains("মিষ্টি দুষ্টুমি"))
    assertTrue(RiyaPersona.TEXT.contains("empathy"))
    assertTrue(RiyaPersona.TEXT.contains("warm laughter"))
    assertTrue(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("Self-Diagnosis"))
  }

  @Test
  fun permPageShowsWriteSettingsAndDefaultAssistantCards() {
    composeRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        PermPage(back = {}, onNavigate = {})
      }
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithTag("perm_list").performScrollToNode(hasText("Modify System Settings"))
    composeRule.onNodeWithText("Modify System Settings").assertIsDisplayed()
    composeRule.onNodeWithTag("perm_list").performScrollToNode(hasText("Default Digital Assistant"))
    composeRule.onNodeWithText("Default Digital Assistant").assertIsDisplayed()
  }

  @Test
  fun settingsPageShowsInteractiveGpsTile() {
    val snap = LxrCompanionAgent.sampleNow(ApplicationProvider.getApplicationContext())
    assertTrue(snap.toCompactContextBn().contains("GPS:"))

    composeRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        SettingsPage(back = {}, onNavigate = {})
      }
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithTag("settings_gps_tile").assertIsDisplayed()
    composeRule.onNodeWithText("GPS / Location").assertIsDisplayed()
  }

  @Test
  fun keyPageAllowsSelectingExtraCriticalAiModel() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Store.setExtraCriticalModel(ctx, "gemini-2.5-pro")

    composeRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        KeyPage(back = {}, onNavigate = {})
      }
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithTag("extra_critical_model_card").performScrollTo()
    composeRule.onNodeWithTag("extra_critical_model_card").assertIsDisplayed()
    composeRule.onNodeWithTag("extra_model_option_gemini_2_5_flash_critical").performClick()
    composeRule.waitForIdle()
    assertEquals("gemini-2.5-flash", Store.extraCriticalModel(ctx))
  }
}
