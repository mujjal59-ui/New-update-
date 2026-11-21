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
      initialValue = 0.55f,
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
        .shadow((10 * glow).dp, CircleShape, spotColor = Color(0xFFFF0D9A))
        .clip(CircleShape)
        .background(
          Brush.linearGradient(
            listOf(Color(0xFFFF0D8A), Color(0xFF8B2CF5), Color(0xFF2563EB))
          )
        )
        .border(1.4.dp, Color.White.copy(alpha = 0.45f + glow * 0.25f), CircleShape)
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Filled.Menu,
      contentDescription = "Menu",
      tint = Color.White,
      modifier = Modifier.size(25.dp),
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
    // Horizontal energy beam + circular breathing waves + symmetric soundwave arcs
    Canvas(modifier = Modifier.fillMaxSize()) {
      val cx = size.width * 0.5f
      val cy = size.height * 0.5f
      val baseR = 35.dp.toPx()

      // Signature cyan glowing horizontal light beam connecting side star nodes
      drawLine(
        brush =
          Brush.horizontalGradient(
            listOf(Color.Transparent, CYBER_CYAN.copy(alpha = 0.55f), Color.Transparent)
          ),
        start = Offset(size.width * 0.05f, cy),
        end = Offset(size.width * 0.95f, cy),
        strokeWidth = 1.6.dp.toPx(),
      )

      // Concentric circular breathing sound wave around Microphone
      val ringR = baseR + waveExpand * 20.dp.toPx()
      val ringAlpha = (1f - waveExpand) * (if (isRunning) 0.78f else 0.45f)
      drawCircle(
        brush = Brush.sweepGradient(listOf(CYBER_CYAN, CYBER_BLUE, CYBER_PURPLE, CYBER_CYAN)),
        radius = ringR,
        center = Offset(cx, cy),
        alpha = ringAlpha,
        style = Stroke(width = if (isRunning) 2.4.dp.toPx() else 1.8.dp.toPx()),
      )

      // 4 symmetric curved cyan soundwave arcs on left and right of Microphone (matching Image 2)
      for (idx in 1..4) {
        val rx = (34f + idx * 7.5f).dp.toPx()
        val ry = (21f + idx * 3.5f).dp.toPx()
        val arcAlpha = (0.42f + pulse * 0.42f) * (1f - (idx - 1) * 0.17f)
        drawArc(
          color = if (idx % 2 == 0) CYBER_BLUE.copy(alpha = arcAlpha) else CYBER_CYAN.copy(alpha = arcAlpha),
          startAngle = 144f,
          sweepAngle = 72f,
          useCenter = false,
          topLeft = Offset(cx - rx, cy - ry),
          size = Size(rx * 2f, ry * 2f),
          style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
          color = if (idx % 2 == 0) CYBER_BLUE.copy(alpha = arcAlpha) else CYBER_CYAN.copy(alpha = arcAlpha),
          startAngle = -36f,
          sweepAngle = 72f,
          useCenter = false,
          topLeft = Offset(cx - rx, cy - ry),
          size = Size(rx * 2f, ry * 2f),
          style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 1. Original Camera Chamfered Pill (soft light pulse)
      OriginalCameraPillButton(
        camOn = camOn,
        pulse = pulse,
        onClick = onCam,
      )

      // 2. Centered Microphone Orb
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
            .shadow(16.dp, CircleShape, spotColor = if (isRunning) CYBER_RED else CYBER_CYAN)
            .clip(CircleShape)
            .background(
              Brush.radialGradient(
                colors =
                  if (isRunning) listOf(Color(0xFFFF176B), Color(0xFF7E22CE), Color(0xFF0A1D4E))
                  else listOf(Color(0xFFC026D3), Color(0xFF6366F1), Color(0xFF1E3A8A))
              )
            )
            .border(
              width = 2.5.dp,
              brush =
                Brush.linearGradient(
                  if (isRunning) listOf(CYBER_RED, CYBER_CYAN)
                  else listOf(CYBER_CYAN, Color(0xFF60A5FA), CYBER_CYAN)
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

      // 3. Original Chat Chamfered Pill (cyan notification-style light pulse)
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
  val glow = if (camOn) CYBER_GREEN else Color(0xFF38BDF8)
  Box(
    modifier =
      Modifier
        .size(width = 64.dp, height = 46.dp)
        .testTag("cam_button")
        .semantics { contentDescription = "Camera" }
        .shadow((7f + pulse * 6f).dp, shape, spotColor = glow)
        .clip(shape)
        .background(Color(0xEA06122B), shape)
        .border(1.7.dp, glow.copy(alpha = 0.65f + pulse * 0.32f), shape)
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(24.dp)) {
      val w = size.width
      val h = size.height
      // Top viewfinder bump
      drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.30f, h * 0.14f),
        size = Size(w * 0.40f, h * 0.18f),
        cornerRadius = CornerRadius(2.dp.toPx()),
      )
      // Camera body
      drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.08f, h * 0.25f),
        size = Size(w * 0.84f, h * 0.60f),
        cornerRadius = CornerRadius(4.dp.toPx()),
      )
      // Lens ring cutout
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
      // Flash indicator dot
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
        .size(width = 64.dp, height = 46.dp)
        .testTag("chat_button")
        .semantics { contentDescription = "Chat" }
        .shadow((7f + pulse * 6f).dp, shape, spotColor = CYBER_CYAN)
        .clip(shape)
        .background(Color(0xEA06122B), shape)
        .border(1.7.dp, CYBER_CYAN.copy(alpha = 0.65f + pulse * 0.32f), shape)
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(22.dp)) {
      val w = size.width
      val h = size.height
      // Clean outlined speech bubble + message lines matching Reference Image 2 Chat icon
      drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.10f, h * 0.14f),
        size = Size(w * 0.80f, h * 0.58f),
        cornerRadius = CornerRadius(3.5.dp.toPx()),
        style = Stroke(width = 2.0.dp.toPx()),
      )
      // Two crisp horizontal message lines inside
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
 * Single Original Horizontal Dock Panel matching Reference Image 2:
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

  val dockShape = remember { ChamferedFuturisticShape(chamferDp = 15.dp, cornerRadiusDp = 5.dp) }
  Box(
    modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .shadow(14.dp, dockShape, spotColor = CYBER_CYAN)
          .clip(dockShape)
          .background(
            Brush.verticalGradient(listOf(Color(0xF0081632), Color(0xF8040B1C))),
            dockShape,
          )
          .border(
            width = 1.7.dp,
            brush =
              Brush.horizontalGradient(
                listOf(
                  CYBER_CYAN.copy(alpha = 0.72f + pulse * 0.25f),
                  Color(0xFF38BDF8),
                  CYBER_CYAN.copy(alpha = 0.72f + pulse * 0.25f),
                )
              ),
            shape = dockShape,
          )
          .padding(horizontal = 7.dp, vertical = 7.dp),
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

    // Top-center and Bottom-center diamond energy nodes on dock perimeter (exact Image 2 detail)
    Canvas(modifier = Modifier.matchParentSize()) {
      drawDiamondStarNode(Offset(size.width * 0.5f, 1.dp.toPx()), 4.5.dp.toPx(), CYBER_CYAN)
      drawDiamondStarNode(Offset(size.width * 0.5f, size.height - 1.dp.toPx()), 4.5.dp.toPx(), CYBER_CYAN)
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
        .clip(RoundedCornerShape(12.dp))
        .background(Color(0x8806142C))
        .border(0.85.dp, Color(0xFF60A5FA).copy(alpha = 0.22f + pulse * 0.14f), RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 5.dp, horizontal = 2.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      modifier =
        Modifier
          .size(24.dp)
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

private fun DrawScope.drawDiamondStarNode(center: Offset, r: Float, color: Color) {
  val path =
    Path().apply {
      moveTo(center.x, center.y - r)
      lineTo(center.x + r * 0.85f, center.y)
      lineTo(center.x, center.y + r)
      lineTo(center.x - r * 0.85f, center.y)
      close()
    }
  drawPath(path, color = color.copy(alpha = 0.90f))
  drawCircle(color = Color.White, radius = r * 0.35f, center = center)
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
