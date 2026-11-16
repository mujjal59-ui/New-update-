package com.example.builder

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

class AppBuilderFileRepository(private val context: Context) {

  private fun projectsRootDir(): File =
    File(context.filesDir, "lxr_app_builder_projects").apply { mkdirs() }

  private fun exportsRootDir(): File =
    File(context.filesDir, "lxr_app_builder_exports").apply { mkdirs() }

  fun sharedWorkspaceDir(projectSlug: String): File {
    val base =
      try {
        File(
          Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
          "LXR-AppBuilder/$projectSlug",
        )
      } catch (_: Throwable) {
        File(exportsRootDir(), projectSlug)
      }
    if (!base.exists()) base.mkdirs()
    return base
  }

  fun publicDownloadsFolderLabel(): String = "Downloads/LXR-AppBuilder"

  fun saveProject(project: AppBuilderProject): AppBuilderProject {
    val updated = project.copy(updatedAtMs = System.currentTimeMillis())
    val dir = File(projectsRootDir(), updated.id).apply { mkdirs() }
    val sourceRoot = File(dir, "source").apply { mkdirs() }

    // Write individual project source files so they mirror real directory structure
    sourceRoot.deleteRecursively()
    sourceRoot.mkdirs()
    updated.files.forEach { (relPath, content) ->
      val cleanRel = relPath.trimStart('/').replace("..", "")
      if (cleanRel.isNotBlank()) {
        val target = File(sourceRoot, cleanRel)
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
      }
    }

    val meta = JSONObject().apply {
      put("id", updated.id)
      put("name", updated.name)
      put("description", updated.description)
      put("category", updated.category.name)
      put("designTheme", updated.designTheme.name)
      put("packageName", updated.packageName)
      put("minSdk", updated.minSdk.name)
      put("language", updated.language.name)
      put("buildMode", updated.buildMode.name)
      put("createdAtMs", updated.createdAtMs)
      put("updatedAtMs", updated.updatedAtMs)
      put("analysisReport", updated.analysisReport)
      put("architecturePlan", updated.architecturePlan)
      put("buildStatus", updated.buildStatus.name)
      put("dependenciesSummary", JSONArray(updated.dependenciesSummary))
      put("buildLogs", JSONArray(updated.buildLogs.takeLast(80)))
      put("apkFilePath", updated.apkResult.apkFilePath)
      put("apkFileName", updated.apkResult.apkFileName)
      put("apkSizeBytes", updated.apkResult.sizeBytes)
      put("downloadExportPath", updated.apkResult.downloadExportPath)

      val filesObj = JSONObject()
      updated.files.forEach { (k, v) -> filesObj.put(k, v) }
      put("files", filesObj)
    }

    File(dir, "project.json").writeText(meta.toString(2), Charsets.UTF_8)
    return updated
  }

  fun loadProjectHistory(): List<AppBuilderProject> {
    val root = projectsRootDir()
    val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
    return dirs.mapNotNull { dir ->
      val metaFile = File(dir, "project.json")
      if (!metaFile.exists()) return@mapNotNull null
      runCatching { parseProjectJson(metaFile.readText(Charsets.UTF_8)) }.getOrNull()
    }.sortedByDescending { it.updatedAtMs }
  }

  fun renameProject(projectId: String, newName: String): AppBuilderProject? {
    val existing = loadProjectHistory().firstOrNull { it.id == projectId } ?: return null
    val trimmed = newName.trim().ifBlank { return existing }
    return saveProject(existing.copy(name = trimmed, updatedAtMs = System.currentTimeMillis()))
  }

  fun deleteProject(projectId: String): Boolean {
    val dir = File(projectsRootDir(), projectId)
    return if (dir.exists()) dir.deleteRecursively() else true
  }

  fun hasExistingProjectNamed(name: String, excludingId: String? = null): Boolean {
    return loadProjectHistory().any {
      it.name.equals(name.trim(), ignoreCase = true) && it.id != excludingId
    }
  }

  fun createProjectZipBackup(project: AppBuilderProject, includeBackupSuffix: Boolean): Result<Pair<File, String>> {
    return runCatching {
      val stamp = if (includeBackupSuffix) "_backup_${project.updatedAtMs / 1000}" else "_source"
      val zipName = "${project.slug}$stamp.zip"
      val internalZip = File(exportsRootDir(), zipName)
      ZipOutputStream(BufferedOutputStream(FileOutputStream(internalZip))).use { zos ->
        project.files.forEach { (path, text) ->
          zos.putNextEntry(ZipEntry("${project.slug}/$path"))
          zos.write(text.toByteArray(Charsets.UTF_8))
          zos.closeEntry()
        }
      }
      val publicLocation = saveFileToDownloads(internalZip, zipName, "application/zip")
      internalZip to publicLocation
    }
  }

  fun saveApkToDownloads(apkFile: File, targetFileName: String): Result<String> {
    return runCatching {
      require(apkFile.exists() && apkFile.length() > 0L) { "APK ফাইলটি পাওয়া যায়নি বা খালি।" }
      saveFileToDownloads(apkFile, targetFileName, "application/vnd.android.package-archive")
    }
  }

  private fun saveFileToDownloads(source: File, fileName: String, mimeType: String): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val resolver = context.contentResolver
      val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LXR-AppBuilder")
        put(MediaStore.Downloads.IS_PENDING, 1)
      }
      val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      if (uri != null) {
        resolver.openOutputStream(uri)?.use { out ->
          FileInputStream(source).use { input -> input.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Downloads/LXR-AppBuilder/$fileName"
      }
    }
    val fallbackDir = File(exportsRootDir(), "downloads").apply { mkdirs() }
    val dest = File(fallbackDir, fileName)
    source.copyTo(dest, overwrite = true)
    return dest.absolutePath
  }

  fun verifyApkFile(apkFile: File, mode: ApkBuildMode, downloadExportPath: String = ""): ApkVerificationResult {
    if (!apkFile.exists() || !apkFile.isFile) {
      return ApkVerificationResult(apkFilePath = apkFile.absolutePath, apkFileName = apkFile.name, exists = false, buildMode = mode)
    }
    val size = apkFile.length()
    var hasManifest = false
    if (size > 100L) {
      runCatching {
        ZipInputStream(FileInputStream(apkFile)).use { zis ->
          var entry = zis.nextEntry
          while (entry != null) {
            if (entry.name == "AndroidManifest.xml") {
              hasManifest = true
              break
            }
            entry = zis.nextEntry
          }
        }
      }
    }
    return ApkVerificationResult(
      apkFilePath = apkFile.absolutePath,
      apkFileName = apkFile.name,
      exists = true,
      sizeBytes = size,
      hasValidApkArchive = hasManifest,
      buildMode = mode,
      downloadExportPath = downloadExportPath.ifBlank { apkFile.absolutePath },
    )
  }

  fun findBuiltApkForProject(project: AppBuilderProject): ApkVerificationResult? {
    val candidates = buildList {
      if (project.apkResult.apkFilePath.isNotBlank()) {
        add(File(project.apkResult.apkFilePath))
      }
      val sharedDir = sharedWorkspaceDir(project.slug)
      add(File(sharedDir, "${project.slug}-debug.apk"))
      add(File(sharedDir, "${project.slug}-release.apk"))
      add(File(sharedDir, "app-debug.apk"))
      add(File(sharedDir, "app-release.apk"))
      add(File(sharedDir, "app/build/outputs/apk/debug/app-debug.apk"))
      add(File(sharedDir, "app/build/outputs/apk/release/app-release.apk"))
      add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LXR-AppBuilder/${project.slug}-debug.apk"))
    }
    val found = candidates.firstOrNull { it.exists() && it.length() > 0L } ?: return null
    return verifyApkFile(found, project.buildMode)
  }

  fun shareableUriForFile(file: File): Uri? {
    return runCatching {
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
  }

  fun createInstallApkIntent(apkFile: File): Intent? {
    val uri = shareableUriForFile(apkFile) ?: return null
    return Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  fun canRequestPackageInstalls(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.packageManager.canRequestPackageInstalls()
    } else {
      true
    }
  }

  fun openUnknownAppsInstallSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      runCatching {
        context.startActivity(
          Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
          ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
      }
    }
  }

  private fun parseProjectJson(raw: String): AppBuilderProject {
    val o = JSONObject(raw)
    val filesMap = linkedMapOf<String, String>()
    val filesObj = o.optJSONObject("files")
    if (filesObj != null) {
      val keys = filesObj.keys()
      while (keys.hasNext()) {
        val k = keys.next()
        filesMap[k] = filesObj.optString(k)
      }
    }
    val deps = buildList {
      val arr = o.optJSONArray("dependenciesSummary")
      if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
    }
    val logs = buildList {
      val arr = o.optJSONArray("buildLogs")
      if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
    }
    val mode = runCatching { ApkBuildMode.valueOf(o.optString("buildMode")) }.getOrDefault(ApkBuildMode.DEBUG)
    val apkPath = o.optString("apkFilePath")
    val apkResult =
      if (apkPath.isNotBlank() && File(apkPath).exists()) {
        verifyApkFile(File(apkPath), mode, o.optString("downloadExportPath"))
      } else {
        ApkVerificationResult(
          apkFilePath = apkPath,
          apkFileName = o.optString("apkFileName"),
          exists = apkPath.isNotBlank() && File(apkPath).exists(),
          sizeBytes = o.optLong("apkSizeBytes", 0L),
          buildMode = mode,
          downloadExportPath = o.optString("downloadExportPath"),
        )
      }
    return AppBuilderProject(
      id = o.optString("id"),
      name = o.optString("name", "LXR App"),
      description = o.optString("description"),
      category = runCatching { AppCategory.valueOf(o.optString("category")) }.getOrDefault(AppCategory.UTILITY),
      designTheme = runCatching { AppDesignTheme.valueOf(o.optString("designTheme")) }.getOrDefault(AppDesignTheme.CYBER_NEON),
      packageName = o.optString("packageName", "com.lxr.appbuilder.sample"),
      minSdk = runCatching { AndroidApiLevel.valueOf(o.optString("minSdk")) }.getOrDefault(AndroidApiLevel.API_26),
      language = runCatching { ProgrammingLanguage.valueOf(o.optString("language")) }.getOrDefault(ProgrammingLanguage.KOTLIN),
      buildMode = mode,
      createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
      updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
      analysisReport = o.optString("analysisReport"),
      architecturePlan = o.optString("architecturePlan"),
      dependenciesSummary = deps,
      files = filesMap,
      buildStatus = runCatching { BuildStatus.valueOf(o.optString("buildStatus")) }.getOrDefault(BuildStatus.GENERATED),
      buildLogs = logs,
      apkResult = apkResult,
    )
  }
}
