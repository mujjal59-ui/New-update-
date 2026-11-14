package com.example.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.RiyaState
import com.example.data.Store
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Back camera preview; ফ্রেম ধরে সাথে সাথে ও নিয়মিতভাবে Gemini Live Vision-এ পাঠায়। */
@Composable
fun LiveCamera(modifier: Modifier = Modifier) {
  val ctx = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val exec = remember { Executors.newSingleThreadExecutor() }
  val preview = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

  DisposableEffect(owner) {
    var provider: ProcessCameraProvider? = null
    var analysisRef: ImageAnalysis? = null
    var notifiedOn = false
    val future = ProcessCameraProvider.getInstance(ctx)
    future.addListener(
      {
        try {
          val p = future.get()
          provider = p
          val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
          var lastSentMs = 0L
          val analysis =
            ImageAnalysis.Builder()
              .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
              .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
              .build()
          analysisRef = analysis

          analysis.setAnalyzer(exec) { img ->
            var bmp: Bitmap? = null
            var rotBmp: Bitmap? = null
            var scaledBmp: Bitmap? = null
            try {
              val now = System.currentTimeMillis()
              val interval = if (Store.lowNetFastMode(ctx)) 1300L else 950L
              if (now - lastSentMs >= interval) {
                val live = RiyaState.live
                if (live != null && live.isReady()) {
                  lastSentMs = now
                  if (!notifiedOn) {
                    notifiedOn = true
                    live.notifyCameraActive(true)
                  }
                  bmp = img.toBitmap()
                  val rot = img.imageInfo.rotationDegrees
                  val rotated =
                    if (rot != 0) {
                      Bitmap.createBitmap(
                        bmp,
                        0,
                        0,
                        bmp.width,
                        bmp.height,
                        Matrix().apply { postRotate(rot.toFloat()) },
                        true,
                      ).also { rotBmp = it }
                    } else {
                      bmp
                    }

                  val maxDim = 512f
                  val sc = maxDim / maxOf(rotated.width, rotated.height)
                  val finalBmp =
                    if (sc < 1f) {
                      Bitmap.createScaledBitmap(
                        rotated,
                        (rotated.width * sc).toInt().coerceAtLeast(64),
                        (rotated.height * sc).toInt().coerceAtLeast(64),
                        true,
                      ).also { scaledBmp = it }
                    } else {
                      rotated
                    }

                  val out = ByteArrayOutputStream()
                  finalBmp.compress(Bitmap.CompressFormat.JPEG, 55, out)
                  val jpegBytes = out.toByteArray()
                  live.sendCameraFrame(jpegBytes)
                }
              }
            } catch (_: Throwable) {
            } finally {
              try {
                if (scaledBmp != null && scaledBmp !== rotBmp && scaledBmp !== bmp) scaledBmp?.recycle()
                if (rotBmp != null && rotBmp !== bmp) rotBmp?.recycle()
                bmp?.recycle()
              } catch (_: Throwable) {}
              try {
                img.close()
              } catch (_: Throwable) {}
            }
          }
          p.unbindAll()
          p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
        } catch (_: Throwable) {}
      },
      ContextCompat.getMainExecutor(ctx),
    )
    onDispose {
      try {
        RiyaState.live?.notifyCameraActive(false)
      } catch (_: Throwable) {}
      try {
        analysisRef?.clearAnalyzer()
      } catch (_: Throwable) {}
      try {
        provider?.unbindAll()
      } catch (_: Throwable) {}
      try {
        exec.shutdownNow()
      } catch (_: Throwable) {}
    }
  }
  AndroidView({ preview }, modifier)
}
