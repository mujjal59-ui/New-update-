package com.example.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.RiyaAiRepository
import com.example.data.HomeTheme
import com.example.data.RiyaPreferences
import com.example.data.RiyaState
import com.example.data.Store
import androidx.compose.foundation.horizontalScroll
import com.example.service.AccessibilityStatusMonitor
import com.example.service.Keep
import com.example.service.RiyaAccessibilityService
import com.example.service.RiyaVoiceManager
import com.example.service.SystemHealthSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val CYBER_RED = Color(0xFFFF0D55)
val CYBER_BLUE = Color(0xFF0051FF)
val CYBER_CYAN = Color(0xFF00E5FF)
val CYBER_PURPLE = Color(0xFF9C27B0)
val CYBER_GREEN = Color(0xFF00E676)
val CYBER_AMBER = Color(0xFFFFB300)
val CYBER_SURFACE = Color(0xFF100A24)
val CYBER_CARD = Color(0xFF181132)

val FULL_BG_GRADIENT =
  Brush.verticalGradient(listOf(Color(0xFF260518), Color(0xFF080E2A), Color(0xFF020410)))

@Composable
fun PremiumIconBadge(
  icon: ImageVector,
  contentDescription: String,
  colors: List<Color>,
  sizeDp: Int = 36,
) {
  Box(
    modifier =
      Modifier
        .size(sizeDp.dp)
        .shadow(6.dp, CircleShape, spotColor = colors.first())
        .background(Brush.linearGradient(colors), CircleShape)
        .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = Color.White,
      modifier = Modifier.size((sizeDp * 0.54f).dp),
    )
  }
}

@Composable
fun PremiumThreeDotButton(
  onNavigate: (String) -> Unit,
  onRunVoiceTest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val ctx = LocalContext.current
  var expanded by remember { mutableStateOf(false) }

  Box(modifier = modifier) {
    IconButton(
      onClick = { expanded = true },
      modifier =
        Modifier
          .size(48.dp)
          .testTag("three_dot_menu_button")
          .background(Color(0x661C1538), CircleShape)
          .border(1.dp, CYBER_CYAN.copy(alpha = 0.45f), CircleShape),
    ) {
      Icon(
        imageVector = Icons.Filled.MoreVert,
        contentDescription = "Three Dot Menu",
        tint = Color.White,
      )
    }

    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      modifier =
        Modifier
          .background(Color(0xFF140D2C))
          .border(1.dp, CYBER_CYAN.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
    ) {
      val items =
        listOf(
          Triple("tasks", "Tasks ও স্ক্রিন রিডার", Pair(Icons.Filled.TaskAlt, listOf(CYBER_CYAN, CYBER_BLUE))),
          Triple(
            "autoreply",
            "অটো রিপ্লাই কন্ট্রোল",
            Pair(Icons.Filled.MarkChatRead, listOf(CYBER_GREEN, CYBER_CYAN)),
          ),
          Triple(
            "perm",
            "Accessibility স্ট্যাটাস ও অনুমতি",
            Pair(Icons.Filled.AccessibilityNew, listOf(CYBER_RED, CYBER_PURPLE)),
          ),
          Triple(
            "playbook",
            "Infinix সার্চ ও imo সেন্ড টেস্ট",
            Pair(Icons.Filled.PhoneAndroid, listOf(CYBER_PURPLE, CYBER_BLUE)),
          ),
          Triple(
            "lockvault",
            "ফোন ও অ্যাপ লক (PIN / Pattern)",
            Pair(Icons.Filled.Lock, listOf(CYBER_RED, CYBER_AMBER)),
          ),
          Triple(
            "settings",
            "সিস্টেম কন্ট্রোল Settings",
            Pair(Icons.Filled.Tune, listOf(CYBER_BLUE, CYBER_CYAN)),
          ),
        )
      items.forEach { (route, label, iconPair) ->
        DropdownMenuItem(
          text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              PremiumIconBadge(iconPair.first, label, iconPair.second, sizeDp = 30)
              Spacer(Modifier.width(10.dp))
              Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
          },
          onClick = {
            expanded = false
            onNavigate(route)
          },
        )
      }
      HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
      DropdownMenuItem(
        text = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconBadge(
              Icons.Filled.RecordVoiceOver,
              "Voice Test",
              listOf(CYBER_AMBER, CYBER_RED),
              sizeDp = 30,
            )
            Spacer(Modifier.width(10.dp))
            Text("স্পষ্ট ভয়েস টেস্ট", color = Color.White, fontSize = 14.sp)
          }
        },
        onClick = {
          expanded = false
          onRunVoiceTest()
        },
      )
      DropdownMenuItem(
        text = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconBadge(
              Icons.Filled.Refresh,
              "Refresh Health",
              listOf(CYBER_CYAN, CYBER_GREEN),
              sizeDp = 30,
            )
            Spacer(Modifier.width(10.dp))
            Text("Accessibility রি-চেক", color = Color.White, fontSize = 14.sp)
          }
        },
        onClick = {
          expanded = false
          val snap = AccessibilityStatusMonitor.refreshNow(ctx, speakIfTurnedOff = true)
          Toast.makeText(
              ctx,
              if (snap.isAccessibilityTrulyActive) "Accessibility সক্রিয় আছে ✓"
              else "সতর্কতা: Accessibility বন্ধ আছে!",
              Toast.LENGTH_SHORT,
            )
            .show()
        },
      )
    }
  }
}

@Composable
fun RiyaThreeLineMenuDialog(
  health: SystemHealthSnapshot,
  alwaysListening: Boolean,
  arWa: Boolean,
  arImo: Boolean,
  arMsg: Boolean,
  onToggleAlways: (Boolean) -> Unit,
  onToggleWa: (Boolean) -> Unit,
  onToggleImo: (Boolean) -> Unit,
  onToggleMsg: (Boolean) -> Unit,
  onNavigate: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val ctx = LocalContext.current

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = CYBER_SURFACE,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PremiumIconBadge(
          icon = Icons.Filled.AutoAwesome,
          contentDescription = "LXR--AI Menu",
          colors = listOf(CYBER_RED, CYBER_PURPLE, CYBER_BLUE),
          sizeDp = 40,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text("LXR--AI Menu", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
          Text(
            if (health.isAccessibilityTrulyActive) "অ্যাক্সেসিবিলিটি: সচল ✓"
            else "অ্যাক্সেসিবিলিটি: বন্ধ ⚠",
            color = if (health.isAccessibilityTrulyActive) CYBER_GREEN else CYBER_RED,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
          )
        }
        PremiumThreeDotButton(
          onNavigate = {
            onDismiss()
            onNavigate(it)
          },
          onRunVoiceTest = {
            RiyaVoiceManager.speakUrgent(
              ctx,
              "নমস্কার বস, আমি এল এক্স আর এ আই। আমার কণ্ঠ এখন একদম স্পষ্ট ও সুন্দর শোনা যাচ্ছে।",
            )
          },
        )
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        MenuSectionRow(
          icon = Icons.Filled.TaskAlt,
          colors = listOf(CYBER_CYAN, CYBER_BLUE),
          title = "Tasks ও স্ক্রিন রিডার",
          subtitle = "দ্রুত টাস্ক + হয়েছে/কাজ শেষ ও উৎসাহ",
          tag = "menu_tasks",
        ) {
          onDismiss()
          onNavigate("tasks")
        }

        MenuSectionRow(
          icon = Icons.Filled.MarkChatRead,
          colors = listOf(CYBER_GREEN, CYBER_CYAN),
          title = "অটো রিপ্লাই (WhatsApp / imo / Messenger)",
          subtitle = "মেসেজ এলে বসকে জানিয়ে অনুমতি নেবে",
          tag = "menu_autoreply",
        ) {
          onDismiss()
          onNavigate("autoreply")
        }

        MenuSectionRow(
          icon = Icons.Filled.VerifiedUser,
          colors = listOf(CYBER_RED, CYBER_PURPLE),
          title = "Permissions ও Accessibility",
          subtitle =
            if (health.isAccessibilityTrulyActive) "সব সার্ভিস চালু আছে" else "বন্ধ — ট্যাপ করে চালু করুন",
          tag = "menu_perm",
        ) {
          onDismiss()
          onNavigate("perm")
        }

        MenuSectionRow(
          icon = Icons.Filled.PhoneAndroid,
          colors = listOf(CYBER_PURPLE, CYBER_BLUE),
          title = "Infinix Android 16 সার্চ ও imo গাইড",
          subtitle = "ইমো সেন্ড বনাম ইমোজি + স্পষ্ট ভয়েস",
          tag = "menu_playbook",
        ) {
          onDismiss()
          onNavigate("playbook")
        }

        MenuSectionRow(
          icon = Icons.Filled.Key,
          colors = listOf(CYBER_AMBER, CYBER_RED),
          title = "Gemini API Key ও Voice",
          subtitle = "Gemini 3.1 Flash TTS (ছেলে/মেয়ে ভয়েস) ও Bluetooth",
          tag = "menu_key",
        ) {
          onDismiss()
          onNavigate("key")
        }

        MenuSectionRow(
          icon = Icons.Filled.Memory,
          colors = listOf(CYBER_BLUE, CYBER_CYAN),
          title = "Memory (30 MB)",
          subtitle = "সংরক্ষিত স্মৃতি ও কথোপকথন",
          tag = "menu_mem",
        ) {
          onDismiss()
          onNavigate("mem")
        }

        MenuSectionRow(
          icon = Icons.Filled.Lock,
          colors = listOf(CYBER_RED, CYBER_AMBER),
          title = "ফোন ও অ্যাপ লক (PIN / Pattern)",
          subtitle = "৮ ডিজিট PIN / প্যাটার্ন সেভ ও অটো আনলক",
          tag = "menu_lockvault",
        ) {
          onDismiss()
          onNavigate("lockvault")
        }

        MenuSectionRow(
          icon = Icons.Filled.Tune,
          colors = listOf(CYBER_CYAN, CYBER_PURPLE),
          title = "System Control Settings",
          subtitle = "Wi-Fi, Bluetooth, Flashlight, Sound, Battery",
          tag = "menu_settings",
        ) {
          onDismiss()
          onNavigate("settings")
        }

        MenuSectionRow(
          icon = Icons.Filled.NotificationsActive,
          colors = listOf(CYBER_AMBER, CYBER_PURPLE),
          title = "Notification Access দিন",
          subtitle = "অটো রিপ্লাই ও মেসেজ শোনার জন্য বাধ্যতামূলক",
          tag = "menu_notif_access",
        ) {
          onDismiss()
          try {
            ctx.startActivity(
              Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          } catch (_: Exception) {}
        }

        HorizontalDivider(
          modifier = Modifier.padding(vertical = 4.dp),
          color = Color.White.copy(alpha = 0.14f),
        )

        val currentThemeId by RiyaState.homeThemeIndex.collectAsState()
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .background(Color(0xFF150E2A), RoundedCornerShape(12.dp))
              .border(1.dp, CYBER_CYAN.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
              .padding(horizontal = 10.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            "হোম অ্যানিমেটেড থিম (Extra Themes)",
            color = CYBER_CYAN,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
          )
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            HomeTheme.entries.forEach { t ->
              val selected = currentThemeId == t.id
              Text(
                text =
                  when (t) {
                    HomeTheme.LXR_STUDIO -> "LXR স্টুডিও"
                    HomeTheme.ARIA_CYBER_ROOM -> "Aria রুম"
                    HomeTheme.MITALI_CYBER_STUDIO -> "মীতালী থিম"
                  },
                color = if (selected) Color.Black else Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                  Modifier
                    .testTag("menu_theme_chip_${t.id}")
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) CYBER_CYAN else Color(0xFF231942))
                    .border(
                      1.dp,
                      if (selected) CYBER_CYAN else Color(0xFF475073),
                      RoundedCornerShape(20.dp),
                    )
                    .clickable {
                      Store.setHomeTheme(ctx, t)
                      Toast.makeText(ctx, "${t.titleBn} চালু হয়েছে ✓", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
              )
            }
          }
        }

        MenuToggleRow(
          icon = Icons.Filled.Shield,
          colors = listOf(CYBER_GREEN, Color(0xFF009688)),
          label = "WhatsApp অটো রিপ্লাই",
          checked = arWa,
          onCheckedChange = onToggleWa,
        )
        MenuToggleRow(
          icon = Icons.Filled.Forum,
          colors = listOf(CYBER_BLUE, CYBER_CYAN),
          label = "imo অটো রিপ্লাই",
          checked = arImo,
          onCheckedChange = onToggleImo,
        )
        MenuToggleRow(
          icon = Icons.AutoMirrored.Filled.Send,
          colors = listOf(CYBER_PURPLE, CYBER_RED),
          label = "Messenger অটো রিপ্লাই",
          checked = arMsg,
          onCheckedChange = onToggleMsg,
        )
        MenuToggleRow(
          icon = Icons.Filled.Hearing,
          colors = listOf(CYBER_RED, CYBER_AMBER),
          label = "Always Listening (App খুললেই)",
          checked = alwaysListening,
          onCheckedChange = onToggleAlways,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Icon(Icons.Filled.Close, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Close", color = CYBER_CYAN, fontWeight = FontWeight.Bold)
      }
    },
  )
}

@Composable
private fun MenuSectionRow(
  icon: ImageVector,
  colors: List<Color>,
  title: String,
  subtitle: String,
  tag: String,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .testTag(tag)
        .clip(RoundedCornerShape(14.dp))
        .background(CYBER_CARD)
        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PremiumIconBadge(icon = icon, contentDescription = title, colors = colors, sizeDp = 36)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
      Text(subtitle, color = Color(0xFFAAB4D0), fontSize = 11.sp, maxLines = 1)
    }
  }
}

@Composable
private fun MenuToggleRow(
  icon: ImageVector,
  colors: List<Color>,
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF150E2A))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PremiumIconBadge(icon = icon, contentDescription = label, colors = colors, sizeDp = 32)
    Spacer(Modifier.width(10.dp))
    Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors =
        SwitchDefaults.colors(
          checkedThumbColor = Color.White,
          checkedTrackColor = CYBER_CYAN,
        ),
    )
  }
}

@Composable
fun RiyaChatDialog(close: () -> Unit) {
  val ctx = LocalContext.current
  val log by RiyaState.log.collectAsState()
  val liveRunning by RiyaState.running.collectAsState()
  var input by remember { mutableStateOf("") }
  var generating by remember { mutableStateOf(false) }
  var streamText by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()
  val aiRepo = remember { RiyaAiRepository() }
  val prefs = remember { RiyaPreferences(ctx) }

  fun submitMessage() {
    val q = input.trim()
    if (q.isBlank() || generating) return
    input = ""

    if (liveRunning && RiyaState.live?.isReady() == true) {
      RiyaState.live?.sendText(q)
      return
    }

    // Fallback via Firebase AI Logic so Chat ALWAYS works smoothly even when Voice Live isn't started!
    RiyaState.addLog("বস: $q")
    generating = true
    streamText = ""
    scope.launch {
      try {
        val (snap, accOk, settings, combinedMemory, lowNetFast) =
          withContext(Dispatchers.IO) {
            Store.remember(ctx, "Boss(text): $q")
            val s = RiyaAccessibilityService.instance?.readEntireScreenTree()
            val a = Keep.accessibilityOn(ctx)
            val st = prefs.currentSettings()
            val matches = Store.searchMemory(ctx, q, maxDays = Store.RETENTION_DAYS)
            val recent = Store.recent(ctx, 2200)
            val mem =
              buildString {
                if (matches.isNotBlank()) appendLine(matches)
                if (recent.isNotBlank()) appendLine(recent)
              }
            Tuple5(s, a, st, mem, Store.lowNetFastMode(ctx))
          }
        var finalReply = ""
        aiRepo
          .streamAssistantReply(
            userPrompt = q,
            screenSummary = snap?.summaryLines?.take(12)?.joinToString(", ").orEmpty(),
            accessibilityActive = accOk,
            preferFastLowNet = lowNetFast,
            memoryContext = combinedMemory,
          )
          .collect { partial ->
            streamText = partial
            finalReply = partial
          }
        if (finalReply.isNotBlank()) {
          RiyaState.addLog("LXR--AI: $finalReply")
          withContext(Dispatchers.IO) { Store.remember(ctx, "LXR--AI: $finalReply") }
          if (settings.voiceOutputEnabled) {
            RiyaVoiceManager.initialize(ctx)
            RiyaVoiceManager.speak(finalReply)
          }
        }
      } catch (e: Exception) {
        val fallback =
          "বস, এই মুহূর্তে সরাসরি উত্তর আনা যায়নি (${e.localizedMessage ?: "নেটওয়ার্ক সমস্যা"})। আপনি চাইলে Menu > Gemini API Key ও Voice দিয়ে ভয়েস লাইভ চালু করতে পারেন।"
        RiyaState.addLog("LXR--AI: $fallback")
      } finally {
        streamText = ""
        generating = false
      }
    }
  }

  AlertDialog(
    onDismissRequest = close,
    containerColor = CYBER_SURFACE,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(
          Icons.Filled.CheckCircle,
          "LXR--AI Chat",
          listOf(CYBER_CYAN, CYBER_BLUE),
          sizeDp = 34,
        )
        Spacer(Modifier.width(10.dp))
        Column {
          Text("LXR--AI Chat", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
          Text(
            if (liveRunning) "Live Audio সংযুক্ত" else "Smart Text Mode প্রস্তুত",
            color = CYBER_CYAN,
            fontSize = 11.sp,
          )
        }
      }
    },
    text = {
      Column {
        LazyColumn(Modifier.heightIn(max = 280.dp)) {
          itemsIndexed(log, key = { idx, _ -> idx }) { _, line ->
            Text(line, Modifier.padding(vertical = 3.dp), fontSize = 14.sp, color = Color.White)
          }
          if (streamText.isNotBlank()) {
            item {
              Text(
                "LXR--AI: $streamText",
                Modifier.padding(vertical = 3.dp),
                fontSize = 14.sp,
                color = CYBER_CYAN,
              )
            }
          }
        }
        if (generating) {
          LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            color = CYBER_CYAN,
          )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = input,
          onValueChange = { input = it },
          modifier = Modifier.fillMaxWidth().testTag("chat_input_field"),
          placeholder = { Text("LXR--AI কে কিছু লিখুন বস…", color = Color.Gray) },
          colors =
            OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              focusedBorderColor = CYBER_CYAN,
              unfocusedBorderColor = Color(0xFF475073),
            ),
          trailingIcon = {
            TextButton(
              onClick = { submitMessage() },
              modifier = Modifier.testTag("chat_send_button"),
            ) {
              Text("Send", color = CYBER_RED, fontWeight = FontWeight.Bold)
            }
          },
        )
      }
    },
    confirmButton = {
      TextButton(onClick = close) { Text("Close", color = CYBER_CYAN, fontWeight = FontWeight.Bold) }
    },
  )
}

private data class Tuple5<A, B, C, D, E>(
  val first: A,
  val second: B,
  val third: C,
  val fourth: D,
  val fifth: E,
)
