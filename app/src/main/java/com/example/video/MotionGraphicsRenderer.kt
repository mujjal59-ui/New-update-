package com.example.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * YouTube Explainer / Documentary / Promo Motion Graphics Renderer:
 * - Kinetic Typography (pop-in highlighted phrases + animated accent underline)
 * - 3D-Style Beveled Animated Charts + Trend Curve + Counter Badges
 * - Orbital Infographics + Moving Icons + Step Progress Sweep
 * - Animated Global Route Maps & Timeline Milestones
 * - Sleek Lower-Third Broadcast Banners & Live Voice Equalizer Bars
 */
object MotionGraphicsRenderer {

  fun drawMotionGraphicsLayer(
    canvas: Canvas,
    scene: VideoScenePlan,
    w: Float,
    h: Float,
    scale: Float,
    phase: Float,
    globalTimeSec: Float,
    mouthOpen: Float,
  ) {
    val kind = resolveEffectiveKind(scene)
    drawDynamicGridAndParticles(canvas, w, h, scale, phase, globalTimeSec, mouthOpen)

    when (kind) {
      MotionGraphicKind.ANIMATED_CHART ->
        drawAnimated3DChartAndCurve(canvas, scene, w, h, scale, phase)
      MotionGraphicKind.INFOGRAPHIC_STEPS ->
        drawInfographicNodesAndIcons(canvas, scene, w, h, scale, phase, globalTimeSec)
      MotionGraphicKind.ANIMATED_MAP_TIMELINE ->
        drawAnimatedMapAndTimeline(canvas, scene, w, h, scale, phase)
      MotionGraphicKind.KINETIC_TYPOGRAPHY ->
        drawKineticTypographyStage(canvas, scene, w, h, scale, phase, mouthOpen)
      MotionGraphicKind.LOWER_THIRD ->
        drawBroadcastLowerThird(canvas, scene, w, h, scale, phase, mouthOpen)
      MotionGraphicKind.NONE ->
        Unit
    }
  }

  fun resolveEffectiveKind(scene: VideoScenePlan): MotionGraphicKind {
    if (scene.motionGraphicKind != MotionGraphicKind.NONE) return scene.motionGraphicKind
    if (scene.motionStyle == MotionChoreography.MOTION_GRAPHICS ||
      scene.productionMode == VideoProductionMode.MOTION_GRAPHICS
    ) {
      return when ((scene.index - 1) % 4) {
        0 -> MotionGraphicKind.KINETIC_TYPOGRAPHY
        1 -> MotionGraphicKind.ANIMATED_CHART
        2 -> MotionGraphicKind.INFOGRAPHIC_STEPS
        else -> MotionGraphicKind.ANIMATED_MAP_TIMELINE
      }
    }
    return MotionGraphicKind.LOWER_THIRD
  }

  private fun drawDynamicGridAndParticles(
    canvas: Canvas,
    w: Float,
    h: Float,
    scale: Float,
    phase: Float,
    globalTimeSec: Float,
    mouthOpen: Float,
  ) {
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.argb(36, 56, 189, 248)
      strokeWidth = 1.4f * scale
    }
    val step = 56f * scale
    val shiftX = (globalTimeSec * 22f * scale) % step
    val shiftY = (globalTimeSec * 14f * scale) % step
    var x = -step + shiftX
    while (x < w + step) {
      canvas.drawLine(x, 0f, x, h, gridPaint)
      x += step
    }
    var y = -step + shiftY
    while (y < h + step) {
      canvas.drawLine(0f, y, w, y, gridPaint)
      y += step
    }

    // Audio-reactive equalizer spectrum along right/bottom rail
    val eqPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val bars = 14
    for (i in 0 until bars) {
      val bx = w * 0.08f + i * (w * 0.03f)
      val barH = (12f + (0.28f + mouthOpen) * 44f * abs(sin(globalTimeSec * 9.5 + i * 0.7)).toFloat()) * scale
      eqPaint.color = if (i % 2 == 0) Color.argb(175, 0, 229, 255) else Color.argb(175, 250, 204, 21)
      canvas.drawRoundRect(
        RectF(bx, h * 0.67f - barH, bx + 7f * scale, h * 0.67f),
        4f * scale,
        4f * scale,
        eqPaint,
      )
    }

    // Floating holographic bokeh rings
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 2.4f * scale
    }
    for (r in 0 until 3) {
      val rad = ((phase * 0.45f + r * 0.28f) % 0.85f) * minOf(w, h) * 0.48f
      val alpha = (110 * (1f - rad / (minOf(w, h) * 0.48f))).toInt().coerceIn(15, 115)
      ringPaint.color = Color.argb(alpha, 0, 229, 255)
      canvas.drawCircle(w * 0.5f, h * 0.46f, rad, ringPaint)
    }
  }

  private fun drawKineticTypographyStage(
    canvas: Canvas,
    scene: VideoScenePlan,
    w: Float,
    h: Float,
    scale: Float,
    phase: Float,
    mouthOpen: Float,
  ) {
    val card = RectF(w * 0.08f, h * 0.22f, w * 0.92f, h * 0.60f)
    val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = LinearGradient(card.left, card.top, card.right, card.bottom, Color.argb(215, 12, 22, 54), Color.argb(215, 30, 16, 62), Shader.TileMode.CLAMP)
    }
    val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 3f * scale
      color = Color.rgb(0, 229, 255)
    }
    val enterSlide = (1f - (phase * 4.5f).coerceIn(0f, 1f)) * 45f * scale

    canvas.save()
    canvas.translate(0f, enterSlide)
    canvas.drawRoundRect(card, 24f * scale, 24f * scale, cardBg)
    canvas.drawRoundRect(card, 24f * scale, 24f * scale, cardBorder)

    val words = scene.overlayHeadline.ifBlank { scene.title }.split(" ").filter { it.isNotBlank() }
    val activeIdx = if (words.isEmpty()) 0 else ((phase * (words.size + 1)).toInt()).coerceIn(0, words.lastIndex)

    val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.DEFAULT_BOLD
      textAlign = Paint.Align.CENTER
      textSize = (32f + mouthOpen * 4f) * scale
    }

    val cy = card.centerY() - 12f * scale
    val fullLine = words.take(4).joinToString(" ")
    bigPaint.color = Color.WHITE
    canvas.drawText(fullLine, card.centerX(), cy, bigPaint)

    val activeWord = words.getOrElse(activeIdx) { scene.overlayHeadline }
    val pillW = maxOf(140f * scale, activeWord.length * 19f * scale)
    val pillRect = RectF(card.centerX() - pillW * 0.5f, cy + 20f * scale, card.centerX() + pillW * 0.5f, cy + 64f * scale)
    val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 204, 21) }
    canvas.drawRoundRect(pillRect, 14f * scale, 14f * scale, pillBg)
    val darkTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 22f * scale
      typeface = Typeface.DEFAULT_BOLD
      textAlign = Paint.Align.CENTER
    }
    canvas.drawText(activeWord, pillRect.centerX(), pillRect.centerY() + 8f * scale, darkTxt)
    canvas.restore()
  }

  private fun drawAnimated3DChartAndCurve(
    canvas: Canvas,
    scene: VideoScenePlan,
    w: Float,
    h: Float,
    scale: Float,
    phase: Float,
  ) {
    val card = RectF(w * 0.08f, h * 0.20f, w * 0.92f, h * 0.64f)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 9, 18, 42) }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE; strokeWidth = 2.5f * scale; color = Color.rgb(56, 189, 248)
    }
    canvas.drawRoundRect(card, 22f * scale, 22f * scale, bgPaint)
    canvas.drawRoundRect(card, 22f * scale, 22f * scale, strokePaint)

    val values = scene.chartValues.ifEmpty { listOf(0.32f, 0.54f, 0.78f, 0.95f) }
    val labels = scene.chartLabels.ifEmpty { listOf("Q1", "Q2", "Q3", "Q4") }
    val grow = (phase * 1.65f).coerceIn(0.08f, 1f)

    val baseLineY = card.bottom - 42f * scale
    val maxBarH = card.height() * 0.54f
    val chartW = card.width() * 0.80f
    val startX = card.left + card.width() * 0.12f
    val slotW = chartW / values.size.coerceAtLeast(1)

    val colors = listOf(
      Color.rgb(0, 229, 255),
      Color.rgb(59, 130, 246),
      Color.rgb(168, 85, 247),
      Color.rgb(34, 197, 94),
    )

    val curvePath = Path()
    val pts = mutableListOf<Pair<Float, Float>>()
    val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 15f * scale
      typeface = Typeface.DEFAULT_BOLD
      textAlign = Paint.Align.CENTER
    }

    values.forEachIndexed { idx, v ->
      val barH = maxBarH * v.coerceIn(0.1f, 1f) * grow
      val bx = startX + idx * slotW + slotW * 0.18f
      val bw = slotW * 0.52f
      val topY = baseLineY - barH
      val col = colors[idx % colors.size]
      barPaint.color = col
      canvas.drawRoundRect(RectF(bx, topY, bx + bw, baseLineY), 8f * scale, 8f * scale, barPaint)
      // 3D depth side face
      bevelPaint.color = Color.argb(170, Color.red(col) / 2, Color.green(col) / 2, Color.blue(col) / 2)
      val side = Path().apply {
        moveTo(bx + bw, topY + 4f * scale)
        lineTo(bx + bw + 10f * scale, topY - 6f * scale)
        lineTo(bx + bw + 10f * scale, baseLineY - 6f * scale)
        lineTo(bx + bw, baseLineY)
        close()
      }
      canvas.drawPath(side, bevelPaint)

      val cx = bx + bw * 0.5f
      pts.add(cx to (topY - 12f * scale))
      val pct = (v * 100f * grow).roundToInt()
      canvas.drawText("$pct%", cx, topY - 16f * scale, lblPaint)
      canvas.drawText(labels.getOrElse(idx) { "S${idx + 1}" }, cx, baseLineY + 22f * scale, lblPaint)
    }

    // Animated golden growth trend curve above 3D bars
    if (pts.isNotEmpty()) {
      curvePath.moveTo(pts.first().first, pts.first().second)
      for (i in 1 until pts.size) {
        val prev = pts[i - 1]
        val cur = pts[i]
        val midX = (prev.first + cur.first) * 0.5f
        curvePath.cubicTo(midX, prev.second, midX, cur.second, cur.first, cur.second)
      }
      val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * scale
        color = Color.rgb(250, 204, 21)
      }
      canvas.drawPath(curvePath, curvePaint)
    }
  }

  private fun drawInfographicNodesAndIcons(
    canvas: Canvas,
    scene: VideoScenePlan,
    w: Float,
    h: Float,
    scale: Float,
    phase: Float,
    globalTimeSec: Float,
  ) {
    val cx = w * 0.62f
    val cy = h * 0.42f
    val radius = minOf(w, h) * 0.17f

    val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 3f * scale
      color = Color.argb(140, 0, 229, 255)
    }
    canvas.drawCircle(cx, cy, radius, orbitPaint)

    val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(14, 116, 144)
    }
    canvas.drawCircle(cx, cy, 42f * scale, hubPaint)
    val hubTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 15f * scale
      typeface = Typeface.DEFAULT_BOLD
      textAlign = Paint.Align.CENTER
    }
    canvas.drawText("AI CORE", cx, cy + 5f * scale, hubTxt)

    val badges = listOf("01 DATA", "02 SYNC", "03 MOTION", "04 RENDER")
    val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      strokeWidth = 2.4f * scale
      color = Color.rgb(250, 204, 21)
    }

    for (i in badges.indices) {
      val angle = globalTimeSec * 0.85 + i * (PI / 2.0)
      val nx = cx + (cos(angle) * radius).toFloat()
      val ny = cy + (sin(angle) * radius).toFloat()
      canvas.drawLine(cx, cy, nx, ny, linePaint)
      val highlight = ((phase * 4).toInt() % 4) == i
      nodePaint.color = if (highlight) Color.rgb(250, 204, 21) else Color.rgb(30, 58, 138)
      canvas.drawCircle(nx, ny, 28f * scale, nodePaint)
      hubTxt.color = if (highlight) Color.BLACK else Color.WHITE
      hubTxt.textSize = 11f * scale
      canvas.drawText(badges[i], nx, ny + 4f * scale, hubTxt)
    }
  }

  private fun drawAnimatedMapAndTimeline(
    canvas: Canvas,
    scene: VideoScenePlan,
    w: Float,
    h: Float,
    scale: Float,
    phase: Float,
  ) {
    val mapRect = RectF(w * 0.09f, h * 0.22f, w * 0.91f, h * 0.56f)
    val mapBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 8, 24, 48) }
    val mapBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE; strokeWidth = 2.5f * scale; color = Color.rgb(0, 229, 255)
    }
    canvas.drawRoundRect(mapRect, 20f * scale, 20f * scale, mapBg)
    canvas.drawRoundRect(mapRect, 20f * scale, 20f * scale, mapBorder)

    // Stylized continents / vector route spline
    val route = Path().apply {
      moveTo(mapRect.left + 44f * scale, mapRect.bottom - 48f * scale)
      cubicTo(
        mapRect.left + mapRect.width() * 0.34f,
        mapRect.top + 32f * scale,
        mapRect.left + mapRect.width() * 0.64f,
        mapRect.bottom - 24f * scale,
        mapRect.right - 44f * scale,
        mapRect.top + 46f * scale,
      )
    }
    val routeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE; strokeWidth = 5f * scale; color = Color.argb(80, 148, 163, 184)
    }
    canvas.drawPath(route, routeBg)

    val pm = PathMeasure(route, false)
    val activePath = Path()
    val dist = pm.length * phase.coerceIn(0.05f, 1f)
    pm.getSegment(0f, dist, activePath, true)
    val routeGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE; strokeWidth = 6f * scale; color = Color.rgb(250, 204, 21)
    }
    canvas.drawPath(activePath, routeGlow)

    val pos = FloatArray(2)
    pm.getPosTan(dist, pos, null)
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 63, 94) }
    canvas.drawCircle(pos[0], pos[1], 11f * scale, pinPaint)
    val pinCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    canvas.drawCircle(pos[0], pos[1], 4.5f * scale, pinCore)

    // Animated timeline milestones at bottom of map card
    val barY = mapRect.bottom - 20f * scale
    val steps = 4
    for (i in 0 until steps) {
      val sx = mapRect.left + 44f * scale + (mapRect.width() - 88f * scale) * (i / 3f)
      val active = phase >= (i / 3.5f)
      pinPaint.color = if (active) Color.rgb(0, 229, 255) else Color.rgb(51, 65, 85)
      canvas.drawCircle(sx, barY, 7f * scale, pinPaint)
    }
  }

  private fun drawBroadcastLowerThird(
    canvas: Canvas,
    scene: VideoScenePlan,
    w: Float,
    h: Float,
    scale: Float,
    phase: Float,
    mouthOpen: Float,
  ) {
    val enter = (phase * 5f).coerceIn(0f, 1f)
    val slideOffset = -(1f - enter) * w * 0.28f
    val box = RectF(w * 0.06f + slideOffset, h * 0.58f, w * 0.58f + slideOffset, h * 0.665f)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(195, 10, 17, 42) }
    val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (mouthOpen > 0.2f) Color.rgb(0, 229, 255) else Color.rgb(250, 204, 21)
    }
    canvas.drawRoundRect(box, 12f * scale, 12f * scale, bg)
    canvas.drawRoundRect(RectF(box.left, box.top, box.left + 7f * scale, box.bottom), 4f * scale, 4f * scale, stripe)

    val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 15.5f * scale
      typeface = Typeface.DEFAULT_BOLD
    }
    val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0, 229, 255)
      textSize = 12f * scale
    }
    canvas.drawText(scene.characterName.ifBlank { scene.overlayHeadline }.take(26), box.left + 16f * scale, box.top + 22f * scale, titleP)
    canvas.drawText(scene.effectTag.take(34), box.left + 16f * scale, box.top + 41f * scale, subP)
  }
}
