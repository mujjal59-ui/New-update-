package com.example.service

import android.app.AlarmManager
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
import android.os.SystemClock
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

class RiyaService : Service() {
  private var live: GeminiLive? = null
  private var wl: PowerManager.WakeLock? = null
  private val serviceScope =
    CoroutineScope(
      SupervisorJob() +
        Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, _ -> }
    )

  override fun onBind(i: Intent?): IBinder? = null

  override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
    if (i?.action == "STOP") {
      stopSelf()
      return START_NOT_STICKY
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      getSystemService(NotificationManager::class.java)
        .createNotificationChannel(
          NotificationChannel("lxr_live", "LXR--AI Live", NotificationManager.IMPORTANCE_LOW)
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
        Notification.Builder(this, "lxr_live")
          .setContentTitle("LXR--AI শুনছেন")
          .setContentText("Always listening & Accessibility Guard ON")
          .setSmallIcon(android.R.drawable.ic_btn_speak_now)
          .setContentIntent(open)
          .addAction(0, "Stop", stop)
          .setOngoing(true)
          .build()
      } else {
        @Suppress("DEPRECATION")
        Notification.Builder(this)
          .setContentTitle("LXR--AI শুনছেন")
          .setContentText("Always listening & Accessibility Guard ON")
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

    // Keep-alive watchdog so Android 16 / Infinix XOS does not silently drop Accessibility
    serviceScope.launch {
      while (true) {
        delay(15_000L)
        try {
          Keep.ensureAccessibility(this@RiyaService)
          AccessibilityStatusMonitor.refreshNow(this@RiyaService, speakIfTurnedOff = true)
        } catch (_: Exception) {}
      }
    }

    if (wl == null) {
      try {
        wl =
          (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lxr_ai:live")
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

  override fun onTaskRemoved(rootIntent: Intent?) {
    if (Store.always(this) && Store.apiKey(this).isNotBlank()) {
      try {
        val restartIntent = Intent(applicationContext, RiyaService::class.java)
        val pi =
          PendingIntent.getService(
            applicationContext,
            202,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
          )
        val alarm = getSystemService(ALARM_SERVICE) as? AlarmManager
        alarm?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1200L, pi)
      } catch (_: Exception) {}
    }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    try {
      serviceScope.cancel()
    } catch (_: Throwable) {}
    try {
      wl?.release()
    } catch (_: Throwable) {}
    wl = null
    try {
      live?.stop()
    } catch (_: Throwable) {}
    live = null
    RiyaState.live = null
    RiyaState.running.value = false
    RiyaState.status.value = "Tap to speak"
    super.onDestroy()
  }
}
