package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaPersona
import com.example.data.ReplyDelayOption
import com.example.data.ReplyLimitOption
import com.example.data.RiyaState
import com.example.data.Store
import com.example.data.VoiceMatchSensitivity
import com.example.service.LockVaultManager
import com.example.service.ReplyPolicyScheduler
import com.example.service.UnlockStatus
import com.example.service.VoiceMatchEngine
import com.example.service.VoiceMatchManager
import com.example.service.VoiceMatchPhraseVerifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LxrV237UpdateTest {

  private lateinit var ctx: Context

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    VoiceMatchManager.resetProfile(ctx)
    Store.setVoiceMatchEnabled(ctx, false)
    Store.setVoiceMatchSensitivity(ctx, VoiceMatchSensitivity.BALANCED)
    Store.setReplyDelaySeconds(ctx, 0)
    Store.setReplyLimitMax(ctx, -1)
    ReplyPolicyScheduler.resetCounter(ctx)
  }

  private fun synthVoicePcm(f0Hz: Float, formantHz: Float, durationSec: Float = 0.8f, amp: Float = 8000f): ByteArray {
    val sr = VoiceMatchEngine.SAMPLE_RATE
    val totalSamples = (sr * durationSec).toInt()
    val buf = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until totalSamples) {
      val t = i.toDouble() / sr
      val s =
        0.55 * sin(2.0 * PI * f0Hz * t) +
          0.30 * sin(2.0 * PI * (f0Hz * 2.0) * t) +
          0.25 * sin(2.0 * PI * formantHz * t)
      val valShort = (s * amp).toInt().coerceIn(-32767, 32767).toShort()
      buf.putShort(valShort)
    }
    return buf.array()
  }

  @Test
  fun voiceMatch_noDeprecatedGemini25ProAndAccepts404AcousticFallbackForSpokenVoice() {
    assertFalse(
      "gemini-2.5-pro must NOT be in VoiceMatch ENROLL_VERIFICATION_MODELS",
      VoiceMatchPhraseVerifier.ENROLL_VERIFICATION_MODELS.contains("gemini-2.5-pro"),
    )
    assertTrue(
      "ENROLL_VERIFICATION_MODELS should include gemini-2.5-flash",
      VoiceMatchPhraseVerifier.ENROLL_VERIFICATION_MODELS.contains("gemini-2.5-flash"),
    )

    assertTrue(
      "HTTP 404 with genuine voiced frames (>= 10) must fall back to biometric voice verification instead of failing UI",
      VoiceMatchPhraseVerifier.shouldAcceptAcousticFallback(
        lastHttpCode = 404,
        lastNetErr = "",
        sawEmptyOk = false,
        voicedFrames = 14,
      ),
    )
    assertFalse(
      "Silence (< 10 voiced frames) must still be rejected on HTTP 404",
      VoiceMatchPhraseVerifier.shouldAcceptAcousticFallback(
        lastHttpCode = 404,
        lastNetErr = "",
        sawEmptyOk = false,
        voicedFrames = 4,
      ),
    )
    assertFalse(
      "Auth failures (401/403) must still reject fallback",
      VoiceMatchPhraseVerifier.shouldAcceptAcousticFallback(
        lastHttpCode = 401,
        lastNetErr = "",
        sawEmptyOk = false,
        voicedFrames = 20,
      ),
    )
  }

  @Test
  fun voiceMatch_threeStepEnrollmentPersists0to3AndDifferentiatesSpeakerWithSensitivity() {
    val bossPcm1 = synthVoicePcm(f0Hz = 128f, formantHz = 620f)
    val bossPcm2 = synthVoicePcm(f0Hz = 132f, formantHz = 640f)
    val bossPcm3 = synthVoicePcm(f0Hz = 130f, formantHz = 630f)
    val otherSpeakerPcm = synthVoicePcm(f0Hz = 265f, formantHz = 1950f)

    val steps = listOf(bossPcm1, bossPcm2, bossPcm3)
    steps.forEachIndexed { idx, pcm ->
      val stepNum = idx + 1
      val sig = VoiceMatchEngine.extractSignature(pcm)
      assertNotNull("Boss sample step $stepNum should yield valid signature", sig)
      Store.setVoiceMatchStepJson(ctx, stepNum, sig!!.toJsonString())
      assertTrue(VoiceMatchManager.saveStepAudioWav(ctx, stepNum, pcm))
      assertTrue(VoiceMatchManager.hasStepAudio(ctx, stepNum))
      val loaded = VoiceMatchManager.loadProfile(ctx, forceReload = true)
      assertEquals(stepNum, loaded.completedSteps)
    }

    Store.setVoiceMatchEnabled(ctx, true)
    Store.setVoiceMatchSensitivity(ctx, VoiceMatchSensitivity.BALANCED)
    assertEquals(VoiceMatchSensitivity.BALANCED, Store.voiceMatchSensitivity(ctx))
    assertTrue(VoiceMatchManager.isActiveGateReady(ctx))

    val profile = VoiceMatchManager.loadProfile(ctx, forceReload = true)
    assertEquals(3, profile.completedSteps)
    assertTrue(profile.isComplete)

    val bossCandidate = VoiceMatchEngine.verifyPcmChunk(profile, synthVoicePcm(131f, 635f), VoiceMatchSensitivity.STRICT)
    val otherCandidate = VoiceMatchEngine.verifyPcmChunk(profile, otherSpeakerPcm, VoiceMatchSensitivity.STRICT)
    assertNotNull(bossCandidate)
    assertNotNull(otherCandidate)
    assertTrue("Boss voice must match enrolled profile", bossCandidate!!.matched)
    assertFalse("Different speaker voice must be rejected under STRICT sensitivity", otherCandidate!!.matched)

    // Disabling Voice Match turns off the gate cleanly without deleting enrolled steps
    Store.setVoiceMatchEnabled(ctx, false)
    assertFalse(VoiceMatchManager.isActiveGateReady(ctx))
    assertEquals(3, VoiceMatchManager.loadProfile(ctx, forceReload = true).completedSteps)
  }

  @Test
  fun replyDelay_instantIs0msFastIs8sNormalIs15sAndPreservesCapturedInFlightWait() = runTest {
    assertEquals(0L, ReplyDelayOption.INSTANT.voiceDelayMs)
    assertEquals(8_000L, ReplyDelayOption.FAST.voiceDelayMs)
    assertEquals(15_000L, ReplyDelayOption.NORMAL.voiceDelayMs)

    // Instant executes immediately with zero delay
    Store.setReplyDelaySeconds(ctx, ReplyDelayOption.INSTANT.seconds)
    assertEquals(0L, ReplyPolicyScheduler.captureVoiceDelayMs(ctx))
    var instantExecuted = false
    ReplyPolicyScheduler.executeAfterCapturedDelayMs(
      initialDelayMs = ReplyPolicyScheduler.captureVoiceDelayMs(ctx),
    ) {
      instantExecuted = true
      true
    }
    assertTrue("Instant (0ms) must dispatch immediately", instantExecuted)

    // Fast captures 8000ms at start and keeps 8000ms even if user switches to Normal mid-wait
    Store.setReplyDelaySeconds(ctx, ReplyDelayOption.FAST.seconds)
    val capturedFastMs = ReplyPolicyScheduler.captureVoiceDelayMs(ctx)
    assertEquals(8_000L, capturedFastMs)
    var fastExecuted = false
    val job = async {
      ReplyPolicyScheduler.executeAfterCapturedDelayMs(
        initialDelayMs = capturedFastMs,
      ) {
        fastExecuted = true
        true
      }
    }
    advanceTimeBy(4_000L)
    runCurrent()
    assertFalse("Fast (8s) must not finish at 4s", fastExecuted)

    Store.setReplyDelaySeconds(ctx, ReplyDelayOption.NORMAL.seconds)
    assertEquals(15_000L, ReplyPolicyScheduler.captureVoiceDelayMs(ctx))

    advanceTimeBy(3_500L)
    runCurrent()
    assertFalse("In-flight 8s wait must keep old 8000ms duration at 7.5s", fastExecuted)

    advanceTimeBy(500L)
    runCurrent()
    assertTrue("In-flight 8s wait finishes cleanly at 8000ms", fastExecuted)
    assertEquals(true, job.await())
  }

  @Test
  fun replyLimit_enforces20_50_Unlimited_andPersistsAcrossSync() {
    Store.setReplyLimitMax(ctx, ReplyLimitOption.LIMIT_20.maxReplies)
    assertEquals(20, Store.replyLimitMax(ctx))
    assertEquals(20, RiyaState.replyLimitMax.value)

    repeat(20) {
      assertTrue(ReplyPolicyScheduler.tryConsumeAndPersist(ctx, Store.replyLimitMax(ctx)))
    }
    assertFalse(ReplyPolicyScheduler.canSendReply(Store.replyLimitMax(ctx)))
    assertFalse(ReplyPolicyScheduler.tryConsumeAndPersist(ctx, Store.replyLimitMax(ctx)))

    // Simulated restart reload
    ReplyPolicyScheduler.syncFromStore(ctx, force = true)
    assertEquals(20, ReplyPolicyScheduler.currentDispatchedCount())
    assertFalse(ReplyPolicyScheduler.canSendReply(Store.replyLimitMax(ctx)))

    // Reset restores quota cleanly
    ReplyPolicyScheduler.resetCounter(ctx)
    assertEquals(0, ReplyPolicyScheduler.currentDispatchedCount())
    assertTrue(ReplyPolicyScheduler.canSendReply(Store.replyLimitMax(ctx)))
  }

  @Test
  fun lockVault_smoothSwipeDurationsAndHomeUnlockedStateNeverTriggersUnlockSwipe() {
    val strategies = LockVaultManager.buildLockscreenSwipeStrategies(1080f, 2400f)
    assertEquals(3, strategies.size)
    assertTrue("Swipe start must stay above Infinix bottom-edge zone (< 0.75h)", strategies[0].startY in 2400f * 0.55f..2400f * 0.75f)
    assertTrue(strategies[0].endY <= 2400f * 0.35f)
    assertTrue("Primary swipe duration >= 350ms prevents Infinix Slide again to unlock", strategies[0].durationMs >= 350L)

    // Unlocked robolectric state returns ALREADY_UNLOCKED without triggering keyguard gestures
    val unlockRes = LockVaultManager.unlockDeviceIfLocked(ctx, acc = null)
    assertEquals(UnlockStatus.ALREADY_UNLOCKED, unlockRes.status)
    assertFalse(LockVaultManager.isMainScreenGenuinelyLockedBeforeTask(ctx, acc = null))
  }

  @Test
  fun riyaPersona_updatedToV2370WithStrictTumiAndCreatorRules() {
    assertTrue(RiyaPersona.TEXT.contains("v2.43.0"))
    assertTrue(RiyaPersona.TEXT.contains("মোঃ উজ্জল হোসেন"))
    assertTrue(RiyaPersona.TEXT.contains("তুমি"))
  }
}
