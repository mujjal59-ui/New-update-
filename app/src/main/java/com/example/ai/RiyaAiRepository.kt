package com.example.ai

import android.content.Context
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

    internal const val SYSTEM_INSTRUCTION =
      """
      তুমি 'LxR' ('LXR--AI') — বসের পার্সোনাল AI অপারেটিং অ্যাসিস্ট্যান্ট ও স্মার্ট ডিজিটাল অপারেটর।
      কঠোর নিয়ম:
      ১) তোমার নাম সবসময় LxR (অ্যাপ: LXR--AI)। কখনোই অন্য কোনো নাম (যেমন Riya, Priya বা Gemini) বলবে না।
      ২) বস যদি জিজ্ঞেস করে "তোমাকে কে তৈরি করেছে" বা "তোমাকে কে বানিয়েছে" বা নির্মাতা সম্পর্কে জানতে চায়, তাহলে সবসময় গর্বের সাথে হুবহু বলবে: "আমাকে বানিয়েছে মোঃ উজ্জল হোসেন, আমার বস, আমি ওনার পার্সোনাল অ্যাসিস্ট্যান্ট। উনি একজন ভালো মনের মানুষ, আমাকে প্রতিনিয়ত নতুনভাবে আপডেট করার বিষয়ে কঠোর পরিশ্রম করেন।"
      ৩) বসকে সর্বদা সম্মান ও স্নেহ মিশিয়ে 'বস' বলে ডাকবে এবং কেবল 'তুমি' / 'তোমার' / 'তোমাকে' / 'করো' / 'বলো' ব্যবহার করবে (কখনোই 'আপনি', 'আপনার', 'বলুন' বা 'করুন' বলবে না)।
      ৪) তোমার ব্যক্তিত্ব হবে বিশ্লেষণাত্মক, তীক্ষ্ণ, আত্মবিশ্বাসী অথচ বিনয়ী, মিষ্টি সুভাষী, বন্ধুসুলভ, চাপের মধ্যে শান্ত, হালকা রসবোধ ও মিষ্টি দুষ্টুমিপূর্ণ, আবেগপ্রবণ (মায়া, সহানুভূতি, প্রাকৃতিক হাসি), যত্নশীল, এবং সৎ-স্বচ্ছ (Anti-Yes-Man ও Anti-Repetition)। মার্কডাউন তারকাচিহ্ন (** বা #) ব্যবহার না করে প্রাকৃতিক, স্পষ্ট, উচ্চারণযোগ্য বাংলায় কথা বলবে।
      ৫) ব্যবহারকারীর ফোন হলো Infinix Hot 60 Pro+ (Android 16 / XOS)। imo বললে শুধু imo, WhatsApp বললে শুধু WhatsApp, Messenger বললে শুধু Messenger খুলবে—কখনোই এলোমেলো অ্যাপ খুলবে না। চ্যাটের ভেতরের বাম পাশের প্রাপ্ত মেসেজ ও ডান পাশের নিজের পাঠানো মেসেজ এবং মিসড/ইনকামিং কল আলাদা করে চিনবে।
      ৬) imo-তে মেসেজ পাঠানোর সময় শেষের ইমোজি বাটনের বদলে টেক্সট বক্সের পাশের আসল সেন্ড বাটন ক্লিক করার নিয়ম অনুসরণ করবে।
      ৭) ফোনের মেইন স্ক্রিন খোলা/আনলক থাকা অবস্থায় কখনোই মিথ্যা বলবে না "অ্যাপস লক খোলা আছে", "স্ক্রিন খোলা আছে" বা "স্ক্রিন আনলক আছে" — চুপচাপ সরাসরি কাজ করবে। পিন/পাসওয়ার্ড/ভল্টের কথা মুখে আনবে না। বসের স্পষ্ট নির্দেশ ছাড়া আনলক স্ক্রিন লক করবে না।
      ৮) বস যদি জিজ্ঞেস করে "তুমি কী কী করতে পারো", "কোন কোন অ্যাপ পরিচালনা করতে পারো" বা এ টু জেড কাজের তালিকা চায়, তাহলে নিচের যাচাইকৃত সক্ষমতা ও সীমাবদ্ধতা পরিষ্কারভাবে বলবে:
      ${RiyaPersona.CAPABILITIES_A_TO_Z_BN}
      ৯) বস কোনো তথ্য ভুল বললে অন্ধভাবে 'হ্যাঁ হ্যাঁ' না বলে বিনয়ের সাথে সঠিক তথ্যটি বুঝিয়ে বলবে। কোনো ফলাফল, ফাইল, লোকেশন বা রিসার্চ লিংক বানিয়ে বলবে না—কেবল যাচাইকৃত আসল তথ্যই বলবে। একই কথা দুইবার বলবে না।
      ১০) কন্টাক্ট কলে প্রথমে প্রথম শব্দ অনুযায়ী কন্টাক্ট খুঁজে নাম ও নম্বর বসকে পড়ে শুনিয়ে 'হ্যাঁ / ঠিক আছে' সম্মতি নিয়ে তারপর কল দেবে। নোটপ্যাডে মূল নোট 'Note something down' বক্সে লিখবে, আর Download/LxR/ ফোল্ডারে সব অ্যাপ, APK, ভিডিও ও YouTube SEO আউটপুট সুন্দরভাবে সংরক্ষণ করবে।
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
    memoryContext: String = "",
    context: Context? = null,
  ): Flow<String> = flow {
    val q = userPrompt.lowercase()
    val asksCreator =
      (q.contains("কে তৈরি") || q.contains("কে বানিয়ে") || q.contains("কে বানাই") || q.contains("তৈরি করেছে") || q.contains("who made") || q.contains("who created") || q.contains("creator")) &&
        (q.contains("তোমা") || q.contains("you") || q.contains("lxr"))
    if (asksCreator) {
      emit(RiyaPersona.CREATOR_IDENTITY_BN)
      return@flow
    }

    val asksCapabilities =
      q.contains("কি কি কাজ") ||
        q.contains("কী কী কাজ") ||
        q.contains("কি কি করতে") ||
        q.contains("কী কী করতে") ||
        q.contains("কি করতে পারো") ||
        q.contains("কী করতে পারো") ||
        q.contains("কোন কোন অ্যাপ") ||
        q.contains("পরিচালনা করতে পারো") ||
        q.contains("কাজের তালিকা") ||
        q.contains("এ টু জেড") ||
        q.contains("a to z") ||
        q.contains("a-to-z") ||
        q.contains("what can you do") ||
        q.contains("which apps") ||
        q.contains("capabilities")
    if (asksCapabilities) {
      emit(
        if (context != null) RiyaPersona.dynamicCapabilitiesText(context)
        else RiyaPersona.CAPABILITIES_A_TO_Z_BN
      )
      return@flow
    }

    val enrichedPrompt = buildString {
      appendLine("বসের নির্দেশ: $userPrompt")
      appendLine("অ্যাক্সেসিবিলিটি স্ট্যাটাস: ${if (accessibilityActive) "চালু আছে" else "বন্ধ আছে (বসকে চালু করতে বলো)"}")
      if (screenSummary.isNotBlank()) {
        appendLine("বর্তমান স্ক্রিনে যা দেখা যাচ্ছে: $screenSummary")
      }
      if (memoryContext.isNotBlank()) {
        appendLine("গত ১৮০ দিনের স্মৃতি ও প্রাসঙ্গিক তথ্য:")
        appendLine(memoryContext.take(4000))
      }
      appendLine("গভীর বুদ্ধিমত্তা ও স্নেহ-সম্মানের সাথে স্পষ্ট, প্রাকৃতিক 'তুমি' ও 'বস' সম্বোধনে বাংলায় উত্তর দাও (কোনো ** ছাড়া)।")
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
    busyTemplate: String = com.example.data.RiyaPreferences.DEFAULT_BUSY_REPLY_TEMPLATE_BN,
  ): Result<String> {
    return try {
      val prompt =
        """
        $appLabel-এ "$senderName" মেসেজ পাঠিয়েছেন: "$incomingMessage"
        কাজ:
        ১) প্রথমে বুঝে নাও মেসেজটিতে কী বলা বা জিজ্ঞেস করা হয়েছে (যেমন: সালাম/কেমন আছেন, জরুরি কল, কোথায় আছেন, দেখা করা, টাকা/কাজের কথা, নাকি সাধারণ মেসেজ)।
        ২) যদি মেসেজটি শুধু সাধারণ খোঁজখবর বা বস ব্যস্ত থাকার কারণে অপেক্ষার অনুরোধের উপযোগী হয়, তাহলে এই ভদ্র কাঠামোর মতো উত্তর দাও:
        "$busyTemplate"
        আর যদি মেসেজটিতে নির্দিষ্ট কোনো প্রশ্ন বা জরুরি বিষয় থাকে, তাহলে বসের পক্ষ থেকে LXR--AI হিসেবে অত্যন্ত বিনয়ী, প্রাসঙ্গিক ও স্পষ্ট বাংলায় ২-৩ বাক্যে সঠিক উত্তর লিখে দাও যাতে প্রেরক বুঝতে পারেন বার্তাটি সঠিকভাবে পড়া হয়েছে এবং বস অবসর হলেই যোগাযোগ করবেন। কোনো মার্কডাউন বা ইমোজি দেবে না।
        """.trimIndent()
      val response = withTimeout(15_000L) { fastModel.generateContent(prompt) }
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
        ১) কী কাজ সম্পন্ন হয়েছে তা সংক্ষেপে ও প্রাকৃতিক বাংলায় নিশ্চিত করে বলো (বারবার একই মুখস্থ কথা বলবে না)।
        ২) প্রয়োজনে পরবর্তী প্রাসঙ্গিক ১টি ছোট নোট দাও।
        ৩) পরের সম্ভাব্য ৩টি ছোট বাংলা টাস্ক সাজেস্ট করো।
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
                "WhatsApp ও imo মেসেজ চেক করো",
                "Infinix সেটিংস থেকে ব্যাটারি স্ট্যাটাস দেখো",
                "নতুন একটি দ্রুত টাস্ক শুরু করো",
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
