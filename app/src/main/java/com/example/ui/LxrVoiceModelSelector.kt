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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.LiveVoiceModelOption
import com.example.data.Store

/**
 * Animated rounded-square purple-violet Voice Model button placed right in front of the
 * upper-right potted plant on all 3 home themes, matching the Gemini Live sparkle icon.
 */
@Composable
fun LxrVoiceModelTreeButton(
  activeModelId: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val activeOption = remember(activeModelId) { Store.liveVoiceModelById(activeModelId) }
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()

  val pressScale by animateFloatAsState(
    targetValue = if (isPressed) 0.90f else 1f,
    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
    label = "vm_btn_press_scale",
  )

  val inf = rememberInfiniteTransition(label = "voice_model_tree_btn")
  val pulse by inf.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "vm_pulse",
  )
  val sparkleSpin by inf.animateFloat(
    initialValue = -6f,
    targetValue = 6f,
    animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
    label = "vm_sparkle_sway",
  )

  val shape = RoundedCornerShape(15.dp)

  Box(
    modifier =
      modifier
        .size(48.dp)
        .scale(pressScale * (1f + pulse * 0.045f))
        .testTag("voice_model_button")
        .semantics { contentDescription = "Voice model: ${activeOption.title}" }
        .shadow((10f + pulse * 8f).dp, shape, spotColor = Color(0xFF8B5CF6))
        .clip(shape)
        .background(
          Brush.linearGradient(
            colors = listOf(Color(0xFF7E3FF2), Color(0xFF6328D9), Color(0xFF4718B3)),
            start = Offset(0f, 0f),
            end = Offset(140f, 140f),
          ),
          shape,
        )
        .border(
          width = 1.6.dp,
          brush =
            Brush.linearGradient(
              listOf(
                Color(0xFF00F0FF).copy(alpha = 0.72f + pulse * 0.25f),
                Color(0xFFA78BFA).copy(alpha = 0.85f),
                Color(0xFF00F0FF).copy(alpha = 0.55f + (1f - pulse) * 0.35f),
              )
            ),
          shape = shape,
        )
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      // Subtle diagonal neon glass sheen
      drawCircle(
        brush =
          Brush.radialGradient(
            listOf(Color(0xFFC4B5FD).copy(alpha = 0.26f + pulse * 0.16f), Color.Transparent),
            center = Offset(w * 0.35f, h * 0.30f),
            radius = w * 0.72f,
          ),
        radius = w * 0.72f,
        center = Offset(w * 0.35f, h * 0.30f),
      )

      // 3 crisp white 4-point Gemini sparkles matching screenshot (IMG_20261122_075600_799)
      rotate(degrees = sparkleSpin, pivot = Offset(w * 0.44f, h * 0.50f)) {
        drawFourPointSparkle(
          center = Offset(w * 0.43f, h * 0.51f),
          radius = w * 0.22f,
          color = Color.White,
        )
      }
      drawFourPointSparkle(
        center = Offset(w * 0.69f, h * 0.31f),
        radius = w * 0.105f,
        color = Color.White.copy(alpha = 0.92f + pulse * 0.08f),
      )
      drawFourPointSparkle(
        center = Offset(w * 0.67f, h * 0.70f),
        radius = w * 0.105f,
        color = Color.White.copy(alpha = 0.88f + (1f - pulse) * 0.12f),
      )
    }
  }
}

private fun DrawScope.drawFourPointSparkle(
  center: Offset,
  radius: Float,
  color: Color,
) {
  val inner = radius * 0.28f
  val path =
    Path().apply {
      moveTo(center.x, center.y - radius)
      quadraticTo(center.x + inner * 0.45f, center.y - inner * 0.45f, center.x + radius, center.y)
      quadraticTo(center.x + inner * 0.45f, center.y + inner * 0.45f, center.x, center.y + radius)
      quadraticTo(center.x - inner * 0.45f, center.y + inner * 0.45f, center.x - radius, center.y)
      quadraticTo(center.x - inner * 0.45f, center.y - inner * 0.45f, center.x, center.y - radius)
      close()
    }
  drawPath(path, color = color)
}

/**
 * Animated "Voice model" selection dialog matching the dark rounded modal design with
 * violet checkmark and golden-amber Close action.
 */
@Composable
fun LxrVoiceModelPopupDialog(
  activeModelId: String,
  onSelectModel: (LiveVoiceModelOption) -> Unit,
  onDismiss: () -> Unit,
) {
  val selectedOption = remember(activeModelId) { Store.liveVoiceModelById(activeModelId) }
  val inf = rememberInfiniteTransition(label = "voice_model_dialog_glow")
  val borderGlow by inf.animateFloat(
    initialValue = 0.45f,
    targetValue = 0.92f,
    animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "vm_dialog_border",
  )

  Dialog(onDismissRequest = onDismiss) {
    val dialogShape = RoundedCornerShape(26.dp)
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .widthIn(max = 380.dp)
          .testTag("voice_model_dialog")
          .shadow(22.dp, dialogShape, spotColor = Color(0xFF7C3AED))
          .clip(dialogShape)
          .background(
            Brush.verticalGradient(listOf(Color(0xFF1F1D26), Color(0xFF16141D))),
            dialogShape,
          )
          .border(
            width = 1.3.dp,
            brush =
              Brush.linearGradient(
                listOf(
                  Color(0xFF8B5CF6).copy(alpha = borderGlow),
                  Color(0xFF00F0FF).copy(alpha = borderGlow * 0.72f),
                  Color(0xFF8B5CF6).copy(alpha = borderGlow),
                )
              ),
            shape = dialogShape,
          )
          .padding(horizontal = 24.dp, vertical = 22.dp),
    ) {
      Text(
        text = "Voice model",
        color = Color(0xFFF3F4F6),
        fontSize = 23.sp,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        modifier = Modifier.padding(bottom = 14.dp),
      )

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Store.ALL_VOICE_MODEL_OPTIONS.forEachIndexed { index, option ->
          val isSelected = selectedOption.id == option.id
          val rowShape = RoundedCornerShape(14.dp)
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("voice_model_option_$index")
                .clip(rowShape)
                .background(
                  if (isSelected) Color(0xFF7C3AED).copy(alpha = 0.14f) else Color.Transparent,
                  rowShape,
                )
                .clickable { onSelectModel(option) }
                .padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = option.title,
              color = if (isSelected) Color.White else Color(0xFFE5E7EB),
              fontSize = 16.5.sp,
              lineHeight = 22.sp,
              fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
              modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            if (isSelected) {
              Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = Color(0xFF8B5CF6),
                modifier = Modifier.size(22.dp),
              )
            }
          }
        }
      }

      Spacer(Modifier.height(14.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        TextButton(
          onClick = onDismiss,
          modifier = Modifier.heightIn(min = 48.dp).testTag("voice_model_close_btn"),
        ) {
          Text(
            text = "Close",
            color = Color(0xFFF5B041),
            fontSize = 15.5.sp,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}
