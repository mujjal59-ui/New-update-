package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaAiRepository
import com.example.ai.RiyaPersona
import com.example.data.ReplyDelayOption
import com.example.data.ReplyLimitOption
import com.example.data.RiyaState
import com.example.data.Store
import com.example.service.LivePhoneLockPhase
import com.example.service.LivePhoneStateReport
import com.example.service.LockVaultManager
import com.example.service.LxrBackgroundTaskManager
import com.example.service.LxrBackgroundTaskStatus
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.LxrParallelTaskSpec
import com.example.service.LxrVoiceCommandQueue
import com.example.service.ReplyPolicyScheduler
import com.example.service.SmartLockExecutionMode
import com.example.service.VoiceQueueItemStatus
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_RED
import com.example.ui.KeyPage
import com.example.ui.TasksPage
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class LxrV239UpdateTest {
  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var ctx: Context

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    LxrBackgroundTaskManager.clearAllForTesting(ctx)
    LxrVoiceCommandQueue.clearGlobalMetricsForTesting()
    ReplyPolicyScheduler.resetCounter(ctx)
    Store.setReplyDelaySeconds(ctx, ReplyDelayOption.INSTANT.seconds)
    Store.setReplyLimitMax(ctx, ReplyLimitOption.UNLIMITED.maxReplies)
  }

  @Test
  fun smartLockDetection_unlockedNeverSwipesAndDistinguishesLockedBackgroundVsAuthRequired() {
    val unlockedState =
      LivePhoneStateReport(
        phase = LivePhoneLockPhase.UNLOCKED,
        isLocked = false,
        activePackage = "com.transsion.XOSLauncher",
        summaryBn = "স্ক্রিন খোলা",
      )
    val unlockedPlan =
      LockVaultManager.evaluateSmartLockExecutionDecision(
        phoneState = unlockedState,
        requiresForegroundUi = true,
        autoUnlockEnabled = true,
        hasSavedCredentials = true,
        isDeviceSecure = true,
      )
    assertEquals(SmartLockExecutionMode.UNLOCKED_DIRECT_EXECUTION, unlockedPlan.mode)
    assertFalse(unlockedPlan.shouldSwipeKeyguard)
    assertFalse(unlockedPlan.shouldWakeScreen)
    assertFalse(unlockedPlan.shouldRelockAfterFinish)

    val lockedState =
      LivePhoneStateReport(
        phase = LivePhoneLockPhase.KEYGUARD_LOCKED,
        isLocked = true,
        activePackage = "com.android.systemui",
        summaryBn = "স্ক্রিন লক",
      )
    val lockedBgPlan =
      LockVaultManager.evaluateSmartLockExecutionDecision(
        phoneState = lockedState,
        requiresForegroundUi = false,
        autoUnlockEnabled = true,
        hasSavedCredentials = true,
        isDeviceSecure = true,
      )
    assertEquals(SmartLockExecutionMode.LOCKED_BACKGROUND_EXECUTION, lockedBgPlan.mode)
    assertFalse(lockedBgPlan.shouldWakeScreen)
    assertFalse(lockedBgPlan.shouldSwipeKeyguard)

    val lockedUiWithCreds =
      LockVaultManager.evaluateSmartLockExecutionDecision(
        phoneState = lockedState,
        requiresForegroundUi = true,
        autoUnlockEnabled = true,
        hasSavedCredentials = true,
        isDeviceSecure = true,
      )
    assertEquals(SmartLockExecutionMode.LOCKED_REQUIRES_DEVICE_AUTH_OR_UNLOCK, lockedUiWithCreds.mode)
    assertTrue(lockedUiWithCreds.shouldRelockAfterFinish)

    val lockedUiNoCreds =
      LockVaultManager.evaluateSmartLockExecutionDecision(
        phoneState = lockedState,
        requiresForegroundUi = true,
        autoUnlockEnabled = true,
        hasSavedCredentials = false,
        isDeviceSecure = true,
      )
    assertEquals(SmartLockExecutionMode.LOCKED_CANNOT_BYPASS_SECURE_LOCK, lockedUiNoCreds.mode)
    assertFalse(lockedUiNoCreds.shouldSwipeKeyguard)
  }

  @Test
  fun replyDelayAndLimit_0s8s15sPersistenceLegacyMigrationAndUiSelection() {
    assertEquals(0L, ReplyDelayOption.INSTANT.voiceDelayMs)
    assertEquals(8_000L, ReplyDelayOption.FAST.voiceDelayMs)
    assertEquals(15_000L, ReplyDelayOption.NORMAL.voiceDelayMs)

    // Backward-compatibility migration for legacy 5s / 10s values
    assertEquals(ReplyDelayOption.FAST, ReplyDelayOption.fromSeconds(5))
    assertEquals(ReplyDelayOption.FAST, ReplyDelayOption.fromSeconds(8))
    assertEquals(ReplyDelayOption.NORMAL, ReplyDelayOption.fromSeconds(10))
    assertEquals(ReplyDelayOption.NORMAL, ReplyDelayOption.fromSeconds(15))

    composeTestRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
        KeyPage(back = {}, onNavigate = {})
      }
    }

    composeTestRule.onNodeWithTag("reply_delay_option_8").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("reply_delay_selected_text").assertTextEquals("Selected: Fast (8 sec)")
    assertEquals(8, Store.replyDelaySeconds(ctx))

    composeTestRule.onNodeWithTag("reply_delay_option_15").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("reply_delay_selected_text").assertTextEquals("Selected: Normal (15 sec)")
    assertEquals(15, Store.replyDelaySeconds(ctx))

    composeTestRule.onNodeWithTag("reply_limit_option_20").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("reply_limit_selected_text").assertTextEquals("Selected: 20 Replies")
    assertEquals(20, Store.replyLimitMax(ctx))

    // Unlimited never blocks
    Store.setReplyLimitMax(ctx, ReplyLimitOption.UNLIMITED.maxReplies)
    repeat(60) {
      assertTrue(ReplyPolicyScheduler.tryConsumeAndPersist(ctx, Store.replyLimitMax(ctx)))
    }
    assertTrue(ReplyPolicyScheduler.canSendReply(Store.replyLimitMax(ctx)))
  }

  @Test
  fun voiceCommandQueue_nonBlockingConcurrentEnqueueDeduplicationAndStrictFifoDelivery() = runBlocking {
    var fakeNow = 10_000L
    val queue = LxrVoiceCommandQueue(dedupWindowMs = 1_500L, clock = { fakeNow })

    val deliveryOrder = mutableListOf<String>()
    val lock = Any()

    // Command 1 enqueued with Fast (8 sec captured) + Command 2 immediately after while Command 1 is waiting!
    val r1 =
      queue.enqueueSerialReply(
        rawText = "অ্যালার্ম সেট করো",
        capturedDelayMs = 8_000L,
        speechEndedAtMs = fakeNow,
        delayFn = { ms ->
          delay(25L) // simulate non-blocking async wait without stalling unit test clock
          fakeNow += ms
        },
      ) { entry ->
        synchronized(lock) { deliveryOrder.add("${entry.taskId}:1") }
        "অ্যালার্ম সেট করা হয়েছে"
      }

    // Immediate duplicate of Command 1 within dedupWindowMs must be deduplicated cleanly
    val r1Dup =
      queue.enqueueSerialReply(
        rawText = "অ্যালার্ম   সেট করো",
        capturedDelayMs = 8_000L,
        speechEndedAtMs = fakeNow,
      ) {
        synchronized(lock) { deliveryOrder.add("DUP") }
        "DUP"
      }

    // Command 2 spoken right away while Command 1 hasn't finished yet!
    fakeNow += 200L
    val r2 =
      queue.enqueueSerialReply(
        rawText = "লোকেশন বলো",
        capturedDelayMs = 0L,
        speechEndedAtMs = fakeNow,
        delayFn = { ms -> if (ms > 0L) delay(10L) },
      ) { entry ->
        synchronized(lock) { deliveryOrder.add("${entry.taskId}:2") }
        "ঢাকা লোকেশন"
      }

    assertTrue(r1.accepted)
    assertFalse(r1Dup.accepted)
    assertEquals(r1.entry.taskId, r1Dup.duplicateOfTaskId)
    assertTrue(r2.accepted)
    assertNotEquals(r1.entry.taskId, r2.entry.taskId)

    // Wait briefly for FIFO background coroutine worker to finish both
    val deadline = System.currentTimeMillis() + 3_000L
    while (queue.recentMetrics(5).size < 2 && System.currentTimeMillis() < deadline) {
      delay(20L)
    }

    assertEquals(listOf("${r1.entry.taskId}:1", "${r2.entry.taskId}:2"), deliveryOrder)
    val metrics = queue.recentMetrics(5)
    assertEquals(2, metrics.size)
    assertEquals(VoiceQueueItemStatus.COMPLETED, r1.entry.status)
    assertEquals(VoiceQueueItemStatus.COMPLETED, r2.entry.status)
    assertTrue(LxrVoiceCommandQueue.liveMetrics.value.size >= 2)
  }

  @Test
  fun voiceCommandQueue_inFlightCapturedDelayRemainsImmutableWhenSettingChangesMidWait() = runTest {
    val capturedMs = 8_000L
    Store.setReplyDelaySeconds(ctx, ReplyDelayOption.FAST.seconds)
    var fired = false
    val deferred = async {
      ReplyPolicyScheduler.executeAfterCapturedDelayMs(initialDelayMs = capturedMs) {
        fired = true
        "OK"
      }
    }
    advanceTimeBy(2_000L)
    runCurrent()
    // User changes to Instant (0 sec) AFTER Command 1 already entered queue/delay
    Store.setReplyDelaySeconds(ctx, ReplyDelayOption.INSTANT.seconds)
    assertEquals(0L, ReplyPolicyScheduler.captureVoiceDelayMs(ctx))
    assertFalse(fired)
    advanceTimeBy(6_000L)
    runCurrent()
    assertTrue(fired)
    assertEquals("OK", deferred.await())
  }

  @Test
  fun backgroundMultiTask_parallelExecutionDependencyOrderErrorIsolationAndDeduplication() = runBlocking {
    val log = mutableListOf<String>()

    // Deduplication check: active task with same dedupKey returns existing record
    val firstTask =
      LxrBackgroundTaskManager.enqueueBackgroundTask(
        ctx = ctx,
        command = "sync_notes",
        targetApp = "Notes",
        operation = "নোট সিঙ্ক",
        scheduleOsJob = false,
        dedupKey = "notes_sync_1",
      ) {
        delay(60L)
        "নোট সিঙ্ক সম্পন্ন" to emptyList()
      }
    val dupTask =
      LxrBackgroundTaskManager.enqueueBackgroundTask(
        ctx = ctx,
        command = "sync_notes",
        targetApp = "Notes",
        operation = "ডুপ্লিকেট নোট সিঙ্ক",
        scheduleOsJob = false,
        dedupKey = "notes_sync_1",
      ) {
        "দ্বিতীয়বার চলবে না" to emptyList()
      }
    assertEquals(firstTask.taskId, dupTask.taskId)

    // Parallel execution with 1 failing task + 2 healthy tasks (including 1 dependent task)
    val failingSpec =
      LxrParallelTaskSpec(
        command = "broken_api",
        targetApp = "Network",
        operation = "ত্রুটিপূর্ণ কাজ",
      ) {
        throw IllegalStateException("simulated timeout")
      }
    val parentSpec =
      LxrParallelTaskSpec(
        command = "fetch_weather",
        targetApp = "Weather",
        operation = "আবহাওয়া আনা",
      ) {
        delay(50L)
        synchronized(log) { log.add("parent_done") }
        "ঢাকা ২৯ ডিগ্রি" to emptyList()
      }

    val batch = LxrBackgroundTaskManager.enqueueParallelTasks(ctx, listOf(failingSpec, parentSpec), scheduleOsJob = false)
    val parentRec = batch[1]

    val childRec =
      LxrBackgroundTaskManager.enqueueBackgroundTask(
        ctx = ctx,
        command = "summarize_weather",
        targetApp = "Assistant",
        operation = "আবহাওয়া সারসংক্ষেপ",
        scheduleOsJob = false,
        dependsOnTaskId = parentRec.taskId,
      ) {
        synchronized(log) { log.add("child_done") }
        "আজ আবহাওয়া সুন্দর" to emptyList()
      }

    val failDone = LxrBackgroundTaskManager.awaitTaskCompletion(batch[0].taskId, timeoutMs = 4_000L)!!
    val parentDone = LxrBackgroundTaskManager.awaitTaskCompletion(parentRec.taskId, timeoutMs = 4_000L)!!
    val childDone = LxrBackgroundTaskManager.awaitTaskCompletion(childRec.taskId, timeoutMs = 4_000L)!!

    assertEquals(LxrBackgroundTaskStatus.FAILED, failDone.status)
    assertNotNull(failDone.errorDetails)
    assertEquals(LxrBackgroundTaskStatus.COMPLETED, parentDone.status)
    assertEquals(LxrBackgroundTaskStatus.COMPLETED, childDone.status)
    assertEquals(listOf("parent_done", "child_done"), log)
  }

  @Test
  fun activityMonitor_rendersAllNineTelemetryFieldsInTasksPageAndHistory() {
    val rec =
      LxrBackgroundTaskManager.createInitialRecord(
        ctx = ctx,
        command = "get_live_location",
        targetApp = "GPS / Maps",
        currentOperation = "লাইভ জিপিএস নির্ণয়",
        executionMode = "BACKGROUND",
      )
    LxrBackgroundTaskManager.finalizeVerified(
      ctx = ctx,
      taskId = rec.taskId,
      rawOutput = "ঢাকা ২৪.৮১০৩, ৯০.৪১২৫",
      actionsCompleted = listOf("লোকেশন যাচাইকৃত"),
    )

    val history = LxrBackgroundTaskManager.queryActivityHistory(ctx, "get_live_location")
    assertTrue(history.contains("[BACKGROUND]"))
    assertTrue(history.contains("প্রাপ্ত:"))
    assertTrue(history.contains("শুরু:"))
    assertTrue(history.contains("মোট সময়:"))
    assertTrue(history.contains("অপারেশন:"))
    assertTrue(history.contains("বাস্তব ফলাফল:"))

    composeTestRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
        TasksPage(back = {}, onNavigate = {})
      }
    }
    composeTestRule.onNodeWithTag("lxr_activity_monitor_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("GPS / Maps • get_live_location").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("ত্রুটি: কোনো ত্রুটি নেই").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun gpsLocationEngine_selectBestLocationPrefersFreshAndAccurateFix() {
    val baseNow = 1_750_000_000_000L
    val oldLoc =
      Location(LocationManager.NETWORK_PROVIDER).apply {
        latitude = 23.70
        longitude = 90.35
        accuracy = 5f
        time = baseNow - 600_000L
      }
    val freshBetterAcc =
      Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 23.81
        longitude = 90.41
        accuracy = 8f
        time = baseNow
      }
    val freshCoarse =
      Location(LocationManager.NETWORK_PROVIDER).apply {
        latitude = 23.82
        longitude = 90.42
        accuracy = 95f
        time = baseNow - 10_000L
      }

    val picked = LxrDeviceAndHardwareEngine.selectBestLocation(listOf(oldLoc, freshCoarse, freshBetterAcc))
    assertNotNull(picked)
    assertEquals(23.81, picked!!.latitude, 0.0001)
    assertNull(LxrDeviceAndHardwareEngine.selectBestLocation(emptyList()))

    // Live GPS report with Robolectric shadow
    val app = ctx as Application
    shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    shadowOf(lm).setLocationEnabled(true)
    shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, freshBetterAcc)
    val report = LxrDeviceAndHardwareEngine.getLiveLocationReport(ctx)
    assertTrue(report.contains("23.810000, 90.410000"))
  }

  @Test
  fun persona_preservesCreatorIdentityAndStrictBossTumiInV239() = runBlocking {
    val expectedCreator =
      "আমাকে বানিয়েছে মোঃ উজ্জল হোসেন, আমার বস, আমি ওনার পার্সোনাল অ্যাসিস্ট্যান্ট। উনি একজন ভালো মনের মানুষ, আমাকে প্রতিনিয়ত নতুনভাবে আপডেট করার বিষয়ে কঠোর পরিশ্রম করেন।"
    assertEquals(expectedCreator, RiyaPersona.CREATOR_IDENTITY_BN)
    assertTrue(RiyaPersona.TEXT.contains("v2.39.0"))
    assertTrue(RiyaPersona.TEXT.contains("Fast = 8 sec, Normal = 15 sec"))
    assertTrue(RiyaPersona.TEXT.contains("FIFO"))
    assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("আপনার"))

    val repo = RiyaAiRepository()
    var reply = ""
    repo.streamAssistantReply("তোমাকে কে বানিয়েছে?", "", true).collect { reply = it }
    assertEquals(expectedCreator, reply)
  }
}
