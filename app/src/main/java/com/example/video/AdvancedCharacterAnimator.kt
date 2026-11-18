package com.example.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time animated Canvas compositor for LXR--AI Video Studio:
 * - Audio-amplitude driven lip-sync mouth movement
 * - Talking Fruit / Vegetable / Flower / Finger characters (ফুলকপি, পাতাকপি, কলা, আপেল, ফুল, আঙ্গুল)
 * - 60 Bundled 3D/Cartoon/Village/Forest assets + User Gallery Photos, Videos & Screenshots
 * - Walking, Dancing, 360° Fashion Turntable Spin, Drama/Serial & Motion Graphics choreography
 */
object AdvancedCharacterAnimator {

  fun computeMouthOpenEnvelope(
    pcm: ShortArray?,
    sampleRate: Int,
    timeSec: Float,
    hasDialogue: Boolean,
  ): Float {
    if (pcm == null || pcm.isEmpty() || sampleRate <= 0) {
      return if (hasDialogue) {
        (0.25f + 0.65f * abs(sin(timeSec * 11.5)).toFloat()).coerceIn(0f, 1f)
      } else 0f
    }
    val center = (timeSec * sampleRate).toInt().coerceIn(0, pcm.lastIndex)
    val window = (sampleRate * 0.03f).toInt().coerceAtLeast(120)
    val start = (center - window).coerceAtLeast(0)
    val end = (center + window).coerceAtMost(pcm.size)
    if (end <= start) return 0f
    var sumSq = 0.0
    var count = 0
    var i = start
    while (i < end) {
      val s = pcm[i].toDouble()
      sumSq += s * s
      count++
      i += 2
    }
    val rms = if (count > 0) sqrt(sumSq / count) else 0.0
    // Normalize 16-bit speech RMS (~600..9000) cleanly to 0..1 mouth openness
    val normalized = ((rms - 420.0) / 5200.0).toFloat().coerceIn(0f, 1f)
    val articulator = (0.72f + 0.28f * sin(timeSec * 19.0).toFloat())
    return (normalized * articulator).coerceIn(0f, 1f)
  }

  fun drawAnimatedSceneToCanvas(
    context: Context?,
    canvas: Canvas,
    scene: VideoScenePlan,
    width: Int,
    height: Int,
    framePhase: Float,
    globalTimeSec: Float,
    mouthOpen: Float,
    showSubtitles: Boolean,
  ) {
    val w = width.toFloat()
    val h = height.toFloat()
    val scale = minOf(w, h) / 720f
    val motion = resolveEffectiveMotion(scene)
    val cx = w * 0.5f
    val cy = h * 0.48f

    // 1. Background Layer (User Video Clip / User Photo / Bundled Village-Forest Scene / Stylized Sky)
    drawBackgroundLayer(
      context = context,
      canvas = canvas,
      scene = scene,
      w = w,
      h = h,
      scale = scale,
      phase = framePhase,
      globalTimeSec = globalTimeSec,
      motion = motion,
    )

    // 2. Motion Graphics / Runway Glow / Atmospheric Particles
    drawAtmosphereAndMotionOverlay(
      canvas = canvas,
      scene = scene,
      w = w,
      h = h,
      scale = scale,
      phase = framePhase,
      globalTimeSec = globalTimeSec,
      motion = motion,
      mouthOpen = mouthOpen,
    )

    // 3. Foreground Character / 3D Model / User Portrait / Talking Cauliflower-Apple-Banana-Flower-Finger
    val preset = scene.characterPreset
    val isProduceChar = preset in setOf(
      TalkingCharacterPreset.CAULIFLOWER,
      TalkingCharacterPreset.CABBAGE,
      TalkingCharacterPreset.BANANA,
      TalkingCharacterPreset.APPLE,
      TalkingCharacterPreset.FLOWER,
      TalkingCharacterPreset.FINGER,
    )

    if (isProduceChar) {
      drawTalkingProduceCharacter(
        canvas = canvas,
        preset = preset,
        motion = motion,
        cx = cx,
        cy = cy + 16f * scale,
        scale = scale * 1.15f,
        phase = framePhase,
        globalTimeSec = globalTimeSec,
        mouthOpen = mouthOpen,
      )
    } else if (scene.userMediaPath.isNotBlank()) {
      // User Photo/Video already fills canvas in background, add optional foreground 360 or talking card accent
      if (motion == MotionChoreography.FASHION_360_SPIN) {
        val userBmp =
          if (scene.userMediaIsVideo) {
            val durMs = (scene.effectiveDurationSec * 1000f).toLong().coerceAtLeast(2000L)
            val vidUs = ((framePhase * durMs).toLong()) * 1000L
            StudioMediaLibrary.extractVideoFrameAtTime(scene.userMediaPath, vidUs, maxDim = 720)
          } else {
            StudioMediaLibrary.loadStudioBitmap(context, filePath = scene.userMediaPath, maxDim = 720)
          }
        if (userBmp != null) {
          drawChoreographedBitmap(canvas, userBmp, cx, cy, w * 0.68f, h * 0.64f, scale, framePhase, motion, mouthOpen, isSprite = false)
        }
      }
    } else if (scene.matchedAssetFile.isNotBlank() && context != null) {
      val fgBmp = StudioMediaLibrary.loadStudioBitmap(
        context = context,
        assetFileName = scene.matchedAssetFile,
        maxDim = 760,
        stripLightBackgroundIfSprite = true,
      )
      if (fgBmp != null) {
        val isSprite = scene.matchedAssetFile.contains("_01_") || scene.matchedAssetFile.contains("_02_") ||
          scene.matchedAssetFile.contains("_05_") || scene.matchedAssetFile.contains("_06_") ||
          scene.matchedAssetFile.contains("_10_") || scene.matchedAssetFile.contains("_15_") ||
          scene.matchedAssetFile.contains("_16_") || scene.matchedAssetFile.contains("_17_") ||
          scene.matchedAssetFile.contains("_19_") || scene.matchedAssetFile.contains("_35_") ||
          scene.matchedAssetFile.contains("_36_") || scene.matchedAssetFile.contains("_37_") ||
          scene.matchedAssetFile.contains("_38_") || scene.matchedAssetFile.contains("_46_") ||
          scene.matchedAssetFile.contains("_50_") || scene.matchedAssetFile.contains("_51_") ||
          scene.matchedAssetFile.contains("_52_") || scene.matchedAssetFile.contains("_53_") ||
          scene.matchedAssetFile.contains("_54_") || scene.matchedAssetFile.contains("_55_") ||
          scene.matchedAssetFile.contains("_56_")

        drawChoreographedBitmap(
          canvas = canvas,
          bmp = fgBmp,
          cx = cx,
          cy = cy + 10f * scale,
          boxW = if (isSprite) w * 0.64f else w * 0.86f,
          boxH = if (isSprite) h * 0.68f else h * 0.74f,
          scale = scale,
          phase = framePhase,
          motion = motion,
          mouthOpen = mouthOpen,
          isSprite = isSprite,
        )
      } else {
        drawStylizedFallbackCenterpiece(canvas, scene, cx, cy, scale, framePhase, mouthOpen, motion)
      }
    } else {
      drawStylizedFallbackCenterpiece(canvas, scene, cx, cy, scale, framePhase, mouthOpen, motion)
    }

    // 4. Drama / Serial Letterbox Bars + Active Character Name & Lip-Sync Voice Pill
    if (motion == MotionChoreography.DRAMA_CINEMATIC) {
      val barPaint = Paint().apply { color = Color.BLACK }
      canvas.drawRect(0f, 0f, w, h * 0.055f, barPaint)
      canvas.drawRect(0f, h * 0.945f, w, h, barPaint)
    }

    drawSceneHudAndSubtitles(
      canvas = canvas,
      scene = scene,
      w = w,
      h = h,
      scale = scale,
      motion = motion,
      mouthOpen = mouthOpen,
      showSubtitles = showSubtitles,
    )
  }

  private fun resolveEffectiveMotion(scene: VideoScenePlan): MotionChoreography {
    if (scene.motionStyle != MotionChoreography.AUTO) return scene.motionStyle
    val combined = "${scene.title} ${scene.narrationText} ${scene.visualPrompt}".lowercase()
    return when {
      combined.contains("৩৬০") || combined.contains("360") || combined.contains("ফ্যাশন") || combined.contains("fashion") -> MotionChoreography.FASHION_360_SPIN
      combined.contains("নাচ") || combined.contains("dance") || combined.contains("গান") -> MotionChoreography.DANCING
      combined.contains("হাঁট") || combined.contains("walk") || combined.contains("রাস্তা") -> MotionChoreography.WALKING
      combined.contains("নাটক") || combined.contains("সিরিয়াল") || combined.contains("drama") || combined.contains("serial") -> MotionChoreography.DRAMA_CINEMATIC
      combined.contains("মোশন") || combined.contains("গ্রাফিক্স") || combined.contains("motion") -> MotionChoreography.MOTION_GRAPHICS
      else -> MotionChoreography.TALKING_LIPSYNC
    }
  }

  private fun drawBackgroundLayer(
    context: Context?,
    canvas: Canvas,
    scene: VideoScenePlan,
    w: Float,
    h: Float,
    scale: Float,
    phase: Float,
    globalTimeSec: Float,
    motion: MotionChoreography,
  ) {
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val userBmp: Bitmap? =
      if (scene.userMediaPath.isNotBlank()) {
        if (scene.userMediaIsVideo) {
          val vidUs = ((globalTimeSec * 1_000_000f).toLong()) % 30_000_000L
          StudioMediaLibrary.extractVideoFrameAtTime(scene.userMediaPath, vidUs, maxDim = 760)
        } else {
          StudioMediaLibrary.loadStudioBitmap(context, filePath = scene.userMediaPath, maxDim = 760)
        }
      } else null

    if (userBmp != null) {
      drawFullBleedKenBurns(canvas, userBmp, w, h, phase, zoomDelta = 0.14f)
      // Subtle readability tint
      bgPaint.color = Color.argb(48, 4, 8, 22)
      canvas.drawRect(0f, 0f, w, h, bgPaint)
      return
    }

    val bgAsset = scene.backgroundAssetFile.ifBlank { scene.matchedAssetFile }
    val bgBmp =
      if (context != null && bgAsset.isNotBlank() && motion != MotionChoreography.MOTION_GRAPHICS) {
        StudioMediaLibrary.loadStudioBitmap(context, assetFileName = bgAsset, maxDim = 760)
      } else null

    if (bgBmp != null) {
      drawFullBleedKenBurns(canvas, bgBmp, w, h, phase, zoomDelta = 0.12f)
      // Depth vignette so foreground character pops cleanly
      bgPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(55, 6, 10, 26), Color.argb(120, 4, 8, 20), Shader.TileMode.CLAMP)
      canvas.drawRect(0f, 0f, w, h, bgPaint)
      bgPaint.shader = null
    } else {
      val (topCol, botCol) = when (motion) {
        MotionChoreography.FASHION_360_SPIN -> Color.rgb(34, 8, 56) to Color.rgb(8, 12, 32)
        MotionChoreography.DANCING -> Color.rgb(22, 12, 64) to Color.rgb(4, 24, 48)
        MotionChoreography.MOTION_GRAPHICS -> Color.rgb(6, 20, 56) to Color.rgb(2, 6, 24)
        MotionChoreography.DRAMA_CINEMATIC -> Color.rgb(26, 14, 24) to Color.rgb(6, 6, 16)
        else -> when (scene.themeKeyword) {
          "village", "produce_story" -> Color.rgb(16, 68, 46) to Color.rgb(6, 28, 24)
          "rocket_launch" -> Color.rgb(18, 12, 54) to Color.rgb(2, 4, 16)
          "solar_system" -> Color.rgb(6, 16, 48) to Color.rgb(2, 4, 16)
          "planet_earth" -> Color.rgb(4, 28, 56) to Color.rgb(2, 4, 16)
          else -> Color.rgb(10, 18, 48) to Color.rgb(2, 5, 18)
        }
      }
      canvas.drawColor(topCol)
      bgPaint.shader = LinearGradient(0f, 0f, 0f, h, topCol, botCol, Shader.TileMode.CLAMP)
      canvas.drawRect(0f, 0f, w, h, bgPaint)
      bgPaint.shader = null

      // Scenic sun & rolling grass stage for produce / village stories
      if (scene.themeKeyword.contains("village") || scene.themeKeyword.contains("produce")) {
        val hillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 101, 52) }
        val drift = sin(phase * PI * 2.0).toFloat() * 18f * scale
        canvas.drawOval(RectF(-w * 0.2f + drift, h * 0.68f, w * 0.65f + drift, h * 1.15f), hillPaint)
        hillPaint.color = Color.rgb(21, 128, 61)
        canvas.drawOval(RectF(w * 0.35f - drift, h * 0.72f, w * 1.2f - drift, h * 1.15f), hillPaint)
      }
    }
  }

  private fun drawFullBleedKenBurns(
    canvas: Canvas,
    bmp: Bitmap,
    w: Float,
    h: Float,
    phase: Float,
    zoomDelta: Float,
  ) {
    val baseScale = maxOf(w / bmp.width, h / bmp.height)
    val zoom = baseScale * (1.03f + zoomDelta * sin(phase * PI).toFloat())
    val scaledW = bmp.width * zoom
    val scaledH = bmp.height * zoom
    val panX = ((w - scaledW) * 0.5f) + sin(phase * 2.0 * PI).toFloat() * (scaledW - w) * 0.28f
    val panY = ((h - scaledH) * 0.5f) + cos(phase * PI).toFloat() * (scaledH - h) * 0.20f
    val dst = RectF(panX, panY, panX + scaledW, panY + scaledH)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    canvas.drawBitmap(bmp, null, dst, paint)
  }

  private fun drawChoreographedBitmap(
    canvas: Canvas,
    bmp: Bitmap,
    cx: Float,
    cy: Float,
    boxW: Float,
    boxH: Float,
    scale: Float,
    phase: Float,
    motion: MotionChoreography,
    mouthOpen: Float,
    isSprite: Boolean,
  ) {
    val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
    var drawW = minOf(boxW, boxH * aspect)
    var drawH = drawW / aspect
    if (drawH > boxH) {
      drawH = boxH
      drawW = drawH * aspect
    }

    var offsetX = 0f
    var offsetY = 0f
    var rotZ = 0f
    var rotY3D = 0f
    var pulseScale = 1.0f

    when (motion) {
      MotionChoreography.WALKING -> {
        val walkCycle = sin(phase * 6.0 * PI).toFloat()
        offsetX = (phase - 0.5f) * boxW * 0.55f
        offsetY = -abs(walkCycle) * 22f * scale
        rotZ = walkCycle * 4.5f
        pulseScale = 1.0f + 0.03f * walkCycle
      }
      MotionChoreography.DANCING -> {
        val beat = sin(phase * 8.0 * PI).toFloat()
        offsetX = sin(phase * 4.0 * PI).toFloat() * 48f * scale
        offsetY = -abs(beat) * 28f * scale
        rotZ = beat * 8.5f
        pulseScale = 1.0f + 0.06f * abs(beat)
      }
      MotionChoreography.FASHION_360_SPIN -> {
        rotY3D = phase * 360f
        offsetY = sin(phase * 4.0 * PI).toFloat() * 10f * scale
        pulseScale = 1.0f + 0.04f * cos(phase * 2.0 * PI).toFloat()
      }
      MotionChoreography.MOTION_GRAPHICS -> {
        offsetX = sin(phase * 2.0 * PI).toFloat() * 26f * scale
        offsetY = cos(phase * 4.0 * PI).toFloat() * 14f * scale
        pulseScale = 0.94f + 0.12f * phase
      }
      else -> {
        // Lifelike talking breath + expressive speaker energy synced to audio amplitude
        offsetY = sin(phase * 4.0 * PI).toFloat() * 9f * scale - mouthOpen * 8f * scale
        rotZ = sin(phase * 2.0 * PI).toFloat() * (1.8f + mouthOpen * 2.4f)
        pulseScale = 1.0f + mouthOpen * 0.045f
      }
    }

    // Ground shadow under character
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.argb(115, 0, 0, 0)
    }
    canvas.drawOval(
      RectF(
        cx + offsetX - drawW * 0.28f,
        cy + drawH * 0.45f,
        cx + offsetX + drawW * 0.28f,
        cy + drawH * 0.52f,
      ),
      shadowPaint,
    )

    // Runway glow pedestal for 360 Fashion or Dancing
    if (motion == MotionChoreography.FASHION_360_SPIN || motion == MotionChoreography.DANCING) {
      val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * scale
        color = Color.argb(200, 0, 229, 255)
      }
      canvas.drawOval(
        RectF(
          cx + offsetX - drawW * 0.42f,
          cy + drawH * 0.40f,
          cx + offsetX + drawW * 0.42f,
          cy + drawH * 0.53f,
        ),
        ringPaint,
      )
    }

    canvas.save()
    canvas.translate(cx + offsetX, cy + offsetY)
    if (rotY3D != 0f) {
      val cam = Camera()
      val m = Matrix()
      cam.save()
      cam.setLocation(0f, 0f, -14f)
      cam.rotateY(rotY3D)
      cam.getMatrix(m)
      cam.restore()
      canvas.concat(m)
    }
    if (rotZ != 0f) canvas.rotate(rotZ)
    canvas.scale(pulseScale, pulseScale)

    val dst = RectF(-drawW * 0.5f, -drawH * 0.5f, drawW * 0.5f, drawH * 0.5f)
    if (!isSprite) {
      val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * scale
        color = if (mouthOpen > 0.15f) Color.rgb(0, 229, 255) else Color.argb(140, 255, 255, 255)
      }
      val clipPath = Path().apply { addRoundRect(dst, 26f * scale, 26f * scale, Path.Direction.CW) }
      canvas.save()
      canvas.clipPath(clipPath)
      canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
      canvas.restore()
      canvas.drawRoundRect(dst, 26f * scale, 26f * scale, framePaint)
    } else {
      canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }
    canvas.restore()

    if (motion == MotionChoreography.FASHION_360_SPIN) {
      val deg = ((rotY3D % 360f) + 360f).toInt() % 360
      val pillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 15, 23, 42) }
      val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(250, 204, 21)
        textSize = 20f * scale
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
      }
      val pill = RectF(cx - 88f * scale, cy + drawH * 0.43f, cx + 88f * scale, cy + drawH * 0.43f + 34f * scale)
      canvas.drawRoundRect(pill, 16f * scale, 16f * scale, pillP)
      canvas.drawText("360° VIEW • ${deg}°", cx, pill.centerY() + 7f * scale, txtP)
    }
  }

  /**
   * Renders rich expressive animated characters for:
   * - ফুলকপি (CAULIFLOWER), পাতাকপি (CABBAGE), কলা (BANANA), আপেল (APPLE), ফুল (FLOWER), আঙ্গুল (FINGER)
   * with blinking eyes, eyebrows, animated talking mouth (lip-sync), waving arms & dancing/walking feet!
   */
  fun drawTalkingProduceCharacter(
    canvas: Canvas,
    preset: TalkingCharacterPreset,
    motion: MotionChoreography,
    cx: Float,
    cy: Float,
    scale: Float,
    phase: Float,
    globalTimeSec: Float,
    mouthOpen: Float,
  ) {
    val walkSwing = sin(phase * 8.0 * PI).toFloat()
    val bounceY = when (motion) {
      MotionChoreography.WALKING -> -abs(walkSwing) * 18f * scale
      MotionChoreography.DANCING -> -abs(sin(phase * 10.0 * PI).toFloat()) * 26f * scale
      else -> sin(phase * 4.0 * PI).toFloat() * 8f * scale
    }
    val walkX = if (motion == MotionChoreography.WALKING) (phase - 0.5f) * 160f * scale else 0f
    val tiltDeg = when (motion) {
      MotionChoreography.DANCING -> sin(phase * 8.0 * PI).toFloat() * 9f
      MotionChoreography.WALKING -> walkSwing * 4f
      else -> sin(phase * 2.0 * PI).toFloat() * 2.5f
    }

    canvas.save()
    canvas.translate(cx + walkX, cy + bounceY)
    if (motion == MotionChoreography.FASHION_360_SPIN) {
      val cam = Camera()
      val m = Matrix()
      cam.save()
      cam.setLocation(0f, 0f, -14f)
      cam.rotateY(phase * 360f)
      cam.getMatrix(m)
      cam.restore()
      canvas.concat(m)
    }
    canvas.rotate(tiltDeg)

    // Animated Cartoon Legs / Shoes
    val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(30, 41, 59)
      strokeWidth = 12f * scale
      strokeCap = Paint.Cap.ROUND
    }
    val shoePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 68, 68) }
    val leftFootY = 148f * scale - maxOf(0f, walkSwing) * 24f * scale
    val rightFootY = 148f * scale - maxOf(0f, -walkSwing) * 24f * scale
    canvas.drawLine(-32f * scale, 82f * scale, -40f * scale, leftFootY, legPaint)
    canvas.drawLine(32f * scale, 82f * scale, 40f * scale, rightFootY, legPaint)
    canvas.drawOval(RectF(-62f * scale, leftFootY - 8f * scale, -22f * scale, leftFootY + 14f * scale), shoePaint)
    canvas.drawOval(RectF(22f * scale, rightFootY - 8f * scale, 62f * scale, rightFootY + 14f * scale), shoePaint)

    // Animated Waving Arms
    val armWaveL = sin(phase * 8.0 * PI).toFloat() * 34f * scale
    val armWaveR = cos(phase * 8.0 * PI).toFloat() * 34f * scale
    val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(254, 240, 138) }
    canvas.drawLine(-72f * scale, 8f * scale, -124f * scale, -18f * scale + armWaveL, legPaint)
    canvas.drawCircle(-124f * scale, -18f * scale + armWaveL, 14f * scale, handPaint)
    canvas.drawLine(72f * scale, 8f * scale, 124f * scale, -22f * scale + armWaveR, legPaint)
    canvas.drawCircle(124f * scale, -22f * scale + armWaveR, 14f * scale, handPaint)

    // Draw specific body silhouette & theme colors
    var faceOffsetY = -10f * scale
    when (preset) {
      TalkingCharacterPreset.CAULIFLOWER -> {
        // Outer crisp green cabbage/cauliflower leaves collar
        val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 197, 94) }
        val darkLeaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 128, 61) }
        for (a in listOf(-140.0, -105.0, -75.0, -40.0, 40.0, 75.0, 105.0, 140.0)) {
          val rad = Math.toRadians(a)
          val lx = (sin(rad) * 95f).toFloat() * scale
          val ly = (cos(rad) * 72f + 35f).toFloat() * scale
          canvas.drawCircle(lx, ly, 36f * scale, if (a < 0) leafPaint else darkLeaf)
        }
        // Creamy scalloped cauliflower floret crown
        val floretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader = RadialGradient(-18f * scale, -40f * scale, 125f * scale, Color.rgb(255, 253, 240), Color.rgb(226, 232, 200), Shader.TileMode.CLAMP)
        }
        val bumps = listOf(0f to -76f, -52f to -60f, 52f to -60f, -72f to -14f, 72f to -14f, -45f to 28f, 45f to 28f, 0f to 36f)
        bumps.forEach { (bx, by) -> canvas.drawCircle(bx * scale, by * scale, 48f * scale, floretPaint) }
        canvas.drawCircle(0f, -15f * scale, 74f * scale, floretPaint)
      }
      TalkingCharacterPreset.CABBAGE -> {
        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 163, 74) }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader = RadialGradient(-22f * scale, -35f * scale, 125f * scale, Color.rgb(134, 239, 172), Color.rgb(21, 128, 61), Shader.TileMode.CLAMP)
        }
        val veinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          style = Paint.Style.STROKE; strokeWidth = 5f * scale; color = Color.rgb(187, 247, 208)
        }
        canvas.drawCircle(-28f * scale, 8f * scale, 84f * scale, outer)
        canvas.drawCircle(28f * scale, 8f * scale, 84f * scale, outer)
        canvas.drawCircle(0f, -8f * scale, 88f * scale, inner)
        canvas.drawArc(RectF(-76f * scale, -80f * scale, 76f * scale, 72f * scale), 195f, 140f, false, veinPaint)
        canvas.drawArc(RectF(-68f * scale, -66f * scale, 68f * scale, 68f * scale), 25f, 130f, false, veinPaint)
      }
      TalkingCharacterPreset.BANANA -> {
        faceOffsetY = -12f * scale
        val bananaBody = Path().apply {
          moveTo(-25f * scale, -122f * scale)
          cubicTo(55f * scale, -96f * scale, 88f * scale, 48f * scale, 10f * scale, 118f * scale)
          cubicTo(-32f * scale, 128f * scale, -72f * scale, 95f * scale, -54f * scale, 35f * scale)
          cubicTo(-35f * scale, -25f * scale, -45f * scale, -88f * scale, -25f * scale, -122f * scale)
          close()
        }
        val bPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader = LinearGradient(-55f * scale, -110f * scale, 65f * scale, 110f * scale, Color.rgb(254, 240, 138), Color.rgb(234, 179, 8), Shader.TileMode.CLAMP)
        }
        canvas.drawPath(bananaBody, bPaint)
        val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 53, 15) }
        canvas.drawRoundRect(RectF(-34f * scale, -138f * scale, -10f * scale, -114f * scale), 5f * scale, 5f * scale, stemPaint)
      }
      TalkingCharacterPreset.APPLE -> {
        val applePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader = RadialGradient(-26f * scale, -45f * scale, 130f * scale, Color.rgb(252, 165, 165), Color.rgb(185, 28, 28), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(-32f * scale, -6f * scale, 68f * scale, applePaint)
        canvas.drawCircle(32f * scale, -6f * scale, 68f * scale, applePaint)
        canvas.drawOval(RectF(-82f * scale, -68f * scale, 82f * scale, 90f * scale), applePaint)
        val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 53, 15); strokeWidth = 9f * scale; strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(0f, -68f * scale, 8f * scale, -114f * scale, stem)
        val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 197, 94) }
        canvas.drawOval(RectF(6f * scale, -116f * scale, 56f * scale, -84f * scale), leaf)
      }
      TalkingCharacterPreset.FLOWER -> {
        val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val spinOffset = sin(phase * 2.0 * PI).toFloat() * 14f
        for (k in 0 until 8) {
          val deg = k * 45f + spinOffset
          val rad = Math.toRadians(deg.toDouble())
          val px = (cos(rad) * 76f).toFloat() * scale
          val py = (sin(rad) * 76f).toFloat() * scale
          petalPaint.color = if (k % 2 == 0) Color.rgb(244, 63, 94) else Color.rgb(251, 113, 133)
          canvas.drawCircle(px, py, 44f * scale, petalPaint)
        }
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader = RadialGradient(-15f * scale, -20f * scale, 85f * scale, Color.rgb(254, 240, 138), Color.rgb(245, 158, 11), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(0f, 0f, 68f * scale, centerPaint)
      }
      TalkingCharacterPreset.FINGER -> {
        // Anthropomorphic Cute Cartoon Finger Character
        val palmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader = LinearGradient(-55f * scale, -120f * scale, 55f * scale, 100f * scale, Color.rgb(255, 224, 189), Color.rgb(244, 180, 135), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(-56f * scale, -135f * scale, 56f * scale, 95f * scale), 56f * scale, 56f * scale, palmPaint)
        // Glossy fingernail at top
        val nailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 241, 242) }
        canvas.drawRoundRect(RectF(-34f * scale, -122f * scale, 34f * scale, -78f * scale), 26f * scale, 26f * scale, nailPaint)
        faceOffsetY = -14f * scale
      }
      else -> Unit
    }

    // Expressive Blinking Eyes + Rosy Cheeks + Real-Time Lip-Sync Mouth
    val isBlinking = ((globalTimeSec * 0.8f) % 3.2f) > 3.04f
    val eyeW = 14f * scale
    val eyeH = if (isBlinking) 2.5f * scale else 16f * scale
    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42) }
    val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 251, 113, 133) }

    canvas.drawOval(RectF(-44f * scale, faceOffsetY - 6f * scale, -22f * scale, faceOffsetY + 10f * scale), blushPaint)
    canvas.drawOval(RectF(22f * scale, faceOffsetY - 6f * scale, 44f * scale, faceOffsetY + 10f * scale), blushPaint)

    if (!isBlinking) {
      canvas.drawOval(RectF(-28f * scale - eyeW, faceOffsetY - 34f * scale, -28f * scale + eyeW, faceOffsetY - 34f * scale + eyeH * 1.4f), whitePaint)
      canvas.drawOval(RectF(28f * scale - eyeW, faceOffsetY - 34f * scale, 28f * scale + eyeW, faceOffsetY - 34f * scale + eyeH * 1.4f), whitePaint)
      canvas.drawCircle(-26f * scale, faceOffsetY - 22f * scale, 8f * scale, pupilPaint)
      canvas.drawCircle(26f * scale, faceOffsetY - 22f * scale, 8f * scale, pupilPaint)
      canvas.drawCircle(-29f * scale, faceOffsetY - 25f * scale, 3f * scale, whitePaint)
      canvas.drawCircle(23f * scale, faceOffsetY - 25f * scale, 3f * scale, whitePaint)
    } else {
      val blinkLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * scale; color = Color.rgb(15, 23, 42) }
      canvas.drawLine(-40f * scale, faceOffsetY - 22f * scale, -16f * scale, faceOffsetY - 22f * scale, blinkLine)
      canvas.drawLine(16f * scale, faceOffsetY - 22f * scale, 40f * scale, faceOffsetY - 22f * scale, blinkLine)
    }

    // Animated Lip-Synced Mouth (closed smile -> wide talking mouth w/ tongue!)
    val openHeight = (6f + mouthOpen * 28f) * scale
    val mouthBox = RectF(-24f * scale, faceOffsetY + 6f * scale, 24f * scale, faceOffsetY + 6f * scale + openHeight)
    val lipOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(127, 29, 29) }
    val tonguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 63, 94) }
    val lipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * scale; color = Color.rgb(69, 10, 10) }

    if (mouthOpen < 0.12f) {
      canvas.drawArc(RectF(-22f * scale, faceOffsetY + 2f * scale, 22f * scale, faceOffsetY + 22f * scale), 15f, 150f, false, lipStroke)
    } else {
      canvas.drawOval(mouthBox, lipOuter)
      // Upper teeth
      canvas.drawRoundRect(RectF(-16f * scale, mouthBox.top + 2f * scale, 16f * scale, mouthBox.top + 8f * scale), 4f * scale, 4f * scale, whitePaint)
      // Animated tongue
      if (mouthOpen > 0.25f) {
        canvas.drawOval(RectF(-12f * scale, mouthBox.centerY(), 12f * scale, mouthBox.bottom - 2f * scale), tonguePaint)
      }
      canvas.drawOval(mouthBox, lipStroke)
    }

    canvas.restore()
  }

  private fun drawAtmosphereAndMotionOverlay(
    canvas: Canvas,
    scene: VideoScenePlan,
    w: Float,
    h: Float,
    scale: Float,
    phase: Float,
    globalTimeSec: Float,
    motion: MotionChoreography,
    mouthOpen: Float,
  ) {
    val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Floating golden fireflies / sparkles / music notes
    val isDance = motion == MotionChoreography.DANCING
    val isMotionGfx = motion == MotionChoreography.MOTION_GRAPHICS

    if (isMotionGfx) {
      val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * scale
      }
      for (i in 0 until 3) {
        val rad = ((0.18f + 0.12f * i + phase * 0.25f) % 0.6f) * minOf(w, h)
        ringP.color = Color.argb(120 - i * 30, 0, 229, 255)
        canvas.drawCircle(w * 0.5f, h * 0.48f, rad, ringP)
      }
      // Animated Neon Equalizer Bars driven by live voice amplitude
      val barP = Paint(Paint.ANTI_ALIAS_FLAG)
      for (b in 0 until 12) {
        val bx = w * 0.14f + b * (w * 0.065f)
        val barH = (18f + (0.25f + mouthOpen) * 70f * abs(sin(globalTimeSec * 8.0 + b * 0.7)).toFloat()) * scale
        barP.color = if (b % 2 == 0) Color.argb(180, 0, 229, 255) else Color.argb(180, 232, 121, 249)
        canvas.drawRoundRect(RectF(bx, h * 0.75f - barH, bx + 12f * scale, h * 0.75f), 6f * scale, 6f * scale, barP)
      }
    }

    for (i in 0 until 26) {
      val px = (((i * 211 + 59) % 1000) / 1000f) * w
      val driftY = (((i * 317 + 73) % 1000) / 1000f) * h * 0.85f - (phase * 40f * scale)
      val py = ((driftY % h) + h) % h
      val alpha = (95 + 145 * abs(sin(phase * 2.0 * PI + i)).toFloat()).toInt().coerceIn(40, 245)
      if (isDance && i % 4 == 0) {
        particlePaint.color = Color.argb(alpha, 250, 204, 21)
        particlePaint.textSize = 26f * scale
        canvas.drawText(if (i % 8 == 0) "♪" else "♫", px, py, particlePaint)
      } else {
        particlePaint.color = if (i % 2 == 0) Color.argb(alpha, 125, 211, 252) else Color.argb(alpha, 253, 224, 71)
        canvas.drawCircle(px, py, (2.2f + (i % 3) * 1.4f) * scale, particlePaint)
      }
    }
  }

  private fun drawStylizedFallbackCenterpiece(
    canvas: Canvas,
    scene: VideoScenePlan,
    cx: Float,
    cy: Float,
    scale: Float,
    phase: Float,
    mouthOpen: Float,
    motion: MotionChoreography,
  ) {
    // Rich astronaut / kid / storyteller character with animated mouth!
    val bobY = sin(phase * 4.0 * PI).toFloat() * 14f * scale
    val walkX = if (motion == MotionChoreography.WALKING) (phase - 0.5f) * 140f * scale else 0f
    val x = cx + walkX
    val y = cy + bobY

    val suitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 232, 240) }
    canvas.drawRoundRect(RectF(x - 56f * scale, y + 32f * scale, x + 56f * scale, y + 148f * scale), 28f * scale, 28f * scale, suitPaint)
    val helmetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255) }
    canvas.drawCircle(x, y - 30f * scale, 64f * scale, helmetPaint)
    val visorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42) }
    canvas.drawCircle(x, y - 28f * scale, 48f * scale, visorPaint)
    val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 189, 248) }
    canvas.drawCircle(x - 16f * scale, y - 36f * scale, 7f * scale, eyePaint)
    canvas.drawCircle(x + 16f * scale, y - 36f * scale, 7f * scale, eyePaint)

    val mouthH = (5f + mouthOpen * 18f) * scale
    val smilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 204, 21) }
    canvas.drawOval(RectF(x - 16f * scale, y - 16f * scale, x + 16f * scale, y - 16f * scale + mouthH), smilePaint)
  }

  private fun drawSceneHudAndSubtitles(
    canvas: Canvas,
    scene: VideoScenePlan,
    w: Float,
    h: Float,
    scale: Float,
    motion: MotionChoreography,
    mouthOpen: Float,
    showSubtitles: Boolean,
  ) {
    val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(175, 10, 15, 36) }
    val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 2.2f * scale
      color = Color.rgb(0, 229, 255)
    }

    val topRect = RectF(w * 0.06f, h * 0.06f, w * 0.94f, h * 0.145f)
    canvas.drawRoundRect(topRect, 20f * scale, 20f * scale, badgePaint)
    canvas.drawRoundRect(topRect, 20f * scale, 20f * scale, badgeStroke)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0, 229, 255)
      textSize = 23f * scale
      typeface = Typeface.DEFAULT_BOLD
      textAlign = Paint.Align.CENTER
    }
    canvas.drawText(
      "Scene ${scene.index}: ${scene.overlayHeadline.take(32)}",
      w * 0.5f,
      topRect.centerY() + 8f * scale,
      titlePaint,
    )

    // Speaking Character & Dialogue Bubble with live voice wave indicator
    if (scene.characterName.isNotBlank() && scene.dialogueLine.isNotBlank()) {
      val dlgRect = RectF(w * 0.08f, h * 0.69f, w * 0.92f, h * 0.775f)
      badgePaint.color = Color.argb(190, 22, 16, 48)
      badgeStroke.color = if (mouthOpen > 0.18f) Color.rgb(250, 204, 21) else Color.argb(150, 232, 121, 249)
      canvas.drawRoundRect(dlgRect, 16f * scale, 16f * scale, badgePaint)
      canvas.drawRoundRect(dlgRect, 16f * scale, 16f * scale, badgeStroke)

      val dlgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(253, 224, 71)
        textSize = 18.5f * scale
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
      }
      val waveBars = if (mouthOpen > 0.2f) "🔊 " else ""
      canvas.drawText(
        "$waveBars${scene.dialogueLine.take(54)}",
        w * 0.5f,
        dlgRect.centerY() + 6f * scale,
        dlgPaint,
      )
    }

    if (showSubtitles && scene.narrationText.isNotBlank()) {
      val subRect = RectF(w * 0.05f, h * 0.80f, w * 0.95f, h * 0.925f)
      badgePaint.color = Color.argb(210, 4, 9, 22)
      badgeStroke.color = Color.argb(185, 0, 229, 255)
      canvas.drawRoundRect(subRect, 18f * scale, 18f * scale, badgePaint)
      canvas.drawRoundRect(subRect, 18f * scale, 18f * scale, badgeStroke)

      val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f * scale
        textAlign = Paint.Align.CENTER
      }
      canvas.drawText(scene.narrationText.take(66), w * 0.5f, subRect.centerY() + 7f * scale, subPaint)
    }
  }
}
