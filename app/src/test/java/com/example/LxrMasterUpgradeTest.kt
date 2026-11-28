package com.example

import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import com.example.data.RiyaState
import com.example.data.Store
import com.example.service.AgentExecutionOutput
import com.example.service.CodeSecurityAuditorAgent
import com.example.service.FailureClassifier
import com.example.service.FreeFirstProviderRouter
import com.example.service.GatewayFailureKind
import com.example.service.GatewayHttpResponse
import com.example.service.GeminiLive
import com.example.service.LiveSessionConfigBuilder
import com.example.service.LxrAutonomousOrchestrator
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.LxrGatewayTransport
import com.example.service.LxrModelRegistry
import com.example.service.LxrProviderGateway
import com.example.service.LxrProviderRegistry
import com.example.service.LxrResearchVerificationHelper
import com.example.service.LxrSecretRedactor
import com.example.service.LxrSpecialistRegistry
import com.example.service.LxrTermuxFrameworkAdapters
import com.example.service.LxrUsageLedger
import com.example.service.ProviderCircuitBreaker
import com.example.service.ProviderCircuitState
import com.example.service.ProviderHealthMonitor
import com.example.service.ReasoningAndMathAgent
import com.example.service.ResearchSourceItem
import com.example.service.RiyaVoiceManager
import com.example.service.SourceCredibilityTier
import com.example.service.TaskComplexityTier
import java.net.SocketTimeoutException
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
class LxrMasterUpgradeTest {
  private lateinit var ctx: Context

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    RiyaState.micMuted.value = false
    RiyaState.isBluetoothMicActive.value = false
    RiyaState.isBtCommOutputVerified.value = false
    Store.setApiKey(ctx, "")
    Store.setElevenLabsApiKey(ctx, "")
    Store.setElevenLabsEnabled(ctx, true)
    Store.setModel(ctx, Store.DEFAULT_LIVE_MODEL)
    LxrProviderGateway.resetForTest()
    LxrUsageLedger.resetForTest()
    RiyaVoiceManager.initialize(ctx)
  }

  @Test
  fun bug1_elevenLabsLiveSetupAlwaysNegotiatesAudio_andSupportsMp3Fallback() {
    val modalities = LiveSessionConfigBuilder.buildResponseModalities(isElevenMode = true)
    assertEquals(1, modalities.length())
    assertEquals("AUDIO", modalities.getString(0))
    assertTrue(LiveSessionConfigBuilder.shouldIncludeOutputAudioTranscription(false))
    assertFalse(LiveSessionConfigBuilder.shouldIncludeOutputAudioTranscription(true))

    val formatOrder = RiyaVoiceManager.resolveElevenLabsFormatOrder()
    assertEquals(listOf("pcm_24000", "pcm_16000", "mp3_44100_128"), formatOrder)
    assertEquals("mp3_44100_128", RiyaVoiceManager.resolveElevenLabsFormatOrder("mp3_44100_128").first())

    val tierBlockedJson =
      """{"detail":{"status":"output_format_not_allowed","message":"pcm_24000 requires Creator tier for key sk-testsecret999999999"}}"""
    val parsed = RiyaVoiceManager.parseElevenLabsError(403, tierBlockedJson)
    assertTrue(parsed.isOutputFormatTierBlocked)
    assertFalse(parsed.detailMessage.contains("sk-testsecret999999999"))
  }

  @Test
  fun bug2_micMuteWorksAcrossAllFourVoiceModels_andBlocksSpeechOutput() {
    val allModels = Store.ALL_VOICE_MODEL_OPTIONS
    assertEquals(4, allModels.size)

    for (opt in allModels) {
      Store.setModel(ctx, opt.modelId)
      val live = GeminiLive(ctx)
      live.setMicMuted(true)
      assertTrue("Model ${opt.title} failed to set micMuted", RiyaState.micMuted.value)
      assertFalse(
        "Output playback must be rejected when muted on ${opt.title}",
        LiveSessionConfigBuilder.shouldAcceptOutputPlayback(
          micMuted = RiyaState.micMuted.value,
          blockedByLimit = false,
          suppressingStale = false,
        ),
      )
      live.setMicMuted(false)
      assertFalse("Model ${opt.title} failed to unmute", RiyaState.micMuted.value)
      assertTrue(
        LiveSessionConfigBuilder.shouldAcceptOutputPlayback(
          micMuted = RiyaState.micMuted.value,
          blockedByLimit = false,
          suppressingStale = false,
        ),
      )
    }

    Store.setElevenLabsApiKey(ctx, "eleven-dummy-key")
    RiyaState.micMuted.value = true
    RiyaVoiceManager.speak(
      rawText = "বস টেস্ট মিউট",
      interrupt = true,
      forceSpeak = true,
      allowWhenMicMuted = false,
    )
    assertFalse(RiyaVoiceManager.isActivelySpeaking())
  }

  @Test
  fun bug3_audioOutputFallsBackToMediaUsageWhenBtCommUnverifiedOrWiredHeadset() {
    assertEquals(
      AudioAttributes.USAGE_MEDIA,
      LiveSessionConfigBuilder.resolveOutputAudioUsage(
        usingBtAudio = true,
        isBtCommOutputVerified = false,
        hasWiredOrA2dpHeadset = false,
      ),
    )
    assertEquals(
      AudioAttributes.USAGE_VOICE_COMMUNICATION,
      LiveSessionConfigBuilder.resolveOutputAudioUsage(
        usingBtAudio = true,
        isBtCommOutputVerified = true,
        hasWiredOrA2dpHeadset = false,
      ),
    )
    assertEquals(
      AudioAttributes.USAGE_MEDIA,
      RiyaVoiceManager.selectOutputAudioUsage(
        isBluetoothMicActive = true,
        isBtCommOutputVerified = false,
      ),
    )
    assertEquals(
      AudioAttributes.USAGE_VOICE_COMMUNICATION,
      RiyaVoiceManager.selectOutputAudioUsage(
        isBluetoothMicActive = true,
        isBtCommOutputVerified = true,
      ),
    )
  }

  @Test
  fun providerGateway_usesVerifiedModels_circuitBreakerTransitions_andFailoverOn429() {
    val models = LxrModelRegistry.orderedGeminiModels("gemini-2.0-flash")
    assertFalse("Retired gemini-2.0-flash must never be used", models.contains("gemini-2.0-flash"))
    assertTrue(models.contains("gemini-2.5-pro"))
    assertTrue(models.contains("gemini-2.5-flash"))

    assertEquals(GatewayFailureKind.AUTH_INVALID, FailureClassifier.classify(GatewayHttpResponse(401, "")))
    assertEquals(GatewayFailureKind.RATE_LIMITED, FailureClassifier.classify(GatewayHttpResponse(429, "")))
    assertEquals(GatewayFailureKind.MODEL_NOT_FOUND, FailureClassifier.classify(GatewayHttpResponse(404, "")))
    assertEquals(GatewayFailureKind.SERVER_ERROR, FailureClassifier.classify(GatewayHttpResponse(503, "")))
    assertEquals(
      GatewayFailureKind.TIMEOUT_OR_NETWORK,
      FailureClassifier.classify(GatewayHttpResponse(-1, "", exception = SocketTimeoutException("timeout"))),
    )

    val breaker = ProviderCircuitBreaker(failureThreshold = 2, cooldownMs = 5_000L)
    assertTrue(breaker.allowRequest("p1", 1000L))
    breaker.recordFailure("p1", 1000L)
    assertEquals(ProviderCircuitState.CLOSED, breaker.stateOf("p1", 1500L))
    breaker.recordFailure("p1", 2000L)
    assertEquals(ProviderCircuitState.OPEN, breaker.stateOf("p1", 2500L))
    assertFalse(breaker.allowRequest("p1", 3000L))
    assertEquals(ProviderCircuitState.HALF_OPEN, breaker.stateOf("p1", 7000L))
    assertTrue(breaker.allowRequest("p1", 7000L))
    breaker.recordSuccess("p1")
    assertEquals(ProviderCircuitState.CLOSED, breaker.stateOf("p1", 7100L))

    val fakeTransport =
      object : LxrGatewayTransport {
        var calls = 0
        override fun executePost(url: String, headers: Map<String, String>, jsonBody: String): GatewayHttpResponse {
          calls += 1
          return if (url.contains("gemini-2.5-pro")) {
            GatewayHttpResponse(429, """{"error":{"message":"Quota exceeded for AIzaFakeSecret123456789"}}""")
          } else {
            GatewayHttpResponse(
              200,
              """{"candidates":[{"content":{"parts":[{"text":"ফেলওভার সফল হয়েছে বস"}]}}],"usageMetadata":{"totalTokenCount":120}}""",
            )
          }
        }
      }

    Store.setApiKey(ctx, "AIzaFakeSecret123456789")
    Store.setExtraCriticalModel(ctx, "gemini-2.5-pro")
    val res =
      LxrProviderGateway.executeCriticalReasoning(
        ctx = ctx,
        taskPrompt = "কঠিন কোড বিশ্লেষণ করো",
        transport = fakeTransport,
        sleeper = {},
      )
    assertTrue(res.succeeded)
    assertEquals("gemini-2.5-flash", res.modelId)
    assertTrue(res.formattedBn.contains("ফেলওভার সফল হয়েছে বস"))
    assertEquals(120L, LxrUsageLedger.snapshot.value.llmTokensUsed)
    assertFalse(LxrProviderRegistry.inspectProviders(ctx).isEmpty())

    Store.setApiKey(ctx, "")
    val noKeyMsg = LxrDeviceAndHardwareEngine.solveCriticalTaskWithExtraModel(ctx, "টেস্ট টাস্ক")
    assertTrue(noKeyMsg.contains("API Key", ignoreCase = true))
  }

  @Test
  fun specialistAgents_15Registered_mathEvaluator_securityAuditor_andOrchestratorWork() {
    assertEquals(15, LxrSpecialistRegistry.ALL_AGENTS.size)

    val mathRes = ReasoningAndMathAgent.evaluateExpression("12 + 3 * (10 - 4) / 2")
    assertTrue(mathRes.isSuccess)
    assertEquals(21.0, mathRes.getOrThrow(), 0.0001)
    assertTrue(ReasoningAndMathAgent.evaluateExpression("10 / 0").isFailure)

    val leaks =
      CodeSecurityAuditorAgent.auditSnippet(
        """
        val k = "AIzaTestLeakKey1234567890"
        val url = "http://insecure.example.com/api"
        """.trimIndent(),
      )
    assertTrue(leaks.size >= 2)

    assertEquals(
      TaskComplexityTier.MULTI_AGENT_PARALLEL,
      LxrAutonomousOrchestrator.classifyComplexity(
        "প্রথমে সিকিউরিটি অডিট করো এবং 25 * 4 + 10 হিসাব করে সারসংক্ষেপ দাও",
      ),
    )

    val report =
      LxrAutonomousOrchestrator.executeAutonomousTask(
        ctx = ctx,
        prompt = "সিকিউরিটি চেক এবং 25 * 4 + 10 হিসাব করো http://bad.example.com",
        recordInMonitor = false,
      )
    assertTrue(report.selectedAgentIds.isNotEmpty())
    assertTrue(report.finalSummaryBn.contains("110"))
    assertTrue(report.verificationGate.succeeded)
  }

  @Test
  fun termuxAdapters_all8Registered_andHonestUnavailabilityReported() {
    assertEquals(8, LxrTermuxFrameworkAdapters.FRAMEWORKS.size)
    val expectedIds = setOf("langgraph", "crewai", "litellm", "aider", "openhands", "haystack", "llamaindex", "semgrep")
    assertEquals(expectedIds, LxrTermuxFrameworkAdapters.FRAMEWORKS.map { it.id }.toSet())

    val probes = LxrTermuxFrameworkAdapters.probeAll(ctx)
    assertEquals(8, probes.size)
    assertTrue(probes.all { !it.isAvailableNow && it.installCommand.isNotBlank() })
  }

  @Test
  fun researchVerification_classifiesCredibilityTiers_andFlagsContradictions() {
    assertEquals(
      SourceCredibilityTier.PRIMARY_OFFICIAL,
      LxrResearchVerificationHelper.classifyUrl("https://developer.android.com/guide"),
    )
    assertEquals(
      SourceCredibilityTier.REFERENCE_DOCS,
      LxrResearchVerificationHelper.classifyUrl("https://en.wikipedia.org/wiki/Android"),
    )
    assertEquals(
      SourceCredibilityTier.NEWS_MEDIA,
      LxrResearchVerificationHelper.classifyUrl("https://www.bbc.com/news/technology"),
    )

    val conflictingSources =
      listOf(
        ResearchSourceItem("Web Search", "Feature launched in 2025", "https://example.com/a", "Confirmed released in 2025"),
        ResearchSourceItem("Web Search", "Feature cancelled in 2024", "https://example.com/b", "Denied and not launched in 2024 2026"),
      )
    val report = LxrResearchVerificationHelper.evaluateSources(conflictingSources)
    assertTrue(report.contradictionAlertsBn.isNotEmpty())
    assertTrue(report.tierSummaryBn.isNotBlank())
  }
}
