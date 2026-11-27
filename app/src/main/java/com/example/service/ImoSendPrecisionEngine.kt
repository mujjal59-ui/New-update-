package com.example.service

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Solves imo's chat bar issue where:
 * 1) A floating quick-reaction emoji row sits above the message input bar, and
 * 2) Smiley/Emoji or '+' buttons sit beside the input bar, causing older automation
 *    to tap an emoji button instead of the circular blue paper-plane Send button.
 */
object ImoSendPrecisionEngine {
  val IMO_PACKAGES =
    setOf(
      "com.imo.android.imoim",
      "com.imo.android.imoimhd",
      "com.imo.android.imoimbeta",
    )

  private val EMOJI_OR_MEDIA_BLOCKLIST =
    listOf(
      "emoji",
      "sticker",
      "smiley",
      "emoticon",
      "face",
      "gif",
      "voice",
      "mic",
      "audio",
      "record",
      "attach",
      "camera",
      "gallery",
      "photo",
      "image",
      "plus",
      "add",
      "more",
      "reaction",
      "heart",
      "kiss",
      "rose",
      "laugh",
      "thumb",
      "like",
      "❤️",
      "😂",
      "💋",
      "🌹",
      "😠",
      "😭",
      "😆",
      "💕",
      "ইমোজি",
      "স্টিকার",
      "ভয়েস",
      "ক্যামেরা",
      "ছবি",
      "ফাইল",
      "মাইক",
    )

  private val SEND_ALLOWLIST =
    listOf(
      "btn_send",
      "send_btn",
      "im_send",
      "chat_send",
      "iv_send",
      "tv_send",
      "send_button",
      "send_msg",
      "send",
      "পাঠান",
      "সেন্ড",
    )

  fun isBlockedEmojiOrMediaNode(desc: String, text: String, viewId: String): Boolean {
    val combined = "$desc $text $viewId".lowercase()
    return EMOJI_OR_MEDIA_BLOCKLIST.any { token -> combined.contains(token) }
  }

  fun isExplicitSendNode(desc: String, text: String, viewId: String): Boolean {
    if (isBlockedEmojiOrMediaNode(desc, text, viewId)) return false
    val combined = "$desc $text $viewId".lowercase()
    return SEND_ALLOWLIST.any { token -> combined.contains(token) }
  }

  fun analyzeAndSelectSendNode(nodes: List<ScreenNodeInfo>): Pair<ScreenNodeInfo?, ImoSendDiagnostic> {
    val inputNode =
      nodes
        .filter { it.isEditable || it.className.contains("EditText", ignoreCase = true) }
        .maxByOrNull { it.bounds.bottom }

    if (inputNode == null) {
      return null to
        ImoSendDiagnostic(
          inputFound = false,
          inputBoundsDescription = "মেসেজ লেখার বক্স পাওয়া যায়নি",
          excludedEmojiOrMediaNodes = emptyList(),
          selectedSendNodeDescription = "কোনো বাটন নির্বাচন করা যায়নি",
          strategyUsed = "none",
          succeeded = false,
        )
    }

    val excluded = mutableListOf<String>()
    val clickableNodes = nodes.filter { it.isClickable && it.bounds.width() > 0 && it.bounds.height() > 0 }

    val inputCenterY = inputNode.bounds.centerY()
    val tightVerticalTolerance = (inputNode.bounds.height().coerceAtLeast(60) * 0.78f).toInt()
    val minAllowedTop = inputNode.bounds.top - 24 // strictly below floating quick-reaction emoji bar

    clickableNodes.forEach { node ->
      val aboveComposer = node.bounds.bottom < inputNode.bounds.top
      if (isBlockedEmojiOrMediaNode(node.contentDescription, node.text, node.viewId) || aboveComposer) {
        val label =
          node.viewId.substringAfterLast('/').ifBlank {
            node.contentDescription.ifBlank {
              node.text.ifBlank { if (aboveComposer) "above_composer_reaction" else "emoji_or_media" }
            }
          }
        excluded.add(label)
      }
    }

    // Strategy 1: Explicit Send button ID / contentDescription / Bengali or English text (preferring composer row)
    val explicitCandidates =
      clickableNodes.filter { node ->
        isExplicitSendNode(node.contentDescription, node.text, node.viewId) &&
          node.bounds.top >= minAllowedTop
      }
    val explicitSend =
      explicitCandidates.minByOrNull { kotlin.math.abs(it.bounds.centerY() - inputCenterY) }
        ?: clickableNodes.firstOrNull { node ->
          isExplicitSendNode(node.contentDescription, node.text, node.viewId)
        }

    if (explicitSend != null) {
      val desc = describeNode(explicitSend)
      return explicitSend to
        ImoSendDiagnostic(
          inputFound = true,
          inputBoundsDescription = inputNode.bounds.toShortString(),
          excludedEmojiOrMediaNodes = excluded,
          selectedSendNodeDescription = desc,
          strategyUsed = "explicit_send_id_or_label",
          succeeded = true,
        )
    }

    // Strategy 2: Strict composer-row geometry (circular blue paper-plane Send sits to the RIGHT
    // of the input pill, at the exact same vertical level as EditText — NEVER above it where reactions sit).
    val rightRowCandidates =
      clickableNodes
        .filter { node ->
          !isBlockedEmojiOrMediaNode(node.contentDescription, node.text, node.viewId) &&
            node.bounds.top >= minAllowedTop &&
            node.bounds.left >= inputNode.bounds.right - 20 &&
            kotlin.math.abs(node.bounds.centerY() - inputCenterY) <= tightVerticalTolerance
        }
        .sortedByDescending { it.bounds.right }

    val spatialSend = rightRowCandidates.firstOrNull()
    if (spatialSend != null) {
      return spatialSend to
        ImoSendDiagnostic(
          inputFound = true,
          inputBoundsDescription = inputNode.bounds.toShortString(),
          excludedEmojiOrMediaNodes = excluded,
          selectedSendNodeDescription = describeNode(spatialSend),
          strategyUsed = "spatial_right_of_input_excluding_emoji",
          succeeded = true,
        )
    }

    return null to
      ImoSendDiagnostic(
        inputFound = true,
        inputBoundsDescription = inputNode.bounds.toShortString(),
        excludedEmojiOrMediaNodes = excluded,
        selectedSendNodeDescription = "সেন্ড বাটন শনাক্ত হয়নি (ইমোজি/রিঅ্যাকশন বাটন এড়িয়ে যাওয়া হয়েছে)",
        strategyUsed = "ime_enter_fallback",
        succeeded = false,
      )
  }

  fun performLiveImoSend(
    root: AccessibilityNodeInfo?,
    messageToType: String? = null,
    coordinateTapFallback: ((Float, Float) -> Boolean)? = null,
  ): ImoSendDiagnostic {
    if (root == null) {
      return ImoSendDiagnostic(
        inputFound = false,
        inputBoundsDescription = "স্ক্রিন রুট পাওয়া যায়নি",
        excludedEmojiOrMediaNodes = emptyList(),
        selectedSendNodeDescription = "ব্যর্থ",
        strategyUsed = "no_root",
        succeeded = false,
      )
    }

    val livePairs = collectLiveNodePairs(root)
    val inputPair =
      livePairs
        .filter { it.first.isEditable || it.first.className.contains("EditText", ignoreCase = true) }
        .maxByOrNull { it.first.bounds.bottom }

    if (inputPair != null && !messageToType.isNullOrBlank()) {
      val args =
        Bundle().apply {
          putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, messageToType)
        }
      inputPair.second.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
      inputPair.second.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    val refreshedPairs = collectLiveNodePairs(root)
    val (selectedInfo, diagnostic) = analyzeAndSelectSendNode(refreshedPairs.map { it.first })

    if (selectedInfo != null) {
      val targetAccessibilityNode =
        refreshedPairs.firstOrNull { it.first == selectedInfo }?.second
      var clicked =
        targetAccessibilityNode?.let { node ->
          var current: AccessibilityNodeInfo? = node
          var ok = false
          var depth = 0
          while (current != null && depth < 4) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
              ok = true
              break
            }
            current = current.parent
            depth++
          }
          ok
        } ?: false

      if (!clicked && coordinateTapFallback != null) {
        clicked =
          coordinateTapFallback(
            selectedInfo.bounds.exactCenterX(),
            selectedInfo.bounds.exactCenterY(),
          )
      }

      val finalDiag = diagnostic.copy(succeeded = clicked)
      RiyaRuntimeBridge.updateImoDiagnostic(finalDiag)
      return finalDiag
    }

    if (inputPair != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val imeSent =
        inputPair.second.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
      val diag =
        diagnostic.copy(
          succeeded = imeSent,
          strategyUsed = if (imeSent) "action_ime_enter_fallback" else diagnostic.strategyUsed,
        )
      RiyaRuntimeBridge.updateImoDiagnostic(diag)
      return diag
    }

    RiyaRuntimeBridge.updateImoDiagnostic(diagnostic)
    return diagnostic
  }

  private fun collectLiveNodePairs(
    root: AccessibilityNodeInfo
  ): List<Pair<ScreenNodeInfo, AccessibilityNodeInfo>> {
    val result = mutableListOf<Pair<ScreenNodeInfo, AccessibilityNodeInfo>>()
    fun traverse(node: AccessibilityNodeInfo?) {
      if (node == null) return
      val rect = Rect()
      node.getBoundsInScreen(rect)
      val info =
        ScreenNodeInfo(
          text = node.text?.toString().orEmpty(),
          contentDescription = node.contentDescription?.toString().orEmpty(),
          viewId = node.viewIdResourceName.orEmpty(),
          className = node.className?.toString().orEmpty(),
          isClickable = node.isClickable,
          isEditable = node.isEditable,
          isScrollable = node.isScrollable,
          bounds = rect,
        )
      result.add(info to node)
      for (i in 0 until node.childCount) {
        traverse(node.getChild(i))
      }
    }
    traverse(root)
    return result
  }

  private fun describeNode(node: ScreenNodeInfo): String {
    val label =
      node.contentDescription
        .ifBlank { node.text }
        .ifBlank { node.viewId.substringAfterLast('/') }
        .ifBlank { "SendButton" }
    return "$label ${node.bounds.toShortString()}"
  }
}
