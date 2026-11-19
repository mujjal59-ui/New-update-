package com.example.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.ai.RiyaAiRepository
import com.example.data.AutoReplyLogEntity
import com.example.data.RiyaPreferences
import com.example.data.RiyaRepository
import com.example.data.RiyaState
import com.example.data.Store
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RiyaNotificationListenerService : NotificationListenerService() {
  companion object {
    private const val TAG = "RiyaNotifListener"

    private val SUPPORTED_MESSAGING_APPS =
      mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.imo.android.imoim" to "imo",
        "com.imo.android.imoimhd" to "imo HD",
        "com.imo.android.imoimbeta" to "imo Beta",
        "com.facebook.orca" to "Messenger",
        "com.facebook.mlite" to "Messenger Lite",
      )

    private val fallbackScope =
      CoroutineScope(
        SupervisorJob() +
          Dispatchers.IO +
          CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Recovered from fallback notification error", throwable)
          }
      )

    private fun resolveAppLabel(pkg: String): String? {
      SUPPORTED_MESSAGING_APPS[pkg]?.let { return it }
      val lower = pkg.lowercase()
      return when {
        lower.contains("whatsapp") -> "WhatsApp"
        lower.startsWith("com.imo.") || lower.contains("imoim") -> "imo"
        lower == "com.facebook.orca" || lower == "com.facebook.mlite" -> "Messenger"
        else -> null
      }
    }

    @Suppress("DEPRECATION")
    fun extractSenderAndMessage(extras: Bundle): Pair<String, String>? {
      val selfName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()?.trim().orEmpty()
      val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
      val extraTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()

      // 1. Android MessagingStyle (WhatsApp, imo, Messenger primary format on Android 14-16)
      val rawMsgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
      if (!rawMsgs.isNullOrEmpty()) {
        for (idx in rawMsgs.indices.reversed()) {
          val b = rawMsgs[idx] as? Bundle ?: continue
          val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
          var sender = b.getCharSequence("sender")?.toString()?.trim().orEmpty()
          if (sender.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val person = b.getParcelable("sender_person") as? android.app.Person
            sender = person?.name?.toString()?.trim().orEmpty()
          }
          val effectiveSender =
            when {
              sender.isNotBlank() && convTitle.isNotBlank() && !sender.equals(convTitle, true) ->
                "$sender ($convTitle)"
              sender.isNotBlank() -> sender
              convTitle.isNotBlank() -> convTitle
              else -> extraTitle
            }
          val isSelfEcho =
            effectiveSender.equals("You", true) ||
              effectiveSender.equals("আপনি", true) ||
              (selfName.isNotEmpty() && sender.equals(selfName, true))
          if (!isSelfEcho && effectiveSender.isNotBlank() && text.isNotBlank() && !isNoiseSummaryText(text)) {
            return effectiveSender to text
          }
        }
      }

      // 2. Fallback: EXTRA_TEXT_LINES / EXTRA_BIG_TEXT / EXTRA_TEXT
      val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
      val lineText =
        textLines
          ?.mapNotNull { it?.toString()?.trim() }
          ?.lastOrNull { it.isNotBlank() && !isNoiseSummaryText(it) }
          .orEmpty()
      val bigOrText =
        (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))
          ?.toString()
          ?.trim()
          .orEmpty()
      val rawMsg = if (bigOrText.isNotBlank() && !isNoiseSummaryText(bigOrText)) bigOrText else lineText

      var sender = convTitle.ifBlank { extraTitle }
      var msgText = rawMsg

      if (msgText.contains(": ") &&
        (sender.equals("WhatsApp", true) ||
          sender.equals("WhatsApp Business", true) ||
          sender.equals("imo", true) ||
          sender.equals("imo HD", true) ||
          sender.equals("Messenger", true) ||
          sender.equals("Messenger Lite", true) ||
          sender.contains("new messages", true) ||
          sender.contains("নতুন বার্তা", true))
      ) {
        val splitIdx = msgText.indexOf(": ")
        sender = msgText.substring(0, splitIdx).trim()
        msgText = msgText.substring(splitIdx + 2).trim()
      }

      if (sender.isBlank() || msgText.isBlank()) return null
      val lowerSender = sender.lowercase()
      if (lowerSender in setOf("whatsapp", "whatsapp business", "imo", "messenger", "messenger lite", "you", "আপনি") ||
        lowerSender.contains("new messages") ||
        lowerSender.contains("নতুন বার্তা") ||
        isNoiseSummaryText(msgText)
      ) {
        return null
      }
      return sender to msgText
    }

    private fun isNoiseSummaryText(text: String): Boolean {
      val lower = text.trim().lowercase()
      if (lower.isEmpty()) return true
      return lower.contains("checking for new messages") ||
        lower.contains("নতুন বার্তা পরীক্ষা করা হচ্ছে") ||
        lower.contains("messages from ") ||
        lower.matches(Regex("^\\d+\\s+new messages?$")) ||
        lower.matches(Regex("^[০-৯\\d]+টি নতুন (বার্তা|মেসেজ)$")) ||
        lower == "chat heads active" ||
        lower.contains("tap for more info")
    }

    fun handleAccessibilityFallbackNotification(
      context: Context,
      pkg: String,
      notification: Notification?,
      fallbackTexts: List<String>,
    ) {
      val appLabel = resolveAppLabel(pkg) ?: return
      if (notification != null && (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

      val extracted =
        notification?.extras?.let { extractSenderAndMessage(it) }
          ?: run {
            val cleaned = fallbackTexts.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) return
            val raw = cleaned.joinToString(": ")
            if (cleaned.size >= 2) {
              cleaned[0] to cleaned.drop(1).joinToString(" ")
            } else if (raw.contains(": ")) {
              val idx = raw.indexOf(": ")
              raw.substring(0, idx).trim() to raw.substring(idx + 2).trim()
            } else {
              return
            }
          }

      val (sender, msgText) = extracted
      val lowerSender = sender.lowercase()
      if (lowerSender in setOf("whatsapp", "imo", "messenger", "you", "আপনি") ||
        msgText.lowercase().contains("checking for new messages")
      ) {
        return
      }
      if (RiyaRuntimeBridge.isDuplicateNotification("$pkg|$sender|$msgText")) return
      val replyPair = notification?.let { extractReplyActionStatic(it) }
      processIncomingMessage(
        ctx = context.applicationContext,
        pkg = pkg,
        appLabel = appLabel,
        sender = sender,
        msgText = msgText,
        replyPair = replyPair,
        repo = RiyaRepository(context.applicationContext),
        aiRepo = RiyaAiRepository(),
        scope = fallbackScope,
      )
    }

    private fun extractReplyActionStatic(
      notification: Notification
    ): Pair<RemoteInput, android.app.PendingIntent>? {
      val actions = notification.actions ?: return null
      for (action in actions) {
        val remoteInputs = action.remoteInputs ?: continue
        val input = remoteInputs.firstOrNull { it.allowFreeFormInput } ?: remoteInputs.firstOrNull()
        if (input != null && action.actionIntent != null) {
          return input to action.actionIntent
        }
      }
      return null
    }

    private fun processIncomingMessage(
      ctx: Context,
      pkg: String,
      appLabel: String,
      sender: String,
      msgText: String,
      replyPair: Pair<RemoteInput, android.app.PendingIntent>?,
      repo: RiyaRepository,
      aiRepo: RiyaAiRepository,
      scope: CoroutineScope,
    ) {
      val eventKey = "${pkg}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
      val notifId = (System.currentTimeMillis() and 0x7FFF).toInt() + 2000

      scope.launch {
        try {
          val settings = repo.preferences.currentSettings()
          if (!Store.autoReplyMaster(ctx) && !settings.strictAutoReplyMaster) return@launch

          val appType =
            when {
              pkg.contains("whatsapp") -> "whatsapp"
              pkg.contains("imo") -> "imo"
              else -> "messenger"
            }
          val isAppEnabled =
            when (appType) {
              "whatsapp" -> Store.autoReplyWhatsApp(ctx) && settings.whatsAppAutoReply
              "imo" -> Store.autoReplyImo(ctx) && settings.imoAutoReply
              else -> Store.autoReplyMessenger(ctx) && settings.messengerAutoReply
            }
          if (!isAppEnabled) return@launch

          if (RiyaRuntimeBridge.isDuplicateOutgoingEcho(appLabel, msgText)) return@launch
          Store.remember(ctx, "[প্রাপ্ত মেসেজ / RECEIVED][$appLabel] $sender: $msgText")
          RiyaState.addLog("📩 $appLabel ($sender): $msgText")

          val immediateTemplate =
            settings.customReplyTemplate.ifBlank { RiyaPreferences.DEFAULT_BUSY_REPLY_TEMPLATE_BN }
          val event =
            IncomingMessageEvent(
              eventKey = eventKey,
              packageName = pkg,
              appLabel = appLabel,
              senderName = sender,
              messageText = msgText,
              suggestedReply = immediateTemplate,
              hasRemoteInput = replyPair != null,
              status = IncomingReplyStatus.PENDING_PERMISSION,
            )
          val payload =
            PendingReplyPayload(
              eventKey = eventKey,
              packageName = pkg,
              appLabel = appLabel,
              senderName = sender,
              messageText = msgText,
              remoteInput = replyPair?.first,
              replyIntent = replyPair?.second,
              notificationId = notifId,
            )

          // Register & notify IMMEDIATELY before any slow LLM call
          RiyaRuntimeBridge.registerIncomingMessage(event, payload)
          repo.logAutoReply(
            AutoReplyLogEntity(
              eventKey = eventKey,
              appPackage = pkg,
              appLabel = appLabel,
              senderName = sender,
              incomingMessage = msgText,
              replyMessage = immediateTemplate,
              status = "PENDING_APPROVAL",
            )
          )

          val senderCooldownKey = "$pkg:$sender"
          val askBoss = Store.requireBossPermission(ctx) || settings.requireBossPermissionBeforeSend
          if (
            !askBoss &&
              (replyPair != null || Keep.accessibilityOn(ctx)) &&
              RiyaRuntimeBridge.canAutoReplyToSender(senderCooldownKey)
          ) {
            val smartReply =
              kotlinx.coroutines.withTimeoutOrNull(2200L) {
                aiRepo.generateSmartAutoReply(appLabel, sender, msgText, immediateTemplate).getOrNull()
              } ?: immediateTemplate
            RiyaRuntimeBridge.updateIncomingMessageReplyText(eventKey, smartReply)

            val sent = sendApprovedReply(ctx, eventKey, smartReply)
            repo.updateAutoReplyStatus(
              eventKey,
              if (sent) "AUTO_SENT" else "FAILED",
              smartReply,
            )
            if (sent) {
              RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.AUTO_SENT)
              RiyaRuntimeBridge.recordOutgoingMessage(appLabel, sender, smartReply)
              Store.remember(ctx, "[আমার পাঠানো উত্তর / SENT_BY_ME][$appLabel -> $sender]: $smartReply")
              val liveMsg = PendingIncomingMessage(appType, appLabel, sender, msgText, pkg, smartReply)
              val sentToLive = RiyaState.live?.onIncomingMessageReceived(liveMsg, autoSentReply = smartReply) == true
              if (!sentToLive) {
                RiyaVoiceManager.speakUrgent(
                  ctx,
                  "বস, $appLabel-এ $sender মেসেজ পাঠিয়েছেন: $msgText। আমি অটো রিপ্লাইয়ে লিখে পাঠিয়েছি: $smartReply।",
                )
              }
            } else {
              RiyaNotificationHelper.notifyIncomingMessageForPermission(ctx, event, notifId)
            }
          } else {
            // Prepare contextual draft reply first (fast timeout) so Boss hears both the incoming text AND prepared reply!
            val plannedDraft =
              (kotlinx.coroutines.withTimeoutOrNull(1800L) {
                aiRepo.generateSmartAutoReply(appLabel, sender, msgText, immediateTemplate).getOrNull()
              } ?: immediateTemplate).trim()
            if (plannedDraft != immediateTemplate) {
              RiyaRuntimeBridge.updateIncomingMessageReplyText(eventKey, plannedDraft)
            }
            val updatedEvent = event.copy(suggestedReply = plannedDraft)
            RiyaNotificationHelper.notifyIncomingMessageForPermission(ctx, updatedEvent, notifId)
            val liveMsg = PendingIncomingMessage(appType, appLabel, sender, msgText, pkg, plannedDraft)
            val sentToLive = RiyaState.live?.onIncomingMessageReceived(liveMsg, autoSentReply = null) == true
            if (!sentToLive) {
              RiyaVoiceManager.speakUrgent(
                ctx,
                "বস, $appLabel-এ $sender মেসেজ পাঠিয়েছেন: $msgText। আমি রিপ্লাই হিসেবে পাঠানোর জন্য তৈরি রেখেছি: $plannedDraft। পাঠিয়ে দেবো কি বস?",
              )
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error handling incoming message notification", e)
        }
      }
    }

    fun sendApprovedReply(
      context: Context,
      eventKey: String,
      customReplyText: String? = null,
    ): Boolean {
      val event = RiyaRuntimeBridge.getPendingMessage(eventKey) ?: return false
      val payload = RiyaRuntimeBridge.getReplyPayload(eventKey)
      val replyText = (customReplyText ?: event.suggestedReply).trim()
      if (replyText.isEmpty()) return false

      RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.SENDING)

      // 1. Primary: Official Android Notification RemoteInput direct reply
      if (payload?.remoteInput != null && payload.replyIntent != null) {
        try {
          val intent = Intent()
          val bundle = Bundle().apply {
            putCharSequence(payload.remoteInput.resultKey, replyText)
          }
          RemoteInput.addResultsToIntent(arrayOf(payload.remoteInput), intent, bundle)
          payload.replyIntent.send(context, 0, intent)
          RiyaRuntimeBridge.markSenderReplied("${event.packageName}:${event.senderName}")
          RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.SENT)
          RiyaState.markTaskCompleted(
            "${event.appLabel}-এ ${event.senderName}-কে রিপ্লাই পাঠানো হয়েছে: \"$replyText\"",
            "মেসেজ পাঠানো সম্পন্ন",
          )
          notifyReplySentSpeech(context, event.appLabel, event.senderName, replyText)
          return true
        } catch (e: Exception) {
          Log.e(TAG, "RemoteInput send failed", e)
        }
      }

      // 2. Fallback via RiyaAccessibilityService + MessageAgent (handles imo Send precision without tapping Emoji!)
      val acc = RiyaAccessibilityService.instance
      if (acc != null && Keep.accessibilityOn(context)) {
        val result =
          MessageAgent.run(context, acc, event.appLabel, event.senderName, replyText)
        if (result.contains("পাঠানো হয়েছে")) {
          RiyaRuntimeBridge.markSenderReplied("${event.packageName}:${event.senderName}")
          RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.SENT)
          notifyReplySentSpeech(context, event.appLabel, event.senderName, replyText)
          return true
        }
      }

      // 3. Fallback when imo chat is already active on screen
      if (event.packageName in ImoSendPrecisionEngine.IMO_PACKAGES && acc != null) {
        val diag =
          ImoSendPrecisionEngine.performLiveImoSend(acc.rootInActiveWindow, replyText) { x, y ->
            acc.tapAt(x, y)
          }
        if (diag.succeeded) {
          RiyaRuntimeBridge.markSenderReplied("${event.packageName}:${event.senderName}")
          RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.SENT)
          notifyReplySentSpeech(context, "ইমো", event.senderName, replyText)
          return true
        }
      }

      // 4. Simulated preview mode
      if (eventKey.startsWith("sim_")) {
        RiyaRuntimeBridge.markSenderReplied("${event.packageName}:${event.senderName}")
        RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.SENT)
        notifyReplySentSpeech(context, event.appLabel, event.senderName, replyText)
        return true
      }

      RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.FAILED)
      return false
    }

    private fun notifyReplySentSpeech(
      context: Context,
      appLabel: String,
      senderName: String,
      replyText: String,
    ) {
      RiyaRuntimeBridge.recordOutgoingMessage(appLabel, senderName, replyText)
      Store.remember(context, "[আমার পাঠানো উত্তর / SENT_BY_ME][$appLabel -> $senderName]: $replyText")
      val spokenConfirm =
        "বস, $appLabel-এ $senderName-কে আমি ঠিক এই উত্তরটি লিখে পাঠিয়েছি: $replyText।"
      val live = RiyaState.live
      if (live != null && live.isReady()) {
        live.announceSentReply(appLabel, senderName, replyText)
      } else {
        RiyaVoiceManager.speakUrgent(context, spokenConfirm)
      }
    }
  }

  private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.e(TAG, "Recovered from coroutine error in NotificationListener", throwable)
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
  private lateinit var repository: RiyaRepository
  private val aiRepository = RiyaAiRepository()

  override fun onCreate() {
    super.onCreate()
    repository = RiyaRepository(applicationContext)
    RiyaVoiceManager.initialize(applicationContext)
  }

  override fun onListenerConnected() {
    super.onListenerConnected()
    RiyaRuntimeBridge.setNotificationListenerConnected(true)
    AccessibilityStatusMonitor.refreshNow(applicationContext, speakIfTurnedOff = false)
    AccessibilityKeepAliveService.sync(applicationContext)
    Log.d(TAG, "LXR--AI NotificationListener connected")
  }

  override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    RiyaRuntimeBridge.setNotificationListenerConnected(false)
    try {
      requestRebind(android.content.ComponentName(this, RiyaNotificationListenerService::class.java))
    } catch (_: Throwable) {}
    Log.w(TAG, "LXR--AI NotificationListener disconnected")
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn == null) return
    val pkg = sbn.packageName ?: return
    val appLabel = resolveAppLabel(pkg) ?: return
    RiyaRuntimeBridge.setNotificationListenerConnected(true)

    val notification = sbn.notification ?: return
    if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

    val extras = notification.extras ?: return
    val extracted = extractSenderAndMessage(extras) ?: return
    val (sender, msgText) = extracted
    if (RiyaRuntimeBridge.isDuplicateNotification("$pkg|$sender|$msgText")) return

    val replyPair = extractReplyActionStatic(notification)
    processIncomingMessage(
      ctx = applicationContext,
      pkg = pkg,
      appLabel = appLabel,
      sender = sender,
      msgText = msgText,
      replyPair = replyPair,
      repo = repository,
      aiRepo = aiRepository,
      scope = scope,
    )
  }

  override fun onDestroy() {
    RiyaRuntimeBridge.setNotificationListenerConnected(false)
    scope.cancel()
    super.onDestroy()
  }
}
