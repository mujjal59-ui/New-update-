package com.example.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Renders:
 * 1. Continuous cyber program animations over the background monitor panels (waveforms, radar HUD, LED grids, scan beam).
 * 2. Crisp, anatomical, photorealistic fast eye blinking clipped strictly inside each eye aperture (zero grey blobs or eyebrow overlap) + clean face with zero mustache/lip lines.
 * 3. Glowing futuristic "LXR--AI" header branding in place of old background text.
 */
@Composable
fun LxrLiveAvatarOverlay(
  ox: Float,
  oy: Float,
  dw: Float,
  dh: Float,
  isRunning: Boolean,
  px: (Float) -> Dp,
  py: (Float) -> Dp,
) {
  val inf = rememberInfiniteTransition(label = "lxr_cyber_bg")

  val wavePhase by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = (2f * PI).toFloat(),
      animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
      label = "wave",
    )
  val radarAngle by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing)),
      label = "radar",
    )
  val auraPulse by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "aura",
    )
  val scanProgress by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
      label = "scan",
    )

  // Crisp, lifelike natural eye blink (fast down-hold-up so eyes never look half-shut or droopy)
  val blink = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    var cycle = 0
    while (true) {
      delay(3100L + ((cycle % 3) * 650L))
      blink.animateTo(1f, tween(78, easing = FastOutSlowInEasing))
      delay(24L)
      blink.animateTo(0f, tween(96, easing = FastOutSlowInEasing))
      if (cycle % 5 == 3) {
        delay(170L)
        blink.animateTo(1f, tween(72, easing = FastOutSlowInEasing))
        delay(20L)
        blink.animateTo(0f, tween(90, easing = FastOutSlowInEasing))
      }
      cycle++
    }
  }

  val blinkVal = blink.value

  Canvas(modifier = Modifier.fillMaxSize()) {
    val density = this.density
    fun fx(f: Float): Float = (ox + f * dw) * density
    fun fyPx(f: Float): Float = (oy + f * dh) * density

    // --- 1. Animated Background Cyber Monitor Programs (Left, Top, Right panels) ---
    val leftWavePath = Path()
    val lwLeft = fx(0.035f)
    val lwWidth = (0.255f * dw) * density
    val lwCenterY = fyPx(0.305f)
    for (s in 0..32) {
      val t = s / 32f
      val x = lwLeft + t * lwWidth
      val amp = (sin(t * 14f - wavePhase) * 7f + sin(t * 27f + wavePhase * 1.4f) * 3.5f) * density
      if (s == 0) leftWavePath.moveTo(x, lwCenterY + amp) else leftWavePath.lineTo(x, lwCenterY + amp)
    }
    drawPath(leftWavePath, color = Color(0xFF00E5FF).copy(alpha = 0.55f), style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))

    val midWavePath = Path()
    val mwCenterY = fyPx(0.415f)
    for (s in 0..28) {
      val t = s / 28f
      val x = fx(0.035f) + t * (0.23f * dw) * density
      val amp = (sin(t * 11f + wavePhase * 1.3f) * 6.5f) * density
      if (s == 0) midWavePath.moveTo(x, mwCenterY + amp) else midWavePath.lineTo(x, mwCenterY + amp)
    }
    drawPath(midWavePath, color = Color(0xFF00E676).copy(alpha = 0.45f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))

    val ledY = fyPx(0.515f)
    for (i in 0..6) {
      val p = (sin(wavePhase + i * 0.9f) + 1f) * 0.5f
      val c = if (i % 3 == 0) Color(0xFFFF1744) else if (i % 2 == 0) Color(0xFF00E676) else Color(0xFF00E5FF)
      drawCircle(
        color = c.copy(alpha = 0.30f + p * 0.55f),
        radius = (2.2f + p * 1.1f).dp.toPx(),
        center = Offset(fx(0.075f + i * 0.029f), ledY),
      )
    }

    val radarCenter = Offset(fx(0.845f), fyPx(0.285f))
    val radarR = (0.072f * dw) * density
    rotate(degrees = radarAngle, pivot = radarCenter) {
      drawArc(
        color = Color(0xFF00E5FF).copy(alpha = 0.48f),
        startAngle = 0f,
        sweepAngle = 115f,
        useCenter = false,
        topLeft = Offset(radarCenter.x - radarR, radarCenter.y - radarR),
        size = Size(radarR * 2f, radarR * 2f),
        style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round),
      )
      drawArc(
        color = Color(0xFF2979FF).copy(alpha = 0.45f),
        startAngle = 180f,
        sweepAngle = 95f,
        useCenter = false,
        topLeft = Offset(radarCenter.x - radarR * 0.68f, radarCenter.y - radarR * 0.68f),
        size = Size(radarR * 1.36f, radarR * 1.36f),
        style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round),
      )
    }

    for (row in 0..2) {
      for (col in 0..2) {
        val idx = row * 3 + col
        val glow = (sin(wavePhase * 1.5f + idx * 0.7f) + 1f) * 0.5f
        drawCircle(
          color = Color(0xFF00E5FF).copy(alpha = 0.16f + glow * 0.34f),
          radius = 3.2.dp.toPx(),
          center = Offset(fx(0.875f + col * 0.036f), fyPx(0.392f + row * 0.025f)),
        )
      }
    }

    val scanY = fyPx(0.22f + scanProgress * 0.39f)
    drawLine(
      brush = Brush.horizontalGradient(
        listOf(
          Color.Transparent,
          Color(0xFF00E5FF).copy(alpha = 0.24f),
          Color.Transparent,
        )
      ),
      start = Offset(fx(0.03f), scanY),
      end = Offset(fx(0.97f), scanY),
      strokeWidth = 2.dp.toPx(),
    )

    // --- 2. Natural Anatomical Eye Blinking (Exact pixel-measured eye openings & matched skin tone) ---
    data class EyeSpec(
      val leftX: Float,
      val rightX: Float,
      val topY: Float,
      val midY: Float,
      val botY: Float,
      val lidTopColor: Color,
      val lidMidColor: Color,
      val lidShadowColor: Color,
    )

    val eyes =
      listOf(
        // Left eye (viewer's left) measured directly from home_bg.png: x=0.484..0.540, y=0.4350..0.4430
        EyeSpec(
          leftX = fx(0.4835f),
          rightX = fx(0.5410f),
          topY = fyPx(0.4348f),
          midY = fyPx(0.4390f),
          botY = fyPx(0.4434f),
          lidTopColor = Color(0xFFB46C5F),
          lidMidColor = Color(0xFF9E5A4F),
          lidShadowColor = Color(0xFF6B3934),
        ),
        // Right eye (viewer's right) measured directly from home_bg.png: x=0.611..0.670, y=0.4408..0.4496
        EyeSpec(
          leftX = fx(0.6110f),
          rightX = fx(0.6705f),
          topY = fyPx(0.4406f),
          midY = fyPx(0.4452f),
          botY = fyPx(0.4498f),
          lidTopColor = Color(0xFF904D41),
          lidMidColor = Color(0xFF7B3E36),
          lidShadowColor = Color(0xFF542824),
        ),
      )

    if (blinkVal > 0.04f) {
      eyes.forEach { e ->
        val cx = (e.leftX + e.rightX) * 0.5f
        val apertureH = e.botY - e.topY
        val aperturePath =
          Path().apply {
            moveTo(e.leftX, e.midY)
            quadraticTo(cx, e.topY - apertureH * 0.45f, e.rightX, e.midY)
            quadraticTo(cx, e.botY + apertureH * 0.38f, e.leftX, e.midY)
            close()
          }

        val lidBottomY = e.topY - apertureH * 0.25f + (apertureH * 1.38f) * blinkVal

        clipPath(aperturePath) {
          drawRect(
            brush = Brush.verticalGradient(
              colors = listOf(e.lidTopColor, e.lidMidColor, e.lidShadowColor),
              startY = e.topY - apertureH * 0.4f,
              endY = lidBottomY.coerceAtLeast(e.topY + 1f),
            ),
            topLeft = Offset(e.leftX - 2f, e.topY - apertureH * 0.5f),
            size = Size((e.rightX - e.leftX) + 4f, (lidBottomY - (e.topY - apertureH * 0.5f)).coerceAtLeast(1f)),
          )

          // Sleek dark upper-lash curve hugging the descending eyelid edge
          val lashCurveY = lidBottomY - 0.8.dp.toPx()
          val lashPath =
            Path().apply {
              moveTo(e.leftX, e.midY)
              quadraticTo(cx, lashCurveY + 1.2.dp.toPx() * blinkVal, e.rightX, e.midY)
            }
          drawPath(
            path = lashPath,
            color = Color(0xFF1A1014).copy(alpha = 0.85f * blinkVal),
            style = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round),
          )
        }
      }
    } else {
      // Subtle attractive specular catchlight twinkle on eyes when open (no smudges or marks)
      val glintAlpha = 0.14f + auraPulse * 0.14f
      drawCircle(
        color = Color.White.copy(alpha = glintAlpha),
        radius = 1.4.dp.toPx(),
        center = Offset(fx(0.511f), fyPx(0.4375f)),
      )
      drawCircle(
        color = Color.White.copy(alpha = glintAlpha),
        radius = 1.4.dp.toPx(),
        center = Offset(fx(0.640f), fyPx(0.4438f)),
      )
    }

    // Note: Intentionally NO dark stroke on mustache/lips so face remains 100% crisp and natural.

    // Active Mic/Voice cyan aura ring around central mic orb
    if (isRunning) {
      val micCenter = Offset(fx(0.5f), fyPx(0.7428f))
      val pulseR = (0.105f * dw) * density + (auraPulse * 6.dp.toPx())
      drawCircle(
        color = CYBER_CYAN.copy(alpha = 0.35f + auraPulse * 0.30f),
        radius = pulseR,
        center = micCenter,
        style = Stroke(width = 2.2.dp.toPx()),
      )
    }
  }

  // --- 3. Glowing LXR--AI Top & Holographic Frame Titles ---
  Box(
    modifier = Modifier.fillMaxSize(),
  ) {
    Text(
      text = "LXR--AI",
      color = Color.White,
      fontSize = 26.sp,
      fontWeight = FontWeight.ExtraBold,
      letterSpacing = 3.sp,
      modifier = Modifier.align(Alignment.TopCenter).offset(y = py(0.118f)),
    )

    Box(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .offset(y = py(0.195f))
          .clip(RoundedCornerShape(14.dp))
          .background(
            Brush.horizontalGradient(
              listOf(Color(0xB8061C3B), Color(0xE00D2E5E), Color(0xB8061C3B))
            )
          )
          .border(
            1.2.dp,
            CYBER_CYAN.copy(alpha = 0.55f + auraPulse * 0.35f),
            RoundedCornerShape(14.dp),
          )
          .padding(horizontal = 22.dp, vertical = 4.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "LXR--AI",
        color = CYBER_CYAN,
        fontSize = 21.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.5.sp,
      )
    }
  }
}
