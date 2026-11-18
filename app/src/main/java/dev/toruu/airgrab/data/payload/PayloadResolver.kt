package dev.toruu.airgrab.data.payload

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.Patterns
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PayloadResolver(private val context: Context) {

    companion object {
        private const val TAG = "AirGrab_Resolver"
    }

    suspend fun resolveIntent(intent: Intent): TransferPayload? = withContext(Dispatchers.IO) {
        val action = intent.action ?: return@withContext null
        val type = intent.type ?: "*/*"

        when (action) {
            Intent.ACTION_SEND -> {
                val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }

                if (uri != null) {
                    resolveUri(uri, type)
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        resolveText(text)
                    } else {
                        null
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }

                if (!uris.isNullOrEmpty()) {
                    archiveUrisToZip(uris, "SharedFiles_${System.currentTimeMillis()}")
                } else {
                    null
                }
            }
            else -> null
        }
    }

    suspend fun resolveUri(uri: Uri, mimeTypeOverride: String? = null): TransferPayload = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val detectedMime = mimeTypeOverride?.takeIf { it != "*/*" }
            ?: contentResolver.getType(uri)
            ?: "application/octet-stream"

        var displayName = "file_${System.currentTimeMillis()}"
        var sizeBytes = 0L

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex) ?: displayName
                    }
                    if (sizeIndex != -1) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query URI metadata: ${e.message}")
        }

        val id = UUID.randomUUID().toString()

        if (detectedMime.startsWith("image/") || detectedMime.startsWith("video/")) {
            TransferPayload.MediaItem(
                id = id,
                displayName = displayName,
                totalBytes = sizeBytes,
                uri = uri,
                mimeType = detectedMime
            )
        } else {
            TransferPayload.BinaryFile(
                id = id,
                displayName = displayName,
                totalBytes = sizeBytes,
                uri = uri,
                mimeType = detectedMime
            )
        }
    }

    fun resolveText(text: String): TransferPayload.TextOrLink {
        val trimmed = text.trim()
        val isUrl = Patterns.WEB_URL.matcher(trimmed).matches()
        val displayName = if (isUrl) "Link: $trimmed" else "Text Snippet"
        return TransferPayload.TextOrLink(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            content = trimmed,
            isUrl = isUrl
        )
    }

    fun resolveClipboard(): TransferPayload.TextOrLink? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val primaryClip = clipboard.primaryClip ?: return null
        if (primaryClip.itemCount > 0) {
            val text = primaryClip.getItemAt(0).coerceToText(context).toString()
            if (text.isNotBlank()) {
                return resolveText(text)
            }
        }
        return null
    }

    suspend fun archiveFolderTree(treeUri: Uri): TransferPayload.FolderArchive = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Invalid DocumentFile tree Uri: $treeUri")

        val folderName = rootDoc.name ?: "Folder_${System.currentTimeMillis()}"
        val tempZipFile = File(context.cacheDir, "$folderName.zip")
        if (tempZipFile.exists()) tempZipFile.delete()

        var totalBytes = 0L
        var fileCount = 0

        ZipOutputStream(FileOutputStream(tempZipFile)).use { zos ->
            fun zipDocument(doc: DocumentFile, currentPath: String) {
                if (doc.isDirectory) {
                    val children = doc.listFiles()
                    for (child in children) {
                        zipDocument(child, if (currentPath.isEmpty()) doc.name ?: "" else "$currentPath/${doc.name}")
                    }
                } else if (doc.isFile) {
                    val entryName = if (currentPath.isEmpty()) doc.name ?: "file" else "$currentPath/${doc.name}"
                    zos.putNextEntry(ZipEntry(entryName))
                    context.contentResolver.openInputStream(doc.uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            zos.write(buffer, 0, read)
                            totalBytes += read
                        }
                    }
                    zos.closeEntry()
                    fileCount++
                }
            }

            for (child in rootDoc.listFiles()) {
                zipDocument(child, "")
            }
        }

        TransferPayload.FolderArchive(
            id = UUID.randomUUID().toString(),
            displayName = folderName,
            totalBytes = tempZipFile.length(),
            treeUri = treeUri,
            zipUri = tempZipFile.toUri(),
            fileCount = fileCount
        )
    }

    private suspend fun archiveUrisToZip(uris: List<Uri>, archiveName: String): TransferPayload.FolderArchive = withContext(Dispatchers.IO) {
        val tempZipFile = File(context.cacheDir, "$archiveName.zip")
        if (tempZipFile.exists()) tempZipFile.delete()

        var totalBytes = 0L
        var fileCount = 0

        ZipOutputStream(FileOutputStream(tempZipFile)).use { zos ->
            for ((index, uri) in uris.withIndex()) {
                var fileName = "file_$index"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex) ?: fileName
                        }
                    }
                }

                zos.putNextEntry(ZipEntry(fileName))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        zos.write(buffer, 0, read)
                        totalBytes += read
                    }
                }
                zos.closeEntry()
                fileCount++
            }
        }

        TransferPayload.FolderArchive(
            id = UUID.randomUUID().toString(),
            displayName = archiveName,
            totalBytes = tempZipFile.length(),
            treeUri = tempZipFile.toUri(),
            zipUri = tempZipFile.toUri(),
            fileCount = fileCount
        )
    }
}
