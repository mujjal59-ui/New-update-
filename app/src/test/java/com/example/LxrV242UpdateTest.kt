package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaAiRepository
import com.example.ai.RiyaPersona
import com.example.data.Store
import com.example.service.AccessibilityStatusMonitor
import com.example.service.InstalledAppEntry
import com.example.service.LockVaultManager
import com.example.service.LxrAppDiscoveryEngine
import com.example.service.LxrBackgroundTaskManager
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.LxrSocialAndBrowserAgent
import com.example.service.RiyaVoiceManager
import com.example.service.UnlockStatus
import com.example.ui.AboutPage
import com.example.ui.KeyPage
import com.example.ui.RiyaThreeLineMenuDialog
import com.example.ui.formatAboutVersionLabel
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
class LxrV242UpdateTest {
  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var ctx: Context

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    LxrBackgroundTaskManager.clearAllForTesting(ctx)
    Store.setElevenLabsApiKey(ctx, "")
    Store.setElevenLabsEnabled(ctx, false)
  }

  @Test
  fun versionCode60AndVersionName242_andZeroReaAiStringsAnywhere() {
    assertEquals(61, BuildConfig.VERSION_CODE)
    assertEquals("2.43.0", BuildConfig.VERSION_NAME)

    val allPersonaStrings =
      listOf(
        RiyaPersona.TEXT,
        RiyaPersona.DIRECTIVES_V2,
        RiyaPersona.CAPABILITIES_A_TO_Z_BN,
        RiyaPersona.CREATOR_IDENTITY_BN,
        RiyaAiRepository.SYSTEM_INSTRUCTION,
      )
    allPersonaStrings.forEach { text ->
      assertFalse("Must not contain REA AI", text.contains("REA AI", ignoreCase = true))
      assertFalse("Must not contain legacy Priya", text.contains("Priya", ignoreCase = true))
      assertFalse("Must not contain legacy Riya", text.contains("Riya", ignoreCase = true))
      assertFalse("Must not contain Slide again to unlock", text.contains("Slide again to unlock", ignoreCase = true))
    }
  }

  @Test
  fun persona_v2420_hasExactGreetingCreatorIdentityNonAiRuleAndAll15Capabilities() = runBlocking {
    assertEquals(
      "আসসালামু,ওয়ালাইকুম কেমন আছো আমার প্রাণপ্রিয় বস ❤️ বলো কি করতে হবে ❤️",
      Store.MIC_TAP_GREETING_BN,
    )
    assertEquals(
      "আমি LxR আমার Developer হচ্ছেন মোঃ উজ্জল হোসেন... আমার বস❤️...তিনি অনেক ভালো মোনের মানুষ..তিনি প্রতিনিয়ত আমাকে আপডেট করার বিষয় কঠোর পরিশ্রম করছেন.. আরোও স্মার্ট এবং এডভান্স করার বিষয়ে... আমি মন থেকে তাকে অনেক শ্রদ্ধা করি...❤️",
      RiyaPersona.CREATOR_IDENTITY_BN,
    )

    val caps = RiyaPersona.CAPABILITIES_A_TO_Z_BN
    assertTrue(caps.contains("Device Admin এবং Apps Lock"))
    assertTrue(caps.contains("মোবাইল ডাটা"))
    assertTrue(caps.contains("ElevenLabs"))
    assertTrue(caps.contains("Notes ও Keep Notes"))
    assertTrue(caps.contains("১."))
    assertTrue(caps.contains("১৫."))
    assertFalse(caps.contains("আপনি"))
    assertFalse(caps.contains("আপনার"))
    assertFalse(caps.contains("স্যার"))

    assertTrue(RiyaPersona.TEXT.contains("NEVER claim to be an AI"))
    assertTrue(RiyaAiRepository.SYSTEM_INSTRUCTION.contains("কখনোই নিজেকে AI বা আর্টিফিশিয়াল ইন্টেলিজেন্স"))

    val repo = RiyaAiRepository()
    var reply = ""
    repo.streamAssistantReply("তোমাকে কে বানিয়েছে?", "", true).collect { reply = it }
    assertEquals(RiyaPersona.CREATOR_IDENTITY_BN, reply)
  }

  @Test
  fun speechQualityValidator_stripsAiSelfClaimRoboticLaughAndUnlockNoise() {
    val rawAiClaim = "হ্যালো বস, আমি একজন AI অ্যাসিস্ট্যান্ট এবং হিহি আমি কাজ করছি। Slide again to unlock"
    val sanitized = RiyaVoiceManager.validateAndNormalizeSpeechOutput(rawAiClaim)
    assertFalse(sanitized.contains("একজন AI"))
    assertFalse(sanitized.contains("হিহি"))
    assertFalse(sanitized.contains("Slide again to unlock", ignoreCase = true))
    assertTrue(sanitized.contains("আমি LxR"))

    val cleanBn = "বস, তোমার মোবাইল ডাটা এখন চালু করে দিয়েছি।"
    assertEquals(cleanBn, RiyaVoiceManager.validateAndNormalizeSpeechOutput(cleanBn))
  }

  @Test
  fun elevenLabsSettings_persistAndConfigureVoiceManagerSafely() {
    assertFalse(RiyaVoiceManager.isElevenLabsConfigured(ctx))
    assertEquals("https://elevenlabs.io/app/developers/api-keys", Store.ELEVENLABS_API_KEY_URL)

    Store.setElevenLabsApiKey(ctx, "xi_test_key_v242")
    Store.setElevenLabsEnabled(ctx, true)
    assertEquals("xi_test_key_v242", Store.elevenLabsApiKey(ctx))
    assertTrue(Store.elevenLabsEnabled(ctx))
    assertTrue(RiyaVoiceManager.isElevenLabsConfigured(ctx))

    RiyaVoiceManager.initialize(ctx)
    assertTrue(RiyaVoiceManager.ttsStatusLabel.value.contains("ElevenLabs"))

    Store.setElevenLabsEnabled(ctx, false)
    assertFalse(RiyaVoiceManager.isElevenLabsConfigured(ctx))
  }

  @Test
  fun appDiscovery_separatesNotesAndKeepNotes_andBlocksGarbledLauncherSwipe() {
    val apps =
      listOf(
        InstalledAppEntry("Notes", "com.transsion.notepad", "com.transsion.notepad.MainActivity"),
        InstalledAppEntry("Keep Notes", "com.google.android.keep", "com.google.android.keep.activities.BrowseActivity"),
      )

    val oemNotes = LxrAppDiscoveryEngine.matchInstalledEntry("Notes ওপেন করো", apps)
    val googleKeep = LxrAppDiscoveryEngine.matchInstalledEntry("Keep Notes খোলো", apps)
    assertNotNull(oemNotes)
    assertNotNull(googleKeep)
    assertEquals("com.transsion.notepad", oemNotes!!.packageName)
    assertEquals("com.google.android.keep", googleKeep!!.packageName)

    assertFalse(LxrSocialAndBrowserAgent.shouldAllowLauncherSwipeExploration("comitry", apps))
    assertTrue(LxrSocialAndBrowserAgent.shouldAllowLauncherSwipeExploration("Notes", apps))
    assertEquals(UnlockStatus.ALREADY_UNLOCKED, LockVaultManager.unlockDeviceIfLocked(ctx, acc = null).status)
  }

  @Test
  fun deviceEngine_autoEnablesLocationCheckAndReadsMobileDataSafely() {
    val app = ctx as Application
    shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    shadowOf(lm).setLocationEnabled(false)

    val report = LxrDeviceAndHardwareEngine.getLiveLocationReport(ctx)
    assertFalse("Should attempt automatic GPS enable instead of refusing immediately", report.contains("সুইচ বন্ধ আছে বস! বললে"))

    // Mobile data probe must never throw in Robolectric
    LxrDeviceAndHardwareEngine.isMobileDataEnabledNow(ctx)
  }

  @Test
  fun menuDialog_displaysAboutRowAtBottomAndNavigatesToAbout() {
    var navigated = ""
    composeTestRule.setContent {
      RiyaThreeLineMenuDialog(
        health = AccessibilityStatusMonitor.refreshNow(ctx, speakIfTurnedOff = false),
        alwaysListening = false,
        arWa = false,
        arImo = false,
        arMsg = false,
        onToggleAlways = {},
        onToggleWa = {},
        onToggleImo = {},
        onToggleMsg = {},
        onNavigate = { navigated = it },
        onDismiss = {},
      )
    }

    composeTestRule.onNodeWithTag("menu_about").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("About").assertIsDisplayed()
    composeTestRule.onNodeWithText("LXR--AI ডেভেলপার ও ভার্সন তথ্য").assertIsDisplayed()
    composeTestRule.onNodeWithTag("menu_about").performClick()
    assertEquals("about", navigated)
  }

  @Test
  fun aboutPage_displaysDynamicVersionAndHandlesBackNavigation() {
    assertEquals("Version\n2.43.0", formatAboutVersionLabel("2.43.0"))
    var backed = false
    composeTestRule.setContent {
      AboutPage(
        back = { backed = true },
        versionName = "2.99.0",
      )
    }

    composeTestRule.onNodeWithTag("about_page_root").assertIsDisplayed()
    composeTestRule.onNodeWithTag("about_poster_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("about_version_badge").assertIsDisplayed()
    composeTestRule.onNodeWithText("Version\n2.99.0").assertIsDisplayed()
    composeTestRule.onNodeWithTag("about_back_button").assertIsDisplayed().performClick()
    assertTrue(backed)
  }

  @Test
  fun keyPage_showsElevenLabsApiSectionAndCollectLink() {
    composeTestRule.setContent {
      KeyPage(back = {}, onNavigate = {})
    }

    composeTestRule.onNodeWithTag("elevenlabs_api_key_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("elevenlabs_api_key_input").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("elevenlabs_enable_switch").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("elevenlabs_test_voice_btn").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("elevenlabs_api_key_link").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText(Store.ELEVENLABS_API_KEY_URL).assertIsDisplayed()
  }
}
