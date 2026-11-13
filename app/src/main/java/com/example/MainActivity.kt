package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ai.configureAppCheck
import com.example.data.RiyaPreferences
import com.example.data.RiyaState
import com.example.data.Store
import com.example.service.AccessibilityStatusMonitor
import com.example.service.IncomingReplyStatus
import com.example.service.Keep
import com.example.service.RiyaNotificationHelper
import com.example.service.RiyaNotificationListenerService
import com.example.service.RiyaRuntimeBridge
import com.example.service.RiyaService
import com.example.service.RiyaVoiceManager
import com.example.ui.AutoReplyPage
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_CYAN
import com.example.ui.CYBER_GREEN
import com.example.ui.CYBER_RED
import com.example.ui.KeyPage
import com.example.ui.LiveCamera
import com.example.ui.MemPage
import com.example.ui.PermPage
import com.example.ui.PlaybookPage
import com.example.ui.RiyaChatDialog
import com.example.ui.RiyaThreeLineMenuDialog
import com.example.ui.SettingsPage
import com.example.ui.TasksPage
import com.example.ui.hasPerm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onResume() {
    super.onResume()
    RiyaState.resume.value++
    try {
      Keep.ensureAccessibility(this)
    } catch (_: Exception) {}
    AccessibilityStatusMonitor.refreshNow(this, speakIfTurnedOff = true)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Configure Firebase App Check before Compose UI or AI usage
    configureAppCheck(this, intent)

    RiyaNotificationHelper.ensureChannels(this)
    RiyaVoiceManager.initialize(this)
    AccessibilityStatusMonitor.startListening(this)

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )

    setContent {
      val lifecycleOwner = LocalLifecycleOwner.current
      DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) {
            AccessibilityStatusMonitor.refreshNow(this@MainActivity, speakIfTurnedOff = true)
          }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
      }

      MaterialTheme(
        colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)
      ) {
        RiyaApp()
      }
    }
  }

  override fun onDestroy() {
    if (isFinishing) {
      RiyaVoiceManager.shutdown()
    }
    super.onDestroy()
  }
}

private fun startRiya(c: Context) {
  if (Store.apiKey(c).isBlank()) {
    Toast.makeText(c, "প্রথমে Menu > Gemini API Key দিন (অথবা Chat বাটন ব্যবহার করুন)", Toast.LENGTH_LONG).show()
    return
  }
  ContextCompat.startForegroundService(c, Intent(c, RiyaService::class.java))
}

private fun stopRiya(c: Context) {
  c.startService(Intent(c, RiyaService::class.java).setAction("STOP"))
}

private fun launchPkg(c: Context, vararg pkgs: String) {
  for (pkg in pkgs) {
    val i = c.packageManager.getLaunchIntentForPackage(pkg)
    if (i != null) {
      c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return
    }
  }
  Toast.makeText(c, "এই অ্যাপটি ফোনে পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
}

@Composable
fun RiyaApp() {
  val ctx = LocalContext.current
  val prefs = remember { RiyaPreferences(ctx) }
  val scope = rememberCoroutineScope()

  var page by remember { mutableStateOf("home") }
  var menu by remember { mutableStateOf(false) }
  var chat by remember { mutableStateOf(false) }
  var always by remember { mutableStateOf(Store.always(ctx)) }
  var arWa by remember { mutableStateOf(Store.autoReplyWhatsApp(ctx)) }
  var arImo by remember { mutableStateOf(Store.autoReplyImo(ctx)) }
  var arMsg by remember { mutableStateOf(Store.autoReplyMessenger(ctx)) }
  var camOn by remember { mutableStateOf(false) }

  val running by RiyaState.running.collectAsState()
  val status by RiyaState.status.collectAsState()
  val health by AccessibilityStatusMonitor.healthState.collectAsState()

  val micLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) startRiya(ctx)
    }
  val camPerm =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) camOn = true
    }

  LaunchedEffect(running) { if (!running) camOn = false }
  LaunchedEffect(camOn) { RiyaState.cameraOn = camOn }

  // Continuous background check so UI NEVER falsely shows Accessibility as ON
  LaunchedEffect(Unit) {
    while (true) {
      AccessibilityStatusMonitor.refreshNow(ctx, speakIfTurnedOff = true)
      delay(2200)
    }
  }

  LaunchedEffect(Unit) {
    if (
      always &&
        !running &&
        hasPerm(ctx, Manifest.permission.RECORD_AUDIO) &&
        Store.apiKey(ctx).isNotBlank()
    ) {
      startRiya(ctx)
    }
  }

  fun toggleMic() {
    if (running) stopRiya(ctx)
    else if (!hasPerm(ctx, Manifest.permission.RECORD_AUDIO)) {
      micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    } else {
      startRiya(ctx)
    }
  }

  BackHandler(page != "home") { page = "home" }

  when (page) {
    "perm" -> PermPage(back = { page = "home" }, onNavigate = { page = it })
    "key" -> KeyPage(back = { page = "home" }, onNavigate = { page = it })
    "mem" -> MemPage(back = { page = "home" }, onNavigate = { page = it })
    "settings" -> SettingsPage(back = { page = "home" }, onNavigate = { page = it })
    "tasks" -> TasksPage(back = { page = "home" }, onNavigate = { page = it })
    "autoreply" -> AutoReplyPage(back = { page = "home" }, onNavigate = { page = it })
    "playbook" -> PlaybookPage(back = { page = "home" }, onNavigate = { page = it })
    else ->
      RiyaHomeScreen(
        status = status,
        camOn = camOn,
        accessibilityOn = health.isAccessibilityTrulyActive,
        onMenu = { menu = true },
        onMic = { toggleMic() },
        onCam = {
          if (!running) {
            Toast.makeText(ctx, "আগে মাইক অন করে রিয়াকে চালু করুন", Toast.LENGTH_SHORT).show()
          } else if (camOn) {
            camOn = false
          } else if (hasPerm(ctx, Manifest.permission.CAMERA)) {
            camOn = true
          } else {
            camPerm.launch(Manifest.permission.CAMERA)
          }
        },
        onChat = { chat = true },
        onSettings = { page = "settings" },
        onFixAccessibility = { Keep.openAccessibility(ctx) },
      )
  }

  if (menu) {
    RiyaThreeLineMenuDialog(
      health = health,
      alwaysListening = always,
      arWa = arWa,
      arImo = arImo,
      arMsg = arMsg,
      onToggleAlways = {
        always = it
        Store.setAlways(ctx, it)
      },
      onToggleWa = {
        arWa = it
        Store.setAutoReplyWhatsApp(ctx, it)
        scope.launch { prefs.updateAppAutoReply("com.whatsapp", it) }
      },
      onToggleImo = {
        arImo = it
        Store.setAutoReplyImo(ctx, it)
        scope.launch { prefs.updateAppAutoReply("com.imo.android.imoim", it) }
      },
      onToggleMsg = {
        arMsg = it
        Store.setAutoReplyMessenger(ctx, it)
        scope.launch { prefs.updateAppAutoReply("com.facebook.orca", it) }
      },
      onNavigate = { page = it },
      onDismiss = { menu = false },
    )
  }

  if (chat) {
    RiyaChatDialog(close = { chat = false })
  }
}

@Composable
private fun Hot(x: Dp, y: Dp, w: Dp, h: Dp, tag: String, onClick: () -> Unit) {
  Box(Modifier.offset(x = x, y = y).size(w, h).testTag(tag).clickable(onClick = onClick))
}

@Composable
fun RiyaHomeScreen(
  status: String,
  camOn: Boolean,
  accessibilityOn: Boolean,
  onMenu: () -> Unit,
  onMic: () -> Unit,
  onCam: () -> Unit,
  onChat: () -> Unit,
  onSettings: () -> Unit,
  onFixAccessibility: () -> Unit,
) {
  val ctx = LocalContext.current
  val pendingMessages by RiyaRuntimeBridge.pendingMessages.collectAsState()
  val pendingIncoming =
    pendingMessages.firstOrNull { it.status == IncomingReplyStatus.PENDING_PERMISSION }
  val motivationBanner by RiyaState.motivationalBanner.collectAsState()

  BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF03060F))) {
    val iw = 941f
    val ih = 2091f
    val sw = maxWidth.value
    val sh = maxHeight.value
    val scale = maxOf(sw / iw, sh / ih)
    val dw = iw * scale
    val dh = ih * scale
    val ox = (sw - dw) / 2f
    val oy = (sh - dh) / 2f

    fun px(fx: Float): Dp = (ox + fx * dw).dp
    fun py(fy: Float): Dp = (oy + fy * dh).dp
    fun pw(f: Float): Dp = (f * dw).dp
    fun ph(f: Float): Dp = (f * dh).dp
    fun fy(f: Float): Float = (f * 1672f + 209f) / 2091f
    fun fh(f: Float): Float = f * 1672f / 2091f

    @Composable
    fun hot(cx: Float, cy: Float, w: Float, h: Float, tag: String, onClick: () -> Unit) {
      val cyP = fy(cy)
      val hP = fh(h)
      Hot(px(cx - w / 2f), py(cyP - hP / 2f), pw(w), ph(hP), tag, onClick)
    }

    Image(
      painter = painterResource(R.drawable.home_bg),
      contentDescription = "Riya AI home",
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
    )

    // Top Status Pill & Live Accessibility Warning Pill (never fakes ON)
    Column(
      modifier = Modifier.align(Alignment.TopCenter).offset(y = py(fy(0.135f))),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = status,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier =
          Modifier
            .testTag("home_status_pill")
            .background(Color(0xCC050B1E), RoundedCornerShape(20.dp))
            .border(1.dp, CYBER_CYAN.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp),
      )

      if (!accessibilityOn) {
        Row(
          modifier =
            Modifier
              .testTag("accessibility_off_warning_pill")
              .clip(RoundedCornerShape(18.dp))
              .background(Color(0xE6380619))
              .border(1.dp, CYBER_RED, RoundedCornerShape(18.dp))
              .clickable(onClick = onFixAccessibility)
              .padding(horizontal = 12.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Accessibility Off",
            tint = CYBER_RED,
            modifier = Modifier.size(15.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = "Accessibility বন্ধ! চালু করতে ট্যাপ করুন",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }

      if (!motivationBanner.isNullOrBlank()) {
        Row(
          modifier =
            Modifier
              .clip(RoundedCornerShape(18.dp))
              .background(Color(0xDD06261E))
              .border(1.dp, CYBER_GREEN, RoundedCornerShape(18.dp))
              .clickable { RiyaState.motivationalBanner.value = null }
              .padding(horizontal = 12.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Task Done",
            tint = CYBER_GREEN,
            modifier = Modifier.size(14.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = motivationBanner.orEmpty(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
          )
        }
      }
    }

    // Floating Boss-Permission Prompt Card when a WhatsApp/imo/Messenger message arrives
    if (pendingIncoming != null) {
      Column(
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 18.dp)
            .offset(y = -ph(0.28f))
            .fillMaxWidth()
            .background(Color(0xE8100A24), RoundedCornerShape(18.dp))
            .border(1.5.dp, CYBER_CYAN, RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.MarkChatUnread, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(
            "${pendingIncoming.appLabel} • ${pendingIncoming.senderName}",
            color = CYBER_CYAN,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
          )
        }
        Text(
          "\"${pendingIncoming.messageText}\" — বস, রিপ্লাই দেবো?",
          color = Color.White,
          fontSize = 12.sp,
          maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              RiyaNotificationListenerService.sendApprovedReply(
                ctx,
                pendingIncoming.eventKey,
                pendingIncoming.suggestedReply,
              )
            },
            modifier = Modifier.weight(1f).height(38.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CYBER_GREEN),
          ) {
            Text("অনুমতি দিন ও পাঠান", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
          OutlinedButton(
            onClick = {
              RiyaRuntimeBridge.updateIncomingMessageStatus(
                pendingIncoming.eventKey,
                IncomingReplyStatus.DISMISSED,
              )
            },
            modifier = Modifier.height(38.dp),
          ) {
            Text("বাতিল", color = Color.White, fontSize = 11.sp)
          }
        }
      }
    }

    if (camOn) {
      LiveCamera(
        Modifier
          .align(Alignment.TopEnd)
          .offset(y = py(fy(0.20f)))
          .padding(end = 14.dp)
          .size(width = 120.dp, height = 160.dp)
          .clip(RoundedCornerShape(16.dp))
          .border(2.dp, CYBER_CYAN, RoundedCornerShape(16.dp))
      )
    }

    // Exact UI artwork hotspots matching Screenshot_20261114-011443_d15b6446.jpg
    hot(0.104f, 0.057f, 0.14f, 0.075f, "menu_button", onMenu)
    hot(0.256f, 0.811f, 0.16f, 0.06f, "cam_button", onCam)
    hot(0.5f, 0.804f, 0.22f, 0.12f, "mic_button", onMic)
    hot(0.741f, 0.811f, 0.16f, 0.06f, "chat_button", onChat)

    hot(0.123f, 0.904f, 0.14f, 0.08f, "dock_whatsapp") { launchPkg(ctx, "com.whatsapp", "com.whatsapp.w4b") }
    hot(0.275f, 0.904f, 0.14f, 0.08f, "dock_imo") {
      launchPkg(ctx, "com.imo.android.imoim", "com.imo.android.imoimhd", "com.imo.android.imoimbeta")
    }
    hot(0.427f, 0.904f, 0.14f, 0.08f, "dock_messenger") { launchPkg(ctx, "com.facebook.orca", "com.facebook.mlite") }
    hot(0.581f, 0.904f, 0.14f, 0.08f, "dock_youtube") { launchPkg(ctx, "com.google.android.youtube") }
    hot(0.735f, 0.904f, 0.14f, 0.08f, "dock_contacts") {
      ctx.startActivity(
        Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    }
    hot(0.887f, 0.904f, 0.14f, 0.08f, "dock_settings", onSettings)
  }
}
