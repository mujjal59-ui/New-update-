package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class RiyaRepository(context: Context) {
  private val dao = RiyaDatabase.getInstance(context).riyaDao()
  val preferences = RiyaPreferences(context)

  val tasksFlow: Flow<List<RiyaTaskEntity>> = dao.observeTasks()
  val autoReplyLogsFlow: Flow<List<AutoReplyLogEntity>> = dao.observeAutoReplyLogs()
  val screenReadLogsFlow: Flow<List<ScreenReadLogEntity>> = dao.observeScreenReadLogs()

  suspend fun seedInitialTasksIfEmpty() {
    if (dao.countTasks() == 0) {
      val starterTasks =
        listOf(
          RiyaTaskEntity(
            title = "Infinix Android 16 সেটিংস সার্চ ও অ্যাক্সেসিবিলিটি চেক",
            commandQuery = "Accessibility",
            targetAppPackage = "com.android.settings",
            targetAppLabel = "Infinix Settings",
          ),
          RiyaTaskEntity(
            title = "ইমো (imo) ওপেন করে সেন্ড বাটন প্রিসিশন যাচাই",
            commandQuery = "send_precision_check",
            targetAppPackage = "com.imo.android.imoim",
            targetAppLabel = "imo",
          ),
          RiyaTaskEntity(
            title = "WhatsApp আনরিড মেসেজ ও অটো-রিপ্লাই প্রস্তুতি",
            commandQuery = "check_messages",
            targetAppPackage = "com.whatsapp",
            targetAppLabel = "WhatsApp",
          ),
        )
      starterTasks.forEach { dao.insertTask(it) }
    }
  }

  suspend fun addTask(
    title: String,
    commandQuery: String,
    targetAppPackage: String,
    targetAppLabel: String,
  ): Long {
    return dao.insertTask(
      RiyaTaskEntity(
        title = title.trim(),
        commandQuery = commandQuery.trim(),
        targetAppPackage = targetAppPackage,
        targetAppLabel = targetAppLabel,
      )
    )
  }

  suspend fun updateTask(task: RiyaTaskEntity) = dao.updateTask(task)

  suspend fun deleteTask(taskId: Long) = dao.deleteTaskById(taskId)

  suspend fun logAutoReply(log: AutoReplyLogEntity): Long = dao.insertAutoReplyLog(log)

  suspend fun updateAutoReplyStatus(eventKey: String, status: String, replyMessage: String) {
    dao.updateAutoReplyStatus(eventKey, status, replyMessage)
  }

  suspend fun logScreenRead(
    packageName: String,
    actionTitle: String,
    nodeCount: Int,
    summaryText: String,
  ) {
    dao.insertScreenReadLog(
      ScreenReadLogEntity(
        packageName = packageName,
        actionTitle = actionTitle,
        nodeCount = nodeCount,
        summaryText = summaryText,
      )
    )
  }

  suspend fun clearAllLogs() {
    dao.clearAutoReplyLogs()
    dao.clearScreenReadLogs()
  }
}
