package com.example.service

import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VoiceQueueItemStatus(val labelBn: String) {
  QUEUED("কিউতে আছে"),
  GENERATING_RESPONSE("উত্তর তৈরি হচ্ছে"),
  WAITING_DELAY("রিপ্লাই ডিলে অপেক্ষায়"),
  SPEAKING("বলছে"),
  COMPLETED("সম্পন্ন"),
  CANCELLED("বাতিল"),
  DEDUP_SKIPPED("ডুপ্লিকেট বাদ"),
}

data class LxrVoiceStageMetrics(
  val taskId: String,
  val commandText: String,
  val vadToFinalizeMs: Long,
  val apiResponseMs: Long,
  val capturedDelayMs: Long,
  val queueWaitMs: Long,
  val ttsPlaybackMs: Long,
  val totalPipelineMs: Long,
  val timestampMs: Long,
) {
  fun toSummaryBn(): String =
    "[$taskId] VAD=${vadToFinalizeMs}ms | API=${apiResponseMs}ms | Delay=${capturedDelayMs}ms | Queue=${queueWaitMs}ms | Speech=${ttsPlaybackMs}ms | Total=${totalPipelineMs}ms"
}

data class VoiceCommandEntry(
  val taskId: String,
  val normalizedText: String,
  val source: String,
  val enqueuedAtMs: Long,
  val speechEndedAtMs: Long,
  val capturedDelayMs: Long,
  @Volatile var apiFirstByteAtMs: Long = 0L,
  @Volatile var playbackStartedAtMs: Long = 0L,
  @Volatile var completedAtMs: Long = 0L,
  @Volatile var vadDurationMs: Long = 0L,
  @Volatile var status: VoiceQueueItemStatus = VoiceQueueItemStatus.QUEUED,
  @Volatile var replyText: String = "",
) {
  fun toStageMetrics(): LxrVoiceStageMetrics {
    val anchor = if (speechEndedAtMs > 0L) speechEndedAtMs else enqueuedAtMs
    val apiMs = if (apiFirstByteAtMs > anchor) apiFirstByteAtMs - anchor else 0L
    val playStart = if (playbackStartedAtMs > 0L) playbackStartedAtMs else completedAtMs.coerceAtLeast(anchor)
    val qWait =
      if (apiFirstByteAtMs > 0L && playStart > apiFirstByteAtMs) {
        (playStart - apiFirstByteAtMs - capturedDelayMs).coerceAtLeast(0L)
      } else {
        0L
      }
    val end = if (completedAtMs >= playStart) completedAtMs else playStart
    val ttsMs = (end - playStart).coerceAtLeast(0L)
    val total = (end - anchor).coerceAtLeast(0L)
    return LxrVoiceStageMetrics(
      taskId = taskId,
      commandText = normalizedText,
      vadToFinalizeMs = vadDurationMs.coerceAtLeast(0L),
      apiResponseMs = apiMs,
      capturedDelayMs = capturedDelayMs,
      queueWaitMs = qWait,
      ttsPlaybackMs = ttsMs,
      totalPipelineMs = total,
      timestampMs = end,
    )
  }
}

data class VoiceEnqueueResult(
  val accepted: Boolean,
  val entry: VoiceCommandEntry,
  val duplicateOfTaskId: String? = null,
)

/**
 * Thread-safe FIFO Voice Command & Response Queue for LXR--AI v2.39.0:
 * - Assigns a unique Task ID per spoken or text command
 * - Deduplicates rapid identical duplicate commands
 * - Captures Reply Delay immutably per command (Instant=0ms, Fast=8000ms, Normal=15000ms)
 * - Guarantees strict FIFO response delivery order without blocking mic capture
 * - Records detailed per-stage timing telemetry (Speech/VAD, API, Delay, Queue, TTS/Audio)
 */
class LxrVoiceCommandQueue(
  private val dedupWindowMs: Long = 1_500L,
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  private val lock = Any()
  private val seq = AtomicInteger(0)
  private val fifoPending = ArrayDeque<VoiceCommandEntry>()
  private val history = ArrayDeque<VoiceCommandEntry>()
  private val metricsHistory = ArrayDeque<LxrVoiceStageMetrics>()

  private val workerScope =
    CoroutineScope(
      SupervisorJob() +
        Dispatchers.IO +
        CoroutineExceptionHandler { _, _ -> },
    )

  private data class SerialJob(
    val entry: VoiceCommandEntry,
    val delayFn: suspend (Long) -> Unit,
    val canSendFn: () -> Boolean,
    val deliverFn: suspend (VoiceCommandEntry) -> String,
  )

  private val serialChannel = Channel<SerialJob>(Channel.UNLIMITED)

  init {
    workerScope.launch {
      for (job in serialChannel) {
        executeSerialJob(job)
      }
    }
  }

  private suspend fun executeSerialJob(job: SerialJob) {
    val e = job.entry
    if (e.status == VoiceQueueItemStatus.CANCELLED) return
    e.status = VoiceQueueItemStatus.GENERATING_RESPONSE
    if (e.apiFirstByteAtMs == 0L) e.apiFirstByteAtMs = clock()
    if (e.capturedDelayMs > 0L) {
      e.status = VoiceQueueItemStatus.WAITING_DELAY
      job.delayFn(e.capturedDelayMs)
    }
    if (e.status == VoiceQueueItemStatus.CANCELLED || !job.canSendFn()) {
      if (e.status != VoiceQueueItemStatus.CANCELLED) {
        e.status = VoiceQueueItemStatus.CANCELLED
      }
      e.completedAtMs = clock()
      return
    }
    e.status = VoiceQueueItemStatus.SPEAKING
    e.playbackStartedAtMs = clock()
    val reply =
      try {
        job.deliverFn(e)
      } catch (_: Throwable) {
        ""
      }
    markCompleted(e, replyText = reply, nowMs = clock())
  }

  fun enqueueCommand(
    rawText: String,
    source: String = "VOICE",
    capturedDelayMs: Long,
    speechEndedAtMs: Long = clock(),
    vadDurationMs: Long = 0L,
  ): VoiceEnqueueResult {
    val now = clock()
    val norm = rawText.trim().replace(Regex("\\s+"), " ")
    synchronized(lock) {
      // Deduplicate only when a real non-placeholder text matches an active or very recent command
      if (norm.isNotBlank() && !norm.startsWith("[VOICE_TURN")) {
        val dup =
          history.firstOrNull { prev ->
            prev.status != VoiceQueueItemStatus.CANCELLED &&
              prev.normalizedText.equals(norm, ignoreCase = true) &&
              (now - prev.enqueuedAtMs) <= dedupWindowMs
          }
        if (dup != null) {
          return VoiceEnqueueResult(
            accepted = false,
            entry = dup,
            duplicateOfTaskId = dup.taskId,
          )
        }
      }

      val taskId = "vcmd-${seq.incrementAndGet()}-${UUID.randomUUID().toString().take(4)}"
      val entry =
        VoiceCommandEntry(
          taskId = taskId,
          normalizedText = norm.ifBlank { "[VOICE_TURN_${seq.get()}]" },
          source = source,
          enqueuedAtMs = now,
          speechEndedAtMs = speechEndedAtMs,
          capturedDelayMs = capturedDelayMs.coerceAtLeast(0L),
          vadDurationMs = vadDurationMs.coerceAtLeast(0L),
        )
      fifoPending.addLast(entry)
      history.addFirst(entry)
      while (history.size > 40) history.removeLast()
      return VoiceEnqueueResult(accepted = true, entry = entry)
    }
  }

  /**
   * Non-blocking serial queue submission: Command 1, Command 2, Command N can be submitted
   * concurrently while microphone capture remains active; replies execute strictly in FIFO order.
   */
  fun enqueueSerialReply(
    rawText: String,
    source: String = "VOICE",
    capturedDelayMs: Long,
    speechEndedAtMs: Long = clock(),
    delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
    canSendFn: () -> Boolean = { true },
    deliverFn: suspend (VoiceCommandEntry) -> String,
  ): VoiceEnqueueResult {
    val res =
      enqueueCommand(
        rawText = rawText,
        source = source,
        capturedDelayMs = capturedDelayMs,
        speechEndedAtMs = speechEndedAtMs,
      )
    if (res.accepted) {
      synchronized(lock) { fifoPending.remove(res.entry) }
      serialChannel.trySend(SerialJob(res.entry, delayFn, canSendFn, deliverFn))
    }
    return res
  }

  /**
   * Pops the oldest pending user command to pair with the next incoming Gemini Live response turn.
   */
  fun popNextPendingForTurn(
    defaultDelayMs: Long = 0L,
    nowMs: Long = clock(),
  ): VoiceCommandEntry {
    synchronized(lock) {
      val next =
        if (fifoPending.isNotEmpty()) fifoPending.removeFirst()
        else {
          VoiceCommandEntry(
            taskId = "vcmd-${seq.incrementAndGet()}-${UUID.randomUUID().toString().take(4)}",
            normalizedText = "[PROACTIVE_OR_SYSTEM_TURN]",
            source = "SYSTEM",
            enqueuedAtMs = nowMs,
            speechEndedAtMs = nowMs,
            capturedDelayMs = defaultDelayMs.coerceAtLeast(0L),
          ).also {
            history.addFirst(it)
            while (history.size > 40) history.removeLast()
          }
        }
      if (next.apiFirstByteAtMs == 0L) next.apiFirstByteAtMs = nowMs
      if (next.capturedDelayMs > 0L) {
        next.status = VoiceQueueItemStatus.WAITING_DELAY
      } else {
        next.status = VoiceQueueItemStatus.GENERATING_RESPONSE
      }
      return next
    }
  }

  fun computeRemainingDelayMs(entry: VoiceCommandEntry, nowMs: Long = clock()): Long {
    if (entry.capturedDelayMs <= 0L) return 0L
    val anchor = if (entry.speechEndedAtMs > 0L) entry.speechEndedAtMs else entry.enqueuedAtMs
    val targetAt = anchor + entry.capturedDelayMs
    return (targetAt - nowMs).coerceAtLeast(0L)
  }

  fun markPlaybackStarted(entry: VoiceCommandEntry, nowMs: Long = clock()) {
    synchronized(lock) {
      if (entry.playbackStartedAtMs == 0L) {
        entry.playbackStartedAtMs = nowMs
      }
      entry.status = VoiceQueueItemStatus.SPEAKING
    }
  }

  fun markCompleted(
    entry: VoiceCommandEntry,
    replyText: String = "",
    nowMs: Long = clock(),
  ): LxrVoiceStageMetrics {
    val metrics: LxrVoiceStageMetrics
    synchronized(lock) {
      if (entry.playbackStartedAtMs == 0L) {
        entry.playbackStartedAtMs = nowMs
      }
      entry.completedAtMs = maxOf(nowMs, entry.playbackStartedAtMs)
      entry.replyText = replyText
      if (entry.status != VoiceQueueItemStatus.CANCELLED) {
        entry.status = VoiceQueueItemStatus.COMPLETED
      }
      metrics = entry.toStageMetrics()
      metricsHistory.addFirst(metrics)
      while (metricsHistory.size > 40) metricsHistory.removeLast()
    }
    publishGlobalMetrics(metrics)
    return metrics
  }

  fun cancelPendingOnInterrupt(nowMs: Long = clock()): Int {
    var cancelled = 0
    synchronized(lock) {
      while (fifoPending.isNotEmpty()) {
        val e = fifoPending.removeFirst()
        e.status = VoiceQueueItemStatus.CANCELLED
        e.completedAtMs = nowMs
        cancelled++
      }
    }
    return cancelled
  }

  fun pendingCount(): Int = synchronized(lock) { fifoPending.size }

  fun recentMetrics(limit: Int = 10): List<LxrVoiceStageMetrics> =
    synchronized(lock) { metricsHistory.take(limit.coerceIn(1, 40)) }

  fun recentHistory(limit: Int = 10): List<VoiceCommandEntry> =
    synchronized(lock) { history.take(limit.coerceIn(1, 40)) }

  companion object {
    private val _liveMetrics = MutableStateFlow<List<LxrVoiceStageMetrics>>(emptyList())
    val liveMetrics: StateFlow<List<LxrVoiceStageMetrics>> = _liveMetrics.asStateFlow()

    fun publishGlobalMetrics(m: LxrVoiceStageMetrics) {
      _liveMetrics.value = (listOf(m) + _liveMetrics.value).take(25)
    }

    fun clearGlobalMetricsForTesting() {
      _liveMetrics.value = emptyList()
    }

    fun buildMetricsSummaryBn(limit: Int = 5): String {
      val list = _liveMetrics.value.take(limit.coerceIn(1, 10))
      if (list.isEmpty()) return ""
      return buildString {
        appendLine("⏱️ ভয়েস পাইপলাইন ল্যাটেন্সি ও কিউ রিপোর্ট (সাম্প্রতিক ${list.size}টি):")
        list.forEachIndexed { idx, m ->
          appendLine("${idx + 1}. ${m.toSummaryBn()}")
        }
      }.trim()
    }

    fun captureDelayMs(ctx: Context): Long = ReplyPolicyScheduler.captureVoiceDelayMs(ctx)
  }
}
