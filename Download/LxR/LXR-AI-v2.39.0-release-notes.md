# LXR--AI v2.39.0 (versionCode 57) — Release Notes

## প্রধান আপডেটসমূহ (Autonomous System + Smart Lock + Voice Queue + 9-Field Monitor)

1. **Direct Autonomous Command Execution**
   - কমান্ড পাওয়া মাত্র অপ্রয়োজনীয় কনফার্মেশন ছাড়াই পূর্ব-অনুমোদিত ক্ষমতা ব্যবহার করে সরাসরি কাজ শুরু ও যাচাইকৃত ফলাফল প্রদান।

2. **Smart Screen Lock Detection & Zero False "Slide again to unlock"**
   - `LockVaultManager.evaluateSmartLockExecutionDecision` ও `hasVisibleRegularAppOrLauncherWindow` যোগ করা হয়েছে।
   - হোম স্ক্রিন, লঞ্চার বা খোলা অ্যাপে কখনোই অকারণ সোয়াইপ বা মিথ্যা `"Slide again to unlock"` সতর্কতা আসবে না।
   - সত্যিকারের লক অবস্থায় ব্যাকগ্রাউন্ড API কাজ স্ক্রিন না জ্বালিয়েই সম্পন্ন হবে, এবং অপরিহার্য UI কাজের জন্য বৈধ আনলক ব্যবস্থা ব্যবহার করে শেষে রিলক করবে।

3. **Real Background Execution & Parallel Multi-Tasking**
   - `RiyaService` Foreground Service, native `JobScheduler` (`LxrBackgroundJobService`), `SupervisorJob + Dispatchers.IO` ও `GeminiLive` ৪-থ্রেড পুল (`uiToolLock` আলাদা রেখে) সমান্তরাল মাল্টি-টাস্ক চালায়।
   - ডিপেন্ডেন্সি অর্ডার (`dependsOnTaskId`), ডুপ্লিকেট প্রোটেকশন (`dedupKey`), এরর আইসোলেশন ও রিস্টার্ট রিকভারি যুক্ত করা হয়েছে।

4. **Voice Response — 3 Independent Rules (`Instant 0s / Fast 8s / Normal 15s` + FIFO Queue)**
   - **Reply Delay:** `Instant — 0 sec (Default)`, `Fast — 8 sec` (`8,000 ms`), `Normal — 15 sec` (`15,000 ms`) — ১২০০ms কৃত্রিম ক্যাপ ও মাইক ব্লকিং দূর করা হয়েছে।
   - **Reply Limit:** `Unlimited (Default)`, `20 Replies`, `50 Replies` এটমিক কোটা ও রিস্টার্ট পার্সিস্টেন্স।
   - **Non-Blocking FIFO Voice Queue (`LxrVoiceCommandQueue`):** আগের কমান্ডের রিপ্লাই বা ডিলে চলাকালীনও মাইক লাইভ থাকে; প্রতিটি কমান্ড আলাদা Task ID ও ডুপ্লিকেট ফিল্টারসহ ক্রমানুসারে (`Response 1 -> Response 2`) প্রসেস হয় এবং VAD/API/Delay/Queue/TTS ল্যাটেন্সি রেকর্ড হয়।

5. **9-Field LXR Activity Monitor & Fresh GPS Fix**
   - প্রতিটি কাজের ৯টি বাস্তব তথ্য (প্রাপ্ত সময়, শুরু সময়, BACKGROUND/FOREGROUND মোড, অপারেশন, অগ্রগতি %, অবস্থা, বাস্তব ফলাফল, সময় ms, ত্রুটি) UI ও হিস্ট্রিতে যুক্ত করা হয়েছে।
   - `LxrDeviceAndHardwareEngine.selectBestLocation` ও API 30+ `getCurrentLocation` ফ্রেশ GPS ফিক্স যোগ করা হয়েছে।

6. **Preserved Core Personality & Features**
   - `"বস"` + `"তুমি"` সম্বোধন, স্রষ্টার হুবহু পরিচয় (`CREATOR_IDENTITY_BN`), অ্যানিমেটেড থিম, ৩-ধাপের ভয়েস ম্যাচ, App Builder, 3D Studio এবং YouTube SEO অক্ষত রয়েছে।
