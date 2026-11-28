# LXR--AI v2.43.0 (versionCode 61) — A-to-Z Master Changelog & Source Audit

- **Application ID:** `com.aistudio.riyaai.krmxvp` (Preserved for seamless in-place update)
- **Version:** `2.43.0` (`versionCode = 61`)
- **Automated Verification:** `191 / 191` unit & Robolectric tests passing (`0 failures`, `0 errors`, `0 skipped`) across 25 test suites
- **Debug APK Artifact:** `LXR-AI-v2.43.0-debug.apk` (`49,823,075 bytes`)

---

## 1. ৩টি জরুরি বাগ ফিক্স (Urgent Voice, Mute & Audio Fixes)

1. **ElevenLabs Voice Fix (`GeminiLive.kt`, `RiyaVoiceManager.kt`, `RiyaSubPages.kt`):**
   - Gemini Live-এ ElevenLabs মোড চালু থাকলে `responseModalities` সবসময় `["AUDIO"]` + `outputAudioTranscription` হিসেবে নেগোসিয়েট করে (নেটিভ-অডিও মডেলের WS `1007/1008` `"পুনরায় সংযোগ হচ্ছে..."` লুপ সমাধান)।
   - Free/Restricted ElevenLabs API Key-এর HTTP `403 (output_format_not_allowed)` সমস্যার জন্য স্বয়ংক্রিয় ফরম্যাট ফলব্যাক (`pcm_24000 -> pcm_16000 -> mp3_44100_128`) + Android `MediaExtractor`/`MediaCodec` MP3 ডিকোডার এবং `eleven_multilingual_v2 -> eleven_flash_v2_5` মডেল ফলব্যাক যুক্ত।
   - KeyPage-এর `"টেস্ট মিষ্টি ভয়েস"` বাটনে ফোর্সড ElevenLabs প্লেব্যাক ও সিক্রেট-রিড্যাক্টেড এরর ডায়াগনস্টিক্স যুক্ত।

2. **Microphone Mute/Unmute Across All 4 Voice Models (`GeminiLive.kt`, `MainActivity.kt`, `RiyaVoiceManager.kt`):**
   - `Gemini 3.8 Live`, `Gemini 3.1 Flash Live (preview)`, `Gemini 2.5 Flash Native Audio (Dec 2025)`, এবং `ElevenLabs Voice` — ৪টি মডেলেই `GeminiLive.setMicMuted(muted)` একীভূত করা হয়েছে।
   - মিউট করলে তাৎক্ষণিকভাবে স্পিকার/TTS থামে, পেন্ডিং টেক্সট/কিউ পরিষ্কার হয়, `audioStreamEnd: true` পাঠানো হয়, `startMic()` জিরো-ফিল পাঠায় এবং মডেল সুইচ (`switchModelAndReconnect()`) করলেও মিউট অবস্থা বজায় থাকে।

3. **"আমি শুনি না" (Silent Playback Recovery — `GeminiLive.kt`, `RiyaVoiceManager.kt`, `RiyaStore.kt`):**
   - `RiyaState.isBtCommOutputVerified` দিয়ে Bluetooth কমিউনিকেশন আউটপুট আসলেই সক্রিয় কিনা যাচাই করা হয়; ব্যর্থ বা তারযুক্ত/A2DP হেডসেট হলে স্বয়ংক্রিয়ভাবে `MODE_NORMAL` + `USAGE_MEDIA` মোডে চলে যায়।
   - `playExec`-এ `AudioTrack.playbackHeadPosition` মনিটর করে সাইলেন্ট পাইপ স্টল (>১৪,৪০০ ফ্রেম লেখার পরও হেড না এগোনো) শনাক্ত হলেই `USAGE_MEDIA`-তে নতুন `AudioTrack` বানিয়ে অডিও পুনরায় চালু হয় এবং ভলিউম ০% হলে সতর্কতা দেখায়।

---

## 2. Critical Network Repair & Multi-Provider Gateway

- **`LxrDeviceAndHardwareEngine.solveCriticalTaskWithExtraModel()`** মেরামত করে রিটায়ার্ড `gemini-2.0-flash` ও খালি `catch` সরিয়ে `LxrProviderGateway`-এ সংযুক্ত করা হয়েছে।
- **নতুন `LxrProviderGateway.kt`:**
  - `LxrModelRegistry`: যাচাইকৃত `gemini-2.5-pro`, `gemini-2.5-flash`, `gemini-2.5-flash-lite`, `gemini-flash-latest`
  - `FailureClassifier`: `401/403`, `429`, `404`, `5xx`, Timeout/IO শ্রেণিবিন্যাস
  - `ExponentialBackoffRetry` + `ProviderCircuitBreaker` (`CLOSED -> OPEN -> HALF_OPEN`) + `ProviderHealthMonitor`
  - `FreeFirstProviderRouter`: ফ্রি-ফার্স্ট রাউটিং, স্বয়ংক্রিয় ফেইলওভার, `LxrUsageLedger` টোকেন ট্র্যাকিং ও `LxrSecretRedactor` সিক্রেট সুরক্ষা
- **`LxrProviderOrchestrator.kt` & `RiyaStore.kt`:** লাইভ গেটওয়ে হেলথ রিপোর্টিং ও ঐচ্ছিক OpenAI-Compatible BYO গেটওয়ে কনফিগারেশন (ডিফল্ট ফাঁকা)।

---

## 3. Autonomous Multi-Agent Ecosystem & Verification

- **`LxrAutonomousOrchestrator.kt` (`run_autonomous_task` টুল):** জটিলতা শ্রেণিবিন্যাস (`FAST_SINGLE / SPECIALIZED / MULTI_AGENT_PARALLEL`), `SupervisorJob` সমান্তরাল এজেন্ট এক্সিকিউশন, টাইমআউট ম্যানেজমেন্ট ও কোয়ালিটি গেট যাচাই।
- **`LxrSpecialistAgents.kt` (১৫টি মডিউলার স্পেশালিস্ট এজেন্ট):**
  1. `IntentAndPlannerAgent`
  2. `ReasoningAndMathAgent` (ডিটারমিনিস্টিক রিকার্সিভ-ডিসেন্ট ম্যাথ ইভ্যালুয়েটরসহ)
  3. `CodingAndDeveloperAgent`
  4. `CodeSecurityAuditorAgent` (হার্ডকোডেড সিক্রেট, দুর্বল TLS, cleartext HTTP, SQLi স্ট্যাটিক স্ক্যান)
  5. `DeepResearchVerificationAgent`
  6. `OsintInvestigationAgent`
  7. `DeviceAndHardwareAgent`
  8. `AccessibilityAndUiAgent`
  9. `CommunicationAndMessagingAgent`
  10. `EducationAndCurriculumAgent`
  11. `CreativeContentAndSeoAgent`
  12. `NetworkAndGatewayManagerAgent`
  13. `MonitoringAndQaAuditAgent`
  14. `VerificationCritiqueGateAgent`
  15. `DocumentationSummaryAgent`
- **`LxrTermuxFrameworkAdapters.kt`:** `LangGraph`, `CrewAI`, `LiteLLM`, `Aider`, `OpenHands`, `Haystack`, `LlamaIndex`, `Semgrep`-এর সৎ Termux প্রোব ও ইনস্টল কমান্ড।
- **`LxrResearchAndStorage.kt`:** `SourceCredibilityTier` ও `LxrResearchVerificationHelper` (উৎসের বিশ্বাসযোগ্যতা স্তর ও পরস্পরবিরোধী তথ্য শনাক্তকরণ)।
- **`RiyaService.kt` & `LxrCapabilityAndAppEngine.kt`:** `LxrNetworkHealthMonitor` (`ConnectivityManager.NetworkCallback`) ও আপডেটেড A-to-Z সক্ষমতা রিপোর্ট।
- **`LxrMasterUpgradeTest.kt`:** সব নতুন বাগ ফিক্স ও আপগ্রেডের পূর্ণাঙ্গ রোবোলেকট্রিক টেস্ট।

---

## 4. Modified vs Added Files (vs Baseline v2.43.0)

- **Modified (10):** `RiyaStore.kt`, `MainActivity.kt`, `GeminiLive.kt`, `LxrCapabilityAndAppEngine.kt`, `LxrDeviceAndHardwareEngine.kt`, `LxrProviderOrchestrator.kt`, `LxrResearchAndStorage.kt`, `RiyaService.kt`, `RiyaVoiceManager.kt`, `RiyaSubPages.kt`
- **Added (6):** `LxrProviderGateway.kt`, `LxrSpecialistAgents.kt`, `LxrAutonomousOrchestrator.kt`, `LxrTermuxFrameworkAdapters.kt`, `LxrMasterUpgradeTest.kt`, `RELEASE_NOTES_v2.43.0.md`
- **Preserved 100%:** হোম UI, ৩টি অ্যানিমেটেড থিম, ব্যক্তিত্ব ("তুমি"/"বস"), OSINT, OpenMontage ভিডিও স্টুডিও, App Builder, Termux ব্রিজ, এবং `Download/LxR` স্টোরেজ।
