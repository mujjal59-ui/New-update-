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
            "হয়েছে, বস! কাজ শেষ — ${event.appLabel}-এ ${event.senderName}-কে রিপ্লাই পাঠানো হয়েছে।",
            "দারুণ হয়েছে বস! এরপর অন্য কোনো কাজ করবো?",
          )
          if (RiyaState.live?.isReady() != true) {
            RiyaVoiceManager.speakUrgent(
              context,
              "হয়েছে, বস! কাজ শেষ। ${event.appLabel}-এ ${event.senderName}-কে উত্তর পাঠানো হয়েছে।",
            )
          }
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
          if (RiyaState.live?.isReady() != true) {
            RiyaVoiceManager.speakUrgent(
              context,
              "হয়েছে, বস! কাজ শেষ। ${event.appLabel}-এ ${event.senderName}-কে উত্তর পাঠানো হয়েছে।",
            )
          }
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
          if (RiyaState.live?.isReady() != true) {
            RiyaVoiceManager.speakUrgent(
              context,
              "হয়েছে, বস! ইমোতে ইমোজি বাটন এড়িয়ে সঠিক সেন্ড বাটনে উত্তর পাঠানো হয়েছে।",
            )
          }
          return true
        }
      }

      // 4. Simulated preview mode
      if (eventKey.startsWith("sim_")) {
        RiyaRuntimeBridge.markSenderReplied("${event.packageName}:${event.senderName}")
        RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.SENT)
        RiyaVoiceManager.speakUrgent(
          context,
          "হয়েছে, বস! কাজ শেষ। ${event.appLabel}-এ ${event.senderName}-কে রিপ্লাই পাঠানো হয়েছে।",
        )
        return true
      }

      RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.FAILED)
      return false
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

  @Suppress("DEPRECATION")
  private fun extractSenderAndMessage(extras: Bundle): Pair<String, String>? {
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
        val effectiveSender = sender.ifBlank { convTitle.ifBlank { extraTitle } }
        val isSelfEcho =
          effectiveSender.equals("You", true) ||
            effectiveSender.equals("আপনি", true) ||
            (selfName.isNotEmpty() && effectiveSender.equals(selfName, true))
        if (!isSelfEcho && effectiveSender.isNotBlank() && text.isNotBlank()) {
          return effectiveSender to text
        }
      }
    }

    // 2. Fallback: EXTRA_TEXT_LINES / EXTRA_BIG_TEXT / EXTRA_TEXT
    val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
    val lineText = textLines?.lastOrNull()?.toString()?.trim().orEmpty()
    val rawMsg =
      (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
          ?: extras.getCharSequence(Notification.EXTRA_TEXT))
        ?.toString()
        ?.trim()
        .orEmpty()
        .ifBlank { lineText }

    var sender = convTitle.ifBlank { extraTitle }
    var msgText = rawMsg

    if (msgText.contains(": ") && (sender.equals("WhatsApp", true) || sender.equals("imo", true) || sender.equals("Messenger", true) || sender.contains("new messages", true))) {
      val splitIdx = msgText.indexOf(": ")
      sender = msgText.substring(0, splitIdx).trim()
      msgText = msgText.substring(splitIdx + 2).trim()
    }

    if (sender.isBlank() || msgText.isBlank()) return null
    val lowerSender = sender.lowercase()
    val lowerMsg = msgText.lowercase()
    if (lowerSender in setOf("whatsapp", "imo", "messenger", "you", "আপনি") ||
      lowerSender.contains("new messages") ||
      lowerMsg.contains("checking for new messages")
    ) {
      return null
    }
    return sender to msgText
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn == null) return
    val pkg = sbn.packageName ?: return
    val appLabel = SUPPORTED_MESSAGING_APPS[pkg] ?: return
    RiyaRuntimeBridge.setNotificationListenerConnected(true)

    val notification = sbn.notification ?: return
    if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

    val extras = notification.extras ?: return
    val extracted = extractSenderAndMessage(extras)
    if (extracted == null) {
      if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
      return
    }
    val (sender, msgText) = extracted
    if (RiyaRuntimeBridge.isDuplicateNotification("$pkg|$sender|$msgText")) return

    val replyPair = extractReplyAction(notification)
    val eventKey = "${pkg}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
    val notifId = (System.currentTimeMillis() and 0x7FFF).toInt() + 2000

    scope.launch {
      try {
        val ctx = applicationContext
        val settings = repository.preferences.currentSettings()
        if (!Store.autoReplyMaster(ctx) || !settings.strictAutoReplyMaster) return@launch

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

        Store.remember(ctx, "[$appLabel] $sender: $msgText")
        RiyaState.addLog("📩 $appLabel ($sender): $msgText")

        // Generate clean draft reply via Firebase AI Logic (or fallback template)
        val draftReply =
          aiRepository
            .generateSmartAutoReply(appLabel, sender, msgText)
            .getOrElse { settings.customReplyTemplate }

        val event =
          IncomingMessageEvent(
            eventKey = eventKey,
            packageName = pkg,
            appLabel = appLabel,
            senderName = sender,
            messageText = msgText,
            suggestedReply = draftReply,
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

        RiyaRuntimeBridge.registerIncomingMessage(event, payload)
        repository.logAutoReply(
          AutoReplyLogEntity(
            eventKey = eventKey,
            appPackage = pkg,
            appLabel = appLabel,
            senderName = sender,
            incomingMessage = msgText,
            replyMessage = draftReply,
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
          val sent = sendApprovedReply(ctx, eventKey, draftReply)
          repository.updateAutoReplyStatus(
            eventKey,
            if (sent) "AUTO_SENT" else "FAILED",
            draftReply,
          )
          if (sent) {
            RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.AUTO_SENT)
            val liveMsg = PendingIncomingMessage(appType, appLabel, sender, msgText, pkg)
            val sentToLive = RiyaState.live?.onIncomingMessageReceived(liveMsg, autoSentReply = draftReply) == true
            if (!sentToLive) {
              RiyaVoiceManager.speakUrgent(
                ctx,
                "বস, $appLabel-এ $sender মেসেজ পাঠিয়েছেন: $msgText। আমি অটো রিপ্লাই দিয়ে দিয়েছি।",
              )
            }
          }
        } else {
          // Inform Boss immediately via Notification + In-App card + Clear Live Voice / TTS!
          RiyaNotificationHelper.notifyIncomingMessageForPermission(ctx, event, notifId)
          val liveMsg = PendingIncomingMessage(appType, appLabel, sender, msgText, pkg)
          val sentToLive = RiyaState.live?.onIncomingMessageReceived(liveMsg, autoSentReply = null) == true
          if (!sentToLive) {
            RiyaVoiceManager.speakUrgent(
              ctx,
              "বস, $appLabel-এ $sender মেসেজ পাঠিয়েছেন: $msgText। আমি কি উত্তর পাঠাবো? অনুগ্রহ করে অনুমতি দিন।",
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error handling incoming message notification", e)
      }
    }
  }

  private fun extractReplyAction(
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

  override fun onDestroy() {
    RiyaRuntimeBridge.setNotificationListenerConnected(false)
    scope.cancel()
    super.onDestroy()
  }
}
