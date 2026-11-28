package com.example.service

import android.content.Context
import com.example.osint.LxrOsintOrchestrator
import java.util.Locale
import kotlin.math.pow

data class AgentExecutionOutput(
  val agentId: String,
  val agentNameBn: String,
  val succeeded: Boolean,
  val summaryBn: String,
  val findings: List<String> = emptyList(),
  val files: List<String> = emptyList(),
  val latencyMs: Long = 0L,
)

interface LxrSpecialistAgent {
  val id: String
  val displayNameBn: String
  val keywords: List<String>
  fun execute(ctx: Context, taskPrompt: String, sharedContext: String = ""): AgentExecutionOutput
}

class IntentAndPlannerAgent : LxrSpecialistAgent {
  override val id = "intent_planner"
  override val displayNameBn = "ইন্টেন্ট ও টাস্ক প্ল্যানার এজেন্ট"
  override val keywords = listOf("plan", "steps", "roadmap", "workflow", "পরিকল্পনা", "ধাপ", "কিভাবে")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val lower = taskPrompt.lowercase(Locale.ROOT)
    val domains = mutableListOf<String>()
    if (lower.containsAny("math", "calculate", "সমীকরণ", "হিসাব", "যোগ", "বিয়োগ", "গুণ", "ভাগ") || Regex("""\d+\s*[+\-*/^%]\s*\d+""").containsMatchIn(lower)) {
      domains.add("গণিত/রিজনিং")
    }
    if (lower.containsAny("security", "vulnerability", "secret", "leak", "সিকিউরিটি", "হ্যাক", "পাসওয়ার্ড", "অডিট")) {
      domains.add("কোড সিকিউরিটি")
    }
    if (lower.containsAny("code", "kotlin", "python", "app", "bug", "কোড", "অ্যাপ", "ফাংশন")) {
      domains.add("ডেভেলপমেন্ট/কোডিং")
    }
    if (lower.containsAny("research", "news", "trend", "verify", "তথ্য", "রিসার্চ", "খবর")) {
      domains.add("গভীর রিসার্চ ও যাচাই")
    }
    if (lower.containsAny("osint", "domain", "investigate", "তদন্ত", "ডোমেইন")) {
      domains.add("OSINT তদন্ত")
    }
    if (lower.containsAny("youtube", "seo", "title", "hashtag", "ট্যাগ", "ভিডিও")) {
      domains.add("ক্রিয়েটিভ SEO")
    }
    if (lower.containsAny("device", "battery", "hardware", "wifi", "bluetooth", "gps", "ফোন", "ব্যাটারি")) {
      domains.add("ডিভাইস ও হার্ডওয়্যার")
    }
    if (domains.isEmpty()) domains.add("সাধারণ বুদ্ধিমত্তা ও বিশ্লেষণ")
    val summary =
      "বসের অনুরোধ বিশ্লেষণ করে সনাক্তকৃত ক্ষেত্র: ${domains.joinToString(", ")}।"
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = summary,
      findings = domains,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class ReasoningAndMathAgent : LxrSpecialistAgent {
  override val id = "reasoning_math"
  override val displayNameBn = "রিজনিং ও ডিটারমিনিস্টিক ম্যাথ এজেন্ট"
  override val keywords = listOf("math", "calculate", "solve", "logic", "হিসাব", "গণিত", "অংক", "যোগ", "গুণ")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val extractedExpr = extractMathCandidate(taskPrompt)
    if (extractedExpr != null) {
      val evalResult = evaluateExpression(extractedExpr)
      if (evalResult.isSuccess) {
        val num = evalResult.getOrThrow()
        val formatted =
          if (num % 1.0 == 0.0 && kotlin.math.abs(num) < 1e15) num.toLong().toString()
          else String.format(Locale.US, "%.6f", num).trimEnd('0').trimEnd('.')
        return AgentExecutionOutput(
          agentId = id,
          agentNameBn = displayNameBn,
          succeeded = true,
          summaryBn = "গাণিতিক হিসাব ($extractedExpr) = $formatted",
          findings = listOf("Expression=$extractedExpr", "ExactResult=$formatted"),
          latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
        )
      }
    }
    val gw = LxrProviderGateway.executeCriticalReasoning(ctx, taskPrompt, sharedContext)
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = gw.succeeded,
      summaryBn = gw.formattedBn,
      findings = listOfNotNull(gw.failureSummaryBn),
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }

  companion object {
    private val EXPR_REGEX = Regex("""[-+*/%^()0-9.\s]{3,}""")

    fun extractMathCandidate(input: String): String? {
      val matches = EXPR_REGEX.findAll(input).map { it.value.trim() }.filter { s ->
        s.any { it.isDigit() } && s.any { it in charArrayOf('+', '-', '*', '/', '%', '^') }
      }.toList()
      return matches.maxByOrNull { it.length }
    }

    fun evaluateExpression(expr: String): Result<Double> {
      val clean = expr.replace(" ", "")
      if (clean.isBlank()) return Result.failure(IllegalArgumentException("Empty math expression"))
      return runCatching {
        val parser =
          object {
            var pos = -1
            var ch = 0

            fun nextChar() {
              pos += 1
              ch = if (pos < clean.length) clean[pos].code else -1
            }

            fun eat(charToEat: Int): Boolean {
              while (ch == ' '.code) nextChar()
              if (ch == charToEat) {
                nextChar()
                return true
              }
              return false
            }

            fun parse(): Double {
              nextChar()
              val x = parseExpression()
              if (pos < clean.length) throw IllegalArgumentException("Unexpected character: ${clean[pos]}")
              return x
            }

            fun parseExpression(): Double {
              var x = parseTerm()
              while (true) {
                when {
                  eat('+'.code) -> x += parseTerm()
                  eat('-'.code) -> x -= parseTerm()
                  else -> return x
                }
              }
            }

            fun parseTerm(): Double {
              var x = parsePower()
              while (true) {
                when {
                  eat('*'.code) -> x *= parsePower()
                  eat('/'.code) -> {
                    val d = parsePower()
                    if (d == 0.0) throw ArithmeticException("Division by zero")
                    x /= d
                  }
                  eat('%'.code) -> {
                    val d = parsePower()
                    if (d == 0.0) throw ArithmeticException("Modulo by zero")
                    x %= d
                  }
                  else -> return x
                }
              }
            }

            fun parsePower(): Double {
              val base = parseFactor()
              return if (eat('^'.code)) base.pow(parsePower()) else base
            }

            fun parseFactor(): Double {
              if (eat('+'.code)) return parseFactor()
              if (eat('-'.code)) return -parseFactor()
              val x: Double
              val startPos = pos
              if (eat('('.code)) {
                x = parseExpression()
                if (!eat(')'.code)) throw IllegalArgumentException("Missing closing parenthesis")
              } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = clean.substring(startPos, pos).toDouble()
              } else {
                throw IllegalArgumentException("Unexpected token at index $pos")
              }
              return x
            }
          }
        parser.parse()
      }
    }
  }
}

class CodingAndDeveloperAgent : LxrSpecialistAgent {
  override val id = "coding_developer"
  override val displayNameBn = "কোডিং ও ডেভেলপার এজেন্ট"
  override val keywords = listOf("code", "kotlin", "java", "python", "compile", "refactor", "bug", "কোড", "বাগ", "অ্যাপ")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val gw =
      LxrProviderGateway.executeCriticalReasoning(
        ctx = ctx,
        taskPrompt = "সফটওয়্যার ইঞ্জিনিয়ারিং ও ক্লিন কোড গাইডলাইন মেনে সমাধান দাও:\n$taskPrompt",
        screenContext = sharedContext,
      )
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = gw.succeeded,
      summaryBn = gw.formattedBn,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class CodeSecurityAuditorAgent : LxrSpecialistAgent {
  override val id = "code_security"
  override val displayNameBn = "কোড সিকিউরিটি অডিটর এজেন্ট"
  override val keywords = listOf("security", "audit", "secret", "token", "tls", "ssl", "sql", "vulnerability", "সিকিউরিটি", "নিরাপত্তা")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val findings = auditSnippet(taskPrompt + "\n" + sharedContext)
    val summary =
      if (findings.isEmpty()) {
        "স্ট্যাটিক সিকিউরিটি অডিটে প্রদত্ত কোড/টেক্সটে কোনো হার্ডকোডেড সিক্রেট, দুর্বল TLS বা স্পষ্ট ঝুঁকি পাওয়া যায়নি ✓"
      } else {
        "সিকিউরিটি সতর্কতা (${findings.size}টি):\n" + findings.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n")
      }
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = summary,
      findings = findings,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }

  companion object {
    fun auditSnippet(source: String): List<String> {
      if (source.isBlank()) return emptyList()
      val issues = mutableListOf<String>()
      if (Regex("AIza[A-Za-z0-9_\\-]{10,}|github_pat_[A-Za-z0-9_]{12,}|ghp_[A-Za-z0-9]{12,}|sk-[A-Za-z0-9_\\-]{10,}").containsMatchIn(source)) {
        issues.add("হার্ডকোডেড API Key বা Token শনাক্ত হয়েছে — সিক্রেটস/স্টোর থেকে রিড করো।")
      }
      if (Regex("TrustAllCerts|ALLOW_ALL_HOSTNAME_VERIFIER|X509TrustManager[\\s\\S]{0,120}checkServerTrusted\\s*\\([^)]*\\)\\s*\\{\\s*\\}").containsMatchIn(source)) {
        issues.add("SSL/TLS সার্টিফিকেট ভ্যালিডেশন বাইপাস নিষিদ্ধ — ডিফল্ট সিস্টেম TrustManager ব্যবহার করো।")
      }
      if (Regex("http://(?!localhost|127\\.0\\.0\\.1|10\\.0\\.2\\.2)").containsMatchIn(source)) {
        issues.add("অনিরাপদ cleartext HTTP URL পাওয়া গেছে — HTTPS ব্যবহার করা উচিত।")
      }
      if (Regex("rawQuery\\s*\\(\\s*\"[^\"]*\"\\s*\\+|SELECT\\s+[^\\n]*\\$\\{").containsMatchIn(source)) {
        issues.add("সম্ভাব্য SQL Injection প্যাটার্ন — প্যারামিটারাইজড কোয়েরি (`?` বা Room DAO) ব্যবহার করো।")
      }
      if (Regex("eval\\s*\\(|Runtime\\.getRuntime\\(\\)\\.exec\\(").containsMatchIn(source)) {
        issues.add("ডায়নামিক কোড/কমান্ড এক্সিকিউশন (`eval`/`Runtime.exec`) — ইনপুট স্যানিটাইজেশন যাচাই করো।")
      }
      return issues
    }
  }
}

class DeepResearchVerificationAgent : LxrSpecialistAgent {
  override val id = "deep_research"
  override val displayNameBn = "ডিপ রিসার্চ ও ফ্যাক্ট ভেরিফিকেশন এজেন্ট"
  override val keywords = listOf("research", "search", "web", "fact", "news", "trend", "রিসার্চ", "তথ্য", "খবর")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val report = LxrResearchEngine.research(ctx, taskPrompt, "auto")
    val verified = LxrResearchVerificationHelper.evaluateSources(report.sources)
    val summary =
      buildString {
        appendLine(report.summaryBn)
        if (verified.tierSummaryBn.isNotBlank()) {
          appendLine()
          appendLine("🔎 সোর্স বিশ্বাসযোগ্যতা: ${verified.tierSummaryBn}")
        }
        if (verified.contradictionAlertsBn.isNotEmpty()) {
          appendLine("⚠️ সম্ভাব্য তথ্যগত অমিল:")
          verified.contradictionAlertsBn.forEach { appendLine("• $it") }
        }
      }.trim()
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = report.sources.isNotEmpty() || !report.summaryBn.contains("পাওয়া যায়নি"),
      summaryBn = summary,
      findings = report.engineStatuses,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class OsintInvestigationAgent : LxrSpecialistAgent {
  override val id = "osint_investigator"
  override val displayNameBn = "পাবলিক OSINT তদন্ত এজেন্ট"
  override val keywords = listOf("osint", "domain", "whois", "username", "investigate", "তদন্ত", "ডোমেইন")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val res = LxrOsintOrchestrator.runInvestigation(ctx, taskPrompt, objective = sharedContext)
    val files =
      res.deliverables?.let { d ->
        listOf(
          d.researchSummaryHtml,
          d.verifiedClaimsJson,
          d.sourceRegistryJson,
          d.evidenceComparisonHtml,
          d.finalReportHtml,
        ).filter { it.isNotBlank() }
      }.orEmpty()
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = !res.blockedByGuardrail && files.isNotEmpty(),
      summaryBn = res.conciseVoiceSummaryBn,
      files = files,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class DeviceAndHardwareAgent : LxrSpecialistAgent {
  override val id = "device_hardware"
  override val displayNameBn = "ডিভাইস ও হার্ডওয়্যার ডায়াগনস্টিক এজেন্ট"
  override val keywords = listOf("hardware", "battery", "storage", "ram", "sensor", "alarm", "হার্ডওয়্যার", "ব্যাটারি", "স্টোরেজ")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val hw = LxrDeviceAndHardwareEngine.getHardwareAndSystemDiagnostics(ctx)
    val selfDiag = LxrDeviceAndHardwareEngine.diagnoseSelfHealth(ctx).summaryBn
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = "$hw\n\n$selfDiag",
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class AccessibilityAndUiAgent : LxrSpecialistAgent {
  override val id = "accessibility_ui"
  override val displayNameBn = "অ্যাক্সেসিবিলিটি ও UI অটোমেশন এজেন্ট"
  override val keywords = listOf("screen", "ui", "tap", "open app", "accessibility", "স্ক্রিন", "অ্যাপ", "ট্যাপ")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val acc = RiyaAccessibilityService.instance
    val plan = LxrUniversalAppController.planAction(ctx, taskPrompt, taskPrompt)
    val summary =
      buildString {
        appendLine("Accessibility সার্ভিস: ${if (acc != null) "সক্রিয় ✓" else "বন্ধ"}")
        appendLine("অ্যাপ/রুট পরিকল্পনা: ${plan.resolvedAppLabel} — ${plan.recommendedMethod}")
        if (acc != null) {
          appendLine("বর্তমান স্ক্রিন: ${RiyaAccessibilityService.currentScreenSummary}")
        }
      }.trim()
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = summary,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class CommunicationAndMessagingAgent : LxrSpecialistAgent {
  override val id = "communication_messaging"
  override val displayNameBn = "কমিউনিকেশন ও মেসেজিং এজেন্ট"
  override val keywords = listOf("whatsapp", "imo", "messenger", "call", "contact", "message", "কল", "মেসেজ", "কন্টাক্ট")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val contacts = LxrDeviceAndHardwareEngine.getContactsSummary(ctx, sampleLimit = 5)
    val callLog = LxrDeviceAndHardwareEngine.getCallLogSummary(ctx, filterType = "all", limit = 5, todayOnly = true)
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = "$contacts\n\n$callLog",
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class EducationAndCurriculumAgent : LxrSpecialistAgent {
  override val id = "education_tutor"
  override val displayNameBn = "শিক্ষা ও কারিকুলাম টিউটর এজেন্ট"
  override val keywords = listOf("learn", "study", "tutorial", "explain", "teach", "শেখা", "পড়া", "বুঝিয়ে", "কারিকুলাম")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val gw =
      LxrProviderGateway.executeCriticalReasoning(
        ctx = ctx,
        taskPrompt = "শিক্ষকের মতো সহজ বাংলায় ধাপে ধাপে উদাহরণসহ শেখাও:\n$taskPrompt",
        screenContext = sharedContext,
      )
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = gw.succeeded,
      summaryBn = gw.formattedBn,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class CreativeContentAndSeoAgent : LxrSpecialistAgent {
  override val id = "creative_seo"
  override val displayNameBn = "ক্রিয়েটিভ কনটেন্ট ও YouTube SEO এজেন্ট"
  override val keywords = listOf("youtube", "seo", "title", "hashtag", "thumbnail", "script", "ভিডিও", "হ্যাশট্যাগ")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val pkg = LxrResearchEngine.generateYoutubeSeoPackage(ctx, topic = taskPrompt)
    val summary =
      buildString {
        appendLine("YouTube SEO প্যাকেজ (${pkg.topic}):")
        appendLine("• শীর্ষ টাইটেল: ${pkg.titles.firstOrNull().orEmpty()}")
        appendLine("• হ্যাশট্যাগ: ${pkg.hashtags.joinToString(" ")}")
        appendLine("• হুক: ${pkg.scriptHookBn}")
      }.trim()
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = pkg.titles.isNotEmpty(),
      summaryBn = summary,
      findings = pkg.titles,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class NetworkAndGatewayManagerAgent : LxrSpecialistAgent {
  override val id = "network_gateway"
  override val displayNameBn = "নেটওয়ার্ক, প্রোভাইডার ও Termux গেটওয়ে এজেন্ট"
  override val keywords = listOf("network", "gateway", "provider", "api", "termux", "langgraph", "crewai", "নেটওয়ার্ক", "প্রোভাইডার")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val providers = LxrProviderRegistry.inspectProviders(ctx)
    val probes = LxrTermuxFrameworkAdapters.probeAll(ctx)
    val netStatus = LxrNetworkHealthMonitor.currentStatusBn(ctx)
    val summary =
      buildString {
        appendLine("🌐 নেটওয়ার্ক অবস্থা: $netStatus")
        appendLine("⚡ এআই গেটওয়ে: ${LxrProviderGateway.healthSummaryBn()}")
        appendLine("🔌 প্রোভাইডার (${providers.count { it.isConfigured }}/${providers.size} সক্রিয়):")
        providers.forEach { p -> appendLine("• ${p.displayName}: ${p.detailBn}") }
        appendLine("🛠️ Termux পাইথন ফ্রেমওয়ার্ক প্রোব:")
        probes.forEach { f -> appendLine("• ${f.displayName}: ${f.statusBn}") }
      }.trim()
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = summary,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class MonitoringAndQaAuditAgent : LxrSpecialistAgent {
  override val id = "monitoring_qa"
  override val displayNameBn = "মনিটরিং, QA ও সক্ষমতা অডিট এজেন্ট"
  override val keywords = listOf("monitor", "activity", "capability", "status", "qa", "টাস্ক", "স্ট্যাটাস", "সক্ষমতা")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val cap = LxrCapabilityInspector.buildLiveCapabilityReportBn(ctx)
    val monitor = LxrBackgroundTaskManager.queryActivityHistory(ctx, "")
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = "$monitor\n\n$cap",
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class VerificationCritiqueGateAgent : LxrSpecialistAgent {
  override val id = "verification_critique"
  override val displayNameBn = "ভেরিফিকেশন ও ক্রিটিক গেট এজেন্ট"
  override val keywords = listOf("verify", "check", "critique", "যাচাই", "সত্য")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val redacted = LxrSecretRedactor.redactSecrets(sharedContext)
    val issues = mutableListOf<String>()
    if (redacted.isBlank()) {
      issues.add("কোনো যাচাইযোগ্য আউটপুট পাওয়া যায়নি")
    }
    if (redacted != sharedContext) {
      issues.add("আউটপুটে সংবেদনশীল কী/টোকেন শনাক্ত হয়ে [REDACTED] করা হয়েছে")
    }
    val lower = redacted.lowercase(Locale.ROOT)
    if (lower.contains("todo") || lower.contains("not implemented") || lower.contains("placeholder")) {
      issues.add("অসম্পূর্ণ বা প্লেসহোল্ডার অংশ থাকতে পারে")
    }
    val passed = redacted.isNotBlank()
    val summary =
      if (issues.isEmpty()) "যাচাই গেট পাস: তথ্য পরিষ্কার, সিক্রেটমুক্ত ও কার্যকর ✓"
      else "যাচাই গেট সতর্কতা: ${issues.joinToString("; ")}"
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = passed,
      summaryBn = summary,
      findings = issues,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class DocumentationSummaryAgent : LxrSpecialistAgent {
  override val id = "documentation_summary"
  override val displayNameBn = "ডকুমেন্টেশন ও সারাংশ এজেন্ট"
  override val keywords = listOf("summary", "report", "document", "সারসংক্ষেপ", "রিপোর্ট", "ব্যাখ্যা")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val clean = LxrSecretRedactor.redactSecrets(sharedContext).trim()
    val summary =
      if (clean.isBlank()) "সারাংশ তৈরির জন্য পর্যাপ্ত ফলাফল পাওয়া যায়নি বস।"
      else if (clean.length <= 2400) clean
      else clean.take(2350) + "\n... (সংক্ষিপ্ত)"
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = clean.isNotBlank(),
      summaryBn = summary,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

object LxrSpecialistRegistry {
  val ALL_AGENTS: List<LxrSpecialistAgent> =
    listOf(
      IntentAndPlannerAgent(),
      ReasoningAndMathAgent(),
      CodingAndDeveloperAgent(),
      CodeSecurityAuditorAgent(),
      DeepResearchVerificationAgent(),
      OsintInvestigationAgent(),
      DeviceAndHardwareAgent(),
      AccessibilityAndUiAgent(),
      CommunicationAndMessagingAgent(),
      EducationAndCurriculumAgent(),
      CreativeContentAndSeoAgent(),
      NetworkAndGatewayManagerAgent(),
      MonitoringAndQaAuditAgent(),
      VerificationCritiqueGateAgent(),
      DocumentationSummaryAgent(),
    )

  fun findById(id: String): LxrSpecialistAgent? =
    ALL_AGENTS.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
}

private fun String.containsAny(vararg needles: String): Boolean =
  needles.any { this.contains(it, ignoreCase = true) }
