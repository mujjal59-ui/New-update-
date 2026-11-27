package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.FaceDetector
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import com.example.data.RiyaState
import com.example.data.Store
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class VisionFrameSnapshot(
  val jpegBytes: ByteArray?,
  val capturedAtMs: Long,
  val isFrontCamera: Boolean,
  val faceDetected: Boolean,
  val faceCount: Int,
  val averageLuma: Int,
  val sourceMode: String,
)

data class VisionVerificationResult(
  val hasFreshFrame: Boolean,
  val isReadableBrightness: Boolean,
  val isFaceVisibleNow: Boolean,
  val ageMs: Long,
  val averageLuma: Int,
  val statusReasonBn: String,
)

/**
 * Anti-hallucination guard & WebSocket media payload builder for Gemini Live Vision.
 */
object LxrCameraVisionGuard {
  const val DEFAULT_MAX_FRAME_AGE_MS = 4500L
  const val MIN_READABLE_LUMA = 10

  fun evaluateFrameUsability(
    snapshot: VisionFrameSnapshot?,
    nowMs: Long = System.currentTimeMillis(),
    maxAgeMs: Long = DEFAULT_MAX_FRAME_AGE_MS,
    minReadableLuma: Int = MIN_READABLE_LUMA,
  ): VisionVerificationResult {
    if (snapshot == null || snapshot.jpegBytes == null || snapshot.jpegBytes.isEmpty()) {
      return VisionVerificationResult(
        hasFreshFrame = false,
        isReadableBrightness = false,
        isFaceVisibleNow = false,
        ageMs = Long.MAX_VALUE,
        averageLuma = 0,
        statusReasonBn = "ক্যামেরা থেকে এখনো কোনো লাইভ ফ্রেম পাওয়া যায়নি",
      )
    }
    val ageMs = (nowMs - snapshot.capturedAtMs).coerceAtLeast(0L)
    if (ageMs > maxAgeMs) {
      return VisionVerificationResult(
        hasFreshFrame = false,
        isReadableBrightness = snapshot.averageLuma >= minReadableLuma,
        isFaceVisibleNow = false,
        ageMs = ageMs,
        averageLuma = snapshot.averageLuma,
        statusReasonBn = "ক্যামেরার সর্বশেষ ফ্রেমটি পুরনো (${ageMs}ms আগে)",
      )
    }
    if (snapshot.averageLuma < minReadableLuma) {
      return VisionVerificationResult(
        hasFreshFrame = true,
        isReadableBrightness = false,
        isFaceVisibleNow = false,
        ageMs = ageMs,
        averageLuma = snapshot.averageLuma,
        statusReasonBn =
          "ক্যামেরার লেন্স ঢাকা বা ঘন অন্ধকার দেখাচ্ছে (আলো ${snapshot.averageLuma}/255)",
      )
    }
    val lensLabel = if (snapshot.isFrontCamera) "ফ্রন্ট" else "ব্যাক"
    return VisionVerificationResult(
      hasFreshFrame = true,
      isReadableBrightness = true,
      isFaceVisibleNow = snapshot.faceDetected,
      ageMs = ageMs,
      averageLuma = snapshot.averageLuma,
      statusReasonBn =
        "$lensLabel ক্যামেরায় তাজা ফ্রেম পাওয়া গেছে (আলো ${snapshot.averageLuma}/255, মুখ: ${if (snapshot.faceDetected) "শনাক্ত (${snapshot.faceCount})" else "দেখা যাচ্ছে না"})",
    )
  }

  fun buildHonestVisionPrompt(
    snapshot: VisionFrameSnapshot?,
    userQuestion: String?,
    nowMs: Long = System.currentTimeMillis(),
  ): String {
    val verification = evaluateFrameUsability(snapshot, nowMs = nowMs)
    if (!verification.hasFreshFrame) {
      return "[কঠোর সত্যতা নির্দেশ — ছবি নেই]: ক্যামেরা থেকে এখনো কোনো তাজা ছবি পাওয়া যায়নি (${verification.statusReasonBn})। বসকে পরিষ্কার ও বিনয়ের সাথে সত্য বলো যে তুমি এই মুহূর্তে ক্যামেরার লাইভ ফ্রেম দেখতে পাচ্ছো না — কল্পনা করে কোনো চেহারা, পোশাক বা বস্তু বানিয়ে বলবে না।"
    }
    val lensLabel = if (snapshot?.isFrontCamera == true) "ফ্রন্ট (সামনের)" else "ব্যাক (পিছনের)"
    val qPart = userQuestion?.trim()?.takeIf { it.isNotBlank() }?.let { "বসের প্রশ্ন: \"$it\"।" }.orEmpty()
    if (!verification.isReadableBrightness) {
      return "[সিস্টেম $lensLabel ক্যামেরা — অন্ধকার ফ্রেম]: সাথে দেওয়া image/jpeg ছবিটি খুবই অন্ধকার বা ঢাকা (${verification.averageLuma}/255)। $qPart ছবিতে পরিষ্কার কিছু দেখা না গেলে অনুমান করে কিছু বানাবে না — সত্য বলো যে আলো খুব কম বা ক্যামেরা ঢাকা আছে।"
    }
    val faceNote =
      if (snapshot?.isFrontCamera == true) {
        if (verification.isFaceVisibleNow) {
          "অন-ডিভাইস ডিটেক্টর অনুযায়ী ফ্রেমে মুখ শনাক্ত হয়েছে (${snapshot.faceCount} জন)।"
        } else {
          "ফ্রেমে এখনো স্পষ্ট মুখ শনাক্ত হয়নি — তাই ছবিতে মানুষ দেখা না গেলে মিথ্যা বলবে না যে বসকে দেখছো।"
        }
      } else {
        "ব্যাক ক্যামেরার দৃশ্য পরীক্ষা করো।"
      }
    return "[সিস্টেম $lensLabel ক্যামেরা আসল লাইভ ছবি (${snapshot?.sourceMode})]: $faceNote $qPart সাথে সংযুক্ত image/jpeg ছবিটি সরাসরি দেখেই সত্য বর্ণনা দাও। ছবিতে যা স্পষ্ট দেখা যাচ্ছে শুধু তাই বলো — না দেখা কোনো পোশাক, রঙ বা মানুষ বানিয়ে বলবে না।"
  }

  fun buildRealtimeVideoChunkJson(modelId: String, jpeg: ByteArray): String {
    val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
    val clean = modelId.lowercase()
    return if (clean.contains("2.5") || clean.contains("2.0")) {
      """{"realtimeInput":{"mediaChunks":[{"mimeType":"image/jpeg","data":"$b64"}]}}"""
    } else {
      """{"realtimeInput":{"video":{"data":"$b64","mimeType":"image/jpeg"}}}"""
    }
  }
}

/**
 * Hybrid Foreground + Background Vision Camera Engine:
 * - Foreground UI preview (LiveCamera) pushes analyzed CameraX frames here.
 * - Whenever app goes to background while [RiyaState.visionCameraActive] is true, seamlessly opens
 *   Camera2 + ImageReader inside RiyaService (camera FGS) and streams real upright JPEG frames.
 */
object LxrCameraVisionEngine {
  private const val TAG = "LxrCameraVision"

  private val stateLock = Any()
  @Volatile private var uiPreviewActive: Boolean = false
  @Volatile private var latestSnapshot: VisionFrameSnapshot? = null
  @Volatile private var lastErrorBn: String? = null
  @Volatile private var activeBackgroundFrontLens: Boolean? = null

  private var camThread: HandlerThread? = null
  private var camHandler: Handler? = null
  private var cameraDevice: CameraDevice? = null
  private var captureSession: CameraCaptureSession? = null
  private var imageReader: ImageReader? = null
  private val opening = AtomicBoolean(false)
  @Volatile private var lastBgFrameSentMs: Long = 0L
  @Volatile private var frameWaitLatch: CountDownLatch? = null

  fun hasCameraPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED

  fun latestSnapshot(): VisionFrameSnapshot? = latestSnapshot

  fun lastErrorMessageBn(): String? = lastErrorBn

  fun setUiPreviewActive(active: Boolean) {
    uiPreviewActive = active
    if (active) {
      stopBackgroundCamera()
    }
  }

  fun isUiPreviewActive(): Boolean = uiPreviewActive

  fun computeAverageLuma(bmp: Bitmap): Int {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return 0
    val stepX = (w / 16).coerceAtLeast(1)
    val stepY = (h / 16).coerceAtLeast(1)
    var sum = 0L
    var count = 0L
    var y = 0
    while (y < h) {
      var x = 0
      while (x < w) {
        val px = bmp.getPixel(x, y)
        val r = (px ushr 16) and 0xFF
        val g = (px ushr 8) and 0xFF
        val b = px and 0xFF
        sum += ((299 * r + 587 * g + 114 * b) / 1000)
        count++
        x += stepX
      }
      y += stepY
    }
    return if (count > 0L) (sum / count).toInt().coerceIn(0, 255) else 0
  }

  fun analyzeBitmap(
    bmp: Bitmap,
    isFrontCamera: Boolean,
    quality: Int = 58,
    sourceMode: String = "foreground",
  ): VisionFrameSnapshot {
    val luma = computeAverageLuma(bmp)
    var faceCount = 0
    var faceDetected = false
    if (isFrontCamera && luma >= LxrCameraVisionGuard.MIN_READABLE_LUMA) {
      var base565: Bitmap? = null
      var bmp565: Bitmap? = null
      try {
        val wEven = (bmp.width / 2 * 2).coerceAtLeast(64)
        val hEven = (bmp.height / 2 * 2).coerceAtLeast(64)
        val base = bmp.copy(Bitmap.Config.RGB_565, false)
        base565 = base
        if (base != null) {
          bmp565 =
            if (base.width == wEven && base.height == hEven) {
              base
            } else {
              Bitmap.createScaledBitmap(base, wEven, hEven, true)
            }
          val target565 = bmp565
          if (target565 != null) {
            val detector = FaceDetector(wEven, hEven, 2)
            val faces = arrayOfNulls<FaceDetector.Face>(2)
            val found = detector.findFaces(target565, faces)
            val confidentCount =
              (0 until found).count { idx -> (faces[idx]?.confidence() ?: 0f) >= 0.30f }
            faceCount = confidentCount
            faceDetected = confidentCount > 0
          }
        }
      } catch (_: Throwable) {
      } finally {
        try {
          if (bmp565 != null && bmp565 !== base565) bmp565.recycle()
          base565?.recycle()
        } catch (_: Throwable) {}
      }
    }

    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(35, 85), out)
    return VisionFrameSnapshot(
      jpegBytes = out.toByteArray(),
      capturedAtMs = System.currentTimeMillis(),
      isFrontCamera = isFrontCamera,
      faceDetected = faceDetected,
      faceCount = faceCount,
      averageLuma = luma,
      sourceMode = sourceMode,
    )
  }

  fun onForegroundFrame(snapshot: VisionFrameSnapshot) {
    latestSnapshot = snapshot
    lastErrorBn = null
    frameWaitLatch?.countDown()
  }

  fun clearSnapshot() {
    latestSnapshot = null
  }

  fun ensureBackgroundCaptureIfActive(ctx: Context) {
    val appCtx = ctx.applicationContext
    if (!RiyaState.visionCameraActive.value || !RiyaState.running.value) {
      stopBackgroundCamera()
      return
    }
    if (uiPreviewActive) {
      stopBackgroundCamera()
      return
    }
    if (!hasCameraPermission(appCtx)) {
      lastErrorBn = "CAMERA পারমিশন নেই"
      return
    }
    val wantFront = RiyaState.useFrontCamera.value
    synchronized(stateLock) {
      if (cameraDevice != null && activeBackgroundFrontLens == wantFront) {
        return
      }
    }
    startBackgroundCamera(appCtx, wantFront)
  }

  fun stopAll() {
    uiPreviewActive = false
    stopBackgroundCamera()
    latestSnapshot = null
  }

  fun captureOrAwaitFreshFrame(
    ctx: Context,
    requestedFrontLens: Boolean? = null,
    timeoutMs: Long = 1800L,
  ): VisionFrameSnapshot? {
    val appCtx = ctx.applicationContext
    if (!hasCameraPermission(appCtx)) {
      lastErrorBn = "CAMERA পারমিশন বন্ধ আছে"
      return null
    }
    if (requestedFrontLens != null && RiyaState.useFrontCamera.value != requestedFrontLens) {
      RiyaState.useFrontCamera.value = requestedFrontLens
      latestSnapshot = null
    }
    if (!RiyaState.visionCameraActive.value) {
      RiyaState.setVisionCameraActive(true)
      RiyaService.syncCameraState(appCtx)
    }

    val wantFront = RiyaState.useFrontCamera.value
    val cur = latestSnapshot
    val now = System.currentTimeMillis()
    if (cur != null &&
      cur.isFrontCamera == wantFront &&
      (now - cur.capturedAtMs) <= 1800L &&
      cur.jpegBytes != null
    ) {
      return cur
    }

    if (!uiPreviewActive) {
      ensureBackgroundCaptureIfActive(appCtx)
    }

    val latch = CountDownLatch(1)
    frameWaitLatch = latch
    try {
      latch.await(timeoutMs.coerceIn(300L, 3500L), TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
    } finally {
      if (frameWaitLatch === latch) frameWaitLatch = null
    }
    val fresh = latestSnapshot
    return if (fresh != null && fresh.isFrontCamera == wantFront) fresh else latestSnapshot
  }

  @SuppressLint("MissingPermission")
  private fun startBackgroundCamera(ctx: Context, useFront: Boolean) {
    if (!hasCameraPermission(ctx)) return
    if (!opening.compareAndSet(false, true)) return
    stopBackgroundCameraInternal(resetOpening = false)

    try {
      val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val targetFacing =
        if (useFront) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK

      var pickedId: String? = null
      var sensorOrientation = if (useFront) 270 else 90
      for (id in cm.cameraIdList) {
        val chars = cm.getCameraCharacteristics(id)
        if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
          pickedId = id
          sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: sensorOrientation
          break
        }
      }
      if (pickedId == null) {
        pickedId = cm.cameraIdList.firstOrNull()
      }
      if (pickedId == null) {
        lastErrorBn = "ডিভাইসে কোনো ক্যামেরা পাওয়া যায়নি"
        opening.set(false)
        return
      }

      val chars = cm.getCameraCharacteristics(pickedId)
      val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
      val chosenSize =
        sizes
          .filter { it.width >= 320 && it.height >= 240 }
          .minByOrNull { kotlin.math.abs((it.width * it.height) - (640 * 480)) }
          ?: sizes.firstOrNull()
          ?: Size(640, 480)

      val thread = HandlerThread("LxrBgCamera").apply { start() }
      val handler = Handler(thread.looper)
      synchronized(stateLock) {
        camThread = thread
        camHandler = handler
        activeBackgroundFrontLens = useFront
      }

      val reader =
        ImageReader.newInstance(chosenSize.width, chosenSize.height, ImageFormat.YUV_420_888, 2)
      imageReader = reader
      lastBgFrameSentMs = 0L

      reader.setOnImageAvailableListener(
        { r ->
          val image =
            try {
              r.acquireLatestImage()
            } catch (_: Throwable) {
              null
            } ?: return@setOnImageAvailableListener

          try {
            if (!RiyaState.visionCameraActive.value || uiPreviewActive) return@setOnImageAvailableListener
            val nowMs = SystemClock.elapsedRealtime()
            val minGapMs =
              when {
                latestSnapshot == null -> 250L
                Store.lowNetFastMode(ctx) -> 1250L
                else -> 950L
              }
            if (nowMs - lastBgFrameSentMs < minGapMs) return@setOnImageAvailableListener
            lastBgFrameSentMs = nowMs

            val upright = yuvImageToUprightScaledBitmap(image, sensorOrientation, maxDim = 512)
              ?: return@setOnImageAvailableListener
            try {
              val snap =
                analyzeBitmap(
                  bmp = upright,
                  isFrontCamera = useFront,
                  quality = 58,
                  sourceMode = "background",
                )
              latestSnapshot = snap
              lastErrorBn = null
              frameWaitLatch?.countDown()
              snap.jpegBytes?.let { bytes ->
                RiyaState.live?.sendCameraFrame(bytes)
              }
            } finally {
              upright.recycle()
            }
          } catch (t: Throwable) {
            Log.w(TAG, "Background frame analyze failed", t)
          } finally {
            try {
              image.close()
            } catch (_: Throwable) {}
          }
        },
        handler,
      )

      cm.openCamera(
        pickedId,
        object : CameraDevice.StateCallback() {
          override fun onOpened(camera: CameraDevice) {
            opening.set(false)
            synchronized(stateLock) {
              if (!RiyaState.visionCameraActive.value || uiPreviewActive) {
                camera.close()
                return
              }
              cameraDevice = camera
            }
            try {
              val surface = reader.surface
              @Suppress("DEPRECATION")
              camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                  override fun onConfigured(session: CameraCaptureSession) {
                    synchronized(stateLock) {
                      if (!RiyaState.visionCameraActive.value || uiPreviewActive || cameraDevice !== camera) {
                        session.close()
                        return
                      }
                      captureSession = session
                    }
                    try {
                      val req =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                          addTarget(surface)
                          set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                          )
                          set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON,
                          )
                        }.build()
                      session.setRepeatingRequest(req, null, handler)
                    } catch (e: Throwable) {
                      lastErrorBn = "ব্যাকগ্রাউন্ড ক্যামেরা স্ট্রিম শুরু করা যায়নি"
                      Log.w(TAG, "setRepeatingRequest failed", e)
                    }
                  }

                  override fun onConfigureFailed(session: CameraCaptureSession) {
                    lastErrorBn = "ক্যামেরা সেশন কনফিগার করা যায়নি"
                  }
                },
                handler,
              )
            } catch (t: Throwable) {
              lastErrorBn = "ক্যামেরা সেশন খোলা যায়নি"
              Log.w(TAG, "createCaptureSession failed", t)
            }
          }

          override fun onDisconnected(camera: CameraDevice) {
            opening.set(false)
            try {
              camera.close()
            } catch (_: Throwable) {}
            synchronized(stateLock) {
              if (cameraDevice === camera) cameraDevice = null
            }
          }

          override fun onError(camera: CameraDevice, error: Int) {
            opening.set(false)
            lastErrorBn =
              if (error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE) {
                "অন্য কোনো অ্যাপ এই মুহূর্তে ক্যামেরা ব্যবহার করছে"
              } else {
                "ব্যাকগ্রাউন্ড ক্যামেরা ত্রুটি ($error)"
              }
            try {
              camera.close()
            } catch (_: Throwable) {}
            synchronized(stateLock) {
              if (cameraDevice === camera) cameraDevice = null
            }
          }
        },
        handler,
      )
    } catch (se: SecurityException) {
      opening.set(false)
      lastErrorBn = "ক্যামেরা পারমিশন পাওয়া যায়নি"
    } catch (t: Throwable) {
      opening.set(false)
      lastErrorBn = "ব্যাকগ্রাউন্ড ক্যামেরা চালু হয়নি (${t.javaClass.simpleName})"
      Log.w(TAG, "openCamera failed", t)
    }
  }

  fun stopBackgroundCamera() {
    stopBackgroundCameraInternal(resetOpening = true)
  }

  private fun stopBackgroundCameraInternal(resetOpening: Boolean) {
    synchronized(stateLock) {
      try {
        captureSession?.stopRepeating()
      } catch (_: Throwable) {}
      try {
        captureSession?.close()
      } catch (_: Throwable) {}
      captureSession = null

      try {
        cameraDevice?.close()
      } catch (_: Throwable) {}
      cameraDevice = null

      try {
        imageReader?.close()
      } catch (_: Throwable) {}
      imageReader = null

      try {
        camThread?.quitSafely()
      } catch (_: Throwable) {}
      camThread = null
      camHandler = null
      activeBackgroundFrontLens = null
      if (resetOpening) opening.set(false)
    }
  }

  private fun yuvImageToUprightScaledBitmap(
    image: Image,
    sensorOrientationDeg: Int,
    maxDim: Int,
  ): Bitmap? {
    val nv21 = yuv420ToNv21(image) ?: return null
    val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val os = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 78, os)
    val rawBytes = os.toByteArray()
    val rawBmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
    var rotatedBmp: Bitmap? = null
    return try {
      val rot = ((sensorOrientationDeg % 360) + 360) % 360
      val oriented =
        if (rot != 0) {
          val m = Matrix().apply { postRotate(rot.toFloat()) }
          Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
            .also { rotatedBmp = it }
        } else {
          rawBmp
        }
      val scale = maxDim.toFloat() / maxOf(oriented.width, oriented.height).coerceAtLeast(1)
      val finalBmp =
        if (scale < 1f) {
          Bitmap.createScaledBitmap(
            oriented,
            (oriented.width * scale).toInt().coerceAtLeast(64),
            (oriented.height * scale).toInt().coerceAtLeast(64),
            true,
          )
        } else {
          oriented
        }
      if (finalBmp === oriented && rotatedBmp === null) {
        // Ensure caller always receives a safe owned Bitmap copy
        rawBmp.copy(Bitmap.Config.ARGB_8888, false)
      } else {
        if (finalBmp !== oriented && rotatedBmp != null) {
          rotatedBmp?.recycle()
        }
        finalBmp
      }
    } catch (_: Throwable) {
      null
    } finally {
      if (rotatedBmp != null && rotatedBmp !== rawBmp) {
        try {
          if (rawBmp != null && !rawBmp.isRecycled) rawBmp.recycle()
        } catch (_: Throwable) {}
      } else {
        try {
          if (!rawBmp.isRecycled) rawBmp.recycle()
        } catch (_: Throwable) {}
      }
    }
  }

  private fun yuv420ToNv21(image: Image): ByteArray? {
    val planes = image.planes
    if (planes.size < 3) return null
    val w = image.width
    val h = image.height
    val ySize = w * h
    val uvSize = ySize / 2
    val out = ByteArray(ySize + uvSize)

    val yPlane = planes[0]
    val yBuf = yPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    var pos = 0
    for (row in 0 until h) {
      val rowStart = row * yRowStride
      if (yPixelStride == 1) {
        yBuf.position(rowStart)
        yBuf.get(out, pos, w)
        pos += w
      } else {
        for (col in 0 until w) {
          out[pos++] = yBuf.get(rowStart + col * yPixelStride)
        }
      }
    }

    val uPlane = planes[1]
    val vPlane = planes[2]
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride
    val chromaH = h / 2
    val chromaW = w / 2
    for (row in 0 until chromaH) {
      val uRowStart = row * uRowStride
      val vRowStart = row * vRowStride
      for (col in 0 until chromaW) {
        out[pos++] = vBuf.get(vRowStart + col * vPixelStride)
        out[pos++] = uBuf.get(uRowStart + col * uPixelStride)
      }
    }
    return out
  }
}
