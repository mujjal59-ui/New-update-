package com.example.video

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class FetchedStockClip(
  val sourceName: String,
  val mediaUrl: String,
  val localFilePath: String,
  val isVideo: Boolean,
  val attribution: String,
)

/**
 * Fetches publicly available, free-to-use stock video & media footage aligned with OpenMontage's
 * stock_sources/{wikimedia,archive_org,nasa}.py + optional Pexels / Pixabay free keys.
 * Never invents fake media or calls Paid APIs. Fails gracefully when offline.
 */
object FreeStockMediaFetcher {

  private val httpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(3500, TimeUnit.MILLISECONDS)
      .readTimeout(6500, TimeUnit.MILLISECONDS)
      .callTimeout(9000, TimeUnit.MILLISECONDS)
      .build()
  }

  fun stockCacheDir(context: Context): File =
    File(File(context.filesDir, "lxr_video_studio"), "stock_cache").apply { mkdirs() }

  fun mapPromptTopicToEnglishQuery(text: String, themeKeyword: String): String {
    val l = "$text $themeKeyword".lowercase()
    return when {
      l.contains("মঙ্গল") || l.contains("শনির") || l.contains("মহাকাশ") || l.contains("রকেট") ||
        l.contains("space") || l.contains("planet") || l.contains("rocket") -> "nasa rocket nebula space"
      l.contains("সমুদ্র") || l.contains("নদী") || l.contains("ocean") || l.contains("sea") -> "ocean waves aerial nature"
      l.contains("পাহাড়") || l.contains("mountain") -> "mountain landscape clouds"
      l.contains("সৌর") || l.contains("বিদ্যুৎ") || l.contains("solar") || l.contains("tech") ||
        l.contains("প্রযুক্তি") || l.contains("বিজ্ঞান") -> "solar panel technology renewable"
      l.contains("শহর") || l.contains("city") || l.contains("গাড়ি") || l.contains("vehicle") -> "city traffic timelapse"
      l.contains("প্রাণী") || l.contains("পাখি") || l.contains("animal") || l.contains("bird") -> "wildlife birds nature"
      l.contains("প্রকৃতি") || l.contains("গাছ") || l.contains("গ্রাম") || l.contains("forest") || l.contains("village") ->
        "green forest sunrise nature"
      else -> "timelapse clouds earth nature"
    }
  }

  fun fetchClipsForScenes(
    context: Context,
    scenes: List<VideoScenePlan>,
    settings: ProductionSettings,
  ): List<VideoScenePlan> {
    if (scenes.isEmpty()) return scenes
    val cacheDir = stockCacheDir(context)
    val usedUrls = mutableSetOf<String>()

    return scenes.map { sc ->
      if (sc.userMediaPath.isNotBlank()) return@map sc
      val query = mapPromptTopicToEnglishQuery(
        "${sc.title} ${sc.narrationText} ${sc.visualPrompt}",
        sc.themeKeyword,
      )

      val clip =
        tryFetchPexelsIfKey(query, settings.pexelsApiKey, cacheDir, sc.index, usedUrls)
          ?: tryFetchPixabayIfKey(query, settings.pixabayApiKey, cacheDir, sc.index, usedUrls)
          ?: tryFetchArchiveOrgMp4(query, cacheDir, sc.index, usedUrls)
          ?: tryFetchNasaMp4(query, cacheDir, sc.index, usedUrls)
          ?: tryFetchWikimediaMedia(query, cacheDir, sc.index, usedUrls)

      if (clip != null) {
        usedUrls.add(clip.mediaUrl)
        if (clip.isVideo) {
          sc.copy(
            realFootagePath = clip.localFilePath,
            realFootageSourceLabel = "${clip.sourceName}: ${clip.attribution}",
            visualSource = VisualSourceKind.FREE_STOCK_OPEN_MEDIA,
          )
        } else {
          sc.copy(
            userMediaPath = clip.localFilePath,
            userMediaIsVideo = false,
            realFootageSourceLabel = "${clip.sourceName}: ${clip.attribution}",
            visualSource = VisualSourceKind.FREE_STOCK_OPEN_MEDIA,
          )
        }
      } else sc
    }
  }

  private fun tryFetchPexelsIfKey(
    query: String,
    apiKey: String,
    cacheDir: File,
    sceneIndex: Int,
    usedUrls: Set<String>,
  ): FetchedStockClip? {
    val key = apiKey.trim()
    if (key.isBlank()) return null
    return runCatching {
      val enc = URLEncoder.encode(query, "UTF-8")
      val req = Request.Builder()
        .url("https://api.pexels.com/videos/search?query=$enc&per_page=4&orientation=landscape")
        .header("Authorization", key)
        .header("User-Agent", "LXR-AI-VideoStudio/1.0")
        .build()
      httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        val json = JSONObject(resp.body?.string() ?: return@use null)
        val videos = json.optJSONArray("videos") ?: return@use null
        for (i in 0 until videos.length()) {
          val v = videos.optJSONObject(i) ?: continue
          val files = v.optJSONArray("video_files") ?: continue
          for (j in 0 until files.length()) {
            val f = files.optJSONObject(j) ?: continue
            val link = f.optString("link")
            val w = f.optInt("width")
            if (link.startsWith("http") && link !in usedUrls && w in 480..1920) {
              val local = downloadToCache(link, File(cacheDir, "pexels_s${sceneIndex}_$i.mp4")) ?: continue
              return@use FetchedStockClip("Pexels Free Video", link, local.absolutePath, true, "Pexels CC0/License")
            }
          }
        }
        null
      }
    }.getOrNull()
  }

  private fun tryFetchPixabayIfKey(
    query: String,
    apiKey: String,
    cacheDir: File,
    sceneIndex: Int,
    usedUrls: Set<String>,
  ): FetchedStockClip? {
    val key = apiKey.trim()
    if (key.isBlank()) return null
    return runCatching {
      val enc = URLEncoder.encode(query, "UTF-8")
      val req = Request.Builder()
        .url("https://pixabay.com/api/videos/?key=$key&q=$enc&per_page=4")
        .header("User-Agent", "LXR-AI-VideoStudio/1.0")
        .build()
      httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        val json = JSONObject(resp.body?.string() ?: return@use null)
        val hits = json.optJSONArray("hits") ?: return@use null
        for (i in 0 until hits.length()) {
          val h = hits.optJSONObject(i) ?: continue
          val vids = h.optJSONObject("videos") ?: continue
          val url = vids.optJSONObject("medium")?.optString("url")
            ?: vids.optJSONObject("small")?.optString("url")
            ?: continue
          if (url.startsWith("http") && url !in usedUrls) {
            val local = downloadToCache(url, File(cacheDir, "pixabay_s${sceneIndex}_$i.mp4")) ?: continue
            return@use FetchedStockClip("Pixabay Free Video", url, local.absolutePath, true, "Pixabay Content License")
          }
        }
        null
      }
    }.getOrNull()
  }

  private fun tryFetchArchiveOrgMp4(
    query: String,
    cacheDir: File,
    sceneIndex: Int,
    usedUrls: Set<String>,
  ): FetchedStockClip? {
    return runCatching {
      val qEnc = URLEncoder.encode("$query AND mediatype:(movies)", "UTF-8")
      val searchUrl =
        "https://archive.org/advancedsearch.php?q=$qEnc&fl[]=identifier&fl[]=title&rows=4&output=json"
      val req = Request.Builder().url(searchUrl).header("User-Agent", "LXR-AI-VideoStudio/1.0").build()
      val idAndTitle = httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        val root = JSONObject(resp.body?.string() ?: return@use null)
        val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: return@use null
        val pickIdx = (sceneIndex - 1) % docs.length().coerceAtLeast(1)
        val doc = docs.optJSONObject(pickIdx) ?: return@use null
        val identifier = doc.optString("identifier").takeIf { it.isNotBlank() } ?: return@use null
        val title = doc.optString("title", identifier)
        identifier to title
      } ?: return null

      val metaReq = Request.Builder()
        .url("https://archive.org/metadata/${idAndTitle.first}/files")
        .header("User-Agent", "LXR-AI-VideoStudio/1.0")
        .build()
      val mp4Name = httpClient.newCall(metaReq).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        val root = JSONObject(resp.body?.string() ?: return@use null)
        val files = root.optJSONArray("result") ?: return@use null
        for (i in 0 until files.length()) {
          val f = files.optJSONObject(i) ?: continue
          val name = f.optString("name")
          val size = f.optString("size").toLongOrNull() ?: 0L
          if (name.endsWith(".mp4", ignoreCase = true) && size in 50_000L..12_000_000L) {
            return@use name
          }
        }
        null
      } ?: return null

      val downloadUrl = "https://archive.org/download/${idAndTitle.first}/${URLEncoder.encode(mp4Name, "UTF-8").replace("+", "%20")}"
      if (downloadUrl in usedUrls) return null
      val local = downloadToCache(downloadUrl, File(cacheDir, "ia_s${sceneIndex}_${idAndTitle.first.take(12)}.mp4"))
        ?: return null
      FetchedStockClip("Internet Archive", downloadUrl, local.absolutePath, true, idAndTitle.second.take(28))
    }.getOrNull()
  }

  private fun tryFetchNasaMp4(
    query: String,
    cacheDir: File,
    sceneIndex: Int,
    usedUrls: Set<String>,
  ): FetchedStockClip? {
    return runCatching {
      val enc = URLEncoder.encode(query, "UTF-8")
      val req = Request.Builder()
        .url("https://images-api.nasa.gov/search?q=$enc&media_type=video&page=1")
        .header("User-Agent", "LXR-AI-VideoStudio/1.0")
        .build()
      val itemInfo = httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        val items = JSONObject(resp.body?.string() ?: return@use null)
          .optJSONObject("collection")?.optJSONArray("items") ?: return@use null
        val pick = items.optJSONObject((sceneIndex - 1) % items.length().coerceAtLeast(1)) ?: return@use null
        val href = pick.optString("href").takeIf { it.startsWith("http") } ?: return@use null
        val title = pick.optJSONArray("data")?.optJSONObject(0)?.optString("title", "NASA Footage") ?: "NASA Footage"
        href to title
      } ?: return null

      val mp4Url = httpClient.newCall(Request.Builder().url(itemInfo.first).build()).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        val arr = JSONArray(resp.body?.string() ?: return@use null)
        (0 until arr.length()).map { arr.optString(it) }
          .firstOrNull { it.endsWith("~small.mp4") || it.endsWith("~medium.mp4") || it.endsWith(".mp4") }
      } ?: return null
      if (mp4Url in usedUrls) return null
      val local = downloadToCache(mp4Url, File(cacheDir, "nasa_s$sceneIndex.mp4")) ?: return null
      FetchedStockClip("NASA Public Domain", mp4Url, local.absolutePath, true, itemInfo.second.take(28))
    }.getOrNull()
  }

  private fun tryFetchWikimediaMedia(
    query: String,
    cacheDir: File,
    sceneIndex: Int,
    usedUrls: Set<String>,
  ): FetchedStockClip? {
    return runCatching {
      val enc = URLEncoder.encode("$query filetype:bitmap", "UTF-8")
      val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=$enc&gsrnamespace=6&gsrlimit=4&prop=imageinfo&iiprop=url|mime&iiurlwidth=960&format=json"
      val req = Request.Builder().url(url).header("User-Agent", "LXR-AI-VideoStudio/1.0").build()
      httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        val pages = JSONObject(resp.body?.string() ?: return@use null)
          .optJSONObject("query")?.optJSONObject("pages") ?: return@use null
        val keys = pages.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          val page = pages.optJSONObject(k) ?: continue
          val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
          val thumbUrl = info.optString("thumburl").ifBlank { info.optString("url") }
          val mime = info.optString("mime")
          if (thumbUrl.startsWith("http") && thumbUrl !in usedUrls && (mime.contains("jpeg") || mime.contains("png"))) {
            val local = downloadToCache(thumbUrl, File(cacheDir, "wiki_s${sceneIndex}_$k.jpg")) ?: continue
            return@use FetchedStockClip("Wikimedia Commons", thumbUrl, local.absolutePath, false, page.optString("title", "CC").take(26))
          }
        }
        null
      }
    }.getOrNull()
  }

  private fun downloadToCache(url: String, target: File): File? {
    if (target.exists() && target.length() > 16_384L) return target
    return runCatching {
      val req = Request.Builder().url(url).header("User-Agent", "LXR-AI-VideoStudio/1.0").build()
      httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        val body = resp.body ?: return@use null
        val maxBytes = 10L * 1024L * 1024L
        var written = 0L
        body.byteStream().use { inp ->
          FileOutputStream(target).use { out ->
            val buf = ByteArray(16 * 1024)
            while (true) {
              val read = inp.read(buf)
              if (read <= 0) break
              written += read
              if (written > maxBytes) break
              out.write(buf, 0, read)
            }
          }
        }
        if (target.length() > 8_192L) target else {
          target.delete()
          null
        }
      }
    }.getOrNull()
  }
}
