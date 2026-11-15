package com.example.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Toast
import com.example.MainActivity
import com.example.R
import com.example.data.Store
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Floating 50%-opacity LXR--AI wake presence orb displayed directly over ANY active screen/app:
 * - Hidden while LXR--AI listens quietly in background
 * - Appears immediately on top of ANY screen when Boss calls "LxR" or taps the Mic button
 * - Renders the inner Earth/planet avatar (`R.drawable.lxr_globe_avatar`) in a strict 1:1 circle
 *   surrounded by 5 glowing orbiting colored satellites at exact 50% opacity (`alpha = 0.50f`)
 * - Disappears automatically once the task & reply complete, while LXR--AI stays active in background
 */
object LxrFloatingOverlayManager {
  private const val TAG = "LxrFloatingOverlay"
  const val OVERLAY_ALPHA = 0.50f

  private val mainHandler = Handler(Looper.getMainLooper())
  private var pendingHideRunnable: Runnable? = null

  @Volatile private var windowManager: WindowManager? = null
  @Volatile private var overlayView: LxrPlanetOrbitView? = null
  @Volatile private var layoutParams: WindowManager.LayoutParams? = null
  @Volatile var isShowing: Boolean = false
    private set

  @Volatile private var lastWakeGreetAtMs: Long = 0L

  private val wakePatterns =
    listOf(
      Regex("\\bl[\\s.]*x[\\s.]*r\\b", RegexOption.IGNORE_CASE),
      Regex("\\bel[\\s.]*x[\\s.]*r\\b", RegexOption.IGNORE_CASE),
      Regex("\\b(?:alexander|alexar|lexar|elxr)\\b", RegexOption.IGNORE_CASE),
      Regex("এল\\s*এক্স\\s*আর"),
      Regex("এলএক্সআর"),
      Regex("এলেক্সার"),
      Regex("এলএক্সার"),
    )

  /** Returns true whenever Boss calls "LxR" / "L.x.R" in Bengali or English. */
  fun containsWakeWord(transcript: String): Boolean {
    val clean = transcript.trim()
    if (clean.isEmpty()) return false
    return wakePatterns.any { it.containsMatchIn(clean) }
  }

  /**
   * Returns true if the transcript is essentially just calling "LxR" to summon the assistant
   * (without a separate follow-up command attached yet).
   */
  fun isPureWakeCall(transcript: String): Boolean {
    val clean = transcript.trim()
    if (!containsWakeWord(clean)) return false
    var stripped = clean
    wakePatterns.forEach { pat -> stripped = stripped.replace(pat, " ") }
    stripped =
      stripped
        .replace(Regex("[.,!?❤️♥।\\s]+"), " ")
        .replace(Regex("\\b(?:hey|hi|hello|boss|ok|okay)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("(?:হেই|হ্যালো|ওই|শোন|শোনো|এই|বস)"), " ")
        .trim()
    return stripped.length <= 3
  }

  fun canTriggerWakeGreetingNow(): Boolean {
    val now = System.currentTimeMillis()
    if (now - lastWakeGreetAtMs < 4500L) return false
    lastWakeGreetAtMs = now
    return true
  }

  /**
   * Computes an exact 1:1 square source crop [Rect] centered on the non-transparent planet pixels,
   * eliminating horizontal/vertical egg-stretching when rendered inside a circular clip.
   */
  fun computeCenteredSquareCrop(
    imgWidth: Int,
    imgHeight: Int,
    minX: Int,
    minY: Int,
    maxX: Int,
    maxY: Int,
  ): Rect {
    if (imgWidth <= 0 || imgHeight <= 0) return Rect(0, 0, 1, 1)
    if (maxX <= minX || maxY <= minY) {
      val side = minOf(imgWidth, imgHeight)
      val left = (imgWidth - side) / 2
      val top = (imgHeight - side) / 2
      return Rect(left, top, left + side, top + side)
    }
    val opaqueW = maxX - minX + 1
    val opaqueH = maxY - minY + 1
    val side = maxOf(opaqueW, opaqueH).coerceAtMost(minOf(imgWidth, imgHeight))
    val cx = (minX + maxX) / 2
    val cy = (minY + maxY) / 2
    val left = (cx - side / 2).coerceIn(0, (imgWidth - side).coerceAtLeast(0))
    val top = (cy - side / 2).coerceIn(0, (imgHeight - side).coerceAtLeast(0))
    return Rect(left, top, left + side, top + side)
  }

  fun cancelPendingHide() {
    pendingHideRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingHideRunnable = null
  }

  fun scheduleAutoHide(context: Context, delayMs: Long = 1300L) {
    val appCtx = context.applicationContext
    cancelPendingHide()
    val r = Runnable {
      pendingHideRunnable = null
      hide(appCtx)
    }
    pendingHideRunnable = r
    mainHandler.postDelayed(r, delayMs.coerceAtLeast(400L))
  }

  fun showForWakeOrMic(context: Context): Boolean {
    cancelPendingHide()
    if (!Store.floatingOrbEnabled(context)) return false
    return show(context, openPermissionIfMissing = false, forceShow = false)
  }

  fun show(
    context: Context,
    openPermissionIfMissing: Boolean = false,
    forceShow: Boolean = false,
  ): Boolean {
    val appCtx = context.applicationContext
    if (!forceShow && !Store.floatingOrbEnabled(appCtx)) {
      return false
    }
    cancelPendingHide()

    if (!Settings.canDrawOverlays(appCtx)) {
      if (openPermissionIfMissing) {
        try {
          appCtx.startActivity(
            Intent(
              Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
              android.net.Uri.parse("package:${appCtx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          )
        } catch (_: Throwable) {}
      }
      return false
    }

    mainHandler.post {
      try {
        if (!forceShow && !Store.floatingOrbEnabled(appCtx)) return@post
        val existing = overlayView
        if (existing != null && existing.isAttachedToWindow) {
          existing.alpha = OVERLAY_ALPHA
          existing.pulseWake()
          isShowing = true
          return@post
        }

        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = appCtx.resources.displayMetrics
        // Strictly square container so width == height at any screen density
        val viewSizePx = (82f * dm.density).toInt().coerceAtLeast(164)
        val type =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
          } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
          }

        val params =
          WindowManager.LayoutParams(
            viewSizePx,
            viewSizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
              WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
              WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
          ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16f * dm.density).toInt()
            y = (108f * dm.density).toInt()
          }

        val view =
          LxrPlanetOrbitView(appCtx).apply {
            alpha = OVERLAY_ALPHA
            attachWindowDragListener(wm, params, appCtx)
          }

        wm.addView(view, params)
        windowManager = wm
        overlayView = view
        layoutParams = params
        isShowing = true
      } catch (t: Throwable) {
        Log.w(TAG, "Unable to attach LxR floating overlay safely", t)
      }
    }
    return true
  }

  fun setSpeakingBoost(speaking: Boolean) {
    mainHandler.post {
      overlayView?.setSpeakingBoost(speaking)
    }
  }

  fun hide(context: Context? = null) {
    cancelPendingHide()
    mainHandler.post {
      try {
        val view = overlayView
        val wm =
          windowManager
            ?: (context?.applicationContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        if (view != null) {
          view.stopAnimation()
          if (view.isAttachedToWindow) {
            wm?.removeViewImmediate(view)
          }
        }
      } catch (_: Throwable) {
      } finally {
        overlayView = null
        layoutParams = null
        isShowing = false
      }
    }
  }

  private class LxrPlanetOrbitView(context: Context) : View(context) {
    private val orbitColors =
      intArrayOf(
        Color.parseColor("#00E5FF"), // Cyan
        Color.parseColor("#FF1744"), // Crimson Red
        Color.parseColor("#2979FF"), // Electric Blue
        Color.parseColor("#00E676"), // Emerald Green
        Color.parseColor("#FFB300"), // Solar Gold
      )

    private val ringPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
      }
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val arcRect = RectF()

    private val planetBitmap: Bitmap? = loadSquarePlanetBitmap(context)
    private var phaseDeg = 0f
    private var speakingBoost = false
    private var pulseScale = 1f
    private var animator: ValueAnimator? = null

    private fun loadSquarePlanetBitmap(ctx: Context): Bitmap? {
      return try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val decoded = BitmapFactory.decodeResource(ctx.resources, R.drawable.lxr_globe_avatar, opts)
          ?: return null
        val w = decoded.width
        val h = decoded.height
        val pixels = IntArray(w * h)
        decoded.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
          val rowOff = y * w
          for (x in 0 until w) {
            val a = (pixels[rowOff + x] ushr 24) and 0xFF
            if (a > 16) {
              if (x < minX) minX = x
              if (x > maxX) maxX = x
              if (y < minY) minY = y
              if (y > maxY) maxY = y
            }
          }
        }
        val crop = computeCenteredSquareCrop(w, h, minX, minY, maxX, maxY)
        val cropped = Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width(), crop.height())
        if (cropped !== decoded) {
          decoded.recycle()
        }
        cropped
      } catch (_: Throwable) {
        null
      }
    }

    fun pulseWake() {
      pulseScale = 1.12f
      invalidate()
    }

    fun setSpeakingBoost(speaking: Boolean) {
      speakingBoost = speaking
    }

    override fun onAttachedToWindow() {
      super.onAttachedToWindow()
      alpha = OVERLAY_ALPHA
      startAnimation()
    }

    override fun onDetachedFromWindow() {
      stopAnimation()
      super.onDetachedFromWindow()
    }

    private fun startAnimation() {
      if (animator?.isRunning == true) return
      animator =
        ValueAnimator.ofFloat(0f, 360f).apply {
          duration = 4600L
          repeatCount = ValueAnimator.INFINITE
          interpolator = LinearInterpolator()
          addUpdateListener { anim ->
            val step = if (speakingBoost) 1.55f else 1f
            phaseDeg = ((anim.animatedValue as Float) * step) % 360f
            if (pulseScale > 1.001f) {
              pulseScale = 1f + (pulseScale - 1f) * 0.88f
            } else {
              pulseScale = 1f
            }
            invalidate()
          }
          start()
        }
    }

    fun stopAnimation() {
      try {
        animator?.cancel()
      } catch (_: Throwable) {}
      animator = null
    }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      val w = width.toFloat()
      val h = height.toFloat()
      if (w <= 0f || h <= 0f) return

      val cx = w * 0.5f
      val cy = h * 0.5f
      val minSide = minOf(w, h)
      val planetRadius = minSide * 0.255f * pulseScale
      val density = resources.displayMetrics.density

      // 1. Background 5-color orbital rings + glowing orbiting colored spheres
      for (i in orbitColors.indices) {
        val color = orbitColors[i]
        val orbitR = planetRadius + ((i + 1) * 3.1f + 1.8f) * density
        if (orbitR + 4f * density > minSide * 0.49f) continue

        ringPaint.color = color
        ringPaint.alpha = 125
        ringPaint.strokeWidth = 1.5f * density
        arcRect.set(cx - orbitR, cy - orbitR, cx + orbitR, cy + orbitR)

        val sweepStart = (phaseDeg * (if (i % 2 == 0) 1f else -1.25f) + i * 72f) % 360f
        canvas.drawArc(arcRect, sweepStart, 130f, false, ringPaint)

        val angleRad = (sweepStart + 130f) * (PI / 180.0)
        val dotX = cx + (cos(angleRad) * orbitR).toFloat()
        val dotY = cy + (sin(angleRad) * orbitR).toFloat()

        glowPaint.color = color
        glowPaint.alpha = 95
        canvas.drawCircle(dotX, dotY, 4.4f * density, glowPaint)

        orbPaint.color = color
        orbPaint.alpha = 255
        canvas.drawCircle(dotX, dotY, 2.4f * density, orbPaint)
      }

      // 2. Central rotating 1:1 circular Earth/planet image (`lxr_globe_avatar`)
      canvas.save()
      clipPath.reset()
      clipPath.addCircle(cx, cy, planetRadius, Path.Direction.CW)
      canvas.clipPath(clipPath)
      canvas.rotate(phaseDeg, cx, cy)

      val bmp = planetBitmap
      if (bmp != null && !bmp.isRecycled) {
        // Since bmp is already tightly cropped 1:1 square around the globe, 1.03x fills circle crisply with zero oval squish
        val drawR = planetRadius * 1.03f
        arcRect.set(cx - drawR, cy - drawR, cx + drawR, cy + drawR)
        canvas.drawBitmap(bmp, null, arcRect, planetPaint)
      } else {
        orbPaint.color = Color.parseColor("#FF5722")
        canvas.drawCircle(cx, cy, planetRadius, orbPaint)
      }
      canvas.restore()

      ringPaint.color = Color.parseColor("#00E5FF")
      ringPaint.alpha = 195
      ringPaint.strokeWidth = 1.6f * density
      canvas.drawCircle(cx, cy, planetRadius, ringPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachWindowDragListener(
      wm: WindowManager,
      params: WindowManager.LayoutParams,
      appCtx: Context,
    ) {
      var startX = 0
      var startY = 0
      var downRawX = 0f
      var downRawY = 0f
      var moved = false
      var downAt = 0L

      setOnTouchListener { _, ev ->
        when (ev.action) {
          MotionEvent.ACTION_DOWN -> {
            startX = params.x
            startY = params.y
            downRawX = ev.rawX
            downRawY = ev.rawY
            moved = false
            downAt = System.currentTimeMillis()
            true
          }
          MotionEvent.ACTION_MOVE -> {
            val dx = ev.rawX - downRawX
            val dy = ev.rawY - downRawY
            if (abs(dx) > 8f || abs(dy) > 8f) moved = true
            if (moved) {
              params.x = (startX - dx.toInt()).coerceAtLeast(0)
              params.y = (startY + dy.toInt()).coerceAtLeast(0)
              try {
                wm.updateViewLayout(this, params)
              } catch (_: Throwable) {}
            }
            true
          }
          MotionEvent.ACTION_UP -> {
            val heldMs = System.currentTimeMillis() - downAt
            if (!moved) {
              if (heldMs >= 650L) {
                hide(appCtx)
                Toast.makeText(
                  appCtx,
                  "LxR অরব লুকানো হয়েছে — আবার 'LxR' বললেই দেখাবে",
                  Toast.LENGTH_SHORT,
                ).show()
              } else {
                try {
                  appCtx.startActivity(
                    Intent(appCtx, MainActivity::class.java)
                      .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                          Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                          Intent.FLAG_ACTIVITY_SINGLE_TOP
                      )
                  )
                } catch (_: Throwable) {}
              }
            }
            true
          }
          else -> false
        }
      }
    }
  }
}
