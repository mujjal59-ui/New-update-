package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RiyaDao {
  @Query("SELECT * FROM riya_tasks ORDER BY createdAt DESC")
  fun observeTasks(): Flow<List<RiyaTaskEntity>>

  @Query("SELECT COUNT(*) FROM riya_tasks")
  suspend fun countTasks(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTask(task: RiyaTaskEntity): Long

  @Update
  suspend fun updateTask(task: RiyaTaskEntity)

  @Query("DELETE FROM riya_tasks WHERE id = :taskId")
  suspend fun deleteTaskById(taskId: Long)

  @Query("SELECT * FROM auto_reply_logs ORDER BY timestamp DESC LIMIT 50")
  fun observeAutoReplyLogs(): Flow<List<AutoReplyLogEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAutoReplyLog(log: AutoReplyLogEntity): Long

  @Query("UPDATE auto_reply_logs SET status = :status, replyMessage = :replyMessage WHERE eventKey = :eventKey")
  suspend fun updateAutoReplyStatus(eventKey: String, status: String, replyMessage: String)

  @Query("DELETE FROM auto_reply_logs")
  suspend fun clearAutoReplyLogs()

  @Query("SELECT * FROM screen_read_logs ORDER BY timestamp DESC LIMIT 40")
  fun observeScreenReadLogs(): Flow<List<ScreenReadLogEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertScreenReadLog(log: ScreenReadLogEntity): Long

  @Query("DELETE FROM screen_read_logs")
  suspend fun clearScreenReadLogs()
}
