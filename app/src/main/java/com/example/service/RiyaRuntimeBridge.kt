package com.example.service

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object RiyaRuntimeBridge {
  private val _isAccessibilityServiceConnected = MutableStateFlow(false)
  val isAccessibilityServiceConnected: StateFlow<Boolean> =
    _isAccessibilityServiceConnected.asStateFlow()

  private val _isNotificationListenerConnected = MutableStateFlow(false)
  val isNotificationListenerConnected: StateFlow<Boolean> =
    _isNotificationListenerConnected.asStateFlow()

  private val _latestScreenSnapshot = MutableStateFlow(ScreenSnapshot())
  val latestScreenSnapshot: StateFlow<ScreenSnapshot> = _latestScreenSnapshot.asStateFlow()

  private val _pendingMessages = MutableStateFlow<List<IncomingMessageEvent>>(emptyList())
  val pendingMessages: StateFlow<List<IncomingMessageEvent>> = _pendingMessages.asStateFlow()

  private val _lastImoDiagnostic = MutableStateFlow<ImoSendDiagnostic?>(null)
  val lastImoDiagnostic: StateFlow<ImoSendDiagnostic?> = _lastImoDiagnostic.asStateFlow()

  private val _lastAutomationBanner = MutableStateFlow<String?>(null)
  val lastAutomationBanner: StateFlow<String?> = _lastAutomationBanner.asStateFlow()

  private val _requestedUiPage = MutableStateFlow<String?>(null)
  val requestedUiPage: StateFlow<String?> = _requestedUiPage.asStateFlow()

  private val replyPayloads = ConcurrentHashMap<String, PendingReplyPayload>()
  private val lastSenderReplyTime = ConcurrentHashMap<String, Long>()
  private val recentNotificationHashes = ConcurrentHashMap<String, Long>()

  fun setServiceConnected(connected: Boolean) {
    _isAccessibilityServiceConnected.value = connected
  }

  fun setNotificationListenerConnected(connected: Boolean) {
    _isNotificationListenerConnected.value = connected
  }

  fun updateScreenSnapshot(snapshot: ScreenSnapshot) {
    _latestScreenSnapshot.value = snapshot
  }

  fun updateImoDiagnostic(diagnostic: ImoSendDiagnostic) {
    _lastImoDiagnostic.value = diagnostic
  }

  fun postAutomationBanner(message: String?) {
    _lastAutomationBanner.value = message
  }

  fun requestUiPage(pageKey: String?) {
    _requestedUiPage.value = pageKey
  }

  fun isDuplicateNotification(signature: String, windowMs: Long = 8_000L): Boolean {
    val now = System.currentTimeMillis()
    val previous = recentNotificationHashes[signature]
    recentNotificationHashes[signature] = now
    // Prune stale hashes opportunistically
    if (recentNotificationHashes.size > 100) {
      recentNotificationHashes.entries.removeIf { now - it.value > 60_000L }
    }
    return previous != null && (now - previous) < windowMs
  }

  fun canAutoReplyToSender(senderKey: String, cooldownMs: Long = 15_000L): Boolean {
    val now = System.currentTimeMillis()
    val last = lastSenderReplyTime[senderKey] ?: 0L
    return (now - last) >= cooldownMs
  }

  fun markSenderReplied(senderKey: String) {
    lastSenderReplyTime[senderKey] = System.currentTimeMillis()
  }

  fun registerIncomingMessage(event: IncomingMessageEvent, payload: PendingReplyPayload?) {
    if (payload != null) {
      replyPayloads[event.eventKey] = payload
    }
    _pendingMessages.update { current ->
      (listOf(event) + current.filterNot { it.eventKey == event.eventKey }).take(20)
    }
  }

  fun updateIncomingMessageReplyText(eventKey: String, newReply: String) {
    _pendingMessages.update { list ->
      list.map { item ->
        if (item.eventKey == eventKey) item.copy(suggestedReply = newReply) else item
      }
    }
  }

  fun updateIncomingMessageStatus(eventKey: String, status: IncomingReplyStatus) {
    _pendingMessages.update { list ->
      list.map { item -> if (item.eventKey == eventKey) item.copy(status = status) else item }
    }
    if (status == IncomingReplyStatus.SENT ||
      status == IncomingReplyStatus.AUTO_SENT ||
      status == IncomingReplyStatus.DISMISSED
    ) {
      replyPayloads.remove(eventKey)
    }
  }

  fun getPendingMessage(eventKey: String): IncomingMessageEvent? {
    return _pendingMessages.value.firstOrNull { it.eventKey == eventKey }
  }

  fun getReplyPayload(eventKey: String): PendingReplyPayload? = replyPayloads[eventKey]

  data class OutgoingMessageRecord(
    val appLabel: String,
    val recipientName: String,
    val messageText: String,
    val timestampMs: Long = System.currentTimeMillis(),
  )

  private val _outgoingSentMessages = MutableStateFlow<List<OutgoingMessageRecord>>(emptyList())
  val outgoingSentMessages: StateFlow<List<OutgoingMessageRecord>> = _outgoingSentMessages.asStateFlow()

  fun recordOutgoingMessage(appLabel: String, recipientName: String, messageText: String) {
    val cleanMsg = messageText.trim()
    if (cleanMsg.isEmpty()) return
    val record = OutgoingMessageRecord(
      appLabel = appLabel.ifBlank { "Messaging" },
      recipientName = recipientName.ifBlank { "অন্য প্রান্ত" },
      messageText = cleanMsg,
      timestampMs = System.currentTimeMillis(),
    )
    _outgoingSentMessages.update { current ->
      (listOf(record) + current).take(30)
    }
  }

  fun getRecentOutgoingMessages(withinMs: Long = 10 * 60_000L): List<OutgoingMessageRecord> {
    val cutoff = System.currentTimeMillis() - withinMs
    return _outgoingSentMessages.value.filter { it.timestampMs >= cutoff }
  }

  fun isDuplicateOutgoingEcho(appLabel: String, text: String, windowMs: Long = 90_000L): Boolean {
    val norm = text.trim()
    if (norm.isEmpty()) return false
    val cutoff = System.currentTimeMillis() - windowMs
    return _outgoingSentMessages.value.any { rec ->
      rec.timestampMs >= cutoff &&
        rec.messageText.equals(norm, ignoreCase = true) &&
        (appLabel.isBlank() || rec.appLabel.equals(appLabel, ignoreCase = true))
    }
  }

  fun clearDismissedMessages() {
    _pendingMessages.update { list ->
      list.filter { it.status == IncomingReplyStatus.PENDING_PERMISSION || it.status == IncomingReplyStatus.SENDING }
    }
  }
}
