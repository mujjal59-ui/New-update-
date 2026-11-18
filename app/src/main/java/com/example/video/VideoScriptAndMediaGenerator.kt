package com.example.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import com.example.data.Store
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class GeneratedStoryBlueprint(
  val title: String,
  val topicAnalysisSummary: String,
  val characters: List<CharacterProfile>,
  val scenes: List<VideoScenePlan>,
  val bgmMoodLabel: String,
  val fullScriptText: String,
  val usedAiEngine: String,
)

data class VoiceoverSynthesisResult(
  val pcm24kMono: ShortArray,
  val sampleRate: Int,
  val engineLabel: String,
  val sceneVoiceMap: Map<Int, String>,
)

object VideoScriptAndMediaGenerator {

  private val ttsClient by lazy {
    OkHttpClient.Builder()
      .connectionPool(okhttp3.ConnectionPool(4, 2, TimeUnit.MINUTES))
      .connectTimeout(3000, TimeUnit.MILLISECONDS)
      .readTimeout(8500, TimeUnit.MILLISECONDS)
      .callTimeout(12000, TimeUnit.MILLISECONDS)
      .build()
  }

  fun pickBestGoogleVoice(
    prompt: String,
    videoType: VideoType,
    settings: ProductionSettings,
  ): String {
    if (!settings.autoSelectVoiceByAi) return settings.selectedVoiceId
    val lower = prompt.lowercase()
    return when {
      lower.contains("শিশু") || lower.contains("বাচ্চা") || lower.contains("ফুলকপি") ||
        lower.contains("আপেল") || lower.contains("কলা") || lower.contains("kid") -> "Aoede"
      videoType == VideoType.DRAMA_SERIAL -> "Leda"
      videoType == VideoType.FASHION_360 -> "Sulafat"
      videoType == VideoType.TALKING_PRODUCE -> "Achernar"
      videoType == VideoType.MOTION_GRAPHICS || videoType == VideoType.MOTIVATIONAL || videoType == VideoType.CINEMATIC -> "Orus"
      videoType == VideoType.DOCUMENTARY -> "Charon"
      videoType == VideoType.EDUCATIONAL || videoType == VideoType.EXPLAINER -> "Iapetus"
      videoType == VideoType.STORYTELLING -> "Leda"
      else -> "Aoede"
    }
  }

  suspend fun generateTopicAndScript(
    context: Context? = null,
    prompt: String,
    videoType: VideoType,
    customTypeLabel: String,
    durationPreset: DurationPreset,
    customDurationSec: Int,
    settings: ProductionSettings,
    userMediaItems: List<UserMediaItem> = emptyList(),
    selectedBundledFiles: List<String> = emptyList(),
  ): GeneratedStoryBlueprint {
    val cleanPrompt = prompt.trim().ifBlank { VideoStudioCatalog.DEFAULT_EXAMPLE_PROMPT }
    val targetTotalSec = when (durationPreset) {
      DurationPreset.SHORT_15_60S -> customDurationSec.coerceIn(15, 60)
      DurationPreset.ANIMATION_3_5M -> customDurationSec.coerceIn(90, 300)
      DurationPreset.YOUTUBE_ADAPTIVE -> 90
      DurationPreset.CUSTOM -> customDurationSec.coerceAtLeast(10)
    }
    val desiredSceneCount = when {
      userMediaItems.count { it.kind != UserMediaKind.AUDIO } >= 4 ->
        userMediaItems.count { it.kind != UserMediaKind.AUDIO }.coerceIn(4, 16)
      targetTotalSec >= 150 -> 6
      targetTotalSec >= 75 -> 5
      else -> 4
    }
    val primaryVoice = pickBestGoogleVoice(cleanPrompt, videoType, settings)

    val aiBlueprint = runCatching {
      withTimeout(10_000L) {
        val model = Firebase.ai.generativeModel(modelName = "gemini-3.5-flash-lite")
        val typeName = customTypeLabel.ifBlank { videoType.labelEn }
        val systemReq = """
          You are an OpenMontage AI Video Director. Create a lively, expressive multi-scene storyboard JSON in ${if (cleanPrompt.any { it in '\u0980'..'\u09FF' }) "Bengali" else "English"}.
          Prompt: $cleanPrompt
          VideoType: $typeName
          TargetDurationSec: $targetTotalSec
          Return ONLY valid JSON:
          {
            "title": "short catchy title",
            "topicAnalysis": "2-sentence creative direction",
            "bgmMood": "ambient mood label",
            "characters": [{"name":"...","role":"...","visual":"...","voiceId":"Aoede|Iapetus|Leda|Orus|Achernar|Charon|Sulafat","variant":"cauliflower|cabbage|banana|apple|flower|finger|girl_3d|boy_3d"}],
            "scenes": [{"title":"...","narration":"clear natural spoken line (not monotonic)","dialogue":"expressive character dialogue","character":"...","voiceId":"Aoede|Iapetus|Leda|Orus|Achernar|Charon|Sulafat","emotion":"Warm|Excited|Playful|Dramatic","motion":"talking|walking|dancing|fashion_360|motion_graphics|drama_cinematic","visualPrompt":"...","headline":"short screen title","themeKeyword":"village|produce_story|dance|fashion|drama|space_kid|general"}]
          }
          Produce $desiredSceneCount vivid varied scenes. No markdown fences.
        """.trimIndent()
        val resp = model.generateContent(systemReq).text.orEmpty().trim()
        parseAiStoryboardJson(
          context = context,
          raw = resp,
          prompt = cleanPrompt,
          videoType = videoType,
          targetTotalSec = targetTotalSec,
          primaryVoice = primaryVoice,
          settings = settings,
          userMediaItems = userMediaItems,
          selectedBundledFiles = selectedBundledFiles,
        )
      }
    }.getOrNull()

    return aiBlueprint ?: buildDeterministicRichStoryboard(
      context = context,
      prompt = cleanPrompt,
      videoType = videoType,
      customTypeLabel = customTypeLabel,
      targetTotalSec = targetTotalSec,
      desiredSceneCount = desiredSceneCount,
      primaryVoice = primaryVoice,
      settings = settings,
      userMediaItems = userMediaItems,
      selectedBundledFiles = selectedBundledFiles,
    )
  }

  private fun parseAiStoryboardJson(
    context: Context?,
    raw: String,
    prompt: String,
    videoType: VideoType,
    targetTotalSec: Int,
    primaryVoice: String,
    settings: ProductionSettings,
    userMediaItems: List<UserMediaItem>,
    selectedBundledFiles: List<String>,
  ): GeneratedStoryBlueprint? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val json = JSONObject(raw.substring(start, end + 1))
    val title = json.optString("title").ifBlank { return null }
    val analysis = json.optString("topicAnalysis").ifBlank { "OpenMontage AI + অ্যানিমেটেড স্টোরিবোর্ড বিশ্লেষণ সম্পন্ন।" }
    val bgmMood = json.optString("bgmMood").ifBlank { "${videoType.defaultMood} Synth (Free)" }

    val charsJson = json.optJSONArray("characters") ?: JSONArray()
    val characters = buildList {
      for (i in 0 until charsJson.length()) {
        val c = charsJson.optJSONObject(i) ?: continue
        val vId = c.optString("voiceId").takeIf { it in VideoStudioCatalog.voiceIds() } ?: primaryVoice
        add(
          CharacterProfile(
            id = "char_$i",
            name = c.optString("name", "অভিযাত্রী"),
            roleBn = c.optString("role", "মূল চরিত্র"),
            visualDescription = c.optString("visual", "রঙিন অ্যানিমেটেড চরিত্র"),
            recommendedVoiceId = vId,
            primaryColorHex = if (i % 2 == 0) 0xFF00E5FF else 0xFFFFB300,
            accentColorHex = if (i % 2 == 0) 0xFF0051FF else 0xFFFF0D55,
            avatarVariant = c.optString("variant", if (i == 0) "boy_3d" else "girl_3d"),
          )
        )
      }
    }.ifEmpty { defaultCharactersForPrompt(prompt, videoType, primaryVoice) }

    val visualUserItems = userMediaItems.filter { it.kind != UserMediaKind.AUDIO }
    val scenesJson = json.optJSONArray("scenes") ?: JSONArray()
    val count = scenesJson.length().coerceAtLeast(1)
    val perSceneSec = (targetTotalSec.toFloat() / count).coerceAtLeast(3.5f)
    val scenes = buildList {
      for (i in 0 until scenesJson.length()) {
        val s = scenesJson.optJSONObject(i) ?: continue
        val vId = if (settings.useCharacterVoicesPerScene) {
          s.optString("voiceId").takeIf { it in VideoStudioCatalog.voiceIds() } ?: primaryVoice
        } else settings.selectedVoiceId
        val nar = s.optString("narration", prompt)
        val dlg = s.optString("dialogue", "")
        val matchedPair =
          if (context != null) {
            StudioMediaLibrary.matchAssetsForScene(context, prompt, "$nar $dlg", videoType, i + 1, selectedBundledFiles, visualUserItems)
          } else null to null
        val userMedia = visualUserItems.getOrNull(i % visualUserItems.size.coerceAtLeast(1))
        val charPreset = detectCharacterPreset(prompt, "$nar $dlg", settings, i)
        val motionStyle = detectMotionChoreography(prompt, s.optString("motion"), videoType, settings, i)
        add(
          VideoScenePlan(
            index = i + 1,
            title = s.optString("title", "দৃশ্য ${i + 1}"),
            narrationText = nar,
            dialogueLine = dlg,
            characterName = s.optString("character", characters.first().name),
            voiceId = vId,
            emotionalTone = s.optString("emotion", "Expressive"),
            visualPrompt = s.optString("visualPrompt", "Animated scene frame"),
            themeKeyword = s.optString("themeKeyword", inferThemeKeyword(prompt, i)),
            visualSource = if (userMedia != null) VisualSourceKind.FREE_STOCK_OPEN_MEDIA else VisualSourceKind.DEPTH_3D_STYLE,
            effectTag = motionStyle.labelBn,
            overlayHeadline = s.optString("headline", "Scene ${i + 1}"),
            durationSec = perSceneSec,
            trimStartSec = 0f,
            trimEndSec = perSceneSec,
            transition = settings.transitionEffect,
            sfxCue = if (i == 0) "Sparkle Intro" else "Whoosh Transition",
            matchedAssetFile = matchedPair.first?.fileName.orEmpty(),
            backgroundAssetFile = matchedPair.second?.fileName.orEmpty(),
            userMediaPath = userMedia?.cachedFilePath.orEmpty(),
            userMediaIsVideo = userMedia?.kind == UserMediaKind.VIDEO,
            motionStyle = motionStyle,
            characterPreset = charPreset,
          )
        )
      }
    }
    if (scenes.isEmpty()) return null
    val scriptTxt = formatFullScriptText(title, analysis, characters, scenes)
    return GeneratedStoryBlueprint(
      title = title,
      topicAnalysisSummary = analysis,
      characters = characters,
      scenes = scenes,
      bgmMoodLabel = bgmMood,
      fullScriptText = scriptTxt,
      usedAiEngine = "Firebase AI Free (Gemini Flash Lite)",
    )
  }

  private fun detectCharacterPreset(
    prompt: String,
    sceneText: String,
    settings: ProductionSettings,
    sceneIdx: Int,
  ): TalkingCharacterPreset {
    if (settings.preferredCharacterPreset != TalkingCharacterPreset.AUTO) {
      return settings.preferredCharacterPreset
    }
    val all = "$prompt $sceneText".lowercase()
    val produceHits = buildList {
      if (all.contains("ফুলকপি") || all.contains("cauliflower")) add(TalkingCharacterPreset.CAULIFLOWER)
      if (all.contains("পাতাকপি") || all.contains("cabbage")) add(TalkingCharacterPreset.CABBAGE)
      if (all.contains("কলা") || all.contains("banana")) add(TalkingCharacterPreset.BANANA)
      if (all.contains("আপেল") || all.contains("apple")) add(TalkingCharacterPreset.APPLE)
      if (all.contains("আঙ্গুল") || all.contains("আঙুল") || all.contains("finger")) add(TalkingCharacterPreset.FINGER)
      if ((all.contains("ফুল") && !all.contains("ফুলকপি")) || all.contains("flower")) add(TalkingCharacterPreset.FLOWER)
    }
    if (produceHits.isNotEmpty()) return produceHits[sceneIdx % produceHits.size]
    if (all.contains("সবজি") || all.contains("ফল") || all.contains("fruit") || all.contains("veg")) {
      val list = listOf(
        TalkingCharacterPreset.CAULIFLOWER,
        TalkingCharacterPreset.CABBAGE,
        TalkingCharacterPreset.BANANA,
        TalkingCharacterPreset.APPLE,
        TalkingCharacterPreset.FLOWER,
        TalkingCharacterPreset.FINGER,
      )
      return list[sceneIdx % list.size]
    }
    return TalkingCharacterPreset.BUNDLED_3D
  }

  private fun detectMotionChoreography(
    prompt: String,
    rawHint: String,
    videoType: VideoType,
    settings: ProductionSettings,
    sceneIdx: Int,
  ): MotionChoreography {
    if (settings.preferredMotionStyle != MotionChoreography.AUTO) {
      return settings.preferredMotionStyle
    }
    val q = "$prompt $rawHint ${videoType.labelEn}".lowercase()
    return when {
      videoType == VideoType.FASHION_360 || q.contains("৩৬০") || q.contains("360") || q.contains("ফ্যাশন") || q.contains("fashion") ->
        MotionChoreography.FASHION_360_SPIN
      videoType == VideoType.MOTION_GRAPHICS || q.contains("মোশন") || q.contains("motion") ->
        MotionChoreography.MOTION_GRAPHICS
      videoType == VideoType.DRAMA_SERIAL || q.contains("নাটক") || q.contains("সিরিয়াল") || q.contains("drama") || q.contains("serial") ->
        if (sceneIdx % 2 == 0) MotionChoreography.DRAMA_CINEMATIC else MotionChoreography.TALKING_LIPSYNC
      q.contains("নাচ") || q.contains("dance") || q.contains("গান") ->
        if (sceneIdx % 3 == 0) MotionChoreography.TALKING_LIPSYNC else MotionChoreography.DANCING
      q.contains("হাঁট") || q.contains("walk") ->
        MotionChoreography.WALKING
      else ->
        listOf(
          MotionChoreography.TALKING_LIPSYNC,
          MotionChoreography.WALKING,
          MotionChoreography.DANCING,
          MotionChoreography.FASHION_360_SPIN,
        )[sceneIdx % 4]
    }
  }

  private fun inferThemeKeyword(prompt: String, sceneIndex: Int): String {
    val l = prompt.lowercase()
    return when {
      l.contains("মহাকাশ") || l.contains("space") || l.contains("গ্রহ") || l.contains("rocket") ->
        listOf("space_kid", "rocket_launch", "solar_system", "planet_earth")[sceneIndex % 4]
      l.contains("ফুলকপি") || l.contains("পাতাকপি") || l.contains("আপেল") || l.contains("কলা") || l.contains("সবজি") || l.contains("ফল") ->
        "produce_story"
      l.contains("গ্রাম") || l.contains("village") -> "village"
      else -> "general"
    }
  }

  private fun defaultCharactersForPrompt(
    prompt: String,
    videoType: VideoType,
    primaryVoice: String,
  ): List<CharacterProfile> {
    val q = prompt.lowercase()
    return when {
      q.contains("ফুলকপি") || q.contains("পাতাকপি") || q.contains("আপেল") || q.contains("কলা") ||
        videoType == VideoType.TALKING_PRODUCE ->
        listOf(
          CharacterProfile("char_veg", "কথা বলা ফুলকপি ও পাতাকপি", "সবুজ সবজি চরিত্র", "হাঁটাচলা ও নাচতে থাকা মুখ-লড়ানো ফুলকপি ও পাতাকপি", "Achernar", 0xFF22C55E, 0xFFFACC15, "cauliflower"),
          CharacterProfile("char_fruit", "কথা বলা আপেল ও কলা", "মজার ফল বন্ধু", "হাস্যোজ্জ্বল চোখ ও লিপ-সিঙ্ক করা আপেল, কলা ও ফুল", "Aoede", 0xFFEF4444, 0xFF00E5FF, "apple"),
        )
      videoType == VideoType.DRAMA_SERIAL || q.contains("নাটক") || q.contains("সিরিয়াল") ->
        listOf(
          CharacterProfile("char_heroine", "অনন্যা (মূল চরিত্র)", "নাটকের প্রধান নায়িকা", "অভিব্যক্তিপূর্ণ ৩ডি চরিত্র ও সংলাপ", "Leda", 0xFFE879F9, 0xFF00E5FF, "girl_3d"),
          CharacterProfile("char_hero", "রাফি (সহ-চরিত্র)", "নাটকের সহ-অভিনেতা", "দৃঢ় ও স্পষ্ট কণ্ঠের চরিত্র", "Orus", 0xFF38BDF8, 0xFFFFB300, "boy_3d"),
        )
      videoType == VideoType.FASHION_360 || q.contains("ফ্যাশন") || q.contains("৩৬০") ->
        listOf(
          CharacterProfile("char_model", "৩৬০° ফ্যাশন মডেল", "রানওয়ে মডেল", "৩৬০ ডিগ্রি ঘূর্ণায়মান স্টাইলিশ ফ্যাশন শোকেস", "Sulafat", 0xFFF43F5E, 0xFFFACC15, "girl_3d"),
          CharacterProfile("char_host", "ফ্যাশন ডিরেক্টর", "উপস্থাপক", "রঙিন স্পটলাইটে বর্ণনা", "Orus", 0xFF00E5FF, 0xFF9C27B0, "boy_3d"),
        )
      q.contains("মহাকাশ") || q.contains("space") ->
        listOf(
          CharacterProfile("char_aryan", "আরিয়ান (শিশু অভিযাত্রী)", "মহাকাশ অভিযাত্রী", "স্পেসস্যুট পরা শিশু ও ৩ডি অ্যানিমেশন", "Aoede", 0xFF00E5FF, 0xFF0051FF, "child_explorer"),
          CharacterProfile("char_astro", "অ্যাস্ট্রো গাইড", "AI শিক্ষক", "হোলোগ্রাফিক গাইড", "Iapetus", 0xFFFFB300, 0xFF9C27B0, "robot_guide"),
        )
      else ->
        listOf(
          CharacterProfile("char_lead", "মূল চরিত্র", "গল্প ও ভিডিও উপস্থাপক", "রিয়েলিস্টিক ৩ডি কার্টুন ও কথাবার্তা বলা চরিত্র", primaryVoice, 0xFF00E5FF, 0xFF9C27B0, "girl_3d"),
          CharacterProfile("char_companion", "বন্ধু চরিত্র", "সংলাপ ও নাচ-হাঁটাচলা সঙ্গী", "প্রাণবন্ত ৩ডি বন্ধু", "Iapetus", 0xFF00E676, 0xFF0051FF, "boy_3d"),
        )
    }
  }

  fun buildDeterministicRichStoryboard(
    context: Context? = null,
    prompt: String,
    videoType: VideoType,
    customTypeLabel: String,
    targetTotalSec: Int,
    desiredSceneCount: Int = 4,
    primaryVoice: String,
    settings: ProductionSettings,
    userMediaItems: List<UserMediaItem> = emptyList(),
    selectedBundledFiles: List<String> = emptyList(),
  ): GeneratedStoryBlueprint {
    val q = prompt.lowercase()
    val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
    val characters = defaultCharactersForPrompt(prompt, videoType, primaryVoice)
    val typeTitle = customTypeLabel.ifBlank { videoType.labelBn }
    val visualUserItems = userMediaItems.filter { it.kind != UserMediaKind.AUDIO }

    val templateScenes: List<Pair<Triple<String, String, String>, String>> = when {
      q.contains("ফুলকপি") || q.contains("পাতাকপি") || q.contains("আপেল") || q.contains("কলা") ||
        q.contains("ফুল") || q.contains("আঙ্গুল") || videoType == VideoType.TALKING_PRODUCE ->
        listOf(
          Triple("দৃশ্য ১: সবজি ও ফলের মজার আসর", "সবুজ মাঠের মাঝে কথা বলা ফুলকপি আর পাতাকপি নেচে-গেয়ে সবাইকে দারুণ স্বাগত জানাচ্ছে!", "ফুলকপি: হ্যালো বন্ধুরা! আজ আমরা হাঁটাচলা আর নাচতে নাচতে মজার গল্প বলবো!") to "produce_story",
          Triple("দৃশ্য ২: কলা ও আপেলের হাসিখুশি আড্ডা", "রঙিন বাগানে কলা আর লাল আপেল মুখ নাড়িয়ে স্বাস্থ্য আর আনন্দের কথা বলছে।", "আপেল ও কলা: প্রতিদিন টাটকা ফল খেলে শরীর হয় দারুণ শক্তিশালী আর চনমনে!") to "produce_story",
          Triple("দৃশ্য ৩: ফুল ও কথা বলা আঙ্গুলের চমক", "সুন্দর ফুটে থাকা ফুল দোল খাচ্ছে আর ছোট্ট আঙ্গুল বন্ধু সবাইকে গুনে গুনে ছন্দ শেখাচ্ছে!", "ফুল ও আঙ্গুল: এক দুই তিন চার — নাচের তালে শিখবো এবার নতুন সব মজার বিষয়!") to "produce_story",
          Triple("দৃশ্য ৪: সবার মিলিত নাচ ও বিদায়", "ফুলকপি, পাতাকপি, কলা, আপেল, ফুল ও আঙ্গুল একসাথে নেচে সুন্দর সমাপ্তি করছে!", "সবাই মিলে: দেখা হবে পরের পর্বে—ভালো থেকো সবাই!") to "produce_story",
        )
      videoType == VideoType.DRAMA_SERIAL || q.contains("নাটক") || q.contains("সিরিয়াল") ->
        listOf(
          Triple("দৃশ্য ১: নাটকের আবেগঘন সূচনা", "$prompt — সকালের শান্ত গ্রামে চরিত্রদের নতুন দিনের নাটকীয় গল্প শুরু হলো।", "অনন্যা: আজকের এই সিদ্ধান্ত আমাদের পুরো পরিবারের গল্প বদলে দেবে!") to "drama",
          Triple("দৃশ্য ২: দ্বন্দ্ব ও চমকপ্রদ সংলাপ", "কাহিনীর মোড় ঘুরে যায় যখন হঠাৎ এক অপ্রত্যাশিত খবর সবার সামনে চলে আসে।", "রাফি: সত্য কখনো চাপা থাকে না, আমি শেষ পর্যন্ত লড়াই করবো!") to "drama",
          Triple("দৃশ্য ৩: আবেগ ও উত্তেজনার ক্লাইম্যাক্স", "চোখে চোখ রেখে সত্যের মুখোমুখি হয় সবাই — প্রতিটি সংলাপে স্পষ্ট আবেগ ফুটে ওঠে।", "অনন্যা: বিশ্বাস আর ভালোবাসাই আমাদের আসল শক্তি!") to "drama",
          Triple("দৃশ্য ৪: সিরিয়াল পর্বের সমাপ্তি", "নতুন রহস্য আর পরবর্তী পর্বের চমক রেখে আজকের নাটকীয় অধ্যায় শেষ হয়।", "উপস্থাপক: পরবর্তী সিরিয়াল পর্ব দেখতে সাথেই থাকুন!") to "drama",
        )
      videoType == VideoType.FASHION_360 || q.contains("৩৬০") || q.contains("ফ্যাশন") ->
        listOf(
          Triple("দৃশ্য ১: ৩৬০° রানওয়ে গ্র্যান্ড এন্ট্রি", "উজ্জ্বল নিয়ন স্পটলাইটের মাঝে আমাদের ফ্যাশন মডেল ৩৬০ ডিগ্রি ঘুরে স্টাইল দেখাচ্ছে!", "মডেল: দেখুন আধুনিক ও ঐতিহ্যবাহী পোশাকের নিখুঁত ৩৬০ ডিগ্রি উপস্থাপনা!") to "fashion",
          Triple("দৃশ্য ২: ক্লোজ-আপ ডিটেইল ও রঙের ছটা", "প্রতিটি ভাঁজ, রঙ আর ডিজাইন ৩৬০ ঘূর্ণনের সাথে দারুণ স্পষ্ট হয়ে উঠছে।", "উপস্থাপক: রঙ আর স্টাইলের এই সমন্বয় নজর কাড়বে সবার!") to "fashion",
          Triple("দৃশ্য ৩: স্টাইলিশ ওয়াক ও ড্যান্স পোজ", "রানওয়েতে আত্মবিশ্বাসী হাঁটাচলা আর ছন্দময় মুভমেন্টে জমে উঠেছে শো।", "মডেল: নিজের পছন্দমতো স্টাইল বেছে নিন এখনই!") to "fashion",
          Triple("দৃশ্য ৪: গ্র্যান্ড ৩৬০° ফিনালে", "চারপাশের আলোয় ঝলমলে ফিনালে পোজ দিয়ে শেষ হচ্ছে ফ্যাশন শোকেস।", "উপস্থাপক: LXR AI ৩৬০ স্টুডিওতে আপনাকে স্বাগতম!") to "fashion",
        )
      q.contains("মহাকাশ") || q.lowercase().contains("space") ->
        listOf(
          Triple("দৃশ্য ১: মহাকাশের স্বপ্নযাত্রা", "শিশু আরিয়ান তারাদের জগৎ দেখে অবাক হয়ে প্রশ্ন করছে আর আকাশে উড়ার স্বপ্ন দেখছে!", "আরিয়ান: ওহ! গ্রহ আর নক্ষত্রের দেশে যেতে আমার ভীষণ ইচ্ছে করছে!") to "space_kid",
          Triple("দৃশ্য ২: রকেট উৎক্ষেপণ ও গতি", "৩, ২, ১! ঝলমলে রকেটে চড়ে আরিয়ান ও তার রোবট বন্ধু পৃথিবী ছেড়ে মহাকাশে পাড়ি জমায়।", "অ্যাস্ট্রো: সিটবেল্ট বেঁধে নাও! আমরা এখন মেঘ পেরিয়ে নক্ষত্রের পথে!") to "rocket_launch",
          Triple("দৃশ্য ৩: সৌরজগতের বিস্ময়", "লাল মঙ্গল আর বিশাল শনি গ্রহের বলয় দেখে আনন্দে হাততালি দেয় আরিয়ান!", "আরিয়ান: শনির চারপাশের রঙিন বলয় দেখতে কী চমৎকার!") to "solar_system",
          Triple("দৃশ্য ৪: সুন্দর নীল পৃথিবী", "দূর থেকে উজ্জ্বল মুক্তার মতো নীল পৃথিবীকে দেখে বিজ্ঞান শেখার অঙ্গীকার করে।", "অ্যাস্ট্রো: প্রতিদিন নতুন কিছু শিখলেই তোমরা হবে ভবিষ্যতের বিজ্ঞানী!") to "planet_earth",
        )
      else ->
        listOf(
          Triple("দৃশ্য ১: চমকপ্রদ সূচনা (Intro)", if (isBengali) "$prompt — প্রাণবন্ত অ্যানিমেশন, হাঁটাচলা ও কথা বলা চরিত্র নিয়ে শুরু হচ্ছে আমাদের নতুন ভিডিও!" else "Welcome! Today we present: $prompt", "উপস্থাপক: চলুন একসাথে দেখে নিই চমৎকার সব দৃশ্য!") to "village",
          Triple("দৃশ্য ২: মূল কাহিনী ও গতিশীল দৃশ্য", if (isBengali) "প্রতিটি কথার তালে তালে চরিত্ররা নড়াচড়া করছে, কথা বলছে এবং দৃশ্যগুলো জীবন্ত হয়ে উঠছে।" else "Characters speak, walk, and dance dynamically in rhythm with every line.", "সঙ্গী: দেখুন প্রতিটি ছবির সাথে মোশন কী সুন্দর মিলে গেছে!") to "general",
          Triple("দৃশ্য ৩: নাচ, মোশন গ্রাফিক্স ও বিস্তারিত", if (isBengali) "বর্ণিল মোশন গ্রাফিক্স, ৩৬০ ডিগ্রি এফেক্ট ও স্পষ্ট বাংলা ভয়েসওভারে জমে উঠেছে পরিবেশনা।" else "Kinetic motion graphics, 360 views, and clear expressive voiceover keep energy high.", "উপস্থাপক: একঘেয়ে নয়—প্রতিটি সেকেন্ডে থাকছে নতুন চমক!") to "dance",
          Triple("দৃশ্য ৪: আনন্দময় সমাপ্তি (Outro)", if (isBengali) "আশা করি ভিডিওটি আপনার অনেক ভালো লেগেছে—চাইলে আরও সীমাহীন ভিডিও তৈরি করতে পারেন!" else "Thank you for watching — create unlimited videos anytime with LXR--AI Studio!", "সবাই: দেখা হবে আবার নতুন কোনো চমকপ্রদ ভিডিওতে!") to "village",
        )
    }

    val perSceneSec = (targetTotalSec.toFloat() / desiredSceneCount).coerceAtLeast(3.5f)
    val scenes = (0 until desiredSceneCount).map { idx ->
      val (triple, themeKey) = templateScenes[idx % templateScenes.size]
      val (scTitle, narration, dialogue) = triple
      val charProfile = characters[idx % characters.size]
      val voiceForScene = if (settings.useCharacterVoicesPerScene) charProfile.recommendedVoiceId else settings.selectedVoiceId
      val matchedPair =
        if (context != null) {
          StudioMediaLibrary.matchAssetsForScene(context, prompt, "$narration $dialogue", videoType, idx + 1, selectedBundledFiles, visualUserItems)
        } else null to null
      val userMedia = if (visualUserItems.isNotEmpty()) visualUserItems[idx % visualUserItems.size] else null
      val charPreset = detectCharacterPreset(prompt, "$narration $dialogue", settings, idx)
      val motionStyle = detectMotionChoreography(prompt, scTitle, videoType, settings, idx)

      VideoScenePlan(
        index = idx + 1,
        title = if (idx < templateScenes.size) scTitle else "দৃশ্য ${idx + 1}: ${scTitle.substringAfter(": ")}",
        narrationText = narration,
        dialogueLine = dialogue,
        characterName = charProfile.name,
        voiceId = voiceForScene,
        emotionalTone = listOf("Warm", "Excited", "Playful", "Dramatic")[idx % 4],
        visualPrompt = "$typeTitle • ${motionStyle.labelBn}: $scTitle",
        themeKeyword = themeKey,
        visualSource = when {
          userMedia != null -> VisualSourceKind.FREE_STOCK_OPEN_MEDIA
          charPreset in setOf(TalkingCharacterPreset.CAULIFLOWER, TalkingCharacterPreset.CABBAGE, TalkingCharacterPreset.BANANA, TalkingCharacterPreset.APPLE, TalkingCharacterPreset.FLOWER, TalkingCharacterPreset.FINGER) ->
            VisualSourceKind.ANIMATED_2D_VECTOR
          else -> VisualSourceKind.DEPTH_3D_STYLE
        },
        effectTag = "${motionStyle.labelBn} • ${charPreset.labelBn}",
        overlayHeadline = scTitle.substringAfter(": ").take(30),
        durationSec = perSceneSec,
        trimStartSec = 0f,
        trimEndSec = perSceneSec,
        transition = settings.transitionEffect,
        sfxCue = if (idx == 0) "Sparkle Intro" else "Dynamic Transition",
        matchedAssetFile = matchedPair.first?.fileName.orEmpty(),
        backgroundAssetFile = matchedPair.second?.fileName.orEmpty(),
        userMediaPath = userMedia?.cachedFilePath.orEmpty(),
        userMediaIsVideo = userMedia?.kind == UserMediaKind.VIDEO,
        motionStyle = motionStyle,
        characterPreset = charPreset,
      )
    }

    val title = "$typeTitle: ${prompt.take(36)}"
    val analysis =
      "বিষয় বিশ্লেষণ: '$prompt' — $typeTitle ফরম্যাটে ${scenes.size}টি ডায়নামিক দৃশ্য (${targetTotalSec}s), ${characters.size}টি কথা বলা/হাঁটা-নাচা চরিত্র ও ${visualUserItems.size}টি কাস্টম মিডিয়াসহ প্রস্তুত।"
    val scriptText = formatFullScriptText(title, analysis, characters, scenes)

    return GeneratedStoryBlueprint(
      title = title,
      topicAnalysisSummary = analysis,
      characters = characters,
      scenes = scenes,
      bgmMoodLabel = "${videoType.defaultMood} Synth (Free CC0)",
      fullScriptText = scriptText,
      usedAiEngine = "LXR--AI OpenMontage Smart Director (100% Free & Unlimited)",
    )
  }

  fun formatFullScriptText(
    title: String,
    analysis: String,
    characters: List<CharacterProfile>,
    scenes: List<VideoScenePlan>,
  ): String = buildString {
    appendLine("==========================================")
    appendLine("LXR--AI OPENMONTAGE VIDEO SCRIPT")
    appendLine("Title: $title")
    appendLine("==========================================")
    appendLine()
    appendLine("TOPIC ANALYSIS:")
    appendLine(analysis)
    appendLine()
    appendLine("CHARACTERS:")
    characters.forEach { c ->
      appendLine("- ${c.name} (${c.roleBn}) | Voice: ${c.recommendedVoiceId} | Visual: ${c.visualDescription}")
    }
    appendLine()
    appendLine("SCENE-BY-SCENE SCRIPT:")
    scenes.forEach { s ->
      appendLine("--- [Scene ${s.index}] ${s.title} (${String.format(Locale.US, "%.1f", s.effectiveDurationSec)}s) ---")
      appendLine("Character / Voice: ${s.characterName} [${s.voiceId} • ${s.emotionalTone}]")
      appendLine("Motion / Visual: ${s.motionStyle.labelBn} | ${s.characterPreset.labelBn} | Asset: ${s.matchedAssetFile.ifBlank { s.userMediaPath }}")
      appendLine("Narration: ${s.narrationText}")
      if (s.dialogueLine.isNotBlank()) appendLine("Dialogue: ${s.dialogueLine}")
      appendLine()
    }
  }

  fun buildSubtitleCues(scenes: List<VideoScenePlan>): List<SubtitleCue> {
    var cursorMs = 0L
    return scenes.mapIndexed { idx, s ->
      val durMs = (s.effectiveDurationSec * 1000f).toLong().coerceAtLeast(1500L)
      val cue = SubtitleCue(
        index = idx + 1,
        startMs = cursorMs,
        endMs = cursorMs + durMs - 120L,
        text = s.narrationText,
      )
      cursorMs += durMs
      cue
    }
  }

  fun buildSrtContent(cues: List<SubtitleCue>): String = buildString {
    cues.forEach { c ->
      appendLine(c.index)
      appendLine("${formatSrtTime(c.startMs)} --> ${formatSrtTime(c.endMs)}")
      appendLine(c.text)
      appendLine()
    }
  }

  private fun formatSrtTime(ms: Long): String {
    val totalSec = ms / 1000
    val hrs = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    val millis = ms % 1000
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", hrs, mins, secs, millis)
  }

  /**
   * Free-only Voiceover generator:
   * 1) Uses custom user-attached audio track if provided
   * 2) Else tries free Gemini Flash TTS (2.5 & 3.1) for crystal-clear human Bengali/English voice
   * 3) Else falls back to expressive multi-contour LXR Offline Voice Synth (never robotic system TTS)
   */
  fun synthesizeSceneVoiceoverPcm(
    context: Context,
    scenes: List<VideoScenePlan>,
    settings: ProductionSettings,
    allowNetworkCloudTry: Boolean = true,
    userAudioItems: List<UserMediaItem> = emptyList(),
  ): VoiceoverSynthesisResult {
    val sampleRate = 24000
    // If user uploaded custom audio, decode its PCM first
    val customAudioFile = userAudioItems.firstOrNull { it.kind == UserMediaKind.AUDIO }?.cachedFilePath
    if (!customAudioFile.isNullOrBlank()) {
      val decoded = decodeAudioFileTo24kMono(customAudioFile, targetRate = sampleRate)
      if (decoded != null && decoded.isNotEmpty()) {
        val totalSamples = (scenes.sumOf { it.effectiveDurationSec.toDouble() } * sampleRate).toInt().coerceAtLeast(sampleRate * 4)
        val aligned = ShortArray(totalSamples) { i -> decoded[i % decoded.size] }
        val map = scenes.associate { it.index to "User Custom Voice (${userAudioItems.first().displayName})" }
        return VoiceoverSynthesisResult(aligned, sampleRate, "User Attached Voice Audio ✓", map)
      }
    }

    val outSamples = mutableListOf<Short>()
    val sceneVoiceUsed = linkedMapOf<Int, String>()
    val apiKey = if (allowNetworkCloudTry) Store.apiKey(context).trim() else ""
    var usedCloudCount = 0

    for (scene in scenes) {
      val voiceName = if (settings.useCharacterVoicesPerScene) scene.voiceId else settings.selectedVoiceId
      val targetSamples = (scene.effectiveDurationSec * sampleRate).toInt().coerceAtLeast(sampleRate)
      val spokenLine = buildString {
        append(scene.narrationText)
        if (scene.dialogueLine.isNotBlank()) {
          append(" ... ")
          append(scene.dialogueLine)
        }
      }.trim()

      val cloudPcm =
        if (apiKey.isNotBlank()) {
          tryFreeGeminiTtsBytes(apiKey, voiceName, scene.emotionalTone, spokenLine)
        } else null

      if (cloudPcm != null && cloudPcm.isNotEmpty()) {
        usedCloudCount++
        sceneVoiceUsed[scene.index] = "$voiceName (Gemini Free Clear Voice)"
        val shortCount = minOf(cloudPcm.size / 2, targetSamples)
        for (i in 0 until shortCount) {
          val lo = cloudPcm[i * 2].toInt() and 0xFF
          val hi = cloudPcm[i * 2 + 1].toInt()
          outSamples.add(((hi shl 8) or lo).toShort())
        }
        for (i in shortCount until targetSamples) outSamples.add(0)
      } else {
        sceneVoiceUsed[scene.index] = "$voiceName (LXR Expressive Offline Voice)"
        val synth = synthesizeExpressiveVoiceFormants(
          text = spokenLine,
          voiceId = voiceName,
          emotion = scene.emotionalTone,
          sampleCount = targetSamples,
          sampleRate = sampleRate,
        )
        for (s in synth) outSamples.add(s)
      }
    }

    val engineLabel = when {
      usedCloudCount == scenes.size && scenes.isNotEmpty() ->
        "Gemini Free Expressive Voice (${settings.selectedVoiceId})"
      usedCloudCount > 0 ->
        "Hybrid Free (Gemini Clear Voice + LXR Offline)"
      else ->
        "LXR Expressive Offline Voice + Piper Ready (Free)"
    }

    return VoiceoverSynthesisResult(
      pcm24kMono = outSamples.toShortArray(),
      sampleRate = sampleRate,
      engineLabel = engineLabel,
      sceneVoiceMap = sceneVoiceUsed,
    )
  }

  private fun decodeAudioFileTo24kMono(path: String, targetRate: Int): ShortArray? {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(path)
      val trackIdx = (0 until extractor.trackCount).firstOrNull { idx ->
        extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      } ?: return null
      extractor.selectTrack(trackIdx)
      val fmt = extractor.getTrackFormat(trackIdx)
      val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
      val srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

      val codec = MediaCodec.createDecoderByType(mime)
      codec.configure(fmt, null, null, 0)
      codec.start()

      val rawMono = mutableListOf<Short>()
      val info = MediaCodec.BufferInfo()
      var inputDone = false
      var outputDone = false
      val maxSamples = targetRate * 180

      while (!outputDone && rawMono.size < maxSamples * 2) {
        if (!inputDone) {
          val inIdx = codec.dequeueInputBuffer(5000)
          if (inIdx >= 0) {
            val inBuf = codec.getInputBuffer(inIdx)!!
            val sampleSize = extractor.readSampleData(inBuf, 0)
            if (sampleSize < 0) {
              codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }
        val outIdx = codec.dequeueOutputBuffer(info, 5000)
        if (outIdx >= 0) {
          val outBuf = codec.getOutputBuffer(outIdx)
          if (info.size > 0 && outBuf != null) {
            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            while (sb.hasRemaining()) {
              var sum = 0
              for (ch in 0 until channels) {
                if (sb.hasRemaining()) sum += sb.get().toInt()
              }
              rawMono.add((sum / channels.coerceAtLeast(1)).toShort())
            }
          }
          codec.releaseOutputBuffer(outIdx, false)
          if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
        }
      }
      runCatching { codec.stop(); codec.release() }
      resamplePcmMono(rawMono.toShortArray(), srcRate, targetRate)
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { extractor.release() }
    }
  }

  fun resamplePcmMono(src: ShortArray, fromRate: Int, toRate: Int): ShortArray {
    if (src.isEmpty() || fromRate <= 0 || toRate <= 0 || fromRate == toRate) return src
    val outLen = ((src.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
    val out = ShortArray(outLen)
    val ratio = fromRate.toDouble() / toRate
    for (i in 0 until outLen) {
      val pos = i * ratio
      val idx = pos.toInt().coerceIn(0, src.lastIndex)
      val next = (idx + 1).coerceAtMost(src.lastIndex)
      val frac = pos - idx
      val s = src[idx] * (1.0 - frac) + src[next] * frac
      out[i] = s.toInt().coerceIn(-32768, 32767).toShort()
    }
    return out
  }

  private fun tryFreeGeminiTtsBytes(
    apiKey: String,
    voiceName: String,
    emotion: String,
    text: String,
  ): ByteArray? {
    val models = listOf(Store.TTS_MODEL_ID, "gemini-2.5-flash-preview-tts").distinct()
    for (modelId in models) {
      val bytes = runCatching {
        val prompt =
          "Deliver with crystal-clear pronunciation, natural expressive rhythm, lively rising and falling intonation (never monotonous), and warm $emotion emotion in Bengali/English: $text"
        val payload = JSONObject()
          .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
          .put(
            "generationConfig",
            JSONObject()
              .put("responseModalities", JSONArray().put("AUDIO"))
              .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName))))
          )
        val req = Request.Builder()
          .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey")
          .post(payload.toString().toRequestBody("application/json".toMediaType()))
          .build()
        ttsClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) return@use null
          val root = JSONObject(resp.body?.string() ?: return@use null)
          val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@use null
          for (i in 0 until parts.length()) {
            val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
            if (!data.isNullOrBlank()) return@use Base64.decode(data, Base64.DEFAULT)
          }
          null
        }
      }.getOrNull()
      if (bytes != null && bytes.isNotEmpty()) return bytes
    }
    return null
  }

  fun synthesizeExpressiveVoiceFormants(
    text: String,
    voiceId: String,
    emotion: String,
    sampleCount: Int,
    sampleRate: Int,
  ): ShortArray {
    val result = ShortArray(sampleCount)
    val baseF0 = when (voiceId) {
      "Aoede" -> 220.0
      "Leda" -> 204.0
      "Achernar" -> 236.0
      "Sulafat" -> 196.0
      "Iapetus" -> 126.0
      "Orus" -> 112.0
      "Charon" -> 96.0
      else -> 185.0
    }
    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }.ifEmpty { listOf("LXR") }
    val syllableStepSamples = (sampleRate * 0.19).toInt().coerceAtLeast(1600)

    for (i in 0 until sampleCount) {
      val t = i.toDouble() / sampleRate
      val syllableIdx = (i / syllableStepSamples)
      val posInSyl = (i % syllableStepSamples).toDouble() / syllableStepSamples
      if (syllableIdx >= words.size * 2 && posInSyl > 0.72) {
        result[i] = 0
        continue
      }
      // Natural phrasing pause every 6 syllables so speech never sounds like a continuous monotone drone
      if (syllableIdx % 7 == 6 && posInSyl > 0.35) {
        result[i] = 0
        continue
      }
      val charSeed = words[syllableIdx % words.size].hashCode()
      val intonationArc = sin(PI * ((syllableIdx % 6).toDouble() / 5.0)) * 0.14
      val emotionMult = if (emotion == "Excited" || emotion == "Playful") 1.07 else 1.0
      val pitchContour = baseF0 * emotionMult * (1.0 + intonationArc + 0.06 * sin(t * 3.8 + (charSeed % 5)))
      val envelope = sin(PI * posInSyl).coerceAtLeast(0.0)
      val harmonic1 = sin(2.0 * PI * pitchContour * t)
      val harmonic2 = 0.45 * sin(2.0 * PI * pitchContour * 2.0 * t + 0.35)
      val formant3 = 0.25 * cos(2.0 * PI * (pitchContour * 4.1 + (charSeed and 0x7F)) * t)
      val sample = ((harmonic1 + harmonic2 + formant3) * envelope * 7800.0).toInt().coerceIn(-28000, 28000)
      result[i] = sample.toShort()
    }
    return result
  }

  fun generateMoodAmbientBgmPcm(totalSamples: Int, sampleRate: Int): ShortArray {
    val out = ShortArray(totalSamples)
    val freqs = doubleArrayOf(261.63, 329.63, 392.00, 493.88, 587.33)
    for (i in 0 until totalSamples) {
      val t = i.toDouble() / sampleRate
      val chordIdx = ((t / 4.0).toInt()) % freqs.size
      val root = freqs[chordIdx] * 0.5
      val fifth = freqs[(chordIdx + 2) % freqs.size]
      val shimmer = freqs[(chordIdx + 3) % freqs.size]
      val padEnv = 0.65 + 0.35 * sin(2.0 * PI * 0.25 * t)
      val sig = (0.55 * sin(2.0 * PI * root * t) + 0.3 * sin(2.0 * PI * fifth * t) + 0.15 * sin(2.0 * PI * shimmer * t)) * padEnv
      out[i] = (sig * 2800.0).toInt().coerceIn(-16000, 16000).toShort()
    }
    return out
  }

  fun mixAudioTracks(
    voicePcm: ShortArray,
    bgmPcm: ShortArray,
    enableVoice: Boolean,
    enableBgm: Boolean,
  ): ShortArray {
    val len = maxOf(voicePcm.size, bgmPcm.size)
    val mixed = ShortArray(len)
    for (i in 0 until len) {
      val v = if (enableVoice && i < voicePcm.size) voicePcm[i].toInt() else 0
      val b = if (enableBgm && i < bgmPcm.size) (bgmPcm[i] * 0.32f).toInt() else 0
      mixed[i] = (v + b).coerceIn(-32767, 32767).toShort()
    }
    return mixed
  }

  fun encodePcm16MonoToWavBytes(pcm: ShortArray, sampleRate: Int): ByteArray {
    val dataBytesLen = pcm.size * 2
    val out = ByteArrayOutputStream(44 + dataBytesLen)
    fun writeStr(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
    fun writeIntLE(v: Int) {
      out.write(v and 0xFF)
      out.write((v shr 8) and 0xFF)
      out.write((v shr 16) and 0xFF)
      out.write((v shr 24) and 0xFF)
    }
    fun writeShortLE(v: Int) {
      out.write(v and 0xFF)
      out.write((v shr 8) and 0xFF)
    }
    writeStr("RIFF")
    writeIntLE(36 + dataBytesLen)
    writeStr("WAVEfmt ")
    writeIntLE(16)
    writeShortLE(1)
    writeShortLE(1)
    writeIntLE(sampleRate)
    writeIntLE(sampleRate * 2)
    writeShortLE(2)
    writeShortLE(16)
    writeStr("data")
    writeIntLE(dataBytesLen)
    for (s in pcm) {
      out.write(s.toInt() and 0xFF)
      out.write((s.toInt() shr 8) and 0xFF)
    }
    return out.toByteArray()
  }

  fun renderSceneBitmap(
    scene: VideoScenePlan,
    width: Int,
    height: Int,
    framePhase: Float,
    showSubtitles: Boolean,
    context: Context? = null,
    mouthOpen: Float = 0.45f,
    globalTimeSec: Float = scene.index * 3f + framePhase * scene.effectiveDurationSec,
  ): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    AdvancedCharacterAnimator.drawAnimatedSceneToCanvas(
      context = context,
      canvas = canvas,
      scene = scene,
      width = width,
      height = height,
      framePhase = framePhase,
      globalTimeSec = globalTimeSec,
      mouthOpen = mouthOpen,
      showSubtitles = showSubtitles,
    )
    return bmp
  }
}
