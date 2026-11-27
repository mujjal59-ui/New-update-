package com.example.service

import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.Rect

data class ScreenNodeInfo(
  val text: String,
  val contentDescription: String,
  val viewId: String,
  val className: String,
  val isClickable: Boolean,
  val isEditable: Boolean,
  val isScrollable: Boolean,
  val bounds: Rect,
)

data class ScreenSnapshot(
  val packageName: String = "",
  val windowCount: Int = 0,
  val timestamp: Long = System.currentTimeMillis(),
  val totalNodes: Int = 0,
  val clickableCount: Int = 0,
  val editableCount: Int = 0,
  val summaryLines: List<String> = emptyList(),
  val nodes: List<ScreenNodeInfo> = emptyList(),
)

data class ImoSendDiagnostic(
  val inputFound: Boolean,
  val inputBoundsDescription: String,
  val excludedEmojiOrMediaNodes: List<String>,
  val selectedSendNodeDescription: String,
  val strategyUsed: String,
  val succeeded: Boolean,
)

enum class IncomingReplyStatus {
  PENDING_PERMISSION,
  SENDING,
  SENT,
  AUTO_SENT,
  DISMISSED,
  FAILED,
}

data class IncomingMessageEvent(
  val eventKey: String,
  val packageName: String,
  val appLabel: String,
  val senderName: String,
  val messageText: String,
  val suggestedReply: String,
  val timestamp: Long = System.currentTimeMillis(),
  val hasRemoteInput: Boolean = false,
  val status: IncomingReplyStatus = IncomingReplyStatus.PENDING_PERMISSION,
)

data class PendingReplyPayload(
  val eventKey: String,
  val packageName: String,
  val appLabel: String,
  val senderName: String,
  val messageText: String,
  val remoteInput: RemoteInput?,
  val replyIntent: PendingIntent?,
  val notificationId: Int,
)
