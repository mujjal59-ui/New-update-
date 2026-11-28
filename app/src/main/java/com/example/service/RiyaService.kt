package com.example.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
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

class RiyaService : Service() {
  private var live: GeminiLive? = null
  private var wl: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null
  private val serviceScope =
    CoroutineScope(
      SupervisorJob() +
        Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, _ -> }
    )

  override fun onBind(i: Intent?): IBinder? = null

  companion object {
    const val ACTION_SYNC_CAMERA = "com.example.service.SYNC_CAMERA"
    @Volatile var pausedForVoiceMatch: Boolean = false

    fun syncCameraState(context: Context) {
      val appCtx = context.applicationContext
      if (!RiyaState.running.value) {
        LxrCameraVisionEngine.stopBackgroundCamera()
        return
      }
      LxrCameraVisionEngine.ensureBackgroundCaptureIfActive(appCtx)
      try {
        val intent = Intent(appCtx, RiyaService::class.java).setAction(ACTION_SYNC_CAMERA)
        ContextCompat.startForegroundService(appCtx, intent)
      } catch (_: Throwable) {}
    }
  }

  private fun computeForegroundType(allowCamera: Boolean = true): Int {
    var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    val hasFine =
      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val hasCoarse =
      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (Build.VERSION.SDK_INT >= 29 && (hasFine || hasCoarse)) {
      fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }
    if (allowCamera &&
      Build.VERSION.SDK_INT >= 34 &&
      RiyaState.visionCameraActive.value &&
      LxrCameraVisionEngine.hasCameraPermission(this)
    ) {
      fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    }
    return fgsType
  }

  private fun buildLiveNotification(): Notification {
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
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, "lxr_live")
        .setContentTitle("LXR--AI ২৪/৭ সক্রিয়")
        .setContentText(
          if (RiyaState.visionCameraActive.value) "ভিশন ক্যামেরা, মাইক ও স্ক্রিন রিডিং ব্যাকগ্রাউন্ডে চালু"
          else "স্ক্রিন লক/বন্ধ থাকলেও শুনছে ও স্ক্রিন পড়ছে"
        )
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentIntent(open)
        .addAction(0, "Stop", stop)
        .setOngoing(true)
        .build()
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
        .setContentTitle("LXR--AI ২৪/৭ সক্রিয়")
        .setContentText("Always listening & Accessibility Guard ON")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentIntent(open)
        .setOngoing(true)
        .build()
    }
  }

  private fun promoteForegroundSafely(notification: Notification) {
    if (Build.VERSION.SDK_INT >= 29) {
      try {
        startForeground(1, notification, computeForegroundType(allowCamera = true))
      } catch (_: SecurityException) {
        try {
          startForeground(1, notification, computeForegroundType(allowCamera = false))
        } catch (_: Throwable) {
          startForeground(1, notification)
        }
      }
    } else {
      startForeground(1, notification)
    }
  }

  override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
    if (i?.action == "STOP") {
      Store.setUserStoppedService(applicationContext, true)
      RiyaState.setVisionCameraActive(false)
      LxrCameraVisionEngine.stopAll()
      stopSelf()
      return START_NOT_STICKY
    }
    if (i?.action == ACTION_SYNC_CAMERA) {
      promoteForegroundSafely(buildLiveNotification())
      LxrCameraVisionEngine.ensureBackgroundCaptureIfActive(applicationContext)
      return START_STICKY
    }
    Store.setUserStoppedService(applicationContext, false)
    pausedForVoiceMatch = false
    ReplyPolicyScheduler.syncFromStore(applicationContext, force = true)
    RiyaState.replyDelaySeconds.value = Store.replyDelaySeconds(applicationContext)
    RiyaState.replyLimitMax.value = Store.replyLimitMax(applicationContext)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      getSystemService(NotificationManager::class.java)
        .createNotificationChannel(
          NotificationChannel("lxr_live", "LXR--AI Live", NotificationManager.IMPORTANCE_LOW)
        )
    }
    promoteForegroundSafely(buildLiveNotification())
    LxrCameraVisionEngine.ensureBackgroundCaptureIfActive(applicationContext)

    LxrCompanionAgent.start(applicationContext)
    LxrBackgroundTaskManager.recoverInterruptedTasks(applicationContext)
    AccessibilityStatusMonitor.startListening(this)
    try {
      Keep.ensureAccessibility(this)
      AccessibilityKeepAliveService.sync(this)
    } catch (_: Exception) {}

    serviceScope.launch {
      while (true) {
        delay(12_000L)
        try {
          Keep.ensureAccessibility(this@RiyaService)
          AccessibilityStatusMonitor.refreshNow(this@RiyaService, speakIfTurnedOff = true)
          if (wl?.isHeld != true) wl?.acquire()
          if (wifiLock?.isHeld != true) wifiLock?.acquire()
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

    if (wifiLock == null) {
      try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode =
          if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
          else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock =
          wm.createWifiLock(mode, "lxr_ai:wifi_keep_alive").apply {
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

  private fun schedulePersistentRestart(delayMs: Long = 900L) {
    if (Store.userStoppedService(applicationContext) || Store.apiKey(applicationContext).isBlank()) return
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
      val triggerAt = SystemClock.elapsedRealtime() + delayMs
      if (alarm != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
          LxrDeviceAndHardwareEngine.canScheduleExactAlarms(applicationContext)
        ) {
          alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else {
          alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
      }
    } catch (_: Exception) {}
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    AccessibilityKeepAliveService.scheduleRestart(applicationContext, delayMs = 600L)
    AccessibilityKeepAliveService.sync(applicationContext)
    schedulePersistentRestart(delayMs = 900L)
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    val shouldStayAlive = !Store.userStoppedService(applicationContext) && !pausedForVoiceMatch
    try {
      serviceScope.cancel()
    } catch (_: Throwable) {}
    try {
      wl?.release()
    } catch (_: Throwable) {}
    wl = null
    try {
      wifiLock?.release()
    } catch (_: Throwable) {}
    wifiLock = null
    try {
      live?.stop()
    } catch (_: Throwable) {}
    live = null
    RiyaState.live = null
    RiyaState.running.value = false
    RiyaState.setVisionCameraActive(false)
    LxrCameraVisionEngine.stopAll()
    RiyaState.activeWorkLabel.value = null
    RiyaState.status.value = "Tap to speak"
    LxrFloatingOverlayManager.hide(applicationContext)
    if (shouldStayAlive) {
      schedulePersistentRestart(delayMs = 1100L)
    }
    super.onDestroy()
  }
}
