package com.example

import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaPersona
import com.example.data.Store
import com.example.service.LivePhoneLockPhase
import com.example.service.LockVaultManager
import com.example.service.LxrCapabilityInspector
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.LxrParallelToolPlanner
import com.example.service.LxrToolWorkDescriber
import com.example.service.UnlockPolicyGuard
import com.example.service.UnlockStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
class LxrV241UpdateTest {

  private lateinit var ctx: Context

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    ctx.getSharedPreferences("lxr_alarm_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    shadowOf(ctx as android.app.Application).clearNextStartedActivities()
  }

  @Test
  fun allThreeVoiceModels_andHomeExitTransitionsNeverTriggerSlideAgainToUnlock() {
    assertTrue(LockVaultManager.HOME_EXIT_UNLOCK_SUPPRESSION_MS >= 5_000L)

    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    shadowOf(pm).setIsInteractive(true)
    shadowOf(km).setKeyguardLocked(false)
    shadowOf(km).setIsDeviceLocked(false)

    // Verify all 3 voice models keep unlocked/Home state clean
    for (opt in Store.LIVE_VOICE_MODEL_OPTIONS) {
      Store.setModel(ctx, opt.modelId)
      LockVaultManager.markHomeOrAppExitTransition()
      assertTrue(LockVaultManager.isInHomeOrAppExitTransition())
      assertFalse(LockVaultManager.isMainScreenGenuinelyLockedBeforeTask(ctx, acc = null))
      val state = LockVaultManager.describeLivePhoneState(ctx, acc = null)
      assertEquals(LivePhoneLockPhase.UNLOCKED, state.phase)
      assertFalse(state.isLocked)
      val unlockRes = LockVaultManager.unlockDeviceIfLocked(ctx, acc = null)
      assertEquals(UnlockStatus.ALREADY_UNLOCKED, unlockRes.status)
    }

    assertTrue(LockVaultManager.isLauncherPackage(ctx, "com.transsion.xoslauncher"))
    assertTrue(LockVaultManager.isLauncherPackage(ctx, "com.transsion.hilauncher"))
    assertFalse(LockVaultManager.isLauncherPackage(ctx, "com.whatsapp"))
  }

  @Test
  fun alarmsAndRemindersPermissionFlow_launchesExactAlarmSettingsSafely() {
    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    assertFalse(LxrDeviceAndHardwareEngine.canScheduleExactAlarms(ctx))

    val opened = LxrDeviceAndHardwareEngine.openExactAlarmSettings(ctx)
    assertTrue(opened)
    val startedIntent = shadowOf(ctx as android.app.Application).nextStartedActivity
    assertNotNull(startedIntent)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, startedIntent.action)
      assertEquals("package:${ctx.packageName}", startedIntent.data?.toString())
    }

    val repairMsg = LxrDeviceAndHardwareEngine.repairIssueWithBossPermission(ctx, "exact_alarm")
    assertTrue(repairMsg.contains("Alarms & reminders"))
    assertTrue(repairMsg.contains("Allow restricted settings"))

    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val grantedMsg = LxrDeviceAndHardwareEngine.repairIssueWithBossPermission(ctx, "exact_alarm")
    assertTrue(grantedMsg.contains("আগে থেকেই চালু"))
  }

  @Test
  fun phoneAlarmSetAndDismiss_schedulesAndCancelsRealOsAlarmClockAndUpdatesStatus() {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    ShadowAlarmManager.setCanScheduleExactAlarms(true)

    val setMorning =
      LxrDeviceAndHardwareEngine.scheduleExactReminder(
        ctx = ctx,
        title = "Morning Fajr Alarm",
        hour = 5,
        minute = 30,
        mode = "alarm",
      )
    assertTrue(setMorning.contains("সরাসরি ফোনের সিস্টেম অ্যালার্ম হিসেবে সেট করেছি"))
    assertNotNull(am.nextAlarmClock)

    val setNight =
      LxrDeviceAndHardwareEngine.scheduleExactReminder(
        ctx = ctx,
        title = "Night Medicine Reminder",
        hour = 21,
        minute = 15,
        mode = "alarm",
      )
    assertTrue(setNight.contains("সেট করেছি"))
    assertEquals(2, LxrDeviceAndHardwareEngine.listPersistedAlarms(ctx).count { it.status == "SCHEDULED" })

    // Dismiss specifically the 05:30 alarm
    val dismissMorning =
      LxrDeviceAndHardwareEngine.dismissOrCancelAlarm(
        ctx = ctx,
        acc = null,
        hour = 5,
        minute = 30,
      )
    assertTrue(dismissMorning.contains("বন্ধ করা LXR/OS অ্যালার্ম"))
    val afterMorning = LxrDeviceAndHardwareEngine.listPersistedAlarms(ctx)
    assertEquals("DISMISSED", afterMorning.first { it.title == "Morning Fajr Alarm" }.status)
    assertEquals("SCHEDULED", afterMorning.first { it.title == "Night Medicine Reminder" }.status)

    // Dismiss remaining alarms with dismissAll = true
    val dismissRest =
      LxrDeviceAndHardwareEngine.dismissOrCancelAlarm(
        ctx = ctx,
        acc = null,
        dismissAll = true,
      )
    assertTrue(dismissRest.contains("Night Medicine Reminder"))
    val afterAll = LxrDeviceAndHardwareEngine.listPersistedAlarms(ctx)
    assertTrue(afterAll.all { it.status == "DISMISSED" })
    assertNull(am.nextAlarmClock)
  }

  @Test
  fun manifestPersonaCapabilitiesAndToolPolicies_includeDismissAlarmAndPreserveIdentity() {
    val pm = ctx.packageManager
    val pkgInfo = pm.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
    val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertTrue(perms.contains("android.permission.SCHEDULE_EXACT_ALARM"))
    assertTrue(perms.contains("com.android.alarm.permission.SET_ALARM"))

    assertTrue(LxrParallelToolPlanner.isParallelSafeTool("dismiss_alarm"))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("dismiss_alarm", genuinelyLocked = true))
    assertTrue(LxrToolWorkDescriber.describeBn("dismiss_alarm").contains("অ্যালার্ম বন্ধ"))

    assertTrue(RiyaPersona.TEXT.contains("v2.41.0"))
    assertTrue(RiyaPersona.TEXT.contains("v2.40.0"))
    assertTrue(RiyaPersona.TEXT.contains("dismiss_alarm"))
    assertTrue(RiyaPersona.DIRECTIVES_V2.contains("dismiss_alarm"))
    assertTrue(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("ACTION_DISMISS_ALARM"))
    assertTrue(RiyaPersona.TEXT.contains(RiyaPersona.CREATOR_IDENTITY_BN))

    val capReport = LxrCapabilityInspector.inspectLive(ctx)
    assertTrue(capReport.summaryBn.contains("অ্যালার্ম সেট/বন্ধ"))
  }
}
