package com.example

import android.content.Context
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
import com.example.service.AudioEchoGuard
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.LxrSpeechInterruptGuard
import com.example.service.LxrToolWorkDescriber
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_RED
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class LxrV233UpdateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun playbackHeadGuardPreventsMidSpeechEchoCutoffAcrossLongBufferedReplies() {
    val now = 100_000L
    // Packets burst-wrote 2.5 seconds ago, AudioTrack still has buffered audio ahead of playbackHead -> MUST stay guarded!
    assertTrue(
      AudioEchoGuard.isPlaybackHeadStillSpeaking(
        isPlaying = true,
        playbackHeadFrames = 48_000L,
        lastAudibleEndFrame = 120_000L,
        lastSpeakerWriteMs = now - 2_500L,
        nowMs = now,
        activeTurnHasStartedOutput = false,
      ),
    )

    // Once playback head catches up to lastAudibleEndFrame AND server turn is finished -> unmute cleanly!
    assertFalse(
      AudioEchoGuard.isPlaybackHeadStillSpeaking(
        isPlaying = true,
        playbackHeadFrames = 120_000L,
        lastAudibleEndFrame = 120_000L,
        lastSpeakerWriteMs = now - 1_200L,
        nowMs = now,
        activeTurnHasStartedOutput = false,
      ),
    )

    // Non-VoiceMatch speaker echo at 5200 RMS must NOT break through loudspeaker threshold (7200)
    assertTrue(
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = now,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = now,
        rawRms = 5200.0,
        lastSpeakerOutputRms = 3500.0,
        voiceMatchBargeIn = false,
        consecutiveLoudChunks = 4,
        phoneTailHoldMs = 180L,
        minDirectBargeInRms = 7200.0,
        directRatio = 1.85,
      ),
    )

    // Boss Voice Match ("শোনো" / "থামো") MUST immediately break through mid-speech!
    assertFalse(
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = now,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = now,
        rawRms = 2400.0,
        lastSpeakerOutputRms = 3500.0,
        voiceMatchBargeIn = true,
        consecutiveLoudChunks = 1,
        phoneTailHoldMs = 180L,
        minDirectBargeInRms = 7200.0,
        directRatio = 1.85,
      ),
    )
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("শোনো"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("থামো"))
  }

  @Test
  fun activeWorkRendersInsideCompactStatusPillAndClearsToListeningWithoutTopPopup() {
    val camLabel = LxrToolWorkDescriber.describeBn("switch_camera_lens", JSONObject().put("lens", "front"))
    assertTrue(camLabel.startsWith("কাজ করছে:"))
    assertFalse(camLabel.contains("LXR--AI এখন কাজ করছে"))

    try {
      RiyaState.activeWorkLabel.value = camLabel
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
      composeTestRule.onNodeWithText(camLabel, substring = true).assertIsDisplayed()

      RiyaState.activeWorkLabel.value = null
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("শুনছি বস...", substring = true).assertIsDisplayed()
    } finally {
      RiyaState.activeWorkLabel.value = null
    }
  }

  @Test
  fun personaEnforcesStrictTumiFull15CapabilitiesAndAll11PersonalityRules() {
    assertTrue(RiyaPersona.TEXT.contains("v2.43.0"))
    assertTrue(RiyaPersona.TEXT.contains("MANDATORY \"বস\" + \"তুমি\" RULE"))
    assertTrue(RiyaPersona.TEXT.contains("মিষ্টি দুষ্টুমি"))
    assertTrue(RiyaPersona.TEXT.contains("Caring & Supportive"))
    assertTrue(RiyaPersona.TEXT.contains("মোঃ উজ্জল হোসেন"))

    assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("আপনার"))
    assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("আপনাকে"))
    assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("বলুন"))
    assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("করুন"))
    assertTrue(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("তোমার"))
    for (num in 1..15) {
      val bnDigits = num.toString().map { ('০'.code + (it - '0')).toChar() }.joinToString("")
      assertTrue(
        "Missing capability number $bnDigits.",
        RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("$bnDigits."),
      )
    }
  }

  @Test
  fun selfDiagnosisChecksMicrophoneCameraAccessibilityAndRequiresBossPermission() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val report = LxrDeviceAndHardwareEngine.diagnoseSelfHealth(ctx)
    val keys = report.issues.map { it.key }.toSet()
    assertTrue(keys.contains("microphone"))
    assertTrue(keys.contains("camera"))
    assertTrue(keys.contains("accessibility"))
    assertTrue(report.summaryBn.contains("তোমার অনুমতি"))
    assertFalse(report.summaryBn.contains("আপনার"))
  }
}
