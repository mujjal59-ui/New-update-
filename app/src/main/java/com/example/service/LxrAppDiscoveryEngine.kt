package com.example.service

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import java.text.Normalizer

data class InstalledAppEntry(
  val label: String,
  val packageName: String,
  val activityName: String?,
)

/**
 * Exhaustive, package-visibility-aware App Discovery & Launch Resolver (Android 7..16).
 * Resolves Bengali (NFC/NFD) + English + OEM (Infinix/Transsion) app names and system category
 * intents without falsely reporting installed apps as missing, and launches safely past Android 14-16 BAL.
 */
object LxrAppDiscoveryEngine {
  private val ALIAS_TO_KEYWORDS: Map<String, List<String>> =
    mapOf(
      "whatsapp" to listOf("whatsapp", "wa business", "whatsapp business", "w4b"),
      "হোয়াটসঅ্যাপ" to listOf("whatsapp"),
      "ওয়াটসঅ্যাপ" to listOf("whatsapp"),
      "হোয়াটস্যাপ" to listOf("whatsapp"),
      "imo" to listOf("imo", "imoim", "imo hd", "imo lite"),
      "ইমো" to listOf("imo", "imoim"),
      "ইমু" to listOf("imo", "imoim"),
      "messenger" to listOf("messenger", "orca", "mlite"),
      "মেসেঞ্জার" to listOf("messenger", "orca"),
      "মেসেনজার" to listOf("messenger", "orca"),
      "ম্যাসেঞ্জার" to listOf("messenger", "orca"),
      "facebook" to listOf("facebook", "katana", "fb lite", "facebook lite"),
      "ফেসবুক" to listOf("facebook", "katana"),
      "fb" to listOf("facebook", "katana"),
      "instagram" to listOf("instagram"),
      "ইনস্টাগ্রাম" to listOf("instagram"),
      "youtube" to listOf("youtube"),
      "ইউটিউব" to listOf("youtube"),
      "chrome" to listOf("chrome"),
      "ক্রোম" to listOf("chrome"),
      "play store" to listOf("play store", "google play", "vending"),
      "প্লে স্টোর" to listOf("play store", "vending"),
      "camera" to listOf("camera", "cam", "photo"),
      "ক্যামেরা" to listOf("camera"),
      "gallery" to listOf("gallery", "photos", "ai gallery", "album"),
      "গ্যালারি" to listOf("gallery", "photos", "ai gallery"),
      "ছবি" to listOf("gallery", "photos", "ai gallery"),
      "photos" to listOf("photos", "gallery", "ai gallery"),
      "calculator" to listOf("calculator", "calc"),
      "ক্যালকুলেটর" to listOf("calculator", "calc"),
      "settings" to listOf("settings", "setting"),
      "সেটিংস" to listOf("settings"),
      "সেটিং" to listOf("settings"),
      "contacts" to listOf("contacts", "contact", "people", "phonebook"),
      "কন্টাক্ট" to listOf("contacts", "contact"),
      "কনট্যাক্ট" to listOf("contacts", "contact"),
      "কন্টাক্টস" to listOf("contacts", "contact"),
      "ফোনবুক" to listOf("contacts", "contact"),
      "phone" to listOf("phone", "dialer", "caller", "smart.caller", "telecom"),
      "dialer" to listOf("phone", "dialer", "caller", "smart.caller", "telecom"),
      "ডায়ালার" to listOf("phone", "dialer", "caller", "smart.caller"),
      "ফোন" to listOf("phone", "dialer", "caller", "smart.caller"),
      "কল" to listOf("phone", "dialer", "caller"),
      "messages" to listOf("messages", "messaging", "sms", "mms"),
      "মেসেজ" to listOf("messages", "messaging", "sms"),
      "মেসেজেস" to listOf("messages", "messaging", "sms"),
      "এসএমএস" to listOf("messages", "messaging", "sms"),
      "clock" to listOf("clock", "deskclock", "alarm"),
      "ঘড়ি" to listOf("clock", "deskclock"),
      "অ্যালার্ম" to listOf("clock", "deskclock"),
      "calendar" to listOf("calendar"),
      "ক্যালেন্ডার" to listOf("calendar"),
      "files" to listOf("files", "file manager", "filemanager", "my files", "nbu.files"),
      "ফাইল" to listOf("files", "file manager", "filemanager"),
      "gmail" to listOf("gmail", "gm"),
      "জিমেইল" to listOf("gmail"),
      "maps" to listOf("maps", "google maps"),
      "ম্যাপ" to listOf("maps"),
      "ম্যাপস" to listOf("maps"),
      "tiktok" to listOf("tiktok", "musically", "trill"),
      "টিকটক" to listOf("tiktok", "musically", "trill"),
      "telegram" to listOf("telegram"),
      "টেলিগ্রাম" to listOf("telegram"),
      "snapchat" to listOf("snapchat"),
      "স্ন্যাপচ্যাট" to listOf("snapchat"),
      "bkash" to listOf("bkash"),
      "বিকাশ" to listOf("bkash"),
      "nagad" to listOf("nagad"),
      "নগদ" to listOf("nagad"),
      "rocket" to listOf("rocket", "nexus"),
      "রকেট" to listOf("rocket", "nexus"),
      "notes" to listOf("notes", "notepad", "transsion.notepad"),
      "notepad" to listOf("notes", "notepad", "transsion.notepad"),
      "নোটস" to listOf("notes", "notepad", "transsion.notepad"),
      "নোটপ্যাড" to listOf("notes", "notepad", "transsion.notepad"),
      "নোট" to listOf("notes", "notepad", "transsion.notepad"),
      "keep" to listOf("keep", "google.android.keep"),
      "keep notes" to listOf("keep", "google.android.keep"),
      "কিপ" to listOf("keep", "google.android.keep"),
      "কিপ নোটস" to listOf("keep", "google.android.keep"),
      "termux" to listOf("termux"),
      "টারমাক্স" to listOf("termux"),
      "capcut" to listOf("capcut", "lemon.lvoverseas"),
      "ক্যাপকাট" to listOf("capcut"),
      "gemini" to listOf("gemini", "bard"),
      "জেমিনি" to listOf("gemini", "bard"),
      "chatgpt" to listOf("chatgpt", "openai"),
      "চ্যাটজিপিটি" to listOf("chatgpt", "openai"),
      "spotify" to listOf("spotify"),
      "mx player" to listOf("mx player", "mxtech"),
      "recorder" to listOf("recorder", "sound recorder"),
      "রেকর্ডার" to listOf("recorder"),
      "music" to listOf("music", "boomplay", "yt music", "hoco.music"),
      "মিউজিক" to listOf("music", "hoco.music"),
      "ai assistant" to listOf("ai assistant", "assistant", "folax"),
      "এআই অ্যাসিস্ট্যান্ট" to listOf("ai assistant", "assistant"),
      "ai gallery" to listOf("ai gallery", "gallery", "transsion.gallery", "transsion.camera.gallery"),
      "এআই গ্যালারি" to listOf("ai gallery", "gallery"),
      "app center" to listOf("app center", "appcenter", "palmstore"),
      "অ্যাপ সেন্টার" to listOf("app center", "palmstore"),
      "authenticator" to listOf("authenticator", "google.android.apps.authenticator2"),
      "অথেনটিকেটর" to listOf("authenticator"),
      "bikroy" to listOf("bikroy", "com.bikroy"),
      "বিক্রয়" to listOf("bikroy"),
      "chrome beta" to listOf("chrome beta", "com.chrome.beta"),
      "ক্রোম বিটা" to listOf("chrome beta"),
      "claude" to listOf("claude", "com.anthropic.claude"),
      "ক্লড" to listOf("claude"),
      "compass" to listOf("compass"),
      "কম্পাস" to listOf("compass"),
      "daraz" to listOf("daraz", "com.daraz.android"),
      "দারাজ" to listOf("daraz"),
      "drive" to listOf("drive", "google drive", "google.android.apps.docs"),
      "ড্রাইভ" to listOf("drive", "google drive"),
      "elevenlabs" to listOf("elevenlabs", "io.elevenlabs"),
      "ইলেভেন ল্যাব" to listOf("elevenlabs"),
      "ইলেভেন ল্যাবস" to listOf("elevenlabs"),
      "ইলেভেনল্যাবস" to listOf("elevenlabs"),
      "f-droid" to listOf("f-droid", "fdroid", "org.fdroid.fdroid"),
      "এফ-ড্রয়েড" to listOf("f-droid", "fdroid"),
      "file manager" to listOf("file manager", "filemanager", "files", "transsion.filemanagerx", "nbu.files"),
      "ফাইল ম্যানেজার" to listOf("file manager", "filemanager", "files"),
      "find hub" to listOf("find hub", "find device", "adm", "google.android.apps.adm"),
      "ফাইন্ড হাব" to listOf("find hub", "find device"),
      "flow" to listOf("flow"),
      "ফ্লো" to listOf("flow"),
      "fm" to listOf("fm", "fm radio", "radio"),
      "এফএম" to listOf("fm", "fm radio"),
      "folax" to listOf("folax", "transsion.folax"),
      "ফোলাক্স" to listOf("folax"),
      "free fire" to listOf("free fire", "dts.freefireth", "freefire"),
      "ফ্রি ফায়ার" to listOf("free fire", "freefire"),
      "github" to listOf("github", "com.github.android"),
      "গিটহাব" to listOf("github"),
      "google" to listOf("google", "googlequicksearchbox"),
      "গুগল" to listOf("google", "googlequicksearchbox"),
      "hi translate" to listOf("hi translate", "hitranslate"),
      "হাই ট্রান্সলেট" to listOf("hi translate"),
      "hoco.music" to listOf("hoco.music", "hoco", "music"),
      "হোকো মিউজিক" to listOf("hoco.music", "hoco"),
      "hola" to listOf("hola", "hola vpn"),
      "হোলা" to listOf("hola"),
      "hopweb" to listOf("hopweb"),
      "হপওয়েব" to listOf("hopweb"),
      "lxr-ai" to listOf("lxr-ai", "lxr--ai", "lxr", "riyaai"),
      "এলএক্সআর" to listOf("lxr-ai", "lxr--ai", "lxr"),
      "meet" to listOf("meet", "google meet", "tachyon"),
      "মিট" to listOf("meet", "google meet"),
      "message" to listOf("message", "messages", "messaging", "sms"),
      "mobile cloner" to listOf("mobile cloner", "cloner", "phone cloner"),
      "মোবাইল ক্লোনার" to listOf("mobile cloner", "cloner"),
      "mone ai - leya" to listOf("mone ai", "leya", "mone"),
      "মনে এআই" to listOf("mone ai", "leya"),
      "my health" to listOf("my health", "myhealth", "health"),
      "মাই হেলথ" to listOf("my health", "health"),
      "mycirkle" to listOf("mycirkle", "cirkle"),
      "মাইসার্কেল" to listOf("mycirkle"),
      "মাই সার্কেল" to listOf("mycirkle"),
      "mygp" to listOf("mygp", "grameenphone", "com.portonics.mygp"),
      "মাইজিপি" to listOf("mygp", "grameenphone"),
      "মাই জিপি" to listOf("mygp", "grameenphone"),
      "phone master" to listOf("phone master", "phonemaster", "transsion.phonemaster"),
      "ফোন মাস্টার" to listOf("phone master"),
      "rea" to listOf("rea", "lxr-ai"),
      "riya ai" to listOf("riya ai", "lxr-ai"),
      "roaming" to listOf("roaming"),
      "রোমিং" to listOf("roaming"),
      "safety" to listOf("safety", "personal safety", "safetyhub"),
      "সেফটি" to listOf("safety"),
      "sim toolkit" to listOf("sim toolkit", "stk", "com.android.stk"),
      "সিম টুলকিট" to listOf("sim toolkit", "stk"),
      "snaptube" to listOf("snaptube", "com.snaptube.premium"),
      "স্ন্যাপটিউব" to listOf("snaptube"),
      "themes" to listOf("themes", "xtheme", "theme"),
      "থিমস" to listOf("themes", "xtheme"),
      "tips" to listOf("tips"),
      "টিপস" to listOf("tips"),
      "ultralink" to listOf("ultralink", "ultra link"),
      "আল্ট্রালিংক" to listOf("ultralink"),
      "video to photos" to listOf("video to photos", "video2photo"),
      "ভিডিও টু ফটোস" to listOf("video to photos"),
      "videos" to listOf("videos", "video player", "gallery"),
      "ভিডিওস" to listOf("videos", "video player"),
      "wa business" to listOf("wa business", "whatsapp business", "com.whatsapp.w4b"),
      "ডব্লিউএ বিজনেস" to listOf("wa business", "whatsapp business", "com.whatsapp.w4b"),
      "হোয়াটসঅ্যাপ বিজনেস" to listOf("wa business", "whatsapp business", "com.whatsapp.w4b"),
      "wallet" to listOf("wallet", "google wallet"),
      "ওয়ালেট" to listOf("wallet"),
      "weather" to listOf("weather", "transsion.weather"),
      "আবহাওয়া" to listOf("weather"),
      "ওয়েদার" to listOf("weather"),
      "welife" to listOf("welife", "tecno.welife"),
      "উইলাইফ" to listOf("welife"),
      "wps office" to listOf("wps office", "cn.wps.moffice"),
      "ডব্লিউপিএস অফিস" to listOf("wps office"),
      "x" to listOf("x", "twitter", "com.twitter.android"),
      "এক্স" to listOf("x", "twitter"),
      "টুইটার" to listOf("x", "twitter"),
      "xarena" to listOf("xarena", "transsion.gameassistant"),
      "এক্সএরিনা" to listOf("xarena"),
      "xclub" to listOf("xclub", "transsion.xclub"),
      "এক্সক্লাব" to listOf("xclub"),
      "yt music" to listOf("yt music", "youtube music", "youtube.music"),
      "ওয়াইটি মিউজিক" to listOf("yt music", "youtube music"),
      "yt studio" to listOf("yt studio", "youtube studio", "youtube.creator"),
      "ওয়াইটি স্টুডিও" to listOf("yt studio", "youtube studio"),
      "ইউটিউব স্টুডিও" to listOf("yt studio", "youtube studio"),
      "ytool" to listOf("ytool"),
      "ওয়াইটুল" to listOf("ytool"),
      "zarchiver" to listOf("zarchiver", "ru.zdevs.zarchiver"),
      "জেডআর্কিভার" to listOf("zarchiver"),
      "1.1.1.1" to listOf("1.1.1.1", "cloudflare", "one.one.one.one"),
      "ওয়ান ওয়ান" to listOf("1.1.1.1", "cloudflare"),
    )

  fun normalizeUnicode(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFC)
      .replace("\u200B", "")
      .replace("\u200C", "")
      .replace("\u200D", "")
      .replace("\uFEFF", "")

  fun normalizeQuery(raw: String): String =
    normalizeUnicode(raw)
      .lowercase()
      .replace(
        Regex("(?:^|[\\s,।!?])(?:অ্যাপস|অ্যাপ্স|অ্যাপটি|অ্যাপ|সফটওয়্যার|সফ্টওয়্যার|খোলো|খুলে|ওপেন|করো|চালু|দাও|apps|app|open|launch)(?=[\\s,।!?]|$)"),
        " ",
      )
      .replace(Regex("\\s+"), " ")
      .trim()

  fun expandSearchTerms(rawQuery: String): List<String> {
    val clean = normalizeQuery(rawQuery)
    if (clean.isBlank()) return emptyList()
    val out = linkedSetOf(clean)
    val words = clean.split(Regex("[\\s.,:/\\-_]+")).filter { it.isNotBlank() }
    out.addAll(words)
    ALIAS_TO_KEYWORDS.forEach { (key, targets) ->
      val normKey = normalizeUnicode(key).lowercase()
      val wholeWordHit =
        if (normKey.length <= 2) {
          clean == normKey || words.any { it == normKey }
        } else {
          clean == normKey || words.any { it == normKey } || clean.contains(normKey)
        }
      if (wholeWordHit) {
        out.addAll(targets)
      }
    }
    return out.toList()
  }

  /**
   * Transliterates common Romanized/English names or app terms into likely Bengali spelling
   * so search works cleanly in English first, then falls back to Bengali.
   */
  fun transliterateEnToBn(rawQuery: String): String {
    val clean = normalizeUnicode(rawQuery).trim()
    if (clean.isBlank()) return ""
    val lower = clean.lowercase()
    // Check explicit dictionary hits first
    val dictHits =
      mapOf(
        "whatsapp" to "হোয়াটসঅ্যাপ",
        "messenger" to "মেসেঞ্জার",
        "facebook" to "ফেসবুক",
        "youtube" to "ইউটিউব",
        "chrome" to "ক্রোম",
        "camera" to "ক্যামেরা",
        "gallery" to "গ্যালারি",
        "contacts" to "কন্টাক্টস",
        "phone" to "ফোন",
        "settings" to "সেটিংস",
        "calculator" to "ক্যালকুলেটর",
        "calendar" to "ক্যালেন্ডার",
        "clock" to "ঘড়ি",
        "bkash" to "বিকাশ",
        "nagad" to "নগদ",
        "daraz" to "দারাজ",
        "bikroy" to "বিক্রয়",
        "telegram" to "টেলিগ্রাম",
        "tiktok" to "টিকটক",
        "claude" to "ক্লড",
        "chatgpt" to "চ্যাটজিপিটি",
        "gemini" to "জেমিনি",
        "github" to "গিটহাব",
        "elevenlabs" to "ইলেভেনল্যাবস",
        "weather" to "আবহাওয়া",
        "notes" to "নোটস",
        "keep notes" to "কিপ নোটস",
        "free fire" to "ফ্রি ফায়ার",
        "rahim" to "রহিম",
        "karim" to "করিম",
        "ujjal" to "উজ্জল",
        "abbu" to "আব্বু",
        "ammu" to "আম্মু",
        "vai" to "ভাই",
        "bhai" to "ভাই",
        "apu" to "আপু",
        "mama" to "মামা",
        "chacha" to "চাচা",
        "ma" to "মা",
        "baba" to "বাবা",
      )
    dictHits[lower]?.let { return it }

    // Digraph-aware phonetic English -> Bengali fallback for contact/item names
    val sb = StringBuilder()
    var i = 0
    while (i < lower.length) {
      val rem = lower.substring(i)
      when {
        rem.startsWith("sh") -> { sb.append("শ"); i += 2 }
        rem.startsWith("ch") -> { sb.append("চ"); i += 2 }
        rem.startsWith("th") -> { sb.append("থ"); i += 2 }
        rem.startsWith("dh") -> { sb.append("ধ"); i += 2 }
        rem.startsWith("kh") -> { sb.append("খ"); i += 2 }
        rem.startsWith("gh") -> { sb.append("ঘ"); i += 2 }
        rem.startsWith("bh") -> { sb.append("ভ"); i += 2 }
        rem.startsWith("ph") -> { sb.append("ফ"); i += 2 }
        rem.startsWith("jh") -> { sb.append("ঝ"); i += 2 }
        rem.startsWith("ng") -> { sb.append("ং"); i += 2 }
        rem.startsWith("aa") -> { sb.append("আ"); i += 2 }
        rem.startsWith("ee") || rem.startsWith("ii") -> { sb.append("ই"); i += 2 }
        rem.startsWith("oo") -> { sb.append("উ"); i += 2 }
        else -> {
          val ch = lower[i]
          val mapped =
            when (ch) {
              'a' -> if (i == 0) "আ" else "া"
              'b' -> "ব"
              'c' -> "স"
              'd' -> "দ"
              'e' -> if (i == 0) "এ" else "ে"
              'f' -> "ফ"
              'g' -> "গ"
              'h' -> "হ"
              'i' -> if (i == 0) "ই" else "ি"
              'j' -> "জ"
              'k' -> "ক"
              'l' -> "ল"
              'm' -> "ম"
              'n' -> "ন"
              'o' -> if (i == 0) "ও" else "ো"
              'p' -> "প"
              'q' -> "ক"
              'r' -> "র"
              's' -> "স"
              't' -> "ত"
              'u' -> if (i == 0) "উ" else "ু"
              'v' -> "ভ"
              'w' -> "ও"
              'x' -> "এক্স"
              'y' -> "ইয়"
              'z' -> "জ"
              else -> ch.toString()
            }
          sb.append(mapped)
          i++
        }
      }
    }
    return sb.toString().trim()
  }

  /**
   * Builds bilingual search queries: English term(s) FIRST, then Bengali alias / transliteration fallback.
   */
  fun buildBilingualSearchQueries(rawQuery: String): List<String> {
    val clean = normalizeQuery(rawQuery).ifBlank { normalizeUnicode(rawQuery).trim() }
    if (clean.isBlank()) return emptyList()
    val hasBengali = clean.any { it in '\u0980'..'\u09FF' }
    val expanded = expandSearchTerms(clean)
    val ordered = linkedSetOf<String>()
    if (!hasBengali) {
      ordered.add(clean)
      expanded.filter { t -> t.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }.forEach { ordered.add(it) }
      // Bengali fallback second
      val bn = transliterateEnToBn(clean)
      if (bn.isNotBlank() && bn != clean) ordered.add(bn)
      ALIAS_TO_KEYWORDS.forEach { (k, vals) ->
        if (k.any { it in '\u0980'..'\u09FF' } && vals.any { v -> clean.equals(v, true) || clean.contains(v, true) }) {
          ordered.add(k)
        }
      }
    } else {
      // Expand English equivalents FIRST, then keep original Bengali as fallback
      expanded.filter { t -> t.any { it in 'a'..'z' || it in 'A'..'Z' } }.forEach { ordered.add(it) }
      ordered.add(clean)
      expanded.forEach { ordered.add(it) }
    }
    return ordered.filter { it.isNotBlank() }
  }

  /**
   * Resolves core system app intents (Dialer, Contacts, Messages, Camera, Gallery, Calculator, Calendar, Settings)
   * directly via Android PackageManager so OEM package differences never fail.
   */
  fun resolveSystemCategoryIntent(ctx: Context, rawQuery: String): Pair<String, Intent>? {
    val terms = expandSearchTerms(rawQuery)
    if (terms.isEmpty()) return null
    val pm = ctx.packageManager

    fun pickResolved(intent: Intent): Intent? {
      val resolved =
        runCatching {
          if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(
              intent,
              PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
          } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
          }
        }.getOrDefault(emptyList())
      val candidate = resolved.firstOrNull { it.activityInfo != null && it.activityInfo.packageName != ctx.packageName }
      if (candidate != null) {
        val pkg = candidate.activityInfo.packageName
        pm.getLaunchIntentForPackage(pkg)?.let {
          return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        return Intent(intent)
          .setClassName(pkg, candidate.activityInfo.name)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
      }
      return null
    }

    val checks =
      listOf(
        Triple(listOf("phone", "dialer", "caller", "smart.caller", "ডায়ালার", "ফোন", "কল"), "Phone") {
          Intent(Intent.ACTION_DIAL)
        },
        Triple(listOf("contacts", "contact", "people", "phonebook", "কন্টাক্ট", "কনট্যাক্ট", "কন্টাক্টস", "ফোনবুক"), "Contacts") {
          Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        },
        Triple(listOf("messages", "messaging", "sms", "mms", "মেসেজ", "মেসেজেস", "এসএমএস"), "Messages") {
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
        },
        Triple(listOf("camera", "ক্যামেরা"), "Camera") {
          Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        },
        Triple(listOf("gallery", "photos", "ai gallery", "গ্যালারি", "ছবি"), "Gallery") {
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
        },
        Triple(listOf("calculator", "calc", "ক্যালকুলেটর"), "Calculator") {
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR)
        },
        Triple(listOf("calendar", "ক্যালেন্ডার"), "Calendar") {
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
        },
        Triple(listOf("settings", "সেটিংস", "সেটিং"), "Settings") {
          Intent(Settings.ACTION_SETTINGS)
        },
      )

    for ((keys, label, intentFactory) in checks) {
      if (terms.any { t -> t in keys }) {
        val intent = intentFactory()
        pickResolved(intent)?.let { return label to it }
        if (label == "Contacts") {
          val catIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)
          pickResolved(catIntent)?.let { return label to it }
        }
      }
    }
    return null
  }

  /**
   * Launches an Activity safely from background Service context even under Android 14/15/16 BAL rules,
   * prioritizing AccessibilityService context and ActivityOptions BAL opt-in before standard fallback.
   */
  fun launchActivitySafely(
    ctx: Context,
    acc: RiyaAccessibilityService?,
    intent: Intent,
  ): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (acc != null) {
      val accOk =
        runCatching {
          acc.startActivity(intent)
          true
        }.getOrDefault(false)
      if (accOk) return true
    }
    if (Build.VERSION.SDK_INT >= 34) {
      val balOk =
        runCatching {
          val opts =
            ActivityOptions.makeBasic()
              .setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
              )
          ctx.startActivity(intent, opts.toBundle())
          true
        }.getOrDefault(false)
      if (balOk) return true
    }
    return runCatching {
      ctx.startActivity(intent)
      true
    }.getOrDefault(false)
  }

  fun queryAllLaunchableApps(ctx: Context): List<InstalledAppEntry> {
    val pm = ctx.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val seen = linkedSetOf<String>()
    val entries = mutableListOf<InstalledAppEntry>()

    val activities =
      runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
          pm.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
          )
        } else {
          @Suppress("DEPRECATION")
          pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }
      }.getOrElse {
        runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())
      }

    for (ri in activities) {
      val pkg = ri.activityInfo?.packageName ?: continue
      val act = ri.activityInfo?.name
      val dedupKey = "$pkg/$act"
      if (!seen.add(dedupKey)) continue
      val label = normalizeUnicode(runCatching { ri.loadLabel(pm).toString().trim() }.getOrDefault(pkg)).ifBlank { pkg }
      entries.add(InstalledAppEntry(label = label, packageName = pkg, activityName = act))
    }

    // Fallback 1: query core system category intents so OEM Dialer / Contacts / SMS / Gallery are always included
    val categoryIntents =
      listOf(
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
      )
    for (ci in categoryIntents) {
      val resolved =
        runCatching {
          if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(ci, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
          } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(ci, PackageManager.MATCH_ALL)
          }
        }.getOrDefault(emptyList())
      for (ri in resolved) {
        val pkg = ri.activityInfo?.packageName ?: continue
        val act = ri.activityInfo?.name
        if (pkg == ctx.packageName) continue
        val dedupKey = "$pkg/$act"
        if (!seen.add(dedupKey)) continue
        val label = normalizeUnicode(runCatching { ri.loadLabel(pm).toString().trim() }.getOrDefault(pkg)).ifBlank { pkg }
        entries.add(InstalledAppEntry(label = label, packageName = pkg, activityName = act))
      }
    }

    // Fallback 2: include any installed launchable apps returned by getInstalledApplications
    val installedApps =
      runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
          pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
          @Suppress("DEPRECATION")
          pm.getInstalledApplications(0)
        }
      }.getOrDefault(emptyList())

    val existingPkgs = entries.map { it.packageName }.toSet()
    for (ai in installedApps) {
      val pkg = ai.packageName ?: continue
      if (pkg in existingPkgs) continue
      val li = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
      val label = normalizeUnicode(runCatching { pm.getApplicationLabel(ai).toString().trim() }.getOrDefault(pkg)).ifBlank { pkg }
      entries.add(InstalledAppEntry(label = label, packageName = pkg, activityName = li.component?.className))
    }

    return entries.sortedBy { it.label.lowercase() }
  }

  fun matchInstalledEntry(
    rawQuery: String,
    installed: List<InstalledAppEntry>,
  ): InstalledAppEntry? {
    val terms = expandSearchTerms(rawQuery)
    if (terms.isEmpty() || installed.isEmpty()) return null
    val cleanQuery = normalizeQuery(rawQuery)

    fun compact(s: String) = normalizeUnicode(s).lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    // Strict guard against Messenger <-> Facebook and Notes <-> Keep Notes mixups
    val wantsMessenger = "messenger" in terms || "মেসেঞ্জার" in terms
    val wantsFacebook = ("facebook" in terms || "fb" in terms || "ফেসবুক" in terms) && !wantsMessenger
    val wantsKeep =
      cleanQuery.contains("keep") ||
        cleanQuery.contains("কিপ") ||
        "google.android.keep" in terms
    val wantsNotes =
      !wantsKeep &&
        ("notes" in terms || "notepad" in terms || "নোট" in terms || "নোটস" in terms || "নোটপ্যাড" in terms)
    val hasDeviceNotepad =
      installed.any { e ->
        val ep = e.packageName.lowercase()
        val el = normalizeUnicode(e.label).lowercase()
        ep.contains("notepad") || (!ep.contains("keep") && (el == "notes" || el == "notepad"))
      }

    fun isBlockedCrossMatch(entry: InstalledAppEntry): Boolean {
      val p = entry.packageName.lowercase()
      val l = normalizeUnicode(entry.label).lowercase()
      if (wantsMessenger && (p == "com.facebook.katana" || p == "com.facebook.lite")) return true
      if (wantsFacebook && (p.contains("orca") || p.contains("mlite") || l == "messenger")) return true
      if (wantsKeep && p.contains("notepad") && !p.contains("keep")) return true
      if (wantsNotes && hasDeviceNotepad && (p == "com.google.android.keep" || l.contains("keep"))) return true
      return false
    }

    var best: InstalledAppEntry? = null
    var bestScore = 0
    for (entry in installed) {
      if (isBlockedCrossMatch(entry)) continue
      val lblLow = normalizeUnicode(entry.label).lowercase().trim()
      val lblComp = compact(lblLow)
      val pkgLow = entry.packageName.lowercase()
      var score = 0

      if (lblLow == cleanQuery || lblComp == compact(cleanQuery)) score = maxOf(score, 120)
      for (t in terms) {
        val tLow = normalizeUnicode(t).lowercase().trim()
        val tComp = compact(tLow)
        if (tLow.isBlank()) continue
        when {
          lblLow == tLow || (tComp.length >= 2 && lblComp == tComp) -> score = maxOf(score, 110)
          lblLow.startsWith("$tLow ") || lblLow == tLow -> score = maxOf(score, 95)
          tLow.length >= 3 && lblLow.contains(tLow) -> score = maxOf(score, 85)
          tComp.length >= 3 && lblComp.contains(tComp) -> score = maxOf(score, 80)
          tComp.length >= 3 && pkgLow.contains(tComp) -> score = maxOf(score, 72)
        }
      }
      if (score > bestScore) {
        bestScore = score
        best = entry
      }
    }
    return if (bestScore >= 72) best else null
  }

  fun buildLaunchIntent(ctx: Context, entry: InstalledAppEntry): Intent? {
    val pm = ctx.packageManager
    val direct = runCatching { pm.getLaunchIntentForPackage(entry.packageName) }.getOrNull()
    if (direct != null) {
      return direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val act = entry.activityName
    if (!act.isNullOrBlank()) {
      return Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(entry.packageName, act)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return null
  }

  fun listInstalledAppsReport(ctx: Context, filterQuery: String = ""): String {
    val all = queryAllLaunchableApps(ctx)
    if (all.isEmpty()) {
      return "ফোনে লঞ্চার অ্যাপ তালিকা পাওয়া যাচ্ছে না বস — `open_app` দিয়ে সরাসরি হোম ও অ্যাপ ড্রয়ার স্ক্যান করা যাবে।"
    }
    val cleanFilter = normalizeQuery(filterQuery)
    if (cleanFilter.isNotBlank()) {
      val matched = matchInstalledEntry(cleanFilter, all)
      if (matched != null) {
        return "হ্যাঁ বস! '${matched.label}' (${matched.packageName}) অ্যাপটি ফোনে ইনস্টল করা আছে ✓"
      }
      val terms = expandSearchTerms(cleanFilter)
      val partial =
        all.filter { e ->
          val hay = "${e.label} ${e.packageName}".lowercase()
          terms.any { t -> t.length >= 2 && hay.contains(t) }
        }
      if (partial.isNotEmpty()) {
        return "ফোনে এই মিল থাকা অ্যাপগুলো পাওয়া গেছে (${partial.size}টি):\n" +
          partial.take(15).joinToString("\n") { "• ${it.label} (${it.packageName})" }
      }
    }
    return buildString {
      appendLine("📱 এই ফোনে মোট ${all.size}টি লঞ্চযোগ্য অ্যাপ ইনস্টল করা আছে:")
      all.take(60).forEach { appendLine("• ${it.label} (${it.packageName})") }
      if (all.size > 60) appendLine("...এবং আরও ${all.size - 60}টি অ্যাপ রয়েছে।")
    }.trim()
  }

  fun buildInstalledAppsSummaryForPrompt(ctx: Context, maxNames: Int = 72): String {
    val all = queryAllLaunchableApps(ctx)
    if (all.isEmpty()) return "Check with open_app / list_installed_apps"
    val coreHints =
      listOf(
        "whatsapp",
        "imo",
        "messenger",
        "facebook",
        "phone",
        "dialer",
        "contacts",
        "messages",
        "youtube",
        "chrome",
        "camera",
        "gallery",
        "settings",
        "bkash",
        "nagad",
        "notes",
        "calculator",
        "gmail",
        "maps",
      )
    val prioritized =
      all.sortedByDescending { entry ->
        val hay = "${entry.label} ${entry.packageName}".lowercase()
        if (coreHints.any { hay.contains(it) }) 1 else 0
      }
    val labels = prioritized.map { it.label }.distinct()
    val shown = labels.take(maxNames).joinToString(", ")
    return "Total ${labels.size} apps installed (key & installed: $shown)"
  }
}
