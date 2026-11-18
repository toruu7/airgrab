package dev.toruu.airgrab.data.nearby

import android.net.Uri
import com.google.android.gms.nearby.connection.Payload
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class IncomingFilePayload(
    val payloadId: Long,
    val endpointId: String,
    val payload: Payload,
    val fileName: String,
    val mimeType: String,
    /** "MEDIA" | "FILE" | "FOLDER_ZIP" */
    val transferKind: String,
    val sessionId: String
)

interface NearbyTransport {
    val connectedPeers: StateFlow<Map<String, String>> // endpointId -> Build.MODEL
    val incomingControl: SharedFlow<Pair<String, ControlMessage>>
    val incomingFiles: SharedFlow<IncomingFilePayload>
    val outgoingComplete: SharedFlow<Pair<Long, String>> // payloadId -> endpointId
    val transferProgress: StateFlow<Float?> // 0f..1f for large videos/folders
    val transferFailed: SharedFlow<Long> // payloadId that failed/cancelled

    fun startAutoRoom(deviceName: String = android.os.Build.MODEL)
    fun stopRoom()
    fun cancelTransfer(payloadId: Long)

    /** Returns the number of connected peers the message was handed to. */
    suspend fun broadcastControl(message: ControlMessage): Int
    suspend fun sendControl(endpointId: String, message: ControlMessage)
    suspend fun sendFile(
        endpointId: String,
        uri: Uri,
        fileName: String,
        mimeType: String,
        transferKind: String = "FILE",
        sessionId: String = ""
    ): Long
    suspend fun sendBytes(endpointId: String, bytes: ByteArray): Long
    fun resetProgress()
    fun pauseDiscovery()
    fun resumeDiscovery()
}
