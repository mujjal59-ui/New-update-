package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.provider.ContactsContract
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaPersona
import com.example.data.ReplyDelayOption
import com.example.data.RiyaState
import com.example.data.Store
import com.example.service.AudioEchoGuard
import com.example.service.InstalledAppEntry
import com.example.service.LiveVadConfig
import com.example.service.LxrAppDiscoveryEngine
import com.example.service.LxrFloatingOverlayManager
import com.example.service.MessageAgent
import java.text.Normalizer
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LxrV236UpdateTest {

  @After
  fun cleanup() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    LxrFloatingOverlayManager.hide(ctx)
    shadowOf(Looper.getMainLooper()).idle()
    RiyaState.activeWorkLabel.value = null
  }

  @Test
  fun floatingOrbSpawnsSingleInstanceOnlyAndAutoDismissesEvenWhenKeepWakeRequested() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Store.setFloatingOrbEnabled(ctx, true)
    ShadowSettings.setCanDrawOverlays(true)

    // Rapid duplicate show()/showForTask()/setScreenReading() calls BEFORE main looper drains must only attach ONE view!
    LxrFloatingOverlayManager.show(ctx, openPermissionIfMissing = false, forceShow = false)
    LxrFloatingOverlayManager.showForTask(ctx, "কাজ করছে: WhatsApp খোলা হচ্ছে", isReadingScreen = true)
    LxrFloatingOverlayManager.setScreenReading(ctx, true)
    LxrFloatingOverlayManager.showForWakeOrMic(ctx)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue("Floating orb must be showing during active task", LxrFloatingOverlayManager.isShowing)
    assertEquals(
      "Must NEVER spawn duplicate floating orbs even across rapid concurrent show calls",
      1,
      LxrFloatingOverlayManager.activeAttachedViewCount(),
    )

    // Completing task schedules hide EVEN if keepShowingForWake = true (no permanently stuck orb!)
    LxrFloatingOverlayManager.completeTaskAndScheduleHide(ctx, hideDelayMs = 600L, keepShowingForWake = true)
    shadowOf(Looper.getMainLooper()).runToEndOfTasks()

    assertFalse("Orb must automatically hide once task completes", LxrFloatingOverlayManager.isShowing)
    assertFalse("Badge state must hide after auto-dismiss", LxrFloatingOverlayManager.orbBadgeState.value.visible)
    assertNull("Active work label must clear after auto-dismiss", RiyaState.activeWorkLabel.value)
    assertEquals("WindowManager must have zero attached orbs after hide", 0, LxrFloatingOverlayManager.activeAttachedViewCount())
  }

  @Test
  fun wakeOrbSchedulesIdleFailsafeAndClearsCleanly() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Store.setFloatingOrbEnabled(ctx, true)
    ShadowSettings.setCanDrawOverlays(true)

    assertTrue(LxrFloatingOverlayManager.showForWakeOrMic(ctx))
    assertTrue("Wake orb must mark badge visible immediately", LxrFloatingOverlayManager.orbBadgeState.value.visible)

    // Draining looper (4200ms idle failsafe + 450ms auto-hide) must auto-dismiss the idle orb cleanly
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
    assertFalse(
      "Wake orb without active task/speech must auto-dismiss via idle failsafe",
      LxrFloatingOverlayManager.isShowing,
    )
    assertFalse("Badge state must hide after idle failsafe", LxrFloatingOverlayManager.orbBadgeState.value.visible)
    assertEquals("Zero attached orbs after idle failsafe", 0, LxrFloatingOverlayManager.activeAttachedViewCount())
  }

  @Test
  fun appDiscoveryNormalizesDecomposedBengaliNfdAndMatchesImoWhatsappMessengerContactsDialer() {
    val installed =
      listOf(
        InstalledAppEntry("WhatsApp", "com.whatsapp", "Main"),
        InstalledAppEntry("imo", "com.imo.android.imoim", "SplashActivity"),
        InstalledAppEntry("Facebook", "com.facebook.katana", "LoginActivity"),
        InstalledAppEntry("Messenger", "com.facebook.orca", "MainActivity"),
        InstalledAppEntry("Contacts", "com.transsion.contacts", "ContactsActivity"),
        InstalledAppEntry("Phone", "com.transsion.dialer", "DialtactsActivity"),
        InstalledAppEntry("Messages", "com.android.mms", "ConversationList"),
      )

    // Decomposed NFD Bengali strings (e.g., য় = য + ়) must normalize via NFC and match accurately
    val nfdWhatsapp = Normalizer.normalize("হোয়াটসঅ্যাপ অ্যাপস খুলে দাও", Normalizer.Form.NFD)
    val nfdDialer = Normalizer.normalize("ডায়ালার অ্যাপ ওপেন করো", Normalizer.Form.NFD)
    val nfdContacts = Normalizer.normalize("কন্টাক্টস অ্যাপ চালু করো", Normalizer.Form.NFD)

    assertEquals("com.whatsapp", LxrAppDiscoveryEngine.matchInstalledEntry(nfdWhatsapp, installed)?.packageName)
    assertEquals("com.imo.android.imoim", LxrAppDiscoveryEngine.matchInstalledEntry("ইমো অ্যাপ খোলো", installed)?.packageName)
    assertEquals("com.imo.android.imoim", LxrAppDiscoveryEngine.matchInstalledEntry("ইমু খুলে দাও", installed)?.packageName)
    assertEquals("com.facebook.orca", LxrAppDiscoveryEngine.matchInstalledEntry("মেসেঞ্জার", installed)?.packageName)
    assertEquals("com.facebook.katana", LxrAppDiscoveryEngine.matchInstalledEntry("ফেসবুক", installed)?.packageName)
    assertEquals("com.transsion.contacts", LxrAppDiscoveryEngine.matchInstalledEntry(nfdContacts, installed)?.packageName)
    assertEquals("com.transsion.dialer", LxrAppDiscoveryEngine.matchInstalledEntry(nfdDialer, installed)?.packageName)
    assertEquals("com.android.mms", LxrAppDiscoveryEngine.matchInstalledEntry("মেসেজ অ্যাপ", installed)?.packageName)

    // Messaging find() must handle NFD Bengali + verb suffixes consistently
    assertEquals("whatsapp", MessageAgent.find(nfdWhatsapp)?.key)
    assertEquals("imo", MessageAgent.find("ইমো অ্যাপস খোলো")?.key)
    assertEquals("messenger", MessageAgent.find("মেসেঞ্জার ওপেন করো")?.key)
  }

  @Test
  fun systemCategoryIntentFallbackResolvesDialerAndContactsWithoutPackageListHardcode() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val shadowPm = shadowOf(ctx.packageManager)

    val dialerCn = ComponentName("com.vendor.customdialer", "com.vendor.customdialer.MainDialer")
    shadowPm.addActivityIfNotPresent(dialerCn)
    shadowPm.addIntentFilterForActivity(
      dialerCn,
      IntentFilter(Intent.ACTION_DIAL).apply { addCategory(Intent.CATEGORY_DEFAULT) },
    )

    val contactsCn = ComponentName("com.vendor.customcontacts", "com.vendor.customcontacts.PeopleActivity")
    shadowPm.addActivityIfNotPresent(contactsCn)
    shadowPm.addIntentFilterForActivity(
      contactsCn,
      IntentFilter(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        try {
          addDataType("vnd.android.cursor.dir/contact")
        } catch (_: Exception) {}
      },
    )

    val dialerResolved = LxrAppDiscoveryEngine.resolveSystemCategoryIntent(ctx, "ডায়ালার অ্যাপ খোলো")
    assertNotNull("Dialer must resolve dynamically via ACTION_DIAL", dialerResolved)
    assertEquals("Phone", dialerResolved!!.first)
    assertEquals("com.vendor.customdialer", dialerResolved.second.component?.packageName)

    // Verify Contacts query also runs cleanly (category fallback or data URI)
    val contactsCatCn = ComponentName("com.vendor.catcontacts", "com.vendor.catcontacts.ListActivity")
    shadowPm.addActivityIfNotPresent(contactsCatCn)
    shadowPm.addIntentFilterForActivity(
      contactsCatCn,
      IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        addCategory(Intent.CATEGORY_APP_CONTACTS)
      },
    )
    val contactsResolved = LxrAppDiscoveryEngine.resolveSystemCategoryIntent(ctx, "কন্টাক্টস")
    assertNotNull(
      "Contacts must resolve via ContactsContract or CATEGORY_APP_CONTACTS (uri=${ContactsContract.Contacts.CONTENT_URI})",
      contactsResolved,
    )
    assertEquals("Contacts", contactsResolved!!.first)
  }

  @Test
  fun audioEchoGuardAndLiveVadPreserveSmoothContinuousSpeechWithoutSelfChop() {
    // Phone Speaker mode with 360ms hardware tail guard must mute low speaker bleed cleanly
    assertTrue(
      "Phone speaker bleed within 360ms tail must be muted so LxR doesn't self-interrupt",
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = false,
        nowMs = 10_250L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = false,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 10_000L,
        phoneTailHoldMs = 360L,
        rawRms = 1100.0,
        lastSpeakerOutputRms = 800.0,
        voiceMatchBargeIn = false,
        consecutiveLoudChunks = 1,
      ),
    )

    // Bluetooth mode with mild handling/downlink noise (< 4200 RMS, not voiceMatched) stays protected
    assertTrue(
      "Mild BT downlink pop during playback must not chop LxR mid-sentence",
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = true,
        nowMs = 10_000L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 10_000L,
        rawRms = 2400.0,
        lastSpeakerOutputRms = 1800.0,
        voiceMatchBargeIn = false,
        consecutiveLoudChunks = 3,
        minDirectBargeInRms = 4200.0,
        directRatio = 1.85,
      ),
    )

    // Verified Boss VoiceMatch still breaks through immediately
    assertFalse(
      AudioEchoGuard.shouldMuteMicWithBargeIn(
        usingBtAudio = true,
        nowMs = 10_000L,
        micMuteUntilMs = 0L,
        isSpeakerAudiblyPlayingNow = true,
        ttsIsSpeaking = false,
        lastAudibleStopMs = 10_000L,
        rawRms = 1200.0,
        lastSpeakerOutputRms = 1000.0,
        voiceMatchBargeIn = true,
        consecutiveLoudChunks = 1,
      ),
    )

    assertEquals(90L, LiveVadConfig.effectiveEndOfTurnFlushMs(ReplyDelayOption.INSTANT))
    assertEquals(180L, LiveVadConfig.effectiveHangoverMs(ReplyDelayOption.INSTANT))
  }

  @Test
  fun personaIncludesV236AndPreservesStrictTumiAnd15Capabilities() {
    assertTrue(RiyaPersona.TEXT.contains("v2.39.0"))
    assertTrue(RiyaPersona.TEXT.contains("মোঃ উজ্জল হোসেন"))
    assertTrue(RiyaPersona.TEXT.contains("MANDATORY \"বস\" + \"তুমি\" RULE"))
    assertFalse(RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("আপনার"))
    for (num in 1..15) {
      val bnDigits = num.toString().map { ('০'.code + (it - '0')).toChar() }.joinToString("")
      assertTrue("Missing capability $bnDigits.", RiyaPersona.CAPABILITIES_A_TO_Z_BN.contains("$bnDigits."))
    }
  }
}
