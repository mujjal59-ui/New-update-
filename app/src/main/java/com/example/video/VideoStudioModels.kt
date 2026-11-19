package com.example.video

import com.example.data.Store
import com.example.data.VoiceGender

enum class VideoType(
  val labelBn: String,
  val labelEn: String,
  val pipelineYamlName: String,
  val defaultMood: String,
) {
  ANIMATION("Animation", "Animation", "animated-music-video.yaml", "Wonder & Playful"),
  ANIMATION_2D("2D Animation", "2D Animation", "character-animation.yaml", "Bright & Story"),
  ANIMATION_3D_STYLE("3D Style Animation", "3D Style Animation", "hybrid.yaml", "Cosmic & Epic"),
  CARTOON("Cartoon Video", "Cartoon Video", "character-animation.yaml", "Cheerful Cartoon"),
  STORYTELLING("Storytelling Video", "Storytelling Video", "narrative-arc.yaml", "Warm Narrative"),
  DRAMA_SERIAL("নাটক ও সিরিয়াল", "Drama & Serial", "cinematic.yaml", "Emotional Drama"),
  FASHION_360("৩৬০° ফ্যাশন ও মডেল", "360 Fashion Showcase", "showcase-360.yaml", "Runway 360 Glam"),
  MOTION_GRAPHICS("মোশন গ্রাফিক্স", "Motion Graphics", "explain-concept.yaml", "Dynamic Kinetic"),
  TALKING_PRODUCE("কথা বলা ফল/সবজি/ফুল", "Talking Produce & Flower", "talking-head.yaml", "Playful Expressive"),
  EDUCATIONAL("Educational Video", "Educational Video", "explain-concept.yaml", "Curious & Clear"),
  DOCUMENTARY("Documentary", "Documentary", "documentary-montage.yaml", "Atmospheric Doc"),
  EXPLAINER("Explainer Video", "Explainer Video", "explain-concept.yaml", "Clean Modern"),
  SHORT_VIDEO("Short Video", "Short Video", "clip-factory.yaml", "Punchy Energy"),
  YOUTUBE_SHORTS("YouTube Shorts", "YouTube Shorts", "clip-factory.yaml", "Fast Hook"),
  YOUTUBE_FULL("YouTube Full Video", "YouTube Full Video", "hybrid.yaml", "Engaging YouTube"),
  CINEMATIC("Cinematic Video", "Cinematic Video", "cinematic.yaml", "Cinematic Drama"),
  MOTIVATIONAL("Motivational Video", "Motivational Video", "cinematic.yaml", "Inspiring Rise"),
  VOICEOVER("Voiceover Video", "Voiceover Video", "documentary-montage.yaml", "Calm Voice Focus"),
  PROMOTIONAL("Promotional Video", "Promotional Video", "screen-demo.yaml", "Bold Promo"),
  CUSTOM("Custom Video Type", "Custom Type", "hybrid.yaml", "Adaptive Freeflow"),
}

enum class VideoProductionMode(
  val labelBn: String,
  val badgeEn: String,
  val descriptionBn: String,
) {
  REAL_AI_VIDEO(
    "A. Real AI Video",
    "REAL AI VIDEO",
    "চলমান চরিত্র, হাঁটা-দৌড়ানো, হাতের ইশারা, লিপ-সিঙ্ক ও সিনেম্যাটিক ক্যামেরা মোশন",
  ),
  MOTION_GRAPHICS(
    "B. Motion Graphics",
    "MOTION GRAPHICS",
    "কাইনেটিক টাইপোগ্রাফি, অ্যানিমেটেড চার্ট, ম্যাপ, টাইমলাইন, ইনফোগ্রাফিক্স ও লোয়ার-থার্ড",
  ),
  REAL_FOOTAGE(
    "C. Real Footage",
    "REAL FOOTAGE",
    "ফ্রি স্টক ভিডিও (Wikimedia, Internet Archive, NASA, গ্যালারি ক্লিপ) ও বাস্তব দৃশ্য",
  ),
  HYBRID_ALL_THREE(
    "সব ৩টি (Hybrid Pro)",
    "HYBRID A+B+C",
    "YouTube প্রফেশনাল মান: চরিত্র অ্যানিমেশন + মোশন গ্রাফিক্স + রিয়েল স্টক ফুটেজ একসাথে",
  ),
}

enum class ProviderBillingTier(
  val labelBn: String,
  val isPaidApi: Boolean,
  val badgeText: String,
) {
  FREE_ON_DEVICE("১০০% ফ্রি • অন-ডিভাইস", false, "FREE LOCAL"),
  FREE_NO_KEY("১০০% ফ্রি • Key ছাড়াই", false, "FREE NO KEY"),
  FREE_STOCK_KEY("ফ্রি স্টক (ফ্রি Key ঐচ্ছিক)", false, "FREE API KEY"),
  OPEN_WEIGHT_GPU_OR_PAID("ওপেন-ওয়েট (Desktop GPU) / Paid Cloud", true, "GPU / PAID"),
  PAID_API("পেইড API • অনুমতির প্রয়োজন", true, "PAID API"),
}

data class OpenMontageVideoProviderEntry(
  val id: String,
  val displayName: String,
  val openMontageToolName: String,
  val billingTier: ProviderBillingTier,
  val supportsTextToVideo: Boolean,
  val supportsImageToVideo: Boolean,
  val supportsVideoToVideo: Boolean,
  val requiredEnvVar: String,
  val availabilityNoteBn: String,
)

enum class MotionChoreography(val labelBn: String, val key: String) {
  AUTO("অটো স্মার্ট মোশন", "auto"),
  TALKING_LIPSYNC("মুখ নাড়িয়ে কথা বলা (Lip-Sync)", "talking"),
  WALKING("হাঁটাচলা (Walking)", "walking"),
  RUNNING("দৌড়ানো ও গতি (Running)", "running"),
  DANCING("নাচানাচি (Dancing)", "dancing"),
  FASHION_360_SPIN("৩৬০° ফ্যাশন ঘূর্ণন", "fashion_360"),
  MOTION_GRAPHICS("মোশন গ্রাফিক্স ও কাইনেটিক", "motion_graphics"),
  DRAMA_CINEMATIC("নাটক ও সিরিয়াল সিনেমাটিক", "drama_cinematic"),
}

enum class MotionGraphicKind(val labelBn: String) {
  NONE("সাধারণ দৃশ্য"),
  KINETIC_TYPOGRAPHY("কাইনেটিক টাইপোগ্রাফি"),
  ANIMATED_CHART("অ্যানিমেটেড চার্ট ও ডেটা"),
  INFOGRAPHIC_STEPS("ইনফোগ্রাফিক ও মুভিং আইকন"),
  ANIMATED_MAP_TIMELINE("অ্যানিমেটেড ম্যাপ ও টাইমলাইন"),
  LOWER_THIRD("প্রফেশনাল লোয়ার-থার্ড ও ইন্ট্রো"),
}

enum class TalkingCharacterPreset(val labelBn: String, val key: String) {
  AUTO("বিষয় অনুযায়ী অটো চরিত্র", "auto"),
  BUNDLED_3D("মানুষ ও ৩ডি চরিত্র (Articulated)", "bundled_3d"),
  CAULIFLOWER("কথা বলা ফুলকপি (Cauliflower)", "cauliflower"),
  CABBAGE("কথা বলা পাতাকপি (Cabbage)", "cabbage"),
  BANANA("কথা বলা কলা (Banana)", "banana"),
  APPLE("কথা বলা আপেল (Apple)", "apple"),
  FLOWER("কথা বলা ফুল (Flower)", "flower"),
  FINGER("কথা বলা আঙ্গুল (Finger)", "finger"),
  USER_MEDIA("আপনার দেওয়া ছবি/ভিডিও", "user_media"),
}

enum class UserMediaKind(val labelBn: String) {
  IMAGE("ছবি / ইমেজ"),
  SCREENSHOT("স্ক্রিনশট"),
  VIDEO("ভিডিও ক্লিপ"),
  AUDIO("অডিও/ভয়েস"),
}

data class UserMediaItem(
  val id: String,
  val uriString: String,
  val displayName: String,
  val kind: UserMediaKind,
  val cachedFilePath: String,
  val durationMs: Long = 0L,
)

enum class DurationPreset(
  val labelBn: String,
  val defaultSeconds: Int,
  val recommendedScenes: Int,
) {
  SHORT_15_60S("১৫–৬০ সেকেন্ড (Shorts)", 30, 5),
  ANIMATION_3_5M("৩–৫ মিনিট (Animation)", 180, 8),
  YOUTUBE_ADAPTIVE("YouTube ভিডিও (৯০ সেকেন্ড)", 90, 6),
  CUSTOM("কাস্টম (আনলিমিটেড)", 45, 5),
}

enum class VideoAspectRatio(
  val labelBn: String,
  val ratioTag: String,
  val width720: Int,
  val height720: Int,
  val width1080: Int,
  val height1080: Int,
) {
  PORTRAIT_9_16("৯:১৬ Shorts/Reels", "9:16", 720, 1280, 1080, 1920),
  LANDSCAPE_16_9("১৬:৯ YouTube HD", "16:9", 1280, 720, 1920, 1080),
}

enum class VideoResolutionPreset(val label: String, val is1080p: Boolean) {
  HD_720P("720p HD (দ্রুত ও রিয়েলিস্টিক)", false),
  FHD_1080P("1080p Full HD", true),
}

enum class TransitionEffect(val labelBn: String, val ffmpegFilter: String) {
  FADE("Cinematic Crossfade", "fade"),
  SLIDE("Smooth Slide", "slideleft"),
  ZOOM("Dolly Zoom Blend", "smoothleft"),
  WIPE("Dynamic Wipe", "wipeleft"),
  CUT("Clean Rhythm Cut", "cut"),
}

enum class ProductionToolTab(val titleBn: String, val badgeEn: String) {
  SCRIPT("স্ক্রিপ্ট", "SCRIPT"),
  VOICE_OVER("ভয়েস ওভার", "VOICE OVER"),
  IMAGE_VIDEO("ইমেজ ও ভিডিও", "IMAGE & VIDEO"),
  EDIT("এডিট ও রেন্ডার", "EDIT"),
}

enum class PipelineStage(val stepNumber: Int, val titleBn: String, val key: String) {
  TOPIC_ANALYSIS(1, "১. টপিক ও OpenMontage পরিকল্পনা", "topic_analysis"),
  SCRIPT_GENERATION(2, "২. সম্পূর্ণ স্ক্রিপ্ট ও সংলাপ তৈরি", "script_generation"),
  SCENE_PLANNING(3, "৩. মাল্টি-দৃশ্য ও ক্যামেরা প্ল্যানিং", "scene_planning"),
  CHARACTER_PLANNING(4, "৪. চরিত্র অ্যানিমেশন ও লিপ-সিঙ্ক ডিজাইন", "character_planning"),
  IMAGE_VISUAL_GEN(5, "৫. রিয়েল ফুটেজ, ৩ডি ও মোশন গ্রাফিক্স", "image_visual_gen"),
  VOICEOVER_GEN(6, "৬. স্পষ্ট ভয়েসওভার ও কণ্ঠ নির্বাচন", "voiceover_gen"),
  BACKGROUND_MUSIC(7, "৭. কপিরাইট-মুক্ত ব্যাকগ্রাউন্ড মিউজিক", "background_music"),
  SUBTITLE_GEN(8, "৮. সাবটাইটেল (.SRT) তৈরি", "subtitle_gen"),
  SCENE_EDITING(9, "৯. Remotion/GSAP/Manim/FFmpeg কম্পোজিশন", "scene_editing"),
  AUDIO_SYNC(10, "১০. লিপ-সিঙ্ক ও অডিও-ভিডিও সিঙ্ক", "audio_sync"),
  FINAL_RENDER(11, "১১. ২৪fps রিয়েল H.264/AAC MP4 রেন্ডারিং", "final_render"),
  MP4_EXPORT(12, "১২. Quality Gate যাচাই ও এক্সপোর্ট", "mp4_export"),
}

enum class PipelineRunStatus(val labelBn: String) {
  IDLE("প্রস্তুত"),
  RUNNING("কাজ চলছে"),
  PAUSED("বিরতিতে (Paused)"),
  CANCELLED("বাতিল করা হয়েছে"),
  FAILED("ত্রুটি হয়েছে — Retry করুন"),
  COMPLETED("সম্পন্ন হয়েছে ✓"),
}

enum class VisualSourceKind(val labelBn: String) {
  AI_SCENE_ART("Articulated AI Scene"),
  ANIMATED_2D_VECTOR("2D/Talking Character"),
  DEPTH_3D_STYLE("3D Multi-Object Rig"),
  IMAGE_TO_VIDEO_MOTION("Motion Graphics Layer"),
  FREE_STOCK_OPEN_MEDIA("Real Moving Footage"),
}

data class CharacterProfile(
  val id: String,
  val name: String,
  val roleBn: String,
  val visualDescription: String,
  val recommendedVoiceId: String,
  val primaryColorHex: Long,
  val accentColorHex: Long,
  val avatarVariant: String,
)

data class VideoScenePlan(
  val index: Int,
  val title: String,
  val narrationText: String,
  val dialogueLine: String,
  val characterName: String,
  val voiceId: String,
  val emotionalTone: String,
  val visualPrompt: String,
  val themeKeyword: String,
  val visualSource: VisualSourceKind,
  val effectTag: String,
  val overlayHeadline: String,
  val durationSec: Float,
  val trimStartSec: Float = 0f,
  val trimEndSec: Float = durationSec,
  val transition: TransitionEffect = TransitionEffect.FADE,
  val sfxCue: String = "Chime",
  val matchedAssetFile: String = "",
  val backgroundAssetFile: String = "",
  val userMediaPath: String = "",
  val userMediaIsVideo: Boolean = false,
  val motionStyle: MotionChoreography = MotionChoreography.AUTO,
  val characterPreset: TalkingCharacterPreset = TalkingCharacterPreset.AUTO,
  val productionMode: VideoProductionMode = VideoProductionMode.HYBRID_ALL_THREE,
  val motionGraphicKind: MotionGraphicKind = MotionGraphicKind.NONE,
  val secondaryCharacterName: String = "",
  val secondaryCharacterPreset: TalkingCharacterPreset = TalkingCharacterPreset.AUTO,
  val realFootagePath: String = "",
  val realFootageSourceLabel: String = "",
  val chartValues: List<Float> = emptyList(),
  val chartLabels: List<String> = emptyList(),
) {
  val effectiveDurationSec: Float
    get() = (trimEndSec - trimStartSec).coerceAtLeast(1.5f)
}

data class SubtitleCue(
  val index: Int,
  val startMs: Long,
  val endMs: Long,
  val text: String,
)

data class GoogleVoiceCatalogItem(
  val id: String,
  val displayName: String,
  val gender: VoiceGender,
  val characterFitBn: String,
  val emotionalProfile: String,
)

data class VideoQualityCheckItem(
  val id: String,
  val titleBn: String,
  val passed: Boolean,
  val detailBn: String,
)

data class VideoQualityReport(
  val approved: Boolean,
  val isSlideshowDetected: Boolean,
  val scorePercent: Int,
  val averageMotionDelta: Float,
  val audioDurationSec: Float,
  val videoDurationSec: Float,
  val verdictBn: String,
  val checks: List<VideoQualityCheckItem>,
)

object VideoStudioCatalog {
  val GOOGLE_VOICES: List<GoogleVoiceCatalogItem> = listOf(
    GoogleVoiceCatalogItem("Aoede", "Aoede", VoiceGender.FEMALE, "শিশু-বান্ধব, গল্প ও মিষ্টি নারী কণ্ঠ", "Warm & Joyful"),
    GoogleVoiceCatalogItem("Iapetus", "Iapetus", VoiceGender.MALE, "স্পষ্ট শিক্ষামূলক ও ডকুমেন্টারি পুরুষ কণ্ঠ", "Clear & Calm"),
    GoogleVoiceCatalogItem("Leda", "Leda", VoiceGender.FEMALE, "কোমল, নাটক ও গল্প বলার নারী কণ্ঠ", "Soft & Gentle"),
    GoogleVoiceCatalogItem("Orus", "Orus", VoiceGender.MALE, "দৃঢ়, মোটিভেশনাল ও সিনেম্যাটিক পুরুষ কণ্ঠ", "Strong & Heroic"),
    GoogleVoiceCatalogItem("Achernar", "Achernar", VoiceGender.FEMALE, "উৎসাহী, নাচ-গান ও কার্টুন কণ্ঠ", "Bright & Expressive"),
    GoogleVoiceCatalogItem("Charon", "Charon", VoiceGender.MALE, "গভীর, রহস্যময় ও সিরিয়াল বিবরণী কণ্ঠ", "Deep & Dramatic"),
    GoogleVoiceCatalogItem("Sulafat", "Sulafat", VoiceGender.FEMALE, "আন্তরিক ও ফ্যাশন/ব্যাখ্যামূলক উপস্থাপিকা কণ্ঠ", "Sincere & Clear"),
  )

  fun voiceIds(): List<String> = Store.VOICE_OPTIONS.map { it.id }

  const val DEFAULT_EXAMPLE_PROMPT =
    "একটি প্রফেশনাল অ্যানিমেশন ও মোশন গ্রাফিক্স ভিডিও তৈরি করো, যেখানে দুটি চরিত্র হেঁটে ও কথা বলে ভবিষ্যতের সৌরশক্তি ও প্রকৃতির রহস্য ব্যাখ্যা করছে।"
}

data class ProductionSettings(
  val aspectRatio: VideoAspectRatio = VideoAspectRatio.LANDSCAPE_16_9,
  val resolution: VideoResolutionPreset = VideoResolutionPreset.HD_720P,
  val productionMode: VideoProductionMode = VideoProductionMode.HYBRID_ALL_THREE,
  val subtitlesEnabled: Boolean = true,
  val backgroundMusicEnabled: Boolean = true,
  val voiceoverEnabled: Boolean = true,
  val soundEffectsEnabled: Boolean = true,
  val imageToVideoMotionEnabled: Boolean = true,
  val lipSyncEnabled: Boolean = true,
  val transitionEffect: TransitionEffect = TransitionEffect.FADE,
  val selectedVoiceId: String = "Aoede",
  val autoSelectVoiceByAi: Boolean = true,
  val useCharacterVoicesPerScene: Boolean = true,
  val selectedVoiceGenderFilter: String = "ALL",
  val preferredEmotionalTone: String = "Warm & Expressive",
  val preferredMotionStyle: MotionChoreography = MotionChoreography.AUTO,
  val preferredCharacterPreset: TalkingCharacterPreset = TalkingCharacterPreset.AUTO,
  val selectedVideoProviderId: String = "lxr_articulated_24fps",
  val paidProviderConsentGranted: Boolean = false,
  val pexelsApiKey: String = "",
  val pixabayApiKey: String = "",
)

data class StudioProgressState(
  val activeStage: PipelineStage? = null,
  val status: PipelineRunStatus = PipelineRunStatus.IDLE,
  val scriptProgress: Float = 0f,
  val voiceProgress: Float = 0f,
  val imageProgress: Float = 0f,
  val sceneProgress: Float = 0f,
  val editingProgress: Float = 0f,
  val renderingProgress: Float = 0f,
  val statusMessageBn: String = "OpenMontage Studio প্রস্তুত — মোড বা প্রম্পট দিয়ে রিয়েল ভিডিও তৈরি করুন!",
  val completedStages: Set<PipelineStage> = emptySet(),
  val failedStage: PipelineStage? = null,
  val errorMessage: String? = null,
) {
  val overallProgress: Float
    get() =
      ((scriptProgress + voiceProgress + imageProgress + sceneProgress + editingProgress + renderingProgress) /
        6f)
        .coerceIn(0f, 1f)
}

data class ExportedVideoArtifacts(
  val projectSlug: String = "",
  val workspaceDirPath: String = "",
  val publicDownloadFolderLabel: String = "Downloads/LXR-VideoStudio",
  val mp4Path: String = "",
  val mp4SizeBytes: Long = 0L,
  val scriptTxtPath: String = "",
  val voiceWavPath: String = "",
  val voiceSizeBytes: Long = 0L,
  val subtitleSrtPath: String = "",
  val projectBundleZipPath: String = "",
  val exportedFilesSummary: List<String> = emptyList(),
  val usedFreeVoiceEngineLabel: String = "",
  val usedFreeRenderEngineLabel: String = "",
)

data class OpenMontageCapabilityReport(
  val frameworkRepoUrl: String = "https://github.com/OpenMontageInc/OpenMontage",
  val onDeviceEncoderAvailable: Boolean = true,
  val onDeviceEncoderNote: String = "Android MediaCodec H.264/AAC (EGL PTS-Synced 24fps) + Articulated Rig প্রস্তুত ✓",
  val termuxInstalled: Boolean = false,
  val termuxRunPermGranted: Boolean = false,
  val ffmpegNote: String = "Termux FFmpeg + Manim + Remotion/GSAP + অন-ডিভাইস 24fps MP4 রেন্ডার সক্রিয় ✓।",
  val piperTtsNote: String = "Free-First নীতি: Gemini Free Voice / Piper TTS / LXR Formant — কোনো Paid API অনুমতি ছাড়া চলে না ✓।",
  val remotionNote: String = "Remotion (.tsx), GSAP HyperFrames (.html) ও Manim (.py) বান্ডিল প্রতিটি প্রজেক্টে এক্সপোর্ট হয়।",
  val stockMediaNote: String = "ফ্রি রিয়েল ফুটেজ (Wikimedia Commons, Internet Archive, NASA) + আপনার গ্যালারি মিডিয়া + ৬০ অ্যাসেট সক্রিয় ✓।",
  val aiVideoApiDisclosureBn: String =
    "গুরুত্বপূর্ণ: Google Veo, Kling, Runway, Hailuo, Seedance হলো Paid API; Wan/LTX/Hunyuan-এর জন্য Desktop GPU লাগে। ফ্রি অবস্থায় অন-ডিভাইস 24fps আর্টিকুলেটেড অ্যানিমেশন, মোশন গ্রাফিক্স ও ফ্রি স্টক ভিডিও ব্যবহৃত হয়।",
)

data class VideoProject(
  val id: String,
  val slug: String,
  val title: String,
  val prompt: String,
  val videoType: VideoType = VideoType.ANIMATION,
  val customTypeLabel: String = "",
  val durationPreset: DurationPreset = DurationPreset.SHORT_15_60S,
  val customDurationSeconds: Int = 30,
  val topicAnalysisSummary: String = "",
  val fullScriptText: String = "",
  val characters: List<CharacterProfile> = emptyList(),
  val scenes: List<VideoScenePlan> = emptyList(),
  val subtitles: List<SubtitleCue> = emptyList(),
  val previewFramePaths: List<String> = emptyList(),
  val bgmMoodLabel: String = "Lively Cinematic Ambient (Free Procedural Synth)",
  val bgmWavPath: String = "",
  val settings: ProductionSettings = ProductionSettings(),
  val artifacts: ExportedVideoArtifacts = ExportedVideoArtifacts(),
  val qualityReport: VideoQualityReport? = null,
  val pipelineYaml: String = "",
  val remotionTsx: String = "",
  val termuxScript: String = "",
  val logs: List<String> = emptyList(),
  val createdAtMs: Long = System.currentTimeMillis(),
)

data class VideoStudioUiState(
  val promptInput: String = VideoStudioCatalog.DEFAULT_EXAMPLE_PROMPT,
  val selectedVideoType: VideoType = VideoType.ANIMATION,
  val customVideoTypeInput: String = "",
  val selectedDurationPreset: DurationPreset = DurationPreset.SHORT_15_60S,
  val customDurationSec: Int = 30,
  val selectedToolTab: ProductionToolTab = ProductionToolTab.SCRIPT,
  val settings: ProductionSettings = ProductionSettings(),
  val bundledAssets: List<StudioAssetEntry> = emptyList(),
  val selectedBundledAssetFiles: List<String> = emptyList(),
  val userMediaItems: List<UserMediaItem> = emptyList(),
  val videoProviders: List<OpenMontageVideoProviderEntry> = emptyList(),
  val pendingPaidProviderConsentId: String? = null,
  val totalVideosCreatedCount: Int = 0,
  val activeProject: VideoProject? = null,
  val savedProjects: List<VideoProject> = emptyList(),
  val progress: StudioProgressState = StudioProgressState(),
  val capabilityReport: OpenMontageCapabilityReport = OpenMontageCapabilityReport(),
  val selectedPreviewSceneIndex: Int = 0,
  val isVoicePreviewPlaying: Boolean = false,
  val showPreviewPlayerDialog: Boolean = false,
  val showSettingsSheet: Boolean = false,
  val showOpenMontageDetails: Boolean = false,
  val lastActionBanner: String? = null,
)
