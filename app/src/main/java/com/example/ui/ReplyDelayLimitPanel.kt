package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ReplyDelayOption
import com.example.data.ReplyLimitOption

private val PANEL_BG = Color(0xFF071022)
private val PANEL_BORDER = Color(0xFF1A3A68)
private val SECTION_BLUE = Color(0xFF56B8FF)
private val PILL_SELECTED_BG = Color(0xFF0E2929)
private val PILL_SELECTED_BORDER = Color(0xFF34D399)
private val PILL_SELECTED_DOT = Color(0xFF4ADE80)
private val PILL_SELECTED_TEXT = Color(0xFFD1FAE5)
private val PILL_UNSELECTED_BG = Color(0xFF141F35)
private val PILL_UNSELECTED_BORDER = Color(0xFF3E5278)
private val PILL_UNSELECTED_TEXT = Color(0xFFB6C3DA)
private val FOOTNOTE_MUTED = Color(0xFF93A5C0)

@Composable
fun ReplyDelayLimitPanel(
  selectedDelay: ReplyDelayOption,
  selectedLimit: ReplyLimitOption,
  onSelectDelay: (ReplyDelayOption) -> Unit,
  onSelectLimit: (ReplyLimitOption) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .testTag("reply_delay_limit_card")
        .background(PANEL_BG, RoundedCornerShape(20.dp))
        .border(1.2.dp, PANEL_BORDER, RoundedCornerShape(20.dp))
        .padding(18.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = "Reply Delay and Reply Limit",
      color = SECTION_BLUE,
      fontSize = 18.sp,
      fontWeight = FontWeight.SemiBold,
    )

    Text(
      text = "Reply Delay",
      color = SECTION_BLUE,
      fontSize = 15.sp,
      fontWeight = FontWeight.Medium,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      ReplyDelayOption.entries.forEach { option ->
        RadioPillChip(
          label = option.optionLabel,
          selected = selectedDelay == option,
          tag = "reply_delay_option_${option.seconds}",
          onClick = { onSelectDelay(option) },
        )
      }
    }

    Text(
      text = selectedDelay.selectedLabel,
      color = Color.White,
      fontSize = 14.5.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.testTag("reply_delay_selected_text"),
    )

    Text(
      text = "Reply Limit",
      color = SECTION_BLUE,
      fontSize = 15.sp,
      fontWeight = FontWeight.Medium,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      ReplyLimitOption.entries.forEach { option ->
        val suffix = if (option.maxReplies < 0) "unlimited" else option.maxReplies.toString()
        RadioPillChip(
          label = option.optionLabel,
          selected = selectedLimit == option,
          tag = "reply_limit_option_$suffix",
          onClick = { onSelectLimit(option) },
        )
      }
    }

    Text(
      text = selectedLimit.selectedLabel,
      color = Color.White,
      fontSize = 14.5.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.testTag("reply_limit_selected_text"),
    )

    Text(
      text = "Saved automatically; stays after restart. A wait already running keeps its old time.",
      color = FOOTNOTE_MUTED,
      fontSize = 12.5.sp,
      lineHeight = 18.sp,
      modifier = Modifier.testTag("reply_delay_limit_note"),
    )
  }
}

@Composable
private fun RadioPillChip(
  label: String,
  selected: Boolean,
  tag: String,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .heightIn(min = 48.dp)
        .testTag(tag)
        .clip(RoundedCornerShape(50))
        .background(if (selected) PILL_SELECTED_BG else PILL_UNSELECTED_BG)
        .border(
          width = if (selected) 1.4.dp else 1.dp,
          color = if (selected) PILL_SELECTED_BORDER else PILL_UNSELECTED_BORDER,
          shape = RoundedCornerShape(50),
        )
        .clickable(role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (selected) {
      Box(
        modifier =
          Modifier
            .size(9.dp)
            .background(PILL_SELECTED_DOT, CircleShape)
      )
    } else {
      Box(
        modifier =
          Modifier
            .size(9.dp)
            .border(1.4.dp, PILL_UNSELECTED_TEXT.copy(alpha = 0.75f), CircleShape)
      )
    }
    Spacer(Modifier.width(10.dp))
    Text(
      text = label,
      color = if (selected) PILL_SELECTED_TEXT else PILL_UNSELECTED_TEXT,
      fontSize = 14.sp,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
    )
  }
}
