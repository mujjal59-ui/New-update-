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

  private val _liveResults = MutableSharedFlow<TermuxCommandResult>(extraBufferCapacity = 16)
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
    val safOk = repo.hasSafTreeAccess()
    val slug = project?.slug ?: "sample_app"
    val sharedDir = repo.sharedWorkspaceDir(slug)
    val scriptExists = File(sharedDir, "lxr_termux_build.sh").exists()

    val summary =
      when {
        !installed ->
          "ফোনে Termux (com.termux) ইনস্টল করা নেই। F-Droid বা GitHub থেকে ফ্রি Termux ইনস্টল করুন।"
        !permGranted ->
          "Termux ইনস্টল আছে ✓ — এখন 'Termux অনুমতি দিন' বাটনে চাপুন।"
        !safOk ->
          "Termux প্রস্তুত ✓ — পূর্ণাঙ্গ APK ও লগ পড়ার জন্য নিচে 'Download ফোল্ডার এক্সেস' অনুমোদন করলে আরও নির্ভুল হবে।"
        else ->
          "Termux, RUN_COMMAND ও Download ফোল্ডার সম্পূর্ণ প্রস্তুত ✓ — সরাসরি APK বিল্ড ও যাচাই চলবে।"
      }

    return TermuxEnvironmentStatus(
      termuxInstalled = installed,
      termuxApiInstalled = apiInstalled,
      runCommandPermissionGranted = permGranted,
      sharedDownloadDirPath = sharedDir.absolutePath,
      scriptExported = scriptExists,
      hasSafAccess = safOk,
      safFolderLabel = if (safOk) "Downloads/LXR-AppBuilder ✓" else "অনুমোদিত নয়",
      setupSummaryBn = summary,
    )
  }

  fun firstTimeTermuxSetupCommand(projectSlug: String): String =
    "[ -d ~/storage/downloads ] || yes | termux-setup-storage; mkdir -p ~/.termux && (grep -q 'allow-external-apps' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties) && termux-reload-settings; bash /sdcard/Download/LXR-AppBuilder/$projectSlug/lxr_termux_build.sh"

  fun generateTermuxBuildScript(project: AppBuilderProject): String {
    val slug = project.slug
    val task = project.buildMode.gradleTask
    val isAab = project.buildMode == ApkBuildMode.PLAY_STORE_AAB
    val apkOutName =
      when (project.buildMode) {
        ApkBuildMode.PLAY_STORE_AAB -> "${slug}-playstore.aab"
        ApkBuildMode.RELEASE -> "${slug}-release.apk"
        ApkBuildMode.DEBUG -> "${slug}-debug.apk"
      }
    return """
    #!/data/data/com.termux/files/usr/bin/bash
    set -o pipefail

    SHARED_ROOT="/sdcard/Download/LXR-AppBuilder"
    DL_PUBLIC="/sdcard/Download"
    SRC_DIR="${'$'}SHARED_ROOT/$slug"
    WORK_DIR="${'$'}HOME/LXR-AppBuilder/$slug"
    TOOLCHAIN_DIR="${'$'}SHARED_ROOT/toolchain"
    LOG_FILE="${'$'}SRC_DIR/build.log"
    RESULT_JSON="${'$'}SRC_DIR/build_result.json"

    mkdir -p "${'$'}SRC_DIR" "${'$'}TOOLCHAIN_DIR"
    : > "${'$'}LOG_FILE"
    : > "${'$'}RESULT_JSON"
    exec > >(tee -a "${'$'}LOG_FILE") 2>&1

    echo "=== LXR--AI Free Termux Build Engine ==="
    echo "Project: ${project.name} (${project.packageName}) | Mode: ${project.buildMode.labelBn}"
    echo "LXR_PROGRESS:STEP=1:ENV_CHECK"

    # 1. Install free open-source build packages from Termux repos if missing
    NEED_PKG=0
    for cmd in java gradle aapt2 wget unzip keytool; do
      if ! command -v "${'$'}cmd" >/dev/null 2>&1; then
        NEED_PKG=1
      fi
    done

    if [ "${'$'}NEED_PKG" -eq 1 ]; then
      echo "Installing missing free Termux packages (openjdk-17, gradle, aapt2, wget, unzip)..."
      pkg update -y && pkg install -y openjdk-17 gradle aapt2 wget unzip
    fi

    # 2. Prepare Android SDK from Download/LXR-AppBuilder/toolchain or free Google repository
    echo "LXR_PROGRESS:STEP=2:SDK_CHECK"
    export ANDROID_HOME="${'$'}HOME/android-sdk"
    export ANDROID_SDK_ROOT="${'$'}HOME/android-sdk"
    if [ ! -d "${'$'}ANDROID_HOME/platforms/android-36" ]; then
      mkdir -p "${'$'}ANDROID_HOME/cmdline-tools"
      if [ -f "${'$'}TOOLCHAIN_DIR/cmdline-tools.zip" ] && [ -s "${'$'}TOOLCHAIN_DIR/cmdline-tools.zip" ]; then
        echo "Using cached Android cmdline-tools from Download folder..."
        unzip -qo "${'$'}TOOLCHAIN_DIR/cmdline-tools.zip" -d "${'$'}ANDROID_HOME/cmdline-tools/"
      elif [ ! -d "${'$'}ANDROID_HOME/cmdline-tools/latest" ]; then
        echo "DOWNLOAD_NOTICE: ফ্রি Android cmdline-tools (~140MB) গুগল সার্ভার থেকে ডাউনলোড করা হচ্ছে..."
        echo "Downloading free Android cmdline-tools into Download toolchain..."
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

    # 3. Sync clean source files to Termux internal workspace
    echo "LXR_PROGRESS:STEP=3:SYNC_SOURCE"
    rm -rf "${'$'}WORK_DIR"
    mkdir -p "${'$'}WORK_DIR"
    cp -r "${'$'}SRC_DIR"/. "${'$'}WORK_DIR"/
    cd "${'$'}WORK_DIR" || exit 1

    # Ensure persistent Play Store upload keystore if building Play Store AAB/Release without external keystore.properties
    KEYSTORE_BACKUP_DIR="/sdcard/Download/LxR/Backups/keystore"
    mkdir -p "${'$'}KEYSTORE_BACKUP_DIR"
    if [ "${if (isAab) "1" else "0"}" = "1" ] && [ ! -f "keystore.properties" ]; then
      if [ ! -f "${'$'}KEYSTORE_BACKUP_DIR/lxr_playstore_upload.jks" ]; then
        UPLOAD_PASS="lxr_${'$'}(date +%Y)_upload"
        keytool -genkeypair -v -keystore "${'$'}KEYSTORE_BACKUP_DIR/lxr_playstore_upload.jks" -alias lxr_upload -keyalg RSA -keysize 2048 -validity 10000 -storepass "${'$'}UPLOAD_PASS" -keypass "${'$'}UPLOAD_PASS" -dname "CN=${project.name}, O=LXR Studio, C=BD" >/dev/null 2>&1 || true
        if [ -f "${'$'}KEYSTORE_BACKUP_DIR/lxr_playstore_upload.jks" ]; then
          printf "storeFile=lxr_playstore_upload.jks\nstorePassword=%s\nkeyAlias=lxr_upload\nkeyPassword=%s\n" "${'$'}UPLOAD_PASS" "${'$'}UPLOAD_PASS" > "${'$'}KEYSTORE_BACKUP_DIR/keystore.properties"
        fi
      fi
      if [ -f "${'$'}KEYSTORE_BACKUP_DIR/lxr_playstore_upload.jks" ] && [ -f "${'$'}KEYSTORE_BACKUP_DIR/keystore.properties" ]; then
        cp -f "${'$'}KEYSTORE_BACKUP_DIR/lxr_playstore_upload.jks" "${'$'}WORK_DIR/"
        cp -f "${'$'}KEYSTORE_BACKUP_DIR/keystore.properties" "${'$'}WORK_DIR/"
      fi
    fi

    AAPT2_BIN="${'$'}(command -v aapt2)"
    if [ -f "gradle.properties" ]; then
      grep -q 'kotlin.compiler.execution.strategy' gradle.properties || echo "kotlin.compiler.execution.strategy=in-process" >> gradle.properties
      grep -q 'org.gradle.daemon' gradle.properties || echo "org.gradle.daemon=false" >> gradle.properties
      if [ -n "${'$'}AAPT2_BIN" ]; then
        grep -q 'android.aapt2FromMavenOverride' gradle.properties || echo "android.aapt2FromMavenOverride=${'$'}AAPT2_BIN" >> gradle.properties
      fi
    fi

    echo "Using Termux AAPT2: ${'$'}AAPT2_BIN"
    echo "LXR_PROGRESS:STEP=4:GRADLE_BUILD"
    echo "Running Gradle task: $task..."

    export GRADLE_OPTS="-Dorg.gradle.daemon=false -Xmx1024m"
    if [ -n "${'$'}AAPT2_BIN" ]; then
      gradle $task --no-daemon --console=plain -Pandroid.aapt2FromMavenOverride="${'$'}AAPT2_BIN"
    else
      gradle $task --no-daemon --console=plain
    fi
    BUILD_EXIT=${'$'}?

    echo "LXR_PROGRESS:STEP=5:VERIFY_APK"
    if [ "${if (isAab) "1" else "0"}" = "1" ]; then
      OUT_APK="${'$'}(find app/build/outputs/bundle -type f -name '*.aab' | head -n 1)"
    else
      OUT_APK="${'$'}(find app/build/outputs/apk -type f -name '*.apk' | head -n 1)"
    fi
    LXR_APK_DIR="/sdcard/Download/LxR/APK/$slug"
    LXR_LOG_DIR="/sdcard/Download/LxR/Logs/$slug"
    mkdir -p "${'$'}LXR_APK_DIR" "${'$'}LXR_LOG_DIR"
    if [ "${'$'}BUILD_EXIT" -eq 0 ] && [ -n "${'$'}OUT_APK" ] && [ -f "${'$'}OUT_APK" ]; then
      cp -f "${'$'}OUT_APK" "${'$'}SRC_DIR/$apkOutName"
      cp -f "${'$'}OUT_APK" "${'$'}SHARED_ROOT/$apkOutName"
      cp -f "${'$'}OUT_APK" "${'$'}LXR_APK_DIR/$apkOutName"
      cp -f "${'$'}LOG_FILE" "${'$'}LXR_LOG_DIR/build.log" 2>/dev/null || true
      APK_SIZE=${'$'}(wc -c < "${'$'}SRC_DIR/$apkOutName")
      echo "SUCCESS: Built $apkOutName (${'$'}APK_SIZE bytes) -> ${'$'}LXR_APK_DIR/$apkOutName"
      JSON_LINE=${'$'}(printf '{"status":"success","exitCode":0,"apkPath":"%s","sizeBytes":%s}' "${'$'}LXR_APK_DIR/$apkOutName" "${'$'}APK_SIZE")
      printf '%s\n' "${'$'}JSON_LINE" > "${'$'}RESULT_JSON"
      echo "LXR_RESULT_JSON=${'$'}JSON_LINE"
      exit 0
    else
      echo "ERROR: Build failed with exit code ${'$'}BUILD_EXIT"
      JSON_LINE=${'$'}(printf '{"status":"failed","exitCode":%s,"apkPath":"","sizeBytes":0}' "${'$'}BUILD_EXIT")
      printf '%s\n' "${'$'}JSON_LINE" > "${'$'}RESULT_JSON"
      echo "LXR_RESULT_JSON=${'$'}JSON_LINE"
      exit "${'$'}BUILD_EXIT"
    fi
    """.trimIndent()
  }

  /**
   * Syncs all project files + `lxr_termux_build.sh` into Downloads/LXR-AppBuilder/<slug>/
   * and pre-creates `build.log` & `build_result.json` owned by LXR so both Termux & LXR read them cleanly.
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

      // Pre-create writable output files owned by LXR UID so in-place redirection stays readable
      val logFile = File(dir, "build.log")
      logFile.writeText("", Charsets.UTF_8)
      val resFile = File(dir, "build_result.json")
      resFile.writeText("""{"status":"pending","exitCode":-1}""", Charsets.UTF_8)
      dir
    }
  }

  /**
   * Fires a safe Termux RUN_COMMAND service intent WITHOUT calling startForegroundService on
   * an interactive Termux terminal launch (fixes ForegroundServiceDidNotStartInTimeException crash).
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
    val reqCode = (project.id.hashCode() and 0x7FFFFFFF)
    val pending = PendingIntent.getBroadcast(context, reqCode, callbackIntent, pendingFlags)

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
      context.startService(intent)
      "Termux-এ লাইভ বিল্ড শুরু হয়েছে: bash $scriptPath"
    }.recoverCatching { firstErr ->
      if (runInBackground) {
        ContextCompat.startForegroundService(context, intent)
        "Termux ব্যাকগ্রাউন্ড সার্ভিস চালু হয়েছে: bash $scriptPath"
      } else {
        // Fallback if OS blocked background service start while Activity was switching
        openTermuxApp(context)
        "Termux খোলা হয়েছে — সেটআপ/বিল্ড কমান্ড Termux-এ চালান (${firstErr.message?.take(40).orEmpty()})"
      }
    }
  }

  fun parseBuildResultStatus(jsonRaw: String?): TermuxResultJson? {
    if (jsonRaw.isNullOrBlank()) return null
    return runCatching {
      val clean = jsonRaw.substringAfter("LXR_RESULT_JSON=").trim()
      val obj = JSONObject(clean)
      TermuxResultJson(
        status = obj.optString("status"),
        exitCode = obj.optInt("exitCode", -1),
        apkPath = obj.optString("apkPath"),
        sizeBytes = obj.optLong("sizeBytes", 0L),
      )
    }.getOrNull()
  }

  fun readSharedBuildArtifacts(
    repo: AppBuilderFileRepository,
    project: AppBuilderProject,
  ): Triple<String?, TermuxResultJson?, ApkVerificationResult?> {
    val logText = repo.readSharedTextCapped(project.slug, "build.log", maxBytes = 65536)
    val resultJsonText = repo.readSharedTextCapped(project.slug, "build_result.json", maxBytes = 8192)
    val parsedJson = parseBuildResultStatus(resultJsonText)

    val foundApk =
      if (parsedJson != null && parsedJson.apkPath.isNotBlank() && File(parsedJson.apkPath).let { it.exists() && it.canRead() }) {
        repo.verifyApkFile(File(parsedJson.apkPath), project.buildMode, "Downloads/LXR-AppBuilder/${project.slug}")
      } else {
        repo.findBuiltApkForProject(project)
      }
    return Triple(logText, parsedJson, foundApk)
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

data class TermuxResultJson(
  val status: String,
  val exitCode: Int,
  val apkPath: String,
  val sizeBytes: Long,
)
