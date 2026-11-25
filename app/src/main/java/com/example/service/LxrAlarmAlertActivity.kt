package com.example.service

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen / lockscreen-capable Alarm & Reminder alert screen triggered directly by
 * LxrReminderReceiver so alarms actually ring, vibrate, wake the screen, and can be stopped
 * or snoozed directly on the phone.
 */
class LxrAlarmAlertActivity : ComponentActivity() {
  companion object {
    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_TITLE = "extra_alarm_title"
    const val EXTRA_NOTIF_ID = "extra_notif_id"

    private val lock = Any()
    @Volatile private var activeRingtone: Ringtone? = null
    @Volatile private var activeVibrator: Vibrator? = null

    fun startRingtoneAndVibration(ctx: Context) {
      synchronized(lock) {
        stopActiveRingtoneLocked()
        try {
          val alarmUri =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
              ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
              ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
          if (alarmUri != null) {
            val rt = RingtoneManager.getRingtone(ctx.applicationContext, alarmUri)
            if (rt != null) {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                rt.isLooping = true
              }
              rt.audioAttributes =
                AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_ALARM)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                  .build()
              rt.play()
              activeRingtone = rt
            }
          }
        } catch (_: Throwable) {}

        try {
          val vib =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
              vm?.defaultVibrator
            } else {
              @Suppress("DEPRECATION")
              ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
          if (vib?.hasVibrator() == true) {
            val pattern = longArrayOf(0L, 650L, 450L, 650L, 450L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
              @Suppress("DEPRECATION")
              vib.vibrate(pattern, 0)
            }
            activeVibrator = vib
          }
        } catch (_: Throwable) {}
      }
    }

    fun stopActiveRingtone() {
      synchronized(lock) {
        stopActiveRingtoneLocked()
      }
    }

    private fun stopActiveRingtoneLocked() {
      try {
        activeRingtone?.stop()
      } catch (_: Throwable) {}
      activeRingtone = null
      try {
        activeVibrator?.cancel()
      } catch (_: Throwable) {}
      activeVibrator = null
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
      runCatching {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        km?.requestDismissKeyguard(this, null)
      }
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
      )
    }

    val title = intent?.getStringExtra(EXTRA_TITLE)?.trim()?.ifBlank { null } ?: "বসের অ্যালার্ম / রিমাইন্ডার"
    val alarmId = intent?.getStringExtra(EXTRA_ALARM_ID).orEmpty()
    val notifId = intent?.getIntExtra(EXTRA_NOTIF_ID, -1) ?: -1

    setContent {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(listOf(Color(0xFF0A0D18), Color(0xFF141126), Color(0xFF090B12)))
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Box(
            modifier =
              Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Color(0xFFF59E0B).copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Filled.Alarm,
              contentDescription = "Alarm Alert",
              tint = Color(0xFFFBBF24),
              modifier = Modifier.size(48.dp),
            )
          }
          Spacer(Modifier.height(20.dp))
          Text(
            text = "LXR--AI অ্যালার্ম ও রিমাইন্ডার",
            color = Color(0xFFFBBF24),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(10.dp))
          Text(
            text = title,
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("alarm_alert_title"),
          )
          Spacer(Modifier.height(32.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            OutlinedButton(
              onClick = {
                dismissAlertUi(notifId)
                LxrDeviceAndHardwareEngine.scheduleExactReminder(
                  ctx = applicationContext,
                  title = title,
                  delaySeconds = 300L,
                  mode = "reminder",
                )
                finish()
              },
              shape = RoundedCornerShape(16.dp),
              modifier = Modifier.weight(1f).height(54.dp).testTag("alarm_snooze_btn"),
            ) {
              Icon(Icons.Filled.Snooze, contentDescription = null, tint = Color(0xFF93C5FD))
              Spacer(Modifier.size(6.dp))
              Text("৫ মিনিট পর", color = Color(0xFFDBEAFE), fontSize = 14.sp)
            }
            Button(
              onClick = {
                if (alarmId.isNotBlank()) {
                  LxrDeviceAndHardwareEngine.markAlarmStatus(applicationContext, alarmId, "DISMISSED")
                }
                dismissAlertUi(notifId)
                finish()
              },
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
              shape = RoundedCornerShape(16.dp),
              modifier = Modifier.weight(1f).height(54.dp).testTag("alarm_stop_btn"),
            ) {
              Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
              Spacer(Modifier.size(6.dp))
              Text("বন্ধ করো", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }
  }

  private fun dismissAlertUi(notifId: Int) {
    stopActiveRingtone()
    RiyaVoiceManager.stopSpeaking()
    if (notifId >= 0) {
      runCatching {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(notifId)
      }
    }
  }
}
