package com.example.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.osint.LxrOsintOrchestrator
import com.example.osint.OsintInvestigationSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LxrOsintReportViewerDialog(
  onDismiss: () -> Unit,
) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  val progress by LxrOsintOrchestrator.progressState.collectAsState()
  val reports by LxrOsintOrchestrator.savedInvestigations.collectAsState()

  var targetInput by remember { mutableStateOf("") }
  var objectiveInput by remember { mutableStateOf("") }
  var selectedReport by remember { mutableStateOf<OsintInvestigationSummary?>(null) }
  var activeTab by remember { mutableStateOf("final") }
  var previewText by remember { mutableStateOf("") }
  var lastMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
      LxrOsintOrchestrator.refreshSavedInvestigations(ctx)
    }
  }

  LaunchedEffect(selectedReport, activeTab) {
    val rep = selectedReport ?: return@LaunchedEffect
    val path =
      when (activeTab) {
        "summary" -> rep.summaryHtmlPath
        "claims" -> rep.claimsJsonPath
        else -> rep.finalReportPath
      }
    previewText =
      withContext(Dispatchers.IO) {
        LxrOsintOrchestrator.readDeliverablePreviewText(path)
      }
  }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .widthIn(max = 560.dp)
          .heightIn(max = 680.dp)
          .testTag("osint_report_viewer_dialog")
          .clip(RoundedCornerShape(22.dp))
          .background(Color(0xFF0A1024))
          .border(1.3.dp, CYBER_CYAN.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
          .padding(16.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Public, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(22.dp))
          Spacer(Modifier.width(8.dp))
          Text(
            text = "অটোনোমাস OSINT ইন্টেলিজেন্স",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
          )
        }
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.size(48.dp).testTag("osint_dialog_close_btn"),
        ) {
          Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
        }
      }

      OutlinedTextField(
        value = targetInput,
        onValueChange = { targetInput = it },
        modifier = Modifier.fillMaxWidth().testTag("osint_target_input"),
        label = { Text("লক্ষ্য (নাম, ডোমেইন, ইউজারনেম বা বিষয়)") },
        singleLine = true,
      )
      OutlinedTextField(
        value = objectiveInput,
        onValueChange = { objectiveInput = it },
        modifier = Modifier.fillMaxWidth().testTag("osint_objective_input"),
        label = { Text("উদ্দেশ্য / প্রসঙ্গ (ঐচ্ছিক)") },
        singleLine = true,
      )

      Button(
        onClick = {
          if (targetInput.isBlank() || progress.isRunning) return@Button
          scope.launch {
            val result =
              withContext(Dispatchers.IO) {
                LxrOsintOrchestrator.runInvestigation(ctx, targetInput, objectiveInput)
              }
            lastMessage = result.conciseVoiceSummaryBn
            LxrOsintOrchestrator.savedInvestigations.value.firstOrNull()?.let { selectedReport = it }
          }
        },
        enabled = !progress.isRunning && targetInput.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osint_run_button"),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_CYAN),
      ) {
        Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("পাবলিক OSINT তদন্ত শুরু করুন", color = Color.Black, fontWeight = FontWeight.Bold)
      }

      if (progress.isRunning || progress.currentStageIndex > 0) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .background(Color(0xFF101933), RoundedCornerShape(12.dp))
              .padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(progress.stageTitleBn, color = CYBER_CYAN, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
          LinearProgressIndicator(
            progress = { (progress.currentStageIndex.toFloat() / progress.totalStages.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = CYBER_GREEN,
          )
        }
      }

      lastMessage?.let { msg ->
        Text(
          text = msg,
          color = Color(0xFFE5E7EB),
          fontSize = 12.sp,
          modifier =
            Modifier
              .fillMaxWidth()
              .background(Color(0xFF0E172E), RoundedCornerShape(12.dp))
              .border(1.dp, CYBER_GREEN.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
              .padding(10.dp),
        )
      }

      Text(
        text = "সংরক্ষিত তদন্তসমূহ (Download/LxR/OSINT): ${reports.size}টি",
        color = CYBER_CYAN,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier.testTag("osint_saved_count_text"),
      )

      if (reports.isEmpty()) {
        Text("এখনো কোনো OSINT রিপোর্ট সংরক্ষিত নেই।", color = Color.Gray, fontSize = 12.sp)
      } else {
        reports.take(6).forEach { rep ->
          val isSel = selectedReport?.investigationId == rep.investigationId
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isSel) Color(0xFF15254A) else Color(0xFF10182E))
                .border(1.dp, if (isSel) CYBER_CYAN else Color(0xFF233252), RoundedCornerShape(12.dp))
                .clickable { selectedReport = rep }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
              Text(rep.displayTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(rep.investigationId, color = Color.LightGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }

      selectedReport?.let { rep ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          OutlinedButton(onClick = { activeTab = "final" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("Final", color = if (activeTab == "final") CYBER_CYAN else Color.White, fontSize = 11.sp)
          }
          OutlinedButton(onClick = { activeTab = "summary" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("Summary", color = if (activeTab == "summary") CYBER_CYAN else Color.White, fontSize = 11.sp)
          }
          OutlinedButton(onClick = { activeTab = "claims" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("JSON", color = if (activeTab == "claims") CYBER_CYAN else Color.White, fontSize = 11.sp)
          }
        }
        Text(
          text = previewText.ifBlank { "লোড হচ্ছে..." }.take(2800),
          color = Color(0xFFD1D5DB),
          fontSize = 11.5.sp,
          modifier =
            Modifier
              .fillMaxWidth()
              .background(Color(0xFF060B17), RoundedCornerShape(10.dp))
              .padding(10.dp),
        )
        Button(
          onClick = {
            val shareIntent =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "LXR--AI OSINT Report: ${rep.displayTitle}")
                putExtra(Intent.EXTRA_TEXT, previewText.take(4000))
              }
            runCatching { ctx.startActivity(Intent.createChooser(shareIntent, "OSINT রিপোর্ট শেয়ার করুন")) }
          },
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osint_share_report_btn"),
          colors = ButtonDefaults.buttonColors(containerColor = CYBER_GREEN),
        ) {
          Icon(Icons.Filled.Share, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("রিপোর্ট শেয়ার / এক্সপোর্ট করুন", color = Color.Black, fontWeight = FontWeight.Bold)
        }
      }
      Spacer(Modifier.height(4.dp))
    }
  }
}
