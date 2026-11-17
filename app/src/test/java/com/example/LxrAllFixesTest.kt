package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.builder.AndroidApiLevel
import com.example.builder.ApkBuildMode
import com.example.builder.AppBuilderProject
import com.example.builder.AppCategory
import com.example.builder.AppDesignTheme
import com.example.builder.AppProjectGenerator
import com.example.builder.AppProjectTemplates
import com.example.builder.ProgrammingLanguage
import com.example.builder.TermuxBuildBridge
import com.example.service.AudioEchoGuard
import com.example.service.EditableFieldCandidate
import com.example.service.EditableFieldMatcher
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_RED
import com.example.ui.LxrAppBuilderWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class LxrAllFixesTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun editableFieldMatcher_picksNoteBodyOverTopTitleUnlessTitleRequested() {
    val titleBox =
      EditableFieldCandidate(
        index = 0,
        text = "",
        hint = "Title",
        contentDescription = "",
        viewId = "com.transsion.notepad:id/et_title",
        top = 260,
        bottom = 360,
        left = 40,
        right = 1040,
        isSingleLine = true,
      )
    val noteBodyBox =
      EditableFieldCandidate(
        index = 1,
        text = "",
        hint = "Note something down",
        contentDescription = "",
        viewId = "com.transsion.notepad:id/et_content",
        top = 380,
        bottom = 1850,
        left = 40,
        right = 1040,
        isSingleLine = false,
      )
    val candidates = listOf(titleBox, noteBodyBox)

    assertEquals(1, EditableFieldMatcher.selectBestCandidateIndex(candidates, "auto"))
    assertEquals(1, EditableFieldMatcher.selectBestCandidateIndex(candidates, "body"))
    assertEquals(0, EditableFieldMatcher.selectBestCandidateIndex(candidates, "title"))
  }

  @Test
  fun editableFieldMatcher_fallsBackToTallLowerFieldWhenNoHintsExist() {
    val topBox =
      EditableFieldCandidate(
        index = 0,
        text = "",
        hint = "",
        contentDescription = "",
        viewId = "",
        top = 200,
        bottom = 290,
        left = 20,
        right = 1060,
        isSingleLine = true,
      )
    val bodyBox =
      EditableFieldCandidate(
        index = 1,
        text = "",
        hint = "",
        contentDescription = "",
        viewId = "",
        top = 310,
        bottom = 1600,
        left = 20,
        right = 1060,
        isSingleLine = false,
      )
    val picked = EditableFieldMatcher.selectBestCandidateIndex(listOf(topBox, bodyBox), "auto")
    assertEquals(1, picked)
  }

  @Test
  fun audioEchoGuard_unmutesCleanlyAndDoesNotLockWhenSpeakerStalls() {
    val now = 20_000L
    // When speaker write stopped (> 950ms ago) and isSpeakerAudiblyPlayingNow is false + tail expired, mic must NOT stay muted
    val mutedWhenIdle =
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = now,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = false,
        ttsIsSpeaking = false,
        lastAudibleStopMs = now - 600L,
        rawRms = 120.0,
        lastSpeakerOutputRms = 0.0,
        voiceMatchBargeIn = false,
        consecutiveLoudChunks = 0,
      )
    assertFalse("Mic must unmute after speaker stops and tail window passes", mutedWhenIdle)

    // When Boss triggers Voice Match barge-in during playback, mic must immediately open
    val mutedOnBossBargeIn =
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = now,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = true,
        lastAudibleStopMs = now,
        rawRms = 900.0,
        lastSpeakerOutputRms = 450.0,
        voiceMatchBargeIn = true,
        consecutiveLoudChunks = 0,
      )
    assertFalse("Boss Voice Match barge-in must unmute mic immediately", mutedOnBossBargeIn)
  }

  @Test
  fun promptInference_mapsCalculatorMiniGameAndAiAssistantAccurately() {
    val (calcName, calcCat, _) = AppProjectGenerator.inferCategoryAndNameFromPrompt("একটি রিয়েল ক্যালকুলেটর অ্যাপ বানাও")
    assertEquals(AppCategory.CALCULATOR, calcCat)
    assertTrue(calcName.isNotBlank())

    val (gameName, gameCat, _) = AppProjectGenerator.inferCategoryAndNameFromPrompt("একটা ছোটখাটো মজার গেম বানাও")
    assertEquals(AppCategory.MINI_GAME, gameCat)
    assertTrue(gameName.isNotBlank())

    val (aiName, aiCat, _) = AppProjectGenerator.inferCategoryAndNameFromPrompt("রিয়েল AI অ্যাসিস্ট্যান্ট বানাও")
    assertEquals(AppCategory.AI_ASSISTANT, aiCat)
    assertTrue(aiName.isNotBlank())
  }

  @Test
  fun generatedTemplatesAndTermuxScript_emitResilientConfigAndRichCode() {
    val calcProj =
      AppBuilderProject(
        name = "Amar Calc",
        description = "ডার্ক ও কমলা গোল বাটনের রিয়েল ক্যালকুলেটর অ্যাপ",
        category = AppCategory.CALCULATOR,
        designTheme = AppDesignTheme.CYBER_NEON,
        packageName = "com.lxr.test.calc",
        minSdk = AndroidApiLevel.API_26,
        language = ProgrammingLanguage.KOTLIN_COMPOSE,
        buildMode = ApkBuildMode.DEBUG,
      )
    val files = AppProjectTemplates.generateFullProjectFiles(calcProj)
    assertTrue(files["gradle.properties"]!!.contains("kotlin.compiler.execution.strategy=in-process"))
    assertTrue(files["gradle.properties"]!!.contains("org.gradle.daemon=false"))
    assertTrue(files.containsKey("app/src/main/res/mipmap-anydpi/ic_launcher.xml"))
    val kt = files["app/src/main/java/com/lxr/test/calc/MainActivity.kt"]!!
    assertTrue(kt.contains("Amar Calc") || kt.contains("evaluateExpression"))

    val script = TermuxBuildBridge.generateTermuxBuildScript(calcProj)
    assertTrue(script.contains("LXR_PROGRESS:STEP="))
    assertTrue(script.contains("LXR_RESULT_JSON="))
    assertTrue(script.contains("org.gradle.daemon=false"))
  }

  @Test
  fun livePreviewPanel_rendersAndEvaluatesCalculatorInsideWorkspace() {
    composeTestRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
        LxrAppBuilderWorkspace(back = {}, onNavigate = {})
      }
    }

    composeTestRule.onNodeWithTag("builder_live_preview_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("preview_calc_display").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("preview_calc_key_7").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("preview_calc_key_+").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("preview_calc_key_5").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("preview_calc_key_=").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("preview_calc_display").assertTextEquals("12")
  }
}
