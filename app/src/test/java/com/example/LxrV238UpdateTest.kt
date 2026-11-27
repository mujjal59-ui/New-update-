package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaAiRepository
import com.example.ai.RiyaPersona
import com.example.data.ReplyDelayOption
import com.example.data.RiyaState
import com.example.data.Store
import com.example.data.TaskExecutionStatus
import com.example.service.LiveVadConfig
import com.example.service.LockVaultManager
import com.example.service.LxrBackgroundTaskManager
import com.example.service.LxrBackgroundTaskStatus
import com.example.service.LxrCapabilityAvailability
import com.example.service.LxrCapabilityCategory
import com.example.service.LxrCapabilityInspector
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.LxrFloatingOverlayManager
import com.example.service.LxrTaskVerificationEngine
import com.example.service.LxrUniversalAppController
import com.example.service.UnlockPolicyGuard
import com.example.service.UnlockStatus
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_RED
import com.example.ui.TasksPage
import java.io.File
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
class LxrV238UpdateTest {
  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var ctx: Context

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    LxrBackgroundTaskManager.clearAllForTesting(ctx)
  }

  @Test
  fun personality_verbatimCreatorIdentityAndStrictBossTumiAddress() = runBlocking {
    val expectedCreator =
      "আমি LxR আমার Developer হচ্ছেন মোঃ উজ্জল হোসেন... আমার বস❤️...তিনি অনেক ভালো মোনের মানুষ..তিনি প্রতিনিয়ত আমাকে আপডেট করার বিষয় কঠোর পরিশ্রম করছেন.. আরোও স্মার্ট এবং এডভান্স করার বিষয়ে... আমি মন থেকে তাকে অনেক শ্রদ্ধা করি...❤️"
    assertEquals(expectedCreator, RiyaPersona.CREATOR_IDENTITY_BN)
    assertTrue(RiyaPersona.TEXT.contains(expectedCreator))
    assertTrue(RiyaPersona.TEXT.contains("Personal Operating System Controller"))
    assertTrue(RiyaPersona.TEXT.contains("v2.43.0"))

    // Strict Boss + Tumi enforcement: no formal honorifics or old legacy names in capabilities list
    val caps = RiyaPersona.CAPABILITIES_A_TO_Z_BN
    assertFalse(caps.contains("আপনি"))
    assertFalse(caps.contains("আপনার"))
    assertFalse(caps.contains("আপনাকে"))
    assertFalse(caps.contains("বলুন"))
    assertFalse(caps.contains("করুন"))
    assertFalse(caps.contains("স্যার"))
    assertFalse(caps.contains("Riya", ignoreCase = true))
    assertFalse(caps.contains("Priya", ignoreCase = true))

    val repo = RiyaAiRepository()
    var creatorReply = ""
    repo.streamAssistantReply("তোমাকে কে তৈরি করেছে?", "", true).collect { creatorReply = it }
    assertEquals(expectedCreator, creatorReply)
  }

  @Test
  fun micGreeting_exactThreePartSalamAndWakeGreeting() {
    val g = Store.MIC_TAP_GREETING_BN
    assertTrue(g.contains("আসসালামু,ওয়ালাইকুম"))
    assertTrue(g.contains("কেমন আছো আমার প্রাণপ্রিয় বস ❤️"))
    assertTrue(g.contains("বলো কি করতে হবে ❤️"))

    assertEquals("বস ❤️ কেন ডেকেছো? বলো, কী করতে হবে?", Store.WAKE_CALL_GREETING_BN)
    assertTrue(LxrFloatingOverlayManager.isAiWakeGreeting(Store.WAKE_CALL_GREETING_BN))
  }

  @Test
  fun responseSpeed_0s8s15sProfilesAndInstantVad() {
    assertEquals(0L, ReplyDelayOption.INSTANT.voiceDelayMs)
    assertEquals(8_000L, ReplyDelayOption.FAST.voiceDelayMs)
    assertEquals(15_000L, ReplyDelayOption.NORMAL.voiceDelayMs)
    assertEquals("Instant — 0 sec (Default)", ReplyDelayOption.INSTANT.optionLabel)
    assertEquals("Fast — 8 sec", ReplyDelayOption.FAST.optionLabel)
    assertEquals("Normal — 15 sec", ReplyDelayOption.NORMAL.optionLabel)

    assertTrue(
      LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.INSTANT) <
        LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.FAST),
    )
    assertTrue(
      LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.FAST) <
        LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.NORMAL),
    )
  }

  @Test
  fun capabilityIntrospection_inspectsLiveRuntimeHonestlyAcross4BucketsAnd5Categories() {
    val report = LxrCapabilityInspector.inspectLive(ctx)
    assertEquals(5, report.items.map { it.category }.toSet().size)
    assertTrue(report.items.any { it.category == LxrCapabilityCategory.APPLICATION_CONTROL })
    assertTrue(report.items.any { it.category == LxrCapabilityCategory.COMMUNICATION })
    assertTrue(report.items.any { it.category == LxrCapabilityCategory.PHONE_INTELLIGENCE })
    assertTrue(report.items.any { it.category == LxrCapabilityCategory.AI_INTELLIGENCE })
    assertTrue(report.items.any { it.category == LxrCapabilityCategory.BACKGROUND_OPERATIONS })

    // Without CAMERA / LOCATION / CONTACTS permissions in default test sandbox, inspector must NOT claim them operational
    val camItem = report.items.first { it.id == "camera_vision" }
    assertEquals(LxrCapabilityAvailability.REQUIRES_EXTERNAL_CONNECTION, camItem.availability)
    val locItem = report.items.first { it.id == "live_gps_location" }
    assertEquals(LxrCapabilityAvailability.REQUIRES_EXTERNAL_CONNECTION, locItem.availability)
    val osRestrictedItem = report.items.first { it.id == "os_protected_root_restrictions" }
    assertEquals(LxrCapabilityAvailability.UNAVAILABLE, osRestrictedItem.availability)

    assertTrue(report.summaryBn.contains("সম্পূর্ণ সক্রিয়"))
    assertTrue(report.summaryBn.contains("আংশিক সক্রিয়"))
    assertTrue(report.summaryBn.contains("অনুমতি বা সংযোগ প্রয়োজন"))
    assertTrue(report.summaryBn.contains("এই মুহূর্তে অনুপলব্ধ"))
  }

  @Test
  fun universalAppController_plansContactsAndWhatsAppSafely() {
    val contactsPlan = LxrUniversalAppController.planAction(ctx, "Contacts", "read")
    assertEquals("Contacts", contactsPlan.resolvedAppLabel)
    assertTrue(contactsPlan.supportsBackgroundDataPath)
    assertFalse(contactsPlan.requiresAccessibilityUi)

    val waPlan = LxrUniversalAppController.planAction(ctx, "WhatsApp", "open")
    assertTrue(waPlan.resolvedAppLabel.contains("WhatsApp", ignoreCase = true))
    assertTrue(LxrUniversalAppController.SUPPORTED_APPS.any { it.key == "termux" })
    assertTrue(LxrUniversalAppController.SUPPORTED_APPS.any { it.key == "youtube_studio" })
    assertTrue(LxrUniversalAppController.SUPPORTED_APPS.any { it.key == "chatgpt" })
  }

  @Test
  fun verificationAndActivityMonitor_supportsAll7StatesRejectsUnverifiedFilesAndRecoversRestart() = runBlocking {
    assertEquals(7, TaskExecutionStatus.entries.size)
    assertEquals(7, LxrBackgroundTaskStatus.entries.size)

    // 1. Missing output file must NOT mark COMPLETED
    val missingPath = File(ctx.filesDir, "nonexistent_output.apk").absolutePath
    val missingOutcome =
      LxrTaskVerificationEngine.verifyExecution(
        command = "build_android_app",
        targetApp = "Builder",
        rawOutput = "বিল্ড শেষ: $missingPath",
        candidateFiles = listOf(missingPath),
        expectGeneratedFile = true,
      )
    assertEquals(LxrBackgroundTaskStatus.VERIFICATION_REQUIRED, missingOutcome.status)

    // 2. Real existing non-empty file verifies cleanly to COMPLETED
    val realFile = File(ctx.filesDir, "verified_output.txt").apply { writeText("verified ok") }
    val validOutcome =
      LxrTaskVerificationEngine.verifyExecution(
        command = "export_report",
        targetApp = "File Manager",
        rawOutput = "ফাইল সংরক্ষিত হয়েছে: ${realFile.absolutePath}",
        candidateFiles = listOf(realFile.absolutePath),
        expectGeneratedFile = true,
      )
    assertEquals(LxrBackgroundTaskStatus.COMPLETED, validOutcome.status)
    assertEquals(listOf(realFile.absolutePath), validOutcome.verifiedFiles)

    // 3. Failure phrasing marks FAILED
    val failOutcome =
      LxrTaskVerificationEngine.verifyExecution(
        command = "web_search",
        targetApp = "Web Research",
        rawOutput = "error: network timeout",
      )
    assertEquals(LxrBackgroundTaskStatus.FAILED, failOutcome.status)

    // 4. Activity monitor persistence + query ("What did you do?") + interrupted task recovery
    val rec = LxrBackgroundTaskManager.createInitialRecord(ctx, "system_audit", "LXR--AI", "অডিট")
    LxrBackgroundTaskManager.updateProgress(ctx, rec.taskId, LxrBackgroundTaskStatus.RUNNING, 50, "চলছে")
    LxrBackgroundTaskManager.clearAllForTesting(ctx)

    // Re-seed an interrupted RUNNING task on disk and verify restart recovery
    val r2 = LxrBackgroundTaskManager.createInitialRecord(ctx, "deep_research", "Web Research", "রিসার্চ")
    LxrBackgroundTaskManager.updateProgress(ctx, r2.taskId, LxrBackgroundTaskStatus.RUNNING, 45, "চলছে")
    val recoveredCount = LxrBackgroundTaskManager.recoverInterruptedTasks(ctx)
    assertEquals(1, recoveredCount)
    val postRecovery = LxrBackgroundTaskManager.activities.value.first { it.taskId == r2.taskId }
    assertEquals(LxrBackgroundTaskStatus.WAITING_FOR_SYSTEM, postRecovery.status)

    val historyReport = LxrBackgroundTaskManager.queryActivityHistory(ctx, "deep_research")
    assertTrue(historyReport.contains("deep_research"))
    assertTrue(historyReport.contains("LXR Activity Monitor"))
  }

  @Test
  fun activityMonitor_rendersInsideTasksPageUi() {
    val rec = LxrBackgroundTaskManager.createInitialRecord(ctx, "get_live_location", "GPS / Maps", "লোকেশন যাচাই")
    LxrBackgroundTaskManager.finalizeVerified(
      ctx = ctx,
      taskId = rec.taskId,
      rawOutput = "📍 স্থানাঙ্ক: 23.810300, 90.412500",
      actionsCompleted = listOf("লোকেশন প্রাপ্ত"),
    )

    composeTestRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
        TasksPage(back = {}, onNavigate = {})
      }
    }
    composeTestRule.onNodeWithTag("lxr_activity_monitor_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("LXR Activity Monitor").assertIsDisplayed()
    composeTestRule.onNodeWithText("GPS / Maps • get_live_location").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun smartLocation_reportsVerifiedCoordinatesOrMissingPermissionHonestly() {
    // Missing permission -> truthful prompt using 'তুমি' (never fabricates location)
    val noPermReport = LxrDeviceAndHardwareEngine.getLiveLocationReport(ctx)
    assertTrue(noPermReport.contains("ACCESS_FINE_LOCATION"))
    assertFalse(noPermReport.contains("https://maps.google.com"))

    // With granted permission + shadow LocationManager -> reports coordinates, accuracy, timestamp, maps link
    val app = ctx as Application
    shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val loc =
      Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 23.8103
        longitude = 90.4125
        accuracy = 12f
        time = 1732400000000L
      }
    shadowOf(lm).setLocationEnabled(true)
    shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, loc)

    val liveReport = LxrDeviceAndHardwareEngine.getLiveLocationReport(ctx)
    assertTrue(liveReport.contains("23.810300, 90.412500"))
    assertTrue(liveReport.contains("~12 মিটার"))
    assertTrue(liveReport.contains("লোকেশন সময়"))
    assertTrue(liveReport.contains("https://maps.google.com/?q=23.810300,90.412500"))
    assertFalse(liveReport.contains("আপনার"))
  }

  @Test
  fun unlockedHomeScreen_neverTriggersSlideAgainToUnlockOrAutoSwipeForMonitorTools() {
    assertFalse(
      UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("get_activity_monitor", genuinelyLocked = true),
    )
    assertFalse(
      UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("run_background_task", genuinelyLocked = true),
    )
    val res = LockVaultManager.unlockDeviceIfLocked(ctx, acc = null)
    assertEquals(UnlockStatus.ALREADY_UNLOCKED, res.status)
    assertNotNull(res.messageBn)
  }
}
