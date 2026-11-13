package com.example

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaPersona
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
  fun brandingAndVoiceSanitizer_useRiyaAiNeverPriya() {
    assertFalse(RiyaPersona.TEXT.contains("Priya", ignoreCase = true))
    assertTrue(RiyaPersona.TEXT.contains("Riya AI"))

    val raw = "**Priya** এখন WhatsApp, imo ও Messenger এ Infinix Hot 60 Pro+ Android 16 তে কাজ করছে!"
    val clean = RiyaVoiceManager.sanitizeForClearBengaliSpeech(raw)

    assertFalse(clean.contains("Priya", ignoreCase = true))
    assertFalse(clean.contains("*"))
    assertTrue(clean.contains("রিয়া এ আই"))
    assertTrue(clean.contains("হোয়াটসঅ্যাপ"))
    assertTrue(clean.contains("ইমো"))
    assertTrue(clean.contains("মেসেঞ্জার"))
  }

  @Test
  fun keepAccessibilityOn_neverFalselyReportsEnabled() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    assertFalse(Keep.accessibilityOn(ctx))
    assertTrue(InfinixDevicePlaybook.rules.any { it.id == "xos_settings_bottom_search" })
  }
}
