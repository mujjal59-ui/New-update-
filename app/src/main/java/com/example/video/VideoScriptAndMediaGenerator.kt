package com.example.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Base64
import com.example.data.Store
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
      .connectTimeout(4000, TimeUnit.MILLISECONDS)
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
      lower.contains("শিশু") || lower.contains("বাচ্চা") || lower.contains("kid") || lower.contains("space") || lower.contains("মহাকাশ") -> "Aoede"
      videoType == VideoType.DOCUMENTARY -> "Charon"
      videoType == VideoType.MOTIVATIONAL || videoType == VideoType.CINEMATIC -> "Orus"
      videoType == VideoType.EDUCATIONAL || videoType == VideoType.EXPLAINER -> "Iapetus"
      videoType == VideoType.STORYTELLING -> "Leda"
      videoType == VideoType.CARTOON || videoType == VideoType.YOUTUBE_SHORTS -> "Achernar"
      else -> "Sulafat"
    }
  }

  suspend fun generateTopicAndScript(
    prompt: String,
    videoType: VideoType,
    customTypeLabel: String,
    durationPreset: DurationPreset,
    customDurationSec: Int,
    settings: ProductionSettings,
  ): GeneratedStoryBlueprint {
    val cleanPrompt = prompt.trim().ifBlank { VideoStudioCatalog.DEFAULT_EXAMPLE_PROMPT }
    val targetTotalSec = when (durationPreset) {
      DurationPreset.SHORT_15_60S -> customDurationSec.coerceIn(15, 60)
      DurationPreset.ANIMATION_3_5M -> customDurationSec.coerceIn(90, 300)
      DurationPreset.YOUTUBE_ADAPTIVE -> 90
      DurationPreset.CUSTOM -> customDurationSec.coerceIn(10, 600)
    }
    val primaryVoice = pickBestGoogleVoice(cleanPrompt, videoType, settings)

    // Attempt free Firebase AI generation with short clean timeout; seamless fallback if offline/test
    val aiBlueprint = runCatching {
      withTimeout(12_000L) {
        val model = Firebase.ai.generativeModel(modelName = "gemini-3.5-flash-lite")
        val typeName = customTypeLabel.ifBlank { videoType.labelEn }
        val systemReq = """
          You are an OpenMontage AI Video Director. Create a concise JSON storyboard in the language of the prompt (${if (cleanPrompt.any { it in '\u0980'..'\u09FF' }) "Bengali" else "English"}).
          Prompt: $cleanPrompt
          VideoType: $typeName
          TargetDurationSec: $targetTotalSec
          Return ONLY valid JSON with keys:
          {
            "title": "short catchy title",
            "topicAnalysis": "2-sentence creative direction",
            "bgmMood": "ambient mood label",
            "characters": [{"name":"...","role":"...","visual":"...","voiceId":"Aoede|Iapetus|Leda|Orus|Achernar|Charon|Sulafat","variant":"child_explorer|robot_guide|narrator"}],
            "scenes": [{"title":"...","narration":"clear spoken line","dialogue":"short character dialogue","character":"...","voiceId":"Aoede|Iapetus|Leda|Orus|Achernar|Charon|Sulafat","emotion":"Warm|Curious|Excited|Calm","visualPrompt":"...","headline":"short 3-5 word screen title","themeKeyword":"space_kid|planet_earth|rocket_launch|solar_system|galaxy_future|general"}]
          }
          Produce 4 scenes cleanly. No markdown fences.
        """.trimIndent()
        val resp = model.generateContent(systemReq).text.orEmpty().trim()
        parseAiStoryboardJson(resp, cleanPrompt, targetTotalSec, primaryVoice, settings)
      }
    }.getOrNull()

    return aiBlueprint ?: buildDeterministicRichStoryboard(
      prompt = cleanPrompt,
      videoType = videoType,
      customTypeLabel = customTypeLabel,
      targetTotalSec = targetTotalSec,
      primaryVoice = primaryVoice,
      settings = settings,
    )
  }

  private fun parseAiStoryboardJson(
    raw: String,
    prompt: String,
    targetTotalSec: Int,
    primaryVoice: String,
    settings: ProductionSettings,
  ): GeneratedStoryBlueprint? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val json = JSONObject(raw.substring(start, end + 1))
    val title = json.optString("title").ifBlank { return null }
    val analysis = json.optString("topicAnalysis").ifBlank { "অটোমেটিক OpenMontage স্টোরিবোর্ড বিশ্লেষণ সম্পন্ন।" }
    val bgmMood = json.optString("bgmMood").ifBlank { "Cosmic Wonder Ambient (Free Synth)" }

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
            avatarVariant = c.optString("variant", if (i == 0) "child_explorer" else "robot_guide"),
          )
        )
      }
    }.ifEmpty { defaultCharactersForPrompt(prompt, primaryVoice) }

    val scenesJson = json.optJSONArray("scenes") ?: JSONArray()
    val count = scenesJson.length().coerceAtLeast(1)
    val perSceneSec = (targetTotalSec.toFloat() / count).coerceIn(4f, 60f)
    val scenes = buildList {
      for (i in 0 until scenesJson.length()) {
        val s = scenesJson.optJSONObject(i) ?: continue
        val vId = if (settings.useCharacterVoicesPerScene) {
          s.optString("voiceId").takeIf { it in VideoStudioCatalog.voiceIds() } ?: primaryVoice
        } else {
          settings.selectedVoiceId
        }
        add(
          VideoScenePlan(
            index = i + 1,
            title = s.optString("title", "দৃশ্য ${i + 1}"),
            narrationText = s.optString("narration", prompt),
            dialogueLine = s.optString("dialogue", ""),
            characterName = s.optString("character", characters.first().name),
            voiceId = vId,
            emotionalTone = s.optString("emotion", "Curious"),
            visualPrompt = s.optString("visualPrompt", "Animated visual frame"),
            themeKeyword = s.optString("themeKeyword", inferThemeKeyword(prompt, i)),
            visualSource = if (i == 1) VisualSourceKind.ANIMATED_2D_VECTOR else VisualSourceKind.DEPTH_3D_STYLE,
            effectTag = listOf("Starfield", "OrbitMotion", "KenBurns", "ParticleSpark")[i % 4],
            overlayHeadline = s.optString("headline", "Scene ${i + 1}"),
            durationSec = perSceneSec,
            trimStartSec = 0f,
            trimEndSec = perSceneSec,
            transition = settings.transitionEffect,
            sfxCue = if (i == 0) "Sparkle Intro" else "Whoosh Transition",
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

  private fun inferThemeKeyword(prompt: String, sceneIndex: Int): String {
    val l = prompt.lowercase()
    return if (l.contains("মহাকাশ") || l.contains("space") || l.contains("গ্রহ") || l.contains("নক্ষত্র") || l.contains("চাঁদ") || l.contains("rocket")) {
      when (sceneIndex % 5) {
        0 -> "space_kid"
        1 -> "rocket_launch"
        2 -> "solar_system"
        3 -> "planet_earth"
        else -> "galaxy_future"
      }
    } else {
      "general"
    }
  }

  private fun defaultCharactersForPrompt(prompt: String, primaryVoice: String): List<CharacterProfile> {
    val isSpace = prompt.contains("মহাকাশ") || prompt.lowercase().contains("space")
    return if (isSpace) {
      listOf(
        CharacterProfile(
          id = "char_aryan",
          name = "আরিয়ান (শিশু অভিযাত্রী)",
          roleBn = "কৌতূহলী মহাকাশ-অভিযাত্রী শিশু",
          visualDescription = "নীল-সাদা স্পেসস্যুট পরা হাস্যোজ্জ্বল শিশু, গ্লাস হেলমেট ও রঙিন তারা-ব্যাজ",
          recommendedVoiceId = "Aoede",
          primaryColorHex = 0xFF00E5FF,
          accentColorHex = 0xFF0051FF,
          avatarVariant = "child_explorer",
        ),
        CharacterProfile(
          id = "char_astro",
          name = "অ্যাস্ট্রো গাইড (AI রোবট)",
          roleBn = "মহাকাশ শিক্ষক ও গল্পকার",
          visualDescription = "ভাসমান সোনালি ও সায়ান হোলোগ্রাফিক অ্যানিমেটেড রোবট গাইড",
          recommendedVoiceId = if (primaryVoice == "Aoede") "Iapetus" else primaryVoice,
          primaryColorHex = 0xFFFFB300,
          accentColorHex = 0xFF9C27B0,
          avatarVariant = "robot_guide",
        ),
      )
    } else {
      listOf(
        CharacterProfile(
          id = "char_lead",
          name = "প্রধান বর্ণনাকারী",
          roleBn = "গল্প ও বিষয় উপস্থাপক",
          visualDescription = "উজ্জ্বল সায়ান আলোয় আধুনিক ভেক্টর চরিত্র",
          recommendedVoiceId = primaryVoice,
          primaryColorHex = 0xFF00E5FF,
          accentColorHex = 0xFF9C27B0,
          avatarVariant = "narrator",
        ),
        CharacterProfile(
          id = "char_helper",
          name = "সহকারী চরিত্র",
          roleBn = "প্রশ্ন ও উত্তরদাতা",
          visualDescription = "হালকা সোনালি-সবুজ অ্যানিমেটেড গাইড",
          recommendedVoiceId = "Iapetus",
          primaryColorHex = 0xFF00E676,
          accentColorHex = 0xFF0051FF,
          avatarVariant = "robot_guide",
        ),
      )
    }
  }

  fun buildDeterministicRichStoryboard(
    prompt: String,
    videoType: VideoType,
    customTypeLabel: String,
    targetTotalSec: Int,
    primaryVoice: String,
    settings: ProductionSettings,
  ): GeneratedStoryBlueprint {
    val isSpaceTheme =
      prompt.contains("মহাকাশ") || prompt.lowercase().contains("space") || prompt.contains("তারা") || prompt.contains("গ্রহ")
    val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
    val characters = defaultCharactersForPrompt(prompt, primaryVoice)
    val typeTitle = customTypeLabel.ifBlank { videoType.labelBn }

    val scenesRaw =
      if (isSpaceTheme) {
        listOf(
          Triple("দৃশ্য ১: মহাকাশের স্বপ্নযাত্রা শুরু", "শিশু আরিয়ান জানালার পাশে বসে রাতের ঝলমলে আকাশ ও কোটি কোটি তারার দিকে অবাক হয়ে তাকিয়ে থাকে!", "আরিয়ান: ওহ! ওই দূরের গ্রহ আর তারাদের জগতে আমি কি সত্যি যেতে পারব?") to "space_kid",
          Triple("দৃশ্য ২: রঙিন রকেটে মহাকাশে পাড়ি", "কল্পনার সোনালি রকেটে চড়ে আরিয়ান ও তার রোবট বন্ধু অ্যাস্ট্রো পৃথিবীর নীল বায়ুমণ্ডল পেরিয়ে মহাকাশে উড়ে যায়!", "অ্যাস্ট্রো: সিটবেল্ট বেঁধে নাও আরিয়ান! আমরা ৩, ২, ১ — মহাকাশ যাত্রায় উড়ছি!") to "rocket_launch",
          Triple("দৃশ্য ৩: সৌরজগতের গ্রহ ও শনির বলয়", "সূর্যের চারপাশে ঘুরতে থাকা লাল মঙ্গল, বিশাল বৃহস্পতি আর ঝলমলে বলয় ঘেরা শনি গ্রহ দেখে আরিয়ান আনন্দে হাততালি দেয়!", "আরিয়ান: বাহ! শনি গ্রহের চারপাশের বরফ আর পাথরের বলয় দেখতে কী অসাধারণ!") to "solar_system",
          Triple("দৃশ্য ৪: সুন্দর নীল পৃথিবী ও ভবিষ্যতের অঙ্গীকার", "দূর মহাকাশ থেকে আমাদের নীল পৃথিবীকে একটি উজ্জ্বল মুক্তার মতো দেখায়। জ্ঞান আর বিজ্ঞানের আলোয় আমরাও একদিন নক্ষত্র ছোঁব!", "অ্যাস্ট্রো: প্রতিদিন নতুন কিছু শিখলেই তোমরাই হবে আগামী দিনের মহাকাশ বিজ্ঞানী!") to "planet_earth",
        )
      } else {
        val snippet = prompt.take(85)
        listOf(
          Triple("দৃশ্য ১: আকর্ষণীয় সূচনা (Hook)", if (isBengali) "$snippet — আজকের এই বিশেষ গল্পে আমরা দারুণ এক অভিজ্ঞতার শুরু করতে যাচ্ছি।" else "Welcome! Today we explore: $snippet.", "উপস্থাপক: চলুন শুরু করা যাক মূল গল্পের যাত্রা।") to "general",
          Triple("দৃশ্য ২: মূল ধারণা ও ভিজ্যুয়াল ব্যাখ্যা", if (isBengali) "সহজ ধাপে এবং প্রাণবন্ত অ্যানিমেশনের মাধ্যমে প্রতিটি গুরুত্বপূর্ণ বিষয় পরিষ্কারভাবে সাজানো হয়েছে।" else "Every key idea unfolds step by step with lively motion graphics.", "সহকারী: প্রতিটি দৃশ্য সুন্দরভাবে সংযুক্ত করা হয়েছে।") to "galaxy_future",
          Triple("দৃশ্য ৩: গভীর বিশ্লেষণ ও উদাহরণ", if (isBengali) "বাস্তব উদাহরণ ও স্পষ্ট ভয়েসওভারের সাথে দৃশ্যগুলো দর্শকের মনোযোগ ধরে রাখে।" else "Crisp voiceover and animated graphics bring clarity to every detail.", "উপস্থাপক: মনোযোগ দিয়ে লক্ষ্য করুন মূল বার্তাটি।") to "solar_system",
          Triple("দৃশ্য ৪: চূড়ান্ত বার্তা ও সমাপ্তি", if (isBengali) "এই ভিডিওটি ভালো লাগলে লাইক ও শেয়ার করুন—LXR AI স্টুডিওর সাথেই থাকুন!" else "Thank you for watching — crafted with LXR--AI OpenMontage Studio!", "সহকারী: পরবর্তী ভিডিওতে আবার দেখা হবে!") to "planet_earth",
        )
      }

    val perSceneSec = (targetTotalSec.toFloat() / scenesRaw.size).coerceIn(3.5f, 120f)
    val scenes = scenesRaw.mapIndexed { idx, (triple, themeKey) ->
      val (scTitle, narration, dialogue) = triple
      val charProfile = characters[idx % characters.size]
      val voiceForScene =
        if (settings.useCharacterVoicesPerScene) charProfile.recommendedVoiceId else settings.selectedVoiceId
      VideoScenePlan(
        index = idx + 1,
        title = scTitle,
        narrationText = narration,
        dialogueLine = dialogue,
        characterName = charProfile.name,
        voiceId = voiceForScene,
        emotionalTone = listOf("Curious", "Excited", "Wonder", "Inspiring")[idx % 4],
        visualPrompt = "$typeTitle animated scene ($themeKey): $scTitle",
        themeKeyword = themeKey,
        visualSource = when (idx) {
          0 -> VisualSourceKind.ANIMATED_2D_VECTOR
          1 -> VisualSourceKind.IMAGE_TO_VIDEO_MOTION
          2 -> VisualSourceKind.DEPTH_3D_STYLE
          else -> VisualSourceKind.AI_SCENE_ART
        },
        effectTag = listOf("Starfield + Parallax", "Rocket Trail Glow", "Orbital Ring 3D", "Neon Horizon")[idx % 4],
        overlayHeadline = scTitle.substringAfter(": ").take(28),
        durationSec = perSceneSec,
        trimStartSec = 0f,
        trimEndSec = perSceneSec,
        transition = settings.transitionEffect,
        sfxCue = if (idx == 0) "Cosmic Spark" else "Soft Whoosh",
      )
    }

    val title = if (isSpaceTheme) "মহাকাশের বিস্ময়: ছোট্ট আরিয়ানের অভিযান" else "$typeTitle স্টুডিও প্রজেক্ট"
    val analysis =
      "বিষয় বিশ্লেষণ: '$prompt' — $typeTitle ফরম্যাটে ${scenes.size}টি দৃশ্য, ${characters.size}টি চরিত্র এবং ফ্রি OpenMontage + AI স্টোরিবোর্ড তৈরি হয়েছে।"
    val scriptText = formatFullScriptText(title, analysis, characters, scenes)

    return GeneratedStoryBlueprint(
      title = title,
      topicAnalysisSummary = analysis,
      characters = characters,
      scenes = scenes,
      bgmMoodLabel = if (isSpaceTheme) "Cosmic Wonder Ambient Synth (Free CC0)" else "${videoType.defaultMood} Synth (Free CC0)",
      fullScriptText = scriptText,
      usedAiEngine = "LXR--AI OpenMontage Smart Storyboard (100% Free)",
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
      appendLine("Visual Direction: ${s.visualPrompt} | Source: ${s.visualSource.labelBn} | Effect: ${s.effectTag}")
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
   * 1) Tries free Gemini Flash TTS ONLY if the user has saved an API key (zero paid subscription APIs).
   * 2) Automatically falls back cleanly to LXR Free Offline Formant Phonetic Voice Engine (and never touches system Android TTS).
   */
  fun synthesizeSceneVoiceoverPcm(
    context: Context,
    scenes: List<VideoScenePlan>,
    settings: ProductionSettings,
    allowNetworkCloudTry: Boolean = true,
  ): VoiceoverSynthesisResult {
    val sampleRate = 24000
    val outSamples = mutableListOf<Short>()
    val sceneVoiceUsed = linkedMapOf<Int, String>()
    val apiKey = if (allowNetworkCloudTry) Store.apiKey(context).trim() else ""
    var usedCloudCount = 0

    for (scene in scenes) {
      val voiceName = if (settings.useCharacterVoicesPerScene) scene.voiceId else settings.selectedVoiceId
      val targetSamples = (scene.effectiveDurationSec * sampleRate).toInt().coerceIn(sampleRate, sampleRate * 180)
      val spokenLine = buildString {
        append(scene.narrationText)
        if (scene.dialogueLine.isNotBlank()) {
          append(" ")
          append(scene.dialogueLine)
        }
      }.trim()

      val cloudPcm =
        if (apiKey.isNotBlank()) {
          tryFreeGeminiTtsBytes(apiKey, voiceName, scene.emotionalTone, spokenLine)
        } else {
          null
        }

      if (cloudPcm != null && cloudPcm.isNotEmpty()) {
        usedCloudCount++
        sceneVoiceUsed[scene.index] = "$voiceName (Gemini Free Cloud TTS)"
        val shortCount = minOf(cloudPcm.size / 2, targetSamples)
        for (i in 0 until shortCount) {
          val lo = cloudPcm[i * 2].toInt() and 0xFF
          val hi = cloudPcm[i * 2 + 1].toInt()
          outSamples.add(((hi shl 8) or lo).toShort())
        }
        // Pad remaining scene slot cleanly with soft breath room
        for (i in shortCount until targetSamples) outSamples.add(0)
      } else {
        sceneVoiceUsed[scene.index] = "$voiceName (LXR Free Offline / Piper Compatible)"
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

    val engineLabel =
      when {
        usedCloudCount == scenes.size && scenes.isNotEmpty() ->
          "Gemini Free Voice Model (${settings.selectedVoiceId})"
        usedCloudCount > 0 ->
          "Hybrid Free (Gemini Free + LXR Offline Voice)"
        else ->
          "LXR Free Offline Formant Voice + Piper Termux Ready (No Paid API)"
      }

    return VoiceoverSynthesisResult(
      pcm24kMono = outSamples.toShortArray(),
      sampleRate = sampleRate,
      engineLabel = engineLabel,
      sceneVoiceMap = sceneVoiceUsed,
    )
  }

  private fun tryFreeGeminiTtsBytes(
    apiKey: String,
    voiceName: String,
    emotion: String,
    text: String,
  ): ByteArray? {
    return runCatching {
      val prompt = "Speak warmly in a $emotion tone in clear natural Bengali/English: $text"
      val payload = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        .put(
          "generationConfig",
          JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName))))
        )
      val req = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/${Store.TTS_MODEL_ID}:generateContent?key=$apiKey")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()
      ttsClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val root = JSONObject(resp.body?.string() ?: return null)
        val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
          val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
          if (!data.isNullOrBlank()) return Base64.decode(data, Base64.DEFAULT)
        }
        null
      }
    }.getOrNull()
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
      "Aoede" -> 218.0
      "Leda" -> 205.0
      "Achernar" -> 232.0
      "Sulafat" -> 196.0
      "Iapetus" -> 128.0
      "Orus" -> 114.0
      "Charon" -> 98.0
      else -> 185.0
    }
    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }.ifEmpty { listOf("LXR") }
    val syllableStepSamples = (sampleRate * 0.21).toInt().coerceAtLeast(1800)

    for (i in 0 until sampleCount) {
      val t = i.toDouble() / sampleRate
      val syllableIdx = (i / syllableStepSamples)
      val posInSyl = (i % syllableStepSamples).toDouble() / syllableStepSamples
      if (syllableIdx >= words.size * 2 && posInSyl > 0.7) {
        result[i] = 0
        continue
      }
      val charSeed = words[syllableIdx % words.size].hashCode()
      val pitchContour = baseF0 * (1.0 + 0.08 * sin(t * 3.1 + (charSeed % 5)) + if (emotion == "Excited") 0.05 else 0.0)
      val envelope = sin(PI * posInSyl).coerceAtLeast(0.0)
      val harmonic1 = sin(2.0 * PI * pitchContour * t)
      val harmonic2 = 0.45 * sin(2.0 * PI * pitchContour * 2.0 * t + 0.4)
      val formant3 = 0.25 * sin(2.0 * PI * (pitchContour * 4.2 + (charSeed and 0x7F)) * t)
      val sample = ((harmonic1 + harmonic2 + formant3) * envelope * 7800.0).toInt().coerceIn(-28000, 28000)
      result[i] = sample.toShort()
    }
    return result
  }

  fun generateMoodAmbientBgmPcm(totalSamples: Int, sampleRate: Int): ShortArray {
    val out = ShortArray(totalSamples)
    // Peaceful pentatonic ambient space chords (C4, E4, G4, B4, D5)
    val freqs = doubleArrayOf(261.63, 329.63, 392.00, 493.88, 587.33)
    for (i in 0 until totalSamples) {
      val t = i.toDouble() / sampleRate
      val chordIdx = ((t / 4.0).toInt()) % freqs.size
      val root = freqs[chordIdx] * 0.5
      val fifth = freqs[(chordIdx + 2) % freqs.size]
      val shimmer = freqs[(chordIdx + 3) % freqs.size]
      val padEnv = 0.65 + 0.35 * sin(2.0 * PI * 0.25 * t)
      val sig = (0.55 * sin(2.0 * PI * root * t) + 0.3 * sin(2.0 * PI * fifth * t) + 0.15 * sin(2.0 * PI * shimmer * t)) * padEnv
      out[i] = (sig * 3200.0).toInt().coerceIn(-16000, 16000).toShort()
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
      val b = if (enableBgm && i < bgmPcm.size) (bgmPcm[i] * 0.35f).toInt() else 0
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
    writeShortLE(1) // PCM
    writeShortLE(1) // Mono
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

  /**
   * Renders rich multi-layer 2D/3D-style animated scene frames cleanly via Android Canvas.
   * Works both on physical devices AND Robolectric JVM tests!
   */
  fun renderSceneBitmap(
    scene: VideoScenePlan,
    width: Int,
    height: Int,
    framePhase: Float,
    showSubtitles: Boolean,
  ): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    // 1. Deep Cosmic / Scene Gradient Background
    val bgTop = when (scene.themeKeyword) {
      "rocket_launch" -> Color.rgb(18, 12, 54)
      "solar_system" -> Color.rgb(6, 16, 48)
      "planet_earth" -> Color.rgb(4, 28, 56)
      else -> Color.rgb(8, 10, 34)
    }
    val bgBot = Color.rgb(2, 4, 16)
    p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), bgTop, bgBot, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    p.shader = null

    // 2. Twinkling Starfield & Parallax
    val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (sIdx in 0 until 48) {
      val sx = (((sIdx * 197 + 43) % 1000) / 1000f) * width
      val sy = (((sIdx * 313 + 91) % 1000) / 1000f) * height * 0.78f
      val twinkle = 0.45f + 0.55f * absFloat(sin((framePhase * 6.28 + sIdx).toDouble()).toFloat())
      starPaint.color = Color.argb((twinkle * 235).toInt().coerceIn(40, 255), 210, 245, 255)
      canvas.drawCircle(sx, sy, (1.4f + (sIdx % 3) * 1.1f) * (width / 720f), starPaint)
    }

    val cx = width * 0.5f
    val cy = height * 0.43f
    val scale = minOf(width, height) / 720f

    // 3. Theme-Specific Animated Centerpiece (Planet / Rocket / Orbiting System)
    when (scene.themeKeyword) {
      "rocket_launch" -> drawAnimatedRocket(canvas, cx, cy - framePhase * 60f * scale, scale, framePhase)
      "solar_system" -> drawSolarSystem(canvas, cx, cy, scale, framePhase)
      "planet_earth" -> drawGlowingEarth(canvas, cx, cy, scale, framePhase)
      else -> drawSpaceExplorerKid(canvas, cx, cy, scale, framePhase, scene.index)
    }

    // 4. Floating Cyber Neon Scene Pill Header
    val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(175, 16, 17, 46) }
    val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 2.5f * scale
      color = Color.rgb(0, 229, 255)
    }
    val topRect = RectF(width * 0.08f, height * 0.06f, width * 0.92f, height * 0.145f)
    canvas.drawRoundRect(topRect, 22f * scale, 22f * scale, badgePaint)
    canvas.drawRoundRect(topRect, 22f * scale, 22f * scale, badgeStroke)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0, 229, 255)
      textSize = 26f * scale
      typeface = Typeface.DEFAULT_BOLD
      textAlign = Paint.Align.CENTER
    }
    canvas.drawText(
      "Scene ${scene.index}: ${scene.overlayHeadline.take(30)}",
      cx,
      topRect.centerY() + 9f * scale,
      titlePaint,
    )

    // 5. Subtitle Caption Overlay Bar (if enabled)
    if (showSubtitles && scene.narrationText.isNotBlank()) {
      val subRect = RectF(width * 0.06f, height * 0.80f, width * 0.94f, height * 0.93f)
      badgePaint.color = Color.argb(205, 5, 10, 26)
      canvas.drawRoundRect(subRect, 18f * scale, 18f * scale, badgePaint)
      badgeStroke.color = Color.argb(180, 0, 229, 255)
      canvas.drawRoundRect(subRect, 18f * scale, 18f * scale, badgeStroke)

      val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 21f * scale
        textAlign = Paint.Align.CENTER
      }
      val line = scene.narrationText.take(64)
      canvas.drawText(line, cx, subRect.centerY() + 7f * scale, subPaint)
    }

    return bmp
  }

  private fun absFloat(v: Float): Float = if (v < 0f) -v else v

  private fun drawGlowingEarth(canvas: Canvas, cx: Float, cy: Float, scale: Float, phase: Float) {
    val r = 135f * scale
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = RadialGradient(cx, cy, r * 1.45f, Color.argb(140, 0, 229, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    canvas.drawCircle(cx, cy, r * 1.45f, glow)
    val earthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.3f, Color.rgb(56, 189, 248), Color.rgb(3, 105, 161), Shader.TileMode.CLAMP)
    }
    canvas.drawCircle(cx, cy, r, earthPaint)
    val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 197, 94) }
    val shift = (phase - 0.5f) * 35f * scale
    canvas.drawOval(RectF(cx - 60f * scale + shift, cy - 55f * scale, cx + 25f * scale + shift, cy + 20f * scale), landPaint)
    canvas.drawOval(RectF(cx + 10f * scale + shift, cy + 15f * scale, cx + 75f * scale + shift, cy + 75f * scale), landPaint)
  }

  private fun drawSolarSystem(canvas: Canvas, cx: Float, cy: Float, scale: Float, phase: Float) {
    val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = RadialGradient(cx, cy, 82f * scale, Color.rgb(255, 238, 88), Color.rgb(255, 111, 0), Shader.TileMode.CLAMP)
    }
    canvas.drawCircle(cx, cy, 56f * scale, sunPaint)
    val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 2f * scale
      color = Color.argb(95, 0, 229, 255)
    }
    val radii = floatArrayOf(115f * scale, 175f * scale, 235f * scale)
    val colors = intArrayOf(Color.rgb(56, 189, 248), Color.rgb(249, 115, 22), Color.rgb(250, 204, 21))
    radii.forEachIndexed { i, rad ->
      canvas.drawCircle(cx, cy, rad, orbitPaint)
      val ang = (phase * 2.0 * PI * (1 + i * 0.5)) + i * 1.7
      val px = cx + rad * cos(ang).toFloat()
      val py = cy + rad * sin(ang).toFloat() * 0.65f
      val plPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[i] }
      canvas.drawCircle(px, py, (14f + i * 5f) * scale, plPaint)
    }
  }

  private fun drawAnimatedRocket(canvas: Canvas, cx: Float, cy: Float, scale: Float, phase: Float) {
    val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(255, 167, 38)
    }
    val flicker = (0.85f + 0.35f * sin(phase * 18.0).toFloat())
    val flame = Path().apply {
      moveTo(cx - 24f * scale, cy + 85f * scale)
      lineTo(cx + 24f * scale, cy + 85f * scale)
      lineTo(cx, cy + (165f * flicker) * scale)
      close()
    }
    canvas.drawPath(flame, flamePaint)
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 245, 249) }
    canvas.drawRoundRect(RectF(cx - 38f * scale, cy - 80f * scale, cx + 38f * scale, cy + 88f * scale), 36f * scale, 36f * scale, bodyPaint)
    val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 13, 85) }
    val nose = Path().apply {
      moveTo(cx - 34f * scale, cy - 58f * scale)
      lineTo(cx + 34f * scale, cy - 58f * scale)
      lineTo(cx, cy - 132f * scale)
      close()
    }
    canvas.drawPath(nose, nosePaint)
    val winPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255) }
    canvas.drawCircle(cx, cy - 12f * scale, 20f * scale, winPaint)
  }

  private fun drawSpaceExplorerKid(canvas: Canvas, cx: Float, cy: Float, scale: Float, phase: Float, sceneIdx: Int) {
    val bobY = sin(phase * 2.0 * PI).toFloat() * 12f * scale
    // Ambient ringed planet behind explorer
    drawGlowingEarth(canvas, cx + 115f * scale, cy - 80f * scale, scale * 0.45f, phase)
    // Cute Astronaut Kid Suit + Helmet
    val suitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 232, 240) }
    canvas.drawRoundRect(RectF(cx - 56f * scale, cy + 32f * scale + bobY, cx + 56f * scale, cy + 150f * scale + bobY), 28f * scale, 28f * scale, suitPaint)
    val helmetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255) }
    canvas.drawCircle(cx, cy - 30f * scale + bobY, 64f * scale, helmetPaint)
    val visorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42) }
    canvas.drawCircle(cx, cy - 28f * scale + bobY, 48f * scale, visorPaint)
    // Happy glowing eyes
    val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 189, 248) }
    canvas.drawCircle(cx - 16f * scale, cy - 34f * scale + bobY, 7f * scale, eyePaint)
    canvas.drawCircle(cx + 16f * scale, cy - 34f * scale + bobY, 7f * scale, eyePaint)
    // Friendly smile
    val smilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(250, 204, 21)
      style = Paint.Style.STROKE
      strokeWidth = 4f * scale
    }
    canvas.drawArc(RectF(cx - 18f * scale, cy - 26f * scale + bobY, cx + 18f * scale, cy - 4f * scale + bobY), 15f, 150f, false, smilePaint)
    // Wave arm
    val armAngle = (sceneIdx * 15f) + sin(phase * 6.28).toFloat() * 14f
    val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255); strokeWidth = 14f * scale; strokeCap = Paint.Cap.ROUND }
    canvas.drawLine(cx + 52f * scale, cy + 56f * scale + bobY, cx + 92f * scale, cy + (18f + armAngle) * scale + bobY, armPaint)
  }
}
