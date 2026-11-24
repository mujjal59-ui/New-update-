package com.example.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.RiyaAiRepository
import com.example.data.ReplyDelayOption
import com.example.data.ReplyLimitOption
import com.example.data.RiyaPreferences
import com.example.data.RiyaRepository
import com.example.data.RiyaSettings
import com.example.data.RiyaState
import com.example.data.Store
import com.example.service.ImoSendPrecisionEngine
import com.example.service.InfinixDevicePlaybook
import com.example.service.Keep
import com.example.service.LxrBackgroundTaskManager
import com.example.service.LxrBackgroundTaskStatus
import com.example.service.ReplyPolicyScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.service.RiyaAccessibilityService
import com.example.service.RiyaNotificationHelper
import com.example.service.RiyaNotificationListenerService
import com.example.service.RiyaRuntimeBridge
import com.example.service.RiyaVoiceManager
import com.example.service.ScreenNodeInfo
import android.graphics.Rect
import com.example.service.IncomingMessageEvent
import com.example.service.IncomingReplyStatus
import com.example.service.PendingIncomingMessage
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun TasksPage(back: () -> Unit, onNavigate: (String) -> Unit) {
  val ctx = LocalContext.current
  val snapshot by RiyaRuntimeBridge.latestScreenSnapshot.collectAsState()
  val lastCompleted by RiyaState.lastCompletedTask.collectAsState()
  val motivation by RiyaState.motivationalBanner.collectAsState()
  val activityRecords by LxrBackgroundTaskManager.activities.collectAsState()
  LaunchedEffect(Unit) { LxrBackgroundTaskManager.recoverInterruptedTasks(ctx) }
  var query by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var nextSuggestions by remember {
    mutableStateOf(
      listOf(
        "WhatsApp ও imo মেসেজ চেক করুন",
        "Infinix Settings-এ ব্যাটারি বা Wi-Fi দেখুন",
        "imo সেন্ড বাটন প্রিসিশন টেস্ট করুন",
      )
    )
  }
  val scope = rememberCoroutineScope()
  val aiRepo = remember { RiyaAiRepository() }

  fun runFastTask(title: String, targetPkg: String, cmd: String) {
    val svc = RiyaAccessibilityService.instance
    if (!Keep.accessibilityOn(ctx) || svc == null) {
      Toast.makeText(ctx, "বস, আগে Accessibility চালু করুন", Toast.LENGTH_LONG).show()
      RiyaVoiceManager.speakUrgent(ctx, "বস, অ্যাক্সেসিবিলিটি বন্ধ আছে। আগে চালু করে দিন।")
      Keep.openAccessibility(ctx)
      return
    }
    busy = true
    svc.executeFastTask(
      targetPackage = targetPkg,
      queryOrCommand = cmd,
      enforceFullScreenRead = true,
      fastMode = true,
    ) { ok, summary, elapsed ->
      scope.launch {
        busy = false
        if (ok) {
          val insight = aiRepo.generateTaskCompletionInsight(title, summary).getOrNull()
          val msg = insight?.completionAnnouncement ?: "হয়েছে, বস — $title (${elapsed}ms)।"
          val mot = insight?.motivationalPrompt ?: "সব সিস্টেম ঠিক আছে।"
          if (!insight?.suggestedNextTasks.isNullOrEmpty()) {
            nextSuggestions = insight!!.suggestedNextTasks
          }
          RiyaState.markTaskCompleted(msg, mot)
          RiyaVoiceManager.speakUrgent(ctx, msg)
        } else {
          Toast.makeText(ctx, summary, Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  Column(
    Modifier
      .fillMaxSize()
      .background(FULL_BG_GRADIENT)
      .statusBarsPadding()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageTopBar(
      title = "Tasks ও স্ক্রিন রিডার",
      icon = Icons.Filled.TaskAlt,
      iconColors = listOf(CYBER_CYAN, CYBER_BLUE),
      onBack = back,
      onNavigate = onNavigate,
    )

    if (!lastCompleted.isNullOrBlank()) {
      Column(
        Modifier
          .fillMaxWidth()
          .background(CYBER_CARD, RoundedCornerShape(16.dp))
          .border(1.dp, CYBER_GREEN.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
          .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          PremiumIconBadge(Icons.Filled.CheckCircle, "Completed", listOf(CYBER_GREEN, CYBER_CYAN), 32)
          Spacer(Modifier.width(10.dp))
          Text(lastCompleted.orEmpty(), color = CYBER_GREEN, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(motivation.orEmpty(), color = Color.White, fontSize = 13.sp)
      }
    }

    // Live Full Screen Reader Preview
    Column(
      Modifier
        .fillMaxWidth()
        .background(CYBER_CARD, RoundedCornerShape(16.dp))
        .border(1.dp, CYBER_CYAN.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(Icons.Filled.RemoveRedEye, "Screen Read", listOf(CYBER_CYAN, CYBER_PURPLE), 32)
        Spacer(Modifier.width(10.dp))
        Text(
          "সম্পূর্ণ স্ক্রিন রিডার (বাধ্যতামূলক)",
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp,
        )
      }
      Text(
        "অ্যাপ: ${snapshot.packageName} • নোড: ${snapshot.totalNodes} • ক্লিকেবল: ${snapshot.clickableCount}",
        color = CYBER_CYAN,
        fontSize = 12.sp,
      )
      Text(
        snapshot.summaryLines.take(5).joinToString(" | ").ifBlank { "অ্যাক্সেসিবিলিটি চালু থাকলে লাইভ স্ক্রিন পড়বে।" },
        color = Color.LightGray,
        fontSize = 12.sp,
      )
    }

    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("যেকোনো অ্যাপে সার্চ/খোঁজার নাম লিখুন") },
      colors =
        OutlinedTextFieldDefaults.colors(
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White,
          focusedBorderColor = CYBER_CYAN,
          unfocusedBorderColor = Color(0xFF475073),
        ),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        onClick = { runFastTask("স্ক্রিন রিড ও সার্চ", "", query.ifBlank { "check_messages" }) },
        enabled = !busy,
        modifier = Modifier.weight(1f).testTag("run_fast_task_button"),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_RED),
      ) {
        Text(if (busy) "চলছে..." else "⚡ দ্রুত টাস্ক চালাও")
      }
      OutlinedButton(
        onClick = { runFastTask("Infinix Settings সার্চ", "com.android.settings", query.ifBlank { "Accessibility" }) },
        enabled = !busy,
        modifier = Modifier.weight(1f),
      ) {
        Text("Settings সার্চ", color = CYBER_CYAN)
      }
    }

    Text("পরবর্তী কাজের অনুপ্রেরণা:", color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    nextSuggestions.forEach { item ->
      Row(
        Modifier
          .fillMaxWidth()
          .background(CYBER_CARD, RoundedCornerShape(14.dp))
          .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PremiumIconBadge(Icons.Filled.AutoAwesome, item, listOf(CYBER_AMBER, CYBER_RED), 30)
        Spacer(Modifier.width(10.dp))
        Text(item, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
      }
    }

    // LXR Activity Monitor — Real-Time Verified Background & Tool Operations
    Column(
      Modifier
        .fillMaxWidth()
        .testTag("lxr_activity_monitor_card")
        .background(CYBER_CARD, RoundedCornerShape(16.dp))
        .border(1.dp, CYBER_PURPLE.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          PremiumIconBadge(Icons.Filled.Bolt, "Activity Monitor", listOf(CYBER_PURPLE, CYBER_CYAN), 30)
          Spacer(Modifier.width(8.dp))
          Text(
            "LXR Activity Monitor",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
          )
        }
        Text(
          "${activityRecords.size} টি রেকর্ড",
          color = CYBER_CYAN,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
      if (activityRecords.isEmpty()) {
        Text(
          "এখনো কোনো ব্যাকগ্রাউন্ড বা অটোমেশন কাজ রেকর্ড হয়নি। যেকোনো কাজ চালালে এখান থেকে লাইভ ধাপ ও যাচাইকৃত ফলাফল দেখা যাবে।",
          color = Color.LightGray,
          fontSize = 12.sp,
        )
      } else {
        activityRecords.take(8).forEach { rec ->
          val statusColor =
            when (rec.status) {
              LxrBackgroundTaskStatus.COMPLETED -> CYBER_GREEN
              LxrBackgroundTaskStatus.RUNNING -> CYBER_CYAN
              LxrBackgroundTaskStatus.WAITING_FOR_SYSTEM -> CYBER_AMBER
              LxrBackgroundTaskStatus.PARTIALLY_COMPLETED -> CYBER_AMBER
              LxrBackgroundTaskStatus.VERIFICATION_REQUIRED -> Color(0xFFF59E0B)
              LxrBackgroundTaskStatus.FAILED -> CYBER_RED
              LxrBackgroundTaskStatus.PENDING -> Color.LightGray
            }
          Column(
            Modifier
              .fillMaxWidth()
              .background(Color(0xFF0A1428), RoundedCornerShape(12.dp))
              .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
              .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                "${rec.targetApp} • ${rec.command}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
              )
              Text(
                "${rec.status.labelBn} (${rec.progressPercent}%)",
                color = statusColor,
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp,
              )
            }
            LinearProgressIndicator(
              progress = { (rec.progressPercent / 100f).coerceIn(0f, 1f) },
              modifier = Modifier.fillMaxWidth(),
              color = statusColor,
              trackColor = Color(0xFF1F2B47),
            )
            if (rec.currentOperation.isNotBlank()) {
              Text(rec.currentOperation, color = CYBER_CYAN, fontSize = 11.5.sp)
            }
            if (rec.retrievedInfo.isNotBlank()) {
              Text(
                "তথ্য: ${rec.retrievedInfo.replace('\n', ' ').take(130)}",
                color = Color.LightGray,
                fontSize = 11.5.sp,
              )
            }
            if (rec.generatedFiles.isNotEmpty()) {
              Text(
                "ফাইল: ${rec.generatedFiles.joinToString(", ")}",
                color = CYBER_GREEN,
                fontSize = 11.sp,
              )
            }
            if (rec.verificationNote.isNotBlank()) {
              Text(
                "যাচাই: ${rec.verificationNote}",
                color = Color(0xFF93A5C0),
                fontSize = 11.sp,
              )
            }
            if (!rec.errorDetails.isNullOrBlank()) {
              Text(
                "ত্রুটি: ${rec.errorDetails}",
                color = CYBER_RED,
                fontSize = 11.sp,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun AutoReplyPage(back: () -> Unit, onNavigate: (String) -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { RiyaPreferences(ctx) }
  val settings by prefs.settingsFlow.collectAsState(initial = RiyaSettings())
  val pending by RiyaRuntimeBridge.pendingMessages.collectAsState()
  val delaySecState by RiyaState.replyDelaySeconds.collectAsState()
  val limitMaxState by RiyaState.replyLimitMax.collectAsState()
  val dispatchedCount by ReplyPolicyScheduler.dispatchedCount.collectAsState()
  var selectedReplyDelay by remember { mutableStateOf(Store.replyDelayOption(ctx)) }
  var selectedReplyLimit by remember { mutableStateOf(Store.replyLimitOption(ctx)) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    RiyaState.replyDelaySeconds.value = Store.replyDelaySeconds(ctx)
    RiyaState.replyLimitMax.value = Store.replyLimitMax(ctx)
    selectedReplyDelay = Store.replyDelayOption(ctx)
    selectedReplyLimit = Store.replyLimitOption(ctx)
    ReplyPolicyScheduler.syncFromStore(ctx)
  }
  LaunchedEffect(delaySecState) {
    selectedReplyDelay = ReplyDelayOption.fromSeconds(delaySecState)
  }
  LaunchedEffect(limitMaxState) {
    selectedReplyLimit = ReplyLimitOption.fromMaxReplies(limitMaxState)
  }

  Column(
    Modifier
      .fillMaxSize()
      .background(FULL_BG_GRADIENT)
      .statusBarsPadding()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageTopBar(
      title = "অটো রিপ্লাই (WhatsApp / imo / Messenger)",
      icon = Icons.Filled.MarkChatRead,
      iconColors = listOf(CYBER_GREEN, CYBER_CYAN),
      onBack = back,
      onNavigate = onNavigate,
    )

    ToggleRowCard(
      icon = Icons.Filled.Shield,
      colors = listOf(CYBER_GREEN, CYBER_BLUE),
      title = "Master Auto-Reply Guard",
      subtitle = "নতুন মেসেজ এলেই বসকে জানাবে",
      checked = settings.strictAutoReplyMaster,
    ) { v ->
      Store.setAutoReplyMaster(ctx, v)
      scope.launch { prefs.updateStrictMaster(v) }
    }

    ToggleRowCard(
      icon = Icons.Filled.Bolt,
      colors = listOf(CYBER_AMBER, CYBER_RED),
      title = "রিপ্লাইয়ের আগে বসের অনুমতি চান",
      subtitle = "মেসেজ এলে আগে বসকে জিজ্ঞেস করবে (Recommended)",
      checked = settings.requireBossPermissionBeforeSend,
    ) { v ->
      Store.setRequireBossPermission(ctx, v)
      scope.launch { prefs.updateRequireBossPermission(v) }
    }

    ToggleRowCard(
      icon = Icons.Filled.MarkChatRead,
      colors = listOf(CYBER_GREEN, Color(0xFF009688)),
      title = "WhatsApp অটো রিপ্লাই",
      subtitle = "WhatsApp ও WhatsApp Business",
      checked = settings.whatsAppAutoReply,
    ) { v ->
      Store.setAutoReplyWhatsApp(ctx, v)
      scope.launch { prefs.updateAppAutoReply("com.whatsapp", v) }
    }

    ToggleRowCard(
      icon = Icons.Filled.Forum,
      colors = listOf(CYBER_BLUE, CYBER_CYAN),
      title = "imo অটো রিপ্লাই (ইমোজি বাদে সেন্ড)",
      subtitle = "imo মেসেজ ও প্রিসিশন সেন্ড",
      checked = settings.imoAutoReply,
    ) { v ->
      Store.setAutoReplyImo(ctx, v)
      scope.launch { prefs.updateAppAutoReply("com.imo.android.imoim", v) }
    }

    ToggleRowCard(
      icon = Icons.AutoMirrored.Filled.Send,
      colors = listOf(CYBER_PURPLE, CYBER_RED),
      title = "Messenger অটো রিপ্লাই",
      subtitle = "Messenger ও Messenger Lite",
      checked = settings.messengerAutoReply,
    ) { v ->
      Store.setAutoReplyMessenger(ctx, v)
      scope.launch { prefs.updateAppAutoReply("com.facebook.orca", v) }
    }

    var templateDraft by remember(settings.customReplyTemplate) {
      mutableStateOf(settings.customReplyTemplate)
    }

    Column(
      Modifier
        .fillMaxWidth()
        .background(CYBER_CARD, RoundedCornerShape(16.dp))
        .border(1.dp, CYBER_CYAN.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        "ব্যস্ত থাকলে অটো রিপ্লাই মেসেজ টেমপ্লেট",
        color = CYBER_CYAN,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
      )
      OutlinedTextField(
        value = templateDraft,
        onValueChange = { templateDraft = it },
        modifier = Modifier.fillMaxWidth().testTag("auto_reply_template_input"),
        minLines = 3,
        maxLines = 6,
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = CYBER_CYAN,
            unfocusedBorderColor = Color(0xFF475073),
          ),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = {
            scope.launch {
              prefs.updateCustomTemplate(templateDraft)
              Toast.makeText(ctx, "রিপ্লাই মেসেজ সেভ হয়েছে ✓", Toast.LENGTH_SHORT).show()
            }
          },
          modifier = Modifier.weight(1f).testTag("auto_reply_template_save_button"),
          colors = ButtonDefaults.buttonColors(containerColor = CYBER_CYAN),
        ) {
          Text("সেভ করুন", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        OutlinedButton(
          onClick = {
            templateDraft = RiyaPreferences.DEFAULT_BUSY_REPLY_TEMPLATE_BN
            scope.launch {
              prefs.updateCustomTemplate(RiyaPreferences.DEFAULT_BUSY_REPLY_TEMPLATE_BN)
              Toast.makeText(ctx, "নতুন ডিফল্ট উত্তর সেট হয়েছে ✓", Toast.LENGTH_SHORT).show()
            }
          },
          modifier = Modifier.weight(1f).testTag("auto_reply_template_reset_button"),
        ) {
          Text("ডিফল্ট সেট করুন", color = Color.White, fontSize = 12.sp)
        }
      }
    }

    ReplyDelayLimitPanel(
      selectedDelay = selectedReplyDelay,
      selectedLimit = selectedReplyLimit,
      dispatchedCount = dispatchedCount,
      onResetCount = {
        ReplyPolicyScheduler.resetCounter(ctx)
        Toast.makeText(ctx, "Reply counter reset ✓", Toast.LENGTH_SHORT).show()
      },
      onSelectDelay = { option ->
        selectedReplyDelay = option
        Store.setReplyDelaySeconds(ctx, option.seconds)
        scope.launch { prefs.updateReplyDelaySeconds(option.seconds) }
      },
      onSelectLimit = { option ->
        selectedReplyLimit = option
        Store.setReplyLimitMax(ctx, option.maxReplies)
        scope.launch { prefs.updateReplyLimitMax(option.maxReplies) }
      },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        onClick = {
          try {
            ctx.startActivity(
              Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          } catch (_: Exception) {}
        },
        modifier = Modifier.weight(1f).testTag("notification_access_button"),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_BLUE),
      ) {
        Text("Notification Access")
      }
      OutlinedButton(
        onClick = {
          val key = "sim_${UUID.randomUUID().toString().take(6)}"
          val ev =
            IncomingMessageEvent(
              eventKey = key,
              packageName = "com.imo.android.imoim",
              appLabel = "imo",
              senderName = "সুমাইয়া (টেস্ট)",
              messageText = "বস, কোথায় আছেন?",
              suggestedReply = settings.customReplyTemplate.ifBlank { RiyaPreferences.DEFAULT_BUSY_REPLY_TEMPLATE_BN },
              hasRemoteInput = true,
              status = IncomingReplyStatus.PENDING_PERMISSION,
            )
          RiyaRuntimeBridge.registerIncomingMessage(ev, null)
          RiyaNotificationHelper.notifyIncomingMessageForPermission(ctx, ev, 7701)
          if (RiyaState.live?.isReady() == true) {
            RiyaState.live?.onIncomingMessageReceived(
              PendingIncomingMessage("imo", "imo", ev.senderName, ev.messageText, ev.packageName, ev.suggestedReply),
              autoSentReply = null,
            )
          } else {
            RiyaVoiceManager.speakUrgent(
              ctx,
              "বস, ইমোতে সুমাইয়া মেসেজ পাঠিয়েছেন: বস, কোথায় আছেন? আমি উত্তর হিসেবে লিখে ঠিক করেছি: ${ev.suggestedReply}। পাঠিয়ে দেবো কি?",
            )
          }
        },
        modifier = Modifier.weight(1f).testTag("simulate_incoming_msg_button"),
      ) {
        Text("টেস্ট মেসেজ", color = CYBER_CYAN)
      }
    }

    pending.forEach { ev ->
      Column(
        Modifier
          .fillMaxWidth()
          .background(CYBER_CARD, RoundedCornerShape(14.dp))
          .border(1.dp, CYBER_CYAN.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
          .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          "${ev.appLabel} • ${ev.senderName} (${ev.status.name})",
          color = CYBER_CYAN,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
        )
        Text("মেসেজ: ${ev.messageText}", color = Color.White, fontSize = 13.sp)
        Text("উত্তর: ${ev.suggestedReply}", color = Color.LightGray, fontSize = 12.sp)
        if (ev.status == IncomingReplyStatus.PENDING_PERMISSION || ev.status == IncomingReplyStatus.FAILED) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                      val sent = RiyaNotificationListenerService.sendApprovedReply(ctx, ev.eventKey, ev.suggestedReply)
                      if (sent) {
                        ReplyPolicyScheduler.tryConsumeAndPersist(ctx, limitMax)
                      }
                      RiyaRepository(ctx.applicationContext).updateAutoReplyStatus(
                        ev.eventKey,
                        if (sent) "SENT_APPROVED" else "FAILED",
                        ev.suggestedReply,
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
              modifier = Modifier.testTag("approve_pending_reply_button"),
              colors = ButtonDefaults.buttonColors(containerColor = CYBER_GREEN),
            ) {
              Text(
                if (ev.status == IncomingReplyStatus.FAILED) "আবার পাঠান" else "অনুমতি দিন ও পাঠান",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
              )
            }
            OutlinedButton(
              onClick = {
                RiyaRuntimeBridge.updateIncomingMessageStatus(
                  ev.eventKey,
                  IncomingReplyStatus.DISMISSED,
                )
                scope.launch(Dispatchers.IO) {
                  RiyaRepository(ctx.applicationContext).updateAutoReplyStatus(ev.eventKey, "DISMISSED", "")
                }
              },
              modifier = Modifier.testTag("dismiss_pending_reply_button"),
            ) {
              Text("বাতিল", color = Color.White)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ToggleRowCard(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  colors: List<Color>,
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(CYBER_CARD, RoundedCornerShape(16.dp))
      .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PremiumIconBadge(icon, title, colors, 36)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
      Text(subtitle, color = Color(0xFFAAB4D0), fontSize = 11.sp)
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CYBER_CYAN),
    )
  }
}

@Composable
fun PlaybookPage(back: () -> Unit, onNavigate: (String) -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { RiyaPreferences(ctx) }
  val settings by prefs.settingsFlow.collectAsState(initial = RiyaSettings())
  var rate by remember(settings.speechRate) { mutableFloatStateOf(settings.speechRate) }
  var imoResult by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()

  Column(
    Modifier
      .fillMaxSize()
      .background(FULL_BG_GRADIENT)
      .statusBarsPadding()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageTopBar(
      title = "Infinix 16 গাইড ও ভয়েস",
      icon = Icons.Filled.PhoneAndroid,
      iconColors = listOf(CYBER_PURPLE, CYBER_BLUE),
      onBack = back,
      onNavigate = onNavigate,
    )

    Column(
      Modifier
        .fillMaxWidth()
        .background(CYBER_CARD, RoundedCornerShape(16.dp))
        .border(1.dp, CYBER_CYAN.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(Icons.Filled.RecordVoiceOver, "Voice", listOf(CYBER_AMBER, CYBER_RED), 34)
        Spacer(Modifier.width(10.dp))
        Text("স্পষ্ট বাংলা কণ্ঠ ও উচ্চারণ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      }
      Text("ভয়েস গতি: ${"%.2f".format(rate)}x", color = CYBER_CYAN, fontSize = 12.sp)
      Slider(
        value = rate,
        onValueChange = {
          rate = it
          RiyaVoiceManager.setSpeechRate(it)
        },
        onValueChangeFinished = { scope.launch { prefs.updateSpeechRate(rate) } },
        valueRange = 0.70f..1.30f,
        colors = SliderDefaults.colors(thumbColor = CYBER_CYAN, activeTrackColor = CYBER_CYAN),
      )
      Button(
        onClick = {
          RiyaVoiceManager.speakUrgent(
            ctx,
            "বস, আমি এল এক্স আর এ আই। ইমোতে ইমোজি বাটন বাদ দিয়ে সঠিক সেন্ড বাটনে মেসেজ পাঠাতে এবং ইনফিনিক্স অ্যান্ড্রয়েড ষোলো-তে দ্রুত সার্চ করতে আমি সম্পূর্ণ প্রস্তুত।",
          )
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = CYBER_RED),
      ) {
        Text("🔊 ভয়েস টেস্ট করুন")
      }
    }

    Button(
      onClick = {
        val sampleNodes =
          listOf(
            ScreenNodeInfo("হ্যালো", "", "com.imo.android.imoim:id/et_input", "EditText", true, true, false, Rect(120, 2000, 740, 2110)),
            ScreenNodeInfo("", "❤️", "com.imo.android.imoim:id/reaction_heart", "ImageView", true, false, false, Rect(850, 1860, 960, 1950)),
            ScreenNodeInfo("", "Smiley", "com.imo.android.imoim:id/iv_emoji", "ImageView", true, false, false, Rect(750, 2010, 830, 2100)),
            ScreenNodeInfo("", "Send", "com.imo.android.imoim:id/btn_send", "ImageButton", true, false, false, Rect(920, 2005, 1040, 2110)),
          )
        val (picked, diag) = ImoSendPrecisionEngine.analyzeAndSelectSendNode(sampleNodes)
        imoResult =
          "নির্বাচিত: ${picked?.viewId ?: "none"} (${diag.strategyUsed}) | বাদ দেওয়া হয়েছে: ${diag.excludedEmojiOrMediaNodes.joinToString(", ")}"
      },
      modifier = Modifier.fillMaxWidth().testTag("test_imo_precision_button"),
      colors = ButtonDefaults.buttonColors(containerColor = CYBER_BLUE),
    ) {
      Text("🎯 imo Send বনাম Emoji প্রিসিশন টেস্ট")
    }
    if (imoResult.isNotBlank()) {
      Text(imoResult, color = CYBER_GREEN, fontSize = 12.sp)
    }

    InfinixDevicePlaybook.rules.forEach { rule ->
      Column(
        Modifier
          .fillMaxWidth()
          .background(CYBER_CARD, RoundedCornerShape(14.dp))
          .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(rule.title, color = CYBER_CYAN, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("${rule.appOrSection} • ${rule.xosAndroid16Path}", color = Color.White, fontSize = 12.sp)
        Text(rule.quickTip, color = Color.LightGray, fontSize = 11.sp)
      }
    }
  }
}
