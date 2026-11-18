package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_RED
import com.example.ui.VideoCreatorStudioScreen
import com.example.video.AdvancedCharacterAnimator
import com.example.video.MotionChoreography
import com.example.video.OpenMontageFrameworkBridge
import com.example.video.PipelineRunStatus
import com.example.video.PipelineStage
import com.example.video.ProductionToolTab
import com.example.video.StudioMediaLibrary
import com.example.video.TalkingCharacterPreset
import com.example.video.TransitionEffect
import com.example.video.VideoScenePlan
import com.example.video.VideoStudioCatalog
import com.example.video.VideoStudioViewModel
import com.example.video.VideoType
import com.example.video.VisualSourceKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.sin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class LxrVideoStudioTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun catalog_contains15PlusVideoTypes_all7GoogleVoices_and60BundledAssets() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    assertTrue("Must support at least 15 video types + custom", VideoType.entries.size >= 16)
    val expectedVoices = setOf("Aoede", "Iapetus", "Leda", "Orus", "Achernar", "Charon", "Sulafat")
    val actualVoices = VideoStudioCatalog.GOOGLE_VOICES.map { it.id }.toSet()
    assertEquals(expectedVoices, actualVoices)

    val bundled = StudioMediaLibrary.listBundledAssets(app)
    assertEquals("All 60 studio pack assets must be bundled", 60, bundled.size)
  }

  @Test
  fun talkingProduceCharacters_lipSyncEnvelope_and360MotionRenderCleanly() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    // 1. Verify audio-driven lip-sync mouth envelope differentiates loud speech vs silence
    val pcm = ShortArray(24000) { i ->
      if (i < 12000) (sin(i * 0.12) * 8000.0).toInt().toShort() else 0
    }
    val loudMouth = AdvancedCharacterAnimator.computeMouthOpenEnvelope(pcm, 24000, 0.25f, true)
    val silentMouth = AdvancedCharacterAnimator.computeMouthOpenEnvelope(pcm, 24000, 0.75f, true)
    assertTrue("Mouth must open during speech ($loudMouth)", loudMouth > 0.25f)
    assertTrue("Mouth must close during silence ($silentMouth)", silentMouth < 0.05f)

    // 2. Render all requested talking produce/flower/finger characters + all motion styles
    val producePresets = listOf(
      TalkingCharacterPreset.CAULIFLOWER,
      TalkingCharacterPreset.CABBAGE,
      TalkingCharacterPreset.BANANA,
      TalkingCharacterPreset.APPLE,
      TalkingCharacterPreset.FLOWER,
      TalkingCharacterPreset.FINGER,
    )
    val motions = listOf(
      MotionChoreography.TALKING_LIPSYNC,
      MotionChoreography.WALKING,
      MotionChoreography.DANCING,
      MotionChoreography.FASHION_360_SPIN,
      MotionChoreography.MOTION_GRAPHICS,
      MotionChoreography.DRAMA_CINEMATIC,
    )

    val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    producePresets.forEachIndexed { idx, preset ->
      val scene = VideoScenePlan(
        index = idx + 1,
        title = "Scene ${idx + 1}",
        narrationText = "কথা বলা ${preset.labelBn}",
        dialogueLine = "হ্যালো! আমি কথা বলছি ও নাচছি!",
        characterName = preset.labelBn,
        voiceId = "Aoede",
        emotionalTone = "Playful",
        visualPrompt = preset.labelBn,
        themeKeyword = "produce_story",
        visualSource = VisualSourceKind.ANIMATED_2D_VECTOR,
        effectTag = motions[idx].labelBn,
        overlayHeadline = preset.labelBn,
        durationSec = 4f,
        motionStyle = motions[idx],
        characterPreset = preset,
      )
      AdvancedCharacterAnimator.drawAnimatedSceneToCanvas(
        context = app,
        canvas = canvas,
        scene = scene,
        width = 640,
        height = 360,
        framePhase = 0.45f,
        globalTimeSec = 1.8f,
        mouthOpen = loudMouth,
        showSubtitles = true,
      )
      // Verify rendered bitmap compresses to valid non-empty PNG frame
      val pngOut = ByteArrayOutputStream()
      assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 90, pngOut))
      assertTrue("Rendered frame PNG must be non-empty", pngOut.size() > 150)
    }
    bmp.recycle()
  }

  @Test
  fun testAnimationEndToEnd_generatesScriptVoiceVisualsSubtitlesMp4AndUnlimitedProjects() = runTest {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = VideoStudioViewModel(app)

    vm.updatePrompt("একটি সুন্দর শিক্ষামূলক অ্যানিমেশন ভিডিও তৈরি করো, যেখানে একটি শিশু মহাকাশ সম্পর্কে শিখছে।")
    vm.selectVideoType(VideoType.ANIMATION)
    vm.runPipelineSynchronouslyForTest(allowNetworkCloudTry = false)

    val st = vm.uiState.value
    assertEquals(PipelineRunStatus.COMPLETED, st.progress.status)
    assertEquals(1, st.totalVideosCreatedCount)
    assertEquals(1f, st.progress.scriptProgress, 0.01f)
    assertEquals(1f, st.progress.voiceProgress, 0.01f)
    assertEquals(1f, st.progress.imageProgress, 0.01f)
    assertEquals(1f, st.progress.sceneProgress, 0.01f)
    assertEquals(1f, st.progress.editingProgress, 0.01f)
    assertEquals(1f, st.progress.renderingProgress, 0.01f)
    assertEquals(PipelineStage.entries.toSet(), st.progress.completedStages)

    val project = st.activeProject!!
    assertEquals(4, project.scenes.size)
    assertTrue(project.characters.size >= 2)
    assertEquals(4, project.previewFramePaths.size)
    project.previewFramePaths.forEach { path ->
      val f = File(path)
      assertTrue("Scene preview PNG must exist: $path", f.exists() && f.length() > 500)
    }

    val art = project.artifacts
    val mp4 = File(art.mp4Path)
    assertTrue("Final MP4 must exist and be non-empty", mp4.exists() && mp4.length() > 1000)
    val mp4Bytes = mp4.readBytes()
    assertEquals("Valid ISO-BMFF MP4 signature 'ftyp'", "ftyp", String(mp4Bytes, 4, 4, Charsets.US_ASCII))

    val wav = File(art.voiceWavPath)
    assertTrue("Voiceover WAV must exist", wav.exists() && wav.length() > 1000)
    val wavBytes = wav.readBytes()
    assertEquals("RIFF", String(wavBytes, 0, 4, Charsets.US_ASCII))
    assertEquals("WAVE", String(wavBytes, 8, 4, Charsets.US_ASCII))

    val srt = File(art.subtitleSrtPath)
    assertTrue("Subtitles SRT must exist", srt.exists())
    val srtText = srt.readText(Charsets.UTF_8)
    assertTrue(srtText.contains("-->"))
    assertTrue(srtText.contains("মহাকাশ"))

    val scriptTxt = File(art.scriptTxtPath)
    assertTrue("Script TXT must exist", scriptTxt.exists())
    assertTrue(scriptTxt.readText(Charsets.UTF_8).contains("LXR--AI OPENMONTAGE VIDEO SCRIPT"))

    val zip = File(art.projectBundleZipPath)
    assertTrue("OpenMontage project bundle ZIP must exist", zip.exists() && zip.length() > 500)
    ZipFile(zip).use { zf ->
      val names = zf.entries().toList().map { it.name }.toSet()
      assertTrue(names.any { it.startsWith("pipeline_defs/") && it.endsWith(".yaml") })
      assertTrue(names.contains("skills/storywriter.md"))
      assertTrue(names.contains("remotion/OpenMontageVideo.tsx"))
      assertTrue(names.contains("openmontage_runner.py"))
      assertTrue(names.contains("lxr_openmontage_termux.sh"))
    }

    // Verify editing controls work cleanly
    vm.updateSceneScript(1, "নতুন সম্পাদিত স্ক্রিপ্ট লাইন", "নতুন সংলাপ")
    vm.updateSceneVoice(1, "Charon", "Dramatic")
    vm.updateSceneTrimAndTransition(1, 0f, 3.5f, TransitionEffect.ZOOM)
    val edited = vm.uiState.value.activeProject!!
    assertEquals("নতুন সম্পাদিত স্ক্রিপ্ট লাইন", edited.scenes.first().narrationText)
    assertEquals("Charon", edited.scenes.first().voiceId)
    assertEquals(TransitionEffect.ZOOM, edited.scenes.first().transition)
    assertTrue(edited.fullScriptText.contains("নতুন সম্পাদিত স্ক্রিপ্ট লাইন"))

    // OpenMontage free-only policy check
    assertTrue(OpenMontageFrameworkBridge.buildPipelineYaml(edited).contains("paid_apis_allowed: false"))

    // Run a second project with talking cauliflower/apple & 360 mode to confirm no limits & semantic matching
    vm.updatePrompt("কথা বলা ফুলকপি এবং আপেল নিয়ে ৩৬০ ফ্যাশন ও নাটক ভিডিও তৈরি করো")
    vm.selectVideoType(VideoType.TALKING_PRODUCE)
    vm.runPipelineSynchronouslyForTest(allowNetworkCloudTry = false)
    val st2 = vm.uiState.value
    assertEquals(PipelineRunStatus.COMPLETED, st2.progress.status)
    assertEquals(2, st2.totalVideosCreatedCount)
    assertEquals(2, st2.savedProjects.size)
  }

  @Test
  fun videoStudioUi_displaysPromptToolCardsAndImmediateBackButton() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = VideoStudioViewModel(app)
    var backClicked = false

    composeTestRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
        VideoCreatorStudioScreen(
          back = { backClicked = true },
          onNavigate = {},
          vm = vm,
        )
      }
    }

    composeTestRule.onNodeWithTag("video_creator_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("video_creator_screen_back_btn").assertIsDisplayed()
    composeTestRule.onNodeWithTag("video_prompt_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("generate_full_video_btn").assertIsDisplayed()

    composeTestRule.onNodeWithTag("pick_gallery_media_btn").assertIsDisplayed()
    composeTestRule.onNodeWithTag("pick_custom_audio_btn").assertIsDisplayed()

    // Switch across all 4 production tool cards
    composeTestRule.onNodeWithTag("tool_card_voice_over").performClick()
    assertEquals(ProductionToolTab.VOICE_OVER, vm.uiState.value.selectedToolTab)

    composeTestRule.onNodeWithTag("tool_card_image_video").performClick()
    assertEquals(ProductionToolTab.IMAGE_VIDEO, vm.uiState.value.selectedToolTab)

    composeTestRule.onNodeWithTag("tool_card_edit").performClick()
    assertEquals(ProductionToolTab.EDIT, vm.uiState.value.selectedToolTab)

    composeTestRule.onNodeWithTag("tool_card_script").performClick()
    assertEquals(ProductionToolTab.SCRIPT, vm.uiState.value.selectedToolTab)

    composeTestRule.onNodeWithTag("video_creator_screen_back_btn").performClick()
    assertTrue(backClicked)
  }
}
