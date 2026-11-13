package com.example.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class TaskCompletionInsight(
  val completionAnnouncement: String,
  val motivationalPrompt: String,
  val suggestedNextTasks: List<String>,
)

class RiyaAiRepository {
  companion object {
    private const val TAG = "RiyaAiRepository"

    private const val SYSTEM_INSTRUCTION =
      """
      আপনি 'Riya AI' (রিয়া এআই) — ব্যবহারকারীর ('বস') বিশ্বস্ত, বিনয়ী ও অত্যন্ত দক্ষ বাংলা ভয়েস ও অ্যাক্সেসিবিলিটি অ্যাসিস্ট্যান্ট।
      কঠোর নিয়ম:
      ১) কখনোই নিজের নাম ভুল করে প্রিয়া বা অন্য কিছু বলবেন না; আপনার নাম সবসময় Riya AI।
      ২) ব্যবহারকারীকে সবসময় সম্মান দিয়ে 'বস' বলে সম্বোধন করবেন।
      ৩) সব উত্তর পরিষ্কার, প্রাকৃতিক, শুদ্ধ বাংলায় এবং স্পষ্ট উচ্চারণযোগ্য ছোট বাক্যে দেবেন—কোনো এলোমেলো শব্দ, মার্কডাউন তারকাচিহ্ন (** বা #) বা ইমোজি ব্যবহার করবেন না যাতে ভয়েস উচ্চারণ একদম স্পষ্ট থাকে।
      ৪) ব্যবহারকারীর ফোন হলো Infinix Hot 60 Pro+ (Android 16 / XOS)। কোনো অ্যাপ বা সেটিংস খুঁজে পেতে বললে আগে ওই অ্যাপের ওপরের সার্চ বার/সার্চ আইকন ব্যবহার করার নিয়ম ও XOS পাথ অনুসরণ করবেন।
      ৫) imo (ইমো)-তে মেসেজ পাঠানোর সময় শেষের ইমোজি বাটনের বদলে টেক্সট বক্সের পাশের আসল সেন্ড বাটন ক্লিক করার নিয়ম অনুসরণ করবেন।
      ৬) কোনো কাজ শেষ হলে পরিষ্কারভাবে 'হয়েছে, বস! কাজ শেষ।' বলবেন এবং পরের কাজের জন্য উৎসাহ দেবেন।
      """
  }

  private val chatModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      systemInstruction = content { text(SYSTEM_INSTRUCTION) },
    )
  }

  private val fastModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.5-flash-lite",
      systemInstruction = content { text(SYSTEM_INSTRUCTION) },
    )
  }

  private val completionSchema by lazy {
    Schema.obj(
      properties = mapOf(
        "completionAnnouncement" to Schema.string(
          description = "Short Bengali confirmation starting with হয়েছে, বস! কাজ শেষ।"
        ),
        "motivationalPrompt" to Schema.string(
          description = "Warm motivating Bengali sentence encouraging Boss to take the next task."
        ),
        "suggestedNextTasks" to Schema.array(
          items = Schema.string(),
          description = "3 short actionable next tasks in Bengali.",
        ),
      )
    )
  }

  private val completionModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.5-flash-lite",
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = completionSchema
      },
      systemInstruction = content { text(SYSTEM_INSTRUCTION) },
    )
  }

  fun streamAssistantReply(
    userPrompt: String,
    screenSummary: String,
    accessibilityActive: Boolean,
  ): Flow<String> = flow {
    val enrichedPrompt = buildString {
      appendLine("বসের নির্দেশ: $userPrompt")
      appendLine("অ্যাক্সেসিবিলিটি স্ট্যাটাস: ${if (accessibilityActive) "চালু আছে" else "বন্ধ আছে (বসকে চালু করতে বলুন)"}")
      if (screenSummary.isNotBlank()) {
        appendLine("বর্তমান স্ক্রিনে যা দেখা যাচ্ছে: $screenSummary")
      }
      appendLine("সংক্ষিপ্ত, স্পষ্ট ও প্রাকৃতিক বাংলায় উত্তর দিন (কোনো ** বা ইমোজি ছাড়া)।")
    }

    val accumulated = StringBuilder()
    try {
      withTimeout(45_000L) {
        chatModel.generateContentStream(enrichedPrompt).collect { chunk ->
          val part = chunk.text.orEmpty()
          if (part.isNotEmpty()) {
            accumulated.append(part)
            emit(accumulated.toString())
          }
        }
      }
    } catch (e: TimeoutCancellationException) {
      Log.e(TAG, "Gemini stream timed out", e)
      throw e
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Gemini stream failed", e)
      throw e
    }
  }

  suspend fun generateSmartAutoReply(
    appLabel: String,
    senderName: String,
    incomingMessage: String,
  ): Result<String> {
    return try {
      val prompt =
        """
        $appLabel-এ $senderName মেসেজ পাঠিয়েছেন: "$incomingMessage"
        বসের পক্ষ থেকে Riya AI হিসেবে ১ থেকে ২ লাইনের ভদ্র, স্পষ্ট ও প্রাকৃতিক বাংলা উত্তর লিখে দিন। কোনো মার্কডাউন বা ইমোজি দেবেন না।
        """.trimIndent()
      val response = withTimeout(30_000L) { fastModel.generateContent(prompt) }
      val reply = response.text?.trim()
      if (!reply.isNullOrBlank()) {
        Result.success(reply)
      } else {
        Result.failure(IllegalStateException("মডেল থেকে কোনো রিপ্লাই টেক্সট পাওয়া যায়নি।"))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Auto-reply generation failed", e)
      Result.failure(e)
    }
  }

  suspend fun generateTaskCompletionInsight(
    completedTaskTitle: String,
    screenSummary: String,
  ): Result<TaskCompletionInsight> {
    return try {
      val prompt =
        """
        বসের কাজ সম্পন্ন হয়েছে: "$completedTaskTitle"
        স্ক্রিন রিড তথ্য: ${screenSummary.take(400)}
        ১) হয়েছে/কাজ শেষ নিশ্চিত করে বলুন।
        ২) এরপর আরেকটি কাজ করার জন্য বসকে সুন্দরভাবে অনুপ্রাণিত করুন।
        ৩) পরের সম্ভাব্য ৩টি ছোট বাংলা টাস্ক সাজেস্ট করুন।
        """.trimIndent()
      val response = withTimeout(30_000L) { completionModel.generateContent(prompt) }
      val raw = response.text ?: throw IllegalStateException("Empty structured response")
      val json = JSONObject(raw)
      val arr = json.optJSONArray("suggestedNextTasks")
      val suggestions =
        if (arr != null) {
          (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
        } else {
          emptyList()
        }
      Result.success(
        TaskCompletionInsight(
          completionAnnouncement =
            json.optString("completionAnnouncement", "হয়েছে, বস! কাজ শেষ।"),
          motivationalPrompt =
            json.optString(
              "motivationalPrompt",
              "দারুণ গতি বজায় রেখেছেন বস! চলুন এখন পরের গুরুত্বপূর্ণ কাজটি সেরে ফেলি।",
            ),
          suggestedNextTasks =
            suggestions.ifEmpty {
              listOf(
                "WhatsApp ও imo মেসেজ চেক করুন",
                "Infinix সেটিংস থেকে ব্যাটারি স্ট্যাটাস দেখুন",
                "নতুন একটি দ্রুত টাস্ক শুরু করুন",
              )
            },
        )
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Task completion insight generation failed", e)
      Result.failure(e)
    }
  }
}
