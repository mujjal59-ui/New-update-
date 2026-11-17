package com.example.builder

import java.util.UUID

enum class AppCategory(
  val titleEn: String,
  val titleBn: String,
  val defaultFeatureHint: String,
  val defaultDependencies: List<String>,
) {
  AI_ASSISTANT(
    "AI Assistant App",
    "এআই অ্যাসিস্ট্যান্ট",
    "চ্যাট প্রম্পট, স্মার্ট সাজেশন, ভয়েস ইনপুট এবং হিস্ট্রি সেভ",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  CHATTING(
    "Chatting App",
    "চ্যাটিং অ্যাপ",
    "কনভার্সেশন থ্রেড, রিয়েল-টাইম মেসেজ লিস্ট এবং কুইক রিপ্লাই",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  VOICE_ASSISTANT(
    "Voice Assistant App",
    "ভয়েস অ্যাসিস্ট্যান্ট",
    "স্পিচ রিকগনিশন, টেক্সট-টু-স্পিচ ও কুইক ভয়েস কমান্ড",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  EDUCATION(
    "Education App",
    "শিক্ষা ও কুইজ অ্যাপ",
    "অধ্যায়ভিত্তিক লেসন, ফ্ল্যাশকার্ড এবং ইন্টারেক্টিভ কুইজ স্কোরিং",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  BUSINESS(
    "Business App",
    "বিজনেস ম্যানেজার",
    "ক্লায়েন্ট তালিকা, ইনভয়েস সামারি এবং দৈনিক আয়-ব্যয় হিসাব",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  ECOMMERCE(
    "E-commerce App",
    "ই-কমার্স স্টোর",
    "প্রোডাক্ট ক্যাটালগ, কার্ট ম্যানেজমেন্ট ও অর্ডার সামারি",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  CALCULATOR(
    "Calculator App",
    "ক্যালকুলেটর অ্যাপ",
    "পূর্ণাঙ্গ গাণিতিক অপারেশন (+, −, ×, ÷, %), ক্লিন ডিসপ্লে ও হিসাব হিস্ট্রি",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  UTILITY(
    "Utility App",
    "ইউটিলিটি টুলস",
    "ইউনিট কনভার্টার, কাউন্টার এবং দৈনন্দিন দরকারি কুইক টুলস",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  MUSIC_PLAYER(
    "Music Player App",
    "মিউজিক প্লেয়ার",
    "অডিও ট্র্যাক তালিকা, প্লে/পজ কন্ট্রোল ও প্লেলিস্ট ভিউ",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  VIDEO_PLAYER(
    "Video Player App",
    "ভিডিও প্লেয়ার",
    "ভিডিও লাইব্রেরি, প্লেব্যাক কন্ট্রোল ও টাইমলাইন প্রোগ্রেস",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  NOTES(
    "Notes App",
    "নোটস ও ডায়েরি",
    "দ্রুত নোট তৈরি, সংরক্ষণ, সার্চ এবং পিন করার সুবিধা",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  FILE_MANAGER(
    "File Manager App",
    "ফাইল ম্যানেজার",
    "ক্যাটাগরিভিত্তিক ফাইল ব্রাউজার, স্টোরেজ সামারি ও ফিল্টার",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  SOCIAL_MEDIA(
    "Social Media App",
    "সোশ্যাল মিডিয়া",
    "পোস্ট ফিড, লাইক-কমেন্ট কাউন্টার এবং নতুন স্ট্যাটাস পাবলিশ",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  PRODUCTIVITY(
    "Productivity App",
    "প্রোডাক্টিভিটি ও টু-ডু",
    "টাস্ক লিস্ট, ফোকাস টাইমার এবং দৈনিক কাজের অগ্রগতি",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  MINI_GAME(
    "Mini Game App",
    "গেম ও পাজল অ্যাপ",
    "ইন্টারেক্টিভ টিক-ট্যাক-টো (X-O) অ্যারেনা গেম, এআই প্রতিপক্ষ, স্কোরবোর্ড ও রিসেট",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
  CUSTOM(
    "Custom Android Application",
    "কাস্টম অ্যান্ড্রয়েড অ্যাপ",
    "আপনার বিবরণ অনুযায়ী সম্পূর্ণ কাস্টম ফিচার ও ইউজার ইন্টারফেস",
    listOf("androidx.core:core-ktx:1.15.0", "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0"),
  ),
}

enum class AppDesignTheme(
  val labelBn: String,
  val primaryHex: String,
  val accentHex: String,
  val surfaceHex: String,
  val textHex: String,
) {
  CYBER_NEON("Cyber Neon Dark", "#00E5FF", "#A855F7", "#0A0F1F", "#F8FAFC"),
  MATERIAL_DYNAMIC("Material 3 Pro", "#3B82F6", "#10B981", "#0F172A", "#F1F5F9"),
  CLEAN_MINIMAL("Clean Minimal Light", "#2563EB", "#7C3AED", "#F8FAFC", "#0F172A"),
  ROYAL_AMOLED("Royal AMOLED", "#FF179E", "#00E5FF", "#05070E", "#FFFFFF"),
  EMERALD_MODERN("Emerald Modern", "#10B981", "#14B8A6", "#061A17", "#ECFDF5"),
}

enum class ProgrammingLanguage(val labelBn: String, val extension: String) {
  KOTLIN("Kotlin (XML + Material 3)", "kt"),
  JAVA("Java (XML + Material 3)", "java"),
  KOTLIN_COMPOSE("Kotlin + Jetpack Compose", "kt"),
}

enum class AndroidApiLevel(val sdkInt: Int, val label: String) {
  API_24(24, "Android 7.0 (API 24)"),
  API_26(26, "Android 8.0 (API 26)"),
  API_29(29, "Android 10 (API 29)"),
  API_31(31, "Android 12 (API 31)"),
  API_33(33, "Android 13 (API 33)"),
}

enum class ApkBuildMode(val labelBn: String, val gradleTask: String) {
  DEBUG("Debug APK", "assembleDebug"),
  RELEASE("Release APK", "assembleRelease"),
}

enum class DevelopmentStage(val stepNumber: Int, val titleBn: String) {
  REQUIREMENT_ANALYSIS(1, "Requirement Analysis"),
  APP_ARCHITECTURE(2, "App Architecture তৈরি"),
  UI_UX_DESIGN(3, "UI/UX Design তৈরি"),
  SOURCE_CODE(4, "Source Code লেখা"),
  RESOURCE_FILES(5, "XML, Kotlin/Java ফাইল তৈরি"),
  ASSETS_RESOURCES(6, "Resources ও Assets তৈরি"),
  PROJECT_STRUCTURE(7, "Project Structure সাজানো"),
  DEPENDENCIES(8, "Dependencies নির্ধারণ"),
  BUILD_CONFIG(9, "Build Configuration তৈরি"),
  APPLICATION_BUILD(10, "Application Build & Verify"),
}

enum class BuildStatus(val labelBn: String) {
  IDLE("প্রস্তুত"),
  GENERATING("কোড ও স্ট্রাকচার তৈরি হচ্ছে..."),
  GENERATED("প্রজেক্ট তৈরি সম্পন্ন — বিল্ডের জন্য প্রস্তুত"),
  SYNCING_TERMUX("Termux ও Download ফোল্ডারে সিঙ্ক হচ্ছে..."),
  BUILDING("Termux-এ APK বিল্ড চলছে..."),
  AUTO_FIXING("Error বিশ্লেষণ ও অটো-ফিক্স চলছে..."),
  SUCCESS("APK বিল্ড সফল ✓"),
  FAILED("বিল্ড ত্রুটি — Error Panel দেখুন"),
}

data class BuildErrorDiagnosis(
  val rawLogSnippet: String,
  val rootCauseBn: String,
  val suggestedFixBn: String,
  val affectedFile: String? = null,
  val autoFixApplied: Boolean = false,
  val attemptCount: Int = 1,
  val isRepeatedSameError: Boolean = false,
)

data class ApkVerificationResult(
  val apkFilePath: String = "",
  val apkFileName: String = "",
  val exists: Boolean = false,
  val sizeBytes: Long = 0L,
  val hasValidApkArchive: Boolean = false,
  val buildMode: ApkBuildMode = ApkBuildMode.DEBUG,
  val downloadExportPath: String = "",
)

data class TermuxEnvironmentStatus(
  val termuxInstalled: Boolean = false,
  val termuxApiInstalled: Boolean = false,
  val runCommandPermissionGranted: Boolean = false,
  val sharedDownloadDirPath: String = "",
  val scriptExported: Boolean = false,
  val hasSafAccess: Boolean = false,
  val safFolderLabel: String = "",
  val setupSummaryBn: String = "",
)

data class AppBuilderProject(
  val id: String = UUID.randomUUID().toString().take(8),
  val name: String = "My LXR App",
  val description: String = "",
  val category: AppCategory = AppCategory.UTILITY,
  val designTheme: AppDesignTheme = AppDesignTheme.CYBER_NEON,
  val packageName: String = "com.lxr.appbuilder.sample",
  val minSdk: AndroidApiLevel = AndroidApiLevel.API_26,
  val language: ProgrammingLanguage = ProgrammingLanguage.KOTLIN,
  val buildMode: ApkBuildMode = ApkBuildMode.DEBUG,
  val createdAtMs: Long = System.currentTimeMillis(),
  val updatedAtMs: Long = System.currentTimeMillis(),
  val analysisReport: String = "",
  val architecturePlan: String = "",
  val dependenciesSummary: List<String> = emptyList(),
  val files: Map<String, String> = emptyMap(),
  val buildStatus: BuildStatus = BuildStatus.IDLE,
  val buildLogs: List<String> = emptyList(),
  val lastDiagnosis: BuildErrorDiagnosis? = null,
  val apkResult: ApkVerificationResult = ApkVerificationResult(),
) {
  val slug: String
    get() =
      name
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "lxr_app_$id" }
}

sealed class BuilderConfirmationRequest {
  abstract val title: String
  abstract val message: String

  data class DeleteProject(val projectId: String, val projectName: String) :
    BuilderConfirmationRequest() {
    override val title = "প্রজেক্ট ডিলিট করবেন?"
    override val message =
      "'$projectName' প্রজেক্ট এবং এর সব ফাইল স্থায়ীভাবে মুছে ফেলা হবে। নিশ্চিত করতে অনুমতি দিন।"
  }

  data class DeleteCodeFile(val filePath: String) : BuilderConfirmationRequest() {
    override val title = "ফাইল ডিলিট করবেন?"
    override val message = "'$filePath' ফাইলটি প্রজেক্ট থেকে মুছে ফেলা হবে। এগিয়ে যেতে চান?"
  }

  data class OverwriteExistingProject(val targetName: String, val onConfirmAction: () -> Unit) :
    BuilderConfirmationRequest() {
    override val title = "বিদ্যমান প্রজেক্ট Overwrite করবেন?"
    override val message =
      "'$targetName' নামে আগে থেকেই একটি প্রজেক্ট রয়েছে। নতুন করে তৈরি করলে আগের ফাইল পরিবর্তন হবে।"
  }

  data class OverwriteEditedFile(val filePath: String, val newContent: String) :
    BuilderConfirmationRequest() {
    override val title = "ফাইল Overwrite নিশ্চিত করুন"
    override val message = "'$filePath' ফাইলে পূর্বের পরিবর্তনের ওপর নতুন কোড সংরক্ষণ করা হবে।"
  }
}
