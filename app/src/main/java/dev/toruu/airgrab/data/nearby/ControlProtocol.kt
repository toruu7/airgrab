package dev.toruu.airgrab.data.nearby

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed interface ControlMessage {
    @Serializable
    data class GrabReady(
        val sessionId: String,
        val senderEndpointId: String,
        val senderName: String, // android.os.Build.MODEL
        val payloadType: String, // "MEDIA" | "FILE" | "FOLDER_ZIP" | "TEXT"
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val textPreview: String? = null,
        val expiresAtEpochMs: Long
    ) : ControlMessage

    @Serializable
    data class AcceptDrop(
        val sessionId: String,
        val receiverEndpointId: String
    ) : ControlMessage

    @Serializable
    data class SessionClaimed(
        val sessionId: String,
        val claimedByEndpointId: String
    ) : ControlMessage

    @Serializable
    data class TransferComplete(
        val sessionId: String,
        val success: Boolean,
        val errorMessage: String? = null
    ) : ControlMessage
}

object ControlMessageSerializer {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: ControlMessage): ByteArray {
        return json.encodeToString(message).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): ControlMessage? {
        return try {
            val text = String(bytes, Charsets.UTF_8)
            json.decodeFromString<ControlMessage>(text)
        } catch (e: Exception) {
            null
        }
    }
}
