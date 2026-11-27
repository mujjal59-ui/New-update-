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

data class OrbTaskBadgeState(
  val visible: Boolean = false,
  val taskLabel: String? = null,
  val isReadingScreen: Boolean = false,
  val isSpeaking: Boolean = false,
  val overlayPermitted: Boolean = false,
) {
  val compactBadgeText: String?
    get() {
      val cleanTask =
        taskLabel
          ?.trim()
          ?.removePrefix("কাজ করছে:")
          ?.trim()
          ?.ifBlank { null }
      return when {
        isReadingScreen && cleanTask != null -> "👁️ স্ক্রিন পড়ছে • $cleanTask"
        isReadingScreen -> "👁️ স্ক্রিন পড়ছে..."
        cleanTask != null -> "⚙️ $cleanTask"
        else -> null
      }
    }
}

/**
 * Floating LXR--AI presence & live task/screen-reading orb displayed over ANY active screen/app:
 * - Hidden while LXR--AI listens quietly in background
 * - Appears immediately over any screen when Boss calls "LxR", taps Mic, OR LxR executes a task / reads the screen
 * - Shows BOTH the active task (`⚙️ ...`) AND live screen-reading indicator (`👁️ স্ক্রিন পড়ছে...`) beneath the rotating 1:1 globe
 * - Disappears automatically once the task & reply complete, while LXR--AI remains active 24/7 in background
 */
object LxrFloatingOverlayManager {
  private const val TAG = "LxrFloatingOverlay"
  const val OVERLAY_ALPHA = 0.50f
  const val ACTIVE_TASK_ALPHA = 0.86f

  private val mainHandler = Handler(Looper.getMainLooper())
  private var pendingHideRunnable: Runnable? = null
  private var idleFailsafeRunnable: Runnable? = null
  private val attachedContainers = LinkedHashSet<View>()
  @Volatile private var overlayGeneration: Long = 0L

  @Volatile private var windowManager: WindowManager? = null
  @Volatile private var rootContainer: android.widget.LinearLayout? = null
  @Volatile private var overlayView: LxrPlanetOrbitView? = null
  @Volatile private var taskBadgeView: android.widget.TextView? = null
  @Volatile private var layoutParams: WindowManager.LayoutParams? = null
  @Volatile private var workBannerView: android.widget.TextView? = null

  val orbBadgeState = kotlinx.coroutines.flow.MutableStateFlow(OrbTaskBadgeState())

  @Volatile var isShowing: Boolean = false
    private set
  @Volatile var isSpeakingNow: Boolean = false
    private set
  internal fun activeAttachedViewCount(): Int = attachedContainers.size
  @Volatile var onInterruptSpeakRequest: (() -> Unit)? = null

  @Volatile private var lastWakeGreetAtMs: Long = 0L

  private val wakePatterns =
    listOf(
      Regex("\\bl[\\s.]*x[\\s.]*r(?:\\s*(?:orb|অরব))?\\b", RegexOption.IGNORE_CASE),
      Regex("\\bel[\\s.]*x[\\s.]*r(?:\\s*(?:orb|অরব))?\\b", RegexOption.IGNORE_CASE),
      Regex("\\b(?:alexander|alexar|lexar|elxr|elaxar)(?:\\s*(?:orb|অরব))?\\b", RegexOption.IGNORE_CASE),
      Regex("এল\\s*এক্স\\s*আর(?:\\s*অরব)?"),
      Regex("এলএক্সআর(?:\\s*অরব)?"),
      Regex("অ্যা?লেক্সা(?:র)?(?:\\s*অরব)?"),
      Regex("এলেক্সার(?:\\s*অরব)?"),
      Regex("লক্সার(?:\\s*অরব)?"),
      Regex("(?:অরব\\s*(?:আসো|দেখাও|অন\\s*করো|চালু\\s*করো)|vashoman\\s*orb)", RegexOption.IGNORE_CASE),
    )

  /** Returns true whenever Boss calls "LxR", "L.x.R", or "LxR অরব" in Bengali or English. */
  fun containsWakeWord(transcript: String): Boolean {
    val clean = transcript.trim()
    if (clean.isEmpty()) return false
    return wakePatterns.any { it.containsMatchIn(clean) }
  }

  const val ORB_WAKE_GREETING_BN = "বস কেনোও ডেকেছ বলো কি করতে হবে"

  /**
   * Returns true if Gemini's own output transcription confirms Boss just woke LxR up.
   */
  fun isAiWakeGreeting(reply: String): Boolean {
    val clean = reply.trim()
    if (clean.isEmpty()) return false
    return clean.contains("কেনোও ডেকেছ") ||
      clean.contains("কেন ডেকেছো") ||
      clean.contains("কেন ডেকেছ") ||
      clean.contains("কী করতে হবে") ||
      clean.contains("কেনো ডেকেছেন") ||
      (clean.contains("বস") && clean.contains("কি করতে হবে"))
  }

  /**
   * Returns true if the transcript is essentially just calling "LxR" / "LxR অরব" to summon the assistant
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
        .replace(Regex("\\b(?:hey|hi|hello|boss|ok|okay|orb|come|show)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("(?:হেই|হ্যালো|ওই|শোন|শোনো|এই|বস|অরব|আসো|দেখাও|বলো)"), " ")
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
    idleFailsafeRunnable?.let { mainHandler.removeCallbacks(it) }
    idleFailsafeRunnable = null
  }

  fun scheduleAutoHide(context: Context, delayMs: Long = 1200L) {
    val appCtx = context.applicationContext
    cancelPendingHide()
    val r = Runnable {
      pendingHideRunnable = null
      hide(appCtx)
    }
    pendingHideRunnable = r
    mainHandler.postDelayed(r, delayMs.coerceAtLeast(350L))
  }

  fun scheduleIdleFailsafeHide(context: Context, delayMs: Long = 4500L) {
    val appCtx = context.applicationContext
    idleFailsafeRunnable?.let { mainHandler.removeCallbacks(it) }
    val r = Runnable {
      idleFailsafeRunnable = null
      val cur = orbBadgeState.value
      if (!cur.isReadingScreen && !cur.isSpeaking) {
        completeTaskAndScheduleHide(appCtx, hideDelayMs = 450L, keepShowingForWake = false)
      }
    }
    idleFailsafeRunnable = r
    mainHandler.postDelayed(r, delayMs.coerceAtLeast(1200L))
  }

  fun showForWakeOrMic(context: Context): Boolean {
    cancelPendingHide()
    if (!Store.floatingOrbEnabled(context)) return false
    val cur = orbBadgeState.value
    orbBadgeState.value =
      cur.copy(
        visible = true,
        overlayPermitted = Settings.canDrawOverlays(context.applicationContext),
      )
    val shown = show(context, openPermissionIfMissing = false, forceShow = false)
    if (shown && cur.taskLabel == null && !cur.isReadingScreen) {
      scheduleIdleFailsafeHide(context, delayMs = 4200L)
    }
    return shown
  }

  fun showForTask(
    context: Context,
    taskLabel: String?,
    isReadingScreen: Boolean = false,
  ): Boolean {
    val appCtx = context.applicationContext
    cancelPendingHide()
    val cleanTask = taskLabel?.trim()?.ifBlank { null }
    com.example.data.RiyaState.activeWorkLabel.value = cleanTask
    val enabled = Store.floatingOrbEnabled(appCtx)
    val canOverlay = Settings.canDrawOverlays(appCtx)
    orbBadgeState.value =
      orbBadgeState.value.copy(
        visible = enabled,
        taskLabel = cleanTask,
        isReadingScreen = isReadingScreen,
        isSpeaking = isSpeakingNow,
        overlayPermitted = canOverlay,
      )
    if (!enabled) return false
    val shown = show(appCtx, openPermissionIfMissing = false, forceShow = false)
    refreshBadgeUi(appCtx)
    return shown
  }

  fun setScreenReading(
    context: Context,
    reading: Boolean,
    taskLabelOverride: String? = null,
  ) {
    val appCtx = context.applicationContext
    val cur = orbBadgeState.value
    val nextTask = taskLabelOverride?.trim()?.ifBlank { null } ?: cur.taskLabel
    val enabled = Store.floatingOrbEnabled(appCtx)
    val canOverlay = Settings.canDrawOverlays(appCtx)
    orbBadgeState.value =
      cur.copy(
        visible = if (reading && enabled) true else cur.visible,
        taskLabel = nextTask,
        isReadingScreen = reading,
        isSpeaking = isSpeakingNow,
        overlayPermitted = canOverlay,
      )
    if (reading && enabled) {
      cancelPendingHide()
      if (!isShowing) {
        show(appCtx, openPermissionIfMissing = false, forceShow = false)
      }
    }
    refreshBadgeUi(appCtx)
  }

  fun completeTaskAndScheduleHide(
    context: Context,
    hideDelayMs: Long = 1000L,
    keepShowingForWake: Boolean = false,
  ) {
    val appCtx = context.applicationContext
    com.example.data.RiyaState.activeWorkLabel.value = null
    orbBadgeState.value =
      orbBadgeState.value.copy(
        taskLabel = null,
        isReadingScreen = false,
        isSpeaking = isSpeakingNow,
      )
    refreshBadgeUi(appCtx)
    val targetDelay = if (keepShowingForWake) maxOf(hideDelayMs, 4000L) else hideDelayMs
    scheduleAutoHide(appCtx, targetDelay)
  }

  private fun buildBadgeDrawable(density: Float, isReading: Boolean): android.graphics.drawable.GradientDrawable {
    return android.graphics.drawable.GradientDrawable().apply {
      shape = android.graphics.drawable.GradientDrawable.RECTANGLE
      cornerRadius = 12f * density
      setColor(Color.parseColor("#D908142B"))
      setStroke(
        (1.4f * density).toInt().coerceAtLeast(2),
        if (isReading) Color.parseColor("#00E5FF") else Color.parseColor("#7C4DFF"),
      )
    }
  }

  private fun refreshBadgeUi(appCtx: Context) {
    mainHandler.post {
      val state = orbBadgeState.value
      val badgeText = state.compactBadgeText
      val orb = overlayView
      val badge = taskBadgeView
      val container = rootContainer
      if (orb != null) {
        orb.alpha = if (badgeText != null) ACTIVE_TASK_ALPHA else OVERLAY_ALPHA
        orb.setScreenReadingPulse(state.isReadingScreen)
      }
      if (badge != null) {
        val density = appCtx.resources.displayMetrics.density
        if (badgeText != null) {
          badge.text = badgeText
          badge.setTextColor(
            if (state.isReadingScreen) Color.parseColor("#00E5FF") else Color.WHITE
          )
          badge.background = buildBadgeDrawable(density, state.isReadingScreen)
          badge.visibility = View.VISIBLE
        } else {
          badge.visibility = View.GONE
        }
      }
      val wm = windowManager
      val params = layoutParams
      if (wm != null && container != null && container.isAttachedToWindow && params != null) {
        try {
          wm.updateViewLayout(container, params)
        } catch (_: Throwable) {}
      }
    }
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

    val reqGen = ++overlayGeneration
    mainHandler.post {
      try {
        if (reqGen != overlayGeneration) return@post
        if (!forceShow && !Store.floatingOrbEnabled(appCtx)) return@post
        val wm = windowManager ?: (appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        val existingContainer = rootContainer
        val existingOrb = overlayView

        // Remove any orphaned duplicate containers if present
        if (attachedContainers.isNotEmpty()) {
          val stale = attachedContainers.filter { it !== existingContainer }
          for (orphan in stale) {
            runCatching { wm.removeViewImmediate(orphan) }
            attachedContainers.remove(orphan)
          }
        }

        // Reuse existing single container EVEN if WindowManager hasn't completed attach traversal yet!
        if (existingContainer != null && existingOrb != null) {
          existingOrb.alpha =
            if (orbBadgeState.value.compactBadgeText != null) ACTIVE_TASK_ALPHA else OVERLAY_ALPHA
          existingOrb.pulseWake()
          isShowing = true
          refreshBadgeUi(appCtx)
          return@post
        }

        val dm = appCtx.resources.displayMetrics
        val orbSizePx = (82f * dm.density).toInt().coerceAtLeast(164)
        val maxPillWidthPx = (186f * dm.density).toInt().coerceAtLeast(320)
        val type =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
          } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
          }

        val params =
          WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
              WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
              WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
          ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (14f * dm.density).toInt()
            y = (104f * dm.density).toInt()
          }

        val container =
          android.widget.LinearLayout(appCtx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
          }

        val orb =
          LxrPlanetOrbitView(appCtx).apply {
            alpha =
              if (orbBadgeState.value.compactBadgeText != null) ACTIVE_TASK_ALPHA else OVERLAY_ALPHA
            layoutParams =
              android.widget.LinearLayout.LayoutParams(orbSizePx, orbSizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
              }
          }

        val padH = (9f * dm.density).toInt()
        val padV = (4f * dm.density).toInt()
        val badgeTopMarginPx = (3f * dm.density).toInt()
        val badge =
          android.widget.TextView(appCtx).apply {
            textSize = 10.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxWidth = maxPillWidthPx
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(padH, padV, padH, padV)
            background = buildBadgeDrawable(dm.density, orbBadgeState.value.isReadingScreen)
            val initText = orbBadgeState.value.compactBadgeText
            if (initText != null) {
              text = initText
              visibility = View.VISIBLE
            } else {
              visibility = View.GONE
            }
            layoutParams =
              android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
              ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = badgeTopMarginPx
              }
          }

        container.addView(orb)
        container.addView(badge)
        orb.attachWindowDragListener(container, wm, params, appCtx)

        wm.addView(container, params)
        attachedContainers.add(container)
        windowManager = wm
        rootContainer = container
        overlayView = orb
        taskBadgeView = badge
        layoutParams = params
        isShowing = true
        refreshBadgeUi(appCtx)
      } catch (t: Throwable) {
        Log.w(TAG, "Unable to attach LxR floating overlay safely", t)
      }
    }
    return true
  }

  fun setSpeakingBoost(speaking: Boolean) {
    isSpeakingNow = speaking
    orbBadgeState.value = orbBadgeState.value.copy(isSpeaking = speaking)
    mainHandler.post {
      overlayView?.setSpeakingBoost(speaking)
    }
  }

  /**
   * Active task label is rendered inside the compact in-app status pill (`home_status_pill`)
   * AND on the floating LxR Orb task pill while running tasks over other apps (without spawning a wide top-bar popup).
   */
  fun updateActiveWorkBanner(context: Context, label: String?) {
    val appCtx = context.applicationContext
    val clean = label?.trim()?.ifBlank { null }
    com.example.data.RiyaState.activeWorkLabel.value = clean
    orbBadgeState.value =
      orbBadgeState.value.copy(
        taskLabel = clean,
        isReadingScreen = if (clean == null) false else orbBadgeState.value.isReadingScreen,
      )
    mainHandler.post {
      try {
        workBannerView?.let { v ->
          val wm =
            windowManager
              ?: (appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
          if (v.isAttachedToWindow) wm?.removeViewImmediate(v)
        }
      } catch (_: Throwable) {
      } finally {
        workBannerView = null
      }
    }
    refreshBadgeUi(appCtx)
  }

  fun hide(context: Context? = null) {
    overlayGeneration++
    cancelPendingHide()
    isShowing = false
    com.example.data.RiyaState.activeWorkLabel.value = null
    orbBadgeState.value = OrbTaskBadgeState(visible = false)
    mainHandler.post {
      try {
        val container = rootContainer
        val view = overlayView
        val banner = workBannerView
        val wm =
          windowManager
            ?: (context?.applicationContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        view?.stopAnimation()
        val toRemove = LinkedHashSet<View>(attachedContainers)
        if (container != null) toRemove.add(container)
        for (v in toRemove) {
          runCatching { wm?.removeViewImmediate(v) }
        }
        attachedContainers.clear()
        if (container == null && view != null && view.isAttachedToWindow) {
          runCatching { wm?.removeViewImmediate(view) }
        }
        if (banner != null && banner.isAttachedToWindow) {
          runCatching { wm?.removeViewImmediate(banner) }
        }
      } catch (_: Throwable) {
      } finally {
        attachedContainers.clear()
        rootContainer = null
        overlayView = null
        taskBadgeView = null
        layoutParams = null
        workBannerView = null
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
    private var screenReadingPulse = false
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

    fun setScreenReadingPulse(reading: Boolean) {
      if (screenReadingPulse != reading) {
        screenReadingPulse = reading
        if (reading) pulseWake() else invalidate()
      }
    }

    override fun onAttachedToWindow() {
      super.onAttachedToWindow()
      alpha = if (orbBadgeState.value.compactBadgeText != null) ACTIVE_TASK_ALPHA else OVERLAY_ALPHA
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
            val step =
              when {
                screenReadingPulse -> 1.95f
                speakingBoost -> 1.55f
                else -> 1f
              }
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

      ringPaint.color = Color.parseColor(if (screenReadingPulse) "#00E5FF" else "#00E5FF")
      ringPaint.alpha = if (screenReadingPulse) 250 else 195
      ringPaint.strokeWidth = (if (screenReadingPulse) 2.5f else 1.6f) * density
      canvas.drawCircle(cx, cy, planetRadius, ringPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachWindowDragListener(
      container: View,
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

      container.setOnTouchListener { _, ev ->
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
                wm.updateViewLayout(container, params)
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
              } else if (isSpeakingNow && onInterruptSpeakRequest != null) {
                onInterruptSpeakRequest?.invoke()
                pulseWake()
                Toast.makeText(
                  appCtx,
                  "শুনছি বস, বলো...",
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
