package com.example.builder

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

object AppProjectGenerator {
  private const val TAG = "LxrAppGenerator"

  private val planSchema by lazy {
    Schema.obj(
      properties = mapOf(
        "requirementSummary" to Schema.string(description = "Concise Bengali requirement analysis of the requested Android app."),
        "architecturePlan" to Schema.string(description = "Concise architecture breakdown (packages, screens, components) in Bengali/English."),
        "keyFeatures" to Schema.array(items = Schema.string(), description = "3 to 5 key screens or features to implement."),
      )
    )
  }

  private val freePlanModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.5-flash-lite",
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = planSchema
      },
    )
  }

  private val freeCodeModel by lazy {
    Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
  }

  suspend fun runAutomaticDevelopmentPipeline(
    draft: AppBuilderProject,
    onStageProgress: (stage: DevelopmentStage, progress: Float, logLine: String) -> Unit,
  ): AppBuilderProject {
    val pkg = AppProjectTemplates.sanitizePackageName(draft.packageName, draft.slug)
    val baseProject = draft.copy(packageName = pkg)
    val collectedLogs = mutableListOf<String>()

    fun step(stage: DevelopmentStage, msg: String) {
      val progress = stage.stepNumber / 10f
      val formatted = "[ধাপ ${stage.stepNumber}/10 • ${stage.titleBn}] $msg"
      collectedLogs.add(formatted)
      onStageProgress(stage, progress, formatted)
    }

    // 1. Requirement Analysis (Free Firebase AI + Local Offline Hybrid)
    step(DevelopmentStage.REQUIREMENT_ANALYSIS, "অ্যাপের বিবরণ ও ক্যাটাগরি বিশ্লেষণ চলছে...")
    val (analysisText, archText) = analyzeRequirementsWithFreeAiOrLocal(baseProject)
    delay(120)

    // 2. App Architecture
    step(DevelopmentStage.APP_ARCHITECTURE, "MVVM এবং Clean Android প্যাকেজ আর্কিটেকচার সাজানো হয়েছে ($pkg)")
    delay(100)

    // 3. UI/UX Design
    step(
      DevelopmentStage.UI_UX_DESIGN,
      "ডিজাইন থিম '${baseProject.designTheme.labelBn}' (${baseProject.designTheme.primaryHex}) প্রয়োগ করা হয়েছে",
    )
    delay(100)

    // 4-9. Source, XML/Kotlin/Java, Resources, Structure, Dependencies, Build Config
    step(DevelopmentStage.SOURCE_CODE, "${baseProject.language.labelBn} সোর্স কোড তৈরি হচ্ছে...")
    val generatedFiles = AppProjectTemplates.generateFullProjectFiles(baseProject).toMutableMap()

    // Enhance primary screen source code via free Firebase AI when user provided rich custom description
    if (baseProject.description.trim().length >= 12) {
      tryAiCustomMainScreen(baseProject, generatedFiles)?.let { enhancementNote ->
        collectedLogs.add("✓ ফ্রি AI কোড কাস্টমাইজেশন যুক্ত হয়েছে: $enhancementNote")
      }
    }

    step(
      DevelopmentStage.RESOURCE_FILES,
      "Kotlin/Java ও XML লেআউট ফাইল লেখা হয়েছে (${generatedFiles.keys.count { it.endsWith(".kt") || it.endsWith(".java") || it.endsWith(".xml") }}টি)",
    )
    delay(80)

    step(DevelopmentStage.ASSETS_RESOURCES, "Adaptive Vector Icon, strings.xml, colors.xml ও themes.xml প্রস্তুত")
    delay(80)

    step(DevelopmentStage.PROJECT_STRUCTURE, "Android স্ট্যান্ডার্ড ফোল্ডার স্ট্রাকচার সাজানো সম্পন্ন (${generatedFiles.size}টি ফাইল)")
    delay(80)

    step(
      DevelopmentStage.DEPENDENCIES,
      "ফ্রি ওপেন-সোর্স ডিপেন্ডেন্সি নির্ধারণ: ${baseProject.category.defaultDependencies.joinToString()}",
    )
    delay(80)

    step(
      DevelopmentStage.BUILD_CONFIG,
      "Gradle 9.1 / AGP 9.1.1 ও ${baseProject.minSdk.label} বিল্ড কনফিগ সম্পন্ন",
    )
    delay(80)

    step(
      DevelopmentStage.APPLICATION_BUILD,
      "প্রজেক্ট সোর্স কোড ও বিল্ড স্ক্রিপ্ট প্রস্তুত — Termux বিল্ডের জন্য তৈরি",
    )

    return baseProject.copy(
      updatedAtMs = System.currentTimeMillis(),
      analysisReport = analysisText,
      architecturePlan = archText,
      dependenciesSummary = baseProject.category.defaultDependencies,
      files = generatedFiles,
      buildStatus = BuildStatus.GENERATED,
      buildLogs = collectedLogs,
      lastDiagnosis = null,
    )
  }

  private suspend fun analyzeRequirementsWithFreeAiOrLocal(
    project: AppBuilderProject,
  ): Pair<String, String> {
    val fallbackAnalysis = buildString {
      appendLine("অ্যাপের নাম: ${project.name}")
      appendLine("ক্যাটাগরি: ${project.category.titleEn} (${project.category.titleBn})")
      appendLine("মূল ফিচার: ${project.description.ifBlank { project.category.defaultFeatureHint }}")
      appendLine("ভাষা ও টার্গেট: ${project.language.labelBn} • ${project.minSdk.label}")
    }.trim()

    val fallbackArch = buildString {
      appendLine("প্যাকেজ: ${project.packageName}")
      appendLine("প্যাটার্ন: Single-Activity + Modular UI + Adaptive Material 3 Resources")
      appendLine("ইন্টারফেস: ${project.designTheme.labelBn} (${project.designTheme.primaryHex} / ${project.designTheme.accentHex})")
      appendLine("বিল্ড সিস্টেম: Free Open-Source Gradle + Termux ARM64 AAPT2")
    }.trim()

    return try {
      val prompt =
        """
        Android App Requirement Analysis তৈরি করো (ফ্রি ওপেন-সোর্স স্ট্যান্ডার্ড):
        Name: ${project.name}
        Category: ${project.category.titleEn}
        Language: ${project.language.labelBn}
        Description: ${project.description.ifBlank { project.category.defaultFeatureHint }}
        MinSDK: ${project.minSdk.label}
        সংক্ষেপে বাংলায় requirementSummary, architecturePlan এবং keyFeatures দাও।
        """.trimIndent()
      val resp = withTimeout(18_000L) { freePlanModel.generateContent(prompt) }
      val json = JSONObject(resp.text.orEmpty())
      val req = json.optString("requirementSummary").takeIf { it.isNotBlank() } ?: fallbackAnalysis
      val arch = json.optString("architecturePlan").takeIf { it.isNotBlank() } ?: fallbackArch
      val arr = json.optJSONArray("keyFeatures")
      val featuresLine =
        if (arr != null && arr.length() > 0) {
          "\nপ্রস্তাবিত ফিচার:\n" +
            (0 until arr.length()).joinToString("\n") { "• " + arr.optString(it) }
        } else {
          ""
        }
      (req + featuresLine) to arch
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Offline/fallback local analysis used: ${e.message}")
      fallbackAnalysis to fallbackArch
    }
  }

  private suspend fun tryAiCustomMainScreen(
    project: AppBuilderProject,
    files: MutableMap<String, String>,
  ): String? {
    val pkgPath = project.packageName.replace('.', '/')
    val mainFilePath =
      if (project.language == ProgrammingLanguage.JAVA) "app/src/main/java/$pkgPath/MainActivity.java"
      else "app/src/main/java/$pkgPath/MainActivity.kt"
    val currentSource = files[mainFilePath] ?: return null
    val layoutXml = files["app/src/main/res/layout/activity_main.xml"]

    return try {
      val prompt =
        """
        You are an expert Android engineer. Enhance this ${project.language.labelBn} MainActivity strictly so it compiles cleanly with ZERO missing imports or undefined view IDs.
        Package: ${project.packageName}
        App Name: ${project.name}
        User Description: ${project.description}
        ${if (layoutXml != null) "Existing activity_main.xml IDs MUST stay identical (tvAppTitle, tvAppSubtitle, etPrimaryInput, btnPrimaryAction, tvOutputList)." else ""}
        Current working file:
        ```
        $currentSource
        ```
        Return ONLY valid complete file code (no markdown explanation) preserving the exact package statement `package ${project.packageName}` and class `MainActivity`.
        """.trimIndent()
      val resp = withTimeout(22_000L) { freeCodeModel.generateContent(prompt) }
      val cleaned = extractCleanCodeBlock(resp.text.orEmpty())
      if (isValidMainActivityCandidate(cleaned, project.packageName, project.language, layoutXml != null)) {
        files[mainFilePath] = cleaned
        "MainActivity কাস্টমাইজড"
      } else {
        null
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  private fun extractCleanCodeBlock(raw: String): String {
    val fenced = Regex("```(?:kotlin|java)?\\s*([\\s\\S]*?)```").find(raw)
    return (fenced?.groupValues?.getOrNull(1) ?: raw).trim()
  }

  internal fun isValidMainActivityCandidate(
    code: String,
    pkg: String,
    lang: ProgrammingLanguage,
    usesXmlLayout: Boolean,
  ): Boolean {
    if (code.length < 180) return false
    if (!code.contains("package $pkg")) return false
    if (!code.contains("class MainActivity")) return false
    if (!code.contains("onCreate")) return false
    if (usesXmlLayout && !code.contains("R.layout.activity_main")) return false
    if (lang == ProgrammingLanguage.JAVA && !code.endsWith("}")) return false
    val openBraces = code.count { it == '{' }
    val closeBraces = code.count { it == '}' }
    return openBraces > 2 && openBraces == closeBraces
  }
}

object AutoErrorFixer {
  /**
   * Analyzes Gradle / AAPT2 / Kotlin / Java / XML error logs and returns a diagnosis + optional
   * automatic file repair map. If [previousSignature] matches the current error signature, sets
   * [BuildErrorDiagnosis.isRepeatedSameError] = true to avoid infinite retry loops and surface
   * clear details to the user.
   */
  fun diagnoseAndRepair(
    project: AppBuilderProject,
    errorLog: String,
    previousSignature: String?,
    attemptCount: Int,
  ): Pair<BuildErrorDiagnosis, Map<String, String>> {
    val lower = errorLog.lowercase()
    val signature =
      errorLog
        .lineSequence()
        .firstOrNull { line ->
          line.contains("error:", ignoreCase = true) ||
            line.contains("exception", ignoreCase = true) ||
            line.contains("failed", ignoreCase = true) ||
            line.contains("unresolved reference", ignoreCase = true)
        }
        ?.trim()
        ?.take(120)
        ?: errorLog.trim().take(120)

    val repeated = previousSignature != null && previousSignature == signature && attemptCount > 1
    val updatedFiles = project.files.toMutableMap()

    // Rule 1: Termux ARM64 aapt2 binary Exec format error
    if (lower.contains("aapt2") && (lower.contains("exec format") || lower.contains("daemon") || lower.contains("cannot run program"))) {
      val gp = updatedFiles["gradle.properties"].orEmpty()
      val patched =
        if (!gp.contains("android.aapt2FromMavenOverride")) {
          gp + "\nandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2\n"
        } else {
          gp
        }
      updatedFiles["gradle.properties"] = patched
      return BuildErrorDiagnosis(
        rawLogSnippet = errorLog.takeLast(600),
        rootCauseBn = "Termux ARM64 ফোনে ডিফল্ট x86 AAPT2 কাজ করেনি।",
        suggestedFixBn = "gradle.properties-এ Termux নেটিভ aapt2 পাথ override যুক্ত করা হয়েছে।",
        affectedFile = "gradle.properties",
        autoFixApplied = !repeated,
        attemptCount = attemptCount,
        isRepeatedSameError = repeated,
      ) to updatedFiles
    }

    // Rule 2: Missing dependency / unresolved material or appcompat
    if (lower.contains("unresolved reference") || lower.contains("package androidx.appcompat") || lower.contains("does not exist")) {
      val buildFile = updatedFiles["app/build.gradle.kts"].orEmpty()
      var patched = buildFile
      if (!patched.contains("appcompat")) {
        patched = patched.replace(
          "dependencies {",
          "dependencies {\n  implementation(\"androidx.appcompat:appcompat:1.7.0\")\n  implementation(\"com.google.android.material:material:1.12.0\")",
        )
      }
      if (!patched.contains("core-ktx") && project.language != ProgrammingLanguage.JAVA) {
        patched = patched.replace("dependencies {", "dependencies {\n  implementation(\"androidx.core:core-ktx:1.15.0\")")
      }
      if (patched != buildFile && !repeated) {
        updatedFiles["app/build.gradle.kts"] = patched
        return BuildErrorDiagnosis(
          rawLogSnippet = errorLog.takeLast(600),
          rootCauseBn = "অপ্রস্তুত বা মিসিং AndroidX/Material ডিপেন্ডেন্সি বা ইম্পোর্ট শনাক্ত হয়েছে।",
          suggestedFixBn = "app/build.gradle.kts-এ প্রয়োজনীয় AndroidX ও Material ডিপেন্ডেন্সি যোগ করা হয়েছে।",
          affectedFile = "app/build.gradle.kts",
          autoFixApplied = true,
          attemptCount = attemptCount,
          isRepeatedSameError = false,
        ) to updatedFiles
      }
    }

    // Rule 3: XML parsing error -> regenerate valid clean XML resources
    if (lower.contains("xml") && (lower.contains("parse") || lower.contains("not well-formed") || lower.contains("strings.xml") || lower.contains("activity_main.xml"))) {
      val cleanTemplate = AppProjectTemplates.generateFullProjectFiles(project)
      if (!repeated) {
        cleanTemplate.filterKeys { it.endsWith(".xml") }.forEach { (k, v) -> updatedFiles[k] = v }
        return BuildErrorDiagnosis(
          rawLogSnippet = errorLog.takeLast(600),
          rootCauseBn = "XML রিসোর্স ফাইলে সিনট্যাক্স বা ক্যারেক্টার ত্রুটি পাওয়া গেছে।",
          suggestedFixBn = "সব XML রিসোর্স ও ম্যানিফেস্ট ফাইল স্বয়ংক্রিয়ভাবে পরিষ্কার XML-এ মেরামত করা হয়েছে।",
          affectedFile = "app/src/main/res/",
          autoFixApplied = true,
          attemptCount = attemptCount,
          isRepeatedSameError = false,
        ) to updatedFiles
      }
    }

    // Rule 4: Kotlin/Java MainActivity syntax error -> restore verified clean MainActivity
    if (lower.contains("mainactivity.kt") || lower.contains("mainactivity.java") || lower.contains("syntax error") || lower.contains("expecting")) {
      val cleanTemplate = AppProjectTemplates.generateFullProjectFiles(project)
      val pkgPath = project.packageName.replace('.', '/')
      val target =
        if (project.language == ProgrammingLanguage.JAVA) "app/src/main/java/$pkgPath/MainActivity.java"
        else "app/src/main/java/$pkgPath/MainActivity.kt"
      val fallbackMain = cleanTemplate[target]
      if (fallbackMain != null && updatedFiles[target] != fallbackMain && !repeated) {
        updatedFiles[target] = fallbackMain
        return BuildErrorDiagnosis(
          rawLogSnippet = errorLog.takeLast(600),
          rootCauseBn = "$target ফাইলে কম্পাইলেশন বা সিনট্যাক্স ত্রুটি পাওয়া গেছে।",
          suggestedFixBn = "সোর্স কোডটি ভেরিফাইড কম্পাইলেবল কোডে অটো-ফিক্স করা হয়েছে।",
          affectedFile = target,
          autoFixApplied = true,
          attemptCount = attemptCount,
          isRepeatedSameError = false,
        ) to updatedFiles
      }
    }

    // Rule 5: Missing Termux packages / permission / generic repeated error -> report clearly
    val cause =
      when {
        lower.contains("termux") && lower.contains("permission") ->
          "Termux-এ 'allow-external-apps=true' অথবা RUN_COMMAND পারমিশন চালু নেই।"
        lower.contains("gradle: command not found") || lower.contains("command not found") ->
          "Termux-এ Gradle বা OpenJDK ইনস্টল করা নেই — প্রথমে সেটআপ কমান্ড চালান।"
        lower.contains("network") || lower.contains("unknownhost") || lower.contains("resolve") ->
          "ডিপেন্ডেন্সি ডাউনলোডের সময় ইন্টারনেট সংযোগ বিচ্ছিন্ন হয়েছে।"
        else -> "বিল্ড লগে ত্রুটি শনাক্ত হয়েছে — নিচের লগ দেখে কোড বা সেটআপ যাচাই করুন।"
      }

    return BuildErrorDiagnosis(
      rawLogSnippet = errorLog.takeLast(800),
      rootCauseBn = cause,
      suggestedFixBn =
        if (repeated) "একই ত্রুটি বারবার হচ্ছে — অনুগ্রহ করে Error Details দেখে Termux সেটআপ বা কোড ঠিক করুন।"
        else "Termux সেটআপ নির্দেশনা অনুসরণ করে অথবা ম্যানুয়াল এডিটরে ফাইলটি দেখে আবার বিল্ড দিন।",
      affectedFile = null,
      autoFixApplied = false,
      attemptCount = attemptCount,
      isRepeatedSameError = repeated,
    ) to updatedFiles
  }
}
