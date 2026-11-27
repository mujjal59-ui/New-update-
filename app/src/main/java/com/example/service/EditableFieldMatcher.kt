package com.example.service

import android.graphics.Rect

data class EditableFieldCandidate(
  val index: Int,
  val text: String,
  val hint: String,
  val contentDescription: String,
  val viewId: String,
  val top: Int,
  val bottom: Int,
  val left: Int,
  val right: Int,
  val isSingleLine: Boolean = false,
) {
  val height: Int
    get() = (bottom - top).coerceAtLeast(0)

  val width: Int
    get() = (right - left).coerceAtLeast(0)

  val combinedLabelsLower: String
    get() = "$hint $contentDescription $viewId $text".lowercase()
}

enum class EditableFieldRole {
  TITLE,
  BODY,
  GENERIC,
}

object EditableFieldMatcher {
  private val titleKeywords =
    listOf(
      "title",
      "subject",
      "heading",
      "header",
      "caption",
      "note_title",
      "et_title",
      "title_edit",
      "শিরোনাম",
      "টাইটেল",
    )

  private val bodyKeywords =
    listOf(
      "note something down",
      "write a note",
      "start writing",
      "note body",
      "note_content",
      "note_body",
      "et_content",
      "et_note",
      "content",
      "body",
      "description",
      "memo",
      "details",
      "message",
      "নোট লিখুন",
      "কিছু লিখুন",
      "মূল লেখা",
    )

  fun classifyRole(
    candidate: EditableFieldCandidate,
    allCandidates: List<EditableFieldCandidate>,
  ): EditableFieldRole {
    val labels = "$candidate".let { "${candidate.hint} ${candidate.contentDescription} ${candidate.viewId}".lowercase() }
    val textLower = candidate.text.lowercase()

    if (bodyKeywords.any { labels.contains(it) }) {
      return EditableFieldRole.BODY
    }
    if (titleKeywords.any { labels.contains(it) } ||
      (candidate.text.isNotBlank() && titleKeywords.any { textLower == it })
    ) {
      return EditableFieldRole.TITLE
    }

    // Geometric heuristic for Notepad-style stacked editors (top compact Title box + large lower Note box)
    if (allCandidates.size >= 2) {
      val sorted = allCandidates.sortedBy { it.top }
      val first = sorted.first()
      val tallest = allCandidates.maxByOrNull { it.height }
      if (tallest != null && tallest.index != first.index && tallest.top >= first.bottom - 16) {
        val isMuchTaller = tallest.height >= maxOf(first.height * 1.6f, 200f)
        if (isMuchTaller) {
          if (candidate.index == first.index) return EditableFieldRole.TITLE
          if (candidate.index == tallest.index) return EditableFieldRole.BODY
        }
      }
      // Even before layout heights settle, top single-line field above a multi-line field is Title
      if (candidate.index == first.index && first.isSingleLine && sorted.drop(1).any { !it.isSingleLine }) {
        return EditableFieldRole.TITLE
      }
      if (candidate.index != first.index && !candidate.isSingleLine && first.isSingleLine) {
        return EditableFieldRole.BODY
      }
    }

    return EditableFieldRole.GENERIC
  }

  /**
   * Selects the best editable node index for [target] ("auto", "body", or "title").
   * In Notepad apps ("Title" above "Note something down"), "auto" and "body" strictly skip Title
   * and target the lower note-body editor.
   */
  fun selectBestCandidateIndex(
    candidates: List<EditableFieldCandidate>,
    target: String = "auto",
  ): Int? {
    if (candidates.isEmpty()) return null
    val normalizedTarget = target.trim().lowercase()
    val classified = candidates.map { it to classifyRole(it, candidates) }

    if (normalizedTarget == "title" || normalizedTarget == "টাইটেল" || normalizedTarget == "শিরোনাম") {
      return (
        classified.firstOrNull { it.second == EditableFieldRole.TITLE }?.first
          ?: candidates.minByOrNull { it.top }
      )?.index
    }

    // Default ("auto" or "body"): strictly prefer BODY and avoid TITLE
    val explicitBody =
      classified
        .filter { it.second == EditableFieldRole.BODY }
        .maxByOrNull { it.first.height.toLong() * 10L + it.first.top }
        ?.first
    if (explicitBody != null) return explicitBody.index

    val nonTitleFields = classified.filter { it.second != EditableFieldRole.TITLE }.map { it.first }
    if (nonTitleFields.isNotEmpty()) {
      return nonTitleFields.maxByOrNull { it.height.toLong() + it.top.toLong() }?.index
    }

    // If user explicitly asked for "body" and only a Title node was found, still pick lower if multiple exist
    if (candidates.size > 1) {
      return candidates.sortedByDescending { it.top }.first().index
    }
    return candidates.first().index
  }

  private val defaultPlaceholderHints =
    setOf(
      "note something down",
      "write a note",
      "title",
      "শিরোনাম",
      "নোট লিখুন",
      "type here",
      "enter text",
      "search",
      "সার্চ",
    )

  /**
   * Preserves pre-existing note/message text on [EditableFieldRole.BODY] and multi-line non-search
   * [EditableFieldRole.GENERIC] fields unless the field only holds placeholder text or [target] == "replace".
   */
  fun mergePreservedFieldText(
    role: EditableFieldRole,
    candidate: EditableFieldCandidate?,
    existingRaw: String,
    hintRaw: String,
    incomingText: String,
    target: String = "auto",
  ): String {
    if (incomingText.isBlank()) return incomingText
    if (target.equals("replace", ignoreCase = true) || role == EditableFieldRole.TITLE) {
      return incomingText
    }
    val existingTrimLower = existingRaw.trim().lowercase()
    val hasRealExistingText =
      existingRaw.isNotBlank() &&
        !existingRaw.equals(hintRaw, ignoreCase = true) &&
        existingTrimLower !in defaultPlaceholderHints
    if (!hasRealExistingText) return incomingText

    val labels =
      "${candidate?.hint.orEmpty()} ${candidate?.contentDescription.orEmpty()} ${candidate?.viewId.orEmpty()}"
        .lowercase()
    val isSearchBox = labels.contains("search") || labels.contains("সার্চ")
    val isMultiLineGeneric =
      role == EditableFieldRole.GENERIC && !(candidate?.isSingleLine ?: false) && !isSearchBox
    val shouldPreserve = role == EditableFieldRole.BODY || isMultiLineGeneric
    if (!shouldPreserve) return incomingText
    return if (existingRaw.contains(incomingText)) existingRaw else "$existingRaw\n$incomingText"
  }

  fun boundsFromRect(rect: Rect): List<Int> = listOf(rect.top, rect.bottom, rect.left, rect.right)
}
