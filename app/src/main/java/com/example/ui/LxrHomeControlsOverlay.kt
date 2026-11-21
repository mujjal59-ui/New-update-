package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Clean Original LXR--AI Foreground Icons & Controls matching Reference Image 2 cleanly with ZERO duplicate icons or ghosting:
 * - Top-left purple-pink-blue circular Menu button
 * - Camera pill, centered Microphone orb (breathing rings + symmetric sound waves), Chat pill
 * - Single horizontal Dock with exact original brand logos:
 *   WhatsApp (green speech bubble + handset), IMO (cyan speech bubble), Messenger (purple bubble + lightning),
 *   YouTube (red beamed music note), Contacts (yellow outline person), Settings (white-blue rotating gear)
 */
@Composable
fun LxrTopLeftMenuButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val inf = rememberInfiniteTransition(label = "menu_btn_pulse")
  val glow by
    inf.animateFloat(
      initialValue = 0.65f,
      targetValue = 1.0f,
      animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "menu_glow",
    )

  Box(
    modifier =
      modifier
        .size(48.dp)
        .testTag("menu_button")
        .semantics { contentDescription = "Open LXR AI Menu" }
        .shadow((11 * glow).dp, CircleShape, spotColor = Color(0xFFEC4899))
        .clip(CircleShape)
        .background(
          Brush.linearGradient(
            listOf(Color(0xFFEC4899), Color(0xFF8B5CF6), Color(0xFF3B82F6))
          )
        )
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Filled.Menu,
      contentDescription = "Menu",
      tint = Color.White,
      modifier = Modifier.size(24.dp),
    )
  }
}

@Composable
fun LxrVoiceControlStrip(
  isRunning: Boolean,
  camOn: Boolean,
  onCam: () -> Unit,
  onMic: () -> Unit,
  onChat: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val inf = rememberInfiniteTransition(label = "voice_strip")
  val pulse by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(if (isRunning) 1100 else 2100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "voice_pulse",
    )
  val waveExpand by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(if (isRunning) 1200 else 2400, easing = LinearEasing), RepeatMode.Restart),
      label = "mic_wave_expand",
    )

  Box(
    modifier = modifier.fillMaxWidth().height(84.dp),
    contentAlignment = Alignment.Center,
  ) {
    // Signature luminous horizontal cyan energy beam + twin side star flares + radiant Mic halo + 3 crisp soundwave arcs
    Canvas(modifier = Modifier.fillMaxSize()) {
      val cx = size.width * 0.5f
      val cy = size.height * 0.5f
      val baseR = 36.dp.toPx()

      // Soft wide cyan-blue bloom behind center Mic orb matching Theme 1
      drawCircle(
        brush =
          Brush.radialGradient(
            listOf(
              Color(0xFF00F0FF).copy(alpha = 0.48f + pulse * 0.18f),
              Color(0xFF2563EB).copy(alpha = 0.30f + pulse * 0.10f),
              Color.Transparent,
            ),
            center = Offset(cx, cy),
            radius = 56.dp.toPx(),
          ),
        radius = 56.dp.toPx(),
        center = Offset(cx, cy),
      )

      // Bright horizontal cyan energy beam behind Camera - Mic - Chat
      drawLine(
        brush =
          Brush.horizontalGradient(
            listOf(
              Color.Transparent,
              Color(0xFF00F0FF).copy(alpha = 0.65f),
              Color.White.copy(alpha = 0.85f),
              Color(0xFF00F0FF).copy(alpha = 0.65f),
              Color.Transparent,
            )
          ),
        start = Offset(size.width * 0.04f, cy),
        end = Offset(size.width * 0.96f, cy),
        strokeWidth = 2.0.dp.toPx(),
      )

      // Left & Right 4-point cyan star flares beside Camera & Chat pills
      drawStarFlare(Offset(size.width * 0.135f, cy), 8.dp.toPx(), Color(0xFF00F0FF))
      drawStarFlare(Offset(size.width * 0.865f, cy), 8.dp.toPx(), Color(0xFF00F0FF))

      // Concentric circular breathing wave around Mic
      val ringR = baseR + waveExpand * 18.dp.toPx()
      val ringAlpha = (1f - waveExpand) * (if (isRunning) 0.82f else 0.50f)
      drawCircle(
        brush = Brush.sweepGradient(listOf(Color(0xFF00F0FF), Color(0xFF3B82F6), Color(0xFFA855F7), Color(0xFF00F0FF))),
        radius = ringR,
        center = Offset(cx, cy),
        alpha = ringAlpha,
        style = Stroke(width = if (isRunning) 2.6.dp.toPx() else 1.8.dp.toPx()),
      )

      // 3 crisp glowing curved cyan-white soundwave arcs on left and right of Microphone orb
      for (idx in 1..3) {
        val rx = (38f + idx * 6.5f).dp.toPx()
        val ry = (23f + idx * 3.8f).dp.toPx()
        val arcAlpha = (0.58f + pulse * 0.36f) * (1f - (idx - 1) * 0.18f)
        val arcColor = if (idx == 1) Color.White.copy(alpha = arcAlpha) else Color(0xFF00F0FF).copy(alpha = arcAlpha)
        drawArc(
          color = arcColor,
          startAngle = 146f,
          sweepAngle = 68f,
          useCenter = false,
          topLeft = Offset(cx - rx, cy - ry),
          size = Size(rx * 2f, ry * 2f),
          style = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
          color = arcColor,
          startAngle = -34f,
          sweepAngle = 68f,
          useCenter = false,
          topLeft = Offset(cx - rx, cy - ry),
          size = Size(rx * 2f, ry * 2f),
          style = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 1. Glowing Cyan Camera Chamfered Pill
      OriginalCameraPillButton(
        camOn = camOn,
        pulse = pulse,
        onClick = onCam,
      )

      // 2. Centered Glowing Purple-Blue-Cyan Microphone Orb
      val micInteraction = remember { MutableInteractionSource() }
      val micPressed by micInteraction.collectIsPressedAsState()
      val micScale by animateFloatAsState(
        targetValue = if (micPressed) 0.92f else 1f + pulse * (if (isRunning) 0.06f else 0.025f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "mic_scale",
      )
      Box(
        modifier =
          Modifier
            .size(72.dp)
            .scale(micScale)
            .testTag("mic_button")
            .semantics { contentDescription = "Microphone Toggle" }
            .shadow(18.dp, CircleShape, spotColor = if (isRunning) CYBER_RED else Color(0xFF00F0FF))
            .clip(CircleShape)
            .background(
              Brush.radialGradient(
                colors =
                  if (isRunning) listOf(Color(0xFFFF176B), Color(0xFF7E22CE), Color(0xFF0B2A6B))
                  else listOf(Color(0xFFD946EF), Color(0xFF7C3AED), Color(0xFF1D4ED8), Color(0xFF06B6D4))
              )
            )
            .border(
              width = 3.0.dp,
              brush =
                Brush.linearGradient(
                  if (isRunning) listOf(CYBER_RED, Color(0xFF00F0FF))
                  else listOf(Color(0xFF00F0FF), Color(0xFF38BDF8), Color(0xFF2563EB), Color(0xFF00F0FF))
                ),
              shape = CircleShape,
            )
            .clickable(interactionSource = micInteraction, indication = null, onClick = onMic),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.Mic,
          contentDescription = "Microphone",
          tint = Color.White,
          modifier = Modifier.size(34.dp),
        )
      }

      // 3. Glowing Cyan Chat Chamfered Pill
      OriginalChatPillButton(
        pulse = 1f - pulse,
        onClick = onChat,
      )
    }
  }
}

@Composable
private fun OriginalCameraPillButton(
  camOn: Boolean,
  pulse: Float,
  onClick: () -> Unit,
) {
  val shape = remember { ChamferedFuturisticShape(chamferDp = 12.dp, cornerRadiusDp = 4.dp) }
  val glow = if (camOn) CYBER_GREEN else Color(0xFF00F0FF)
  Box(
    modifier =
      Modifier
        .size(width = 66.dp, height = 42.dp)
        .testTag("cam_button")
        .semantics { contentDescription = "Camera" }
        .shadow((10f + pulse * 6f).dp, shape, spotColor = glow)
        .clip(shape)
        .background(
          Brush.verticalGradient(listOf(Color(0xDD08224A), Color(0xEA04112B))),
          shape,
        )
        .border(2.0.dp, glow.copy(alpha = 0.78f + pulse * 0.20f), shape)
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(24.dp)) {
      val w = size.width
      val h = size.height
      drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.30f, h * 0.14f),
        size = Size(w * 0.40f, h * 0.18f),
        cornerRadius = CornerRadius(2.dp.toPx()),
      )
      drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.08f, h * 0.25f),
        size = Size(w * 0.84f, h * 0.60f),
        cornerRadius = CornerRadius(4.dp.toPx()),
      )
      drawCircle(
        color = Color(0xFF0A1D3E),
        radius = w * 0.18f,
        center = Offset(w * 0.50f, h * 0.56f),
      )
      drawCircle(
        color = Color.White,
        radius = w * 0.11f,
        center = Offset(w * 0.50f, h * 0.56f),
      )
      drawCircle(
        color = Color(0xFF0A1D3E),
        radius = w * 0.045f,
        center = Offset(w * 0.77f, h * 0.39f),
      )
    }
  }
}

@Composable
private fun OriginalChatPillButton(
  pulse: Float,
  onClick: () -> Unit,
) {
  val shape = remember { ChamferedFuturisticShape(chamferDp = 12.dp, cornerRadiusDp = 4.dp) }
  Box(
    modifier =
      Modifier
        .size(width = 66.dp, height = 42.dp)
        .testTag("chat_button")
        .semantics { contentDescription = "Chat" }
        .shadow((10f + pulse * 6f).dp, shape, spotColor = Color(0xFF00F0FF))
        .clip(shape)
        .background(
          Brush.verticalGradient(listOf(Color(0xDD08224A), Color(0xEA04112B))),
          shape,
        )
        .border(2.0.dp, Color(0xFF00F0FF).copy(alpha = 0.78f + pulse * 0.20f), shape)
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(22.dp)) {
      val w = size.width
      val h = size.height
      drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.10f, h * 0.14f),
        size = Size(w * 0.80f, h * 0.58f),
        cornerRadius = CornerRadius(3.5.dp.toPx()),
        style = Stroke(width = 2.0.dp.toPx()),
      )
      drawLine(
        color = Color.White,
        start = Offset(w * 0.28f, h * 0.36f),
        end = Offset(w * 0.72f, h * 0.36f),
        strokeWidth = 1.8.dp.toPx(),
        cap = StrokeCap.Round,
      )
      drawLine(
        color = Color.White,
        start = Offset(w * 0.28f, h * 0.51f),
        end = Offset(w * 0.58f, h * 0.51f),
        strokeWidth = 1.8.dp.toPx(),
        cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Single Original Horizontal Dock Panel matching Theme 1 (`home_bg.png`) neon cyan glow & clean glass tiles:
 * WhatsApp (Green), IMO (Cyan), Messenger (Purple), YouTube (Red Music Note), Contacts (Yellow), Settings (White/Blue Rotating Gear).
 */
@Composable
fun LxrDockAppsRow(
  onWhatsApp: () -> Unit,
  onImo: () -> Unit,
  onMessenger: () -> Unit,
  onYouTube: () -> Unit,
  onContacts: () -> Unit,
  onSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val inf = rememberInfiniteTransition(label = "dock_animations")
  val gearAngle by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(tween(6500, easing = LinearEasing)),
      label = "settings_gear_rotate",
    )
  val pulse by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "dock_pulse",
    )

  val dockShape = remember { ChamferedFuturisticShape(chamferDp = 16.dp, cornerRadiusDp = 5.dp) }
  Box(
    modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .shadow(16.dp, dockShape, spotColor = Color(0xFF00F0FF))
          .clip(dockShape)
          .background(
            Brush.verticalGradient(listOf(Color(0xDC061636), Color(0xEA030A1C))),
            dockShape,
          )
          .border(
            width = 2.0.dp,
            brush =
              Brush.horizontalGradient(
                listOf(
                  Color(0xFF00F0FF).copy(alpha = 0.85f + pulse * 0.15f),
                  Color(0xFF38BDF8),
                  Color.White.copy(alpha = 0.85f),
                  Color(0xFF38BDF8),
                  Color(0xFF00F0FF).copy(alpha = 0.85f + pulse * 0.15f),
                )
              ),
            shape = dockShape,
          )
          .padding(horizontal = 8.dp, vertical = 7.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      DockOriginalItem(
        label = "WhatsApp",
        accent = Color(0xFF00E676),
        pulse = pulse,
        tag = "dock_whatsapp",
        onClick = onWhatsApp,
        modifier = Modifier.weight(1f),
      ) {
        drawOriginalWhatsAppIcon(pulse)
      }
      DockOriginalItem(
        label = "IMO",
        accent = Color(0xFF00E5FF),
        pulse = 1f - pulse,
        tag = "dock_imo",
        onClick = onImo,
        modifier = Modifier.weight(1f),
      ) {
        drawOriginalImoIcon(1f - pulse)
      }
      DockOriginalItem(
        label = "Messenger",
        accent = Color(0xFFC026D3),
        pulse = pulse,
        tag = "dock_messenger",
        onClick = onMessenger,
        modifier = Modifier.weight(1f),
      ) {
        drawOriginalMessengerIcon(pulse)
      }
      DockOriginalItem(
        label = "YouTube",
        accent = Color(0xFFFF1744),
        pulse = 1f - pulse,
        tag = "dock_youtube",
        onClick = onYouTube,
        modifier = Modifier.weight(1f),
      ) {
        drawOriginalYouTubeMusicIcon(1f - pulse)
      }
      DockOriginalItem(
        label = "Contacts",
        accent = Color(0xFFFFD600),
        pulse = pulse,
        tag = "dock_contacts",
        onClick = onContacts,
        modifier = Modifier.weight(1f),
      ) {
        drawOriginalContactsIcon(pulse)
      }
      DockOriginalItem(
        label = "Settings",
        accent = Color(0xFFBAE6FD),
        pulse = 1f - pulse,
        tag = "dock_settings",
        onClick = onSettings,
        modifier = Modifier.weight(1f),
        iconRotationDeg = gearAngle,
      ) {
        drawOriginalSettingsGearIcon()
      }
    }

    // Top-center and Bottom-center diamond energy nodes on dock perimeter (exact Theme 1 detail)
    Canvas(modifier = Modifier.matchParentSize()) {
      drawDiamondStarNode(Offset(size.width * 0.5f, 1.dp.toPx()), 5.0.dp.toPx(), Color(0xFF00F0FF))
      drawDiamondStarNode(Offset(size.width * 0.5f, size.height - 1.dp.toPx()), 5.0.dp.toPx(), Color(0xFF00F0FF))
    }
  }
}

@Composable
private fun DockOriginalItem(
  label: String,
  accent: Color,
  pulse: Float,
  tag: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  iconRotationDeg: Float = 0f,
  drawIcon: DrawScope.() -> Unit,
) {
  Column(
    modifier =
      modifier
        .heightIn(min = 52.dp)
        .testTag(tag)
        .semantics { contentDescription = label }
        .clip(RoundedCornerShape(11.dp))
        .background(Color(0x6606122A))
        .border(0.75.dp, Color(0x4593C5FD), RoundedCornerShape(11.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 5.dp, horizontal = 2.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      modifier =
        Modifier
          .size(23.dp)
          .let { if (iconRotationDeg != 0f) it.rotate(iconRotationDeg) else it },
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        drawIcon()
      }
    }
    Spacer(Modifier.height(3.dp))
    Text(
      text = label,
      color = Color.White,
      fontSize = 9.sp,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun DrawScope.drawStarFlare(center: Offset, r: Float, color: Color) {
  drawCircle(
    brush = Brush.radialGradient(listOf(Color.White, color.copy(alpha = 0.6f), Color.Transparent), center = center, radius = r * 1.6f),
    radius = r * 1.6f,
    center = center,
  )
  val path =
    Path().apply {
      moveTo(center.x, center.y - r)
      lineTo(center.x + r * 0.25f, center.y - r * 0.25f)
      lineTo(center.x + r, center.y)
      lineTo(center.x + r * 0.25f, center.y + r * 0.25f)
      lineTo(center.x, center.y + r)
      lineTo(center.x - r * 0.25f, center.y + r * 0.25f)
      lineTo(center.x - r, center.y)
      lineTo(center.x - r * 0.25f, center.y - r * 0.25f)
      close()
    }
  drawPath(path, color = Color.White.copy(alpha = 0.92f))
}

private fun DrawScope.drawDiamondStarNode(center: Offset, r: Float, color: Color) {
  drawCircle(
    brush = Brush.radialGradient(listOf(color.copy(alpha = 0.70f), Color.Transparent), center = center, radius = r * 2.2f),
    radius = r * 2.2f,
    center = center,
  )
  val path =
    Path().apply {
      moveTo(center.x, center.y - r)
      lineTo(center.x + r * 0.85f, center.y)
      lineTo(center.x, center.y + r)
      lineTo(center.x - r * 0.85f, center.y)
      close()
    }
  drawPath(path, color = color.copy(alpha = 0.95f))
  drawCircle(color = Color.White, radius = r * 0.38f, center = center)
}

private fun DrawScope.drawOriginalWhatsAppIcon(pulse: Float) {
  val w = size.width
  val h = size.height
  val green = Color(0xFF00E676)
  // Soft breathing radial glow
  drawCircle(
    brush = Brush.radialGradient(listOf(green.copy(alpha = 0.26f + pulse * 0.20f), Color.Transparent)),
    radius = w * 0.60f,
  )
  // Classic WhatsApp speech bubble outline
  val cx = w * 0.50f
  val cy = h * 0.47f
  val r = w * 0.36f
  drawCircle(
    color = green,
    radius = r,
    center = Offset(cx, cy),
    style = Stroke(width = 2.1.dp.toPx()),
  )
  val tail =
    Path().apply {
      moveTo(cx - r * 0.52f, cy + r * 0.72f)
      lineTo(w * 0.14f, h * 0.88f)
      lineTo(cx - r * 0.12f, cy + r * 0.95f)
      close()
    }
  drawPath(tail, color = green)
  // Classic curved handset silhouette inside
  val phone =
    Path().apply {
      moveTo(w * 0.37f, h * 0.33f)
      quadraticTo(w * 0.33f, h * 0.46f, w * 0.43f, h * 0.56f)
      quadraticTo(w * 0.52f, h * 0.64f, w * 0.63f, h * 0.60f)
    }
  drawPath(
    phone,
    color = green,
    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
}

private fun DrawScope.drawOriginalImoIcon(pulse: Float) {
  val w = size.width
  val h = size.height
  val cyan = Color(0xFF00E5FF)
  drawCircle(
    brush = Brush.radialGradient(listOf(cyan.copy(alpha = 0.24f + pulse * 0.20f), Color.Transparent)),
    radius = w * 0.58f,
  )
  // IMO cyan rounded square speech bubble
  drawRoundRect(
    color = cyan,
    topLeft = Offset(w * 0.15f, h * 0.15f),
    size = Size(w * 0.70f, h * 0.58f),
    cornerRadius = CornerRadius(4.5.dp.toPx()),
    style = Stroke(width = 2.2.dp.toPx()),
  )
  val notch =
    Path().apply {
      moveTo(w * 0.22f, h * 0.72f)
      lineTo(w * 0.18f, h * 0.88f)
      lineTo(w * 0.39f, h * 0.72f)
      close()
    }
  drawPath(notch, color = cyan)
}

private fun DrawScope.drawOriginalMessengerIcon(pulse: Float) {
  val w = size.width
  val h = size.height
  val purple = Color(0xFFD946EF)
  drawCircle(
    brush = Brush.radialGradient(listOf(purple.copy(alpha = 0.28f + pulse * 0.20f), Color.Transparent)),
    radius = w * 0.60f,
  )
  val cx = w * 0.50f
  val cy = h * 0.46f
  drawCircle(
    brush = Brush.linearGradient(listOf(Color(0xFFE879F9), Color(0xFF9333EA))),
    radius = w * 0.37f,
    center = Offset(cx, cy),
  )
  val tail =
    Path().apply {
      moveTo(w * 0.24f, h * 0.71f)
      lineTo(w * 0.19f, h * 0.88f)
      lineTo(w * 0.40f, h * 0.77f)
      close()
    }
  drawPath(tail, color = Color(0xFF9333EA))
  // Crisp white lightning bolt inside
  val bolt =
    Path().apply {
      moveTo(w * 0.30f, h * 0.52f)
      lineTo(w * 0.47f, h * 0.34f)
      lineTo(w * 0.53f, h * 0.45f)
      lineTo(w * 0.70f, h * 0.38f)
      lineTo(w * 0.52f, h * 0.58f)
      lineTo(w * 0.46f, h * 0.47f)
      close()
    }
  drawPath(bolt, color = Color(0xFF0A0618))
}

private fun DrawScope.drawOriginalYouTubeMusicIcon(pulse: Float) {
  val w = size.width
  val h = size.height
  val red = Color(0xFFFF1744)
  drawCircle(
    brush = Brush.radialGradient(listOf(red.copy(alpha = 0.28f + pulse * 0.20f), Color.Transparent)),
    radius = w * 0.60f,
  )
  // Clean vibrant red beamed eighth music note matching Image 2's 4th dock icon
  drawLine(
    color = red,
    start = Offset(w * 0.39f, h * 0.22f),
    end = Offset(w * 0.75f, h * 0.15f),
    strokeWidth = 3.6.dp.toPx(),
    cap = StrokeCap.Round,
  )
  drawLine(
    color = red,
    start = Offset(w * 0.39f, h * 0.22f),
    end = Offset(w * 0.39f, h * 0.72f),
    strokeWidth = 2.4.dp.toPx(),
    cap = StrokeCap.Round,
  )
  drawLine(
    color = red,
    start = Offset(w * 0.75f, h * 0.15f),
    end = Offset(w * 0.75f, h * 0.65f),
    strokeWidth = 2.4.dp.toPx(),
    cap = StrokeCap.Round,
  )
  drawOval(color = red, topLeft = Offset(w * 0.19f, h * 0.63f), size = Size(w * 0.23f, h * 0.18f))
  drawOval(color = red, topLeft = Offset(w * 0.55f, h * 0.56f), size = Size(w * 0.23f, h * 0.18f))
}

private fun DrawScope.drawOriginalContactsIcon(pulse: Float) {
  val w = size.width
  val h = size.height
  val yellow = Color(0xFFFFD600)
  drawCircle(
    brush = Brush.radialGradient(listOf(yellow.copy(alpha = 0.24f + pulse * 0.20f), Color.Transparent)),
    radius = w * 0.58f,
  )
  // Crisp yellow outline person icon matching Image 2
  drawCircle(
    color = yellow,
    radius = w * 0.16f,
    center = Offset(w * 0.50f, h * 0.33f),
    style = Stroke(width = 2.2.dp.toPx()),
  )
  val shoulders =
    Path().apply {
      moveTo(w * 0.20f, h * 0.80f)
      quadraticTo(w * 0.22f, h * 0.56f, w * 0.50f, h * 0.56f)
      quadraticTo(w * 0.78f, h * 0.56f, w * 0.80f, h * 0.80f)
    }
  drawPath(shoulders, color = yellow, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawOriginalSettingsGearIcon() {
  val cx = size.width * 0.50f
  val cy = size.height * 0.50f
  val outerR = size.width * 0.37f
  val innerR = size.width * 0.26f
  val holeR = size.width * 0.13f
  val gearColor = Color(0xFFE0F2FE)

  for (i in 0..7) {
    val angle = (i * 45.0) * PI / 180.0
    drawLine(
      color = gearColor,
      start = Offset(cx + (cos(angle) * innerR).toFloat(), cy + (sin(angle) * innerR).toFloat()),
      end = Offset(cx + (cos(angle) * outerR).toFloat(), cy + (sin(angle) * outerR).toFloat()),
      strokeWidth = 3.2.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }
  drawCircle(color = gearColor, radius = innerR, center = Offset(cx, cy), style = Stroke(width = 2.8.dp.toPx()))
  drawCircle(color = Color(0xFF0A142B), radius = holeR, center = Offset(cx, cy))
}
