package com.example

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaPersona
import com.example.data.Store
import com.example.data.VoiceGender
import com.example.service.ImoSendPrecisionEngine
import com.example.service.InfinixDevicePlaybook
import com.example.service.Keep
import com.example.service.RiyaVoiceManager
import com.example.service.ScreenNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RiyaCoreUnitTest {
  @Test
  fun imoSendPrecisionEngine_excludesFloatingReactionsAndTrailingEmoji_selectsBlueSendButton() {
    val nodes =
      listOf(
        // Floating quick-reaction emoji bar above composer (e.g. heart / laugh)
        ScreenNodeInfo(
          text = "",
          contentDescription = "💕",
          viewId = "com.imo.android.imoim:id/quick_reaction_love",
          className = "android.widget.ImageView",
          isClickable = true,
          isEditable = false,
          isScrollable = false,
          bounds = Rect(930, 1850, 1050, 1940),
        ),
        ScreenNodeInfo(
          text = "হ্যালো বস",
          contentDescription = "",
          viewId = "com.imo.android.imoim:id/et_input",
          className = "android.widget.EditText",
          isClickable = true,
          isEditable = true,
          isScrollable = false,
          bounds = Rect(160, 2000, 720, 2120),
        ),
        ScreenNodeInfo(
          text = "",
          contentDescription = "Emoji",
          viewId = "com.imo.android.imoim:id/iv_emoji",
          className = "android.widget.ImageView",
          isClickable = true,
          isEditable = false,
          isScrollable = false,
          bounds = Rect(730, 2010, 820, 2110),
        ),
        ScreenNodeInfo(
          text = "",
          contentDescription = "",
          viewId = "com.imo.android.imoim:id/btn_send",
          className = "android.widget.ImageButton",
          isClickable = true,
          isEditable = false,
          isScrollable = false,
          bounds = Rect(930, 2005, 1050, 2115),
        ),
        ScreenNodeInfo(
          text = "",
          contentDescription = "Smiley Stickers",
          viewId = "com.imo.android.imoim:id/trailing_emoji_btn",
          className = "android.widget.ImageButton",
          isClickable = true,
          isEditable = false,
          isScrollable = false,
          bounds = Rect(825, 2005, 920, 2115),
        ),
      )

    val (selected, diag) = ImoSendPrecisionEngine.analyzeAndSelectSendNode(nodes)
    assertNotNull(selected)
    assertEquals("com.imo.android.imoim:id/btn_send", selected?.viewId)
    assertTrue(diag.succeeded)
    assertTrue(diag.excludedEmojiOrMediaNodes.any { it.contains("reaction") || it.contains("💕") })
  }

  @Test
  fun imoSendPrecisionEngine_withoutExplicitSendId_picksComposerRowNotAboveReactions() {
    val nodes =
      listOf(
        // Reaction above input bar with no blocked keyword in desc/id
        ScreenNodeInfo(
          text = "",
          contentDescription = "",
          viewId = "com.imo.android.imoim:id/item_7",
          className = "android.widget.ImageView",
          isClickable = true,
          isEditable = false,
          isScrollable = false,
          bounds = Rect(950, 1840, 1060, 1930),
        ),
        ScreenNodeInfo(
          text = "কেমন আছেন",
          contentDescription = "",
          viewId = "com.imo.android.imoim:id/input",
          className = "android.widget.EditText",
          isClickable = true,
          isEditable = true,
          isScrollable = false,
          bounds = Rect(140, 2000, 760, 2110),
        ),
        ScreenNodeInfo(
          text = "",
          contentDescription = "",
          viewId = "com.imo.android.imoim:id/action_right",
          className = "android.widget.ImageButton",
          isClickable = true,
          isEditable = false,
          isScrollable = false,
          bounds = Rect(910, 2005, 1030, 2105),
        ),
      )

    val (selected, diag) = ImoSendPrecisionEngine.analyzeAndSelectSendNode(nodes)
    assertNotNull(selected)
    assertEquals("com.imo.android.imoim:id/action_right", selected?.viewId)
    assertTrue(diag.succeeded)
  }

  @Test
  fun brandingAndVoiceSanitizer_useLxrAiNeverRiyaOrPriya() {
    assertFalse(RiyaPersona.TEXT.contains("Priya", ignoreCase = true))
    assertFalse(RiyaPersona.TEXT.contains("Riya", ignoreCase = true))
    assertTrue(RiyaPersona.TEXT.contains("LXR--AI"))

    val raw = "**Priya** ও Riya AI এখন WhatsApp, imo ও Messenger এ Infinix Hot 60 Pro+ Android 16 তে কাজ করছে!"
    val clean = RiyaVoiceManager.sanitizeForClearBengaliSpeech(raw)

    assertFalse(clean.contains("Priya", ignoreCase = true))
    assertFalse(clean.contains("Riya", ignoreCase = true))
    assertFalse(clean.contains("রিয়া"))
    assertFalse(clean.contains("*"))
    assertTrue(clean.contains("এল এক্স আর এ আই"))
    assertTrue(clean.contains("হোয়াটসঅ্যাপ"))
    assertTrue(clean.contains("ইমো"))
    assertTrue(clean.contains("মেসেঞ্জার"))
  }

  @Test
  fun geminiTtsVoiceStudio_containsAllRequestedVoicesAndBnBdConfig() {
    assertEquals("gemini-3.1-flash-tts-preview", Store.TTS_MODEL_ID)
    assertEquals("bn-BD", Store.TTS_LOCALE_TAG)
    assertEquals("Read aloud in a warm, welcoming tone.", Store.DEFAULT_TTS_STYLE)

    val expectedNames = listOf("Aoede", "Iapetus", "Leda", "Orus", "Achernar", "Charon", "Sulafat")
    val actualNames = Store.VOICE_OPTIONS.map { it.id }
    assertTrue(actualNames.containsAll(expectedNames))
    assertEquals(7, Store.VOICE_OPTIONS.size)
    assertTrue(Store.VOICE_OPTIONS.any { it.gender == VoiceGender.MALE })
    assertTrue(Store.VOICE_OPTIONS.any { it.gender == VoiceGender.FEMALE })
  }

  @Test
  fun keepAccessibilityOn_neverFalselyReportsEnabled() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    assertFalse(Keep.accessibilityOn(ctx))
    assertTrue(InfinixDevicePlaybook.rules.any { it.id == "xos_settings_bottom_search" })
  }

  @Test
  fun creatorIdentity_containsExpectedFounderBioAndRepositoryDirectMatch() = kotlinx.coroutines.runBlocking {
    assertTrue(RiyaPersona.TEXT.contains("মোঃ উজ্জল হোসেন"))
    assertTrue(RiyaPersona.TEXT.contains("পার্সোনাল অ্যাসিস্ট্যান্ট"))
    val repo = com.example.ai.RiyaAiRepository()
    var reply = ""
    repo.streamAssistantReply("তোমাকে কে বানিয়েছে?", "", true).collect { reply = it }
    assertTrue(reply.contains("মোঃ উজ্জল হোসেন"))
    assertTrue(reply.contains("কঠোর পরিশ্রম করেন"))
  }

  @Test
  fun runtimeBridge_tracksNotificationListenerAndServiceState() {
    com.example.service.RiyaRuntimeBridge.setNotificationListenerConnected(true)
    assertTrue(com.example.service.RiyaRuntimeBridge.isNotificationListenerConnected.value)
    com.example.service.RiyaRuntimeBridge.setNotificationListenerConnected(false)
    assertFalse(com.example.service.RiyaRuntimeBridge.isNotificationListenerConnected.value)
  }

  @Test
  fun personaRules_coverAutonomousSearchOpenChatReuseAndIncomingReplies() {
    assertTrue(RiyaPersona.TEXT.contains("reply_to_incoming_message"))
    assertTrue(RiyaPersona.TEXT.contains("WITHOUT asking Boss for permission"))
    assertTrue(RiyaPersona.TEXT.contains("ALREADY open from earlier"))
    assertTrue(RiyaPersona.TEXT.contains("FIRST scan the top visible chat rows"))
    assertTrue(RiyaPersona.TEXT.contains("Never guess or falsely claim Accessibility is OFF"))
  }

  @Test
  fun voiceMatchEngine_threeStepsDistinguishBossFromOtherSpeakerSafely() {
    assertEquals(
      listOf(
        "তোমার নাম LxR",
        "তুমি আমার পার্সোনাল অ্যাসিস্ট্যান্ট LxR",
        "LxR এখন থেকে আমি তোমার বস",
      ),
      Store.VOICE_MATCH_PHRASES,
    )

    fun synthVoice(f0Hz: Double, formantBoostHz: Double, durationSec: Double = 0.8): ShortArray {
      val n = (16000 * durationSec).toInt()
      return ShortArray(n) { i ->
        val t = i / 16000.0
        val fund = kotlin.math.sin(2.0 * Math.PI * f0Hz * t)
        val h2 = 0.6 * kotlin.math.sin(2.0 * Math.PI * (f0Hz * 2.0) * t)
        val h3 = 0.4 * kotlin.math.sin(2.0 * Math.PI * (f0Hz * 3.0) * t)
        val formant = 0.75 * kotlin.math.sin(2.0 * Math.PI * formantBoostHz * t)
        ((fund + h2 + h3 + formant) * 3500.0).toInt().coerceIn(-32768, 32767).toShort()
      }
    }

    val boss1 = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthVoice(125.0, 580.0))
    val boss2 = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthVoice(128.0, 600.0))
    val boss3 = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthVoice(122.0, 560.0))
    assertNotNull(boss1)
    assertNotNull(boss2)
    assertNotNull(boss3)

    val profile =
      com.example.service.VoiceMatchEngine.buildProfile(
        mapOf(1 to boss1!!, 2 to boss2!!, 3 to boss3!!)
      )
    assertTrue(profile.isComplete)

    // Boss speaks again (slightly softer or different distance) -> MUST match
    val bossTest = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthVoice(126.0, 590.0))!!
    val bossMatch =
      com.example.service.VoiceMatchEngine.compareCandidate(
        profile,
        bossTest,
        com.example.data.VoiceMatchSensitivity.BALANCED,
      )
    assertTrue("Expected Boss voice to match, score=${bossMatch.score}", bossMatch.matched)

    // Different surrounding person (higher pitch + different vocal tract formants) -> MUST reject
    val otherSpeaker = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthVoice(245.0, 1750.0))!!
    val otherMatch =
      com.example.service.VoiceMatchEngine.compareCandidate(
        profile,
        otherSpeaker,
        com.example.data.VoiceMatchSensitivity.BALANCED,
      )
    assertFalse("Expected surrounding person to be avoided, score=${otherMatch.score}", otherMatch.matched)

    // Safe persistence & malformed JSON guard
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Store.setVoiceMatchStepJson(ctx, 1, boss1.toJsonString())
    Store.setVoiceMatchStepJson(ctx, 2, "{corrupted_json")
    Store.setVoiceMatchStepJson(ctx, 3, boss3.toJsonString())
    val loadedPartial = com.example.service.VoiceMatchManager.loadProfile(ctx, forceReload = true)
    assertEquals(2, loadedPartial.completedSteps)
    assertFalse(loadedPartial.isComplete)

    Store.setVoiceMatchStepJson(ctx, 2, boss2.toJsonString())
    val loadedFull = com.example.service.VoiceMatchManager.loadProfile(ctx, forceReload = true)
    assertTrue(loadedFull.isComplete)
    assertTrue(com.example.service.VoiceMatchManager.isActiveGateReady(ctx))

    com.example.service.VoiceMatchManager.resetProfile(ctx)
    assertFalse(com.example.service.VoiceMatchManager.isActiveGateReady(ctx))
  }

  @Test
  fun messageAgent_exactAppDisambiguationAndBengaliContactMatchingWork() {
    assertEquals("com.whatsapp", com.example.service.MessageAgent.find("WhatsApp")?.pkg)
    assertEquals("com.whatsapp", com.example.service.MessageAgent.find("হোয়াটসঅ্যাপ")?.pkg)
    assertEquals("com.imo.android.imoim", com.example.service.MessageAgent.find("imo")?.pkg)
    assertEquals("com.imo.android.imoim", com.example.service.MessageAgent.find("ইমো")?.pkg)
    assertEquals("com.imo.android.imoim", com.example.service.MessageAgent.find("ইমু")?.pkg)
    assertEquals("com.facebook.orca", com.example.service.MessageAgent.find("Messenger")?.pkg)
    assertEquals("com.facebook.orca", com.example.service.MessageAgent.find("মেসেঞ্জার")?.pkg)

    assertTrue(com.example.service.MessageAgent.matchesContactName("Sumon Khan", "সুমন"))
    assertTrue(com.example.service.MessageAgent.matchesContactName("Dr: Rasel Vai", "রাসেল"))
    assertTrue(com.example.service.MessageAgent.matchesContactName("Mizanur Rahman", "মিজান"))
    assertFalse(com.example.service.MessageAgent.matchesContactName("Sumon Khan", "করিম"))
  }

  @Test
  fun micTapGreetingAndCalibratedVoiceSensitivity_areConfiguredProperly() {
    assertTrue(Store.MIC_TAP_GREETING_BN.contains("আসসালামু"))
    assertTrue(Store.MIC_TAP_GREETING_BN.contains("প্রাণ প্রিয় বস"))
    assertTrue(Store.MIC_TAP_GREETING_BN.contains("বলুন কি করতে হবে"))

    assertEquals(0.46f, com.example.data.VoiceMatchSensitivity.EASY.threshold, 0.001f)
    assertEquals(0.52f, com.example.data.VoiceMatchSensitivity.BALANCED.threshold, 0.001f)
    assertEquals(0.60f, com.example.data.VoiceMatchSensitivity.STRICT.threshold, 0.001f)
  }

  @Test
  fun voiceMatchEngine_softWhisperBossSpeechMatchesBalancedAndStrictWithoutShouting_andOffDisablesGate() {
    fun synthScaledVoice(
      f0Hz: Double,
      formantBoostHz: Double,
      amplitude: Double,
      durationSec: Double = 0.8,
    ): ShortArray {
      val n = (16000 * durationSec).toInt()
      return ShortArray(n) { i ->
        val t = i / 16000.0
        val fund = kotlin.math.sin(2.0 * Math.PI * f0Hz * t)
        val h2 = 0.6 * kotlin.math.sin(2.0 * Math.PI * (f0Hz * 2.0) * t)
        val h3 = 0.4 * kotlin.math.sin(2.0 * Math.PI * (f0Hz * 3.0) * t)
        val formant = 0.75 * kotlin.math.sin(2.0 * Math.PI * formantBoostHz * t)
        ((fund + h2 + h3 + formant) * amplitude).toInt().coerceIn(-32768, 32767).toShort()
      }
    }

    val b1 = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthScaledVoice(125.0, 580.0, 3500.0))!!
    val b2 = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthScaledVoice(127.0, 595.0, 3400.0))!!
    val b3 = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthScaledVoice(123.0, 565.0, 3600.0))!!
    val profile = com.example.service.VoiceMatchEngine.buildProfile(mapOf(1 to b1, 2 to b2, 3 to b3))

    // Boss speaks softly (~1/20th amplitude of enrollment, ~160 peak): MUST match in BALANCED & STRICT
    val softBossSig = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthScaledVoice(125.0, 585.0, 160.0))
    assertNotNull("Soft Boss speech must still extract valid voice signature", softBossSig)

    val softBalanced =
      com.example.service.VoiceMatchEngine.compareCandidate(
        profile,
        softBossSig!!,
        com.example.data.VoiceMatchSensitivity.BALANCED,
      )
    val softStrict =
      com.example.service.VoiceMatchEngine.compareCandidate(
        profile,
        softBossSig,
        com.example.data.VoiceMatchSensitivity.STRICT,
      )
    assertTrue("Expected soft Boss voice to match BALANCED (score=${softBalanced.score})", softBalanced.matched)
    assertTrue("Expected soft Boss voice to match STRICT (score=${softStrict.score})", softStrict.matched)

    // Stranger voice still rejected even in BALANCED
    val strangerSig = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthScaledVoice(250.0, 1800.0, 3500.0))!!
    val strangerBalanced =
      com.example.service.VoiceMatchEngine.compareCandidate(
        profile,
        strangerSig,
        com.example.data.VoiceMatchSensitivity.BALANCED,
      )
    assertFalse("Expected stranger voice to be rejected (score=${strangerBalanced.score})", strangerBalanced.matched)

    // Disabling Voice Match toggle disables gate even when all 3 steps are enrolled
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Store.setVoiceMatchStepJson(ctx, 1, b1.toJsonString())
    Store.setVoiceMatchStepJson(ctx, 2, b2.toJsonString())
    Store.setVoiceMatchStepJson(ctx, 3, b3.toJsonString())
    com.example.service.VoiceMatchManager.loadProfile(ctx, forceReload = true)
    Store.setVoiceMatchEnabled(ctx, false)
    assertFalse(com.example.service.VoiceMatchManager.isActiveGateReady(ctx))
    Store.setVoiceMatchEnabled(ctx, true)
    assertTrue(com.example.service.VoiceMatchManager.isActiveGateReady(ctx))
    com.example.service.VoiceMatchManager.resetProfile(ctx)
  }

  @Test
  fun lxrFloatingOverlay_triggersOnWakeWordsAndUsesExact50PercentOpacity() {
    assertEquals(0.50f, com.example.service.LxrFloatingOverlayManager.OVERLAY_ALPHA, 0.001f)
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("Hey LxR, open WhatsApp"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("L X R"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("এল এক্স আর লাইট জ্বালাও"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("এলএক্সআর কেমন আছো"))
    assertFalse(com.example.service.LxrFloatingOverlayManager.containsWakeWord("আবহাওয়া কেমন আজ"))
  }
}
