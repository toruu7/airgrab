package dev.toruu.airgrab.data.payload

import android.net.Uri

sealed interface TransferPayload {
    val id: String
    val displayName: String
    val totalBytes: Long

    data class MediaItem(
        override val id: String,
        override val displayName: String,
        override val totalBytes: Long,
        val uri: Uri,
        val mimeType: String
    ) : TransferPayload

    data class BinaryFile(
        override val id: String,
        override val displayName: String,
        override val totalBytes: Long,
        val uri: Uri,
        val mimeType: String
    ) : TransferPayload

    data class FolderArchive(
        override val id: String,
        override val displayName: String,
        override val totalBytes: Long,
        val treeUri: Uri,
        val zipUri: Uri,
        val fileCount: Int
    ) : TransferPayload

    data class TextOrLink(
        override val id: String,
        override val displayName: String,
        val content: String,
        val isUrl: Boolean
    ) : TransferPayload {
        override val totalBytes: Long get() = content.toByteArray(Charsets.UTF_8).size.toLong()
    }
}
