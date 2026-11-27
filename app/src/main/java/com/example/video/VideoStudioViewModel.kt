package com.example.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.service.LxrStudioHandoff
import com.example.service.LxrUsageLedger
import com.example.service.RiyaVoiceManager
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
    val library = CharacterLibraryStore.loadLibrary(ctx)
    val series = SeriesStoryBibleManager.loadSeriesProjects(ctx)
    val defaultCast = library.take(2).map { it.id }
    _uiState.update {
      it.copy(
        videoProviders = OpenMontageFrameworkBridge.VIDEO_GENERATION_PROVIDER_REGISTRY,
        characterLibrary = library,
        selectedCastIds = defaultCast,
        seriesProjects = series,
        activeSeriesId = series.firstOrNull()?.seriesId,
      )
    }
  }

  fun refreshCapabilities() {
    val report = OpenMontageFrameworkBridge.inspectCapabilities(ctx)
    _uiState.update {
      it.copy(
        capabilityReport = report,
        videoProviders = OpenMontageFrameworkBridge.VIDEO_GENERATION_PROVIDER_REGISTRY,
      )
    }
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

  fun selectProductionMode(mode: VideoProductionMode) {
    updateSettings { it.copy(productionMode = mode) }
  }

  fun requestSelectVideoProvider(providerId: String) {
    val entry = OpenMontageFrameworkBridge.findProvider(providerId) ?: return
    if (entry.billingTier.isPaidApi && !_uiState.value.settings.paidProviderConsentGranted) {
      _uiState.update { it.copy(pendingPaidProviderConsentId = providerId) }
    } else {
      updateSettings { it.copy(selectedVideoProviderId = providerId) }
    }
  }

  fun confirmPaidProviderConsent(providerId: String, pexelsKey: String = "", pixabayKey: String = "") {
    updateSettings { st ->
      st.copy(
        selectedVideoProviderId = providerId,
        paidProviderConsentGranted = true,
        pexelsApiKey = pexelsKey.ifBlank { st.pexelsApiKey },
        pixabayApiKey = pixabayKey.ifBlank { st.pixabayApiKey },
      )
    }
    _uiState.update {
      it.copy(
        pendingPaidProviderConsentId = null,
        lastActionBanner = "অনুমতি নিশ্চিত হয়েছে: $providerId নির্বাচিত (API Key প্রয়োজন হবে)।",
      )
    }
  }

  fun declinePaidProviderConsent() {
    _uiState.update {
      it.copy(
        pendingPaidProviderConsentId = null,
        lastActionBanner = "ফ্রি-ফার্স্ট নীতি সক্রিয়: কোনো Paid API ব্যবহার করা হয়নি।",
      )
    }
  }

  fun updateFreeStockApiKeys(pexelsKey: String, pixabayKey: String) {
    updateSettings { it.copy(pexelsApiKey = pexelsKey.trim(), pixabayApiKey = pixabayKey.trim()) }
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

  fun toggleCartoonSeriesStudio(show: Boolean) {
    _uiState.update { it.copy(showCartoonSeriesStudio = show) }
  }

  fun toggleCastMember(charId: String) {
    _uiState.update { st ->
      val cur = st.selectedCastIds
      val next = when {
        charId in cur -> cur - charId
        cur.size >= 4 -> cur.drop(1) + charId
        else -> cur + charId
      }
      st.copy(selectedCastIds = next)
    }
  }

  fun createCustomCharacter(
    name: String,
    roleBn: String,
    category: CharacterCategory,
    ageGroup: CharacterAgeGroup,
    voiceId: String,
    personality: String,
    outfitStyle: String,
    referenceImagePath: String = "",
  ) {
    val created = CharacterLibraryStore.createCustomCharacter(
      name = name,
      roleBn = roleBn,
      category = category,
      ageGroup = ageGroup,
      voiceId = voiceId,
      personality = personality,
      outfitStyle = outfitStyle,
      referenceImagePath = referenceImagePath,
    )
    _uiState.update { st ->
      val nextLib = listOf(created) + st.characterLibrary
      CharacterLibraryStore.saveLibrary(ctx, nextLib)
      val nextCast = (st.selectedCastIds + created.id).distinct().takeLast(4)
      st.copy(
        characterLibrary = nextLib,
        selectedCastIds = nextCast,
        lastActionBanner = "নতুন চরিত্র '${created.name}' Character Library-তে সংরক্ষিত হয়েছে ✓",
      )
    }
  }

  fun attachReferenceImageToCharacter(charId: String, uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      val imported = StudioMediaLibrary.importUserUriToProjectCache(ctx, uri, UserMediaKind.IMAGE) ?: return@launch
      _uiState.update { st ->
        val nextLib = st.characterLibrary.map { ch ->
          if (ch.id == charId) ch.copy(referenceImagePath = imported.cachedFilePath) else ch
        }
        CharacterLibraryStore.saveLibrary(ctx, nextLib)
        st.copy(
          characterLibrary = nextLib,
          userMediaItems = st.userMediaItems + imported,
          lastActionBanner = "Reference ছবি চরিত্রে যুক্ত হয়েছে — কথা বললে চোখ, ঠোঁট ও মাথা নড়বে ✓",
        )
      }
    }
  }

  fun selectAnimationFormat(format: AnimationFormat) {
    _uiState.update { st ->
      st.copy(
        selectedVideoType = format.recommendedVideoType,
        customDurationSec = format.defaultDurationSec,
        settings = st.settings.copy(
          animationFormat = format,
          aspectRatio = format.defaultAspectRatio,
        ),
      )
    }
  }

  fun selectCameraShot(shot: CameraShotType) {
    updateSettings { it.copy(preferredCameraShot = shot) }
  }

  fun selectEnvironment(env: EnvironmentSetting) {
    updateSettings { it.copy(preferredEnvironment = env) }
  }

  fun selectWeatherMood(mood: TimeWeatherMood) {
    updateSettings { it.copy(preferredWeatherMood = mood) }
  }

  fun selectCharacterEmotion(emotion: CharacterEmotion) {
    updateSettings { it.copy(preferredEmotion = emotion) }
  }

  fun applyStoryTemplate(template: StoryActivityTemplate) {
    _uiState.update { st ->
      st.copy(
        promptInput = template.promptBn,
        selectedVideoType = template.format.recommendedVideoType,
        settings = st.settings.copy(
          animationFormat = template.format,
          preferredEnvironment = template.environment,
          preferredMotionStyle = template.motion,
          preferredEmotion = template.emotion,
        ),
        lastActionBanner = "'${template.titleBn}' টেমপ্লেট প্রয়োগ হয়েছে ✓",
      )
    }
  }

  fun selectSeriesProject(seriesId: String?) {
    _uiState.update { it.copy(activeSeriesId = seriesId) }
  }

  fun createSeriesProject(seriesTitle: String, genreBn: String, storyBibleSummary: String) {
    val cleanTitle = seriesTitle.trim().ifBlank { "নতুন ৩ডি কার্টুন সিরিজ" }
    val created = CartoonSeriesProject(
      seriesId = "series_${System.currentTimeMillis() % 1_000_000L}",
      seriesTitle = cleanTitle,
      genreBn = genreBn.trim().ifBlank { "নাটক, স্কুল ও পারিবারিক" },
      storyBibleSummary = storyBibleSummary.trim().ifBlank { "স্থায়ী চরিত্র ও ধারাবাহিক পর্বের গল্প" },
      characterIds = _uiState.value.selectedCastIds,
    )
    _uiState.update { st ->
      val nextSeries = listOf(created) + st.seriesProjects
      SeriesStoryBibleManager.saveSeriesProjects(ctx, nextSeries)
      st.copy(
        seriesProjects = nextSeries,
        activeSeriesId = created.seriesId,
        lastActionBanner = "নতুন সিরিজ '$cleanTitle' Story Bible সহ তৈরি হয়েছে ✓",
      )
    }
  }

  fun generateNextEpisodeForSeries(episodeIdea: String, allowNetworkCloudTry: Boolean = true) {
    val st = _uiState.value
    val series = st.seriesProjects.firstOrNull { it.seriesId == st.activeSeriesId }
      ?: st.seriesProjects.firstOrNull()
      ?: return
    val cast = st.characterLibrary.filter { it.id in st.selectedCastIds }
      .ifEmpty { st.characterLibrary.filter { it.id in series.characterIds } }
    val continuityPrompt = SeriesStoryBibleManager.buildEpisodeContinuityPrompt(
      series = series,
      cast = cast,
      userIdea = episodeIdea.ifBlank { "আগের পর্বের পর নতুন ঘটনা ও চমক নিয়ে এগিয়ে চলা" },
    )
    _uiState.update {
      it.copy(
        promptInput = continuityPrompt,
        activeSeriesId = series.seriesId,
        selectedVideoType = VideoType.DRAMA_SERIAL,
        settings = it.settings.copy(
          animationFormat = AnimationFormat.CARTOON_SERIAL,
          productionMode = VideoProductionMode.REAL_AI_VIDEO,
        ),
      )
    }
    startFullPipeline(allowNetworkCloudTry = allowNetworkCloudTry)
  }

  fun startFullPipeline(allowNetworkCloudTry: Boolean = true) {
    pipelineJob?.cancel()
    pauseRequested = false
    cancelRequested = false
    pipelineJob = viewModelScope.launch(Dispatchers.IO) {
      runPipelineInternal(startFromStageIndex = 0, allowNetworkCloudTry = allowNetworkCloudTry)
    }
  }

  fun launch30SecondShowcaseTest(allowNetworkCloudTry: Boolean = true) {
    _uiState.update {
      it.copy(
        promptInput = "সৌরশক্তি ও ভবিষ্যতের পরিবেশ: দুটি চরিত্র হেঁটে-দৌড়ে বাংলা ও ইংরেজিতে কথা বলছে, সাথে 3D অ্যানিমেটেড চার্ট, কাইনেটিক টাইপোগ্রাফি ও প্রাকৃতিক দৃশ্য!",
        selectedVideoType = VideoType.ANIMATION,
        selectedDurationPreset = DurationPreset.SHORT_15_60S,
        customDurationSec = 30,
        settings = it.settings.copy(
          productionMode = VideoProductionMode.HYBRID_ALL_THREE,
          subtitlesEnabled = true,
          backgroundMusicEnabled = true,
          voiceoverEnabled = true,
          lipSyncEnabled = true,
          preferredMotionStyle = MotionChoreography.AUTO,
        ),
      )
    }
    startFullPipeline(allowNetworkCloudTry = allowNetworkCloudTry)
  }

  fun launch14RequirementSeriesTest(allowNetworkCloudTry: Boolean = true) {
    _uiState.update { st ->
      val multiCast = st.characterLibrary.take(4).map { it.id }
      val epNum = st.seriesProjects.firstOrNull { it.seriesId == st.activeSeriesId }?.nextEpisodeNumber ?: 1
      st.copy(
        selectedCastIds = multiCast,
        promptInput = "Episode %02d: স্কুল মাঠে ও ক্লাসরুমে ৩-৪ জন চরিত্র (মায়া, রাফি, ফারহানা ম্যাম, পিকু) হাঁটাচলা, হাত নেড়ে কথা বলা, লিপ-সিঙ্ক ও দলীয় ছন্দময় নাচ-গান করছে!".format(epNum),
        selectedVideoType = VideoType.DRAMA_SERIAL,
        selectedDurationPreset = DurationPreset.SHORT_15_60S,
        customDurationSec = 20,
        settings = st.settings.copy(
          animationFormat = AnimationFormat.CARTOON_SERIAL,
          productionMode = VideoProductionMode.REAL_AI_VIDEO,
          preferredEnvironment = EnvironmentSetting.CLASSROOM,
          preferredEmotion = CharacterEmotion.HAPPY,
          preferredMotionStyle = MotionChoreography.GROUP_DANCE,
          lipSyncEnabled = true,
          subtitlesEnabled = true,
          backgroundMusicEnabled = true,
          voiceoverEnabled = true,
        ),
      )
    }
    startFullPipeline(allowNetworkCloudTry = allowNetworkCloudTry)
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
    val renderStartMs = System.currentTimeMillis()
    val st0 = _uiState.value
    val prompt = st0.promptInput.trim().ifBlank { VideoStudioCatalog.DEFAULT_EXAMPLE_PROMPT }
    val slug = buildSlug(prompt)
    val projectId = "vid_${System.currentTimeMillis()}"
    val activeSeries = st0.seriesProjects.firstOrNull { it.seriesId == st0.activeSeriesId }
    val epNum = activeSeries?.nextEpisodeNumber ?: 1
    val continuityNote = activeSeries?.episodes?.lastOrNull()?.let { prev ->
      "EP%02d (%s) এর পরের অংশ: %s".format(prev.episodeNumber, prev.title, prev.endingHookBn)
    } ?: activeSeries?.storyBibleSummary.orEmpty()
    val selectedCast = st0.characterLibrary.filter { it.id in st0.selectedCastIds }

    var workingProj = st0.activeProject?.takeIf { startFromStageIndex > 0 } ?: VideoProject(
      id = projectId,
      slug = slug,
      title = "LXR Studio Project",
      prompt = prompt,
      videoType = st0.selectedVideoType,
      animationFormat = st0.settings.animationFormat,
      customTypeLabel = st0.customVideoTypeInput,
      durationPreset = st0.selectedDurationPreset,
      customDurationSeconds = st0.customDurationSec,
      seriesId = activeSeries?.seriesId.orEmpty(),
      episodeNumber = epNum,
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
          selectedCast = selectedCast,
          animationFormat = st0.settings.animationFormat,
          episodeNumber = epNum,
          seriesContinuityNote = continuityNote,
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

      // Stage 5: Free Real Stock Footage + Motion Graphics + Animated Character Preview Frames
      currentStage = PipelineStage.IMAGE_VISUAL_GEN
      checkpointWaitIfPaused()
      updateStageProgress(currentStage, completed, script = 1f, scene = 0.8f, image = 0.35f, msg = "৫/১২: ফ্রি স্টক ভিডিও, আর্টিকুলেটেড চরিত্র ও মোশন গ্রাফিক্স প্রস্তুত হচ্ছে...")
      val scenesWithStock = VideoScriptAndMediaGenerator.attachRealStockFootageToScenes(
        context = ctx,
        scenes = workingProj.scenes,
        settings = workingProj.settings,
        allowNetworkCloudTry = allowNetworkCloudTry,
      )
      workingProj = workingProj.copy(scenes = scenesWithStock)
      val framePaths = AndroidVideoRendererAndExporter.renderSceneThumbnails(ctx, workingProj)
      completed.add(PipelineStage.IMAGE_VISUAL_GEN)
      val stockHits = scenesWithStock.count { it.realFootageSourceLabel.isNotBlank() }
      workingProj = workingProj.copy(
        previewFramePaths = framePaths,
        logs = workingProj.logs + "[Visuals] Rendered ${framePaths.size} animated scenes • Real free stock matched: $stockHits",
      )
      updateStageProgress(currentStage, completed, script = 1f, scene = 0.9f, image = 1f, msg = "৫/১২: চলমান দৃশ্য ও মোশন গ্রাফিক্স ফ্রেম প্রস্তুত ✓")
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

      // Stage 12: Video Quality Gate Verification + Multi-format Export
      currentStage = PipelineStage.MP4_EXPORT
      checkpointWaitIfPaused()
      updateStageProgress(currentStage, completed, script = 1f, scene = 1f, image = 1f, voice = 1f, editing = 1f, rendering = 0.90f, msg = "১২/১২: Quality Gate মোশন যাচাই ও ফাইল এক্সপোর্ট হচ্ছে...")
      val qualityReport = VideoQualityGate.evaluateRenderedVideo(
        context = ctx,
        project = workingProj,
        mp4File = mp4Outcome.mp4File,
        voicePcm = voiceRes.pcm24kMono,
        sampleRate = voiceRes.sampleRate,
      )
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
        qualityReport = qualityReport,
        termuxScript = if (termuxPath.exists()) termuxPath.readText(Charsets.UTF_8) else "",
        logs = workingProj.logs +
          "[QualityGate] ${qualityReport.verdictBn}" +
          "[Export] Saved MP4 (${artifacts.mp4SizeBytes / 1024} KB) + TXT + WAV + SRT + OpenMontage ZIP to ${artifacts.publicDownloadFolderLabel}",
      )

      val renderSec = ((System.currentTimeMillis() - renderStartMs).coerceAtLeast(100L)) / 1000.0
      LxrUsageLedger.recordRenderMetrics(
        renderDurationSec = renderSec,
        outputBytes = artifacts.mp4SizeBytes,
      )
      LxrUsageLedger.refreshStorageUsage(ctx)
      LxrStudioHandoff.publishVideoHandoff(
        projectTitle = finalProj.title,
        scriptSummary = finalProj.topicAnalysisSummary,
        actualDurationSec = finalProj.customDurationSeconds,
        aspectRatioLabel = finalProj.settings.aspectRatio.labelBn,
        sceneHeadlines = finalProj.scenes.map { it.overlayHeadline },
        mp4Path = artifacts.mp4Path,
        publicFolderLabel = artifacts.publicDownloadFolderLabel,
      )

      _uiState.update { st ->
        val updatedSeries = if (finalProj.seriesId.isNotBlank()) {
          val nextList = st.seriesProjects.map { sp ->
            if (sp.seriesId == finalProj.seriesId) {
              val newEp = EpisodeRecord(
                episodeNumber = finalProj.episodeNumber,
                title = finalProj.title,
                summaryBn = finalProj.scenes.firstOrNull()?.narrationText ?: finalProj.topicAnalysisSummary,
                endingHookBn = finalProj.scenes.lastOrNull()?.dialogueLine ?: "পরবর্তী পর্ব শীঘ্রই আসছে!",
                sceneTitles = finalProj.scenes.map { it.title },
                mp4Path = artifacts.mp4Path,
              )
              sp.copy(
                episodes = sp.episodes + newEp,
                lastCliffhangerBn = newEp.endingHookBn,
                updatedAtMs = System.currentTimeMillis(),
              )
            } else sp
          }
          SeriesStoryBibleManager.saveSeriesProjects(ctx, nextList)
          nextList
        } else st.seriesProjects

        st.copy(
          activeProject = finalProj,
          savedProjects = listOf(finalProj) + st.savedProjects.filterNot { it.id == finalProj.id },
          seriesProjects = updatedSeries,
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
            statusMessageBn = "${qualityReport.verdictBn} • ${artifacts.publicDownloadFolderLabel}",
            completedStages = completed,
          ),
          lastActionBanner = qualityReport.verdictBn,
        )
      }
      runCatching {
        RiyaVoiceManager.initialize(ctx)
        RiyaVoiceManager.speak(
          "বস, '${finalProj.title}' ভিডিও তৈরি সম্পন্ন হয়েছে। টাইটেল, থাম্বনেইল বা YouTube SEO নিয়ে কোনো পরিবর্তন করতে চাইলে বলো।",
          interrupt = false,
          forceSpeak = false,
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

  fun sendActiveVideoToSeoStudio(onNavigate: (String) -> Unit) {
    val st = _uiState.value
    val proj = st.activeProject
    if (proj != null) {
      LxrStudioHandoff.publishVideoHandoff(
        projectTitle = proj.title,
        scriptSummary = proj.topicAnalysisSummary.ifBlank { proj.prompt },
        actualDurationSec = proj.customDurationSeconds,
        aspectRatioLabel = proj.settings.aspectRatio.labelBn,
        sceneHeadlines = proj.scenes.map { it.overlayHeadline },
        mp4Path = proj.artifacts.mp4Path,
        publicFolderLabel = proj.artifacts.publicDownloadFolderLabel,
      )
    } else {
      val cleanTopic = st.promptInput.trim().ifBlank { "LXR 3D Cartoon Video" }.take(80)
      LxrStudioHandoff.publishVideoHandoff(
        projectTitle = cleanTopic,
        scriptSummary = st.promptInput.trim(),
        actualDurationSec = st.customDurationSec,
        aspectRatioLabel = st.settings.aspectRatio.labelBn,
        sceneHeadlines = emptyList(),
        mp4Path = "",
        publicFolderLabel = "Download/LxR/Videos",
      )
    }
    onNavigate("seo_studio")
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
