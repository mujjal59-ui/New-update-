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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ai.configureAppCheck
import com.example.data.HomeTheme
import com.example.data.RiyaPreferences
import com.example.data.RiyaRepository
import com.example.data.RiyaSettings
import com.example.data.RiyaState
import com.example.data.Store
import com.example.service.AccessibilityStatusMonitor
import com.example.service.IncomingReplyStatus
import com.example.service.Keep
import com.example.service.LxrFloatingOverlayManager
import com.example.service.ReplyPolicyScheduler
import com.example.service.RiyaNotificationHelper
import com.example.service.RiyaNotificationListenerService
import com.example.service.RiyaRuntimeBridge
import com.example.service.RiyaService
import com.example.service.RiyaVoiceManager
import com.example.ui.AboutPage
import com.example.ui.AppBuilderScreen
import com.example.ui.AutoReplyPage
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_CYAN
import com.example.ui.CYBER_GREEN
import com.example.ui.CYBER_PURPLE
import com.example.ui.CYBER_RED
import com.example.ui.KeyPage
import com.example.ui.LiveCamera
import com.example.ui.LockVaultPage
import com.example.ui.LxrDockAppsRow
import com.example.ui.LxrLiveAvatarOverlay
import com.example.ui.LxrMicMutePillButton
import com.example.ui.LxrThreeSectionRow
import com.example.ui.LxrTopLeftMenuButton
import com.example.ui.LxrVoiceControlStrip
import com.example.ui.LxrVoiceModelPopupDialog
import com.example.ui.LxrVoiceModelTreeButton
import com.example.ui.MemPage
import com.example.ui.PermPage
import com.example.ui.PlaybookPage
import com.example.ui.RiyaChatDialog
import com.example.ui.RiyaThreeLineMenuDialog
import com.example.ui.SeoStudioScreen
import com.example.ui.SettingsPage
import com.example.ui.TasksPage
import com.example.ui.VideoCreatorScreen
import com.example.ui.hasPerm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    com.example.service.LockVaultManager.markHomeOrAppExitTransition()
  }

  override fun onPause() {
    com.example.service.LockVaultManager.markHomeOrAppExitTransition()
    super.onPause()
  }

  override fun onStop() {
    com.example.service.LockVaultManager.markHomeOrAppExitTransition()
    super.onStop()
  }

  override fun onResume() {
    super.onResume()
    RiyaState.resume.value++
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
      try {
        Keep.ensureAccessibility(applicationContext)
      } catch (_: Exception) {}
      AccessibilityStatusMonitor.refreshNow(applicationContext, speakIfTurnedOff = true)
      com.example.service.AccessibilityKeepAliveService.sync(applicationContext)
    }
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
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
              AccessibilityStatusMonitor.refreshNow(applicationContext, speakIfTurnedOff = true)
            }
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
    if (isFinishing &&
      !Keep.isEnabledInSettingsOrAm(this) &&
      com.example.service.RiyaAccessibilityService.instance == null &&
      !RiyaState.running.value
    ) {
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
  val prefsSettings by prefs.settingsFlow.collectAsState(initial = null)
  var arWa by remember { mutableStateOf(Store.autoReplyWhatsApp(ctx)) }
  var arImo by remember { mutableStateOf(Store.autoReplyImo(ctx)) }
  var arMsg by remember { mutableStateOf(Store.autoReplyMessenger(ctx)) }
  LaunchedEffect(prefsSettings) {
    prefsSettings?.let { s ->
      arWa = Store.autoReplyWhatsApp(ctx) && s.whatsAppAutoReply
      arImo = Store.autoReplyImo(ctx) && s.imoAutoReply
      arMsg = Store.autoReplyMessenger(ctx) && s.messengerAutoReply
    }
  }
  val camOn by RiyaState.visionCameraActive.collectAsState()

  LaunchedEffect(Unit) {
    RiyaState.homeThemeIndex.value = Store.design(ctx)
    RiyaState.liveModelId.value = Store.model(ctx)
    RiyaState.replyDelaySeconds.value = Store.replyDelaySeconds(ctx)
    RiyaState.replyLimitMax.value = Store.replyLimitMax(ctx)
    ReplyPolicyScheduler.syncFromStore(ctx)
  }

  val running by RiyaState.running.collectAsState()
  val status by RiyaState.status.collectAsState()
  val health by AccessibilityStatusMonitor.healthState.collectAsState()

  val micLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) startRiya(ctx)
    }
  val camPerm =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        RiyaState.setVisionCameraActive(true)
        RiyaService.syncCameraState(ctx)
      }
    }

  LaunchedEffect(running) {
    if (!running) {
      RiyaState.setVisionCameraActive(false)
      com.example.service.LxrCameraVisionEngine.stopAll()
    }
  }

  val requestedPage by RiyaRuntimeBridge.requestedUiPage.collectAsState()
  LaunchedEffect(requestedPage) {
    requestedPage?.let { target ->
      page = target
      RiyaRuntimeBridge.requestUiPage(null)
    }
  }

  // Continuous background check off Main Thread so Binder IPC never causes UI ANRs
  LaunchedEffect(Unit) {
    while (true) {
      withContext(Dispatchers.IO) {
        AccessibilityStatusMonitor.refreshNow(ctx, speakIfTurnedOff = true)
      }
      delay(3500)
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
  BackHandler(page == "home") {
    // Move task to background cleanly instead of finishing Activity so Infinix XOS never drops Accessibility
    com.example.service.LockVaultManager.markHomeOrAppExitTransition()
    (ctx as? android.app.Activity)?.moveTaskToBack(true)
  }

  AnimatedContent(
    targetState = page,
    transitionSpec = {
      if (targetState == "home") {
        (slideInHorizontally(tween(220)) { -it / 5 } + fadeIn(tween(200))) togetherWith
          (slideOutHorizontally(tween(200)) { it / 5 } + fadeOut(tween(180)))
      } else {
        (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(200))) togetherWith
          (slideOutHorizontally(tween(200)) { -it / 5 } + fadeOut(tween(180)))
      }
    },
    label = "lxr_page_nav",
  ) { currentPage ->
    when (currentPage) {
      "perm" -> PermPage(back = { page = "home" }, onNavigate = { page = it })
      "key" -> KeyPage(back = { page = "home" }, onNavigate = { page = it })
      "mem" -> MemPage(back = { page = "home" }, onNavigate = { page = it })
      "settings" -> SettingsPage(back = { page = "home" }, onNavigate = { page = it })
      "lockvault" -> LockVaultPage(back = { page = "home" }, onNavigate = { page = it })
      "tasks" -> TasksPage(back = { page = "home" }, onNavigate = { page = it })
      "autoreply" -> AutoReplyPage(back = { page = "home" }, onNavigate = { page = it })
      "playbook" -> PlaybookPage(back = { page = "home" }, onNavigate = { page = it })
      "app_builder" -> AppBuilderScreen(back = { page = "home" }, onNavigate = { page = it })
      "video_creator" -> VideoCreatorScreen(back = { page = "home" }, onNavigate = { page = it })
      "seo_studio" -> SeoStudioScreen(back = { page = "home" }, onNavigate = { page = it })
      "about" -> AboutPage(back = { page = "home" })
      else ->
        RiyaHomeScreen(
          status = status,
          running = running,
          camOn = camOn,
          accessibilityOn = health.isAccessibilityTrulyActive,
          accessibilityWaitingRebind =
            health.isAccessibilityEnabledInSettings && !health.isAccessibilityTrulyActive,
          notifListenerOn = health.isNotificationListenerActive,
          onMenu = { menu = true },
          onMic = { toggleMic() },
          onCam = {
            if (!running) {
              Toast.makeText(ctx, "আগে মাইক অন করে LXR--AI চালু করুন", Toast.LENGTH_SHORT).show()
            } else if (camOn) {
              RiyaState.setVisionCameraActive(false)
              RiyaService.syncCameraState(ctx)
              RiyaState.live?.notifyCameraActive(false, isFrontCamera = RiyaState.useFrontCamera.value)
            } else if (hasPerm(ctx, Manifest.permission.CAMERA)) {
              RiyaState.setVisionCameraActive(true)
              RiyaService.syncCameraState(ctx)
            } else {
              camPerm.launch(Manifest.permission.CAMERA)
            }
          },
          onChat = { chat = true },
          onSettings = { page = "settings" },
          onAppBuilder = { page = "app_builder" },
          onVideoCreator = { page = "video_creator" },
          onSeoStudio = { page = "seo_studio" },
          onFixAccessibility = {
            if (!Keep.ensureAccessibility(ctx)) {
              Keep.openAccessibility(ctx)
            }
          },
          onFixNotifAccess = { Keep.openNotificationAccess(ctx) },
        )
    }
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
fun RiyaHomeScreen(
  status: String,
  running: Boolean,
  camOn: Boolean,
  accessibilityOn: Boolean,
  accessibilityWaitingRebind: Boolean = false,
  notifListenerOn: Boolean = true,
  onMenu: () -> Unit,
  onMic: () -> Unit,
  onCam: () -> Unit,
  onChat: () -> Unit,
  onSettings: () -> Unit,
  onAppBuilder: () -> Unit = {},
  onVideoCreator: () -> Unit = {},
  onSeoStudio: () -> Unit = {},
  onFixAccessibility: () -> Unit,
  onFixNotifAccess: () -> Unit = {},
) {
  val ctx = LocalContext.current
  val pendingMessages by RiyaRuntimeBridge.pendingMessages.collectAsState()
  val pendingIncoming =
    pendingMessages.firstOrNull { it.status == IncomingReplyStatus.PENDING_PERMISSION }
  val motivationBanner by RiyaState.motivationalBanner.collectAsState()
  val audioDevice by RiyaState.connectedAudioDevice.collectAsState()
  val btMicActive by RiyaState.isBluetoothMicActive.collectAsState()
  val netLabel by RiyaState.networkSpeedLabel.collectAsState()
  val voiceMatchLabel by RiyaState.voiceMatchLiveLabel.collectAsState()
  val isSpeaking by RiyaVoiceManager.isSpeaking.collectAsState()
  val themeIdx by RiyaState.homeThemeIndex.collectAsState()
  val activeTheme = remember(themeIdx) { HomeTheme.fromId(themeIdx) }
  val liveModelId by RiyaState.liveModelId.collectAsState()
  val activeWorkLabel by RiyaState.activeWorkLabel.collectAsState()
  val micMuted by RiyaState.micMuted.collectAsState()
  var showVoiceModelDialog by remember { mutableStateOf(false) }

  BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF03060F))) {
    val iw = 691f
    val ih = 1536f
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

    val density = LocalDensity.current
    val statusTopDp = with(density) { WindowInsets.statusBars.getTop(density).toDp().value }
    val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp().value }

    val safeBottomDp = sh - navBottomDp - 6f
    val cardHeightDp = (sh * 0.088f).coerceIn(66f, 84f)
    val totalSectionHeightDp = cardHeightDp + 18f
    val sectionTopY =
      minOf(oy + 0.850f * dh, safeBottomDp - totalSectionHeightDp)
        .coerceAtLeast(oy + 0.844f * dh)

    val dockApproxHeight = 68f
    val dockTopY =
      minOf(oy + 0.742f * dh, sectionTopY - dockApproxHeight - 8f)
        .coerceAtLeast(0f)

    val voiceStripHeight = 82f
    val voiceStripTopY =
      minOf(oy + 0.695f * dh - voiceStripHeight / 2f, dockTopY - voiceStripHeight)
        .coerceAtLeast(0f)

    val bgRes =
      when (activeTheme) {
        HomeTheme.LXR_STUDIO -> R.drawable.home_bg
        HomeTheme.ARIA_CYBER_ROOM,
        HomeTheme.MITALI_CYBER_STUDIO -> R.drawable.bg_aria
      }

    Image(
      painter = painterResource(bgRes),
      contentDescription = "LXR--AI home",
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
    )

    // Separated multi-layer 60fps animations: Studio background, locked portrait eyes, petals & animated characters
    LxrLiveAvatarOverlay(
      ox = ox,
      oy = oy,
      dw = dw,
      dh = dh,
      isRunning = running,
      isSpeaking = isSpeaking,
      homeTheme = activeTheme,
      px = ::px,
      py = ::py,
    )

    // Top-left Menu button: hotspot on LXR_STUDIO (matching home_bg artwork) with soft neon pulse, styled orb at identical spot on Aria/Mitali themes
    if (activeTheme == HomeTheme.LXR_STUDIO) {
      Box(
        modifier =
          Modifier
            .offset(x = px(0.098f - 0.072f), y = py(0.172f - 0.036f))
            .size(width = maxOf(pw(0.144f), 48.dp), height = maxOf(ph(0.072f), 48.dp))
            .testTag("menu_button")
            .clickable(onClick = onMenu)
      )
    } else {
      val menuLeftX = (ox + 0.098f * dw - 24f).coerceAtLeast(14f)
      val menuTopY = maxOf(oy + 0.172f * dh - 24f, statusTopDp + 6f)
      LxrTopLeftMenuButton(
        onClick = onMenu,
        modifier = Modifier.align(Alignment.TopStart).offset(x = menuLeftX.dp, y = menuTopY.dp),
      )
    }

    // Animated Voice Model selector button right in front of the upper-right potted plant on all 3 themes
    val vmLeftX = minOf(ox + 0.885f * dw - 24f, sw - 58f).coerceAtLeast(16f)
    val vmTopY = maxOf(oy + 0.272f * dh - 24f, statusTopDp + 48f)
    LxrVoiceModelTreeButton(
      activeModelId = liveModelId,
      onClick = { showVoiceModelDialog = true },
      modifier = Modifier.align(Alignment.TopStart).offset(x = vmLeftX.dp, y = vmTopY.dp),
    )

    val isCharacterTheme =
      activeTheme == HomeTheme.ARIA_CYBER_ROOM || activeTheme == HomeTheme.MITALI_CYBER_STUDIO

    // Compact center Status Pill strictly above hair/head (never covering Aria/Mitali face!)
    val statusPillTopY =
      if (isCharacterTheme) maxOf(oy + 0.128f * dh, statusTopDp + 4f)
      else maxOf(oy + 0.168f * dh, statusTopDp + 10f)

    val shownStatusText = activeWorkLabel?.takeIf { it.isNotBlank() } ?: status
    val isDoingWorkNow = !activeWorkLabel.isNullOrBlank()

    Column(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .offset(y = statusPillTopY.dp)
          .widthIn(max = if (isCharacterTheme) 230.dp else 185.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(if (isCharacterTheme) 3.dp else 4.dp),
    ) {
      if (isCharacterTheme && (running || btMicActive)) {
        val compactDevice = if (btMicActive) "BT" else "Mic"
        val compactNet = if (netLabel.contains("Turbo", true) || netLabel.contains("Low", true)) "Fast" else netLabel
        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = shownStatusText,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isDoingWorkNow) CYBER_CYAN else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier
                .testTag("home_status_pill")
                .background(if (isDoingWorkNow) Color(0xE806142E) else Color(0xD0050B1E), RoundedCornerShape(16.dp))
                .border(
                  if (isDoingWorkNow) 1.2.dp else 1.dp,
                  CYBER_CYAN.copy(alpha = if (isDoingWorkNow) 0.95f else 0.65f),
                  RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 9.dp, vertical = 2.dp),
          )
          Text(
            text = "$compactDevice • $compactNet",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (btMicActive) CYBER_GREEN else CYBER_CYAN,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier
                .background(Color(0xCC050B1E), RoundedCornerShape(14.dp))
                .border(
                  0.9.dp,
                  (if (btMicActive) CYBER_GREEN else CYBER_CYAN).copy(alpha = 0.50f),
                  RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 8.dp, vertical = 2.dp),
          )
        }
      } else {
        Text(
          text = shownStatusText,
          fontSize = if (isCharacterTheme) 11.sp else 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = if (isDoingWorkNow) CYBER_CYAN else Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier =
            Modifier
              .testTag("home_status_pill")
              .background(if (isDoingWorkNow) Color(0xE806142E) else Color(0xCC050B1E), RoundedCornerShape(20.dp))
              .border(
                if (isDoingWorkNow) 1.2.dp else 1.dp,
                CYBER_CYAN.copy(alpha = if (isDoingWorkNow) 0.95f else 0.65f),
                RoundedCornerShape(20.dp),
              )
              .padding(horizontal = 12.dp, vertical = if (isCharacterTheme) 2.dp else 3.dp),
        )

        if (running || btMicActive) {
          val compactDevice = if (btMicActive) "BT Mic" else "Direct"
          val compactNet = if (netLabel.contains("Turbo", true) || netLabel.contains("Low", true)) "Fast Net" else netLabel
          Text(
            text = "$compactDevice • $compactNet",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (btMicActive) CYBER_GREEN else CYBER_CYAN,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier
                .background(Color(0xBB050B1E), RoundedCornerShape(14.dp))
                .border(
                  0.9.dp,
                  (if (btMicActive) CYBER_GREEN else CYBER_CYAN).copy(alpha = 0.45f),
                  RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 9.dp, vertical = 2.dp),
          )
        }
      }

      if (running && voiceMatchLabel.isNotBlank()) {
        val avoided = voiceMatchLabel.contains("এভোয়েড")
        Text(
          text = voiceMatchLabel,
          fontSize = if (isCharacterTheme) 9.5.sp else 10.5.sp,
          fontWeight = FontWeight.SemiBold,
          color = if (avoided) Color(0xFFFFB300) else CYBER_GREEN,
          modifier =
            Modifier
              .testTag("home_voice_match_pill")
              .background(Color(0xCC050B1E), RoundedCornerShape(16.dp))
              .border(
                1.dp,
                (if (avoided) Color(0xFFFFB300) else CYBER_GREEN).copy(alpha = 0.6f),
                RoundedCornerShape(16.dp),
              )
              .padding(horizontal = 9.dp, vertical = 2.dp),
        )
      }

      if (!isCharacterTheme) {
        if (!accessibilityOn) {
          val label =
            if (accessibilityWaitingRebind) "Accessibility চালু আছে — রিকানেক্ট করতে ট্যাপ করুন"
            else "Accessibility বন্ধ! চালু করতে ট্যাপ করুন"
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
              text = label,
              color = Color.White,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }

        if (!notifListenerOn &&
          (Store.autoReplyWhatsApp(ctx) || Store.autoReplyImo(ctx) || Store.autoReplyMessenger(ctx))
        ) {
          Row(
            modifier =
              Modifier
                .testTag("notification_access_warning_pill")
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE62E1904))
                .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(18.dp))
                .clickable(onClick = onFixNotifAccess)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.Warning,
              contentDescription = "Notification Access Off",
              tint = Color(0xFFFFB300),
              modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = "মেসেজ শুনতে Notification Access চালু করুন",
              color = Color.White,
              fontSize = 10.5.sp,
              fontWeight = FontWeight.SemiBold,
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
    }

    // On Aria / Mitali themes, keep any alert/motivation pills neatly above Voice Strip so faces stay 100% clear
    if (isCharacterTheme &&
      (!accessibilityOn ||
        (!notifListenerOn && (Store.autoReplyWhatsApp(ctx) || Store.autoReplyImo(ctx) || Store.autoReplyMessenger(ctx))) ||
        !motivationBanner.isNullOrBlank())
    ) {
      Column(
        modifier =
          Modifier
            .align(Alignment.TopCenter)
            .offset(y = (voiceStripTopY - 102f).coerceAtLeast(160f).dp)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (!accessibilityOn) {
          val label =
            if (accessibilityWaitingRebind) "Accessibility চালু আছে — রিকানেক্ট করতে ট্যাপ করুন"
            else "Accessibility বন্ধ! চালু করতে ট্যাপ করুন"
          Row(
            modifier =
              Modifier
                .testTag("accessibility_off_warning_pill")
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE6380619))
                .border(1.dp, CYBER_RED, RoundedCornerShape(18.dp))
                .clickable(onClick = onFixAccessibility)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Filled.Warning, contentDescription = "Accessibility Off", tint = CYBER_RED, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = label, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
          }
        }
        if (!notifListenerOn &&
          (Store.autoReplyWhatsApp(ctx) || Store.autoReplyImo(ctx) || Store.autoReplyMessenger(ctx))
        ) {
          Row(
            modifier =
              Modifier
                .testTag("notification_access_warning_pill")
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE62E1904))
                .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(18.dp))
                .clickable(onClick = onFixNotifAccess)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Filled.Warning, contentDescription = "Notification Access Off", tint = Color(0xFFFFB300), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = "মেসেজ শুনতে Notification Access চালু করুন", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
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
            Icon(Icons.Filled.CheckCircle, contentDescription = "Task Done", tint = CYBER_GREEN, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = motivationBanner.orEmpty(), color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
          }
        }
      }
    }

    // Floating Boss-Permission Prompt Card when a WhatsApp/imo/Messenger message arrives
    if (pendingIncoming != null) {
      Column(
        modifier =
          Modifier
            .align(Alignment.TopCenter)
            .padding(horizontal = 18.dp)
            .offset(y = (voiceStripTopY - 184f).coerceAtLeast(145f).dp)
            .widthIn(max = 560.dp)
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
          val scope = rememberCoroutineScope()
          Button(
            onClick = {
              scope.launch {
                ReplyPolicyScheduler.syncFromStore(ctx)
                val limitMax = Store.replyLimitMax(ctx)
                if (!ReplyPolicyScheduler.canSendReply(limitMax)) {
                  Toast.makeText(
                      ctx,
                      "Reply Limit পূর্ণ ($limitMax/$limitMax) — Reset করুন।",
                      Toast.LENGTH_SHORT,
                    )
                    .show()
                  return@launch
                }
                val ok =
                  withContext(Dispatchers.IO) {
                    val sent =
                      RiyaNotificationListenerService.sendApprovedReply(
                        ctx,
                        pendingIncoming.eventKey,
                        pendingIncoming.suggestedReply,
                      )
                    if (sent) {
                      ReplyPolicyScheduler.tryConsumeAndPersist(ctx, limitMax)
                    }
                    RiyaRepository(ctx.applicationContext).updateAutoReplyStatus(
                      pendingIncoming.eventKey,
                      if (sent) "SENT_APPROVED" else "FAILED",
                      pendingIncoming.suggestedReply,
                    )
                    sent
                  }
                Toast.makeText(
                    ctx,
                    if (ok) "রিপ্লাই পাঠানো হয়েছে ✓" else "পাঠানো যায়নি — Accessibility পরীক্ষা করুন",
                    Toast.LENGTH_SHORT,
                  )
                  .show()
              }
            },
            modifier = Modifier.weight(1f).height(38.dp).testTag("home_approve_reply_button"),
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
              scope.launch(Dispatchers.IO) {
                RiyaRepository(ctx.applicationContext)
                  .updateAutoReplyStatus(pendingIncoming.eventKey, "DISMISSED", "")
              }
            },
            modifier = Modifier.height(38.dp).testTag("home_dismiss_reply_button"),
          ) {
            Text("বাতিল", color = Color.White, fontSize = 11.sp)
          }
        }
      }
    }

    if (camOn) {
      val useFrontCam by RiyaState.useFrontCamera.collectAsState()
      Box(
        Modifier
          .align(Alignment.TopStart)
          .offset(x = px(0.032f), y = py(0.285f))
          .size(width = pw(0.325f), height = ph(0.245f))
          .clip(RoundedCornerShape(16.dp))
          .border(2.dp, CYBER_CYAN, RoundedCornerShape(16.dp))
      ) {
        LiveCamera(Modifier.fillMaxSize())
        Text(
          text = if (useFrontCam) "FRONT CAM" else "LIVE VISION",
          color = Color.Black,
          fontSize = 9.sp,
          fontWeight = FontWeight.ExtraBold,
          modifier =
            Modifier
              .align(Alignment.TopStart)
              .padding(6.dp)
              .background(CYBER_CYAN, RoundedCornerShape(8.dp))
              .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        IconButton(
          onClick = { RiyaState.useFrontCamera.value = !useFrontCam },
          modifier =
            Modifier
              .align(Alignment.TopEnd)
              .size(48.dp)
              .padding(4.dp)
              .testTag("camera_flip_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Cameraswitch,
            contentDescription = if (useFrontCam) "Switch to back camera" else "Switch to front camera",
            tint = Color.White,
            modifier =
              Modifier
                .size(26.dp)
                .background(Color(0x99081024), CircleShape)
                .padding(4.dp),
          )
        }
      }
    }

    if (activeTheme == HomeTheme.LXR_STUDIO) {
      // Subtle animated cyber glow overlay around LXR_STUDIO hotspots so all buttons pulse across all 3 themes
      val homeBtnInf = rememberInfiniteTransition(label = "lxr_theme1_btn_pulse")
      val glowAlpha by homeBtnInf.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.56f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "theme1_glow",
      )
      Canvas(modifier = Modifier.fillMaxSize()) {
        val densityPx = this.density
        val oxPx = ox * densityPx
        val oyPx = oy * densityPx
        val dwPx = dw * densityPx
        val dhPx = dh * densityPx

        // Menu orb subtle cyan-purple ring
        drawCircle(
          color = CYBER_CYAN.copy(alpha = glowAlpha * 0.48f),
          radius = 25f * densityPx,
          center = Offset(oxPx + 0.098f * dwPx, oyPx + 0.172f * dhPx),
          style = Stroke(width = 1.6f * densityPx),
        )
        // Center Mic pulsing neon halo
        drawCircle(
          color = (if (running) CYBER_GREEN else CYBER_CYAN).copy(alpha = glowAlpha * 0.58f),
          radius = (34f + glowAlpha * 5f) * densityPx,
          center = Offset(oxPx + 0.498f * dwPx, oyPx + 0.695f * dhPx),
          style = Stroke(width = 2.0f * densityPx),
        )
        // Camera & Chat octagonal capsule pulse
        val pillW = 56f * densityPx
        val pillH = 32f * densityPx
        drawRoundRect(
          color = CYBER_CYAN.copy(alpha = glowAlpha * 0.45f),
          topLeft = Offset(oxPx + 0.253f * dwPx - pillW / 2f, oyPx + 0.695f * dhPx - pillH / 2f),
          size = Size(pillW, pillH),
          cornerRadius = CornerRadius(12f * densityPx, 12f * densityPx),
          style = Stroke(width = 1.5f * densityPx),
        )
        drawRoundRect(
          color = CYBER_CYAN.copy(alpha = glowAlpha * 0.45f),
          topLeft = Offset(oxPx + 0.742f * dwPx - pillW / 2f, oyPx + 0.695f * dhPx - pillH / 2f),
          size = Size(pillW, pillH),
          cornerRadius = CornerRadius(12f * densityPx, 12f * densityPx),
          style = Stroke(width = 1.5f * densityPx),
        )
        // 6 Dock items subtle neon breathing outline
        val dockCenters = listOf(0.120f, 0.272f, 0.424f, 0.576f, 0.728f, 0.880f)
        val tileW = 46f * densityPx
        val tileH = 44f * densityPx
        dockCenters.forEachIndexed { idx, cxFrac ->
          val itemAlpha = if (idx % 2 == 0) glowAlpha * 0.34f else (0.72f - glowAlpha) * 0.34f
          drawRoundRect(
            color = (if (idx % 2 == 0) CYBER_CYAN else CYBER_PURPLE).copy(alpha = itemAlpha.coerceIn(0.08f, 0.42f)),
            topLeft = Offset(oxPx + cxFrac * dwPx - tileW / 2f, oyPx + 0.782f * dhPx - tileH / 2f),
            size = Size(tileW, tileH),
            cornerRadius = CornerRadius(11f * densityPx, 11f * densityPx),
            style = Stroke(width = 1.2f * densityPx),
          )
        }
      }

      // Exact LXR_AI_with_menu_button hotspots aligned pixel-for-pixel over home_bg.png artwork (v2.22.0 style)
      Box(
        modifier =
          Modifier
            .offset(x = px(0.253f - 0.085f), y = py(0.695f - 0.028f))
            .size(width = maxOf(pw(0.170f), 48.dp), height = maxOf(ph(0.056f), 48.dp))
            .testTag("cam_button")
            .clickable(onClick = onCam)
      )
      Box(
        modifier =
          Modifier
            .offset(x = px(0.498f - 0.105f), y = py(0.695f - 0.044f))
            .size(width = maxOf(pw(0.210f), 48.dp), height = maxOf(ph(0.088f), 48.dp))
            .testTag("mic_button")
            .clickable(onClick = onMic)
      )
      Box(
        modifier =
          Modifier
            .offset(x = px(0.742f - 0.085f), y = py(0.695f - 0.028f))
            .size(width = maxOf(pw(0.170f), 48.dp), height = maxOf(ph(0.056f), 48.dp))
            .testTag("chat_button")
            .clickable(onClick = onChat)
      )

      val dockHotspots =
        listOf(
          Triple("dock_whatsapp", 0.120f) { launchPkg(ctx, "com.whatsapp", "com.whatsapp.w4b") },
          Triple("dock_imo", 0.272f) {
            launchPkg(ctx, "com.imo.android.imoim", "com.imo.android.imoimhd", "com.imo.android.imoimbeta")
          },
          Triple("dock_messenger", 0.424f) { launchPkg(ctx, "com.facebook.orca", "com.facebook.mlite") },
          Triple("dock_youtube", 0.576f) { launchPkg(ctx, "com.google.android.youtube") },
          Triple("dock_contacts", 0.728f) {
            ctx.startActivity(
              Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          },
          Triple("dock_settings", 0.880f, onSettings),
        )
      dockHotspots.forEach { (tag, cx, action) ->
        Box(
          modifier =
            Modifier
              .offset(x = px(cx - 0.072f), y = py(0.782f - 0.040f))
              .size(width = maxOf(pw(0.144f), 48.dp), height = maxOf(ph(0.080f), 48.dp))
              .testTag(tag)
              .clickable(onClick = action)
        )
      }
    } else {
      // 1. Animated Voice Control Strip for Aria / Mitali themes
      Box(
        modifier = Modifier.fillMaxWidth().offset(y = voiceStripTopY.dp),
        contentAlignment = Alignment.TopCenter,
      ) {
        LxrVoiceControlStrip(
          isRunning = running,
          camOn = camOn,
          onCam = onCam,
          onMic = onMic,
          onChat = onChat,
          modifier = Modifier.widthIn(max = 600.dp),
        )
      }

      // 2. Animated 6-Icon Cyber Dock matching v2.22.0 glass style for Aria / Mitali themes
      Box(
        modifier = Modifier.fillMaxWidth().offset(y = dockTopY.dp),
        contentAlignment = Alignment.TopCenter,
      ) {
        LxrDockAppsRow(
          onWhatsApp = { launchPkg(ctx, "com.whatsapp", "com.whatsapp.w4b") },
          onImo = {
            launchPkg(ctx, "com.imo.android.imoim", "com.imo.android.imoimhd", "com.imo.android.imoimbeta")
          },
          onMessenger = { launchPkg(ctx, "com.facebook.orca", "com.facebook.mlite") },
          onYouTube = { launchPkg(ctx, "com.google.android.youtube") },
          onContacts = {
            ctx.startActivity(
              Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          },
          onSettings = onSettings,
          modifier = Modifier.widthIn(max = 600.dp),
        )
      }
    }

    // Compact animated Mute button centered directly above the Microphone orb across all 3 themes
    val muteTopY = (voiceStripTopY - 46f).coerceAtLeast(170f)
    LxrMicMutePillButton(
      isMuted = micMuted,
      homeTheme = activeTheme,
      onToggle = {
        val nextMuted = !micMuted
        val liveSession = RiyaState.live
        if (liveSession != null) {
          liveSession.setMicMuted(nextMuted)
        } else {
          RiyaState.micMuted.value = nextMuted
          if (nextMuted) {
            RiyaVoiceManager.stopSpeaking()
            if (running) RiyaState.status.value = "মাইক মিউট (Mute)"
          } else {
            if (running) RiyaState.status.value = "শুনছি বস..."
          }
        }
      },
      modifier = Modifier.align(Alignment.TopCenter).offset(y = muteTopY.dp),
    )

    // 3. Animated Three Section Cards ("অ্যাপস তৈরি", "ভিডিও তৈরি", "YouTube ভিডিও SEO") + Neon Divider
    Box(
      modifier = Modifier.fillMaxWidth().offset(y = sectionTopY.dp),
      contentAlignment = Alignment.TopCenter,
    ) {
      LxrThreeSectionRow(
        cardHeight = cardHeightDp.dp,
        isCompactWidth = sw < 365f,
        onAppBuilder = onAppBuilder,
        onVideoCreator = onVideoCreator,
        onSeoStudio = onSeoStudio,
        modifier = Modifier.widthIn(max = 600.dp),
      )
    }

    if (showVoiceModelDialog) {
      LxrVoiceModelPopupDialog(
        activeModelId = liveModelId,
        onSelectModel = { option ->
          com.example.service.LockVaultManager.markHomeOrAppExitTransition()
          Store.setModel(ctx, option.modelId)
          if (option.modelId == Store.ELEVENLABS_LIVE_MODEL_ID) {
            Store.setElevenLabsEnabled(ctx, true)
            if (Store.elevenLabsApiKey(ctx).isBlank()) {
              Toast.makeText(
                  ctx,
                  "ElevenLabs Voice সিলেক্ট হয়েছে — Menu > Key পেজে API Key সেভ করো বস!",
                  Toast.LENGTH_LONG,
                )
                .show()
            } else {
              Toast.makeText(ctx, "Voice model: ElevenLabs Voice (জীবন্ত কণ্ঠ) ✓", Toast.LENGTH_SHORT).show()
            }
          } else {
            Toast.makeText(ctx, "Voice model: ${option.title} ✓", Toast.LENGTH_SHORT).show()
          }
          RiyaState.live?.switchModelAndReconnect(option.modelId)
          showVoiceModelDialog = false
        },
        onDismiss = { showVoiceModelDialog = false },
      )
    }
  }
}
