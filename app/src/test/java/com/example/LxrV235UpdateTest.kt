package com.example

import android.app.AlarmManager
import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.ai.RiyaPersona
import com.example.data.ReplyDelayOption
import com.example.service.AudioEchoGuard
import com.example.service.InstalledAppEntry
import com.example.service.LiveVadConfig
import com.example.service.LockVaultManager
import com.example.service.LxrAppDiscoveryEngine
import com.example.service.LxrAudioNoiseSuppressor
import com.example.service.LxrDeviceAdminReceiver
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.LxrProactiveCheckInPolicy
import com.example.service.LxrReminderReceiver
import com.example.service.LxrSpeechInterruptGuard
import com.example.service.LxrToolWorkDescriber
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LxrV235UpdateTest {

  @Test
  fun appDiscoveryResolvesBengaliEnglishAndOemAppsWithoutCrossMatching() {
    val installed =
      listOf(
        InstalledAppEntry("AI Gallery", "com.transsion.camera.gallery", "GalleryActivity"),
        InstalledAppEntry("Camera", "com.transsion.camera", "CameraActivity"),
        InstalledAppEntry("bKash", "com.bKash.customerapp", "SplashActivity"),
        InstalledAppEntry("Nagad", "com.konasl.nagad", "SplashActivity"),
        InstalledAppEntry("WhatsApp", "com.whatsapp", "Main"),
        InstalledAppEntry("Facebook", "com.facebook.katana", "LoginActivity"),
        InstalledAppEntry("Messenger", "com.facebook.orca", "MainActivity"),
        InstalledAppEntry("CapCut", "com.lemon.lvoverseas", "SplashActivity"),
      )

    assertEquals(
      "com.transsion.camera",
      LxrAppDiscoveryEngine.matchInstalledEntry("ক্যামেরা অ্যাপ খোলো", installed)?.packageName,
    )
    assertEquals(
      "com.bKash.customerapp",
      LxrAppDiscoveryEngine.matchInstalledEntry("বিকাশ ওপেন করো", installed)?.packageName,
    )
    assertEquals(
      "com.konasl.nagad",
      LxrAppDiscoveryEngine.matchInstalledEntry("নগদ", installed)?.packageName,
    )
    assertEquals(
      "com.facebook.orca",
      LxrAppDiscoveryEngine.matchInstalledEntry("মেসেঞ্জার", installed)?.packageName,
    )
    assertEquals(
      "com.facebook.katana",
      LxrAppDiscoveryEngine.matchInstalledEntry("ফেসবুক", installed)?.packageName,
    )
    assertEquals(
      "com.lemon.lvoverseas",
      LxrAppDiscoveryEngine.matchInstalledEntry("ক্যাপকাট", installed)?.packageName,
    )
    // Strict Facebook <-> Messenger isolation when only the other is available
    val onlyFb = listOf(InstalledAppEntry("Facebook", "com.facebook.katana", "LoginActivity"))
    assertEquals(null, LxrAppDiscoveryEngine.matchInstalledEntry("মেসেঞ্জার", onlyFb))
  }

  @Test
  fun deviceAdminAndExactAlarmManifestReceiversAndSelfDiagnosisWork() {
    val ctx = ApplicationProviderContext()
    val pkgFlags =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_PERMISSIONS
      else @Suppress("DEPRECATION") PackageManager.GET_PERMISSIONS
    val perms = ctx.packageManager.getPackageInfo(ctx.packageName, pkgFlags).requestedPermissions?.toSet().orEmpty()
    assertTrue(
      "Manifest must declare SCHEDULE_EXACT_ALARM for Alarms & reminders screen",
      perms.contains("android.permission.SCHEDULE_EXACT_ALARM"),
    )

    // Verify LxrDeviceAdminReceiver & LxrReminderReceiver are registered in AndroidManifest
    val adminReceivers =
      ctx.packageManager.queryBroadcastReceivers(
        Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED),
        PackageManager.MATCH_ALL,
      )
    assertTrue(
      "LxrDeviceAdminReceiver must handle ACTION_DEVICE_ADMIN_ENABLED",
      adminReceivers.any { it.activityInfo.name == LxrDeviceAdminReceiver::class.java.name },
    )
    val adminCn = ComponentName(ctx, LxrDeviceAdminReceiver::class.java)
    assertNotNull(ctx.packageManager.getReceiverInfo(adminCn, PackageManager.GET_META_DATA))
    val reminderCn = ComponentName(ctx, LxrReminderReceiver::class.java)
    assertNotNull(ctx.packageManager.getReceiverInfo(reminderCn, 0))

    // Self diagnosis includes both Device Admin and Exact Alarms with strict "তুমি"
    val health = LxrDeviceAndHardwareEngine.diagnoseSelfHealth(ctx)
    val keys = health.issues.map { it.key }.toSet()
    assertTrue(keys.contains("device_admin"))
    assertTrue(keys.contains("exact_alarm"))
    assertFalse(health.summaryBn.contains("আপনার"))
    assertTrue(health.summaryBn.contains("তোমার"))

    // Relock & exact reminder helpers never crash cleanly
    assertFalse(LockVaultManager.relockScreen(ctx, null))
    val shadowAlarm = shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    val remMsg = LxrDeviceAndHardwareEngine.scheduleExactReminder(ctx, "পানি খাওয়ার সময়", 90L)
    assertTrue(remMsg.contains("পানি খাওয়ার সময়"))
    assertTrue(shadowAlarm.scheduledAlarms.isNotEmpty())

    assertTrue(LxrToolWorkDescriber.describeBn("list_installed_apps", JSONObject()).contains("অ্যাপ"))
    assertTrue(LxrToolWorkDescriber.describeBn("schedule_exact_reminder", JSONObject()).contains("রিমাইন্ডার"))
  }

  @Test
  fun instantSpeechInterruptZeroDelayAndBluetoothBargeInAreImmediate() {
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("থামো"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("শোনো"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("স্টপ"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("বাই"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("bye"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("stop"))
    assertTrue(LxrSpeechInterruptGuard.containsInterruptCommand("বাই বাই"))
    assertFalse(LxrSpeechInterruptGuard.containsInterruptCommand("আজ আবহাওয়া কেমন"))
    assertFalse(LxrSpeechInterruptGuard.containsInterruptCommand("তোমাকে একটা গান শোনাও"))

    assertEquals(60L, LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.INSTANT))
    assertEquals(180L, LiveVadConfig.effectiveHangoverMs(ReplyDelayOption.INSTANT))
    assertTrue(
      LiveVadConfig.effectiveHangoverMs(ReplyDelayOption.INSTANT) <
        LiveVadConfig.effectiveHangoverMs(ReplyDelayOption.FAST),
    )

    // Bluetooth mid-speech barge-in MUST unmute when Boss speaks clearly above downlink bleed OR VoiceMatch
    assertFalse(
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = true,
        nowMs = 10_000L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 10_000L,
        rawRms = 2400.0,
        lastSpeakerOutputRms = 1100.0,
        voiceMatchBargeIn = false,
        consecutiveLoudChunks = 3,
        minDirectBargeInRms = 2200.0,
        directRatio = 1.45,
      ),
    )
    assertFalse(
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = true,
        nowMs = 10_000L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 10_000L,
        rawRms = 960.0,
        lastSpeakerOutputRms = 1200.0,
        voiceMatchBargeIn = true,
        consecutiveLoudChunks = 1,
      ),
    )
  }

  @Test
  fun proactiveCheckInPolicyFiresOnlyWhenUnlockedInteractiveAndQuiet() {
    val start = 1_000_000L
    // Blocked when locked, non-interactive, busy, or too early
    assertFalse(
      LxrProactiveCheckInPolicy.shouldTriggerProactiveCheckIn(
        ready = true,
        stopped = false,
        isExecutingTool = false,
        waitingServerReply = false,
        activeTurnStarted = false,
        isAiSpeaking = false,
        isBossSpeaking = false,
        isInteractive = true,
        isPhoneLocked = true,
        nowMs = start + 60_000L,
        sessionStartMs = start,
        lastBossActivityMs = 0L,
        lastProactiveAtMs = 0L,
        proactiveCountInStretch = 0,
      ),
    )
    assertFalse(
      LxrProactiveCheckInPolicy.shouldTriggerProactiveCheckIn(
        ready = true,
        stopped = false,
        isExecutingTool = false,
        waitingServerReply = false,
        activeTurnStarted = false,
        isAiSpeaking = false,
        isBossSpeaking = false,
        isInteractive = false,
        isPhoneLocked = false,
        nowMs = start + 60_000L,
        sessionStartMs = start,
        lastBossActivityMs = 0L,
        lastProactiveAtMs = 0L,
        proactiveCountInStretch = 0,
      ),
    )
    assertFalse(
      LxrProactiveCheckInPolicy.shouldTriggerProactiveCheckIn(
        ready = true,
        stopped = false,
        isExecutingTool = false,
        waitingServerReply = false,
        activeTurnStarted = false,
        isAiSpeaking = false,
        isBossSpeaking = false,
        isInteractive = true,
        isPhoneLocked = false,
        nowMs = start + 15_000L,
        sessionStartMs = start,
        lastBossActivityMs = 0L,
        lastProactiveAtMs = 0L,
        proactiveCountInStretch = 0,
      ),
    )

    // Allowed after 35s quiet unlocked interactive stretch
    assertTrue(
      LxrProactiveCheckInPolicy.shouldTriggerProactiveCheckIn(
        ready = true,
        stopped = false,
        isExecutingTool = false,
        waitingServerReply = false,
        activeTurnStarted = false,
        isAiSpeaking = false,
        isBossSpeaking = false,
        isInteractive = true,
        isPhoneLocked = false,
        nowMs = start + 40_000L,
        sessionStartMs = start,
        lastBossActivityMs = 0L,
        lastProactiveAtMs = 0L,
        proactiveCountInStretch = 0,
      ),
    )
  }

  @Test
  fun noiseSuppressorPreservesCleanSpeechGainWithoutOverClipping() {
    val suppressor = LxrAudioNoiseSuppressor(sampleRate = 16000)
    // Clear modulated speech (~1400 RMS): should stay active and remain undistorted (cleanRms <= originalRms * 2.0)
    val speechPcm = synthesizeSpeechPcm(samples = 640, scale = 1.0)
    val inRms = pcmRms(speechPcm)
    suppressor.processFrameInPlace(speechPcm.copyOf(), speechPcm.size, bluetoothMode = false, whisperBoostOn = true)
    val res = suppressor.processFrameInPlace(speechPcm.copyOf(), speechPcm.size, bluetoothMode = false, whisperBoostOn = true)
    assertTrue("Clear speech must be detected as active speech", res.isSpeechActive)
    assertTrue("Natural speech must not be over-amplified/distorted (${res.cleanRms} vs $inRms)", res.cleanRms <= inRms * 2.0)
    assertTrue("Speech RMS must remain clearly intelligible", res.cleanRms >= inRms * 0.70)
  }

  @Test
  fun personaEnforcesV235StrictTumiAll15CapabilitiesAndNoLegacyNameLeak() {
    assertTrue(RiyaPersona.TEXT.contains("v2.42.0"))
    assertTrue(RiyaPersona.TEXT.contains("list_installed_apps"))
    assertTrue(RiyaPersona.TEXT.contains("schedule_exact_reminder"))
    assertTrue(RiyaPersona.TEXT.contains("Device Admin"))
    assertTrue(RiyaPersona.TEXT.contains("Alarms & reminders"))
    assertTrue(RiyaPersona.TEXT.contains("স্টপ"))
    assertTrue(RiyaPersona.TEXT.contains("বাই"))
    assertTrue(RiyaPersona.TEXT.contains("মোঃ উজ্জল হোসেন"))
    assertFalse(RiyaPersona.TEXT.contains("riya", ignoreCase = true))
    assertFalse(RiyaPersona.TEXT.contains("priya", ignoreCase = true))
    assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("আপনার"))
    for (num in 1..15) {
      val bnDigits = num.toString().map { ('০'.code + (it - '0')).toChar() }.joinToString("")
      assertTrue(
        "Missing capability number $bnDigits.",
        RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("$bnDigits."),
      )
    }
  }

  private fun ApplicationProviderContext(): Context =
    androidx.test.core.app.ApplicationProvider.getApplicationContext()

  private fun synthesizeSpeechPcm(samples: Int, scale: Double): ByteArray {
    val out = ByteArray(samples * 2)
    for (i in 0 until samples) {
      val env = 0.7 + 0.3 * sin(2.0 * PI * 12.0 * i / 16000.0)
      val raw =
        scale * env *
          (900.0 * sin(2.0 * PI * 210.0 * i / 16000.0) +
            520.0 * sin(2.0 * PI * 640.0 * i / 16000.0) +
            280.0 * sin(2.0 * PI * 1420.0 * i / 16000.0))
      val v = raw.toInt().coerceIn(-32767, 32767)
      out[i * 2] = (v and 0xFF).toByte()
      out[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
    }
    return out
  }

  private fun pcmRms(pcm: ByteArray): Double {
    var sum = 0.0
    var count = 0
    var i = 0
    while (i + 1 < pcm.size) {
      val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
      sum += s * s
      count++
      i += 2
    }
    return if (count > 0) sqrt(sum / count) else 0.0
  }
}
