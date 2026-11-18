package com.example.ui

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.data.RiyaState
import com.example.data.Store
import com.example.data.VoiceGender
import com.example.data.VoiceMatchSensitivity
import com.example.service.AccessibilityStatusMonitor
import com.example.service.Keep
import com.example.service.RiyaVoiceManager
import com.example.service.VoiceMatchManager
import kotlinx.coroutines.delay

fun hasPerm(c: Context, p: String): Boolean =
  ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

@Composable
fun SubPageTopBar(
  title: String,
  icon: ImageVector,
  iconColors: List<Color>,
  onBack: () -> Unit,
  onNavigate: (String) -> Unit,
) {
  val ctx = LocalContext.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Back",
        tint = Color.White,
      )
    }
    PremiumIconBadge(icon = icon, contentDescription = title, colors = iconColors, sizeDp = 34)
    Spacer(Modifier.width(10.dp))
    Text(
      text = title,
      fontSize = 20.sp,
      color = Color.White,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f),
    )
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
}

private data class PermItem(
  val name: String,
  val why: String,
  val icon: ImageVector,
  val colors: List<Color>,
  val ok: Boolean,
  val go: () -> Unit,
  val hint: String? = null,
  val hintLabel: String = "",
  val hintGo: (() -> Unit)? = null,
)

private class SpItem(val id: String, val ok: Boolean, val go: () -> Unit)

@Composable
fun PermPage(back: () -> Unit, onNavigate: (String) -> Unit) {
  val ctx = LocalContext.current
  var tick by remember { mutableIntStateOf(0) }
  val resume by RiyaState.resume.collectAsState()
  LaunchedEffect(Unit) {
    while (true) {
      delay(800)
      tick++
    }
  }
  val stamp = tick + resume
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
  val multi =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
  fun rt(p: String) = { launcher.launch(p) }
  fun open(i: Intent) {
    try {
      ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
      Keep.openAppInfo(ctx)
    }
  }

  val pkg = Uri.parse("package:${ctx.packageName}")
  val prefs = ctx.getSharedPreferences("riya_ai", Context.MODE_PRIVATE)
  val accOk = remember(stamp) { Keep.accessibilityOn(ctx) }
  val notifOk = remember(stamp) {
    try {
      NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    } catch (_: Exception) {
      false
    }
  }
  val overlayOk = Settings.canDrawOverlays(ctx)
  val batOk =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(
      ctx.packageName
    )
  val dndOk =
    (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
      .isNotificationPolicyAccessGranted
  val oemOk = prefs.getBoolean("oemDone", false)
  val accGo = { if (!Keep.ensureAccessibility(ctx)) Keep.openAccessibility(ctx) }
  val notifGo = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
  val batGo = { Keep.openBattery(ctx) }
  val oemGo = {
    prefs.edit().putBoolean("oemDone", true).apply()
    Keep.openOemAutostart(ctx)
  }

  val runtime = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.CALL_PHONE)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
  }
  val missingRt = runtime.filter { !hasPerm(ctx, it) }
  val specials =
    listOf(
      SpItem("acc", accOk, accGo),
      SpItem("notif", notifOk, notifGo),
      SpItem("overlay", overlayOk) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)) },
      SpItem("bat", batOk, batGo),
      SpItem("dnd", dndOk) { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
      SpItem("oem", oemOk, oemGo),
    )

  var wizard by remember { mutableStateOf(false) }
  var lastActed by remember { mutableIntStateOf(-1) }
  val asked = remember { mutableSetOf<String>() }
  var askedRt by remember { mutableStateOf(false) }
  LaunchedEffect(stamp, wizard) {
    if (!wizard || resume == lastActed) return@LaunchedEffect
    if (!askedRt && missingRt.isNotEmpty()) {
      askedRt = true
      lastActed = resume
      multi.launch(missingRt.toTypedArray())
      return@LaunchedEffect
    }
    val next = specials.firstOrNull { !it.ok && it.id !in asked }
    if (next != null) {
      asked.add(next.id)
      if (next.id == "acc" && Keep.ensureAccessibility(ctx)) {
        lastActed = -1
        return@LaunchedEffect
      }
      lastActed = resume
      next.go()
      return@LaunchedEffect
    }
    wizard = false
    val allOk = missingRt.isEmpty() && specials.all { it.ok || it.id == "oem" }
    Toast.makeText(
        ctx,
        if (allOk) "সব অনুমতি চালু ✓" else "কিছু অনুমতি এখনও বাকি, নিচে দেখুন",
        Toast.LENGTH_LONG,
      )
      .show()
  }

  val list =
    listOf(
      PermItem(
        "Accessibility Service",
        "ফোন নিয়ন্ত্রণ, imo সেন্ড ও সম্পূর্ণ স্ক্রিন রিডিংয়ের জন্য",
        Icons.Filled.AccessibilityNew,
        listOf(CYBER_RED, CYBER_PURPLE),
        accOk,
        accGo,
        hint =
          "সুইচ ধূসর হলে বা চালু না হলে: App info → ⋮ (উপরে ডানে) → Restricted settings অনুমতি দিন, তারপর আবার Allow চাপুন।",
        hintLabel = "App info খুলুন",
        hintGo = { Keep.openAppInfo(ctx) },
      ),
      PermItem(
        "Notification Access",
        "WhatsApp, imo ও Messenger-এ মেসেজ এলে সাথে সাথে বসকে জানাতে",
        Icons.Filled.Notifications,
        listOf(CYBER_GREEN, CYBER_CYAN),
        notifOk,
        notifGo,
      ),
      PermItem(
        "Microphone",
        "স্পষ্ট বাংলা ভয়েস কথা বলার জন্য",
        Icons.Filled.Mic,
        listOf(CYBER_CYAN, CYBER_BLUE),
        hasPerm(ctx, Manifest.permission.RECORD_AUDIO),
        rt(Manifest.permission.RECORD_AUDIO),
      ),
      PermItem(
        "Bluetooth",
        "ব্লুটুথ হেডসেট/TWS কানেক্টের জন্য",
        Icons.Filled.Bluetooth,
        listOf(CYBER_BLUE, CYBER_CYAN),
        Build.VERSION.SDK_INT < 31 || hasPerm(ctx, Manifest.permission.BLUETOOTH_CONNECT),
        if (Build.VERSION.SDK_INT >= 31) rt(Manifest.permission.BLUETOOTH_CONNECT) else ({}),
      ),
      PermItem(
        "Camera",
        "ছবি দেখে কথা বলার জন্য",
        Icons.Filled.PhotoCamera,
        listOf(CYBER_PURPLE, CYBER_RED),
        hasPerm(ctx, Manifest.permission.CAMERA),
        rt(Manifest.permission.CAMERA),
      ),
      PermItem(
        "Display over apps",
        "অন্যান্য অ্যাপের ওপর নির্বিঘ্নে কাজ করতে",
        Icons.Filled.Layers,
        listOf(CYBER_AMBER, CYBER_RED),
        overlayOk,
        specials[2].go,
      ),
      PermItem(
        "Battery optimization OFF",
        "ব্যাকগ্রাউন্ডে LXR--AI ও Accessibility সচল রাখতে",
        Icons.Filled.BatteryChargingFull,
        listOf(CYBER_GREEN, CYBER_BLUE),
        batOk,
        batGo,
      ),
      PermItem(
        "Autostart / Background (Infinix XOS)",
        "Infinix Android 16 যেন LXR--AI বা Accessibility বন্ধ না করে",
        Icons.Filled.RocketLaunch,
        listOf(CYBER_RED, CYBER_AMBER),
        oemOk,
        oemGo,
        hint = "Infinix Phone Master-এ LXR--AI এর Auto-start ও Background চালু রাখুন।",
      ),
      PermItem(
        "Do Not Disturb access",
        "ফোন সাইলেন্ট বা ভাইব্রেট করার জন্য",
        Icons.Filled.DoNotDisturbOn,
        listOf(CYBER_PURPLE, CYBER_BLUE),
        dndOk,
        specials[4].go,
      ),
      PermItem(
        "Contacts",
        "নাম দিয়ে সরাসরি কল ও চ্যাট খুঁজতে",
        Icons.Filled.Contacts,
        listOf(CYBER_AMBER, CYBER_CYAN),
        hasPerm(ctx, Manifest.permission.READ_CONTACTS),
        rt(Manifest.permission.READ_CONTACTS),
      ),
      PermItem(
        "Phone calls",
        "কল লাগানোর জন্য",
        Icons.Filled.Call,
        listOf(CYBER_GREEN, CYBER_PURPLE),
        hasPerm(ctx, Manifest.permission.CALL_PHONE),
        rt(Manifest.permission.CALL_PHONE),
      ),
    )

  Column(
    Modifier.fillMaxSize().background(FULL_BG_GRADIENT).statusBarsPadding().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SubPageTopBar(
      title = "Permissions",
      icon = Icons.Filled.AccessibilityNew,
      iconColors = listOf(CYBER_RED, CYBER_PURPLE),
      onBack = back,
      onNavigate = onNavigate,
    )
    Button(
      onClick = {
        asked.clear()
        askedRt = false
        lastActed = -1
        wizard = true
      },
      modifier = Modifier.fillMaxWidth().testTag("enable_all_perms_button"),
      colors = ButtonDefaults.buttonColors(containerColor = CYBER_RED),
    ) {
      Text(if (wizard) "চালু করা হচ্ছে… প্রতিটি পেজে Allow দিন" else "⚡ সব একসাথে চালু করুন")
    }
    Column(
      Modifier
        .fillMaxWidth()
        .background(CYBER_CARD, RoundedCornerShape(14.dp))
        .border(1.dp, CYBER_CYAN.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      val selfHealOn = remember(stamp) { Keep.canSelfEnable(ctx) }
      Text(
        "🛡️ Accessibility অটো-বন্ধ প্রতিরোধ গার্ড",
        color = CYBER_CYAN,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
      )
      Text(
        if (selfHealOn)
          "অটো-রিপেয়ার সক্রিয় ✓ — OS বন্ধ করলেও LXR--AI নিজেই সাথে সাথে চালু করে নেবে।"
        else
          "ফোন যেন Accessibility বন্ধ না করে সেজন্য Battery Unrestricted + Restricted Settings চালু রাখুন।",
        color = Color(0xFFC5CEE6),
        fontSize = 11.5.sp,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = batGo, modifier = Modifier.weight(1f)) {
          Text("Battery", color = Color.White, fontSize = 11.sp)
        }
        OutlinedButton(onClick = { Keep.openAppInfo(ctx) }, modifier = Modifier.weight(1f)) {
          Text("Restricted", color = Color.White, fontSize = 11.sp)
        }
        OutlinedButton(onClick = oemGo, modifier = Modifier.weight(1f)) {
          Text("Autostart", color = Color.White, fontSize = 11.sp)
        }
      }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(list) { p ->
        Column(
          Modifier
            .fillMaxWidth()
            .background(CYBER_CARD, RoundedCornerShape(16.dp))
            .border(
              1.dp,
              if (p.ok) CYBER_GREEN.copy(alpha = 0.35f) else CYBER_RED.copy(alpha = 0.35f),
              RoundedCornerShape(16.dp),
            )
            .padding(14.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconBadge(p.icon, p.name, p.colors, sizeDp = 38)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(p.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
              Text(p.why, color = Color(0xFFAAB4D0), fontSize = 12.sp)
            }
            if (p.ok) {
              Text("✓ ON", color = CYBER_GREEN, fontWeight = FontWeight.Bold)
            } else {
              Button(p.go, colors = ButtonDefaults.buttonColors(containerColor = CYBER_RED)) {
                Text("Allow")
              }
            }
          }
          if (p.hint != null && !p.ok) {
            Text(p.hint, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            if (p.hintGo != null) {
              TextButton(p.hintGo) { Text(p.hintLabel, color = CYBER_CYAN) }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyPage(back: () -> Unit, onNavigate: (String) -> Unit) {
  val ctx = LocalContext.current
  var key by remember { mutableStateOf(Store.apiKey(ctx)) }
  var model by remember { mutableStateOf(Store.model(ctx)) }
  var voice by remember { mutableStateOf(Store.voice(ctx)) }
  var ttsCloud by remember { mutableStateOf(Store.ttsCloudEnabled(ctx)) }
  var ttsStyle by remember { mutableStateOf(Store.ttsStyleInstruction(ctx)) }
  var voiceSpeed by remember { mutableFloatStateOf(Store.voiceSpeed(ctx)) }
  var whisperBoost by remember { mutableStateOf(Store.whisperBoost(ctx)) }
  var lowNetFast by remember { mutableStateOf(Store.lowNetFastMode(ctx)) }
  var genderFilter by remember { mutableStateOf<VoiceGender?>(null) }
  var show by remember { mutableStateOf(false) }

  // 3-step Voice Match state inside Gemini API Key & Voice section
  var vmEnabled by remember { mutableStateOf(Store.voiceMatchEnabled(ctx)) }
  var vmSensitivity by remember { mutableStateOf(Store.voiceMatchSensitivity(ctx)) }
  var vmStamp by remember { mutableIntStateOf(0) }
  var recordingStep by remember { mutableIntStateOf(0) }
  var playingAudioStep by remember { mutableIntStateOf(0) }
  var testingMatch by remember { mutableStateOf(false) }
  var vmLevel by remember { mutableFloatStateOf(0f) }
  var vmFeedback by remember { mutableStateOf("") }
  var vmFeedbackPositive by remember { mutableStateOf(true) }
  var pendingVmAction by remember { mutableStateOf<(() -> Unit)?>(null) }

  val vmProfile = remember(vmStamp) { VoiceMatchManager.loadProfile(ctx, forceReload = true) }

  val vmMicPermLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        pendingVmAction?.invoke()
      } else {
        vmFeedback = "ভয়েস ম্যাচ ব্যবহারের জন্য মাইক্রোফোন অনুমতি দিন বস।"
        vmFeedbackPositive = false
      }
      pendingVmAction = null
    }

  DisposableEffect(Unit) {
    onDispose {
      VoiceMatchManager.cancelActiveCapture()
      VoiceMatchManager.stopStepAudio()
    }
  }

  fun startEnrollStep(stepNumber: Int) {
    val action = {
      playingAudioStep = 0
      VoiceMatchManager.stopStepAudio()
      recordingStep = stepNumber
      testingMatch = false
      vmFeedback = "ধাপ $stepNumber শুনছি ও কথা যাচাই করছি — সঠিক বাক্যটি বলুন..."
      vmFeedbackPositive = true
      VoiceMatchManager.enrollStep(
        ctx = ctx,
        step = stepNumber,
        onLevel = { vmLevel = it },
        onDone = { ok, msg ->
          recordingStep = 0
          vmLevel = 0f
          vmFeedback = msg
          vmFeedbackPositive = ok
          vmStamp++
          vmEnabled = Store.voiceMatchEnabled(ctx)
        },
      )
    }
    if (hasPerm(ctx, Manifest.permission.RECORD_AUDIO)) {
      action()
    } else {
      pendingVmAction = action
      vmMicPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  fun startTestMatch() {
    val action = {
      playingAudioStep = 0
      VoiceMatchManager.stopStepAudio()
      testingMatch = true
      recordingStep = 0
      vmFeedback = "এখন কথা বলুন — বসের কণ্ঠ মিলিয়ে দেখছি..."
      vmFeedbackPositive = true
      VoiceMatchManager.testLiveMatch(
        ctx = ctx,
        onLevel = { vmLevel = it },
        onDone = { matched, msg ->
          testingMatch = false
          vmLevel = 0f
          vmFeedback = msg
          vmFeedbackPositive = matched
        },
      )
    }
    if (hasPerm(ctx, Manifest.permission.RECORD_AUDIO)) {
      action()
    } else {
      pendingVmAction = action
      vmMicPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  val ttsStatus by RiyaVoiceManager.ttsStatusLabel.collectAsState()

  val fieldColors =
    OutlinedTextFieldDefaults.colors(
      focusedTextColor = Color.White,
      unfocusedTextColor = Color.White,
      focusedBorderColor = CYBER_CYAN,
      unfocusedBorderColor = Color(0xFF475073),
      focusedLabelColor = CYBER_CYAN,
      unfocusedLabelColor = Color(0xFFAAB4D0),
    )

  fun applyVoiceSettings() {
    Store.setApiKey(ctx, key)
    Store.setModel(ctx, model)
    Store.setVoice(ctx, voice)
    Store.setTtsCloudEnabled(ctx, ttsCloud)
    Store.setTtsStyleInstruction(ctx, ttsStyle)
    Store.setVoiceSpeed(ctx, voiceSpeed)
    Store.setWhisperBoost(ctx, whisperBoost)
    Store.setLowNetFastMode(ctx, lowNetFast)
    Store.setVoiceMatchEnabled(ctx, vmEnabled)
    Store.setVoiceMatchSensitivity(ctx, vmSensitivity)
    RiyaVoiceManager.refreshVoicePreferences(ctx)
    RiyaState.live?.updatePlaybackSpeed(voiceSpeed)
  }

  fun updateSpeed(newRate: Float) {
    val snapped = ((newRate * 20f).toInt() / 20f).coerceIn(Store.MIN_VOICE_SPEED, Store.MAX_VOICE_SPEED)
    voiceSpeed = snapped
    Store.setVoiceSpeed(ctx, snapped)
    RiyaVoiceManager.refreshVoicePreferences(ctx)
    RiyaState.live?.updatePlaybackSpeed(snapped)
  }

  Column(
    Modifier
      .fillMaxSize()
      .background(FULL_BG_GRADIENT)
      .statusBarsPadding()
      .navigationBarsPadding()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageTopBar(
      title = "Gemini API Key ও Voice",
      icon = Icons.Filled.Key,
      iconColors = listOf(CYBER_AMBER, CYBER_RED),
      onBack = back,
      onNavigate = onNavigate,
    )

    OutlinedTextField(
      value = key,
      onValueChange = { key = it },
      modifier = Modifier.fillMaxWidth().testTag("gemini_api_key_input"),
      label = { Text("Gemini Live & TTS API Key") },
      singleLine = true,
      colors = fieldColors,
      visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
      TextButton({ show = !show }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (show) "Hide Key" else "Show Key", color = CYBER_CYAN)
      }
      Text(
        "Google AI Studio ফ্রি কী",
        color = Color(0xFFAAB4D0),
        fontSize = 11.sp,
      )
    }
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .testTag("gemini_api_key_link")
          .clip(RoundedCornerShape(12.dp))
          .background(CYBER_CYAN.copy(alpha = 0.10f))
          .border(1.dp, CYBER_CYAN.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
          .clickable {
            try {
              ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/api-keys"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              )
            } catch (_: Throwable) {
              Toast.makeText(ctx, "ব্রাউজার খোলা যায়নি", Toast.LENGTH_SHORT).show()
            }
          }
          .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        "Gemini API key সংগ্রহ করুন:",
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
      )
      Text(
        "https://aistudio.google.com/api-keys",
        color = CYBER_CYAN,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline,
      )
    }

    OutlinedTextField(
      value = model,
      onValueChange = { model = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Live Voice Model") },
      singleLine = true,
      colors = fieldColors,
    )

    // Google Free Voice Studio (Gemini 3.1 Flash TTS Preview • bn-BD)
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(1.dp, CYBER_CYAN.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(Icons.Filled.RecordVoiceOver, "Google Free TTS", listOf(CYBER_CYAN, CYBER_BLUE), sizeDp = 36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text(Store.TTS_MODEL_LABEL, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          Text(
            "${Store.TTS_LOCALE_LABEL} (${Store.TTS_LOCALE_TAG}) • ফ্রি ভয়েস ইঞ্জিন",
            color = CYBER_CYAN,
            fontSize = 11.5.sp,
          )
        }
        Switch(
          checked = ttsCloud,
          onCheckedChange = {
            ttsCloud = it
            Store.setTtsCloudEnabled(ctx, it)
            RiyaVoiceManager.refreshVoicePreferences(ctx)
          },
          colors = SwitchDefaults.colors(checkedTrackColor = CYBER_CYAN, checkedThumbColor = Color.White),
        )
      }

      Text("সক্রিয় ভয়েস: $ttsStatus", color = Color(0xFFD5DEF5), fontSize = 11.5.sp)

      // Gender filter: All / Female / Male
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = genderFilter == null,
          onClick = { genderFilter = null },
          label = { Text("সব (৭টি)") },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_BLUE, selectedLabelColor = Color.White, labelColor = Color(0xFFB8C4E0)),
        )
        FilterChip(
          selected = genderFilter == VoiceGender.FEMALE,
          onClick = { genderFilter = VoiceGender.FEMALE },
          label = { Text("মেয়ে ভয়েস") },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_RED, selectedLabelColor = Color.White, labelColor = Color(0xFFB8C4E0)),
        )
        FilterChip(
          selected = genderFilter == VoiceGender.MALE,
          onClick = { genderFilter = VoiceGender.MALE },
          label = { Text("ছেলের ভয়েস") },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CYBER_CYAN, selectedLabelColor = Color.Black, labelColor = Color(0xFFB8C4E0)),
        )
      }

      val filteredVoices =
        remember(genderFilter) {
          if (genderFilter == null) Store.VOICE_OPTIONS
          else Store.VOICE_OPTIONS.filter { it.gender == genderFilter }
        }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        filteredVoices.forEach { option ->
          val selected = voice.equals(option.id, ignoreCase = true)
          val accent = if (option.gender == VoiceGender.FEMALE) CYBER_RED else CYBER_CYAN
          Column(
            modifier =
              Modifier
                .testTag("voice_option_${option.id.lowercase()}")
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) accent.copy(alpha = 0.24f) else Color(0xFF0E152E))
                .border(
                  1.2.dp,
                  if (selected) accent else Color(0xFF2D3759),
                  RoundedCornerShape(14.dp),
                )
                .clickable {
                  voice = option.id
                  Store.setVoice(ctx, option.id)
                  RiyaVoiceManager.refreshVoicePreferences(ctx)
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(option.displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              Text(
                option.gender.labelBn,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
              )
            }
            Text(option.toneBn, color = Color(0xFFAAB4D0), fontSize = 10.5.sp)
          }
        }
      }

      OutlinedTextField(
        value = ttsStyle,
        onValueChange = { ttsStyle = it },
        modifier = Modifier.fillMaxWidth().testTag("tts_style_input"),
        label = { Text("Style instructions") },
        colors = fieldColors,
      )

      // Dedicated Voice Model Speaking Speed Control (Slow <-> Fast)
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .testTag("voice_speed_card")
            .background(Color(0xFF0A1229), RoundedCornerShape(14.dp))
            .border(1.dp, CYBER_PURPLE.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Tune, contentDescription = "কথার গতি", tint = CYBER_CYAN, modifier = Modifier.size(18.dp))
            Text("কথার স্পিড / গতি (কমানো ও বাড়ানো)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }
        }
        Text(
          text = Store.voiceSpeedLabelBn(voiceSpeed),
          color = CYBER_CYAN,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          OutlinedButton(
            onClick = { updateSpeed(voiceSpeed - 0.05f) },
            modifier = Modifier.size(48.dp).testTag("voice_speed_decrease_btn"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
          ) {
            Text("−", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
          }
          Slider(
            value = voiceSpeed,
            onValueChange = { updateSpeed(it) },
            valueRange = Store.MIN_VOICE_SPEED..Store.MAX_VOICE_SPEED,
            steps = 11,
            modifier = Modifier.weight(1f).testTag("voice_speed_slider"),
            colors =
              SliderDefaults.colors(
                thumbColor = CYBER_CYAN,
                activeTrackColor = CYBER_PURPLE,
                inactiveTrackColor = Color(0xFF283358),
              ),
          )
          OutlinedButton(
            onClick = { updateSpeed(voiceSpeed + 0.05f) },
            modifier = Modifier.size(48.dp).testTag("voice_speed_increase_btn"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
          ) {
            Text("+", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
          }
        }
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          listOf(
            0.80f to "ধীর (0.80x)",
            0.88f to "শান্ত (0.88x)",
            1.00f to "স্বাভাবিক (1.00x)",
            1.15f to "দ্রুত (1.15x)",
          ).forEach { (presetSpeed, label) ->
            val active = kotlin.math.abs(voiceSpeed - presetSpeed) < 0.03f
            FilterChip(
              selected = active,
              onClick = { updateSpeed(presetSpeed) },
              modifier = Modifier.testTag("voice_speed_preset_${(presetSpeed * 100).toInt()}"),
              label = { Text(label, fontSize = 11.sp) },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = CYBER_PURPLE,
                  selectedLabelColor = Color.White,
                  labelColor = Color(0xFFB8C4E0),
                ),
            )
          }
        }
      }

      OutlinedButton(
        onClick = {
          applyVoiceSettings()
          val opt = Store.voiceOption(ctx)
          RiyaVoiceManager.speakUrgent(
            ctx,
            "হ্যালো বস, আমি এল এক্স আর এ আই। এখন ${opt.displayName} ভয়েসে আপনার নির্ধারিত গতিতে কথা বলছি।",
          )
        },
        modifier = Modifier.fillMaxWidth().testTag("test_voice_button"),
      ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("🔊 টেস্ট ভয়েস শুনুন ($voice)", color = CYBER_CYAN, fontWeight = FontWeight.Bold)
      }
    }

    // Soft-Voice Mic + LxR Bluetooth + Low Network Speed controls
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(1.dp, CYBER_GREEN.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(Icons.Filled.GraphicEq, "Mic Boost", listOf(CYBER_GREEN, CYBER_CYAN), sizeDp = 34)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text("LxR Bluetooth ও ফিসফিস মাইক বুস্ট", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
          Text("আস্তে কথা বললেও LxR ব্লুটুথ ও ফোনের মাইক স্পষ্ট শুনবে", color = Color(0xFFAAB4D0), fontSize = 11.sp)
        }
        Switch(
          checked = whisperBoost,
          onCheckedChange = { whisperBoost = it },
          colors = SwitchDefaults.colors(checkedTrackColor = CYBER_GREEN, checkedThumbColor = Color.White),
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(Icons.Filled.Speed, "Low Net", listOf(CYBER_BLUE, CYBER_PURPLE), sizeDp = 34)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text("অল্প নেট স্পিড ফাস্ট রেসপন্স মোড", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
          Text("কম ইন্টারনেট স্পিডেও দ্রুত উত্তর ও হালকা ডেটা ট্রান্সফার", color = Color(0xFFAAB4D0), fontSize = 11.sp)
        }
        Switch(
          checked = lowNetFast,
          onCheckedChange = { lowNetFast = it },
          colors = SwitchDefaults.colors(checkedTrackColor = CYBER_BLUE, checkedThumbColor = Color.White),
        )
      }
    }

    // Voice Match Tool (3-Step Speaker Identification & Non-Boss Voice Avoidance)
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .testTag("voice_match_card")
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(
            1.2.dp,
            if (vmProfile.isComplete && vmEnabled) CYBER_PURPLE.copy(alpha = 0.7f)
            else CYBER_AMBER.copy(alpha = 0.45f),
            RoundedCornerShape(18.dp),
          )
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(
          Icons.Filled.VerifiedUser,
          "Voice Match",
          listOf(CYBER_PURPLE, CYBER_CYAN),
          sizeDp = 36,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text("ভয়েস ম্যাচ (Voice Match)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
          Text(
            if (vmProfile.isComplete && vmEnabled)
              "সক্রিয় ✓ — স্বাভাবিক ও আস্তে বললেও বসের কণ্ঠ চিনবে, অন্যদের এভোয়েড করবে"
            else if (vmProfile.isComplete)
              "বন্ধ আছে — অ্যাপের সব কাজ ও কমান্ড স্বাভাবিকভাবে চলবে"
            else
              "৩ ধাপে কণ্ঠ দিন (${vmProfile.completedSteps}/3) — না দিলেও সব কাজ স্বাভাবিকভাবে চলবে",
            color = if (vmProfile.isComplete && vmEnabled) CYBER_GREEN else CYBER_AMBER,
            fontSize = 11.5.sp,
          )
        }
        Switch(
          checked = vmEnabled,
          onCheckedChange = {
            vmEnabled = it
            Store.setVoiceMatchEnabled(ctx, it)
          },
          modifier = Modifier.testTag("voice_match_toggle"),
          colors = SwitchDefaults.colors(checkedTrackColor = CYBER_PURPLE, checkedThumbColor = Color.White),
        )
      }

      LinearProgressIndicator(
        progress = { vmProfile.completedSteps / 3f },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
        color = if (vmProfile.isComplete) CYBER_GREEN else CYBER_PURPLE,
        trackColor = Color(0xFF232B45),
      )

      AnimatedVisibility(visible = recordingStep != 0 || testingMatch) {
        val animatedLvl by animateFloatAsState(targetValue = vmLevel, label = "vm_mic_level")
        Column(
          Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A1128), RoundedCornerShape(12.dp))
            .border(1.dp, CYBER_CYAN.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            if (testingMatch) "🎙️ কণ্ঠ মিলিয়ে দেখা হচ্ছে..."
            else "🎙️ ধাপ $recordingStep শুনছি ও কথা যাচাই করছি — সঠিক বাক্যটি বলুন...",
            color = CYBER_CYAN,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
          )
          LinearProgressIndicator(
            progress = { animatedLvl.coerceIn(0.05f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
            color = CYBER_GREEN,
            trackColor = Color(0xFF1B2440),
          )
        }
      }

      if (vmFeedback.isNotBlank()) {
        Text(
          text = vmFeedback,
          color = if (vmFeedbackPositive) CYBER_GREEN else CYBER_AMBER,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }

      val stepTitles = listOf("১- প্রথম ভয়েস সনাক্ত", "২- দ্বিতীয় ভয়েস সনাক্ত", "৩- তৃতীয় ভয়েস সনাক্ত")
      Store.VOICE_MATCH_PHRASES.forEachIndexed { idx, phrase ->
        val stepNum = idx + 1
        val done = vmProfile.isStepDone(stepNum)
        val isRecordingThis = recordingStep == stepNum
        val hasAudioClip = remember(vmStamp, done) { done && VoiceMatchManager.hasStepAudio(ctx, stepNum) }
        val isPlayingThis = playingAudioStep == stepNum
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .background(
                if (isRecordingThis) CYBER_CYAN.copy(alpha = 0.14f)
                else if (done) CYBER_GREEN.copy(alpha = 0.10f)
                else Color(0xFF0E152E),
                RoundedCornerShape(14.dp),
              )
              .border(
                1.dp,
                when {
                  isRecordingThis -> CYBER_CYAN
                  done -> CYBER_GREEN.copy(alpha = 0.55f)
                  else -> Color(0xFF2D3759)
                },
                RoundedCornerShape(14.dp),
              )
              .padding(horizontal = 12.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (done) {
                  Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Step $stepNum Done",
                    tint = CYBER_GREEN,
                    modifier = Modifier.size(16.dp),
                  )
                }
                Text(
                  stepTitles[idx],
                  color = if (done) CYBER_GREEN else Color(0xFFAAB4D0),
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                )
              }
              Text("“$phrase”", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
            Button(
              onClick = {
                if (isRecordingThis) VoiceMatchManager.cancelActiveCapture()
                else startEnrollStep(stepNum)
              },
              enabled = (recordingStep == 0 || isRecordingThis) && !testingMatch,
              modifier = Modifier.heightIn(min = 48.dp).testTag("voice_match_step_${stepNum}_btn"),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = if (isRecordingThis) CYBER_RED else if (done) CYBER_BLUE else CYBER_PURPLE,
                ),
            ) {
              Icon(
                imageVector = if (isRecordingThis) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isRecordingThis) "Stop recording" else "Record step $stepNum",
                modifier = Modifier.size(16.dp),
              )
              Spacer(Modifier.width(4.dp))
              Text(
                text = if (isRecordingThis) "থামান" else if (done) "আবার" else "বলুন",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          if (hasAudioClip) {
            val durSec = remember(vmStamp, done) { VoiceMatchManager.stepAudioDurationSec(ctx, stepNum) }
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .heightIn(min = 48.dp)
                  .testTag("voice_match_audio_play_${stepNum}")
                  .clip(RoundedCornerShape(10.dp))
                  .background(if (isPlayingThis) CYBER_CYAN.copy(alpha = 0.18f) else Color(0xFF0A1128))
                  .border(
                    1.dp,
                    if (isPlayingThis) CYBER_CYAN else CYBER_GREEN.copy(alpha = 0.45f),
                    RoundedCornerShape(10.dp),
                  )
                  .clickable(enabled = recordingStep == 0 && !testingMatch) {
                    if (isPlayingThis) {
                      playingAudioStep = 0
                      VoiceMatchManager.stopStepAudio()
                    } else {
                      playingAudioStep = stepNum
                      VoiceMatchManager.playStepAudio(ctx, stepNum) {
                        if (playingAudioStep == stepNum) playingAudioStep = 0
                      }
                    }
                  }
                  .padding(horizontal = 10.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = if (isPlayingThis) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription =
                  if (isPlayingThis) "ধাপ $stepNum অডিও থামান" else "ধাপ $stepNum অডিও শুনুন",
                tint = if (isPlayingThis) CYBER_RED else CYBER_CYAN,
                modifier = Modifier.size(20.dp),
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = if (isPlayingThis) "আপনার অডিও বাজছে..." else "ধাপ $stepNum — আপনার কণ্ঠ শুনুন",
                color = if (isPlayingThis) CYBER_CYAN else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
              )
              Text(
                text = "${"%.1f".format(durSec)} সে.",
                color = CYBER_GREEN,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }

      Text("সনাক্তকরণ সতর্কতা (Sensitivity / বন্ধ):", color = Color(0xFFD5DEF5), fontSize = 11.5.sp)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        FilterChip(
          selected = !vmEnabled,
          onClick = {
            vmEnabled = false
            Store.setVoiceMatchEnabled(ctx, false)
            vmFeedback = "ভয়েস ম্যাচ বন্ধ করা হয়েছে — অ্যাপের সব কাজ ও কমান্ড ১০০% স্বাভাবিকভাবে চলবে।"
            vmFeedbackPositive = true
          },
          modifier = Modifier.testTag("voice_match_mode_off"),
          label = { Text("বন্ধ (কোনটাই না)", fontSize = 11.sp) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = CYBER_AMBER,
              selectedLabelColor = Color.Black,
              labelColor = Color(0xFFB8C4E0),
            ),
        )
        VoiceMatchSensitivity.entries.forEach { preset ->
          FilterChip(
            selected = vmEnabled && vmSensitivity == preset,
            onClick = {
              vmSensitivity = preset
              vmEnabled = true
              Store.setVoiceMatchSensitivity(ctx, preset)
              Store.setVoiceMatchEnabled(ctx, true)
            },
            modifier = Modifier.testTag("voice_match_mode_${preset.name.lowercase()}"),
            label = { Text(preset.labelBn, fontSize = 11.sp) },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = CYBER_PURPLE,
                selectedLabelColor = Color.White,
                labelColor = Color(0xFFB8C4E0),
              ),
          )
        }
      }

      Text(
        text =
          if (!vmEnabled)
            "ভয়েস ম্যাচ বন্ধ — অ্যাপের সব কাজ ও কমান্ড ১০০% স্বাভাবিকভাবে চলবে এবং ফোনের নিজস্ব স্পিকারের শব্দ কখনোই শুনবে না।"
          else vmSensitivity.descriptionBn,
        color = if (!vmEnabled) CYBER_AMBER else CYBER_CYAN,
        fontSize = 11.5.sp,
      )

      if (vmProfile.isComplete) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          OutlinedButton(
            onClick = {
              if (testingMatch) VoiceMatchManager.cancelActiveCapture()
              else startTestMatch()
            },
            enabled = recordingStep == 0,
            modifier = Modifier.weight(1f).testTag("voice_match_test_btn"),
          ) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
              if (testingMatch) "শুনছি..." else "টেস্ট ভয়েস ম্যাচ",
              color = CYBER_CYAN,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
            )
          }
          OutlinedButton(
            onClick = {
              playingAudioStep = 0
              VoiceMatchManager.resetProfile(ctx)
              vmStamp++
              vmFeedback = "ভয়েস ম্যাচ রিসেট করা হয়েছে। আবার ৩ ধাপে সেট করুন।"
              vmFeedbackPositive = true
            },
            enabled = recordingStep == 0 && !testingMatch,
            modifier = Modifier.testTag("voice_match_reset_btn"),
          ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = CYBER_RED, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("রিসেট", color = CYBER_RED, fontSize = 12.sp)
          }
        }
      }
    }

    Button(
      onClick = {
        applyVoiceSettings()
        Toast.makeText(ctx, "হয়েছে, বস! LXR--AI ভয়েস ও ভয়েস ম্যাচ সেভ করা হয়েছে।", Toast.LENGTH_SHORT).show()
        back()
      },
      modifier = Modifier.fillMaxWidth().height(50.dp).testTag("save_key_voice_button"),
      colors = ButtonDefaults.buttonColors(containerColor = CYBER_RED),
    ) {
      Text("Save Settings", fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
fun MemPage(back: () -> Unit, onNavigate: (String) -> Unit) {
  val ctx = LocalContext.current
  var refresh by remember { mutableIntStateOf(0) }
  var searchQuery by remember { mutableStateOf("") }
  val used = remember(refresh) { Store.size(ctx) }
  val text =
    remember(refresh, searchQuery) {
      if (searchQuery.isBlank()) Store.recent(ctx, 8000)
      else Store.searchMemory(ctx, searchQuery, maxDays = Store.RETENTION_DAYS)
    }
  Column(
    Modifier.fillMaxSize().background(FULL_BG_GRADIENT).statusBarsPadding().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageTopBar(
      title = "Memory (৩০ দিন)",
      icon = Icons.Filled.Memory,
      iconColors = listOf(CYBER_BLUE, CYBER_CYAN),
      onBack = back,
      onNavigate = onNavigate,
    )
    Text("%.2f MB / 30 MB ব্যবহৃত হয়েছে (গত ৩০ দিনের স্মৃতি সুরক্ষিত)".format(used / 1048576.0), color = Color.White, fontSize = 13.sp)
    LinearProgressIndicator(
      progress = { (used.toFloat() / Store.CAP).coerceIn(0f, 1f) },
      modifier = Modifier.fillMaxWidth(),
      color = CYBER_RED,
    )
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      modifier = Modifier.fillMaxWidth().testTag("memory_search_input"),
      singleLine = true,
      placeholder = { Text("১ মাসের পুরনো কথার সূত্র দিয়ে খুঁজুন…", color = Color.Gray, fontSize = 13.sp) },
      colors =
        OutlinedTextFieldDefaults.colors(
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White,
          focusedBorderColor = CYBER_CYAN,
          unfocusedBorderColor = Color(0xFF475073),
        ),
    )
    Text(
      if (searchQuery.isBlank()) "সাম্প্রতিক স্মৃতিসমূহ:" else "সূত্র অনুযায়ী পাওয়া স্মৃতি:",
      color = CYBER_CYAN,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text.ifBlank { "এখনো কোনো মেমোরি সেভ হয়নি" },
      Modifier.weight(1f).verticalScroll(rememberScrollState()),
      color = Color.White,
      fontSize = 13.sp,
    )
    OutlinedButton(
      onClick = {
        Store.clear(ctx)
        refresh++
      },
      modifier = Modifier.fillMaxWidth().testTag("memory_clear_button"),
    ) {
      Text("স্মৃতি মুছে ফেলুন", color = CYBER_RED)
    }
  }
}

private fun openIntent(c: Context, i: Intent) {
  try {
    c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } catch (_: Exception) {
    Toast.makeText(c, "খোলা যায়নি", Toast.LENGTH_SHORT).show()
  }
}

private fun setTorch(c: Context, on: Boolean): String {
  return try {
    val cam = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ids =
      cam.cameraIdList
        .filter { cid ->
          try {
            cam.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
          } catch (_: Exception) {
            false
          }
        }
        .sortedByDescending { cid ->
          try {
            cam.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) ==
              CameraCharacteristics.LENS_FACING_BACK
          } catch (_: Exception) {
            false
          }
        }
    if (ids.isEmpty()) return "এই ফোনে ফ্ল্যাশ নেই"
    for (id in ids) {
      try {
        cam.setTorchMode(id, on)
        return if (on) "ফ্ল্যাশলাইট চালু" else "ফ্ল্যাশলাইট বন্ধ"
      } catch (_: Exception) {}
    }
    "ফ্ল্যাশ নিয়ন্ত্রণ করা গেল না"
  } catch (_: Exception) {
    "ফ্ল্যাশ নিয়ন্ত্রণ করা গেল না"
  }
}

private fun setRinger(c: Context, mode: String): String {
  val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  return try {
    am.ringerMode =
      when (mode) {
        "silent" -> AudioManager.RINGER_MODE_SILENT
        "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
        else -> AudioManager.RINGER_MODE_NORMAL
      }
    "সাউন্ড মোড বদলানো হয়েছে"
  } catch (_: SecurityException) {
    openIntent(c, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    "সাইলেন্ট করতে Do Not Disturb অনুমতি দিন"
  }
}

private fun wifiState(c: Context): Boolean =
  try {
    @Suppress("DEPRECATION")
    (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
  } catch (_: Exception) {
    false
  }

private fun btState(c: Context): Boolean =
  try {
    (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
  } catch (_: Exception) {
    false
  }

private fun ringerLabel(c: Context): String {
  val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  return when (am.ringerMode) {
    AudioManager.RINGER_MODE_SILENT -> "SILENT"
    AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
    else -> "NORMAL"
  }
}

private fun batteryPct(c: Context): Int {
  val bat = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  val lvl = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
  val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
  return if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
}

@Composable
private fun TileIcon(kind: String, color: Color) {
  Canvas(Modifier.size(26.dp)) {
    val w = size.width
    val h = size.height
    val st = Stroke(width = w * 0.12f, cap = StrokeCap.Round)
    when (kind) {
      "wifi" -> {
        for (k in 1..3) {
          val r = w * (0.2f * k)
          drawArc(
            color = color,
            startAngle = 225f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w / 2f - r, h * 0.9f - r),
            size = Size(r * 2f, r * 2f),
            style = st,
          )
        }
        drawCircle(color = color, radius = w * 0.08f, center = Offset(w / 2f, h * 0.9f))
      }
      "bt" -> {
        val p =
          Path().apply {
            moveTo(w * 0.3f, h * 0.3f)
            lineTo(w * 0.7f, h * 0.7f)
            lineTo(w * 0.5f, h * 0.95f)
            lineTo(w * 0.5f, h * 0.05f)
            lineTo(w * 0.7f, h * 0.3f)
            lineTo(w * 0.3f, h * 0.7f)
          }
        drawPath(p, color = color, style = st)
      }
      "torch" -> {
        val p =
          Path().apply {
            moveTo(w * 0.3f, h * 0.08f)
            lineTo(w * 0.7f, h * 0.08f)
            lineTo(w * 0.6f, h * 0.45f)
            lineTo(w * 0.6f, h * 0.92f)
            lineTo(w * 0.4f, h * 0.92f)
            lineTo(w * 0.4f, h * 0.45f)
            close()
          }
        drawPath(p, color = color, style = st)
      }
      "sound" -> {
        val p =
          Path().apply {
            moveTo(w * 0.08f, h * 0.36f)
            lineTo(w * 0.3f, h * 0.36f)
            lineTo(w * 0.55f, h * 0.12f)
            lineTo(w * 0.55f, h * 0.88f)
            lineTo(w * 0.3f, h * 0.64f)
            lineTo(w * 0.08f, h * 0.64f)
            close()
          }
        drawPath(p, color = color, style = st)
        drawArc(
          color = color,
          startAngle = -50f,
          sweepAngle = 100f,
          useCenter = false,
          topLeft = Offset(w * 0.55f, h * 0.28f),
          size = Size(w * 0.4f, h * 0.44f),
          style = st,
        )
      }
      else -> {
        drawRoundRect(
          color = color,
          topLeft = Offset(w * 0.05f, h * 0.25f),
          size = Size(w * 0.8f, h * 0.5f),
          cornerRadius = CornerRadius(4f),
          style = st,
        )
        drawRect(
          color = color,
          topLeft = Offset(w * 0.88f, h * 0.4f),
          size = Size(w * 0.07f, h * 0.2f),
        )
      }
    }
  }
}

@Composable
private fun StateTile(
  modifier: Modifier,
  title: String,
  kind: String,
  on: Boolean,
  value: String,
  onClick: () -> Unit,
) {
  val accent = if (on) CYBER_CYAN else Color(0xFF7C86A2)
  val shape = RoundedCornerShape(20.dp)
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val tileScale by animateFloatAsState(
    targetValue = if (pressed) 0.96f else 1f,
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    label = "tile_scale",
  )
  Column(
    modifier
      .scale(tileScale)
      .shadow(if (on) 14.dp else 2.dp, shape, spotColor = accent)
      .background(Brush.verticalGradient(listOf(Color(0xFF17213F), Color(0xFF0B1028))), shape)
      .border(
        1.dp,
        if (on) Brush.linearGradient(listOf(CYBER_CYAN, CYBER_BLUE))
        else Brush.linearGradient(listOf(Color(0xFF3A4260), Color(0xFF3A4260))),
        shape,
      )
      .clickable(interactionSource = interaction, indication = null, onClick = onClick)
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier
          .size(46.dp)
          .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent)), CircleShape)
          .border(1.5.dp, accent, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        TileIcon(kind, accent)
      }
      Spacer(Modifier.weight(1f))
      Box(
        Modifier
          .background(
            if (on) Brush.horizontalGradient(listOf(CYBER_CYAN, CYBER_BLUE))
            else Brush.horizontalGradient(listOf(Color(0xFF2A2F3E), Color(0xFF2A2F3E))),
            RoundedCornerShape(12.dp),
          )
          .padding(horizontal = 10.dp, vertical = 4.dp)
      ) {
        Text(
          value,
          fontSize = 11.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 1.sp,
          color = if (on) Color.White else Color(0xFFB8BFD6),
        )
      }
    }
    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.3.sp)
  }
}

@Composable
fun SettingsPage(back: () -> Unit, onNavigate: (String) -> Unit) {
  val ctx = LocalContext.current
  var tick by remember { mutableIntStateOf(0) }
  var torchOn by remember { mutableStateOf(false) }
  var note by remember { mutableStateOf("") }
  var floatingOrbOn by remember { mutableStateOf(Store.floatingOrbEnabled(ctx)) }
  val health by AccessibilityStatusMonitor.healthState.collectAsState()
  val audioDevice by RiyaState.connectedAudioDevice.collectAsState()
  val btMicActive by RiyaState.isBluetoothMicActive.collectAsState()
  val netLabel by RiyaState.networkSpeedLabel.collectAsState()

  DisposableEffect(ctx) {
    val cam = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cb =
      object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
          torchOn = enabled
        }
      }
    try {
      cam.registerTorchCallback(cb, null)
    } catch (_: Exception) {}
    onDispose {
      try {
        cam.unregisterTorchCallback(cb)
      } catch (_: Exception) {}
    }
  }

  LaunchedEffect(Unit) {
    while (true) {
      delay(1500)
      tick++
    }
  }
  val wifiOn = remember(tick) { wifiState(ctx) }
  val btOn = remember(tick) { btState(ctx) }
  val mode = remember(tick) { ringerLabel(ctx) }
  val pct = remember(tick) { batteryPct(ctx) }

  Column(
    Modifier
      .fillMaxSize()
      .background(FULL_BG_GRADIENT)
      .statusBarsPadding()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SubPageTopBar(
      title = "Settings",
      icon = Icons.Filled.Tune,
      iconColors = listOf(CYBER_CYAN, CYBER_BLUE),
      onBack = back,
      onNavigate = onNavigate,
    )
    if (note.isNotEmpty()) Text(note, color = CYBER_CYAN, fontSize = 12.sp)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      StateTile(Modifier.weight(1f), "Wi-Fi", "wifi", wifiOn, if (wifiOn) "ON" else "OFF") {
        val acc = com.example.service.RiyaAccessibilityService.instance
        if (acc != null) {
          note = if (!wifiOn) "Wi-Fi চালু করা হচ্ছে..." else "Wi-Fi বন্ধ করা হচ্ছে..."
          Thread {
            acc.toggleQuickSettingTile(
              enable = !wifiOn,
              labels = listOf("wi-fi", "wifi", "wlan", "internet", "ওয়াইফাই"),
              excludeLabels = listOf("calling", "hotspot", "tethering", "scanning"),
              hardwareStateKnown = wifiOn,
            )
            tick++
          }.start()
        } else {
          openIntent(ctx, Intent(Settings.ACTION_WIFI_SETTINGS))
          note = "Wi-Fi সেটিংস খোলা হয়েছে"
        }
      }
      StateTile(Modifier.weight(1f), "Bluetooth", "bt", btOn, if (btOn) "ON" else "OFF") {
        val acc = com.example.service.RiyaAccessibilityService.instance
        if (acc != null) {
          note = if (!btOn) "Bluetooth চালু করা হচ্ছে..." else "Bluetooth বন্ধ করা হচ্ছে..."
          Thread {
            acc.toggleQuickSettingTile(
              enable = !btOn,
              labels = listOf("bluetooth", "ব্লুটুথ"),
              excludeLabels = listOf("tethering", "scanning"),
              hardwareStateKnown = btOn,
            )
            tick++
          }.start()
        } else {
          openIntent(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
          note = "Bluetooth সেটিংস খোলা হয়েছে"
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      StateTile(Modifier.weight(1f), "Flashlight", "torch", torchOn, if (torchOn) "ON" else "OFF") {
        val next = !torchOn
        torchOn = next
        note = setTorch(ctx, next)
      }
      StateTile(Modifier.weight(1f), "Sound mode", "sound", mode == "NORMAL", mode) {
        val next = when (mode) { "NORMAL" -> "vibrate"; "VIBRATE" -> "silent"; else -> "normal" }
        note = setRinger(ctx, next)
      }
    }
    StateTile(Modifier.fillMaxWidth(), "Battery", "battery", true, if (pct >= 0) "$pct%" else "N/A") {}

    // LxR 50%-Opacity Rotating Planet Wake Orb ON/OFF + quick test card
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .testTag("lxr_wake_orb_card")
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(
            1.dp,
            if (floatingOrbOn) CYBER_AMBER.copy(alpha = 0.55f) else Color(0xFF3A4260),
            RoundedCornerShape(18.dp),
          )
          .clickable {
            if (!floatingOrbOn) {
              note = "আগে ডানের সুইচ চালু করে ভাসমান অরব ON করুন।"
            } else if (com.example.service.LxrFloatingOverlayManager.isShowing) {
              com.example.service.LxrFloatingOverlayManager.hide(ctx)
              note = "LxR ভাসমান অরব লুকানো হয়েছে — 'LxR' বললেই আবার ভেসে উঠবে"
            } else {
              val shown = com.example.service.LxrFloatingOverlayManager.show(ctx, openPermissionIfMissing = true)
              note =
                if (shown) "LxR ৫০% স্বচ্ছ গোলাকার অরব প্রিভিউ চালু ✓"
                else "অন্য অ্যাপের ওপর দেখাতে 'Display over apps' অনুমতি দিন"
            }
          }
          .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PremiumIconBadge(Icons.Filled.Layers, "LxR Wake Orb", listOf(CYBER_AMBER, CYBER_RED), sizeDp = 40)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text("LxR ভাসমান ঘূর্ণায়মান অরব (৫০% স্বচ্ছ)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
          if (floatingOrbOn) "'LxR' ডাকলে বা মাইকে ক্লিক করলে ভাসবে, কাজ শেষে চলে যাবে"
          else "ভাসমান অরব বন্ধ আছে (ব্যাকগ্রাউন্ড সচল থাকবে)",
          color = if (floatingOrbOn) CYBER_AMBER else Color(0xFFAAB4D0),
          fontSize = 11.5.sp,
        )
      }
      Spacer(Modifier.width(8.dp))
      Switch(
        checked = floatingOrbOn,
        onCheckedChange = { enabled ->
          floatingOrbOn = enabled
          Store.setFloatingOrbEnabled(ctx, enabled)
          if (!enabled) {
            com.example.service.LxrFloatingOverlayManager.hide(ctx)
            note = "ভাসমান অরব বন্ধ (OFF) করা হয়েছে ✓"
          } else {
            note = "ভাসমান অরব চালু (ON) করা হয়েছে — 'LxR' ডাকলেই দেখাবে ✓"
          }
        },
        modifier = Modifier.testTag("floating_orb_toggle"),
        colors = SwitchDefaults.colors(checkedTrackColor = CYBER_AMBER, checkedThumbColor = Color.White),
      )
    }

    // Smartphone Screen Lock & App Lock Vault Card
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .testTag("settings_lock_vault_card")
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(1.dp, CYBER_PURPLE.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
          .clickable { onNavigate("lockvault") }
          .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PremiumIconBadge(Icons.Filled.Lock, "Lock Vault", listOf(CYBER_RED, CYBER_PURPLE), sizeDp = 40)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text("ফোন ও অ্যাপ লক (PIN / Pattern ভল্ট)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
          if (Store.hasAnyPhoneLockSaved(ctx) || Store.hasAnyAppLockSaved(ctx))
            "লক সংরক্ষিত ✓ — লক থাকা অবস্থায় কাজ করে পুনরায় লক করবে"
          else "৮ ডিজিট PIN বা প্যাটার্ন লক সেভ করতে ট্যাপ করুন",
          color = CYBER_CYAN,
          fontSize = 11.5.sp,
        )
      }
    }

    // Live LxR Bluetooth & Low-Network Status Card
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(1.dp, (if (btMicActive) CYBER_GREEN else CYBER_CYAN).copy(alpha = 0.45f), RoundedCornerShape(18.dp))
          .clickable { onNavigate("key") }
          .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PremiumIconBadge(
        Icons.Filled.Bluetooth,
        "Audio & Network",
        if (btMicActive) listOf(CYBER_GREEN, CYBER_CYAN) else listOf(CYBER_BLUE, CYBER_CYAN),
        sizeDp = 40,
      )
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text("অডিও: $audioDevice", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("নেটওয়ার্ক: $netLabel • ভয়েস ও বুস্ট পরিবর্তন করতে ট্যাপ করুন", color = CYBER_CYAN, fontSize = 11.5.sp)
      }
    }

    // Live Accessibility Guard Card inside Settings so Boss always sees real status
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(
            1.dp,
            if (health.isAccessibilityTrulyActive) CYBER_GREEN.copy(alpha = 0.5f)
            else CYBER_RED.copy(alpha = 0.75f),
            RoundedCornerShape(18.dp),
          )
          .clickable { if (!Keep.ensureAccessibility(ctx)) Keep.openAccessibility(ctx) }
          .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PremiumIconBadge(
        Icons.Filled.AccessibilityNew,
        "Accessibility",
        if (health.isAccessibilityTrulyActive) listOf(CYBER_GREEN, CYBER_CYAN)
        else listOf(CYBER_RED, CYBER_AMBER),
        sizeDp = 40,
      )
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(
          if (health.isAccessibilityTrulyActive) "Accessibility: সক্রিয় ✓"
          else "⚠ Accessibility: বন্ধ আছে!",
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 15.sp,
        )
        Text(
          if (health.isAccessibilityTrulyActive) "পূর্ণ স্ক্রিন রিডিং ও দ্রুত টাস্ক চালু"
          else "ট্যাপ করে এখনই LXR--AI Accessibility চালু করুন",
          color = if (health.isAccessibilityTrulyActive) CYBER_GREEN else CYBER_RED,
          fontSize = 12.sp,
        )
      }
    }
  }
}
