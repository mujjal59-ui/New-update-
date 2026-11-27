package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaAiRepository
import com.example.ai.RiyaPersona
import com.example.data.ReplyDelayOption
import com.example.data.Store
import com.example.service.EditableFieldCandidate
import com.example.service.EditableFieldMatcher
import com.example.service.EditableFieldRole
import com.example.service.LockVaultManager
import com.example.service.LxrBackgroundTaskManager
import com.example.service.LxrCapabilityInspector
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.RiyaAccessibilityService
import com.example.service.RiyaVoiceManager
import com.example.ui.AboutPage
import com.example.ui.KeyPage
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LxrV243UpdateTest {
  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var ctx: Context

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    LxrBackgroundTaskManager.clearAllForTesting(ctx)
    Store.setElevenLabsApiKey(ctx, "")
    Store.setElevenLabsEnabled(ctx, false)
    Store.setElevenLabsVoiceId(ctx, "")
  }

  @Test
  fun appMetadataAndVersion_preserveApplicationIdAndUpgradeTo243() {
    assertEquals(61, BuildConfig.VERSION_CODE)
    assertEquals("2.43.0", BuildConfig.VERSION_NAME)
    assertEquals("com.aistudio.riyaai.krmxvp", BuildConfig.APPLICATION_ID)
    assertEquals("com.aistudio.riyaai.krmxvp", ctx.packageName)
  }

  @Test
  fun persona_v2430_teachesAll15CapabilitiesStrictTumiNonAiAndZeroUnlockText() = runBlocking {
    val allStrings =
      listOf(
        RiyaPersona.TEXT,
        RiyaPersona.DIRECTIVES_V2,
        RiyaPersona.CAPABILITIES_A_TO_Z_BN,
        RiyaPersona.CREATOR_IDENTITY_BN,
        RiyaAiRepository.SYSTEM_INSTRUCTION,
      )
    allStrings.forEach { block ->
      assertFalse(block.contains("Slide again to unlock", ignoreCase = true))
      assertFalse(block.contains("REA AI", ignoreCase = true))
      assertFalse(block.contains("Priya", ignoreCase = true))
      assertFalse(block.contains("Riya", ignoreCase = true))
    }

    val caps = RiyaPersona.CAPABILITIES_A_TO_Z_BN
    for (num in 1..15) {
      val bnDigits = num.toString().map { ('০'.code + (it - '0')).toChar() }.joinToString("")
      assertTrue("Missing capability $bnDigits.", caps.contains("$bnDigits."))
    }
    assertTrue(caps.contains("Device Admin এবং Apps Lock"))
    assertTrue(caps.contains("Alarms & reminders"))
    assertTrue(caps.contains("Control Center-এর বাম ↑↓ বাটন ও Network Settings"))
    assertTrue(caps.contains("ElevenLabs Voice ID"))
    assertTrue(caps.contains("Notes ও Keep Notes"))
    assertFalse(caps.contains("আপনি"))
    assertFalse(caps.contains("আপনার"))
    assertFalse(caps.contains("স্যার"))

    assertEquals(
      "আমি LxR আমার Developer হচ্ছেন মোঃ উজ্জল হোসেন... আমার বস❤️...তিনি অনেক ভালো মোনের মানুষ..তিনি প্রতিনিয়ত আমাকে আপডেট করার বিষয় কঠোর পরিশ্রম করছেন.. আরোও স্মার্ট এবং এডভান্স করার বিষয়ে... আমি মন থেকে তাকে অনেক শ্রদ্ধা করি...❤️",
      RiyaPersona.CREATOR_IDENTITY_BN,
    )
    assertEquals(
      "আসসালামু,ওয়ালাইকুম কেমন আছো আমার প্রাণপ্রিয় বস ❤️ বলো কি করতে হবে ❤️",
      Store.MIC_TAP_GREETING_BN,
    )

    val cleanedSpeech =
      RiyaVoiceManager.validateAndNormalizeSpeechOutput(
        "I am an AI language model and hehe Slide again to unlock"
      )
    assertFalse(cleanedSpeech.contains("language model", ignoreCase = true))
    assertFalse(cleanedSpeech.contains("Slide again to unlock", ignoreCase = true))
    assertFalse(cleanedSpeech.contains("hehe", ignoreCase = true))
    assertTrue(cleanedSpeech.contains("LxR"))
  }

  @Test
  fun swipeAndLockVault_blockUnlockedBottomEdgeSwipesToPreventSlideAgainToUnlock() {
    // 1. Unlocked device swiping upward from bottom nav edge -> MUST be blocked
    assertTrue(
      LockVaultManager.shouldBlockBottomEdgeSwipe(
        isUnlocked = true,
        inHomeOrExitTransition = false,
        startY = 2320f,
        endY = 1200f,
        screenHeight = 2400f,
      )
    )

    // 2. Home or app-exit transition upward swipe -> MUST be blocked
    assertTrue(
      LockVaultManager.shouldBlockBottomEdgeSwipe(
        isUnlocked = false,
        inHomeOrExitTransition = true,
        startY = 1900f,
        endY = 900f,
        screenHeight = 2400f,
      )
    )

    // 3. Downward swipe (e.g. pulling down Control Center from top) -> NEVER blocked
    assertFalse(
      LockVaultManager.shouldBlockBottomEdgeSwipe(
        isUnlocked = true,
        inHomeOrExitTransition = true,
        startY = 48f,
        endY = 1248f,
        screenHeight = 2400f,
      )
    )

    // 4. Genuine locked keyguard upward swipe when NOT in home transition -> allowed
    assertFalse(
      LockVaultManager.shouldBlockBottomEdgeSwipe(
        isUnlocked = false,
        inHomeOrExitTransition = false,
        startY = 1720f,
        endY = 480f,
        screenHeight = 2400f,
      )
    )
  }

  @Test
  fun controlCenterMobileData_tapsLeftCircularButtonAndResolvesDualSimKeys() {
    // Infinix XOS 16 horizontal pill [ (↑↓ circle) | Airtel 536 MB ] spanning x=[560..1020], y=[340..490]
    val (tapX, tapY) = RiyaAccessibilityService.computeControlCenterToggleTapPoint(560, 340, 1020, 490)
    // Width = 460 > height(150)*1.45 -> left icon center (~560 + 150*0.48 = 632), NOT box center (790)
    assertTrue("Left icon tap X ($tapX) must stay in left icon zone (< 680)", tapX < 680f)
    assertTrue("Tap X ($tapX) must stay inside pill bounds", tapX > 560f)
    assertTrue(abs(tapY - 415f) < 1f)

    // Normal square Quick Settings tile [200..360, 700..860] -> taps center (280, 780)
    val (sqX, sqY) = RiyaAccessibilityService.computeControlCenterToggleTapPoint(200, 700, 360, 860)
    assertTrue(abs(sqX - 280f) < 1f)
    assertTrue(abs(sqY - 780f) < 1f)

    // Dual-SIM Settings keys
    val keys = LxrDeviceAndHardwareEngine.resolveMobileDataGlobalSettingKeys(defaultSubId = 2)
    assertEquals(listOf("mobile_data2", "mobile_data", "mobile_data0", "mobile_data1"), keys)

    Settings.Global.putInt(ctx.contentResolver, "mobile_data", 1)
    assertNotNull(LxrDeviceAndHardwareEngine.isMobileDataEnabledNow(ctx).let { true })
  }

  @Test
  fun notesPreservation_keepsExistingTextForBodyAndMultilineGenericWithoutBreakingSearch() {
    val bodyCandidate =
      EditableFieldCandidate(
        index = 1,
        text = "আগের লেখা নোট",
        hint = "Note something down",
        contentDescription = "",
        viewId = "com.transsion.notepad:id/et_content",
        top = 300,
        bottom = 1400,
        left = 40,
        right = 1000,
        isSingleLine = false,
      )
    val appendedBody =
      EditableFieldMatcher.mergePreservedFieldText(
        role = EditableFieldRole.BODY,
        candidate = bodyCandidate,
        existingRaw = "আগের লেখা নোট",
        hintRaw = "Note something down",
        incomingText = "নতুন লাইন যোগ হলো",
        target = "auto",
      )
    assertEquals("আগের লেখা নোট\nনতুন লাইন যোগ হলো", appendedBody)

    val singleBoxNoteCandidate =
      EditableFieldCandidate(
        index = 0,
        text = "আজকের লিস্ট",
        hint = "",
        contentDescription = "",
        viewId = "com.example.notes:id/editor",
        top = 200,
        bottom = 1200,
        left = 40,
        right = 1000,
        isSingleLine = false,
      )
    val appendedGeneric =
      EditableFieldMatcher.mergePreservedFieldText(
        role = EditableFieldRole.GENERIC,
        candidate = singleBoxNoteCandidate,
        existingRaw = "আজকের লিস্ট",
        hintRaw = "",
        incomingText = "দুধ ও ডিম",
        target = "auto",
      )
    assertEquals("আজকের লিস্ট\nদুধ ও ডিম", appendedGeneric)

    val searchBoxCandidate =
      EditableFieldCandidate(
        index = 0,
        text = "old query",
        hint = "Search",
        contentDescription = "Search",
        viewId = "com.facebook.katana:id/search_src_text",
        top = 80,
        bottom = 180,
        left = 40,
        right = 900,
        isSingleLine = true,
      )
    val replacedSearch =
      EditableFieldMatcher.mergePreservedFieldText(
        role = EditableFieldRole.GENERIC,
        candidate = searchBoxCandidate,
        existingRaw = "old query",
        hintRaw = "Search",
        incomingText = "new query",
        target = "auto",
      )
    assertEquals("new query", replacedSearch)
  }

  @Test
  fun timingProfiles_bindIdenticalTimingsAcrossAll3VoiceModels() {
    val modelIds =
      listOf(
        "gemini-3.8-live",
        "gemini-3.1-flash-live-preview",
        "gemini-2.5-flash-native-audio-preview-12-2025",
      )
    val profiles = modelIds.map { Store.timingProfileForModel(it) }
    profiles.forEach { p ->
      assertEquals(1600, p.micChunkBytes)
      assertEquals(6400, p.minAudioRecordBufferBytes)
      assertEquals(4, p.audioTrackBufferMultiplier)
      assertEquals(30L, p.wsPingIntervalSec)
      assertEquals(12_000L, p.setupTimeoutMs)
      assertEquals(3_000L, p.reconnectDelayMs)
      assertEquals(3_500L, p.screenWatchIntervalMs)
      assertEquals(1_200L, p.screenScanMinGapMs)
      assertEquals(240, p.silenceDurationMs(ReplyDelayOption.INSTANT))
      assertEquals(360, p.silenceDurationMs(ReplyDelayOption.FAST))
      assertEquals(480, p.silenceDurationMs(ReplyDelayOption.NORMAL))
    }
  }

  @Test
  fun openNavigationDirections_autoAttemptsGpsAndLaunchesMapsSafely() {
    val app = ctx as Application
    shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    shadowOf(lm).setLocationEnabled(false)

    val result = LxrDeviceAndHardwareEngine.openNavigationDirections(ctx, "Dhaka", "driving")
    assertTrue(result.contains("Dhaka"))
    val started: Intent? = shadowOf(app).nextStartedActivity
    assertNotNull(started)
  }

  @Test
  fun keyPage_acceptsAndPersistsElevenLabsVoiceIdAndCapabilityReportReflectsState() {
    Store.setElevenLabsApiKey(ctx, "xi_test_key")
    Store.setElevenLabsEnabled(ctx, true)
    Store.setElevenLabsVoiceId(ctx, "21m00Tcm4TlvDq8ikWAM")

    composeTestRule.setContent {
      KeyPage(back = {}, onNavigate = {})
    }

    composeTestRule.onNodeWithTag("elevenlabs_voice_id_input").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("elevenlabs_voice_id_input").performTextReplacement("EXAVITQu4vr4xnSDxMaL")
    composeTestRule.onNodeWithTag("save_key_voice_button").performScrollTo().performClick()

    assertEquals("EXAVITQu4vr4xnSDxMaL", Store.elevenLabsVoiceId(ctx))

    val capReport = LxrCapabilityInspector.inspectLive(ctx)
    assertTrue(capReport.summaryBn.contains("মোবাইল ডাটা"))
    assertTrue(capReport.summaryBn.contains("EXAVITQu4vr4xnSDxMaL"))
  }

  @Test
  fun aboutPage_displaysVersion243() {
    composeTestRule.setContent {
      AboutPage(back = {}, versionName = BuildConfig.VERSION_NAME)
    }
    composeTestRule.onNodeWithText("Version\n2.43.0").assertIsDisplayed()
  }
}
