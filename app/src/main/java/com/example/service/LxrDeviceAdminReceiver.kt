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
 * Handles exact Alarms & Reminders scheduled via AlarmManager (SCHEDULE_EXACT_ALARM).
 */
class LxrReminderReceiver : BroadcastReceiver() {
  companion object {
    const val EXTRA_REMINDER_TEXT = "extra_reminder_text"
    private const val CHANNEL_ID = "lxr_exact_reminders"
  }

  override fun onReceive(context: Context, intent: Intent?) {
    val msg =
      intent?.getStringExtra(EXTRA_REMINDER_TEXT)?.trim()?.ifBlank { null }
        ?: "বস, তোমার নির্ধারিত রিমাইন্ডারের সময় হয়েছে।"
    RiyaState.addLog("⏰ রিমাইন্ডার: $msg")
    Store.remember(context, "[রিমাইন্ডার বেজেছে]: $msg")

    try {
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
        nm.createNotificationChannel(
          NotificationChannel(
            CHANNEL_ID,
            "LXR--AI অ্যালার্ম ও রিমাইন্ডার",
            NotificationManager.IMPORTANCE_HIGH,
          )
        )
      }
      val pi =
        PendingIntent.getActivity(
          context,
          9101,
          Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      val notif =
        NotificationCompat.Builder(context, CHANNEL_ID)
          .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
          .setContentTitle("LXR--AI রিমাইন্ডার")
          .setContentText(msg)
          .setPriority(NotificationCompat.PRIORITY_HIGH)
          .setAutoCancel(true)
          .setContentIntent(pi)
          .build()
      nm?.notify((System.currentTimeMillis() % 100_000).toInt(), notif)
    } catch (_: Throwable) {}

    RiyaVoiceManager.speakUrgent(context, "বস, তোমার রিমাইন্ডার: $msg")
  }
}
