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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Interactive & animated Home controls matching the exact reference positions & colors:
 * - Top-left purple circular Menu button
 * - Camera (white-blue pulse), centered Microphone (smooth circular breathing waves), Chat (cyan glow)
 * - 6 Dock buttons: WhatsApp (green), IMO (cyan), Messenger (purple), YouTube (red), Contacts (yellow), Settings (rotating gear)
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
        .border(1.5.dp, Color.White.copy(alpha = 0.45f + glow * 0.25f), CircleShape)
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
    // Breathing circular & side sound waves around center Microphone
    Canvas(modifier = Modifier.fillMaxSize()) {
      val cx = size.width * 0.5f
      val cy = size.height * 0.5f
      val baseR = 34.dp.toPx()

      // Concentric expanding circular sound wave
      val ringR = baseR + waveExpand * 22.dp.toPx()
      val ringAlpha = (1f - waveExpand) * (if (isRunning) 0.72f else 0.42f)
      drawCircle(
        brush = Brush.sweepGradient(listOf(CYBER_CYAN, CYBER_BLUE, CYBER_PURPLE, CYBER_CYAN)),
        radius = ringR,
        center = Offset(cx, cy),
        alpha = ringAlpha,
        style = Stroke(width = if (isRunning) 2.5.dp.toPx() else 1.8.dp.toPx()),
      )

      // Side symmetric circular sound-wave arcs (matching reference artwork waves between Cam-Mic-Chat)
      for (idx in 1..3) {
        val gap = (34f + idx * 9f).dp.toPx()
        val arcAlpha = (0.35f + pulse * 0.45f) * (1f - (idx - 1) * 0.22f)
        // Left wave arc
        drawArc(
          color = if (idx == 2) CYBER_BLUE.copy(alpha = arcAlpha) else CYBER_CYAN.copy(alpha = arcAlpha),
          startAngle = 140f,
          sweepAngle = 80f,
          useCenter = false,
          topLeft = Offset(cx - gap, cy - gap * 0.58f),
          size = androidx.compose.ui.geometry.Size(gap * 2f, gap * 1.16f),
          style = Stroke(width = 2.dp.toPx()),
        )
        // Right wave arc
        drawArc(
          color = if (idx == 2) CYBER_BLUE.copy(alpha = arcAlpha) else CYBER_CYAN.copy(alpha = arcAlpha),
          startAngle = -40f,
          sweepAngle = 80f,
          useCenter = false,
          topLeft = Offset(cx - gap, cy - gap * 0.58f),
          size = androidx.compose.ui.geometry.Size(gap * 2f, gap * 1.16f),
          style = Stroke(width = 2.dp.toPx()),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 1. Camera (White-blue pulse)
      AnimatedPillButton(
        icon = Icons.Filled.PhotoCamera,
        contentDescription = "Camera",
        testTag = "cam_button",
        glowColor = if (camOn) CYBER_GREEN else Color(0xFF7DD3FC),
        iconTint = Color.White,
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
                  else listOf(Color(0xFFA855F7), Color(0xFF3B82F6), Color(0xFF061B48))
              )
            )
            .border(
              width = 2.4.dp,
              brush =
                Brush.linearGradient(
                  if (isRunning) listOf(CYBER_RED, CYBER_CYAN)
                  else listOf(CYBER_CYAN, Color(0xFF60A5FA), CYBER_PURPLE)
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

      // 3. Chat (Gentle cyan notification glow)
      AnimatedPillButton(
        icon = Icons.AutoMirrored.Filled.Chat,
        contentDescription = "Chat",
        testTag = "chat_button",
        glowColor = CYBER_CYAN,
        iconTint = Color.White,
        pulse = 1f - pulse,
        onClick = onChat,
      )
    }
  }
}

@Composable
private fun AnimatedPillButton(
  icon: ImageVector,
  contentDescription: String,
  testTag: String,
  glowColor: Color,
  iconTint: Color,
  pulse: Float,
  onClick: () -> Unit,
) {
  val shape = remember { ChamferedFuturisticShape(chamferDp = 12.dp, cornerRadiusDp = 4.dp) }
  Box(
    modifier =
      Modifier
        .size(width = 62.dp, height = 48.dp)
        .testTag(testTag)
        .shadow((6f + pulse * 6f).dp, shape, spotColor = glowColor)
        .clip(shape)
        .background(Color(0xE0061229), shape)
        .border(
          width = 1.6.dp,
          color = glowColor.copy(alpha = 0.60f + pulse * 0.35f),
          shape = shape,
        )
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = iconTint,
      modifier = Modifier.size(23.dp),
    )
  }
}

/**
 * 6 Interactive Neon Dock Icons row matching exact order & colors:
 * WhatsApp (Green), IMO (Cyan), Messenger (Purple), YouTube (Red), Contacts (Yellow), Settings (White-Blue Rotating Gear)
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
      animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
      label = "settings_gear_rotate",
    )
  val pulse by
    inf.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "dock_pulse",
    )

  val dockShape = remember { ChamferedFuturisticShape(chamferDp = 14.dp, cornerRadiusDp = 5.dp) }
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp)
        .shadow(12.dp, dockShape, spotColor = CYBER_CYAN)
        .clip(dockShape)
        .background(
          Brush.verticalGradient(listOf(Color(0xEB07142C), Color(0xF5040A18))),
          dockShape,
        )
        .border(
          width = 1.6.dp,
          brush =
            Brush.horizontalGradient(
              listOf(
                CYBER_CYAN.copy(alpha = 0.72f + pulse * 0.25f),
                Color(0xFF3B82F6),
                CYBER_CYAN.copy(alpha = 0.72f + pulse * 0.25f),
              )
            ),
          shape = dockShape,
        )
        .padding(horizontal = 6.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    DockIconItem(
      label = "WhatsApp",
      icon = Icons.Filled.Call,
      accent = Color(0xFF00E676),
      pulse = pulse,
      tag = "dock_whatsapp",
      onClick = onWhatsApp,
      modifier = Modifier.weight(1f),
    )
    DockIconItem(
      label = "IMO",
      icon = Icons.Filled.Forum,
      accent = Color(0xFF00E5FF),
      pulse = 1f - pulse,
      tag = "dock_imo",
      onClick = onImo,
      modifier = Modifier.weight(1f),
    )
    DockIconItem(
      label = "Messenger",
      icon = Icons.Filled.Bolt,
      accent = Color(0xFFD946EF),
      pulse = pulse,
      tag = "dock_messenger",
      onClick = onMessenger,
      modifier = Modifier.weight(1f),
    )
    DockIconItem(
      label = "YouTube",
      icon = Icons.Filled.MusicNote,
      accent = Color(0xFFFF1744),
      pulse = 1f - pulse,
      tag = "dock_youtube",
      onClick = onYouTube,
      modifier = Modifier.weight(1f),
    )
    DockIconItem(
      label = "Contacts",
      icon = Icons.Filled.PersonOutline,
      accent = Color(0xFFFFD600),
      pulse = pulse,
      tag = "dock_contacts",
      onClick = onContacts,
      modifier = Modifier.weight(1f),
    )
    DockIconItem(
      label = "Settings",
      icon = Icons.Filled.Settings,
      accent = Color(0xFFBAE6FD),
      pulse = 1f - pulse,
      iconRotationDeg = gearAngle,
      tag = "dock_settings",
      onClick = onSettings,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun DockIconItem(
  label: String,
  icon: ImageVector,
  accent: Color,
  pulse: Float,
  tag: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  iconRotationDeg: Float = 0f,
) {
  Column(
    modifier =
      modifier
        .heightIn(min = 52.dp)
        .testTag(tag)
        .clip(RoundedCornerShape(12.dp))
        .background(Color(0xAA081126))
        .border(1.dp, accent.copy(alpha = 0.25f + pulse * 0.28f), RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 5.dp, horizontal = 2.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      modifier =
        Modifier
          .size(26.dp)
          .clip(CircleShape)
          .background(accent.copy(alpha = 0.14f + pulse * 0.14f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = accent,
        modifier =
          Modifier
            .size(18.dp)
            .let { if (iconRotationDeg != 0f) it.rotate(iconRotationDeg) else it },
      )
    }
    Spacer(Modifier.height(2.dp))
    Text(
      text = label,
      color = Color.White,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
