package com.example.video

import com.example.data.Store
import com.example.data.VoiceGender

enum class VideoType(
  val labelBn: String,
  val labelEn: String,
  val pipelineYamlName: String,
  val defaultMood: String,
) {
  ANIMATION("Animation", "Animation", "animation_studio.yaml", "Wonder & Playful"),
  ANIMATION_2D("2D Animation", "2D Animation", "2d_vector_animation.yaml", "Bright & Story"),
  ANIMATION_3D_STYLE("3D Style Animation", "3D Style Animation", "3d_depth_animation.yaml", "Cosmic & Epic"),
  CARTOON("Cartoon Video", "Cartoon Video", "cartoon_adventure.yaml", "Cheerful Cartoon"),
  STORYTELLING("Storytelling Video", "Storytelling Video", "storytelling_arc.yaml", "Warm Narrative"),
  EDUCATIONAL("Educational Video", "Educational Video", "educational_explainer.yaml", "Curious & Clear"),
  DOCUMENTARY("Documentary", "Documentary", "documentary_deep.yaml", "Atmospheric Doc"),
  EXPLAINER("Explainer Video", "Explainer Video", "explainer_motion.yaml", "Clean Modern"),
  SHORT_VIDEO("Short Video", "Short Video", "short_vertical.yaml", "Punchy Energy"),
  YOUTUBE_SHORTS("YouTube Shorts", "YouTube Shorts", "youtube_shorts_9_16.yaml", "Fast Hook"),
  YOUTUBE_FULL("YouTube Full Video", "YouTube Full Video", "youtube_full_16_9.yaml", "Engaging YouTube"),
  CINEMATIC("Cinematic Video", "Cinematic Video", "cinematic.yaml", "Cinematic Drama"),
  MOTIVATIONAL("Motivational Video", "Motivational Video", "motivational_rise.yaml", "Inspiring Rise"),
  VOICEOVER("Voiceover Video", "Voiceover Video", "voiceover_essay.yaml", "Calm Voice Focus"),
  PROMOTIONAL("Promotional Video", "Promotional Video", "promo_showcase.yaml", "Bold Promo"),
  CUSTOM("Custom Video Type", "Custom Type", "custom_openmontage.yaml", "Adaptive Freeflow"),
}

enum class DurationPreset(
  val labelBn: String,
  val defaultSeconds: Int,
  val recommendedScenes: Int,
) {
  SHORT_15_60S("১৫–৬০ সেকেন্ড (Shorts)", 30, 4),
  ANIMATION_3_5M("৩–৫ মিনিট (Animation)", 210, 6),
  YOUTUBE_ADAPTIVE("YouTube ভিডিও (৯০ সেকেন্ড)", 90, 5),
  CUSTOM("কাস্টম সময়কাল", 45, 5),
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
  HD_720P("720p HD (দ্রুত ও নির্ভরযোগ্য)", false),
  FHD_1080P("1080p Full HD", true),
}

enum class TransitionEffect(val labelBn: String, val ffmpegFilter: String) {
  FADE("Smooth Fade", "fade"),
  SLIDE("Cinematic Slide", "slideleft"),
  ZOOM("Ken Burns Zoom", "smoothleft"),
  WIPE("Cyber Wipe", "wipeleft"),
  CUT("Clean Cut", "cut"),
}

enum class ProductionToolTab(val titleBn: String, val badgeEn: String) {
  SCRIPT("স্ক্রিপ্ট", "SCRIPT"),
  VOICE_OVER("ভয়েস ওভার", "VOICE OVER"),
  IMAGE_VIDEO("ইমেজ ও ভিডিও", "IMAGE & VIDEO"),
  EDIT("এডিট ও রেন্ডার", "EDIT"),
}

enum class PipelineStage(val stepNumber: Int, val titleBn: String, val key: String) {
  TOPIC_ANALYSIS(1, "১. টপিক ও ধারণা বিশ্লেষণ", "topic_analysis"),
  SCRIPT_GENERATION(2, "২. সম্পূর্ণ স্ক্রিপ্ট তৈরি", "script_generation"),
  SCENE_PLANNING(3, "৩. দৃশ্য (Scene) পরিকল্পনা", "scene_planning"),
  CHARACTER_PLANNING(4, "৪. চরিত্র (Character) ডিজাইন", "character_planning"),
  IMAGE_VISUAL_GEN(5, "৫. AI ভিজ্যুয়াল ও অ্যানিমেশন দৃশ্য", "image_visual_gen"),
  VOICEOVER_GEN(6, "৬. ভয়েসওভার ও কণ্ঠ নির্বাচন", "voiceover_gen"),
  BACKGROUND_MUSIC(7, "৭. কপিরাইট-মুক্ত ব্যাকগ্রাউন্ড মিউজিক", "background_music"),
  SUBTITLE_GEN(8, "৮. সাবটাইটেল (.SRT) তৈরি", "subtitle_gen"),
  SCENE_EDITING(9, "৯. টাইমলাইন ও দৃশ্য এডিটিং", "scene_editing"),
  AUDIO_SYNC(10, "১০. ভয়েস ও মিউজিক সিঙ্ক", "audio_sync"),
  FINAL_RENDER(11, "১১. চূড়ান্ত MP4 ভিডিও রেন্ডারিং", "final_render"),
  MP4_EXPORT(12, "১২. Downloads ফোল্ডারে এক্সপোর্ট", "mp4_export"),
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
  AI_SCENE_ART("AI Scene Art"),
  ANIMATED_2D_VECTOR("2D Vector Animation"),
  DEPTH_3D_STYLE("3D Depth Render"),
  IMAGE_TO_VIDEO_MOTION("Image-to-Video Motion"),
  FREE_STOCK_OPEN_MEDIA("Free Open Stock Clip"),
}

data class CharacterProfile(
  val id: String,
  val name: String,
  val roleBn: String,
  val visualDescription: String,
  val recommendedVoiceId: String,
  val primaryColorHex: Long,
  val accentColorHex: Long,
  val avatarVariant: String, // "child_explorer", "robot_guide", "narrator", "cosmic_owl", "hero"
)

data class VideoScenePlan(
  val index: Int,
  val title: String,
  val narrationText: String,
  val dialogueLine: String,
  val characterName: String,
  val voiceId: String,
  val emotionalTone: String, // "Warm", "Curious", "Excited", "Calm", "Dramatic"
  val visualPrompt: String,
  val themeKeyword: String, // "space_kid", "planet_earth", "rocket_launch", "solar_system", "galaxy_future", "general"
  val visualSource: VisualSourceKind,
  val effectTag: String, // "Starfield", "OrbitMotion", "GlowPulse", "KenBurns", "ParticleSpark"
  val overlayHeadline: String,
  val durationSec: Float,
  val trimStartSec: Float = 0f,
  val trimEndSec: Float = durationSec,
  val transition: TransitionEffect = TransitionEffect.FADE,
  val sfxCue: String = "Chime",
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

object VideoStudioCatalog {
  // Strictly includes all 7 requested Google Voice Models matched with Store.VOICE_OPTIONS
  val GOOGLE_VOICES: List<GoogleVoiceCatalogItem> = listOf(
    GoogleVoiceCatalogItem("Aoede", "Aoede", VoiceGender.FEMALE, "শিশু-বান্ধব ও শিক্ষামূলক গল্পের নারী কণ্ঠ", "Warm & Joyful"),
    GoogleVoiceCatalogItem("Iapetus", "Iapetus", VoiceGender.MALE, "স্পষ্ট শিক্ষামূলক ও ডকুমেন্টারি পুরুষ কণ্ঠ", "Clear & Calm"),
    GoogleVoiceCatalogItem("Leda", "Leda", VoiceGender.FEMALE, "কোমল, শান্ত ও গল্প বলার নারী কণ্ঠ", "Soft & Gentle"),
    GoogleVoiceCatalogItem("Orus", "Orus", VoiceGender.MALE, "দৃঢ়, মোটিভেশনাল ও সিনেম্যাটিক পুরুষ কণ্ঠ", "Strong & Heroic"),
    GoogleVoiceCatalogItem("Achernar", "Achernar", VoiceGender.FEMALE, "উৎসাহী, প্রাণবন্ত ও চরিত্রের কণ্ঠ", "Bright & Expressive"),
    GoogleVoiceCatalogItem("Charon", "Charon", VoiceGender.MALE, "গভীর, রহস্যময় ও মহাকাশ বিবরণী পুরুষ কণ্ঠ", "Deep & Dramatic"),
    GoogleVoiceCatalogItem("Sulafat", "Sulafat", VoiceGender.FEMALE, "আন্তরিক ও ব্যাখ্যামূলক উপস্থাপিকা কণ্ঠ", "Sincere & Clear"),
  )

  fun voiceIds(): List<String> = Store.VOICE_OPTIONS.map { it.id }

  const val DEFAULT_EXAMPLE_PROMPT =
    "একটি সুন্দর শিক্ষামূলক অ্যানিমেশন ভিডিও তৈরি করো, যেখানে একটি শিশু মহাকাশ সম্পর্কে শিখছে।"
}

data class ProductionSettings(
  val aspectRatio: VideoAspectRatio = VideoAspectRatio.LANDSCAPE_16_9,
  val resolution: VideoResolutionPreset = VideoResolutionPreset.HD_720P,
  val subtitlesEnabled: Boolean = true,
  val backgroundMusicEnabled: Boolean = true,
  val voiceoverEnabled: Boolean = true,
  val soundEffectsEnabled: Boolean = true,
  val imageToVideoMotionEnabled: Boolean = true,
  val transitionEffect: TransitionEffect = TransitionEffect.FADE,
  val selectedVoiceId: String = "Aoede",
  val autoSelectVoiceByAi: Boolean = true,
  val useCharacterVoicesPerScene: Boolean = true,
  val selectedVoiceGenderFilter: String = "ALL", // "ALL", "FEMALE", "MALE"
  val preferredEmotionalTone: String = "Warm & Curious",
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
  val statusMessageBn: String = "একটি প্রম্পট লিখে 'সম্পূর্ণ ভিডিও তৈরি করুন' চাপুন।",
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
  val frameworkRepoUrl: String = "https://github.com/Open-Montage-app/OpenMontage",
  val onDeviceEncoderAvailable: Boolean = true,
  val onDeviceEncoderNote: String = "Android MediaCodec H.264 + MediaMuxer + Canvas 100% ফ্রি ও অফলাইন প্রস্তুত ✓",
  val termuxInstalled: Boolean = false,
  val termuxRunPermGranted: Boolean = false,
  val ffmpegNote: String = "Termux-এ `pkg install ffmpeg` দিয়ে হাই-স্পিড রেন্ডারিং সমর্থিত (বিকল্প হিসেবে অন-ডিভাইস MP4 রেন্ডার সক্রিয় ✓)।",
  val piperTtsNote: String = "পেইড API নয় — ফ্রি Gemini/Firebase বা অফলাইন Piper TTS / LXR ফনেটিক ভয়েস ইঞ্জিন ব্যবহৃত হয় ✓।",
  val remotionNote: String = "Remotion কম্পোজিশন এক্সপোর্ট হয়; অ্যান্ড্রয়েড Termux-এ Chromium সীমাবদ্ধতায় নেটিভ Canvas/FFmpeg অটো-ব্যবহৃত হয়।",
  val stockMediaNote: String = "কপিরাইট-মুক্ত Wikimedia/CC0 প্রম্পট ও অন-ডিভাইস ভেক্টর/অ্যানিমেশন দৃশ্য সম্পূর্ণ ফ্রি।",
)

data class VideoProject(
  val id: String,
  val slug: String,
  val title: String,
  val prompt: String,
  val videoType: VideoType = VideoType.ANIMATION,
  val customTypeLabel: String = "",
  val durationPreset: DurationPreset = DurationPreset.ANIMATION_3_5M,
  val customDurationSeconds: Int = 36,
  val topicAnalysisSummary: String = "",
  val fullScriptText: String = "",
  val characters: List<CharacterProfile> = emptyList(),
  val scenes: List<VideoScenePlan> = emptyList(),
  val subtitles: List<SubtitleCue> = emptyList(),
  val previewFramePaths: List<String> = emptyList(),
  val bgmMoodLabel: String = "Cosmic Wonder Ambient (Free Procedural Synth)",
  val bgmWavPath: String = "",
  val settings: ProductionSettings = ProductionSettings(),
  val artifacts: ExportedVideoArtifacts = ExportedVideoArtifacts(),
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
  val customDurationSec: Int = 24,
  val selectedToolTab: ProductionToolTab = ProductionToolTab.SCRIPT,
  val settings: ProductionSettings = ProductionSettings(),
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
