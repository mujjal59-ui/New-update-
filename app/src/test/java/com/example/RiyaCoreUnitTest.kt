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
    assertTrue(Store.DEFAULT_TTS_STYLE.contains("calmly", ignoreCase = true))
    assertTrue(Store.DEFAULT_TTS_STYLE.contains("slightly slower", ignoreCase = true))

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
    assertTrue(Store.WAKE_CALL_GREETING_BN.contains("কেনো ডেকেছেন"))

    assertEquals(0.44f, com.example.data.VoiceMatchSensitivity.EASY.threshold, 0.001f)
    assertEquals(0.53f, com.example.data.VoiceMatchSensitivity.BALANCED.threshold, 0.001f)
    assertEquals(0.63f, com.example.data.VoiceMatchSensitivity.STRICT.threshold, 0.001f)

    // Verify EASY, BALANCED, STRICT are genuinely distinct algorithms & thresholds
    assertTrue(
      com.example.data.VoiceMatchSensitivity.EASY.mfccFloor <
        com.example.data.VoiceMatchSensitivity.BALANCED.mfccFloor
    )
    assertTrue(
      com.example.data.VoiceMatchSensitivity.BALANCED.mfccFloor <
        com.example.data.VoiceMatchSensitivity.STRICT.mfccFloor
    )
    assertTrue(
      com.example.data.VoiceMatchSensitivity.EASY.onsetMinChunks <
        com.example.data.VoiceMatchSensitivity.STRICT.onsetMinChunks
    )
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
  fun lxrFloatingOverlay_triggersOnWakeWords_keeps1to1SquareCrop_andRespectsToggle() {
    assertEquals(0.50f, com.example.service.LxrFloatingOverlayManager.OVERLAY_ALPHA, 0.001f)
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("Hey LxR, open WhatsApp"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("L X R"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("এল এক্স আর লাইট জ্বালাও"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("এলএক্সআর কেমন আছো"))
    assertFalse(com.example.service.LxrFloatingOverlayManager.containsWakeWord("আবহাওয়া কেমন আজ"))

    assertTrue(com.example.service.LxrFloatingOverlayManager.isPureWakeCall("LxR"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.isPureWakeCall("হ্যালো L.x.R"))
    assertFalse(com.example.service.LxrFloatingOverlayManager.isPureWakeCall("LxR ইউটিউব ওপেন করো"))

    // Landscape image with wide transparent margins must produce strict 1:1 square crop around the planet
    val crop = com.example.service.LxrFloatingOverlayManager.computeCenteredSquareCrop(
      imgWidth = 1024,
      imgHeight = 512,
      minX = 312,
      minY = 56,
      maxX = 711,
      maxY = 455,
    )
    assertEquals("Crop must be strict 1:1 square so planet never stretches oval", crop.width(), crop.height())
    assertEquals(400, crop.width())

    val ctx = ApplicationProvider.getApplicationContext<Context>()
    assertTrue(Store.floatingOrbEnabled(ctx))
    Store.setFloatingOrbEnabled(ctx, false)
    assertFalse(Store.floatingOrbEnabled(ctx))
    assertFalse(com.example.service.LxrFloatingOverlayManager.showForWakeOrMic(ctx))
    Store.setFloatingOrbEnabled(ctx, true)
  }

  @Test
  fun audioEchoGuard_blocksPhoneSpeakerSelfHearingUnconditionallyEvenAtHighRms() {
    // On Phone Speaker (!usingBtAudio): high speaker RMS (14000) MUST still mute mic during pending frames, TTS, or 850ms tail
    assertTrue(
      com.example.service.AudioEchoGuard.shouldMuteMic(
        usingBtAudio = false,
        nowMs = 10_000L,
        micMuteUntilMs = 0L,
        hasPendingSpeakerFrames = true,
        ttsIsSpeaking = false,
        lastSpeakerSoundingMs = 0L,
        rawRms = 14_000.0,
      )
    )
    assertTrue(
      com.example.service.AudioEchoGuard.shouldMuteMic(
        usingBtAudio = false,
        nowMs = 10_400L,
        micMuteUntilMs = 0L,
        hasPendingSpeakerFrames = false,
        ttsIsSpeaking = false,
        lastSpeakerSoundingMs = 10_000L,
        rawRms = 14_000.0,
      )
    )
    assertTrue(
      com.example.service.AudioEchoGuard.shouldMuteMic(
        usingBtAudio = false,
        nowMs = 10_000L,
        micMuteUntilMs = 0L,
        hasPendingSpeakerFrames = false,
        ttsIsSpeaking = true,
        lastSpeakerSoundingMs = 0L,
        rawRms = 14_000.0,
      )
    )
    // After acoustic echo tail (>850ms) and no pending audio, Phone Mic hears Boss clearly
    assertFalse(
      com.example.service.AudioEchoGuard.shouldMuteMic(
        usingBtAudio = false,
        nowMs = 11_000L,
        micMuteUntilMs = 0L,
        hasPendingSpeakerFrames = false,
        ttsIsSpeaking = false,
        lastSpeakerSoundingMs = 10_000L,
        rawRms = 800.0,
      )
    )
    // Bluetooth headset allows Boss barge-in above 950 RMS even within short tail
    assertFalse(
      com.example.service.AudioEchoGuard.shouldMuteMic(
        usingBtAudio = true,
        nowMs = 10_200L,
        micMuteUntilMs = 0L,
        hasPendingSpeakerFrames = false,
        ttsIsSpeaking = false,
        lastSpeakerSoundingMs = 10_000L,
        rawRms = 1200.0,
      )
    )
  }

  @Test
  fun voiceMatchEngine_loudAndSoftBossMatchDistinctSensitivityAndStrictPhraseVerification() {
    fun synthScaledVoice(f0Hz: Double, formantBoostHz: Double, amplitude: Double): ShortArray {
      val n = (16000 * 0.8).toInt()
      return ShortArray(n) { i ->
        val t = i / 16000.0
        val fund = kotlin.math.sin(2.0 * Math.PI * f0Hz * t)
        val h2 = 0.6 * kotlin.math.sin(2.0 * Math.PI * (f0Hz * 2.0) * t)
        val h3 = 0.4 * kotlin.math.sin(2.0 * Math.PI * (f0Hz * 3.0) * t)
        val formant = 0.75 * kotlin.math.sin(2.0 * Math.PI * formantBoostHz * t)
        ((fund + h2 + h3 + formant) * amplitude).toInt().coerceIn(-32768, 32767).toShort()
      }
    }

    val b1 = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthScaledVoice(130.0, 600.0, 3500.0))!!
    val b2 = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthScaledVoice(132.0, 610.0, 3400.0))!!
    val b3 = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthScaledVoice(128.0, 590.0, 3600.0))!!
    val profile = com.example.service.VoiceMatchEngine.buildProfile(mapOf(1 to b1, 2 to b2, 3 to b3))

    // Both whisper-quiet (150) AND loud-close (14000) Boss speech must match in BALANCED
    val quietBoss = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthScaledVoice(130.0, 605.0, 150.0))!!
    val loudBoss = com.example.service.VoiceMatchEngine.extractSignatureFromSamples(synthScaledVoice(133.0, 605.0, 14000.0))!!
    assertTrue(
      com.example.service.VoiceMatchEngine.compareCandidate(
        profile, quietBoss, com.example.data.VoiceMatchSensitivity.BALANCED
      ).matched
    )
    assertTrue(
      com.example.service.VoiceMatchEngine.compareCandidate(
        profile, loudBoss, com.example.data.VoiceMatchSensitivity.BALANCED
      ).matched
    )

    // Strict phrase matching rejects unrelated speech & accepts proper Step 1-3 Bengali+LxR sentences
    assertTrue(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(1, "তোমার নাম L.x.R"))
    assertTrue(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(1, "তোমার নাম এল এক্স আর"))
    assertFalse(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(1, "আজকের আবহাওয়া খুব সুন্দর"))

    assertTrue(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(2, "তুমি আমার পার্সোনাল অ্যাসিস্ট্যান্ট এলএক্সআর"))
    assertFalse(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(2, "আমি ভাত খাবো এখন"))

    assertTrue(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(3, "LxR এখন থেকে আমি তোমার বস"))
    assertFalse(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(3, "হ্যালো কেমন আছেন ভাই"))
  }

  @Test
  fun lockVaultManager_saves8DigitPinPatternAndEnforcesConditionalRelockOnlyWhenStartedLocked() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Store.setDeviceBrandProfile(ctx, com.example.data.DeviceBrandProfile.INFINIX)
    Store.setPhoneLockType(ctx, com.example.data.ScreenLockType.PIN)
    Store.setPhonePin(ctx, "1234567890") // must clamp to 8 chars
    assertEquals("12345678", Store.phonePin(ctx))
    assertTrue(Store.hasAnyPhoneLockSaved(ctx))

    Store.setAppLockSameAsPhone(ctx, false)
    Store.setAppLockType(ctx, com.example.data.ScreenLockType.PATTERN)
    Store.setAppPattern(ctx, "12589")
    assertEquals("12589", Store.appPattern(ctx))
    assertTrue(Store.hasAnyAppLockSaved(ctx))

    val centers = com.example.service.LockVaultManager.computePatternDotCenters(Rect(0, 0, 900, 900))
    assertEquals(9, centers.size)
    assertEquals(150f, centers[0].first, 0.5f)
    assertEquals(150f, centers[0].second, 0.5f)
    assertEquals(750f, centers[8].first, 0.5f)
    assertEquals(750f, centers[8].second, 0.5f)
    val pts = com.example.service.LockVaultManager.patternDigitsToPoints("12589", centers)
    assertEquals(5, pts.size)

    // Conditional re-lock rule: MUST ONLY relock if turn began while locked!
    assertTrue(com.example.service.LockVaultManager.shouldAutoRelockAfterTask(startedWithLockedScreen = true, autoUnlockEnabled = true))
    assertFalse(com.example.service.LockVaultManager.shouldAutoRelockAfterTask(startedWithLockedScreen = false, autoUnlockEnabled = true))
    assertFalse(com.example.service.LockVaultManager.shouldAutoRelockAfterTask(startedWithLockedScreen = true, autoUnlockEnabled = false))
  }

  @Test
  fun personaAndRepository_knowScreenLockUnlockDoubleTapWakeAndFullAToZCapabilities() =
    kotlinx.coroutines.runBlocking {
      assertTrue(RiyaPersona.TEXT.contains("unlock_phone"))
      assertTrue(RiyaPersona.TEXT.contains("lock_phone"))
      assertTrue(RiyaPersona.TEXT.contains("unlock_locked_app"))
      assertTrue(RiyaPersona.TEXT.contains("show_wake_orb"))
      assertTrue(RiyaPersona.TEXT.contains("get_capabilities_a_to_z"))
      assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("*"))
      assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("Priya", ignoreCase = true))
      assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("Riya", ignoreCase = true))
      assertTrue(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("ডাবল ট্যাপ"))
      assertTrue(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("১."))
      assertTrue(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("১২."))

      val repo = com.example.ai.RiyaAiRepository()
      var reply = ""
      repo.streamAssistantReply("তুমি কি কি কাজ করতে পারো এ টু জেড বলো", "", true).collect { reply = it }
      assertEquals(RiyaPersona.CAPABILITIES_A_TO_Z_BN, reply)
    }

  @Test
  fun lxrFloatingOverlay_supportsLxrOrbWakeVariantsAndAiGreetingTrigger() {
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("LxR অরব"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("এল এক্স আর অরব আসো"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.containsWakeWord("অ্যালেক্সার"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.isPureWakeCall("LxR অরব"))
    assertTrue(com.example.service.LxrFloatingOverlayManager.isPureWakeCall("এল এক্স আর অরব আসো"))
    assertFalse(com.example.service.LxrFloatingOverlayManager.isPureWakeCall("LxR অরব হোয়াটসঅ্যাপ ওপেন করো"))
    assertTrue(
      com.example.service.LxrFloatingOverlayManager.isAiWakeGreeting(
        "বস, কেনো ডেকেছেন? বলুন কি করতে হবে!"
      )
    )
    assertFalse(
      com.example.service.LxrFloatingOverlayManager.isAiWakeGreeting("আজকের আবহাওয়া খুব সুন্দর বস।")
    )
  }

  @Test
  fun lockVaultManager_detectsAppLockHintsAndSafelyWakesWithoutAccessibility() {
    assertTrue(com.example.service.LockVaultManager.matchesAppLockHint("Verify privacy password"))
    assertTrue(com.example.service.LockVaultManager.matchesAppLockHint("অ্যাপটি লক করা — পিন দিন"))
    assertFalse(com.example.service.LockVaultManager.matchesAppLockHint("WhatsApp Home Chats"))

    val ctx = ApplicationProvider.getApplicationContext<Context>()
    com.example.service.LockVaultManager.wakeScreenIfNeeded(ctx, null)
    val res = com.example.service.LockVaultManager.unlockAppLockWithResult(ctx, null)
    assertEquals(com.example.service.UnlockStatus.ACCESSIBILITY_OFF, res.status)
  }

  @Test
  fun mainScreenLockDetector_neverFalselyReportsLockedWhenUnlockedAppIsOnScreen() {
    // Screen ON + unlocked user app visible (even if keyguardLocked briefly lagged true) -> MUST be false (unlocked)
    assertFalse(
      com.example.service.LockVaultManager.evaluateMainScreenLocked(
        screenInteractive = true,
        keyguardLocked = true,
        deviceLocked = true,
        activeWindowPkg = "com.whatsapp",
        lockScreenVisibleInUi = false,
      )
    )
    assertFalse(
      com.example.service.LockVaultManager.evaluateMainScreenLocked(
        screenInteractive = true,
        keyguardLocked = false,
        deviceLocked = false,
        activeWindowPkg = "com.example",
        lockScreenVisibleInUi = false,
      )
    )
    // Screen OFF -> definitely locked
    assertTrue(
      com.example.service.LockVaultManager.evaluateMainScreenLocked(
        screenInteractive = false,
        keyguardLocked = true,
        deviceLocked = true,
        activeWindowPkg = "com.whatsapp",
        lockScreenVisibleInUi = false,
      )
    )
    // Screen ON + SystemUI keyguard visible -> locked
    assertTrue(
      com.example.service.LockVaultManager.evaluateMainScreenLocked(
        screenInteractive = true,
        keyguardLocked = true,
        deviceLocked = true,
        activeWindowPkg = "com.android.systemui",
        lockScreenVisibleInUi = true,
      )
    )
    assertTrue(RiyaPersona.TEXT.contains("MAIN SCREEN"))
    assertTrue(RiyaPersona.TEXT.contains("STOP reciting the list IMMEDIATELY"))
  }

  @Test
  fun audioEchoGuardWithBargeIn_unmutesBetweenListPausesAndAllowsBossInterruptMidSpeech() {
    // 1. Mid-sentence during audible speaker frames with only normal speaker echo -> MUST stay muted
    assertTrue(
      com.example.service.AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = 10_000L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 10_000L,
        rawRms = 3_200.0,
        lastSpeakerOutputRms = 3_500.0,
        voiceMatchBargeIn = false,
        consecutiveLoudChunks = 0,
      )
    )
    // 2. Natural 350ms pause between A-to-Z items (isSpeakerAudiblyPlayingNow = false, past 320ms tail) -> Mic UNMUTES so Boss can speak!
    assertFalse(
      com.example.service.AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = 10_360L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = false,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 10_000L,
        rawRms = 600.0,
        lastSpeakerOutputRms = 3_500.0,
        voiceMatchBargeIn = false,
        consecutiveLoudChunks = 0,
      )
    )
    // 3. Boss speaks loudly right over the speaker for 2+ chunks OR matches Voice Match -> Instant barge-in!
    assertFalse(
      com.example.service.AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = 10_000L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 10_000L,
        rawRms = 11_000.0,
        lastSpeakerOutputRms = 3_000.0,
        voiceMatchBargeIn = false,
        consecutiveLoudChunks = 2,
      )
    )
    assertFalse(
      com.example.service.AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = 10_000L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 10_000L,
        rawRms = 1_500.0,
        lastSpeakerOutputRms = 3_000.0,
        voiceMatchBargeIn = true,
        consecutiveLoudChunks = 0,
      )
    )
  }

  @Test
  fun voiceMatchPhraseVerifier_boostsQuietPcm_handlesPhoneticSteps_andNeverFalselyBlamesOfflineWhenOnline() {
    // 1. Phonetic Bengali/Banglish STT variants + ZWNJ handling
    assertTrue(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(1, "তোমার নাম এল\u200Cএক্স\u200Cআর"))
    assertTrue(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(1, "তোমার নাম এলেক্স আর"))
    assertTrue(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(2, "তুমি আমার পারছোনাল এসিসটেন্ট অ্যালেক্সার"))
    assertTrue(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(3, "এল.এক্স.আর এখন থেকে আমি তোমার বোস"))
    assertFalse(com.example.service.VoiceMatchPhraseVerifier.matchesStepPhrase(1, "ভুল বাক্য বলছি এখন"))

    // 2. Honest error classification: NEVER claim "নেট সংযোগ পাওয়া যাচ্ছে না" when internetOnline == true
    val onlineTimeoutMsg =
      com.example.service.VoiceMatchPhraseVerifier.classifyPhraseVerifyFailure(
        internetOnline = true,
        lastHttpCode = 0,
        lastApiMessage = "",
        sawEmptySuccessfulReply = false,
        lastNetworkErr = "SocketTimeoutException",
        expectedPhrase = "তোমার নাম LxR",
      )
    assertTrue(onlineTimeoutMsg.contains("ইন্টারনেট চালু আছে"))
    assertFalse(onlineTimeoutMsg.contains("নেটওয়ার্কের কারণে কথা যাচাই"))

    val quotaMsg =
      com.example.service.VoiceMatchPhraseVerifier.classifyPhraseVerifyFailure(
        internetOnline = true,
        lastHttpCode = 429,
        lastApiMessage = "Quota exceeded",
        sawEmptySuccessfulReply = false,
        lastNetworkErr = "",
        expectedPhrase = "তোমার নাম LxR",
      )
    assertTrue(quotaMsg.contains("429"))

    val emptyMsg =
      com.example.service.VoiceMatchPhraseVerifier.classifyPhraseVerifyFailure(
        internetOnline = true,
        lastHttpCode = 0,
        lastApiMessage = "",
        sawEmptySuccessfulReply = true,
        lastNetworkErr = "",
        expectedPhrase = "তোমার নাম LxR",
      )
    assertTrue(emptyMsg.contains("স্পষ্ট বোঝা যায়নি"))

    // 3. Quiet PCM speech amplification for clear STT recognition
    val quietSamples =
      ShortArray(1600) { i ->
        val t = i.toDouble() / 16000.0
        (120.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 180.0 * t)).toInt().toShort()
      }
    val quietBytes = ByteArray(quietSamples.size * 2)
    for (i in quietSamples.indices) {
      quietBytes[i * 2] = (quietSamples[i].toInt() and 0xFF).toByte()
      quietBytes[i * 2 + 1] = ((quietSamples[i].toInt() ushr 8) and 0xFF).toByte()
    }
    val boosted = com.example.service.VoiceMatchPhraseVerifier.boostPcmForTranscription(quietBytes)
    val boostedSamples = com.example.service.VoiceMatchEngine.pcm16BytesToSamples(boosted)
    var peakAfter = 0
    for (s in boostedSamples) peakAfter = maxOf(peakAfter, kotlin.math.abs(s.toInt()))
    assertTrue("Expected quiet audio to be cleanly boosted for transcription, peak=$peakAfter", peakAfter > 1500)
  }

  @Test
  fun voiceSpeedConfig_defaultsSlightlySlower_andClampsAndPersists() {
    val ctx = org.robolectric.RuntimeEnvironment.getApplication()
    assertEquals(0.88f, Store.voiceSpeed(ctx), 0.001f)
    Store.setVoiceSpeed(ctx, 0.50f)
    assertEquals(Store.MIN_VOICE_SPEED, Store.voiceSpeed(ctx), 0.001f)
    Store.setVoiceSpeed(ctx, 1.80f)
    assertEquals(Store.MAX_VOICE_SPEED, Store.voiceSpeed(ctx), 0.001f)
    Store.setVoiceSpeed(ctx, 1.15f)
    assertEquals(1.15f, Store.voiceSpeed(ctx), 0.001f)
    assertTrue(Store.voiceSpeedLabelBn(0.88f).contains("ডিফল্ট"))
    Store.setVoiceSpeed(ctx, Store.DEFAULT_VOICE_SPEED)
  }
}
