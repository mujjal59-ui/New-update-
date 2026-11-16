package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.ui.AppBuilderScreen
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_RED
import com.example.ui.LxrThreeSectionRow
import com.example.ui.SeoStudioScreen
import com.example.ui.VideoCreatorScreen
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class LxrCreatorStudioUiTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun threeSectionButtons_haveEqualDimensionsAndExactLabels() {
    composeTestRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
        LxrThreeSectionRow(
          cardHeight = 84.dp,
          isCompactWidth = false,
          onAppBuilder = {},
          onVideoCreator = {},
          onSeoStudio = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("section_btn_app_builder").assertIsDisplayed()
    composeTestRule.onNodeWithTag("section_btn_video_creator").assertIsDisplayed()
    composeTestRule.onNodeWithTag("section_btn_seo_studio").assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_section_neon_divider").assertIsDisplayed()

    composeTestRule.onNodeWithText("অ্যাপস তৈরি").assertIsDisplayed()
    composeTestRule.onNodeWithText("ভিডিও তৈরি").assertIsDisplayed()
    composeTestRule.onNodeWithText("YouTube\nভিডিও SEO").assertIsDisplayed()

    val b1 = composeTestRule.onNodeWithTag("section_btn_app_builder").getUnclippedBoundsInRoot()
    val b2 = composeTestRule.onNodeWithTag("section_btn_video_creator").getUnclippedBoundsInRoot()
    val b3 = composeTestRule.onNodeWithTag("section_btn_seo_studio").getUnclippedBoundsInRoot()

    val w1 = b1.right.value - b1.left.value
    val w2 = b2.right.value - b2.left.value
    val w3 = b3.right.value - b3.left.value
    val h1 = b1.bottom.value - b1.top.value
    val h2 = b2.bottom.value - b2.top.value
    val h3 = b3.bottom.value - b3.top.value

    assertTrue("Widths should be equal: $w1, $w2, $w3", abs(w1 - w2) < 1f && abs(w2 - w3) < 1f)
    assertTrue("Heights should be equal: $h1, $h2, $h3", abs(h1 - h2) < 1f && abs(h2 - h3) < 1f)
    assertTrue("Cards should be arranged horizontally left-to-right", b1.right <= b2.left && b2.right <= b3.left)
  }

  @Test
  fun homeSectionButtons_navigateToEachStudioScreenAndBack() {
    composeTestRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
        var page by remember { mutableStateOf("home") }
        when (page) {
          "app_builder" -> AppBuilderScreen(back = { page = "home" }, onNavigate = { page = it })
          "video_creator" -> VideoCreatorScreen(back = { page = "home" }, onNavigate = { page = it })
          "seo_studio" -> SeoStudioScreen(back = { page = "home" }, onNavigate = { page = it })
          else ->
            RiyaHomeScreen(
              status = "Ready",
              running = false,
              camOn = false,
              accessibilityOn = true,
              notifListenerOn = true,
              onMenu = {},
              onMic = {},
              onCam = {},
              onChat = {},
              onSettings = {},
              onAppBuilder = { page = "app_builder" },
              onVideoCreator = { page = "video_creator" },
              onSeoStudio = { page = "seo_studio" },
              onFixAccessibility = {},
            )
        }
      }
    }

    // Verify all 6 existing dock icons + 3 new buttons render cleanly without vertical overlap
    val dockBottom = composeTestRule.onNodeWithTag("dock_whatsapp").getUnclippedBoundsInRoot().bottom
    val sectionTop = composeTestRule.onNodeWithTag("section_btn_app_builder").getUnclippedBoundsInRoot().top
    assertTrue(
      "New section buttons ($sectionTop) must sit below the 6 app icons dock ($dockBottom)",
      sectionTop >= dockBottom - 2.dp,
    )

    // 1. Navigate to App Builder Screen and back
    composeTestRule.onNodeWithTag("section_btn_app_builder").performClick()
    composeTestRule.onNodeWithTag("app_builder_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("app_builder_screen_back_btn").performClick()
    composeTestRule.onNodeWithTag("section_btn_video_creator").assertIsDisplayed()

    // 2. Navigate to Video Creator Screen and back
    composeTestRule.onNodeWithTag("section_btn_video_creator").performClick()
    composeTestRule.onNodeWithTag("video_creator_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("video_creator_screen_back_btn").performClick()
    composeTestRule.onNodeWithTag("section_btn_seo_studio").assertIsDisplayed()

    // 3. Navigate to SEO Studio Screen and back
    composeTestRule.onNodeWithTag("section_btn_seo_studio").performClick()
    composeTestRule.onNodeWithTag("seo_studio_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("seo_studio_screen_back_btn").performClick()
    composeTestRule.onNodeWithTag("section_btn_app_builder").assertIsDisplayed()
  }
}
