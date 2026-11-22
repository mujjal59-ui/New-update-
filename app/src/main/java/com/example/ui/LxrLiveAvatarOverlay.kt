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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.HomeTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Separated multi-layer 60fps Visual Engine for LXR--AI Home Screen:
 * 1. Background Animation Layer (L X.R 3D Globe, counter-rotating orbital rings, ChatGPT/Gemini/Claude/Cloud
 *    panels, monitors, RGB PC fans, robot eyes, headphones, plants, floor reflections, particles, back petals).
 * 2. Locked Original Foreground Man Face (`LXR_STUDIO`) — preserves face/smile/hair/watch/chair 100%,
 *    animating ONLY natural photographic eyelid blinking (`eye_blink_lxr`).
 * 3. Extra Animated Theme Character Layer (`ARIA_CYBER_ROOM`, `MITALI_CYBER_STUDIO`) —
 *    renders above bottom controls with breathing motion & pixel-exact synchronized tilted eyelid blinking
 *    (`eye_blink_aria`, `eye_blink_mitali`), ZERO floating ovals & ZERO talking.
 * 4. Foreground Falling Flower Petals Layer — pink, purple & subtle blue petals avoiding static occlusion over faces/buttons.
 */
@Composable
fun LxrLiveAvatarOverlay(
  ox: Float,
  oy: Float,
  dw: Float,
  dh: Float,
  isRunning: Boolean,
  isSpeaking: Boolean = false,
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
      animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing)),
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
      animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "aura",
    )
  val particleDrift by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
      label = "particles",
    )
  val petalFall by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(11500, easing = LinearEasing), RepeatMode.Restart),
      label = "petal_fall",
    )

  // Natural silky-smooth human eyelid blink
  val blink = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    var cycle = 0
    while (true) {
      delay(3100L + ((cycle % 4) * 580L))
      blink.animateTo(1f, tween(88, easing = FastOutSlowInEasing))
      delay(22L)
      blink.animateTo(0f, tween(122, easing = FastOutSlowInEasing))
      if (cycle % 5 == 2) {
        delay(175L)
        blink.animateTo(1f, tween(80, easing = FastOutSlowInEasing))
        delay(16L)
        blink.animateTo(0f, tween(114, easing = FastOutSlowInEasing))
      }
      cycle++
    }
  }

  // Desk robot blink pulse
  val robotBlink = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(2600L)
      robotBlink.animateTo(1f, tween(65, easing = LinearEasing))
      delay(45L)
      robotBlink.animateTo(0f, tween(85, easing = LinearEasing))
    }
  }

  val blinkVal = blink.value
  val blinkEased = sin(blinkVal.coerceIn(0f, 1f) * PI.toFloat() * 0.5f).coerceIn(0f, 1f)
  val robotBlinkVal = robotBlink.value

  Box(modifier = Modifier.fillMaxSize()) {
    // LAYER 1: Studio Background Animations + Background-Depth Falling Petals
    Canvas(modifier = Modifier.fillMaxSize()) {
      val d = this.density
      fun fx(f: Float): Float = (ox + f * dw) * d
      fun fyPx(f: Float): Float = (oy + f * dh) * d

      // 1A. Holographic 3D Earth (L X.R / AI) + Dual Counter-Rotating Orbital Rings
      val globeCx = fx(0.494f)
      val globeCy = fyPx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.088f else 0.092f)
      val globeRx = (0.142f * dw) * d
      val globeRy = (0.049f * dh) * d

      // Subtle rotating internal 3D longitude arc behind L X.R text
      val lonFactor = sin(wavePhase * 0.65f)
      if (abs(lonFactor) > 0.06f) {
        val merRx = abs(lonFactor) * (globeRx * 0.65f)
        drawOval(
          color = CYBER_CYAN.copy(alpha = 0.22f),
          topLeft = Offset(globeCx - merRx, globeCy - globeRy * 0.96f),
          size = Size(merRx * 2f, globeRy * 1.92f),
          style = Stroke(width = 1.1.dp.toPx()),
        )
      }

      rotate(degrees = 14f + continuousDeg, pivot = Offset(globeCx, globeCy)) {
        drawOval(
          brush =
            Brush.sweepGradient(
              listOf(
                Color.Transparent,
                CYBER_CYAN.copy(alpha = 0.68f),
                Color.White.copy(alpha = 0.60f),
                Color.Transparent,
              ),
              center = Offset(globeCx, globeCy),
            ),
          topLeft = Offset(globeCx - globeRx, globeCy - globeRy),
          size = Size(globeRx * 2f, globeRy * 2f),
          style = Stroke(width = 1.6.dp.toPx()),
        )
        drawCircle(
          color = Color.White.copy(alpha = 0.88f),
          radius = 2.4.dp.toPx(),
          center = Offset(globeCx + globeRx, globeCy),
        )
      }

      rotate(degrees = -22f - (continuousDeg * 1.18f), pivot = Offset(globeCx, globeCy)) {
        drawOval(
          brush =
            Brush.sweepGradient(
              listOf(
                Color.Transparent,
                Color(0xFF60A5FA).copy(alpha = 0.58f),
                CYBER_PURPLE.copy(alpha = 0.52f),
                Color.Transparent,
              ),
              center = Offset(globeCx, globeCy),
            ),
          topLeft = Offset(globeCx - globeRx * 0.92f, globeCy - globeRy * 0.88f),
          size = Size(globeRx * 1.84f, globeRy * 1.76f),
          style = Stroke(width = 1.4.dp.toPx()),
        )
        drawCircle(
          color = CYBER_CYAN.copy(alpha = 0.92f),
          radius = 2.1.dp.toPx(),
          center = Offset(globeCx - globeRx * 0.92f, globeCy),
        )
      }

      val shimmerX = globeCx + sin(wavePhase) * (globeRx * 0.42f)
      drawCircle(
        brush =
          Brush.radialGradient(
            listOf(CYBER_CYAN.copy(alpha = 0.13f + auraPulse * 0.09f), Color.Transparent),
            center = Offset(shimmerX, globeCy),
            radius = globeRx * 0.62f,
          ),
        radius = globeRx * 0.62f,
        center = Offset(shimmerX, globeCy),
      )

      // 1B. ChatGPT, Gemini, Claude, Google Cloud & "Dream Big with AI" Panels
      drawHoloAmbientGlow(fx(0.175f), fyPx(0.095f), (0.14f * dw) * d, CYBER_CYAN, 0.14f + auraPulse * 0.12f)
      drawHoloAmbientGlow(fx(0.825f), fyPx(0.080f), (0.14f * dw) * d, Color(0xFF38BDF8), 0.14f + (1f - auraPulse) * 0.12f)
      drawHoloAmbientGlow(fx(0.745f), fyPx(0.192f), (0.10f * dw) * d, Color(0xFF60A5FA), 0.12f + auraPulse * 0.10f)
      drawHoloAmbientGlow(fx(0.920f), fyPx(0.198f), (0.10f * dw) * d, Color(0xFF38BDF8), 0.12f + (1f - auraPulse) * 0.10f)
      drawHoloAmbientGlow(fx(0.215f), fyPx(0.226f), (0.095f * dw) * d, Color(0xFFD946EF), 0.10f + auraPulse * 0.12f)

      // 1C. Computer Monitors
      if (homeTheme == HomeTheme.LXR_STUDIO) {
        drawMonitorWave(fx(0.145f), fx(0.285f), fyPx(0.312f), wavePhase, CYBER_CYAN.copy(alpha = 0.58f))
        drawMonitorWave(fx(0.150f), fx(0.280f), fyPx(0.340f), -wavePhase * 1.25f, Color(0xFF60A5FA).copy(alpha = 0.50f))
        for (b in 0..4) {
          val hFrac = (sin(wavePhase * 1.4f + b * 0.85f) + 1f) * 0.5f
          val bx = fx(0.732f + b * 0.015f)
          val byBottom = fyPx(0.336f)
          val bh = (5f + hFrac * 15f) * d
          drawLine(
            color = CYBER_CYAN.copy(alpha = 0.55f),
            start = Offset(bx, byBottom),
            end = Offset(bx, byBottom - bh),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
          )
        }
      } else {
        drawMonitorWave(fx(0.335f), fx(0.625f), fyPx(0.316f), wavePhase, CYBER_CYAN.copy(alpha = 0.52f))
        drawMonitorWave(fx(0.345f), fx(0.615f), fyPx(0.344f), -wavePhase * 1.2f, Color(0xFF60A5FA).copy(alpha = 0.45f))
      }

      // 1D. RGB Gaming PC Fans
      val fanXs = if (homeTheme == HomeTheme.LXR_STUDIO) 0.980f else 0.884f
      val fanYs = if (homeTheme == HomeTheme.LXR_STUDIO) listOf(0.337f, 0.371f, 0.406f) else listOf(0.336f, 0.370f, 0.404f)
      val fanRadius = (0.014f * dw) * d
      fanYs.forEachIndexed { idx, fyVal ->
        val center = Offset(fx(fanXs), fyPx(fyVal))
        val rgbColor = Color.hsv(((continuousDeg * 0.85f) + idx * 80f) % 360f, 0.85f, 1f)
        drawCircle(
          color = rgbColor.copy(alpha = 0.45f),
          radius = fanRadius * 1.28f,
          center = center,
          style = Stroke(width = 1.6.dp.toPx()),
        )
        rotate(degrees = fastSpinDeg + idx * 45f, pivot = center) {
          for (blade in 0..4) {
            val ang = blade * 72.0 * PI / 180.0
            drawLine(
              color = rgbColor.copy(alpha = 0.60f),
              start = center,
              end = Offset(center.x + (cos(ang) * fanRadius).toFloat(), center.y + (sin(ang) * fanRadius).toFloat()),
              strokeWidth = 1.3.dp.toPx(),
              cap = StrokeCap.Round,
            )
          }
        }
      }

      // 1E. Desk Robot Blinking Eyes & Headphone Rim Glow
      val robotLeftEye = Offset(fx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.104f else 0.160f), fyPx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.382f else 0.383f))
      val robotRightEye = Offset(fx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.130f else 0.184f), fyPx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.382f else 0.383f))
      val eyeOpenAlpha = (1f - robotBlinkVal).coerceIn(0.10f, 1f)
      drawCircle(color = CYBER_CYAN.copy(alpha = 0.70f * eyeOpenAlpha), radius = 3.2.dp.toPx(), center = robotLeftEye)
      drawCircle(color = CYBER_CYAN.copy(alpha = 0.70f * eyeOpenAlpha), radius = 3.2.dp.toPx(), center = robotRightEye)

      val hpCenter = Offset(fx(if (homeTheme == HomeTheme.LXR_STUDIO) 0.048f else 0.068f), fyPx(0.320f))
      drawCircle(
        color = Color(0xFFD946EF).copy(alpha = 0.24f + auraPulse * 0.28f),
        radius = (0.032f * dw) * d,
        center = hpCenter,
        style = Stroke(width = 2.dp.toPx()),
      )

      // 1F. Plants & Leaves Gentle Breeze Highlights
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
              listOf(Color(0xFF4ADE80).copy(alpha = 0.17f + auraPulse * 0.10f), Color.Transparent),
              center = Offset(p.x + phaseShift, p.y),
              radius = 18.dp.toPx(),
            ),
          radius = 18.dp.toPx(),
          center = Offset(p.x + phaseShift, p.y),
        )
      }

      // 1G. Floating Holographic Particles
      for (i in 0..13) {
        val seedX = ((i * 73 + 19) % 94) / 100f + 0.03f
        val baseY = ((i * 41 + 11) % 62) / 100f + 0.03f
        val driftY = (baseY - particleDrift * 0.18f + 1f) % 0.66f
        val swayX = sin(wavePhase + i * 0.9f) * 4.dp.toPx()
        val pAlpha = 0.22f + 0.42f * abs(sin((particleDrift + i * 0.13f) * PI.toFloat()))
        drawCircle(
          color = if (i % 3 == 0) Color(0xFFD946EF).copy(alpha = pAlpha) else CYBER_CYAN.copy(alpha = pAlpha),
          radius = (1.1f + (i % 3) * 0.6f).dp.toPx(),
          center = Offset(fx(seedX) + swayX, fyPx(driftY)),
        )
      }

      // 1H. Moving Blue & Purple Cyber Floor Reflections
      val reflShiftX = sin(wavePhase * 0.7f) * (0.15f * dw) * d
      drawRect(
        brush =
          Brush.horizontalGradient(
            listOf(
              Color.Transparent,
              Color(0xFF7C3AED).copy(alpha = 0.18f + auraPulse * 0.12f),
              CYBER_CYAN.copy(alpha = 0.20f + (1f - auraPulse) * 0.10f),
              Color.Transparent,
            ),
            startX = fx(0.08f) + reflShiftX,
            endX = fx(0.92f) + reflShiftX,
          ),
        topLeft = Offset(0f, fyPx(0.935f)),
        size = Size(size.width, fyPx(1f) - fyPx(0.935f)),
      )

      if (homeTheme == HomeTheme.LXR_STUDIO && isRunning) {
        val micCenter = Offset(fx(0.498f), fyPx(0.695f))
        val pulseR = (0.095f * dw) * d + (auraPulse * 5.dp.toPx())
        drawCircle(
          color = CYBER_CYAN.copy(alpha = 0.45f + auraPulse * 0.35f),
          radius = pulseR,
          center = micCenter,
          style = Stroke(width = 2.4.dp.toPx()),
        )
      }

      // 1I. Background-Depth Falling Flower Petals
      drawFallingPetalLayer(
        petalFall = petalFall,
        wavePhase = wavePhase,
        isForeground = false,
        avoidFaceRect = homeTheme == HomeTheme.LXR_STUDIO,
        fx = ::fx,
        fyPx = ::fyPx,
      )
    }

    // 2. Locked Original Foreground Man Face (`LXR_STUDIO`):
    // Photographic closed-eyelid sprite (`eye_blink_lxr`) revealed strictly from top crease down
    if (homeTheme == HomeTheme.LXR_STUDIO && blinkEased > 0.02f) {
      Image(
        painter = painterResource(R.drawable.eye_blink_lxr),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier =
          Modifier
            .offset(x = px(0.457308f), y = py(0.356771f))
            .size(width = (0.234443f * dw).dp, height = (0.028646f * dh).dp)
            .graphicsLayer { alpha = (0.22f + 0.78f * blinkEased).coerceIn(0f, 1f) }
            .drawWithContent {
              clipRect(top = 0f, bottom = size.height * blinkEased) {
                this@drawWithContent.drawContent()
              }
            },
      )
    }

    // LAYER 2: Non-Talking Animated Theme Characters (Aria or Mitali)
    if (homeTheme == HomeTheme.ARIA_CYBER_ROOM || homeTheme == HomeTheme.MITALI_CYBER_STUDIO) {
      val charH = dh * 0.65f
      val charRatio = if (homeTheme == HomeTheme.ARIA_CYBER_ROOM) 660f / 1536f else 663f / 1672f
      val charW = charH * charRatio
      val charRes = if (homeTheme == HomeTheme.ARIA_CYBER_ROOM) R.drawable.char_aria else R.drawable.char_mitali

      BoxWithConstraints(
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .offset(y = -(dh * 0.132f).dp + (breezePhase * 2.0f).dp)
            .size(width = charW.dp, height = charH.dp)
      ) {
        val cw = maxWidth.value
        val ch = maxHeight.value

        Image(
          painter = painterResource(charRes),
          contentDescription = "${homeTheme.name} Animated Character",
          modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.99f },
          contentScale = ContentScale.Fit,
        )

        // Pixel-accurate anatomical eyelid blink sprites synced along exact eye tilt (NO floating ovals!)
        if (blinkEased > 0.02f) {
          val isAria = homeTheme == HomeTheme.ARIA_CYBER_ROOM
          val blinkRes = if (isAria) R.drawable.eye_blink_aria else R.drawable.eye_blink_mitali
          val bxFrac = if (isAria) 0.348485f else 0.349925f
          val byFrac = if (isAria) 0.074219f else 0.080144f
          val bwFrac = if (isAria) 0.265152f else 0.232278f
          val bhFrac = if (isAria) 0.055990f else 0.044258f

          Image(
            painter = painterResource(blinkRes),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier =
              Modifier
                .offset(x = (cw * bxFrac).dp, y = (ch * byFrac).dp)
                .size(width = (cw * bwFrac).dp, height = (ch * bhFrac).dp)
                .graphicsLayer { alpha = (0.25f + 0.75f * blinkEased).coerceIn(0f, 1f) }
                .drawWithContent {
                  val w = size.width
                  val h = size.height
                  val splitX = if (isAria) w * 0.543f else w * 0.474f
                  val slope = if (isAria) -0.28f else -0.26f
                  val leftStartY = (if (isAria) 0.28f else 0.34f) * h
                  val rightStartY = (if (isAria) 0.02f else 0.04f) * h
                  val leftSweepY = leftStartY + ((h * 1.12f - leftStartY) * blinkEased)
                  val rightSweepY = rightStartY + ((h * 1.06f - rightStartY) * blinkEased)

                  val leftClip =
                    Path().apply {
                      moveTo(0f, -h * 0.15f)
                      lineTo(splitX, -h * 0.15f)
                      lineTo(splitX, leftSweepY + (splitX * 0.5f) * slope)
                      lineTo(0f, leftSweepY - (splitX * 0.5f) * slope)
                      close()
                    }
                  val rightClip =
                    Path().apply {
                      moveTo(splitX, -h * 0.15f)
                      lineTo(w, -h * 0.15f)
                      lineTo(w, rightSweepY + ((w - splitX) * 0.5f) * slope)
                      lineTo(splitX, rightSweepY - ((w - splitX) * 0.5f) * slope)
                      close()
                    }
                  clipPath(leftClip) { this@drawWithContent.drawContent() }
                  clipPath(rightClip) { this@drawWithContent.drawContent() }
                },
          )
        }
      }
    }

    // LAYER 3: Foreground Falling Flower Petals (drifting gently in front with clear center safety)
    Canvas(modifier = Modifier.fillMaxSize()) {
      val d = this.density
      fun fx(f: Float): Float = (ox + f * dw) * d
      fun fyPx(f: Float): Float = (oy + f * dh) * d

      drawFallingPetalLayer(
        petalFall = petalFall,
        wavePhase = wavePhase,
        isForeground = true,
        avoidFaceRect = true,
        fx = ::fx,
        fyPx = ::fyPx,
      )
    }
  }
}

private fun DrawScope.drawFallingPetalLayer(
  petalFall: Float,
  wavePhase: Float,
  isForeground: Boolean,
  avoidFaceRect: Boolean,
  fx: (Float) -> Float,
  fyPx: (Float) -> Float,
) {
  val count = if (isForeground) 12 else 14
  val palette =
    listOf(
      Color(0xFFF472B6), // soft pink
      Color(0xFFC084FC), // lavender purple
      Color(0xFF60A5FA), // subtle cyber blue
    )
  for (i in 0 until count) {
    val speedMult = if (isForeground) 1.0f else 0.78f
    val yFrac = ((petalFall * speedMult + i * 0.083f + (i % 3) * 0.04f) % 1.08f) - 0.04f
    val baseLane =
      if (isForeground) {
        val raw = ((i * 29 + 11) % 92) / 100f + 0.04f
        if (avoidFaceRect && yFrac in 0.22f..0.46f && raw in 0.34f..0.70f) {
          if (i % 2 == 0) 0.14f + (i % 4) * 0.045f else 0.76f + (i % 3) * 0.055f
        } else raw
      } else {
        ((i * 37 + 7) % 94) / 100f + 0.03f
      }
    val swayX = sin(wavePhase * 0.85f + i * 1.3f) * (if (isForeground) 0.026f else 0.018f)
    val xFrac = (baseLane + swayX).coerceIn(0.02f, 0.98f)

    val nearFace = avoidFaceRect && xFrac in 0.34f..0.68f && yFrac in 0.23f..0.44f
    val nearMicOrButtons = yFrac in 0.65f..0.95f && xFrac in 0.20f..0.80f
    val baseAlpha = if (isForeground) 0.62f else 0.34f
    val alpha =
      when {
        nearFace -> 0.06f
        nearMicOrButtons -> baseAlpha * 0.35f
        else -> baseAlpha
      }

    val color = palette[i % palette.size]
    val pLen = (if (isForeground) 7.5f else 5.2f + (i % 2) * 1.0f).dp.toPx()
    val pWid = pLen * 0.56f
    val cx = fx(xFrac)
    val cy = fyPx(yFrac)
    val rotDeg = (petalFall * 360f * (if (i % 2 == 0) 1f else -1f)) + i * 37f

    rotate(degrees = rotDeg, pivot = Offset(cx, cy)) {
      val petalPath =
        Path().apply {
          moveTo(cx, cy - pLen * 0.5f)
          cubicTo(
            cx + pWid * 0.95f,
            cy - pLen * 0.18f,
            cx + pWid * 0.80f,
            cy + pLen * 0.42f,
            cx,
            cy + pLen * 0.52f,
          )
          cubicTo(
            cx - pWid * 0.80f,
            cy + pLen * 0.42f,
            cx - pWid * 0.95f,
            cy - pLen * 0.18f,
            cx,
            cy - pLen * 0.5f,
          )
          close()
        }
      drawPath(
        path = petalPath,
        brush =
          Brush.linearGradient(
            listOf(Color.White.copy(alpha = alpha * 0.85f), color.copy(alpha = alpha)),
            start = Offset(cx - pWid * 0.5f, cy - pLen * 0.4f),
            end = Offset(cx + pWid * 0.5f, cy + pLen * 0.4f),
          ),
      )
    }
  }
}

private fun DrawScope.drawHoloAmbientGlow(
  cx: Float,
  cy: Float,
  radius: Float,
  color: Color,
  alpha: Float,
) {
  drawCircle(
    brush =
      Brush.radialGradient(
        listOf(color.copy(alpha = alpha), Color.Transparent),
        center = Offset(cx, cy),
        radius = radius.coerceAtLeast(1f),
      ),
    radius = radius.coerceAtLeast(1f),
    center = Offset(cx, cy),
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
    val amp = (sin(t * 14f - phase) * 4.0f + sin(t * 25f + phase) * 2.0f).dp.toPx()
    if (s == 0) path.moveTo(x, centerY + amp) else path.lineTo(x, centerY + amp)
  }
  drawPath(path, color = color, style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round))
}
