package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.MainActivity
import com.example.data.RiyaState

class RiyaService : Service() {
  private var live: GeminiLive? = null
  private var wl: PowerManager.WakeLock? = null

  override fun onBind(i: Intent?): IBinder? = null

  override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
    if (i?.action == "STOP") {
      stopSelf()
      return START_NOT_STICKY
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      getSystemService(NotificationManager::class.java)
        .createNotificationChannel(
          NotificationChannel("riya_live", "Riya AI Live", NotificationManager.IMPORTANCE_LOW)
        )
    }
    val stop =
      PendingIntent.getService(
        this,
        0,
        Intent(this, RiyaService::class.java).setAction("STOP"),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val open =
      PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val n =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(this, "riya_live")
          .setContentTitle("Riya AI শুনছেন")
          .setContentText("Always listening mode ON")
          .setSmallIcon(android.R.drawable.ic_btn_speak_now)
          .setContentIntent(open)
          .addAction(0, "Stop", stop)
          .setOngoing(true)
          .build()
      } else {
        @Suppress("DEPRECATION")
        Notification.Builder(this)
          .setContentTitle("Riya AI শুনছেন")
          .setContentText("Always listening mode ON")
          .setSmallIcon(android.R.drawable.ic_btn_speak_now)
          .setContentIntent(open)
          .setOngoing(true)
          .build()
      }
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(1, n)
    }

    AccessibilityStatusMonitor.startListening(this)
    try {
      Keep.ensureAccessibility(this)
    } catch (_: Exception) {}

    if (wl == null) {
      try {
        wl =
          (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "riya_ai:live")
            .apply {
              setReferenceCounted(false)
              acquire()
            }
      } catch (_: Exception) {}
    }

    if (live == null) {
      live =
        GeminiLive(applicationContext).also {
          RiyaState.live = it
          it.start()
        }
      RiyaState.running.value = true
    }
    return START_STICKY
  }

  override fun onDestroy() {
    try {
      wl?.release()
    } catch (_: Exception) {}
    wl = null
    live?.stop()
    live = null
    RiyaState.live = null
    RiyaState.running.value = false
    RiyaState.status.value = "Tap to speak"
    super.onDestroy()
  }
}
