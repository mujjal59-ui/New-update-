package com.example.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Environment
import android.os.PersistableBundle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class LxrBackgroundTaskStatus(val labelBn: String) {
  PENDING("অপেক্ষমাণ"),
  RUNNING("চলমান"),
  WAITING_FOR_SYSTEM("সিস্টেমের জন্য অপেক্ষমাণ"),
  COMPLETED("যাচাইকৃত সম্পন্ন"),
  FAILED("ব্যর্থ"),
  PARTIALLY_COMPLETED("আংশিক সম্পন্ন"),
  VERIFICATION_REQUIRED("যাচাই প্রয়োজন"),
}

data class LxrActivityRecord(
  val taskId: String,
  val command: String,
  val targetApp: String,
  val currentOperation: String,
  val status: LxrBackgroundTaskStatus,
  val progressPercent: Int,
  val retrievedInfo: String = "",
  val actionsCompleted: List<String> = emptyList(),
  val generatedFiles: List<String> = emptyList(),
  val startedAtMs: Long,
  val completedAtMs: Long? = null,
  val verificationNote: String = "",
  val errorDetails: String? = null,
  val receivedAtMs: Long = startedAtMs,
  val executionMode: String = "BACKGROUND",
  val dependsOnTaskId: String? = null,
  val dedupKey: String? = null,
) {
  val executionDurationMs: Long
    get() =
      if (startedAtMs <= 0L) 0L
      else ((completedAtMs ?: System.currentTimeMillis()) - startedAtMs).coerceAtLeast(0L)

  fun toJson(): JSONObject =
    JSONObject()
      .put("taskId", taskId)
      .put("command", command)
      .put("targetApp", targetApp)
      .put("currentOperation", currentOperation)
      .put("status", status.name)
      .put("progressPercent", progressPercent)
      .put("retrievedInfo", retrievedInfo)
      .put("actionsCompleted", JSONArray(actionsCompleted))
      .put("generatedFiles", JSONArray(generatedFiles))
      .put("receivedAtMs", receivedAtMs)
      .put("startedAtMs", startedAtMs)
      .put("completedAtMs", completedAtMs ?: JSONObject.NULL)
      .put("verificationNote", verificationNote)
      .put("errorDetails", errorDetails ?: JSONObject.NULL)
      .put("executionMode", executionMode)
      .put("dependsOnTaskId", dependsOnTaskId ?: JSONObject.NULL)
      .put("dedupKey", dedupKey ?: JSONObject.NULL)

  companion object {
    fun fromJson(obj: JSONObject): LxrActivityRecord {
      val actionsArr = obj.optJSONArray("actionsCompleted")
      val actions =
        if (actionsArr != null) (0 until actionsArr.length()).mapNotNull { actionsArr.optString(it).takeIf { s -> s.isNotBlank() } }
        else emptyList()
      val filesArr = obj.optJSONArray("generatedFiles")
      val files =
        if (filesArr != null) (0 until filesArr.length()).mapNotNull { filesArr.optString(it).takeIf { s -> s.isNotBlank() } }
        else emptyList()
      val statusName = obj.optString("status", LxrBackgroundTaskStatus.PENDING.name)
      val status = LxrBackgroundTaskStatus.entries.firstOrNull { it.name == statusName } ?: LxrBackgroundTaskStatus.VERIFICATION_REQUIRED
      val completedRaw = obj.opt("completedAtMs")
      val completedMs = if (completedRaw is Number) completedRaw.toLong() else null
      val errRaw = obj.optString("errorDetails").takeIf { it.isNotBlank() && it != "null" }
      val startedMs = obj.optLong("startedAtMs", System.currentTimeMillis())
      val receivedMs = obj.optLong("receivedAtMs", startedMs)
      val modeRaw = obj.optString("executionMode", "BACKGROUND").ifBlank { "BACKGROUND" }
      val depRaw = obj.optString("dependsOnTaskId").takeIf { it.isNotBlank() && it != "null" }
      val dedupRaw = obj.optString("dedupKey").takeIf { it.isNotBlank() && it != "null" }
      return LxrActivityRecord(
        taskId = obj.optString("taskId", UUID.randomUUID().toString().take(8)),
        command = obj.optString("command"),
        targetApp = obj.optString("targetApp"),
        currentOperation = obj.optString("currentOperation"),
        status = status,
        progressPercent = obj.optInt("progressPercent", 0).coerceIn(0, 100),
        retrievedInfo = obj.optString("retrievedInfo"),
        actionsCompleted = actions,
        generatedFiles = files,
        startedAtMs = startedMs,
        completedAtMs = completedMs,
        verificationNote = obj.optString("verificationNote"),
        errorDetails = errRaw,
        receivedAtMs = receivedMs,
        executionMode = modeRaw,
        dependsOnTaskId = depRaw,
        dedupKey = dedupRaw,
      )
    }
  }
}

data class LxrParallelTaskSpec(
  val command: String,
  val targetApp: String,
  val operation: String,
  val executionMode: String = "BACKGROUND",
  val dependsOnTaskId: String? = null,
  val dedupKey: String? = null,
  val executor: suspend () -> Pair<String, List<String>>,
)

data class LxrVerificationOutcome(
  val status: LxrBackgroundTaskStatus,
  val verificationNote: String,
  val errorDetails: String? = null,
  val verifiedFiles: List<String> = emptyList(),
)

/**
 * Accuracy & Verification Engine:
 * Never marks a task COMPLETED unless output is non-empty, real, and generated files exist on disk.
 * Unverified or ambiguous results are cleanly marked VERIFICATION_REQUIRED, PARTIALLY_COMPLETED,
 * WAITING_FOR_SYSTEM, or FAILED.
 */
object LxrTaskVerificationEngine {
  private val WAITING_PATTERNS =
    listOf(
      "পারমিশন",
      "permission",
      "চালু নেই",
      "বন্ধ আছে",
      "accessibility",
      "অ্যাক্সেসিবিলিটি",
      "gps সংযোগ নিচ্ছে",
      "অপেক্ষা",
      "confirmed=true",
      "সম্মতি",
      "অনুমতি দিন",
      "অনুমতি চালু",
      "ফ্রেম সংগ্রহ চলছে",
    )

  private val FAILURE_PATTERNS =
    listOf(
      "error:",
      "টুল ত্রুটি",
      "ব্যর্থ",
      "পাওয়া যায়নি",
      "পাওয়া যায়নি",
      "খুঁজে পাইনি",
      "খুঁজে পাওয়া যায়নি",
      "করা যায়নি",
      "করা গেল না",
      "unknown tool",
      "পাইনি",
      "পারিনি",
      "অনুপলব্ধ",
      "নেটওয়ার্ক সমস্যা",
      "সাড়া দিতে পারেনি",
    )

  private val PARTIAL_PATTERNS =
    listOf(
      "আংশিক",
      "সতর্কতা",
      "কিন্তু",
      "খালি",
      "বাকি আছে",
      "এখনো আসেনি",
    )

  fun verifyExecution(
    command: String,
    targetApp: String,
    rawOutput: String,
    candidateFiles: List<String> = emptyList(),
    expectGeneratedFile: Boolean = false,
  ): LxrVerificationOutcome {
    val clean = rawOutput.trim()
    val verifiedFiles =
      candidateFiles
        .map { it.trim() }
        .filter { path ->
          path.isNotBlank() &&
            runCatching {
              val f = File(path)
              f.exists() && f.isFile && f.length() > 0L
            }.getOrDefault(false)
        }

    if (expectGeneratedFile && candidateFiles.isEmpty()) {
      return LxrVerificationOutcome(
        status = LxrBackgroundTaskStatus.VERIFICATION_REQUIRED,
        verificationNote = "ফাইল তৈরি হওয়ার কথা ছিল, কিন্তু কোনো ফাইল পাথ পাওয়া যায়নি — যাচাই প্রয়োজন।",
        errorDetails = "Expected generated file path missing",
      )
    }

    if (candidateFiles.isNotEmpty() && verifiedFiles.size < candidateFiles.size) {
      val missingCount = candidateFiles.size - verifiedFiles.size
      return if (verifiedFiles.isNotEmpty()) {
        LxrVerificationOutcome(
          status = LxrBackgroundTaskStatus.PARTIALLY_COMPLETED,
          verificationNote = "${verifiedFiles.size}টি ফাইল যাচাইকৃত হয়েছে, তবে $missingCount টি ফাইল স্টোরেজে মেলেনি।",
          errorDetails = "Missing $missingCount expected output file(s)",
          verifiedFiles = verifiedFiles,
        )
      } else {
        LxrVerificationOutcome(
          status = LxrBackgroundTaskStatus.VERIFICATION_REQUIRED,
          verificationNote = "ফাইল স্টোরেজে পাওয়া যায়নি বা ০ বাইট — তাই সম্পন্ন হিসেবে চিহ্নিত করা হয়নি।",
          errorDetails = "Generated file not found or empty on disk",
        )
      }
    }

    if (clean.isBlank()) {
      return LxrVerificationOutcome(
        status = LxrBackgroundTaskStatus.VERIFICATION_REQUIRED,
        verificationNote = "কাজ শেষে কোনো আউটপুট টেক্সট পাওয়া যায়নি — ফলাফল যাচাই প্রয়োজন।",
        errorDetails = "Empty execution result",
        verifiedFiles = verifiedFiles,
      )
    }

    val lower = clean.lowercase(Locale.ROOT)
    val hasWaiting = WAITING_PATTERNS.any { lower.contains(it) }
    val hasFailure = FAILURE_PATTERNS.any { lower.contains(it) }
    val hasPartial = PARTIAL_PATTERNS.any { lower.contains(it) }

    return when {
      hasWaiting && !hasFailure ->
        LxrVerificationOutcome(
          status = LxrBackgroundTaskStatus.WAITING_FOR_SYSTEM,
          verificationNote = "সিস্টেম স্টেট, পারমিশন বা বসের সম্মতির জন্য অপেক্ষমাণ।",
          verifiedFiles = verifiedFiles,
        )
      hasFailure && !lower.contains("আগের") && verifiedFiles.isEmpty() ->
        LxrVerificationOutcome(
          status = LxrBackgroundTaskStatus.FAILED,
          verificationNote = "কাজটি সম্পূর্ণ হয়নি — বাস্তব ত্রুটি রেকর্ড করা হয়েছে।",
          errorDetails = clean.take(240),
        )
      hasPartial || (hasFailure && verifiedFiles.isNotEmpty()) ->
        LxrVerificationOutcome(
          status = LxrBackgroundTaskStatus.PARTIALLY_COMPLETED,
          verificationNote = "অংশবিশেষ সম্পন্ন হয়েছে; সম্পূর্ণ ফলাফলের জন্য সতর্কতা অংশ দেখে নাও।",
          errorDetails = if (hasFailure) clean.take(200) else null,
          verifiedFiles = verifiedFiles,
        )
      else ->
        LxrVerificationOutcome(
          status = LxrBackgroundTaskStatus.COMPLETED,
          verificationNote =
            if (verifiedFiles.isNotEmpty()) "যাচাইকৃত: আসল ফলাফল ও ${verifiedFiles.size}টি ফাইল নিশ্চিত হয়েছে।"
            else "যাচাইকৃত: $targetApp/$command এর প্রকৃত ফলাফল নিশ্চিত হয়েছে।",
          verifiedFiles = verifiedFiles,
        )
    }
  }
}

/**
 * Persistent Background Execution & Real-Time Activity Monitor Engine.
 * Supports background coroutine execution + Android JobScheduler scheduling + disk recovery.
 */
object LxrBackgroundTaskManager {
  private const val MAX_RECORDS = 60
  private const val STATE_FILE_NAME = "lxr_activity_monitor.json"
  private const val LOG_DIR_NAME = "LxR/Logs"
  private const val JOB_ID_BASE = 8400

  private val bgScope =
    CoroutineScope(
      SupervisorJob() +
        Dispatchers.IO +
        CoroutineExceptionHandler { _, _ -> },
    )

  private val lock = Any()
  private val loadedContexts = ConcurrentHashMap.newKeySet<String>()
  private val pendingJobExecutors = ConcurrentHashMap<String, suspend () -> Pair<String, List<String>>>()
  private val _activities = MutableStateFlow<List<LxrActivityRecord>>(emptyList())
  val activities: StateFlow<List<LxrActivityRecord>> = _activities.asStateFlow()

  private fun stateFile(ctx: Context): File = File(ctx.applicationContext.filesDir, STATE_FILE_NAME)

  private fun appendMirrorLog(ctx: Context, line: String) {
    runCatching {
      val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
      val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      val targetDir =
        if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs()) && downloadsDir.canWrite()) {
          File(downloadsDir, LOG_DIR_NAME)
        } else {
          File(ctx.applicationContext.filesDir, "lxr_logs")
        }
      if (!targetDir.exists()) targetDir.mkdirs()
      val logFile = File(targetDir, "lxr_activity_log.txt")
      logFile.appendText("[$ts] $line\n", Charsets.UTF_8)
    }
  }

  private fun ensureLoaded(ctx: Context) {
    val key = ctx.applicationContext.filesDir.absolutePath
    if (!loadedContexts.add(key)) return
    synchronized(lock) {
      val file = stateFile(ctx)
      if (!file.exists()) return
      runCatching {
        val raw = file.readText(Charsets.UTF_8)
        if (raw.isBlank()) return
        val arr = JSONArray(raw)
        val parsed =
          (0 until arr.length()).mapNotNull { idx ->
            arr.optJSONObject(idx)?.let { LxrActivityRecord.fromJson(it) }
          }
        _activities.value = parsed.take(MAX_RECORDS)
      }
    }
  }

  private fun persistLocked(ctx: Context) {
    val file = stateFile(ctx)
    val tmp = File(ctx.applicationContext.filesDir, "$STATE_FILE_NAME.tmp")
    val arr = JSONArray()
    _activities.value.forEach { arr.put(it.toJson()) }
    runCatching {
      tmp.writeText(arr.toString(), Charsets.UTF_8)
      if (!tmp.renameTo(file)) {
        file.writeText(arr.toString(), Charsets.UTF_8)
        tmp.delete()
      }
    }
  }

  private fun upsertRecord(ctx: Context, record: LxrActivityRecord) {
    ensureLoaded(ctx)
    synchronized(lock) {
      val current = _activities.value.toMutableList()
      val idx = current.indexOfFirst { it.taskId == record.taskId }
      if (idx >= 0) {
        current[idx] = record
      } else {
        current.add(0, record)
      }
      _activities.value = current.take(MAX_RECORDS)
      persistLocked(ctx)
    }
  }

  fun recoverInterruptedTasks(ctx: Context): Int {
    ensureLoaded(ctx)
    var recovered = 0
    synchronized(lock) {
      val updated =
        _activities.value.map { rec ->
          if (rec.status == LxrBackgroundTaskStatus.RUNNING) {
            recovered++
            rec.copy(
              status = LxrBackgroundTaskStatus.WAITING_FOR_SYSTEM,
              currentOperation = "সিস্টেম/সার্ভিস রিস্টার্টে রিকভার করা হয়েছে",
              verificationNote = "আগের সেশনে চলমান ছিল — রিকভার করে অপেক্ষমাণ রাখা হয়েছে",
            )
          } else {
            rec
          }
        }
      if (recovered > 0) {
        _activities.value = updated
        persistLocked(ctx)
        appendMirrorLog(ctx, "Recovered $recovered interrupted task(s) on startup")
      }
    }
    return recovered
  }

  fun clearAllForTesting(ctx: Context) {
    synchronized(lock) {
      _activities.value = emptyList()
      pendingJobExecutors.clear()
      runCatching { stateFile(ctx).delete() }
    }
  }

  fun createInitialRecord(
    ctx: Context,
    command: String,
    targetApp: String,
    currentOperation: String,
    executionMode: String = "BACKGROUND",
    dependsOnTaskId: String? = null,
    dedupKey: String? = null,
  ): LxrActivityRecord {
    val now = System.currentTimeMillis()
    val rec =
      LxrActivityRecord(
        taskId = "task-${now}-${UUID.randomUUID().toString().take(4)}",
        command = command,
        targetApp = targetApp,
        currentOperation = currentOperation,
        status = LxrBackgroundTaskStatus.PENDING,
        progressPercent = 5,
        startedAtMs = now,
        receivedAtMs = now,
        executionMode = executionMode,
        dependsOnTaskId = dependsOnTaskId,
        dedupKey = dedupKey,
      )
    upsertRecord(ctx, rec)
    appendMirrorLog(ctx, "PENDING [${rec.taskId}] ($executionMode | $targetApp): $command — $currentOperation")
    return rec
  }

  fun updateProgress(
    ctx: Context,
    taskId: String,
    status: LxrBackgroundTaskStatus,
    progressPercent: Int,
    currentOperation: String,
    actionsCompleted: List<String>? = null,
    retrievedInfo: String? = null,
  ) {
    ensureLoaded(ctx)
    synchronized(lock) {
      val existing = _activities.value.firstOrNull { it.taskId == taskId } ?: return
      val updatedStart =
        if (status == LxrBackgroundTaskStatus.RUNNING && existing.status == LxrBackgroundTaskStatus.PENDING) {
          System.currentTimeMillis()
        } else {
          existing.startedAtMs
        }
      val updated =
        existing.copy(
          status = status,
          progressPercent = progressPercent.coerceIn(0, 100),
          currentOperation = currentOperation,
          actionsCompleted = actionsCompleted ?: existing.actionsCompleted,
          retrievedInfo = retrievedInfo ?: existing.retrievedInfo,
          startedAtMs = updatedStart,
        )
      upsertRecord(ctx, updated)
    }
  }

  fun finalizeVerified(
    ctx: Context,
    taskId: String,
    rawOutput: String,
    actionsCompleted: List<String> = emptyList(),
    candidateFiles: List<String> = emptyList(),
    expectGeneratedFile: Boolean = false,
  ): LxrActivityRecord {
    ensureLoaded(ctx)
    val existing =
      _activities.value.firstOrNull { it.taskId == taskId }
        ?: LxrActivityRecord(
          taskId = taskId,
          command = "tool",
          targetApp = "LXR--AI",
          currentOperation = "সম্পন্ন",
          status = LxrBackgroundTaskStatus.RUNNING,
          progressPercent = 80,
          startedAtMs = System.currentTimeMillis(),
        )

    val outcome =
      LxrTaskVerificationEngine.verifyExecution(
        command = existing.command,
        targetApp = existing.targetApp,
        rawOutput = rawOutput,
        candidateFiles = candidateFiles,
        expectGeneratedFile = expectGeneratedFile,
      )
    val finalProgress =
      when (outcome.status) {
        LxrBackgroundTaskStatus.COMPLETED -> 100
        LxrBackgroundTaskStatus.PARTIALLY_COMPLETED -> 75
        LxrBackgroundTaskStatus.WAITING_FOR_SYSTEM -> 60
        LxrBackgroundTaskStatus.VERIFICATION_REQUIRED -> 90
        LxrBackgroundTaskStatus.FAILED -> existing.progressPercent
        else -> existing.progressPercent
      }
    val finalized =
      existing.copy(
        status = outcome.status,
        progressPercent = finalProgress,
        currentOperation = outcome.status.labelBn,
        retrievedInfo = rawOutput.take(600),
        actionsCompleted = (existing.actionsCompleted + actionsCompleted).distinct(),
        generatedFiles = outcome.verifiedFiles,
        completedAtMs = System.currentTimeMillis(),
        verificationNote = outcome.verificationNote,
        errorDetails = outcome.errorDetails,
      )
    upsertRecord(ctx, finalized)
    appendMirrorLog(
      ctx,
      "${finalized.status.name} [${finalized.taskId}] (${finalized.targetApp}): ${finalized.command} -> ${finalized.verificationNote}",
    )
    return finalized
  }

  fun recordAndVerifyToolExecution(
    ctx: Context,
    toolName: String,
    args: JSONObject,
    executionMode: String = "BACKGROUND",
    block: () -> String,
  ): String {
    if (toolName == "get_activity_monitor") {
      return block()
    }
    val targetApp =
      args.optString("app_name")
        .ifBlank { args.optString("setting_name") }
        .ifBlank { inferTargetAppFromTool(toolName) }
    val opDesc = LxrToolWorkDescriber.describeBn(toolName, args)
    val rec =
      createInitialRecord(
        ctx = ctx,
        command = toolName,
        targetApp = targetApp,
        currentOperation = opDesc,
        executionMode = executionMode,
      )
    updateProgress(ctx, rec.taskId, LxrBackgroundTaskStatus.RUNNING, 35, opDesc, listOf("শুরু: $toolName"))

    return try {
      val result = block()
      val files = extractCandidateFilePaths(result)
      val expectFile = toolName == "build_android_app" && result.contains(".apk", ignoreCase = true)
      finalizeVerified(
        ctx = ctx,
        taskId = rec.taskId,
        rawOutput = result,
        actionsCompleted = listOf("টুল সম্পন্ন: $toolName"),
        candidateFiles = files,
        expectGeneratedFile = expectFile,
      )
      result
    } catch (t: Throwable) {
      val errMsg = "টুল ত্রুটি: ${t.message ?: "unknown error"}"
      finalizeVerified(
        ctx = ctx,
        taskId = rec.taskId,
        rawOutput = errMsg,
        actionsCompleted = listOf("ত্রুটি: $toolName"),
      )
      errMsg
    }
  }

  private fun isTerminalStatus(st: LxrBackgroundTaskStatus): Boolean =
    st == LxrBackgroundTaskStatus.COMPLETED ||
      st == LxrBackgroundTaskStatus.FAILED ||
      st == LxrBackgroundTaskStatus.PARTIALLY_COMPLETED ||
      st == LxrBackgroundTaskStatus.VERIFICATION_REQUIRED

  suspend fun awaitTaskCompletion(taskId: String, timeoutMs: Long = 10_000L): LxrActivityRecord? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val rec = _activities.value.firstOrNull { it.taskId == taskId }
      if (rec != null && isTerminalStatus(rec.status)) return rec
      kotlinx.coroutines.delay(20L)
    }
    return _activities.value.firstOrNull { it.taskId == taskId }
  }

  /**
   * Enqueues a background operation onto Android JobScheduler + coroutine background executor
   * with deduplication and optional dependency ordering so work continues reliably outside foreground UI.
   */
  fun enqueueBackgroundTask(
    ctx: Context,
    command: String,
    targetApp: String,
    operation: String,
    scheduleOsJob: Boolean = true,
    executionMode: String = "BACKGROUND",
    dependsOnTaskId: String? = null,
    dedupKey: String? = null,
    executor: (suspend () -> Pair<String, List<String>>)? = null,
  ): LxrActivityRecord {
    val appCtx = ctx.applicationContext
    ensureLoaded(appCtx)
    if (!dedupKey.isNullOrBlank()) {
      synchronized(lock) {
        val activeDup =
          _activities.value.firstOrNull { prev ->
            prev.dedupKey == dedupKey && !isTerminalStatus(prev.status)
          }
        if (activeDup != null) {
          return activeDup
        }
      }
    }
    val rec =
      createInitialRecord(
        ctx = appCtx,
        command = command,
        targetApp = targetApp,
        currentOperation = operation,
        executionMode = executionMode,
        dependsOnTaskId = dependsOnTaskId,
        dedupKey = dedupKey,
      )
    if (executor != null) {
      pendingJobExecutors[rec.taskId] = executor
    }
    var osJobScheduled = false
    if (scheduleOsJob) {
      osJobScheduled = scheduleJobWithOs(appCtx, rec.taskId)
    }
    if (!osJobScheduled && executor != null) {
      bgScope.launch {
        runQueuedJobNow(appCtx, rec.taskId)
      }
    }
    return rec
  }

  fun enqueueParallelTasks(
    ctx: Context,
    specs: List<LxrParallelTaskSpec>,
    scheduleOsJob: Boolean = false,
  ): List<LxrActivityRecord> {
    return specs.map { spec ->
      enqueueBackgroundTask(
        ctx = ctx,
        command = spec.command,
        targetApp = spec.targetApp,
        operation = spec.operation,
        scheduleOsJob = scheduleOsJob,
        executionMode = spec.executionMode,
        dependsOnTaskId = spec.dependsOnTaskId,
        dedupKey = spec.dedupKey,
        executor = spec.executor,
      )
    }
  }

  internal suspend fun runQueuedJobNow(ctx: Context, taskId: String): LxrActivityRecord? {
    val executor = pendingJobExecutors.remove(taskId) ?: return null
    val depId = _activities.value.firstOrNull { it.taskId == taskId }?.dependsOnTaskId
    if (!depId.isNullOrBlank()) {
      updateProgress(
        ctx,
        taskId,
        LxrBackgroundTaskStatus.WAITING_FOR_SYSTEM,
        15,
        "পূর্ববর্তী কাজের ($depId) সমাপ্তির অপেক্ষায়...",
      )
      awaitTaskCompletion(depId, timeoutMs = 15_000L)
    }
    updateProgress(
      ctx,
      taskId,
      LxrBackgroundTaskStatus.RUNNING,
      40,
      "ব্যাকগ্রাউন্ড ইঞ্জিনে প্রসেস চলছে...",
      listOf("ব্যাকগ্রাউন্ড এক্সিকিউটর শুরু"),
    )
    return try {
      val (output, files) = executor()
      finalizeVerified(
        ctx = ctx,
        taskId = taskId,
        rawOutput = output,
        actionsCompleted = listOf("ব্যাকগ্রাউন্ড কাজ সম্পন্ন"),
        candidateFiles = files,
        expectGeneratedFile = files.isNotEmpty(),
      )
    } catch (t: Throwable) {
      finalizeVerified(
        ctx = ctx,
        taskId = taskId,
        rawOutput = "error: ${t.message ?: "background failure"}",
      )
    }
  }

  private fun scheduleJobWithOs(ctx: Context, taskId: String): Boolean {
    return runCatching {
      val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return false
      val extras = PersistableBundle().apply { putString("taskId", taskId) }
      val jobId = JOB_ID_BASE + (taskId.hashCode() and 0x0FFF)
      val info =
        JobInfo.Builder(jobId, ComponentName(ctx, LxrBackgroundJobService::class.java))
          .setOverrideDeadline(1500L)
          .setExtras(extras)
          .build()
      js.schedule(info) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)
  }

  fun queryActivityHistory(ctx: Context, query: String = "", limit: Int = 8): String {
    ensureLoaded(ctx)
    val list = _activities.value
    val voiceSummary = LxrVoiceCommandQueue.buildMetricsSummaryBn(3)
    if (list.isEmpty() && voiceSummary.isBlank()) {
      return "বস, এখন পর্যন্ত LXR Activity Monitor-এ কোনো কাজ রেকর্ড হয়নি।"
    }
    val qLow = query.trim().lowercase(Locale.ROOT)
    val filtered =
      if (qLow.isBlank()) list.take(limit.coerceIn(1, 20))
      else list.filter {
        it.command.lowercase(Locale.ROOT).contains(qLow) ||
          it.targetApp.lowercase(Locale.ROOT).contains(qLow) ||
          it.retrievedInfo.lowercase(Locale.ROOT).contains(qLow)
      }.take(limit.coerceIn(1, 20)).ifEmpty { list.take(limit.coerceIn(1, 20)) }

    val fmt = SimpleDateFormat("hh:mm:ss a", Locale("bn", "BD"))
    return buildString {
      if (filtered.isNotEmpty()) {
        appendLine("📊 LXR Activity Monitor — সাম্প্রতিক ${filtered.size}টি কাজের যাচাইকৃত হিসাব:")
        filtered.forEachIndexed { idx, rec ->
          val recv = fmt.format(Date(rec.receivedAtMs))
          val start = fmt.format(Date(rec.startedAtMs))
          val end = rec.completedAtMs?.let { fmt.format(Date(it)) } ?: "চলছে"
          val dur = rec.executionDurationMs
          appendLine(
            "${idx + 1}. [${rec.executionMode}] [${rec.status.labelBn} • ${rec.progressPercent}%] ${rec.targetApp} → ${rec.command}",
          )
          appendLine("   প্রাপ্ত: $recv | শুরু: $start | শেষ: $end | মোট সময়: ${dur}ms")
          appendLine("   অপারেশন: ${rec.currentOperation}")
          if (rec.retrievedInfo.isNotBlank()) {
            appendLine("   বাস্তব ফলাফল: ${rec.retrievedInfo.replace('\n', ' ').take(160)}")
          }
          if (rec.generatedFiles.isNotEmpty()) {
            appendLine("   তৈরি ফাইল: ${rec.generatedFiles.joinToString(", ")}")
          }
          if (rec.verificationNote.isNotBlank()) {
            appendLine("   যাচাই নোট: ${rec.verificationNote}")
          }
          if (!rec.errorDetails.isNullOrBlank()) {
            appendLine("   ত্রুটি: ${rec.errorDetails}")
          }
        }
      }
      if (voiceSummary.isNotBlank()) {
        if (isNotEmpty()) appendLine()
        appendLine(voiceSummary)
      }
    }.trim()
  }

  private fun inferTargetAppFromTool(toolName: String): String =
    when (toolName) {
      "get_live_location", "open_navigation_directions" -> "GPS / Maps"
      "get_contacts_summary", "search_contact" -> "Contacts"
      "get_call_log_summary", "call_contact" -> "Dialer"
      "facebook_action" -> "Facebook"
      "instagram_action" -> "Instagram"
      "play_youtube" -> "YouTube"
      "inspect_camera_vision", "switch_camera_lens" -> "Camera Vision"
      "build_android_app", "check_termux_build_status", "run_termux_command" -> "Termux / Builder"
      "web_search", "deep_research" -> "Web Research"
      "schedule_exact_reminder" -> "Alarms & Reminders"
      else -> "LXR--AI System"
    }

  private fun extractCandidateFilePaths(output: String): List<String> {
    val regex = Regex("(/[A-Za-z0-9_./\\-]+\\.(?:apk|mp4|txt|json|kt|png|jpg))")
    return regex.findAll(output).map { it.groupValues[1] }.distinct().toList()
  }
}
