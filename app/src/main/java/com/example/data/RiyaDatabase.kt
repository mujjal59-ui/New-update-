package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [RiyaTaskEntity::class, AutoReplyLogEntity::class, ScreenReadLogEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class RiyaDatabase : RoomDatabase() {
  abstract fun riyaDao(): RiyaDao

  companion object {
    @Volatile private var instance: RiyaDatabase? = null

    fun getInstance(context: Context): RiyaDatabase {
      return instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
                context.applicationContext,
                RiyaDatabase::class.java,
                "riya_ai_local.db",
              )
              .build()
              .also { instance = it }
        }
    }
  }
}
