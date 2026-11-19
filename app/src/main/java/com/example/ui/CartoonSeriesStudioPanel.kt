package com.example.ui

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.video.AnimationFormat
import com.example.video.CameraShotType
import com.example.video.CharacterAgeGroup
import com.example.video.CharacterCategory
import com.example.video.CharacterEmotion
import com.example.video.EnvironmentSetting
import com.example.video.PipelineRunStatus
import com.example.video.SeriesStoryBibleManager
import com.example.video.TimeWeatherMood
import com.example.video.VideoStudioCatalog
import com.example.video.VideoStudioUiState
import com.example.video.VideoStudioViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CartoonSeriesStudioPanel(
  state: VideoStudioUiState,
  vm: VideoStudioViewModel,
) {
  var showNewCharForm by remember { mutableStateOf(false) }
  var showNewSeriesForm by remember { mutableStateOf(false) }
  var pendingRefCharId by remember { mutableStateOf<String?>(null) }
  var nextEpisodeIdea by remember { mutableStateOf("") }

  var newCharName by remember { mutableStateOf("") }
  var newCharRole by remember { mutableStateOf("") }
  var newCharCategory by remember { mutableStateOf(CharacterCategory.CARTOON_GIRL) }
  var newCharAge by remember { mutableStateOf(CharacterAgeGroup.CHILD) }
  var newCharVoice by remember { mutableStateOf("Aoede") }
  var newCharOutfit by remember { mutableStateOf("") }

  var newSeriesTitle by remember { mutableStateOf("") }
  var newSeriesGenre by remember { mutableStateOf("স্কুল, বন্ধুত্ব ও নাটক") }
  var newSeriesBible by remember { mutableStateOf("") }

  val pickRefPhotoLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      val targetId = pendingRefCharId
      if (uri != null && targetId != null) {
        vm.attachReferenceImageToCharacter(targetId, uri)
      }
      pendingRefCharId = null
    }

  val isRunning = state.progress.status == PipelineRunStatus.RUNNING
  val activeSeries = state.seriesProjects.firstOrNull { it.seriesId == state.activeSeriesId }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("cartoon_series_studio_card")
      .clip(RoundedCornerShape(18.dp))
      .background(Brush.linearGradient(listOf(Color(0xFF15193D), Color(0xFF101F3A))))
      .border(1.3.dp, Color(0xFFA855F7).copy(alpha = 0.65f), RoundedCornerShape(18.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Filled.MovieCreation, contentDescription = null, tint = Color(0xFFE879F9), modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          "3D CARTOON MOVIE, CHARACTER LIBRARY & SERIES STUDIO",
          color = Color(0xFFE879F9),
          fontWeight = FontWeight.Bold,
          fontSize = 12.5.sp,
        )
        Text(
          "${state.characterLibrary.size}টি চরিত্র • ${state.selectedCastIds.size}টি সক্রিয় কাস্ট • EP %02d প্রস্তুত".format(activeSeries?.nextEpisodeNumber ?: 1),
          color = CYBER_CYAN,
          fontSize = 10.5.sp,
        )
      }
      TextButton(
        onClick = { vm.toggleCartoonSeriesStudio(!state.showCartoonSeriesStudio) },
        modifier = Modifier.testTag("toggle_cartoon_series_btn"),
      ) {
        Text(if (state.showCartoonSeriesStudio) "সংক্ষেপ" else "পূর্ণ স্টুডিও", color = CYBER_AMBER, fontSize = 11.5.sp)
      }
    }

    // 1. UNLIMITED CHARACTER LIBRARY (1–4 simultaneous actors + Reference Image binding)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Filled.GroupAdd, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(15.dp))
      Spacer(Modifier.width(4.dp))
      Text("চরিত্র নির্বাচন (একসাথে ১–৪ জন কাস্ট + Reference ইমেজ):", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
      TextButton(onClick = { showNewCharForm = !showNewCharForm }, modifier = Modifier.testTag("create_character_btn")) {
        Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = CYBER_GREEN, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text("+ নতুন চরিত্র", color = CYBER_GREEN, fontSize = 11.sp)
      }
    }

    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      state.characterLibrary.forEach { ch ->
        val selected = ch.id in state.selectedCastIds
        val refBmp = remember(ch.referenceImagePath) {
          ch.referenceImagePath.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
          }
        }
        Column(
          modifier = Modifier
            .width(126.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(ch.primaryColorHex).copy(alpha = 0.22f) else Color(0xFF0C1329))
            .border(if (selected) 1.6.dp else 1.dp, if (selected) Color(ch.primaryColorHex) else Color(0xFF283459), RoundedCornerShape(12.dp))
            .clickable { vm.toggleCastMember(ch.id) }
            .padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (refBmp != null) {
              Image(
                bitmap = refBmp.asImageBitmap(),
                contentDescription = ch.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(28.dp).clip(CircleShape).border(1.dp, CYBER_GREEN, CircleShape),
              )
            } else {
              Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(ch.primaryColorHex)),
                contentAlignment = Alignment.Center,
              ) {
                Text(ch.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
              }
            }
            Spacer(Modifier.weight(1f))
            if (selected) {
              Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = CYBER_GREEN, modifier = Modifier.size(16.dp))
            }
          }
          Text(ch.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(ch.roleBn, color = Color(0xFF94A3B8), fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text("কণ্ঠ: ${ch.recommendedVoiceId}", color = CYBER_CYAN, fontSize = 9.5.sp)
          OutlinedButton(
            onClick = {
              pendingRefCharId = ch.id
              pickRefPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            modifier = Modifier.fillMaxWidth().height(28.dp),
          ) {
            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = CYBER_AMBER, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
            Text(if (refBmp != null) "Ref ✓" else "+ Ref ছবি", color = CYBER_AMBER, fontSize = 9.sp)
          }
        }
      }
    }

    // Inline Unlimited Custom Character Creation Form
    AnimatedVisibility(visible = showNewCharForm) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Color(0xFF0A1024))
          .border(1.dp, CYBER_GREEN.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
          .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("নতুন কাস্টম চরিত্র তৈরি করুন (লাইব্রেরিতে স্থায়ীভাবে সেভ হবে):", color = CYBER_GREEN, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
        OutlinedTextField(
          value = newCharName,
          onValueChange = { newCharName = it },
          label = { Text("চরিত্রের নাম (যেমন: রাইসা, মাস্টার সাহেব, টাইগার)", fontSize = 10.5.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = newCharRole,
          onValueChange = { newCharRole = it },
          label = { Text("পরিচয়/পেশা (যেমন: শিক্ষার্থী, ডাক্তার, মা)", fontSize = 10.5.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          CharacterCategory.entries.forEach { cat ->
            FilterChip(
              selected = newCharCategory == cat,
              onClick = { newCharCategory = cat },
              label = { Text(cat.labelBn, fontSize = 10.sp) },
            )
          }
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          CharacterAgeGroup.entries.forEach { ag ->
            FilterChip(
              selected = newCharAge == ag,
              onClick = { newCharAge = ag },
              label = { Text(ag.labelBn, fontSize = 10.sp) },
            )
          }
          VideoStudioCatalog.GOOGLE_VOICES.forEach { gv ->
            FilterChip(
              selected = newCharVoice == gv.id,
              onClick = { newCharVoice = gv.id },
              label = { Text("কণ্ঠ: ${gv.displayName}", fontSize = 10.sp) },
            )
          }
        }
        OutlinedTextField(
          value = newCharOutfit,
          onValueChange = { newCharOutfit = it },
          label = { Text("পোশাক ও বৈশিষ্ট্য", fontSize = 10.5.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Button(
          onClick = {
            vm.createCustomCharacter(
              name = newCharName.ifBlank { "নতুন তারকা" },
              roleBn = newCharRole.ifBlank { newCharCategory.labelBn },
              category = newCharCategory,
              ageGroup = newCharAge,
              voiceId = newCharVoice,
              personality = "প্রাণবন্ত ও অভিব্যক্তিপূর্ণ",
              outfitStyle = newCharOutfit.ifBlank { "রঙিন ৩ডি পোশাক" },
            )
            newCharName = ""
            newCharRole = ""
            newCharOutfit = ""
            showNewCharForm = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = CYBER_GREEN, contentColor = Color.Black),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("চরিত্র লাইব্রেরিতে সংরক্ষণ করুন ✓", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
        }
      }
    }

    // 2. STORY & ACTIVITY TEMPLATES + 14-REQ SERIES TEST
    Text("দ্রুত গল্প, স্কুল, নাচ-গান ও নাটক টেমপ্লেট:", color = CYBER_AMBER, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      SeriesStoryBibleManager.STORY_AND_ACTIVITY_TEMPLATES.forEach { tpl ->
        FilterChip(
          selected = state.promptInput == tpl.promptBn,
          onClick = { vm.applyStoryTemplate(tpl) },
          label = { Text(tpl.titleBn, fontSize = 10.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_AMBER, selectedLabelColor = Color.Black),
        )
      }
    }

    // 3. ANIMATION FORMATS, ENVIRONMENT, CAMERA & EMOTIONS (Expandable or Compact)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      AnimationFormat.entries.forEach { fmt ->
        FilterChip(
          selected = state.settings.animationFormat == fmt,
          onClick = { vm.selectAnimationFormat(fmt) },
          label = { Text(fmt.labelBn, fontSize = 10.sp) },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_PURPLE, selectedLabelColor = Color.White),
        )
      }
    }

    AnimatedVisibility(visible = state.showCartoonSeriesStudio) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("পরিবেশ ও ব্যাকগ্রাউন্ড সেটিংস:", color = CYBER_CYAN, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          EnvironmentSetting.entries.forEach { env ->
            FilterChip(
              selected = state.settings.preferredEnvironment == env,
              onClick = { vm.selectEnvironment(env) },
              label = { Text(env.labelBn, fontSize = 10.sp) },
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(14.dp))
          Spacer(Modifier.width(4.dp))
          Text("ক্যামেরা শট, আবেগ (Emotion) ও সময়/আবহাওয়া:", color = CYBER_CYAN, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          CameraShotType.entries.forEach { shot ->
            FilterChip(
              selected = state.settings.preferredCameraShot == shot,
              onClick = { vm.selectCameraShot(shot) },
              label = { Text(shot.labelBn, fontSize = 10.sp) },
            )
          }
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          CharacterEmotion.entries.forEach { em ->
            FilterChip(
              selected = state.settings.preferredEmotion == em,
              onClick = { vm.selectCharacterEmotion(em) },
              label = { Text(em.labelBn, fontSize = 10.sp) },
            )
          }
          TimeWeatherMood.entries.forEach { w ->
            FilterChip(
              selected = state.settings.preferredWeatherMood == w,
              onClick = { vm.selectWeatherMood(w) },
              label = { Text(w.labelBn, fontSize = 10.sp) },
            )
          }
        }

        // 4. DRAMA, MOVIE & SERIAL EPISODE CREATOR (EP 01..05+ Continuity)
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A1024))
            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AutoStories, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("সিরিয়াল ও ধারাবাহিক এপিসোড নির্মাতা (Story Bible)", color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { showNewSeriesForm = !showNewSeriesForm }) {
              Text("+ নতুন সিরিজ", color = CYBER_GREEN, fontSize = 10.5.sp)
            }
          }

          Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.seriesProjects.forEach { sp ->
              FilterChip(
                selected = state.activeSeriesId == sp.seriesId,
                onClick = { vm.selectSeriesProject(sp.seriesId) },
                label = { Text("${sp.seriesTitle} (${sp.episodes.size} পর্ব)", fontSize = 10.sp) },
              )
            }
          }

          AnimatedVisibility(visible = showNewSeriesForm) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              OutlinedTextField(
                value = newSeriesTitle,
                onValueChange = { newSeriesTitle = it },
                label = { Text("নতুন সিরিজের নাম", fontSize = 10.5.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
              )
              OutlinedTextField(
                value = newSeriesBible,
                onValueChange = { newSeriesBible = it },
                label = { Text("মূল গল্প ও ধারাবাহিকতা (Story Bible)", fontSize = 10.5.sp) },
                modifier = Modifier.fillMaxWidth(),
              )
              Button(
                onClick = {
                  vm.createSeriesProject(newSeriesTitle, newSeriesGenre, newSeriesBible)
                  newSeriesTitle = ""
                  newSeriesBible = ""
                  showNewSeriesForm = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = CYBER_CYAN, contentColor = Color.Black),
              ) {
                Text("সিরিজ প্রজেক্ট তৈরি করুন", fontWeight = FontWeight.Bold, fontSize = 11.sp)
              }
            }
          }

          if (activeSeries != null) {
            Text(activeSeries.storyBibleSummary, color = Color(0xFFCBD5E1), fontSize = 10.5.sp)
            if (activeSeries.episodes.isNotEmpty()) {
              Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                activeSeries.episodes.forEach { ep ->
                  Text(
                    "EP %02d ✓".format(ep.episodeNumber),
                    color = CYBER_GREEN,
                    fontSize = 10.sp,
                    modifier = Modifier
                      .clip(RoundedCornerShape(8.dp))
                      .background(CYBER_GREEN.copy(alpha = 0.14f))
                      .padding(horizontal = 8.dp, vertical = 3.dp),
                  )
                }
              }
            }
            OutlinedTextField(
              value = nextEpisodeIdea,
              onValueChange = { nextEpisodeIdea = it },
              label = { Text("Episode %02d-এর নতুন ঘটনা লিখুন".format(activeSeries.nextEpisodeNumber), fontSize = 10.5.sp) },
              modifier = Modifier.fillMaxWidth(),
            )
            Button(
              onClick = { vm.generateNextEpisodeForSeries(nextEpisodeIdea) },
              enabled = !isRunning,
              modifier = Modifier.fillMaxWidth().testTag("generate_next_episode_btn"),
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE879F9), contentColor = Color.Black),
            ) {
              Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(5.dp))
              Text("Episode %02d তৈরি করুন (ধারাবাহিকতা রক্ষা করে)".format(activeSeries.nextEpisodeNumber), fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
            }
          }
        }
      }
    }

    // Quality Test Button + Honest Reference Animation Note
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Button(
        onClick = { vm.launch14RequirementSeriesTest() },
        enabled = !isRunning,
        modifier = Modifier.weight(1f).testTag("test_14_req_series_btn"),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_AMBER, contentColor = Color.Black),
      ) {
        Text("মাল্টি-কাস্ট ৩ডি নাটক ও নাচ টেস্ট (১৪নং টেস্ট)", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    Text(
      "ফ্রি-ফার্স্ট ও অ্যানিমেশন নিশ্চয়তা: Reference ছবিতে চোখের পলক, ঠোঁট মেলানো Lip-Sync ও হাত-পা নড়াচড়া সবই জীবন্ত; কখনো স্ট্যাটিক স্লাইডশো হবে না।",
      color = Color(0xFF94A3B8),
      fontSize = 10.sp,
    )
  }
}
