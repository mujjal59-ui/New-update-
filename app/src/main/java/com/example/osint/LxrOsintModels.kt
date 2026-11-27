package com.example.osint

import org.json.JSONArray
import org.json.JSONObject

enum class OsintTargetType(val labelBn: String) {
  PERSON("ব্যক্তি (Person)"),
  ORGANIZATION("প্রতিষ্ঠান (Organization)"),
  USERNAME("ইউজারনেম (Username)"),
  DOMAIN("ডোমেইন / ওয়েবসাইট (Domain)"),
  EMAIL("পাবলিক ইমেইল (Public Email)"),
  PHONE_PUBLIC("পাবলিক বিজনেস নম্বর (Public Phone)"),
  GENERAL_TOPIC("সাধারণ বিষয় (General Topic)"),
}

enum class OsintVerificationStatus(val labelBn: String) {
  VERIFIED("VERIFIED (একাধিক স্বাধীন সূত্রে প্রমাণিত)"),
  PARTIALLY_VERIFIED("PARTIALLY_VERIFIED (আংশিক প্রমাণিত)"),
  CONFLICTING_EVIDENCE("CONFLICTING_EVIDENCE (সূত্রগুলোর তথ্যে অমিল)"),
  UNVERIFIED("UNVERIFIED (অপ্রমাণিত / পর্যাপ্ত সূত্র নেই)"),
  OUTDATED("OUTDATED (পুরোনো তথ্য)"),
}

enum class OsintSourceOrigin(val labelBn: String) {
  OFFICIAL("Official"),
  UNOFFICIAL("Unofficial"),
  SYNDICATED_COPY("Syndicated Copy"),
}

enum class OsintEvidenceTier(val labelBn: String) {
  PRIMARY("Primary Source"),
  SECONDARY("Secondary Source"),
}

enum class OsintToolAvailability(val labelBn: String) {
  AVAILABLE_CLI("Termux CLI সক্রিয়"),
  AVAILABLE_BUILTIN("অ্যাপ বিল্ট-ইন পাবলিক কালেক্টর সক্রিয়"),
  NOT_INSTALLED_IN_TERMUX("Termux-এ ইনস্টল করা নেই"),
  TERMUX_NOT_INSTALLED("ফোনে Termux অ্যাপ পাওয়া যায়নি"),
}

data class OsintToolStatus(
  val toolName: String,
  val availability: OsintToolAvailability,
  val detailBn: String,
  val installHint: String = "",
)

data class OsintSourceRecord(
  val sourceId: String,
  val title: String,
  val url: String,
  val domain: String,
  val collector: String,
  val snippet: String,
  val origin: OsintSourceOrigin,
  val evidenceTier: OsintEvidenceTier,
  val credibilityScore: Int,
  val isSyndicatedDuplicate: Boolean = false,
  val duplicateOfSourceId: String? = null,
  val publishedYearHint: Int? = null,
  val fetchedAtDhaka: String,
) {
  fun toJson(): JSONObject =
    JSONObject()
      .put("sourceId", sourceId)
      .put("title", title)
      .put("url", url)
      .put("domain", domain)
      .put("collector", collector)
      .put("snippet", snippet)
      .put("origin", origin.name)
      .put("evidenceTier", evidenceTier.name)
      .put("credibilityScore", credibilityScore)
      .put("isSyndicatedDuplicate", isSyndicatedDuplicate)
      .put("duplicateOfSourceId", duplicateOfSourceId ?: JSONObject.NULL)
      .put("publishedYearHint", publishedYearHint ?: JSONObject.NULL)
      .put("fetchedAtDhaka", fetchedAtDhaka)
}

data class OsintVerifiedClaim(
  val claimId: String,
  val statement: String,
  val status: OsintVerificationStatus,
  val supportingSourceIds: List<String>,
  val conflictingSourceIds: List<String>,
  val supportingUrls: List<String>,
  val reasoningBn: String,
) {
  fun toJson(): JSONObject =
    JSONObject()
      .put("claimId", claimId)
      .put("statement", statement)
      .put("status", status.name)
      .put("supportingSourceIds", JSONArray(supportingSourceIds))
      .put("conflictingSourceIds", JSONArray(conflictingSourceIds))
      .put("supportingUrls", JSONArray(supportingUrls))
      .put("reasoningBn", reasoningBn)
}

data class OsintContradictionNote(
  val topic: String,
  val detailBn: String,
  val involvedSourceIds: List<String>,
)

data class OsintGuardrailVerdict(
  val allowed: Boolean,
  val reasonBn: String,
)

data class OsintDeliverablePaths(
  val directoryPath: String,
  val researchSummaryHtml: String,
  val verifiedClaimsJson: String,
  val sourceRegistryJson: String,
  val evidenceComparisonHtml: String,
  val finalReportHtml: String,
)

data class OsintInvestigationReport(
  val investigationId: String,
  val target: String,
  val targetType: OsintTargetType,
  val objective: String,
  val blockedByGuardrail: Boolean,
  val guardrailReasonBn: String?,
  val createdAtDhaka: String,
  val toolStatuses: List<OsintToolStatus>,
  val sources: List<OsintSourceRecord>,
  val claims: List<OsintVerifiedClaim>,
  val contradictions: List<OsintContradictionNote>,
  val impersonationWarnings: List<String>,
  val deliverables: OsintDeliverablePaths?,
  val conciseVoiceSummaryBn: String,
)

data class OsintInvestigationSummary(
  val investigationId: String,
  val directoryPath: String,
  val finalReportPath: String,
  val summaryHtmlPath: String,
  val claimsJsonPath: String,
  val createdMillis: Long,
  val displayTitle: String,
)

data class OsintProgressState(
  val isRunning: Boolean = false,
  val currentStageIndex: Int = 0,
  val totalStages: Int = 8,
  val stageTitleBn: String = "প্রস্তুত",
  val lastInvestigationId: String? = null,
)
