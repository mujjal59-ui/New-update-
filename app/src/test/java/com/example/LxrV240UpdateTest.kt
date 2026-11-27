package com.example

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaAiRepository
import com.example.ai.RiyaPersona
import com.example.data.ReplyDelayOption
import com.example.data.Store
import com.example.service.LiveVadConfig
import com.example.service.LockVaultManager
import com.example.service.LxrAlarmAlertActivity
import com.example.service.LxrAlarmBootReceiver
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.LxrParallelToolPlanner
import com.example.service.LxrReminderReceiver
import com.example.service.UnlockPolicyGuard
import com.example.service.UnlockStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class LxrV240UpdateTest {

  private lateinit var ctx: Context

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    ctx.getSharedPreferences("lxr_alarm_prefs", Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun allThreeVoiceModels_bindExactConfiguredTimingsInStoreAndLiveVadConfig() {
    val expectedModels =
      listOf(
        "gemini-3.8-live",
        "gemini-3.1-flash-live-preview",
        "gemini-2.5-flash-native-audio-preview-12-2025",
      )
    assertEquals(expectedModels, Store.LIVE_VOICE_MODEL_OPTIONS.map { it.modelId })

    for (modelId in expectedModels) {
      Store.setModel(ctx, modelId)
      val profile = Store.activeTimingProfile(ctx)
      assertEquals(modelId, profile.modelId)
      assertEquals(16L, profile.wsConnectTimeoutSec)
      assertEquals(30L, profile.wsWriteTimeoutSec)
      assertEquals(30L, profile.wsPingIntervalSec)
      assertEquals(12_000L, profile.setupTimeoutMs)
      assertEquals(3_000L, profile.reconnectDelayMs)
      assertEquals(1600, profile.micChunkBytes)
      assertEquals(6400, profile.minAudioRecordBufferBytes)
      assertEquals(4, profile.audioTrackBufferMultiplier)
      assertEquals(3_500L, profile.screenWatchIntervalMs)
      assertEquals(1_200L, profile.screenScanMinGapMs)
      assertEquals(2_000L, profile.bossSpeakingGapMs)
      assertEquals(1_000L, profile.aiSpeakingGapMs)
      assertEquals(240L, profile.phoneTailHoldMs)

      assertEquals(240, LiveVadConfig.effectiveSilenceDurationMs(ReplyDelayOption.INSTANT, modelId))
      assertEquals(360, LiveVadConfig.effectiveSilenceDurationMs(ReplyDelayOption.FAST, modelId))
      assertEquals(480, LiveVadConfig.effectiveSilenceDurationMs(ReplyDelayOption.NORMAL, modelId))

      assertEquals(60L, LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.INSTANT, modelId))
      assertEquals(100L, LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.FAST, modelId))
      assertEquals(140L, LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.NORMAL, modelId))

      assertEquals(180L, LiveVadConfig.effectiveHangoverMs(ReplyDelayOption.INSTANT, modelId))
      assertEquals(220L, LiveVadConfig.effectiveHangoverMs(ReplyDelayOption.FAST, modelId))
      assertEquals(260L, LiveVadConfig.effectiveHangoverMs(ReplyDelayOption.NORMAL, modelId))
    }

    assertTrue(Store.timingProfileForModel("gemini-3.8-live").supportsMinimalThinking)
    assertTrue(Store.timingProfileForModel("gemini-3.1-flash-live-preview").supportsMinimalThinking)
    assertFalse(Store.timingProfileForModel("gemini-2.5-flash-native-audio-preview-12-2025").supportsMinimalThinking)
  }

  @Test
  fun slideAgainToUnlock_completelyRemovedAcrossAllModelsPersonaAndHomeTransitions() {
    assertFalse(
      "RiyaPersona.TEXT must not contain Slide again to unlock",
      RiyaPersona.TEXT.contains("Slide again to unlock", ignoreCase = true),
    )
    assertFalse(
      "RiyaPersona.DIRECTIVES_V2 must not contain Slide again to unlock",
      RiyaPersona.DIRECTIVES_V2.contains("Slide again to unlock", ignoreCase = true),
    )
    assertFalse(
      "RiyaPersona.CAPABILITIES_A_TO_Z_BN must not contain Slide again to unlock",
      RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("Slide again to unlock", ignoreCase = true),
    )
    assertFalse(
      "RiyaAiRepository.SYSTEM_INSTRUCTION must not contain Slide again to unlock",
      RiyaAiRepository.SYSTEM_INSTRUCTION.contains("Slide again to unlock", ignoreCase = true),
    )
    assertTrue(RiyaPersona.TEXT.contains("v2.43.0"))
    assertTrue(RiyaPersona.TEXT.contains(RiyaPersona.CREATOR_IDENTITY_BN))

    // Home/app-exit transition guard suppresses lock/swipe false positives
    LockVaultManager.markHomeOrAppExitTransition()
    assertTrue(LockVaultManager.isInHomeOrAppExitTransition())
    assertFalse(LockVaultManager.isMainScreenGenuinelyLockedBeforeTask(ctx, acc = null))
    assertEquals(UnlockStatus.ALREADY_UNLOCKED, LockVaultManager.unlockDeviceIfLocked(ctx, acc = null).status)

    // Swipe strategies stay above Infinix bottom-edge Slide again to unlock zone
    val strategies = LockVaultManager.buildLockscreenSwipeStrategies(1080f, 2400f)
    assertEquals(3, strategies.size)
    strategies.forEach { s ->
      assertTrue("Start Y (${s.startY}) must stay above bottom-edge gesture zone", s.startY < 2400f * 0.75f)
      assertTrue("Duration (${s.durationMs}) must stay smooth >= 350ms", s.durationMs >= 350L)
    }
  }

  @Test
  fun parallelMultiTaskPlanner_andBackgroundAlarmPolicyAreEnforced() {
    assertTrue(LxrParallelToolPlanner.isParallelSafeTool("schedule_exact_reminder"))
    assertTrue(LxrParallelToolPlanner.isParallelSafeTool("list_scheduled_alarms"))
    assertTrue(LxrParallelToolPlanner.isParallelSafeTool("web_search"))
    assertTrue(LxrParallelToolPlanner.isParallelSafeTool("get_live_location"))
    assertTrue(LxrParallelToolPlanner.isParallelSafeTool("current_time"))

    assertFalse(LxrParallelToolPlanner.isParallelSafeTool("open_app"))
    assertFalse(LxrParallelToolPlanner.isParallelSafeTool("send_message"))
    assertFalse(LxrParallelToolPlanner.isParallelSafeTool("click_text"))
    assertFalse(LxrParallelToolPlanner.isParallelSafeTool("read_screen"))

    assertTrue(LxrParallelToolPlanner.canRunBatchInParallel(listOf("web_search", "schedule_exact_reminder")))
    assertFalse(LxrParallelToolPlanner.canRunBatchInParallel(listOf("schedule_exact_reminder")))

    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("schedule_exact_reminder", genuinelyLocked = true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("list_scheduled_alarms", genuinelyLocked = true))
  }

  @Test
  fun phoneAlarmAndReminder_schedulesOsAlarmClockFiresAlertPersistsAndReschedulesOnBoot() {
    val pm = ctx.packageManager
    val pkgInfo =
      pm.getPackageInfo(
        ctx.packageName,
        PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS,
      )
    val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertTrue(perms.contains("com.android.alarm.permission.SET_ALARM"))
    assertTrue(perms.contains("android.permission.SCHEDULE_EXACT_ALARM"))
    assertTrue(perms.contains("android.permission.USE_FULL_SCREEN_INTENT"))
    assertTrue(perms.contains("android.permission.RECEIVE_BOOT_COMPLETED"))

    val activityNames = pkgInfo.activities?.map { it.name }?.toSet().orEmpty()
    assertTrue(activityNames.contains(LxrAlarmAlertActivity::class.java.name))
    val receiverNames = pkgInfo.receivers?.map { it.name }?.toSet().orEmpty()
    assertTrue(receiverNames.contains(LxrAlarmBootReceiver::class.java.name))
    assertTrue(receiverNames.contains(LxrReminderReceiver::class.java.name))

    // Verify target clock math for explicit morning hour/minute
    val (_, hMorning, mMorning) =
      LxrDeviceAndHardwareEngine.computeTargetAlarmTrigger(delaySeconds = 0L, hour = 6, minute = 30)
    assertEquals(6, hMorning)
    assertEquals(30, mMorning)

    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    val result =
      LxrDeviceAndHardwareEngine.scheduleExactReminder(
        ctx = ctx,
        title = "ফজর ও সকালের মিটিং",
        delaySeconds = 0L,
        hour = 6,
        minute = 30,
        mode = "alarm",
      )
    assertTrue("Result should confirm OS alarm registration: $result", result.contains("অ্যালার্ম"))
    assertTrue(result.contains("ফজর ও সকালের মিটিং"))

    val shadowAm = shadowOf(am)
    assertTrue("AlarmManager must hold real scheduled alarm", shadowAm.scheduledAlarms.isNotEmpty())
    assertNotNull("Android nextAlarmClock must be populated", am.nextAlarmClock)

    val persisted = LxrDeviceAndHardwareEngine.listPersistedAlarms(ctx)
    assertEquals(1, persisted.size)
    val saved = persisted.first()
    assertEquals("ফজর ও সকালের মিটিং", saved.title)
    assertEquals(6, saved.hour)
    assertEquals(30, saved.minute)
    assertEquals("SCHEDULED", saved.status)
    assertTrue(saved.alarmClockRegistered)

    val report = LxrDeviceAndHardwareEngine.listScheduledAlarmsReport(ctx)
    assertTrue(report.contains("ফজর ও সকালের মিটিং"))
    assertTrue(report.contains("SCHEDULED"))

    // Simulate OS Alarm trigger -> marks FIRED + posts CATEGORY_ALARM full-screen notification
    val receiver = LxrReminderReceiver()
    receiver.onReceive(
      ctx,
      Intent(ctx, LxrReminderReceiver::class.java)
        .setAction(LxrReminderReceiver.ACTION_ALARM_TRIGGER)
        .putExtra(LxrReminderReceiver.EXTRA_REMINDER_TEXT, saved.title)
        .putExtra(LxrReminderReceiver.EXTRA_ALARM_ID, saved.id)
        .putExtra(LxrReminderReceiver.EXTRA_NOTIF_ID, saved.reqCode),
    )
    assertEquals("FIRED", LxrDeviceAndHardwareEngine.listPersistedAlarms(ctx).first().status)
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val activeNotifs = shadowOf(nm).allNotifications
    assertTrue("Alarm trigger must post notification", activeNotifs.isNotEmpty())
    assertEquals(Notification.CATEGORY_ALARM, activeNotifs.first().category)

    // Dismiss intent marks DISMISSED cleanly
    receiver.onReceive(
      ctx,
      Intent(ctx, LxrReminderReceiver::class.java)
        .setAction(LxrReminderReceiver.ACTION_ALARM_DISMISS)
        .putExtra(LxrReminderReceiver.EXTRA_ALARM_ID, saved.id)
        .putExtra(LxrReminderReceiver.EXTRA_NOTIF_ID, saved.reqCode),
    )
    assertEquals("DISMISSED", LxrDeviceAndHardwareEngine.listPersistedAlarms(ctx).first().status)

    // Boot receiver restores remaining future SCHEDULED alarms
    LxrDeviceAndHardwareEngine.scheduleExactReminder(
      ctx = ctx,
      title = "বিকালে এক্সারসাইজ",
      delaySeconds = 3600L,
      mode = "alarm",
    )
    val bootReceiver = LxrAlarmBootReceiver()
    bootReceiver.onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
    val afterBootAlarms = LxrDeviceAndHardwareEngine.listPersistedAlarms(ctx)
    assertTrue(afterBootAlarms.any { it.title == "বিকালে এক্সারসাইজ" && it.status == "SCHEDULED" })
    assertNotNull(AlarmClock.ACTION_SET_ALARM)
  }
}
