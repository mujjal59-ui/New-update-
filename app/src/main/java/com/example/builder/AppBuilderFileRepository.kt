package com.example.builder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
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

  private val prefs by lazy {
    context.getSharedPreferences("lxr_app_builder_prefs", Context.MODE_PRIVATE)
  }

  private fun projectsRootDir(): File =
    File(context.filesDir, "lxr_app_builder_projects").apply { mkdirs() }

  private fun exportsRootDir(): File =
    File(context.filesDir, "lxr_app_builder_exports").apply { mkdirs() }

  private fun verifiedApkCacheDir(): File =
    File(context.cacheDir, "verified_apks").apply { mkdirs() }

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

  fun saveSafTreeUri(uri: Uri) {
    runCatching {
      val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      context.contentResolver.takePersistableUriPermission(uri, flags)
    }
    prefs.edit().putString("saf_tree_uri", uri.toString()).apply()
  }

  fun getSafTreeUri(): Uri? {
    val raw = prefs.getString("saf_tree_uri", null)?.takeIf { it.isNotBlank() } ?: return null
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    val persisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    return if (persisted) uri else null
  }

  fun hasSafTreeAccess(): Boolean = getSafTreeUri() != null

  fun createOpenDownloadsTreeIntent(): Intent {
    return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
      addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
          Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
          Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
      )
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val initial =
          Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
      }
    }
  }

  fun saveProject(project: AppBuilderProject): AppBuilderProject {
    val updated = project.copy(updatedAtMs = System.currentTimeMillis())
    val dir = File(projectsRootDir(), updated.id).apply { mkdirs() }
    val sourceRoot = File(dir, "source").apply { mkdirs() }

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
      put("buildLogs", JSONArray(updated.buildLogs.takeLast(120)))
      put("apkFilePath", updated.apkResult.apkFilePath)
      put("apkFileName", updated.apkResult.apkFileName)
      put("apkSizeBytes", updated.apkResult.sizeBytes)
      put("hasValidApkArchive", updated.apkResult.hasValidApkArchive)
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
      val lxrCat =
        if (includeBackupSuffix) com.example.service.LxrStorageCategory.BACKUPS
        else com.example.service.LxrStorageCategory.APPS
      val lxrCopy =
        com.example.service.LxrDownloadStorage.copyFileDeliverable(
          context = context,
          srcFile = internalZip,
          category = lxrCat,
          projectSlug = project.slug,
          desiredFileName = zipName,
        )
      val publicLocation = saveFileToDownloads(internalZip, zipName, "application/zip", "${Environment.DIRECTORY_DOWNLOADS}/LxR/${lxrCat.folderName}/${project.slug}")
      val finalPath = lxrCopy.getOrNull()?.absolutePath ?: publicLocation
      internalZip to finalPath
    }
  }

  fun saveApkToDownloads(apkFile: File, targetFileName: String, projectSlug: String = "lxr_app"): Result<String> {
    return runCatching {
      require(apkFile.exists() && apkFile.length() > 0L) { "APK ফাইলটি পাওয়া যায়নি বা খালি।" }
      val cleanSlug = com.example.service.LxrDownloadStorage.sanitizeSlug(projectSlug)
      val lxrCopy =
        com.example.service.LxrDownloadStorage.copyFileDeliverable(
          context = context,
          srcFile = apkFile,
          category = com.example.service.LxrStorageCategory.APK,
          projectSlug = cleanSlug,
          desiredFileName = targetFileName,
        )
      val mediaPath =
        saveFileToDownloads(
          source = apkFile,
          fileName = targetFileName,
          mimeType = "application/vnd.android.package-archive",
          relativeSubdir = "${Environment.DIRECTORY_DOWNLOADS}/LxR/APK/$cleanSlug",
        )
      lxrCopy.getOrNull()?.let { "Downloads/LxR/APK/$cleanSlug/${it.name}" } ?: mediaPath
    }
  }

  private fun saveFileToDownloads(
    source: File,
    fileName: String,
    mimeType: String,
    relativeSubdir: String = "${Environment.DIRECTORY_DOWNLOADS}/LXR-AppBuilder",
  ): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val resolver = context.contentResolver
      val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, relativeSubdir)
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
        return "$relativeSubdir/$fileName"
      }
    }
    val fallbackDir = File(exportsRootDir(), "downloads").apply { mkdirs() }
    val dest = com.example.service.LxrDownloadStorage.uniqueTargetFile(fallbackDir, fileName)
    source.copyTo(dest, overwrite = false)
    return dest.absolutePath
  }

  fun readSharedTextCapped(projectSlug: String, fileName: String, maxBytes: Int = 65536): String? {
    // 1. Direct File check (works when owned by our UID / pre-created via LXR--AI)
    val directFile = File(sharedWorkspaceDir(projectSlug), fileName)
    if (directFile.exists() && directFile.canRead() && directFile.length() > 0L) {
      runCatching {
        val len = directFile.length()
        if (len <= maxBytes) {
          return directFile.readText(Charsets.UTF_8)
        }
        java.io.RandomAccessFile(directFile, "r").use { raf ->
          raf.seek(len - maxBytes)
          val buf = ByteArray(maxBytes)
          val read = raf.read(buf)
          if (read > 0) return String(buf, 0, read, Charsets.UTF_8)
        }
      }
    }

    // 2. SAF tree check (reads files created by Termux UID on Android 11-16)
    findSafFileUri(projectSlug, fileName)?.let { uri ->
      runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
          val bytes = input.readBytes()
          if (bytes.isNotEmpty()) {
            val slice = if (bytes.size > maxBytes) bytes.copyOfRange(bytes.size - maxBytes, bytes.size) else bytes
            return String(slice, Charsets.UTF_8)
          }
        }
      }
    }
    return null
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
      exists = size > 1024L && hasManifest,
      sizeBytes = size,
      hasValidApkArchive = hasManifest,
      buildMode = mode,
      downloadExportPath = downloadExportPath.ifBlank { apkFile.absolutePath },
    )
  }

  fun findBuiltApkForProject(project: AppBuilderProject): ApkVerificationResult? {
    val slug = project.slug
    val expectedDebug = "${slug}-debug.apk"
    val expectedRelease = "${slug}-release.apk"

    // 1. Direct accessible file paths
    val candidates = buildList {
      if (project.apkResult.apkFilePath.isNotBlank()) {
        add(File(project.apkResult.apkFilePath))
      }
      val sharedDir = sharedWorkspaceDir(slug)
      add(File(sharedDir, expectedDebug))
      add(File(sharedDir, expectedRelease))
      add(File(sharedDir, "app-debug.apk"))
      add(File(sharedDir, "app-release.apk"))
      add(File(sharedDir, "app/build/outputs/apk/debug/app-debug.apk"))
      add(File(sharedDir, "app/build/outputs/apk/release/app-release.apk"))
      val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      add(File(dl, "LxR/APK/$slug/$expectedDebug"))
      add(File(dl, "LxR/APK/$slug/$expectedRelease"))
      add(File(dl, "LXR-AppBuilder/$expectedDebug"))
      add(File(dl, "LXR-AppBuilder/$expectedRelease"))
      add(File(dl, expectedDebug))
      add(File(dl, expectedRelease))
    }
    val directFound = candidates.firstOrNull { it.exists() && it.canRead() && it.length() > 1024L }
    if (directFound != null) {
      val cached = copyToInstallableCache(directFound, directFound.name) ?: directFound
      val res = verifyApkFile(cached, project.buildMode, "Downloads/LxR/APK/$slug/${directFound.name}")
      if (res.exists) return res
    }

    // 2. SAF tree discovery (works across Termux UID on Android 11–16)
    val safNames = listOf(expectedDebug, expectedRelease, "app-debug.apk", "app-release.apk")
    for (fname in safNames) {
      val uri = findSafFileUri(slug, fname) ?: findSafFileUri(null, fname)
      if (uri != null) {
        val cached = copyUriToInstallableCache(uri, "${slug}-${project.buildMode.name.lowercase()}.apk")
        if (cached != null) {
          val res = verifyApkFile(cached, project.buildMode, "Downloads/LXR-AppBuilder/$slug/$fname")
          if (res.exists) return res
        }
      }
    }

    // 3. MediaStore Downloads scan (finds public Downloads/*.apk saved by Termux or LXR)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      findApkViaMediaStore(slug)?.let { uri ->
        val outName = "${slug}-${project.buildMode.name.lowercase()}.apk"
        val cached = copyUriToInstallableCache(uri, outName)
        if (cached != null) {
          val res = verifyApkFile(cached, project.buildMode, "Downloads/LXR-AppBuilder/$outName")
          if (res.exists) return res
        }
      }
    }

    return null
  }

  private fun copyToInstallableCache(src: File, targetName: String): File? {
    return runCatching {
      val dest = File(verifiedApkCacheDir(), targetName)
      if (src.absolutePath != dest.absolutePath) {
        src.copyTo(dest, overwrite = true)
      }
      dest
    }.getOrNull()
  }

  private fun copyUriToInstallableCache(uri: Uri, targetName: String): File? {
    return runCatching {
      val dest = File(verifiedApkCacheDir(), targetName)
      context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(dest).use { out -> input.copyTo(out) }
      } ?: return null
      if (dest.length() > 1024L) dest else null
    }.getOrNull()
  }

  private fun findApkViaMediaStore(slug: String): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
      val resolver = context.contentResolver
      val projection = arrayOf(
        MediaStore.Downloads._ID,
        MediaStore.Downloads.DISPLAY_NAME,
        MediaStore.Downloads.SIZE,
        MediaStore.Downloads.DATE_MODIFIED,
      )
      val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
      val args = arrayOf("%$slug%.apk")
      resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        args,
        "${MediaStore.Downloads.DATE_MODIFIED} DESC",
      )?.use { cursor ->
        if (cursor.moveToFirst()) {
          val id = cursor.getLong(0)
          val size = cursor.getLong(2)
          if (size > 1024L) {
            return@runCatching ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
          }
        }
      }
      null
    }.getOrNull()
  }

  private fun findSafFileUri(projectSlug: String?, targetFileName: String): Uri? {
    val treeUri = getSafTreeUri() ?: return null
    return runCatching {
      val resolver = context.contentResolver
      val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
      var searchDocId = treeDocId

      // Step into LXR-AppBuilder if user granted Download/ directly
      findChildDocId(resolver, treeUri, searchDocId, "LXR-AppBuilder", wantDirectory = true)?.let {
        searchDocId = it
      }
      if (!projectSlug.isNullOrBlank()) {
        findChildDocId(resolver, treeUri, searchDocId, projectSlug, wantDirectory = true)?.let { slugDocId ->
          findChildDocId(resolver, treeUri, slugDocId, targetFileName, wantDirectory = false)?.let { fileDocId ->
            return@runCatching DocumentsContract.buildDocumentUriUsingTree(treeUri, fileDocId)
          }
        }
      }
      findChildDocId(resolver, treeUri, searchDocId, targetFileName, wantDirectory = false)?.let { fileDocId ->
        return@runCatching DocumentsContract.buildDocumentUriUsingTree(treeUri, fileDocId)
      }
      null
    }.getOrNull()
  }

  private fun findChildDocId(
    resolver: android.content.ContentResolver,
    treeUri: Uri,
    parentDocId: String,
    name: String,
    wantDirectory: Boolean,
  ): String? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val proj = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    resolver.query(childrenUri, proj, null, null, null)?.use { c ->
      while (c.moveToNext()) {
        val docId = c.getString(0) ?: continue
        val disp = c.getString(1) ?: continue
        val mime = c.getString(2).orEmpty()
        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
        if (disp.equals(name, ignoreCase = true) && isDir == wantDirectory) {
          return docId
        }
      }
    }
    return null
  }

  fun shareableUriForFile(file: File): Uri? {
    return runCatching {
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
  }

  fun createInstallApkIntent(apkFile: File): Intent? {
    val installable =
      if (apkFile.absolutePath.startsWith(context.cacheDir.absolutePath)) apkFile
      else copyToInstallableCache(apkFile, apkFile.name) ?: apkFile
    val uri = shareableUriForFile(installable) ?: return null
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
    val apkFile = File(apkPath)
    val apkResult =
      if (apkPath.isNotBlank() && apkFile.exists()) {
        verifyApkFile(apkFile, mode, o.optString("downloadExportPath"))
      } else {
        ApkVerificationResult(
          apkFilePath = apkPath,
          apkFileName = o.optString("apkFileName"),
          exists = apkPath.isNotBlank() && apkFile.exists(),
          sizeBytes = o.optLong("apkSizeBytes", 0L),
          hasValidApkArchive = o.optBoolean("hasValidApkArchive", false),
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
