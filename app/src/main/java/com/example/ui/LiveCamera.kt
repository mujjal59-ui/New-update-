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
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Back camera preview; প্রতি ১ সেকেন্ডে একটি ফ্রেম Gemini Live-এ পাঠায়। */
@Composable
fun LiveCamera(modifier: Modifier = Modifier) {
  val ctx = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val exec = remember { Executors.newSingleThreadExecutor() }
  val preview = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

  DisposableEffect(owner) {
    var provider: ProcessCameraProvider? = null
    val future = ProcessCameraProvider.getInstance(ctx)
    future.addListener(
      {
        try {
          val p = future.get()
          provider = p
          val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
          var last = 0L
          val analysis =
            ImageAnalysis.Builder()
              .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
              .build()
          analysis.setAnalyzer(exec) { img ->
            try {
              val now = System.currentTimeMillis()
              if (now - last >= 1000) {
                last = now
                var bmp = img.toBitmap()
                val rot = img.imageInfo.rotationDegrees
                if (rot != 0) {
                  bmp =
                    Bitmap.createBitmap(
                      bmp,
                      0,
                      0,
                      bmp.width,
                      bmp.height,
                      Matrix().apply { postRotate(rot.toFloat()) },
                      true,
                    )
                }
                val sc = 640f / maxOf(bmp.width, bmp.height)
                if (sc < 1f) {
                  bmp =
                    Bitmap.createScaledBitmap(
                      bmp,
                      (bmp.width * sc).toInt(),
                      (bmp.height * sc).toInt(),
                      true,
                    )
                }
                val o = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 60, o)
                RiyaState.live?.sendImage(o.toByteArray())
              }
            } catch (_: Exception) {
            } finally {
              img.close()
            }
          }
          p.unbindAll()
          p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
        } catch (_: Exception) {}
      },
      ContextCompat.getMainExecutor(ctx),
    )
    onDispose {
      try {
        provider?.unbindAll()
      } catch (_: Exception) {}
      try {
        exec.shutdown()
      } catch (_: Exception) {}
    }
  }
  AndroidView({ preview }, modifier)
}
