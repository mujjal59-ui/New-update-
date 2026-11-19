package com.example.video

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
 * Multi-Joint Articulated Human / Storyteller Character Animator:
 * - Natural breathing torso & posture lean
 * - Walking & Running full leg/arm gait cycles + ground shadows + speed lines
 * - Expressive face: tracked eyes, automatic blinking, emotion eyebrows, audio-synced mouth & teeth/tongue
 * - Conversational Multi-Character Interaction (primary speaker + listening/reacting secondary partner)
 */
object ArticulatedCharacterRenderer {

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
  ) {
    val hasPartner = scene.secondaryCharacterName.isNotBlank() ||
      scene.productionMode == VideoProductionMode.REAL_AI_VIDEO ||
      scene.productionMode == VideoProductionMode.HYBRID_ALL_THREE

    if (hasPartner) {
      val leadX = cx - 95f * scale
      val partnerX = cx + 108f * scale
      drawSingleArticulatedActor(
        canvas = canvas,
        name = scene.characterName.ifBlank { "Lead" },
        isPrimarySpeaker = true,
        motion = motion,
        emotion = scene.emotionalTone,
        cx = leadX,
        cy = cy + 12f * scale,
        scale = scale * 0.94f,
        phase = phase,
        globalTimeSec = globalTimeSec,
        mouthOpen = mouthOpen,
        lookDirX = 1f,
        jacketColor = Color.rgb(14, 165, 233),
        hairColor = Color.rgb(30, 41, 59),
      )

      // Partner nods, breathes, blinks, waves attentively while listening/reacting
      val partnerRespondMouth =
        if (phase > 0.72f && scene.dialogueLine.isNotBlank()) mouthOpen * 0.45f else 0.04f
      val partnerMotion =
        if (motion == MotionChoreography.RUNNING || motion == MotionChoreography.WALKING) motion
        else MotionChoreography.TALKING_LIPSYNC
      drawSingleArticulatedActor(
        canvas = canvas,
        name = scene.secondaryCharacterName.ifBlank { "সঙ্গী" },
        isPrimarySpeaker = false,
        motion = partnerMotion,
        emotion = "Warm",
        cx = partnerX,
        cy = cy + 16f * scale,
        scale = scale * 0.88f,
        phase = (phase + 0.18f) % 1f,
        globalTimeSec = globalTimeSec + 0.6f,
        mouthOpen = partnerRespondMouth,
        lookDirX = -1f,
        jacketColor = Color.rgb(168, 85, 247),
        hairColor = Color.rgb(88, 28, 135),
      )
    } else {
      drawSingleArticulatedActor(
        canvas = canvas,
        name = scene.characterName.ifBlank { "Actor" },
        isPrimarySpeaker = true,
        motion = motion,
        emotion = scene.emotionalTone,
        cx = cx,
        cy = cy + 10f * scale,
        scale = scale * 1.02f,
        phase = phase,
        globalTimeSec = globalTimeSec,
        mouthOpen = mouthOpen,
        lookDirX = 0f,
        jacketColor = Color.rgb(14, 165, 233),
        hairColor = Color.rgb(30, 41, 59),
      )
    }
  }

  private fun drawSingleArticulatedActor(
    canvas: Canvas,
    name: String,
    isPrimarySpeaker: Boolean,
    motion: MotionChoreography,
    emotion: String,
    cx: Float,
    cy: Float,
    scale: Float,
    phase: Float,
    globalTimeSec: Float,
    mouthOpen: Float,
    lookDirX: Float,
    jacketColor: Int,
    hairColor: Int,
  ) {
    val freq = when (motion) {
      MotionChoreography.RUNNING -> 14.0
      MotionChoreography.WALKING -> 8.0
      MotionChoreography.DANCING -> 10.0
      else -> 4.0
    }
    val cycle = sin(phase * freq * PI).toFloat()
    val cosCycle = cos(phase * freq * PI).toFloat()

    val strideTravelX = when (motion) {
      MotionChoreography.RUNNING -> ((phase - 0.5f) * 210f * scale)
      MotionChoreography.WALKING -> ((phase - 0.5f) * 145f * scale)
      MotionChoreography.DANCING -> sin(phase * 4.0 * PI).toFloat() * 44f * scale
      else -> 0f
    }
    val bounceY = when (motion) {
      MotionChoreography.RUNNING -> -abs(cycle) * 26f * scale
      MotionChoreography.WALKING -> -abs(cycle) * 15f * scale
      MotionChoreography.DANCING -> -abs(cycle) * 22f * scale
      else -> sin(globalTimeSec * 2.4).toFloat() * 5.5f * scale - mouthOpen * 6f * scale
    }
    val leanDeg = when (motion) {
      MotionChoreography.RUNNING -> 12f + cycle * 4f
      MotionChoreography.WALKING -> 4f + cycle * 2.8f
      MotionChoreography.DANCING -> cycle * 8f
      else -> sin(globalTimeSec * 1.7).toFloat() * 2.5f + mouthOpen * 2f
    }

    val drawX = cx + strideTravelX
    val drawY = cy + bounceY

    // Cast ground shadow with dynamic contact stretch
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
    val shadowRadiusX = (58f - abs(bounceY) * 0.4f) * scale
    canvas.drawOval(
      RectF(drawX - shadowRadiusX, cy + 148f * scale, drawX + shadowRadiusX, cy + 168f * scale),
      shadowPaint,
    )

    // Speed wind streaks & dust puffs when Running
    if (motion == MotionChoreography.RUNNING) {
      val streakP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * scale
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(145, 224, 247, 250)
      }
      for (s in 0 until 4) {
        val sy = drawY - 40f * scale + s * 36f * scale
        val sl = (38f + ((s * 17) % 30)) * scale
        canvas.drawLine(drawX - 88f * scale - sl, sy, drawX - 82f * scale, sy, streakP)
      }
    }

    canvas.save()
    canvas.translate(drawX, drawY)
    canvas.rotate(leanDeg)

    val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(30, 41, 59)
      strokeWidth = 14f * scale
      strokeCap = Paint.Cap.ROUND
    }
    val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(252, 211, 179) }
    val shoePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (isPrimarySpeaker) Color.rgb(0, 229, 255) else Color.rgb(250, 204, 21)
    }

    // Articulated Hip -> Knee -> Foot legs
    val swingLeg = when (motion) {
      MotionChoreography.RUNNING -> cycle * 42f * scale
      MotionChoreography.WALKING -> cycle * 26f * scale
      MotionChoreography.DANCING -> cycle * 22f * scale
      else -> 0f
    }
    val liftL = maxOf(0f, cycle) * (if (motion == MotionChoreography.RUNNING) 30f else 18f) * scale
    val liftR = maxOf(0f, -cycle) * (if (motion == MotionChoreography.RUNNING) 30f else 18f) * scale

    // Left Leg (thigh + knee + shin)
    val lKneeX = -18f * scale + swingLeg * 0.55f
    val lKneeY = 100f * scale - liftL * 0.45f
    val lFootX = -20f * scale + swingLeg
    val lFootY = 146f * scale - liftL
    canvas.drawLine(-16f * scale, 54f * scale, lKneeX, lKneeY, limbPaint)
    canvas.drawLine(lKneeX, lKneeY, lFootX, lFootY, limbPaint)
    canvas.drawOval(RectF(lFootX - 18f * scale, lFootY - 7f * scale, lFootX + 20f * scale, lFootY + 11f * scale), shoePaint)

    // Right Leg
    val rKneeX = 18f * scale - swingLeg * 0.55f
    val rKneeY = 100f * scale - liftR * 0.45f
    val rFootX = 20f * scale - swingLeg
    val rFootY = 146f * scale - liftR
    canvas.drawLine(16f * scale, 54f * scale, rKneeX, rKneeY, limbPaint)
    canvas.drawLine(rKneeX, rKneeY, rFootX, rFootY, limbPaint)
    canvas.drawOval(RectF(rFootX - 18f * scale, rFootY - 7f * scale, rFootX + 20f * scale, rFootY + 11f * scale), shoePaint)

    // Torso / Jacket with subtle rim highlight
    val torsoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = LinearGradient(
        -40f * scale, -24f * scale, 40f * scale, 64f * scale,
        jacketColor,
        Color.rgb(15, 23, 42),
        Shader.TileMode.CLAMP,
      )
    }
    canvas.drawRoundRect(RectF(-38f * scale, -20f * scale, 38f * scale, 64f * scale), 20f * scale, 20f * scale, torsoPaint)

    // Articulated Shoulder -> Elbow -> Hand Gestures (driven by speaking / walking / running)
    val gestureBoost = if (isPrimarySpeaker) mouthOpen * 26f * scale else 6f * scale
    val armAmp = when (motion) {
      MotionChoreography.RUNNING -> 38f * scale
      MotionChoreography.WALKING -> 24f * scale
      MotionChoreography.DANCING -> 42f * scale
      else -> 12f * scale + gestureBoost
    }
    val lElbowX = -56f * scale - cosCycle * 8f * scale
    val lElbowY = 14f * scale + cycle * armAmp * 0.5f
    val lHandX = -68f * scale + lookDirX * 14f * scale
    val lHandY = -2f * scale - gestureBoost + cosCycle * armAmp
    val sleevePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = jacketColor
      strokeWidth = 13f * scale
      strokeCap = Paint.Cap.ROUND
    }
    canvas.drawLine(-34f * scale, -8f * scale, lElbowX, lElbowY, sleevePaint)
    canvas.drawLine(lElbowX, lElbowY, lHandX, lHandY, sleevePaint)
    canvas.drawCircle(lHandX, lHandY, 10f * scale, skinPaint)

    val rElbowX = 56f * scale + cosCycle * 8f * scale
    val rElbowY = 14f * scale - cycle * armAmp * 0.5f
    val rHandX = 68f * scale + lookDirX * 14f * scale
    val rHandY = -6f * scale - gestureBoost * 0.8f - cosCycle * armAmp
    canvas.drawLine(34f * scale, -8f * scale, rElbowX, rElbowY, sleevePaint)
    canvas.drawLine(rElbowX, rElbowY, rHandX, rHandY, sleevePaint)
    canvas.drawCircle(rHandX, rHandY, 10f * scale, skinPaint)

    // Neck + Head with natural head nod during dialogue
    canvas.drawRoundRect(RectF(-10f * scale, -34f * scale, 10f * scale, -14f * scale), 6f * scale, 6f * scale, skinPaint)
    val headNod = sin(globalTimeSec * 3.1).toFloat() * (3f + mouthOpen * 5f)
    canvas.save()
    canvas.translate(0f, -66f * scale)
    canvas.rotate(headNod)

    // Head base
    canvas.drawCircle(0f, 0f, 38f * scale, skinPaint)
    // Stylized sculpted hair arc
    val hairP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hairColor }
    val hairPath = Path().apply {
      addArc(RectF(-41f * scale, -44f * scale, 41f * scale, 22f * scale), 175f, 190f)
      close()
    }
    canvas.drawPath(hairPath, hairP)

    // Blinking & Gazing Eyes + Expressive Eyebrows
    val isBlinking = ((globalTimeSec * 0.9f) % 3.1f) > 2.95f
    val eyeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val pupilP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42) }
    val browP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE; strokeWidth = 3.2f * scale; strokeCap = Paint.Cap.ROUND; color = hairColor
    }
    val browLift = if (emotion.contains("Excit", true) || mouthOpen > 0.45f) -4f * scale else 0f
    canvas.drawLine(-22f * scale, -14f * scale + browLift, -7f * scale, -16f * scale + browLift, browP)
    canvas.drawLine(7f * scale, -16f * scale + browLift, 22f * scale, -14f * scale + browLift, browP)

    val gazeOffset = lookDirX * 3.2f * scale
    if (!isBlinking) {
      canvas.drawCircle(-14f * scale, -3f * scale, 7.5f * scale, eyeWhite)
      canvas.drawCircle(14f * scale, -3f * scale, 7.5f * scale, eyeWhite)
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

    // Lip-Synced Mouth (with Teeth & Tongue)
    val lipOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(127, 29, 29) }
    val tongue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 63, 94) }
    val mouthStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE; strokeWidth = 2.6f * scale; color = Color.rgb(69, 10, 10)
    }
    if (mouthOpen < 0.10f) {
      canvas.drawArc(RectF(-12f * scale, 9f * scale, 12f * scale, 20f * scale), 15f, 150f, false, mouthStroke)
    } else {
      val mH = (4f + mouthOpen * 16f) * scale
      val mBox = RectF(-13f * scale, 10f * scale, 13f * scale, 10f * scale + mH)
      canvas.drawOval(mBox, lipOuter)
      canvas.drawRoundRect(RectF(-9f * scale, mBox.top + 1.5f * scale, 9f * scale, mBox.top + 5f * scale), 2f * scale, 2f * scale, eyeWhite)
      if (mouthOpen > 0.25f) {
        canvas.drawOval(RectF(-7f * scale, mBox.centerY(), 7f * scale, mBox.bottom - 1.5f * scale), tongue)
      }
      canvas.drawOval(mBox, mouthStroke)
    }
    canvas.restore()

    // Compact character speaker nameplate pill if actively speaking
    if (isPrimarySpeaker && mouthOpen > 0.14f) {
      val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 10, 20, 46) }
      val tagTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 229, 255)
        textSize = 11f * scale
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
      }
      val rect = RectF(-46f * scale, -124f * scale, 46f * scale, -104f * scale)
      canvas.drawRoundRect(rect, 10f * scale, 10f * scale, tagBg)
      canvas.drawText(name.take(12), 0f, rect.centerY() + 4f * scale, tagTxt)
    }

    canvas.restore()
  }
}
