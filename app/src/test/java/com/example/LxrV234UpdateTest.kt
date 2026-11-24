package com.example

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaPersona
import com.example.data.RiyaState
import com.example.data.Store
import com.example.service.LxrCameraVisionEngine
import com.example.service.LxrCameraVisionGuard
import com.example.service.LxrFloatingOverlayManager
import com.example.service.LxrToolWorkDescriber
import com.example.service.OrbTaskBadgeState
import com.example.service.VisionFrameSnapshot
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_RED
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class LxrV234UpdateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @After
  fun cleanup() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    LxrFloatingOverlayManager.hide(ctx)
    shadowOf(Looper.getMainLooper()).idle()
    RiyaState.activeWorkLabel.value = null
    RiyaState.setVisionCameraActive(false)
    LxrCameraVisionEngine.clearSnapshot()
  }

  @Test
  fun orbBadgeShowsTaskAndScreenReadingOverEveryScreenAndDisappearsWhenDone() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Store.setFloatingOrbEnabled(ctx, true)
    ShadowSettings.setCanDrawOverlays(true)

    val taskLabel = LxrToolWorkDescriber.describeBn("open_app", JSONObject().put("app_name", "WhatsApp"))
    assertTrue(taskLabel.startsWith("কাজ করছে:"))
    assertTrue(LxrToolWorkDescriber.isScreenReadingTool("read_screen"))
    assertTrue(LxrToolWorkDescriber.isScreenReadingTool("inspect_contact_chat"))
    assertFalse(LxrToolWorkDescriber.isScreenReadingTool("set_flashlight"))

    val shown = LxrFloatingOverlayManager.showForTask(ctx, taskLabel, isReadingScreen = true)
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue("Floating LxR Orb must appear over screen when task starts", shown)
    assertTrue("Overlay must be attached when overlay permission is granted", LxrFloatingOverlayManager.isShowing)

    val activeBadge = LxrFloatingOverlayManager.orbBadgeState.value
    assertTrue(activeBadge.visible)
    assertTrue(activeBadge.isReadingScreen)
    val badgeText = activeBadge.compactBadgeText.orEmpty()
    assertTrue("Orb pill must show screen-reading state", badgeText.contains("স্ক্রিন পড়ছে"))
    assertTrue("Orb pill must show current app/task", badgeText.contains("WhatsApp"))

    // In-app home pill also mirrors current work cleanly without legacy top banner
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
    assertTrue(
      composeTestRule.onAllNodesWithTag("active_work_banner").fetchSemanticsNodes().isEmpty(),
    )
    composeTestRule.onNodeWithTag("home_status_pill").assertIsDisplayed()
    composeTestRule.onNodeWithText("WhatsApp", substring = true).assertIsDisplayed()

    // Once reading finishes and task completes, orb clears and hides cleanly
    LxrFloatingOverlayManager.setScreenReading(ctx, false)
    val taskOnlyText = LxrFloatingOverlayManager.orbBadgeState.value.compactBadgeText.orEmpty()
    assertFalse(taskOnlyText.contains("স্ক্রিন পড়ছে"))
    assertTrue(taskOnlyText.contains("WhatsApp"))

    LxrFloatingOverlayManager.completeTaskAndScheduleHide(ctx, hideDelayMs = 450L)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(null, RiyaState.activeWorkLabel.value)
    assertFalse(LxrFloatingOverlayManager.orbBadgeState.value.isReadingScreen)

    LxrFloatingOverlayManager.hide(ctx)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse("Orb must dismiss cleanly after task finishes", LxrFloatingOverlayManager.isShowing)
    assertEquals(OrbTaskBadgeState(visible = false), LxrFloatingOverlayManager.orbBadgeState.value)
  }

  @Test
  fun cameraVisionGuardRejectsMissingStaleDarkFramesAndBuildsHonestPrompts() {
    val now = 200_000L

    // 1) Missing frame -> MUST forbid fabrication
    val missingEval = LxrCameraVisionGuard.evaluateFrameUsability(null, nowMs = now)
    assertFalse(missingEval.hasFreshFrame)
    assertFalse(missingEval.isFaceVisibleNow)
    val missingPrompt = LxrCameraVisionGuard.buildHonestVisionPrompt(null, "আমাকে দেখো", nowMs = now)
    assertTrue(missingPrompt.contains("ছবি নেই"))
    assertTrue(missingPrompt.contains("বানিয়ে বলবে না"))

    // 2) Stale frame (> 4500ms) -> rejected as expired
    val stale =
      VisionFrameSnapshot(
        jpegBytes = byteArrayOf(1, 2, 3, 4),
        capturedAtMs = now - 6_500L,
        isFrontCamera = true,
        faceDetected = true,
        faceCount = 1,
        averageLuma = 120,
        sourceMode = "background",
      )
    val staleEval = LxrCameraVisionGuard.evaluateFrameUsability(stale, nowMs = now)
    assertFalse(staleEval.hasFreshFrame)

    // 3) Dark / covered lens (averageLuma < 10) -> rejected cleanly
    val darkBmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    darkBmp.eraseColor(Color.rgb(2, 2, 2))
    val darkSnap = LxrCameraVisionEngine.analyzeBitmap(darkBmp, isFrontCamera = true, sourceMode = "background")
    darkBmp.recycle()
    assertTrue((darkSnap.jpegBytes?.size ?: 0) > 0)
    assertTrue(darkSnap.averageLuma < LxrCameraVisionGuard.MIN_READABLE_LUMA)
    val darkEval = LxrCameraVisionGuard.evaluateFrameUsability(darkSnap, nowMs = darkSnap.capturedAtMs)
    assertTrue(darkEval.hasFreshFrame)
    assertFalse(darkEval.isReadableBrightness)
    val darkPrompt = LxrCameraVisionGuard.buildHonestVisionPrompt(darkSnap, "কী দেখছো?")
    assertTrue(darkPrompt.contains("অন্ধকার"))

    // 4) Bright real frame without a face -> dynamically reports faceDetected=false and instructs no fabrication
    val brightBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    brightBmp.eraseColor(Color.rgb(180, 190, 200))
    val brightSnap = LxrCameraVisionEngine.analyzeBitmap(brightBmp, isFrontCamera = true, sourceMode = "foreground")
    brightBmp.recycle()
    assertTrue(brightSnap.averageLuma >= 150)
    assertFalse("Blank bright frame must NOT hallucinate a face", brightSnap.faceDetected)
    val brightPrompt = LxrCameraVisionGuard.buildHonestVisionPrompt(brightSnap, "আমাকে কেমন লাগছে?")
    assertTrue(brightPrompt.contains("আসল লাইভ ছবি"))
    assertTrue(brightPrompt.contains("স্পষ্ট মুখ শনাক্ত হয়নি"))

    // 5) Real-time video JSON schema switches cleanly between Gemini 2.5 and 3.1
    val payload = byteArrayOf(10, 20, 30)
    val json25 = JSONObject(LxrCameraVisionGuard.buildRealtimeVideoChunkJson("gemini-2.5-flash-native-audio", payload))
    assertTrue(json25.getJSONObject("realtimeInput").has("mediaChunks"))
    val json31 = JSONObject(LxrCameraVisionGuard.buildRealtimeVideoChunkJson("gemini-3.1-flash-live-preview", payload))
    assertTrue(json31.getJSONObject("realtimeInput").has("video"))
  }

  @Test
  fun stateSyncsVisionCameraAndManifestDeclaresForegroundServiceCamera() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    RiyaState.setVisionCameraActive(true)
    assertTrue(RiyaState.cameraOn)
    assertTrue(RiyaState.visionCameraActive.value)

    RiyaState.setVisionCameraActive(false)
    assertFalse(RiyaState.cameraOn)
    assertFalse(RiyaState.visionCameraActive.value)

    val pkgFlags =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_PERMISSIONS
      } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_PERMISSIONS
      }
    val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, pkgFlags)
    val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertTrue(
      "Manifest must declare FOREGROUND_SERVICE_CAMERA for background vision",
      perms.contains("android.permission.FOREGROUND_SERVICE_CAMERA"),
    )
    assertTrue(perms.contains("android.permission.CAMERA"))
    assertNotNull(LxrToolWorkDescriber.describeBn("inspect_camera_vision", JSONObject()))
  }

  @Test
  fun personaIncludesV234HonestVisionAndOrbCapabilitiesWithStrictTumi() {
    assertTrue(RiyaPersona.TEXT.contains("v2.34.0"))
    assertTrue(RiyaPersona.TEXT.contains("v2.33.0"))
    assertTrue(RiyaPersona.TEXT.contains("inspect_camera_vision"))
    assertTrue(RiyaPersona.TEXT.contains("FOREGROUND_SERVICE_CAMERA"))
    assertTrue(RiyaPersona.TEXT.contains("👁️ স্ক্রিন পড়ছে..."))
    assertTrue(RiyaPersona.TEXT.contains("একজন প্রিয় মানুষের মতো কথা বলবে"))
    assertTrue(RiyaPersona.TEXT.contains("মোঃ উজ্জল হোসেন"))
    assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("আপনার"))
    assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("বলুন"))
    for (num in 1..15) {
      val bnDigits = num.toString().map { ('০'.code + (it - '0')).toChar() }.joinToString("")
      assertTrue(
        "Missing capability number $bnDigits.",
        RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("$bnDigits."),
      )
    }
  }
}
