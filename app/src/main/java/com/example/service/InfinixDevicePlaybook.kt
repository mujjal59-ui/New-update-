package com.example.service

data class InfinixNavigationRule(
  val id: String,
  val title: String,
  val appOrSection: String,
  val searchKeyword: String,
  val xosAndroid16Path: String,
  val quickTip: String,
)

object InfinixDevicePlaybook {
  const val DEVICE_PROFILE = "Infinix Hot 60 Pro+ • Android 16 (XOS)"

  val rules: List<InfinixNavigationRule> =
    listOf(
      InfinixNavigationRule(
        id = "xos_settings_bottom_search",
        title = "Infinix Settings-এ নিচের Search বার নিয়ম",
        appOrSection = "Infinix Android 16 Settings",
        searchKeyword = "Search",
        xosAndroid16Path =
          "Settings-এর একদম নিচের সার্চ বার ('Search') ট্যাপ -> কিওয়ার্ড টাইপ -> উপরের Suggestion/Result ট্যাপ",
        quickTip =
          "Infinix Hot 60 Pro+ Android 16-এ Settings-এর সার্চ বার স্ক্রিনের নিচে থাকে।",
      ),
      InfinixNavigationRule(
        id = "xos_contacts_dialer_search",
        title = "Phone ও Contacts-এ উপরের Search আইকন নিয়ম",
        appOrSection = "Phone / Contacts (Android 16)",
        searchKeyword = "Search icon / সার্চ",
        xosAndroid16Path =
          "উপরের ডান কোণের সার্চ আইকন (🔍) ট্যাপ -> নিচে/মাঝে সার্চ বক্স খুললে নাম টাইপ -> ফলাফল ট্যাপ",
        quickTip = "কন্টাক্ট লিস্ট লম্বা হলেও ২ সেকেন্ডে নাম বা নম্বর বের করা যায়।",
      ),
      InfinixNavigationRule(
        id = "imo_chat_send",
        title = "ইমো (imo)-তে নীল সেন্ড বাটন বনাম ইমোজি নিয়ম",
        appOrSection = "imo Messenger",
        searchKeyword = "btn_send / send / ডানের নীল বিমান",
        xosAndroid16Path =
          "মেসেজ বক্সে লেখা -> ইমোজি/রিঅ্যাকশন সারি বাদ -> ইনপুট বারের ডান পাশের নীল সেন্ড বাটনে ক্লিক",
        quickTip = "LXR--AI এখন উপরের রিঅ্যাকশন ইমোজি বা শেষের স্মাইলি বাটনে কখনোই ভুল ক্লিক করবে না।",
      ),
      InfinixNavigationRule(
        id = "messenger_whatsapp_search",
        title = "Messenger ও WhatsApp দ্রুত চ্যাট সার্চ নিয়ম",
        appOrSection = "WhatsApp / Messenger",
        searchKeyword = "Search / Ask Meta AI or Search",
        xosAndroid16Path =
          "উপরের সার্চ বারে/আইকনে ট্যাপ -> নাম টাইপ -> সার্চ বক্সের নিচে আসা চ্যাট/কন্টাক্ট তালিকায় সরাসরি নামের টেক্সটে ট্যাপ (সার্চ বক্স বা প্রোফাইল ছবিতে নয়)",
        quickTip = "সার্চ করার পর নিচে যে নামের তালিকা আসে, সেখানে নামের ওপর ট্যাপ করলেই চ্যাট খুলে যাবে।",
      ),
      InfinixNavigationRule(
        id = "ai_apps_send",
        title = "Gemini ও ChatGPT-তে প্রম্পট লিখে সেন্ড নিয়ম",
        appOrSection = "Gemini / ChatGPT",
        searchKeyword = "Send / Submit / Up Arrow",
        xosAndroid16Path =
          "নিচের লেখার বক্সে প্রশ্ন/কমান্ড টাইপ -> নিচে ডানের নীল উপরমুখী তির (↑) সেন্ড বাটনে ট্যাপ",
        quickTip = "কথা বলার পরপরই নিজে থেকে পুরো স্ক্রিন পড়ে রেজাল্ট যাচাই করে।",
      ),
      InfinixNavigationRule(
        id = "xos_accessibility_autostart",
        title = "XOS ব্যাকগ্রাউন্ড ও অ্যাক্সেসিবিলিটি সুরক্ষা",
        appOrSection = "Phone Master & Battery",
        searchKeyword = "Unrestricted / Auto-start / Notification access",
        xosAndroid16Path =
          "App Info -> Battery Unrestricted + Phone Master Auto-start + Accessibility & Notification Access চালু",
        quickTip = "Infinix Android 16-এ অ্যাক্সেসিবিলিটি বন্ধ হলে LXR--AI সাথে সাথে বসকে সতর্ক করে।",
      ),
    )

  val searchButtonKeywords =
    listOf(
      "search",
      "খুঁজুন",
      "সার্চ",
      "find",
      "query",
      "menu_search",
      "action_search",
      "search_bar",
      "search_button",
      "search_src_text",
      "et_search",
      "ic_search",
      "search_plate",
      "search_box",
    )
}
