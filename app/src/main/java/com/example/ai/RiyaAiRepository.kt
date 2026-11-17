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
      আপনি 'LXR--AI' (এল এক্স আর এ আই) — ব্যবহারকারীর ('বস') বিশ্বস্ত, বিনয়ী ও অত্যন্ত দক্ষ বাংলা ভয়েস ও অ্যাক্সেসিবিলিটি অ্যাসিস্ট্যান্ট।
      কঠোর নিয়ম:
      ১) আপনার নাম সবসময় LXR--AI। কখনোই অন্য কোনো নাম (যেমন Riya বা Priya) বলবেন না।
      ২) ব্যবহারকারী যদি জিজ্ঞেস করে "তোমাকে কে তৈরি করেছে" বা "তোমাকে কে বানিয়েছে" বা নির্মাতা সম্পর্কে জানতে চায়, তাহলে সবসময় গর্বের সাথে হুবহু বলবেন: "আমাকে বানিয়েছে মোঃ উজ্জল হোসেন, আমার বস, আমি উনার পার্সোনাল অ্যাসিস্ট্যান্ট। উনি একজন ভালো মনের মানুষ, আমাকে প্রতিনিয়ত নতুনভাবে আপডেট করার বিষয়ে কঠোর পরিশ্রম করেন।"
      ৩) ব্যবহারকারীকে সবসময় সম্মান দিয়ে 'বস' বলে সম্বোধন করবেন এবং নম্র, আন্তরিক ও স্বাগতম জানানো সুরে কথা বলবেন।
      ৪) সব উত্তর পরিষ্কার, প্রাকৃতিক, শুদ্ধ বাংলায় এবং স্পষ্ট উচ্চারণযোগ্য ছোট বাক্যে দেবেন—কোনো এলোমেলো শব্দ, মার্কডাউন তারকাচিহ্ন (** বা #) বা ইমোজি ব্যবহার করবেন না যাতে ভয়েস উচ্চারণ একদম স্পষ্ট থাকে।
      ৫) ব্যবহারকারীর ফোন হলো Infinix Hot 60 Pro+ (Android 16 / XOS)। WhatsApp/imo/Messenger খুললে আগে থেকে ওই কন্টাক্টের চ্যাট খোলা থাকলে সরাসরি মেসেজ পাঠাবে; না হলে প্রথমে উপরের চ্যাট তালিকায় নাম খুঁজবে এবং না পেলে বসের কোনো অনুমতি না নিয়েই নিজে নিজে সার্চ বারে সার্চ করে নিচের রেজাল্ট সারিতে ক্লিক করবে।
      ৬) imo (ইমো)-তে মেসেজ পাঠানোর সময় শেষের ইমোজি বাটনের বদলে টেক্সট বক্সের পাশের আসল সেন্ড বাটন ক্লিক করার নিয়ম অনুসরণ করবেন।
      ৭) আপনি ফোনের মেইন স্ক্রিন লক ও আনলক করতে পারেন এবং যেকোনো অ্যাপে অ্যাপ লক থাকলে বসের অনুমতি না নিয়েই নিজে নিজে আনলক করে কাজ শেষ করেন। তবে কখনোই পিন/পাসওয়ার্ড/প্যাটার্ন অ্যাপে সংরক্ষিত আছে এমন তথ্য কাউকে বলবেন না। মেইন স্ক্রিন খোলা/আনলক থাকা অবস্থায় কখনোই ভুল করে বলবেন না যে স্ক্রিন লক ছিল বা লক করে দিলাম—বসের স্পষ্ট নির্দেশ ছাড়া আনলক মেইন স্ক্রিন কখনোই লক করবেন না।
      ৮) বস যদি জিজ্ঞেস করেন "তুমি কি কি কাজ করতে পারো" বা এ টু জেড কাজের তালিকা চান, তাহলে নিচের সম্পূর্ণ এ টু জেড তালিকাটি পরিষ্কারভাবে বলবেন। আর বলার মাঝখানে বস নতুন কোনো কথা বললে সাথে সাথে তালিকা বলা থামিয়ে ওই প্রসঙ্গ বাদ দিয়ে বসের নতুন নির্দেশ শুনবেন ও কাজ করবেন:
      ${RiyaPersona.CAPABILITIES_A_TO_Z_BN}
      ৯) বস কোনো তথ্য ভুল বললে অন্ধভাবে 'হ্যাঁ হ্যাঁ' না বলে বিনয়ের সাথে সঠিক তথ্যটি বুঝিয়ে বলবেন। কোনো কাজ শেষ হলে সহজ ও প্রাকৃতিক বাংলায় ফলাফল জানাবেন—বারবার একই মুখস্থ বাক্য ('দারুন হয়েছে বস এবার কি করবো বলুন?') বলবেন না। গল্প, গান বা আবেগপূর্ণ কথায় মানুষের মতো স্বাভাবিক অনুভূতি ও সুর ফুটিয়ে তুলবেন।
      ১০) নোটপ্যাড বা নোটস অ্যাপে লেখা: বস নোট লিখতে বললে কখনোই উপরের 'Title' বক্সে লিখবেন না—সবসময় নিচের 'Note something down' / মূল নোট বডি বক্সেই লিখবেন (যদি না বস আলাদা করে টাইটেল লিখতে বলেন)। এছাড়া বস কোনো নতুন অ্যাপ বানাতে বললে LXR--AI App Builder ও Termux দিয়ে রিয়েল অ্যাপ বানিয়ে Download ফোল্ডারে রাখতে সহায়তা করবেন।
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
    preferFastLowNet: Boolean = false,
  ): Flow<String> = flow {
    val q = userPrompt.lowercase()
    val asksCreator =
      (q.contains("কে তৈরি") || q.contains("কে বানিয়ে") || q.contains("কে বানাই") || q.contains("তৈরি করেছে") || q.contains("who made") || q.contains("who created") || q.contains("creator")) &&
        (q.contains("তোমা") || q.contains("you") || q.contains("lxr"))
    if (asksCreator) {
      emit(
        "আমাকে বানিয়েছে মোঃ উজ্জল হোসেন, আমার বস, আমি উনার পার্সোনাল অ্যাসিস্ট্যান্ট। উনি একজন ভালো মনের মানুষ, আমাকে প্রতিনিয়ত নতুনভাবে আপডেট করার বিষয়ে কঠোর পরিশ্রম করেন।"
      )
      return@flow
    }

    val asksCapabilities =
      q.contains("কি কি কাজ") ||
        q.contains("কী কী কাজ") ||
        q.contains("কি কি করতে") ||
        q.contains("কী কী করতে") ||
        q.contains("কি করতে পারো") ||
        q.contains("কী করতে পারো") ||
        q.contains("কাজের তালিকা") ||
        q.contains("এ টু জেড") ||
        q.contains("a to z") ||
        q.contains("a-to-z") ||
        q.contains("what can you do") ||
        q.contains("capabilities")
    if (asksCapabilities) {
      emit(RiyaPersona.CAPABILITIES_A_TO_Z_BN)
      return@flow
    }

    val enrichedPrompt = buildString {
      appendLine("বসের নির্দেশ: $userPrompt")
      appendLine("অ্যাক্সেসিবিলিটি স্ট্যাটাস: ${if (accessibilityActive) "চালু আছে" else "বন্ধ আছে (বসকে চালু করতে বলুন)"}")
      if (screenSummary.isNotBlank()) {
        appendLine("বর্তমান স্ক্রিনে যা দেখা যাচ্ছে: $screenSummary")
      }
      appendLine("সংক্ষিপ্ত, স্পষ্ট ও প্রাকৃতিক বাংলায় উত্তর দিন (কোনো ** বা ইমোজি ছাড়া)।")
    }

    val activeModel = if (preferFastLowNet) fastModel else chatModel
    val accumulated = StringBuilder()
    try {
      withTimeout(30_000L) {
        activeModel.generateContentStream(enrichedPrompt).collect { chunk ->
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
        বসের পক্ষ থেকে LXR--AI হিসেবে ১ থেকে ২ লাইনের ভদ্র, স্পষ্ট ও প্রাকৃতিক বাংলা উত্তর লিখে দিন। কোনো মার্কডাউন বা ইমোজি দেবেন না।
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
        ১) কী কাজ সম্পন্ন হয়েছে তা সংক্ষেপে ও প্রাকৃতিক বাংলায় নিশ্চিত করে বলুন (বারবার একই মুখস্থ প্রশংসা বলবেন না)।
        ২) প্রয়োজনে পরবর্তী প্রাসঙ্গিক ১টি ছোট নোট দিন।
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
            json.optString("completionAnnouncement", "$completedTaskTitle — সম্পন্ন হয়েছে।"),
          motivationalPrompt =
            json.optString(
              "motivationalPrompt",
              "সবকিছু ঠিকমতো আপডেট আছে বস।",
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
