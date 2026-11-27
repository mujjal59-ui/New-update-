package com.example.service

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * OS-scheduled background job executor for LXR--AI managed tasks.
 * Ensures queued background operations continue reliably across app lifecycle changes.
 */
class LxrBackgroundJobService : JobService() {
  private val jobScope =
    CoroutineScope(
      SupervisorJob() +
        Dispatchers.IO +
        CoroutineExceptionHandler { _, _ -> },
    )
  private var activeJob: Job? = null

  override fun onStartJob(params: JobParameters?): Boolean {
    val taskId = params?.extras?.getString("taskId").orEmpty()
    if (taskId.isBlank()) return false
    activeJob =
      jobScope.launch {
        try {
          LxrBackgroundTaskManager.runQueuedJobNow(applicationContext, taskId)
        } finally {
          jobFinished(params, false)
        }
      }
    return true
  }

  override fun onStopJob(params: JobParameters?): Boolean {
    activeJob?.cancel()
    return true
  }
}
