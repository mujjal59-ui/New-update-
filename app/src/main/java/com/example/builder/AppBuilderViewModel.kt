package com.example.builder

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.service.LxrDownloadStorage
import com.example.service.LxrSecretRedactor
import com.example.service.LxrStorageCategory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
  val language: ProgrammingLanguage = ProgrammingLanguage.KOTLIN_COMPOSE,
  val buildMode: ApkBuildMode = ApkBuildMode.DEBUG,
  val activeProject: AppBuilderProject? = null,
  val history: List<AppBuilderProject> = emptyList(),
  val selectedFilePath: String = "",
  val editorText: String = "",
  val editorModified: Boolean = false,
  val pipelineProgress: Float = 0f,
  val currentStage: DevelopmentStage? = null,
  val replicaStage: ReplicaPipelineStage = ReplicaPipelineStage.RECON,
  val completedReplicaStages: Set<ReplicaPipelineStage> = emptySet(),
  val termuxStepLabel: String = "",
  val termuxTelemetry: TermuxCommandTelemetry = TermuxCommandTelemetry(),
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

  private var lastAppliedBridgeSeq = 0L

  init {
    refreshHistoryAndTermux()
    observeTermuxCallbacks()
    observeRuntimeBridge()
  }

  private fun observeRuntimeBridge() {
    viewModelScope.launch {
      AppBuilderRuntimeBridge.snapshot.collect { snap ->
        if (snap.versionSeq == 0L || snap.versionSeq == lastAppliedBridgeSeq) return@collect
        lastAppliedBridgeSeq = snap.versionSeq
        val history = withContext(Dispatchers.IO) { repo.loadProjectHistory() }
        val proj = snap.activeProject
        val defaultFile = pickDefaultEditorFile(proj)
        _uiState.update { state ->
          val fileKey =
            if (state.selectedFilePath.isNotBlank() && proj?.files?.containsKey(state.selectedFilePath) == true) {
              state.selectedFilePath
            } else {
              defaultFile
            }
          state.copy(
            projectName = proj?.name ?: state.projectName,
            appDescription = proj?.description ?: state.appDescription,
            category = proj?.category ?: state.category,
            designTheme = proj?.designTheme ?: state.designTheme,
            packageName = proj?.packageName ?: state.packageName,
            language = proj?.language ?: state.language,
            activeProject = proj ?: state.activeProject,
            history = history,
            selectedFilePath = fileKey,
            editorText = if (!state.editorModified && proj != null) proj.files[fileKey].orEmpty() else state.editorText,
            pipelineProgress = snap.pipelineProgress,
            currentStage = snap.currentStage ?: state.currentStage,
            replicaStage = snap.currentStage?.let { mapToReplicaStage(it) } ?: state.replicaStage,
            termuxStepLabel = snap.termuxStepLabel,
            isGenerating = snap.isGenerating,
            isBuilding = snap.isBuilding,
            consoleLogs = snap.consoleLogs.map { LxrSecretRedactor.redactSecrets(it) },
            errorDiagnosis = snap.errorDiagnosis,
            lastErrorSignature = snap.lastErrorSignature,
            autoFixAttempts = snap.autoFixAttempts,
            statusBanner = snap.statusBanner ?: state.statusBanner,
          )
        }
      }
    }
  }

  fun onSafTreeGranted(uri: Uri?) {
    if (uri == null) return
    viewModelScope.launch(Dispatchers.IO) {
      repo.saveSafTreeUri(uri)
      val active = _uiState.value.activeProject
      val termux = TermuxBuildBridge.inspectEnvironment(ctx, repo, active)
      _uiState.update {
        it.copy(
          termuxStatus = termux,
          statusBanner = "Download ফোল্ডার SAF এক্সেস অনুমোদিত হয়েছে ✓ এখন APK ও লগ সরাসরি পড়া যাবে।",
        )
      }
    }
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
        if (_uiState.value.isBuilding) {
          // AppBuilderRuntimeBridge is already handling the active Termux poll/callback cycle
          return@collect
        }
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

  fun clearConsoleLogs() {
    _uiState.update { it.copy(consoleLogs = emptyList(), termuxStepLabel = "লগ পরিষ্কার করা হয়েছে") }
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
    viewModelScope.launch(Dispatchers.IO) {
      val exists = repo.hasExistingProjectNamed(targetName, excludingId = state.activeProject?.id)
      if (exists) {
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
          replicaStage = ReplicaPipelineStage.RECON,
          completedReplicaStages = emptySet(),
          termuxStepLabel = "সোর্স কোড ও ডিজাইন তৈরি হচ্ছে...",
          termuxTelemetry =
            TermuxCommandTelemetry(
              commandPreview = "generate_android_project(${draft.slug})",
              workingDir = "~/storage/downloads/LxR/Apps/${draft.slug}",
              outputMode = "Buffered Callback",
              state = TermuxExecutionState.RUNNING,
              startedAtLabel = dhakaTimeLabel(),
              apkVariantLabel = "${draft.buildMode.labelBn} (debugConfig)",
            ),
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
              val mapped = mapToReplicaStage(stage)
              val safeLog = LxrSecretRedactor.redactSecrets(logLine)
              _uiState.update { cur ->
                val nextDone = cur.completedReplicaStages + cur.replicaStage
                cur.copy(
                  currentStage = stage,
                  replicaStage = mapped,
                  completedReplicaStages = if (mapped == ReplicaPipelineStage.RECON) emptySet() else nextDone,
                  pipelineProgress = prog,
                  consoleLogs = (cur.consoleLogs + safeLog).takeLast(140),
                )
              }
            }
          }

        val saved = withContext(Dispatchers.IO) { repo.saveProject(completed) }
        val sharedSync = withContext(Dispatchers.IO) { TermuxBuildBridge.syncProjectToSharedWorkspace(repo, saved) }
        val termux = withContext(Dispatchers.IO) { TermuxBuildBridge.inspectEnvironment(ctx, repo, saved) }
        val history = withContext(Dispatchers.IO) { repo.loadProjectHistory() }
        val defaultFile = pickDefaultEditorFile(saved)
        val stageSet =
          ReplicaPipelineStage.entries.filter { it.stepNumber <= ReplicaPipelineStage.LAUNCH.stepNumber }.toSet()
        withContext(Dispatchers.IO) {
          persistStagesJson(saved, ReplicaPipelineStage.LAUNCH, stageSet)
        }

        _uiState.update { cur ->
          val syncMsg =
            sharedSync.fold(
              onSuccess = { "✓ ফাইল Download ফোল্ডারে সেভ হয়েছে: ${it.absolutePath}" },
              onFailure = { "⚠ Shared Sync নোট: ${it.message}" },
            )
          cur.copy(
            isGenerating = false,
            pipelineProgress = 1f,
            replicaStage = ReplicaPipelineStage.LAUNCH,
            completedReplicaStages = stageSet,
            termuxStepLabel = "প্রজেক্ট কোড তৈরি সম্পন্ন ✓",
            termuxTelemetry =
              cur.termuxTelemetry.copy(
                state = TermuxExecutionState.SUCCEEDED,
                exitCode = 0,
                finishedAtLabel = dhakaTimeLabel(),
                changedFilesSummary = "${saved.files.size} files (${saved.language.extension})",
              ),
            activeProject = saved,
            history = history,
            termuxStatus = termux,
            selectedFilePath = defaultFile,
            editorText = saved.files[defaultFile].orEmpty(),
            editorModified = false,
            consoleLogs = (cur.consoleLogs + LxrSecretRedactor.redactSecrets(syncMsg)).takeLast(140),
            statusBanner = "'${saved.name}' এর সম্পূর্ণ সোর্স কোড ও প্রজেক্ট তৈরি সম্পন্ন ✓",
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val safeErr = LxrSecretRedactor.redactSecrets(e.message ?: "Unknown error")
        _uiState.update { cur ->
          cur.copy(
            isGenerating = false,
            termuxTelemetry =
              cur.termuxTelemetry.copy(
                state = TermuxExecutionState.FAILED,
                exitCode = 1,
                finishedAtLabel = dhakaTimeLabel(),
              ),
            consoleLogs = (cur.consoleLogs + "✗ জেনারেশন ত্রুটি: $safeErr").takeLast(140),
            statusBanner = "প্রজেক্ট জেনারেশনে সমস্যা: $safeErr",
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
          errorDiagnosis = target.lastDiagnosis,
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
      is BuilderConfirmationRequest.DeployProject -> pending.onConfirmDeploy()
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

  fun requestDeployProject() {
    val project = _uiState.value.activeProject ?: return
    _uiState.update {
      it.copy(
        pendingConfirmation =
          BuilderConfirmationRequest.DeployProject(project.name) {
            val doneSet = _uiState.value.completedReplicaStages + ReplicaPipelineStage.DEPLOY
            _uiState.update { cur ->
              cur.copy(
                replicaStage = ReplicaPipelineStage.DEPLOY,
                completedReplicaStages = doneSet,
                statusBanner = "'${project.name}' Deploy অনুমোদিত হয়েছে ✓",
              )
            }
            viewModelScope.launch(Dispatchers.IO) {
              persistStagesJson(project, ReplicaPipelineStage.DEPLOY, doneSet)
            }
            val apk = project.apkResult
            if (apk.exists && apk.apkFilePath.isNotBlank()) {
              val err = installBuiltApk()
              if (err != null) {
                _uiState.update { cur -> cur.copy(statusBanner = err) }
              }
            }
          }
      )
    }
  }

  fun syncAndBuildViaTermux() {
    val project = _uiState.value.activeProject ?: return
    _uiState.update {
      it.copy(
        isBuilding = true,
        errorDiagnosis = null,
        replicaStage = ReplicaPipelineStage.LAUNCH,
        termuxStepLabel = "Termux বিল্ড শুরু হচ্ছে...",
        termuxTelemetry =
          TermuxCommandTelemetry(
            commandPreview = "bash build_lxr_app.sh (${project.buildMode.gradleTask})",
            workingDir = "~/storage/downloads/LxR/Apps/${project.slug}",
            outputMode = "Buffered Callback",
            state = TermuxExecutionState.RUNNING,
            startedAtLabel = dhakaTimeLabel(),
            changedFilesSummary = "${project.files.size} files",
            apkVariantLabel =
              if (project.buildMode == ApkBuildMode.RELEASE) "Release APK"
              else "Debug APK (debugConfig)",
          ),
        activeTab = IdeWorkspaceTab.BUILD,
      )
    }
    AppBuilderRuntimeBridge.startUiBuildMonitor(ctx, project, narrateByVoice = true)
  }

  fun verifyAndFinalizeSharedBuild(extraLog: String = "") {
    val project = _uiState.value.activeProject ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val (diskLog, parsedJson, foundApk) = TermuxBuildBridge.readSharedBuildArtifacts(repo, project)
      val mergedLogs = buildList {
        addAll(_uiState.value.consoleLogs)
        if (!diskLog.isNullOrBlank()) {
          addAll(diskLog.lines().filter { it.isNotBlank() }.takeLast(25))
        } else if (extraLog.isNotBlank()) {
          addAll(extraLog.lines().filter { it.isNotBlank() }.takeLast(20))
        }
      }.map { LxrSecretRedactor.redactSecrets(it) }.distinct().takeLast(140)

      if (foundApk != null && foundApk.exists) {
        val exportName = "${project.slug}-${project.buildMode.name.lowercase()}.apk"
        val savedDownload = repo.saveApkToDownloads(File(foundApk.apkFilePath), exportName, project.slug).getOrDefault(foundApk.downloadExportPath)
        val verified = foundApk.copy(downloadExportPath = savedDownload)
        val updated =
          repo.saveProject(
            project.copy(
              buildStatus = BuildStatus.SUCCESS,
              buildLogs = mergedLogs,
              apkResult = verified,
              lastDiagnosis = null,
            )
          )
        val doneSet =
          _uiState.value.completedReplicaStages +
            ReplicaPipelineStage.entries.filter { it.stepNumber <= ReplicaPipelineStage.LAUNCH.stepNumber }
        persistStagesJson(updated, ReplicaPipelineStage.LAUNCH, doneSet)
        val history = repo.loadProjectHistory()
        _uiState.update {
          it.copy(
            isBuilding = false,
            activeProject = updated,
            history = history,
            replicaStage = ReplicaPipelineStage.LAUNCH,
            completedReplicaStages = doneSet,
            consoleLogs = mergedLogs,
            errorDiagnosis = null,
            termuxStepLabel = "APK তৈরি ও যাচাই সম্পন্ন ✓",
            termuxTelemetry =
              it.termuxTelemetry.copy(
                state = TermuxExecutionState.SUCCEEDED,
                exitCode = 0,
                finishedAtLabel = dhakaTimeLabel(),
                changedFilesSummary = "${updated.files.size} files",
              ),
            statusBanner = "APK সফলভাবে যাচাই হয়েছে (${verified.sizeBytes / 1024} KB) ✓",
          )
        }
      } else {
        val isFailed =
          parsedJson?.status.equals("failed", ignoreCase = true) ||
            (!diskLog.isNullOrBlank() &&
              (diskLog.contains("BUILD FAILED") || diskLog.contains("ERROR:", ignoreCase = true)))
        if (isFailed) {
          handleBuildFailureLog(diskLog ?: "Termux বিল্ড ব্যর্থ হয়েছে (Exit ${parsedJson?.exitCode ?: -1})")
        } else {
          _uiState.update {
            it.copy(
              isBuilding = false,
              consoleLogs = mergedLogs,
              statusBanner = "Termux বিল্ড শেষ হলে অথবা Download ফোল্ডার SAF অনুমোদন দিয়ে পুনরায় যাচাই করুন।",
            )
          }
        }
      }
    }
  }

  fun handleBuildFailureLog(errorLog: String) {
    val project = _uiState.value.activeProject ?: return
    val redactedError = LxrSecretRedactor.redactSecrets(errorLog)
    val nextAttempt = _uiState.value.autoFixAttempts + 1
    val prevSig = _uiState.value.lastErrorSignature
    val (diagnosis, repairedFiles) =
      AutoErrorFixer.diagnoseAndRepair(project, redactedError, prevSig, nextAttempt)

    val signature =
      redactedError.lineSequence().firstOrNull {
        it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true)
      }?.trim()?.take(120) ?: redactedError.trim().take(120)

    viewModelScope.launch(Dispatchers.IO) {
      val updatedProject =
        repo.saveProject(
          project.copy(
            files = repairedFiles,
            buildStatus = if (diagnosis.autoFixApplied && !diagnosis.isRepeatedSameError) BuildStatus.AUTO_FIXING else BuildStatus.FAILED,
            buildLogs = (_uiState.value.consoleLogs + redactedError.lines().takeLast(20)).map { LxrSecretRedactor.redactSecrets(it) }.takeLast(140),
            lastDiagnosis = diagnosis,
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
          termuxTelemetry =
            it.termuxTelemetry.copy(
              state = TermuxExecutionState.FAILED,
              exitCode = 1,
              finishedAtLabel = dhakaTimeLabel(),
            ),
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

  fun runDirectTermuxCommand(rawCommand: String) {
    val cmd = rawCommand.trim()
    if (cmd.isBlank()) {
      _uiState.update { it.copy(statusBanner = "Termux-এ চালানোর জন্য একটি কমান্ড লিখুন।") }
      return
    }
    val safeLabel = LxrSecretRedactor.redactSecrets(cmd.take(100))
    val activeSlug = _uiState.value.activeProject?.slug ?: "workspace"
    _uiState.update { cur ->
      cur.copy(
        consoleLogs = (cur.consoleLogs + "▶ Termux cmd: $safeLabel").takeLast(140),
        termuxTelemetry =
          cur.termuxTelemetry.copy(
            commandPreview = safeLabel,
            workingDir = "~/storage/downloads/LxR/Apps/$activeSlug",
            outputMode = "Buffered Callback",
            state = TermuxExecutionState.RUNNING,
            exitCode = null,
            startedAtLabel = dhakaTimeLabel(),
            finishedAtLabel = "",
          ),
        statusBanner = "Termux কমান্ড চালানো হচ্ছে...",
      )
    }
    viewModelScope.launch(Dispatchers.IO) {
      val acc = com.example.service.RiyaAccessibilityService.instance
      val resultText =
        if (!TermuxBuildBridge.isPackageInstalled(ctx, TermuxBuildBridge.TERMUX_PACKAGE)) {
          "✗ Termux অ্যাপ ইনস্টল করা নেই।"
        } else if (acc != null && com.example.service.Keep.accessibilityOn(ctx)) {
          acc.executeTermuxCommandViaUi(ctx, cmd)
        } else {
          val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
          cm.setPrimaryClip(android.content.ClipData.newPlainText("Termux Cmd", cmd))
          TermuxBuildBridge.openTermuxApp(ctx)
          "✓ কমান্ড ক্লিপবোর্ডে কপি করে Termux খোলা হয়েছে (সরাসরি পেস্ট অটোমেশনের জন্য Accessibility চালু করুন)।"
        }
      val redactedResult = LxrSecretRedactor.redactSecrets(resultText)
      val failed = redactedResult.startsWith("✗")
      _uiState.update { cur ->
        cur.copy(
          consoleLogs = (cur.consoleLogs + redactedResult).takeLast(140),
          termuxTelemetry =
            cur.termuxTelemetry.copy(
              state = if (failed) TermuxExecutionState.FAILED else TermuxExecutionState.SUCCEEDED,
              exitCode = if (failed) 1 else 0,
              finishedAtLabel = dhakaTimeLabel(),
            ),
          statusBanner = redactedResult.take(120),
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
      val res = repo.saveApkToDownloads(File(apk.apkFilePath), "${project.slug}.apk", project.slug)
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

  private fun dhakaTimeLabel(): String =
    SimpleDateFormat("hh:mm:ss a 'BST'", Locale.US)
      .apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }
      .format(Date())

  private fun mapToReplicaStage(stage: DevelopmentStage): ReplicaPipelineStage =
    when (stage) {
      DevelopmentStage.REQUIREMENT_ANALYSIS -> ReplicaPipelineStage.RECON
      DevelopmentStage.APP_ARCHITECTURE -> ReplicaPipelineStage.ARCHITECT
      DevelopmentStage.UI_UX_DESIGN -> ReplicaPipelineStage.DESIGN
      DevelopmentStage.SOURCE_CODE,
      DevelopmentStage.RESOURCE_FILES -> ReplicaPipelineStage.BUILD
      DevelopmentStage.DEPENDENCIES -> ReplicaPipelineStage.BACKEND
      DevelopmentStage.BUILD_CONFIG -> ReplicaPipelineStage.TEST
      DevelopmentStage.PROJECT_STRUCTURE -> ReplicaPipelineStage.DIFF
      DevelopmentStage.ASSETS_RESOURCES -> ReplicaPipelineStage.BRAND
      DevelopmentStage.APPLICATION_BUILD -> ReplicaPipelineStage.LAUNCH
    }

  private fun persistStagesJson(
    proj: AppBuilderProject,
    current: ReplicaPipelineStage,
    completed: Set<ReplicaPipelineStage>,
  ) {
    runCatching {
      val root =
        JSONObject().apply {
          put("projectId", proj.id)
          put("projectName", proj.name)
          put("slug", proj.slug)
          put("currentStage", current.name)
          put("updatedAtDhaka", dhakaTimeLabel())
          put(
            "completedStages",
            JSONArray().apply { completed.sortedBy { it.stepNumber }.forEach { put(it.name) } },
          )
        }
      LxrDownloadStorage.saveTextDeliverable(
        context = ctx,
        category = LxrStorageCategory.APPS,
        projectSlug = proj.slug,
        fileName = "stages.json",
        content = root.toString(2),
        preserveUnique = false,
      )
    }
  }
}

