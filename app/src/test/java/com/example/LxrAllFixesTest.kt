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
import com.example.service.LiveVadConfig
import com.example.service.RiyaVoiceManager
import com.example.service.TermuxUiAutomation
import com.example.service.TermuxWindowNodeInfo
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

  @Test
  fun termuxUiAutomation_matches3sHold2sWaitPastePopupAndGboardBlueEnter() {
    assertEquals(3000L, TermuxUiAutomation.LONG_PRESS_DURATION_MS)
    assertEquals(2000L, TermuxUiAutomation.POPUP_WAIT_AFTER_RELEASE_MS)

    val (lpX, lpY) = TermuxUiAutomation.computeLongPressPoint(screenW = 1080, screenH = 2400, imeTopOrNull = 1480)
    assertTrue("Long press X should be near horizontal center", lpX in 400f..650f)
    assertTrue("Long press Y must stay in Termux viewport above Extra Keys + Gboard", lpY in 420f..1180f)

    val popupNodes =
      listOf(
        TermuxWindowNodeInfo("Copy", "Copy", "", "android.widget.TextView", "com.termux", true, 60, 520, 210, 620),
        TermuxWindowNodeInfo("Paste", "Paste", "", "android.widget.TextView", "com.termux", true, 220, 520, 380, 620),
        TermuxWindowNodeInfo("More...", "More...", "", "android.widget.TextView", "com.termux", true, 390, 520, 560, 620),
      )
    val pickedPaste = TermuxUiAutomation.findPastePopupCandidate(popupNodes)
    assertEquals("Paste", pickedPaste?.text)

    val imeNodes =
      listOf(
        TermuxWindowNodeInfo("q", "q", "key_pos_0_0", "android.view.View", "com.google.android.inputmethod.latin", true, 0, 1750, 108, 1900),
        TermuxWindowNodeInfo("QWERTY", "Space", "key_pos_space", "android.view.View", "com.google.android.inputmethod.latin", true, 360, 2120, 740, 2280),
        TermuxWindowNodeInfo("", "Enter", "key_pos_ime_action", "android.widget.FrameLayout", "com.google.android.inputmethod.latin", true, 895, 2100, 1065, 2275),
      )
    val pickedEnter = TermuxUiAutomation.findGboardEnterKeyCandidate(imeNodes, 0, 1480, 1080, 2400)
    assertEquals("key_pos_ime_action", pickedEnter?.viewId)

    val (fbX, fbY) = TermuxUiAutomation.computeGboardBlueEnterFallbackPoint(1080, 2400, imeTop = 1480, imeRight = 1080, imeBottom = 2400)
    assertTrue("Fallback X should hit bottom-right corner", fbX > 900f && fbX < 1050f)
    assertTrue("Fallback Y should hit blue Enter above bottom nav strip", fbY > 2050f && fbY < 2280f)
  }

  @Test
  fun liveVadAndLxrVoiceOnly_guaranteeFastTurnTakingWithoutSystemTts() {
    assertFalse(
      "Phone-default Android system TextToSpeech fallback must NEVER be used",
      RiyaVoiceManager.ALLOW_ANDROID_SYSTEM_TTS_FALLBACK,
    )
    assertEquals("START_SENSITIVITY_HIGH", LiveVadConfig.START_SENSITIVITY)
    assertEquals("END_SENSITIVITY_LOW", LiveVadConfig.END_SENSITIVITY)
    assertTrue(
      "Silence window should respond within 1-2 seconds without cutting off Boss mid-sentence",
      LiveVadConfig.SILENCE_DURATION_MS in 1050..1500,
    )
    val cfg = LiveVadConfig.buildRealtimeInputConfigJson().getJSONObject("automaticActivityDetection")
    assertFalse(cfg.getBoolean("disabled"))
    assertEquals(LiveVadConfig.SILENCE_DURATION_MS, cfg.getInt("silenceDurationMs"))
  }
}
