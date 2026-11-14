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
}
