package com.example.service

import android.content.Context
import com.example.data.Store
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
  val roleTitleEn: String
  val usesSharedCloudModel: Boolean
  val keywords: List<String>
  fun execute(ctx: Context, taskPrompt: String, sharedContext: String = ""): AgentExecutionOutput
}

class IntentAndPlannerAgent : LxrSpecialistAgent {
  override val id = "intent_planner"
  override val displayNameBn = "অ্যাডভান্সড প্ল্যানিং ও ইন্টেন্ট এজেন্ট"
  override val roleTitleEn = "Advanced Planning"
  override val usesSharedCloudModel = false
  override val keywords = listOf("plan", "steps", "roadmap", "workflow", "architecture", "পরিকল্পনা", "ধাপ", "কিভাবে")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val lower = taskPrompt.lowercase(Locale.ROOT)
    val domains = mutableListOf<String>()
    if (lower.containsAny("math", "calculate", "সমীকরণ", "হিসাব", "যোগ", "বিয়োগ", "গুণ", "ভাগ") || Regex("""\d+\s*[+\-*/^%]\s*\d+""").containsMatchIn(lower)) {
      domains.add("গণিত ও লজিক (Mathematics and Logic)")
    }
    if (lower.containsAny("security", "vulnerability", "secret", "leak", "সিকিউরিটি", "হ্যাক", "পাসওয়ার্ড", "অডিট")) {
      domains.add("কোড সিকিউরিটি (Code Security)")
    }
    if (lower.contains("python") || lower.contains("পাইথন")) domains.add("Python Engineering")
    if (lower.contains("kotlin") || lower.contains("compose") || lower.contains("android") || lower.contains("gradle")) domains.add("Kotlin/Android Engineering")
    if (lower.contains("java") && !lower.contains("javascript")) domains.add("Java Engineering")
    if (lower.containsAny("javascript", "typescript", "node", "react", "nextjs", "tsx", "jsx")) domains.add("JavaScript/TypeScript Engineering")
    if (lower.containsAny("code", "bug", "refactor", "কোড", "বাগ", "ফাংশন")) domains.add("Advanced Coding")
    if (lower.containsAny("research", "news", "trend", "verify", "তথ্য", "রিসার্চ", "খবর")) domains.add("Deep Research")
    if (lower.containsAny("osint", "domain", "investigate", "তদন্ত", "ডোমেইন")) domains.add("OSINT তদন্ত")
    if (lower.containsAny("learn", "study", "explain", "teach", "শেখা", "পড়া", "বুঝিয়ে")) domains.add("Education and Learning")
    if (lower.containsAny("network", "gateway", "termux", "provider", "নেটওয়ার্ক", "প্রোভাইডার")) domains.add("Autonomous Network Intelligence")
    if (lower.containsAny("youtube", "seo", "title", "hashtag", "ট্যাগ", "ভিডিও")) domains.add("ক্রিয়েটিভ SEO")
    if (lower.containsAny("device", "battery", "hardware", "wifi", "bluetooth", "gps", "ফোন", "ব্যাটারি")) domains.add("ডিভাইস ও হার্ডওয়্যার")
    if (domains.isEmpty()) domains.add("এক্সট্রা ক্রিটিক্যাল রিজনিং ও বিশ্লেষণ")
    val summary =
      "বসের অনুরোধ বিশ্লেষণ করে চিহ্নিত স্পেশালিস্ট ক্ষেত্র: ${domains.joinToString(", ")}।"
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

class ExtraCriticalReasoningAgent : LxrSpecialistAgent {
  override val id = "extra_critical_reasoning"
  override val displayNameBn = "এক্সট্রা ক্রিটিক্যাল রিজনিং এজেন্ট"
  override val roleTitleEn = "Extra Critical Reasoning"
  override val usesSharedCloudModel = true
  override val keywords = listOf("critical", "deep", "reason", "analyze", "compare", "tradeoff", "ক্রিটিক্যাল", "গভীর", "বিশ্লেষণ")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val gw =
      LxrProviderGateway.executeCriticalReasoning(
        ctx = ctx,
        taskPrompt = "গভীর যুক্তি, সম্ভাব্য ত্রুটি ও ধাপে ধাপে বিশ্লেষণসহ উত্তর দাও:\n$taskPrompt",
        screenContext = sharedContext,
      )
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = gw.succeeded,
      summaryBn = gw.formattedBn,
      findings = listOfNotNull(gw.failureSummaryBn),
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }
}

class ReasoningAndMathAgent : LxrSpecialistAgent {
  override val id = "reasoning_math"
  override val displayNameBn = "গণিত ও লজিক এজেন্ট"
  override val roleTitleEn = "Mathematics and Logic"
  override val usesSharedCloudModel = true
  override val keywords = listOf("math", "calculate", "solve", "logic", "equation", "হিসাব", "গণিত", "অংক", "যোগ", "গুণ")

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
          summaryBn = "ডিটারমিনিস্টিক গাণিতিক হিসাব ($extractedExpr) = $formatted",
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
  override val displayNameBn = "অ্যাডভান্সড কোডিং এজেন্ট"
  override val roleTitleEn = "Advanced Coding"
  override val usesSharedCloudModel = true
  override val keywords = listOf("code", "compile", "refactor", "bug", "algorithm", "কোড", "বাগ", "অ্যাপ", "রিফ্যাক্টর")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val gw =
      LxrProviderGateway.executeCriticalReasoning(
        ctx = ctx,
        taskPrompt = "ক্লিন আর্কিটেকচার, এজ-কেস ও পারফরম্যান্স মেনে প্রোডাকশন-মানের কোড সমাধান দাও:\n$taskPrompt",
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

class PythonEngineeringAgent : LxrSpecialistAgent {
  override val id = "python_engineering"
  override val displayNameBn = "Python ইঞ্জিনিয়ারিং এজেন্ট"
  override val roleTitleEn = "Python Engineering"
  override val usesSharedCloudModel = true
  override val keywords = listOf("python", "pip", "pytest", "fastapi", "django", "pandas", "পাইথন")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val staticFindings = auditPython(taskPrompt + "\n" + sharedContext)
    val apiConfigured = Store.apiKey(ctx).isNotBlank() || Store.openAiCompatBaseUrl(ctx).isNotBlank()
    val summary =
      if (apiConfigured) {
        val gw =
          LxrProviderGateway.executeCriticalReasoning(
            ctx = ctx,
            taskPrompt = "Python 3.11+ টাইপ হিন্ট ও PEP-8 মেনে সমাধান দাও:\n$taskPrompt",
            screenContext = sharedContext,
          )
        if (staticFindings.isEmpty()) gw.formattedBn
        else "🐍 Python স্ট্যাটিক অডিট:\n" + staticFindings.joinToString("\n• ", prefix = "• ") + "\n\n" + gw.formattedBn
      } else {
        if (staticFindings.isEmpty()) {
          "🐍 Python স্ট্যাটিক ইঞ্জিনিয়ারিং চেক: প্রদত্ত Python অংশে তাৎক্ষণিক eval/shell=True/pickle বা mutable default ত্রুটি নেই ✓ (বিস্তারিত কোড জেনারেশনের জন্য API Key যোগ করো)"
        } else {
          "🐍 Python স্ট্যাটিক সতর্কতা (${staticFindings.size}টি):\n" + staticFindings.joinToString("\n• ", prefix = "• ")
        }
      }
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = summary,
      findings = staticFindings,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }

  companion object {
    fun auditPython(src: String): List<String> {
      if (src.isBlank()) return emptyList()
      val issues = mutableListOf<String>()
      if (Regex("""\b(eval|exec)\s*\(""").containsMatchIn(src)) {
        issues.add("Python `eval()`/`exec()` ব্যবহার ঝুঁকিপূর্ণ — `ast.literal_eval()` বা নিরাপদ পার্সার ব্যবহার করো।")
      }
      if (Regex("""subprocess\.[a-zA-Z_]+\([^)]*shell\s*=\s*True""").containsMatchIn(src)) {
        issues.add("`subprocess(..., shell=True)` কমান্ড ইনজেকশন ঝুঁকি তৈরি করে — লিস্ট আর্গুমেন্ট ব্যবহার করো।")
      }
      if (Regex("""pickle\.loads?\s*\(""").containsMatchIn(src)) {
        issues.add("অবিশ্বস্ত ডেটায় `pickle.loads()` ব্যবহার করা অনিরাপদ — JSON ব্যবহার করো।")
      }
      if (Regex("""def\s+\w+\s*\([^)]*=\s*(\[\]|\{\})""").containsMatchIn(src)) {
        issues.add("Mutable default argument (`=[]` বা `={}`) পাওয়া গেছে — `=None` ব্যবহার করো।")
      }
      if (Regex("""^\s*except\s*:""", RegexOption.MULTILINE).containsMatchIn(src)) {
        issues.add("খালি `except:` ব্যবহার করলে আসল ত্রুটি লুকিয়ে যায় — নির্দিষ্ট Exception ধরো।")
      }
      return issues
    }
  }
}

class JavaEngineeringAgent : LxrSpecialistAgent {
  override val id = "java_engineering"
  override val displayNameBn = "Java ইঞ্জিনিয়ারিং এজেন্ট"
  override val roleTitleEn = "Java Engineering"
  override val usesSharedCloudModel = true
  override val keywords = listOf("java", "jvm", "maven", "spring", "openjdk", "জাভা")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val staticFindings = auditJava(taskPrompt + "\n" + sharedContext)
    val apiConfigured = Store.apiKey(ctx).isNotBlank() || Store.openAiCompatBaseUrl(ctx).isNotBlank()
    val summary =
      if (apiConfigured) {
        val gw =
          LxrProviderGateway.executeCriticalReasoning(
            ctx = ctx,
            taskPrompt = "আধুনিক Java 17/21 কনভেনশন, null-safety ও কনকারেন্সি মেনে সমাধান দাও:\n$taskPrompt",
            screenContext = sharedContext,
          )
        if (staticFindings.isEmpty()) gw.formattedBn
        else "☕ Java স্ট্যাটিক অডিট:\n" + staticFindings.joinToString("\n• ", prefix = "• ") + "\n\n" + gw.formattedBn
      } else {
        if (staticFindings.isEmpty()) {
          "☕ Java স্ট্যাটিক চেক: প্রদত্ত Java অংশে SQL concatenation বা deprecated Thread API ঝুঁকি মেলেনি ✓"
        } else {
          "☕ Java স্ট্যাটিক সতর্কতা (${staticFindings.size}টি):\n" + staticFindings.joinToString("\n• ", prefix = "• ")
        }
      }
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = summary,
      findings = staticFindings,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }

  companion object {
    fun auditJava(src: String): List<String> {
      if (src.isBlank()) return emptyList()
      val issues = mutableListOf<String>()
      if (Regex("""Statement\b[\s\S]{0,120}\.execute(Query|Update)?\s*\([^)]*\+""").containsMatchIn(src)) {
        issues.add("Java `Statement` স্ট্রিং কনক্যাটেনেশন SQL Injection ঝুঁকি — `PreparedStatement` ব্যবহার করো।")
      }
      if (Regex("""\.stop\(\s*\)|\.suspend\(\s*\)""").containsMatchIn(src)) {
        issues.add("Deprecated `Thread.stop()`/`suspend()` নিষিদ্ধ — `ExecutorService` বা interrupt ফ্ল্যাগ ব্যবহার করো।")
      }
      if (Regex("""new\s+SimpleDateFormat\(""").containsMatchIn(src)) {
        issues.add("`SimpleDateFormat` thread-safe নয় — `java.time.format.DateTimeFormatter` ব্যবহার করো।")
      }
      return issues
    }
  }
}

class KotlinAndroidEngineeringAgent : LxrSpecialistAgent {
  override val id = "kotlin_android_engineering"
  override val displayNameBn = "Kotlin / Android ইঞ্জিনিয়ারিং এজেন্ট"
  override val roleTitleEn = "Kotlin/Android Engineering"
  override val usesSharedCloudModel = true
  override val keywords = listOf("kotlin", "android", "compose", "gradle", "coroutine", "flow", "viewmodel", "অ্যান্ড্রয়েড", "কটলিন")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val staticFindings = auditKotlinAndroid(taskPrompt + "\n" + sharedContext)
    val apiConfigured = Store.apiKey(ctx).isNotBlank() || Store.openAiCompatBaseUrl(ctx).isNotBlank()
    val summary =
      if (apiConfigured) {
        val gw =
          LxrProviderGateway.executeCriticalReasoning(
            ctx = ctx,
            taskPrompt = "Kotlin Coroutines, Jetpack Compose M3 ও Android লাইফসাইকেল বেস্ট প্র্যাকটিস মেনে সমাধান দাও:\n$taskPrompt",
            screenContext = sharedContext,
          )
        if (staticFindings.isEmpty()) gw.formattedBn
        else "🤖 Kotlin/Android স্ট্যাটিক অডিট:\n" + staticFindings.joinToString("\n• ", prefix = "• ") + "\n\n" + gw.formattedBn
      } else {
        if (staticFindings.isEmpty()) {
          "🤖 Kotlin/Android স্ট্যাটিক চেক: GlobalScope বা অকারণ Main-thread ব্লকিং প্যাটার্ন মেলেনি ✓"
        } else {
          "🤖 Kotlin/Android সতর্কতা (${staticFindings.size}টি):\n" + staticFindings.joinToString("\n• ", prefix = "• ")
        }
      }
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = summary,
      findings = staticFindings,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }

  companion object {
    fun auditKotlinAndroid(src: String): List<String> {
      if (src.isBlank()) return emptyList()
      val issues = mutableListOf<String>()
      if (src.contains("GlobalScope.launch") || src.contains("GlobalScope.async")) {
        issues.add("`GlobalScope` লিক তৈরি করে — `viewModelScope`, `lifecycleScope` বা `SupervisorJob` স্কোপ ব্যবহার করো।")
      }
      if (Regex("""Thread\.sleep\(""").containsMatchIn(src) && src.contains("@Composable")) {
        issues.add("Composable ফাংশনের ভেতর `Thread.sleep()` UI থ্রেড আটকে দেয় — `LaunchedEffect` + `delay()` ব্যবহার করো।")
      }
      if (Regex("""!!\.""").containsMatchIn(src)) {
        issues.add("Non-null assertion (`!!`) NPE ঝুঁকি বাড়ায় — safe call (`?.`) ও default মান ব্যবহার করো।")
      }
      return issues
    }
  }
}

class JsTsEngineeringAgent : LxrSpecialistAgent {
  override val id = "js_ts_engineering"
  override val displayNameBn = "JavaScript / TypeScript ইঞ্জিনিয়ারিং এজেন্ট"
  override val roleTitleEn = "JavaScript/TypeScript Engineering"
  override val usesSharedCloudModel = true
  override val keywords = listOf("javascript", "typescript", "node", "react", "next", "npm", "js", "ts", "tsx")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val staticFindings = auditJsTs(taskPrompt + "\n" + sharedContext)
    val apiConfigured = Store.apiKey(ctx).isNotBlank() || Store.openAiCompatBaseUrl(ctx).isNotBlank()
    val summary =
      if (apiConfigured) {
        val gw =
          LxrProviderGateway.executeCriticalReasoning(
            ctx = ctx,
            taskPrompt = "Strict TypeScript ও আধুনিক ESM মানদণ্ড মেনে সমাধান দাও:\n$taskPrompt",
            screenContext = sharedContext,
          )
        if (staticFindings.isEmpty()) gw.formattedBn
        else "🟨 JS/TS স্ট্যাটিক অডিট:\n" + staticFindings.joinToString("\n• ", prefix = "• ") + "\n\n" + gw.formattedBn
      } else {
        if (staticFindings.isEmpty()) {
          "🟨 JS/TS স্ট্যাটিক চেক: `innerHTML`/`dangerouslySetInnerHTML`/`eval()` বা XSS-ঝুঁকি মেলেনি ✓"
        } else {
          "🟨 JS/TS স্ট্যাটিক সতর্কতা (${staticFindings.size}টি):\n" + staticFindings.joinToString("\n• ", prefix = "• ")
        }
      }
    return AgentExecutionOutput(
      agentId = id,
      agentNameBn = displayNameBn,
      succeeded = true,
      summaryBn = summary,
      findings = staticFindings,
      latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
    )
  }

  companion object {
    fun auditJsTs(src: String): List<String> {
      if (src.isBlank()) return emptyList()
      val issues = mutableListOf<String>()
      if (src.contains("innerHTML") || src.contains("dangerouslySetInnerHTML")) {
        issues.add("`innerHTML`/`dangerouslySetInnerHTML` XSS ঝুঁকি তৈরি করে — safe text binding বা DOMPurify ব্যবহার করো।")
      }
      if (Regex("""\beval\s*\(|new\s+Function\s*\(""").containsMatchIn(src)) {
        issues.add("`eval()`/`new Function()` নিষিদ্ধ — ডায়নামিক স্ট্রিং এক্সিকিউশন সরিয়ে ফেলো।")
      }
      if (Regex("""\bvar\s+\w+""").containsMatchIn(src)) {
        issues.add("`var` স্কোপ বিভ্রান্তি করে — `const` বা `let` ব্যবহার করো।")
      }
      return issues
    }
  }
}

class CodeSecurityAuditorAgent : LxrSpecialistAgent {
  override val id = "code_security"
  override val displayNameBn = "কোড সিকিউরিটি অডিটর এজেন্ট"
  override val roleTitleEn = "Code Security"
  override val usesSharedCloudModel = false
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
  override val roleTitleEn = "Deep Research"
  override val usesSharedCloudModel = false
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
  override val roleTitleEn = "OSINT Investigation"
  override val usesSharedCloudModel = false
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
  override val roleTitleEn = "Device & Hardware Diagnostics"
  override val usesSharedCloudModel = false
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
  override val roleTitleEn = "Accessibility & UI Control"
  override val usesSharedCloudModel = false
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
  override val roleTitleEn = "Communication & Messaging"
  override val usesSharedCloudModel = false
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
  override val roleTitleEn = "Education and Learning"
  override val usesSharedCloudModel = true
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
  override val roleTitleEn = "Creative Content & YouTube SEO"
  override val usesSharedCloudModel = false
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
  override val displayNameBn = "নেটওয়ার্ক ইন্টেলিজেন্স, প্রোভাইডার ও Termux গেটওয়ে এজেন্ট"
  override val roleTitleEn = "Autonomous Network Intelligence"
  override val usesSharedCloudModel = false
  override val keywords = listOf("network", "gateway", "provider", "api", "termux", "langgraph", "crewai", "litellm", "aider", "openhands", "haystack", "llamaindex", "semgrep", "নেটওয়ার্ক", "প্রোভাইডার")

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
        appendLine()
        appendLine("🛠️ Termux ৮টি ফ্রেমওয়ার্ক প্রোব:")
        probes.forEach { f ->
          appendLine("• ${f.displayName} [${f.status.name}]: Python ${f.minPythonVersion}, ${f.diskRequiredMb}MB Disk, ${f.ramRequiredMb}MB RAM — ${f.statusBn}")
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

class MonitoringAndQaAuditAgent : LxrSpecialistAgent {
  override val id = "monitoring_qa"
  override val displayNameBn = "কোয়ালিটি অ্যাসুরেন্স (QA), মনিটরিং ও সক্ষমতা অডিট এজেন্ট"
  override val roleTitleEn = "Quality Assurance"
  override val usesSharedCloudModel = false
  override val keywords = listOf("monitor", "activity", "capability", "status", "qa", "quality", "test", "টাস্ক", "স্ট্যাটাস", "সক্ষমতা")

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
  override val roleTitleEn = "Verification Critique Gate"
  override val usesSharedCloudModel = false
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
  override val displayNameBn = "টেকনিক্যাল ডকুমেন্টেশন ও সারাংশ এজেন্ট"
  override val roleTitleEn = "Technical Documentation"
  override val usesSharedCloudModel = false
  override val keywords = listOf("summary", "report", "document", "docs", "readme", "সারসংক্ষেপ", "রিপোর্ট", "ব্যাখ্যা")

  override fun execute(ctx: Context, taskPrompt: String, sharedContext: String): AgentExecutionOutput {
    val start = System.currentTimeMillis()
    val clean = LxrSecretRedactor.redactSecrets(sharedContext).trim()
    val summary =
      if (clean.isBlank()) "সারাংশ তৈরির জন্য পর্যাপ্ত ফলাফল পাওয়া যায়নি বস।"
      else if (clean.length <= 2800) clean
      else clean.take(2750) + "\n... (সংক্ষিপ্ত)"
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
  /**
   * All 15 named roles from the Master Upgrade architecture specification:
   * 1) Autonomous Orchestrator (LxrAutonomousOrchestrator), plus the 14 specialist agents below
   * (and extended LXR domain agents in ALL_AGENTS).
   */
  val CORE_SPEC_15_ROLES: List<String> =
    listOf(
      "autonomous_orchestrator",
      "extra_critical_reasoning",
      "coding_developer",
      "python_engineering",
      "java_engineering",
      "kotlin_android_engineering",
      "js_ts_engineering",
      "education_tutor",
      "reasoning_math",
      "deep_research",
      "documentation_summary",
      "code_security",
      "network_gateway",
      "intent_planner",
      "monitoring_qa",
    )

  val ALL_AGENTS: List<LxrSpecialistAgent> =
    listOf(
      IntentAndPlannerAgent(),
      ExtraCriticalReasoningAgent(),
      ReasoningAndMathAgent(),
      CodingAndDeveloperAgent(),
      PythonEngineeringAgent(),
      JavaEngineeringAgent(),
      KotlinAndroidEngineeringAgent(),
      JsTsEngineeringAgent(),
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

  fun architectureTransparencyBn(): String =
    "১৫টি স্পেশালিস্ট রোল (Autonomous Orchestrator + ১৪টি মডিউলার রোল এবং ৬টি এক্সটেন্ডেড LXR ডোমেইন এজেন্ট): " +
      "ডিটারমিনিস্টিক অডিট/ম্যাথ/রিসার্চ/মনিটর এজেন্ট সম্পূর্ণ লোকালি চলে এবং রিজনিং/কোডিং/টিউটর এজেন্টগুলো একীভূত LxrProviderGateway " +
      "(বসের কনফিগার করা শেয়ার্ড Gemini/BYO মডেল) ব্যবহার করে — প্রতিটি রোলের জন্য আলাদা প্রাইভেট LLM দাবি করা হয় না।"
}

private fun String.containsAny(vararg needles: String): Boolean =
  needles.any { this.contains(it, ignoreCase = true) }
