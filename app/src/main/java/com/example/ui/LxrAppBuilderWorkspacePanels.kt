package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.builder.AppBuilderFileRepository
import com.example.builder.AppBuilderUiState
import com.example.builder.AppBuilderViewModel
import com.example.builder.BuildStatus
import com.example.builder.BuilderConfirmationRequest
import com.example.builder.DevelopmentStage
import com.example.builder.TermuxBuildBridge

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdeCodeEditorTab(
  state: AppBuilderUiState,
  vm: AppBuilderViewModel,
) {
  val project = state.activeProject
  var showAddFileDialog by remember { mutableStateOf(false) }

  if (project == null || project.files.isEmpty()) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .background(CYBER_CARD)
          .border(1.dp, CYBER_PURPLE.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
          .padding(18.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(Icons.Filled.Code, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(34.dp))
      Text("এখনো কোনো প্রজেক্ট কোড তৈরি হয়নি", color = Color.White, fontWeight = FontWeight.Bold)
      Text(
        "প্রথমে ড্যাশবোর্ড থেকে অ্যাপের বিবরণ দিয়ে 'স্বয়ংক্রিয় অ্যাপ তৈরি করুন' চাপুন অথবা History থেকে পুরনো প্রজেক্ট খুলুন।",
        color = Color(0xFFAAB4D0),
        fontSize = 12.sp,
      )
    }
    return
  }

  // Requirement Analysis & Architecture Summary Card
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(CYBER_CARD)
        .border(1.dp, CYBER_PURPLE.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text("📋 Requirement Analysis ও Architecture", color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
    Text(project.analysisReport, color = Color(0xFFD8E2FF), fontSize = 11.5.sp, lineHeight = 16.sp)
    if (project.architecturePlan.isNotBlank()) {
      Text(project.architecturePlan, color = Color(0xFFAAB4D0), fontSize = 11.sp, lineHeight = 15.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(
        onClick = { vm.downloadSourceCodeZip() },
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("builder_download_source_zip_btn"),
      ) {
        Icon(Icons.Filled.Download, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Source ZIP", color = Color.White, fontSize = 12.sp)
      }
      OutlinedButton(
        onClick = { vm.backupProjectZip() },
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("builder_backup_zip_btn"),
      ) {
        Icon(Icons.Filled.Archive, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Backup ZIP", color = Color.White, fontSize = 12.sp)
      }
    }
  }

  // File Explorer Strip
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF0C1226))
        .border(1.dp, Color(0xFF283459), RoundedCornerShape(16.dp))
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = CYBER_AMBER, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("প্রজেক্ট ফাইলসমূহ (${project.files.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
      IconButton(
        onClick = { showAddFileDialog = true },
        modifier = Modifier.size(48.dp).testTag("builder_add_file_btn"),
      ) {
        Icon(Icons.Filled.Add, contentDescription = "Add File", tint = CYBER_GREEN)
      }
      IconButton(
        onClick = { vm.requestDeleteCodeFile(state.selectedFilePath) },
        modifier = Modifier.size(48.dp).testTag("builder_delete_file_btn"),
      ) {
        Icon(Icons.Filled.Delete, contentDescription = "Delete Selected File", tint = CYBER_RED)
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      project.files.keys.forEach { path ->
        val selected = path == state.selectedFilePath
        val shortName = path.substringAfterLast('/')
        Box(
          modifier =
            Modifier
              .clip(RoundedCornerShape(10.dp))
              .background(if (selected) CYBER_PURPLE.copy(alpha = 0.35f) else Color(0xFF151E3B))
              .border(1.dp, if (selected) CYBER_CYAN else Color(0xFF2D3961), RoundedCornerShape(10.dp))
              .clickable { vm.selectEditorFile(path) }
              .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
          Text(
            text = shortName,
            color = if (selected) CYBER_CYAN else Color(0xFFCBD5E1),
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
          )
        }
      }
    }

    Text(
      text = "ফাইল পাথ: ${state.selectedFilePath}",
      color = Color(0xFF94A3B8),
      fontSize = 10.5.sp,
      fontFamily = FontFamily.Monospace,
    )

    // Code Editor Surface
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(min = 240.dp, max = 380.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(Color(0xFF060913))
          .border(1.dp, CYBER_CYAN.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
          .padding(10.dp),
    ) {
      BasicTextField(
        value = state.editorText,
        onValueChange = { vm.updateEditorText(it) },
        textStyle =
          TextStyle(
            color = Color(0xFFE2E8F0),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 16.5.sp,
          ),
        cursorBrush = SolidColor(CYBER_CYAN),
        modifier = Modifier.fillMaxWidth().testTag("builder_code_editor_field"),
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      Button(
        onClick = { vm.requestSaveEditedFile() },
        enabled = state.editorModified,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("builder_save_file_btn"),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_GREEN, contentColor = Color.Black),
      ) {
        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (state.editorModified) "Save Code (অনুমতিসহ)" else "Saved ✓", fontWeight = FontWeight.Bold, fontSize = 12.sp)
      }
    }
  }

  if (showAddFileDialog) {
    AddCodeFileDialog(
      onDismiss = { showAddFileDialog = false },
      onAdd = { path ->
        showAddFileDialog = false
        vm.addCustomCodeFile(path)
      },
    )
  }
}

@Composable
fun IdeBuildAndTermuxTab(
  state: AppBuilderUiState,
  vm: AppBuilderViewModel,
) {
  val ctx = LocalContext.current
  val termuxPermLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      vm.refreshHistoryAndTermux()
    }
  val safTreeLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      vm.onSafTreeGranted(uri)
    }

  // 1. Progress Bar & Stage Status Card
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(CYBER_CARD)
        .border(1.2.dp, CYBER_CYAN.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.Build, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
      Text(
        text = state.activeProject?.buildStatus?.labelBn ?: BuildStatus.IDLE.labelBn,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "${(state.pipelineProgress * 100).toInt()}%",
        color = CYBER_CYAN,
        fontWeight = FontWeight.Bold,
        fontSize = 12.5.sp,
      )
    }

    LinearProgressIndicator(
      progress = { state.pipelineProgress.coerceIn(0f, 1f) },
      modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(6.dp)).testTag("builder_progress_bar"),
      color = CYBER_CYAN,
      trackColor = Color(0xFF1F2A48),
    )

    state.currentStage?.let { stage ->
      Text(
        text = "চলমান ধাপ (${stage.stepNumber}/10): ${stage.titleBn}",
        color = CYBER_GREEN,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
      )
    }
    if (state.termuxStepLabel.isNotBlank()) {
      Text(
        text = "Termux লাইভ অবস্থা: ${state.termuxStepLabel}",
        color = Color(0xFF93C5FD),
        fontSize = 11.5.sp,
      )
    }
  }

  // 1.5 Interactive Live Preview Card visible right inside Build tab too
  LxrLiveAppPreviewCard(
    appName = state.activeProject?.name ?: state.projectName,
    description = state.activeProject?.description ?: state.appDescription,
    category = state.activeProject?.category ?: state.category,
    designTheme = state.activeProject?.designTheme ?: state.designTheme,
    isBuilding = state.isBuilding || state.isGenerating,
    stepLabel = state.termuxStepLabel,
  )

  // 2. Termux Free Build Integration Card
  val termux = state.termuxStatus
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .testTag("builder_termux_card")
        .clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF0C152B))
        .border(1.2.dp, (if (termux.termuxInstalled && termux.runCommandPermissionGranted) CYBER_GREEN else CYBER_AMBER).copy(alpha = 0.6f), RoundedCornerShape(16.dp))
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.Terminal, contentDescription = null, tint = CYBER_GREEN, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
      Text("Termux ও ফ্রি ওপেন-সোর্স বিল্ড সিস্টেম", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
      IconButton(onClick = { vm.refreshHistoryAndTermux() }, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.Refresh, contentDescription = "Refresh Termux Status", tint = CYBER_CYAN)
      }
    }

    Text(
      text =
        "Termux: ${if (termux.termuxInstalled) "ইনস্টল আছে ✓" else "পাওয়া যায়নি ✗"} • " +
          "RUN_COMMAND: ${if (termux.runCommandPermissionGranted) "চালু ✓" else "বন্ধ ⚠"} • " +
          "Download এক্সেস: ${termux.safFolderLabel.ifBlank { "চেকিং..." }}",
      color = if (termux.termuxInstalled && termux.runCommandPermissionGranted) CYBER_GREEN else CYBER_AMBER,
      fontSize = 11.5.sp,
      fontWeight = FontWeight.SemiBold,
    )
    Text(termux.setupSummaryBn, color = Color(0xFFCBD5E1), fontSize = 11.5.sp)
    if (termux.sharedDownloadDirPath.isNotBlank()) {
      Text(
        text = "Shared Download Folder:\n${termux.sharedDownloadDirPath}",
        color = Color(0xFF94A3B8),
        fontSize = 10.5.sp,
        fontFamily = FontFamily.Monospace,
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      if (!termux.runCommandPermissionGranted) {
        OutlinedButton(
          onClick = { termuxPermLauncher.launch(TermuxBuildBridge.TERMUX_RUN_COMMAND_PERMISSION) },
          modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
          Text("Termux অনুমতি দিন", color = CYBER_AMBER, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        }
      }
      if (!termux.hasSafAccess) {
        OutlinedButton(
          onClick = {
            runCatching {
              val initialUri =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                  android.net.Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary%3ADownload"
                  )
                } else {
                  null
                }
              safTreeLauncher.launch(initialUri)
            }
          },
          modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("builder_grant_saf_btn"),
        ) {
          Text("Download এক্সেস", color = CYBER_CYAN, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        }
      }
      OutlinedButton(
        onClick = {
          val slug = state.activeProject?.slug ?: "amar_calculator"
          val cmd = TermuxBuildBridge.firstTimeTermuxSetupCommand(slug)
          val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("Termux Setup", cmd))
          Toast.makeText(ctx, "Termux সেটআপ ও বিল্ড কমান্ড কপি হয়েছে!", Toast.LENGTH_SHORT).show()
          TermuxBuildBridge.openTermuxApp(ctx)
        },
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("builder_copy_termux_cmd_btn"),
      ) {
        Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text("কমান্ড কপি ও Termux", color = Color.White, fontSize = 11.sp)
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      Button(
        onClick = { vm.syncAndBuildViaTermux() },
        enabled = state.activeProject != null && !state.isBuilding && !state.isGenerating,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("builder_run_termux_build_btn"),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_CYAN, contentColor = Color.Black),
      ) {
        Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (state.isBuilding) "বিল্ড চলছে..." else "Termux-এ APK Build", fontWeight = FontWeight.Bold, fontSize = 12.sp)
      }
      OutlinedButton(
        onClick = { vm.verifyAndFinalizeSharedBuild() },
        enabled = state.activeProject != null,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("builder_verify_apk_btn"),
      ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = CYBER_GREEN, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text("APK ও লগ যাচাই", color = Color.White, fontSize = 12.sp)
      }
    }
  }

  // 3. Error Panel + Automatic Error Fixing
  state.errorDiagnosis?.let { diag ->
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .testTag("builder_error_panel")
          .clip(RoundedCornerShape(16.dp))
          .background(Color(0xFF2B0915))
          .border(1.4.dp, CYBER_RED, RoundedCornerShape(16.dp))
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = CYBER_RED, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          text = if (diag.isRepeatedSameError) "Error Panel — একই ত্রুটি বারবার হচ্ছে" else "Error Diagnosis (প্রচেষ্টা ${diag.attemptCount})",
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 13.5.sp,
        )
      }
      Text("কারণ: ${diag.rootCauseBn}", color = Color(0xFFFFD1DC), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      Text("সমাধান: ${diag.suggestedFixBn}", color = Color(0xFFCBD5E1), fontSize = 11.5.sp)
      if (!diag.affectedFile.isNullOrBlank()) {
        Text("ফাইল: ${diag.affectedFile}", color = CYBER_AMBER, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
      }
      Text(
        text = diag.rawLogSnippet,
        color = Color(0xFFFDA4AF),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier =
          Modifier
            .fillMaxWidth()
            .background(Color(0xFF14040A), RoundedCornerShape(8.dp))
            .padding(8.dp),
      )
      if (diag.autoFixApplied && !diag.isRepeatedSameError) {
        Button(
          onClick = { vm.syncAndBuildViaTermux() },
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("builder_retry_autofix_btn"),
          colors = ButtonDefaults.buttonColors(containerColor = CYBER_AMBER, contentColor = Color.Black),
        ) {
          Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("অটো-ফিক্স প্রয়োগ হয়েছে — পুনরায় Build দিন", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
      }
    }
  }

  // 4. APK Result Card
  val apk = state.activeProject?.apkResult
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .testTag("builder_apk_result_card")
        .clip(RoundedCornerShape(16.dp))
        .background(if (apk?.exists == true) Color(0xFF07231C) else CYBER_CARD)
        .border(
          1.4.dp,
          if (apk?.exists == true) CYBER_GREEN else Color(0xFF334155),
          RoundedCornerShape(16.dp),
        )
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.Android, contentDescription = null, tint = if (apk?.exists == true) CYBER_GREEN else Color(0xFF94A3B8), modifier = Modifier.size(22.dp))
      Spacer(Modifier.width(8.dp))
      Text("APK Result Card", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
      Text(
        text =
          when {
            apk?.exists == true -> "Verified APK ✓"
            state.isBuilding -> "বিল্ড চলছে..."
            else -> "প্রস্তুত / অপেক্ষমান"
          },
        color = if (apk?.exists == true) CYBER_GREEN else CYBER_AMBER,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
      )
    }

    if (apk != null && apk.exists) {
      val kb = (apk.sizeBytes / 1024.0)
      val sizeLabel = if (kb >= 1024) String.format("%.2f MB", kb / 1024.0) else String.format("%.1f KB", kb)
      Text(
        text = "ফাইল: ${apk.apkFileName} ($sizeLabel) • মোড: ${apk.buildMode.labelBn}\nডাউনলোড পাথ: ${apk.downloadExportPath}",
        color = Color(0xFFD1FAE5),
        fontSize = 11.5.sp,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
          onClick = { vm.downloadApkToPublicFolder() },
          modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("builder_download_apk_btn"),
          colors = ButtonDefaults.buttonColors(containerColor = CYBER_CYAN, contentColor = Color.Black),
        ) {
          Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Download APK", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Button(
          onClick = {
            val err = vm.installBuiltApk()
            if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
          },
          modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("builder_install_apk_btn"),
          colors = ButtonDefaults.buttonColors(containerColor = CYBER_GREEN, contentColor = Color.Black),
        ) {
          Icon(Icons.Filled.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Install APK", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
      }
    } else {
      val activeSlug = state.activeProject?.slug ?: "myapp"
      Text(
        text =
          if (state.isBuilding) "Termux-এ এখন APK কম্পাইল হচ্ছে... বিল্ড শেষ হওয়ামাত্র এখানে APK সাইজ, Download ও Install বাটন সক্রিয় হয়ে যাবে।"
          else "ফোল্ডার: Downloads/LXR-AppBuilder/$activeSlug/\nTermux-এ 'Termux-এ APK Build' চালালে বা শেষ হলে 'APK ও লগ যাচাই' চাপলে এখানে Download ও Install বাটন সক্রিয় হবে।",
        color = Color(0xFFAAB4D0),
        fontSize = 11.5.sp,
      )
    }
  }

  // 5. Build Console Log
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .testTag("builder_console_card")
        .clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF050811))
        .border(1.dp, Color(0xFF283459), RoundedCornerShape(16.dp))
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = "🖥 Build Console (${state.consoleLogs.size} lines)",
        color = CYBER_CYAN,
        fontWeight = FontWeight.Bold,
        fontSize = 12.5.sp,
        modifier = Modifier.weight(1f),
      )
      TextButton(
        onClick = { vm.verifyAndFinalizeSharedBuild() },
        enabled = state.activeProject != null,
      ) {
        Text("Refresh Log", color = CYBER_GREEN, fontSize = 11.sp)
      }
      TextButton(onClick = { vm.clearConsoleLogs() }) {
        Text("Clear", color = CYBER_AMBER, fontSize = 11.sp)
      }
    }
    val logText =
      if (state.consoleLogs.isEmpty()) "প্রস্তুত — কোনো বিল্ড লগ এখনো তৈরি হয়নি।"
      else state.consoleLogs.joinToString("\n")
    Text(
      text = logText,
      color = Color(0xFFCBD5E1),
      fontSize = 10.5.sp,
      fontFamily = FontFamily.Monospace,
      lineHeight = 15.sp,
    )
  }
}

@Composable
fun BuilderConfirmationDialog(
  request: BuilderConfirmationRequest,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = CYBER_SURFACE,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = CYBER_AMBER, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(request.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      }
    },
    text = {
      Text(request.message, color = Color(0xFFD1D5DB), fontSize = 13.sp, lineHeight = 18.sp)
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        modifier = Modifier.testTag("builder_confirm_action_btn"),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_RED, contentColor = Color.White),
      ) {
        Text("অনুমতি দিন")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("বাতিল", color = CYBER_CYAN)
      }
    },
  )
}

@Composable
private fun AddCodeFileDialog(
  onDismiss: () -> Unit,
  onAdd: (String) -> Unit,
) {
  var path by remember { mutableStateOf("app/src/main/java/com/lxr/Helper.kt") }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = CYBER_SURFACE,
    title = { Text("নতুন ফাইল তৈরি করুন", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
    text = {
      OutlinedTextField(
        value = path,
        onValueChange = { path = it },
        label = { Text("ফাইল পাথ") },
        singleLine = true,
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = CYBER_CYAN,
            unfocusedBorderColor = Color(0xFF475073),
          ),
      )
    },
    confirmButton = {
      Button(onClick = { onAdd(path) }) { Text("যোগ করুন") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("বাতিল", color = CYBER_CYAN) }
    },
  )
}
