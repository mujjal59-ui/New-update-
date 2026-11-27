package com.example.service

data class TermuxWindowNodeInfo(
  val text: String,
  val contentDescription: String,
  val viewId: String,
  val className: String,
  val packageName: String,
  val isClickable: Boolean,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val width: Int
    get() = (right - left).coerceAtLeast(0)

  val height: Int
    get() = (bottom - top).coerceAtLeast(0)

  val centerX: Float
    get() = (left + right) / 2f

  val centerY: Float
    get() = (top + bottom) / 2f
}

/**
 * Pure geometry & Accessibility node selection helpers for automating Termux Terminal UI:
 * 1) Copy command to Clipboard and keep Termux + Gboard keyboard ON
 * 2) 3-second long-press inside Termux terminal viewport & release
 * 3) Wait ~2 seconds -> locate and tap "Paste" inside floating [Copy | Paste | More...] popup
 * 4) Tap Gboard's bottom-right blue Enter/Return arrow button while keeping keyboard open
 */
object TermuxUiAutomation {
  const val TERMUX_PACKAGE = "com.termux"
  val GBOARD_PACKAGES =
    setOf(
      "com.google.android.inputmethod.latin",
      "com.google.android.inputmethod.latin.dev",
    )

  const val LONG_PRESS_DURATION_MS = 3000L
  const val POPUP_WAIT_AFTER_RELEASE_MS = 2000L

  fun computeLongPressPoint(
    screenW: Int,
    screenH: Int,
    imeTopOrNull: Int? = null,
    terminalBottomOrNull: Int? = null,
  ): Pair<Float, Float> {
    val w = screenW.coerceAtLeast(720)
    val h = screenH.coerceAtLeast(1280)
    val x = w * 0.48f

    // Determine visible Termux black terminal area above Extra Keys + Gboard
    val statusTop = (h * 0.06f).toInt()
    val estimatedTerminalBottom =
      when {
        terminalBottomOrNull != null && terminalBottomOrNull > statusTop + 120 -> terminalBottomOrNull
        imeTopOrNull != null && imeTopOrNull in (h * 0.30f).toInt()..(h * 0.85f).toInt() ->
          (imeTopOrNull - (h * 0.11f).toInt()).coerceAtLeast(statusTop + 160)
        else -> (h * 0.43f).toInt()
      }

    // Place tap squarely in the upper-middle Termux terminal section (safe whether empty or text-filled)
    val rawY = statusTop + (estimatedTerminalBottom - statusTop) * 0.48f
    val clampedY = rawY.coerceIn(h * 0.18f, (estimatedTerminalBottom - h * 0.04f).coerceAtLeast(h * 0.22f))
    return x to clampedY
  }

  fun findPastePopupCandidate(nodes: List<TermuxWindowNodeInfo>): TermuxWindowNodeInfo? {
    val valid = nodes.filter { it.width > 0 && it.height > 0 }
    if (valid.isEmpty()) return null

    fun labelOf(n: TermuxWindowNodeInfo): String =
      "${n.text} ${n.contentDescription}".trim().lowercase()

    fun viewIdOf(n: TermuxWindowNodeInfo): String = n.viewId.lowercase()

    // 1. Exact "Paste" / "Pasta" / "পেস্ট" popup item (preferred, ignoring "More..." & "Copy")
    val exact =
      valid.firstOrNull { n ->
        val lbl = labelOf(n)
        val vid = viewIdOf(n)
        (lbl == "paste" || lbl == "pasta" || lbl == "পেস্ট" || vid.endsWith(":id/paste") || vid.contains("paste_button")) &&
          !lbl.contains("more") &&
          !lbl.contains("copy")
      }
    if (exact != null) return exact

    // 2. Fallback: clickable node containing "paste" but not "copy" / "more" / "plain text"
    return valid.firstOrNull { n ->
      val lbl = labelOf(n)
      val vid = viewIdOf(n)
      (lbl.contains("paste") || lbl.contains("পেস্ট") || vid.contains("paste")) &&
        !lbl.contains("more") &&
        !lbl.contains("copy") &&
        !lbl.contains("clipboard")
    }
  }

  fun findGboardEnterKeyCandidate(
    imeNodes: List<TermuxWindowNodeInfo>,
    imeLeft: Int,
    imeTop: Int,
    imeRight: Int,
    imeBottom: Int,
  ): TermuxWindowNodeInfo? {
    val imeW = (imeRight - imeLeft).coerceAtLeast(1)
    val imeH = (imeBottom - imeTop).coerceAtLeast(1)
    val minRightX = imeLeft + imeW * 0.70f
    val minBottomY = imeTop + imeH * 0.65f

    val enterKeywords = listOf("enter", "return", "done", "go", "search", "send", "next", "এন্টার")
    val idKeywords = listOf("ime_action", "key_pos_ime_action", "enter", "action_key")

    val matches =
      imeNodes.filter { n ->
        if (n.width <= 0 || n.height <= 0) return@filter false
        val inCorner = n.centerX >= minRightX && n.centerY >= minBottomY
        if (!inCorner) return@filter false
        val combined = "${n.text} ${n.contentDescription}".lowercase()
        val idLower = n.viewId.lowercase()
        enterKeywords.any { combined.contains(it) } || idKeywords.any { idLower.contains(it) }
      }

    return matches.maxByOrNull { n ->
      val clickableBonus = if (n.isClickable) 1000f else 0f
      clickableBonus + n.centerX + n.centerY
    }
  }

  /**
   * Computes exact tap coordinates for Gboard's bottom-right circular blue Enter/Return arrow (`↵`),
   * accounting for Gboard toolbar, 5 key rows, and bottom gesture/collapse-keyboard strip.
   */
  fun computeGboardBlueEnterFallbackPoint(
    screenW: Int,
    screenH: Int,
    imeTop: Int? = null,
    imeRight: Int? = null,
    imeBottom: Int? = null,
  ): Pair<Float, Float> {
    val w = screenW.coerceAtLeast(720)
    val h = screenH.coerceAtLeast(1280)

    val x =
      if (imeRight != null && imeRight > w * 0.75f) {
        (imeRight - w * 0.09f).coerceIn(w * 0.84f, w * 0.96f)
      } else {
        w * 0.91f
      }

    val y =
      if (imeTop != null && imeBottom != null && imeBottom > imeTop + h * 0.20f) {
        val imeH = (imeBottom - imeTop).toFloat()
        if (imeBottom >= (h * 0.975f).toInt()) {
          // IME window extends to the bottom nav strip (~4.2% of screen); bottom Enter row sits just above it
          (imeBottom - h * 0.082f).coerceIn(imeTop + imeH * 0.78f, h * 0.935f)
        } else {
          // IME window stops cleanly above the nav strip
          (imeBottom - imeH * 0.11f).coerceIn(imeTop + imeH * 0.75f, imeBottom - h * 0.02f)
        }
      } else {
        h * 0.918f
      }

    return x to y
  }
}
