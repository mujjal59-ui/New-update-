package com.example.builder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class IdeWorkspaceTab(val titleBn: String) {
  DASHBOARD("ড্যাশবোর্ড"),
  EDITOR("কোড এডিটর"),
  BUILD("বিল্ড ও Termux"),
}

data class AppBuilderUiState(
  val activeTab: IdeWorkspaceTab = IdeWorkspaceTab.DASHBOARD,
  val projectName: String = "Amar Calculator",
  val appDescription: String = "",
  val category: AppCategory = AppCategory.CALCULATOR,
  val designTheme: AppDesignTheme = AppDesignTheme.CYBER_NEON,
  val packageName: String = "com.lxr.appbuilder.calculator",
  val minSdk: AndroidApiLevel = AndroidApiLevel.API_26,
  val language: ProgrammingLanguage = ProgrammingLanguage.KOTLIN,
  val buildMode: ApkBuildMode = ApkBuildMode.DEBUG,
  val activeProject: AppBuilderProject? = null,
  val history: List<AppBuilderProject> = emptyList(),
  val selectedFilePath: String = "",
  val editorText: String = "",
  val editorModified: Boolean = false,
  val pipelineProgress: Float = 0f,
  val currentStage: DevelopmentStage? = null,
  val isGenerating: Boolean = false,
  val isBuilding: Boolean = false,
  val consoleLogs: List<String> = emptyList(),
  val termuxStatus: TermuxEnvironmentStatus = TermuxEnvironmentStatus(),
  val errorDiagnosis: BuildErrorDiagnosis? = null,
  val lastErrorSignature: String? = null,
  val autoFixAttempts: Int = 0,
  val statusBanner: String? = null,
  val pendingConfirmation: BuilderConfirmationRequest? = null,
)

class AppBuilderViewModel(application: Application) : AndroidViewModel(application) {
  private val ctx = application.applicationContext
  private val repo = AppBuilderFileRepository(ctx)

  private val _uiState = MutableStateFlow(AppBuilderUiState())
  val uiState: StateFlow<AppBuilderUiState> = _uiState.asStateFlow()

  init {
    refreshHistoryAndTermux()
    observeTermuxCallbacks()
  }

  fun refreshHistoryAndTermux() {
    viewModelScope.launch(Dispatchers.IO) {
      val history = repo.loadProjectHistory()
      val active = _uiState.value.activeProject ?: history.firstOrNull()
      val termux = TermuxBuildBridge.inspectEnvironment(ctx, repo, active)
      _uiState.update { state ->
        val firstFile =
          if (state.selectedFilePath.isNotBlank() && active?.files?.containsKey(state.selectedFilePath) == true) {
            state.selectedFilePath
          } else {
            pickDefaultEditorFile(active)
          }
        state.copy(
          history = history,
          activeProject = active,
          termuxStatus = termux,
          selectedFilePath = firstFile,
          editorText = if (!state.editorModified) active?.files?.get(firstFile).orEmpty() else state.editorText,
          consoleLogs = if (state.consoleLogs.isEmpty()) active?.buildLogs.orEmpty() else state.consoleLogs,
        )
      }
    }
  }

  private fun observeTermuxCallbacks() {
    viewModelScope.launch {
      TermuxBuildBridge.liveResults.collect { res ->
        val project = _uiState.value.activeProject ?: return@collect
        if (res.projectId.isNotBlank() && res.projectId != project.id) return@collect
        val combinedLog =
          buildString {
            if (res.stdout.isNotBlank()) appendLine(res.stdout)
            if (res.stderr.isNotBlank()) appendLine(res.stderr)
            if (res.errmsg.isNotBlank()) appendLine(res.errmsg)
          }.trim()

        if (res.exitCode == 0) {
          verifyAndFinalizeSharedBuild(extraLog = combinedLog)
        } else {
          handleBuildFailureLog(
            combinedLog.ifBlank { "Termux বিল্ড ব্যর্থ হয়েছে (Exit Code: ${res.exitCode})" }
          )
        }
      }
    }
  }

  fun selectTab(tab: IdeWorkspaceTab) {
    _uiState.update { it.copy(activeTab = tab) }
  }

  fun updateForm(
    name: String? = null,
    description: String? = null,
    category: AppCategory? = null,
    designTheme: AppDesignTheme? = null,
    packageName: String? = null,
    minSdk: AndroidApiLevel? = null,
    language: ProgrammingLanguage? = null,
    buildMode: ApkBuildMode? = null,
  ) {
    _uiState.update { s ->
      val nextCat = category ?: s.category
      val nextName = name ?: s.projectName
      val autoPkg =
        if (category != null && packageName == null) {
          "com.lxr.appbuilder.${nextCat.name.lowercase().replace("_", "")}"
        } else {
          packageName ?: s.packageName
        }
      s.copy(
        projectName = nextName,
        appDescription = description ?: s.appDescription,
        category = nextCat,
        designTheme = designTheme ?: s.designTheme,
        packageName = autoPkg,
        minSdk = minSdk ?: s.minSdk,
        language = language ?: s.language,
        buildMode = buildMode ?: s.buildMode,
      )
    }
  }

  fun startNewProjectDraft() {
    _uiState.update {
      it.copy(
        activeTab = IdeWorkspaceTab.DASHBOARD,
        projectName = "New LXR App",
        appDescription = "",
        packageName = "com.lxr.appbuilder.customapp",
        errorDiagnosis = null,
        statusBanner = "নতুন প্রজেক্টের তথ্য লিখে 'AI দিয়ে অ্যাপ তৈরি করুন' চাপুন।",
      )
    }
  }

  fun requestGenerateProject() {
    val state = _uiState.value
    val targetName = state.projectName.trim().ifBlank { state.category.titleEn }
    if (repo.hasExistingProjectNamed(targetName, excludingId = state.activeProject?.id)) {
      _uiState.update {
        it.copy(
          pendingConfirmation =
            BuilderConfirmationRequest.OverwriteExistingProject(targetName) {
              executeGenerateProject()
            }
        )
      }
    } else {
      executeGenerateProject()
    }
  }

  private fun executeGenerateProject() {
    val s = _uiState.value
    val name = s.projectName.trim().ifBlank { s.category.titleEn }
    val desc = s.appDescription.trim().ifBlank { s.category.defaultFeatureHint }
    val draft =
      AppBuilderProject(
        name = name,
        description = desc,
        category = s.category,
        designTheme = s.designTheme,
        packageName = AppProjectTemplates.sanitizePackageName(s.packageName, name),
        minSdk = s.minSdk,
        language = s.language,
        buildMode = s.buildMode,
      )

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isGenerating = true,
          pipelineProgress = 0.05f,
          currentStage = DevelopmentStage.REQUIREMENT_ANALYSIS,
          consoleLogs = listOf("▶ স্বয়ংক্রিয় অ্যাপ ডেভেলপমেন্ট পাইপলাইন শুরু হয়েছে..."),
          errorDiagnosis = null,
          lastErrorSignature = null,
          autoFixAttempts = 0,
          statusBanner = null,
          activeTab = IdeWorkspaceTab.BUILD,
        )
      }

      try {
        val completed =
          withContext(Dispatchers.Default) {
            AppProjectGenerator.runAutomaticDevelopmentPipeline(draft) { stage, prog, logLine ->
              _uiState.update { cur ->
                cur.copy(
                  currentStage = stage,
                  pipelineProgress = prog,
                  consoleLogs = (cur.consoleLogs + logLine).takeLast(120),
                )
              }
            }
          }

        val saved = withContext(Dispatchers.IO) { repo.saveProject(completed) }
        val sharedSync = withContext(Dispatchers.IO) { TermuxBuildBridge.syncProjectToSharedWorkspace(repo, saved) }
        val termux = withContext(Dispatchers.IO) { TermuxBuildBridge.inspectEnvironment(ctx, repo, saved) }
        val history = withContext(Dispatchers.IO) { repo.loadProjectHistory() }
        val defaultFile = pickDefaultEditorFile(saved)

        _uiState.update { cur ->
          val syncMsg =
            sharedSync.fold(
              onSuccess = { "✓ ফাইল Download ফোল্ডারে সেভ হয়েছে: ${it.absolutePath}" },
              onFailure = { "⚠ Shared Sync নোট: ${it.message}" },
            )
          cur.copy(
            isGenerating = false,
            pipelineProgress = 1f,
            activeProject = saved,
            history = history,
            termuxStatus = termux,
            selectedFilePath = defaultFile,
            editorText = saved.files[defaultFile].orEmpty(),
            editorModified = false,
            consoleLogs = (cur.consoleLogs + syncMsg).takeLast(120),
            statusBanner = "'${saved.name}' এর সম্পূর্ণ সোর্স কোড ও প্রজেক্ট তৈরি সম্পন্ন ✓",
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _uiState.update { cur ->
          cur.copy(
            isGenerating = false,
            consoleLogs = (cur.consoleLogs + "✗ জেনারেশন ত্রুটি: ${e.message}").takeLast(120),
            statusBanner = "প্রজেক্ট জেনারেশনে সমস্যা: ${e.message}",
          )
        }
      }
    }
  }

  fun openProjectFromHistory(projectId: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val target = repo.loadProjectHistory().firstOrNull { it.id == projectId } ?: return@launch
      val termux = TermuxBuildBridge.inspectEnvironment(ctx, repo, target)
      val fileKey = pickDefaultEditorFile(target)
      _uiState.update {
        it.copy(
          activeProject = target,
          projectName = target.name,
          appDescription = target.description,
          category = target.category,
          designTheme = target.designTheme,
          packageName = target.packageName,
          minSdk = target.minSdk,
          language = target.language,
          buildMode = target.buildMode,
          selectedFilePath = fileKey,
          editorText = target.files[fileKey].orEmpty(),
          editorModified = false,
          consoleLogs = target.buildLogs,
          termuxStatus = termux,
          errorDiagnosis = null,
          activeTab = IdeWorkspaceTab.EDITOR,
          statusBanner = "প্রজেক্ট '${target.name}' খোলা হয়েছে",
        )
      }
    }
  }

  fun saveActiveProjectExplicitly() {
    val curProject = _uiState.value.activeProject ?: return
    val fileKey = _uiState.value.selectedFilePath
    val updatedFiles =
      if (fileKey.isNotBlank() && _uiState.value.editorModified) {
        curProject.files.toMutableMap().apply { put(fileKey, _uiState.value.editorText) }
      } else {
        curProject.files
      }
    viewModelScope.launch(Dispatchers.IO) {
      val saved = repo.saveProject(curProject.copy(files = updatedFiles))
      TermuxBuildBridge.syncProjectToSharedWorkspace(repo, saved)
      val history = repo.loadProjectHistory()
      _uiState.update {
        it.copy(
          activeProject = saved,
          history = history,
          editorModified = false,
          statusBanner = "প্রজেক্ট ও কোড সংরক্ষণ সম্পন্ন ✓",
        )
      }
    }
  }

  fun renameProject(projectId: String, newName: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val updated = repo.renameProject(projectId, newName) ?: return@launch
      val history = repo.loadProjectHistory()
      _uiState.update { state ->
        state.copy(
          history = history,
          activeProject = if (state.activeProject?.id == projectId) updated else state.activeProject,
          projectName = if (state.activeProject?.id == projectId) updated.name else state.projectName,
          statusBanner = "প্রজেক্টের নাম '${updated.name}' এ পরিবর্তন হয়েছে ✓",
        )
      }
    }
  }

  fun requestDeleteProject(project: AppBuilderProject) {
    _uiState.update {
      it.copy(pendingConfirmation = BuilderConfirmationRequest.DeleteProject(project.id, project.name))
    }
  }

  fun selectEditorFile(filePath: String) {
    val project = _uiState.value.activeProject ?: return
    _uiState.update {
      it.copy(
        selectedFilePath = filePath,
        editorText = project.files[filePath].orEmpty(),
        editorModified = false,
      )
    }
  }

  fun updateEditorText(newText: String) {
    _uiState.update { it.copy(editorText = newText, editorModified = true) }
  }

  fun requestSaveEditedFile() {
    val s = _uiState.value
    if (s.selectedFilePath.isBlank()) return
    _uiState.update {
      it.copy(
        pendingConfirmation =
          BuilderConfirmationRequest.OverwriteEditedFile(s.selectedFilePath, s.editorText)
      )
    }
  }

  fun addCustomCodeFile(relativePath: String) {
    val clean = relativePath.trim().trimStart('/').replace("..", "")
    if (clean.isBlank()) return
    val project = _uiState.value.activeProject ?: return
    if (project.files.containsKey(clean)) {
      _uiState.update {
        it.copy(
          pendingConfirmation =
            BuilderConfirmationRequest.OverwriteEditedFile(clean, project.files[clean].orEmpty())
        )
      }
      return
    }
    val nextFiles = project.files.toMutableMap().apply { put(clean, "// New file: $clean\n") }
    viewModelScope.launch(Dispatchers.IO) {
      val saved = repo.saveProject(project.copy(files = nextFiles))
      _uiState.update {
        it.copy(
          activeProject = saved,
          selectedFilePath = clean,
          editorText = saved.files[clean].orEmpty(),
          editorModified = false,
          statusBanner = "নতুন ফাইল '$clean' যোগ করা হয়েছে ✓",
        )
      }
    }
  }

  fun requestDeleteCodeFile(filePath: String) {
    if (filePath.isBlank()) return
    _uiState.update { it.copy(pendingConfirmation = BuilderConfirmationRequest.DeleteCodeFile(filePath)) }
  }

  fun confirmPendingAction() {
    val pending = _uiState.value.pendingConfirmation ?: return
    _uiState.update { it.copy(pendingConfirmation = null) }
    when (pending) {
      is BuilderConfirmationRequest.DeleteProject -> {
        viewModelScope.launch(Dispatchers.IO) {
          repo.deleteProject(pending.projectId)
          val history = repo.loadProjectHistory()
          _uiState.update { state ->
            val nextActive =
              if (state.activeProject?.id == pending.projectId) history.firstOrNull() else state.activeProject
            val nextFile = pickDefaultEditorFile(nextActive)
            state.copy(
              history = history,
              activeProject = nextActive,
              selectedFilePath = nextFile,
              editorText = nextActive?.files?.get(nextFile).orEmpty(),
              statusBanner = "প্রজেক্ট '${pending.projectName}' মুছে ফেলা হয়েছে।",
            )
          }
        }
      }
      is BuilderConfirmationRequest.DeleteCodeFile -> {
        val project = _uiState.value.activeProject ?: return
        val nextFiles = project.files.toMutableMap().apply { remove(pending.filePath) }
        viewModelScope.launch(Dispatchers.IO) {
          val saved = repo.saveProject(project.copy(files = nextFiles))
          val nextFile = pickDefaultEditorFile(saved)
          _uiState.update {
            it.copy(
              activeProject = saved,
              selectedFilePath = nextFile,
              editorText = saved.files[nextFile].orEmpty(),
              editorModified = false,
              statusBanner = "ফাইল '${pending.filePath}' মুছে ফেলা হয়েছে।",
            )
          }
        }
      }
      is BuilderConfirmationRequest.OverwriteExistingProject -> pending.onConfirmAction()
      is BuilderConfirmationRequest.OverwriteEditedFile -> {
        val project = _uiState.value.activeProject ?: return
        val nextFiles = project.files.toMutableMap().apply { put(pending.filePath, pending.newContent) }
        viewModelScope.launch(Dispatchers.IO) {
          val saved = repo.saveProject(project.copy(files = nextFiles))
          TermuxBuildBridge.syncProjectToSharedWorkspace(repo, saved)
          _uiState.update {
            it.copy(
              activeProject = saved,
              editorModified = false,
              statusBanner = "'${pending.filePath}' সংরক্ষণ করা হয়েছে ✓",
            )
          }
        }
      }
    }
  }

  fun dismissPendingAction() {
    _uiState.update { it.copy(pendingConfirmation = null) }
  }

  fun syncAndBuildViaTermux() {
    val project = _uiState.value.activeProject ?: return
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isBuilding = true,
          errorDiagnosis = null,
          consoleLogs =
            (it.consoleLogs + "▶ Download ওয়ার্কস্পেস ও Termux বিল্ড প্রস্তুত হচ্ছে...").takeLast(120),
          activeTab = IdeWorkspaceTab.BUILD,
        )
      }

      val syncRes = withContext(Dispatchers.IO) { TermuxBuildBridge.syncProjectToSharedWorkspace(repo, project) }
      val termux = withContext(Dispatchers.IO) { TermuxBuildBridge.inspectEnvironment(ctx, repo, project) }
      _uiState.update { it.copy(termuxStatus = termux) }

      val sharedDir =
        syncRes.getOrElse { err ->
          _uiState.update {
            it.copy(
              isBuilding = false,
              statusBanner = "ফোল্ডার সিঙ্ক ব্যর্থ: ${err.message}",
            )
          }
          return@launch
        }

      val dispatch =
        TermuxBuildBridge.dispatchBuildToTermux(ctx, project, sharedDir, runInBackground = false)
      dispatch.fold(
        onSuccess = { msg ->
          _uiState.update {
            it.copy(
              consoleLogs = (it.consoleLogs + "✓ $msg").takeLast(120),
              statusBanner = "Termux বিল্ড শুরু হয়েছে — ফলাফলের জন্য অপেক্ষা করা হচ্ছে...",
            )
          }
          // Poll shared folder build_result.json / APK in case broadcast callback is delayed by OEM battery policy
          repeat(15) {
            delay(2000)
            val (_, foundApk) =
              withContext(Dispatchers.IO) { TermuxBuildBridge.readSharedBuildArtifacts(repo, project) }
            if (foundApk?.exists == true) {
              verifyAndFinalizeSharedBuild()
              return@launch
            }
          }
          _uiState.update { it.copy(isBuilding = false) }
        },
        onFailure = { err ->
          handleBuildFailureLog(err.message ?: "Termux RUN_COMMAND চালু করা যায়নি")
        },
      )
    }
  }

  fun verifyAndFinalizeSharedBuild(extraLog: String = "") {
    val project = _uiState.value.activeProject ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val (diskLog, foundApk) = TermuxBuildBridge.readSharedBuildArtifacts(repo, project)
      val mergedLogs = buildList {
        addAll(_uiState.value.consoleLogs)
        if (!diskLog.isNullOrBlank()) {
          addAll(diskLog.lines().takeLast(25))
        } else if (extraLog.isNotBlank()) {
          addAll(extraLog.lines().takeLast(20))
        }
      }.takeLast(120)

      if (foundApk != null && foundApk.exists) {
        val exportName = "${project.slug}-${project.buildMode.name.lowercase()}.apk"
        val savedDownload = repo.saveApkToDownloads(File(foundApk.apkFilePath), exportName).getOrDefault(foundApk.apkFilePath)
        val verified = foundApk.copy(downloadExportPath = savedDownload)
        val updated =
          repo.saveProject(
            project.copy(
              buildStatus = BuildStatus.SUCCESS,
              buildLogs = mergedLogs,
              apkResult = verified,
            )
          )
        val history = repo.loadProjectHistory()
        _uiState.update {
          it.copy(
            isBuilding = false,
            activeProject = updated,
            history = history,
            consoleLogs = mergedLogs,
            errorDiagnosis = null,
            statusBanner = "APK সফলভাবে যাচাই হয়েছে (${verified.sizeBytes / 1024} KB) ✓",
          )
        }
      } else {
        if (!diskLog.isNullOrBlank() &&
          (diskLog.contains("ERROR", ignoreCase = true) || diskLog.contains("FAILED", ignoreCase = true))
        ) {
          handleBuildFailureLog(diskLog)
        } else {
          _uiState.update {
            it.copy(
              isBuilding = false,
              consoleLogs = mergedLogs,
              statusBanner = "Termux বিল্ড শেষ হলে 'APK ও লগ যাচাই করুন' বাটনে চাপুন।",
            )
          }
        }
      }
    }
  }

  fun handleBuildFailureLog(errorLog: String) {
    val project = _uiState.value.activeProject ?: return
    val nextAttempt = _uiState.value.autoFixAttempts + 1
    val prevSig = _uiState.value.lastErrorSignature
    val (diagnosis, repairedFiles) =
      AutoErrorFixer.diagnoseAndRepair(project, errorLog, prevSig, nextAttempt)

    val signature =
      errorLog.lineSequence().firstOrNull {
        it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true)
      }?.trim()?.take(120) ?: errorLog.trim().take(120)

    viewModelScope.launch(Dispatchers.IO) {
      val updatedProject =
        repo.saveProject(
          project.copy(
            files = repairedFiles,
            buildStatus = if (diagnosis.autoFixApplied && !diagnosis.isRepeatedSameError) BuildStatus.AUTO_FIXING else BuildStatus.FAILED,
            buildLogs = (_uiState.value.consoleLogs + errorLog.lines().takeLast(20)).takeLast(120),
          )
        )
      TermuxBuildBridge.syncProjectToSharedWorkspace(repo, updatedProject)

      _uiState.update {
        it.copy(
          isBuilding = false,
          activeProject = updatedProject,
          errorDiagnosis = diagnosis,
          lastErrorSignature = signature,
          autoFixAttempts = nextAttempt,
          consoleLogs = updatedProject.buildLogs,
          statusBanner =
            if (diagnosis.isRepeatedSameError) "একই Error বারবার হচ্ছে — Error Panel দেখুন।"
            else if (diagnosis.autoFixApplied) "অটো-ফিক্স প্রয়োগ হয়েছে — পুনরায় Build দিন।"
            else diagnosis.rootCauseBn,
        )
      }
    }
  }

  fun downloadSourceCodeZip() {
    val project = _uiState.value.activeProject ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val res = repo.createProjectZipBackup(project, includeBackupSuffix = false)
      _uiState.update {
        it.copy(
          statusBanner =
            res.fold(
              onSuccess = { (_, pub) -> "Source Code ZIP সেভ হয়েছে: $pub ✓" },
              onFailure = { err -> "ZIP ডাউনলোড ব্যর্থ: ${err.message}" },
            )
        )
      }
    }
  }

  fun backupProjectZip() {
    val project = _uiState.value.activeProject ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val res = repo.createProjectZipBackup(project, includeBackupSuffix = true)
      _uiState.update {
        it.copy(
          statusBanner =
            res.fold(
              onSuccess = { (_, pub) -> "Project Backup সংরক্ষিত: $pub ✓" },
              onFailure = { err -> "Backup ব্যর্থ: ${err.message}" },
            )
        )
      }
    }
  }

  fun downloadApkToPublicFolder() {
    val project = _uiState.value.activeProject ?: return
    val apk = project.apkResult
    if (!apk.exists || apk.apkFilePath.isBlank()) {
      _uiState.update { it.copy(statusBanner = "প্রথমে Termux-এ Build সম্পন্ন করে APK তৈরি করুন।") }
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      val res = repo.saveApkToDownloads(File(apk.apkFilePath), "${project.slug}.apk")
      _uiState.update {
        it.copy(
          statusBanner =
            res.fold(
              onSuccess = { path -> "APK ডাউনলোড হয়েছে: $path ✓" },
              onFailure = { err -> "APK সেভ ব্যর্থ: ${err.message}" },
            )
        )
      }
    }
  }

  fun installBuiltApk(): String? {
    val project = _uiState.value.activeProject ?: return "সক্রিয় প্রজেক্ট নেই।"
    val apk = project.apkResult
    if (!apk.exists || apk.apkFilePath.isBlank()) return "ইনস্টল করার মতো ভেরিফায়েড APK পাওয়া যায়নি।"
    if (!repo.canRequestPackageInstalls()) {
      repo.openUnknownAppsInstallSettings()
      return "LXR--AI-এর জন্য 'Install unknown apps' অনুমতি চালু করে আবার চেষ্টা করুন।"
    }
    val intent = repo.createInstallApkIntent(File(apk.apkFilePath)) ?: return "APK URI তৈরি করা যায়নি।"
    return runCatching {
      ctx.startActivity(intent)
      null
    }.getOrElse { "ইনস্টলার খোলা যায়নি: ${it.message}" }
  }

  fun dismissBanner() {
    _uiState.update { it.copy(statusBanner = null) }
  }

  private fun pickDefaultEditorFile(project: AppBuilderProject?): String {
    if (project == null || project.files.isEmpty()) return ""
    return project.files.keys.firstOrNull { it.endsWith("MainActivity.kt") || it.endsWith("MainActivity.java") }
      ?: project.files.keys.firstOrNull { it.endsWith("activity_main.xml") }
      ?: project.files.keys.first()
  }
}
