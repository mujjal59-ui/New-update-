package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.RiyaState
import com.example.data.Store

/**
 * Integrates LXR--AI with Android "Device admin apps" so Boss can lock the screen reliably
 * and protect 24/7 background assistant availability.
 */
class LxrDeviceAdminReceiver : DeviceAdminReceiver() {
  override fun onEnabled(context: Context, intent: Intent) {
    super.onEnabled(context, intent)
    RiyaState.addLog("LXR--AI: Device Admin সক্রিয় করা হয়েছে ✓")
    Toast.makeText(context, "LXR--AI Device Admin সক্রিয় হয়েছে ✓", Toast.LENGTH_SHORT).show()
  }

  override fun onDisabled(context: Context, intent: Intent) {
    super.onDisabled(context, intent)
    RiyaState.addLog("LXR--AI: Device Admin বন্ধ করা হয়েছে")
  }
}

/**
 * Handles exact Alarms & Reminders scheduled via AlarmManager (setAlarmClock / SCHEDULE_EXACT_ALARM).
 */
class LxrReminderReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_ALARM_TRIGGER = "com.example.action.LXR_ALARM_TRIGGER"
    const val ACTION_ALARM_DISMISS = "com.example.action.LXR_ALARM_DISMISS"
    const val EXTRA_REMINDER_TEXT = "extra_reminder_text"
    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_NOTIF_ID = "extra_notif_id"
    private const val CHANNEL_ID = "lxr_exact_reminders_v2"
  }

  @Suppress("DEPRECATION")
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action == ACTION_ALARM_DISMISS) {
      val alarmId = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
      val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
      if (alarmId.isNotBlank()) {
        LxrDeviceAndHardwareEngine.markAlarmStatus(context, alarmId, "DISMISSED")
      }
      LxrAlarmAlertActivity.stopActiveRingtone()
      RiyaVoiceManager.stopSpeaking()
      if (notifId >= 0) {
        runCatching {
          val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
          nm?.cancel(notifId)
        }
      }
      return
    }

    val msg =
      intent?.getStringExtra(EXTRA_REMINDER_TEXT)?.trim()?.ifBlank { null }
        ?: "বস, তোমার নির্ধারিত অ্যালার্ম / রিমাইন্ডারের সময় হয়েছে।"
    val alarmId = intent?.getStringExtra(EXTRA_ALARM_ID).orEmpty()
    val notifId =
      intent?.getIntExtra(EXTRA_NOTIF_ID, -1)?.takeIf { it > 0 }
        ?: ((System.currentTimeMillis() % 100_000L).toInt() + 9200)

    if (alarmId.isNotBlank()) {
      LxrDeviceAndHardwareEngine.markAlarmStatus(context, alarmId, "FIRED")
    }
    RiyaState.addLog("⏰ অ্যালার্ম / রিমাইন্ডার বেজেছে: $msg")
    Store.remember(context, "[অ্যালার্ম / রিমাইন্ডার বেজেছে]: $msg")

    var wl: android.os.PowerManager.WakeLock? = null
    try {
      val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
      if (pm != null) {
        wl =
          pm.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
              android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
              android.os.PowerManager.ON_AFTER_RELEASE,
            "lxr_ai:alarm_wake",
          )
        wl.setReferenceCounted(false)
        wl.acquire(10_000L)
      }
    } catch (_: Throwable) {}

    if (!Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
      LxrAlarmAlertActivity.startRingtoneAndVibration(context)
    }

    try {
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
        val ch =
          NotificationChannel(
            CHANNEL_ID,
            "LXR--AI অ্যালার্ম ও রিমাইন্ডার",
            NotificationManager.IMPORTANCE_HIGH,
          ).apply {
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
          }
        nm.createNotificationChannel(ch)
      }
      val alertIntent =
        Intent(context, LxrAlarmAlertActivity::class.java)
          .putExtra(LxrAlarmAlertActivity.EXTRA_TITLE, msg)
          .putExtra(LxrAlarmAlertActivity.EXTRA_ALARM_ID, alarmId)
          .putExtra(LxrAlarmAlertActivity.EXTRA_NOTIF_ID, notifId)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      val fullScreenPi =
        PendingIntent.getActivity(
          context,
          notifId + 1,
          alertIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      val dismissIntent =
        Intent(context, LxrReminderReceiver::class.java)
          .setAction(ACTION_ALARM_DISMISS)
          .putExtra(EXTRA_ALARM_ID, alarmId)
          .putExtra(EXTRA_NOTIF_ID, notifId)
      val dismissPi =
        PendingIntent.getBroadcast(
          context,
          notifId + 2,
          dismissIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      val mainPi =
        PendingIntent.getActivity(
          context,
          9101,
          Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      val notif =
        NotificationCompat.Builder(context, CHANNEL_ID)
          .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
          .setContentTitle("⏰ LXR--AI অ্যালার্ম ও রিমাইন্ডার")
          .setContentText(msg)
          .setCategory(NotificationCompat.CATEGORY_ALARM)
          .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
          .setPriority(NotificationCompat.PRIORITY_MAX)
          .setFullScreenIntent(fullScreenPi, true)
          .addAction(android.R.drawable.ic_menu_close_clear_cancel, "বন্ধ করো", dismissPi)
          .setAutoCancel(true)
          .setContentIntent(mainPi)
          .build()
      nm?.notify(notifId, notif)

      if (!Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
        runCatching { context.startActivity(alertIntent) }
      }
    } catch (_: Throwable) {}

    try {
      RiyaVoiceManager.speakUrgent(context, "বস, তোমার অ্যালার্ম রিমাইন্ডার: $msg")
    } catch (_: Throwable) {}

    try {
      if (wl?.isHeld == true) wl.release()
    } catch (_: Throwable) {}
  }
}

/**
 * Restores persisted future LXR alarms/reminders across device reboots or exact-alarm permission changes.
 */
class LxrAlarmBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (
      action == Intent.ACTION_BOOT_COMPLETED ||
        action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
        action == Intent.ACTION_MY_PACKAGE_REPLACED ||
        action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    ) {
      runCatching { LxrDeviceAndHardwareEngine.reschedulePersistedAlarms(context.applicationContext) }
    }
  }
}
