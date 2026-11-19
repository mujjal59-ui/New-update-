package com.example.video

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class StoryActivityTemplate(
  val id: String,
  val titleBn: String,
  val promptBn: String,
  val format: AnimationFormat,
  val environment: EnvironmentSetting,
  val motion: MotionChoreography,
  val emotion: CharacterEmotion,
)

/**
 * Persistent Drama, Movie & Episodic Series Story Bible Manager:
 * - Saves Series Projects, Story Bible, Character Library references, Scene History & Episode History (EP 01, 02, 03+)
 * - Builds continuity-preserving prompts for sequential episodes
 * - Exposes School/Daily Life/Dance/Music story templates & honest Free-First limitations
 */
object SeriesStoryBibleManager {
  private const val FILE_NAME = "lxr_series_story_bible.json"

  val STORY_AND_ACTIVITY_TEMPLATES: List<StoryActivityTemplate> = listOf(
    StoryActivityTemplate(
      id = "tpl_school_class",
      titleBn = "স্কুলের ক্লাসরুম ও পড়াশোনা",
      promptBn = "শিশুরা হাসিমুখে স্কুলে যাচ্ছে, ক্লাসরুমে ফারহানা ম্যাম পড়াচ্ছেন, মায়া ও রাফি হাত তুলে প্রশ্ন করছে ও উত্তর দিচ্ছে।",
      format = AnimationFormat.EDUCATIONAL,
      environment = EnvironmentSetting.CLASSROOM,
      motion = MotionChoreography.CLASSROOM_TEACHING,
      emotion = CharacterEmotion.HAPPY,
    ),
    StoryActivityTemplate(
      id = "tpl_group_dance_song",
      titleBn = "বাংলা ও ইংরেজি দলীয় নাচ-গান",
      promptBn = "রঙিন স্টেজে ৩-৪ জন বন্ধু একসাথে ছন্দময় দলীয় নাচ করছে, গানের তালে ঠোঁট মিলিয়ে বাংলা ও ইংরেজিতে গাইছে।",
      format = AnimationFormat.DANCE_VIDEO,
      environment = EnvironmentSetting.CONCERT_STAGE,
      motion = MotionChoreography.GROUP_DANCE,
      emotion = CharacterEmotion.EXCITED_JOY,
    ),
    StoryActivityTemplate(
      id = "tpl_village_family",
      titleBn = "পারিবারিক ও গ্রামের জীবনের নাটক",
      promptBn = "গ্রামের সবুজ পথে বাবা করিম, মা আয়েশা ও কৃষক জামাল চাচা কথা বলছেন; পরিবার ও প্রতিবেশীরা মিলেমিশে আনন্দে দিন কাটাচ্ছে।",
      format = AnimationFormat.ANIMATED_DRAMA,
      environment = EnvironmentSetting.VILLAGE_PATH,
      motion = MotionChoreography.WALKING,
      emotion = CharacterEmotion.SMILING,
    ),
    StoryActivityTemplate(
      id = "tpl_city_mystery",
      titleBn = "শহরের রহস্য ও পুলিশ-ডাক্তার অ্যাডভেঞ্চার",
      promptBn = "শহরের রাস্তায় অফিসার তারিক ও ডা. নাদিয়া একসাথে শহরের মানুষকে সাহায্য করছে এবং রহস্য সমাধান করছে।",
      format = AnimationFormat.CARTOON_SERIAL,
      environment = EnvironmentSetting.CITY_STREET,
      motion = MotionChoreography.RUNNING,
      emotion = CharacterEmotion.SURPRISED,
    ),
    StoryActivityTemplate(
      id = "tpl_park_play",
      titleBn = "পার্ক, পিকনিক ও বন্ধুদের খেলাধুলা",
      promptBn = "সুন্দর পার্কে শিশুরা ও ফ্যান্টাসি বন্ধু পিকু একসাথে লাফাচ্ছে, হাত মেলাচ্ছে ও মজার মজার কথা বলছে।",
      format = AnimationFormat.COMEDY,
      environment = EnvironmentSetting.PARK_GARDEN,
      motion = MotionChoreography.PLAYING,
      emotion = CharacterEmotion.HAPPY,
    ),
  )

  fun loadSeriesProjects(context: Context): List<CartoonSeriesProject> {
    val file = File(context.filesDir, FILE_NAME)
    if (!file.exists()) {
      val initial = defaultSeriesSeed()
      saveSeriesProjects(context, initial)
      return initial
    }
    return runCatching {
      val arr = JSONArray(file.readText(Charsets.UTF_8))
      val list = buildList {
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val charArr = o.optJSONArray("characterIds") ?: JSONArray()
          val charIds = (0 until charArr.length()).mapNotNull { charArr.optString(it).takeIf { s -> s.isNotBlank() } }
          val epArr = o.optJSONArray("episodes") ?: JSONArray()
          val episodes = buildList {
            for (j in 0 until epArr.length()) {
              val eo = epArr.optJSONObject(j) ?: continue
              val scArr = eo.optJSONArray("sceneTitles") ?: JSONArray()
              val scTitles = (0 until scArr.length()).map { scArr.optString(it) }
              add(
                EpisodeRecord(
                  episodeNumber = eo.optInt("episodeNumber", j + 1),
                  title = eo.optString("title", "Episode ${j + 1}"),
                  summaryBn = eo.optString("summaryBn", ""),
                  endingHookBn = eo.optString("endingHookBn", ""),
                  sceneTitles = scTitles,
                  mp4Path = eo.optString("mp4Path", ""),
                  createdAtMs = eo.optLong("createdAtMs", System.currentTimeMillis()),
                )
              )
            }
          }
          add(
            CartoonSeriesProject(
              seriesId = o.optString("seriesId", "series_$i"),
              seriesTitle = o.optString("seriesTitle", "নতুন কার্টুন সিরিজ"),
              genreBn = o.optString("genreBn", "পারিবারিক ও শিক্ষামূলক"),
              storyBibleSummary = o.optString("storyBibleSummary", ""),
              characterIds = charIds,
              episodes = episodes,
              lastCliffhangerBn = o.optString("lastCliffhangerBn", ""),
              updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
            )
          )
        }
      }
      if (list.isEmpty()) defaultSeriesSeed() else list
    }.getOrElse { defaultSeriesSeed() }
  }

  fun saveSeriesProjects(context: Context, list: List<CartoonSeriesProject>) {
    runCatching {
      val arr = JSONArray()
      list.forEach { sp ->
        val charArr = JSONArray().apply { sp.characterIds.forEach { put(it) } }
        val epArr = JSONArray().apply {
          sp.episodes.forEach { ep ->
            val scArr = JSONArray().apply { ep.sceneTitles.forEach { put(it) } }
            put(
              JSONObject()
                .put("episodeNumber", ep.episodeNumber)
                .put("title", ep.title)
                .put("summaryBn", ep.summaryBn)
                .put("endingHookBn", ep.endingHookBn)
                .put("sceneTitles", scArr)
                .put("mp4Path", ep.mp4Path)
                .put("createdAtMs", ep.createdAtMs)
            )
          }
        }
        arr.put(
          JSONObject()
            .put("seriesId", sp.seriesId)
            .put("seriesTitle", sp.seriesTitle)
            .put("genreBn", sp.genreBn)
            .put("storyBibleSummary", sp.storyBibleSummary)
            .put("characterIds", charArr)
            .put("episodes", epArr)
            .put("lastCliffhangerBn", sp.lastCliffhangerBn)
            .put("updatedAtMs", sp.updatedAtMs)
        )
      }
      File(context.filesDir, FILE_NAME).writeText(arr.toString(2), Charsets.UTF_8)
    }
  }

  private fun defaultSeriesSeed(): List<CartoonSeriesProject> = listOf(
    CartoonSeriesProject(
      seriesId = "series_sonar_bangla_school",
      seriesTitle = "মায়া ও রাফির অদ্ভুত স্কুল ডায়েরি",
      genreBn = "স্কুল, বন্ধুত্ব, রহস্য ও নৈতিক শিক্ষা",
      storyBibleSummary = "মায়া, রাফি, শিক্ষিকা ফারহানা ও ফ্যান্টাসি বন্ধু পিকু প্রতি পর্বে নতুন বিজ্ঞান ও জীবনের রহস্য সমাধান করে।",
      characterIds = listOf("lib_maya_girl", "lib_rafi_boy", "lib_teacher_farhana", "lib_fantasy_piku"),
      episodes = emptyList(),
      lastCliffhangerBn = "প্রথম পর্বে স্কুল বাগানে একটি রহস্যময় আলোকিত নোটবুক খুঁজে পাওয়া যাবে।",
    )
  )

  fun buildEpisodeContinuityPrompt(
    series: CartoonSeriesProject,
    cast: List<CharacterProfile>,
    userIdea: String,
  ): String {
    val epNum = series.nextEpisodeNumber
    val epTag = "Episode %02d".format(epNum)
    val castDesc = if (cast.isNotEmpty()) {
      cast.joinToString(", ") { "${it.name} (${it.roleBn}, কণ্ঠ: ${it.recommendedVoiceId})" }
    } else "মায়া ও রাফি"
    val prevEp = series.episodes.lastOrNull()
    val continuity = if (prevEp != null) {
      "আগের পর্ব (Episode %02d: ${prevEp.title}) এর ঘটনা: ${prevEp.summaryBn} | পরবর্তী সূত্র: ${prevEp.endingHookBn}".format(prevEp.episodeNumber)
    } else {
      "গল্পের শুরু: ${series.storyBibleSummary}"
    }
    return "$epTag — ${series.seriesTitle}। কাস্ট: $castDesc। ধারাবাহিকতা: $continuity। নতুন পর্বের ঘটনা: ${userIdea.trim()}"
  }
}
