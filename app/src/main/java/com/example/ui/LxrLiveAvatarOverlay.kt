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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Separated multi-layer 60fps Visual Engine for LXR--AI Home Screen:
 * 1. Background Animation Layer (L X.R 3D Globe, counter-rotating orbital rings, ChatGPT/Gemini/Claude/Cloud
 *    panels, monitors, RGB PC fans, robot eyes, headphones, plants, floor reflections, particles, back petals).
 * 2. Locked Original Foreground Man Face (`LXR_STUDIO`) — preserves face/smile/hair/watch/chair 100%,
 *    animating ONLY natural eyelid blinking + subtle left/right eye catchlight micro-gaze.
 * 3. Extra Talking Character Layer (`MITALI_CYBER_STUDIO`, `LUNA_CYBER_STUDIO`, `ARIA_CYBER_ROOM`) —
 *    renders above bottom controls with breathing motion, blinking eyes, talking aura & lip movement when speaking.
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

  // Natural human blink + subtle left/right eye focus movement
  val blink = remember { Animatable(0f) }
  val eyeGaze = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    var cycle = 0
    while (true) {
      delay(2900L + ((cycle % 4) * 620L))
      blink.animateTo(1f, tween(72, easing = FastOutSlowInEasing))
      delay(24L)
      blink.animateTo(0f, tween(96, easing = FastOutSlowInEasing))
      if (cycle % 5 == 2) {
        delay(170L)
        blink.animateTo(1f, tween(68, easing = FastOutSlowInEasing))
        delay(16L)
        blink.animateTo(0f, tween(90, easing = FastOutSlowInEasing))
      }
      val nextGaze = when (cycle % 5) {
        0 -> 0.70f
        1 -> -0.65f
        2 -> 0.28f
        3 -> -0.25f
        else -> 0f
      }
      eyeGaze.animateTo(nextGaze, tween(420, easing = FastOutSlowInEasing))
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
  val gazeVal = eyeGaze.value
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

      // Soft radial shimmer keeps "L X.R" crisp & readable
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
      drawHoloPanelFrame(fx(0.045f), fyPx(0.025f), fx(0.305f), fyPx(0.165f), CYBER_CYAN, 0.20f + auraPulse * 0.24f)
      drawHoloPanelFrame(fx(0.695f), fyPx(0.020f), fx(0.955f), fyPx(0.142f), Color(0xFF38BDF8), 0.20f + (1f - auraPulse) * 0.24f)
      drawHoloPanelFrame(fx(0.655f), fyPx(0.145f), fx(0.840f), fyPx(0.240f), Color(0xFF60A5FA), 0.18f + auraPulse * 0.22f)
      drawHoloPanelFrame(fx(0.845f), fyPx(0.140f), fx(0.990f), fyPx(0.258f), Color(0xFF38BDF8), 0.18f + (1f - auraPulse) * 0.22f)

      drawRoundRect(
        color = Color(0xFFD946EF).copy(alpha = 0.12f + auraPulse * 0.22f),
        topLeft = Offset(fx(0.115f), fyPx(0.194f)),
        size = Size(fx(0.305f) - fx(0.115f), fyPx(0.256f) - fyPx(0.194f)),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = Stroke(width = 1.5.dp.toPx()),
      )

      // 1C. Computer Monitors: Clean Flowing Telemetry Waveforms strictly inside dark screen areas
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

      // 1D. RGB Gaming PC Fans: Exact hub coordinates (x=0.980 in LXR_STUDIO) with soft glowing neon blades
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

      // Active running cyan glow halo around Mic orb on LXR_STUDIO without obscuring artwork
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

      // 1I. Background-Depth Falling Flower Petals (smaller, behind characters / side background lanes)
      drawFallingPetalLayer(
        petalFall = petalFall,
        wavePhase = wavePhase,
        isForeground = false,
        avoidFaceRect = homeTheme == HomeTheme.LXR_STUDIO,
        fx = ::fx,
        fyPx = ::fyPx,
      )

      // 2. Locked Original Foreground Man Face (`LXR_STUDIO`):
      // EYE-ONLY natural blinking & subtle iris left/right focus catchlight. ZERO mouth changes!
      if (homeTheme == HomeTheme.LXR_STUDIO) {
        drawNaturalEyelidBlink(
          leftX = fx(0.468f),
          rightX = fx(0.533f),
          topY = fyPx(0.3612f),
          midY = fyPx(0.3668f),
          botY = fyPx(0.3730f),
          lidColors = listOf(Color(0xFF8C595D), Color(0xFF724249), Color(0xFF4E2B31)),
          blinkVal = blinkVal,
          gazeShiftPx = gazeVal * 1.5.dp.toPx(),
          catchlightPos = Offset(fx(0.503f), fyPx(0.3662f)),
        )
        drawNaturalEyelidBlink(
          leftX = fx(0.625f),
          rightX = fx(0.688f),
          topY = fyPx(0.3645f),
          midY = fyPx(0.3700f),
          botY = fyPx(0.3762f),
          lidColors = listOf(Color(0xFF5E3539), Color(0xFF462328), Color(0xFF2F1519)),
          blinkVal = blinkVal,
          gazeShiftPx = gazeVal * 1.5.dp.toPx(),
          catchlightPos = Offset(fx(0.661f), fyPx(0.3695f)),
        )
      }
    }

    // LAYER 2: Non-Talking Animated Theme Characters (Aria or Mitali) with natural blinking & breathing motion
    if (homeTheme == HomeTheme.ARIA_CYBER_ROOM || homeTheme == HomeTheme.MITALI_CYBER_STUDIO) {
      val charH = dh * 0.56f
      val charRatio = if (homeTheme == HomeTheme.ARIA_CYBER_ROOM) 660f / 1536f else 663f / 1672f
      val charW = charH * charRatio
      val charRes = if (homeTheme == HomeTheme.ARIA_CYBER_ROOM) R.drawable.char_aria else R.drawable.char_mitali

      BoxWithConstraints(
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .offset(y = -(dh * 0.295f).dp + (breezePhase * 2.0f).dp)
            .size(width = charW.dp, height = charH.dp)
      ) {
        Image(
          painter = painterResource(charRes),
          contentDescription = "${homeTheme.name} Animated Character",
          modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.99f },
          contentScale = ContentScale.Fit,
        )

        // Natural eyelid blinking only (NO mouth modification / NO talking animation)
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          if (homeTheme == HomeTheme.ARIA_CYBER_ROOM) {
            drawNaturalEyelidBlink(
              leftX = w * 0.380f,
              rightX = w * 0.448f,
              topY = h * 0.095f,
              midY = h * 0.102f,
              botY = h * 0.109f,
              lidColors = listOf(Color(0xFFF3D5CE), Color(0xFFD8A9A0), Color(0xFF4F272E)),
              blinkVal = blinkVal,
              gazeShiftPx = gazeVal * 0.9.dp.toPx(),
              catchlightPos = Offset(w * 0.413f, h * 0.102f),
            )
            drawNaturalEyelidBlink(
              leftX = w * 0.522f,
              rightX = w * 0.592f,
              topY = h * 0.085f,
              midY = h * 0.092f,
              botY = h * 0.099f,
              lidColors = listOf(Color(0xFFF3D5CE), Color(0xFFD8A9A0), Color(0xFF4F272E)),
              blinkVal = blinkVal,
              gazeShiftPx = gazeVal * 0.9.dp.toPx(),
              catchlightPos = Offset(w * 0.557f, h * 0.092f),
            )
          } else {
            drawNaturalEyelidBlink(
              leftX = w * 0.342f,
              rightX = w * 0.410f,
              topY = h * 0.100f,
              midY = h * 0.107f,
              botY = h * 0.114f,
              lidColors = listOf(Color(0xFFEFC5B8), Color(0xFFD6A096), Color(0xFF6B3936)),
              blinkVal = blinkVal,
              gazeShiftPx = gazeVal * 0.9.dp.toPx(),
              catchlightPos = Offset(w * 0.372f, h * 0.107f),
            )
            drawNaturalEyelidBlink(
              leftX = w * 0.492f,
              rightX = w * 0.558f,
              topY = h * 0.086f,
              midY = h * 0.092f,
              botY = h * 0.099f,
              lidColors = listOf(Color(0xFFEFC5B8), Color(0xFFD6A096), Color(0xFF6B3936)),
              blinkVal = blinkVal,
              gazeShiftPx = gazeVal * 0.9.dp.toPx(),
              catchlightPos = Offset(w * 0.525f, h * 0.092f),
            )
          }
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
    // Distribute across sky & outer corridors, gently weaving
    val baseLane =
      if (isForeground) {
        val raw = ((i * 29 + 11) % 92) / 100f + 0.04f
        // Keep central portrait face band clear while gliding beautifully around shoulders/backdrop/sides
        if (avoidFaceRect && yFrac in 0.24f..0.46f && raw in 0.36f..0.68f) {
          if (i % 2 == 0) 0.16f + (i % 4) * 0.045f else 0.76f + (i % 3) * 0.055f
        } else raw
      } else {
        ((i * 37 + 7) % 94) / 100f + 0.03f
      }
    val swayX = sin(wavePhase * 0.85f + i * 1.3f) * (if (isForeground) 0.026f else 0.018f)
    val xFrac = (baseLane + swayX).coerceIn(0.02f, 0.98f)

    // Dim petal softly if near face/buttons so it never obscures important icons or face
    val nearFace = avoidFaceRect && xFrac in 0.38f..0.67f && yFrac in 0.28f..0.44f
    val nearMicOrButtons = yFrac in 0.65f..0.95f && xFrac in 0.20f..0.80f
    val baseAlpha = if (isForeground) 0.62f else 0.34f
    val alpha =
      when {
        nearFace -> 0.08f
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
    val amp = (sin(t * 14f - phase) * 4.0f + sin(t * 25f + phase) * 2.0f).dp.toPx()
    if (s == 0) path.moveTo(x, centerY + amp) else path.lineTo(x, centerY + amp)
  }
  drawPath(path, color = color, style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round))
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
      color = Color.White.copy(alpha = 0.24f),
      radius = 1.35.dp.toPx(),
      center = Offset(catchlightPos.x + gazeShiftPx, catchlightPos.y),
    )
  }
}
