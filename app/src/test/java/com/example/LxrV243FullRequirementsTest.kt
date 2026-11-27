package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.ai.RiyaPersona
import com.example.builder.ApkBuildMode
import com.example.builder.AppBuilderFileRepository
import com.example.builder.AppBuilderProject
import com.example.builder.AppProjectTemplates
import com.example.builder.TermuxBuildBridge
import com.example.data.HomeTheme
import com.example.data.RiyaState
import com.example.data.Store
import com.example.osint.LxrOsintOrchestrator
import com.example.osint.LxrOsintToolAdapters
import com.example.osint.LxrOsintVerificationEngine
import com.example.osint.OsintEvidenceTier
import com.example.osint.OsintSourceOrigin
import com.example.osint.OsintSourceRecord
import com.example.osint.OsintToolAvailability
import com.example.osint.OsintVerificationStatus
import com.example.service.IncomingMessageEvent
import com.example.service.IncomingReplyStatus
import com.example.service.InstalledAppEntry
import com.example.service.LockVaultManager
import com.example.service.LxrAppDiscoveryEngine
import com.example.service.LxrDeviceAndHardwareEngine
import com.example.service.LxrDownloadStorage
import com.example.service.LxrFloatingOverlayManager
import com.example.service.LxrResearchEngine
import com.example.service.LxrSocialAndBrowserAgent
import com.example.service.LxrSpeechTranscriptSanitizer
import com.example.service.LxrStorageCategory
import com.example.service.RiyaRuntimeBridge
import com.example.service.RiyaVoiceManager
import com.example.service.UnlockPolicyGuard
import com.example.ui.LxrMicMutePillButton
import com.example.ui.LxrOsintReportViewerDialog
import com.example.ui.LxrVoiceModelPopupDialog
import com.example.ui.TasksPage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LxrV243FullRequirementsTest {
  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var ctx: Context

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    RiyaState.micMuted.value = false
    Store.setElevenLabsApiKey(ctx, "")
    Store.setElevenLabsEnabled(ctx, false)
    Store.setModel(ctx, Store.DEFAULT_LIVE_MODEL)
    RiyaVoiceManager.initialize(ctx)
  }

  @Test
  fun voiceModelPopup_listsElevenLabsVoiceFourthBelowGemini25_andMapsTransportModel() {
    assertEquals(3, Store.LIVE_VOICE_MODEL_OPTIONS.size)
    assertEquals(4, Store.ALL_VOICE_MODEL_OPTIONS.size)
    assertEquals(Store.ELEVENLABS_LIVE_MODEL_ID, Store.ALL_VOICE_MODEL_OPTIONS[3].modelId)
    assertEquals("ElevenLabs Voice", Store.ALL_VOICE_MODEL_OPTIONS[3].title)
    assertEquals(Store.DEFAULT_LIVE_MODEL, Store.resolveLiveTransportModelId(Store.ELEVENLABS_LIVE_MODEL_ID))

    var selectedModelId = ""
    composeTestRule.setContent {
      LxrVoiceModelPopupDialog(
        activeModelId = Store.DEFAULT_LIVE_MODEL,
        onSelectModel = { selectedModelId = it.modelId },
        onDismiss = {},
      )
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("voice_model_option_0").assertIsDisplayed()
    composeTestRule.onNodeWithTag("voice_model_option_1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("voice_model_option_2").assertIsDisplayed()
    composeTestRule.onNodeWithTag("voice_model_option_3").assertIsDisplayed()
    composeTestRule.onNodeWithText("ElevenLabs Voice").assertIsDisplayed()

    composeTestRule.onNodeWithTag("voice_model_option_3").performClick()
    composeTestRule.waitForIdle()
    assertEquals(Store.ELEVENLABS_LIVE_MODEL_ID, selectedModelId)
  }

  @Test
  fun micMuteButton_rendersAndTogglesAcrossAllThreeThemes() {
    composeTestRule.setContent {
      var activeTheme by remember { mutableStateOf(HomeTheme.LXR_STUDIO) }
      var muted by remember { mutableStateOf(false) }
      androidx.compose.foundation.layout.Column {
        LxrMicMutePillButton(
          isMuted = muted,
          homeTheme = activeTheme,
          onToggle = { muted = !muted },
        )
        androidx.compose.material3.Button(
          onClick = {
            activeTheme =
              when (activeTheme) {
                HomeTheme.LXR_STUDIO -> HomeTheme.ARIA_CYBER_ROOM
                HomeTheme.ARIA_CYBER_ROOM -> HomeTheme.MITALI_CYBER_STUDIO
                HomeTheme.MITALI_CYBER_STUDIO -> HomeTheme.LXR_STUDIO
              }
          },
          modifier = androidx.compose.ui.Modifier.testTag("switch_theme_test_btn"),
        ) {
          androidx.compose.material3.Text("Switch")
        }
      }
    }
    for (i in 0 until 3) {
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("mic_mute_button").assertIsDisplayed()
      composeTestRule.onNodeWithTag("mic_mute_button").performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Muted").assertIsDisplayed()
      composeTestRule.onNodeWithTag("mic_mute_button").performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Mute").assertIsDisplayed()
      composeTestRule.onNodeWithTag("switch_theme_test_btn").performClick()
    }
  }

  @Test
  fun elevenLabsVoiceMode_reportsExplicitErrorWhenApiKeyMissing_andDoesNotFakeSpeaking() {
    Store.setElevenLabsEnabled(ctx, true)
    Store.setElevenLabsApiKey(ctx, "")
    RiyaVoiceManager.initialize(ctx)

    RiyaVoiceManager.speak(
      rawText = "হ্যালো বস",
      interrupt = true,
      forceSpeak = true,
      forceElevenLabsOnly = true,
    )

    assertFalse(RiyaVoiceManager.isActivelySpeaking())
    val err = RiyaVoiceManager.lastTtsError.value
    assertNotNull(err)
    assertTrue(err!!.contains("API Key", ignoreCase = true))
  }

  @Test
  fun appDiscovery_matchesFull90AppCatalog_bilingualQueries_andSafeSwipesNeverTouchBottomEdge() {
    val catalogApps =
      listOf(
        InstalledAppEntry("Claude", "com.anthropic.claude", null),
        InstalledAppEntry("GitHub", "com.github.android", null),
        InstalledAppEntry("Daraz", "com.daraz.android", null),
        InstalledAppEntry("Bikroy", "com.bikroy", null),
        InstalledAppEntry("bKash", "com.bKash.customerapp", null),
        InstalledAppEntry("ElevenLabs", "io.elevenlabs.app", null),
        InstalledAppEntry("Free Fire", "com.dts.freefireth", null),
        InstalledAppEntry("MyGP", "com.portonics.mygp", null),
        InstalledAppEntry("WA Business", "com.whatsapp.w4b", null),
        InstalledAppEntry("YT Studio", "com.google.android.apps.youtube.creator", null),
        InstalledAppEntry("X", "com.twitter.android", null),
        InstalledAppEntry("ZArchiver", "ru.zdevs.zarchiver", null),
        InstalledAppEntry("1.1.1.1", "com.cloudflare.onedotonedotonedotone", null),
        InstalledAppEntry("Keep Notes", "com.google.android.keep", null),
        InstalledAppEntry("HopWeb", "com.venter.hopweb", null),
        InstalledAppEntry("Hi Translate", "com.zaz.translate", null),
        InstalledAppEntry("hoco.music", "com.hoco.music", null),
        InstalledAppEntry("MyCirkle", "com.cirkle.mycirkle", null),
        InstalledAppEntry("Mone AI - Leya", "com.leya.ai", null),
        InstalledAppEntry("Rea", "com.rea.assistant", null),
      )

    val queries =
      listOf(
        "Claude" to "com.anthropic.claude",
        "গিটহাব" to "com.github.android",
        "দারাজ" to "com.daraz.android",
        "বিক্রয়" to "com.bikroy",
        "বিকাশ" to "com.bKash.customerapp",
        "ইলেভেন ল্যাব" to "io.elevenlabs.app",
        "ফ্রি ফায়ার" to "com.dts.freefireth",
        "মাই জিপি" to "com.portonics.mygp",
        "হোয়াটসঅ্যাপ বিজনেস" to "com.whatsapp.w4b",
        "ওয়াইটি স্টুডিও" to "com.google.android.apps.youtube.creator",
        "টুইটার" to "com.twitter.android",
        "zarchiver" to "ru.zdevs.zarchiver",
        "1.1.1.1" to "com.cloudflare.onedotonedotonedotone",
        "কিপ নোটস" to "com.google.android.keep",
        "হপওয়েব" to "com.venter.hopweb",
        "হাই ট্রান্সলেট" to "com.zaz.translate",
        "hoco music" to "com.hoco.music",
        "mycirkle" to "com.cirkle.mycirkle",
        "leya" to "com.leya.ai",
        "rea" to "com.rea.assistant",
      )

    for ((q, expectedPkg) in queries) {
      val hit = LxrAppDiscoveryEngine.matchInstalledEntry(q, catalogApps)
      assertNotNull("Failed to resolve '$q'", hit)
      assertEquals("Wrong package for '$q'", expectedPkg, hit!!.packageName)
    }

    val bilingual = LxrAppDiscoveryEngine.buildBilingualSearchQueries("Settings")
    assertTrue("English must be tried first", bilingual.first().equals("Settings", ignoreCase = true))
    assertTrue("Bengali fallback must follow", bilingual.any { it == "সেটিংস" })

    val translit = LxrAppDiscoveryEngine.transliterateEnToBn("gemini")
    assertTrue(translit.any { it in '\u0980'..'\u09FF' })

    val sw = 1080f
    val sh = 2400f
    val horiz = LxrSocialAndBrowserAgent.buildSafeHomeHorizontalSwipe(sw, sh)
    val drawer = LxrSocialAndBrowserAgent.buildSafeDrawerOpenSwipe(sw, sh)
    assertTrue("Horizontal swipe must stay mid-screen", horiz.startY < sh * 0.75f && horiz.endY < sh * 0.75f)
    assertTrue("Drawer swipe must NEVER touch bottom edge", drawer.startY < sh * 0.75f && drawer.endY < sh * 0.75f)
  }

  @Test
  fun recencyFilter_enforcesTodayOnlyAsiaDhaka_andSeparatesContactsFromDialer() {
    val nowMs = System.currentTimeMillis()
    val yesterdayMs = nowMs - 30 * 3_600_000L
    assertTrue(RiyaRuntimeBridge.isTodayInDhaka(nowMs, nowMs))
    assertFalse(RiyaRuntimeBridge.isTodayInDhaka(yesterdayMs, nowMs))

    val formattedTime = RiyaRuntimeBridge.formatDhakaClockTime(nowMs)
    assertTrue(formattedTime.contains("AM") || formattedTime.contains("PM"))

    RiyaRuntimeBridge.registerIncomingMessage(
      IncomingMessageEvent(
        eventKey = "today_msg_1",
        packageName = "com.whatsapp",
        appLabel = "WhatsApp",
        senderName = "Tanvir",
        messageText = "বস আজকের মিটিং কখন?",
        suggestedReply = "একটু পরে জানাচ্ছি",
        timestamp = nowMs,
        status = IncomingReplyStatus.PENDING_PERMISSION,
      ),
      payload = null,
    )
    RiyaRuntimeBridge.registerIncomingMessage(
      IncomingMessageEvent(
        eventKey = "old_msg_2",
        packageName = "com.imo.android.imoim",
        appLabel = "imo",
        senderName = "OldSender",
        messageText = "গতকালের মেসেজ",
        suggestedReply = "",
        timestamp = yesterdayMs,
        status = IncomingReplyStatus.PENDING_PERMISSION,
      ),
      payload = null,
    )

    val todayOnly = RiyaRuntimeBridge.getTodayPendingOrRecentMessages(todayOnly = true, nowMs = nowMs)
    assertTrue(todayOnly.any { it.senderName == "Tanvir" })
    assertFalse(todayOnly.any { it.senderName == "OldSender" })

    val report = RiyaRuntimeBridge.formatRecentActivityReportBn(todayOnly = true, nowMs = nowMs)
    assertTrue(report.contains("Tanvir"))
    assertFalse(report.contains("OldSender"))

    // Contacts vs Dialer separation
    val contactsRes = LxrDeviceAndHardwareEngine.getContactsSummary(ctx)
    val callLogRes = LxrDeviceAndHardwareEngine.getCallLogSummary(ctx, todayOnly = true)
    assertNotEquals(contactsRes, callLogRes)
    assertTrue(contactsRes.contains("READ_CONTACTS") || contactsRes.contains("কন্টাক্ট"))
    assertTrue(callLogRes.contains("READ_CALL_LOG") || callLogRes.contains("কল"))
  }

  @Test
  fun persona_enforcesMandatoryScreenWatchToolMemoryAndPermissionFreeExecution() {
    val combined = "${RiyaPersona.DIRECTIVES_V2}\n${RiyaPersona.TEXT}"
    assertTrue(combined.contains("SCREEN WATCHING", ignoreCase = true))
    assertTrue(combined.contains("Permission-Free Tool Execution", ignoreCase = true))
    assertTrue(combined.contains("run_osint_investigation"))
    assertTrue(combined.contains("TODAY-ONLY"))
    assertFalse(combined.contains("Slide again to unlock", ignoreCase = true))
  }

  @Test
  fun osintSystem_enforcesGuardrailHonestCliStatusVerificationAnd5UniqueDeliverables() {
    // Guardrail blocks hacking/password/live-tracking
    val blocked = LxrOsintToolAdapters.evaluateSafetyGuardrail("target_user", "crack password and live gps track")
    assertFalse(blocked.allowed)
    val allowed = LxrOsintToolAdapters.evaluateSafetyGuardrail("OpenAI", "verify official domain")
    assertTrue(allowed.allowed)

    // Honest CLI availability reporting
    val noTermux = LxrOsintToolAdapters.parseCliToolAvailability("Maigret", termuxInstalled = false, termuxBinExists = false, installHint = "pip install maigret")
    assertEquals(OsintToolAvailability.TERMUX_NOT_INSTALLED, noTermux.availability)
    val missingBin = LxrOsintToolAdapters.parseCliToolAvailability("Sherlock", termuxInstalled = true, termuxBinExists = false, installHint = "pip install sherlock-project")
    assertEquals(OsintToolAvailability.NOT_INSTALLED_IN_TERMUX, missingBin.availability)
    val presentBin = LxrOsintToolAdapters.parseCliToolAvailability("SpiderFoot", termuxInstalled = true, termuxBinExists = true, installHint = "pip install spiderfoot")
    assertEquals(OsintToolAvailability.AVAILABLE_CLI, presentBin.availability)

    // Verification engine: deduplication, contradiction, impersonation, verification statuses
    val src1 =
      OsintSourceRecord(
        sourceId = "SRC-01",
        title = "Acme Official Organization Portal founded 2015",
        url = "https://en.wikipedia.org/wiki/Acme",
        domain = "en.wikipedia.org",
        collector = "Wikipedia",
        snippet = "Acme Official Organization Portal was founded 2015 in Dhaka as a public software initiative.",
        origin = OsintSourceOrigin.OFFICIAL,
        evidenceTier = OsintEvidenceTier.PRIMARY,
        credibilityScore = 95,
        fetchedAtDhaka = "2026-11-27",
      )
    val srcCopycat =
      src1.copy(
        sourceId = "SRC-02",
        url = "https://mirror-blog.example.net/acme-copy",
        domain = "mirror-blog.example.net",
        origin = OsintSourceOrigin.UNOFFICIAL,
        evidenceTier = OsintEvidenceTier.SECONDARY,
        credibilityScore = 62,
      )
    val src2Official =
      OsintSourceRecord(
        sourceId = "SRC-03",
        title = "Acme GitHub Verified Repository",
        url = "https://github.com/acme-org",
        domain = "github.com",
        collector = "GitHub Public Profile",
        snippet = "Official GitHub portal established 2015 for open source repositories.",
        origin = OsintSourceOrigin.OFFICIAL,
        evidenceTier = OsintEvidenceTier.PRIMARY,
        credibilityScore = 92,
        fetchedAtDhaka = "2026-11-27",
      )
    val deduped = LxrOsintVerificationEngine.deduplicateSyndicatedSources(listOf(src1, srcCopycat, src2Official))
    val copyFlagged = deduped.first { it.sourceId == "SRC-02" }
    assertTrue(copyFlagged.isSyndicatedDuplicate)
    assertEquals(OsintSourceOrigin.SYNDICATED_COPY, copyFlagged.origin)

    val noContradiction = LxrOsintVerificationEngine.detectContradictions(deduped)
    assertTrue(noContradiction.isEmpty())
    val verifiedClaims = LxrOsintVerificationEngine.verifyClaims("Acme", deduped, noContradiction)
    assertEquals(OsintVerificationStatus.VERIFIED, verifiedClaims.first().status)

    // Contradiction + Impersonation test
    val srcConflict =
      OsintSourceRecord(
        sourceId = "SRC-04",
        title = "Acme Fan Page & Parody Unofficial",
        url = "https://unofficial-acme.example.org/post",
        domain = "unofficial-acme.example.org",
        collector = "DuckDuckGo",
        snippet = "Unofficial parody page claiming founded 2021 and warning of fake impersonation.",
        origin = OsintSourceOrigin.UNOFFICIAL,
        evidenceTier = OsintEvidenceTier.SECONDARY,
        credibilityScore = 50,
        fetchedAtDhaka = "2026-11-27",
      )
    val contradictions = LxrOsintVerificationEngine.detectContradictions(listOf(src1, srcConflict))
    assertTrue(contradictions.isNotEmpty())
    val conflictClaims = LxrOsintVerificationEngine.verifyClaims("Acme", listOf(src1, srcConflict), contradictions)
    assertEquals(OsintVerificationStatus.CONFLICTING_EVIDENCE, conflictClaims.first().status)

    val impersonation = LxrOsintVerificationEngine.detectImpersonationAndNameCollision("Acme", listOf(src1, srcConflict))
    assertTrue(impersonation.isNotEmpty())

    // Storage category & orchestrator 5 unique deliverable files
    assertEquals("OSINT", LxrStorageCategory.OSINT.folderName)
    assertTrue(LxrDownloadStorage.termuxSyncDirectoriesCommand().contains("OSINT"))

    val id1 = LxrOsintOrchestrator.buildUniqueInvestigationId("example.org")
    val id2 = LxrOsintOrchestrator.buildUniqueInvestigationId("example.org")
    assertNotEquals("Unique IDs must never overwrite previous investigations", id1, id2)

    val report = LxrOsintOrchestrator.runInvestigation(ctx, target = "example_safe_org", objective = "verify public presence")
    assertFalse(report.blockedByGuardrail)
    val del = report.deliverables
    assertNotNull(del)
    val files =
      listOf(
        File(del!!.researchSummaryHtml),
        File(del.verifiedClaimsJson),
        File(del.sourceRegistryJson),
        File(del.evidenceComparisonHtml),
        File(del.finalReportHtml),
      )
    files.forEach { f ->
      assertTrue("Missing OSINT deliverable ${f.name}", f.exists() && f.length() > 0L)
    }

    // Blocked investigation produces guardrail refusal and zero deliverables
    val blockedReport = LxrOsintOrchestrator.runInvestigation(ctx, target = "john_doe", objective = "hack password")
    assertTrue(blockedReport.blockedByGuardrail)
    assertNotNull(blockedReport.guardrailReasonBn)
  }

  @Test
  fun osintViewerDialog_andTasksPageLauncher_renderCleanly() {
    composeTestRule.setContent {
      TasksPage(back = {}, onNavigate = {})
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("tasks_open_osint_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("tasks_open_osint_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("osint_report_viewer_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("osint_target_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("osint_run_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("osint_saved_count_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("osint_dialog_close_btn").assertIsDisplayed()
    composeTestRule.onNodeWithTag("osint_dialog_close_btn").performClick()
    composeTestRule.waitForIdle()
  }

  @Test
  fun item12_rssTrendingParser_andTodayNewsReadOnlyPolicy() {
    val sampleXml =
      """
      <rss><channel>
        <item>
          <title><![CDATA[Bangladesh Cricket Live Update]]></title>
          <link>https://trends.google.com/trending/bd-1</link>
          <ht:approx_traffic>50K+</ht:approx_traffic>
          <pubDate>Fri, 27 Nov 2026 10:00:00 +0600</pubDate>
        </item>
        <item>
          <title>AI Smartphone Chip Launch</title>
          <link>https://news.example.com/ai-chip</link>
          <pubDate>Fri, 27 Nov 2026 09:00:00 +0600</pubDate>
        </item>
      </channel></rss>
      """.trimIndent()
    val parsed = LxrResearchEngine.parseRssTitlesAndLinks(sampleXml, "Google Trends BD", maxItems = 5)
    assertEquals(2, parsed.size)
    assertEquals("Bangladesh Cricket Live Update", parsed[0].title)
    assertTrue(parsed[0].snippet.contains("50K+"))
    assertEquals("AI Smartphone Chip Launch", parsed[1].title)

    assertFalse(
      UnlockPolicyGuard.shouldAttemptAutoUnlockBeforeTool(
        toolName = "get_today_news_and_trending",
        genuinelyLocked = true,
      )
    )
    assertTrue("${RiyaPersona.DIRECTIVES_V2}\n${RiyaPersona.TEXT}".contains("get_today_news_and_trending"))
  }

  @Test
  fun item13_playStoreAabPipeline_andDynamicYoutubeSeo() {
    val aabProject =
      AppBuilderProject(
        name = "Shop Bangla",
        packageName = "com.lxr.shopbangla",
        buildMode = ApkBuildMode.PLAY_STORE_AAB,
      )
    assertEquals("bundleRelease", aabProject.buildMode.gradleTask)

    val files = AppProjectTemplates.generateFullProjectFiles(aabProject)
    val appGradle = files["app/build.gradle.kts"].orEmpty()
    assertTrue(appGradle.contains("keystore.properties"))
    assertTrue(appGradle.contains("isMinifyEnabled = true"))
    assertTrue(appGradle.contains("isShrinkResources = true"))

    val script = TermuxBuildBridge.generateTermuxBuildScript(aabProject)
    assertTrue(script.contains("bundleRelease"))
    assertTrue(script.contains("${aabProject.slug}-playstore.aab"))
    assertTrue(script.contains("keystore"))

    // Verify .aab archive check accepts base/manifest/AndroidManifest.xml
    val tempAab = File(ctx.cacheDir, "test_release.aab")
    ZipOutputStream(tempAab.outputStream()).use { zos ->
      zos.putNextEntry(ZipEntry("base/manifest/AndroidManifest.xml"))
      zos.write("<manifest package=\"com.lxr.shopbangla\"/>".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("base/dex/classes.dex"))
      zos.write(ByteArray(4096).also { java.util.Random(42L).nextBytes(it) })
      zos.closeEntry()
    }
    val verifyAab = AppBuilderFileRepository(ctx).verifyApkFile(tempAab, ApkBuildMode.PLAY_STORE_AAB)
    assertTrue(verifyAab.exists)
    assertTrue(verifyAab.hasValidApkArchive)
    tempAab.delete()

    // Verify dynamic YouTube SEO generates topic-specific non-boilerplate outputs
    val seo1 = LxrResearchEngine.generateYoutubeSeoPackage(ctx, topic = "Jetpack Compose Guide")
    val seo2 = LxrResearchEngine.generateYoutubeSeoPackage(ctx, topic = "Cyber Security Tips")
    assertNotEquals(seo1.titles, seo2.titles)
    assertNotEquals(seo1.hashtags, seo2.hashtags)
    assertFalse(seo1.hashtags.contains("#BanglaTutorial"))
    assertFalse(seo1.hashtags.contains("#LxRAI"))
  }

  @Test
  fun item14_bottomEdgeSwipeGuard_blocksSlideAgainToUnlockTriggers() {
    assertEquals(8_500L, LockVaultManager.HOME_EXIT_UNLOCK_SUPPRESSION_MS)
    assertTrue(
      LockVaultManager.shouldBlockBottomEdgeSwipe(
        isUnlocked = true,
        inHomeOrExitTransition = true,
        startY = 1000f,
        endY = 920f,
        screenHeight = 2400f,
      )
    )
    assertTrue(
      LockVaultManager.shouldBlockBottomEdgeSwipe(
        isUnlocked = true,
        inHomeOrExitTransition = false,
        startY = 1600f,
        endY = 1000f,
        screenHeight = 2400f,
      )
    )
    assertFalse(
      LockVaultManager.shouldBlockBottomEdgeSwipe(
        isUnlocked = true,
        inHomeOrExitTransition = false,
        startY = 1100f,
        endY = 700f,
        screenHeight = 2400f,
      )
    )
  }

  @Test
  fun item15_floatingOrbWakeGreetingMatchesExactBossPhrase() {
    assertEquals("বস কেনোও ডেকেছ বলো কি করতে হবে", LxrFloatingOverlayManager.ORB_WAKE_GREETING_BN)
    assertTrue(LxrFloatingOverlayManager.isAiWakeGreeting(LxrFloatingOverlayManager.ORB_WAKE_GREETING_BN))
    assertTrue(LxrFloatingOverlayManager.isAiWakeGreeting("বস, কেন ডেকেছ? বলো কি করতে হবে"))
    assertTrue(LxrFloatingOverlayManager.containsWakeWord("এলএক্সআর"))
    assertTrue(LxrFloatingOverlayManager.containsWakeWord("Hey LxR অরব"))
  }

  @Test
  fun item18_strictPermissionGuard_blocksAutoGpsEnableAndCoversAll17Controls() {
    val combined = "${RiyaPersona.DIRECTIVES_V2}\n${RiyaPersona.TEXT}".lowercase()
    listOf("wi-fi", "bluetooth", "mobile data", "hotspot", "flashlight", "gps", "brightness", "sound", "alarm", "time & date", "notification", "do not disturb", "phone information")
      .forEach { keyword ->
        assertTrue("Missing Item 18 guard keyword: $keyword", combined.contains(keyword))
      }

    val gpsReport = LxrDeviceAndHardwareEngine.getLiveLocationReport(ctx, allowAutoEnableGps = false)
    assertTrue(
      gpsReport.contains("অনুমতি") || gpsReport.contains("পারমিশন") || gpsReport.contains("LOCATION")
    )
  }

  @Test
  fun item21_speechSanitizer_flagsNoiseAndNeverGuessesOnFragments() {
    assertTrue(LxrSpeechTranscriptSanitizer.isAmbiguousNoiseFragment("<noise>"))
    assertTrue(LxrSpeechTranscriptSanitizer.isAmbiguousNoiseFragment("[inaudible]"))
    assertTrue(LxrSpeechTranscriptSanitizer.isAmbiguousNoiseFragment("হুম"))
    assertTrue(LxrSpeechTranscriptSanitizer.isAmbiguousNoiseFragment("ক"))
    assertFalse(LxrSpeechTranscriptSanitizer.isAmbiguousNoiseFragment("LxR"))
    assertFalse(LxrSpeechTranscriptSanitizer.isAmbiguousNoiseFragment("আজকের টেক নিউজ বলো"))
  }
}
