package com.example.service

import android.content.Context
import com.example.data.Store
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LxrProviderStatus(
  val id: String,
  val displayName: String,
  val tierLabel: String,
  val isConfigured: Boolean,
  val detailBn: String,
)

data class LxrUsageSnapshot(
  val llmTokensUsed: Long? = null,
  val videoCreditsUsed: Long? = null,
  val renderTimeSec: Double = 0.0,
  val storageBytesUsed: Long = 0L,
  val lastUpdatedDhaka: String = "",
) {
  fun llmTokensLabelBn(): String =
    llmTokensUsed?.let { "$it tokens" } ?: "Unknown / Not reported"

  fun videoCreditsLabelBn(): String =
    videoCreditsUsed?.let { "$it credits" } ?: "0 (On-device Free / Not reported)"

  fun storageLabelBn(): String {
    if (storageBytesUsed <= 0L) return "0 KB"
    val kb = storageBytesUsed / 1024.0
    return if (kb >= 1024.0) String.format(Locale.US, "%.2f MB", kb / 1024.0)
    else String.format(Locale.US, "%.1f KB", kb)
  }
}

data class VideoToSeoHandoff(
  val projectTitle: String,
  val scriptSummary: String,
  val actualDurationSec: Int,
  val aspectRatioLabel: String,
  val sceneHeadlines: List<String>,
  val mp4Path: String,
  val publicFolderLabel: String,
  val createdAtDhaka: String,
)

object LxrProviderRegistry {
  fun inspectProviders(context: Context): List<LxrProviderStatus> {
    val geminiConfigured = Store.apiKey(context).isNotBlank()
    val pplxConfigured = Store.perplexityApiKey(context).isNotBlank()
    val youConfigured = Store.youApiKey(context).isNotBlank()
    val compatUrl = Store.openAiCompatBaseUrl(context)
    val compatModel = Store.openAiCompatModel(context)
    val compatConfigured = compatUrl.startsWith("https://", ignoreCase = true) && compatModel.isNotBlank()
    val gwHealth = LxrProviderGateway.healthSummaryBn()
    return listOf(
      LxrProviderStatus(
        id = "gemini_live",
        displayName = "Gemini Live & 2.5 Pro/Flash Gateway",
        tierLabel = "Gemini Official",
        isConfigured = geminiConfigured,
        detailBn =
          if (geminiConfigured) "API Key সক্রিয় — ভয়েস, স্ক্রিপ্ট ও ক্রিটিক্যাল গেটওয়ে চালু ($gwHealth)"
          else "API Key সেট করা নেই (Menu থেকে দিলে চালু হবে)",
      ),
      LxrProviderStatus(
        id = "duckduckgo_wiki",
        displayName = "Web Search (DuckDuckGo + Wikipedia)",
        tierLabel = "Free / No-Key",
        isConfigured = true,
        detailBn = "ফ্রি ওপেন ওয়েব রিসার্চ (কোনো Key ছাড়াই সক্রিয়)",
      ),
      LxrProviderStatus(
        id = "openmontage_local",
        displayName = "OpenMontage + Android MediaCodec",
        tierLabel = "On-Device Free",
        isConfigured = true,
        detailBn = "লোকাল ৩ডি অ্যানিমেশন, H.264 MP4 ও .SRT/.WAV রেন্ডারিং প্রস্তুত",
      ),
      LxrProviderStatus(
        id = "perplexity_sonar",
        displayName = "Perplexity Sonar",
        tierLabel = "Optional BYO Key",
        isConfigured = pplxConfigured,
        detailBn =
          if (pplxConfigured) "কনফিগার করা আছে ✓"
          else "কনফিগার করা নেই (ঐচ্ছিক API Key)",
      ),
      LxrProviderStatus(
        id = "you_search",
        displayName = "You.com Search",
        tierLabel = "Optional BYO Key",
        isConfigured = youConfigured,
        detailBn =
          if (youConfigured) "কনফিগার করা আছে ✓"
          else "কনফিগার করা নেই (ঐচ্ছিক API Key)",
      ),
      LxrProviderStatus(
        id = "openai_compat_byo",
        displayName = "OpenAI-Compatible BYO Gateway",
        tierLabel = "Optional BYO Endpoint",
        isConfigured = compatConfigured,
        detailBn =
          if (compatConfigured) "কনফিগার করা আছে ($compatModel) ✓"
          else "কনফিগার করা নেই (ঐচ্ছিক OpenAI-Compatible Endpoint)",
      ),
    )
  }
}

object LxrSecretRedactor {
  private val PATTERNS =
    listOf(
      Regex("AIza[A-Za-z0-9_\\-]{10,}"),
      Regex("github_pat_[A-Za-z0-9_]{12,}"),
      Regex("ghp_[A-Za-z0-9]{12,}"),
      Regex("pplx-[A-Za-z0-9_\\-]{8,}"),
      Regex("sk-[A-Za-z0-9_\\-]{10,}"),
      Regex("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]{10,}"),
      Regex("(?i)(key=)[^\\s&\"']{8,}"),
    )

  fun redactSecrets(raw: String): String {
    if (raw.isEmpty()) return raw
    var out = raw
    for (p in PATTERNS) {
      out =
        p.replace(out) { m ->
          if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) {
            "${m.groupValues[1]}[REDACTED]"
          } else {
            "[REDACTED]"
          }
        }
    }
    return out
  }
}

object LxrUsageLedger {
  private val _snapshot = MutableStateFlow(LxrUsageSnapshot())
  val snapshot: StateFlow<LxrUsageSnapshot> = _snapshot.asStateFlow()

  private fun dhakaNowLabel(): String =
    SimpleDateFormat("hh:mm:ss a 'BST'", Locale.US)
      .apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }
      .format(Date())

  fun recordRenderMetrics(
    renderDurationSec: Double,
    videoCreditsUsed: Long? = null,
    outputBytes: Long = 0L,
  ) {
    val cur = _snapshot.value
    _snapshot.value =
      cur.copy(
        videoCreditsUsed = videoCreditsUsed ?: cur.videoCreditsUsed,
        renderTimeSec = (cur.renderTimeSec + renderDurationSec.coerceAtLeast(0.0)),
        storageBytesUsed = if (outputBytes > 0L) outputBytes else cur.storageBytesUsed,
        lastUpdatedDhaka = dhakaNowLabel(),
      )
  }

  fun recordTokenUsage(tokensDelta: Long?) {
    if (tokensDelta == null || tokensDelta <= 0L) return
    val cur = _snapshot.value
    val next = (cur.llmTokensUsed ?: 0L) + tokensDelta
    _snapshot.value =
      cur.copy(
        llmTokensUsed = next,
        lastUpdatedDhaka = dhakaNowLabel(),
      )
  }

  fun refreshStorageUsage(context: Context) {
    val total =
      runCatching {
        val root = LxrDownloadStorage.lxrPublicRoot(context)
        computeDirSize(root)
      }.getOrDefault(0L)
    _snapshot.value =
      _snapshot.value.copy(
        storageBytesUsed = total,
        lastUpdatedDhaka = dhakaNowLabel(),
      )
  }

  fun resetForTest() {
    _snapshot.value = LxrUsageSnapshot()
  }

  private fun computeDirSize(dir: File): Long {
    if (!dir.exists()) return 0L
    if (dir.isFile) return dir.length()
    var sum = 0L
    dir.listFiles()?.forEach { child ->
      sum += if (child.isDirectory) computeDirSize(child) else child.length()
    }
    return sum
  }
}

object LxrStudioHandoff {
  private val _latestVideoHandoff = MutableStateFlow<VideoToSeoHandoff?>(null)
  val latestVideoHandoff: StateFlow<VideoToSeoHandoff?> = _latestVideoHandoff.asStateFlow()

  private fun dhakaStamp(): String =
    SimpleDateFormat("yyyy-MM-dd hh:mm a 'BST'", Locale.US)
      .apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }
      .format(Date())

  fun publishVideoHandoff(
    projectTitle: String,
    scriptSummary: String,
    actualDurationSec: Int,
    aspectRatioLabel: String,
    sceneHeadlines: List<String>,
    mp4Path: String,
    publicFolderLabel: String,
  ) {
    _latestVideoHandoff.value =
      VideoToSeoHandoff(
        projectTitle = projectTitle.trim().ifBlank { "LXR Studio Video" },
        scriptSummary = scriptSummary.trim().take(500),
        actualDurationSec = actualDurationSec.coerceAtLeast(1),
        aspectRatioLabel = aspectRatioLabel,
        sceneHeadlines = sceneHeadlines.filter { it.isNotBlank() }.take(8),
        mp4Path = mp4Path,
        publicFolderLabel = publicFolderLabel,
        createdAtDhaka = dhakaStamp(),
      )
  }

  fun clear() {
    _latestVideoHandoff.value = null
  }
}
