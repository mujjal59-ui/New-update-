package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R

object RiyaNotificationHelper {
  const val CHANNEL_ALERTS = "riya_alerts_channel"
  const val CHANNEL_MESSAGES = "riya_messages_channel"
  private const val NOTIF_ID_ACCESSIBILITY_OFF = 9001

  fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = context.getSystemService(NotificationManager::class.java) ?: return
      val alertChannel =
        NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
          )
          .apply {
            description = "অ্যাক্সেসিবিলিটি বন্ধ হলে তাৎক্ষণিক সতর্কবার্তা"
            enableVibration(true)
          }
      val msgChannel =
        NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH,
          )
          .apply {
            description = "WhatsApp, imo ও Messenger মেসেজ রিপ্লাই অনুমতি"
            enableVibration(true)
          }
      manager.createNotificationChannel(alertChannel)
      manager.createNotificationChannel(msgChannel)
    }
  }

  fun notifyAccessibilityDisabled(context: Context) {
    try {
      ensureChannels(context)
      val settingsIntent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      val settingsPending =
        PendingIntent.getActivity(
          context,
          101,
          settingsIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

      val openAppIntent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
      val appPending =
        PendingIntent.getActivity(
          context,
          102,
          openAppIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

      val notification =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
          .setSmallIcon(android.R.drawable.stat_sys_warning)
          .setContentTitle("LXR--AI: অ্যাক্সেসিবিলিটি বন্ধ হয়ে গেছে!")
          .setContentText("বস, স্ক্রিন রিড ও অটো কাজ চালাতে এখনই অ্যাক্সেসিবিলিটি চালু করুন।")
          .setStyle(
            NotificationCompat.BigTextStyle()
              .bigText(
                "বস, আপনার ফোনে LXR--AI-এর Accessibility Service বন্ধ হয়ে গেছে। দয়া করে সেটিংস থেকে এখনই LXR--AI চালু করে দিন (এবং Battery Unrestricted ও Restricted Settings অনুমতি রাখুন)।"
              )
          )
          .setPriority(NotificationCompat.PRIORITY_HIGH)
          .setCategory(NotificationCompat.CATEGORY_ALARM)
          .setAutoCancel(true)
          .setContentIntent(appPending)
          .addAction(0, "সেটিংসে চালু করুন", settingsPending)
          .build()

      if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        NotificationManagerCompat.from(context).notify(NOTIF_ID_ACCESSIBILITY_OFF, notification)
      }
    } catch (_: SecurityException) {
      // POST_NOTIFICATIONS not granted yet on Android 13+; safe ignore
    } catch (_: Exception) {
      // Never crash on notification errors
    }
  }

  fun cancelAccessibilityDisabledAlert(context: Context) {
    try {
      NotificationManagerCompat.from(context).cancel(NOTIF_ID_ACCESSIBILITY_OFF)
    } catch (_: Exception) {}
  }

  fun notifyIncomingMessageForPermission(
    context: Context,
    event: IncomingMessageEvent,
    notificationId: Int,
  ) {
    try {
      ensureChannels(context)
      val approveIntent =
        Intent(context, AutoReplyActionReceiver::class.java).apply {
          action = AutoReplyActionReceiver.ACTION_APPROVE_REPLY
          putExtra(AutoReplyActionReceiver.EXTRA_EVENT_KEY, event.eventKey)
          putExtra(AutoReplyActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
      val approvePending =
        PendingIntent.getBroadcast(
          context,
          notificationId * 10 + 1,
          approveIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

      val dismissIntent =
        Intent(context, AutoReplyActionReceiver::class.java).apply {
          action = AutoReplyActionReceiver.ACTION_DISMISS_REPLY
          putExtra(AutoReplyActionReceiver.EXTRA_EVENT_KEY, event.eventKey)
          putExtra(AutoReplyActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
      val dismissPending =
        PendingIntent.getBroadcast(
          context,
          notificationId * 10 + 2,
          dismissIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

      val openAppIntent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
      val openPending =
        PendingIntent.getActivity(
          context,
          notificationId * 10 + 3,
          openAppIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

      val notification =
        NotificationCompat.Builder(context, CHANNEL_MESSAGES)
          .setSmallIcon(android.R.drawable.ic_dialog_email)
          .setContentTitle("${event.appLabel}: ${event.senderName}")
          .setContentText("বস, রিপ্লাই দেবো? \"${event.messageText}\"")
          .setStyle(
            NotificationCompat.BigTextStyle()
              .bigText(
                "বস, ${event.appLabel}-এ ${event.senderName} লিখেছেন: \"${event.messageText}\"\nপ্রস্তাবিত উত্তর: \"${event.suggestedReply}\"\nঅনুমতি দিলে আমি এখনই পাঠিয়ে দেবো।"
              )
          )
          .setPriority(NotificationCompat.PRIORITY_HIGH)
          .setAutoCancel(true)
          .setContentIntent(openPending)
          .addAction(0, "অনুমতি দিন ও পাঠান", approvePending)
          .addAction(0, "বাতিল", dismissPending)
          .build()

      if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
      }
    } catch (_: SecurityException) {
    } catch (_: Exception) {}
  }
}
