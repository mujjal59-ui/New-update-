package com.example.builder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

data class TermuxCommandResult(
  val projectId: String,
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
  val errmsg: String,
)

class TermuxBuildResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val resultBundle = intent.getBundleExtra("result")
    val projectId = intent.getStringExtra("lxr_project_id").orEmpty()
    val exitCode = resultBundle?.getInt("exitCode", -1) ?: intent.getIntExtra("exitCode", -1)
    val stdout = resultBundle?.getString("stdout").orEmpty()
    val stderr = resultBundle?.getString("stderr").orEmpty()
    val errmsg = resultBundle?.getString("errmsg").orEmpty()
    Log.d("TermuxBuildReceiver", "Result for $projectId exit=$exitCode err=$errmsg")
    TermuxBuildBridge.emitResult(
      TermuxCommandResult(
        projectId = projectId,
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        errmsg = errmsg,
      )
    )
  }
}

object TermuxBuildBridge {
  private const val TAG = "TermuxBuildBridge"
  const val TERMUX_PACKAGE = "com.termux"
  const val TERMUX_API_PACKAGE = "com.termux.api"
  const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
  private const val TERMUX_SERVICE_CLASS = "com.termux.app.RunCommandService"
  private const val TERMUX_ACTION_RUN = "com.termux.RUN_COMMAND"

  private val _liveResults = MutableSharedFlow<TermuxCommandResult>(extraBufferCapacity = 8)
  val liveResults: SharedFlow<TermuxCommandResult> = _liveResults

  fun emitResult(result: TermuxCommandResult) {
    _liveResults.tryEmit(result)
  }

  fun isPackageInstalled(context: Context, pkg: String): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  fun hasRunCommandPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, TERMUX_RUN_COMMAND_PERMISSION) ==
      PackageManager.PERMISSION_GRANTED
  }

  fun inspectEnvironment(context: Context, repo: AppBuilderFileRepository, project: AppBuilderProject?): TermuxEnvironmentStatus {
    val installed = isPackageInstalled(context, TERMUX_PACKAGE)
    val apiInstalled = isPackageInstalled(context, TERMUX_API_PACKAGE)
    val permGranted = hasRunCommandPermission(context)
    val slug = project?.slug ?: "sample_app"
    val sharedDir = repo.sharedWorkspaceDir(slug)
    val scriptExists = File(sharedDir, "lxr_termux_build.sh").exists()

    val summary =
      when {
        !installed ->
          "ফোনে Termux (com.termux) ইনস্টল করা নেই। F-Droid বা GitHub থেকে ফ্রি Termux ইনস্টল করুন।"
        !permGranted ->
          "Termux ইনস্টল আছে ✓ — এখন LXR--AI App info / Permissions থেকে 'RUN_COMMAND' অনুমতি দিন।"
        else ->
          "Termux ও RUN_COMMAND প্রস্তুত ✓ — সরাসরি ফ্রি ওপেন-সোর্স Gradle/AAPT2 বিল্ড চালানো যাবে।"
      }

    return TermuxEnvironmentStatus(
      termuxInstalled = installed,
      termuxApiInstalled = apiInstalled,
      runCommandPermissionGranted = permGranted,
      sharedDownloadDirPath = sharedDir.absolutePath,
      scriptExported = scriptExists,
      setupSummaryBn = summary,
    )
  }

  fun firstTimeTermuxSetupCommand(projectSlug: String): String =
    "termux-setup-storage && mkdir -p ~/.termux && grep -q 'allow-external-apps' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings && bash /sdcard/Download/LXR-AppBuilder/$projectSlug/lxr_termux_build.sh"

  fun generateTermuxBuildScript(project: AppBuilderProject): String {
    val slug = project.slug
    val task = project.buildMode.gradleTask
    val apkOutName = "${slug}-${if (project.buildMode == ApkBuildMode.RELEASE) "release" else "debug"}.apk"
    return """
    #!/data/data/com.termux/files/usr/bin/bash
    set -o pipefail

    SHARED_ROOT="/sdcard/Download/LXR-AppBuilder"
    SRC_DIR="${'$'}SHARED_ROOT/$slug"
    WORK_DIR="${'$'}HOME/LXR-AppBuilder/$slug"
    TOOLCHAIN_DIR="${'$'}SHARED_ROOT/toolchain"
    LOG_FILE="${'$'}SRC_DIR/build.log"
    RESULT_JSON="${'$'}SRC_DIR/build_result.json"

    mkdir -p "${'$'}SRC_DIR" "${'$'}WORK_DIR" "${'$'}TOOLCHAIN_DIR"
    exec > >(tee "${'$'}LOG_FILE") 2>&1

    echo "=== LXR--AI Free Termux Build Engine ==="
    echo "Project: ${project.name} (${project.packageName})"
    echo "Checking Termux environment..."

    # 1. Install free open-source build packages if missing
    NEED_PKG=0
    for cmd in java gradle aapt2 wget unzip; do
      if ! command -v "${'$'}cmd" >/dev/null 2>&1; then
        NEED_PKG=1
      fi
    done

    if [ "${'$'}NEED_PKG" -eq 1 ]; then
      echo "Installing missing free Termux packages (openjdk-17, gradle, aapt2, wget, unzip)..."
      pkg update -y && pkg install -y openjdk-17 gradle aapt2 wget unzip
    fi

    # 2. Prepare Android SDK from local Download toolchain or free Google repo
    export ANDROID_HOME="${'$'}HOME/android-sdk"
    if [ ! -d "${'$'}ANDROID_HOME/platforms/android-36" ]; then
      mkdir -p "${'$'}ANDROID_HOME/cmdline-tools"
      if [ -f "${'$'}TOOLCHAIN_DIR/cmdline-tools.zip" ]; then
        echo "Using local toolchain zip from Download folder..."
        unzip -qo "${'$'}TOOLCHAIN_DIR/cmdline-tools.zip" -d "${'$'}ANDROID_HOME/cmdline-tools/"
      elif [ ! -d "${'$'}ANDROID_HOME/cmdline-tools/latest" ]; then
        echo "Downloading free Android cmdline-tools from dl.google.com..."
        wget -q -O "${'$'}TOOLCHAIN_DIR/cmdline-tools.zip" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        unzip -qo "${'$'}TOOLCHAIN_DIR/cmdline-tools.zip" -d "${'$'}ANDROID_HOME/cmdline-tools/"
      fi
      if [ -d "${'$'}ANDROID_HOME/cmdline-tools/cmdline-tools" ] && [ ! -d "${'$'}ANDROID_HOME/cmdline-tools/latest" ]; then
        mv "${'$'}ANDROID_HOME/cmdline-tools/cmdline-tools" "${'$'}ANDROID_HOME/cmdline-tools/latest"
      fi
      export PATH="${'$'}ANDROID_HOME/cmdline-tools/latest/bin:${'$'}PATH"
      yes | sdkmanager --licenses >/dev/null 2>&1 || true
      sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
    fi

    # 3. Sync source files to Termux workspace
    cp -r "${'$'}SRC_DIR"/* "${'$'}WORK_DIR"/
    cd "${'$'}WORK_DIR" || exit 1

    AAPT2_BIN="${'$'}(command -v aapt2)"
    echo "Using Termux AAPT2: ${'$'}AAPT2_BIN"
    echo "Running Gradle task: $task..."

    if [ -n "${'$'}AAPT2_BIN" ]; then
      gradle $task --no-daemon -Pandroid.aapt2FromMavenOverride="${'$'}AAPT2_BIN"
    else
      gradle $task --no-daemon
    fi
    BUILD_EXIT=${'$'}?

    OUT_APK="${'$'}(find app/build/outputs/apk -type f -name '*.apk' | head -n 1)"
    if [ "${'$'}BUILD_EXIT" -eq 0 ] && [ -n "${'$'}OUT_APK" ] && [ -f "${'$'}OUT_APK" ]; then
      cp -f "${'$'}OUT_APK" "${'$'}SRC_DIR/$apkOutName"
      cp -f "${'$'}OUT_APK" "${'$'}SHARED_ROOT/$apkOutName"
      APK_SIZE=${'$'}(wc -c < "${'$'}SRC_DIR/$apkOutName")
      echo "SUCCESS: Built $apkOutName (${'$'}APK_SIZE bytes)"
      printf '{"status":"success","exitCode":0,"apkPath":"%s","sizeBytes":%s}\n' "${'$'}SRC_DIR/$apkOutName" "${'$'}APK_SIZE" > "${'$'}RESULT_JSON"
      exit 0
    else
      echo "ERROR: Build failed with exit code ${'$'}BUILD_EXIT"
      printf '{"status":"failed","exitCode":%s,"apkPath":"","sizeBytes":0}\n' "${'$'}BUILD_EXIT" > "${'$'}RESULT_JSON"
      exit "${'$'}BUILD_EXIT"
    fi
    """.trimIndent()
  }

  /**
   * Syncs all project files + `lxr_termux_build.sh` into Downloads/LXR-AppBuilder/<slug>/
   * so both Termux and LXR--AI can access the exact same workspace.
   */
  fun syncProjectToSharedWorkspace(
    repo: AppBuilderFileRepository,
    project: AppBuilderProject,
  ): Result<File> {
    return runCatching {
      val dir = repo.sharedWorkspaceDir(project.slug)
      project.files.forEach { (relPath, content) ->
        val clean = relPath.trimStart('/').replace("..", "")
        if (clean.isNotBlank()) {
          val dest = File(dir, clean)
          dest.parentFile?.mkdirs()
          dest.writeText(content, Charsets.UTF_8)
        }
      }
      val scriptFile = File(dir, "lxr_termux_build.sh")
      scriptFile.writeText(generateTermuxBuildScript(project), Charsets.UTF_8)
      scriptFile.setExecutable(true, false)
      dir
    }
  }

  /**
   * Fires a real Termux RUN_COMMAND service intent to execute `lxr_termux_build.sh` inside Termux.
   */
  fun dispatchBuildToTermux(
    context: Context,
    project: AppBuilderProject,
    sharedDir: File,
    runInBackground: Boolean = false,
  ): Result<String> {
    if (!isPackageInstalled(context, TERMUX_PACKAGE)) {
      return Result.failure(IllegalStateException("Termux ইনস্টল করা নেই (com.termux পাওয়া যায়নি)।"))
    }
    if (!hasRunCommandPermission(context)) {
      return Result.failure(SecurityException("Termux RUN_COMMAND পারমিশন দেওয়া নেই।"))
    }

    val scriptPath = File(sharedDir, "lxr_termux_build.sh").absolutePath
    val callbackIntent =
      Intent(context, TermuxBuildResultReceiver::class.java).apply {
        putExtra("lxr_project_id", project.id)
      }
    val pendingFlags =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }
    val pending = PendingIntent.getBroadcast(context, project.id.hashCode(), callbackIntent, pendingFlags)

    val intent =
      Intent(TERMUX_ACTION_RUN).apply {
        component = ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE_CLASS)
        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(scriptPath))
        putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", runInBackground)
        putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
      }

    return runCatching {
      ContextCompat.startForegroundService(context, intent)
      "Termux-এ বিল্ড কমান্ড পাঠানো হয়েছে: bash $scriptPath"
    }.recoverCatching { firstErr ->
      context.startService(intent)
      "Termux-এ বিল্ড সার্ভিস চালু হয়েছে (fallback): ${firstErr.message.orEmpty()}"
    }
  }

  fun readSharedBuildArtifacts(
    repo: AppBuilderFileRepository,
    project: AppBuilderProject,
  ): Pair<String?, ApkVerificationResult?> {
    val dir = repo.sharedWorkspaceDir(project.slug)
    val logFile = File(dir, "build.log")
    val logText = if (logFile.exists()) logFile.readText(Charsets.UTF_8) else null

    val resultFile = File(dir, "build_result.json")
    if (resultFile.exists()) {
      runCatching {
        val json = JSONObject(resultFile.readText(Charsets.UTF_8))
        val apkPath = json.optString("apkPath")
        if (apkPath.isNotBlank() && File(apkPath).exists()) {
          return logText to repo.verifyApkFile(File(apkPath), project.buildMode)
        }
      }
    }

    val foundApk = repo.findBuiltApkForProject(project)
    return logText to foundApk
  }

  fun openTermuxApp(context: Context): Boolean {
    return try {
      val launch = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
      if (launch != null) {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
      } else {
        false
      }
    } catch (_: Exception) {
      false
    }
  }
}
