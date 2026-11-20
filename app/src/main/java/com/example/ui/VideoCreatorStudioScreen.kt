package com.example.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.service.LxrUsageLedger
import com.example.service.RiyaVoiceManager
import java.util.Locale
import com.example.video.DurationPreset
import com.example.video.MotionChoreography
import com.example.video.OpenMontageFrameworkBridge
import com.example.video.PipelineRunStatus
import com.example.video.ProductionToolTab
import com.example.video.TalkingCharacterPreset
import com.example.video.UserMediaKind
import com.example.video.VideoAspectRatio
import com.example.video.VideoProductionMode
import com.example.video.VideoResolutionPreset
import com.example.video.VideoStudioCatalog
import com.example.video.VideoStudioUiState
import com.example.video.VideoStudioViewModel
import com.example.video.VideoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoCreatorStudioScreen(
  back: () -> Unit,
  onNavigate: (String) -> Unit,
  vm: VideoStudioViewModel = viewModel(),
) {
  BackHandler(onBack = back)
  val state by vm.uiState.collectAsState()
  val usage by LxrUsageLedger.snapshot.collectAsState()
  val ctx = LocalContext.current

  val pickVisualLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 12)) { uris ->
      if (uris.isNotEmpty()) vm.importUserMediaUris(uris)
    }

  val pickAudioLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) vm.importUserMediaUris(listOf(uri), UserMediaKind.AUDIO)
    }

  val onPickGalleryVisuals: () -> Unit = {
    pickVisualLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
  }
  val onPickCustomAudio: () -> Unit = {
    pickAudioLauncher.launch(arrayOf("audio/*"))
  }

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
        Text(
          "Unlimited • ${state.bundledAssets.size} Assets • MediaCodec H.264",
          color = CYBER_CYAN,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
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

        // 0. MINI PRODUCTION WORLD (9 Stations) + HONEST USAGE LEDGER
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .testTag("mini_production_world_strip")
              .clip(RoundedCornerShape(16.dp))
              .background(Color(0xFF0C142B))
              .border(1.dp, CYBER_CYAN.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
              .padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            "LXR--AI Mini Production World (৯ স্টেশন দ্রুত অ্যাক্সেস)",
            color = CYBER_CYAN,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
          )
          val stations =
            listOf(
              "Story Room" to { vm.selectToolTab(ProductionToolTab.SCRIPT) },
              "Character Studio" to { vm.toggleCartoonSeriesStudio(true) },
              "Animation Studio" to { vm.toggleCartoonSeriesStudio(true) },
              "Voice Studio" to { vm.selectToolTab(ProductionToolTab.VOICE_OVER) },
              "Camera & Scene" to { vm.setShowSettingsSheet(true) },
              "Editing Room" to { vm.selectToolTab(ProductionToolTab.EDIT) },
              "Rendering Center" to { vm.startFullPipeline() },
              "AI Engine Center" to { vm.setShowOpenMontageDetails(true) },
              "Resource Center" to { vm.selectToolTab(ProductionToolTab.IMAGE_VIDEO) },
            )
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            stations.forEach { (stationTitle, action) ->
              OutlinedButton(
                onClick = action,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 48.dp),
              ) {
                Text(stationTitle, color = Color.White, fontSize = 11.sp)
              }
            }
          }

          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .testTag("video_usage_ledger_strip")
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF070C1A))
                .border(1.dp, Color(0xFF233052), RoundedCornerShape(10.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text(
              "প্রকৃত ব্যবহার (Honest Telemetry) • LLM: ${usage.llmTokensLabelBn()} • Credits: ${usage.videoCreditsLabelBn()}",
              color = CYBER_GREEN,
              fontSize = 10.sp,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              "Render: ${String.format(Locale.US, "%.1fs", usage.renderTimeSec)} • Download/LxR: ${usage.storageLabelBn()}",
              color = Color(0xFFCBD5E1),
              fontSize = 10.sp,
            )
          }
        }

        // 1. ONE LINE TO VIDEO PROMPT BOX + GALLERY UPLOAD
        OneLinePromptCard(
          state = state,
          vm = vm,
          onPickVisuals = onPickGalleryVisuals,
          onPickAudio = onPickCustomAudio,
        )

        // 2. FOUR MAIN PRODUCTION TOOL CARDS (SCRIPT / VOICE OVER / IMAGE & VIDEO / EDIT)
        FourMainToolCardsGrid(
          selectedTab = state.selectedToolTab,
          onSelect = { vm.selectToolTab(it) },
        )

        // Active Tool Studio Workspace
        when (state.selectedToolTab) {
          ProductionToolTab.SCRIPT -> ToolCardScriptPanel(project = state.activeProject, vm = vm)
          ProductionToolTab.VOICE_OVER -> ToolCardVoicePanel(state = state, vm = vm)
          ProductionToolTab.IMAGE_VIDEO -> ToolCardImageVisualPanel(
            state = state,
            vm = vm,
            onPickVisuals = onPickGalleryVisuals,
            onPickAudio = onPickCustomAudio,
          )
          ProductionToolTab.EDIT -> ToolCardEditPanel(state = state, vm = vm)
        }

        // Complete 3D Cartoon Animation, Movie, Character Library & Episodic Series Studio
        CartoonSeriesStudioPanel(state = state, vm = vm)

        // Video Settings Strip (20 Types, Motion, Talking Character, Duration, Ratio, Quality)
        VideoTypeAndSettingsCard(state = state, vm = vm)

        // 3. OPENMONTAGE INTEGRATION CARD
        OpenMontageIntegrationCard(state = state, vm = vm)

        // 4. 12-STAGE PROGRESS DASHBOARD & EXPORTS
        ProgressDashboardCard(
          progress = state.progress,
          project = state.activeProject,
          vm = vm,
          onNavigate = onNavigate,
        )

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

  state.pendingPaidProviderConsentId?.let { pendingId ->
    val provider = OpenMontageFrameworkBridge.findProvider(pendingId)
    if (provider != null) {
      AlertDialog(
        onDismissRequest = { vm.declinePaidProviderConsent() },
        containerColor = CYBER_SURFACE,
        title = {
          Text("Paid API অনুমতি প্রয়োজন (${provider.displayName})", color = CYBER_AMBER, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(provider.availabilityNoteBn, color = Color.White, fontSize = 12.sp)
            Text("প্রয়োজনীয় API Key: ${provider.requiredEnvVar}", color = CYBER_CYAN, fontSize = 11.5.sp)
            Text("আপনার স্পষ্ট অনুমতি ছাড়া LXR--AI কখনো কোনো Paid API ব্যবহার করে না।", color = Color(0xFFCBD5E1), fontSize = 11.sp)
          }
        },
        confirmButton = {
          Button(
            onClick = { vm.confirmPaidProviderConsent(provider.id) },
            colors = ButtonDefaults.buttonColors(containerColor = CYBER_AMBER, contentColor = Color.Black),
          ) {
            Text("অনুমতি দিন", fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { vm.declinePaidProviderConsent() }) {
            Text("ফ্রি মোডে থাকুন", color = CYBER_CYAN)
          }
        },
      )
    }
  }
}

@Composable
private fun OneLinePromptCard(
  state: VideoStudioUiState,
  vm: VideoStudioViewModel,
  onPickVisuals: () -> Unit,
  onPickAudio: () -> Unit,
) {
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text(
        "১. ONE LINE TO VIDEO — এক লাইনে আনলিমিটেড ভিডিও",
        color = CYBER_CYAN,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.weight(1f),
      )
      if (state.totalVideosCreatedCount > 0) {
        Text(
          "তৈরি: ${state.totalVideosCreatedCount}টি",
          color = CYBER_GREEN,
          fontSize = 10.5.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }

    OutlinedTextField(
      value = state.promptInput,
      onValueChange = { vm.updatePrompt(it) },
      label = { Text("আপনার ভিডিওর আইডিয়া লিখুন (নাটক, ৩৬০° ফ্যাশন, কথা বলা ফুলকপি-আপেল, নাচ, স্টোরি...)", fontSize = 11.sp) },
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

    // Quick preset inspiration chips
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf(
        "ফুলকপি ও আপেল কথা বলা" to VideoStudioCatalog.DEFAULT_EXAMPLE_PROMPT,
        "৩৬০° ফ্যাশন শো" to "একটি আধুনিক ৩৬০ ডিগ্রি ফ্যাশন শো ভিডিও তৈরি করো যেখানে মডেল স্টাইলিশ ড্রেসে ঘুরে ঘুরে কথা বলছে",
        "নাটক ও সিরিয়াল" to "গ্রামের পটভূমিতে আবেগঘন সামাজিক নাটক ও সিরিয়াল পর্ব তৈরি করো যেখানে চরিত্ররা স্পষ্ট বাংলায় সংলাপ বলছে",
        "নাচ ও মোশন গ্রাফিক্স" to "রঙিন মোশন গ্রাফিক্স এবং কার্টুন চরিত্রের নাচানাচি ও গান দিয়ে একটি প্রাণবন্ত শর্ট ভিডিও বানাও",
        "শিশু ও মহাকাশ" to "একটি সুন্দর শিক্ষামূলক অ্যানিমেশন ভিডিও তৈরি করো, যেখানে একটি শিশু মহাকাশ সম্পর্কে শিখছে।",
      ).forEach { (chipLabel, chipPrompt) ->
        FilterChip(
          selected = state.promptInput == chipPrompt,
          onClick = { vm.updatePrompt(chipPrompt) },
          label = { Text(chipLabel, fontSize = 10.5.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_PURPLE, selectedLabelColor = Color.White),
        )
      }
    }

    // Play-compliant Gallery Photo / Video / Screenshot / Audio Picker
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(
        onClick = onPickVisuals,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier.weight(1f).testTag("pick_gallery_media_btn"),
      ) {
        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = CYBER_AMBER, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("+ ছবি/ভিডিও/স্ক্রিনশট", color = CYBER_AMBER, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      OutlinedButton(
        onClick = onPickAudio,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier.weight(1f).testTag("pick_custom_audio_btn"),
      ) {
        Icon(Icons.Filled.AudioFile, contentDescription = null, tint = Color(0xFFE879F9), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("+ অডিও/ভয়েস", color = Color(0xFFE879F9), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }

    if (state.userMediaItems.isNotEmpty()) {
      Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.userMediaItems.forEach { item ->
          val thumbBmp by produceState<android.graphics.Bitmap?>(initialValue = null, item.cachedFilePath, item.kind) {
            value =
              if (item.kind == UserMediaKind.IMAGE || item.kind == UserMediaKind.SCREENSHOT) {
                withContext(Dispatchers.IO) {
                  runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(item.cachedFilePath, opts)
                  }.getOrNull()
                }
              } else {
                null
              }
          }
          Row(
            modifier = Modifier
              .clip(RoundedCornerShape(10.dp))
              .background(Color(0xFF0E1530))
              .border(1.dp, CYBER_CYAN.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
              .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            val bmp = thumbBmp
            if (bmp != null) {
              Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
              )
              Spacer(Modifier.width(6.dp))
            }
            Text(
              "${item.kind.labelBn}: ${item.displayName.take(14)}",
              color = Color.White,
              fontSize = 10.5.sp,
              maxLines = 1,
            )
            IconButton(onClick = { vm.removeUserMedia(item.id) }, modifier = Modifier.size(24.dp)) {
              Icon(Icons.Filled.Close, contentDescription = "বাদ দিন", tint = CYBER_RED, modifier = Modifier.size(14.dp))
            }
          }
        }
      }
    }

    // Production Modes row (A. Real AI Video, B. Motion Graphics, C. Real Footage, Hybrid Pro)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      VideoProductionMode.entries.forEach { mode ->
        FilterChip(
          selected = state.settings.productionMode == mode,
          onClick = { vm.selectProductionMode(mode) },
          modifier = Modifier.testTag("mode_chip_${mode.name}"),
          label = { Text(mode.labelBn, fontSize = 10.5.sp) },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = CYBER_BLUE,
            selectedLabelColor = Color.White,
          ),
        )
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      Button(
        onClick = { vm.startFullPipeline() },
        enabled = !isRunning,
        modifier = Modifier.weight(1f).testTag("generate_full_video_btn"),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_CYAN, contentColor = Color.Black),
      ) {
        Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
          if (isRunning) "রিয়েল MP4 তৈরি হচ্ছে..." else "রিয়েল ভিডিও তৈরি করুন",
          fontWeight = FontWeight.Bold,
          fontSize = 12.5.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      OutlinedButton(
        onClick = { vm.launch30SecondShowcaseTest() },
        enabled = !isRunning,
        modifier = Modifier.testTag("test_30s_showcase_btn"),
      ) {
        Text("৩০সে. টেস্ট", color = CYBER_AMBER, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
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
      Text("ভিডিও ধরন (${VideoType.entries.size}টি), মোশন ও কথা বলা চরিত্র", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
      TextButton(onClick = { vm.setShowSettingsSheet(!state.showSettingsSheet) }) {
        Text(if (state.showSettingsSheet) "সংক্ষেপ" else "বিস্তারিত", color = CYBER_CYAN, fontSize = 11.5.sp)
      }
    }

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

    Text("মোশন ও মুভমেন্ট (Walking, Dancing, 360° Fashion, Lip-Sync):", color = CYBER_CYAN, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      MotionChoreography.entries.forEach { m ->
        FilterChip(
          selected = state.settings.preferredMotionStyle == m,
          onClick = { vm.selectMotionChoreography(m) },
          label = { Text(m.labelBn, fontSize = 10.5.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_AMBER, selectedLabelColor = Color.Black),
        )
      }
    }

    Text("কথা বলা চরিত্র (ফুলকপি, পাতাকপি, কলা, আপেল, ফুল, আঙ্গুল, ৩ডি):", color = Color(0xFFE879F9), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      TalkingCharacterPreset.entries.forEach { cp ->
        FilterChip(
          selected = state.settings.preferredCharacterPreset == cp,
          onClick = { vm.selectTalkingCharacterPreset(cp) },
          label = { Text(cp.labelBn, fontSize = 10.5.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_PURPLE, selectedLabelColor = Color.White),
        )
      }
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
          value = state.customDurationSec.coerceIn(10, 600).toFloat(),
          onValueChange = { vm.updateCustomDurationSeconds(it.toInt()) },
          valueRange = 10f..600f,
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
        subtitle = "Story, Drama & Dialogue",
        selected = selectedTab == ProductionToolTab.SCRIPT,
        modifier = Modifier.weight(1f).testTag("tool_card_script"),
        onClick = { onSelect(ProductionToolTab.SCRIPT) },
      )
      ToolGridCard(
        tab = ProductionToolTab.VOICE_OVER,
        icon = Icons.Filled.RecordVoiceOver,
        accent = Color(0xFFE879F9),
        subtitle = "7 Google Voices + LipSync",
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
        subtitle = "60+ 3D Assets & Gallery",
        selected = selectedTab == ProductionToolTab.IMAGE_VIDEO,
        modifier = Modifier.weight(1f).testTag("tool_card_image_video"),
        onClick = { onSelect(ProductionToolTab.IMAGE_VIDEO) },
      )
      ToolGridCard(
        tab = ProductionToolTab.EDIT,
        icon = Icons.Filled.Tune,
        accent = CYBER_GREEN,
        subtitle = "Timeline, 360° & MP4",
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
  var pexelsInput by remember(state.settings.pexelsApiKey) { mutableStateOf(state.settings.pexelsApiKey) }
  var pixabayInput by remember(state.settings.pixabayApiKey) { mutableStateOf(state.settings.pixabayApiKey) }

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
      Text("৩. OPENMONTAGE PROVIDER REGISTRY", color = CYBER_AMBER, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
      TextButton(onClick = { vm.setShowOpenMontageDetails(!state.showOpenMontageDetails) }) {
        Text(if (state.showOpenMontageDetails) "লুকান" else "বিস্তারিত", color = CYBER_CYAN, fontSize = 11.sp)
      }
    }

    Text(cap.aiVideoApiDisclosureBn, color = CYBER_AMBER, fontSize = 11.sp)
    Text(cap.onDeviceEncoderNote, color = CYBER_GREEN, fontSize = 11.5.sp)
    Text(cap.ffmpegNote, color = Color(0xFFCBD5E1), fontSize = 11.sp)

    // Honest Provider Registry Chips (Free Local / Free No-Key vs Paid API requiring Consent)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      state.videoProviders.forEach { provider ->
        val selected = state.settings.selectedVideoProviderId == provider.id
        val isPaid = provider.billingTier.isPaidApi
        FilterChip(
          selected = selected,
          onClick = { vm.requestSelectVideoProvider(provider.id) },
          modifier = Modifier.testTag("provider_chip_${provider.id}"),
          label = { Text("${provider.displayName.substringBefore(" (")} [${provider.billingTier.badgeText}]", fontSize = 10.sp) },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = if (isPaid) CYBER_RED else CYBER_GREEN,
            selectedLabelColor = Color.Black,
          ),
        )
      }
    }

    AnimatedVisibility(visible = state.showOpenMontageDetails) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Repo: ${cap.frameworkRepoUrl}", color = CYBER_CYAN, fontSize = 11.sp)
        Text("Remotion/GSAP/Manim: ${cap.remotionNote}", color = CYBER_AMBER, fontSize = 11.sp)
        Text("Real Footage: ${cap.stockMediaNote}", color = Color(0xFFCBD5E1), fontSize = 11.sp)

        OutlinedTextField(
          value = pexelsInput,
          onValueChange = {
            pexelsInput = it
            vm.updateFreeStockApiKeys(pexelsInput, pixabayInput)
          },
          label = { Text("ঐচ্ছিক ফ্রি Pexels API Key (ফ্রি স্টক HD ভিডিও)", fontSize = 10.5.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = pixabayInput,
          onValueChange = {
            pixabayInput = it
            vm.updateFreeStockApiKeys(pexelsInput, pixabayInput)
          },
          label = { Text("ঐচ্ছিক ফ্রি Pixabay API Key (ফ্রি স্টক ভিডিও)", fontSize = 10.5.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

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
