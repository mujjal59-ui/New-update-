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

enum class AnimationFormat(
  val labelBn: String,
  val labelEn: String,
  val recommendedVideoType: VideoType,
  val defaultAspectRatio: VideoAspectRatio,
  val defaultDurationSec: Int,
) {
  SHORT_FILM_3D("3D Cartoon Short Film", "3D Cartoon Short Film", VideoType.ANIMATION_3D_STYLE, VideoAspectRatio.LANDSCAPE_16_9, 45),
  FULL_MOVIE_3D("3D Cartoon Full Movie", "3D Cartoon Full Movie", VideoType.CINEMATIC, VideoAspectRatio.LANDSCAPE_16_9, 180),
  ANIMATED_DRAMA("Animated Drama (নাটক)", "Animated Drama", VideoType.DRAMA_SERIAL, VideoAspectRatio.LANDSCAPE_16_9, 90),
  CARTOON_SERIAL("Cartoon Serial (সিরিজ পর্ব)", "Cartoon Serial Episode", VideoType.DRAMA_SERIAL, VideoAspectRatio.LANDSCAPE_16_9, 60),
  EDUCATIONAL("Educational Animation (শিক্ষা)", "Educational Animation", VideoType.EDUCATIONAL, VideoAspectRatio.LANDSCAPE_16_9, 60),
  COMEDY("Comedy Animation (কমেডি)", "Comedy Animation", VideoType.CARTOON, VideoAspectRatio.LANDSCAPE_16_9, 45),
  MUSICAL("Musical Animation (গান)", "Musical Animation", VideoType.ANIMATION, VideoAspectRatio.LANDSCAPE_16_9, 45),
  DANCE_VIDEO("Dance Video (নাচ)", "Dance Video", VideoType.ANIMATION_3D_STYLE, VideoAspectRatio.PORTRAIT_9_16, 30),
  STORYTELLING("Storytelling Video (গল্প)", "Storytelling Video", VideoType.STORYTELLING, VideoAspectRatio.LANDSCAPE_16_9, 60),
  YOUTUBE_SHORTS("YouTube Shorts (৯:১৬)", "YouTube Shorts", VideoType.YOUTUBE_SHORTS, VideoAspectRatio.PORTRAIT_9_16, 30),
  YOUTUBE_FULL("YouTube Full-Length (১৬:৯)", "YouTube Full-Length", VideoType.YOUTUBE_FULL, VideoAspectRatio.LANDSCAPE_16_9, 120),
}

enum class CharacterAgeGroup(val labelBn: String, val scaleFactor: Float) {
  CHILD("শিশু (Child)", 0.78f),
  TEEN("কিশোর (Teen)", 0.88f),
  YOUNG_ADULT("তরুণ (Young Adult)", 0.98f),
  ADULT("প্রাপ্তবয়স্ক (Adult)", 1.04f),
  ELDER("বয়োজ্যেষ্ঠ (Elder)", 1.00f),
  CREATURE("প্রাণী / ফ্যান্টাসি", 0.90f),
}

enum class CharacterCategory(val labelBn: String) {
  CARTOON_GIRL("3D Cartoon Girl"),
  CARTOON_BOY("3D Cartoon Boy"),
  CHILD_KID("শিশু ও শিক্ষার্থী"),
  PARENT_FAMILY("মা-বাবা ও পরিবার"),
  TEACHER("শিক্ষক / প্রফেসর"),
  STUDENT("ছাত্র-ছাত্রী"),
  FRIEND_NEIGHBOR("বন্ধু ও প্রতিবেশী"),
  DOCTOR("ডাক্তার"),
  POLICE("পুলিশ অফিসার"),
  FARMER("কৃষক"),
  BUSINESS("ব্যবসায়ী"),
  ANIMAL_FANTASY("প্রাণী ও ফ্যান্টাসি"),
  PRODUCE_MASCOT("কথা বলা ফল/সবজি/ফুল"),
}

enum class CharacterEmotion(val labelBn: String) {
  HAPPY("হাসি ও আনন্দ (Happy)"),
  SMILING("মিষ্টি হাসি (Smiling)"),
  SAD_CRY("কান্না ও মন খারাপ (Sad)"),
  ANGRY("রাগ ও গম্ভীর (Angry)"),
  FEAR("ভয় ও অবাক (Fear)"),
  SHY("লজ্জা ও নম্র (Shy)"),
  EXCITED_JOY("উচ্ছ্বাস ও উৎসব (Joy)"),
  SURPRISED("বিস্ময় (Surprised)"),
  CALM("শান্ত ও স্বাভাবিক (Calm)"),
}

enum class CameraShotType(val labelBn: String, val zoomFactor: Float) {
  WIDE_SHOT("Wide Shot (সম্পূর্ণ দৃশ্য)", 1.0f),
  CLOSE_UP("Close-up (মুখ ও অভিব্যক্তি)", 1.32f),
  TRACKING_SHOT("Tracking Shot (গতি অনুসরণ)", 1.12f),
  LOW_ANGLE("Low Angle (নাটকীয় কোণ)", 1.10f),
  TWO_SHOT("Two-Shot (যুগল সংলাপ)", 1.06f),
  ORBIT_360("Orbit 360° (ঘূর্ণায়মান)", 1.08f),
}

enum class EnvironmentSetting(val labelBn: String) {
  AUTO("গল্প অনুযায়ী অটো পরিবেশ"),
  CLASSROOM("স্কুল ও কলেজ ক্লাসরুম"),
  SCHOOL_YARD("স্কুল মাঠ ও খেলাধুলা"),
  HOME_LIVING("বাড়ি ও পারিবারিক ঘর"),
  VILLAGE_PATH("গ্রামের প্রাকৃতিক পথ"),
  CITY_STREET("শহরের রাস্তা ও ভবন"),
  PARK_GARDEN("পার্ক ও ফুলের বাগান"),
  MARKETPLACE("বাজার ও সামাজিক অনুষ্ঠান"),
  CONCERT_STAGE("মিউজিক ও ড্যান্স স্টেজ"),
  FANTASY_FOREST("রূপকথা ও ফ্যান্টাসি বন"),
  SPACE_COSMOS("মহাকাশ ও নক্ষত্র"),
}

enum class TimeWeatherMood(val labelBn: String) {
  DAYLIGHT("উজ্জ্বল দিন (Day)"),
  SUNSET_GOLDEN("গোধূলি বিকেল (Sunset)"),
  NIGHT_MOONLIGHT("চাঁদনী রাত (Night)"),
  RAINY_DRIZZLE("বৃষ্টিমুখর আবহ (Rain)"),
  NEON_FESTIVE("রঙিন উৎসব ও আলো (Neon)"),
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
  JUMPING("লাফানো ও উচ্ছ্বাস (Jumping)", "jumping"),
  SITTING("বসে কথা বলা ও পড়া (Sitting)", "sitting"),
  STANDING_TURN("দাঁড়ানো ও ঘুরে তাকানো", "standing_turn"),
  HAND_WAVE_GESTURE("হাত নেড়ে ইশারা ও অভিবাদন", "hand_wave"),
  HUG_INTERACTION("আলিঙ্গন ও বন্ধুত্ব (Hug)", "hug"),
  HANDSHAKE("হাত মেলানো (Handshake)", "handshake"),
  DANCING("একক নাচ (Solo Dance)", "dancing"),
  GROUP_DANCE("দলীয় ছন্দময় নাচ (Group Dance)", "group_dance"),
  SINGING("গান ও লিপ-সিঙ্ক (Singing)", "singing"),
  CLASSROOM_TEACHING("ক্লাসরুমে পাঠদান ও প্রশ্নোত্তর", "classroom"),
  PLAYING("খেলাধুলা ও দৌড়ঝাঁপ (Playing)", "playing"),
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
  USER_MEDIA("আপনার দেওয়া Reference ছবি/ভিডিও", "user_media"),
}

enum class UserMediaKind(val labelBn: String) {
  IMAGE("ছবি / Reference"),
  SCREENSHOT("স্ক্রিনশট"),
  VIDEO("ভিডিও ক্লিপ"),
  AUDIO("অডিও/গান/ভয়েস"),
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
  ANIMATION_3_5M("৩–৫ মিনিট (Movie/Episode)", 180, 8),
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
  SQUARE_1_1("১:১ Square (Social)", "1:1", 720, 720, 1080, 1080),
}

enum class VideoResolutionPreset(
  val label: String,
  val is1080p: Boolean,
  val is4K: Boolean = false,
) {
  HD_720P("720p HD (দ্রুত ও রিয়েলিস্টিক)", false, false),
  FHD_1080P("1080p Full HD", true, false),
  UHD_4K("4K UHD (ডিভাইস সাপোর্ট অনুযায়ী)", true, true),
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
  val ageGroup: CharacterAgeGroup = CharacterAgeGroup.YOUNG_ADULT,
  val category: CharacterCategory = CharacterCategory.CARTOON_BOY,
  val personality: String = "বন্ধুত্বপূর্ণ ও প্রাণবন্ত",
  val outfitStyle: String = "আধুনিক রঙিন পোশাক",
  val referenceImagePath: String = "",
  val hairColorHex: Long = 0xFF1E293B,
  val isSavedInLibrary: Boolean = true,
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
  val castNames: List<String> = emptyList(),
  val castRefImagePaths: List<String> = emptyList(),
  val castPrimaryColors: List<Long> = emptyList(),
  val castHairColors: List<Long> = emptyList(),
  val castAgeGroups: List<CharacterAgeGroup> = emptyList(),
  val emotion: CharacterEmotion = CharacterEmotion.HAPPY,
  val cameraShot: CameraShotType = CameraShotType.WIDE_SHOT,
  val environment: EnvironmentSetting = EnvironmentSetting.AUTO,
  val weatherMood: TimeWeatherMood = TimeWeatherMood.DAYLIGHT,
  val episodeNumber: Int = 1,
  val extrasCount: Int = 2,
) {
  val effectiveDurationSec: Float
    get() = (trimEndSec - trimStartSec).coerceAtLeast(1.5f)
}

data class EpisodeRecord(
  val episodeNumber: Int,
  val title: String,
  val summaryBn: String,
  val endingHookBn: String,
  val sceneTitles: List<String>,
  val mp4Path: String = "",
  val createdAtMs: Long = System.currentTimeMillis(),
)

data class CartoonSeriesProject(
  val seriesId: String,
  val seriesTitle: String,
  val genreBn: String,
  val storyBibleSummary: String,
  val characterIds: List<String> = emptyList(),
  val episodes: List<EpisodeRecord> = emptyList(),
  val lastCliffhangerBn: String = "",
  val updatedAtMs: Long = System.currentTimeMillis(),
) {
  val nextEpisodeNumber: Int
    get() = (episodes.maxOfOrNull { it.episodeNumber } ?: 0) + 1
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
    "একটি প্রফেশনাল 3D কার্টুন ও অ্যানিমেশন গল্প তৈরি করো, যেখানে শিশু, শিক্ষার্থী ও বন্ধুরা মিলে স্কুলে যাচ্ছে, ক্লাসে কথা বলছে ও আনন্দে নাচ-গান করছে।"
}

data class ProductionSettings(
  val aspectRatio: VideoAspectRatio = VideoAspectRatio.LANDSCAPE_16_9,
  val resolution: VideoResolutionPreset = VideoResolutionPreset.HD_720P,
  val productionMode: VideoProductionMode = VideoProductionMode.HYBRID_ALL_THREE,
  val animationFormat: AnimationFormat = AnimationFormat.SHORT_FILM_3D,
  val preferredCameraShot: CameraShotType = CameraShotType.WIDE_SHOT,
  val preferredEnvironment: EnvironmentSetting = EnvironmentSetting.AUTO,
  val preferredWeatherMood: TimeWeatherMood = TimeWeatherMood.DAYLIGHT,
  val preferredEmotion: CharacterEmotion = CharacterEmotion.HAPPY,
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
  val statusMessageBn: String = "3D Cartoon & OpenMontage Studio প্রস্তুত — চরিত্র, গল্প বা সিরিজ এপিসোড তৈরি করুন!",
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
  val onDeviceEncoderNote: String = "Android MediaCodec H.264/AAC (EGL PTS-Synced 24fps) + 3D Articulated Cast প্রস্তুত ✓",
  val termuxInstalled: Boolean = false,
  val termuxRunPermGranted: Boolean = false,
  val ffmpegNote: String = "Termux FFmpeg + Manim + Remotion/GSAP + অন-ডিভাইস 24fps MP4 রেন্ডার সক্রিয় ✓।",
  val piperTtsNote: String = "Free-First নীতি: Gemini Free Voice / Piper TTS / LXR Formant — কোনো Paid API অনুমতি ছাড়া চলে না ✓।",
  val remotionNote: String = "Remotion (.tsx), GSAP HyperFrames (.html) ও Manim (.py) বান্ডিল প্রতিটি প্রজেক্টে এক্সপোর্ট হয়।",
  val stockMediaNote: String = "ফ্রি রিয়েল ফুটেজ (Wikimedia Commons, Internet Archive, NASA) + আপনার Reference ইমেজ + Character Library সক্রিয় ✓।",
  val aiVideoApiDisclosureBn: String =
    "খরচ ও সীমাবদ্ধতার স্বচ্ছতা: Google Veo, Kling, Runway, Hailuo, Seedance হলো Paid API; Wan/LTX/Hunyuan-এর জন্য Desktop GPU লাগে। ফ্রি অবস্থায় অন-ডিভাইস 24fps 3D আর্টিকুলেটেড অ্যানিমেশন, Reference Lip-Sync ও ফ্রি স্টক ভিডিও চলে।",
)

data class VideoProject(
  val id: String,
  val slug: String,
  val title: String,
  val prompt: String,
  val videoType: VideoType = VideoType.ANIMATION,
  val animationFormat: AnimationFormat = AnimationFormat.SHORT_FILM_3D,
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
  val seriesId: String = "",
  val episodeNumber: Int = 1,
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
  val characterLibrary: List<CharacterProfile> = emptyList(),
  val selectedCastIds: List<String> = emptyList(),
  val seriesProjects: List<CartoonSeriesProject> = emptyList(),
  val activeSeriesId: String? = null,
  val showCartoonSeriesStudio: Boolean = false,
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
