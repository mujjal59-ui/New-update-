package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.example.data.RiyaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AutoReplyActionReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_APPROVE_REPLY = "com.example.ACTION_APPROVE_REPLY"
    const val ACTION_DISMISS_REPLY = "com.example.ACTION_DISMISS_REPLY"
    const val EXTRA_EVENT_KEY = "extra_event_key"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
  }

  override fun onReceive(context: Context?, intent: Intent?) {
    if (context == null || intent == null) return
    val eventKey = intent.getStringExtra(EXTRA_EVENT_KEY) ?: return
    val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

    if (notifId > 0) {
      try {
        NotificationManagerCompat.from(context).cancel(notifId)
      } catch (_: Exception) {}
    }

    val pendingResult = goAsync()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope.launch {
      try {
        val repo = RiyaRepository(context.applicationContext)
        when (intent.action) {
          ACTION_APPROVE_REPLY -> {
            ReplyPolicyScheduler.syncFromStore(context)
            val limitMax = com.example.data.Store.replyLimitMax(context)
            if (ReplyPolicyScheduler.canSendReply(limitMax)) {
              val event = RiyaRuntimeBridge.getPendingMessage(eventKey)
              val sent = RiyaNotificationListenerService.sendApprovedReply(context, eventKey)
              if (sent) {
                ReplyPolicyScheduler.tryConsumeAndPersist(context, limitMax)
              }
              repo.updateAutoReplyStatus(
                eventKey,
                if (sent) "SENT_APPROVED" else "FAILED",
                event?.suggestedReply.orEmpty(),
              )
            }
          }

          ACTION_DISMISS_REPLY -> {
            RiyaRuntimeBridge.updateIncomingMessageStatus(eventKey, IncomingReplyStatus.DISMISSED)
            repo.updateAutoReplyStatus(eventKey, "DISMISSED", "")
          }
        }
      } finally {
        pendingResult.finish()
      }
    }
  }
}
