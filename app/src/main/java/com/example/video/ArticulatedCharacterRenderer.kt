package com.example.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Multi-Joint Articulated 3D Cartoon Character Animator (1 to 4 Simultaneous Actors):
 * - Preserves user-supplied Reference Image face/look inside animated head + live blinking & PCM lip-sync!
 * - Human-like full-body articulation: walk, run, jump, sit, turn, wave, hug, handshake, solo & group dance, singing
 * - Rich facial emotions: Happy, Smiling, Sad/Cry (animated tears), Angry, Fear, Shy (blush), Excited, Surprised, Calm
 * - Natural hair & clothing motion + multi-character turn-taking reactions
 */
object ArticulatedCharacterRenderer {

  private data class CastSlot(
    val name: String,
    val refImagePath: String,
    val primaryColor: Int,
    val hairColor: Int,
    val ageScale: Float,
  )

  fun drawArticulatedSceneCast(
    canvas: Canvas,
    scene: VideoScenePlan,
    cx: Float,
    cy: Float,
    scale: Float,
    phase: Float,
    globalTimeSec: Float,
    mouthOpen: Float,
    motion: MotionChoreography,
    context: Context? = null,
  ) {
    val castSlots = buildCastSlots(scene)
    val actorCount = castSlots.size.coerceIn(1, 4)

    val xOffsets = when (actorCount) {
      1 -> listOf(0f)
      2 -> when (motion) {
        MotionChoreography.HUG_INTERACTION -> listOf(-46f * scale, 46f * scale)
        MotionChoreography.HANDSHAKE -> listOf(-64f * scale, 64f * scale)
        else -> listOf(-102f * scale, 108f * scale)
      }
      3 -> listOf(-155f * scale, 0f, 155f * scale)
      else -> listOf(-188f * scale, -64f * scale, 64f * scale, 188f * scale)
    }
    val depthScales = when (actorCount) {
      1 -> listOf(1.04f)
      2 -> listOf(0.95f, 0.90f)
      3 -> listOf(0.84f, 0.92f, 0.84f)
      else -> listOf(0.78f, 0.86f, 0.84f, 0.76f)
    }

    val activeSpeakerIdx = if (actorCount == 1) 0 else ((phase * actorCount).toInt().coerceIn(0, actorCount - 1))

    castSlots.forEachIndexed { idx, slot ->
      val isSpeaker = idx == activeSpeakerIdx
      val actorMouth =
        if (motion == MotionChoreography.SINGING || motion == MotionChoreography.GROUP_DANCE) {
          (mouthOpen * (0.75f + 0.25f * sin(globalTimeSec * 4.0 + idx).toFloat())).coerceIn(0f, 1f)
        } else if (isSpeaker) {
          mouthOpen
        } else {
          // Listening partner reacts with subtle sympathetic lip movement or smile
          if (phase > 0.7f && scene.dialogueLine.isNotBlank()) mouthOpen * 0.28f else 0.03f
        }

      val lookDir = when {
        actorCount == 1 -> 0f
        idx < activeSpeakerIdx -> 1f
        idx > activeSpeakerIdx -> -1f
        idx == 0 -> 1f
        else -> -1f
      }
      val partnerMotion = when {
        motion == MotionChoreography.GROUP_DANCE ||
          motion == MotionChoreography.DANCING ||
          motion == MotionChoreography.WALKING ||
          motion == MotionChoreography.RUNNING ||
          motion == MotionChoreography.PLAYING ||
          motion == MotionChoreography.JUMPING ||
          motion == MotionChoreography.SITTING ||
          motion == MotionChoreography.SINGING -> motion
        motion == MotionChoreography.HUG_INTERACTION || motion == MotionChoreography.HANDSHAKE -> motion
        !isSpeaker && motion == MotionChoreography.CLASSROOM_TEACHING -> MotionChoreography.SITTING
        !isSpeaker -> MotionChoreography.HAND_WAVE_GESTURE
        else -> motion
      }

      val refBitmap =
        if (context != null && slot.refImagePath.isNotBlank()) {
          StudioMediaLibrary.loadStudioBitmap(context, filePath = slot.refImagePath, maxDim = 256)
        } else null

      drawSingleArticulatedActor(
        canvas = canvas,
        name = slot.name,
        isPrimarySpeaker = isSpeaker,
        motion = partnerMotion,
        emotion = scene.emotion,
        emotionHint = scene.emotionalTone,
        cx = cx + xOffsets[idx],
        cy = cy + (10f + idx * 3f) * scale,
        scale = scale * depthScales[idx] * slot.ageScale,
        phase = if (motion == MotionChoreography.GROUP_DANCE) phase else (phase + idx * 0.14f) % 1f,
        globalTimeSec = globalTimeSec + idx * 0.45f,
        mouthOpen = actorMouth,
        lookDirX = lookDir,
        jacketColor = slot.primaryColor,
        hairColor = slot.hairColor,
        refFaceBitmap = refBitmap,
        actorSlotIndex = idx,
      )
    }
  }

  private fun buildCastSlots(scene: VideoScenePlan): List<CastSlot> {
    val defaultPalette = listOf(
      Color.rgb(14, 165, 233) to Color.rgb(30, 41, 59),
      Color.rgb(236, 72, 153) to Color.rgb(49, 16, 47),
      Color.rgb(139, 92, 246) to Color.rgb(15, 23, 42),
      Color.rgb(34, 197, 94) to Color.rgb(51, 65, 85),
    )

    if (scene.castNames.isNotEmpty()) {
      return scene.castNames.take(4).mapIndexed { i, nm ->
        val (defJ, defH) = defaultPalette[i % defaultPalette.size]
        val refPath = scene.castRefImagePaths.getOrNull(i).orEmpty()
          .ifBlank { if (i == 0 && !scene.userMediaIsVideo) scene.userMediaPath else "" }
        CastSlot(
          name = nm,
          refImagePath = refPath,
          primaryColor = scene.castPrimaryColors.getOrNull(i)?.toInt() ?: defJ,
          hairColor = scene.castHairColors.getOrNull(i)?.toInt() ?: defH,
          ageScale = scene.castAgeGroups.getOrNull(i)?.scaleFactor ?: 1.0f,
        )
      }
    }

    val list = mutableListOf(
      CastSlot(
        name = scene.characterName.ifBlank { "মায়া (Lead)" },
        refImagePath = if (!scene.userMediaIsVideo) scene.userMediaPath else "",
        primaryColor = defaultPalette[0].first,
        hairColor = defaultPalette[0].second,
        ageScale = 0.96f,
      )
    )
    if (scene.secondaryCharacterName.isNotBlank() ||
      scene.productionMode == VideoProductionMode.REAL_AI_VIDEO ||
      scene.productionMode == VideoProductionMode.HYBRID_ALL_THREE
    ) {
      list.add(
        CastSlot(
          name = scene.secondaryCharacterName.ifBlank { "রাফি (বন্ধু)" },
          refImagePath = "",
          primaryColor = defaultPalette[1].first,
          hairColor = defaultPalette[1].second,
          ageScale = 0.92f,
        )
      )
    }
    return list
  }

  private fun drawSingleArticulatedActor(
    canvas: Canvas,
    name: String,
    isPrimarySpeaker: Boolean,
    motion: MotionChoreography,
    emotion: CharacterEmotion,
    emotionHint: String,
    cx: Float,
    cy: Float,
    scale: Float,
    phase: Float,
    globalTimeSec: Float,
    mouthOpen: Float,
    lookDirX: Float,
    jacketColor: Int,
    hairColor: Int,
    refFaceBitmap: Bitmap? = null,
    actorSlotIndex: Int = 0,
  ) {
    val freq = when (motion) {
      MotionChoreography.RUNNING, MotionChoreography.PLAYING -> 14.0
      MotionChoreography.WALKING -> 8.0
      MotionChoreography.DANCING, MotionChoreography.GROUP_DANCE -> 10.0
      MotionChoreography.JUMPING -> 7.0
      MotionChoreography.SINGING -> 6.0
      else -> 4.0
    }
    val cycle = sin(phase * freq * PI).toFloat()
    val cosCycle = cos(phase * freq * PI).toFloat()

    val strideTravelX = when (motion) {
      MotionChoreography.RUNNING -> ((phase - 0.5f) * 185f * scale)
      MotionChoreography.WALKING -> ((phase - 0.5f) * 125f * scale)
      MotionChoreography.PLAYING -> sin(phase * 3.0 * PI).toFloat() * 70f * scale
      MotionChoreography.DANCING, MotionChoreography.GROUP_DANCE -> sin(phase * 4.0 * PI).toFloat() * 38f * scale
      else -> 0f
    }
    val bounceY = when (motion) {
      MotionChoreography.RUNNING, MotionChoreography.PLAYING -> -abs(cycle) * 26f * scale
      MotionChoreography.JUMPING -> -abs(sin(phase * 4.0 * PI).toFloat()) * 68f * scale
      MotionChoreography.WALKING -> -abs(cycle) * 14f * scale
      MotionChoreography.DANCING, MotionChoreography.GROUP_DANCE -> -abs(cycle) * 24f * scale
      MotionChoreography.SITTING -> 24f * scale + sin(globalTimeSec * 2.0).toFloat() * 3f * scale
      else -> sin(globalTimeSec * 2.4).toFloat() * 5.5f * scale - mouthOpen * 6f * scale
    }
    val leanDeg = when (motion) {
      MotionChoreography.RUNNING -> 11f + cycle * 4f
      MotionChoreography.WALKING -> 4f + cycle * 2.8f
      MotionChoreography.DANCING, MotionChoreography.GROUP_DANCE -> cycle * 9f
      MotionChoreography.SINGING -> sin(globalTimeSec * 3.6).toFloat() * 5f
      MotionChoreography.HUG_INTERACTION -> lookDirX * 8f
      else -> sin(globalTimeSec * 1.7).toFloat() * 2.5f + mouthOpen * 2f
    }
    val turnScaleX =
      if (motion == MotionChoreography.STANDING_TURN) cos(phase * 2.0 * PI).toFloat().let { if (abs(it) < 0.25f) 0.25f * (if (it >= 0f) 1f else -1f) else it }
      else 1f

    val drawX = cx + strideTravelX
    val drawY = cy + bounceY

    // Cast dynamic ground shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
    val shadowRadiusX = (54f - abs(bounceY) * 0.35f).coerceAtLeast(22f) * scale
    canvas.drawOval(
      RectF(drawX - shadowRadiusX, cy + 148f * scale, drawX + shadowRadiusX, cy + 166f * scale),
      shadowPaint,
    )

    // Speed lines when running or jumping
    if (motion == MotionChoreography.RUNNING || motion == MotionChoreography.JUMPING) {
      val streakP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.6f * scale
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(145, 224, 247, 250)
      }
      for (s in 0 until 3) {
        val sy = drawY - 30f * scale + s * 34f * scale
        val sl = (34f + ((s * 17) % 26)) * scale
        canvas.drawLine(drawX - 82f * scale - sl, sy, drawX - 76f * scale, sy, streakP)
      }
    }

    canvas.save()
    canvas.translate(drawX, drawY)
    canvas.rotate(leanDeg)
    if (turnScaleX != 1f) canvas.scale(turnScaleX, 1f)

    val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(30, 41, 59)
      strokeWidth = 14f * scale
      strokeCap = Paint.Cap.ROUND
    }
    val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(252, 211, 179) }
    val shoePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (isPrimarySpeaker) Color.rgb(0, 229, 255) else Color.rgb(250, 204, 21)
    }

    // Legs (including Sitting pose vs Standing/Walking/Jumping)
    if (motion == MotionChoreography.SITTING) {
      // Bent thighs & hanging shins for Sitting on chair/bench
      canvas.drawLine(-16f * scale, 54f * scale, -44f * scale, 78f * scale, limbPaint)
      canvas.drawLine(-44f * scale, 78f * scale, -40f * scale, 122f * scale, limbPaint)
      canvas.drawOval(RectF(-56f * scale, 116f * scale, -22f * scale, 132f * scale), shoePaint)

      canvas.drawLine(16f * scale, 54f * scale, 44f * scale, 78f * scale, limbPaint)
      canvas.drawLine(44f * scale, 78f * scale, 40f * scale, 122f * scale, limbPaint)
      canvas.drawOval(RectF(22f * scale, 116f * scale, 56f * scale, 132f * scale), shoePaint)
    } else {
      val swingLeg = when (motion) {
        MotionChoreography.RUNNING, MotionChoreography.PLAYING -> cycle * 42f * scale
        MotionChoreography.WALKING -> cycle * 26f * scale
        MotionChoreography.DANCING, MotionChoreography.GROUP_DANCE -> cycle * 24f * scale
        else -> 0f
      }
      val liftL = maxOf(0f, cycle) * (if (motion == MotionChoreography.RUNNING) 30f else 18f) * scale
      val liftR = maxOf(0f, -cycle) * (if (motion == MotionChoreography.RUNNING) 30f else 18f) * scale

      val lKneeX = -18f * scale + swingLeg * 0.55f
      val lKneeY = 100f * scale - liftL * 0.45f
      val lFootX = -20f * scale + swingLeg
      val lFootY = 146f * scale - liftL
      canvas.drawLine(-16f * scale, 54f * scale, lKneeX, lKneeY, limbPaint)
      canvas.drawLine(lKneeX, lKneeY, lFootX, lFootY, limbPaint)
      canvas.drawOval(RectF(lFootX - 18f * scale, lFootY - 7f * scale, lFootX + 20f * scale, lFootY + 11f * scale), shoePaint)

      val rKneeX = 18f * scale - swingLeg * 0.55f
      val rKneeY = 100f * scale - liftR * 0.45f
      val rFootX = 20f * scale - swingLeg
      val rFootY = 146f * scale - liftR
      canvas.drawLine(16f * scale, 54f * scale, rKneeX, rKneeY, limbPaint)
      canvas.drawLine(rKneeX, rKneeY, rFootX, rFootY, limbPaint)
      canvas.drawOval(RectF(rFootX - 18f * scale, rFootY - 7f * scale, rFootX + 20f * scale, rFootY + 11f * scale), shoePaint)
    }

    // Swaying coat/dress cloth tail at hip for living cloth physics
    val clothSway = sin(globalTimeSec * 4.5 + actorSlotIndex).toFloat() * 8f * scale
    val coatTail = Path().apply {
      moveTo(-36f * scale, 52f * scale)
      lineTo(-42f * scale + clothSway, 74f * scale)
      lineTo(42f * scale + clothSway, 74f * scale)
      lineTo(36f * scale, 52f * scale)
      close()
    }
    val coatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = jacketColor }
    canvas.drawPath(coatTail, coatPaint)

    // Torso / Outfit
    val torsoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = LinearGradient(
        -40f * scale, -24f * scale, 40f * scale, 64f * scale,
        jacketColor,
        Color.rgb(15, 23, 42),
        Shader.TileMode.CLAMP,
      )
    }
    canvas.drawRoundRect(RectF(-38f * scale, -20f * scale, 38f * scale, 64f * scale), 20f * scale, 20f * scale, torsoPaint)

    // Articulated Shoulder -> Elbow -> Wrist -> Multi-Finger Hands
    val gestureBoost = if (isPrimarySpeaker) mouthOpen * 26f * scale else 6f * scale
    val armAmp = when (motion) {
      MotionChoreography.RUNNING, MotionChoreography.PLAYING -> 38f * scale
      MotionChoreography.WALKING -> 24f * scale
      MotionChoreography.DANCING, MotionChoreography.GROUP_DANCE, MotionChoreography.JUMPING -> 46f * scale
      MotionChoreography.HAND_WAVE_GESTURE -> 32f * scale
      MotionChoreography.SINGING -> 26f * scale
      else -> 12f * scale + gestureBoost
    }

    val lElbowX = -56f * scale - cosCycle * 8f * scale
    val lElbowY = 14f * scale + cycle * armAmp * 0.5f
    val lHandX = when (motion) {
      MotionChoreography.HUG_INTERACTION -> 16f * scale * lookDirX
      MotionChoreography.HANDSHAKE -> lookDirX * 42f * scale
      else -> -68f * scale + lookDirX * 14f * scale
    }
    val lHandY = when (motion) {
      MotionChoreography.HAND_WAVE_GESTURE, MotionChoreography.JUMPING -> -44f * scale - abs(cycle) * 18f * scale
      MotionChoreography.CLASSROOM_TEACHING -> -36f * scale - mouthOpen * 14f * scale
      else -> -2f * scale - gestureBoost + cosCycle * armAmp
    }

    val sleevePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = jacketColor
      strokeWidth = 13f * scale
      strokeCap = Paint.Cap.ROUND
    }
    canvas.drawLine(-34f * scale, -8f * scale, lElbowX, lElbowY, sleevePaint)
    canvas.drawLine(lElbowX, lElbowY, lHandX, lHandY, sleevePaint)
    drawHandWithFingers(canvas, lHandX, lHandY, scale, skinPaint, -1f)

    val rElbowX = 56f * scale + cosCycle * 8f * scale
    val rElbowY = 14f * scale - cycle * armAmp * 0.5f
    val rHandX = when (motion) {
      MotionChoreography.HUG_INTERACTION -> -14f * scale * lookDirX
      MotionChoreography.HANDSHAKE -> lookDirX * 48f * scale
      else -> 68f * scale + lookDirX * 14f * scale
    }
    val rHandY = when (motion) {
      MotionChoreography.DANCING, MotionChoreography.GROUP_DANCE -> -32f * scale - sin(phase * 10.0 * PI).toFloat() * 24f * scale
      MotionChoreography.HAND_WAVE_GESTURE -> -48f * scale - cycle * 18f * scale
      MotionChoreography.HANDSHAKE -> 12f * scale + cycle * 6f * scale
      else -> -6f * scale - gestureBoost * 0.8f - cosCycle * armAmp
    }
    canvas.drawLine(34f * scale, -8f * scale, rElbowX, rElbowY, sleevePaint)
    canvas.drawLine(rElbowX, rElbowY, rHandX, rHandY, sleevePaint)
    drawHandWithFingers(canvas, rHandX, rHandY, scale, skinPaint, 1f)

    // Neck + Head
    canvas.drawRoundRect(RectF(-10f * scale, -34f * scale, 10f * scale, -14f * scale), 6f * scale, 6f * scale, skinPaint)
    val headNod = sin(globalTimeSec * 3.1).toFloat() * (3f + mouthOpen * 5f)
    canvas.save()
    canvas.translate(0f, -66f * scale)
    canvas.rotate(headNod)

    // Flowing hair back sway (for lively hair animation)
    val hairP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hairColor }
    val hairSway = sin(globalTimeSec * 5.2 + actorSlotIndex).toFloat() * 6f * scale
    canvas.drawOval(RectF(-40f * scale + hairSway * 0.5f, -38f * scale, 40f * scale + hairSway * 0.5f, 30f * scale), hairP)

    if (refFaceBitmap != null) {
      // Preserve user-supplied Reference Image inside animated oval head, with LIVE eyes/blink + lip-sync!
      val headRect = RectF(-42f * scale, -44f * scale, 42f * scale, 44f * scale)
      val headClip = Path().apply { addOval(headRect, Path.Direction.CW) }
      canvas.save()
      canvas.clipPath(headClip)
      canvas.drawBitmap(refFaceBitmap, null, headRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
      canvas.restore()

      val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * scale
        color = if (isPrimarySpeaker && mouthOpen > 0.14f) Color.rgb(0, 229, 255) else Color.argb(180, 255, 255, 255)
      }
      canvas.drawOval(headRect, ringP)

      // Overlay living blinking eyelids & audio-synced animated mouth onto Reference Image lower face
      drawFacialExpressionsAndLipSync(
        canvas = canvas,
        scale = scale,
        globalTimeSec = globalTimeSec,
        mouthOpen = mouthOpen,
        lookDirX = lookDirX,
        emotion = emotion,
        emotionHint = emotionHint,
        hairColor = hairColor,
        overlayOnPhoto = true,
      )
    } else {
      // Sculpted 3D Cartoon Head
      canvas.drawCircle(0f, 0f, 38f * scale, skinPaint)
      val hairPath = Path().apply {
        addArc(RectF(-41f * scale, -44f * scale, 41f * scale, 22f * scale), 175f, 190f)
        close()
      }
      canvas.drawPath(hairPath, hairP)

      drawFacialExpressionsAndLipSync(
        canvas = canvas,
        scale = scale,
        globalTimeSec = globalTimeSec,
        mouthOpen = mouthOpen,
        lookDirX = lookDirX,
        emotion = emotion,
        emotionHint = emotionHint,
        hairColor = hairColor,
        overlayOnPhoto = false,
      )
    }

    canvas.restore()

    // Nameplate pill
    if (isPrimarySpeaker || mouthOpen > 0.14f) {
      val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(195, 10, 20, 46) }
      val tagTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isPrimarySpeaker) Color.rgb(0, 229, 255) else Color.rgb(226, 232, 240)
        textSize = 11f * scale
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
      }
      val rect = RectF(-48f * scale, -126f * scale, 48f * scale, -106f * scale)
      canvas.drawRoundRect(rect, 10f * scale, 10f * scale, tagBg)
      canvas.drawText(name.take(12), 0f, rect.centerY() + 4f * scale, tagTxt)
    }

    canvas.restore()
  }

  private fun drawHandWithFingers(
    canvas: Canvas,
    hx: Float,
    hy: Float,
    scale: Float,
    skinPaint: Paint,
    sideSign: Float,
  ) {
    canvas.drawCircle(hx, hy, 9.5f * scale, skinPaint)
    val fingerP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = skinPaint.color
      strokeWidth = 3.2f * scale
      strokeCap = Paint.Cap.ROUND
    }
    for (f in -1..1) {
      val fx = hx + sideSign * 6f * scale + f * 3.5f * scale
      val fy = hy - 9f * scale
      canvas.drawLine(hx, hy - 3f * scale, fx, fy, fingerP)
    }
  }

  private fun drawFacialExpressionsAndLipSync(
    canvas: Canvas,
    scale: Float,
    globalTimeSec: Float,
    mouthOpen: Float,
    lookDirX: Float,
    emotion: CharacterEmotion,
    emotionHint: String,
    hairColor: Int,
    overlayOnPhoto: Boolean,
  ) {
    val isBlinking = ((globalTimeSec * 0.9f) % 3.1f) > 2.94f
    val eyeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val pupilP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42) }
    val browP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE; strokeWidth = 3.2f * scale; strokeCap = Paint.Cap.ROUND; color = hairColor
    }

    val effectiveEmotion = when {
      emotionHint.contains("Sad", true) || emotionHint.contains("Cry", true) -> CharacterEmotion.SAD_CRY
      emotionHint.contains("Angry", true) -> CharacterEmotion.ANGRY
      emotionHint.contains("Fear", true) -> CharacterEmotion.FEAR
      emotionHint.contains("Shy", true) -> CharacterEmotion.SHY
      else -> emotion
    }

    // Shy or Happy Cheek Blush
    if (effectiveEmotion == CharacterEmotion.SHY || effectiveEmotion == CharacterEmotion.HAPPY || effectiveEmotion == CharacterEmotion.EXCITED_JOY) {
      val blushAlpha = if (effectiveEmotion == CharacterEmotion.SHY) 165 else 95
      val blushP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(blushAlpha, 244, 63, 94) }
      canvas.drawOval(RectF(-28f * scale, 1f * scale, -14f * scale, 10f * scale), blushP)
      canvas.drawOval(RectF(14f * scale, 1f * scale, 28f * scale, 10f * scale), blushP)
    }

    // Animated Tears for SAD_CRY
    if (effectiveEmotion == CharacterEmotion.SAD_CRY) {
      val tearY = ((globalTimeSec * 22f) % 18f) * scale
      val tearP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 56, 189, 248) }
      canvas.drawCircle(-14f * scale, 4f * scale + tearY, 3.2f * scale, tearP)
      canvas.drawCircle(14f * scale, 4f * scale + tearY, 3.2f * scale, tearP)
    }

    if (!overlayOnPhoto) {
      val browTilt = when (effectiveEmotion) {
        CharacterEmotion.ANGRY -> 5f * scale
        CharacterEmotion.SAD_CRY, CharacterEmotion.FEAR -> -4f * scale
        else -> 0f
      }
      val browLift = if (effectiveEmotion == CharacterEmotion.EXCITED_JOY || effectiveEmotion == CharacterEmotion.SURPRISED || mouthOpen > 0.45f) -4.5f * scale else 0f
      canvas.drawLine(-22f * scale, -14f * scale + browLift - browTilt, -7f * scale, -16f * scale + browLift + browTilt, browP)
      canvas.drawLine(7f * scale, -16f * scale + browLift + browTilt, 22f * scale, -14f * scale + browLift - browTilt, browP)

      val gazeOffset = lookDirX * 3.2f * scale
      if (!isBlinking) {
        val eyeRadius = if (effectiveEmotion == CharacterEmotion.FEAR || effectiveEmotion == CharacterEmotion.SURPRISED) 8.8f * scale else 7.5f * scale
        canvas.drawCircle(-14f * scale, -3f * scale, eyeRadius, eyeWhite)
        canvas.drawCircle(14f * scale, -3f * scale, eyeRadius, eyeWhite)
        canvas.drawCircle(-14f * scale + gazeOffset, -3f * scale, 4.2f * scale, pupilP)
        canvas.drawCircle(14f * scale + gazeOffset, -3f * scale, 4.2f * scale, pupilP)
        canvas.drawCircle(-15.5f * scale + gazeOffset, -4.5f * scale, 1.5f * scale, eyeWhite)
        canvas.drawCircle(12.5f * scale + gazeOffset, -4.5f * scale, 1.5f * scale, eyeWhite)
      } else {
        val blinkP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          style = Paint.Style.STROKE; strokeWidth = 2.8f * scale; color = Color.rgb(15, 23, 42)
        }
        canvas.drawLine(-20f * scale, -3f * scale, -8f * scale, -3f * scale, blinkP)
        canvas.drawLine(8f * scale, -3f * scale, 20f * scale, -3f * scale, blinkP)
      }
    } else if (isBlinking) {
      // Subtle animated blink eyelids over Reference Image eyes
      val lidP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 212, 165, 130) }
      canvas.drawOval(RectF(-22f * scale, -10f * scale, -6f * scale, -1f * scale), lidP)
      canvas.drawOval(RectF(6f * scale, -10f * scale, 22f * scale, -1f * scale), lidP)
    }

    // Lip-Synced Mouth (with Teeth, Tongue & Expression Curves)
    val lipOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(127, 29, 29) }
    val tongue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 63, 94) }
    val mouthStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE; strokeWidth = 2.6f * scale; color = Color.rgb(69, 10, 10)
    }
    val mouthTopY = if (overlayOnPhoto) 15f * scale else 10f * scale

    if (mouthOpen < 0.10f) {
      if (!overlayOnPhoto) {
        when (effectiveEmotion) {
          CharacterEmotion.SAD_CRY, CharacterEmotion.ANGRY ->
            canvas.drawArc(RectF(-12f * scale, 14f * scale, 12f * scale, 24f * scale), 195f, 150f, false, mouthStroke)
          else ->
            canvas.drawArc(RectF(-12f * scale, 9f * scale, 12f * scale, 20f * scale), 15f, 150f, false, mouthStroke)
        }
      }
    } else {
      val mH = (4f + mouthOpen * 15f) * scale
      val mBox = RectF(-12.5f * scale, mouthTopY, 12.5f * scale, mouthTopY + mH)
      canvas.drawOval(mBox, lipOuter)
      canvas.drawRoundRect(RectF(-8.5f * scale, mBox.top + 1.5f * scale, 8.5f * scale, mBox.top + 4.8f * scale), 2f * scale, 2f * scale, eyeWhite)
      if (mouthOpen > 0.25f) {
        canvas.drawOval(RectF(-6.5f * scale, mBox.centerY(), 6.5f * scale, mBox.bottom - 1.5f * scale), tongue)
      }
      canvas.drawOval(mBox, mouthStroke)
    }
  }
}
