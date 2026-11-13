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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.data.RiyaState
import com.example.data.Store
import com.example.service.AccessibilityStatusMonitor
import com.example.service.Keep
import com.example.service.RiyaVoiceManager
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
          "বস, আমি Riya AI। সব সিস্টেম এবং ভয়েস স্পষ্টভাবে কাজ করছে।",
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
        "ব্যাকগ্রাউন্ডে Riya AI ও Accessibility সচল রাখতে",
        Icons.Filled.BatteryChargingFull,
        listOf(CYBER_GREEN, CYBER_BLUE),
        batOk,
        batGo,
      ),
      PermItem(
        "Autostart / Background (Infinix XOS)",
        "Infinix Android 16 যেন Riya AI বা Accessibility বন্ধ না করে",
        Icons.Filled.RocketLaunch,
        listOf(CYBER_RED, CYBER_AMBER),
        oemOk,
        oemGo,
        hint = "Infinix Phone Master-এ Riya AI এর Auto-start ও Background চালু রাখুন।",
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

@Composable
fun KeyPage(back: () -> Unit, onNavigate: (String) -> Unit) {
  val ctx = LocalContext.current
  var key by remember { mutableStateOf(Store.apiKey(ctx)) }
  var model by remember { mutableStateOf(Store.model(ctx)) }
  var voice by remember { mutableStateOf(Store.voice(ctx)) }
  var show by remember { mutableStateOf(false) }

  val fieldColors =
    OutlinedTextFieldDefaults.colors(
      focusedTextColor = Color.White,
      unfocusedTextColor = Color.White,
      focusedBorderColor = CYBER_CYAN,
      unfocusedBorderColor = Color(0xFF475073),
      focusedLabelColor = CYBER_CYAN,
      unfocusedLabelColor = Color(0xFFAAB4D0),
    )

  Column(
    Modifier.fillMaxSize().background(FULL_BG_GRADIENT).statusBarsPadding().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageTopBar(
      title = "Gemini API Key",
      icon = Icons.Filled.Key,
      iconColors = listOf(CYBER_AMBER, CYBER_RED),
      onBack = back,
      onNavigate = onNavigate,
    )
    OutlinedTextField(
      value = key,
      onValueChange = { key = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Gemini Live API Key") },
      singleLine = true,
      colors = fieldColors,
      visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
    )
    TextButton({ show = !show }) { Text(if (show) "Hide" else "Show", color = CYBER_CYAN) }
    OutlinedTextField(
      value = model,
      onValueChange = { model = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Live Model") },
      singleLine = true,
      colors = fieldColors,
    )
    OutlinedTextField(
      value = voice,
      onValueChange = { voice = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Voice (Leda)") },
      singleLine = true,
      colors = fieldColors,
    )
    Text(
      "API Key aistudio.google.com থেকে সংগ্রহ করুন (টেক্সট চ্যাট ও অটো রিপ্লাই কী ছাড়াই কাজ করে)।",
      color = Color.LightGray,
      fontSize = 12.sp,
    )
    Button(
      onClick = {
        Store.setApiKey(ctx, key)
        Store.setModel(ctx, model)
        Store.setVoice(ctx, voice)
        Toast.makeText(ctx, "হয়েছে, বস! সেভ করা হয়েছে।", Toast.LENGTH_SHORT).show()
        back()
      },
      modifier = Modifier.fillMaxWidth(),
      colors = ButtonDefaults.buttonColors(containerColor = CYBER_RED),
    ) {
      Text("Save")
    }
  }
}

@Composable
fun MemPage(back: () -> Unit, onNavigate: (String) -> Unit) {
  val ctx = LocalContext.current
  var refresh by remember { mutableIntStateOf(0) }
  val used = remember(refresh) { Store.size(ctx) }
  val text = remember(refresh) { Store.recent(ctx, 4000) }
  Column(
    Modifier.fillMaxSize().background(FULL_BG_GRADIENT).statusBarsPadding().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageTopBar(
      title = "Memory",
      icon = Icons.Filled.Memory,
      iconColors = listOf(CYBER_BLUE, CYBER_CYAN),
      onBack = back,
      onNavigate = onNavigate,
    )
    Text("%.2f MB / 30 MB ব্যবহৃত হয়েছে".format(used / 1048576.0), color = Color.White)
    LinearProgressIndicator(
      progress = { (used.toFloat() / Store.CAP).coerceIn(0f, 1f) },
      modifier = Modifier.fillMaxWidth(),
      color = CYBER_RED,
    )
    Text("স্মৃতিসমূহ:", color = CYBER_CYAN, fontWeight = FontWeight.SemiBold)
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
      modifier = Modifier.fillMaxWidth(),
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
    val id =
      cam.cameraIdList.firstOrNull { cid ->
        cam.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
      } ?: return "এই ফোনে ফ্ল্যাশ নেই"
    cam.setTorchMode(id, on)
    if (on) "ফ্ল্যাশলাইট চালু" else "ফ্ল্যাশলাইট বন্ধ"
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
  Column(
    modifier
      .shadow(if (on) 14.dp else 2.dp, shape, spotColor = accent)
      .background(Brush.verticalGradient(listOf(Color(0xFF17213F), Color(0xFF0B1028))), shape)
      .border(
        1.dp,
        if (on) Brush.linearGradient(listOf(CYBER_CYAN, CYBER_BLUE))
        else Brush.linearGradient(listOf(Color(0xFF3A4260), Color(0xFF3A4260))),
        shape,
      )
      .clickable(onClick = onClick)
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
  val health by AccessibilityStatusMonitor.healthState.collectAsState()

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
        openIntent(ctx, Intent(Settings.Panel.ACTION_WIFI))
        note = "খোলা প্যানেলে Wi-Fi চালু/বন্ধ করুন"
      }
      StateTile(Modifier.weight(1f), "Bluetooth", "bt", btOn, if (btOn) "ON" else "OFF") {
        openIntent(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        note = "Bluetooth সেটিংসে চালু/বন্ধ করুন"
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      StateTile(Modifier.weight(1f), "Flashlight", "torch", torchOn, if (torchOn) "ON" else "OFF") {
        torchOn = !torchOn
        note = setTorch(ctx, torchOn)
      }
      StateTile(Modifier.weight(1f), "Sound mode", "sound", mode == "NORMAL", mode) {
        val next = when (mode) { "NORMAL" -> "vibrate"; "VIBRATE" -> "silent"; else -> "normal" }
        note = setRinger(ctx, next)
      }
    }
    StateTile(Modifier.fillMaxWidth(), "Battery", "battery", true, if (pct >= 0) "$pct%" else "N/A") {}

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
          .clickable { Keep.openAccessibility(ctx) }
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
          else "ট্যাপ করে এখনই Riya AI Accessibility চালু করুন",
          color = if (health.isAccessibilityTrulyActive) CYBER_GREEN else CYBER_RED,
          fontSize = 12.sp,
        )
      }
    }
  }
}
