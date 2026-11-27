package com.example.video

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent Unlimited Character Library for 3D Cartoon Movies & Episodic Series:
 * - Pre-seeded diverse cast (3D Cartoon Boy/Girl, Kids, Parents, Teacher, Student, Friends, Doctor, Police, Farmer, Business, Animals & Fantasy)
 * - Unlimited custom character creation & Reference Image attachment
 * - Reusable across videos, dramas & episodes to preserve look, outfit & voice continuity
 */
object CharacterLibraryStore {
  private const val FILE_NAME = "lxr_character_library.json"

  fun defaultCharacters(): List<CharacterProfile> = listOf(
    CharacterProfile(
      id = "lib_maya_girl",
      name = "মায়া (Maya)",
      roleBn = "3D Cartoon Girl / মেধাবী ছাত্রী",
      visualDescription = "উজ্জ্বল চোখ, বেণি করা চুল, নীল-গোলাপি ফ্রক ও প্রাণবন্ত হাসি",
      recommendedVoiceId = "Aoede",
      primaryColorHex = 0xFFEC4899,
      accentColorHex = 0xFFFDE047,
      avatarVariant = "girl_3d",
      ageGroup = CharacterAgeGroup.CHILD,
      category = CharacterCategory.CARTOON_GIRL,
      personality = "কৌতূহলী, মেধাবী ও বন্ধুত্বপূর্ণ",
      outfitStyle = "স্কুল ইউনিফর্ম ও ফিতে বাঁধা চুল",
      hairColorHex = 0xFF31102F,
    ),
    CharacterProfile(
      id = "lib_rafi_boy",
      name = "রাফি (Rafi)",
      roleBn = "3D Cartoon Boy / স্কুল বন্ধু",
      visualDescription = "চটপটে ৩ডি ছেলে, সবুজ জ্যাকেট ও স্পোর্টস জুতা",
      recommendedVoiceId = "Orus",
      primaryColorHex = 0xFF0EA5E9,
      accentColorHex = 0xFF22D3EE,
      avatarVariant = "boy_3d",
      ageGroup = CharacterAgeGroup.CHILD,
      category = CharacterCategory.CARTOON_BOY,
      personality = "সাহসী, মজার ও সাহায্যকারী",
      outfitStyle = "নীল-হলুদ হুডি ও কার্টুন ব্যাকপ্যাক",
      hairColorHex = 0xFF1E293B,
    ),
    CharacterProfile(
      id = "lib_teacher_farhana",
      name = "ফারহানা ম্যাম",
      roleBn = "শিক্ষিকা (Teacher)",
      visualDescription = "স্নেহময়ী শিক্ষিকা, চশমা, পরিপাটি শাড়ি ও হাতের নির্দেশিকা",
      recommendedVoiceId = "Leda",
      primaryColorHex = 0xFF8B5CF6,
      accentColorHex = 0xFFC084FC,
      avatarVariant = "teacher_3d",
      ageGroup = CharacterAgeGroup.ADULT,
      category = CharacterCategory.TEACHER,
      personality = "ধৈর্যশীল, জ্ঞানদায়ী ও মমতাময়ী",
      outfitStyle = "সাদা-বেগুনি শাড়ি ও চশমা",
      hairColorHex = 0xFF0F172A,
    ),
    CharacterProfile(
      id = "lib_baba_karim",
      name = "বাবা করিম",
      roleBn = "বাবা ও অভিভাবক (Parent)",
      visualDescription = "যত্নশীল বাবা, শান্ত মুখভঙ্গি ও সাদামাটা পাঞ্জাবি-শার্ট",
      recommendedVoiceId = "Iapetus",
      primaryColorHex = 0xFF14B8A6,
      accentColorHex = 0xFF5EEAD4,
      avatarVariant = "parent_male",
      ageGroup = CharacterAgeGroup.ADULT,
      category = CharacterCategory.PARENT_FAMILY,
      personality = "দায়িত্বশীল ও স্নেহশীল",
      outfitStyle = "হালকা সবুজ শার্ট ও প্যান্ট",
      hairColorHex = 0xFF334155,
    ),
    CharacterProfile(
      id = "lib_maa_ayesha",
      name = "মা আয়েশা",
      roleBn = "মা ও গৃহিণী (Mother)",
      visualDescription = "হাসিমুখ মা, কোমল কণ্ঠ ও ঐতিহ্যবাহী পোশাক",
      recommendedVoiceId = "Sulafat",
      primaryColorHex = 0xFFF97316,
      accentColorHex = 0xFFFED7AA,
      avatarVariant = "parent_female",
      ageGroup = CharacterAgeGroup.ADULT,
      category = CharacterCategory.PARENT_FAMILY,
      personality = "মমতাময়ী ও বুদ্ধিমতী",
      outfitStyle = "কমলা-সোনালি শাড়ি",
      hairColorHex = 0xFF1E1B4B,
    ),
    CharacterProfile(
      id = "lib_doctor_nadia",
      name = "ডা. নাদিয়া",
      roleBn = "ডাক্তার (Doctor)",
      visualDescription = "সাদা অ্যাপ্রোন, স্টেথোস্কোপ ও ভরসাদায়ক হাসি",
      recommendedVoiceId = "Sulafat",
      primaryColorHex = 0xFF38BDF8,
      accentColorHex = 0xFFFFFFFF,
      avatarVariant = "doctor_3d",
      ageGroup = CharacterAgeGroup.YOUNG_ADULT,
      category = CharacterCategory.DOCTOR,
      personality = "দয়ালু ও দক্ষ চিকিৎসক",
      outfitStyle = "সাদা ডাক্তারি কোট ও স্টেথোস্কোপ",
      hairColorHex = 0xFF1E293B,
    ),
    CharacterProfile(
      id = "lib_police_tariq",
      name = "অফিসার তারিক",
      roleBn = "পুলিশ অফিসার (Police)",
      visualDescription = "ইউনিফর্ম পরা সৎ পুলিশ অফিসার, দৃঢ় ভঙ্গি",
      recommendedVoiceId = "Charon",
      primaryColorHex = 0xFF1D4ED8,
      accentColorHex = 0xFFFACC15,
      avatarVariant = "police_3d",
      ageGroup = CharacterAgeGroup.ADULT,
      category = CharacterCategory.POLICE,
      personality = "সৎ, সাহসী ও ন্যায়পরায়ণ",
      outfitStyle = "নীল পুলিশ ইউনিফর্ম ও ব্যাজ",
      hairColorHex = 0xFF0F172A,
    ),
    CharacterProfile(
      id = "lib_farmer_jamal",
      name = "কৃষক জামাল চাচা",
      roleBn = "কৃষক (Farmer)",
      visualDescription = "মাথায় মাথাল, কাঁধে গামছা ও হাস্যোজ্জ্বল পরিশ্রমী কৃষক",
      recommendedVoiceId = "Orus",
      primaryColorHex = 0xFF22C55E,
      accentColorHex = 0xFFFDE047,
      avatarVariant = "farmer_3d",
      ageGroup = CharacterAgeGroup.ELDER,
      category = CharacterCategory.FARMER,
      personality = "সহজ-সরল, পরিশ্রমী ও দেশপ্রেমিক",
      outfitStyle = "সবুজ ফতুয়া ও গ্রামীণ লুঙ্গি/গামছা",
      hairColorHex = 0xFF475569,
    ),
    CharacterProfile(
      id = "lib_business_araf",
      name = "আরাফ চৌধুরী",
      roleBn = "উদ্যোক্তা ও ব্যবসায়ী (Business)",
      visualDescription = "স্মার্ট স্যুট-টাই ও আত্মবিশ্বাসী উপস্থাপনা",
      recommendedVoiceId = "Iapetus",
      primaryColorHex = 0xFF4F46E5,
      accentColorHex = 0xFF38BDF8,
      avatarVariant = "business_3d",
      ageGroup = CharacterAgeGroup.YOUNG_ADULT,
      category = CharacterCategory.BUSINESS,
      personality = "আত্মবিশ্বাসী ও দূরদর্শী",
      outfitStyle = "নেভি ব্লু ব্লেজার ও টাই",
      hairColorHex = 0xFF0F172A,
    ),
    CharacterProfile(
      id = "lib_fantasy_piku",
      name = "পিকু পরী / ফ্যান্টাসি পাখি",
      roleBn = "প্রাণী ও ফ্যান্টাসি চরিত্র (Fantasy)",
      visualDescription = "রঙিন ডানা ও জাদুকরী আলোর ছটা নিয়ে উড়ন্ত বন্ধু",
      recommendedVoiceId = "Achernar",
      primaryColorHex = 0xFFA855F7,
      accentColorHex = 0xFF34D399,
      avatarVariant = "fantasy_3d",
      ageGroup = CharacterAgeGroup.CREATURE,
      category = CharacterCategory.ANIMAL_FANTASY,
      personality = "মজার, জাদুকরী ও গানের সুর ভালোবাসে",
      outfitStyle = "রঙিন ফ্যান্টাসি পোশাক ও ডানা",
      hairColorHex = 0xFF6B21A8,
    ),
  )

  fun loadLibrary(context: Context): List<CharacterProfile> {
    val file = File(context.filesDir, FILE_NAME)
    if (!file.exists()) {
      val seeded = defaultCharacters()
      saveLibrary(context, seeded)
      return seeded
    }
    return runCatching {
      val arr = JSONArray(file.readText(Charsets.UTF_8))
      val list = buildList {
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          add(
            CharacterProfile(
              id = o.optString("id", "char_$i"),
              name = o.optString("name", "চরিত্র $i"),
              roleBn = o.optString("roleBn", "অভিনয় চরিত্র"),
              visualDescription = o.optString("visualDescription", "জীবন্ত ৩ডি চরিত্র"),
              recommendedVoiceId = o.optString("recommendedVoiceId", "Aoede"),
              primaryColorHex = o.optLong("primaryColorHex", 0xFF0EA5E9),
              accentColorHex = o.optLong("accentColorHex", 0xFF22D3EE),
              avatarVariant = o.optString("avatarVariant", "boy_3d"),
              ageGroup = CharacterAgeGroup.entries.firstOrNull { it.name == o.optString("ageGroup") }
                ?: CharacterAgeGroup.YOUNG_ADULT,
              category = CharacterCategory.entries.firstOrNull { it.name == o.optString("category") }
                ?: CharacterCategory.CARTOON_BOY,
              personality = o.optString("personality", "প্রাণবন্ত"),
              outfitStyle = o.optString("outfitStyle", "আধুনিক পোশাক"),
              referenceImagePath = o.optString("referenceImagePath", ""),
              hairColorHex = o.optLong("hairColorHex", 0xFF1E293B),
              isSavedInLibrary = true,
            )
          )
        }
      }
      if (list.isEmpty()) defaultCharacters() else list
    }.getOrElse { defaultCharacters() }
  }

  fun saveLibrary(context: Context, characters: List<CharacterProfile>) {
    runCatching {
      val arr = JSONArray()
      characters.forEach { ch ->
        arr.put(
          JSONObject()
            .put("id", ch.id)
            .put("name", ch.name)
            .put("roleBn", ch.roleBn)
            .put("visualDescription", ch.visualDescription)
            .put("recommendedVoiceId", ch.recommendedVoiceId)
            .put("primaryColorHex", ch.primaryColorHex)
            .put("accentColorHex", ch.accentColorHex)
            .put("avatarVariant", ch.avatarVariant)
            .put("ageGroup", ch.ageGroup.name)
            .put("category", ch.category.name)
            .put("personality", ch.personality)
            .put("outfitStyle", ch.outfitStyle)
            .put("referenceImagePath", ch.referenceImagePath)
            .put("hairColorHex", ch.hairColorHex)
        )
      }
      File(context.filesDir, FILE_NAME).writeText(arr.toString(2), Charsets.UTF_8)
    }
  }

  fun createCustomCharacter(
    name: String,
    roleBn: String,
    category: CharacterCategory,
    ageGroup: CharacterAgeGroup,
    voiceId: String,
    personality: String,
    outfitStyle: String,
    referenceImagePath: String = "",
  ): CharacterProfile {
    val (pColor, aColor, hair) = when (category) {
      CharacterCategory.CARTOON_GIRL -> Triple(0xFFEC4899L, 0xFFFDE047L, 0xFF31102FL)
      CharacterCategory.CARTOON_BOY -> Triple(0xFF0EA5E9L, 0xFF22D3EEL, 0xFF1E293BL)
      CharacterCategory.TEACHER -> Triple(0xFF8B5CF6L, 0xFFC084FCL, 0xFF0F172AL)
      CharacterCategory.DOCTOR -> Triple(0xFF38BDF8L, 0xFFFFFFFFL, 0xFF1E293BL)
      CharacterCategory.POLICE -> Triple(0xFF1D4ED8L, 0xFFFACC15L, 0xFF0F172AL)
      CharacterCategory.FARMER -> Triple(0xFF22C55EL, 0xFFFDE047L, 0xFF475569L)
      CharacterCategory.BUSINESS -> Triple(0xFF4F46E5L, 0xFF38BDF8L, 0xFF0F172AL)
      CharacterCategory.ANIMAL_FANTASY -> Triple(0xFFA855F7L, 0xFF34D399L, 0xFF6B21A8L)
      else -> Triple(0xFFF97316L, 0xFF22D3EEL, 0xFF1E293BL)
    }
    return CharacterProfile(
      id = "lib_custom_${System.currentTimeMillis() % 1_000_000L}",
      name = name.trim().ifBlank { "নতুন চরিত্র" },
      roleBn = roleBn.trim().ifBlank { category.labelBn },
      visualDescription = "$outfitStyle • $personality (${ageGroup.labelBn})",
      recommendedVoiceId = voiceId.ifBlank { "Aoede" },
      primaryColorHex = pColor,
      accentColorHex = aColor,
      avatarVariant = category.name.lowercase(),
      ageGroup = ageGroup,
      category = category,
      personality = personality.ifBlank { "বন্ধুত্বপূর্ণ ও প্রাণবন্ত" },
      outfitStyle = outfitStyle.ifBlank { "রঙিন কার্টুন পোশাক" },
      referenceImagePath = referenceImagePath,
      hairColorHex = hair,
      isSavedInLibrary = true,
    )
  }
}
