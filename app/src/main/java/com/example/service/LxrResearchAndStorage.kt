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
    "mkdir -p ~/storage/downloads/LxR/{Apps,APK,Videos,YouTube_SEO,Images,Documents,Logs,Backups}"
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
    val report = research(context, "$cleanTopic youtube tutorial guide tips", preferredEngine = "auto")
    val apiKey = Store.apiKey(context)

    if (apiKey.isNotBlank()) {
      val aiPkg = requestAiSeoStructuredJson(apiKey, cleanTopic, languageLabel, audienceGoal, report.sources, checkedAt)
      if (aiPkg != null) return aiPkg
    }

    // Deterministic, zero-fabrication SEO builder when Gemini API key is absent or offline
    val slugTag = cleanTopic.replace(Regex("[^\\p{L}\\p{N}]"), "").take(22).ifBlank { "LxrAi" }
    val titles =
      listOf(
        "$cleanTopic — সম্পূর্ণ গাইড ও সহজ সমাধান",
        "$cleanTopic কেন জরুরি? জানুন বিস্তারিত টিপস",
        "$cleanTopic করার সঠিক নিয়ম (ধাপে ধাপে)",
        "$cleanTopic: নতুনদের জন্য সেরা প্র্যাকটিক্যাল টিউটোরিয়াল",
      )
    val keywords =
      listOf(
        cleanTopic,
        "$cleanTopic bangla",
        "$cleanTopic tutorial",
        "how to $cleanTopic",
        "bangla tech guide",
      )
    val hashtags = listOf("#$slugTag", "#YouTubeSEO", "#BanglaTutorial", "#LxRAI")
    val hook = "প্রথম ৫ সেকেন্ডে দর্শককে মূল সমস্যা ও ফলাফল দেখান: \"আজকের ভিডিওতে আমি সরাসরি $cleanTopic সহজভাবে করে দেখাবো।\""
    val desc =
      buildString {
        appendLine("এই ভিডিওতে $cleanTopic নিয়ে ধাপে ধাপে সহজ আলোচনা ও প্র্যাকটিক্যাল ডেমো দেখানো হয়েছে।")
        appendLine("লক্ষ্য: $audienceGoal")
        appendLine()
        appendLine("ভিডিও চ্যাপ্টার (সম্ভাব্য সময়):")
        appendLine("00:00 পরিচিতি ও মূল লক্ষ্য")
        appendLine("00:35 ধাপ-১: সেটআপ ও প্রস্তুতি")
        appendLine("02:10 ধাপ-২: মূল কাজ ও ডেমো")
        appendLine("04:30 সাধারণ ভুল ও সমাধান")
        appendLine()
        appendLine(hashtags.joinToString(" "))
      }.trim()

    return YoutubeSeoPackage(
      topic = cleanTopic,
      languageLabel = languageLabel,
      titles = titles,
      keywords = keywords,
      hashtags = hashtags,
      description = desc,
      scriptHookBn = hook,
      thumbnailTextIdea = "${cleanTopic.take(22)} | সহজ গাইড",
      checklist =
        listOf(
          "শিরোনামের প্রথম ৫০ অক্ষরে মূল কিওয়ার্ড রাখা হয়েছে",
          "ট্যাগ ও হ্যাশট্যাগ আলাদাভাবে সাজানো হয়েছে",
          "প্রথম ৩০ সেকেন্ডের হুক যুক্ত করা হয়েছে",
          "থাম্বনেইল টেক্সট ৩-৫ শব্দে সংক্ষিপ্ত রাখা হয়েছে",
        ),
      sources = report.sources,
      providerLabel = if (report.sources.isNotEmpty()) "Structured SEO + Web Research" else "Offline Structured SEO Builder",
      checkedAtDhaka = checkedAt,
    )
  }

  private fun requestAiSeoStructuredJson(
    apiKey: String,
    topic: String,
    languageLabel: String,
    audienceGoal: String,
    sources: List<ResearchSourceItem>,
    checkedAt: String,
  ): YoutubeSeoPackage? {
    val prompt =
      """
      Create a practical, honest YouTube Video SEO package for:
      Topic: $topic
      Language: $languageLabel
      Audience Goal: $audienceGoal
      Return ONLY valid JSON with these exact keys:
      {
        "titles": ["4 clickable, honest SEO titles without fake promises"],
        "keywords": ["6 relevant search keywords"],
        "hashtags": ["5 hashtags starting with #"],
        "thumbnailTextIdea": "3 to 5 punchy words for thumbnail",
        "scriptHookBn": "2 sentence engaging opening hook in Bengali",
        "description": "Well-structured YouTube description with intro and chapter placeholders",
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
