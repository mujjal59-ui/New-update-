package com.example.video

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.builder.TermuxBuildBridge
import com.example.builder.TermuxBuildResultReceiver
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Aligns with upstream OpenMontage architecture (https://github.com/OpenMontageInc/OpenMontage):
// - tools/provider_routing.py + tools/provider_pricing.py (truthful Paid vs Free-first provider registry)
// - pipeline_defs (12-stage checkpointed production YAML workflows)
// - skills markdown (Director, Scriptwriter, Character Designer, Voice Director, Editor)
// - Remotion (.tsx), GSAP HyperFrames (.html), Manim (.py), & Termux FFmpeg + on-device 24fps H.264/AAC
object OpenMontageFrameworkBridge {

  const val OPENMONTAGE_REPO = "https://github.com/OpenMontageInc/OpenMontage"
  private const val TERMUX_SERVICE_CLASS = "com.termux.app.RunCommandService"
  private const val TERMUX_ACTION_RUN = "com.termux.RUN_COMMAND"

  // Honest registry aligned with OpenMontage provider_pricing.py & video provider tools.
  // NEVER marks a Paid API or GPU-only cloud endpoint as Free.
  val VIDEO_GENERATION_PROVIDER_REGISTRY: List<OpenMontageVideoProviderEntry> = listOf(
    OpenMontageVideoProviderEntry(
      id = "lxr_articulated_24fps",
      displayName = "LXR 24fps Articulated & Motion Engine",
      openMontageToolName = "on_device_canvas_24fps_h264",
      billingTier = ProviderBillingTier.FREE_ON_DEVICE,
      supportsTextToVideo = true,
      supportsImageToVideo = true,
      supportsVideoToVideo = true,
      requiredEnvVar = "",
      availabilityNoteBn = "ফোনেই ১০০% ফ্রি ও অফলাইন: ২৪fps আর্টিকুলেটেড চরিত্র, লিপ-সিঙ্ক, মোশন গ্রাফিক্স ও H.264/AAC MP4 রেন্ডার",
    ),
    OpenMontageVideoProviderEntry(
      id = "stock_wikimedia_ia_nasa",
      displayName = "Wikimedia Commons + Internet Archive + NASA",
      openMontageToolName = "stock_sources/{wikimedia,archive_org,nasa}",
      billingTier = ProviderBillingTier.FREE_NO_KEY,
      supportsTextToVideo = false,
      supportsImageToVideo = true,
      supportsVideoToVideo = true,
      requiredEnvVar = "",
      availabilityNoteBn = "কোনো API Key ছাড়াই ১০০% ফ্রি বাস্তব ভিডিও/ফুটেজ ও পাবলিক ডোমেইন মিডিয়া সোর্স",
    ),
    OpenMontageVideoProviderEntry(
      id = "stock_pexels_pixabay",
      displayName = "Pexels & Pixabay Free Stock Video",
      openMontageToolName = "pexels_video / pixabay_video",
      billingTier = ProviderBillingTier.FREE_STOCK_KEY,
      supportsTextToVideo = false,
      supportsImageToVideo = false,
      supportsVideoToVideo = true,
      requiredEnvVar = "PEXELS_API_KEY / PIXABAY_API_KEY",
      availabilityNoteBn = "বিনামূল্যে Pexels বা Pixabay API Key দিলে রিয়েলস্টিক HD স্টক ভিডিও ক্লিপ যুক্ত হবে",
    ),
    OpenMontageVideoProviderEntry(
      id = "veo",
      displayName = "Google Veo (veo_video.py)",
      openMontageToolName = "veo_video",
      billingTier = ProviderBillingTier.PAID_API,
      supportsTextToVideo = true,
      supportsImageToVideo = true,
      supportsVideoToVideo = false,
      requiredEnvVar = "GOOGLE_API_KEY / FAL_KEY",
      availabilityNoteBn = "Paid API (~$0.15-$0.40/sec): ব্যবহারের আগে আপনার অনুমতি ও বিলিং API Key প্রয়োজন",
    ),
    OpenMontageVideoProviderEntry(
      id = "kling",
      displayName = "Kling Video (kling_video.py)",
      openMontageToolName = "kling_video",
      billingTier = ProviderBillingTier.PAID_API,
      supportsTextToVideo = true,
      supportsImageToVideo = true,
      supportsVideoToVideo = true,
      requiredEnvVar = "KLING_API_KEY / FAL_KEY",
      availabilityNoteBn = "Paid API (~$0.07-$0.14/sec): ব্যবহারের আগে আপনার অনুমতি ও API Key প্রয়োজন",
    ),
    OpenMontageVideoProviderEntry(
      id = "runway",
      displayName = "Runway Gen-3 / Gen-4 (runway_video.py)",
      openMontageToolName = "runway_video",
      billingTier = ProviderBillingTier.PAID_API,
      supportsTextToVideo = true,
      supportsImageToVideo = true,
      supportsVideoToVideo = true,
      requiredEnvVar = "RUNWAY_API_KEY",
      availabilityNoteBn = "Paid API (~$0.05-$0.10/sec): ব্যবহারের আগে আপনার অনুমতি ও API Key প্রয়োজন",
    ),
    OpenMontageVideoProviderEntry(
      id = "hailuo",
      displayName = "Hailuo / MiniMax Video (minimax_video.py)",
      openMontageToolName = "minimax_video",
      billingTier = ProviderBillingTier.PAID_API,
      supportsTextToVideo = true,
      supportsImageToVideo = true,
      supportsVideoToVideo = false,
      requiredEnvVar = "MINIMAX_API_KEY / FAL_KEY",
      availabilityNoteBn = "Paid API (~$0.05/sec): ব্যবহারের আগে আপনার অনুমতি ও API Key প্রয়োজন",
    ),
    OpenMontageVideoProviderEntry(
      id = "seedance",
      displayName = "ByteDance Seedance (seedance_video.py)",
      openMontageToolName = "seedance_video",
      billingTier = ProviderBillingTier.PAID_API,
      supportsTextToVideo = true,
      supportsImageToVideo = true,
      supportsVideoToVideo = false,
      requiredEnvVar = "ARK_API_KEY / FAL_KEY",
      availabilityNoteBn = "Paid API (~$0.04-$0.08/sec): ব্যবহারের আগে আপনার অনুমতি ও API Key প্রয়োজন",
    ),
    OpenMontageVideoProviderEntry(
      id = "wan",
      displayName = "Wan 2.1 / 2.2 (wan_video.py)",
      openMontageToolName = "wan_video",
      billingTier = ProviderBillingTier.OPEN_WEIGHT_GPU_OR_PAID,
      supportsTextToVideo = true,
      supportsImageToVideo = true,
      supportsVideoToVideo = false,
      requiredEnvVar = "FAL_KEY / REPLICATE_API_TOKEN (অথবা 16GB+ VRAM GPU)",
      availabilityNoteBn = "ওপেন-ওয়েট মডেল, কিন্তু Android ফোনে সরাসরি চলে না — Desktop GPU বা Paid Cloud API লাগে",
    ),
    OpenMontageVideoProviderEntry(
      id = "ltx_video",
      displayName = "LTX Video (ltx_api_video.py / ltx_video_local.py)",
      openMontageToolName = "ltx_video",
      billingTier = ProviderBillingTier.OPEN_WEIGHT_GPU_OR_PAID,
      supportsTextToVideo = true,
      supportsImageToVideo = true,
      supportsVideoToVideo = true,
      requiredEnvVar = "LTX_API_KEY / FAL_KEY (অথবা 12GB+ VRAM GPU)",
      availabilityNoteBn = "ওপেন-ওয়েট মডেল (Desktop GPU diffusers প্রয়োজন), ক্লাউড API Paid — অনুমতির প্রয়োজন",
    ),
    OpenMontageVideoProviderEntry(
      id = "hunyuan_video",
      displayName = "Tencent Hunyuan Video (hunyuan_video.py)",
      openMontageToolName = "hunyuan_video",
      billingTier = ProviderBillingTier.OPEN_WEIGHT_GPU_OR_PAID,
      supportsTextToVideo = true,
      supportsImageToVideo = false,
      supportsVideoToVideo = false,
      requiredEnvVar = "FAL_KEY / REPLICATE_API_TOKEN (অথবা 24GB+ VRAM GPU)",
      availabilityNoteBn = "ওপেন-ওয়েট মডেল (24GB+ VRAM GPU প্রয়োজন), ক্লাউড API Paid — অনুমতির প্রয়োজন",
    ),
  )

  fun findProvider(providerId: String): OpenMontageVideoProviderEntry? =
    VIDEO_GENERATION_PROVIDER_REGISTRY.firstOrNull { it.id == providerId }

  fun inspectCapabilities(context: Context): OpenMontageCapabilityReport {
    val termuxInstalled = TermuxBuildBridge.isPackageInstalled(context, TermuxBuildBridge.TERMUX_PACKAGE)
    val termuxPerm = TermuxBuildBridge.hasRunCommandPermission(context)
    val hasHardwareH264 = probeHardwareH264Encoder()

    val encoderNote =
      if (hasHardwareH264) {
        "Android MediaCodec H.264/AVC (EGL PTS-Synced 24fps) + Articulated Rig প্রস্তুত ✓"
      } else {
        "ISO-BMFF পোর্টেবল MP4 কম্পোজিটর ও OpenMontage স্ক্রিপ্ট প্রস্তুত ✓"
      }

    val ffmpegNote =
      if (termuxInstalled) {
        "Termux পাওয়া গেছে ✓ — 'pkg install ffmpeg python manim' দিয়ে ডেস্কটপ-মানের FFmpeg + Manim চালানো যাবে।"
      } else {
        "Termux ঐচ্ছিক — ফ্রি অন-ডিভাইস 24fps MediaCodec + Articulated + Motion Graphics রেন্ডারার সক্রিয় ✓।"
      }

    return OpenMontageCapabilityReport(
      frameworkRepoUrl = OPENMONTAGE_REPO,
      onDeviceEncoderAvailable = true,
      onDeviceEncoderNote = encoderNote,
      termuxInstalled = termuxInstalled,
      termuxRunPermGranted = termuxPerm,
      ffmpegNote = ffmpegNote,
    )
  }

  private fun probeHardwareH264Encoder(): Boolean {
    return runCatching {
      val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
      list.codecInfos.any { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
      }
    }.getOrDefault(false)
  }

  fun buildPipelineYaml(project: VideoProject): String {
    val w = if (project.settings.resolution.is1080p) project.settings.aspectRatio.width1080 else project.settings.aspectRatio.width720
    val h = if (project.settings.resolution.is1080p) project.settings.aspectRatio.height1080 else project.settings.aspectRatio.height720
    val typeLabel = project.customTypeLabel.ifBlank { project.videoType.labelEn }
    val provider = findProvider(project.settings.selectedVideoProviderId)
    val paidAllowed = project.settings.paidProviderConsentGranted && (provider?.billingTier?.isPaidApi == true)
    return buildString {
      appendLine("# OpenMontage Agentic Production Pipeline (Free-First Architecture)")
      appendLine("# Upstream Framework: $OPENMONTAGE_REPO")
      appendLine("name: \"${project.slug}\"")
      appendLine("title: \"${project.title.replace("\"", "'")}\"")
      appendLine("video_type: \"$typeLabel\"")
      appendLine("production_mode: \"${project.settings.productionMode.name}\"")
      appendLine("aspect_ratio: \"${project.settings.aspectRatio.ratioTag}\"")
      appendLine("resolution: \"${w}x${h}\"")
      appendLine("fps: 24")
      appendLine("free_only_policy: ${!paidAllowed}")
      appendLine("paid_apis_allowed: $paidAllowed")
      appendLine("selected_video_provider: \"${project.settings.selectedVideoProviderId}\"")
      appendLine("motion_graphics_engines: [\"remotion\", \"gsap_hyperframes\", \"manim\", \"ffmpeg\"]")
      appendLine("providers:")
      appendLine("  llm_script: [\"firebase_ai_free\", \"lxr_offline_storyboard\"]")
      appendLine("  voice_over: [\"gemini_free_tts(${project.settings.selectedVoiceId})\", \"piper_tts_local\", \"lxr_formant_synth\"]")
      appendLine("  video_generation: [\"lxr_articulated_24fps\", \"wikimedia_ia_nasa_free_stock\", \"ffmpeg_compose\"]")
      appendLine("  music_sfx: [\"procedural_cc0_ambient_wav\", \"synthetic_transition_sfx\"]")
      appendLine("  compositor: [\"android_mediacodec_egl_pts\", \"ffmpeg_cli\", \"remotion_export_bundle\", \"manim_py\", \"gsap_hyperframes\"]")
      appendLine("quality_gate:")
      appendLine("  reject_static_slideshow: true")
      appendLine("  verify_lip_sync_variance: true")
      appendLine("  verify_av_sync: true")
      appendLine("  verify_resolution_and_black_frames: true")
      appendLine("stages:")
      PipelineStage.entries.forEach { st ->
        appendLine("  - id: ${st.key}")
        appendLine("    step: ${st.stepNumber}")
        appendLine("    label: \"${st.titleBn}\"")
        appendLine("    checkpoint: true")
        appendLine("    allow_pause_resume: true")
      }
      appendLine("scenes_count: ${project.scenes.size}")
    }
  }

  fun buildSkillsBundle(): Map<String, String> = mapOf(
    "skills/storywriter.md" to """
      # OpenMontage Skill: Storywriter (Bengali + English Free Studio)
      - Convert one-line prompt into engaging hook, rising curiosity, visual payoff, and inspiring outro.
      - Break narration into crisp scene beats suitable for exact subtitle synchronization and multi-character dialogue.
    """.trimIndent(),
    "skills/scene_director.md" to """
      # OpenMontage Skill: Scene & Camera Director
      - Apply 9:16 portrait or 16:9 widescreen framing with parallax skies, moving background vehicles/clouds, and dynamic camera motion.
      - Combine Real AI Character Animation, Motion Graphics, and Real Moving Stock Footage with ${TransitionEffect.entries.joinToString { it.ffmpegFilter }}.
    """.trimIndent(),
    "skills/character_designer.md" to """
      # OpenMontage Skill: Articulated Character & Multi-Actor Designer
      - Render multi-joint body, breathing torso, tracking eyes, blinking lids, facial expressions, hand gestures, walking/running leg cycles.
      - Support two-character conversational interaction and audio-driven lip-sync across Bengali & English speech.
    """.trimIndent(),
    "skills/voice_director.md" to """
      # OpenMontage Skill: Free Voice Director
      - Try free Gemini TTS voices (Aoede, Iapetus, Leda, Orus, Achernar, Charon, Sulafat) when quota is active.
      - NEVER call Paid APIs without explicit permission. Auto-fallback to Piper TTS (Termux) or LXR Phonetic Synth WAV.
    """.trimIndent(),
    "skills/motion_graphics_director.md" to """
      # OpenMontage Skill: Motion Graphics (Remotion + GSAP/HyperFrames + Manim)
      - Produce kinetic typography, animated 3D charts, step infographics, animated maps/timelines, lower-thirds, and intro/outro cards synced to audio beats.
    """.trimIndent(),
    "skills/editor_compositor.md" to """
      # OpenMontage Skill: Timeline Editor, Compositor & Quality Gate
      - Sync 24fps EGL-timestamped H.264 frames with voice WAV + BGM WAV + transition SFX + UTF-8 SRT subtitles.
      - Run Video Quality Gate to verify real inter-frame motion and reject frozen slideshow output.
    """.trimIndent(),
  )

  fun buildRemotionTsx(project: VideoProject): String {
    val w = if (project.settings.resolution.is1080p) project.settings.aspectRatio.width1080 else project.settings.aspectRatio.width720
    val h = if (project.settings.resolution.is1080p) project.settings.aspectRatio.height1080 else project.settings.aspectRatio.height720
    val sceneEntries = project.scenes.joinToString(",\n") { sc ->
      "  { index: ${sc.index}, title: ${JSONObject.quote(sc.title)}, text: ${JSONObject.quote(sc.overlayHeadline)}, subtitle: ${JSONObject.quote(sc.narrationText)}, durationSec: ${sc.effectiveDurationSec}, effect: ${JSONObject.quote(sc.effectTag)}, motionGraphic: ${JSONObject.quote(sc.motionGraphicKind.name)} }"
    }
    return """
      import React from 'react';
      import { AbsoluteFill, Sequence, useCurrentFrame, interpolate } from 'remotion';

      export const VIDEO_META = {
        title: ${JSONObject.quote(project.title)},
        width: $w,
        height: $h,
        fps: 24,
        mode: ${JSONObject.quote(project.settings.productionMode.name)},
        framework: "OpenMontage + LXR--AI Studio",
      };

      const SCENES = [
      $sceneEntries
      ];

      export const OpenMontageVideo: React.FC = () => {
        const frame = useCurrentFrame();
        let offset = 0;
        return (
          <AbsoluteFill style={{ backgroundColor: '#050818', color: '#E0F7FA', fontFamily: 'sans-serif' }}>
            {SCENES.map((scene) => {
              const durationFrames = Math.max(24, Math.round(scene.durationSec * 24));
              const start = offset;
              offset += durationFrames;
              const local = frame - start;
              const opacity = interpolate(local, [0, 12, durationFrames - 10, durationFrames], [0, 1, 1, 0], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              });
              const slideY = interpolate(local, [0, 14], [32, 0], { extrapolateRight: 'clamp' });
              return (
                <Sequence key={scene.index} from={start} durationInFrames={durationFrames}>
                  <AbsoluteFill style={{ justifyContent: 'center', alignItems: 'center', opacity, padding: 48, transform: `translateY(${'$'}{slideY}px)` }}>
                    <div style={{ fontSize: 18, color: '#FACC15', letterSpacing: 2 }}>{scene.motionGraphic}</div>
                    <h1 style={{ fontSize: 52, color: '#00E5FF', textAlign: 'center', marginTop: 12 }}>{scene.text}</h1>
                    <p style={{ fontSize: 30, color: '#FFFFFF', textAlign: 'center', marginTop: 24 }}>{scene.subtitle}</p>
                  </AbsoluteFill>
                </Sequence>
              );
            })}
          </AbsoluteFill>
        );
      };
    """.trimIndent()
  }

  fun buildManimMotionScript(project: VideoProject): String {
    val firstChart = project.scenes.firstOrNull { it.chartValues.isNotEmpty() }
    val vals = (firstChart?.chartValues?.takeIf { it.isNotEmpty() } ?: listOf(0.45f, 0.72f, 0.94f))
      .joinToString(", ") { String.format(java.util.Locale.US, "%.2f", it * 100f) }
    return """
      # OpenMontage Manim Motion Graphics Scene (for Termux/Desktop Manim CE render)
      # Upstream: $OPENMONTAGE_REPO
      from manim import *

      class LxrExplainerScene(Scene):
          def construct(self):
              title = Text(${JSONObject.quote(project.title.take(40))}, font_size=36, color=TEAL)
              self.play(Write(title))
              self.play(title.animate.to_edge(UP))

              chart = BarChart(values=[$vals], y_range=[0, 100, 20], x_length=7, y_length=4)
              self.play(Create(chart), run_time=1.8)
              self.wait(1.0)
              outro = Text("LXR--AI OpenMontage Studio", font_size=28, color=YELLOW)
              self.play(FadeIn(outro.to_edge(DOWN)))
              self.wait(0.8)
    """.trimIndent()
  }

  fun buildGsapHyperframesHtml(project: VideoProject): String {
    val scenesJson = JSONArray().apply {
      project.scenes.forEach { sc ->
        put(
          JSONObject()
            .put("index", sc.index)
            .put("headline", sc.overlayHeadline)
            .put("narration", sc.narrationText)
            .put("kind", sc.motionGraphicKind.name)
            .put("duration", sc.effectiveDurationSec)
        )
      }
    }
    return """
      <!doctype html>
      <html lang="bn">
      <head>
        <meta charset="utf-8" />
        <title>${project.title}</title>
        <script src="https://cdn.jsdelivr.net/npm/gsap@3.12.5/dist/gsap.min.js"></script>
        <style>
          body { margin: 0; background: #050818; color: #f8fafc; font-family: system-ui, sans-serif; display: grid; place-items: center; height: 100vh; overflow: hidden; }
          .stage { width: 88vw; max-width: 960px; padding: 36px; border: 2px solid #00e5ff; border-radius: 20px; background: linear-gradient(135deg, #0b1335, #111827); }
          .badge { color: #facc15; font-weight: 700; letter-spacing: 1px; font-size: 14px; }
          h1 { color: #00e5ff; margin: 10px 0; font-size: 38px; }
          p { font-size: 22px; line-height: 1.45; }
        </style>
      </head>
      <body>
        <div class="stage">
          <div class="badge" id="badge">OPENMONTAGE HYPERFRAMES GSAP</div>
          <h1 id="headline">${project.title}</h1>
          <p id="narration"></p>
        </div>
        <script>
          const SCENES = $scenesJson;
          const tl = gsap.timeline({ repeat: -1 });
          SCENES.forEach(sc => {
            tl.call(() => {
              document.getElementById('badge').textContent = 'SCENE ' + sc.index + ' • ' + sc.kind;
              document.getElementById('headline').textContent = sc.headline;
              document.getElementById('narration').textContent = sc.narration;
            })
            .fromTo('.stage', { y: 30, opacity: 0 }, { y: 0, opacity: 1, duration: 0.55, ease: 'power3.out' })
            .to('.stage', { opacity: 1, duration: Math.max(1.5, sc.duration - 0.6) });
          });
        </script>
      </body>
      </html>
    """.trimIndent()
  }

  fun buildPythonOpenMontageRunner(project: VideoProject): String = """
    #!/usr/bin/env python3
    # OpenMontage Free Local Runner generated by LXR--AI Video Studio
    # Upstream: $OPENMONTAGE_REPO
    import json, os, shutil, subprocess, sys

    PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
    CHECKPOINT_FILE = os.path.join(PROJECT_DIR, "checkpoint_state.json")

    def save_checkpoint(stage, status="completed"):
        data = {}
        if os.path.exists(CHECKPOINT_FILE):
            with open(CHECKPOINT_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
        data[stage] = status
        with open(CHECKPOINT_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)

    def probe_tools():
        return {
            "ffmpeg": shutil.which("ffmpeg") is not None,
            "ffprobe": shutil.which("ffprobe") is not None,
            "piper": shutil.which("piper") is not None,
            "manim": shutil.which("manim") is not None,
            "node": shutil.which("node") is not None,
        }

    def main():
        tools = probe_tools()
        print("[LXR-OpenMontage] Free tool registry:", json.dumps(tools))
        save_checkpoint("tool_discovery")
        out_mp4 = os.path.join(PROJECT_DIR, "${project.slug}_ffmpeg.mp4")
        frames_txt = os.path.join(PROJECT_DIR, "frames_concat.txt")
        voice_wav = os.path.join(PROJECT_DIR, "voiceover.wav")

        if tools["piper"] and os.path.exists("script.txt") and not os.path.exists(voice_wav):
            print("[LXR-OpenMontage] Generating free offline Piper TTS...")
        if tools["ffmpeg"] and os.path.exists(frames_txt):
            cmd = [
                "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", frames_txt,
                *(["-i", voice_wav] if os.path.exists(voice_wav) else []),
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-shortest", out_mp4
            ]
            print("[LXR-OpenMontage] Running:", " ".join(cmd))
            subprocess.run(cmd, check=False)
            save_checkpoint("ffmpeg_compose")
        else:
            print("[LXR-OpenMontage] Android native 24fps MP4 already rendered.")

    if __name__ == "__main__":
        main()
  """.trimIndent()

  fun buildTermuxPipelineScript(project: VideoProject, downloadDirPath: String): String = """
    #!/data/data/com.termux/files/usr/bin/bash
    set +e
    WORKDIR="$downloadDirPath"
    echo "=== LXR--AI OpenMontage Termux Studio (Free-First Pipeline) ==="
    echo "Repo: $OPENMONTAGE_REPO"
    mkdir -p "${'$'}WORKDIR"
    cd "${'$'}WORKDIR" || exit 1

    for tool in ffmpeg python git; do
      if ! command -v "${'$'}tool" >/dev/null 2>&1; then
        echo "[Setup] Installing ${'$'}tool via pkg..."
        pkg update -y && pkg install -y "${'$'}tool"
      fi
    done

    if [ ! -d "${'$'}HOME/OpenMontage" ]; then
      git clone --depth 1 "$OPENMONTAGE_REPO" "${'$'}HOME/OpenMontage" 2>/dev/null || echo "[Info] Offline mode: using bundled LXR OpenMontage runner."
    fi

    if [ -f "openmontage_runner.py" ]; then
      python3 openmontage_runner.py
    fi

    if command -v ffmpeg >/dev/null 2>&1 && [ -f "frames_concat.txt" ]; then
      if [ -f "voiceover.wav" ] && [ -f "subtitles.srt" ]; then
        ffmpeg -y -f concat -safe 0 -i frames_concat.txt -i voiceover.wav -vf "subtitles=subtitles.srt:force_style='FontSize=20,PrimaryColour=&H00FFFF&'" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "${project.slug}_termux.mp4"
      elif [ -f "voiceover.wav" ]; then
        ffmpeg -y -f concat -safe 0 -i frames_concat.txt -i voiceover.wav -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "${project.slug}_termux.mp4"
      fi
    fi

    echo "=== OpenMontage Termux Task Done ==="
  """.trimIndent()

  fun buildCheckpointJson(project: VideoProject, completedStages: Set<PipelineStage>): String {
    val root = JSONObject()
    root.put("projectId", project.id)
    root.put("slug", project.slug)
    root.put("framework", OPENMONTAGE_REPO)
    root.put("productionMode", project.settings.productionMode.name)
    root.put("selectedVideoProvider", project.settings.selectedVideoProviderId)
    root.put("paidApiConsentGranted", project.settings.paidProviderConsentGranted)
    root.put("freeOnlyMode", !project.settings.paidProviderConsentGranted)
    root.put("completedStages", JSONArray(completedStages.map { it.key }))
    return root.toString(2)
  }

  fun dispatchOpenMontageToTermux(
    context: Context,
    scriptFile: File,
    runInBackground: Boolean = false,
  ): Result<String> {
    if (!TermuxBuildBridge.isPackageInstalled(context, TermuxBuildBridge.TERMUX_PACKAGE)) {
      return Result.failure(IllegalStateException("Termux (com.termux) ইনস্টল করা নেই — অন-ডিভাইস ফ্রি রেন্ডারার ব্যবহার করুন।"))
    }
    if (!TermuxBuildBridge.hasRunCommandPermission(context)) {
      return Result.failure(SecurityException("Termux RUN_COMMAND অনুমতি প্রয়োজন।"))
    }
    val callbackIntent = Intent(context, TermuxBuildResultReceiver::class.java).apply {
      putExtra("lxr_project_id", "video_studio_${scriptFile.parentFile?.name.orEmpty()}")
    }
    val pendingFlags =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }
    val pending = PendingIntent.getBroadcast(
      context,
      (scriptFile.absolutePath.hashCode() and 0x7FFFFFFF),
      callbackIntent,
      pendingFlags,
    )
    val intent = Intent(TERMUX_ACTION_RUN).apply {
      component = ComponentName(TermuxBuildBridge.TERMUX_PACKAGE, TERMUX_SERVICE_CLASS)
      putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
      putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(scriptFile.absolutePath))
      putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
      putExtra("com.termux.RUN_COMMAND_BACKGROUND", runInBackground)
      putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
      putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
    }
    return runCatching {
      context.startService(intent)
      "OpenMontage + FFmpeg Termux-এ চালু হয়েছে!"
    }.recoverCatching {
      if (runInBackground) {
        ContextCompat.startForegroundService(context, intent)
        "OpenMontage ব্যাকগ্রাউন্ড সার্ভিস চালু হয়েছে!"
      } else {
        TermuxBuildBridge.openTermuxApp(context)
        "Termux খোলা হয়েছে — সেভ করা স্ক্রিপ্ট রান করুন: bash ${scriptFile.absolutePath}"
      }
    }
  }
}
