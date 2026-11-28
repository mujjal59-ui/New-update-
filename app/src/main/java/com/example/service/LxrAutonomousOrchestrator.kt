package com.example.service

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

enum class TaskComplexityTier(val labelBn: String) {
  FAST_SINGLE("একক দ্রুত রুট"),
  SPECIALIZED("বিশেষজ্ঞ এজেন্ট"),
  MULTI_AGENT_PARALLEL("সমান্তরাল মাল্টি-এজেন্ট"),
}

data class AutonomousTaskReport(
  val prompt: String,
  val tier: TaskComplexityTier,
  val selectedAgentIds: List<String>,
  val agentOutputs: List<AgentExecutionOutput>,
  val verificationGate: AgentExecutionOutput,
  val finalSummaryBn: String,
  val totalLatencyMs: Long,
)

/**
 * Central Autonomous Task Orchestrator.
 * Classifies task complexity, selects matching specialist agents from [LxrSpecialistRegistry],
 * executes independent agents concurrently under SupervisorJob with hard timeout, and validates
 * the merged output through [VerificationCritiqueGateAgent] + [DocumentationSummaryAgent].
 */
object LxrAutonomousOrchestrator {
  fun classifyComplexity(prompt: String): TaskComplexityTier {
    val clean = prompt.trim().lowercase(Locale.ROOT)
    val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
    val matchedDomains = countMatchedDomains(clean)
    return when {
      matchedDomains >= 2 || words.size >= 14 || clean.contains("এবং") || clean.contains(" and ") ->
        TaskComplexityTier.MULTI_AGENT_PARALLEL
      matchedDomains == 1 || words.size >= 5 ->
        TaskComplexityTier.SPECIALIZED
      else ->
        TaskComplexityTier.FAST_SINGLE
    }
  }

  fun selectAgents(prompt: String, tier: TaskComplexityTier = classifyComplexity(prompt)): List<LxrSpecialistAgent> {
    val lower = prompt.lowercase(Locale.ROOT)
    val scored =
      LxrSpecialistRegistry.ALL_AGENTS.mapNotNull { agent ->
        if (agent.id == "intent_planner" || agent.id == "verification_critique" || agent.id == "documentation_summary") {
          return@mapNotNull null
        }
        val hits = agent.keywords.count { kw -> matchesKeyword(lower, kw) }
        if (hits > 0) agent to hits else null
      }.sortedByDescending { it.second }.map { it.first }.toMutableList()

    if (ReasoningAndMathAgent.extractMathCandidate(prompt) != null) {
      LxrSpecialistRegistry.findById("reasoning_math")?.let { mathAgent ->
        if (scored.none { it.id == mathAgent.id }) scored.add(0, mathAgent)
      }
    }
    if (scored.isEmpty()) {
      LxrSpecialistRegistry.findById("extra_critical_reasoning")?.let { scored.add(it) }
    }
    return when (tier) {
      TaskComplexityTier.FAST_SINGLE -> scored.take(1)
      TaskComplexityTier.SPECIALIZED -> scored.take(2)
      TaskComplexityTier.MULTI_AGENT_PARALLEL -> {
        val planner = LxrSpecialistRegistry.findById("intent_planner")
        (listOfNotNull(planner) + scored).distinctBy { it.id }.take(4)
      }
    }
  }

  fun executeAutonomousTask(
    ctx: Context,
    prompt: String,
    screenContext: String = "",
    recordInMonitor: Boolean = true,
  ): AutonomousTaskReport {
    val startMs = System.currentTimeMillis()
    val cleanPrompt = prompt.trim()
    if (cleanPrompt.isBlank()) {
      val emptyGate =
        AgentExecutionOutput(
          agentId = "verification_critique",
          agentNameBn = "ভেরিফিকেশন গেট",
          succeeded = false,
          summaryBn = "কোনো কাজের নির্দেশনা দেওয়া হয়নি বস।",
        )
      return AutonomousTaskReport(
        prompt = "",
        tier = TaskComplexityTier.FAST_SINGLE,
        selectedAgentIds = emptyList(),
        agentOutputs = emptyList(),
        verificationGate = emptyGate,
        finalSummaryBn = "কোনো কাজের নির্দেশনা দেওয়া হয়নি বস।",
        totalLatencyMs = 0L,
      )
    }

    val tier = classifyComplexity(cleanPrompt)
    val agents = selectAgents(cleanPrompt, tier)

    val outputs =
      runBlocking(Dispatchers.IO + SupervisorJob()) {
        agents.map { agent ->
          async {
            withTimeoutOrNull(18_000L) {
              runCatching { agent.execute(ctx, cleanPrompt, screenContext) }
                .getOrElse { t ->
                  AgentExecutionOutput(
                    agentId = agent.id,
                    agentNameBn = agent.displayNameBn,
                    succeeded = false,
                    summaryBn = "এজেন্ট ত্রুটি (${agent.id}): ${LxrSecretRedactor.redactSecrets(t.message.orEmpty())}",
                  )
                }
            } ?: AgentExecutionOutput(
              agentId = agent.id,
              agentNameBn = agent.displayNameBn,
              succeeded = false,
              summaryBn = "${agent.displayNameBn} সময়সীমা অতিক্রম করেছে (Timeout)।",
            )
          }
        }.awaitAll()
      }

    val combinedRaw =
      buildString {
        appendLine("🧭 অরকেস্ট্রেশন স্তর: ${tier.labelBn} (${agents.size}টি এজেন্ট)")
        outputs.forEach { out ->
          appendLine()
          appendLine("【${out.agentNameBn}】 (${if (out.succeeded) "সফল" else "সতর্কতা"} • ${out.latencyMs}ms)")
          appendLine(out.summaryBn)
        }
      }.trim()

    val critiqueAgent = VerificationCritiqueGateAgent()
    val gateResult = critiqueAgent.execute(ctx, cleanPrompt, combinedRaw)
    val docAgent = DocumentationSummaryAgent()
    val docInput =
      if (gateResult.findings.isEmpty()) combinedRaw
      else "$combinedRaw\n\n🛡️ ${gateResult.summaryBn}"
    val finalDoc = docAgent.execute(ctx, cleanPrompt, docInput)
    val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

    if (recordInMonitor) {
      runCatching {
        LxrBackgroundTaskManager.enqueueBackgroundTask(
          ctx = ctx,
          command = "autonomous_orchestration",
          targetApp = agents.joinToString(",") { it.id },
          operation = "স্বয়ংক্রিয় মাল্টি-এজেন্ট কাজ (${tier.labelBn})",
          scheduleOsJob = false,
        ) {
          finalDoc.summaryBn to outputs.flatMap { it.files }
        }
      }
    }

    return AutonomousTaskReport(
      prompt = cleanPrompt,
      tier = tier,
      selectedAgentIds = agents.map { it.id },
      agentOutputs = outputs,
      verificationGate = gateResult,
      finalSummaryBn = finalDoc.summaryBn,
      totalLatencyMs = totalMs,
    )
  }

  private fun matchesKeyword(lower: String, kw: String): Boolean {
    if (kw.length <= 4 && kw.all { it.isLetter() && it.code < 128 }) {
      return Regex("""\b${Regex.escape(kw)}\b""").containsMatchIn(lower)
    }
    return lower.contains(kw)
  }

  private fun countMatchedDomains(lower: String): Int {
    var count = 0
    for (agent in LxrSpecialistRegistry.ALL_AGENTS) {
      if (agent.id == "intent_planner" || agent.id == "verification_critique" || agent.id == "documentation_summary") {
        continue
      }
      if (agent.keywords.any { matchesKeyword(lower, it) }) {
        count += 1
      }
    }
    return count
  }
}
