package com.example.data

import android.content.Context
import com.example.service.GeminiLive
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

object RiyaState {
  val running = MutableStateFlow(false)
  val resume = MutableStateFlow(0)
  val status = MutableStateFlow("Tap to speak")
  val log = MutableStateFlow<List<String>>(emptyList())
  val lastCompletedTask = MutableStateFlow<String?>(null)
  val motivationalBanner = MutableStateFlow<String?>(null)

  @Volatile var live: GeminiLive? = null
  @Volatile var cameraOn: Boolean = false

  fun addLog(s: String) {
    val clean = s.replace(Regex("\\bPriya\\b", RegexOption.IGNORE_CASE), "Riya AI").replace("প্রিয়া", "রিয়া")
    log.value = (log.value + clean).takeLast(80)
  }

  fun markTaskCompleted(summary: String, motivation: String) {
    lastCompletedTask.value = summary
    motivationalBanner.value = motivation
    addLog("Riya AI: $summary $motivation")
  }
}

object Store {
  const val CAP = 30L * 1024 * 1024 // 30 MB memory cap

  private fun p(c: Context) = c.getSharedPreferences("riya_ai", Context.MODE_PRIVATE)

  fun apiKey(c: Context): String = p(c).getString("key", "") ?: ""

  fun setApiKey(c: Context, v: String) = p(c).edit().putString("key", v.trim()).apply()

  fun model(c: Context): String {
    val m = p(c).getString("model", "gemini-3.1-flash-live-preview") ?: "gemini-3.1-flash-live-preview"
    return if (m.contains("2.0-flash")) "gemini-3.1-flash-live-preview" else m
  }

  fun setModel(c: Context, v: String) =
    p(c).edit().putString("model", v.trim().ifBlank { "gemini-3.1-flash-live-preview" }).apply()

  fun voice(c: Context): String = p(c).getString("voice", "Leda") ?: "Leda"

  fun setVoice(c: Context, v: String) = p(c).edit().putString("voice", v.trim().ifBlank { "Leda" }).apply()

  // Screen Watch সবসময় চালু (বাধ্যতামূলক)
  fun screenWatch(c: Context): Boolean = true

  fun setScreenWatch(c: Context, v: Boolean) {}

  fun autoReplyMaster(c: Context): Boolean = p(c).getBoolean("arMaster", true)

  fun setAutoReplyMaster(c: Context, v: Boolean) = p(c).edit().putBoolean("arMaster", v).apply()

  fun requireBossPermission(c: Context): Boolean = p(c).getBoolean("arAskBoss", true)

  fun setRequireBossPermission(c: Context, v: Boolean) = p(c).edit().putBoolean("arAskBoss", v).apply()

  fun autoReplyWhatsApp(c: Context): Boolean = p(c).getBoolean("arWa", true)

  fun setAutoReplyWhatsApp(c: Context, v: Boolean) = p(c).edit().putBoolean("arWa", v).apply()

  fun autoReplyImo(c: Context): Boolean = p(c).getBoolean("arImo", true)

  fun setAutoReplyImo(c: Context, v: Boolean) = p(c).edit().putBoolean("arImo", v).apply()

  fun autoReplyMessenger(c: Context): Boolean = p(c).getBoolean("arMsg", true)

  fun setAutoReplyMessenger(c: Context, v: Boolean) = p(c).edit().putBoolean("arMsg", v).apply()

  fun design(c: Context): Int = p(c).getInt("design", 0)

  fun setDesign(c: Context, v: Int) = p(c).edit().putInt("design", v).apply()

  fun always(c: Context): Boolean = p(c).getBoolean("always", false)

  fun setAlways(c: Context, v: Boolean) = p(c).edit().putBoolean("always", v).apply()

  private fun f(c: Context) = File(c.filesDir, "memory.txt")

  fun size(c: Context): Long = f(c).let { if (it.exists()) it.length() else 0L }

  fun clear(c: Context) {
    f(c).delete()
  }

  @Synchronized
  fun remember(c: Context, line: String) {
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    val file = f(c)
    val sanitized = line.replace(Regex("\\bPriya\\b", RegexOption.IGNORE_CASE), "Riya AI").replace("প্রিয়া", "রিয়া")
    file.appendText("[$ts] ${sanitized.replace("\n", " ")}\n")
    if (file.length() > CAP) trim(file)
  }

  private fun trim(file: File) {
    val keep = (CAP * 0.8).toLong()
    val rest =
      RandomAccessFile(file, "r").use { r ->
        r.seek(file.length() - keep)
        r.readLine()
        ByteArray((file.length() - r.filePointer).toInt()).also { r.readFully(it) }
      }
    file.writeBytes(rest)
  }

  fun recent(c: Context, max: Int = 20000): String {
    val file = f(c)
    if (!file.exists()) return ""
    return RandomAccessFile(file, "r").use { r ->
      val start = maxOf(0L, r.length() - max)
      r.seek(start)
      ByteArray((r.length() - start).toInt()).also { r.readFully(it) }.toString(Charsets.UTF_8)
    }
  }
}
