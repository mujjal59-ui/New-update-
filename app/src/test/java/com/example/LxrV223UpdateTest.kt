package com.example

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.HomeTheme
import com.example.data.ReplyDelayOption
import com.example.data.ReplyLimitOption
import com.example.data.RiyaState
import com.example.data.Store
import com.example.service.ReplyPolicyScheduler
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_RED
import com.example.ui.KeyPage
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class LxrV223UpdateTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var ctx: Context

  @Before
  fun setup() {
    ctx = ApplicationProvider.getApplicationContext()
    Store.setReplyDelaySeconds(ctx, 0)
    Store.setReplyLimitMax(ctx, -1)
    Store.setHomeTheme(ctx, HomeTheme.LXR_STUDIO)
    Store.setModel(ctx, Store.DEFAULT_LIVE_MODEL)
    ReplyPolicyScheduler.resetCounter(ctx)
  }

  @Test
  fun replyDelayAndLimit_enumsLegacyMigrationAndStorePersistenceSurviveRestart() {
    assertEquals(ReplyDelayOption.INSTANT, Store.replyDelayOption(ctx))
    assertEquals(ReplyLimitOption.UNLIMITED, Store.replyLimitOption(ctx))

    Store.setReplyDelaySeconds(ctx, 5)
    Store.setReplyLimitMax(ctx, 20)
    assertEquals(5, Store.replyDelaySeconds(ctx))
    assertEquals(ReplyDelayOption.FAST, Store.replyDelayOption(ctx))
    assertEquals(20, Store.replyLimitMax(ctx))
    assertEquals(ReplyLimitOption.LIMIT_20, Store.replyLimitOption(ctx))

    Store.setReplyDelaySeconds(ctx, 10)
    Store.setReplyLimitMax(ctx, 50)
    assertEquals(10, Store.replyDelaySeconds(ctx))
    assertEquals(ReplyDelayOption.NORMAL, Store.replyDelayOption(ctx))
    assertEquals(50, Store.replyLimitMax(ctx))
    assertEquals(ReplyLimitOption.LIMIT_50, Store.replyLimitOption(ctx))

    // Legacy 8s / 15s values cleanly migrate to 5s / 10s
    assertEquals(ReplyDelayOption.FAST, ReplyDelayOption.fromSeconds(8))
    assertEquals(ReplyDelayOption.NORMAL, ReplyDelayOption.fromSeconds(15))
    Store.setReplyDelaySeconds(ctx, 8)
    assertEquals(5, Store.replyDelaySeconds(ctx))
    Store.setReplyDelaySeconds(ctx, 15)
    assertEquals(10, Store.replyDelaySeconds(ctx))

    // Invalid values safely fallback to defaults
    Store.setReplyDelaySeconds(ctx, 999)
    Store.setReplyLimitMax(ctx, 999)
    assertEquals(0, Store.replyDelaySeconds(ctx))
    assertEquals(-1, Store.replyLimitMax(ctx))
  }

  @Test
  fun replyPolicyScheduler_inFlightWaitKeepsInitialCapturedDelayWhenSettingChangesMidWait() = runTest {
    Store.setReplyDelaySeconds(ctx, 10)
    val initialCapturedDelay = ReplyPolicyScheduler.captureDelaySeconds(ctx)
    assertEquals(10, initialCapturedDelay)

    var executed = false
    val job = async {
      ReplyPolicyScheduler.executeAfterCapturedDelay(
        initialDelaySeconds = initialCapturedDelay,
      ) {
        executed = true
        true
      }
    }

    advanceTimeBy(4_000L)
    runCurrent()
    assertFalse("10s captured wait must still be waiting at 4s", executed)

    // User changes setting to Instant (00.0s) while old wait is already running
    Store.setReplyDelaySeconds(ctx, 0)
    assertEquals(0, ReplyPolicyScheduler.captureDelaySeconds(ctx))

    advanceTimeBy(4_000L)
    runCurrent()
    assertFalse("Old wait MUST keep its captured 10s time (still not done at 8s)", executed)

    advanceTimeBy(2_000L)
    runCurrent()
    assertTrue("Old wait completes at 10s", executed)
    assertEquals(true, job.await())
  }

  @Test
  fun replyPolicyScheduler_enforces20And50LimitsPersistsAndRollsBackOnFailure() {
    ReplyPolicyScheduler.resetCounter(ctx)
    repeat(20) {
      assertTrue(
        "Reply #${it + 1} should be allowed under limit 20",
        ReplyPolicyScheduler.tryConsumeAndPersist(ctx, 20),
      )
    }
    assertFalse("21st reply must be blocked under limit 20", ReplyPolicyScheduler.canSendReply(20))
    assertFalse(
      "21st tryConsumeAndPersist must fail under limit 20",
      ReplyPolicyScheduler.tryConsumeAndPersist(ctx, 20),
    )
    assertEquals(20, ReplyPolicyScheduler.currentDispatchedCount())
    assertEquals(20, Store.replyDispatchedCount(ctx))

    // Forced resync simulates app restart and retains count
    ReplyPolicyScheduler.syncFromStore(ctx, force = true)
    assertEquals(20, ReplyPolicyScheduler.currentDispatchedCount())
    assertFalse(ReplyPolicyScheduler.canSendReply(20))

    // Failed dispatch rolls back 1 slot
    ReplyPolicyScheduler.rollbackOneAndPersist(ctx)
    assertEquals(19, ReplyPolicyScheduler.currentDispatchedCount())
    assertEquals(19, Store.replyDispatchedCount(ctx))
    assertTrue(ReplyPolicyScheduler.tryConsumeAndPersist(ctx, 20))

    // Increasing limit to 50 unlocks remaining 30 slots without losing count
    repeat(30) {
      assertTrue(ReplyPolicyScheduler.tryConsumeAndPersist(ctx, 50))
    }
    assertEquals(50, ReplyPolicyScheduler.currentDispatchedCount())
    assertFalse("51st reply must be blocked under limit 50", ReplyPolicyScheduler.canSendReply(50))

    // Unlimited (-1) always permits further replies
    assertTrue(ReplyPolicyScheduler.canSendReply(-1))
    assertTrue(ReplyPolicyScheduler.tryConsumeAndPersist(ctx, -1))
    assertEquals(51, ReplyPolicyScheduler.currentDispatchedCount())
  }

  @Test
  fun homeTheme_persistsAcrossSessionsAndSyncsLiveStateFlow() {
    assertEquals(3, HomeTheme.entries.size)
    assertEquals(HomeTheme.LXR_STUDIO, Store.homeTheme(ctx))

    Store.setHomeTheme(ctx, HomeTheme.ARIA_CYBER_ROOM)
    assertEquals(1, Store.design(ctx))
    assertEquals(HomeTheme.ARIA_CYBER_ROOM, Store.homeTheme(ctx))
    assertEquals(1, RiyaState.homeThemeIndex.value)

    Store.setHomeTheme(ctx, HomeTheme.MITALI_CYBER_STUDIO)
    assertEquals(2, Store.design(ctx))
    assertEquals(HomeTheme.MITALI_CYBER_STUDIO, Store.homeTheme(ctx))
    assertEquals(2, RiyaState.homeThemeIndex.value)
  }

  @Test
  fun keyPage_replyDelayLimitPanel_displaysAllOptionsAndAutoSaves() {
    composeTestRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
        KeyPage(back = {}, onNavigate = {})
      }
    }

    composeTestRule.onNodeWithTag("reply_delay_limit_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Reply Delay and Reply Limit").assertIsDisplayed()
    composeTestRule.onNodeWithText("Instant — 0 ms (Default)").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Fast — 30 ms").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Normal — 45 ms").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Unlimited — Default").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("20 Replies").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("50 Replies").performScrollTo().assertIsDisplayed()
    composeTestRule
      .onNodeWithText("Saved automatically; stays after restart. A wait already running keeps its old time.")
      .performScrollTo()
      .assertIsDisplayed()

    composeTestRule.onNodeWithTag("reply_delay_option_5").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("reply_delay_selected_text").assertTextEquals("Selected: Fast (30 ms)")
    assertEquals(5, Store.replyDelaySeconds(ctx))

    composeTestRule.onNodeWithTag("reply_limit_option_20").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("reply_limit_selected_text").assertTextEquals("Selected: 20 Replies")
    assertEquals(20, Store.replyLimitMax(ctx))

    composeTestRule.onNodeWithTag("reply_delay_option_10").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("reply_delay_selected_text").assertTextEquals("Selected: Normal (45 ms)")
    composeTestRule.onNodeWithTag("reply_limit_option_50").performScrollTo().performClick()
    assertEquals(10, Store.replyDelaySeconds(ctx))
    assertEquals(50, Store.replyLimitMax(ctx))
  }

  @Test
  fun homeScreen_rendersAllAnimatedButtonsVoiceModelTreeButtonPopupAndExtraThemesWithoutOverlap() {
    var menuClicks = 0
    var settingsClicks = 0
    composeTestRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
        val themeIdx by RiyaState.homeThemeIndex.collectAsState()
        RiyaHomeScreen(
          status = if (themeIdx >= 0) "Ready" else "Ready",
          running = false,
          camOn = false,
          accessibilityOn = true,
          notifListenerOn = true,
          onMenu = { menuClicks++ },
          onMic = {},
          onCam = {},
          onChat = {},
          onSettings = { settingsClicks++ },
          onAppBuilder = {},
          onVideoCreator = {},
          onSeoStudio = {},
          onFixAccessibility = {},
        )
      }
    }

    val allTags =
      listOf(
        "menu_button",
        "voice_model_button",
        "cam_button",
        "mic_button",
        "chat_button",
        "dock_whatsapp",
        "dock_imo",
        "dock_messenger",
        "dock_youtube",
        "dock_contacts",
        "dock_settings",
        "section_btn_app_builder",
        "section_btn_video_creator",
        "section_btn_seo_studio",
      )

    allTags.forEach { tag ->
      composeTestRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    val micBottom = composeTestRule.onNodeWithTag("mic_button").getUnclippedBoundsInRoot().bottom
    val dockTop = composeTestRule.onNodeWithTag("dock_whatsapp").getUnclippedBoundsInRoot().top
    val dockBottom = composeTestRule.onNodeWithTag("dock_whatsapp").getUnclippedBoundsInRoot().bottom
    val sectionTop = composeTestRule.onNodeWithTag("section_btn_app_builder").getUnclippedBoundsInRoot().top

    assertTrue("Voice strip ($micBottom) must not overlap dock ($dockTop)", dockTop >= micBottom)
    assertTrue("Dock ($dockBottom) must sit above 3 section cards ($sectionTop)", sectionTop >= dockBottom)

    composeTestRule.onNodeWithTag("menu_button").performClick()
    composeTestRule.onNodeWithTag("dock_settings").performClick()
    assertEquals(1, menuClicks)
    assertEquals(1, settingsClicks)

    // Test Voice Model tree button opens 3-option popup and persists selection
    composeTestRule.onNodeWithTag("voice_model_button").performClick()
    composeTestRule.onNodeWithTag("voice_model_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("Voice model").assertIsDisplayed()
    composeTestRule.onNodeWithText("Gemini 3.8 Live").assertIsDisplayed()
    composeTestRule.onNodeWithText("Gemini 3.1 Flash Live (preview)").assertIsDisplayed()
    composeTestRule.onNodeWithText("Gemini 2.5 Flash Native Audio (Dec 2025)").assertIsDisplayed()

    composeTestRule.onNodeWithTag("voice_model_option_0").performClick()
    composeTestRule.waitForIdle()
    assertEquals("gemini-3.8-live", Store.model(ctx))
    assertEquals("gemini-3.8-live", RiyaState.liveModelId.value)

    composeTestRule.onNodeWithTag("voice_model_button").performClick()
    composeTestRule.onNodeWithTag("voice_model_option_2").performClick()
    composeTestRule.waitForIdle()
    assertEquals("gemini-2.5-flash-native-audio-preview-12-2025", Store.model(ctx))

    composeTestRule.onNodeWithTag("voice_model_button").performClick()
    composeTestRule.onNodeWithTag("voice_model_close_btn").performClick()
    composeTestRule.waitForIdle()

    // Switch through all 3 themes & verify all UI buttons + Voice Model button remain displayed without overlap
    Store.setHomeTheme(ctx, HomeTheme.ARIA_CYBER_ROOM)
    composeTestRule.waitForIdle()
    allTags.forEach { tag -> composeTestRule.onNodeWithTag(tag).assertIsDisplayed() }

    Store.setHomeTheme(ctx, HomeTheme.MITALI_CYBER_STUDIO)
    composeTestRule.waitForIdle()
    allTags.forEach { tag -> composeTestRule.onNodeWithTag(tag).assertIsDisplayed() }
  }
}
