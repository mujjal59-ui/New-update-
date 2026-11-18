package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.service.RiyaVoiceManager
import com.example.video.DurationPreset
import com.example.video.PipelineRunStatus
import com.example.video.ProductionToolTab
import com.example.video.VideoAspectRatio
import com.example.video.VideoResolutionPreset
import com.example.video.VideoStudioCatalog
import com.example.video.VideoStudioUiState
import com.example.video.VideoStudioViewModel
import com.example.video.VideoType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoCreatorStudioScreen(
  back: () -> Unit,
  onNavigate: (String) -> Unit,
  vm: VideoStudioViewModel = viewModel(),
) {
  BackHandler(onBack = back)
  val state by vm.uiState.collectAsState()
  val ctx = LocalContext.current

  Column(
    modifier = Modifier
      .fillMaxSize()
      .testTag("video_creator_screen")
      .background(FULL_BG_GRADIENT)
      .statusBarsPadding()
      .navigationBarsPadding(),
  ) {
    // Pinned Top Bar: keeps video_creator_screen_back_btn always clickable without scrolling!
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = back,
        modifier = Modifier.size(48.dp).testTag("video_creator_screen_back_btn"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "হোম স্ক্রিনে ফিরুন",
          tint = Color.White,
        )
      }
      PremiumIconBadge(Icons.Filled.Videocam, "AI Video Studio", listOf(CYBER_CYAN, CYBER_BLUE), sizeDp = 34)
      Spacer(Modifier.width(10.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text("LXR AI Video Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("OpenMontage + Free MediaCodec Engine", color = CYBER_CYAN, fontSize = 11.sp)
      }
      PremiumThreeDotButton(
        onNavigate = onNavigate,
        onRunVoiceTest = {
          RiyaVoiceManager.speakUrgent(
            ctx,
            "বস, আমি LXR--AI। সব সিস্টেম এবং ভয়েস স্পষ্টভাবে কাজ করছে।",
          )
        },
      )
    }

    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        state.lastActionBanner?.let { banner ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(CYBER_GREEN.copy(alpha = 0.16f))
              .border(1.dp, CYBER_GREEN, RoundedCornerShape(12.dp))
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(banner, color = CYBER_GREEN, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.clearBanner() }) { Text("ঠিক আছে", color = Color.White, fontSize = 11.sp) }
          }
        }

        // 1. ONE LINE TO VIDEO PROMPT BOX
        OneLinePromptCard(state = state, vm = vm)

        // Video Settings Strip (15+ Types, Duration, 9:16/16:9, 720p/1080p)
        VideoTypeAndSettingsCard(state = state, vm = vm)

        // 2. FOUR MAIN PRODUCTION TOOL CARDS (SCRIPT / VOICE OVER / IMAGE & VIDEO / EDIT)
        FourMainToolCardsGrid(
          selectedTab = state.selectedToolTab,
          onSelect = { vm.selectToolTab(it) },
        )

        // Active Tool Studio Workspace
        when (state.selectedToolTab) {
          ProductionToolTab.SCRIPT -> ToolCardScriptPanel(project = state.activeProject, vm = vm)
          ProductionToolTab.VOICE_OVER -> ToolCardVoicePanel(state = state, vm = vm)
          ProductionToolTab.IMAGE_VIDEO -> ToolCardImageVisualPanel(state = state, vm = vm)
          ProductionToolTab.EDIT -> ToolCardEditPanel(state = state, vm = vm)
        }

        // 3. OPENMONTAGE INTEGRATION CARD
        OpenMontageIntegrationCard(state = state, vm = vm)

        // 4. 12-STAGE PROGRESS DASHBOARD & EXPORTS
        ProgressDashboardCard(progress = state.progress, project = state.activeProject, vm = vm)

        Spacer(Modifier.height(18.dp))
      }
    }
  }

  val proj = state.activeProject
  if (state.showPreviewPlayerDialog && proj != null) {
    VideoPreviewPlayerDialog(
      project = proj,
      onDismiss = { vm.setShowPreviewDialog(false) },
    )
  }
}

@Composable
private fun OneLinePromptCard(state: VideoStudioUiState, vm: VideoStudioViewModel) {
  val isRunning = state.progress.status == PipelineRunStatus.RUNNING
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .background(Brush.linearGradient(listOf(Color(0xFF14183D), Color(0xFF0E1B3A))))
      .border(1.4.dp, CYBER_CYAN.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("১. ONE LINE TO VIDEO — এক লাইনে সম্পূর্ণ ভিডিও", color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
    }

    OutlinedTextField(
      value = state.promptInput,
      onValueChange = { vm.updatePrompt(it) },
      label = { Text("আপনার ভিডিওর আইডিয়া লিখুন (বাংলা বা ইংরেজি)", fontSize = 11.5.sp) },
      minLines = 2,
      maxLines = 4,
      modifier = Modifier.fillMaxWidth().testTag("video_prompt_input"),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CYBER_CYAN,
        unfocusedBorderColor = Color(0xFF334155),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color(0xFFE2E8F0),
      ),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedButton(
        onClick = { vm.updatePrompt(VideoStudioCatalog.DEFAULT_EXAMPLE_PROMPT) },
      ) {
        Text("উদাহরণ: শিশু ও মহাকাশ", color = CYBER_AMBER, fontSize = 11.sp)
      }
      Button(
        onClick = { vm.startFullPipeline() },
        enabled = !isRunning,
        modifier = Modifier.weight(1f).testTag("generate_full_video_btn"),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_CYAN, contentColor = Color.Black),
      ) {
        Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (isRunning) "তৈরি হচ্ছে..." else "সম্পূর্ণ ভিডিও তৈরি করুন", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoTypeAndSettingsCard(state: VideoStudioUiState, vm: VideoStudioViewModel) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(CYBER_CARD)
      .border(1.dp, Color(0xFF283459), RoundedCornerShape(16.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Filled.Settings, contentDescription = null, tint = CYBER_AMBER, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(6.dp))
      Text("ভিডিও ধরন (${VideoType.entries.size}টি ফরম্যাট), সময়কাল ও কোয়ালিটি", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
      TextButton(onClick = { vm.setShowSettingsSheet(!state.showSettingsSheet) }) {
        Text(if (state.showSettingsSheet) "সংক্ষেপ" else "বিস্তারিত", color = CYBER_CYAN, fontSize = 11.5.sp)
      }
    }

    // Horizontal chip selector for all 15+ Video Types
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      VideoType.entries.forEach { type ->
        FilterChip(
          selected = state.selectedVideoType == type,
          onClick = { vm.selectVideoType(type) },
          label = { Text(type.labelBn, fontSize = 11.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_BLUE, selectedLabelColor = Color.White),
        )
      }
    }

    if (state.selectedVideoType == VideoType.CUSTOM) {
      OutlinedTextField(
        value = state.customVideoTypeInput,
        onValueChange = { vm.updateCustomVideoType(it) },
        label = { Text("কাস্টম ভিডিওর ধরন লিখুন", fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth(),
      )
    }

    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      DurationPreset.entries.forEach { dpPreset ->
        FilterChip(
          selected = state.selectedDurationPreset == dpPreset,
          onClick = { vm.selectDurationPreset(dpPreset) },
          label = { Text(dpPreset.labelBn, fontSize = 11.sp) },
        )
      }
    }

    AnimatedVisibility(visible = state.selectedDurationPreset == DurationPreset.CUSTOM || state.showSettingsSheet) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("কাস্টম সময়কাল: ${state.customDurationSec} সেকেন্ড", color = CYBER_CYAN, fontSize = 11.5.sp)
        Slider(
          value = state.customDurationSec.toFloat(),
          onValueChange = { vm.updateCustomDurationSeconds(it.toInt()) },
          valueRange = 10f..360f,
          colors = SliderDefaults.colors(thumbColor = CYBER_CYAN, activeTrackColor = CYBER_CYAN),
        )
      }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      VideoAspectRatio.entries.forEach { ar ->
        FilterChip(
          selected = state.settings.aspectRatio == ar,
          onClick = { vm.updateSettings { it.copy(aspectRatio = ar) } },
          label = { Text(ar.labelBn, fontSize = 11.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_PURPLE, selectedLabelColor = Color.White),
        )
      }
      VideoResolutionPreset.entries.forEach { res ->
        FilterChip(
          selected = state.settings.resolution == res,
          onClick = { vm.updateSettings { it.copy(resolution = res) } },
          label = { Text(res.label, fontSize = 11.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_GREEN, selectedLabelColor = Color.Black),
        )
      }
    }
  }
}

@Composable
private fun FourMainToolCardsGrid(
  selectedTab: ProductionToolTab,
  onSelect: (ProductionToolTab) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
    Text("২. FOUR MAIN PRODUCTION TOOLS", color = Color(0xFFCBD5E1), fontWeight = FontWeight.Bold, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      ToolGridCard(
        tab = ProductionToolTab.SCRIPT,
        icon = Icons.Filled.Description,
        accent = CYBER_CYAN,
        subtitle = "Story, Dialogue & Script",
        selected = selectedTab == ProductionToolTab.SCRIPT,
        modifier = Modifier.weight(1f).testTag("tool_card_script"),
        onClick = { onSelect(ProductionToolTab.SCRIPT) },
      )
      ToolGridCard(
        tab = ProductionToolTab.VOICE_OVER,
        icon = Icons.Filled.RecordVoiceOver,
        accent = Color(0xFFE879F9),
        subtitle = "7 Google Voices / Piper",
        selected = selectedTab == ProductionToolTab.VOICE_OVER,
        modifier = Modifier.weight(1f).testTag("tool_card_voice_over"),
        onClick = { onSelect(ProductionToolTab.VOICE_OVER) },
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      ToolGridCard(
        tab = ProductionToolTab.IMAGE_VIDEO,
        icon = Icons.Filled.PhotoLibrary,
        accent = Color(0xFF60A5FA),
        subtitle = "2D/3D Scenes & Stock",
        selected = selectedTab == ProductionToolTab.IMAGE_VIDEO,
        modifier = Modifier.weight(1f).testTag("tool_card_image_video"),
        onClick = { onSelect(ProductionToolTab.IMAGE_VIDEO) },
      )
      ToolGridCard(
        tab = ProductionToolTab.EDIT,
        icon = Icons.Filled.Tune,
        accent = CYBER_GREEN,
        subtitle = "Timeline, BGM & MP4",
        selected = selectedTab == ProductionToolTab.EDIT,
        modifier = Modifier.weight(1f).testTag("tool_card_edit"),
        onClick = { onSelect(ProductionToolTab.EDIT) },
      )
    }
  }
}

@Composable
private fun ToolGridCard(
  tab: ProductionToolTab,
  icon: ImageVector,
  accent: Color,
  subtitle: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(14.dp))
      .background(if (selected) accent.copy(alpha = 0.16f) else CYBER_CARD)
      .border(if (selected) 1.6.dp else 1.dp, if (selected) accent else Color(0xFF283459), RoundedCornerShape(14.dp))
      .clickable(onClick = onClick)
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = tab.badgeEn, tint = accent, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text(tab.badgeEn, color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
    Text(tab.titleBn, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    Text(subtitle, color = Color(0xFF94A3B8), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun OpenMontageIntegrationCard(state: VideoStudioUiState, vm: VideoStudioViewModel) {
  val cap = state.capabilityReport
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(CYBER_CARD)
      .border(1.dp, CYBER_AMBER.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Filled.MovieFilter, contentDescription = null, tint = CYBER_AMBER, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("৩. OPENMONTAGE FRAMEWORK (১০০% ফ্রি)", color = CYBER_AMBER, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
      TextButton(onClick = { vm.setShowOpenMontageDetails(!state.showOpenMontageDetails) }) {
        Text(if (state.showOpenMontageDetails) "লুকান" else "বিস্তারিত", color = CYBER_CYAN, fontSize = 11.sp)
      }
    }

    Text(cap.onDeviceEncoderNote, color = CYBER_GREEN, fontSize = 11.5.sp)
    Text(cap.piperTtsNote, color = Color(0xFFE2E8F0), fontSize = 11.sp)
    Text(cap.ffmpegNote, color = Color(0xFFCBD5E1), fontSize = 11.sp)

    AnimatedVisibility(visible = state.showOpenMontageDetails) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Repo: ${cap.frameworkRepoUrl}", color = CYBER_CYAN, fontSize = 11.sp)
        Text("Remotion নোট: ${cap.remotionNote}", color = CYBER_AMBER, fontSize = 11.sp)
        Text("Stock Media নোট: ${cap.stockMediaNote}", color = Color(0xFFCBD5E1), fontSize = 11.sp)
        state.activeProject?.pipelineYaml?.takeIf { it.isNotBlank() }?.let { yaml ->
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(Color(0xFF060B19))
              .padding(8.dp),
          ) {
            Text(yaml.take(650), color = Color(0xFF38BDF8), fontSize = 10.sp)
          }
        }
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(onClick = { vm.refreshCapabilities() }, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.Code, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("ডিভাইস যাচাই", color = Color.White, fontSize = 11.5.sp)
      }
      Button(
        onClick = { vm.runOpenMontageInTermux() },
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_AMBER, contentColor = Color.Black),
      ) {
        Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Termux-এ চালান", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
      }
    }
  }
}
