package com.example.builder

import android.content.Context
import android.util.Log
import com.example.service.RiyaRuntimeBridge
import com.example.service.RiyaVoiceManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppBuilderRuntimeSnapshot(
  val isVoiceTriggered: Boolean = false,
  val activeProject: AppBuilderProject? = null,
  val isGenerating: Boolean = false,
  val isBuilding: Boolean = false,
  val pipelineProgress: Float = 0f,
  val currentStage: DevelopmentStage? = null,
  val termuxStepLabel: String = "প্রস্তুত",
  val sharedFolderPath: String = "",
  val consoleLogs: List<String> = emptyList(),
  val errorDiagnosis: BuildErrorDiagnosis? = null,
  val statusBanner: String? = null,
  val lastErrorSignature: String? = null,
  val autoFixAttempts: Int = 0,
  val versionSeq: Long = 0L,
)

/**
 * Shared coordinator between UI (AppBuilderViewModel / LxrAppBuilderWorkspace) and Voice AI (GeminiLive).
 * Monitors Termux build.log / build_result.json in real time, triggers voice narration milestones,
 * runs AutoErrorFixer with bounded retries, and verifies final APKs into Downloads/LXR-AppBuilder.
 */
object AppBuilderRuntimeBridge {
  private const val TAG = "AppBuilderBridge"
  private const val MAX_POLL_CYCLES = 210 // 210 * 2s = 7 minutes max Termux build window
  private const val MAX_AUTO_FIX_RETRIES = 2

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var activeMonitorJob: Job? = null
  private var lastCallbackResult: TermuxCommandResult? = null

  private val _snapshot = MutableStateFlow(AppBuilderRuntimeSnapshot())
  val snapshot: StateFlow<AppBuilderRuntimeSnapshot> = _snapshot.asStateFlow()

  init {
    scope.launch {
      TermuxBuildBridge.liveResults.collect { res ->
        lastCallbackResult = res
      }
    }
  }

  /**
   * Called from GeminiLive `build_android_app` tool when Boss asks by voice to build any app.
   */
  fun startVoiceAppBuild(
    context: Context,
    prompt: String,
    requestedName: String? = null,
    requestedCategory: String? = null,
    shouldAutoBuildTermux: Boolean = true,
  ): String {
    val appCtx = context.applicationContext
    val (name, category, lang) =
      AppProjectGenerator.inferCategoryAndNameFromPrompt(
        rawPrompt = prompt,
        requestedName = requestedName,
        requestedCategory = requestedCategory,
      )
    val cleanSlug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "lxr_app" }
    val draft =
      AppBuilderProject(
        name = name,
        description = prompt.ifBlank { category.defaultFeatureHint },
        category = category,
        designTheme = if (category == AppCategory.CALCULATOR) AppDesignTheme.CYBER_NEON else AppDesignTheme.MATERIAL_DYNAMIC,
        packageName = "com.lxr.appbuilder.$cleanSlug",
        minSdk = AndroidApiLevel.API_26,
        language = lang,
        buildMode = ApkBuildMode.DEBUG,
      )

    // Switch UI screen to App Builder so Boss sees live interactive preview & build progress
    RiyaRuntimeBridge.requestUiPage("app_builder")

    activeMonitorJob?.cancel()
    activeMonitorJob =
      scope.launch {
        runFullGenerationAndTermuxBuild(
          ctx = appCtx,
          draft = draft,
          narrateByVoice = true,
          autoDispatchTermux = shouldAutoBuildTermux,
        )
      }

    val previewDir = "Downloads/LXR-AppBuilder/${draft.slug}"
    return "বস, '$name' (${category.titleBn}) অ্যাপ তৈরির কাজ শুরু হয়েছে! LXR স্ক্রিনে এখন লাইভ প্রিভিউ দেখা যাচ্ছে, সোর্স কোড $previewDir ফোল্ডারে সেভ করে Termux-এ APK বিল্ড ও যাচাই চালানো হচ্ছে। বিল্ড চলাকালীন আমি লাইভ আপডেট জানাবো।"
  }

  fun startUiBuildMonitor(
    context: Context,
    project: AppBuilderProject,
    narrateByVoice: Boolean = true,
  ) {
    val appCtx = context.applicationContext
    activeMonitorJob?.cancel()
    activeMonitorJob =
      scope.launch {
        val repo = AppBuilderFileRepository(appCtx)
        dispatchAndMonitorTermuxLoop(
          ctx = appCtx,
          repo = repo,
          initialProject = project,
          narrateByVoice = narrateByVoice,
          attempt = 0,
        )
      }
  }

  private suspend fun runFullGenerationAndTermuxBuild(
    ctx: Context,
    draft: AppBuilderProject,
    narrateByVoice: Boolean,
    autoDispatchTermux: Boolean,
  ) {
    val repo = AppBuilderFileRepository(ctx)
    _snapshot.update {
      it.copy(
        isVoiceTriggered = narrateByVoice,
        activeProject = draft,
        isGenerating = true,
        isBuilding = false,
        pipelineProgress = 0.08f,
        currentStage = DevelopmentStage.REQUIREMENT_ANALYSIS,
        termuxStepLabel = "সোর্স কোড ও ডিজাইন তৈরি হচ্ছে...",
        sharedFolderPath = "Downloads/LXR-AppBuilder/${draft.slug}",
        consoleLogs = listOf("▶ ভয়েস/প্রম্পট অনুযায়ী '${draft.name}' প্রজেক্ট তৈরি শুরু হয়েছে..."),
        errorDiagnosis = null,
        statusBanner = "'${draft.name}' এর রিয়েল অ্যাপ কোড তৈরি হচ্ছে...",
        versionSeq = it.versionSeq + 1,
      )
    }

    if (narrateByVoice) {
      safeSpeak(ctx, "বস, ${draft.name} অ্যাপের আসল কোড ও ডিজাইন তৈরি শুরু করেছি।")
    }

    try {
      val generated =
        AppProjectGenerator.runAutomaticDevelopmentPipeline(draft) { stage, prog, line ->
          _snapshot.update { cur ->
            cur.copy(
              currentStage = stage,
              pipelineProgress = prog,
              consoleLogs = (cur.consoleLogs + line).takeLast(140),
              versionSeq = cur.versionSeq + 1,
            )
          }
        }
      val saved = repo.saveProject(generated)
      val sharedRes = TermuxBuildBridge.syncProjectToSharedWorkspace(repo, saved)
      val sharedPath = sharedRes.getOrNull()?.absolutePath ?: repo.sharedWorkspaceDir(saved.slug).absolutePath

      _snapshot.update { cur ->
        cur.copy(
          activeProject = saved,
          isGenerating = false,
          pipelineProgress = 1f,
          sharedFolderPath = sharedPath,
          consoleLogs = (cur.consoleLogs + "✓ প্রজেক্ট Download ফোল্ডারে সংরক্ষিত হয়েছে: $sharedPath").takeLast(140),
          statusBanner = "'${saved.name}' কোড তৈরি সম্পন্ন — এখন Termux APK বিল্ড শুরু হচ্ছে।",
          versionSeq = cur.versionSeq + 1,
        )
      }

      if (autoDispatchTermux) {
        dispatchAndMonitorTermuxLoop(ctx, repo, saved, narrateByVoice, attempt = 0)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Generation pipeline failed", e)
      _snapshot.update {
        it.copy(
          isGenerating = false,
          isBuilding = false,
          statusBanner = "প্রজেক্ট তৈরিতে সমস্যা: ${e.message}",
          consoleLogs = (it.consoleLogs + "✗ ত্রুটি: ${e.message}").takeLast(140),
          versionSeq = it.versionSeq + 1,
        )
      }
    }
  }

  private suspend fun dispatchAndMonitorTermuxLoop(
    ctx: Context,
    repo: AppBuilderFileRepository,
    initialProject: AppBuilderProject,
    narrateByVoice: Boolean,
    attempt: Int,
  ) {
    var currentProject = initialProject.copy(buildStatus = BuildStatus.SYNCING_TERMUX)
    val syncRes = TermuxBuildBridge.syncProjectToSharedWorkspace(repo, currentProject)
    val sharedDir =
      syncRes.getOrElse { err ->
        _snapshot.update {
          it.copy(
            isBuilding = false,
            statusBanner = "Download ফোল্ডার সিঙ্ক ব্যর্থ: ${err.message}",
            consoleLogs = (it.consoleLogs + "✗ সিঙ্ক ব্যর্থ: ${err.message}").takeLast(140),
            versionSeq = it.versionSeq + 1,
          )
        }
        return
      }

    currentProject = repo.saveProject(currentProject.copy(buildStatus = BuildStatus.BUILDING))
    lastCallbackResult = null

    _snapshot.update {
      it.copy(
        activeProject = currentProject,
        isBuilding = true,
        sharedFolderPath = sharedDir.absolutePath,
        termuxStepLabel = "Termux বিল্ড চালু হচ্ছে...",
        consoleLogs = (it.consoleLogs + "▶ Termux বিল্ড শুরু (প্রচেষ্টা ${attempt + 1}) -> ${sharedDir.absolutePath}").takeLast(140),
        errorDiagnosis = null,
        statusBanner = "Termux-এ '${currentProject.name}' বিল্ড হচ্ছে — লাইভ লগ পর্যবেক্ষণ চলছে...",
        versionSeq = it.versionSeq + 1,
      )
    }

    val dispatch = TermuxBuildBridge.dispatchBuildToTermux(ctx, currentProject, sharedDir, runInBackground = false)
    if (dispatch.isFailure) {
      val msg = dispatch.exceptionOrNull()?.message ?: "Termux RUN_COMMAND চালু করা যায়নি"
      handleTermuxBuildFailure(ctx, repo, currentProject, msg, narrateByVoice, attempt)
      return
    }

    _snapshot.update {
      it.copy(
        consoleLogs = (it.consoleLogs + "✓ ${dispatch.getOrNull()}").takeLast(140),
        versionSeq = it.versionSeq + 1,
      )
    }
    if (narrateByVoice && attempt == 0) {
      safeSpeak(ctx, "বস, Termux-এ বিল্ড কমান্ড চালু করে দিয়েছি। আমি লাইভ লগ পড়ছি।")
    }

    var lastLogHash = 0
    var lastSpokenStep = 0
    var lastNarrationMs = System.currentTimeMillis()

    for (cycle in 0 until MAX_POLL_CYCLES) {
      delay(2000L)

      val cb = lastCallbackResult
      if (cb != null && cb.projectId == currentProject.id) {
        val cbText = listOf(cb.stdout, cb.stderr, cb.errmsg).filter { it.isNotBlank() }.joinToString("\n")
        if (cbText.isNotBlank()) {
          val parsedCb = TermuxBuildBridge.parseBuildResultStatus(cbText)
          if (parsedCb != null && parsedCb.status == "success" && parsedCb.apkPath.isNotBlank()) {
            val f = File(parsedCb.apkPath)
            if (f.exists() && f.canRead()) {
              finalizeVerifiedBuild(ctx, repo, currentProject, f, cbText, narrateByVoice)
              return
            }
          }
          if (cb.exitCode != 0 && cbText.contains("ERROR", ignoreCase = true)) {
            handleTermuxBuildFailure(ctx, repo, currentProject, cbText, narrateByVoice, attempt)
            return
          }
        }
      }

      val (diskLog, parsedJson, foundApk) = TermuxBuildBridge.readSharedBuildArtifacts(repo, currentProject)

      if (!diskLog.isNullOrBlank()) {
        val newHash = diskLog.hashCode()
        if (newHash != lastLogHash) {
          lastLogHash = newHash
          val tailLines = diskLog.lines().filter { it.isNotBlank() }.takeLast(30)
          val detectedStep = extractStepNumber(diskLog)
          val stepText = stepDescriptionBn(detectedStep, diskLog)

          _snapshot.update { cur ->
            val prefix = cur.consoleLogs.filter { it.startsWith("▶") || it.startsWith("✓") }
            cur.copy(
              termuxStepLabel = stepText,
              consoleLogs = (prefix + tailLines).distinct().takeLast(140),
              statusBanner = stepText,
              versionSeq = cur.versionSeq + 1,
            )
          }

          val now = System.currentTimeMillis()
          if (narrateByVoice && detectedStep > lastSpokenStep && (now - lastNarrationMs) >= 6500L) {
            lastSpokenStep = detectedStep
            lastNarrationMs = now
            safeSpeak(ctx, stepVoiceNarrationBn(detectedStep))
          }
        }
      }

      if (foundApk != null && foundApk.exists) {
        finalizeVerifiedBuild(ctx, repo, currentProject, File(foundApk.apkFilePath), diskLog.orEmpty(), narrateByVoice, verifiedPrebuilt = foundApk)
        return
      }

      val failedInJson = parsedJson != null && parsedJson.status.equals("failed", ignoreCase = true)
      val failedInLog =
        !diskLog.isNullOrBlank() &&
          (diskLog.contains("BUILD FAILED") || diskLog.contains("ERROR: Build failed")) &&
          (cycle >= 3)
      if (failedInJson || failedInLog) {
        val logErr = diskLog ?: "Termux বিল্ড ব্যর্থ হয়েছে (Exit ${parsedJson?.exitCode ?: -1})"
        handleTermuxBuildFailure(ctx, repo, currentProject, logErr, narrateByVoice, attempt)
        return
      }
    }

    _snapshot.update {
      it.copy(
        isBuilding = false,
        termuxStepLabel = "সময়সীমা অতিক্রান্ত — হাতে যাচাই করুন",
        statusBanner = "Termux বিল্ড এখনো চলতে পারে — সম্পন্ন হলে 'APK ও লগ যাচাই' চাপুন।",
        versionSeq = it.versionSeq + 1,
      )
    }
  }

  private suspend fun finalizeVerifiedBuild(
    ctx: Context,
    repo: AppBuilderFileRepository,
    project: AppBuilderProject,
    apkFile: File,
    logText: String,
    narrateByVoice: Boolean,
    verifiedPrebuilt: ApkVerificationResult? = null,
  ) {
    val exportName = "${project.slug}-${project.buildMode.name.lowercase()}.apk"
    val savedDownload = repo.saveApkToDownloads(apkFile, exportName).getOrDefault("Downloads/LXR-AppBuilder/$exportName")
    val baseVerified =
      verifiedPrebuilt ?: repo.verifyApkFile(apkFile, project.buildMode, savedDownload)
    val verified = baseVerified.copy(downloadExportPath = savedDownload)

    val mergedLogs = buildList {
      addAll(_snapshot.value.consoleLogs)
      if (logText.isNotBlank()) addAll(logText.lines().filter { it.isNotBlank() }.takeLast(25))
      add("✓ APK যাচাই সফল: ${verified.apkFileName} (${verified.sizeBytes / 1024} KB) -> $savedDownload")
    }.distinct().takeLast(140)

    val updated =
      repo.saveProject(
        project.copy(
          buildStatus = BuildStatus.SUCCESS,
          buildLogs = mergedLogs,
          apkResult = verified,
          lastDiagnosis = null,
        )
      )

    _snapshot.update {
      it.copy(
        activeProject = updated,
        isGenerating = false,
        isBuilding = false,
        pipelineProgress = 1f,
        termuxStepLabel = "APK তৈরি ও যাচাই সম্পন্ন ✓",
        sharedFolderPath = savedDownload,
        consoleLogs = mergedLogs,
        errorDiagnosis = null,
        statusBanner = "'${updated.name}' সফলভাবে তৈরি ও টেস্ট হয়েছে (${verified.sizeBytes / 1024} KB) • পাথ: $savedDownload ✓",
        versionSeq = it.versionSeq + 1,
      )
    }

    if (narrateByVoice) {
      val kb = (verified.sizeBytes / 1024).coerceAtLeast(1)
      safeSpeak(
        ctx,
        "অভিনন্দন বস! ${updated.name} অ্যাপটির APK সফলভাবে তৈরি ও যাচাই হয়ে গেছে, সাইজ $kb কিলোবাইট। ফাইলটি রাখা হয়েছে ডাউনলোড ফোল্ডারের LXR-AppBuilder/${updated.slug} এবং $savedDownload-এ। আপনি চাইলে এখনই ইনস্টল করতে পারেন।",
      )
    }
  }

  private suspend fun handleTermuxBuildFailure(
    ctx: Context,
    repo: AppBuilderFileRepository,
    project: AppBuilderProject,
    errorLog: String,
    narrateByVoice: Boolean,
    attempt: Int,
  ) {
    val prevSig = _snapshot.value.lastErrorSignature
    val nextAttempt = attempt + 1
    val (diagnosis, repairedFiles) =
      AutoErrorFixer.diagnoseAndRepair(project, errorLog, prevSig, nextAttempt)

    val signature =
      errorLog.lineSequence().firstOrNull {
        it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true)
      }?.trim()?.take(120) ?: errorLog.trim().take(120)

    val willAutoRetry =
      diagnosis.autoFixApplied && !diagnosis.isRepeatedSameError && attempt < MAX_AUTO_FIX_RETRIES

    val updated =
      repo.saveProject(
        project.copy(
          files = repairedFiles,
          buildStatus = if (willAutoRetry) BuildStatus.AUTO_FIXING else BuildStatus.FAILED,
          buildLogs = (_snapshot.value.consoleLogs + errorLog.lines().filter { it.isNotBlank() }.takeLast(20)).takeLast(140),
          lastDiagnosis = diagnosis,
        )
      )
    TermuxBuildBridge.syncProjectToSharedWorkspace(repo, updated)

    _snapshot.update {
      it.copy(
        activeProject = updated,
        isBuilding = willAutoRetry,
        errorDiagnosis = diagnosis,
        lastErrorSignature = signature,
        autoFixAttempts = nextAttempt,
        termuxStepLabel = if (willAutoRetry) "ত্রুটি শনাক্ত — অটো-ফিক্স করে আবার বিল্ড হচ্ছে..." else "বিল্ড ত্রুটি: ${diagnosis.rootCauseBn}",
        consoleLogs = updated.buildLogs,
        statusBanner =
          if (willAutoRetry) "ত্রুটি ধরে অটো-ফিক্স প্রয়োগ হয়েছে — পুনরায় Termux বিল্ড চালানো হচ্ছে (প্রচেষ্টা ${nextAttempt + 1})..."
          else diagnosis.rootCauseBn,
        versionSeq = it.versionSeq + 1,
      )
    }

    if (willAutoRetry) {
      if (narrateByVoice) {
        safeSpeak(ctx, "বস, বিল্ডে একটি সমস্যা ধরা পড়েছে: ${diagnosis.rootCauseBn}। আমি নিজেই ফাইল ঠিক করে আবার বিল্ড দিচ্ছি।")
      }
      delay(1200L)
      dispatchAndMonitorTermuxLoop(ctx, repo, updated, narrateByVoice, attempt + 1)
    } else if (narrateByVoice) {
      safeSpeak(ctx, "বস, Termux বিল্ডে ত্রুটি হয়েছে: ${diagnosis.rootCauseBn}। ${diagnosis.suggestedFixBn}")
    }
  }

  /**
   * Used by GeminiLive `check_termux_build_status` so Boss can ask "বিল্ড কতদূর হলো?" anytime.
   */
  fun readLiveStatusSummaryForAi(context: Context): String {
    val s = _snapshot.value
    val project = s.activeProject ?: AppBuilderFileRepository(context).loadProjectHistory().firstOrNull()
    val tail = s.consoleLogs.takeLast(6).joinToString(" | ")
    val apk = project?.apkResult
    return buildString {
      if (project != null) {
        append("প্রজেক্ট: ${project.name} (${project.category.titleBn}) | অবস্থা: ${project.buildStatus.labelBn}")
      } else {
        append("অবস্থা: ${s.termuxStepLabel}")
      }
      if (s.termuxStepLabel.isNotBlank()) append(" | ধাপ: ${s.termuxStepLabel}")
      if (apk?.exists == true) {
        append(" | APK প্রস্তুত: ${apk.apkFileName} (${apk.sizeBytes / 1024} KB), ফোল্ডার: ${apk.downloadExportPath}")
      } else if (s.sharedFolderPath.isNotBlank()) {
        append(" | ফোল্ডার: ${s.sharedFolderPath}")
      }
      if (s.errorDiagnosis != null) {
        append(" | ত্রুটি: ${s.errorDiagnosis.rootCauseBn} | সমাধান: ${s.errorDiagnosis.suggestedFixBn}")
      }
      if (tail.isNotBlank()) append("\nসাম্প্রতিক লগ: $tail")
    }
  }

  private fun extractStepNumber(log: String): Int {
    val m = Regex("LXR_PROGRESS:STEP=(\\d)").findAll(log).lastOrNull()
    return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: when {
      log.contains("SUCCESS: Built") -> 5
      log.contains("> Task :app:") || log.contains("Running Gradle task") -> 4
      log.contains("Syncing") -> 3
      log.contains("sdkmanager") || log.contains("cmdline-tools") -> 2
      else -> 1
    }
  }

  private fun stepDescriptionBn(step: Int, log: String): String =
    when (step) {
      1 -> "ধাপ ১/৫: Termux প্যাকেজ (Java, Gradle, AAPT2) পরীক্ষা চলছে"
      2 -> "ধাপ ২/৫: Android SDK ও Build Tools ডাউনলোড/যাচাই চলছে"
      3 -> "ধাপ ৩/৫: প্রজেক্ট ফাইল Download ওয়ার্কস্পেসে সিঙ্ক হচ্ছে"
      4 -> {
        val lastTask = log.lineSequence().lastOrNull { it.trim().startsWith("> Task ") }?.trim()
        if (lastTask != null) "ধাপ ৪/৫: $lastTask" else "ধাপ ৪/৫: Gradle দিয়ে APK কম্পাইল হচ্ছে..."
      }
      5 -> "ধাপ ৫/৫: APK আউটপুট যাচাই ও Download ফোল্ডারে সংরক্ষণ"
      else -> "Termux বিল্ড সক্রিয়..."
    }

  private fun stepVoiceNarrationBn(step: Int): String =
    when (step) {
      1 -> "বস, Termux এনভায়রনমেন্ট ও প্যাকেজ যাচাই হচ্ছে।"
      2 -> "বস, ফ্রি অ্যান্ড্রয়েড SDK চেক ও ডাউনলোড হচ্ছে, একটু অপেক্ষা করুন।"
      3 -> "প্রজেক্টের সব ফাইল Termux ওয়ার্কস্পেসে সিঙ্ক হয়েছে বস।"
      4 -> "এখন Gradle দিয়ে আসল APK কম্পাইল হচ্ছে বস, আমি লগ দেখছি।"
      5 -> "কম্পাইল শেষের দিকে বস, এখন APK ফাইলটি যাচাই করা হচ্ছে।"
      else -> ""
    }

  private fun safeSpeak(ctx: Context, text: String) {
    if (text.isBlank()) return
    runCatching {
      RiyaVoiceManager.initialize(ctx)
      RiyaVoiceManager.speak(text, interrupt = false, forceSpeak = false)
    }
  }
}
