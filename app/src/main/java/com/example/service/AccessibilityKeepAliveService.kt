package com.example.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.RiyaState
import com.example.data.Store
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lightweight Foreground Guard Service that keeps the host process alive whenever Accessibility
 * or Notification Access is enabled, preventing Infinix XOS / Android 16 App Freezer from killing
 * RiyaAccessibilityService when Boss exits or swipes LXR--AI away from Recent Apps.
 */
class AccessibilityKeepAliveService : Service() {
  companion object {
    private const val CHANNEL_ID = "lxr_guard_channel_v2"
    private const val NOTIF_ID = 102

    fun shouldGuardBeActive(context: Context): Boolean {
      val appCtx = context.applicationContext
      val accAlive =
        RiyaAccessibilityService.instance != null || Keep.isEnabledInSettingsOrAm(appCtx)
      val notifAllowed =
        try {
          NotificationManagerCompat.getEnabledListenerPackages(appCtx).contains(appCtx.packageName)
        } catch (_: Throwable) {
          false
        }
      val ryaAlwaysOn =
        !Store.userStoppedService(appCtx) &&
          Store.apiKey(appCtx).isNotBlank() &&
          RiyaState.running.value
      return accAlive || notifAllowed || ryaAlwaysOn
    }

    fun sync(context: Context) {
      val appCtx = context.applicationContext
      if (shouldGuardBeActive(appCtx)) {
        try {
          ContextCompat.startForegroundService(
            appCtx,
            Intent(appCtx, AccessibilityKeepAliveService::class.java),
          )
        } catch (_: Throwable) {}
      }
    }

    fun scheduleRestart(context: Context, delayMs: Long = 650L) {
      val appCtx = context.applicationContext
      if (!shouldGuardBeActive(appCtx)) return
      try {
        val restartIntent = Intent(appCtx, AccessibilityKeepAliveService::class.java)
        val pi =
          PendingIntent.getService(
            appCtx,
            1029,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
        val alarm = appCtx.getSystemService(ALARM_SERVICE) as? AlarmManager
        alarm?.set(
          AlarmManager.ELAPSED_REALTIME_WAKEUP,
          SystemClock.elapsedRealtime() + delayMs,
          pi,
        )
      } catch (_: Throwable) {}
    }

    fun stop(context: Context) {
      try {
        context.applicationContext.stopService(
          Intent(context.applicationContext, AccessibilityKeepAliveService::class.java)
        )
      } catch (_: Throwable) {}
    }
  }

  private val scope =
    CoroutineScope(
      SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> }
    )

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    promoteForegroundSafe()
    scope.launch {
      var loopCount = 0
      while (true) {
        delay(8_000L)
        loopCount++
        try {
          Keep.ensureAccessibility(applicationContext)
          val notifAllowed =
            NotificationManagerCompat.getEnabledListenerPackages(applicationContext)
              .contains(packageName)
          if (notifAllowed && !RiyaRuntimeBridge.isNotificationListenerConnected.value) {
            NotificationListenerService.requestRebind(
              ComponentName(applicationContext, RiyaNotificationListenerService::class.java)
            )
          }
          if (
            loopCount % 2 == 0 &&
              !Store.userStoppedService(applicationContext) &&
              Store.apiKey(applicationContext).isNotBlank() &&
              !RiyaState.running.value &&
              ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.RECORD_AUDIO,
              ) == PackageManager.PERMISSION_GRANTED
          ) {
            try {
              ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, RiyaService::class.java),
              )
            } catch (_: Throwable) {}
          }
          AccessibilityStatusMonitor.refreshNow(applicationContext, speakIfTurnedOff = false)
        } catch (_: Throwable) {}
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == "STOP") {
      stopSelf()
      return START_NOT_STICKY
    }
    promoteForegroundSafe()
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // Crucial for Infinix Hot 60 Pro+ (Android 16 XOS): immediately schedule guard keep-alive so exiting/swiping UI never unbinds Accessibility
    if (shouldGuardBeActive(applicationContext)) {
      scheduleRestart(applicationContext, delayMs = 600L)
      sync(applicationContext)
    }
    super.onTaskRemoved(rootIntent)
  }

  private fun promoteForegroundSafe() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "LXR--AI সক্রিয় গার্ড", NotificationManager.IMPORTANCE_LOW)
            .apply {
              setShowBadge(false)
              setSound(null, null)
              enableVibration(false)
            }
        )
      }
      val open =
        PendingIntent.getActivity(
          this,
          102,
          Intent(this, MainActivity::class.java),
          PendingIntent.FLAG_IMMUTABLE,
        )
      val notif =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LXR--AI অ্যাক্সেসিবিলিটি গার্ড সক্রিয়")
            .setContentText("অ্যাপ বন্ধ করলেও Accessibility ও মেসেজ সুরক্ষা চালু থাকবে")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        } else {
          @Suppress("DEPRECATION")
          Notification.Builder(this)
            .setContentTitle("LXR--AI অ্যাক্সেসিবিলিটি গার্ড সক্রিয়")
            .setContentText("Accessibility ও মেসেজ সুরক্ষা চালু")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        }
      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else if (Build.VERSION.SDK_INT >= 29) {
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
      } else {
        startForeground(NOTIF_ID, notif)
      }
    } catch (_: Throwable) {}
  }

  override fun onDestroy() {
    scope.cancel()
    if (shouldGuardBeActive(applicationContext)) {
      scheduleRestart(applicationContext, delayMs = 900L)
    }
    super.onDestroy()
  }
}
