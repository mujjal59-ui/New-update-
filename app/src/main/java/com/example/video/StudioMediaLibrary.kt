package com.example.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

enum class AssetTag {
  BOY_3D,
  GIRL_3D,
  KIDS_GROUP,
  DANCE,
  FASHION,
  VILLAGE,
  FOREST,
  NATURE_HILL,
  NIGHT,
  BIRD,
  PANDA,
  MOUSE,
  VIDEO_EDITOR,
  OFFICE_DRAMA,
  SPRITE_CUTOUT,
}

data class StudioAssetEntry(
  val fileName: String,
  val titleBn: String,
  val tags: Set<AssetTag>,
  val isCutoutSprite: Boolean,
  val suggestedVoiceId: String = "Aoede",
)

/**
 * Manages all 60 bundled high-res 3D/Cartoon/Village/Nature/Character assets
 * plus Play-compliant user-selected Gallery Photos, Videos, Screenshots & Audio.
 */
object StudioMediaLibrary {

  private const val ASSET_DIR = "studio_packs"
  private val bitmapCache = LruCache<String, Bitmap>(28)
  @Volatile private var cachedCatalog: List<StudioAssetEntry>? = null

  fun listBundledAssets(context: Context): List<StudioAssetEntry> {
    cachedCatalog?.let { if (it.isNotEmpty()) return it }
    val files = runCatching { context.assets.list(ASSET_DIR)?.toList().orEmpty() }
      .getOrDefault(emptyList())
      .sorted()
    val entries = files.map { buildCatalogEntry(it) }
    cachedCatalog = entries
    return entries
  }

  private fun buildCatalogEntry(fileName: String): StudioAssetEntry {
    val l = fileName.lowercase()
    return when {
      l.contains("asset_01_") -> StudioAssetEntry(fileName, "শিক্ষার্থী ছেলে (চশমা ও নোটবুক)", setOf(AssetTag.BOY_3D, AssetTag.SPRITE_CUTOUT, AssetTag.OFFICE_DRAMA), true, "Iapetus")
      l.contains("asset_02_") -> StudioAssetEntry(fileName, "আনন্দিত ৩ শিশু রংধনু লাফ", setOf(AssetTag.KIDS_GROUP, AssetTag.DANCE, AssetTag.SPRITE_CUTOUT), true, "Aoede")
      l.contains("asset_03_") -> StudioAssetEntry(fileName, "সবুজ পাহাড় ও ঘাস (ব্যাকগ্রাউন্ড)", setOf(AssetTag.NATURE_HILL, AssetTag.VILLAGE), false)
      l.contains("asset_04_") -> StudioAssetEntry(fileName, "বৃষ্টির শহরে হাঁটা ছেলে (সিনেম্যাটিক)", setOf(AssetTag.BOY_3D, AssetTag.OFFICE_DRAMA, AssetTag.NIGHT), false, "Orus")
      l.contains("asset_05_") -> StudioAssetEntry(fileName, "লাল জ্যাকেট ৩ডি ছেলে", setOf(AssetTag.BOY_3D, AssetTag.FASHION, AssetTag.SPRITE_CUTOUT), true, "Iapetus")
      l.contains("asset_06_") -> StudioAssetEntry(fileName, "নোটবুকে পড়াশোনা করা ছেলে", setOf(AssetTag.BOY_3D, AssetTag.SPRITE_CUTOUT), true, "Iapetus")
      l.contains("asset_07_") -> StudioAssetEntry(fileName, "সোনালী সূর্যাস্ত গ্রাম ও গাছ", setOf(AssetTag.VILLAGE, AssetTag.NATURE_HILL), false)
      l.contains("asset_08_") -> StudioAssetEntry(fileName, "জাদুর বই পড়া শিশু", setOf(AssetTag.BOY_3D, AssetTag.OFFICE_DRAMA), false, "Aoede")
      l.contains("asset_09_") -> StudioAssetEntry(fileName, "হুডি পরা অবাক শিশু", setOf(AssetTag.BOY_3D, AssetTag.OFFICE_DRAMA), false, "Aoede")
      l.contains("asset_10_") -> StudioAssetEntry(fileName, "চিন্তামগ্ন ৩ডি ছেলে", setOf(AssetTag.BOY_3D, AssetTag.SPRITE_CUTOUT), true, "Iapetus")
      l.contains("asset_11_") -> StudioAssetEntry(fileName, "গ্রামের কৃষক, গরু ও কুঁড়েঘর", setOf(AssetTag.VILLAGE, AssetTag.OFFICE_DRAMA), false)
      l.contains("asset_12_") -> StudioAssetEntry(fileName, "চশমা পরা স্টাইলিশ ছেলে", setOf(AssetTag.BOY_3D, AssetTag.FASHION, AssetTag.NIGHT), false, "Orus")
      l.contains("asset_13_") -> StudioAssetEntry(fileName, "রাতের আলোকিত গ্রাম", setOf(AssetTag.VILLAGE, AssetTag.NIGHT), false)
      l.contains("asset_14_") -> StudioAssetEntry(fileName, "সবুজ বনের রাস্তা ও গাছপালা", setOf(AssetTag.FOREST, AssetTag.NATURE_HILL), false)
      l.contains("asset_15_") -> StudioAssetEntry(fileName, "ট্যাবলেট হাতে ছেলে", setOf(AssetTag.BOY_3D, AssetTag.VIDEO_EDITOR, AssetTag.SPRITE_CUTOUT), true, "Iapetus")
      l.contains("asset_16_") -> StudioAssetEntry(fileName, "বিজনেস স্যুট ও হাস্যোজ্জ্বল পুরুষ", setOf(AssetTag.FASHION, AssetTag.OFFICE_DRAMA, AssetTag.SPRITE_CUTOUT), true, "Orus")
      l.contains("asset_17_") -> StudioAssetEntry(fileName, "স্কুল ব্যাগ নিয়ে হাঁটা ছেলে", setOf(AssetTag.BOY_3D, AssetTag.SPRITE_CUTOUT), true, "Aoede")
      l.contains("asset_18_") -> StudioAssetEntry(fileName, "ডেস্কে মন দিয়ে লেখা ছেলে", setOf(AssetTag.BOY_3D, AssetTag.VIDEO_EDITOR), false, "Iapetus")
      l.contains("asset_19_") -> StudioAssetEntry(fileName, "খুশিতে লাফানো ৩ডি ছেলে", setOf(AssetTag.BOY_3D, AssetTag.DANCE, AssetTag.SPRITE_CUTOUT), true, "Aoede")
      l.contains("asset_20_") -> StudioAssetEntry(fileName, "স্কেটবোর্ড হাতে কিশোর", setOf(AssetTag.BOY_3D, AssetTag.FASHION), false, "Orus")
      l.contains("asset_21_") -> StudioAssetEntry(fileName, "সবুজ গ্রাম, পুকুর ও ফসলের মাঠ", setOf(AssetTag.VILLAGE, AssetTag.NATURE_HILL), false)
      l.contains("asset_22_") -> StudioAssetEntry(fileName, "তালগাছ ও মাটির ঘরের গ্রাম", setOf(AssetTag.VILLAGE), false)
      l.contains("asset_23_") -> StudioAssetEntry(fileName, "শরতের শান্ত গ্রাম ও খামারবাড়ি", setOf(AssetTag.VILLAGE, AssetTag.NATURE_HILL), false)
      l.contains("asset_24_") -> StudioAssetEntry(fileName, "গ্রামের রাস্তায় হাত নাড়ানো ছেলে", setOf(AssetTag.BOY_3D, AssetTag.VILLAGE, AssetTag.DANCE), false, "Aoede")
      l.contains("asset_25_") -> StudioAssetEntry(fileName, "খেলনা নিয়ে খেলা ছোট্ট শিশু", setOf(AssetTag.BOY_3D), false, "Aoede")
      l.contains("asset_26_") -> StudioAssetEntry(fileName, "বাগানে পিঁপড়া ও মাটি খোঁড়া ছেলে", setOf(AssetTag.BOY_3D, AssetTag.VILLAGE), false, "Aoede")
      l.contains("asset_27_") -> StudioAssetEntry(fileName, "অবাক হয়ে তাকানো স্কুল বালক", setOf(AssetTag.BOY_3D, AssetTag.OFFICE_DRAMA), false, "Aoede")
      l.contains("asset_28_") -> StudioAssetEntry(fileName, "নাচানাচি করা ছোট্ট শিশু", setOf(AssetTag.DANCE, AssetTag.BOY_3D), false, "Achernar")
      l.contains("asset_29_") -> StudioAssetEntry(fileName, "পার্কে ক্যাপ পরা স্টাইলিশ ছেলে", setOf(AssetTag.BOY_3D, AssetTag.FASHION), false, "Iapetus")
      l.contains("asset_30_") -> StudioAssetEntry(fileName, "হল/গ্যালারিতে নাচতে থাকা ৩ডি মেয়ে", setOf(AssetTag.GIRL_3D, AssetTag.DANCE, AssetTag.FASHION), false, "Achernar")
      l.contains("asset_31_") -> StudioAssetEntry(fileName, "জিন্স ও সাদা জ্যাকেট ৩ডি মেয়ে", setOf(AssetTag.GIRL_3D, AssetTag.FASHION), false, "Leda")
      l.contains("asset_32_") -> StudioAssetEntry(fileName, "সানগ্লাস ও ফ্যাশন স্টাইল ছেলে", setOf(AssetTag.BOY_3D, AssetTag.FASHION), false, "Orus")
      l.contains("asset_33_") -> StudioAssetEntry(fileName, "শাড়ি/লেহেঙ্গায় হাঁটতে থাকা ৩ডি মেয়ে", setOf(AssetTag.GIRL_3D, AssetTag.FASHION, AssetTag.VILLAGE, AssetTag.OFFICE_DRAMA), false, "Leda")
      l.contains("asset_34_") -> StudioAssetEntry(fileName, "সালোয়ার-কামিজে হাস্যোজ্জ্বল মেয়ে", setOf(AssetTag.GIRL_3D, AssetTag.DANCE, AssetTag.FASHION, AssetTag.OFFICE_DRAMA), false, "Aoede")
      l.contains("asset_35_") -> StudioAssetEntry(fileName, "চশমা পরা তরুণ (ক্যারেক্টার)", setOf(AssetTag.BOY_3D, AssetTag.OFFICE_DRAMA, AssetTag.SPRITE_CUTOUT), true, "Iapetus")
      l.contains("asset_36_") -> StudioAssetEntry(fileName, "ব্যাকপ্যাক নিয়ে হাঁটা স্কুল ছেলে", setOf(AssetTag.BOY_3D, AssetTag.SPRITE_CUTOUT), true, "Aoede")
      l.contains("asset_37_") -> StudioAssetEntry(fileName, "দাঁড়িয়ে থাকা অফিস ভদ্রলোক", setOf(AssetTag.OFFICE_DRAMA, AssetTag.FASHION, AssetTag.SPRITE_CUTOUT), true, "Orus")
      l.contains("asset_38_") -> StudioAssetEntry(fileName, "মোবাইল হাতে কথা বলা তরুণ", setOf(AssetTag.BOY_3D, AssetTag.OFFICE_DRAMA, AssetTag.SPRITE_CUTOUT), true, "Iapetus")
      l.contains("asset_39_") -> StudioAssetEntry(fileName, "সিনেমা ক্যামেরা ৩ডি", setOf(AssetTag.VIDEO_EDITOR, AssetTag.SPRITE_CUTOUT), true)
      l.contains("asset_40_") -> StudioAssetEntry(fileName, "রঙিন শহরের দৃশ্য ও নাটক প্যানেল", setOf(AssetTag.OFFICE_DRAMA, AssetTag.BOY_3D), false, "Iapetus")
      l.contains("asset_41_") -> StudioAssetEntry(fileName, "গলির পথে জাদুর আলো দেখা ছেলে", setOf(AssetTag.BOY_3D, AssetTag.OFFICE_DRAMA), false, "Aoede")
      l.contains("asset_42_") -> StudioAssetEntry(fileName, "সবুজ জঙ্গলে অ্যাডভেঞ্চার বালক", setOf(AssetTag.FOREST, AssetTag.BOY_3D), false, "Aoede")
      l.contains("asset_43_") -> StudioAssetEntry(fileName, "ভোরের গ্রামে বেড়ার ওপর বসা ছেলে", setOf(AssetTag.VILLAGE, AssetTag.BOY_3D, AssetTag.OFFICE_DRAMA), false, "Iapetus")
      l.contains("asset_44_") -> StudioAssetEntry(fileName, "বনের পাখি ও প্রজাপতি দেখা ছেলে", setOf(AssetTag.FOREST, AssetTag.BIRD, AssetTag.BOY_3D), false, "Aoede")
      l.contains("asset_45_") -> StudioAssetEntry(fileName, "ট্রেন্ডিং নাচের ৩ মেয়ে (কোরাস)", setOf(AssetTag.GIRL_3D, AssetTag.DANCE, AssetTag.FASHION), false, "Achernar")
      l.contains("asset_46_") -> StudioAssetEntry(fileName, "কথা বলা কিউট ইঁদুর (Mouse)", setOf(AssetTag.MOUSE, AssetTag.SPRITE_CUTOUT), true, "Achernar")
      l.contains("asset_47_") -> StudioAssetEntry(fileName, "সবুজ পাহাড়ি টিলা ও প্রকৃতি", setOf(AssetTag.NATURE_HILL), false)
      l.contains("asset_48_") -> StudioAssetEntry(fileName, "বনের আঁকাবাঁকা পথ ও গাছ", setOf(AssetTag.FOREST, AssetTag.NATURE_HILL), false)
      l.contains("asset_49_") -> StudioAssetEntry(fileName, "জঙ্গলের গাছ ও ঘাস", setOf(AssetTag.FOREST, AssetTag.NATURE_HILL), false)
      l.contains("asset_50_") -> StudioAssetEntry(fileName, "কম্পিউটারে ভিডিও এডিটর", setOf(AssetTag.VIDEO_EDITOR, AssetTag.SPRITE_CUTOUT), true, "Iapetus")
      l.contains("asset_51_") -> StudioAssetEntry(fileName, "৪ জন বন্ধু স্কুলের পথে", setOf(AssetTag.KIDS_GROUP, AssetTag.SPRITE_CUTOUT), true, "Aoede")
      l.contains("asset_52_") -> StudioAssetEntry(fileName, "কথা বলা সুন্দর নীল পাখি", setOf(AssetTag.BIRD, AssetTag.SPRITE_CUTOUT), true, "Achernar")
      l.contains("asset_53_") -> StudioAssetEntry(fileName, "বাঁশ খাওয়া কিউট পান্ডা", setOf(AssetTag.PANDA, AssetTag.SPRITE_CUTOUT), true, "Aoede")
      l.contains("asset_54_") -> StudioAssetEntry(fileName, "বুদ্ধিমান চশমা বালক", setOf(AssetTag.BOY_3D, AssetTag.SPRITE_CUTOUT), true, "Iapetus")
      l.contains("asset_55_") -> StudioAssetEntry(fileName, "হাসিখুশি স্কুল বন্ধুদের দল", setOf(AssetTag.KIDS_GROUP, AssetTag.DANCE, AssetTag.SPRITE_CUTOUT), true, "Aoede")
      l.contains("asset_56_") -> StudioAssetEntry(fileName, "গেমিং পিসিতে ভিডিও এডিটর", setOf(AssetTag.VIDEO_EDITOR, AssetTag.SPRITE_CUTOUT), true, "Orus")
      l.contains("asset_57_") -> StudioAssetEntry(fileName, "সিনেমা ক্ল্যাপারবোর্ড আইকন", setOf(AssetTag.VIDEO_EDITOR, AssetTag.SPRITE_CUTOUT), true)
      l.contains("asset_58_") -> StudioAssetEntry(fileName, "কুয়াশাচ্ছন্ন গ্রামের কুঁড়েঘর", setOf(AssetTag.VILLAGE, AssetTag.NIGHT), false)
      l.contains("asset_59_") -> StudioAssetEntry(fileName, "রৌদ্রোজ্জ্বল গ্রাম ও তালগাছ", setOf(AssetTag.VILLAGE, AssetTag.NATURE_HILL), false)
      else -> StudioAssetEntry(fileName, "মুভি ক্যামেরা ট্রাইপড", setOf(AssetTag.VIDEO_EDITOR, AssetTag.SPRITE_CUTOUT), true)
    }
  }

  /**
   * Matches the best foreground asset + landscape background for a scene based on Bengali/English script & prompt.
   */
  fun matchAssetsForScene(
    context: Context,
    prompt: String,
    sceneText: String,
    videoType: VideoType,
    sceneIndex: Int,
    selectedBundledFiles: List<String>,
    userMediaItems: List<UserMediaItem>,
  ): Pair<StudioAssetEntry?, StudioAssetEntry?> {
    val catalog = listBundledAssets(context)
    if (catalog.isEmpty()) return null to null

    // If user explicitly picked specific assets in Gallery tab, rotate through them first
    val manualPool =
      if (selectedBundledFiles.isNotEmpty()) {
        catalog.filter { it.fileName in selectedBundledFiles }
      } else emptyList()

    val q = "$prompt $sceneText ${videoType.labelEn}".lowercase()

    val scoredFg = catalog.map { item ->
      var score = 0
      if (q.anyContains("মেয়ে", "নাচ", "গান", "ফ্যাশন", "শাড়ি", "ড্রেস", "মডেল", "রিলস", "girl", "dance", "fashion", "360", "model")) {
        if (AssetTag.GIRL_3D in item.tags) score += 28
        if (AssetTag.DANCE in item.tags) score += 24
        if (AssetTag.FASHION in item.tags) score += 26
      }
      if (q.anyContains("নাটক", "সিরিয়াল", "পর্ব", "অফিস", "কথা", "ঝগড়া", "ভালোবাসা", "drama", "serial", "episode", "story")) {
        if (AssetTag.OFFICE_DRAMA in item.tags) score += 22
        if (AssetTag.GIRL_3D in item.tags || AssetTag.BOY_3D in item.tags) score += 16
      }
      if (q.anyContains("পাখি", "bird", "টিয়া")) if (AssetTag.BIRD in item.tags) score += 45
      if (q.anyContains("পান্ডা", "ভাল্লুক", "panda")) if (AssetTag.PANDA in item.tags) score += 45
      if (q.anyContains("ইঁদুর", "mouse", "rat")) if (AssetTag.MOUSE in item.tags) score += 45
      if (q.anyContains("ছেলে", "বাচ্চা", "শিশু", "স্কুল", "পড়া", "বই", "boy", "kid", "student", "school")) {
        if (AssetTag.BOY_3D in item.tags) score += 20
        if (AssetTag.KIDS_GROUP in item.tags) score += 22
      }
      if (q.anyContains("এডিট", "ভিডিও", "ক্যামেরা", "কম্পিউটার", "সিনেমা", "youtube", "studio", "motion")) {
        if (AssetTag.VIDEO_EDITOR in item.tags) score += 24
      }
      if (q.anyContains("রাত", "night", "চাঁদ", "ভুত", "অন্ধকার")) if (AssetTag.NIGHT in item.tags) score += 20
      if (q.anyContains("গ্রাম", "বাড়ি", "মাঠ", "কৃষক", "village", "hut", "rural")) {
        if (AssetTag.VILLAGE in item.tags) score += 18
      }
      if (q.anyContains("জঙ্গল", "বন", "গাছ", "প্রকৃতি", "forest", "tree", "jungle", "nature")) {
        if (AssetTag.FOREST in item.tags || AssetTag.NATURE_HILL in item.tags) score += 18
      }
      // Prefer cutout sprites so they animate vividly over backgrounds, or full portraits for scene variety
      if (item.isCutoutSprite) score += 6
      // Slight deterministic tie-breaker per scene index so consecutive scenes pick distinct visuals!
      val varietyBoost = ((item.fileName.hashCode() + sceneIndex * 31) and 0x07)
      item to (score + varietyBoost)
    }.sortedByDescending { it.second }

    val fg =
      if (manualPool.isNotEmpty() && userMediaItems.isEmpty()) {
        manualPool[(sceneIndex - 1).coerceAtLeast(0) % manualPool.size]
      } else {
        val topPool = scoredFg.take(6).map { it.first }
        topPool[(sceneIndex - 1).coerceAtLeast(0) % topPool.size]
      }

    // Pick complementary landscape background (especially when fg is a sprite cutout)
    val backgrounds = catalog.filter {
      AssetTag.VILLAGE in it.tags || AssetTag.FOREST in it.tags || AssetTag.NATURE_HILL in it.tags ||
        AssetTag.NIGHT in it.tags
    }
    val bgScore = backgrounds.map { bg ->
      var sc = 0
      if (q.anyContains("রাত", "night", "তারা") && AssetTag.NIGHT in bg.tags) sc += 30
      if (q.anyContains("জঙ্গল", "বন", "পাখি", "পান্ডা", "forest", "bird", "panda") && AssetTag.FOREST in bg.tags) sc += 25
      if (q.anyContains("গ্রাম", "মেয়ে", "কৃষক", "village", "শাড়ি", "নাটক") && AssetTag.VILLAGE in bg.tags) sc += 25
      sc += ((bg.fileName.hashCode() + sceneIndex * 17) and 0x07)
      bg to sc
    }.sortedByDescending { it.second }

    val bg = bgScore.firstOrNull()?.first
    return fg to bg
  }

  private fun String.anyContains(vararg needles: String): Boolean = needles.any { this.contains(it) }

  fun loadStudioBitmap(
    context: Context?,
    assetFileName: String = "",
    filePath: String = "",
    maxDim: Int = 720,
    stripLightBackgroundIfSprite: Boolean = false,
  ): Bitmap? {
    val key = "${assetFileName}|$filePath|$maxDim|$stripLightBackgroundIfSprite"
    bitmapCache.get(key)?.let { if (!it.isRecycled) return it }

    val raw: Bitmap =
      (try {
        when {
          filePath.isNotBlank() && File(filePath).exists() -> decodeSampledFile(filePath, maxDim)
          context != null && assetFileName.isNotBlank() -> decodeSampledAsset(context, "$ASSET_DIR/$assetFileName", maxDim)
          else -> null
        }
      } catch (_: Throwable) {
        null
      }) ?: return null

    val processed =
      if (stripLightBackgroundIfSprite && !raw.hasAlpha()) {
        removeNearWhiteBackdrop(raw)
      } else if (stripLightBackgroundIfSprite && hasOpaqueLightCorners(raw)) {
        removeNearWhiteBackdrop(raw)
      } else {
        raw
      }
    bitmapCache.put(key, processed)
    return processed
  }

  fun extractVideoFrameAtTime(videoPath: String, timeUs: Long, maxDim: Int = 720): Bitmap? {
    val file = File(videoPath)
    if (!file.exists()) return null
    val bucketMs = (timeUs / 150_000L) * 150L
    val key = "vid|$videoPath|$bucketMs|$maxDim"
    bitmapCache.get(key)?.let { if (!it.isRecycled) return it }
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      val frame = retriever.getFrameAtTime(timeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        ?: retriever.getFrameAtTime(0L)
        ?: return null
      val scaled = scaleBitmapDown(frame, maxDim)
      bitmapCache.put(key, scaled)
      scaled
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  /**
   * Play-compliant zero-permission user media importer via PickVisualMedia / OpenDocument URIs.
   */
  fun importUserUriToProjectCache(context: Context, uri: Uri, forcedKind: UserMediaKind? = null): UserMediaItem? {
    return runCatching {
      val cr = context.contentResolver
      val mime = cr.getType(uri).orEmpty().lowercase()
      var displayName = "media_${System.currentTimeMillis()}"
      cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) displayName = c.getString(idx) ?: displayName
        }
      }
      val kind = forcedKind ?: when {
        mime.startsWith("video/") -> UserMediaKind.VIDEO
        mime.startsWith("audio/") -> UserMediaKind.AUDIO
        displayName.contains("screenshot", ignoreCase = true) -> UserMediaKind.SCREENSHOT
        else -> UserMediaKind.IMAGE
      }
      val dir = File(context.filesDir, "lxr_video_studio/user_media").apply { mkdirs() }
      val ext = displayName.substringAfterLast('.', when (kind) {
        UserMediaKind.VIDEO -> "mp4"
        UserMediaKind.AUDIO -> "wav"
        else -> "jpg"
      }).take(6)
      val outFile = File(dir, "um_${System.currentTimeMillis()}_${displayName.hashCode().and(0xFFFF)}.$ext")
      cr.openInputStream(uri)?.use { inp ->
        FileOutputStream(outFile).use { out -> inp.copyTo(out) }
      } ?: return null

      var durMs = 0L
      if (kind == UserMediaKind.VIDEO || kind == UserMediaKind.AUDIO) {
        val mmr = MediaMetadataRetriever()
        runCatching {
          mmr.setDataSource(outFile.absolutePath)
          durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
        runCatching { mmr.release() }
      }
      UserMediaItem(
        id = "um_${System.currentTimeMillis()}",
        uriString = uri.toString(),
        displayName = displayName,
        kind = kind,
        cachedFilePath = outFile.absolutePath,
        durationMs = durMs,
      )
    }.getOrNull()
  }

  private fun decodeSampledFile(path: String, maxDim: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    opts.inSampleSize = computeInSampleSize(opts.outWidth, opts.outHeight, maxDim)
    opts.inJustDecodeBounds = false
    val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
    return scaleBitmapDown(bmp, maxDim)
  }

  private fun decodeSampledAsset(context: Context, assetPath: String, maxDim: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
    opts.inSampleSize = computeInSampleSize(opts.outWidth, opts.outHeight, maxDim)
    opts.inJustDecodeBounds = false
    val bmp = context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    return scaleBitmapDown(bmp, maxDim)
  }

  private fun computeInSampleSize(w: Int, h: Int, maxDim: Int): Int {
    var sample = 1
    while ((w / sample) > maxDim * 2 || (h / sample) > maxDim * 2) sample *= 2
    return sample.coerceAtLeast(1)
  }

  private fun scaleBitmapDown(src: Bitmap, maxDim: Int): Bitmap {
    val w = src.width
    val h = src.height
    if (w <= maxDim && h <= maxDim) return src
    val ratio = maxDim.toFloat() / maxOf(w, h)
    val nw = (w * ratio).toInt().coerceAtLeast(32)
    val nh = (h * ratio).toInt().coerceAtLeast(32)
    val out = Bitmap.createScaledBitmap(src, nw, nh, true)
    if (out !== src) src.recycle()
    return out
  }

  private fun hasOpaqueLightCorners(bmp: Bitmap): Boolean {
    if (bmp.width < 16 || bmp.height < 16) return false
    val corners = listOf(
      bmp.getPixel(2, 2),
      bmp.getPixel(bmp.width - 3, 2),
      bmp.getPixel(2, bmp.height - 3),
    )
    return corners.all { c ->
      Color.alpha(c) > 220 && Color.red(c) > 228 && Color.green(c) > 228 && Color.blue(c) > 228
    }
  }

  private fun removeNearWhiteBackdrop(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    // Only key out if corners are bright white/checkerboard light
    val c0 = pixels[2 * w + 2]
    val cornerBright = Color.red(c0) > 224 && Color.green(c0) > 224 && Color.blue(c0) > 224
    if (!cornerBright) return src

    for (i in pixels.indices) {
      val px = pixels[i]
      val a = Color.alpha(px)
      if (a < 20) continue
      val r = Color.red(px)
      val g = Color.green(px)
      val b = Color.blue(px)
      val minRgb = minOf(r, g, b)
      val maxRgb = maxOf(r, g, b)
      val sat = maxRgb - minRgb
      // Key out pure white / light gray background while preserving colorful characters
      if (minRgb > 235 && sat < 20) {
        pixels[i] = Color.TRANSPARENT
      } else if (minRgb > 218 && sat < 18) {
        val newAlpha = ((236 - minRgb) * 14).coerceIn(0, 255)
        pixels[i] = Color.argb(newAlpha, r, g, b)
      }
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    if (out !== src) src.recycle()
    return out
  }
}
