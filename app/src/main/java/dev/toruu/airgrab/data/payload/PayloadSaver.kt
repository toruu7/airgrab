package dev.toruu.airgrab.data.payload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class PayloadSaver(private val context: Context) {

    companion object {
        private const val TAG = "AirGrab_Saver"
        private const val AIRGRAB_PICTURES = "Pictures/AirGrab"
        private const val AIRGRAB_DOWNLOADS = "Download/AirGrab"
    }

    sealed interface SaveResult {
        data class MediaSaved(val uri: Uri, val displayName: String) : SaveResult
        data class FileSaved(val uri: Uri, val displayName: String) : SaveResult
        data class FolderExtracted(val folderName: String, val extractedCount: Int) : SaveResult
        data class TextCopied(val text: String, val isUrl: Boolean) : SaveResult
    }

    suspend fun saveMedia(
        fileName: String,
        mimeType: String,
        inputStream: InputStream
    ): SaveResult = withContext(Dispatchers.IO) {
        val safeName = sanitizeFileName(fileName).ifBlank { "airgrab_${System.currentTimeMillis()}" }
        val mime = resolveMimeType(safeName, mimeType)

        val collectionUri = if (mime.startsWith("video/")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, AIRGRAB_PICTURES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val itemUri = try {
            context.contentResolver.insert(collectionUri, contentValues)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert failed for $safeName: ${e.message}", e)
            null
        }

        if (itemUri == null) {
            // Nothing has been read from the stream yet, so the fallback is safe.
            Log.w(TAG, "MediaStore refused insert for $safeName, falling back to Downloads")
            return@withContext saveDocumentOrBinary(safeName, mime, inputStream)
        }

        try {
            context.contentResolver.openOutputStream(itemUri)?.use { out ->
                inputStream.copyTo(out)
            } ?: throw IOException("Cannot open output stream for $itemUri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(itemUri, contentValues, null, null)
            }
            Log.d(TAG, "Media saved to MediaStore: $itemUri ($safeName)")
            SaveResult.MediaSaved(itemUri, safeName)
        } catch (t: Throwable) {
            // Do not leave half-written rows behind. The stream has already been consumed,
            // so a fallback would only produce a corrupt duplicate.
            runCatching { context.contentResolver.delete(itemUri, null, null) }
            throw t
        }
    }

    suspend fun saveDocumentOrBinary(
        fileName: String,
        mimeType: String,
        inputStream: InputStream
    ): SaveResult.FileSaved = withContext(Dispatchers.IO) {
        val safeName = sanitizeFileName(fileName).ifBlank { "airgrab_${System.currentTimeMillis()}" }
        val uri = publishToDownloads(
            subPath = AIRGRAB_DOWNLOADS,
            fileName = safeName,
            mimeType = resolveMimeType(safeName, mimeType),
            inputStream = inputStream
        )
        Log.d(TAG, "Document saved: $uri ($safeName)")
        SaveResult.FileSaved(uri, safeName)
    }

    /**
     * Extracts an archive into an app-private cache directory first (with Zip Slip protection),
     * then publishes every file into Download/AirGrab/<folder>/ through MediaStore. Writing
     * directly into public Downloads with the File API throws EACCES on Android 10+.
     */
    suspend fun saveAndExtractZip(
        zipFileName: String,
        inputStream: InputStream
    ): SaveResult.FolderExtracted = withContext(Dispatchers.IO) {
        val folderName = sanitizeFileName(zipFileName.removeSuffix(".zip"))
            .ifBlank { "AirGrab_${System.currentTimeMillis()}" }
        val tempRoot = File(context.cacheDir, "airgrab_extract_${System.currentTimeMillis()}").apply { mkdirs() }
        val canonicalRoot = tempRoot.canonicalPath
        var extractedCount = 0

        try {
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val newFile = File(tempRoot, entry.name)
                    val canonicalNewFilePath = newFile.canonicalPath

                    // Critical security check: Zip Slip vulnerability protection
                    if (canonicalNewFilePath != canonicalRoot &&
                        !canonicalNewFilePath.startsWith(canonicalRoot + File.separator)
                    ) {
                        throw SecurityException("Zip Slip detected: Entry outside target folder! (${entry.name})")
                    }

                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        extractedCount++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            tempRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(tempRoot).path.replace('\\', '/')
                val subDir = relative.substringBeforeLast('/', missingDelimiterValue = "")
                val targetPath = buildString {
                    append(AIRGRAB_DOWNLOADS)
                    append('/')
                    append(folderName)
                    if (subDir.isNotEmpty()) {
                        append('/')
                        append(subDir)
                    }
                }
                FileInputStream(file).use { input ->
                    publishToDownloads(targetPath, file.name, resolveMimeType(file.name, null), input)
                }
            }
        } finally {
            tempRoot.deleteRecursively()
        }

        Log.d(TAG, "Zip extracted $extractedCount files into Download/AirGrab/$folderName")
        SaveResult.FolderExtracted(folderName, extractedCount)
    }

    fun saveTextToClipboard(text: String, isUrl: Boolean): SaveResult.TextCopied {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AirGrab Content", text)
        clipboard.setPrimaryClip(clip)
        Log.d(TAG, "Copied text to clipboard (isUrl=$isUrl): ${text.take(64)}")
        return SaveResult.TextCopied(text, isUrl)
    }

    /**
     * Writes [inputStream] into the public Downloads collection.
     *
     * On Android 10+ this must go through MediaStore; direct File writes into shared storage
     * (Environment.getExternalStoragePublicDirectory(...)) fail with EACCES.
     *
     * @param subPath path below the Downloads root, e.g. "AirGrab" or "AirGrab/Photos/2026".
     */
    private fun publishToDownloads(
        subPath: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream
    ): Uri {
        val safeName = sanitizeFileName(fileName).ifBlank { "airgrab_${System.currentTimeMillis()}" }
        val normalizedSubPath = subPath.trim('/')

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$normalizedSubPath"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("MediaStore.Downloads insert failed for $safeName")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    inputStream.copyTo(out)
                } ?: throw IOException("Cannot open output stream for $uri")

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                return uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }

        // Legacy path (API 26-28) - requires WRITE_EXTERNAL_STORAGE.
        @Suppress("DEPRECATION")
        val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsRoot, normalizedSubPath).apply { mkdirs() }
        if (!targetDir.isDirectory) {
            throw IOException("Cannot create directory ${targetDir.absolutePath}")
        }

        var target = File(targetDir, safeName)
        val stem = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 1
        while (target.exists()) {
            target = File(targetDir, "$stem (${index++})$extension")
        }

        FileOutputStream(target).use { out ->
            inputStream.copyTo(out)
        }
        return Uri.fromFile(target)
    }

    private fun sanitizeFileName(name: String): String {
        return name
            .replace('/', '_')
            .replace('\\', '_')
            .replace("\u0000", "")
            .trim()
            .take(120)
    }

    private fun resolveMimeType(fileName: String, mimeType: String?): String {
        if (!mimeType.isNullOrBlank() && mimeType != "application/octet-stream" && mimeType != "*/*") {
            return mimeType
        }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}
