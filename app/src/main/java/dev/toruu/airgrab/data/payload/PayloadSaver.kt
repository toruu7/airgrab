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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class PayloadSaver(private val context: Context) {

    companion object {
        private const val TAG = "AirGrab_Saver"
        private const val DIRECTORY_AIRGRAB_PICTURES = "Pictures/AirGrab"
        private const val DIRECTORY_AIRGRAB_DOWNLOADS = "Download/AirGrab"
    }

    sealed interface SaveResult {
        data class MediaSaved(val uri: Uri, val displayName: String) : SaveResult
        data class FileSaved(val file: File, val displayName: String) : SaveResult
        data class FolderExtracted(val folder: File, val extractedCount: Int) : SaveResult
        data class TextCopied(val text: String, val isUrl: Boolean) : SaveResult
    }

    suspend fun saveMedia(
        fileName: String,
        mimeType: String,
        inputStream: InputStream
    ): SaveResult.MediaSaved = withContext(Dispatchers.IO) {
        val isVideo = mimeType.startsWith("video/")
        val collectionUri = if (isVideo) {
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
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, DIRECTORY_AIRGRAB_PICTURES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val itemUri = context.contentResolver.insert(collectionUri, contentValues)
            ?: throw IllegalStateException("Failed to insert MediaStore record for $fileName")

        context.contentResolver.openOutputStream(itemUri)?.use { out ->
            inputStream.copyTo(out)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(itemUri, contentValues, null, null)
        }

        Log.d(TAG, "Media successfully saved to MediaStore: $itemUri ($fileName)")
        SaveResult.MediaSaved(itemUri, fileName)
    }

    suspend fun saveDocumentOrBinary(
        fileName: String,
        inputStream: InputStream
    ): SaveResult.FileSaved = withContext(Dispatchers.IO) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val airGrabDir = File(downloadsDir, "AirGrab")
        if (!airGrabDir.exists()) {
            airGrabDir.mkdirs()
        }

        val targetFile = File(airGrabDir, fileName)
        FileOutputStream(targetFile).use { out ->
            inputStream.copyTo(out)
        }

        Log.d(TAG, "Binary/Document saved to: ${targetFile.absolutePath}")
        SaveResult.FileSaved(targetFile, fileName)
    }

    suspend fun saveAndExtractZip(
        zipFileName: String,
        inputStream: InputStream
    ): SaveResult.FolderExtracted = withContext(Dispatchers.IO) {
        val folderBaseName = zipFileName.removeSuffix(".zip")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val airGrabDir = File(downloadsDir, "AirGrab")
        val targetExtractDir = File(airGrabDir, folderBaseName)
        if (!targetExtractDir.exists()) {
            targetExtractDir.mkdirs()
        }

        val canonicalTargetDir = targetExtractDir.canonicalPath
        var extractedCount = 0

        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val newFile = File(targetExtractDir, entry.name)
                val canonicalNewFilePath = newFile.canonicalPath

                // Critical security check: Zip Slip vulnerability protection
                if (!canonicalNewFilePath.startsWith(canonicalTargetDir + File.separator) &&
                    canonicalNewFilePath != canonicalTargetDir) {
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

        Log.d(TAG, "Zip extracted $extractedCount files into: ${targetExtractDir.absolutePath}")
        SaveResult.FolderExtracted(targetExtractDir, extractedCount)
    }

    fun saveTextToClipboard(text: String, isUrl: Boolean): SaveResult.TextCopied {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AirGrab Content", text)
        clipboard.setPrimaryClip(clip)
        Log.d(TAG, "Copied text to clipboard (isUrl=$isUrl): $text")
        return SaveResult.TextCopied(text, isUrl)
    }
}
