# LXR--AI v2.43.0 (versionCode 61) — A-to-Z Master Changelog, Source Audit & Release Report

- **Application ID:** `com.aistudio.riyaai.krmxvp` (Preserved for seamless in-place update)
- **Version:** `2.43.0` (`versionCode = 61`)
- **Automated JVM/Robolectric Verification:** `191 / 191` tests passing (`0 failures`, `0 errors`, `0 skipped`) across `25` suites (`2026-11-28T09:45:03Z`)
- **Debug APK Artifact:** `LXR-AI-v2.43.0-debug.apk` (`48,933,078 bytes`)
- **Physical Device Hardware Verification:** `NOT TESTED IN CLOUD` (No physical Android device/ADB/Bluetooth headset attached in build container — see Phone Test Steps below)

---

## 1. ৩টি জরুরি বাগ ফিক্স (Urgent Voice, Mute & Audio Fixes)

1. **ElevenLabs Voice Fix (`GeminiLive.kt`, `RiyaVoiceManager.kt`, `RiyaSubPages.kt`):**
   - Gemini Live-এ ElevenLabs মোড চালু থাকলে `responseModalities` সবসময় `["AUDIO"]` + `outputAudioTranscription` হিসেবে নেগোসিয়েট করে (নেটিভ-অডিও মডেলের WebSocket `1007/1008` `"পুনরায় সংযোগ হচ্ছে..."` লুপ সমাধান)।
   - Free/Starter/Restricted ElevenLabs API Key-এর HTTP `403 (output_format_not_allowed)` সমস্যার জন্য স্বয়ংক্রিয় ফরম্যাট ফলব্যাক (`pcm_24000 -> pcm_16000 -> mp3_44100_128`) + Android `MediaExtractor`/`MediaCodec` MP3-to-PCM ডিকোডার এবং `eleven_multilingual_v2 -> eleven_flash_v2_5` মডেল ফলব্যাক যুক্ত।
   - KeyPage-এর `"টেস্ট মিষ্টি ভয়েস"` বাটনে ফোর্সড ElevenLabs প্লেব্যাক ও সিক্রেট-রিড্যাক্টেড এরর ডায়াগনস্টিক্স যুক্ত।

2. **Microphone Mute/Unmute Across All 4 Voice Models (`GeminiLive.kt`, `MainActivity.kt`, `RiyaVoiceManager.kt`):**
   - `Gemini 3.8 Live`, `Gemini 3.1 Flash Live (preview)`, `Gemini 2.5 Flash Native Audio (Dec 2025)`, এবং `ElevenLabs Voice` — ৪টি মডেলেই `GeminiLive.setMicMuted(muted)` একীভূত করা হয়েছে।
   - মিউট করলে তাৎক্ষণিকভাবে স্পিকার/TTS থামে, পেন্ডিং টেক্সট/কিউ পরিষ্কার হয়, `audioStreamEnd: true` পাঠানো হয়, `startMic()` জিরো-ফিল পাঠায় এবং মডেল সুইচ (`switchModelAndReconnect()`) করলেও মিউট অবস্থা বজায় থাকে।

3. **"আমি শুনি না" (Silent Playback Recovery — `GeminiLive.kt`, `RiyaVoiceManager.kt`, `RiyaStore.kt`):**
   - `RiyaState.isBtCommOutputVerified` দিয়ে Bluetooth কমিউনিকেশন আউটপুট আসলেই সক্রিয় কিনা যাচাই করা হয়; ব্যর্থ বা তারযুক্ত/A2DP হেডসেট হলে স্বয়ংক্রিয়ভাবে `MODE_NORMAL` + `USAGE_MEDIA` মোডে চলে যায়।
   - `playExec`-এ `AudioTrack.playbackHeadPosition` মনিটর করে সাইলেন্ট পাইপ স্টল (>১৪,৪০০ ফ্রেম লেখার পরও হেড না এগোনো) শনাক্ত হলেই `USAGE_MEDIA`-তে নতুন `AudioTrack` বানিয়ে অডিও পুনরায় চালু হয় এবং ভলিউম ০% হলে সতর্কতা দেখায়।

---

## 2. Critical Network Repair & Free-First Multi-Provider Gateway

- **`LxrDeviceAndHardwareEngine.solveCriticalTaskWithExtraModel()`** মেরামত করে রিটায়ার্ড `gemini-2.0-flash` ও খালি `catch` ব্লক সরিয়ে `LxrProviderGateway`-এ সংযুক্ত করা হয়েছে।
- **নতুন `LxrProviderGateway.kt`:**
  - `LxrModelRegistry`: যাচাইকৃত `gemini-2.5-pro`, `gemini-2.5-flash`, `gemini-2.5-flash-lite`, `gemini-flash-latest` (কোনো অচল `gemini-2.0-flash` নয়)
  - `FailureClassifier`: `401/403` (`AUTH_INVALID`), `429` (`RATE_LIMITED`), `404` (`MODEL_NOT_FOUND`), `5xx` (`SERVER_ERROR`), Timeout/IO (`TIMEOUT_OR_NETWORK`), `CIRCUIT_OPEN`
  - `ExponentialBackoffRetry` + `ProviderCircuitBreaker` (`CLOSED -> OPEN -> HALF_OPEN`) + `ProviderHealthMonitor`
  - `FreeFirstProviderRouter`: ফ্রি-ফার্স্ট রাউটিং, স্বয়ংক্রিয় ফেইলওভার, `LxrUsageLedger` টোকেন ট্র্যাকিং ও `LxrSecretRedactor` সিক্রেট সুরক্ষা (কোনো SSL/TLS বাইপাস নেই)
- **`LxrProviderOrchestrator.kt` & `RiyaStore.kt`:** লাইভ গেটওয়ে হেলথ রিপোর্টিং ও ঐচ্ছিক OpenAI-Compatible BYO গেটওয়ে কনফিগারেশন (ডিফল্ট ফাঁকা)।

---

## 3. Central Autonomous Orchestrator & All 15 Specialist Roles

- **`LxrAutonomousOrchestrator.kt` (`run_autonomous_task` টুল):** জটিলতা শ্রেণিবিন্যাস (`FAST_SINGLE / SPECIALIZED / MULTI_AGENT_PARALLEL`), `SupervisorJob` সমান্তরাল এজেন্ট এক্সিকিউশন (১৮ সেকেন্ড টাইমআউট গার্ডসহ), এবং কোয়ালিটি/ক্রিটিক গেট যাচাই।
- **`LxrSpecialistAgents.kt` (১৫টি কোর রোল + এক্সটেন্ডেড LXR ডোমেইন এজেন্ট):**
  1. **Autonomous Orchestrator** (`LxrAutonomousOrchestrator`)
  2. **Extra Critical Reasoning** (`ExtraCriticalReasoningAgent`)
  3. **Advanced Coding** (`CodingAndDeveloperAgent`)
  4. **Python Engineering** (`PythonEngineeringAgent` — Python `eval`/`exec`/`pickle`/`shell=True`/mutable default স্ট্যাটিক চেকসহ)
  5. **Java Engineering** (`JavaEngineeringAgent` — SQL concatenation/`SimpleDateFormat`/deprecated `Thread` চেকসহ)
  6. **Kotlin/Android Engineering** (`KotlinAndroidEngineeringAgent` — `GlobalScope`/`Thread.sleep`/`!!` চেকসহ)
  7. **JavaScript/TypeScript Engineering** (`JsTsEngineeringAgent` — XSS `innerHTML`/`eval`/`var` চেকসহ)
  8. **Education and Learning** (`EducationAndCurriculumAgent`)
  9. **Mathematics and Logic** (`ReasoningAndMathAgent` — ডিটারমিনিস্টিক রিকার্সিভ-ডিসেন্ট ম্যাথ ইভ্যালুয়েটরসহ)
  10. **Deep Research** (`DeepResearchVerificationAgent` — `SourceCredibilityTier` + দ্বন্দ্ব সতর্কতা)
  11. **Technical Documentation** (`DocumentationSummaryAgent`)
  12. **Code Security** (`CodeSecurityAuditorAgent` — হার্ডকোডেড সিক্রেট, দুর্বল TLS, cleartext HTTP, SQLi স্ট্যাটিক স্ক্যান)
  13. **Autonomous Network Intelligence** (`NetworkAndGatewayManagerAgent` — নেটওয়ার্ক + গেটওয়ে + ৮ Termux ফ্রেমওয়ার্ক)
  14. **Advanced Planning** (`IntentAndPlannerAgent`)
  15. **Quality Assurance** (`MonitoringAndQaAuditAgent` + `VerificationCritiqueGateAgent`)
  *(বর্ধিত ডোমেইন এজেন্ট: `OsintInvestigationAgent`, `DeviceAndHardwareAgent`, `AccessibilityAndUiAgent`, `CommunicationAndMessagingAgent`, `CreativeContentAndSeoAgent`)*
  - **স্বচ্ছতা নীতি:** ডিটারমিনিস্টিক এজেন্ট লোকালি চলে এবং ক্লাউড রিজনিং/কোডিং এজেন্ট একীভূত `LxrProviderGateway` (বসের শেয়ার্ড Gemini/BYO মডেল) ব্যবহার করে; প্রতিটি এজেন্টের আলাদা LLM আছে এমন মিথ্যা দাবি করা হয় না।

---

## 4. Termux ৮টি ফ্রেমওয়ার্ক, ৮-স্তরের স্ট্যাটাস ও ARM64 সামঞ্জস্য (`LxrTermuxFrameworkAdapters.kt` + `GeminiLive.kt`)

- **নতুন Live টুল:** `inspect_termux_frameworks` ও `run_termux_framework` (Termux UI অটোমেশন ও ইনপুট স্যানিটাইজেশনসহ — কখনোই ভুয়া CLI আউটপুট তৈরি করে না)।
- **৮-স্তরের সৎ স্ট্যাটাস মডেল:** `NOT_CHECKED`, `NOT_INSTALLED`, `INSTALLED`, `EXECUTABLE`, `TESTED`, `WORKING`, `UNSUPPORTED`, `UNAVAILABLE`।
- **Android ARM64 সামঞ্জস্য ও রিসোর্স তালিকা:**
  1. **LangGraph:** Python `3.10+`, ~180 MB Disk, ~512 MB RAM | ARM64 Direct: Yes (নেটিভ সমতুল্য `LxrAutonomousOrchestrator`)
  2. **CrewAI:** Python `3.10–3.12`, ~650 MB Disk, ~1024 MB RAM | ARM64 Direct: External/Proot Recommended (`rust`/`chromadb`/`onnxruntime` হুইল ঝুঁকি)
  3. **LiteLLM:** Python `3.9+`, ~220 MB Disk, ~512 MB RAM | ARM64 Direct: Yes (নেটিভ সমতুল্য `LxrProviderGateway`)
  4. **Aider:** Python `3.10+`, ~420 MB Disk, ~768 MB RAM | ARM64 Direct: Yes (`git` + `clang` সহ)
  5. **OpenHands:** Python `3.12+`, ~2500 MB Disk, ~4096 MB RAM | ARM64 Direct: Remote Host (`Docker`/Linux cgroups প্রয়োজন -> স্ট্যান্ডার্ড Termux-এ `UNSUPPORTED`)
  6. **Haystack:** Python `3.9+`, ~300 MB Disk, ~768 MB RAM | ARM64 Direct: Yes (হালকা পাইপলাইন)
  7. **LlamaIndex:** Python `3.9+`, ~320 MB Disk, ~768 MB RAM | ARM64 Direct: Yes (`llama-index-core`)
  8. **Semgrep:** Python `3.9+`, ~500 MB Disk, ~1024 MB RAM | ARM64 Direct: External/Proot Recommended (`semgrep-core` OCaml/glibc বাইনারি Bionic-এ চলে না)

---

## 5. Modified / Added / Preserved File Comparison (vs `6fe4a4e` Baseline)

- **Modified (11):** `RiyaPersona.kt`, `MainActivity.kt`, `RiyaStore.kt`, `GeminiLive.kt`, `LxrCapabilityAndAppEngine.kt`, `LxrDeviceAndHardwareEngine.kt`, `LxrProviderOrchestrator.kt`, `LxrResearchAndStorage.kt`, `RiyaService.kt`, `RiyaVoiceManager.kt`, `RiyaSubPages.kt`
- **Added (6):** `LxrProviderGateway.kt`, `LxrSpecialistAgents.kt`, `LxrAutonomousOrchestrator.kt`, `LxrTermuxFrameworkAdapters.kt`, `LxrMasterUpgradeTest.kt`, `RELEASE_NOTES_v2.43.0.md`
- **Removed (0 code files):** কোনো কার্যকর ফিচার বা সোর্স ফাইল মুছে ফেলা হয়নি।
- **Preserved 100%:** হোম UI ও ৩টি অ্যানিমেটেড থিম, ব্যক্তিত্ব ("তুমি"/"বস"), OSINT, OpenMontage ভিডিও স্টুডিও, App Builder, Termux ব্রিজ, কীস্টোর/সাইনিং ও `Download/LxR` স্টোরেজ।

---

## 6. Physical Device Verification Checklist (ফোনে যাচাই করার ধাপ)

> যেহেতু ক্লাউড বিল্ড কন্টেইনারে সরাসরি আপনার Infinix ফোন বা আসল Bluetooth হেডসেট যুক্ত নেই, তাই `191/191` রোবোলেকট্রিক ও ইউনিট টেস্ট পাসের পরও নিচের ৩টি হার্ডওয়্যার পরীক্ষা ফোনে ইনস্টল করে নিশ্চিত করুন:

1. **ElevenLabs Voice Test:** `LXR-AI-v2.43.0-debug.apk` ইনস্টল করুন -> Settings/Key পেজে ElevenLabs API Key ও Voice ID সেট করুন -> `"টেস্ট মিষ্টি ভয়েস"` চাপুন -> Voice Model পপআপ থেকে `ElevenLabs Voice` সিলেক্ট করে মাইক অন করুন এবং `"পুনরায় সংযোগ হচ্ছে..."` লুপ ছাড়াই স্পষ্ট বাংলা ভয়েস শুনুন।
2. **4-Model Mic Mute Test:** একে একে `Gemini 3.8 Live`, `Gemini 3.1 Flash Live (preview)`, `Gemini 2.5 Flash Native Audio (Dec 2025)`, এবং `ElevenLabs Voice` চালিয়ে Mic Mute বাটন অন করে কথা বলুন — কোনো কথা শোনা বা উত্তর আসা উচিত নয়; আনমিউট করলে আবার স্বাভাবিকভাবে কাজ করবে।
3. **Speaker & Bluetooth Playback Test:** Bluetooth হেডসেট ও ফোনের সাধারণ স্পিকার উভয় ক্ষেত্রেই উত্তর শোনার সময় `"LXR--AI কথা বলছে..."` এর পাশাপাশি অডিও বের হচ্ছে কি না এবং `run_autonomous_task` / `inspect_termux_frameworks` কমান্ড সঠিকভাবে চলে কি না যাচাই করুন।
