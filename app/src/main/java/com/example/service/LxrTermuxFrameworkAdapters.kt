package com.example.service

import android.content.Context
import com.example.data.Store
import java.io.File
import java.util.Locale

/**
 * Strict 8-state verification model for external Termux / CLI frameworks.
 * Prevents ever conflating installed packages with tested or working runtimes.
 */
enum class TermuxFrameworkStatus(val labelBn: String) {
  NOT_CHECKED("এখনো যাচাই করা হয়নি (NOT_CHECKED)"),
  NOT_INSTALLED("ইনস্টল করা নেই (NOT_INSTALLED)"),
  INSTALLED("ইনস্টল শনাক্ত হয়েছে (INSTALLED)"),
  EXECUTABLE("এক্সিকিউটেবল বাইনারি প্রস্তুত (EXECUTABLE)"),
  TESTED("সেল্ফ-টেস্ট সম্পন্ন (TESTED)"),
  WORKING("সক্রিয় ও কার্যকর (WORKING)"),
  UNSUPPORTED("Android ARM64-এ সরাসরি অসমর্থিত (UNSUPPORTED)"),
  UNAVAILABLE("Termux বা রানটাইম অনুপলব্ধ (UNAVAILABLE)"),
}

data class TermuxFrameworkDescriptor(
  val id: String,
  val displayName: String,
  val pythonImportName: String,
  val cliBinaryName: String,
  val purposeBn: String,
  val termuxInstallCommand: String,
  val minPythonVersion: String,
  val estimatedDiskMb: Int,
  val estimatedRamMb: Int,
  val supportsDirectTermuxArm64: Boolean,
  val requiresExternalBackendRecommended: Boolean,
  val nativeBuildDeps: List<String>,
  val arm64CompatibilityNoteBn: String,
)

data class TermuxFrameworkProbeResult(
  val id: String,
  val displayName: String,
  val status: TermuxFrameworkStatus,
  val termuxAppInstalled: Boolean,
  val runtimeMarkerDetected: Boolean,
  val isAvailableNow: Boolean,
  val arm64Compatible: Boolean,
  val diskRequiredMb: Int,
  val ramRequiredMb: Int,
  val minPythonVersion: String,
  val externalBackendUrlConfigured: Boolean,
  val nativeDepsSummary: String,
  val arm64CompatibilityNoteBn: String,
  val statusBn: String,
  val installCommand: String,
)

data class TermuxExecutionOutcome(
  val frameworkId: String,
  val executed: Boolean,
  val status: TermuxFrameworkStatus,
  val exitCode: Int? = null,
  val messageBn: String,
  val rawOutput: String = "",
)

/**
 * Honest bridge/adapter registry for heavyweight external Python/CLI frameworks.
 * Distinguishes all 8 lifecycle states (NOT_CHECKED .. UNAVAILABLE), checks ARM64
 * compatibility/RAM/disk requirements, and dispatches verified commands to Termux UI
 * without ever fabricating CLI success when Termux or a framework is unavailable.
 */
object LxrTermuxFrameworkAdapters {
  private val UNSAFE_SHELL_REGEX = Regex("[;|&`$()<>\\n\\r]")

  val FRAMEWORKS: List<TermuxFrameworkDescriptor> =
    listOf(
      TermuxFrameworkDescriptor(
        id = "langgraph",
        displayName = "LangGraph",
        pythonImportName = "langgraph",
        cliBinaryName = "python3",
        purposeBn = "স্টেটফুল মাল্টি-এজেন্ট গ্রাফ ওয়ার্কফ্লো (অ্যান্ড্রয়েডে LxrAutonomousOrchestrator সমতুল্য নেটিভভাবে চালু আছে)",
        termuxInstallCommand = "pkg install -y python && pip install langgraph",
        minPythonVersion = "3.10+",
        estimatedDiskMb = 180,
        estimatedRamMb = 512,
        supportsDirectTermuxArm64 = true,
        requiresExternalBackendRecommended = false,
        nativeBuildDeps = listOf("python", "pip", "pydantic-core (precompiled wheel/rust if missing)"),
        arm64CompatibilityNoteBn = "ARM64 Termux-এ Pure Python + Pydantic সহ সাধারণত চলে; কিছু সংস্করণে rust দরকার হতে পারে।",
      ),
      TermuxFrameworkDescriptor(
        id = "crewai",
        displayName = "CrewAI",
        pythonImportName = "crewai",
        cliBinaryName = "crewai",
        purposeBn = "রোল-ভিত্তিক টিম এজেন্ট অটোমেশন",
        termuxInstallCommand = "pkg install -y python rust clang libffi openssl && pip install crewai",
        minPythonVersion = "3.10–3.12",
        estimatedDiskMb = 650,
        estimatedRamMb = 1024,
        supportsDirectTermuxArm64 = false,
        requiresExternalBackendRecommended = true,
        nativeBuildDeps = listOf("rust", "clang", "onnxruntime/chromadb native wheels"),
        arm64CompatibilityNoteBn = "CrewAI-এর ভারী নেটিভ ডিপেন্ডেন্সি (chromadb/onnxruntime) Android ARM64 Termux-এ প্রায়ই ব্যর্থ হয় — রিমোট/পিসি ব্যাকএন্ড উত্তম।",
      ),
      TermuxFrameworkDescriptor(
        id = "litellm",
        displayName = "LiteLLM",
        pythonImportName = "litellm",
        cliBinaryName = "litellm",
        purposeBn = "ইউনিফায়েড LLM প্রক্সি (নেটিভভাবে LxrProviderGateway কাজ করছে)",
        termuxInstallCommand = "pkg install -y python && pip install litellm",
        minPythonVersion = "3.9+",
        estimatedDiskMb = 220,
        estimatedRamMb = 512,
        supportsDirectTermuxArm64 = true,
        requiresExternalBackendRecommended = false,
        nativeBuildDeps = listOf("python", "aiohttp", "tiktoken (rust optional)"),
        arm64CompatibilityNoteBn = "ARM64 Termux-এ SDK মোডে চলে; প্রক্সি সার্ভারের জন্য অতিরিক্ত RAM প্রয়োজন।",
      ),
      TermuxFrameworkDescriptor(
        id = "aider",
        displayName = "Aider",
        pythonImportName = "aider",
        cliBinaryName = "aider",
        purposeBn = "গিট-ভিত্তিক AI পেয়ার প্রোগ্রামিং CLI",
        termuxInstallCommand = "pkg install -y python git clang && pip install aider-chat",
        minPythonVersion = "3.10+",
        estimatedDiskMb = 420,
        estimatedRamMb = 768,
        supportsDirectTermuxArm64 = true,
        requiresExternalBackendRecommended = false,
        nativeBuildDeps = listOf("python", "git", "tree-sitter C compiler (clang)"),
        arm64CompatibilityNoteBn = "Termux-এ git ও clang থাকলে ARM64-এ ইনস্টল ও CLI চালানো সম্ভব।",
      ),
      TermuxFrameworkDescriptor(
        id = "openhands",
        displayName = "OpenHands",
        pythonImportName = "openhands",
        cliBinaryName = "openhands",
        purposeBn = "স্যান্ডবক্সড সফটওয়্যার ইঞ্জিনিয়ারিং এজেন্ট",
        termuxInstallCommand = "Remote Linux/Docker host recommended; OpenHands requires Docker sandbox unavailable in standard Termux.",
        minPythonVersion = "3.12+",
        estimatedDiskMb = 2500,
        estimatedRamMb = 4096,
        supportsDirectTermuxArm64 = false,
        requiresExternalBackendRecommended = true,
        nativeBuildDeps = listOf("docker daemon", "Linux cgroups/namespaces", "Node.js + Poetry"),
        arm64CompatibilityNoteBn = "Docker ও Linux cgroups ছাড়া স্ট্যান্ডার্ড Android ARM64 Termux-এ চলে না — বহিরাগত সার্ভার প্রয়োজন।",
      ),
      TermuxFrameworkDescriptor(
        id = "haystack",
        displayName = "Haystack",
        pythonImportName = "haystack",
        cliBinaryName = "python3",
        purposeBn = "RAG ও ডকুমেন্ট পাইপলাইন ফ্রেমওয়ার্ক",
        termuxInstallCommand = "pkg install -y python && pip install haystack-ai",
        minPythonVersion = "3.9+",
        estimatedDiskMb = 300,
        estimatedRamMb = 768,
        supportsDirectTermuxArm64 = true,
        requiresExternalBackendRecommended = false,
        nativeBuildDeps = listOf("python", "numpy (Termux tur-repo wheel recommended)"),
        arm64CompatibilityNoteBn = "হালকা পাইপলাইন ARM64 Termux-এ চলে; লোকাল এম্বেডিং মডেল বড় হলে ভারী হয়।",
      ),
      TermuxFrameworkDescriptor(
        id = "llamaindex",
        displayName = "LlamaIndex",
        pythonImportName = "llama_index",
        cliBinaryName = "python3",
        purposeBn = "ডেটা কানেক্টর ও নলেজ ইনডেক্সিং",
        termuxInstallCommand = "pkg install -y python && pip install llama-index-core",
        minPythonVersion = "3.9+",
        estimatedDiskMb = 320,
        estimatedRamMb = 768,
        supportsDirectTermuxArm64 = true,
        requiresExternalBackendRecommended = false,
        nativeBuildDeps = listOf("python", "numpy/scipy via Termux pkg if needed"),
        arm64CompatibilityNoteBn = "`llama-index-core` ARM64 Termux-এ হালকা ও সহজে চলে।",
      ),
      TermuxFrameworkDescriptor(
        id = "semgrep",
        displayName = "Semgrep",
        pythonImportName = "semgrep",
        cliBinaryName = "semgrep",
        purposeBn = "ডিপ AST সিকিউরিটি ও প্যাটার্ন স্ট্যাটিক অ্যানালাইজার",
        termuxInstallCommand = "pkg install -y python proot-distro (Debian/Ubuntu ARM64 proot recommended for Semgrep binary)",
        minPythonVersion = "3.9+",
        estimatedDiskMb = 500,
        estimatedRamMb = 1024,
        supportsDirectTermuxArm64 = false,
        requiresExternalBackendRecommended = true,
        nativeBuildDeps = listOf("OCaml/glibc semgrep-core binary (Bionic libc incompatibility in plain Termux)"),
        arm64CompatibilityNoteBn = "Semgrep-এর OCaml `semgrep-core` বাইনারি glibc-নির্ভর হওয়ায় সাধারণ Termux Bionic-এ চলে না; proot-distro বা বাইরের সার্ভার দরকার।",
      ),
    )

  fun isTermuxInstalled(ctx: Context): Boolean =
    runCatching {
      @Suppress("DEPRECATION")
      ctx.packageManager.getPackageInfo("com.termux", 0)
      true
    }.getOrDefault(false)

  fun probeFramework(
    ctx: Context,
    frameworkId: String,
    customMarkerPaths: List<File> = emptyList(),
    performCheck: Boolean = true,
    termuxInstalledOverride: Boolean? = null,
  ): TermuxFrameworkProbeResult? {
    val desc = FRAMEWORKS.firstOrNull { it.id.equals(frameworkId.trim(), ignoreCase = true) } ?: return null
    val externalUrl = runCatching { Store.openAiCompatBaseUrl(ctx) }.getOrDefault("").isNotBlank()

    if (!performCheck) {
      return TermuxFrameworkProbeResult(
        id = desc.id,
        displayName = desc.displayName,
        status = TermuxFrameworkStatus.NOT_CHECKED,
        termuxAppInstalled = false,
        runtimeMarkerDetected = false,
        isAvailableNow = false,
        arm64Compatible = desc.supportsDirectTermuxArm64,
        diskRequiredMb = desc.estimatedDiskMb,
        ramRequiredMb = desc.estimatedRamMb,
        minPythonVersion = desc.minPythonVersion,
        externalBackendUrlConfigured = externalUrl,
        nativeDepsSummary = desc.nativeBuildDeps.joinToString(", "),
        arm64CompatibilityNoteBn = desc.arm64CompatibilityNoteBn,
        statusBn = TermuxFrameworkStatus.NOT_CHECKED.labelBn,
        installCommand = desc.termuxInstallCommand,
      )
    }

    val termuxInstalled = termuxInstalledOverride ?: isTermuxInstalled(ctx)
    val baseToolDir = File(ctx.filesDir, "termux_tools")
    val installedMarker = File(baseToolDir, "${desc.id}.installed")
    val testedMarker = File(baseToolDir, "${desc.id}.tested")
    val workingMarker = File(baseToolDir, "${desc.id}.working")
    val defaultBinary = File("/data/data/com.termux/files/usr/bin/${desc.cliBinaryName}")

    val extraMarkers = customMarkerPaths.filter { runCatching { it.exists() }.getOrDefault(false) }
    val workingDetected =
      runCatching { workingMarker.exists() }.getOrDefault(false) ||
        extraMarkers.any { it.name.endsWith(".working") }
    val testedDetected =
      runCatching { testedMarker.exists() }.getOrDefault(false) ||
        extraMarkers.any { it.name.endsWith(".tested") }
    val executableDetected =
      (desc.cliBinaryName != "python3" && runCatching { defaultBinary.exists() && defaultBinary.canExecute() }.getOrDefault(false)) ||
        extraMarkers.any { it.canExecute() && !it.name.endsWith(".installed") }
    val installedDetected =
      runCatching { installedMarker.exists() }.getOrDefault(false) ||
        extraMarkers.any { it.name.endsWith(".installed") || it.isFile }

    val markerDetected = workingDetected || testedDetected || executableDetected || installedDetected

    val resolvedStatus =
      when {
        !desc.supportsDirectTermuxArm64 && !markerDetected && !externalUrl ->
          if (!termuxInstalled) TermuxFrameworkStatus.UNAVAILABLE else TermuxFrameworkStatus.UNSUPPORTED
        !termuxInstalled && !markerDetected -> TermuxFrameworkStatus.UNAVAILABLE
        termuxInstalled && workingDetected -> TermuxFrameworkStatus.WORKING
        termuxInstalled && testedDetected -> TermuxFrameworkStatus.TESTED
        termuxInstalled && executableDetected -> TermuxFrameworkStatus.EXECUTABLE
        termuxInstalled && installedDetected -> TermuxFrameworkStatus.INSTALLED
        !termuxInstalled && markerDetected -> TermuxFrameworkStatus.UNAVAILABLE
        else -> TermuxFrameworkStatus.NOT_INSTALLED
      }

    val availableNow =
      resolvedStatus == TermuxFrameworkStatus.WORKING ||
        resolvedStatus == TermuxFrameworkStatus.TESTED ||
        resolvedStatus == TermuxFrameworkStatus.EXECUTABLE

    val statusBn =
      when (resolvedStatus) {
        TermuxFrameworkStatus.NOT_CHECKED -> TermuxFrameworkStatus.NOT_CHECKED.labelBn
        TermuxFrameworkStatus.UNAVAILABLE ->
          "UNAVAILABLE — com.termux ইনস্টল বা অ্যাক্সেসযোগ্য নয়; নেটিভ LXR Kotlin ইঞ্জিন চালু আছে (${desc.arm64CompatibilityNoteBn})"
        TermuxFrameworkStatus.UNSUPPORTED ->
          "UNSUPPORTED — ${desc.displayName} স্ট্যান্ডার্ড Android ARM64 Termux-এ সরাসরি সমর্থিত নয়; ${desc.arm64CompatibilityNoteBn}"
        TermuxFrameworkStatus.NOT_INSTALLED ->
          "NOT_INSTALLED — Termux আছে, তবে '${desc.displayName}' ইনস্টল করা নেই (কমান্ড: ${desc.termuxInstallCommand})"
        TermuxFrameworkStatus.INSTALLED ->
          "INSTALLED — '${desc.displayName}' মার্কার পাওয়া গেছে, তবে বাইনারি/সেল্ফ-টেস্ট যাচাই বাকি।"
        TermuxFrameworkStatus.EXECUTABLE ->
          "EXECUTABLE — '${desc.cliBinaryName}' বাইনারি চালানোর জন্য প্রস্তুত (সেল্ফ-টেস্ট বাকি)।"
        TermuxFrameworkStatus.TESTED ->
          "TESTED — '${desc.displayName}' সেল্ফ-টেস্ট সম্পন্ন হয়েছে ✓"
        TermuxFrameworkStatus.WORKING ->
          "WORKING — '${desc.displayName}' Termux-এ সম্পূর্ণ সক্রিয় ও যাচাইকৃত ✓"
      }

    return TermuxFrameworkProbeResult(
      id = desc.id,
      displayName = desc.displayName,
      status = resolvedStatus,
      termuxAppInstalled = termuxInstalled,
      runtimeMarkerDetected = markerDetected,
      isAvailableNow = availableNow,
      arm64Compatible = desc.supportsDirectTermuxArm64,
      diskRequiredMb = desc.estimatedDiskMb,
      ramRequiredMb = desc.estimatedRamMb,
      minPythonVersion = desc.minPythonVersion,
      externalBackendUrlConfigured = externalUrl,
      nativeDepsSummary = desc.nativeBuildDeps.joinToString(", "),
      arm64CompatibilityNoteBn = desc.arm64CompatibilityNoteBn,
      statusBn = statusBn,
      installCommand = desc.termuxInstallCommand,
    )
  }

  fun probeAll(
    ctx: Context,
    performCheck: Boolean = true,
    termuxInstalledOverride: Boolean? = null,
  ): List<TermuxFrameworkProbeResult> =
    FRAMEWORKS.mapNotNull {
      probeFramework(
        ctx = ctx,
        frameworkId = it.id,
        performCheck = performCheck,
        termuxInstalledOverride = termuxInstalledOverride,
      )
    }

  /**
   * Honest Termux execution bridge: validates input & prerequisite status first, never invents
   * output when Termux/Accessibility/framework runtime is unavailable, and forwards verified
   * safe commands through RiyaAccessibilityService Termux UI automation.
   */
  fun executeViaTermux(
    ctx: Context,
    frameworkId: String,
    commandArgs: String,
    probeOverride: TermuxFrameworkProbeResult? = null,
  ): TermuxExecutionOutcome {
    val cleanId = frameworkId.trim().lowercase(Locale.ROOT)
    val desc =
      FRAMEWORKS.firstOrNull { it.id == cleanId }
        ?: return TermuxExecutionOutcome(
          frameworkId = cleanId,
          executed = false,
          status = TermuxFrameworkStatus.UNAVAILABLE,
          messageBn = "অজানা ফ্রেমওয়ার্ক '$frameworkId' — কেবল নিবন্ধিত ৮টি ফ্রেমওয়ার্ক (${FRAMEWORKS.joinToString { it.id }}) সমর্থিত।",
        )

    val sanitizedArgs = commandArgs.trim()
    if (sanitizedArgs.isBlank()) {
      return TermuxExecutionOutcome(
        frameworkId = desc.id,
        executed = false,
        status = TermuxFrameworkStatus.NOT_CHECKED,
        messageBn = "Termux-এ চালানোর মতো কোনো আর্গুমেন্ট বা কমান্ড দেওয়া হয়নি বস।",
      )
    }
    if (UNSAFE_SHELL_REGEX.containsMatchIn(sanitizedArgs)) {
      return TermuxExecutionOutcome(
        frameworkId = desc.id,
        executed = false,
        status = TermuxFrameworkStatus.UNAVAILABLE,
        messageBn = "সিকিউরিটি গার্ড: অনিরাপদ শেল মেটাক্যারেক্টার (`;`, `&&`, `|`, `` ` ``, `$(`) ব্লক করা হয়েছে।",
      )
    }

    val probe = probeOverride ?: probeFramework(ctx, desc.id)!!
    if (!probe.isAvailableNow) {
      return TermuxExecutionOutcome(
        frameworkId = desc.id,
        executed = false,
        status = probe.status,
        messageBn =
          "${desc.displayName} এখন চালানো যাবে না — অবস্থা: ${probe.status.name}। ${probe.statusBn}",
      )
    }

    val acc = RiyaAccessibilityService.instance
      ?: return TermuxExecutionOutcome(
        frameworkId = desc.id,
        executed = false,
        status = TermuxFrameworkStatus.UNAVAILABLE,
        messageBn =
          "${desc.displayName} এর অবস্থা ${probe.status.name}, কিন্তু Termux UI অটোমেশনের জন্য Accessibility সার্ভিস চালু নেই।",
      )

    val fullCommand =
      if (desc.cliBinaryName == "python3") "python3 -m ${desc.pythonImportName} $sanitizedArgs"
      else "${desc.cliBinaryName} $sanitizedArgs"

    val uiOut = runCatching { acc.executeTermuxCommandViaUi(ctx, fullCommand) }
      .getOrElse { t ->
        return TermuxExecutionOutcome(
          frameworkId = desc.id,
          executed = false,
          status = TermuxFrameworkStatus.UNAVAILABLE,
          messageBn = "Termux UI এক্সিকিউশন ত্রুটি: ${LxrSecretRedactor.redactSecrets(t.message.orEmpty())}",
        )
      }

    val succeeded = !uiOut.contains("নেই") && !uiOut.contains("ব্যর্থ") && !uiOut.contains("ত্রুটি")
    return TermuxExecutionOutcome(
      frameworkId = desc.id,
      executed = succeeded,
      status = if (succeeded) TermuxFrameworkStatus.WORKING else probe.status,
      exitCode = if (succeeded) 0 else null,
      messageBn = uiOut,
      rawOutput = LxrSecretRedactor.redactSecrets(uiOut),
    )
  }

  fun formatAllStatusesBn(ctx: Context): String {
    val probes = probeAll(ctx)
    return buildString {
      appendLine("🛠️ Termux ৮টি ফ্রেমওয়ার্ক ও ARM64 সামঞ্জস্য রিপোর্ট:")
      probes.forEachIndexed { idx, p ->
        appendLine(
          "${idx + 1}. ${p.displayName} [${p.status.name}] — Python ${p.minPythonVersion}, ~${p.diskRequiredMb}MB Disk, ~${p.ramRequiredMb}MB RAM, ARM64 Direct=${if (p.arm64Compatible) "Yes" else "External/Proot Recommended"}",
        )
        appendLine("   অবস্থা: ${p.statusBn}")
      }
    }.trim()
  }
}
