package com.example.service

import android.content.Context
import com.example.data.Store
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Reply Delay and Reply Limit policy execution:
 * 1. Captures the configured delay at wait start so any in-flight wait keeps its original duration
 *    even if the user changes the Reply Delay setting mid-wait.
 * 2. Enforces reply limit quotas (Unlimited, 20 Replies, 50 Replies) thread-safely.
 */
object ReplyPolicyScheduler {
  private val sentCounter = AtomicInteger(0)
  private val _dispatchedCount = MutableStateFlow(0)
  val dispatchedCount: StateFlow<Int> = _dispatchedCount.asStateFlow()

  fun currentDispatchedCount(): Int = sentCounter.get()

  fun canSendReply(maxLimit: Int): Boolean {
    if (maxLimit < 0) return true
    return sentCounter.get() < maxLimit
  }

  /**
   * Reserves/records a reply dispatch if within [maxLimit] (< 0 means Unlimited).
   * Returns true if allowed & recorded, false if limit already reached.
   */
  @Synchronized
  fun tryRecordReply(maxLimit: Int): Boolean {
    if (maxLimit >= 0 && sentCounter.get() >= maxLimit) {
      return false
    }
    val next = sentCounter.incrementAndGet()
    _dispatchedCount.value = next
    return true
  }

  @Synchronized
  fun resetCounter() {
    sentCounter.set(0)
    _dispatchedCount.value = 0
  }

  /**
   * Captures [initialDelaySeconds] before sleeping so later edits to Store.replyDelaySeconds()
   * only affect future waits, never altering an already-running wait.
   */
  suspend fun <T> executeAfterCapturedDelay(
    initialDelaySeconds: Int,
    delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
    onBeforeExecute: (() -> Boolean)? = null,
    block: suspend () -> T,
  ): T? {
    val capturedMs = initialDelaySeconds.coerceAtLeast(0).toLong() * 1000L
    if (capturedMs > 0L) {
      delayFn(capturedMs)
    }
    if (onBeforeExecute != null && !onBeforeExecute()) {
      return null
    }
    return block()
  }

  fun captureDelaySeconds(context: Context): Int = Store.replyDelaySeconds(context)

  fun currentLimitMax(context: Context): Int = Store.replyLimitMax(context)
}
