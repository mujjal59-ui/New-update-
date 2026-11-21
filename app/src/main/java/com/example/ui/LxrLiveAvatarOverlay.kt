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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.HomeTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Full 60fps Canvas & Layer Animation Engine for LXR--AI Home Screen:
 * - Preserves the reference man's face, smile, hair, watch, hand, posture, and gaming chair 100% locked.
 * - Animates ONLY natural eye blinking + subtle specular gaze catchlight (zero mouth/face distortion).
 * - Animates holographic L X.R globe & counter-rotating orbital rings, ChatGPT/Gemini/Claude/Google Cloud
 *   panels, monitors, RGB PC fans, desk robot blinking eyes, headphones, breeze-swaying plants,
 *   floating digital particles, and moving blue/purple floor reflections.
 * - Supports Extra Themes (LXR Master Studio, Aria Cyber Room, Mitali Cyber Studio).
 */
@Composable
fun LxrLiveAvatarOverlay(
  ox: Float,
  oy: Float,
  dw: Float,
  dh: Float,
  isRunning: Boolean,
  homeTheme: HomeTheme = HomeTheme.LXR_STUDIO,
  px: (Float) -> Dp,
  py: (Float) -> Dp,
) {
  val inf = rememberInfiniteTransition(label = "lxr_master_scene")

  val continuousDeg by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
      label = "deg_9s",
    )
  val fastSpinDeg by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
      label = "fan_spin",
    )
  val wavePhase by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = (2f * PI).toFloat(),
      animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
      label = "wave",
    )
  val breezePhase by
    inf.animateFloat(
      initialValue = -1f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "breeze",
    )
  val auraPulse by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "aura",
    )
  val particleDrift by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
      label = "particles",
    )

  // Natural human blink + very subtle eye catchlight micro-gaze (no mouth or face morphing)
  val blink = remember { Animatable(0f) }
  val eyeGaze = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    var cycle = 0
    while (true) {
      delay(3050L + ((cycle % 4) * 580L))
      blink.animateTo(1f, tween(76, easing = FastOutSlowInEasing))
      delay(24L)
      blink.animateTo(0f, tween(98, easing = FastOutSlowInEasing))
      if (cycle % 5 == 2) {
        delay(175L)
        blink.animateTo(1f, tween(70, easing = FastOutSlowInEasing))
        delay(18L)
        blink.animateTo(0f, tween(92, easing = FastOutSlowInEasing))
      }
      val nextGaze = when (cycle % 4) {
        0 -> 0.55f
        1 -> -0.55f
        2 -> 0.25f
        else -> 0f
      }
      eyeGaze.animateTo(nextGaze, tween(450, easing = FastOutSlowInEasing))
      cycle++
    }
  }

  // Desk robot blink pulse (periodic quick digital cyan blink)
  val robotBlink = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(2700L)
      robotBlink.animateTo(1f, tween(65, easing = LinearEasing))
      delay(45L)
      robotBlink.animateTo(0f, tween(85, easing = LinearEasing))
    }
  }

  val blinkVal = blink.value
  val gazeVal = eyeGaze.value
  val robotBlinkVal = robotBlink.value

  Box(modifier = Modifier.fillMaxSize()) {
    // Theme 2: Mitali foreground character placed cleanly above bottom dock with subtle breathing
    if (homeTheme == HomeTheme.MITALI_CYBER_STUDIO) {
      val mitaliH = dh * 0.67f
      val mitaliW = mitaliH * (663f / 1672f)
      Image(
        painter = painterResource(R.drawable.char_mitali),
        contentDescription = "Mitali Character",
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .offset(y = -(dh * 0.14f).dp + (breezePhase * 1.8f).dp)
            .size(width = mitaliW.dp, height = mitaliH.dp)
            .graphicsLayer { alpha = 0.98f },
        contentScale = ContentScale.Fit,
      )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
      val d = this.density
      fun fx(f: Float): Float = (ox + f * dw) * d
      fun fyPx(f: Float): Float = (oy + f * dh) * d

      // 1. Holographic Globe (L X.R / AI) + Dual Counter-Rotating Orbital Rings
      val globeCx = fx(0.494f)
      val globeCy = fyPx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.080f else 0.092f)
      val globeRx = (0.138f * dw) * d
      val globeRy = (0.048f * dh) * d

      rotate(degrees = 14f + continuousDeg, pivot = Offset(globeCx, globeCy)) {
        drawOval(
          brush =
            Brush.sweepGradient(
              listOf(
                Color.Transparent,
                CYBER_CYAN.copy(alpha = 0.62f),
                Color.White.copy(alpha = 0.55f),
                Color.Transparent,
              ),
              center = Offset(globeCx, globeCy),
            ),
          topLeft = Offset(globeCx - globeRx, globeCy - globeRy),
          size = Size(globeRx * 2f, globeRy * 2f),
          style = Stroke(width = 1.6.dp.toPx()),
        )
        // Orbiting bright energy node
        drawCircle(
          color = Color.White.copy(alpha = 0.85f),
          radius = 2.4.dp.toPx(),
          center = Offset(globeCx + globeRx, globeCy),
        )
      }

      rotate(degrees = -22f - (continuousDeg * 1.15f), pivot = Offset(globeCx, globeCy)) {
        drawOval(
          brush =
            Brush.sweepGradient(
              listOf(
                Color.Transparent,
                Color(0xFF60A5FA).copy(alpha = 0.55f),
                CYBER_PURPLE.copy(alpha = 0.50f),
                Color.Transparent,
              ),
              center = Offset(globeCx, globeCy),
            ),
          topLeft = Offset(globeCx - globeRx * 0.92f, globeCy - globeRy * 0.88f),
          size = Size(globeRx * 1.84f, globeRy * 1.76f),
          style = Stroke(width = 1.4.dp.toPx()),
        )
        drawCircle(
          color = CYBER_CYAN.copy(alpha = 0.90f),
          radius = 2.1.dp.toPx(),
          center = Offset(globeCx - globeRx * 0.92f, globeCy),
        )
      }

      // Subtle holographic diamond shimmer sweeps inside the globe (keeps L X.R text crystal readable)
      val shimmerX = globeCx + sin(wavePhase) * (globeRx * 0.45f)
      drawCircle(
        brush =
          Brush.radialGradient(
            listOf(CYBER_CYAN.copy(alpha = 0.14f + auraPulse * 0.10f), Color.Transparent),
            center = Offset(shimmerX, globeCy),
            radius = globeRx * 0.65f,
          ),
        radius = globeRx * 0.65f,
        center = Offset(shimmerX, globeCy),
      )

      // 2. ChatGPT, Gemini, Claude, Google Cloud & "Dream Big with AI" Panel Animations
      drawHoloPanelFrame(fx(0.045f), fyPx(0.025f), fx(0.305f), fyPx(0.165f), CYBER_CYAN, 0.18f + auraPulse * 0.24f)
      drawHoloPanelFrame(fx(0.695f), fyPx(0.020f), fx(0.955f), fyPx(0.142f), Color(0xFF38BDF8), 0.18f + (1f - auraPulse) * 0.24f)
      drawHoloPanelFrame(fx(0.655f), fyPx(0.145f), fx(0.840f), fyPx(0.240f), Color(0xFF60A5FA), 0.16f + auraPulse * 0.22f)
      drawHoloPanelFrame(fx(0.845f), fyPx(0.140f), fx(0.990f), fyPx(0.258f), Color(0xFF38BDF8), 0.16f + (1f - auraPulse) * 0.22f)

      // "Dream Big with AI" magenta-purple neon pulse
      drawRoundRect(
        color = Color(0xFFD946EF).copy(alpha = 0.10f + auraPulse * 0.22f),
        topLeft = Offset(fx(0.115f), fyPx(0.194f)),
        size = Size(fx(0.305f) - fx(0.115f), fyPx(0.256f) - fyPx(0.194f)),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = Stroke(width = 1.6.dp.toPx()),
      )

      // 3. Computer Monitors: Moving Data Waveforms & Telemetry Bars
      if (homeTheme == HomeTheme.LXR_STUDIO) {
        drawMonitorWave(fx(0.135f), fx(0.365f), fyPx(0.315f), wavePhase, CYBER_CYAN.copy(alpha = 0.65f))
        drawMonitorWave(fx(0.140f), fx(0.340f), fyPx(0.348f), -wavePhase * 1.3f, CYBER_GREEN.copy(alpha = 0.50f))
        // Right monitor small chart bars
        for (b in 0..4) {
          val hFrac = (sin(wavePhase * 1.4f + b * 0.85f) + 1f) * 0.5f
          val bx = fx(0.730f + b * 0.017f)
          val byBottom = fyPx(0.338f)
          val bh = (6f + hFrac * 18f) * d
          drawLine(
            color = CYBER_CYAN.copy(alpha = 0.55f),
            start = Offset(bx, byBottom),
            end = Offset(bx, byBottom - bh),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round,
          )
        }
      } else {
        // Aria / Mitali wider background monitor waveforms
        drawMonitorWave(fx(0.320f), fx(0.640f), fyPx(0.318f), wavePhase, CYBER_CYAN.copy(alpha = 0.55f))
        drawMonitorWave(fx(0.330f), fx(0.620f), fyPx(0.348f), -wavePhase * 1.2f, Color(0xFF60A5FA).copy(alpha = 0.48f))
      }

      // 4. RGB Gaming PC Fans Continuous Rotation + Smooth RGB Case Lighting
      val fanXs = if (homeTheme == HomeTheme.LXR_STUDIO) 0.952f else 0.884f
      val fanYs = if (homeTheme == HomeTheme.LXR_STUDIO) listOf(0.337f, 0.371f, 0.406f) else listOf(0.336f, 0.370f, 0.404f)
      val fanRadius = (0.016f * dw) * d
      fanYs.forEachIndexed { idx, fyVal ->
        val center = Offset(fx(fanXs), fyPx(fyVal))
        val rgbColor = Color.hsv(((continuousDeg * 0.8f) + idx * 75f) % 360f, 0.82f, 1f)
        drawCircle(
          color = rgbColor.copy(alpha = 0.42f),
          radius = fanRadius * 1.25f,
          center = center,
          style = Stroke(width = 1.8.dp.toPx()),
        )
        rotate(degrees = fastSpinDeg + idx * 40f, pivot = center) {
          for (blade in 0..3) {
            val ang = blade * 90.0 * PI / 180.0
            drawLine(
              color = Color.White.copy(alpha = 0.55f),
              start = center,
              end = Offset(center.x + (cos(ang) * fanRadius).toFloat(), center.y + (sin(ang) * fanRadius).toFloat()),
              strokeWidth = 1.4.dp.toPx(),
              cap = StrokeCap.Round,
            )
          }
        }
      }

      // 5. Desk Robot Blinking Eyes & Subtle Headphone Equipment Glow
      val robotLeftEye = Offset(fx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.104f else 0.160f), fyPx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.382f else 0.383f))
      val robotRightEye = Offset(fx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.130f else 0.184f), fyPx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.382f else 0.383f))
      val eyeOpenAlpha = (1f - robotBlinkVal).coerceIn(0.08f, 1f)
      drawCircle(color = CYBER_CYAN.copy(alpha = 0.65f * eyeOpenAlpha), radius = 3.2.dp.toPx(), center = robotLeftEye)
      drawCircle(color = CYBER_CYAN.copy(alpha = 0.65f * eyeOpenAlpha), radius = 3.2.dp.toPx(), center = robotRightEye)

      // Headphone earcup rim shimmer (left side)
      val hpCenter = Offset(fx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.048f else 0.068f), fyPx(0.320f))
      drawCircle(
        color = Color(0xFFD946EF).copy(alpha = 0.22f + auraPulse * 0.28f),
        radius = (0.032f * dw) * d,
        center = hpCenter,
        style = Stroke(width = 2.dp.toPx()),
      )

      // 6. Plants & Leaves Gentle Breeze Sway Highlights
      val breezeDx = breezePhase * 3.5.dp.toPx()
      listOf(
        Offset(fx(0.038f), fyPx(0.085f)),
        Offset(fx(0.885f), fyPx(0.268f)),
        Offset(fx(0.036f), fyPx(0.555f)),
      ).forEachIndexed { idx, p ->
        val phaseShift = if (idx % 2 == 0) breezeDx else -breezeDx * 0.8f
        drawCircle(
          brush =
            Brush.radialGradient(
              listOf(Color(0xFF4ADE80).copy(alpha = 0.16f + auraPulse * 0.10f), Color.Transparent),
              center = Offset(p.x + phaseShift, p.y),
              radius = 18.dp.toPx(),
            ),
          radius = 18.dp.toPx(),
          center = Offset(p.x + phaseShift, p.y),
        )
      }

      // 7. Floating Digital Holographic Particles
      for (i in 0..13) {
        val seedX = ((i * 73 + 19) % 94) / 100f + 0.03f
        val baseY = ((i * 41 + 11) % 62) / 100f + 0.03f
        val driftY = (baseY - particleDrift * 0.18f + 1f) % 0.66f
        val swayX = sin(wavePhase + i * 0.9f) * 4.dp.toPx()
        val pAlpha = (0.20f + 0.45f * sin((particleDrift + i * 0.13f) * PI.toFloat()).let { if (it < 0f) -it else it })
        drawCircle(
          color = if (i % 3 == 0) Color(0xFFD946EF).copy(alpha = pAlpha) else CYBER_CYAN.copy(alpha = pAlpha),
          radius = (1.1f + (i % 3) * 0.6f).dp.toPx(),
          center = Offset(fx(seedX) + swayX, fyPx(driftY)),
        )
      }

      // 8. Floor Moving Blue & Purple Reflections
      val reflShiftX = sin(wavePhase * 0.7f) * (0.14f * dw) * d
      drawRect(
        brush =
          Brush.horizontalGradient(
            listOf(
              Color.Transparent,
              Color(0xFF7C3AED).copy(alpha = 0.16f + auraPulse * 0.12f),
              CYBER_CYAN.copy(alpha = 0.18f + (1f - auraPulse) * 0.10f),
              Color.Transparent,
            ),
            startX = fx(0.10f) + reflShiftX,
            endX = fx(0.90f) + reflShiftX,
          ),
        topLeft = Offset(0f, fyPx(0.945f)),
        size = Size(size.width, fyPx(1f) - fyPx(0.945f)),
      )

      // 9. Locked Foreground Face: Eye-ONLY Natural Blinking & Micro Catchlight (Theme 0 Master Portrait)
      if (homeTheme == HomeTheme.LXR_STUDIO) {
        drawNaturalEyelidBlink(
          leftX = fx(0.469f),
          rightX = fx(0.531f),
          topY = fyPx(0.3618f),
          midY = fyPx(0.3675f),
          botY = fyPx(0.3735f),
          lidColors = listOf(Color(0xFF8C595D), Color(0xFF724249), Color(0xFF4E2B31)),
          blinkVal = blinkVal,
          gazeShiftPx = gazeVal * 1.3.dp.toPx(),
          catchlightPos = Offset(fx(0.502f), fyPx(0.3665f)),
        )
        drawNaturalEyelidBlink(
          leftX = fx(0.611f),
          rightX = fx(0.674f),
          topY = fyPx(0.3688f),
          midY = fyPx(0.3748f),
          botY = fyPx(0.3808f),
          lidColors = listOf(Color(0xFF5E3539), Color(0xFF462328), Color(0xFF2F1519)),
          blinkVal = blinkVal,
          gazeShiftPx = gazeVal * 1.3.dp.toPx(),
          catchlightPos = Offset(fx(0.642f), fyPx(0.3738f)),
        )
      }

      // Active voice listening halo synced with mic state
      if (isRunning) {
        val micCenter = Offset(fx(0.499f), fyPx(0.696f))
        val pulseR = (0.118f * dw) * d + (auraPulse * 7.dp.toPx())
        drawCircle(
          color = CYBER_CYAN.copy(alpha = 0.40f + auraPulse * 0.35f),
          radius = pulseR,
          center = micCenter,
          style = Stroke(width = 2.4.dp.toPx()),
        )
      }
    }
  }
}

private fun DrawScope.drawHoloPanelFrame(
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
  color: Color,
  alpha: Float,
) {
  drawRoundRect(
    color = color.copy(alpha = alpha),
    topLeft = Offset(left, top),
    size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
    cornerRadius = CornerRadius(6.dp.toPx()),
    style = Stroke(width = 1.4.dp.toPx()),
  )
}

private fun DrawScope.drawMonitorWave(
  xStart: Float,
  xEnd: Float,
  centerY: Float,
  phase: Float,
  color: Color,
) {
  val path = Path()
  val span = xEnd - xStart
  for (s in 0..24) {
    val t = s / 24f
    val x = xStart + t * span
    val amp = (sin(t * 14f - phase) * 4.5f + sin(t * 25f + phase) * 2.2f).dp.toPx()
    if (s == 0) path.moveTo(x, centerY + amp) else path.lineTo(x, centerY + amp)
  }
  drawPath(path, color = color, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawNaturalEyelidBlink(
  leftX: Float,
  rightX: Float,
  topY: Float,
  midY: Float,
  botY: Float,
  lidColors: List<Color>,
  blinkVal: Float,
  gazeShiftPx: Float,
  catchlightPos: Offset,
) {
  if (blinkVal > 0.04f) {
    val cx = (leftX + rightX) * 0.5f
    val apertureH = botY - topY
    val aperturePath =
      Path().apply {
        moveTo(leftX, midY)
        quadraticTo(cx, topY - apertureH * 0.42f, rightX, midY)
        quadraticTo(cx, botY + apertureH * 0.35f, leftX, midY)
        close()
      }
    val lidBottomY = topY - apertureH * 0.22f + (apertureH * 1.36f) * blinkVal

    clipPath(aperturePath) {
      drawRect(
        brush =
          Brush.verticalGradient(
            colors = lidColors,
            startY = topY - apertureH * 0.35f,
            endY = lidBottomY.coerceAtLeast(topY + 1f),
          ),
        topLeft = Offset(leftX - 2f, topY - apertureH * 0.45f),
        size = Size((rightX - leftX) + 4f, (lidBottomY - (topY - apertureH * 0.45f)).coerceAtLeast(1f)),
      )
      val lashY = lidBottomY - 0.7.dp.toPx()
      val lashPath =
        Path().apply {
          moveTo(leftX, midY)
          quadraticTo(cx, lashY + 1.1.dp.toPx() * blinkVal, rightX, midY)
        }
      drawPath(
        path = lashPath,
        color = Color(0xFF190F13).copy(alpha = 0.85f * blinkVal),
        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
      )
    }
  } else {
    drawCircle(
      color = Color.White.copy(alpha = 0.22f),
      radius = 1.35.dp.toPx(),
      center = Offset(catchlightPos.x + gazeShiftPx, catchlightPos.y),
    )
  }
}
