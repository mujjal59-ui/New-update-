package com.example.osint

import android.content.Context
import android.os.Environment
import com.example.service.LxrDownloadStorage
import com.example.service.LxrStorageCategory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 3: Central Autonomous OSINT Orchestrator.
 * Executes all 8 investigation stages, publishes live progress via StateFlow,
 * and saves 5 unique non-overwriting deliverable files under Download/LxR/OSINT/<investigation_id>/.
 */
object LxrOsintOrchestrator {
  private val _progressState = MutableStateFlow(OsintProgressState())
  val progressState: StateFlow<OsintProgressState> = _progressState.asStateFlow()

  private val _savedInvestigations = MutableStateFlow<List<OsintInvestigationSummary>>(emptyList())
  val savedInvestigations: StateFlow<List<OsintInvestigationSummary>> = _savedInvestigations.asStateFlow()

  private fun nowDhakaStamp(): String =
    SimpleDateFormat("yyyy-MM-dd hh:mm a 'BST'", Locale.US)
      .apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }
      .format(Date())

  private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")

  fun buildUniqueInvestigationId(target: String): String {
    val ts =
      SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }
        .format(Date())
    val slug = LxrDownloadStorage.sanitizeSlug(target, "target").take(18)
    val suffix = UUID.randomUUID().toString().replace("-", "").take(5)
    return "osint_${slug}_${ts}_$suffix"
  }

  private fun updateStage(stage: Int, titleBn: String, id: String? = null) {
    _progressState.value =
      OsintProgressState(
        isRunning = stage < 8,
        currentStageIndex = stage,
        totalStages = 8,
        stageTitleBn = titleBn,
        lastInvestigationId = id ?: _progressState.value.lastInvestigationId,
      )
  }

  fun runInvestigation(
    context: Context,
    target: String,
    objective: String = "",
    targetTypeHint: String = "",
  ): OsintInvestigationReport {
    val cleanTarget = target.trim()
    val createdStamp = nowDhakaStamp()
    val investigationId = buildUniqueInvestigationId(cleanTarget)

    // Stage 1: Guardrail & Target Normalization
    updateStage(1, "১/৮: আইনগত ও প্রাইভেসি গার্ডরেইল যাচাই", investigationId)
    val verdict = LxrOsintToolAdapters.evaluateSafetyGuardrail(cleanTarget, objective)
    val targetType = LxrOsintToolAdapters.detectTargetType(cleanTarget, targetTypeHint)
    if (!verdict.allowed) {
      updateStage(8, "নিরাপত্তা নীতিমালায় স্থগিত", investigationId)
      return OsintInvestigationReport(
        investigationId = investigationId,
        target = cleanTarget,
        targetType = targetType,
        objective = objective,
        blockedByGuardrail = true,
        guardrailReasonBn = verdict.reasonBn,
        createdAtDhaka = createdStamp,
        toolStatuses = emptyList(),
        sources = emptyList(),
        claims = emptyList(),
        contradictions = emptyList(),
        impersonationWarnings = emptyList(),
        deliverables = null,
        conciseVoiceSummaryBn = verdict.reasonBn,
      )
    }

    // Stage 2: Tool Availability Inspection
    updateStage(2, "২/৮: Termux ও পাবলিক OSINT টুল যাচাই", investigationId)
    val toolStatuses = LxrOsintToolAdapters.inspectExternalOsintTools(context)

    // Stage 3: Public Source & Collector Execution
    updateStage(3, "৩/৮: পাবলিক ওয়েব, DNS ও প্রোফাইল সংগ্রহ", investigationId)
    val rawSources = LxrOsintToolAdapters.collectPublicSources(context, cleanTarget, targetType, objective)

    // Stage 4: Copycat / Syndication Deduplication
    updateStage(4, "৪/৮: কপি-পেস্ট ও সিন্ডিকেটেড সূত্র ফিল্টার", investigationId)
    val dedupedSources = LxrOsintVerificationEngine.deduplicateSyndicatedSources(rawSources)

    // Stage 5: Cross-Source Verification
    updateStage(5, "৫/৮: ক্রস-সোর্স সত্যতা যাচাই ও স্কোরিং", investigationId)
    val contradictions = LxrOsintVerificationEngine.detectContradictions(dedupedSources)
    val claims = LxrOsintVerificationEngine.verifyClaims(cleanTarget, dedupedSources, contradictions)

    // Stage 6: Impersonation & Outdated Detection
    updateStage(6, "৬/৮: ভুয়া অ্যাকাউন্ট ও নামের অমিল পরীক্ষা", investigationId)
    val impersonationWarnings =
      LxrOsintVerificationEngine.detectImpersonationAndNameCollision(cleanTarget, dedupedSources)

    // Stage 7: Save 5 unique deliverable files under Download/LxR/OSINT/<investigation_id>/
    updateStage(7, "৭/৮: ৫টি ইউনিক রিপোর্ট ফাইল সংরক্ষণ", investigationId)
    val deliverables =
      persistFiveDeliverables(
        context = context,
        investigationId = investigationId,
        target = cleanTarget,
        targetType = targetType,
        objective = objective,
        createdStamp = createdStamp,
        toolStatuses = toolStatuses,
        sources = dedupedSources,
        claims = claims,
        contradictions = contradictions,
        impersonationWarnings = impersonationWarnings,
      )

    // Stage 8: Complete & refresh index
    val primaryClaim = claims.firstOrNull()
    val independentCount = dedupedSources.count { !it.isSyndicatedDuplicate }
    val copycatCount = dedupedSources.count { it.isSyndicatedDuplicate }
    val missingCliTools =
      toolStatuses
        .filter {
          it.availability == OsintToolAvailability.NOT_INSTALLED_IN_TERMUX ||
            it.availability == OsintToolAvailability.TERMUX_NOT_INSTALLED
        }
        .map { it.toolName }

    val voiceSummary =
      buildString {
        append("বস, '$cleanTarget' এর উপর OSINT তদন্ত ($investigationId) সম্পন্ন করেছি। ")
        append("পাওয়া গেছে $independentCount টি স্বাধীন পাবলিক সূত্র")
        if (copycatCount > 0) append(" ($copycatCount টি কপি-পেস্ট/সিন্ডিকেটেড সূত্র বাদ দিয়ে)")
        append("। ")
        primaryClaim?.let {
          append("মৌলিক অবস্থা: ${it.status.labelBn}। ")
        }
        if (impersonationWarnings.isNotEmpty()) {
          append("সতর্কতা: ${impersonationWarnings.first()} ")
        }
        if (missingCliTools.isNotEmpty()) {
          append("Termux-এ ${missingCliTools.joinToString(", ")} ইনস্টল নেই (কোনো মিথ্যা CLI ফল বানাইনি; বিল্ট-ইন ওয়েব/DNS/GitHub দিয়ে যাচাই করেছি)। ")
        }
        deliverables?.let {
          append("৫টি পূর্ণাঙ্গ রিপোর্ট ফাইল সেভ হয়েছে: ${it.directoryPath}।")
        }
      }.trim()

    refreshSavedInvestigations(context)
    updateStage(8, "৮/৮: OSINT তদন্ত সম্পূর্ণ ✓", investigationId)

    return OsintInvestigationReport(
      investigationId = investigationId,
      target = cleanTarget,
      targetType = targetType,
      objective = objective,
      blockedByGuardrail = false,
      guardrailReasonBn = null,
      createdAtDhaka = createdStamp,
      toolStatuses = toolStatuses,
      sources = dedupedSources,
      claims = claims,
      contradictions = contradictions,
      impersonationWarnings = impersonationWarnings,
      deliverables = deliverables,
      conciseVoiceSummaryBn = voiceSummary,
    )
  }

  private fun persistFiveDeliverables(
    context: Context,
    investigationId: String,
    target: String,
    targetType: OsintTargetType,
    objective: String,
    createdStamp: String,
    toolStatuses: List<OsintToolStatus>,
    sources: List<OsintSourceRecord>,
    claims: List<OsintVerifiedClaim>,
    contradictions: List<OsintContradictionNote>,
    impersonationWarnings: List<String>,
  ): OsintDeliverablePaths? {
    val summaryHtml = buildResearchSummaryHtml(investigationId, target, targetType, objective, createdStamp, sources, claims)
    val claimsJson = buildVerifiedClaimsJson(investigationId, target, targetType, createdStamp, claims, contradictions, impersonationWarnings)
    val registryJson = buildSourceRegistryJson(investigationId, target, createdStamp, toolStatuses, sources)
    val comparisonHtml = buildEvidenceComparisonHtml(investigationId, target, createdStamp, sources, contradictions, impersonationWarnings)
    val finalHtml = buildFinalReportHtml(investigationId, target, targetType, objective, createdStamp, toolStatuses, sources, claims, contradictions, impersonationWarnings)

    val f1 = LxrDownloadStorage.saveTextDeliverable(context, LxrStorageCategory.OSINT, investigationId, "research_summary.html", summaryHtml, preserveUnique = true).getOrNull()
    val f2 = LxrDownloadStorage.saveTextDeliverable(context, LxrStorageCategory.OSINT, investigationId, "verified_claims.json", claimsJson, preserveUnique = true).getOrNull()
    val f3 = LxrDownloadStorage.saveTextDeliverable(context, LxrStorageCategory.OSINT, investigationId, "source_registry.json", registryJson, preserveUnique = true).getOrNull()
    val f4 = LxrDownloadStorage.saveTextDeliverable(context, LxrStorageCategory.OSINT, investigationId, "evidence_comparison.html", comparisonHtml, preserveUnique = true).getOrNull()
    val f5 = LxrDownloadStorage.saveTextDeliverable(context, LxrStorageCategory.OSINT, investigationId, "final_investigation_report.html", finalHtml, preserveUnique = true).getOrNull()

    val parentDir = f5?.parentFile?.absolutePath ?: f1?.parentFile?.absolutePath ?: return null
    return OsintDeliverablePaths(
      directoryPath = parentDir,
      researchSummaryHtml = f1?.absolutePath.orEmpty(),
      verifiedClaimsJson = f2?.absolutePath.orEmpty(),
      sourceRegistryJson = f3?.absolutePath.orEmpty(),
      evidenceComparisonHtml = f4?.absolutePath.orEmpty(),
      finalReportHtml = f5?.absolutePath.orEmpty(),
    )
  }

  private fun buildVerifiedClaimsJson(
    id: String,
    target: String,
    type: OsintTargetType,
    stamp: String,
    claims: List<OsintVerifiedClaim>,
    contradictions: List<OsintContradictionNote>,
    warnings: List<String>,
  ): String =
    JSONObject()
      .put("investigationId", id)
      .put("target", target)
      .put("targetType", type.name)
      .put("createdAtDhaka", stamp)
      .put("claims", JSONArray().apply { claims.forEach { put(it.toJson()) } })
      .put(
        "contradictions",
        JSONArray().apply {
          contradictions.forEach {
            put(JSONObject().put("topic", it.topic).put("detail", it.detailBn).put("sourceIds", JSONArray(it.involvedSourceIds)))
          }
        },
      )
      .put("impersonationWarnings", JSONArray(warnings))
      .toString(2)

  private fun buildSourceRegistryJson(
    id: String,
    target: String,
    stamp: String,
    tools: List<OsintToolStatus>,
    sources: List<OsintSourceRecord>,
  ): String =
    JSONObject()
      .put("investigationId", id)
      .put("target", target)
      .put("createdAtDhaka", stamp)
      .put(
        "toolStatuses",
        JSONArray().apply {
          tools.forEach {
            put(
              JSONObject()
                .put("tool", it.toolName)
                .put("availability", it.availability.name)
                .put("detail", it.detailBn)
                .put("installHint", it.installHint)
            )
          }
        },
      )
      .put("sources", JSONArray().apply { sources.forEach { put(it.toJson()) } })
      .toString(2)

  private fun wrapHtmlPage(title: String, bodyHtml: String): String =
    """
    <!DOCTYPE html>
    <html lang="bn">
    <head>
      <meta charset="utf-8"/>
      <meta name="viewport" content="width=device-width,initial-scale=1"/>
      <title>${escapeHtml(title)}</title>
      <style>
        body { font-family: sans-serif; background:#070B19; color:#E5E7EB; padding:18px; line-height:1.55; }
        h1,h2,h3 { color:#00F0FF; }
        a { color:#38BDF8; word-break:break-all; }
        .card { background:#111827; border:1px solid #1E293B; border-radius:12px; padding:14px; margin:12px 0; }
        .badge { display:inline-block; padding:3px 9px; border-radius:999px; font-size:12px; font-weight:bold; background:#1D4ED8; color:#fff; }
        .warn { border-color:#F59E0B; background:#1F1607; color:#FDE68A; }
        table { width:100%; border-collapse:collapse; margin-top:10px; }
        th,td { border:1px solid #1E293B; padding:8px; text-align:left; font-size:13px; }
        th { background:#0F172A; color:#38BDF8; }
      </style>
    </head>
    <body>
      $bodyHtml
    </body>
    </html>
    """.trimIndent()

  private fun buildResearchSummaryHtml(
    id: String,
    target: String,
    type: OsintTargetType,
    objective: String,
    stamp: String,
    sources: List<OsintSourceRecord>,
    claims: List<OsintVerifiedClaim>,
  ): String {
    val body = buildString {
      append("<h1>LXR--AI OSINT Research Summary</h1>")
      append("<div class='card'><b>Investigation ID:</b> ${escapeHtml(id)}<br/>")
      append("<b>Target:</b> ${escapeHtml(target)} (${escapeHtml(type.labelBn)})<br/>")
      if (objective.isNotBlank()) append("<b>Objective:</b> ${escapeHtml(objective)}<br/>")
      append("<b>Generated (Asia/Dhaka):</b> ${escapeHtml(stamp)}</div>")
      append("<h2>Verification Highlights</h2>")
      claims.forEach { c ->
        append("<div class='card'><span class='badge'>${escapeHtml(c.status.name)}</span>")
        append("<p><b>${escapeHtml(c.statement)}</b></p>")
        append("<p>${escapeHtml(c.reasoningBn)}</p>")
        if (c.supportingUrls.isNotEmpty()) {
          append("<p>Links: " + c.supportingUrls.joinToString(" | ") { "<a href='${escapeHtml(it)}'>${escapeHtml(it)}</a>" } + "</p>")
        }
        append("</div>")
      }
      append("<h2>Collected Public Sources (${sources.size})</h2>")
      sources.forEach { s ->
        append("<div class='card'><b>${escapeHtml(s.sourceId)} — ${escapeHtml(s.title)}</b> ")
        append("<span class='badge'>${escapeHtml(s.origin.labelBn)} • ${escapeHtml(s.evidenceTier.labelBn)}</span><br/>")
        if (s.url.startsWith("http")) append("<a href='${escapeHtml(s.url)}'>${escapeHtml(s.url)}</a><br/>")
        append("<p>${escapeHtml(s.snippet)}</p></div>")
      }
    }
    return wrapHtmlPage("OSINT Summary: $target", body)
  }

  private fun buildEvidenceComparisonHtml(
    id: String,
    target: String,
    stamp: String,
    sources: List<OsintSourceRecord>,
    contradictions: List<OsintContradictionNote>,
    warnings: List<String>,
  ): String {
    val body = buildString {
      append("<h1>Evidence Comparison Matrix — ${escapeHtml(target)}</h1>")
      append("<p>ID: ${escapeHtml(id)} | Time: ${escapeHtml(stamp)}</p>")
      if (warnings.isNotEmpty() || contradictions.isNotEmpty()) {
        append("<div class='card warn'><h3>Contradictions & Name/Impersonation Alerts</h3><ul>")
        warnings.forEach { append("<li>${escapeHtml(it)}</li>") }
        contradictions.forEach { append("<li><b>${escapeHtml(it.topic)}:</b> ${escapeHtml(it.detailBn)}</li>") }
        append("</ul></div>")
      }
      append("<table><tr><th>ID</th><th>Domain</th><th>Origin</th><th>Tier</th><th>Score</th><th>Syndicated Copy?</th><th>URL</th></tr>")
      sources.forEach { s ->
        append("<tr>")
        append("<td>${escapeHtml(s.sourceId)}</td>")
        append("<td>${escapeHtml(s.domain)}</td>")
        append("<td>${escapeHtml(s.origin.labelBn)}</td>")
        append("<td>${escapeHtml(s.evidenceTier.labelBn)}</td>")
        append("<td>${s.credibilityScore}/100</td>")
        append("<td>${if (s.isSyndicatedDuplicate) "Yes (Copy of ${escapeHtml(s.duplicateOfSourceId.orEmpty())})" else "No (Independent)"}</td>")
        append("<td><a href='${escapeHtml(s.url)}'>${escapeHtml(s.url)}</a></td>")
        append("</tr>")
      }
      append("</table>")
    }
    return wrapHtmlPage("Evidence Comparison: $target", body)
  }

  private fun buildFinalReportHtml(
    id: String,
    target: String,
    type: OsintTargetType,
    objective: String,
    stamp: String,
    tools: List<OsintToolStatus>,
    sources: List<OsintSourceRecord>,
    claims: List<OsintVerifiedClaim>,
    contradictions: List<OsintContradictionNote>,
    warnings: List<String>,
  ): String {
    val body = buildString {
      append("<h1>LXR--AI Autonomous OSINT Final Investigation Report</h1>")
      append("<div class='card'><b>ID:</b> ${escapeHtml(id)}<br/>")
      append("<b>Target:</b> ${escapeHtml(target)} | <b>Type:</b> ${escapeHtml(type.labelBn)}<br/>")
      if (objective.isNotBlank()) append("<b>Objective:</b> ${escapeHtml(objective)}<br/>")
      append("<b>Verified At:</b> ${escapeHtml(stamp)}</div>")

      append("<h2>1. Tool & Environment Honesty Audit</h2><ul>")
      tools.forEach { t ->
        append("<li><b>${escapeHtml(t.toolName)}</b> [${escapeHtml(t.availability.labelBn)}]: ${escapeHtml(t.detailBn)}")
        if (t.installHint.isNotBlank()) append(" (<code>${escapeHtml(t.installHint)}</code>)")
        append("</li>")
      }
      append("</ul>")

      append("<h2>2. Verified Findings</h2>")
      claims.forEach { c ->
        append("<div class='card'><span class='badge'>${escapeHtml(c.status.name)}</span> ")
        append("<b>${escapeHtml(c.claimId)}</b><p>${escapeHtml(c.statement)}</p>")
        append("<p>${escapeHtml(c.reasoningBn)}</p>")
        if (c.supportingUrls.isNotEmpty()) {
          append("<p>Sources: " + c.supportingUrls.joinToString("<br/>") { "<a href='${escapeHtml(it)}'>${escapeHtml(it)}</a>" } + "</p>")
        }
        append("</div>")
      }

      if (warnings.isNotEmpty() || contradictions.isNotEmpty()) {
        append("<h2>3. Risk, Conflict & Impersonation Warnings</h2><div class='card warn'><ul>")
        warnings.forEach { append("<li>${escapeHtml(it)}</li>") }
        contradictions.forEach { append("<li><b>${escapeHtml(it.topic)}:</b> ${escapeHtml(it.detailBn)}</li>") }
        append("</ul></div>")
      }

      append("<h2>4. Public Source Registry (${sources.size})</h2><ul>")
      sources.forEach { s ->
        append("<li><b>${escapeHtml(s.sourceId)}</b> [${escapeHtml(s.origin.labelBn)}/${s.credibilityScore}] ")
        append("<a href='${escapeHtml(s.url)}'>${escapeHtml(s.title)}</a> — ${escapeHtml(s.snippet.take(180))}</li>")
      }
      append("</ul>")
    }
    return wrapHtmlPage("Final OSINT Report: $target", body)
  }

  private fun candidateOsintRoots(context: Context): List<File> {
    val roots = linkedSetOf<File>()
    runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()?.let {
      roots.add(File(File(it, "LxR"), LxrStorageCategory.OSINT.folderName))
    }
    (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir).let {
      roots.add(File(File(it, "LxR"), LxrStorageCategory.OSINT.folderName))
    }
    return roots.toList()
  }

  fun refreshSavedInvestigations(context: Context): List<OsintInvestigationSummary> {
    val found = linkedMapOf<String, OsintInvestigationSummary>()
    for (root in candidateOsintRoots(context)) {
      if (!root.exists() || !root.isDirectory) continue
      root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
        val finalFile = File(dir, "final_investigation_report.html")
        val sumFile = File(dir, "research_summary.html")
        val claimsFile = File(dir, "verified_claims.json")
        if (finalFile.exists() || sumFile.exists() || claimsFile.exists()) {
          val id = dir.name
          if (!found.containsKey(id)) {
            val title =
              runCatching {
                if (claimsFile.exists()) JSONObject(claimsFile.readText()).optString("target").ifBlank { id } else id
              }.getOrDefault(id)
            found[id] =
              OsintInvestigationSummary(
                investigationId = id,
                directoryPath = dir.absolutePath,
                finalReportPath = finalFile.absolutePath,
                summaryHtmlPath = sumFile.absolutePath,
                claimsJsonPath = claimsFile.absolutePath,
                createdMillis = maxOf(finalFile.lastModified(), claimsFile.lastModified(), dir.lastModified()),
                displayTitle = title,
              )
          }
        }
      }
    }
    val sorted = found.values.sortedByDescending { it.createdMillis }
    _savedInvestigations.value = sorted
    return sorted
  }

  fun readDeliverablePreviewText(filePath: String): String {
    val f = File(filePath)
    if (!f.exists() || !f.isFile) return "রিপোর্ট ফাইল খুঁজে পাওয়া যায়নি।"
    val raw = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
    return if (filePath.endsWith(".html", ignoreCase = true)) {
      raw.replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<[^>]+>"), "\n")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    } else {
      raw
    }
  }
}
