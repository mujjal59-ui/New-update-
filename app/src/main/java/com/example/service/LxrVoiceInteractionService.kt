package com.example.service

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.util.Log
import com.example.MainActivity
import com.example.data.RiyaState
import com.example.data.Store

/**
 * Holds OS-supplied AssistStructure screen snapshots delivered whenever Android triggers
 * LXR--AI as the Default Digital Assistant, serving as a guaranteed fallback layer for readScreen().
 */
object LxrAssistScreenBridge {
  @Volatile var latestAssistScreenText: String = ""
  @Volatile var latestAssistAppPackage: String = ""
  @Volatile var latestAssistUpdatedAtMs: Long = 0L

  fun captureAssistStructure(structure: AssistStructure?, content: AssistContent? = null) {
    if (structure == null) return
    try {
      val lines = LinkedHashSet<String>()
      var topPkg = ""
      val count = structure.windowNodeCount
      for (i in 0 until count) {
        val win = structure.getWindowNodeAt(i) ?: continue
        val root = win.rootViewNode ?: continue
        val title = win.title?.toString()?.trim().orEmpty()
        if (title.isNotBlank() && lines.size < 120) {
          lines.add(title)
        }
        walkViewNode(root, 0, lines) { pkg ->
          if (topPkg.isBlank() && pkg.isNotBlank() && pkg != "com.android.systemui") {
            topPkg = pkg
          }
        }
      }
      content?.webUri?.toString()?.takeIf { it.isNotBlank() }?.let { uri ->
        lines.add("Web URL: $uri")
      }
      if (lines.isNotEmpty()) {
        latestAssistAppPackage = topPkg
        latestAssistScreenText = lines.take(120).joinToString("\n").take(3500)
        latestAssistUpdatedAtMs = System.currentTimeMillis()
      }
    } catch (_: Throwable) {}
  }

  private fun walkViewNode(
    node: AssistStructure.ViewNode?,
    depth: Int,
    out: MutableSet<String>,
    onPkg: (String) -> Unit,
  ) {
    if (node == null || depth > 36 || out.size >= 140) return
    val pkg = node.idPackage?.trim().orEmpty()
    if (pkg.isNotBlank()) onPkg(pkg)
    val text = node.text?.toString()?.trim().orEmpty()
    val desc = node.contentDescription?.toString()?.trim().orEmpty()
    val hint = node.hint?.trim().orEmpty()
    val webDomain = node.webDomain?.trim().orEmpty()
    if (webDomain.isNotBlank() && out.size < 140) {
      out.add("Site: $webDomain")
    }
    val readable =
      when {
        text.isNotBlank() -> text.take(120)
        desc.isNotBlank() -> desc.take(120)
        hint.isNotBlank() -> "(হিন্ট: ${hint.take(50)})"
        else -> ""
      }
    if (readable.isNotBlank()) {
      out.add(readable)
    }
    for (i in 0 until node.childCount) {
      walkViewNode(node.getChildAt(i), depth + 1, out, onPkg)
    }
  }

  fun buildFallbackScreenReport(
    snapshotLines: List<String>,
    currentScreenSummary: String,
    activePkgHint: String = "",
  ): String {
    val lines = LinkedHashSet<String>()
    snapshotLines.filter { it.isNotBlank() }.forEach { lines.add(it.take(140)) }
    val freshAssist =
      (System.currentTimeMillis() - latestAssistUpdatedAtMs) < 30_000L &&
        latestAssistScreenText.isNotBlank()
    if (freshAssist) {
      latestAssistScreenText.lines().filter { it.isNotBlank() }.forEach { lines.add(it) }
    }
    val pkgLabel = activePkgHint.ifBlank { latestAssistAppPackage }.ifBlank { "current_screen" }
    if (lines.isNotEmpty()) {
      return "অ্যাপ: $pkgLabel\n" + lines.take(100).joinToString("\n").take(3800)
    }
    if (currentScreenSummary.isNotBlank()) {
      return currentScreenSummary
    }
    return "অ্যাপ: $pkgLabel\nস্ক্রিনে এই মুহূর্তে অতিরিক্ত কোনো লেখা দৃশ্যমান নেই।"
  }
}

class LxrVoiceInteractionService : VoiceInteractionService() {
  override fun onReady() {
    super.onReady()
    Log.d("LxrAssistantSvc", "LXR--AI VoiceInteractionService ready")
  }
}

class LxrVoiceInteractionSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession = LxrVoiceInteractionSession(this)
}

class LxrVoiceInteractionSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    LxrAssistScreenBridge.captureAssistStructure(state.assistStructure, state.assistContent)
  }

  @Suppress("DEPRECATION")
  override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
    super.onHandleAssist(data, structure, content)
    LxrAssistScreenBridge.captureAssistStructure(structure, content)
  }

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    try {
      if (!RiyaState.running.value && Store.apiKey(ctx).isNotBlank()) {
        val svcIntent = Intent(ctx, RiyaService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
          ctx.startForegroundService(svcIntent)
        } else {
          ctx.startService(svcIntent)
        }
      } else if (Store.apiKey(ctx).isBlank()) {
        ctx.startActivity(
          Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
      }
    } catch (_: Throwable) {
    } finally {
      try {
        finish()
      } catch (_: Throwable) {}
    }
  }
}

class LxrRecognitionService : RecognitionService() {
  override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
    listener?.readyForSpeech(Bundle())
  }

  override fun onCancel(listener: Callback?) {}

  override fun onStopListening(listener: Callback?) {
    listener?.endOfSpeech()
  }
}
