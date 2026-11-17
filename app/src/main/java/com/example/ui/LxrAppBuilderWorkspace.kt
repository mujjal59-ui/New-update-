package com.example.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.builder.AndroidApiLevel
import com.example.builder.ApkBuildMode
import com.example.builder.AppBuilderProject
import com.example.builder.AppBuilderUiState
import com.example.builder.AppBuilderViewModel
import com.example.builder.AppCategory
import com.example.builder.AppDesignTheme
import com.example.builder.IdeWorkspaceTab
import com.example.builder.ProgrammingLanguage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LxrAppBuilderWorkspace(
  back: () -> Unit,
  onNavigate: (String) -> Unit,
  vm: AppBuilderViewModel = viewModel(),
) {
  BackHandler(onBack = back)
  val state by vm.uiState.collectAsState()
  var renameTarget by remember { mutableStateOf<AppBuilderProject?>(null) }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .testTag("app_builder_screen")
        .background(FULL_BG_GRADIENT)
        .statusBarsPadding()
        .navigationBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(
      modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SubPageTopBar(
        title = "AI App Builder IDE",
        icon = Icons.Filled.Apps,
        iconColors = listOf(Color(0xFFA855F7), CYBER_BLUE),
        onBack = back,
        onNavigate = onNavigate,
      )

      // Free-Only Open-Source Toolchain Banner
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A241E))
            .border(1.dp, CYBER_GREEN.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.Verified, contentDescription = null, tint = CYBER_GREEN, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          text = "১০০% ফ্রি ও ওপেন-সোর্স • কোনো Paid API নেই • Termux Local Build",
          color = Color(0xFFD1FAE5),
          fontSize = 11.5.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
        )
      }

      // Status feedback banner
      state.statusBanner?.let { banner ->
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(Color(0xFF10213F))
              .border(1.dp, CYBER_CYAN.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
              .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = banner,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = { vm.dismissBanner() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Close Banner", tint = CYBER_CYAN, modifier = Modifier.size(16.dp))
          }
        }
      }

      // IDE Tab Switcher + Quick Save
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IdeWorkspaceTab.entries.forEach { tab ->
          val active = state.activeTab == tab
          Box(
            modifier =
              Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("builder_tab_${tab.name.lowercase()}")
                .clip(RoundedCornerShape(12.dp))
                .background(
                  if (active) Brush.linearGradient(listOf(Color(0xFFA855F7), CYBER_BLUE))
                  else Brush.linearGradient(listOf(CYBER_CARD, CYBER_CARD))
                )
                .border(1.dp, if (active) CYBER_CYAN else Color(0xFF334155), RoundedCornerShape(12.dp))
                .clickable { vm.selectTab(tab) }
                .padding(horizontal = 6.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = tab.titleBn,
              color = Color.White,
              fontSize = 12.sp,
              fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      when (state.activeTab) {
        IdeWorkspaceTab.DASHBOARD ->
          IdeDashboardTab(
            state = state,
            vm = vm,
            onRequestRename = { renameTarget = it },
          )
        IdeWorkspaceTab.EDITOR -> IdeCodeEditorTab(state = state, vm = vm)
        IdeWorkspaceTab.BUILD -> IdeBuildAndTermuxTab(state = state, vm = vm)
      }

      OutlinedButton(
        onClick = back,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("app_builder_screen_back_btn"),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = null,
          tint = Color(0xFFA855F7),
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = stringResource(R.string.back_to_home_label), fontWeight = FontWeight.Bold)
      }
    }
  }

  state.pendingConfirmation?.let { req ->
    BuilderConfirmationDialog(
      request = req,
      onConfirm = { vm.confirmPendingAction() },
      onDismiss = { vm.dismissPendingAction() },
    )
  }

  renameTarget?.let { proj ->
    RenameProjectDialog(
      initialName = proj.name,
      onDismiss = { renameTarget = null },
      onConfirm = { newName ->
        renameTarget = null
        vm.renameProject(proj.id, newName)
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdeDashboardTab(
  state: AppBuilderUiState,
  vm: AppBuilderViewModel,
  onRequestRename: (AppBuilderProject) -> Unit,
) {
  val fieldColors =
    OutlinedTextFieldDefaults.colors(
      focusedTextColor = Color.White,
      unfocusedTextColor = Color.White,
      focusedBorderColor = CYBER_CYAN,
      unfocusedBorderColor = Color(0xFF475073),
      focusedLabelColor = CYBER_CYAN,
      unfocusedLabelColor = Color(0xFFAAB4D0),
    )

  // Project Creation Card
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .testTag("builder_creation_card")
        .clip(RoundedCornerShape(18.dp))
        .background(CYBER_CARD)
        .border(1.4.dp, Color(0xFFA855F7).copy(alpha = 0.6f), RoundedCornerShape(18.dp))
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("🚀 নতুন অ্যাপ তৈরি করুন (Dashboard)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
      TextButton(
        onClick = { vm.startNewProjectDraft() },
        modifier = Modifier.heightIn(min = 48.dp).testTag("builder_new_project_btn"),
      ) {
        Icon(Icons.Filled.AddCircleOutline, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("New", color = CYBER_CYAN, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
      if (state.activeProject != null) {
        IconButton(onClick = { vm.saveActiveProjectExplicitly() }, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.Save, contentDescription = "Save Project", tint = CYBER_GREEN)
        }
      }
    }

    OutlinedTextField(
      value = state.projectName,
      onValueChange = { vm.updateForm(name = it) },
      modifier = Modifier.fillMaxWidth().testTag("builder_project_name_input"),
      label = { Text("Project Name (অ্যাপের নাম)") },
      singleLine = true,
      colors = fieldColors,
    )

    OutlinedTextField(
      value = state.appDescription,
      onValueChange = { vm.updateForm(description = it) },
      modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).testTag("builder_app_desc_input"),
      label = { Text("App Description (কী ধরনের অ্যাপ তৈরি করতে চান লিখুন)") },
      placeholder = { Text("যেমন: একটি আধুনিক ক্যালকুলেটর বা নোটস অ্যাপ যেখানে ইতিহাস সেভ থাকবে...", color = Color.Gray) },
      maxLines = 4,
      colors = fieldColors,
    )

    Text("App Category (${AppCategory.entries.size} ধরনের অ্যাপ)", color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      AppCategory.entries.forEach { cat ->
        val selected = state.category == cat
        FilterChip(
          selected = selected,
          onClick = { vm.updateForm(category = cat) },
          modifier = Modifier.testTag("builder_cat_${cat.name.lowercase()}"),
          label = { Text("${cat.titleEn} (${cat.titleBn})", fontSize = 11.sp) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = Color(0xFF7E22CE),
              selectedLabelColor = Color.White,
              labelColor = Color(0xFFCBD5E1),
            ),
        )
      }
    }

    Text("App Design Selection", color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      AppDesignTheme.entries.forEach { theme ->
        val selected = state.designTheme == theme
        val swatch = Color(android.graphics.Color.parseColor(theme.primaryHex))
        Row(
          modifier =
            Modifier
              .clip(RoundedCornerShape(12.dp))
              .background(if (selected) swatch.copy(alpha = 0.22f) else Color(0xFF0E152E))
              .border(1.2.dp, if (selected) swatch else Color(0xFF334155), RoundedCornerShape(12.dp))
              .clickable { vm.updateForm(designTheme = theme) }
              .padding(horizontal = 10.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(Modifier.size(12.dp).background(swatch, CircleShape))
          Spacer(Modifier.width(6.dp))
          Text(theme.labelBn, color = Color.White, fontSize = 11.5.sp)
        }
      }
    }

    OutlinedTextField(
      value = state.packageName,
      onValueChange = { vm.updateForm(packageName = it) },
      modifier = Modifier.fillMaxWidth().testTag("builder_package_name_input"),
      label = { Text("Package Name (যেমন: com.lxr.app.myapp)") },
      singleLine = true,
      colors = fieldColors,
    )

    Text("Programming Language & UI", color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      ProgrammingLanguage.entries.forEach { lang ->
        FilterChip(
          selected = state.language == lang,
          onClick = { vm.updateForm(language = lang) },
          modifier = Modifier.testTag("builder_lang_${lang.name.lowercase()}"),
          label = { Text(lang.labelBn, fontSize = 11.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_BLUE, selectedLabelColor = Color.White, labelColor = Color(0xFFCBD5E1)),
        )
      }
    }

    Text("Minimum Android Version ও Build Type", color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      AndroidApiLevel.entries.forEach { api ->
        FilterChip(
          selected = state.minSdk == api,
          onClick = { vm.updateForm(minSdk = api) },
          label = { Text("API ${api.sdkInt}", fontSize = 11.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_PURPLE, selectedLabelColor = Color.White, labelColor = Color(0xFFCBD5E1)),
        )
      }
      ApkBuildMode.entries.forEach { mode ->
        FilterChip(
          selected = state.buildMode == mode,
          onClick = { vm.updateForm(buildMode = mode) },
          label = { Text(mode.labelBn, fontSize = 11.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_GREEN, selectedLabelColor = Color.Black, labelColor = Color(0xFFCBD5E1)),
        )
      }
    }

    Button(
      onClick = { vm.requestGenerateProject() },
      enabled = !state.isGenerating && !state.isBuilding,
      modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("builder_generate_btn"),
      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA855F7), contentColor = Color.White),
    ) {
      Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(
        text = if (state.isGenerating) "AI তৈরি করছে..." else "⚡ AI দিয়ে স্বয়ংক্রিয় অ্যাপ তৈরি করুন",
        fontWeight = FontWeight.Bold,
        fontSize = 13.5.sp,
      )
    }
  }

  // Live Interactive App Preview / Demo
  LxrLiveAppPreviewCard(
    appName = state.projectName,
    description = state.appDescription,
    category = state.category,
    designTheme = state.designTheme,
    isBuilding = state.isGenerating || state.isBuilding,
    stepLabel = state.termuxStepLabel,
  )

  // Project History Card
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .testTag("builder_history_card")
        .clip(RoundedCornerShape(18.dp))
        .background(CYBER_CARD)
        .border(1.dp, CYBER_CYAN.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.History, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("Project History (${state.history.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
    if (state.history.isEmpty()) {
      Text("এখনো কোনো সেভ করা প্রজেক্ট নেই।", color = Color(0xFFAAB4D0), fontSize = 12.sp)
    } else {
      val fmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
      state.history.forEach { proj ->
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(Color(0xFF0E152E))
              .border(1.dp, if (state.activeProject?.id == proj.id) CYBER_CYAN else Color(0xFF283459), RoundedCornerShape(12.dp))
              .padding(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text(proj.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
              "${proj.category.titleEn} • ${proj.language.name} • ${fmt.format(Date(proj.updatedAtMs))}",
              color = Color(0xFFAAB4D0),
              fontSize = 10.5.sp,
            )
            Text(proj.buildStatus.labelBn, color = CYBER_GREEN, fontSize = 10.5.sp)
          }
          IconButton(onClick = { vm.openProjectFromHistory(proj.id) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.FolderOpen, contentDescription = "Open Project", tint = CYBER_CYAN)
          }
          IconButton(onClick = { onRequestRename(proj) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Rename Project", tint = CYBER_AMBER)
          }
          IconButton(onClick = { vm.backupProjectZip() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Archive, contentDescription = "Backup Project", tint = Color(0xFFA855F7))
          }
          IconButton(onClick = { vm.requestDeleteProject(proj) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete Project", tint = CYBER_RED)
          }
        }
      }
    }
  }
}

@Composable
private fun RenameProjectDialog(
  initialName: String,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  var name by remember { mutableStateOf(initialName) }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = CYBER_SURFACE,
    title = { Text("প্রজেক্ট Rename করুন", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("নতুন নাম") },
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
      Button(onClick = { onConfirm(name) }) { Text("সংরক্ষণ") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("বাতিল", color = CYBER_CYAN) }
    },
  )
}
