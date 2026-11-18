package com.example.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VideoStudioViewModel(application: Application) : AndroidViewModel(application) {

  private val ctx = application.applicationContext
  private val _uiState = MutableStateFlow(VideoStudioUiState())
  val uiState: StateFlow<VideoStudioUiState> = _uiState.asStateFlow()

  private var pipelineJob: Job? = null
  @Volatile private var pauseRequested = false
  @Volatile private var cancelRequested = false

  init {
    refreshCapabilities()
    loadBundledAssets()
  }

  fun refreshCapabilities() {
    val report = OpenMontageFrameworkBridge.inspectCapabilities(ctx)
    _uiState.update { it.copy(capabilityReport = report) }
  }

  fun loadBundledAssets() {
    val list = StudioMediaLibrary.listBundledAssets(ctx)
    _uiState.update { it.copy(bundledAssets = list) }
  }

  fun importUserMediaUris(uris: List<Uri>, forcedKind: UserMediaKind? = null) {
    if (uris.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
      val imported = uris.mapNotNull { StudioMediaLibrary.importUserUriToProjectCache(ctx, it, forcedKind) }
      if (imported.isNotEmpty()) {
        _uiState.update { st ->
          st.copy(
            userMediaItems = st.userMediaItems + imported,
            lastActionBanner = "${imported.size}টি আপনার গ্যালারি ছবি/ভিডিও/অডিও যোগ হয়েছে ✓",
          )
        }
      }
    }
  }

  fun removeUserMedia(id: String) {
    _uiState.update { st ->
      st.copy(userMediaItems = st.userMediaItems.filterNot { it.id == id })
    }
  }

  fun toggleBundledAssetSelection(fileName: String) {
    _uiState.update { st ->
      val current = st.selectedBundledAssetFiles
      val next = if (fileName in current) current - fileName else current + fileName
      st.copy(selectedBundledAssetFiles = next)
    }
  }

  fun clearSelectedBundledAssets() {
    _uiState.update { it.copy(selectedBundledAssetFiles = emptyList()) }
  }

  fun selectMotionChoreography(motion: MotionChoreography) {
    updateSettings { it.copy(preferredMotionStyle = motion) }
  }

  fun selectTalkingCharacterPreset(preset: TalkingCharacterPreset) {
    updateSettings { it.copy(preferredCharacterPreset = preset) }
  }

  fun updatePrompt(text: String) = _uiState.update { it.copy(promptInput = text) }

  fun selectVideoType(type: VideoType) = _uiState.update { it.copy(selectedVideoType = type) }

  fun updateCustomVideoType(text: String) = _uiState.update { it.copy(customVideoTypeInput = text) }

  fun selectDurationPreset(preset: DurationPreset) {
    _uiState.update {
      it.copy(
        selectedDurationPreset = preset,
        customDurationSec = preset.defaultSeconds,
      )
    }
  }

  fun updateCustomDurationSeconds(sec: Int) {
    _uiState.update { it.copy(customDurationSec = sec.coerceIn(10, 1800)) }
  }

  fun selectToolTab(tab: ProductionToolTab) = _uiState.update { it.copy(selectedToolTab = tab) }

  fun updateSettings(transform: (ProductionSettings) -> ProductionSettings) {
    _uiState.update { st ->
      val nextSettings = transform(st.settings)
      val updatedProj = st.activeProject?.copy(settings = nextSettings)
      st.copy(settings = nextSettings, activeProject = updatedProj)
    }
  }

  fun selectPreviewScene(idx: Int) = _uiState.update { it.copy(selectedPreviewSceneIndex = idx) }

  fun setShowPreviewDialog(show: Boolean) = _uiState.update { it.copy(showPreviewPlayerDialog = show) }

  fun setShowSettingsSheet(show: Boolean) = _uiState.update { it.copy(showSettingsSheet = show) }

  fun setShowOpenMontageDetails(show: Boolean) = _uiState.update { it.copy(showOpenMontageDetails = show) }

  fun clearBanner() = _uiState.update { it.copy(lastActionBanner = null) }

  fun startFullPipeline(allowNetworkCloudTry: Boolean = true) {
    pipelineJob?.cancel()
    pauseRequested = false
    cancelRequested = false
    pipelineJob = viewModelScope.launch(Dispatchers.IO) {
      runPipelineInternal(startFromStageIndex = 0, allowNetworkCloudTry = allowNetworkCloudTry)
    }
  }

  fun pausePipeline() {
    if (_uiState.value.progress.status != PipelineRunStatus.RUNNING) return
    pauseRequested = true
    _uiState.update {
      it.copy(
        progress = it.progress.copy(
          status = PipelineRunStatus.PAUSED,
          statusMessageBn = "পাইপলাইন বিরতিতে (Paused) — Resume চাপলে চলবে।",
        )
      )
    }
  }

  fun resumePipeline() {
    if (_uiState.value.progress.status != PipelineRunStatus.PAUSED) return
    pauseRequested = false
    _uiState.update {
      it.copy(
        progress = it.progress.copy(
          status = PipelineRunStatus.RUNNING,
          statusMessageBn = "পাইপলাইন পুনরায় চলছে...",
        )
      )
    }
  }

  fun cancelPipeline() {
    cancelRequested = true
    pauseRequested = false
    pipelineJob?.cancel()
    AndroidVideoRendererAndExporter.stopPreviewAudio()
    _uiState.update {
      it.copy(
        progress = it.progress.copy(
          status = PipelineRunStatus.CANCELLED,
          statusMessageBn = "প্রোডাকশন বাতিল করা হয়েছে।",
        )
      )
    }
  }

  fun retryPipeline(allowNetworkCloudTry: Boolean = true) {
    val failedIdx = _uiState.value.progress.failedStage?.ordinal ?: 0
    pipelineJob?.cancel()
    pauseRequested = false
    cancelRequested = false
    pipelineJob = viewModelScope.launch(Dispatchers.IO) {
      runPipelineInternal(startFromStageIndex = failedIdx, allowNetworkCloudTry = allowNetworkCloudTry)
    }
  }

  private suspend fun checkpointWaitIfPaused() {
    while (pauseRequested && !cancelRequested) {
      delay(200L)
    }
    if (cancelRequested) throw CancellationException("User cancelled video pipeline")
  }

  suspend fun runPipelineSynchronouslyForTest(allowNetworkCloudTry: Boolean = false) {
    pauseRequested = false
    cancelRequested = false
    runPipelineInternal(startFromStageIndex = 0, allowNetworkCloudTry = allowNetworkCloudTry)
  }

  private suspend fun runPipelineInternal(startFromStageIndex: Int, allowNetworkCloudTry: Boolean) {
    val st0 = _uiState.value
    val prompt = st0.promptInput.trim().ifBlank { VideoStudioCatalog.DEFAULT_EXAMPLE_PROMPT }
    val slug = buildSlug(prompt)
    val projectId = "vid_${System.currentTimeMillis()}"

    var workingProj = st0.activeProject?.takeIf { startFromStageIndex > 0 } ?: VideoProject(
      id = projectId,
      slug = slug,
      title = "LXR Studio Project",
      prompt = prompt,
      videoType = st0.selectedVideoType,
      customTypeLabel = st0.customVideoTypeInput,
      durationPreset = st0.selectedDurationPreset,
      customDurationSeconds = st0.customDurationSec,
      settings = st0.settings,
    )

    val completed =
      if (startFromStageIndex > 0) st0.progress.completedStages.toMutableSet() else mutableSetOf()

    _uiState.update {
      it.copy(
        activeProject = workingProj,
        progress = StudioProgressState(
          activeStage = PipelineStage.TOPIC_ANALYSIS,
          status = PipelineRunStatus.RUNNING,
          statusMessageBn = "১/১২: বিষয় বিশ্লেষণ শুরু হচ্ছে...",
          completedStages = completed,
        ),
      )
    }

    var currentStage = PipelineStage.TOPIC_ANALYSIS
    try {
      var blueprint: GeneratedStoryBlueprint? = null
      var voiceRes: VoiceoverSynthesisResult? = null
      var bgmPcm = ShortArray(0)
      var srtText = ""
      var mixedPcm = ShortArray(0)
      var mp4Outcome: RenderOutcome? = null

      // Stages 1–4: Topic Analysis, Script, Scene Plan, Character Plan
      if (startFromStageIndex <= PipelineStage.CHARACTER_PLANNING.ordinal) {
        currentStage = PipelineStage.TOPIC_ANALYSIS
        checkpointWaitIfPaused()
        updateStageProgress(currentStage, script = 0.25f, msg = "১/১২: প্রম্পট ও OpenMontage টপিক বিশ্লেষণ চলছে...")
        blueprint = VideoScriptAndMediaGenerator.generateTopicAndScript(
          context = ctx,
          prompt = prompt,
          videoType = workingProj.videoType,
          customTypeLabel = workingProj.customTypeLabel,
          durationPreset = workingProj.durationPreset,
          customDurationSec = workingProj.customDurationSeconds,
          settings = workingProj.settings,
          userMediaItems = st0.userMediaItems,
          selectedBundledFiles = st0.selectedBundledAssetFiles,
        )
        completed.add(PipelineStage.TOPIC_ANALYSIS)

        currentStage = PipelineStage.SCRIPT_GENERATION
        checkpointWaitIfPaused()
        updateStageProgress(currentStage, completed, script = 0.70f, msg = "২/১২: সম্পূর্ণ স্ক্রিপ্ট ও সংলাপ তৈরি হচ্ছে...")
        workingProj = workingProj.copy(
          title = blueprint.title,
          topicAnalysisSummary = blueprint.topicAnalysisSummary,
          fullScriptText = blueprint.fullScriptText,
          bgmMoodLabel = blueprint.bgmMoodLabel,
        )
        completed.add(PipelineStage.SCRIPT_GENERATION)

        currentStage = PipelineStage.SCENE_PLANNING
        checkpointWaitIfPaused()
        updateStageProgress(currentStage, completed, script = 1f, scene = 0.45f, msg = "৩/১২: দৃশ্য (Scene) টাইমলাইন পরিকল্পনা চলছে...")
        workingProj = workingProj.copy(scenes = blueprint.scenes)
        completed.add(PipelineStage.SCENE_PLANNING)

        currentStage = PipelineStage.CHARACTER_PLANNING
        checkpointWaitIfPaused()
        updateStageProgress(currentStage, completed, script = 1f, scene = 0.65f, msg = "৪/১২: চরিত্র (Character) ও কণ্ঠ ম্যাচিং সম্পন্ন হচ্ছে...")
        workingProj = workingProj.copy(
          characters = blueprint.characters,
          logs = workingProj.logs + "[AI] ${blueprint.usedAiEngine} • ${blueprint.scenes.size} scenes ready",
        )
        completed.add(PipelineStage.CHARACTER_PLANNING)
        _uiState.update { it.copy(activeProject = workingProj) }
      }

      // Stage 5: Visual & Animation Scene Artwork Generation
      currentStage = PipelineStage.IMAGE_VISUAL_GEN
      checkpointWaitIfPaused()
      updateStageProgress(currentStage, completed, script = 1f, scene = 0.8f, image = 0.4f, msg = "৫/১২: AI অ্যানিমেশন দৃশ্য ও চরিত্রের ফ্রেম তৈরি হচ্ছে...")
      val framePaths = AndroidVideoRendererAndExporter.renderSceneThumbnails(ctx, workingProj)
      completed.add(PipelineStage.IMAGE_VISUAL_GEN)
      workingProj = workingProj.copy(
        previewFramePaths = framePaths,
        logs = workingProj.logs + "[Visuals] Rendered ${framePaths.size} animated scene canvases",
      )
      updateStageProgress(currentStage, completed, script = 1f, scene = 0.9f, image = 1f, msg = "৫/১২: দৃশ্যসমূহের ভিজ্যুয়াল ও অ্যানিমেশন ফ্রেম প্রস্তুত ✓")
      _uiState.update { it.copy(activeProject = workingProj) }

      // Stage 6: Free Voiceover Generation (7 Google Voice Models + Offline Piper/Formant Fallback)
      currentStage = PipelineStage.VOICEOVER_GEN
      checkpointWaitIfPaused()
      updateStageProgress(currentStage, completed, script = 1f, scene = 0.95f, image = 1f, voice = 0.35f, msg = "৬/১২: ফ্রি Google ভয়েস মডেল / অফলাইন ভয়েস তৈরি হচ্ছে...")
      voiceRes = VideoScriptAndMediaGenerator.synthesizeSceneVoiceoverPcm(
        context = ctx,
        scenes = workingProj.scenes,
        settings = workingProj.settings,
        allowNetworkCloudTry = allowNetworkCloudTry,
        userAudioItems = st0.userMediaItems.filter { it.kind == UserMediaKind.AUDIO },
      )
      completed.add(PipelineStage.VOICEOVER_GEN)
      updateStageProgress(currentStage, completed, script = 1f, scene = 1f, image = 1f, voice = 1f, msg = "৬/১২: ভয়েসওভার সম্পন্ন (${voiceRes.engineLabel})")

      // Stage 7: Free Background Music Generation
      currentStage = PipelineStage.BACKGROUND_MUSIC
      checkpointWaitIfPaused()
      updateStageProgress(currentStage, completed, script = 1f, scene = 1f, image = 1f, voice = 1f, editing = 0.25f, msg = "৭/১২: কপিরাইট-মুক্ত ব্যাকগ্রাউন্ড মিউজিক তৈরি হচ্ছে...")
      val totalSamples = voiceRes.pcm24kMono.size.coerceAtLeast(24000 * 4)
      bgmPcm = VideoScriptAndMediaGenerator.generateMoodAmbientBgmPcm(totalSamples, voiceRes.sampleRate)
      completed.add(PipelineStage.BACKGROUND_MUSIC)

      // Stage 8: Subtitle (.SRT) Generation
      currentStage = PipelineStage.SUBTITLE_GEN
      checkpointWaitIfPaused()
      updateStageProgress(currentStage, completed, script = 1f, scene = 1f, image = 1f, voice = 1f, editing = 0.5f, msg = "৮/১২: টাইম-সিঙ্ক করা সাবটাইটেল (.SRT) তৈরি হচ্ছে...")
      val cues = VideoScriptAndMediaGenerator.buildSubtitleCues(workingProj.scenes)
      srtText = VideoScriptAndMediaGenerator.buildSrtContent(cues)
      completed.add(PipelineStage.SUBTITLE_GEN)
      workingProj = workingProj.copy(subtitles = cues)

      // Stage 9: Scene Editing & Arrangement
      currentStage = PipelineStage.SCENE_EDITING
      checkpointWaitIfPaused()
      updateStageProgress(currentStage, completed, script = 1f, scene = 1f, image = 1f, voice = 1f, editing = 0.78f, msg = "৯/১২: দৃশ্য ট্রিম, ট্রানজিশন ও টেক্সট ওভারলে সেট হচ্ছে...")
      val refreshedScript = VideoScriptAndMediaGenerator.formatFullScriptText(
        workingProj.title,
        workingProj.topicAnalysisSummary,
        workingProj.characters,
        workingProj.scenes,
      )
      workingProj = workingProj.copy(
        fullScriptText = refreshedScript,
        pipelineYaml = OpenMontageFrameworkBridge.buildPipelineYaml(workingProj),
        remotionTsx = OpenMontageFrameworkBridge.buildRemotionTsx(workingProj),
      )
      completed.add(PipelineStage.SCENE_EDITING)

      // Stage 10: Audio Sync (Voice + BGM Mix)
      currentStage = PipelineStage.AUDIO_SYNC
      checkpointWaitIfPaused()
      updateStageProgress(currentStage, completed, script = 1f, scene = 1f, image = 1f, voice = 1f, editing = 1f, msg = "১০/১২: ভয়েস, মিউজিক ও সাউন্ড এফেক্ট সিঙ্ক হচ্ছে...")
      mixedPcm = VideoScriptAndMediaGenerator.mixAudioTracks(
        voicePcm = voiceRes.pcm24kMono,
        bgmPcm = bgmPcm,
        enableVoice = workingProj.settings.voiceoverEnabled,
        enableBgm = workingProj.settings.backgroundMusicEnabled,
      )
      completed.add(PipelineStage.AUDIO_SYNC)

      // Stage 11: Final MP4 Rendering
      currentStage = PipelineStage.FINAL_RENDER
      checkpointWaitIfPaused()
      updateStageProgress(currentStage, completed, script = 1f, scene = 1f, image = 1f, voice = 1f, editing = 1f, rendering = 0.1f, msg = "১১/১২: চূড়ান্ত MP4 ভিডিও রেন্ডার হচ্ছে...")
      mp4Outcome = AndroidVideoRendererAndExporter.renderMp4Video(
        context = ctx,
        project = workingProj,
        mixedPcmMono = mixedPcm,
        audioSampleRate = voiceRes.sampleRate,
      ) { frac ->
        _uiState.update { st ->
          st.copy(
            progress = st.progress.copy(
              renderingProgress = (0.1f + frac * 0.75f).coerceIn(0.1f, 0.85f),
            )
          )
        }
      }
      completed.add(PipelineStage.FINAL_RENDER)

      // Stage 12: Multi-format Export (MP4, TXT, WAV, SRT, OpenMontage ZIP)
      currentStage = PipelineStage.MP4_EXPORT
      checkpointWaitIfPaused()
      updateStageProgress(currentStage, completed, script = 1f, scene = 1f, image = 1f, voice = 1f, editing = 1f, rendering = 0.92f, msg = "১২/১২: Downloads/LXR-VideoStudio ফোল্ডারে সব ফাইল সেভ হচ্ছে...")
      val voiceWavBytes = VideoScriptAndMediaGenerator.encodePcm16MonoToWavBytes(
        voiceRes.pcm24kMono,
        voiceRes.sampleRate,
      )
      val artifacts = AndroidVideoRendererAndExporter.exportAllArtifacts(
        context = ctx,
        project = workingProj,
        mp4InternalFile = mp4Outcome.mp4File,
        voiceWavBytes = voiceWavBytes,
        srtContent = srtText,
        completedStages = completed + PipelineStage.MP4_EXPORT,
        voiceEngineLabel = voiceRes.engineLabel,
        renderEngineLabel = mp4Outcome.engineLabel,
      )
      completed.add(PipelineStage.MP4_EXPORT)

      val termuxPath = File(artifacts.workspaceDirPath, "lxr_openmontage_termux.sh")
      val finalProj = workingProj.copy(
        artifacts = artifacts,
        termuxScript = if (termuxPath.exists()) termuxPath.readText(Charsets.UTF_8) else "",
        logs = workingProj.logs + "[Export] Saved MP4 (${artifacts.mp4SizeBytes / 1024} KB) + TXT + WAV + SRT + OpenMontage ZIP to ${artifacts.publicDownloadFolderLabel}",
      )

      _uiState.update { st ->
        st.copy(
          activeProject = finalProj,
          savedProjects = listOf(finalProj) + st.savedProjects.filterNot { it.id == finalProj.id },
          totalVideosCreatedCount = st.totalVideosCreatedCount + 1,
          progress = StudioProgressState(
            activeStage = PipelineStage.MP4_EXPORT,
            status = PipelineRunStatus.COMPLETED,
            scriptProgress = 1f,
            voiceProgress = 1f,
            imageProgress = 1f,
            sceneProgress = 1f,
            editingProgress = 1f,
            renderingProgress = 1f,
            statusMessageBn = "ভিডিও তৈরি সম্পন্ন! ${artifacts.publicDownloadFolderLabel}-এ সেভ হয়েছে ✓",
            completedStages = completed,
          ),
          lastActionBanner = "ভিডিও, স্ক্রিপ্ট, ভয়েস, সাবটাইটেল ও প্রজেক্ট সেভ হয়েছে ✓",
        )
      }
    } catch (c: CancellationException) {
      // Cancelled cleanly via Cancel button
      throw c
    } catch (e: Throwable) {
      _uiState.update { st ->
        st.copy(
          progress = st.progress.copy(
            status = PipelineRunStatus.FAILED,
            failedStage = currentStage,
            errorMessage = e.message ?: "অজানা ত্রুটি",
            statusMessageBn = "${currentStage.titleBn} পর্যায়ে সমস্যা হয়েছে — Retry চাপুন",
          )
        )
      }
    }
  }

  private fun updateStageProgress(
    stage: PipelineStage,
    completed: Set<PipelineStage> = emptySet(),
    script: Float = _uiState.value.progress.scriptProgress,
    voice: Float = _uiState.value.progress.voiceProgress,
    image: Float = _uiState.value.progress.imageProgress,
    scene: Float = _uiState.value.progress.sceneProgress,
    editing: Float = _uiState.value.progress.editingProgress,
    rendering: Float = _uiState.value.progress.renderingProgress,
    msg: String,
  ) {
    _uiState.update { st ->
      st.copy(
        progress = st.progress.copy(
          activeStage = stage,
          status = PipelineRunStatus.RUNNING,
          scriptProgress = script,
          voiceProgress = voice,
          imageProgress = image,
          sceneProgress = scene,
          editingProgress = editing,
          renderingProgress = rendering,
          statusMessageBn = msg,
          completedStages = completed.toSet(),
        )
      )
    }
  }

  fun updateSceneScript(sceneIdx: Int, newNarration: String, newDialogue: String) {
    _uiState.update { st ->
      val proj = st.activeProject ?: return@update st
      val updatedScenes = proj.scenes.map { sc ->
        if (sc.index == sceneIdx) sc.copy(narrationText = newNarration, dialogueLine = newDialogue) else sc
      }
      val newFullScript = VideoScriptAndMediaGenerator.formatFullScriptText(
        proj.title,
        proj.topicAnalysisSummary,
        proj.characters,
        updatedScenes,
      )
      st.copy(activeProject = proj.copy(scenes = updatedScenes, fullScriptText = newFullScript))
    }
  }

  fun updateSceneVoice(sceneIdx: Int, voiceId: String, emotion: String) {
    _uiState.update { st ->
      val proj = st.activeProject ?: return@update st
      val updatedScenes = proj.scenes.map { sc ->
        if (sc.index == sceneIdx) sc.copy(voiceId = voiceId, emotionalTone = emotion) else sc
      }
      st.copy(activeProject = proj.copy(scenes = updatedScenes))
    }
  }

  fun updateSceneMotionAndCharacter(
    sceneIdx: Int,
    motion: MotionChoreography,
    preset: TalkingCharacterPreset,
    assetFile: String? = null,
  ) {
    _uiState.update { st ->
      val proj = st.activeProject ?: return@update st
      val updatedScenes = proj.scenes.map { sc ->
        if (sc.index == sceneIdx) {
          sc.copy(
            motionStyle = motion,
            characterPreset = preset,
            matchedAssetFile = assetFile ?: sc.matchedAssetFile,
            effectTag = "${motion.labelBn} • ${preset.labelBn}",
          )
        } else sc
      }
      st.copy(activeProject = proj.copy(scenes = updatedScenes))
    }
  }

  fun updateSceneTrimAndTransition(sceneIdx: Int, trimStart: Float, trimEnd: Float, transition: TransitionEffect) {
    _uiState.update { st ->
      val proj = st.activeProject ?: return@update st
      val updatedScenes = proj.scenes.map { sc ->
        if (sc.index == sceneIdx) {
          sc.copy(
            trimStartSec = trimStart.coerceIn(0f, (sc.durationSec - 2f).coerceAtLeast(0f)),
            trimEndSec = trimEnd.coerceIn(2f, sc.durationSec),
            transition = transition,
          )
        } else sc
      }
      st.copy(activeProject = proj.copy(scenes = updatedScenes))
    }
  }

  fun moveSceneOrder(fromPos: Int, delta: Int) {
    _uiState.update { st ->
      val proj = st.activeProject ?: return@update st
      val list = proj.scenes.toMutableList()
      val target = (fromPos + delta).coerceIn(0, list.lastIndex)
      if (fromPos !in list.indices || target == fromPos) return@update st
      val item = list.removeAt(fromPos)
      list.add(target, item)
      val reindexed = list.mapIndexed { i, sc -> sc.copy(index = i + 1) }
      st.copy(activeProject = proj.copy(scenes = reindexed))
    }
  }

  fun reRenderWithEdits() {
    val proj = _uiState.value.activeProject ?: return
    pipelineJob?.cancel()
    pauseRequested = false
    cancelRequested = false
    pipelineJob = viewModelScope.launch(Dispatchers.IO) {
      runPipelineInternal(startFromStageIndex = PipelineStage.IMAGE_VISUAL_GEN.ordinal, allowNetworkCloudTry = true)
    }
  }

  fun previewGoogleVoice(voiceId: String, emotion: String = "Warm") {
    if (_uiState.value.isVoicePreviewPlaying) {
      AndroidVideoRendererAndExporter.stopPreviewAudio()
      _uiState.update { it.copy(isVoicePreviewPlaying = false) }
      return
    }
    _uiState.update { it.copy(isVoicePreviewPlaying = true) }
    viewModelScope.launch(Dispatchers.IO) {
      val sampleScene = _uiState.value.activeProject?.scenes?.firstOrNull()?.copy(
        voiceId = voiceId,
        emotionalTone = emotion,
      ) ?: VideoScenePlan(
        index = 1,
        title = "Voice Sample",
        narrationText = "হ্যালো বস! আমি $voiceId ভয়েস মডেল — LXR এআই স্টুডিও থেকে আপনার ভিডিওর জন্য প্রস্তুত।",
        dialogueLine = "",
        characterName = voiceId,
        voiceId = voiceId,
        emotionalTone = emotion,
        visualPrompt = "",
        themeKeyword = "space_kid",
        visualSource = VisualSourceKind.AI_SCENE_ART,
        effectTag = "Starfield",
        overlayHeadline = voiceId,
        durationSec = 4f,
      )
      val synth = VideoScriptAndMediaGenerator.synthesizeSceneVoiceoverPcm(
        context = ctx,
        scenes = listOf(sampleScene),
        settings = _uiState.value.settings.copy(selectedVoiceId = voiceId, useCharacterVoicesPerScene = false),
        allowNetworkCloudTry = true,
      )
      AndroidVideoRendererAndExporter.playPcmPreviewAsync(synth.pcm24kMono, synth.sampleRate) {
        _uiState.update { it.copy(isVoicePreviewPlaying = false) }
      }
    }
  }

  fun runOpenMontageInTermux() {
    val proj = _uiState.value.activeProject
    val scriptPath = proj?.artifacts?.workspaceDirPath?.let { File(it, "lxr_openmontage_termux.sh") }
    if (scriptPath == null || !scriptPath.exists()) {
      _uiState.update { it.copy(lastActionBanner = "আগে প্রজেক্ট তৈরি করুন, তারপর Termux-এ OpenMontage চালান।") }
      return
    }
    val res = OpenMontageFrameworkBridge.dispatchOpenMontageToTermux(ctx, scriptPath)
    _uiState.update {
      it.copy(
        lastActionBanner = res.getOrElse { err -> err.message ?: "Termux চালু করা যায়নি" }
      )
    }
  }

  private fun buildSlug(prompt: String): String {
    val ascii = prompt.lowercase(Locale.US)
      .replace(Regex("[^a-z0-9]+"), "_")
      .trim('_')
      .take(22)
    val suffix = (System.currentTimeMillis() % 10_000L).toString()
    return if (ascii.length >= 3) "lxr_${ascii}_$suffix" else "lxr_video_$suffix"
  }

  override fun onCleared() {
    super.onCleared()
    AndroidVideoRendererAndExporter.stopPreviewAudio()
    pipelineJob?.cancel()
  }
}
