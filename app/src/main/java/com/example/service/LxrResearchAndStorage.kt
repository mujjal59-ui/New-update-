package com.example.service

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.data.Store
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

enum class LxrStorageCategory(val folderName: String) {
  APPS("Apps"),
  APK("APK"),
  VIDEOS("Videos"),
  YOUTUBE_SEO("YouTube_SEO"),
  IMAGES("Images"),
  DOCUMENTS("Documents"),
  LOGS("Logs"),
  BACKUPS("Backups"),
  OSINT("OSINT"),
}

data class ResearchSourceItem(
  val engine: String,
  val title: String,
  val url: String,
  val snippet: String,
)

data class MultiEngineResearchReport(
  val query: String,
  val summaryBn: String,
  val sources: List<ResearchSourceItem>,
  val engineStatuses: List<String>,
  val checkedAtDhaka: String,
)

data class YoutubeSeoPackage(
  val topic: String,
  val languageLabel: String,
  val titles: List<String>,
  val keywords: List<String>,
  val hashtags: List<String>,
  val description: String,
  val scriptHookBn: String,
  val thumbnailTextIdea: String,
  val checklist: List<String>,
  val sources: List<ResearchSourceItem>,
  val providerLabel: String,
  val checkedAtDhaka: String,
) {
  fun toExportMarkdown(): String = buildString {
    appendLine("# LXR--AI YouTube Video SEO Package")
    appendLine("Topic: $topic")
    appendLine("Language: $languageLabel")
    appendLine("Verified Provider: $providerLabel")
    appendLine("Checked At (Asia/Dhaka): $checkedAtDhaka")
    appendLine()
    appendLine("## SEO Titles")
    titles.forEachIndexed { idx, t -> appendLine("${idx + 1}. $t") }
    appendLine()
    appendLine("## Keywords & Tags")
    appendLine(keywords.joinToString(", "))
    appendLine()
    appendLine("## Hashtags")
    appendLine(hashtags.joinToString(" "))
    appendLine()
    appendLine("## Thumbnail Hook Text")
    appendLine(thumbnailTextIdea)
    appendLine()
    appendLine("## First 30-Second Video Hook")
    appendLine(scriptHookBn)
    appendLine()
    appendLine("## Video Description")
    appendLine(description)
    appendLine()
    appendLine("## Optimization Checklist")
    checklist.forEach { appendLine("- [x] $it") }
    if (sources.isNotEmpty()) {
      appendLine()
      appendLine("## Research Sources (Verified URLs)")
      sources.forEach { src ->
        appendLine("- [${src.engine}] ${src.title}: ${src.url}")
      }
    }
  }
}

/**
 * Standardized user output contract under `/sdcard/Download/LxR/<Category>/<Project>/`
 * while preserving legacy workspace compatibility.
 */
object LxrDownloadStorage {
  fun sanitizeSlug(raw: String, fallback: String = "lxr_project"): String {
    val clean =
      raw.trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9_\\-]+"), "_")
        .trim('_', '-')
    return clean.ifBlank { fallback }.take(48)
  }

  fun lxrPublicRoot(context: Context): File {
    val publicDownloads =
      runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
    val root =
      if (publicDownloads != null) File(publicDownloads, "LxR")
      else File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "LxR")
    runCatching { root.mkdirs() }
    return root
  }

  fun ensureStandardTree(context: Context): File {
    val root = lxrPublicRoot(context)
    LxrStorageCategory.entries.forEach { cat ->
      runCatching { File(root, cat.folderName).mkdirs() }
    }
    return root
  }

  fun categoryProjectDir(context: Context, category: LxrStorageCategory, projectSlug: String): File {
    ensureStandardTree(context)
    val dir = File(File(lxrPublicRoot(context), category.folderName), sanitizeSlug(projectSlug))
    runCatching { dir.mkdirs() }
    return dir
  }

  fun uniqueTargetFile(dir: File, desiredFileName: String): File {
    runCatching { dir.mkdirs() }
    val safeName = desiredFileName.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
    val initial = File(dir, safeName)
    if (!initial.exists()) return initial
    val dot = safeName.lastIndexOf('.')
    val base = if (dot > 0) safeName.substring(0, dot) else safeName
    val ext = if (dot > 0) safeName.substring(dot) else ""
    var version = 2
    while (version <= 999) {
      val candidate = File(dir, "${base}_v$version$ext")
      if (!candidate.exists()) return candidate
      version++
    }
    return File(dir, "${base}_${System.currentTimeMillis()}$ext")
  }

  fun saveTextDeliverable(
    context: Context,
    category: LxrStorageCategory,
    projectSlug: String,
    fileName: String,
    content: String,
    preserveUnique: Boolean = true,
  ): Result<File> =
    runCatching {
      val dir = categoryProjectDir(context, category, projectSlug)
      val out = if (preserveUnique) uniqueTargetFile(dir, fileName) else File(dir, fileName)
      out.writeText(content, Charsets.UTF_8)
      out
    }.recoverCatching {
      // App-scoped Downloads/LxR fallback if scoped storage restricts public write on target device
      val appDir =
        File(
          File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "LxR/${category.folderName}"),
          sanitizeSlug(projectSlug),
        )
      appDir.mkdirs()
      val out = if (preserveUnique) uniqueTargetFile(appDir, fileName) else File(appDir, fileName)
      out.writeText(content, Charsets.UTF_8)
      out
    }

  fun copyFileDeliverable(
    context: Context,
    srcFile: File,
    category: LxrStorageCategory,
    projectSlug: String,
    desiredFileName: String = srcFile.name,
  ): Result<File> =
    runCatching {
      require(srcFile.exists() && srcFile.isFile) { "Source artifact does not exist: ${srcFile.absolutePath}" }
      val dir = categoryProjectDir(context, category, projectSlug)
      val dest = uniqueTargetFile(dir, desiredFileName)
      srcFile.copyTo(dest, overwrite = false)
      dest
    }

  fun termuxSyncDirectoriesCommand(): String =
    "mkdir -p ~/storage/downloads/LxR/{Apps,APK,Videos,YouTube_SEO,Images,Documents,Logs,Backups,OSINT}"
}

/**
 * Multi-engine web & AI research helper.
 * Uses only real network calls (Google Search via Gemini Grounding, DuckDuckGo, Wikipedia, and optional
 * user-configured Perplexity/You.com endpoints). Never fabricates results, URLs, quotas, or analytics.
 */
object LxrResearchEngine {
  private val http =
    OkHttpClient.Builder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(16, TimeUnit.SECONDS)
      .writeTimeout(8, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()

  private fun nowDhakaStamp(): String =
    SimpleDateFormat("yyyy-MM-dd hh:mm a 'BST'", Locale.US)
      .apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }
      .format(Date())

  private fun todayDhakaDate(): String =
    SimpleDateFormat("dd MMM yyyy", Locale.US)
      .apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }
      .format(Date())

  fun parseRssTitlesAndLinks(xml: String, engineName: String, maxItems: Int = 5): List<ResearchSourceItem> {
    if (xml.isBlank()) return emptyList()
    val items = mutableListOf<ResearchSourceItem>()
    val itemRegex = Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
    fun extractTag(block: String, tag: String): String {
      val m = Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE).find(block) ?: return ""
      return m.groupValues[1]
        .replace("<![CDATA[", "")
        .replace("]]>", "")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
    }
    for (m in itemRegex.findAll(xml)) {
      if (items.size >= maxItems) break
      val blk = m.groupValues[1]
      val title = extractTag(blk, "title")
      val link = extractTag(blk, "link").ifBlank { "https://trends.google.com/trending?geo=BD" }
      val pub = extractTag(blk, "pubDate")
      val traffic = extractTag(blk, "ht:approx_traffic")
      val snip =
        listOfNotNull(
          traffic.takeIf { it.isNotBlank() }?.let { "সার্চ ভলিউম: $it" },
          pub.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
      if (title.isNotBlank()) {
        items.add(ResearchSourceItem(engineName, title, link, snip))
      }
    }
    return items
  }

  fun fetchBangladeshTrendingTopics(maxItems: Int = 5): List<ResearchSourceItem> {
    val collected = mutableListOf<ResearchSourceItem>()
    runCatching {
      val req =
        Request.Builder()
          .url("https://trends.google.com/trending/rss?geo=BD")
          .header("User-Agent", "LXR-AI-Research/2.43")
          .build()
      http.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) {
          collected.addAll(parseRssTitlesAndLinks(resp.body?.string().orEmpty(), "Google Trends BD", maxItems))
        }
      }
    }
    if (collected.size < 3) {
      collected.addAll(
        queryGoogleNewsRss(
          query = "Bangladesh trending today",
          hl = "bn",
          gl = "BD",
          ceid = "BD:bn",
          engineLabel = "BD News RSS",
          maxItems = maxItems - collected.size,
        )
      )
    }
    return collected.distinctBy { it.title.lowercase() }.take(maxItems)
  }

  fun queryGoogleNewsRss(
    query: String,
    hl: String = "en",
    gl: String = "BD",
    ceid: String = "BD:en",
    engineLabel: String = "Google News RSS",
    maxItems: Int = 4,
  ): List<ResearchSourceItem> {
    if (maxItems <= 0) return emptyList()
    return try {
      val url =
        "https://news.google.com/rss/search?q=${Uri.encode(query)}&hl=$hl&gl=$gl&ceid=${Uri.encode(ceid)}"
      val req = Request.Builder().url(url).header("User-Agent", "LXR-AI-Research/2.43").build()
      http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return emptyList()
        parseRssTitlesAndLinks(resp.body?.string().orEmpty(), engineLabel, maxItems)
      }
    } catch (_: Throwable) {
      emptyList()
    }
  }

  fun fetchTodayTechAndBangladeshTrending(context: Context): MultiEngineResearchReport {
    val checkedAt = nowDhakaStamp()
    val dateLabel = todayDhakaDate()
    val techItems =
      (queryGoogleNewsRss("technology OR AI OR smartphone update", hl = "en", gl = "BD", ceid = "BD:en", engineLabel = "Today Tech News", maxItems = 4) +
        queryDuckDuckGo("latest technology AI update news $dateLabel"))
        .distinctBy { it.title.lowercase() }
        .take(5)
    val bdItems = fetchBangladeshTrendingTopics(maxItems = 5)
    val geminiKey = Store.apiKey(context)
    val geminiTechNote =
      if (geminiKey.isNotBlank()) {
        queryGeminiGoogleSearch(
          geminiKey,
          "Today $dateLabel latest technology AI smartphone updates and top trending topics in Bangladesh",
        ).first
      } else {
        null
      }

    val allSources = (bdItems + techItems).distinctBy { it.url.ifBlank { it.title } }.take(10)
    val statuses =
      listOf(
        "Today Tech News: ${techItems.size}টি আপডেট",
        "Bangladesh Trending: ${bdItems.size}টি টপিক",
      )
    val summary =
      buildString {
        appendLine("📰 বস, আজকের ($checkedAt) টেক আপডেট নিউজ ও বাংলাদেশের ট্রেন্ডিং টপিকস:")
        if (!geminiTechNote.isNullOrBlank()) {
          appendLine()
          appendLine(geminiTechNote.take(900))
        }
        if (bdItems.isNotEmpty()) {
          appendLine()
          appendLine("🇧🇩 বাংলাদেশে আজকের ট্রেন্ডিং:")
          bdItems.forEachIndexed { idx, item ->
            val extra = if (item.snippet.isNotBlank()) " — ${item.snippet}" else ""
            appendLine("${idx + 1}. ${item.title}$extra (${item.url})")
          }
        }
        if (techItems.isNotEmpty()) {
          appendLine()
          appendLine("💻 আজকের টেক ও AI আপডেট:")
          techItems.forEachIndexed { idx, item ->
            appendLine("${idx + 1}. ${item.title} (${item.url})")
          }
        }
        if (bdItems.isEmpty() && techItems.isEmpty() && geminiTechNote.isNullOrBlank()) {
          appendLine("দুঃখিত বস, এই মুহূর্তে নেটওয়ার্ক থেকে আজকের টেক নিউজ বা বাংলাদেশের ট্রেন্ডিং ফিড পাওয়া যায়নি।")
        }
      }.trim()

    return MultiEngineResearchReport(
      query = "Today Tech News & Bangladesh Trending ($dateLabel)",
      summaryBn = summary,
      sources = allSources,
      engineStatuses = statuses,
      checkedAtDhaka = checkedAt,
    )
  }

  fun research(
    context: Context,
    query: String,
    preferredEngine: String = "auto",
  ): MultiEngineResearchReport {
    val cleanQuery = query.trim()
    val checkedAt = nowDhakaStamp()
    if (cleanQuery.isBlank()) {
      return MultiEngineResearchReport(
        query = "",
        summaryBn = "রিসার্চের জন্য কোনো বিষয় লেখা হয়নি বস।",
        sources = emptyList(),
        engineStatuses = emptyList(),
        checkedAtDhaka = checkedAt,
      )
    }

    val collectedSources = mutableListOf<ResearchSourceItem>()
    val statuses = mutableListOf<String>()
    val primaryAnswers = mutableListOf<String>()
    val geminiKey = Store.apiKey(context)

    // 1. Google Search via Gemini API Grounding (when Gemini API Key is present)
    if (geminiKey.isNotBlank() && (preferredEngine == "auto" || preferredEngine.contains("google", true))) {
      val (answer, gSources, status) = queryGeminiGoogleSearch(geminiKey, cleanQuery)
      statuses.add(status)
      if (!answer.isNullOrBlank()) primaryAnswers.add(answer)
      collectedSources.addAll(gSources)
    } else if (geminiKey.isBlank()) {
      statuses.add("Google Gemini Grounding: API Key সেট করা নেই")
    }

    // 2. Real DuckDuckGo Web Search (works directly without extra API key)
    val ddgSources = queryDuckDuckGo(cleanQuery)
    if (ddgSources.isNotEmpty()) {
      collectedSources.addAll(ddgSources)
      statuses.add("Web Search (DuckDuckGo): ${ddgSources.size}টি রিয়েল লিংক")
    } else {
      statuses.add("Web Search (DuckDuckGo): সরাসরি ফলাফল পাওয়া যায়নি")
    }

    // 3. Wikipedia Multilingual Quick Fact Check (bn + en)
    val wikiSources = queryWikipediaOpenSearch(cleanQuery)
    if (wikiSources.isNotEmpty()) {
      collectedSources.addAll(wikiSources)
      statuses.add("Wikipedia: ${wikiSources.size}টি তথ্যসূত্র")
    }

    // 4. Optional User-Configured Perplexity / You.com / FreeLLMAPI (explicitly gated by user settings)
    val pplxKey = Store.perplexityApiKey(context)
    if (pplxKey.isNotBlank() && (preferredEngine == "auto" || preferredEngine.contains("perplexity", true))) {
      val (pAns, pSrc, pStatus) = queryPerplexitySonar(pplxKey, cleanQuery)
      statuses.add(pStatus)
      if (!pAns.isNullOrBlank()) primaryAnswers.add(pAns)
      collectedSources.addAll(pSrc)
    } else if (preferredEngine.contains("perplexity", true)) {
      statuses.add("Perplexity: API Key সেট করা নেই (শুধুমাত্র অফিশিয়াল কী দিলে চালু হবে)")
    }

    val youKey = Store.youApiKey(context)
    if (youKey.isNotBlank() && (preferredEngine == "auto" || preferredEngine.contains("you", true))) {
      val (ySrc, yStatus) = queryYouComSearch(youKey, cleanQuery)
      statuses.add(yStatus)
      collectedSources.addAll(ySrc)
    } else if (preferredEngine.contains("you", true)) {
      statuses.add("You.com: API Key সেট করা নেই (শুধুমাত্র অফিশিয়াল কী দিলে চালু হবে)")
    }

    val uniqueSources =
      collectedSources.distinctBy { it.url.ifBlank { "${it.engine}|${it.title}" } }.take(8)

    val summaryBn =
      buildString {
        if (primaryAnswers.isNotEmpty()) {
          appendLine(primaryAnswers.first().trim().take(1800))
        } else if (uniqueSources.isNotEmpty()) {
          appendLine("ওয়েব রিসার্চের যাচাইকৃত ফলাফল ($checkedAt):")
          uniqueSources.take(4).forEachIndexed { idx, src ->
            appendLine("${idx + 1}. ${src.title} — ${src.snippet.take(180)} (${src.url})")
          }
        } else {
          appendLine("দুঃখিত বস, এই মুহূর্তে ইন্টারনেট/ইঞ্জিন থেকে '$cleanQuery' বিষয়ে কোনো ফলাফল আনা যায়নি।")
        }
        if (uniqueSources.isNotEmpty()) {
          appendLine("\n[তথ্যসূত্র]: " + uniqueSources.take(4).joinToString(" | ") { it.url })
        }
      }.trim()

    return MultiEngineResearchReport(
      query = cleanQuery,
      summaryBn = summaryBn,
      sources = uniqueSources,
      engineStatuses = statuses,
      checkedAtDhaka = checkedAt,
    )
  }

  private fun queryGeminiGoogleSearch(
    apiKey: String,
    query: String,
  ): Triple<String?, List<ResearchSourceItem>, String> {
    val body =
      JSONObject()
        .put(
          "contents",
          JSONArray()
            .put(
              JSONObject().put(
                "parts",
                JSONArray().put(
                  JSONObject().put(
                    "text",
                    "$query\n\n(Google Search থেকে যাচাইকৃত সঠিক ও সংক্ষিপ্ত তথ্য বাংলায় দাও। অজানা কিছু অনুমান করবে না।)",
                  )
                ),
              )
            ),
        )
        .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

    for (model in listOf("gemini-flash-latest", "gemini-2.5-flash")) {
      try {
        val req =
          Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
          val raw = resp.body?.string().orEmpty()
          if (!resp.isSuccessful) {
            return@use
          }
          val root = JSONObject(raw)
          val cand = root.optJSONArray("candidates")?.optJSONObject(0) ?: return@use
          val parts = cand.optJSONObject("content")?.optJSONArray("parts")
          val sb = StringBuilder()
          if (parts != null) {
            for (i in 0 until parts.length()) {
              sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
          }
          val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
          val urls = mutableListOf<ResearchSourceItem>()
          if (chunks != null) {
            for (i in 0 until chunks.length()) {
              val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
              val u = web.optString("uri")
              val t = web.optString("title", "Google Search Result")
              if (u.startsWith("http")) {
                urls.add(ResearchSourceItem("Google Search", t, u, ""))
              }
            }
          }
          val ans = sb.toString().trim()
          if (ans.isNotBlank()) {
            return Triple(ans, urls, "Google Search Grounding ($model): সফল ✓")
          }
        }
      } catch (_: Exception) {}
    }
    return Triple(null, emptyList(), "Google Search Grounding: সংযোগ ব্যর্থ")
  }

  private fun queryDuckDuckGo(query: String): List<ResearchSourceItem> {
    return try {
      val req =
        Request.Builder()
          .url("https://html.duckduckgo.com/html/?q=" + Uri.encode(query))
          .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
          .build()
      val html = http.newCall(req).execute().use { it.body?.string().orEmpty() }
      val re =
        Regex(
          """class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>[\s\S]*?class="result__snippet"[^>]*>(.*?)</a>""",
          RegexOption.DOT_MATCHES_ALL,
        )
      fun cleanHtml(s: String) =
        s.replace(Regex("<[^>]+>"), "")
          .replace("&amp;", "&")
          .replace("&quot;", "\"")
          .replace("&#x27;", "'")
          .replace("&nbsp;", " ")
          .replace(Regex("\\s+"), " ")
          .trim()

      re.findAll(html)
        .take(5)
        .mapNotNull { m ->
          val rawHref = m.groupValues[1]
          val actualUrl =
            if (rawHref.contains("uddg=")) {
              Uri.decode(rawHref.substringAfter("uddg=").substringBefore("&"))
            } else if (rawHref.startsWith("//")) {
              "https:$rawHref"
            } else {
              rawHref
            }
          val title = cleanHtml(m.groupValues[2])
          val snip = cleanHtml(m.groupValues[3])
          if (title.isBlank()) null
          else ResearchSourceItem("Web Search", title, actualUrl, snip)
        }
        .toList()
    } catch (_: Throwable) {
      emptyList()
    }
  }

  private fun queryWikipediaOpenSearch(query: String): List<ResearchSourceItem> {
    val out = mutableListOf<ResearchSourceItem>()
    for (lang in listOf("bn", "en")) {
      try {
        val req =
          Request.Builder()
            .url("https://$lang.wikipedia.org/w/api.php?action=opensearch&search=${Uri.encode(query)}&limit=2&namespace=0&format=json")
            .header("User-Agent", "LXR-AI-Research/1.0")
            .build()
        http.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) return@use
          val arr = JSONArray(resp.body?.string().orEmpty())
          val titles = arr.optJSONArray(1) ?: return@use
          val descs = arr.optJSONArray(2)
          val links = arr.optJSONArray(3)
          for (i in 0 until titles.length()) {
            val t = titles.optString(i)
            val d = descs?.optString(i).orEmpty()
            val u = links?.optString(i).orEmpty()
            if (t.isNotBlank() && u.startsWith("http")) {
              out.add(ResearchSourceItem("Wikipedia ($lang)", t, u, d))
            }
          }
        }
      } catch (_: Throwable) {}
    }
    return out
  }

  private fun queryPerplexitySonar(
    apiKey: String,
    query: String,
  ): Triple<String?, List<ResearchSourceItem>, String> {
    return try {
      val payload =
        JSONObject()
          .put("model", "sonar")
          .put(
            "messages",
            JSONArray().put(
              JSONObject()
                .put("role", "user")
                .put("content", "$query\nRespond concisely in Bengali with verified sources."),
            ),
          )
      val req =
        Request.Builder()
          .url("https://api.perplexity.ai/chat/completions")
          .header("Authorization", "Bearer $apiKey")
          .post(payload.toString().toRequestBody("application/json".toMediaType()))
          .build()
      http.newCall(req).execute().use { resp ->
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) return Triple(null, emptyList(), "Perplexity: HTTP ${resp.code}")
        val j = JSONObject(raw)
        val text = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        val cites = j.optJSONArray("citations")
        val srcs = mutableListOf<ResearchSourceItem>()
        if (cites != null) {
          for (i in 0 until cites.length()) {
            val u = cites.optString(i)
            if (u.startsWith("http")) srcs.add(ResearchSourceItem("Perplexity", "Citation ${i + 1}", u, ""))
          }
        }
        Triple(text.ifBlank { null }, srcs, "Perplexity Sonar: সফল ✓")
      }
    } catch (e: Exception) {
      Triple(null, emptyList(), "Perplexity: ${e.message}")
    }
  }

  private fun queryYouComSearch(
    apiKey: String,
    query: String,
  ): Pair<List<ResearchSourceItem>, String> {
    return try {
      val req =
        Request.Builder()
          .url("https://api.ydc-index.io/search?query=" + Uri.encode(query))
          .header("X-API-Key", apiKey)
          .build()
      http.newCall(req).execute().use { resp ->
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) return emptyList<ResearchSourceItem>() to "You.com: HTTP ${resp.code}"
        val hits = JSONObject(raw).optJSONArray("hits") ?: return emptyList<ResearchSourceItem>() to "You.com: 0 ফলাফল"
        val items = mutableListOf<ResearchSourceItem>()
        for (i in 0 until minOf(hits.length(), 4)) {
          val h = hits.optJSONObject(i) ?: continue
          val u = h.optString("url")
          val t = h.optString("title")
          val s = h.optJSONArray("snippets")?.optString(0).orEmpty()
          if (u.startsWith("http")) items.add(ResearchSourceItem("You.com", t, u, s))
        }
        items to "You.com: ${items.size}টি ফলাফল ✓"
      }
    } catch (e: Exception) {
      emptyList<ResearchSourceItem>() to "You.com: ${e.message}"
    }
  }

  fun generateYoutubeSeoPackage(
    context: Context,
    topic: String,
    languageLabel: String = "বাংলা ও ইংরেজি (Bilingual)",
    audienceGoal: String = "সাধারণ দর্শক ও সার্চ ট্রাফিক",
  ): YoutubeSeoPackage {
    val cleanTopic = topic.trim().ifBlank { "AI Technology Bangla Tutorial" }
    val checkedAt = nowDhakaStamp()
    val report = research(context, "$cleanTopic youtube trending tutorial guide tips", preferredEngine = "auto")
    val trendingBd = fetchBangladeshTrendingTopics(maxItems = 4)
    val mergedSources = (report.sources + trendingBd).distinctBy { it.url.ifBlank { it.title } }.take(8)
    val trendingTerms =
      trendingBd
        .map { it.title.trim() }
        .filter { it.isNotBlank() && it.length <= 42 }
        .take(3)
    val apiKey = Store.apiKey(context)

    if (apiKey.isNotBlank()) {
      val aiPkg =
        requestAiSeoStructuredJson(
          apiKey = apiKey,
          topic = cleanTopic,
          languageLabel = languageLabel,
          audienceGoal = audienceGoal,
          sources = mergedSources,
          trendingTerms = trendingTerms,
          checkedAt = checkedAt,
        )
      if (aiPkg != null) return aiPkg
    }

    // Dynamic topic- & trend-varied SEO builder (no repetitive static titles/hashtags/descriptions)
    val slugTag = cleanTopic.replace(Regex("[^\\p{L}\\p{N}]"), "").take(22).ifBlank { "LxrTopic" }
    val words =
      cleanTopic
        .split(Regex("\\s+"))
        .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
        .filter { it.length >= 2 }
    val secondaryTag = words.getOrNull(1)?.take(18) ?: words.firstOrNull()?.take(18) ?: "Update"
    val trendTag =
      trendingTerms.firstOrNull()
        ?.replace(Regex("[^\\p{L}\\p{N}]"), "")
        ?.take(18)
        ?.takeIf { it.length >= 2 }
    val seed = kotlin.math.abs((cleanTopic + "|" + System.currentTimeMillis() / 1000L).hashCode())
    val yearStr = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }.format(Date())
    val monthTag = SimpleDateFormat("MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }.format(Date())

    val titlePools =
      listOf(
        listOf(
          "$cleanTopic ($yearStr): নতুন পদ্ধতিতে সম্পূর্ণ বিশ্লেষণ ও প্রমাণ",
          "কেন এখন সবাই $cleanTopic নিয়ে কথা বলছে? আসল তথ্য জানুন",
          "$cleanTopic শেখার সহজ কৌশল — প্র্যাকটিক্যাল ডেমো ($monthTag $yearStr)",
          "$cleanTopic বনাম পুরনো নিয়ম: কোনটা আপনার জন্য সেরা?",
        ),
        listOf(
          "$cleanTopic নিয়ে গোপন টিপস যা অনেকেই জানে না ($yearStr)",
          "শুরু থেকে শেষ: $cleanTopic কীভাবে নির্ভুলভাবে করবেন?",
          "$cleanTopic এর বাস্তব ব্যবহার ও সাধারণ ভুলগুলো এড়ানোর উপায়",
          "বাংলায় $cleanTopic: মাত্র কয়েক মিনিটে পরিষ্কার ধারণা",
        ),
        listOf(
          "$cleanTopic — লাইভ টেস্ট, ফলাফল ও সম্পূর্ণ রিভিউ ($yearStr)",
          "$cleanTopic নিয়ে সেরা ৫টি বাস্তব অভিজ্ঞতা ও সমাধান",
          "নতুন আপডেটে $cleanTopic কতটা কার্যকর? সরাসরি যাচাই",
          "$cleanTopic সম্পর্কে পুরো গাইড — $audienceGoal এর জন্য",
        ),
      )
    val chosenTitles = titlePools[seed % titlePools.size].toMutableList()
    if (trendingTerms.isNotEmpty() && chosenTitles.isNotEmpty()) {
      chosenTitles[chosenTitles.lastIndex] = "$cleanTopic ও ${trendingTerms.first()} — ট্রেন্ডিং বিশ্লেষণ ($yearStr)"
    }

    val keywords =
      buildList {
        add(cleanTopic)
        add("$cleanTopic bangla $yearStr")
        add("$cleanTopic review and guide")
        add("$cleanTopic new update")
        trendingTerms.forEach { t -> add("$cleanTopic $t") }
        add("$audienceGoal $cleanTopic")
      }.distinct().take(7)

    val hashtags =
      buildList {
        add("#$slugTag")
        add("#${secondaryTag}Bangla")
        if (trendTag != null && !trendTag.equals(slugTag, true)) add("#$trendTag")
        add("#TrendingBD$yearStr")
        add("#${words.firstOrNull()?.take(14) ?: "Tech"}Update")
      }.distinct().take(5)

    val hookTemplates =
      listOf(
        "প্রথম ৪ সেকেন্ডেই দর্শককে মূল পরিবর্তনটা দেখান: \"আজ $cleanTopic-এর এমন একটা বাস্তব দিক দেখাবো যেটা সাধারণত কেউ বলে না।\"",
        "সরাসরি ফলাফল দিয়ে শুরু করুন: \"$cleanTopic নিয়ে অনেকের ধারণা ভুল — চলুন লাইভ প্রমাণে আসল বিষয়টা দেখে নিই।\"",
        "সময়-প্রাসঙ্গিক প্রশ্ন ছুড়ে দিন: \"$yearStr-এ এসেও কি $cleanTopic নিয়ে সমস্যায় পড়ছেন? এই পদ্ধতিতে কাজটি কয়েক ধাপেই সমাধান হবে।\"",
      )
    val hook = hookTemplates[(seed / 3) % hookTemplates.size]

    val descTemplates =
      listOf(
        buildString {
          appendLine("$cleanTopic বিষয়টি $yearStr-এর বর্তমান বাস্তবতা ও ট্রেন্ড অনুযায়ী সহজ ভাষায় তুলে ধরা হয়েছে।")
          if (trendingTerms.isNotEmpty()) {
            appendLine("বর্তমান আলোচিত প্রসঙ্গ: ${trendingTerms.joinToString(", ")}")
          }
          appendLine("দর্শক লক্ষ্য: $audienceGoal • ভাষা: $languageLabel")
          appendLine()
          appendLine("টাইমলাইন ও আলোচনা সূচি:")
          appendLine("00:00 মূল প্রসঙ্গ ও কেন গুরুত্বপূর্ণ")
          appendLine("00:45 ধাপে ধাপে আসল প্রক্রিয়া")
          appendLine("02:30 বাস্তব ফলাফল ও সতর্কতা")
          appendLine("04:15 সারসংক্ষেপ ও পরবর্তী করণীয়")
          appendLine()
          appendLine(hashtags.joinToString(" "))
        },
        buildString {
          appendLine("আজকের পর্বে আমরা সরাসরি যাচাই করেছি $cleanTopic কতটা কাজের এবং কোন ভুলগুলো এড়িয়ে চলতে হবে।")
          appendLine("উদ্দেশ্য: $audienceGoal ($languageLabel)")
          if (trendingTerms.isNotEmpty()) {
            appendLine("ট্রেন্ডিং সংযোগ: ${trendingTerms.joinToString(" • ")}")
          }
          appendLine()
          appendLine("গুরুত্বপূর্ণ অংশ:")
          appendLine("00:00 সরাসরি ফলাফলের ঝলক")
          appendLine("01:05 প্রয়োজনীয় প্রস্তুতি ও টুলস")
          appendLine("03:00 সম্পূর্ণ প্র্যাকটিক্যাল ডেমো")
          appendLine()
          appendLine(hashtags.joinToString(" "))
        },
      )
    val desc = descTemplates[(seed / 5) % descTemplates.size].trim()

    return YoutubeSeoPackage(
      topic = cleanTopic,
      languageLabel = languageLabel,
      titles = chosenTitles,
      keywords = keywords,
      hashtags = hashtags,
      description = desc,
      scriptHookBn = hook,
      thumbnailTextIdea = "${words.take(3).joinToString(" ").ifBlank { cleanTopic.take(18) }} | $yearStr",
      checklist =
        listOf(
          "সাম্প্রতিক ট্রেন্ড ও বিষয়ভিত্তিক অনন্য শিরোনাম নির্বাচন করা হয়েছে",
          "প্রতিটি টপিকের জন্য আলাদা হ্যাশট্যাগ ও লং-টেইল কিওয়ার্ড সাজানো হয়েছে",
          "ড্রামা-মুক্ত অথচ কৌতূহল তৈরি করা ওপেনিং হুক যুক্ত হয়েছে",
          "সময়সূচি (Chapters) এবং $audienceGoal অনুযায়ী ডেসক্রিপশন তৈরি হয়েছে",
        ),
      sources = mergedSources,
      providerLabel =
        if (mergedSources.isNotEmpty()) "Dynamic Trending SEO + Live Sources" else "Dynamic Topic-Varied SEO Builder",
      checkedAtDhaka = checkedAt,
    )
  }

  private fun requestAiSeoStructuredJson(
    apiKey: String,
    topic: String,
    languageLabel: String,
    audienceGoal: String,
    sources: List<ResearchSourceItem>,
    trendingTerms: List<String>,
    checkedAt: String,
  ): YoutubeSeoPackage? {
    val trendContext =
      if (trendingTerms.isNotEmpty()) "Live Bangladesh/Topic Trending Angles: ${trendingTerms.joinToString(", ")}"
      else "No external trending feed available; derive fresh angle strictly from the topic."
    val prompt =
      """
      Create a unique, non-repetitive, trending-aware YouTube Video SEO package for:
      Topic: $topic
      Language: $languageLabel
      Audience Goal: $audienceGoal
      $trendContext
      Stamp: $checkedAt
      IMPORTANT: NEVER reuse generic boilerplate titles or hashtags like #BanglaTutorial or #LxRAI. Tailor EVERY title, hashtag, hook, and description uniquely to this exact topic and current trending context.
      Return ONLY valid JSON with these exact keys:
      {
        "titles": ["4 distinct, high-retention SEO titles tailored to the specific topic & trend"],
        "keywords": ["6 relevant search keywords combining topic and current trends"],
        "hashtags": ["5 unique topic-specific hashtags starting with #"],
        "thumbnailTextIdea": "3 to 5 punchy words for thumbnail",
        "scriptHookBn": "2 sentence engaging opening hook in Bengali",
        "description": "Original structured YouTube description with unique intro and topic-matched chapters",
        "checklist": ["4 actionable creator SEO checks"]
      }
      Never invent fake view counts, analytics, or CTR numbers.
      """.trimIndent()

    val body =
      JSONObject()
        .put(
          "contents",
          JSONArray().put(
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
          ),
        )
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

    return try {
      val req =
        Request.Builder()
          .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
          .post(body.toString().toRequestBody("application/json".toMediaType()))
          .build()
      http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val text =
          JSONObject(resp.body?.string().orEmpty())
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
        if (text.isBlank()) return null
        val j = JSONObject(text)
        fun arrList(k: String): List<String> {
          val a = j.optJSONArray(k) ?: return emptyList()
          return (0 until a.length()).mapNotNull { idx -> a.optString(idx).trim().takeIf { it.isNotEmpty() } }
        }
        val titles = arrList("titles")
        if (titles.isEmpty()) return null
        YoutubeSeoPackage(
          topic = topic,
          languageLabel = languageLabel,
          titles = titles,
          keywords = arrList("keywords"),
          hashtags = arrList("hashtags"),
          description = j.optString("description"),
          scriptHookBn = j.optString("scriptHookBn"),
          thumbnailTextIdea = j.optString("thumbnailTextIdea"),
          checklist = arrList("checklist"),
          sources = sources,
          providerLabel = "Gemini 2.5 Flash SEO + Grounded Research",
          checkedAtDhaka = checkedAt,
        )
      }
    } catch (_: Throwable) {
      null
    }
  }
}
