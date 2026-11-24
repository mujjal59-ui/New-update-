package com.example

import com.example.ai.RiyaPersona
import com.example.data.ReplyDelayOption
import com.example.service.AudioEchoGuard
import com.example.service.LiveVadConfig
import com.example.service.LxrSpeechInterruptGuard
import com.example.service.SwitchRowMatcher
import com.example.service.UnlockPolicyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LxrV231UpdateTest {

  @Test
  fun speechInterruptGuardRecognizesShonoThamoDaraoAndEnglishCues() {
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("শোনো"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("আরে একটু শোনো বস বলছি"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("থামো"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("এই থামো তো"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("দাঁড়াও এক মিনিট"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("stop talking"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("wait a second"))

    assertFalse(LxrSpeechInterruptGuard.containsInterruptCommand("আজ আবহাওয়া কেমন"))
    assertFalse(LxrSpeechInterruptGuard.containsInterruptCommand("তোমাকে একটা গান শোনাও"))
    assertFalse(LxrSpeechInterruptGuard.containsInterruptCommand(""))
  }

  @Test
  fun replyDelayOptionsAndVadProfilesMatchInstantFastNormalSpeeds() {
    assertEquals(0L, ReplyDelayOption.INSTANT.voiceDelayMs)
    assertEquals(500L, ReplyDelayOption.FAST.voiceDelayMs)
    assertEquals(100L, ReplyDelayOption.NORMAL.voiceDelayMs)

    assertEquals(360, LiveVadConfig.effectiveSilenceDurationMs(ReplyDelayOption.INSTANT))
    assertEquals(540, LiveVadConfig.effectiveSilenceDurationMs(ReplyDelayOption.FAST))
    assertEquals(700, LiveVadConfig.effectiveSilenceDurationMs(ReplyDelayOption.NORMAL))

    assertTrue(
      LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.INSTANT) <
        LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.FAST)
    )
    assertTrue(
      LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.FAST) <
        LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.NORMAL)
    )
  }

  @Test
  fun audioEchoGuardPermitsStrongBargeInWhileProtectingBaselineThresholds() {
    // Baseline signature remains backward compatible with older echo guard expectations
    assertTrue(
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = 5000L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 4950L,
        rawRms = 3200.0,
        lastSpeakerOutputRms = 3500.0,
        voiceMatchBargeIn = false,
        consecutiveLoudChunks = 0,
        phoneTailHoldMs = 140L,
      )
    )

    // Lowered v2.31.0 live barge-in threshold unmutes after 2 loud speech chunks
    assertFalse(
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = 5000L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 4950L,
        rawRms = 4200.0,
        lastSpeakerOutputRms = 2100.0,
        voiceMatchBargeIn = false,
        consecutiveLoudChunks = 2,
        phoneTailHoldMs = 150L,
        minDirectBargeInRms = 3200.0,
        directRatio = 1.55,
      )
    )
  }

  @Test
  fun switchRowMatcherPrefersUseLocationAndRejectsNeighborSettings() {
    val useLocScore =
      SwitchRowMatcher.scoreRowMatch(
        rowTextRaw = "Use location",
        labels = listOf("Use location", "Location", "GPS"),
        excludeLabels = listOf("Emergency Location Service", "Wi-Fi scanning", "Bluetooth scanning"),
      )
    val emergencyScore =
      SwitchRowMatcher.scoreRowMatch(
        rowTextRaw = "Emergency Location Service",
        labels = listOf("Use location", "Location", "GPS"),
        excludeLabels = listOf("Emergency Location Service", "Wi-Fi scanning", "Bluetooth scanning"),
      )
    val scanningScore =
      SwitchRowMatcher.scoreRowMatch(
        rowTextRaw = "Wi-Fi scanning",
        labels = listOf("Use location", "Location", "GPS"),
        excludeLabels = listOf("Emergency Location Service", "Wi-Fi scanning", "Bluetooth scanning"),
      )

    assertTrue("Exact Use location row must score high positive", useLocScore >= 100)
    assertTrue("Excluded Emergency Location Service must be rejected", emergencyScore < 0)
    assertTrue("Unmatched Wi-Fi scanning must not match", scanningScore < 0)
  }

  @Test
  fun lockedScreenToolsAutoUnlockSilentlyWhileApiOnlyToolsDoNot() {
    // Locked-screen tools Boss wants executed automatically (notifications, messages, screen reading, opening apps)
    assertTrue(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("read_screen", genuinelyLocked = true))
    assertTrue(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("current_screen", genuinelyLocked = true))
    assertTrue(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("check_notifications", genuinelyLocked = true))
    assertTrue(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("inspect_contact_chat", genuinelyLocked = true))
    assertTrue(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("reply_to_incoming_message", genuinelyLocked = true))
    assertTrue(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("open_app", genuinelyLocked = true))
    assertTrue(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("send_message", genuinelyLocked = true))

    // Pure API tools remain zero-unlock on locked screen
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("current_time", genuinelyLocked = true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("get_live_location", genuinelyLocked = true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("search_memory", genuinelyLocked = true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("diagnose_and_fix_self", genuinelyLocked = true))

    // Nothing auto-unlocks when phone is already unlocked
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("read_screen", genuinelyLocked = false))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("open_app", genuinelyLocked = false))
  }

  @Test
  fun personaReflectsV231PersonalityAndCapabilities() {
    assertTrue(RiyaPersona.TEXT.contains("v2.31.0"))
    assertTrue(RiyaPersona.TEXT.contains("শোনো"))
    assertTrue(RiyaPersona.TEXT.contains("থামো"))
    assertTrue(RiyaPersona.TEXT.contains("কথার ভঙ্গি ও স্টাইল বোঝা"))
    assertTrue(RiyaPersona.TEXT.contains("গান গেয়ে"))
    assertTrue(RiyaPersona.TEXT.contains("অনুমতি চেয়ে নিজের কথা"))
    assertTrue(RiyaPersona.TEXT.contains("মাথা নত"))
    assertTrue(RiyaPersona.TEXT.contains("v2.30.0"))
  }
}
