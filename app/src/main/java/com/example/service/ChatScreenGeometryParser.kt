package com.example.service

import android.graphics.Rect

enum class ChatBubbleDirection {
  INCOMING_LEFT,
  OUTGOING_RIGHT,
  SYSTEM_CENTER,
}

data class DetectedChatVoiceCall(
  val appLabel: String,
  val callerName: String,
  val callSummaryBn: String,
  val rawText: String,
  val isIncomingOrMissed: Boolean,
)

/**
 * Geometric & structural chat parser tuned for WhatsApp, Facebook Messenger, and imo layouts:
 * - Left-aligned bubbles (`centerX < 0.48*W` or left avatar gutter) = Incoming from the other person.
 * - Right-aligned bubbles (`centerX > 0.52*W` / right anchor `>= 0.80*W` / double ticks `✓✓`) = Outgoing sent by Boss/LXR.
 * - Detects incoming / missed audio & video calls inside message threads (e.g., imo "Missed Audio Call / Tap to call back")
 *   while ignoring outgoing completed calls on the right side (e.g., "Audio call 57 sec").
 */
object ChatScreenGeometryParser {

  private val OUTGOING_TEXT_HINTS =
    listOf(
      "[you deleted this message]",
      "you deleted this message",
      "আপনি এই মেসেজটি মুছে ফেলেছেন",
      "আমি lxr--ai",
      "আমি lxr",
      "private assistant, এখন বস ব্যস্ত",
      "হ্যালো, বস এই মুহূর্তে ব্যস্ত আছেন",
      "বস এই মুহূর্তে কিছুটা ব্যস্ত",
      "বস এখন কাজ করতেছে",
    )

  private val INCOMING_MISSED_CALL_MARKERS =
    listOf(
      "missed audio call",
      "missed voice call",
      "missed video call",
      "missed call",
      "মিসড অডিও কল",
      "মিসড ভয়েস কল",
      "মিসড ভিডিও কল",
      "মিসড কল",
      "incoming voice call",
      "incoming audio call",
      "incoming video call",
      "ইনকামিং ভয়েস কল",
    )

  private val SYSTEM_DATE_OR_NOTICE_PATTERNS =
    listOf(
      Regex("^(today|yesterday|sunday|monday|tuesday|wednesday|thursday|friday|saturday)$", RegexOption.IGNORE_CASE),
      Regex("^(আজ|গতকাল)$"),
      Regex("^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\s+\\d{1,2}.*", RegexOption.IGNORE_CASE),
      Regex("^\\d{1,2}\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*", RegexOption.IGNORE_CASE),
    )

  fun appLabelForPackage(pkg: String): String {
    val lower = pkg.lowercase()
    return when {
      lower.contains("whatsapp") -> "WhatsApp"
      lower.startsWith("com.imo.") || lower.contains("imoim") -> "imo"
      lower == "com.facebook.orca" || lower == "com.facebook.mlite" -> "Messenger"
      else -> "Messaging"
    }
  }

  fun isMessagingPackage(pkg: String): Boolean {
    val lower = pkg.lowercase()
    return lower.contains("whatsapp") ||
      lower.startsWith("com.imo.") ||
      lower.contains("imoim") ||
      lower == "com.facebook.orca" ||
      lower == "com.facebook.mlite"
  }

  fun classifyBubbleDirection(
    boundsLeft: Int,
    boundsRight: Int,
    screenWidth: Int,
    text: String,
    viewId: String = "",
    @Suppress("UNUSED_PARAMETER") pkg: String = "",
  ): ChatBubbleDirection {
    val clean = text.trim()
    val lower = clean.lowercase()
    val idLower = viewId.lowercase()
    val w = screenWidth.coerceAtLeast(320).toFloat()

    if (SYSTEM_DATE_OR_NOTICE_PATTERNS.any { it.matches(clean) } ||
      lower.contains("messages and calls are end-to-end encrypted")
    ) {
      return ChatBubbleDirection.SYSTEM_CENTER
    }

    if (idLower.contains("outgoing") || idLower.contains("msg_out") || idLower.contains("bubble_right")) {
      return ChatBubbleDirection.OUTGOING_RIGHT
    }
    if (idLower.contains("incoming") || idLower.contains("msg_in") || idLower.contains("bubble_left")) {
      return ChatBubbleDirection.INCOMING_LEFT
    }

    if (OUTGOING_TEXT_HINTS.any { lower.contains(it) }) {
      return ChatBubbleDirection.OUTGOING_RIGHT
    }

    val leftRatio = boundsLeft.toFloat() / w
    val rightRatio = boundsRight.toFloat() / w
    val centerRatio = (boundsLeft + boundsRight) * 0.5f / w

    // Strong right-edge anchor (Boss / LXR bubble on right side of Messenger / imo / WhatsApp)
    if (rightRatio >= 0.80f && leftRatio >= 0.10f && centerRatio >= 0.50f) {
      return ChatBubbleDirection.OUTGOING_RIGHT
    }
    // Short right-side single-word bubble ("হ্যাঁ", "না", "ok", ticks)
    if (leftRatio >= 0.48f && rightRatio >= 0.65f) {
      return ChatBubbleDirection.OUTGOING_RIGHT
    }
    // Strong left-edge anchor (Other person's bubble on left side beside their avatar)
    if (leftRatio <= 0.25f && rightRatio <= 0.85f && centerRatio <= 0.50f) {
      return ChatBubbleDirection.INCOMING_LEFT
    }
    // Short left-side single-word bubble ("ও", "হুম")
    if (rightRatio <= 0.52f && leftRatio <= 0.30f) {
      return ChatBubbleDirection.INCOMING_LEFT
    }

    // Centered system banners (neither clearly anchored left nor right)
    if (leftRatio > 0.20f && rightRatio < 0.80f && centerRatio in 0.42f..0.58f) {
      return ChatBubbleDirection.SYSTEM_CENTER
    }

    return if (centerRatio > 0.52f) ChatBubbleDirection.OUTGOING_RIGHT else ChatBubbleDirection.INCOMING_LEFT
  }

  fun isIncomingMissedOrVoiceCallText(rawText: String): Boolean {
    val lower = rawText.trim().lowercase()
    if (lower.isEmpty()) return false
    return INCOMING_MISSED_CALL_MARKERS.any { lower.contains(it) }
  }

  fun extractVisibleVoiceCalls(
    nodes: List<ScreenNodeInfo>,
    pkg: String,
    screenWidth: Int,
    screenHeight: Int,
    openChatName: String?,
  ): List<DetectedChatVoiceCall> {
    if (!isMessagingPackage(pkg) || nodes.isEmpty()) return emptyList()
    val w = screenWidth.coerceAtLeast(720)
    val h = screenHeight.coerceAtLeast(1280)
    val appLabel = appLabelForPackage(pkg)
    val results = mutableListOf<DetectedChatVoiceCall>()
    val seen = HashSet<String>()

    for (node in nodes) {
      if (node.isEditable) continue
      val r: Rect = node.bounds
      if (r.top < (h * 0.11f).toInt() || r.bottom > (h * 0.90f).toInt()) continue
      val txt = node.text.ifBlank { node.contentDescription }.trim()
      if (txt.isEmpty()) continue
      if (!isIncomingMissedOrVoiceCallText(txt)) continue

      val dir = classifyBubbleDirection(r.left, r.right, w, txt, node.viewId, pkg)
      if (dir == ChatBubbleDirection.OUTGOING_RIGHT) continue

      val lower = txt.lowercase()
      val isVideo = lower.contains("video") || lower.contains("ভিডিও")
      val callTypeBn = if (isVideo) "মিসড ভিডিও কল" else "মিসড অডিও/ভয়েস কল"
      val caller =
        openChatName?.takeIf { it.isNotBlank() }
          ?: inferRowCallerAboveOrLeft(nodes, r, w, h)
          ?: "কন্টাক্ট"
      val dedupKey = "${appLabel.lowercase()}|${caller.lowercase()}|$callTypeBn"
      if (seen.add(dedupKey)) {
        results.add(
          DetectedChatVoiceCall(
            appLabel = appLabel,
            callerName = caller,
            callSummaryBn = "$appLabel-এ $caller আপনাকে একটি $callTypeBn দিয়েছিলেন",
            rawText = txt,
            isIncomingOrMissed = true,
          )
        )
      }
    }
    return results
  }

  private fun inferRowCallerAboveOrLeft(
    nodes: List<ScreenNodeInfo>,
    callRect: Rect,
    screenW: Int,
    screenH: Int,
  ): String? {
    return nodes
      .firstOrNull { cand ->
        if (cand.isEditable) return@firstOrNull false
        val t = cand.text.trim()
        if (t.length !in 2..50 || isIncomingMissedOrVoiceCallText(t)) return@firstOrNull false
        val cr = cand.bounds
        cr.top in (callRect.top - (screenH * 0.08f).toInt())..(callRect.top + 12) &&
          cr.left < (screenW * 0.55f).toInt() &&
          MessageAgent.isLikelyChatHeaderContactText(t)
      }
      ?.text
      ?.trim()
  }
}
