package com.example

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Rect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaPersona
import com.example.data.Store
import com.example.service.FormFieldDescriptor
import com.example.service.LxrAudioNoiseSuppressor
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.LxrSocialAndBrowserAgent
import com.example.service.UnlockPolicyGuard
import com.example.ui.MemPage
import com.example.ui.PermPage
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class LxrV229UpdateTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun memoryRetention180DaysAndSelectiveDeletionWork() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Store.clear(ctx)

    val now = 1_790_000_000_000L
    val dayMs = 86_400_000L
    Store.rememberAt(ctx, "FACT: 185 দিন আগের পুরনো কথা", now - 185L * dayMs)
    Store.rememberAt(ctx, "FACT: বসের ঢাকার মিটিং ১৫০ দিন আগে হয়েছিল", now - 150L * dayMs)
    Store.rememberAt(ctx, "Boss: আমার প্রিয় কফি ব্ল্যাক কফি", now - 2L * dayMs)

    val pruned = Store.pruneOlderThanDays(ctx, days = Store.RETENTION_DAYS, nowMs = now)
    assertEquals(1, pruned)
    assertEquals(180, Store.RETENTION_DAYS)
    assertEquals(64L * 1024 * 1024, Store.CAP)

    val recall150 = Store.searchMemory(ctx, "ঢাকার মিটিং", nowMs = now)
    assertTrue(recall150.contains("১৫০ দিন"))

    val (removedCount, removedLines) =
      Store.deleteMemoryEntries(ctx, "ব্ল্যাক কফি", nowMs = now)
    assertEquals(1, removedCount)
    assertTrue(removedLines.first().contains("ব্ল্যাক কফি"))

    val remaining = Store.recent(ctx, 4000)
    assertFalse(remaining.contains("ব্ল্যাক কফি"))
    assertTrue(remaining.contains("ঢাকার মিটিং"))
    Store.clear(ctx)
  }

  @Test
  fun userStoppedServiceFlagDefaultsFalseAndPersistsExplicitStop() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Store.setUserStoppedService(ctx, false)
    assertFalse(Store.userStoppedService(ctx))

    Store.setUserStoppedService(ctx, true)
    assertTrue(Store.userStoppedService(ctx))

    Store.setUserStoppedService(ctx, false)
    assertFalse(Store.userStoppedService(ctx))
  }

  @Test
  fun noiseSuppressorRejectsHornsAndChirpsWhilePassingBluetoothWhispers() {
    val suppressor = LxrAudioNoiseSuppressor(sampleRate = 16000)

    // 1. Sustained tonal vehicle horn (~900 Hz pure loud sinusoid) across 3 consecutive 40ms frames
    val hornPcm = synthesizeSinePcm(freqHz = 900.0, amplitude = 1600.0, samples = 640)
    suppressor.processFrameInPlace(hornPcm.copyOf(), hornPcm.size, bluetoothMode = false, whisperBoostOn = true)
    val hornFrame2 =
      suppressor.processFrameInPlace(hornPcm.copyOf(), hornPcm.size, bluetoothMode = false, whisperBoostOn = true)
    assertFalse("Sustained tonal car horn must NOT trigger speech", hornFrame2.isSpeechActive)

    // 2. Shrill high-frequency bird chirp / squeal (~4200 Hz)
    suppressor.reset(40.0)
    val chirpPcm = synthesizeSinePcm(freqHz = 4200.0, amplitude = 1100.0, samples = 640)
    val chirpRes =
      suppressor.processFrameInPlace(chirpPcm.copyOf(), chirpPcm.size, bluetoothMode = true, whisperBoostOn = true)
    assertFalse("High-frequency chirp/hiss must be rejected", chirpRes.isSpeechActive)

    // 3. Soft organic Boss whisper on Bluetooth headset (~220 Hz + ~780 Hz modulated speech harmonics)
    suppressor.reset(18.0)
    val whisperPcm = synthesizeSpeechLikeWhisperPcm(samples = 640)
    suppressor.processFrameInPlace(whisperPcm.copyOf(), whisperPcm.size, bluetoothMode = true, whisperBoostOn = true)
    val whisperRes =
      suppressor.processFrameInPlace(whisperPcm.copyOf(), whisperPcm.size, bluetoothMode = true, whisperBoostOn = true)
    assertTrue("Bluetooth whisper must be detected cleanly", whisperRes.isSpeechActive)
  }

  @Test
  fun hardwareHelpersAndPermissionGuardedQueriesAreCrashFree() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()

    val diag = LxrDeviceAndHardwareEngine.getHardwareAndSystemDiagnostics(ctx)
    assertTrue(diag.contains("RAM:"))
    assertTrue(diag.contains("অপারেটিং সিস্টেম"))

    assertEquals(15, LxrDeviceAndHardwareEngine.computeScaledVolumeIndex(100, 15))
    assertEquals(8, LxrDeviceAndHardwareEngine.computeScaledVolumeIndex(50, 15))
    assertEquals(0, LxrDeviceAndHardwareEngine.computeScaledVolumeIndex(-5, 15))

    assertEquals(255, LxrDeviceAndHardwareEngine.computeBrightnessValue(100))
    assertEquals(128, LxrDeviceAndHardwareEngine.computeBrightnessValue(50))

    val navWalk = LxrDeviceAndHardwareEngine.buildNavigationUri("Gulshan 2 Dhaka", "walking").toString()
    assertTrue(navWalk.startsWith("google.navigation:q="))
    assertTrue(navWalk.contains("&mode=w"))

    // Ungranted runtime permissions must return polite guidance strings instead of SecurityException
    val locReport = LxrDeviceAndHardwareEngine.getLiveLocationReport(ctx)
    assertTrue(locReport.contains("Location"))
    val callLogReport = LxrDeviceAndHardwareEngine.getCallLogSummary(ctx)
    assertTrue(callLogReport.contains("READ_CALL_LOG"))
  }

  @Test
  fun launcherAndFormMatchersResolveTargetAccurately() {
    fun fakeResolve(pkg: String, cls: String): ResolveInfo =
      ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
          packageName = pkg
          name = cls
        }
      }

    val apps =
      listOf(
        "Chrome" to fakeResolve("com.android.chrome", "Main"),
        "Instagram" to fakeResolve("com.instagram.android", "MainTab"),
        "bKash" to fakeResolve("com.bKash.customerapp", "Splash"),
      )

    val foundIg = LxrSocialAndBrowserAgent.findMatchingLauncherActivity("ইনস্টাগ্রাম অ্যাপ খোলো", apps)
    assertNotNull(foundIg)
    assertEquals("com.instagram.android", foundIg?.activityInfo?.packageName)

    val foundBkash = LxrSocialAndBrowserAgent.findMatchingLauncherActivity("bkash", apps)
    assertEquals("com.bKash.customerapp", foundBkash?.activityInfo?.packageName)

    val fields =
      listOf(
        FormFieldDescriptor(1, "Full Name", "Enter your name", "", "et_name", false, Rect(0, 0, 100, 50)),
        FormFieldDescriptor(2, "Phone Number", "017...", "", "et_phone", false, Rect(0, 60, 100, 110)),
        FormFieldDescriptor(3, "Email Address", "user@mail.com", "", "et_email", false, Rect(0, 120, 100, 170)),
      )
    assertEquals(0, LxrSocialAndBrowserAgent.matchBestFieldIndex(fields, "name"))
    assertEquals(1, LxrSocialAndBrowserAgent.matchBestFieldIndex(fields, "2"))
    assertEquals(2, LxrSocialAndBrowserAgent.matchBestFieldIndex(fields, "email"))
  }

  @Test
  fun unlockPolicyAndPersonaPreserveV229Contracts() {
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("get_device_hardware_info", true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("get_live_location", true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("get_contacts_summary", true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("get_call_log_summary", true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("delete_memory_entry", true))
    assertFalse(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("inspect_form_fields", true))
    assertTrue(UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool("open_navigation_directions", true))

    assertTrue(RiyaPersona.TEXT.contains("আমি LxR"))
    assertTrue(RiyaPersona.TEXT.contains("LAX"))
    assertTrue(RiyaPersona.TEXT.contains("v2.43.0"))
    assertTrue(RiyaPersona.TEXT.contains("১৮০ দিন"))
    assertTrue(RiyaPersona.TEXT.contains("delete_memory_entry"))
    assertTrue(RiyaPersona.TEXT.contains("3D Studio"))
    assertTrue(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("Episode 01"))
  }

  @Test
  fun memPageShows180DayHeaderAndConfirmationDialogBeforeDeleting() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Store.clear(ctx)
    Store.remember(ctx, "Boss: টেস্ট মেমোরি লাইন")

    composeRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        MemPage(back = {}, onNavigate = {})
      }
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithText("Memory (১৮০ দিন / ৬ মাস)").assertIsDisplayed()
    composeRule.onNodeWithTag("memory_clear_button").performClick()
    composeRule.onNodeWithTag("memory_clear_confirm_dialog").assertIsDisplayed()

    // Dismissing dialog preserves saved memory
    composeRule.onNodeWithTag("memory_cancel_delete_button").performClick()
    assertTrue(Store.recent(ctx, 1000).contains("টেস্ট মেমোরি লাইন"))

    // Confirming deletion clears saved memory
    composeRule.onNodeWithTag("memory_clear_button").performClick()
    composeRule.onNodeWithTag("memory_confirm_delete_button").performClick()
    composeRule.waitForIdle()
    Thread.sleep(250L)
    assertEquals("", Store.recent(ctx, 1000))
  }

  @Test
  fun permPageDisplaysLocationAndCallLogPermissions() {
    composeRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        PermPage(back = {}, onNavigate = {})
      }
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithTag("perm_list").performScrollToNode(hasText("Call Logs"))
    composeRule.onNodeWithText("Call Logs").assertIsDisplayed()
    composeRule.onNodeWithTag("perm_list").performScrollToNode(hasText("Location (GPS)"))
    composeRule.onNodeWithText("Location (GPS)").assertIsDisplayed()
  }

  private fun synthesizeSinePcm(freqHz: Double, amplitude: Double, samples: Int): ByteArray {
    val out = ByteArray(samples * 2)
    for (i in 0 until samples) {
      val v = (amplitude * sin(2.0 * PI * freqHz * i / 16000.0)).toInt().coerceIn(-32767, 32767)
      out[i * 2] = (v and 0xFF).toByte()
      out[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
    }
    return out
  }

  private fun synthesizeSpeechLikeWhisperPcm(samples: Int): ByteArray {
    val out = ByteArray(samples * 2)
    for (i in 0 until samples) {
      val envelope = 0.65 + 0.35 * sin(2.0 * PI * 14.0 * i / 16000.0)
      val raw =
        envelope *
          (90.0 * sin(2.0 * PI * 210.0 * i / 16000.0) +
            55.0 * sin(2.0 * PI * 640.0 * i / 16000.0) +
            32.0 * sin(2.0 * PI * 1450.0 * i / 16000.0))
      val v = raw.toInt().coerceIn(-32767, 32767)
      out[i * 2] = (v and 0xFF).toByte()
      out[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
    }
    return out
  }
}
