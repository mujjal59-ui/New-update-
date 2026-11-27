package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TaskExecutionStatus {
  PENDING,
  RUNNING,
  WAITING_FOR_SYSTEM,
  COMPLETED,
  FAILED,
  PARTIALLY_COMPLETED,
  VERIFICATION_REQUIRED,
}

@Entity(tableName = "riya_tasks")
data class RiyaTaskEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val title: String,
  val commandQuery: String,
  val targetAppPackage: String,
  val targetAppLabel: String,
  val status: String = TaskExecutionStatus.PENDING.name,
  val screenReadSummary: String = "",
  val completionMessage: String = "",
  val motivationMessage: String = "",
  val durationMs: Long = 0L,
  val createdAt: Long = System.currentTimeMillis(),
  val completedAt: Long = 0L,
)

@Entity(tableName = "auto_reply_logs")
data class AutoReplyLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val eventKey: String,
  val appPackage: String,
  val appLabel: String,
  val senderName: String,
  val incomingMessage: String,
  val replyMessage: String,
  val status: String, // PENDING_APPROVAL, SENT_APPROVED, AUTO_SENT, DISMISSED, FAILED
  val timestamp: Long = System.currentTimeMillis(),
)

@Entity(tableName = "screen_read_logs")
data class ScreenReadLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val packageName: String,
  val actionTitle: String,
  val nodeCount: Int,
  val summaryText: String,
  val timestamp: Long = System.currentTimeMillis(),
)
