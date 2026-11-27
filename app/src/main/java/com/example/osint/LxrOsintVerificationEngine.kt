package com.example.osint

import java.util.Calendar
import java.util.Locale

/**
 * Phase 2: Verification & Credibility Engine.
 * - Classifies OFFICIAL vs UNOFFICIAL origins and PRIMARY vs SECONDARY tiers.
 * - Deduplicates syndicated copycat mirrors so repeated copies never inflate confidence.
 * - Detects conflicting facts, outdated historical statements, and impersonation/name collisions.
 */
object LxrOsintVerificationEngine {
  private val OFFICIAL_OR_AUTHORITATIVE_DOMAINS =
    setOf(
      "wikipedia.org",
      "bn.wikipedia.org",
      "en.wikipedia.org",
      "wikidata.org",
      "github.com",
      "linkedin.com",
      "play.google.com",
      "apple.com",
      "microsoft.com",
      "google.com",
      "elevenlabs.io",
      "who.int",
      "un.org",
      "bangladesh.gov.bd",
    )

  fun classifySourceOrigin(url: String, title: String): OsintSourceOrigin {
    val uLow = url.lowercase(Locale.US)
    val tLow = title.lowercase(Locale.US)
    if (uLow.contains(".gov") ||
      uLow.contains(".edu") ||
      OFFICIAL_OR_AUTHORITATIVE_DOMAINS.any { dom -> uLow.contains(dom) } ||
      tLow.contains("official")
    ) {
      return OsintSourceOrigin.OFFICIAL
    }
    return OsintSourceOrigin.UNOFFICIAL
  }

  fun classifyEvidenceTier(
    url: String,
    collector: String,
    origin: OsintSourceOrigin,
  ): OsintEvidenceTier {
    val cLow = collector.lowercase(Locale.US)
    if (cLow.contains("dns") ||
      cLow.contains("http live") ||
      cLow.contains("github") ||
      cLow.contains("maigret") ||
      cLow.contains("sherlock") ||
      origin == OsintSourceOrigin.OFFICIAL && (url.contains("wikipedia.org") || url.contains(".gov"))
    ) {
      return OsintEvidenceTier.PRIMARY
    }
    return OsintEvidenceTier.SECONDARY
  }

  fun computeCredibilityScore(
    origin: OsintSourceOrigin,
    tier: OsintEvidenceTier,
    url: String,
  ): Int {
    var score =
      when {
        origin == OsintSourceOrigin.OFFICIAL && tier == OsintEvidenceTier.PRIMARY -> 94
        origin == OsintSourceOrigin.OFFICIAL -> 86
        tier == OsintEvidenceTier.PRIMARY -> 80
        else -> 62
      }
    if (url.startsWith("https://")) score += 2
    if (origin == OsintSourceOrigin.SYNDICATED_COPY) score = minOf(score, 40)
    return score.coerceIn(10, 99)
  }

  /**
   * Groups articles/entries sharing nearly identical normalized content fingerprints across different
   * websites and marks subsequent mirrors as SYNDICATED_COPY so consensus count isn't artificially inflated.
   */
  fun deduplicateSyndicatedSources(sources: List<OsintSourceRecord>): List<OsintSourceRecord> {
    if (sources.size <= 1) return sources
    fun fingerprint(text: String): String =
      text.lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length >= 4 }
        .take(12)
        .joinToString(" ")

    val ordered = sources.sortedByDescending { it.credibilityScore }
    val seenFingerprints = linkedMapOf<String, OsintSourceRecord>()
    val out = ArrayList<OsintSourceRecord>(ordered.size)

    for (src in ordered) {
      val fp = fingerprint("${src.title} ${src.snippet}")
      val existing = if (fp.length >= 18) seenFingerprints[fp] else null
      if (existing != null && existing.domain != src.domain) {
        out.add(
          src.copy(
            origin = OsintSourceOrigin.SYNDICATED_COPY,
            credibilityScore = minOf(src.credibilityScore, 38),
            isSyndicatedDuplicate = true,
            duplicateOfSourceId = existing.sourceId,
          )
        )
      } else {
        if (fp.length >= 18) seenFingerprints[fp] = src
        out.add(src)
      }
    }
    return out
  }

  fun detectContradictions(sources: List<OsintSourceRecord>): List<OsintContradictionNote> {
    val active = sources.filterNot { it.isSyndicatedDuplicate }
    if (active.size < 2) return emptyList()
    val notes = mutableListOf<OsintContradictionNote>()

    // Detect conflicting establishment/born/founded years across independent sources
    val years =
      active.mapNotNull { s ->
        val m = Regex("(?:founded|established|born|created|launched|স্থাপিত|প্রতিষ্ঠিত|জন্ম)\\D{0,12}(19\\d{2}|20\\d{2})", RegexOption.IGNORE_CASE)
          .find("${s.title} ${s.snippet}")
          ?.groupValues
          ?.getOrNull(1)
        if (m != null) s.sourceId to m else null
      }
    val distinctYears = years.map { it.second }.distinct()
    if (distinctYears.size >= 2) {
      notes.add(
        OsintContradictionNote(
          topic = "সাল/তারিখ সংক্রান্ত অমিল",
          detailBn = "স্বাধীন সূত্রগুলোতে ভিন্ন ভিন্ন সাল উল্লেখ আছে: ${distinctYears.joinToString(" vs ")}।",
          involvedSourceIds = years.map { it.first },
        )
      )
    }

    // Detect explicit contradictory polarity markers (e.g., official/confirmed vs fake/scam/unofficial)
    val negSources =
      active.filter { s ->
        val txt = "${s.title} ${s.snippet}".lowercase(Locale.US)
        txt.contains("scam") || txt.contains("fake") || txt.contains("impersonat") || txt.contains("hoax") || txt.contains("ভুয়া")
      }
    val posSources =
      active.filter { s -> s.origin == OsintSourceOrigin.OFFICIAL && s !in negSources }
    if (negSources.isNotEmpty() && posSources.isNotEmpty()) {
      notes.add(
        OsintContradictionNote(
          topic = "সত্যতা / নকল সতর্কতা অমিল",
          detailBn = "কিছু সূত্রে অফিসিয়াল তথ্য আছে, আবার অপর সূত্রে 'fake/scam/impersonation' সতর্কতা পাওয়া গেছে।",
          involvedSourceIds = (posSources.map { it.sourceId } + negSources.map { it.sourceId }).distinct(),
        )
      )
    }
    return notes
  }

  fun detectImpersonationAndNameCollision(
    target: String,
    sources: List<OsintSourceRecord>,
  ): List<String> {
    val warnings = mutableListOf<String>()
    val active = sources.filterNot { it.isSyndicatedDuplicate }
    if (active.isEmpty()) return warnings

    val fanOrParody =
      active.filter { s ->
        val low = "${s.title} ${s.snippet}".lowercase(Locale.US)
        low.contains("fan page") ||
          low.contains("parody") ||
          low.contains("unofficial") ||
          low.contains("clone") ||
          low.contains("impersonat")
      }
    if (fanOrParody.isNotEmpty()) {
      warnings.add(
        "নকল বা ফ্যান-অ্যাকাউন্ট ঝুঁকি: ${fanOrParody.joinToString(", ") { it.domain.ifBlank { it.sourceId } }}-এ Unofficial/Parody/Fan চিহ্ন রয়েছে।"
      )
    }

    // Multiple unrelated domains claiming same username/brand handle
    val cleanTarget = target.trim().lowercase(Locale.US)
    val distinctPersonalDomains =
      active.map { it.domain }.filter { it.isNotBlank() && !it.contains("wikipedia") && !it.contains("duckduckgo") }.distinct()
    if (distinctPersonalDomains.size >= 4 && !cleanTarget.contains(".")) {
      warnings.add(
        "একই নাম/হ্যান্ডেল ('$target') একাধিক ভিন্ন ডোমেইনে (${distinctPersonalDomains.take(4).joinToString(", ")}) পাওয়া গেছে — নামের মিল (Name Collision) যাচাই ছাড়া এক ব্যক্তি ধরে নেওয়া নিরাপদ নয়।"
      )
    }
    return warnings
  }

  fun verifyClaims(
    target: String,
    dedupedSources: List<OsintSourceRecord>,
    contradictions: List<OsintContradictionNote>,
    currentYear: Int = Calendar.getInstance().get(Calendar.YEAR),
  ): List<OsintVerifiedClaim> {
    val independentSources = dedupedSources.filterNot { it.isSyndicatedDuplicate }
    if (independentSources.isEmpty()) {
      return listOf(
        OsintVerifiedClaim(
          claimId = "CLM-01",
          statement = "'$target' সম্পর্কে উন্মুক্ত পাবলিক সোর্সে সরাসরি কোনো তথ্য পাওয়া যায়নি।",
          status = OsintVerificationStatus.UNVERIFIED,
          supportingSourceIds = emptyList(),
          conflictingSourceIds = emptyList(),
          supportingUrls = emptyList(),
          reasoningBn = "কোনো স্বাধীন পাবলিক ইউআরএল বা প্রাইমারি রেকর্ড মেলেনি।",
        )
      )
    }

    val officialOrPrimary =
      independentSources.filter {
        it.origin == OsintSourceOrigin.OFFICIAL || it.evidenceTier == OsintEvidenceTier.PRIMARY
      }
    val allYears = independentSources.mapNotNull { it.publishedYearHint }
    val allOutdated = allYears.isNotEmpty() && allYears.all { it <= currentYear - 4 }

    val primaryStatus =
      when {
        contradictions.isNotEmpty() -> OsintVerificationStatus.CONFLICTING_EVIDENCE
        allOutdated && officialOrPrimary.isEmpty() -> OsintVerificationStatus.OUTDATED
        officialOrPrimary.distinctBy { it.domain.ifBlank { it.url } }.size >= 2 -> OsintVerificationStatus.VERIFIED
        officialOrPrimary.size == 1 || independentSources.distinctBy { it.domain }.size >= 2 ->
          OsintVerificationStatus.PARTIALLY_VERIFIED
        else -> OsintVerificationStatus.UNVERIFIED
      }

    val claims = mutableListOf<OsintVerifiedClaim>()
    val topUrls = independentSources.map { it.url }.filter { it.startsWith("http") }.distinct().take(5)
    claims.add(
      OsintVerifiedClaim(
        claimId = "CLM-01",
        statement = "'$target' সম্পর্কে স্বাধীন পাবলিক অস্তিত্ব ও মৌলিক পরিচিতি",
        status = primaryStatus,
        supportingSourceIds = independentSources.map { it.sourceId },
        conflictingSourceIds = contradictions.flatMap { it.involvedSourceIds }.distinct(),
        supportingUrls = topUrls,
        reasoningBn =
          when (primaryStatus) {
            OsintVerificationStatus.VERIFIED ->
              "${officialOrPrimary.size}টি অফিশিয়াল/প্রাইমারি ও মোট ${independentSources.size}টি স্বাধীন সূত্রে তথ্যটি নিশ্চিত হয়েছে।"
            OsintVerificationStatus.PARTIALLY_VERIFIED ->
              "${independentSources.size}টি স্বাধীন সূত্রে আংশিক মিল পাওয়া গেছে; আরও প্রাইমারি ক্রস-চেক উপকারী।"
            OsintVerificationStatus.CONFLICTING_EVIDENCE ->
              "স্বাধীন সূত্রগুলোতে সাল বা সত্যতা নিয়ে অমিল (${contradictions.firstOrNull()?.detailBn.orEmpty()}) পাওয়া গেছে।"
            OsintVerificationStatus.OUTDATED ->
              "পাওয়া সূত্রগুলোর তথ্য পুরোনো (সর্বশেষ সাল hint: ${allYears.maxOrNull()}) — সাম্প্রতিক আপডেট মেলেনি।"
            OsintVerificationStatus.UNVERIFIED ->
              "স্বাধীন সূত্র অপ্রতুল।"
          },
      )
    )

    // Add up to 3 specific evidence claims derived directly from top collected sources (zero hallucination)
    independentSources.take(3).forEachIndexed { idx, src ->
      val srcOutdated = src.publishedYearHint != null && src.publishedYearHint <= currentYear - 4
      val st =
        when {
          contradictions.any { src.sourceId in it.involvedSourceIds } -> OsintVerificationStatus.CONFLICTING_EVIDENCE
          srcOutdated -> OsintVerificationStatus.OUTDATED
          src.origin == OsintSourceOrigin.OFFICIAL && src.evidenceTier == OsintEvidenceTier.PRIMARY ->
            OsintVerificationStatus.VERIFIED
          else -> OsintVerificationStatus.PARTIALLY_VERIFIED
        }
      claims.add(
        OsintVerifiedClaim(
          claimId = "CLM-${(idx + 2).toString().padStart(2, '0')}",
          statement = "${src.title}: ${src.snippet.take(220)}",
          status = st,
          supportingSourceIds = listOf(src.sourceId),
          conflictingSourceIds = emptyList(),
          supportingUrls = listOfNotNull(src.url.takeIf { it.startsWith("http") }),
          reasoningBn = "${src.origin.labelBn} | ${src.evidenceTier.labelBn} | স্কোর ${src.credibilityScore}/100 (${src.collector})",
        )
      )
    }
    return claims
  }
}
