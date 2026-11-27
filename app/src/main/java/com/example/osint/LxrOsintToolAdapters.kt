package com.example.osint

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.example.service.LxrResearchEngine
import java.io.File
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Phase 1: Lawful, honest OSINT Tool Adapters + Privacy/Legality Guardrail.
 * - Blocks unauthorized/illegal surveillance, password cracking, hacking, or private data breaches.
 * - Probes real Termux CLI availability (`maigret`, `sherlock`, `spiderfoot`, `theHarvester`) honestly
 *   without ever simulating/fabricating missing CLI tool results.
 * - Collects live public OSINT artifacts using built-in public HTTP/DNS/Wikipedia/GitHub/Web collectors.
 */
object LxrOsintToolAdapters {
  private val http =
    OkHttpClient.Builder()
      .connectTimeout(7, TimeUnit.SECONDS)
      .readTimeout(12, TimeUnit.SECONDS)
      .writeTimeout(7, TimeUnit.SECONDS)
      .followRedirects(true)
      .build()

  private val DISALLOWED_PATTERNS =
    listOf(
      Regex("\\b(hack|crack|brute\\s*force|password|passcode|pin\\s*crack|phish|keylog|spyware|rat|exploit)\\b", RegexOption.IGNORE_CASE),
      Regex("\\b(nid\\s*database|passport\\s*leak|bank\\s*balance|sim\\s*clone|cdr\\s*record|call\\s*detail\\s*leak)\\b", RegexOption.IGNORE_CASE),
      Regex("\\b(live\\s*gps\\s*track|stalk|intercept\\s*sms|wiretap|private\\s*dm\\s*read)\\b", RegexOption.IGNORE_CASE),
      Regex("(হ্যাক|পাসওয়ার্ড\\s*বের|পিন\\s*ভাঙা|কল\\s*রেকর্ড\\s*হ্যাক|লাইভ\\s*লোকেশন\\s*ট্র্যাক\\s*অন্যের|ব্যক্তিগত\\s*অ্যাকাউন্ট\\s*হ্যাক)"),
    )

  fun evaluateSafetyGuardrail(target: String, objective: String = ""): OsintGuardrailVerdict {
    val combined = "${target.trim()} ${objective.trim()}".trim()
    if (combined.isBlank()) {
      return OsintGuardrailVerdict(
        allowed = false,
        reasonBn = "অনুসন্ধানের জন্য কোনো লক্ষ্যবস্তু (নাম, ডোমেইন বা ইউজারনেম) উল্লেখ করা হয়নি বস।",
      )
    }
    if (DISALLOWED_PATTERNS.any { it.containsMatchIn(combined) }) {
      return OsintGuardrailVerdict(
        allowed = false,
        reasonBn = "নিরাপত্তা ও আইনগত নীতিমালার কারণে পাসওয়ার্ড ভাঙা, অ্যাকাউন্ট হ্যাকিং, গোপন ব্যক্তিগত তথ্য চুরি বা কারো অজান্তে লাইভ ট্র্যাকিং করা যাবে না বস। শুধু উন্মুক্ত পাবলিক সোর্স (OSINT) বিশ্লেষণ করা সম্ভব।",
      )
    }
    return OsintGuardrailVerdict(allowed = true, reasonBn = "আইনসম্মত পাবলিক OSINT অনুমোদিত ✓")
  }

  fun detectTargetType(target: String, hint: String = ""): OsintTargetType {
    val hLow = hint.trim().lowercase()
    when {
      hLow.contains("domain") || hLow.contains("web") -> return OsintTargetType.DOMAIN
      hLow.contains("user") -> return OsintTargetType.USERNAME
      hLow.contains("email") -> return OsintTargetType.EMAIL
      hLow.contains("org") || hLow.contains("company") -> return OsintTargetType.ORGANIZATION
      hLow.contains("person") -> return OsintTargetType.PERSON
      hLow.contains("phone") -> return OsintTargetType.PHONE_PUBLIC
    }
    val clean = target.trim()
    return when {
      clean.contains("@") && clean.contains(".") -> OsintTargetType.EMAIL
      clean.startsWith("http://", true) ||
        clean.startsWith("https://", true) ||
        Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$").matches(clean) -> OsintTargetType.DOMAIN
      clean.startsWith("@") || (!clean.contains(" ") && clean.length in 3..30 && Regex("^[a-zA-Z0-9_.-]+$").matches(clean) && !clean.all { it.isDigit() }) ->
        OsintTargetType.USERNAME
      clean.replace(Regex("[+\\-\\s()]"), "").let { it.length >= 7 && it.all(Char::isDigit) } ->
        OsintTargetType.PHONE_PUBLIC
      else -> OsintTargetType.GENERAL_TOPIC
    }
  }

  fun parseCliToolAvailability(
    toolName: String,
    termuxInstalled: Boolean,
    termuxBinExists: Boolean,
    installHint: String,
  ): OsintToolStatus {
    if (!termuxInstalled) {
      return OsintToolStatus(
        toolName = toolName,
        availability = OsintToolAvailability.TERMUX_NOT_INSTALLED,
        detailBn = "$toolName CLI ব্যবহারের জন্য Termux অ্যাপ ইনস্টল করা নেই — বিল্ট-ইন পাবলিক কালেক্টর ব্যবহৃত হবে।",
        installHint = installHint,
      )
    }
    return if (termuxBinExists) {
      OsintToolStatus(
        toolName = toolName,
        availability = OsintToolAvailability.AVAILABLE_CLI,
        detailBn = "Termux পরিবেশে $toolName পাওয়া গেছে ✓",
        installHint = installHint,
      )
    } else {
      OsintToolStatus(
        toolName = toolName,
        availability = OsintToolAvailability.NOT_INSTALLED_IN_TERMUX,
        detailBn = "Termux-এ $toolName ইনস্টল করা নেই (কোনো ভুয়া CLI ফলাফল বানানো হয়নি)।",
        installHint = installHint,
      )
    }
  }

  fun isTermuxInstalled(ctx: Context): Boolean =
    runCatching {
      ctx.packageManager.getPackageInfo("com.termux", 0)
      true
    }.getOrDefault(false)

  fun inspectExternalOsintTools(ctx: Context): List<OsintToolStatus> {
    val termuxPresent = isTermuxInstalled(ctx)
    val binDirs =
      listOf(
        "/data/data/com.termux/files/usr/bin",
        "/data/data/com.termux/files/home/.local/bin",
      )
    fun hasBin(vararg names: String): Boolean =
      termuxPresent &&
        names.any { n -> binDirs.any { d -> runCatching { File(d, n).exists() }.getOrDefault(false) } }

    return listOf(
      parseCliToolAvailability(
        toolName = "Maigret",
        termuxInstalled = termuxPresent,
        termuxBinExists = hasBin("maigret"),
        installHint = "pkg install python && pip install maigret",
      ),
      parseCliToolAvailability(
        toolName = "Sherlock",
        termuxInstalled = termuxPresent,
        termuxBinExists = hasBin("sherlock"),
        installHint = "pkg install python && pip install sherlock-project",
      ),
      parseCliToolAvailability(
        toolName = "SpiderFoot",
        termuxInstalled = termuxPresent,
        termuxBinExists = hasBin("spiderfoot", "sf.py"),
        installHint = "pkg install python git && pip install spiderfoot",
      ),
      parseCliToolAvailability(
        toolName = "theHarvester",
        termuxInstalled = termuxPresent,
        termuxBinExists = hasBin("theHarvester", "theharvester"),
        installHint = "pkg install python && pip install theHarvester",
      ),
      OsintToolStatus(
        toolName = "Built-in Public Web, Wikipedia, DNS & GitHub Collector",
        availability = OsintToolAvailability.AVAILABLE_BUILTIN,
        detailBn = "সরাসরি বাস্তব পাবলিক ওয়েব, Wikipedia API, Public GitHub/DNS প্রোব সক্রিয় ✓",
      ),
    )
  }

  private fun nowDhakaStamp(): String =
    SimpleDateFormat("yyyy-MM-dd hh:mm a 'BST'", Locale.US)
      .apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }
      .format(Date())

  private fun extractDomain(url: String): String =
    runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")

  private fun extractYearHint(text: String): Int? =
    Regex("\\b(201\\d|202\\d)\\b").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

  fun collectPublicSources(
    ctx: Context,
    target: String,
    targetType: OsintTargetType,
    objective: String = "",
  ): List<OsintSourceRecord> {
    val cleanTarget = target.trim().removePrefix("@")
    val nowStamp = nowDhakaStamp()
    val rawRecords = mutableListOf<OsintSourceRecord>()
    var idx = 1

    fun nextId(): String = "SRC-${(idx++).toString().padStart(2, '0')}"

    // 1. Multi-engine real web research (Gemini Grounding + DuckDuckGo + Wikipedia + optional Perplexity/You)
    val searchQuery =
      when (targetType) {
        OsintTargetType.DOMAIN -> "$cleanTarget official website company overview"
        OsintTargetType.USERNAME -> "\"$cleanTarget\" profile OR github OR social"
        OsintTargetType.EMAIL -> "\"$cleanTarget\" official contact"
        else -> if (objective.isNotBlank()) "$cleanTarget $objective" else cleanTarget
      }
    val webReport = runCatching { LxrResearchEngine.research(ctx, searchQuery) }.getOrNull()
    webReport?.sources?.forEach { item ->
      val dom = extractDomain(item.url)
      val origin = LxrOsintVerificationEngine.classifySourceOrigin(item.url, item.title)
      val tier = LxrOsintVerificationEngine.classifyEvidenceTier(item.url, item.engine, origin)
      val cred = LxrOsintVerificationEngine.computeCredibilityScore(origin, tier, item.url)
      rawRecords.add(
        OsintSourceRecord(
          sourceId = nextId(),
          title = item.title.ifBlank { dom.ifBlank { item.engine } },
          url = item.url,
          domain = dom,
          collector = item.engine,
          snippet = item.snippet.ifBlank { webReport.summaryBn.take(260) },
          origin = origin,
          evidenceTier = tier,
          credibilityScore = cred,
          publishedYearHint = extractYearHint("${item.title} ${item.snippet}"),
          fetchedAtDhaka = nowStamp,
        )
      )
    }

    // 2. Domain DNS + direct HTTP metadata inspection when target is a domain/URL
    if (targetType == OsintTargetType.DOMAIN || targetType == OsintTargetType.EMAIL) {
      val host =
        if (targetType == OsintTargetType.EMAIL) cleanTarget.substringAfter("@").trim()
        else cleanTarget.removePrefix("https://").removePrefix("http://").substringBefore("/").trim()
      if (host.isNotBlank() && host.contains(".")) {
        runCatching {
          val addrs = InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct()
          if (addrs.isNotEmpty()) {
            val url = "https://$host"
            rawRecords.add(
              OsintSourceRecord(
                sourceId = nextId(),
                title = "Public DNS Resolution ($host)",
                url = url,
                domain = host.lowercase(Locale.US),
                collector = "Public DNS",
                snippet = "Resolved public IP address(es): ${addrs.take(4).joinToString(", ")}",
                origin = OsintSourceOrigin.OFFICIAL,
                evidenceTier = OsintEvidenceTier.PRIMARY,
                credibilityScore = 94,
                fetchedAtDhaka = nowStamp,
              )
            )
          }
        }
        // Lightweight HTTP HEAD/GET title probe
        runCatching {
          val url = "https://$host"
          val req =
            Request.Builder()
              .url(url)
              .header("User-Agent", "LXR-AI-OSINT/2.43")
              .build()
          http.newCall(req).execute().use { resp ->
            val html = resp.body?.string().orEmpty().take(24_000)
            val titleMatch = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)
            val pageTitle =
              titleMatch?.groupValues?.getOrNull(1)
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
                .ifBlank { "HTTP ${resp.code} ($host)" }
            rawRecords.add(
              OsintSourceRecord(
                sourceId = nextId(),
                title = pageTitle,
                url = resp.request.url.toString(),
                domain = host.lowercase(Locale.US),
                collector = "HTTP Live Probe",
                snippet = "HTTP Status ${resp.code}; Title: $pageTitle",
                origin = OsintSourceOrigin.OFFICIAL,
                evidenceTier = OsintEvidenceTier.PRIMARY,
                credibilityScore = 92,
                fetchedAtDhaka = nowStamp,
              )
            )
          }
        }
      }
    }

    // 3. Public GitHub user API check when targetType == USERNAME
    if (targetType == OsintTargetType.USERNAME && cleanTarget.matches(Regex("^[A-Za-z0-9-]{2,39}$"))) {
      runCatching {
        val req =
          Request.Builder()
            .url("https://api.github.com/users/${Uri.encode(cleanTarget)}")
            .header("User-Agent", "LXR-AI-OSINT/2.43")
            .build()
        http.newCall(req).execute().use { resp ->
          if (resp.isSuccessful) {
            val j = JSONObject(resp.body?.string().orEmpty())
            val htmlUrl = j.optString("html_url")
            val name = j.optString("name").ifBlank { cleanTarget }
            val bio = j.optString("bio")
            val repos = j.optInt("public_repos", 0)
            val createdAt = j.optString("created_at")
            if (htmlUrl.startsWith("http")) {
              rawRecords.add(
                OsintSourceRecord(
                  sourceId = nextId(),
                  title = "GitHub Public Profile: $name (@$cleanTarget)",
                  url = htmlUrl,
                  domain = "github.com",
                  collector = "GitHub Public API",
                  snippet = "Public repos: $repos | Created: ${createdAt.take(10)} | Bio: ${bio.ifBlank { "N/A" }}",
                  origin = OsintSourceOrigin.OFFICIAL,
                  evidenceTier = OsintEvidenceTier.PRIMARY,
                  credibilityScore = 95,
                  publishedYearHint = extractYearHint(createdAt),
                  fetchedAtDhaka = nowStamp,
                )
              )
            }
          }
        }
      }
    }

    return rawRecords.distinctBy { "${it.collector}|${it.url}|${it.title}" }
  }
}
