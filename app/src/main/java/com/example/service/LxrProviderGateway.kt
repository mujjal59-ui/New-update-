package com.example.service

import android.content.Context
import com.example.data.Store
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

enum class ProviderCircuitState {
  CLOSED,
  OPEN,
  HALF_OPEN,
}

enum class GatewayFailureKind(val retryableSameRoute: Boolean, val shouldFailover: Boolean, val labelBn: String) {
  AUTH_INVALID(false, true, "API Key অনুমোদিত নয় বা সীমিত (401/403)"),
  RATE_LIMITED(true, true, "রেট লিমিট বা কোটা পূর্ণ (429)"),
  MODEL_NOT_FOUND(false, true, "মডেল পাওয়া যায়নি (404)"),
  SERVER_ERROR(true, true, "সার্ভার সাময়িক ব্যস্ত (5xx)"),
  TIMEOUT_OR_NETWORK(true, true, "নেটওয়ার্ক টাইমআউট বা সংযোগ বিচ্ছিন্ন"),
  BAD_RESPONSE(false, true, "অসম্পূর্ণ বা অগ্রহণযোগ্য রেসপন্স"),
  CIRCUIT_OPEN(false, true, "সার্কিট ব্রেকার কুলডাউনে আছে"),
}

data class ProviderHealthMetrics(
  val providerId: String,
  val circuitState: ProviderCircuitState = ProviderCircuitState.CLOSED,
  val successCount: Int = 0,
  val failureCount: Int = 0,
  val consecutiveFailures: Int = 0,
  val avgLatencyMs: Long = 0L,
  val lastErrorBn: String? = null,
  val lastUpdatedMs: Long = 0L,
)

data class GatewayHttpResponse(
  val code: Int,
  val body: String,
  val errorMessage: String? = null,
  val exception: Throwable? = null,
)

data class GatewayRouteCandidate(
  val providerId: String,
  val providerKind: String, // "gemini", "perplexity", "openai_compat"
  val modelId: String,
  val endpointUrl: String,
  val apiKey: String,
  val priorityTier: Int,
)

data class GatewayCallResult(
  val succeeded: Boolean,
  val providerId: String,
  val modelId: String,
  val text: String,
  val formattedBn: String,
  val attempts: Int,
  val tokenUsage: Long? = null,
  val failureSummaryBn: String? = null,
)

interface LxrGatewayTransport {
  fun executePost(
    url: String,
    headers: Map<String, String>,
    jsonBody: String,
  ): GatewayHttpResponse
}

class OkHttpGatewayTransport(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(12, TimeUnit.SECONDS)
      .readTimeout(28, TimeUnit.SECONDS)
      .writeTimeout(12, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build(),
) : LxrGatewayTransport {
  override fun executePost(
    url: String,
    headers: Map<String, String>,
    jsonBody: String,
  ): GatewayHttpResponse {
    return try {
      val builder =
        Request.Builder()
          .url(url)
          .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
      for ((k, v) in headers) {
        builder.header(k, v)
      }
      client.newCall(builder.build()).execute().use { resp ->
        GatewayHttpResponse(
          code = resp.code,
          body = resp.body?.string().orEmpty(),
        )
      }
    } catch (t: Throwable) {
      GatewayHttpResponse(
        code = -1,
        body = "",
        errorMessage = LxrSecretRedactor.redactSecrets(t.message.orEmpty()),
        exception = t,
      )
    }
  }
}

object LxrModelRegistry {
  val VERIFIED_GEMINI_CRITICAL_MODELS =
    listOf(
      "gemini-2.5-pro",
      "gemini-2.5-flash",
      "gemini-2.5-flash-lite",
      "gemini-flash-latest",
    )

  fun normalizeVerifiedGeminiModel(preferred: String): String {
    val clean = preferred.trim()
    return when {
      clean.isBlank() -> "gemini-2.5-pro"
      clean.contains("2.0-flash", ignoreCase = true) -> "gemini-2.5-flash"
      clean.contains("1.5", ignoreCase = true) -> "gemini-2.5-flash"
      clean in VERIFIED_GEMINI_CRITICAL_MODELS -> clean
      clean.startsWith("gemini-2.5", ignoreCase = true) || clean.startsWith("gemini-flash", ignoreCase = true) -> clean
      else -> "gemini-2.5-pro"
    }
  }

  fun orderedGeminiModels(preferred: String): List<String> {
    val first = normalizeVerifiedGeminiModel(preferred)
    return (listOf(first) + VERIFIED_GEMINI_CRITICAL_MODELS).distinct()
  }
}

object FailureClassifier {
  fun classify(resp: GatewayHttpResponse): GatewayFailureKind {
    val ex = resp.exception
    if (ex != null) {
      return when (ex) {
        is SocketTimeoutException, is IOException -> GatewayFailureKind.TIMEOUT_OR_NETWORK
        else -> GatewayFailureKind.BAD_RESPONSE
      }
    }
    return when (resp.code) {
      401, 403 -> GatewayFailureKind.AUTH_INVALID
      429 -> GatewayFailureKind.RATE_LIMITED
      404 -> GatewayFailureKind.MODEL_NOT_FOUND
      in 500..599 -> GatewayFailureKind.SERVER_ERROR
      in 200..299 -> GatewayFailureKind.BAD_RESPONSE
      else -> GatewayFailureKind.TIMEOUT_OR_NETWORK
    }
  }
}

class ProviderCircuitBreaker(
  private val failureThreshold: Int = 3,
  private val cooldownMs: Long = 30_000L,
) {
  private data class Entry(
    var state: ProviderCircuitState = ProviderCircuitState.CLOSED,
    var consecutiveFailures: Int = 0,
    var openedAtMs: Long = 0L,
  )

  private val entries = ConcurrentHashMap<String, Entry>()

  @Synchronized
  fun allowRequest(providerId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
    val entry = entries.getOrPut(providerId) { Entry() }
    return when (entry.state) {
      ProviderCircuitState.CLOSED -> true
      ProviderCircuitState.OPEN -> {
        if (nowMs - entry.openedAtMs >= cooldownMs) {
          entry.state = ProviderCircuitState.HALF_OPEN
          true
        } else {
          false
        }
      }
      ProviderCircuitState.HALF_OPEN -> true
    }
  }

  @Synchronized
  fun recordSuccess(providerId: String) {
    val entry = entries.getOrPut(providerId) { Entry() }
    entry.state = ProviderCircuitState.CLOSED
    entry.consecutiveFailures = 0
    entry.openedAtMs = 0L
  }

  @Synchronized
  fun recordFailure(providerId: String, nowMs: Long = System.currentTimeMillis()) {
    val entry = entries.getOrPut(providerId) { Entry() }
    entry.consecutiveFailures += 1
    if (entry.state == ProviderCircuitState.HALF_OPEN || entry.consecutiveFailures >= failureThreshold) {
      entry.state = ProviderCircuitState.OPEN
      entry.openedAtMs = nowMs
    }
  }

  @Synchronized
  fun stateOf(providerId: String, nowMs: Long = System.currentTimeMillis()): ProviderCircuitState {
    val entry = entries[providerId] ?: return ProviderCircuitState.CLOSED
    if (entry.state == ProviderCircuitState.OPEN && nowMs - entry.openedAtMs >= cooldownMs) {
      return ProviderCircuitState.HALF_OPEN
    }
    return entry.state
  }

  @Synchronized
  fun consecutiveFailuresOf(providerId: String): Int = entries[providerId]?.consecutiveFailures ?: 0

  @Synchronized
  fun reset() {
    entries.clear()
  }
}

class ProviderHealthMonitor(
  private val breaker: ProviderCircuitBreaker,
) {
  private data class Stats(
    var successes: Int = 0,
    var failures: Int = 0,
    var totalLatencyMs: Long = 0L,
    var lastErrorBn: String? = null,
    var lastUpdatedMs: Long = 0L,
  )

  private val statsMap = ConcurrentHashMap<String, Stats>()

  @Synchronized
  fun onSuccess(providerId: String, latencyMs: Long, nowMs: Long = System.currentTimeMillis()) {
    breaker.recordSuccess(providerId)
    val s = statsMap.getOrPut(providerId) { Stats() }
    s.successes += 1
    s.totalLatencyMs += latencyMs.coerceAtLeast(1L)
    s.lastErrorBn = null
    s.lastUpdatedMs = nowMs
  }

  @Synchronized
  fun onFailure(providerId: String, redactedErrorBn: String, nowMs: Long = System.currentTimeMillis()) {
    breaker.recordFailure(providerId, nowMs)
    val s = statsMap.getOrPut(providerId) { Stats() }
    s.failures += 1
    s.lastErrorBn = LxrSecretRedactor.redactSecrets(redactedErrorBn).take(220)
    s.lastUpdatedMs = nowMs
  }

  @Synchronized
  fun snapshotFor(providerId: String, nowMs: Long = System.currentTimeMillis()): ProviderHealthMetrics {
    val s = statsMap[providerId]
    val avg = if (s != null && s.successes > 0) s.totalLatencyMs / s.successes else 0L
    return ProviderHealthMetrics(
      providerId = providerId,
      circuitState = breaker.stateOf(providerId, nowMs),
      successCount = s?.successes ?: 0,
      failureCount = s?.failures ?: 0,
      consecutiveFailures = breaker.consecutiveFailuresOf(providerId),
      avgLatencyMs = avg,
      lastErrorBn = s?.lastErrorBn,
      lastUpdatedMs = s?.lastUpdatedMs ?: 0L,
    )
  }

  @Synchronized
  fun allSnapshots(nowMs: Long = System.currentTimeMillis()): Map<String, ProviderHealthMetrics> =
    statsMap.keys.associateWith { snapshotFor(it, nowMs) }

  @Synchronized
  fun reset() {
    statsMap.clear()
    breaker.reset()
  }
}

object ExponentialBackoffRetry {
  fun delayMsForAttempt(
    attemptZeroBased: Int,
    baseDelayMs: Long = 350L,
    maxDelayMs: Long = 4_000L,
  ): Long {
    val shifted = baseDelayMs shl attemptZeroBased.coerceIn(0, 6)
    return shifted.coerceAtMost(maxDelayMs)
  }
}

class FreeFirstProviderRouter(
  private val breaker: ProviderCircuitBreaker,
  private val healthMonitor: ProviderHealthMonitor,
  private val transport: LxrGatewayTransport,
  private val sleeper: (Long) -> Unit = { ms -> if (ms > 0L) Thread.sleep(ms) },
  private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
  fun buildCandidates(
    geminiKey: String,
    preferredGeminiModel: String,
    perplexityKey: String = "",
    openAiCompatBaseUrl: String = "",
    openAiCompatApiKey: String = "",
    openAiCompatModel: String = "",
  ): List<GatewayRouteCandidate> {
    val out = mutableListOf<GatewayRouteCandidate>()
    val cleanGeminiKey = geminiKey.trim()
    if (cleanGeminiKey.isNotBlank()) {
      val models = LxrModelRegistry.orderedGeminiModels(preferredGeminiModel)
      models.forEachIndexed { idx, modelId ->
        val tier = if (modelId.contains("flash-lite") || modelId.contains("flash")) 0 else 1
        out.add(
          GatewayRouteCandidate(
            providerId = "gemini:$modelId",
            providerKind = "gemini",
            modelId = modelId,
            endpointUrl = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent",
            apiKey = cleanGeminiKey,
            priorityTier = idx.coerceAtMost(tier + idx),
          )
        )
      }
    }
    val cleanCompatUrl = openAiCompatBaseUrl.trim().trimEnd('/')
    val cleanCompatKey = openAiCompatApiKey.trim()
    val cleanCompatModel = openAiCompatModel.trim()
    if (cleanCompatUrl.startsWith("https://", ignoreCase = true) && cleanCompatModel.isNotBlank()) {
      out.add(
        GatewayRouteCandidate(
          providerId = "openai_compat:$cleanCompatModel",
          providerKind = "openai_compat",
          modelId = cleanCompatModel,
          endpointUrl = "$cleanCompatUrl/chat/completions",
          apiKey = cleanCompatKey,
          priorityTier = 10,
        )
      )
    }
    val cleanPplx = perplexityKey.trim()
    if (cleanPplx.isNotBlank()) {
      out.add(
        GatewayRouteCandidate(
          providerId = "perplexity:sonar",
          providerKind = "perplexity",
          modelId = "sonar",
          endpointUrl = "https://api.perplexity.ai/chat/completions",
          apiKey = cleanPplx,
          priorityTier = 12,
        )
      )
    }
    return out
  }

  fun execute(
    candidates: List<GatewayRouteCandidate>,
    promptBody: String,
    maxRetriesPerRoute: Int = 1,
  ): GatewayCallResult {
    if (candidates.isEmpty()) {
      return GatewayCallResult(
        succeeded = false,
        providerId = "none",
        modelId = "none",
        text = "",
        formattedBn = "এক্সট্রা এআই মডেল চালানোর জন্য Gemini API Key (অথবা কনফিগার করা প্রোভাইডার Key) প্রয়োজন বস।",
        attempts = 0,
        failureSummaryBn = "No configured AI provider key found",
      )
    }

    var totalAttempts = 0
    val diagnostics = mutableListOf<String>()

    for (candidate in candidates) {
      val now = clockMs()
      if (!breaker.allowRequest(candidate.providerId, now)) {
        diagnostics.add("${candidate.modelId}: সার্কিট ব্রেকার OPEN")
        continue
      }

      var retryCount = 0
      while (retryCount <= maxRetriesPerRoute) {
        totalAttempts += 1
        val startMs = clockMs()
        val (headers, jsonPayload) = buildRequestPayload(candidate, promptBody)
        val urlWithOptionalKey =
          if (candidate.providerKind == "gemini") {
            "${candidate.endpointUrl}?key=${candidate.apiKey}"
          } else {
            candidate.endpointUrl
          }
        val resp = transport.executePost(urlWithOptionalKey, headers, jsonPayload)
        val latency = (clockMs() - startMs).coerceAtLeast(1L)

        if (resp.code in 200..299 && resp.body.isNotBlank()) {
          val parsed = extractTextAndTokens(candidate.providerKind, resp.body)
          val txt = parsed.first
          val tokens = parsed.second
          if (txt.isNotBlank()) {
            healthMonitor.onSuccess(candidate.providerId, latency, clockMs())
            LxrUsageLedger.recordTokenUsage(tokens)
            return GatewayCallResult(
              succeeded = true,
              providerId = candidate.providerId,
              modelId = candidate.modelId,
              text = txt,
              formattedBn = "[${candidate.modelId} ক্রিটিক্যাল মডেল]:\n$txt",
              attempts = totalAttempts,
              tokenUsage = tokens,
            )
          }
        }

        val kind = FailureClassifier.classify(resp)
        val detail =
          LxrSecretRedactor.redactSecrets(
            resp.errorMessage?.takeIf { it.isNotBlank() }
              ?: extractRemoteErrorMessage(resp.body).takeIf { it.isNotBlank() }
              ?: "HTTP ${resp.code}",
          )
        val errLabel = "${candidate.modelId}: ${kind.labelBn} ($detail)"
        diagnostics.add(errLabel)
        healthMonitor.onFailure(candidate.providerId, errLabel, clockMs())

        if (kind.retryableSameRoute && retryCount < maxRetriesPerRoute) {
          sleeper(ExponentialBackoffRetry.delayMsForAttempt(retryCount))
          retryCount += 1
        } else {
          break
        }
      }
    }

    val safeSummary = LxrSecretRedactor.redactSecrets(diagnostics.distinct().take(4).joinToString(" | "))
    return GatewayCallResult(
      succeeded = false,
      providerId = candidates.first().providerId,
      modelId = candidates.first().modelId,
      text = "",
      formattedBn =
        "এক্সট্রা ক্রিটিক্যাল মডেল এই মুহূর্তে সাড়া দিতে পারেনি বস।\n• যাচাইকৃত ত্রুটি: $safeSummary",
      attempts = totalAttempts,
      failureSummaryBn = safeSummary,
    )
  }

  private fun buildRequestPayload(
    candidate: GatewayRouteCandidate,
    promptBody: String,
  ): Pair<Map<String, String>, String> {
    return when (candidate.providerKind) {
      "gemini" -> {
        val body =
          JSONObject()
            .put(
              "contents",
              JSONArray().put(
                JSONObject().put(
                  "parts",
                  JSONArray().put(JSONObject().put("text", promptBody)),
                )
              ),
            )
            .toString()
        mapOf("Content-Type" to "application/json; charset=utf-8") to body
      }
      else -> {
        val body =
          JSONObject()
            .put("model", candidate.modelId)
            .put(
              "messages",
              JSONArray().put(
                JSONObject()
                  .put("role", "user")
                  .put("content", promptBody),
              ),
            )
            .toString()
        val headers =
          buildMap {
            put("Content-Type", "application/json; charset=utf-8")
            if (candidate.apiKey.isNotBlank()) {
              put("Authorization", "Bearer ${candidate.apiKey}")
            }
          }
        headers to body
      }
    }
  }

  private fun extractTextAndTokens(providerKind: String, rawBody: String): Pair<String, Long?> {
    return try {
      val root = JSONObject(rawBody)
      if (providerKind == "gemini") {
        val parts =
          root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
          for (i in 0 until parts.length()) {
            sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
          }
        }
        val usage = root.optJSONObject("usageMetadata")?.optLong("totalTokenCount", 0L)?.takeIf { it > 0L }
        sb.toString().trim() to usage
      } else {
        val content =
          root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
            .trim()
        val usage = root.optJSONObject("usage")?.optLong("total_tokens", 0L)?.takeIf { it > 0L }
        content to usage
      }
    } catch (_: Throwable) {
      "" to null
    }
  }

  private fun extractRemoteErrorMessage(rawBody: String): String {
    if (rawBody.isBlank()) return ""
    return runCatching {
      val j = JSONObject(rawBody)
      j.optJSONObject("error")?.optString("message").orEmpty()
        .ifBlank { j.optString("message") }
        .ifBlank { j.optString("detail") }
    }.getOrDefault("")
  }
}

object LxrProviderGateway {
  val circuitBreaker = ProviderCircuitBreaker(failureThreshold = 3, cooldownMs = 30_000L)
  val healthMonitor = ProviderHealthMonitor(circuitBreaker)
  private val defaultTransport = OkHttpGatewayTransport()
  private val defaultRouter = FreeFirstProviderRouter(circuitBreaker, healthMonitor, defaultTransport)

  fun executeCriticalReasoning(
    ctx: Context,
    taskPrompt: String,
    screenContext: String = "",
    transport: LxrGatewayTransport? = null,
    sleeper: (Long) -> Unit = { ms -> if (ms > 0L) Thread.sleep(ms) },
  ): GatewayCallResult {
    val geminiKey = Store.apiKey(ctx).trim()
    val preferred = Store.extraCriticalModel(ctx)
    val pplxKey = Store.perplexityApiKey(ctx).trim()
    val compatUrl = Store.openAiCompatBaseUrl(ctx).trim()
    val compatKey = Store.openAiCompatApiKey(ctx).trim()
    val compatModel = Store.openAiCompatModel(ctx).trim()

    val router =
      if (transport == null) defaultRouter
      else FreeFirstProviderRouter(circuitBreaker, healthMonitor, transport, sleeper)

    val candidates =
      router.buildCandidates(
        geminiKey = geminiKey,
        preferredGeminiModel = preferred,
        perplexityKey = pplxKey,
        openAiCompatBaseUrl = compatUrl,
        openAiCompatApiKey = compatKey,
        openAiCompatModel = compatModel,
      )
    val promptBody =
      buildString {
        appendLine("তুমি LXR--AI-এর এক্সট্রা ক্রিটিক্যাল রিজনিং ও টাস্ক ইঞ্জিন। বসের কঠিন বা জটিল কাজের জন্য পরিষ্কার, সঠিক এবং কার্যকর বাংলা সমাধান ও ধাপ দাও।")
        if (screenContext.isNotBlank()) {
          appendLine("বর্তমান স্ক্রিন/সিস্টেম তথ্য: $screenContext")
        }
        appendLine("কাজ/প্রশ্ন: $taskPrompt")
      }
    return router.execute(candidates = candidates, promptBody = promptBody)
  }

  fun healthSummaryBn(): String {
    val snaps = healthMonitor.allSnapshots()
    if (snaps.isEmpty()) return "সব এআই গেটওয়ে রুট সতেজ ও CLOSED অবস্থায় আছে ✓"
    return snaps.values.joinToString(" | ") { m ->
      "${m.providerId}=${m.circuitState.name} (OK=${m.successCount}, Err=${m.failureCount}, ${m.avgLatencyMs}ms)"
    }
  }

  fun resetForTest() {
    healthMonitor.reset()
  }
}
