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
  private val recentHandledIncomingSignatures = ConcurrentHashMap<String, Long>()
  private val recentVoiceCallSignatures = ConcurrentHashMap<String, Long>()

  data class IncomingVoiceCallEvent(
    val callKey: String,
    val appLabel: String,
    val callerName: String,
    val summaryBn: String,
    val timestampMs: Long = System.currentTimeMillis(),
  )

  private val _detectedVoiceCalls = MutableStateFlow<List<IncomingVoiceCallEvent>>(emptyList())
  val detectedVoiceCalls: StateFlow<List<IncomingVoiceCallEvent>> = _detectedVoiceCalls.asStateFlow()

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

  private fun normSig(s: String): String =
    s.trim().lowercase().replace(Regex("\\s+"), " ")

  fun isDuplicateNotification(signature: String, windowMs: Long = 120_000L): Boolean {
    val normKey = normSig(signature)
    val now = System.currentTimeMillis()
    val previous = recentNotificationHashes[normKey]
    if (previous != null && (now - previous) < windowMs) {
      return true
    }
    recentNotificationHashes[normKey] = now
    if (recentNotificationHashes.size > 150) {
      recentNotificationHashes.entries.removeIf { now - it.value > 15 * 60_000L }
    }
    return false
  }

  fun hasRecentlyHandledIncomingMessage(
    appLabel: String,
    senderName: String,
    messageText: String,
    windowMs: Long = 10 * 60_000L,
  ): Boolean {
    val key = "${normSig(appLabel)}|${normSig(senderName)}|${normSig(messageText)}"
    val prev = recentHandledIncomingSignatures[key] ?: return false
    return (System.currentTimeMillis() - prev) < windowMs
  }

  fun markIncomingMessageHandled(appLabel: String, senderName: String, messageText: String) {
    val now = System.currentTimeMillis()
    val key = "${normSig(appLabel)}|${normSig(senderName)}|${normSig(messageText)}"
    recentHandledIncomingSignatures[key] = now
    if (recentHandledIncomingSignatures.size > 150) {
      recentHandledIncomingSignatures.entries.removeIf { now - it.value > 30 * 60_000L }
    }
  }

  fun canAutoReplyToSender(senderKey: String, cooldownMs: Long = 45_000L): Boolean {
    val now = System.currentTimeMillis()
    val last = lastSenderReplyTime[normSig(senderKey)] ?: 0L
    return (now - last) >= cooldownMs
  }

  fun markSenderReplied(senderKey: String) {
    lastSenderReplyTime[normSig(senderKey)] = System.currentTimeMillis()
  }

  /**
   * Records a missed or incoming voice/video call cleanly, returning the newly recorded event
   * or null if this exact caller + app call alert was already recorded recently.
   */
  fun recordDetectedVoiceCall(
    appLabel: String,
    callerName: String,
    summaryBn: String,
    dedupWindowMs: Long = 3 * 60_000L,
  ): IncomingVoiceCallEvent? {
    val cleanCaller = callerName.trim().ifBlank { "কন্টাক্ট" }
    val cleanApp = appLabel.trim().ifBlank { "Messaging" }
    val sig = "${normSig(cleanApp)}|${normSig(cleanCaller)}|${normSig(summaryBn)}"
    val now = System.currentTimeMillis()
    val prev = recentVoiceCallSignatures[sig]
    if (prev != null && (now - prev) < dedupWindowMs) {
      return null
    }
    recentVoiceCallSignatures[sig] = now
    if (recentVoiceCallSignatures.size > 80) {
      recentVoiceCallSignatures.entries.removeIf { now - it.value > 30 * 60_000L }
    }
    val ev =
      IncomingVoiceCallEvent(
        callKey = "${cleanApp}_${now}_${cleanCaller.hashCode()}",
        appLabel = cleanApp,
        callerName = cleanCaller,
        summaryBn = summaryBn,
        timestampMs = now,
      )
    _detectedVoiceCalls.update { current -> (listOf(ev) + current).take(15) }
    return ev
  }

  fun dhakaTodayStartMs(nowMs: Long = System.currentTimeMillis()): Long {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Dhaka")).apply {
      timeInMillis = nowMs
      set(java.util.Calendar.HOUR_OF_DAY, 0)
      set(java.util.Calendar.MINUTE, 0)
      set(java.util.Calendar.SECOND, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
  }

  fun isTodayInDhaka(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
    val start = dhakaTodayStartMs(nowMs)
    return timestampMs in start..(start + 86_400_000L)
  }

  fun formatDhakaClockTime(timestampMs: Long): String {
    val fmt = java.text.SimpleDateFormat("hh:mm a (dd MMM)", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Dhaka")
    return fmt.format(java.util.Date(timestampMs))
  }

  fun getRecentVoiceCalls(
    withinMs: Long = 24 * 60 * 60_000L,
    todayOnly: Boolean = true,
    nowMs: Long = System.currentTimeMillis(),
  ): List<IncomingVoiceCallEvent> {
    val cutoff = nowMs - withinMs
    return _detectedVoiceCalls.value.filter { ev ->
      val recent = ev.timestampMs >= cutoff
      if (todayOnly) recent && isTodayInDhaka(ev.timestampMs, nowMs) else recent
    }
  }

  fun getTodayPendingOrRecentMessages(
    appFilter: String = "",
    todayOnly: Boolean = true,
    nowMs: Long = System.currentTimeMillis(),
  ): List<IncomingMessageEvent> {
    val cleanApp = appFilter.trim().lowercase()
    return _pendingMessages.value.filter { ev ->
      val appOk = cleanApp.isBlank() || ev.appLabel.lowercase().contains(cleanApp)
      val dayOk = !todayOnly || isTodayInDhaka(ev.timestamp, nowMs)
      appOk && dayOk
    }
  }

  fun formatRecentActivityReportBn(
    appFilter: String = "",
    todayOnly: Boolean = true,
    nowMs: Long = System.currentTimeMillis(),
  ): String {
    val msgs = getTodayPendingOrRecentMessages(appFilter, todayOnly, nowMs)
    val calls = getRecentVoiceCalls(todayOnly = todayOnly, nowMs = nowMs).filter { c ->
      appFilter.isBlank() || c.appLabel.lowercase().contains(appFilter.trim().lowercase())
    }
    val scopeLabel = if (todayOnly) "আজকের (Asia/Dhaka)" else "সাম্প্রতিক"
    return buildString {
      if (msgs.isEmpty() && calls.isEmpty()) {
        append("$scopeLabel কোনো নতুন WhatsApp/imo/Messenger মেসেজ বা ইন-অ্যাপ কল পাওয়া যায়নি বস।")
      } else {
        if (msgs.isNotEmpty()) {
          appendLine("📩 $scopeLabel মেসেজ (${msgs.size}টি):")
          msgs.take(10).forEachIndexed { idx, m ->
            appendLine("${idx + 1}. [${m.appLabel}] ${m.senderName} (${formatDhakaClockTime(m.timestamp)}): \"${m.messageText}\" [স্ট্যাটাস: ${m.status.name}]")
          }
        }
        if (calls.isNotEmpty()) {
          appendLine("📞 $scopeLabel ভয়েস/ভিডিও কল (${calls.size}টি):")
          calls.take(8).forEachIndexed { idx, c ->
            appendLine("${idx + 1}. [${c.appLabel}] ${c.callerName} (${formatDhakaClockTime(c.timestampMs)}): ${c.summaryBn}")
          }
        }
      }
    }.trim()
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

  fun isDuplicateOutgoingToRecipient(
    appLabel: String,
    recipientName: String,
    messageText: String,
    windowMs: Long = 90_000L,
  ): Boolean {
    val normMsg = normSig(messageText)
    val normRec = normSig(recipientName)
    if (normMsg.isEmpty()) return false
    val cutoff = System.currentTimeMillis() - windowMs
    return _outgoingSentMessages.value.any { rec ->
      rec.timestampMs >= cutoff &&
        normSig(rec.messageText) == normMsg &&
        (appLabel.isBlank() || rec.appLabel.equals(appLabel, ignoreCase = true)) &&
        (normRec.isBlank() || normSig(rec.recipientName) == normRec || rec.recipientName.equals("অন্য প্রান্ত"))
    }
  }

  fun isDuplicateOutgoingEcho(appLabel: String, text: String, windowMs: Long = 90_000L): Boolean {
    val norm = normSig(text)
    if (norm.isEmpty()) return false
    val cutoff = System.currentTimeMillis() - windowMs
    return _outgoingSentMessages.value.any { rec ->
      rec.timestampMs >= cutoff &&
        normSig(rec.messageText) == norm &&
        (appLabel.isBlank() || rec.appLabel.equals(appLabel, ignoreCase = true))
    }
  }

  fun clearDismissedMessages() {
    _pendingMessages.update { list ->
      list.filter { it.status == IncomingReplyStatus.PENDING_PERMISSION || it.status == IncomingReplyStatus.SENDING }
    }
  }
}
