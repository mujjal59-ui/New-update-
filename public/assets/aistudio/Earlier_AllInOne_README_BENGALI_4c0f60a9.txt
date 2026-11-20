LXR--AI ALL-IN-ONE BUILDER PACKAGE
(LXR APK Build + General App Builder + LXR Video Make + LXR YouTube Video SEO)

এই ZIP-এ Google AI Studio-তে ব্যবহার করার জন্য Master Prompt, ধাপে ধাপে Prompt, Architecture/Acceptance Criteria এবং Regression Test Checklist আছে।

গুরুত্বপূর্ণ:
- এটি implementation prompt/specification package; আপনার আসল Android source project বা APK নয়।
- আপনার সর্বশেষ LXR--AI source project ZIP আলাদাভাবে একই Google AI Studio chat-এ আপলোড করতে হবে।
- source project ছাড়া পুরোনো Termux integration ঠিক কোন ফাইলে আছে তা যাচাই বা বাস্তবে সংরক্ষণ করা সম্ভব নয়।
- প্রথমে Read-only Audit চালান। Audit না দেখে সরাসরি code change করতে দেবেন না।
- সবগুলো কাজ একই ZIP-এ পরিকল্পনা করা হয়েছে, কিন্তু বাস্তব implementation ধাপে ধাপে এবং approval/checkpoint সহ হবে।
- Termux-এর বর্তমান direct command execution path-কে protected core feature হিসেবে সংরক্ষণের নির্দেশ দেওয়া হয়েছে।
- কোনো provider/API, Phind endpoint, video generation, build বা test সফল হয়েছে বলে দাবি করা যাবে না যতক্ষণ না বাস্তবে যাচাই হয়।
- Paid API ব্যবহারের আগে আপনার অনুমতি আবশ্যক।

ব্যবহারের নিয়ম:
1) Google AI Studio-তে সর্বশেষ LXR--AI source project ZIP আপলোড করুন।
2) এই package ZIP-টিও আপলোড করুন।
3) 01_MASTER_PROMPT/LXR_ALL_IN_ONE_MASTER_PROMPT.txt পাঠান। এটি প্রথমে শুধু audit করবে; code edit করবে না।
4) Audit report-এ Termux integration, সব existing feature এবং exact file-level plan যাচাই করুন।
5) অনুমোদনের পর 02_STAGED_PROMPTS-এর prompt একে একে পাঠান: General App Builder/Termux, Video Make, YouTube SEO, Provider Orchestration।
6) প্রতিটি ধাপের পর build/test report দেখে পরের ধাপে যান।
7) সবশেষে Release & Regression Test Checklist পূরণ করান।

আপনার বিশেষ শর্ত:
LXR আগে যেভাবে ফোনের Termux-এ command চালাতে পারত, সেই বাস্তব integration যেন নষ্ট না হয়। এটিকে mock/simulated terminal বা অন্য কোনো অযাচাইকৃত ব্যবস্থায় বদলানো যাবে না। সব unrelated feature, UI, voice, settings, navigation ও data অপরিবর্তিত রাখতে হবে।
