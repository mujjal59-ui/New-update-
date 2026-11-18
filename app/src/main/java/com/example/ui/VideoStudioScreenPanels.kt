package com.example.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.video.AndroidVideoRendererAndExporter
import com.example.video.PipelineRunStatus
import com.example.video.ProductionSettings
import com.example.video.StudioMediaLibrary
import com.example.video.StudioProgressState
import com.example.video.TransitionEffect
import com.example.video.VideoAspectRatio
import com.example.video.VideoProject
import com.example.video.VideoResolutionPreset
import com.example.video.VideoStudioCatalog
import com.example.video.VideoStudioUiState
import com.example.video.VideoStudioViewModel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun ToolCardScriptPanel(
  project: VideoProject?,
  vm: VideoStudioViewModel,
) {
  val ctx = LocalContext.current
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(CYBER_CARD)
        .border(1.dp, CYBER_CYAN.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("A. SCRIPT STUDIO — গল্প, সংলাপ ও দৃশ্য সম্পাদনা", color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
      if (project != null && project.artifacts.scriptTxtPath.isNotBlank()) {
        IconButton(onClick = {
          val intent = AndroidVideoRendererAndExporter.createShareIntent(ctx, project.artifacts.scriptTxtPath, "text/plain", "Script TXT")
          if (intent != null) ctx.startActivity(Intent.createChooser(intent, "Script TXT দেখুন/ডাউনলোড"))
          else Toast.makeText(ctx, "সেভ করা আছে: ${project.artifacts.scriptTxtPath}", Toast.LENGTH_SHORT).show()
        }) {
          Icon(Icons.Filled.Download, contentDescription = "Download Script", tint = CYBER_GREEN)
        }
      }
    }
    if (project == null) {
      Text(
        "উপরে প্রম্পট লিখে 'সম্পূর্ণ ভিডিও তৈরি করুন' চাপুন — স্বয়ংক্রিয়ভাবে গল্প, চরিত্র, দৃশ্য-ভিত্তিক স্ক্রিপ্ট ও সংলাপ তৈরি হবে।",
        color = Color(0xFF94A3B8),
        fontSize = 12.sp,
      )
      return
    }
    Text(project.topicAnalysisSummary, color = Color(0xFFE2E8F0), fontSize = 12.sp)
    if (project.characters.isNotEmpty()) {
      Text("চরিত্র বিবরণ (Characters):", color = CYBER_AMBER, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
      project.characters.forEach { ch ->
        Text("• ${ch.name} (${ch.roleBn}) — কণ্ঠ: ${ch.recommendedVoiceId} | ${ch.visualDescription}", color = Color(0xFFCBD5E1), fontSize = 11.sp)
      }
    }
    project.scenes.forEach { sc ->
      var nar by remember(sc.index, sc.narrationText) { mutableStateOf(sc.narrationText) }
      var dlg by remember(sc.index, sc.dialogueLine) { mutableStateOf(sc.dialogueLine) }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Color(0xFF0E1530))
          .border(1.dp, Color(0xFF283459), RoundedCornerShape(12.dp))
          .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text("${sc.title} (${String.format(Locale.US, "%.1f", sc.effectiveDurationSec)}s)", color = CYBER_CYAN, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        OutlinedTextField(
          value = nar,
          onValueChange = {
            nar = it
            vm.updateSceneScript(sc.index, nar, dlg)
          },
          label = { Text("Narration (স্ক্রিপ্ট)", fontSize = 11.sp) },
          modifier = Modifier.fillMaxWidth(),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CYBER_CYAN,
            unfocusedBorderColor = Color(0xFF334155),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color(0xFFE2E8F0),
          ),
        )
        OutlinedTextField(
          value = dlg,
          onValueChange = {
            dlg = it
            vm.updateSceneScript(sc.index, nar, dlg)
          },
          label = { Text("Dialogue (${sc.characterName})", fontSize = 11.sp) },
          modifier = Modifier.fillMaxWidth(),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CYBER_PURPLE,
            unfocusedBorderColor = Color(0xFF334155),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color(0xFFE2E8F0),
          ),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolCardVoicePanel(
  state: VideoStudioUiState,
  vm: VideoStudioViewModel,
) {
  val settings = state.settings
  val project = state.activeProject
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(CYBER_CARD)
      .border(1.dp, CYBER_PURPLE.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("B. VOICE OVER — ৭টি Google Voice Model ও ফ্রি অফলাইন ভয়েস", color = Color(0xFFE879F9), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Text(
      "১০০% ফ্রি নীতি: ফ্রি Google Voice মডেল বা অফলাইন Piper / LXR ভয়েস ইঞ্জিন ব্যবহৃত হয় — কোনো Paid API ব্যবহার হয় না।",
      color = CYBER_GREEN,
      fontSize = 11.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("AI নিজে বিষয় ও চরিত্র অনুযায়ী Voice নির্বাচন করুক", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
      Switch(
        checked = settings.autoSelectVoiceByAi,
        onCheckedChange = { v -> vm.updateSettings { it.copy(autoSelectVoiceByAi = v) } },
        colors = SwitchDefaults.colors(checkedTrackColor = CYBER_PURPLE),
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("প্রতি চরিত্রে ভিন্ন ভিন্ন ভয়েস (Scene-wise Character Voice)", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
      Switch(
        checked = settings.useCharacterVoicesPerScene,
        onCheckedChange = { v -> vm.updateSettings { it.copy(useCharacterVoicesPerScene = v) } },
        colors = SwitchDefaults.colors(checkedTrackColor = CYBER_CYAN),
      )
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf("ALL" to "সব ভয়েস (৭টি)", "FEMALE" to "নারী (Female)", "MALE" to "পুরুষ (Male)").forEach { (k, label) ->
        FilterChip(
          selected = settings.selectedVoiceGenderFilter == k,
          onClick = { vm.updateSettings { it.copy(selectedVoiceGenderFilter = k) } },
          label = { Text(label, fontSize = 11.sp) },
        )
      }
    }

    val filteredVoices = VideoStudioCatalog.GOOGLE_VOICES.filter {
      when (settings.selectedVoiceGenderFilter) {
        "FEMALE" -> it.gender.name == "FEMALE"
        "MALE" -> it.gender.name == "MALE"
        else -> true
      }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      filteredVoices.forEach { voice ->
        val selected = settings.selectedVoiceId == voice.id
        FilterChip(
          selected = selected,
          onClick = { vm.updateSettings { it.copy(selectedVoiceId = voice.id, autoSelectVoiceByAi = false) } },
          label = { Text("${voice.displayName} (${voice.gender.labelBn})", fontSize = 11.sp) },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = CYBER_PURPLE,
            selectedLabelColor = Color.White,
          ),
        )
      }
    }

    val chosenInfo = VideoStudioCatalog.GOOGLE_VOICES.firstOrNull { it.id == settings.selectedVoiceId }
    if (chosenInfo != null) {
      Text("নির্বাচিত: ${chosenInfo.displayName} • ${chosenInfo.characterFitBn} (${chosenInfo.emotionalProfile})", color = Color(0xFFCBD5E1), fontSize = 11.5.sp)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = { vm.previewGoogleVoice(settings.selectedVoiceId, settings.preferredEmotionalTone) },
        colors = ButtonDefaults.buttonColors(containerColor = if (state.isVoicePreviewPlaying) CYBER_RED else CYBER_PURPLE),
      ) {
        Icon(if (state.isVoicePreviewPlaying) Icons.Filled.Stop else Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (state.isVoicePreviewPlaying) "থামান" else "Voice Preview শুনুন", fontSize = 12.sp)
      }
    }

    if (project != null && project.scenes.isNotEmpty()) {
      Text("দৃশ্য অনুযায়ী ভয়েস কন্ট্রোল:", color = CYBER_CYAN, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      project.scenes.forEach { sc ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
          Text("Scene ${sc.index}: ${sc.voiceId} (${sc.emotionalTone})", color = Color(0xFFE2E8F0), fontSize = 11.5.sp, modifier = Modifier.weight(1f))
          TextButton(onClick = {
            val ids = VideoStudioCatalog.voiceIds()
            val nextVoice = ids[(ids.indexOf(sc.voiceId) + 1) % ids.size]
            vm.updateSceneVoice(sc.index, nextVoice, sc.emotionalTone)
          }) {
            Text("বদলান", color = CYBER_CYAN, fontSize = 11.sp)
          }
        }
      }
    }
  }
}

@Composable
fun ToolCardImageVisualPanel(
  state: VideoStudioUiState,
  vm: VideoStudioViewModel,
  onPickVisuals: () -> Unit = {},
  onPickAudio: () -> Unit = {},
) {
  val ctx = LocalContext.current
  val project = state.activeProject
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(CYBER_CARD)
      .border(1.dp, CYBER_BLUE.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      "C. IMAGE & VIDEO — ${state.bundledAssets.size}টি ৩ডি/গ্রাম/কার্টুন ইমেজ, গ্যালারি ও Lip-Sync",
      color = Color(0xFF60A5FA),
      fontWeight = FontWeight.Bold,
      fontSize = 13.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("Image-to-Video মোশন, হাঁটা, নাচ ও ৩৬০° এফেক্ট", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
      Switch(
        checked = state.settings.imageToVideoMotionEnabled,
        onCheckedChange = { v -> vm.updateSettings { it.copy(imageToVideoMotionEnabled = v) } },
        colors = SwitchDefaults.colors(checkedTrackColor = CYBER_BLUE),
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("মুখ নাড়িয়ে কথা বলা ও লিপ-সিঙ্ক (Audio Lip-Sync)", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
      Switch(
        checked = state.settings.lipSyncEnabled,
        onCheckedChange = { v -> vm.updateSettings { it.copy(lipSyncEnabled = v) } },
        colors = SwitchDefaults.colors(checkedTrackColor = CYBER_CYAN),
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(onClick = onPickVisuals, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = CYBER_AMBER, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("গ্যালারি ছবি/ভিডিও", color = CYBER_AMBER, fontSize = 11.sp)
      }
      OutlinedButton(onClick = onPickAudio, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.AudioFile, contentDescription = null, tint = Color(0xFFE879F9), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("কাস্টম অডিও", color = Color(0xFFE879F9), fontSize = 11.sp)
      }
    }

    if (state.bundledAssets.isNotEmpty()) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
          "বিল্ট-ইন ৩ডি/গ্রাম প্যাক (${state.bundledAssets.size}টি) — ট্যাপ করে দৃশ্যে যুক্ত করুন (${state.selectedBundledAssetFiles.size} নির্বাচিত):",
          color = CYBER_CYAN,
          fontSize = 11.sp,
          modifier = Modifier.weight(1f),
        )
        if (state.selectedBundledAssetFiles.isNotEmpty()) {
          TextButton(onClick = { vm.clearSelectedBundledAssets() }) {
            Text("Clear", color = CYBER_RED, fontSize = 10.5.sp)
          }
        }
      }
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        state.bundledAssets.forEach { asset ->
          val selected = asset.fileName in state.selectedBundledAssetFiles
          val thumb = remember(asset.fileName) {
            StudioMediaLibrary.loadStudioBitmap(ctx, assetFileName = asset.fileName, maxDim = 120, stripLightBackgroundIfSprite = false)
          }
          Column(
            modifier = Modifier
              .width(74.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(if (selected) CYBER_CYAN.copy(alpha = 0.2f) else Color(0xFF0E1530))
              .border(if (selected) 1.5.dp else 1.dp, if (selected) CYBER_CYAN else Color(0xFF283459), RoundedCornerShape(10.dp))
              .clickable { vm.toggleBundledAssetSelection(asset.fileName) }
              .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            if (thumb != null) {
              Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = asset.titleBn,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)),
              )
            }
            Text(
              asset.titleBn,
              color = if (selected) CYBER_CYAN else Color(0xFFCBD5E1),
              fontSize = 9.sp,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }

    if (project == null || project.previewFramePaths.isEmpty()) {
      Text(
        "উপরে প্রম্পট বা ছবি দিয়ে 'সম্পূর্ণ রিয়েল ভিডিও তৈরি করুন' চাপুন — প্রতিটি দৃশ্যের লাইভ চরিত্র ও ইফেক্ট প্রিভিউ এখানে দেখা যাবে।",
        color = Color(0xFF94A3B8),
        fontSize = 12.sp,
      )
      return
    }

    val safeIdx = state.selectedPreviewSceneIndex.coerceIn(0, project.previewFramePaths.lastIndex)
    val frameFile = File(project.previewFramePaths[safeIdx])
    val bmp = remember(frameFile.absolutePath, frameFile.lastModified()) {
      if (frameFile.exists()) BitmapFactory.decodeFile(frameFile.absolutePath) else null
    }
    if (bmp != null) {
      Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = "Scene ${safeIdx + 1} Visual Preview",
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(if (project.settings.aspectRatio == VideoAspectRatio.PORTRAIT_9_16) 9f / 16f else 16f / 9f)
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, CYBER_CYAN.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
      )
    }

    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      project.scenes.forEachIndexed { idx, sc ->
        FilterChip(
          selected = idx == safeIdx,
          onClick = { vm.selectPreviewScene(idx) },
          label = { Text("Scene ${sc.index}", fontSize = 11.sp) },
        )
      }
    }

    val activeSc = project.scenes.getOrNull(safeIdx)
    if (activeSc != null) {
      Text(
        "মোশন: ${activeSc.motionStyle.labelBn} • চরিত্র: ${activeSc.characterPreset.labelBn}",
        color = CYBER_CYAN,
        fontSize = 11.5.sp,
      )
      Text(
        "মিডিয়া/অ্যাসেট: ${activeSc.matchedAssetFile.ifBlank { activeSc.userMediaPath.substringAfterLast('/') }}",
        color = Color(0xFFCBD5E1),
        fontSize = 11.sp,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolCardEditPanel(
  state: VideoStudioUiState,
  vm: VideoStudioViewModel,
) {
  val project = state.activeProject
  val settings = state.settings
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(CYBER_CARD)
      .border(1.dp, CYBER_GREEN.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("D. EDIT & RENDER — টাইমলাইন, কাটিং, অডিও সিঙ্ক ও MP4 রেন্ডারিং", color = CYBER_GREEN, fontWeight = FontWeight.Bold, fontSize = 13.sp)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      ToggleChipRow("Subtitles (.SRT)", settings.subtitlesEnabled) { v -> vm.updateSettings { it.copy(subtitlesEnabled = v) } }
      ToggleChipRow("BGM Music", settings.backgroundMusicEnabled) { v -> vm.updateSettings { it.copy(backgroundMusicEnabled = v) } }
      ToggleChipRow("Sound FX", settings.soundEffectsEnabled) { v -> vm.updateSettings { it.copy(soundEffectsEnabled = v) } }
    }

    Text("Transition Effects:", color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      TransitionEffect.entries.forEach { tr ->
        FilterChip(
          selected = settings.transitionEffect == tr,
          onClick = { vm.updateSettings { it.copy(transitionEffect = tr) } },
          label = { Text(tr.labelBn, fontSize = 11.sp) },
        )
      }
    }

    if (project != null && project.scenes.isNotEmpty()) {
      Text("ভিডিও টাইমলাইন ও Scene Cutting/Combining (${project.scenes.size} clips):", color = CYBER_CYAN, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      project.scenes.forEachIndexed { pos, sc ->
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0E1530))
            .padding(8.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              "Clip ${sc.index}: ${sc.overlayHeadline} (${String.format(Locale.US, "%.1f", sc.effectiveDurationSec)}s)",
              color = Color.White,
              fontSize = 11.5.sp,
              modifier = Modifier.weight(1f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { vm.moveSceneOrder(pos, -1) }, modifier = Modifier.size(32.dp)) {
              Icon(Icons.Filled.ArrowUpward, contentDescription = "Move Up", tint = CYBER_CYAN, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { vm.moveSceneOrder(pos, +1) }, modifier = Modifier.size(32.dp)) {
              Icon(Icons.Filled.ArrowDownward, contentDescription = "Move Down", tint = CYBER_CYAN, modifier = Modifier.size(16.dp))
            }
          }
          Slider(
            value = sc.trimEndSec.coerceIn(2f, sc.durationSec),
            onValueChange = { endV -> vm.updateSceneTrimAndTransition(sc.index, sc.trimStartSec, endV, settings.transitionEffect) },
            valueRange = 2f..sc.durationSec.coerceAtLeast(3f),
            colors = SliderDefaults.colors(thumbColor = CYBER_GREEN, activeTrackColor = CYBER_GREEN),
          )
        }
      }

      Button(
        onClick = { vm.reRenderWithEdits() },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_GREEN, contentColor = Color.Black),
      ) {
        Icon(Icons.Filled.SmartDisplay, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("এডিটসহ ফাইনাল MP4 রেন্ডার করুন", fontWeight = FontWeight.Bold)
      }
    }
  }
}

@Composable
private fun ToggleChipRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
  FilterChip(
    selected = checked,
    onClick = { onToggle(!checked) },
    label = { Text(label, fontSize = 11.sp) },
    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_BLUE, selectedLabelColor = Color.White),
  )
}

@Composable
fun ProgressDashboardCard(
  progress: StudioProgressState,
  project: VideoProject?,
  vm: VideoStudioViewModel,
) {
  val ctx = LocalContext.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("video_progress_dashboard")
      .clip(RoundedCornerShape(18.dp))
      .background(CYBER_CARD)
      .border(1.2.dp, CYBER_CYAN.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("প্রোডাকশন প্রগ্রেস ড্যাশবোর্ড", color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
      Text(progress.status.labelBn, color = if (progress.status == PipelineRunStatus.COMPLETED) CYBER_GREEN else CYBER_AMBER, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
    Text(progress.statusMessageBn, color = Color(0xFFE2E8F0), fontSize = 12.sp)

    ProgressBarRow("Script Progress", progress.scriptProgress, CYBER_CYAN)
    ProgressBarRow("Voice Progress", progress.voiceProgress, CYBER_PURPLE)
    ProgressBarRow("Image Progress", progress.imageProgress, CYBER_BLUE)
    ProgressBarRow("Scene Progress", progress.sceneProgress, Color(0xFF38BDF8))
    ProgressBarRow("Editing Progress", progress.editingProgress, CYBER_AMBER)
    ProgressBarRow("Rendering Progress", progress.renderingProgress, CYBER_GREEN)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      if (progress.status == PipelineRunStatus.RUNNING) {
        OutlinedButton(onClick = { vm.pausePipeline() }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Pause, contentDescription = "Pause", modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Pause", fontSize = 11.5.sp)
        }
      }
      if (progress.status == PipelineRunStatus.PAUSED) {
        Button(onClick = { vm.resumePipeline() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CYBER_GREEN, contentColor = Color.Black)) {
          Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Resume", fontSize = 11.5.sp)
        }
      }
      if (progress.status == PipelineRunStatus.RUNNING || progress.status == PipelineRunStatus.PAUSED) {
        OutlinedButton(onClick = { vm.cancelPipeline() }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Cancel, contentDescription = "Cancel", tint = CYBER_RED, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Cancel", color = CYBER_RED, fontSize = 11.5.sp)
        }
      }
      if (progress.status == PipelineRunStatus.FAILED || progress.status == PipelineRunStatus.CANCELLED) {
        Button(onClick = { vm.retryPipeline() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CYBER_AMBER, contentColor = Color.Black)) {
          Icon(Icons.Filled.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Retry", fontSize = 11.5.sp)
        }
      }
    }

    if (project != null && project.artifacts.mp4Path.isNotBlank()) {
      val art = project.artifacts
      Spacer(Modifier.height(4.dp))
      Text("তৈরিকৃত ফাইল (${art.publicDownloadFolderLabel}):", color = CYBER_GREEN, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
      Text("Voice Engine: ${art.usedFreeVoiceEngineLabel}", color = Color(0xFFCBD5E1), fontSize = 11.sp)
      Text("Render Engine: ${art.usedFreeRenderEngineLabel}", color = Color(0xFFCBD5E1), fontSize = 11.sp)

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
          onClick = { vm.setShowPreviewDialog(true) },
          modifier = Modifier.weight(1f),
          colors = ButtonDefaults.buttonColors(containerColor = CYBER_BLUE),
        ) {
          Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Video প্রিভিউ", fontSize = 12.sp)
        }
        Button(
          onClick = {
            val intent = AndroidVideoRendererAndExporter.createShareIntent(ctx, art.mp4Path, "video/mp4", project.title)
            if (intent != null) ctx.startActivity(Intent.createChooser(intent, "MP4 শেয়ার / ওপেন করুন"))
          },
          modifier = Modifier.weight(1f),
          colors = ButtonDefaults.buttonColors(containerColor = CYBER_GREEN, contentColor = Color.Black),
        ) {
          Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("MP4 (${art.mp4SizeBytes / 1024} KB)", fontSize = 11.5.sp)
        }
      }

      Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ArtifactShareChip("Script .TXT", art.scriptTxtPath, "text/plain")
        ArtifactShareChip("Voice .WAV", art.voiceWavPath, "audio/wav")
        ArtifactShareChip("Subtitle .SRT", art.subtitleSrtPath, "application/x-subrip")
        ArtifactShareChip("OpenMontage .ZIP", art.projectBundleZipPath, "application/zip")
      }
    }
  }
}

@Composable
private fun ProgressBarRow(label: String, progress: Float, barColor: Color) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, color = Color(0xFFCBD5E1), fontSize = 11.sp)
      Text("${(progress * 100).toInt()}%", color = barColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
    LinearProgressIndicator(
      progress = { progress.coerceIn(0f, 1f) },
      modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
      color = barColor,
      trackColor = Color(0xFF283459),
    )
  }
}

@Composable
private fun ArtifactShareChip(label: String, path: String, mime: String) {
  val ctx = LocalContext.current
  OutlinedButton(
    onClick = {
      val intent = AndroidVideoRendererAndExporter.createShareIntent(ctx, path, mime, label)
      if (intent != null) ctx.startActivity(Intent.createChooser(intent, label))
      else Toast.makeText(ctx, "সেভ করা ফাইল: $path", Toast.LENGTH_SHORT).show()
    },
  ) {
    Icon(Icons.Filled.Download, contentDescription = label, tint = CYBER_CYAN, modifier = Modifier.size(14.dp))
    Spacer(Modifier.width(4.dp))
    Text(label, color = Color.White, fontSize = 11.sp)
  }
}

@Composable
fun VideoPreviewPlayerDialog(
  project: VideoProject,
  onDismiss: () -> Unit,
) {
  val ctx = LocalContext.current
  val mp4File = File(project.artifacts.mp4Path)
  val canTryVideoView = mp4File.exists() && project.artifacts.usedFreeRenderEngineLabel.contains("MediaCodec", ignoreCase = true)
  var useFallbackSlideshow by remember(mp4File.absolutePath) { mutableStateOf(!canTryVideoView) }
  var slideIndex by remember { mutableIntStateOf(0) }

  if (useFallbackSlideshow && project.previewFramePaths.isNotEmpty()) {
    LaunchedEffect(project.previewFramePaths.size) {
      while (true) {
        delay(1800L)
        slideIndex = (slideIndex + 1) % project.previewFramePaths.size.coerceAtLeast(1)
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      AndroidVideoRendererAndExporter.stopPreviewAudio()
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = CYBER_SURFACE,
    title = { Text(project.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (!useFallbackSlideshow && mp4File.exists()) {
          val playUri = remember(mp4File.absolutePath) {
            runCatching {
              FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", mp4File)
            }.getOrElse { Uri.fromFile(mp4File) }
          }
          AndroidView(
            factory = { c ->
              VideoView(c).apply {
                setVideoURI(playUri)
                setMediaController(MediaController(c).apply { setAnchorView(this@apply) })
                setOnPreparedListener { mp -> mp.isLooping = true; start() }
                setOnErrorListener { _, _, _ ->
                  useFallbackSlideshow = true
                  true
                }
              }
            },
            modifier = Modifier
              .fillMaxWidth()
              .aspectRatio(if (project.settings.aspectRatio == VideoAspectRatio.PORTRAIT_9_16) 9f / 16f else 16f / 9f)
              .clip(RoundedCornerShape(10.dp)),
          )
        } else {
          val framePath = project.previewFramePaths.getOrNull(slideIndex % project.previewFramePaths.size.coerceAtLeast(1))
          val frameFile = framePath?.let { File(it) }
          val bmp = remember(frameFile?.absolutePath) { frameFile?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) } }
          if (bmp != null) {
            Image(
              bitmap = bmp.asImageBitmap(),
              contentDescription = "Animated Scene Preview ${slideIndex + 1}",
              modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (project.settings.aspectRatio == VideoAspectRatio.PORTRAIT_9_16) 9f / 16f else 16f / 9f)
                .clip(RoundedCornerShape(10.dp)),
              contentScale = ContentScale.Crop,
            )
          }
          Text("MP4 ফাইল প্রস্তুত (${mp4File.length() / 1024} KB): ${project.artifacts.publicDownloadFolderLabel}", color = CYBER_GREEN, fontSize = 11.5.sp)
        }
        Text("সাবটাইটেল প্রিভিউ:", color = CYBER_CYAN, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        project.subtitles.take(4).forEach { cue ->
          Text("${cue.index}. ${cue.text}", color = Color(0xFFE2E8F0), fontSize = 11.sp)
        }
      }
    },
    confirmButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mp4File.exists()) {
          TextButton(onClick = {
            val intent = AndroidVideoRendererAndExporter.createShareIntent(ctx, mp4File.absolutePath, "video/mp4", project.title)
            if (intent != null) ctx.startActivity(Intent.createChooser(intent, "এক্সটার্নাল প্লেয়ারে চালান"))
          }) { Text("প্লেয়ারে ওপেন", color = CYBER_GREEN) }
        }
        TextButton(onClick = onDismiss) { Text("বন্ধ করুন", color = CYBER_CYAN) }
      }
    },
  )
}
