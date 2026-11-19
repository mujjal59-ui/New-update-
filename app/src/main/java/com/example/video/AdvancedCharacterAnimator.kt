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
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time 24fps Animated Canvas Compositor for LXR--AI Video Studio:
 * - Real Moving Video Clips (realFootagePath / User Video via MediaMetadataRetriever timestamps)
 * - Articulated Multi-Joint Characters (walking, running, hand gestures, blinking, gaze, 2-actor interaction)
 * - Audio-amplitude driven lip-sync across Bengali & English speech
 * - Talking Produce / Fruit / Flower / Finger characters (ফুলকপি, পাতাকপি, কলা, আপেল, ফুল, আঙ্গুল)
 * - YouTube-Grade Motion Graphics Layer (Kinetic Typography, 3D Charts, Infographics, Maps, Lower-Thirds)
 * - Parallax Sky/Clouds/Birds/Particles & Smooth Scene Transitions
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
    val mode = scene.productionMode
    val cx = w * 0.5f
    val cy = h * 0.48f

    // Camera shot framing transform (Wide, Close-Up, Tracking, Low-Angle, Two-Shot, Orbit 360)
    val shotZoom = scene.cameraShot.zoomFactor
    val trackingPanX = when (scene.cameraShot) {
      CameraShotType.TRACKING_SHOT -> -((framePhase - 0.5f) * w * 0.10f)
      CameraShotType.ORBIT_360 -> sin(framePhase * 2.0 * PI).toFloat() * w * 0.06f
      else -> 0f
    }
    val lowAngleShiftY = if (scene.cameraShot == CameraShotType.LOW_ANGLE) -h * 0.04f else 0f
    val enterAlpha = if (framePhase < 0.08f) (framePhase / 0.08f).coerceIn(0f, 1f) else 1f
    val slideShiftX = when (scene.transition) {
      TransitionEffect.SLIDE -> if (framePhase < 0.12f) (1f - framePhase / 0.12f) * w * 0.25f else 0f
      TransitionEffect.WIPE -> if (framePhase < 0.10f) -(1f - framePhase / 0.10f) * w * 0.2f else 0f
      else -> 0f
    }

    canvas.save()
    if (slideShiftX != 0f) canvas.translate(slideShiftX, 0f)

    // 1. Dynamic Environment / Real Footage Video Clip / Scene Architecture / Weather
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

    // Moving environmental props, classroom board, trees, clouds, stars, rain, spotlights & extras
    drawDynamicEnvironmentObjects(canvas, scene, w, h, scale, framePhase, globalTimeSec)

    // 2. Motion Graphics Layer (dominant in MOTION_GRAPHICS mode, integrated overlay in HYBRID_ALL_THREE)
    if (mode == VideoProductionMode.MOTION_GRAPHICS ||
      mode == VideoProductionMode.HYBRID_ALL_THREE ||
      motion == MotionChoreography.MOTION_GRAPHICS ||
      scene.motionGraphicKind != MotionGraphicKind.NONE
    ) {
      MotionGraphicsRenderer.drawMotionGraphicsLayer(
        canvas = canvas,
        scene = scene,
        w = w,
        h = h,
        scale = scale,
        phase = framePhase,
        globalTimeSec = globalTimeSec,
        mouthOpen = mouthOpen,
      )
    }

    // 3. Foreground Living Articulated Characters (NEVER a static photo slideshow!)
    val drawCharacters = mode != VideoProductionMode.MOTION_GRAPHICS ||
      scene.characterPreset in producePresetSet() ||
      scene.motionStyle == MotionChoreography.TALKING_LIPSYNC ||
      scene.castNames.isNotEmpty()

    if (drawCharacters) {
      val preset = scene.characterPreset
      val isProduceChar = preset in producePresetSet()
      val actorCenterX =
        if (mode == VideoProductionMode.HYBRID_ALL_THREE && scene.motionGraphicKind in setOf(MotionGraphicKind.INFOGRAPHIC_STEPS, MotionGraphicKind.ANIMATED_CHART)) {
          w * 0.26f
        } else cx + trackingPanX

      canvas.save()
      canvas.scale(shotZoom, shotZoom, actorCenterX, cy + lowAngleShiftY)

      when {
        isProduceChar -> {
          drawTalkingProduceCharacter(
            canvas = canvas,
            preset = preset,
            motion = motion,
            cx = actorCenterX,
            cy = cy + 16f * scale + lowAngleShiftY,
            scale = scale * 1.12f,
            phase = framePhase,
            globalTimeSec = globalTimeSec,
            mouthOpen = mouthOpen,
          )
        }
        scene.userMediaPath.isNotBlank() && motion == MotionChoreography.FASHION_360_SPIN -> {
          val userBmp =
            if (scene.userMediaIsVideo) {
              val durMs = (scene.effectiveDurationSec * 1000f).toLong().coerceAtLeast(2000L)
              val vidUs = ((framePhase * durMs).toLong()) * 1000L
              StudioMediaLibrary.extractVideoFrameAtTime(scene.userMediaPath, vidUs, maxDim = 720)
            } else {
              StudioMediaLibrary.loadStudioBitmap(context, filePath = scene.userMediaPath, maxDim = 720)
            }
          if (userBmp != null) {
            drawChoreographedBitmap(canvas, userBmp, actorCenterX, cy, w * 0.68f, h * 0.64f, scale, framePhase, motion, mouthOpen, isSprite = false)
          }
        }
        else -> {
          // Living multi-joint actors (with preserved Reference Image heads + blinking + PCM lip-sync!)
          ArticulatedCharacterRenderer.drawArticulatedSceneCast(
            canvas = canvas,
            scene = scene,
            cx = actorCenterX,
            cy = cy + 8f * scale + lowAngleShiftY,
            scale = scale * (if (mode == VideoProductionMode.HYBRID_ALL_THREE) 0.92f else 1.05f),
            phase = framePhase,
            globalTimeSec = globalTimeSec,
            mouthOpen = mouthOpen,
            motion = motion,
            context = context,
          )

          if (scene.matchedAssetFile.isNotBlank() && context != null && motion == MotionChoreography.FASHION_360_SPIN) {
            val fgBmp = StudioMediaLibrary.loadStudioBitmap(context, assetFileName = scene.matchedAssetFile, maxDim = 420, stripLightBackgroundIfSprite = true)
            if (fgBmp != null) {
              drawChoreographedBitmap(canvas, fgBmp, w * 0.80f, h * 0.45f, w * 0.28f, h * 0.42f, scale * 0.7f, framePhase, motion, mouthOpen, isSprite = true)
            }
          }
        }
      }
      canvas.restore()
    }

    // 4. Drama / Serial Letterbox Bars + Atmospheric Particles + Dialogue & Subtitles
    drawAtmosphereAndMotionOverlay(canvas, scene, w, h, scale, framePhase, globalTimeSec, motion, mouthOpen)

    if (motion == MotionChoreography.DRAMA_CINEMATIC || scene.cameraShot == CameraShotType.LOW_ANGLE) {
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

    // Smooth Fade transition veil at very start/end of scene clip
    if (scene.transition == TransitionEffect.FADE && enterAlpha < 0.99f) {
      val fadePaint = Paint().apply {
        color = Color.argb(((1f - enterAlpha) * 210f).toInt().coerceIn(0, 220), 4, 8, 22)
      }
      canvas.drawRect(0f, 0f, w, h, fadePaint)
    }

    canvas.restore()
  }

  private fun producePresetSet() = setOf(
    TalkingCharacterPreset.CAULIFLOWER,
    TalkingCharacterPreset.CABBAGE,
    TalkingCharacterPreset.BANANA,
    TalkingCharacterPreset.APPLE,
    TalkingCharacterPreset.FLOWER,
    TalkingCharacterPreset.FINGER,
  )

  private fun resolveEffectiveMotion(scene: VideoScenePlan): MotionChoreography {
    if (scene.motionStyle != MotionChoreography.AUTO) return scene.motionStyle
    val combined = "${scene.title} ${scene.narrationText} ${scene.visualPrompt}".lowercase()
    return when {
      combined.contains("৩৬০") || combined.contains("360") || combined.contains("ফ্যাশন") || combined.contains("fashion") -> MotionChoreography.FASHION_360_SPIN
      combined.contains("দলীয় নাচ") || combined.contains("group dance") -> MotionChoreography.GROUP_DANCE
      combined.contains("গান") || combined.contains("sing") -> MotionChoreography.SINGING
      combined.contains("ক্লাস") || combined.contains("শিক্ষক") || combined.contains("school") || combined.contains("class") -> MotionChoreography.CLASSROOM_TEACHING
      combined.contains("লাফ") || combined.contains("jump") -> MotionChoreography.JUMPING
      combined.contains("বস") || combined.contains("sit") -> MotionChoreography.SITTING
      combined.contains("আলিঙ্গন") || combined.contains("hug") -> MotionChoreography.HUG_INTERACTION
      combined.contains("হাত মেলা") || combined.contains("handshake") -> MotionChoreography.HANDSHAKE
      combined.contains("দৌড়") || combined.contains("run") || combined.contains("রকেট") -> MotionChoreography.RUNNING
      combined.contains("হাঁট") || combined.contains("walk") || combined.contains("রাস্তা") -> MotionChoreography.WALKING
      combined.contains("নাচ") || combined.contains("dance") -> MotionChoreography.DANCING
      combined.contains("নাটক") || combined.contains("সিরিয়াল") || combined.contains("drama") || combined.contains("serial") -> MotionChoreography.DRAMA_CINEMATIC
      combined.contains("মোশন") || combined.contains("গ্রাফিক্স") || combined.contains("চার্ট") || combined.contains("motion") -> MotionChoreography.MOTION_GRAPHICS
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

    // Priority A: Real moving video footage (from FreeStockMediaFetcher or User Video clip)
    val videoClipPath = when {
      scene.realFootagePath.isNotBlank() -> scene.realFootagePath
      scene.userMediaIsVideo && scene.userMediaPath.isNotBlank() -> scene.userMediaPath
      else -> ""
    }
    if (videoClipPath.isNotBlank()) {
      val vidUs = ((globalTimeSec * 1_000_000f).toLong()) % 30_000_000L
      val vidFrame = StudioMediaLibrary.extractVideoFrameAtTime(videoClipPath, vidUs, maxDim = 760)
      if (vidFrame != null) {
        drawFullBleedKenBurns(canvas, vidFrame, w, h, phase, zoomDelta = 0.08f)
        bgPaint.color = Color.argb(46, 4, 8, 22)
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        return
      }
    }

    // Priority B: User Reference photo only as subtle atmospheric backdrop when NOT showing produce preset,
    // because the main character rig uses Reference Image for living face/lip-sync animation!
    val bgAsset = scene.backgroundAssetFile.ifBlank { scene.matchedAssetFile }
    val bgBmp =
      if (context != null && bgAsset.isNotBlank() && scene.productionMode != VideoProductionMode.MOTION_GRAPHICS) {
        StudioMediaLibrary.loadStudioBitmap(context, assetFileName = bgAsset, maxDim = 760)
      } else null

    if (bgBmp != null) {
      drawFullBleedKenBurns(canvas, bgBmp, w, h, phase, zoomDelta = 0.12f)
      bgPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(60, 6, 10, 26), Color.argb(130, 4, 8, 20), Shader.TileMode.CLAMP)
      canvas.drawRect(0f, 0f, w, h, bgPaint)
      bgPaint.shader = null
    } else {
      val (topCol, botCol) = when (scene.weatherMood) {
        TimeWeatherMood.SUNSET_GOLDEN -> Color.rgb(124, 45, 18) to Color.rgb(30, 14, 48)
        TimeWeatherMood.NIGHT_MOONLIGHT -> Color.rgb(5, 10, 32) to Color.rgb(2, 4, 14)
        TimeWeatherMood.RAINY_DRIZZLE -> Color.rgb(22, 34, 56) to Color.rgb(10, 18, 34)
        TimeWeatherMood.NEON_FESTIVE -> Color.rgb(38, 12, 72) to Color.rgb(6, 20, 48)
        TimeWeatherMood.DAYLIGHT -> when (scene.environment) {
          EnvironmentSetting.CLASSROOM, EnvironmentSetting.HOME_LIVING -> Color.rgb(30, 58, 92) to Color.rgb(15, 23, 42)
          EnvironmentSetting.VILLAGE_PATH, EnvironmentSetting.PARK_GARDEN, EnvironmentSetting.SCHOOL_YARD -> Color.rgb(14, 116, 144) to Color.rgb(6, 78, 59)
          EnvironmentSetting.CONCERT_STAGE -> Color.rgb(49, 16, 86) to Color.rgb(15, 10, 38)
          EnvironmentSetting.FANTASY_FOREST -> Color.rgb(19, 78, 74) to Color.rgb(6, 28, 36)
          EnvironmentSetting.SPACE_COSMOS -> Color.rgb(6, 16, 48) to Color.rgb(2, 4, 16)
          else -> when (scene.themeKeyword) {
            "village", "produce_story" -> Color.rgb(14, 82, 58) to Color.rgb(5, 28, 22)
            "rocket_launch", "solar_system" -> Color.rgb(12, 18, 54) to Color.rgb(2, 4, 16)
            else -> Color.rgb(14, 44, 88) to Color.rgb(4, 12, 32)
          }
        }
      }
      bgPaint.shader = LinearGradient(0f, 0f, 0f, h, topCol, botCol, Shader.TileMode.CLAMP)
      canvas.drawRect(0f, 0f, w, h, bgPaint)
      bgPaint.shader = null

      val hillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(175, 22, 101, 52) }
      val drift = sin(phase * PI * 2.0).toFloat() * 22f * scale
      canvas.drawOval(RectF(-w * 0.25f + drift, h * 0.68f, w * 0.65f + drift, h * 1.15f), hillPaint)
      hillPaint.color = Color.argb(175, 21, 128, 61)
      canvas.drawOval(RectF(w * 0.35f - drift, h * 0.72f, w * 1.25f - drift, h * 1.15f), hillPaint)
    }
  }

  private fun drawDynamicEnvironmentObjects(
    canvas: Canvas,
    scene: VideoScenePlan,
    w: Float,
    h: Float,
    scale: Float,
    phase: Float,
    globalTimeSec: Float,
  ) {
    // Environment-specific landmarks (Classroom Blackboard, City Buildings, Stage Lights, Moon)
    when (scene.environment) {
      EnvironmentSetting.CLASSROOM -> {
        val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 83, 45) }
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f * scale; color = Color.rgb(180, 83, 9) }
        val bRect = RectF(w * 0.20f, h * 0.16f, w * 0.80f, h * 0.42f)
        canvas.drawRoundRect(bRect, 10f * scale, 10f * scale, boardPaint)
        canvas.drawRoundRect(bRect, 10f * scale, 10f * scale, framePaint)
      }
      EnvironmentSetting.CITY_STREET, EnvironmentSetting.MARKETPLACE -> {
        val bldP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 15, 23, 42) }
        val winP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 253, 224, 71) }
        for (i in 0 until 6) {
          val bx = i * w * 0.18f + 12f * scale
          val bh = h * (0.28f + (i % 3) * 0.08f)
          canvas.drawRect(bx, h * 0.68f - bh, bx + w * 0.13f, h * 0.72f, bldP)
          canvas.drawRect(bx + 14f * scale, h * 0.68f - bh + 18f * scale, bx + 28f * scale, h * 0.68f - bh + 34f * scale, winP)
        }
      }
      EnvironmentSetting.CONCERT_STAGE -> {
        val beamL = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 236, 72, 153) }
        val beamR = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 34, 211, 238) }
        val sweep = sin(globalTimeSec * 2.5).toFloat() * w * 0.18f
        canvas.drawCircle(w * 0.35f + sweep, h * 0.40f, w * 0.28f, beamL)
        canvas.drawCircle(w * 0.65f - sweep, h * 0.40f, w * 0.28f, beamR)
      }
      else -> Unit
    }

    // Rainy weather streaks if RAINY_DRIZZLE
    if (scene.weatherMood == TimeWeatherMood.RAINY_DRIZZLE) {
      val rainP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(135, 186, 230, 253)
        strokeWidth = 2f * scale
      }
      for (r in 0 until 24) {
        val rx = (((r * 173 + globalTimeSec * 90f).toInt() % 1000) / 1000f) * w
        val ry = (((r * 251 + globalTimeSec * 420f).toInt() % 1000) / 1000f) * h
        canvas.drawLine(rx, ry, rx - 8f * scale, ry + 22f * scale, rainP)
      }
    }

    // Soft sunbeam / moonlight cone moving across background
    val spotX = w * (0.25f + 0.5f * sin(globalTimeSec * 0.65).toFloat())
    val beamColor = if (scene.weatherMood == TimeWeatherMood.NIGHT_MOONLIGHT) Color.argb(40, 186, 230, 253) else Color.argb(46, 253, 224, 71)
    val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = RadialGradient(spotX, h * 0.14f, w * 0.52f, beamColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    canvas.drawCircle(spotX, h * 0.14f, w * 0.52f, beamPaint)

    // Drifting clouds
    val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 226, 232, 240) }
    for (c in 0 until 3) {
      val cx = ((phase * 0.35f + c * 0.38f) % 1.2f - 0.1f) * w
      val cy = h * (0.17f + c * 0.055f)
      canvas.drawOval(RectF(cx - 52f * scale, cy - 14f * scale, cx + 52f * scale, cy + 16f * scale), cloudPaint)
      canvas.drawCircle(cx - 22f * scale, cy - 8f * scale, 18f * scale, cloudPaint)
      canvas.drawCircle(cx + 16f * scale, cy - 10f * scale, 22f * scale, cloudPaint)
    }

    // Animated birds / background extras silhouettes
    val birdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 2.5f * scale
      strokeCap = Paint.Cap.ROUND
      color = Color.argb(175, 224, 247, 250)
    }
    for (b in 0 until 2) {
      val bx = (((globalTimeSec * 0.14f + b * 0.48f) % 1.15f) - 0.05f) * w
      val by = h * (0.22f + b * 0.06f) + sin(globalTimeSec * 4.5 + b).toFloat() * 8f * scale
      val flap = sin(globalTimeSec * 12.0 + b).toFloat() * 7f * scale
      val p = Path().apply {
        moveTo(bx - 12f * scale, by - flap)
        quadTo(bx - 6f * scale, by - 6f * scale, bx, by)
        quadTo(bx + 6f * scale, by - 6f * scale, bx + 12f * scale, by - flap)
      }
      canvas.drawPath(p, birdPaint)
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
    val zoom = baseScale * (1.04f + zoomDelta * sin(phase * PI).toFloat())
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
      MotionChoreography.RUNNING -> {
        val runCycle = sin(phase * 12.0 * PI).toFloat()
        offsetX = (phase - 0.5f) * boxW * 0.72f
        offsetY = -abs(runCycle) * 28f * scale
        rotZ = 9f + runCycle * 6f
        pulseScale = 1.0f + 0.04f * runCycle
      }
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
      else -> {
        offsetY = sin(phase * 4.0 * PI).toFloat() * 9f * scale - mouthOpen * 8f * scale
        rotZ = sin(phase * 2.0 * PI).toFloat() * (1.8f + mouthOpen * 2.4f)
        pulseScale = 1.0f + mouthOpen * 0.045f
      }
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
  }

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
    val freq = if (motion == MotionChoreography.RUNNING) 14.0 else 8.0
    val walkSwing = sin(phase * freq * PI).toFloat()
    val bounceY = when (motion) {
      MotionChoreography.RUNNING -> -abs(walkSwing) * 26f * scale
      MotionChoreography.WALKING -> -abs(walkSwing) * 18f * scale
      MotionChoreography.DANCING -> -abs(sin(phase * 10.0 * PI).toFloat()) * 26f * scale
      else -> sin(phase * 4.0 * PI).toFloat() * 8f * scale
    }
    val walkX = when (motion) {
      MotionChoreography.RUNNING -> (phase - 0.5f) * 210f * scale
      MotionChoreography.WALKING -> (phase - 0.5f) * 160f * scale
      else -> 0f
    }
    val tiltDeg = when (motion) {
      MotionChoreography.RUNNING -> 10f + walkSwing * 5f
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

    val armWaveL = sin(phase * freq * PI).toFloat() * 34f * scale
    val armWaveR = cos(phase * freq * PI).toFloat() * 34f * scale
    val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(254, 240, 138) }
    canvas.drawLine(-72f * scale, 8f * scale, -124f * scale, -18f * scale + armWaveL, legPaint)
    canvas.drawCircle(-124f * scale, -18f * scale + armWaveL, 14f * scale, handPaint)
    canvas.drawLine(72f * scale, 8f * scale, 124f * scale, -22f * scale + armWaveR, legPaint)
    canvas.drawCircle(124f * scale, -22f * scale + armWaveR, 14f * scale, handPaint)

    var faceOffsetY = -10f * scale
    when (preset) {
      TalkingCharacterPreset.CAULIFLOWER -> {
        val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 197, 94) }
        val darkLeaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 128, 61) }
        for (a in listOf(-140.0, -105.0, -75.0, -40.0, 40.0, 75.0, 105.0, 140.0)) {
          val rad = Math.toRadians(a)
          val lx = (sin(rad) * 95f).toFloat() * scale
          val ly = (cos(rad) * 72f + 35f).toFloat() * scale
          canvas.drawCircle(lx, ly, 36f * scale, if (a < 0) leafPaint else darkLeaf)
        }
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
        val palmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader = LinearGradient(-55f * scale, -120f * scale, 55f * scale, 100f * scale, Color.rgb(255, 224, 189), Color.rgb(244, 180, 135), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(-56f * scale, -135f * scale, 56f * scale, 95f * scale), 56f * scale, 56f * scale, palmPaint)
        val nailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 241, 242) }
        canvas.drawRoundRect(RectF(-34f * scale, -122f * scale, 34f * scale, -78f * scale), 26f * scale, 26f * scale, nailPaint)
        faceOffsetY = -14f * scale
      }
      else -> Unit
    }

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

    val openHeight = (6f + mouthOpen * 28f) * scale
    val mouthBox = RectF(-24f * scale, faceOffsetY + 6f * scale, 24f * scale, faceOffsetY + 6f * scale + openHeight)
    val lipOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(127, 29, 29) }
    val tonguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 63, 94) }
    val lipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * scale; color = Color.rgb(69, 10, 10) }

    if (mouthOpen < 0.12f) {
      canvas.drawArc(RectF(-22f * scale, faceOffsetY + 2f * scale, 22f * scale, faceOffsetY + 22f * scale), 15f, 150f, false, lipStroke)
    } else {
      canvas.drawOval(mouthBox, lipOuter)
      canvas.drawRoundRect(RectF(-16f * scale, mouthBox.top + 2f * scale, 16f * scale, mouthBox.top + 8f * scale), 4f * scale, 4f * scale, whitePaint)
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
    val isDance = motion == MotionChoreography.DANCING

    for (i in 0 until 24) {
      val px = (((i * 211 + 59) % 1000) / 1000f) * w
      val driftY = (((i * 317 + 73) % 1000) / 1000f) * h * 0.85f - (phase * 48f * scale)
      val py = ((driftY % h) + h) % h
      val alpha = (90 + 145 * abs(sin(phase * 2.0 * PI + i)).toFloat()).toInt().coerceIn(35, 240)
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
      textSize = 22f * scale
      typeface = Typeface.DEFAULT_BOLD
      textAlign = Paint.Align.CENTER
    }
    val epTag = if (scene.episodeNumber > 1) "EP %02d • ".format(scene.episodeNumber) else ""
    canvas.drawText(
      "${epTag}Scene ${scene.index} • ${scene.cameraShot.name.replace('_', ' ')}: ${scene.overlayHeadline.take(24)}",
      w * 0.5f,
      topRect.centerY() + 8f * scale,
      titlePaint,
    )

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
