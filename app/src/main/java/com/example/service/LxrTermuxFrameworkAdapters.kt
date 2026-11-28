package com.example.service

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

data class TermuxFrameworkDescriptor(
  val id: String,
  val displayName: String,
  val pythonImportName: String,
  val cliBinaryName: String,
  val purposeBn: String,
  val termuxInstallCommand: String,
)

data class TermuxFrameworkProbeResult(
  val id: String,
  val displayName: String,
  val termuxAppInstalled: Boolean,
  val runtimeMarkerDetected: Boolean,
  val isAvailableNow: Boolean,
  val statusBn: String,
  val installCommand: String,
)

/**
 * Honest bridge/adapter registry for heavyweight external Python/CLI frameworks.
 * Checks actual Termux package presence and local workspace/runtime markers instead of
 * inflating APK size or fabricating execution output.
 */
object LxrTermuxFrameworkAdapters {
  val FRAMEWORKS: List<TermuxFrameworkDescriptor> =
    listOf(
      TermuxFrameworkDescriptor(
        id = "langgraph",
        displayName = "LangGraph",
        pythonImportName = "langgraph",
        cliBinaryName = "python3",
        purposeBn = "স্টেটফুল মাল্টি-এজেন্ট গ্রাফ ওয়ার্কফ্লো (অ্যান্ড্রয়েডে LxrAutonomousOrchestrator সমতুল্য নেটিভভাবে চালু আছে)",
        termuxInstallCommand = "pkg install -y python && pip install langgraph",
      ),
      TermuxFrameworkDescriptor(
        id = "crewai",
        displayName = "CrewAI",
        pythonImportName = "crewai",
        cliBinaryName = "crewai",
        purposeBn = "রোল-ভিত্তিক টিম এজেন্ট অটোমেশন",
        termuxInstallCommand = "pkg install -y python rust && pip install crewai",
      ),
      TermuxFrameworkDescriptor(
        id = "litellm",
        displayName = "LiteLLM",
        pythonImportName = "litellm",
        cliBinaryName = "litellm",
        purposeBn = "ইউনিফায়েড LLM প্রক্সি (নেটিভভাবে LxrProviderGateway কাজ করছে)",
        termuxInstallCommand = "pkg install -y python && pip install litellm",
      ),
      TermuxFrameworkDescriptor(
        id = "aider",
        displayName = "Aider",
        pythonImportName = "aider",
        cliBinaryName = "aider",
        purposeBn = "গিট-ভিত্তিক AI পেয়ার প্রোগ্রামিং CLI",
        termuxInstallCommand = "pkg install -y python git && pip install aider-chat",
      ),
      TermuxFrameworkDescriptor(
        id = "openhands",
        displayName = "OpenHands",
        pythonImportName = "openhands",
        cliBinaryName = "openhands",
        purposeBn = "স্যান্ডবক্সড সফটওয়্যার ইঞ্জিনিয়ারিং এজেন্ট",
        termuxInstallCommand = "pkg install -y python git && pip install openhands-ai",
      ),
      TermuxFrameworkDescriptor(
        id = "haystack",
        displayName = "Haystack",
        pythonImportName = "haystack",
        cliBinaryName = "python3",
        purposeBn = "RAG ও ডকুমেন্ট পাইপলাইন ফ্রেমওয়ার্ক",
        termuxInstallCommand = "pkg install -y python && pip install haystack-ai",
      ),
      TermuxFrameworkDescriptor(
        id = "llamaindex",
        displayName = "LlamaIndex",
        pythonImportName = "llama_index",
        cliBinaryName = "python3",
        purposeBn = "ডেটা কানেক্টর ও নলেজ ইনডেক্সিং",
        termuxInstallCommand = "pkg install -y python && pip install llama-index",
      ),
      TermuxFrameworkDescriptor(
        id = "semgrep",
        displayName = "Semgrep",
        pythonImportName = "semgrep",
        cliBinaryName = "semgrep",
        purposeBn = "ডিপ AST সিকিউরিটি ও প্যাটার্ন স্ট্যাটিক অ্যানালাইজার",
        termuxInstallCommand = "pkg install -y python && pip install semgrep",
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
  ): TermuxFrameworkProbeResult? {
    val desc = FRAMEWORKS.firstOrNull { it.id.equals(frameworkId.trim(), ignoreCase = true) } ?: return null
    val termuxInstalled = isTermuxInstalled(ctx)
    val defaultMarkers =
      listOf(
        File(ctx.filesDir, "termux_tools/${desc.id}.installed"),
        File("/data/data/com.termux/files/usr/bin/${desc.cliBinaryName}"),
      )
    val markerDetected =
      (customMarkerPaths + defaultMarkers).any { f ->
        runCatching { f.exists() && f.length() >= 0L }.getOrDefault(false) &&
          (f.name.endsWith(".installed") || desc.cliBinaryName != "python3" || f.name == "${desc.id}.installed")
      }
    val available = termuxInstalled && markerDetected
    val statusBn =
      when {
        !termuxInstalled -> "Termux ইনস্টল নেই — নেটিভ LXR Kotlin ইঞ্জিন ব্যবহার হচ্ছে; Termux ইনস্টল করলে CLI চালু করা যাবে।"
        available -> "Termux-এ সক্রিয় পাওয়া গেছে ✓"
        else -> "Termux আছে, কিন্তু '${desc.displayName}' এখনো ইনস্টল করা নেই (কমান্ড: ${desc.termuxInstallCommand})"
      }
    return TermuxFrameworkProbeResult(
      id = desc.id,
      displayName = desc.displayName,
      termuxAppInstalled = termuxInstalled,
      runtimeMarkerDetected = markerDetected,
      isAvailableNow = available,
      statusBn = statusBn,
      installCommand = desc.termuxInstallCommand,
    )
  }

  fun probeAll(ctx: Context): List<TermuxFrameworkProbeResult> =
    FRAMEWORKS.mapNotNull { probeFramework(ctx, it.id) }
}
