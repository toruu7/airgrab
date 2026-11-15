package dev.toruu.airgrab.data.nearby

import android.net.Uri
import com.google.android.gms.nearby.connection.Payload
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class IncomingFilePayload(
    val payloadId: Long,
    val endpointId: String,
    val payload: Payload
)

interface NearbyTransport {
    val connectedPeers: StateFlow<Map<String, String>> // endpointId -> Build.MODEL
    val incomingControl: SharedFlow<Pair<String, ControlMessage>>
    val incomingFiles: SharedFlow<IncomingFilePayload>
    val transferProgress: StateFlow<Float?> // 0f..1f for large videos/folders

    fun startOpenRoom(deviceName: String = android.os.Build.MODEL, isHost: Boolean)
    fun stopRoom()
    fun cancelTransfer(payloadId: Long)
    suspend fun broadcastControl(message: ControlMessage)
    suspend fun sendControl(endpointId: String, message: ControlMessage)
    suspend fun sendFile(endpointId: String, uri: Uri, fileName: String, mimeType: String): Long
    suspend fun sendBytes(endpointId: String, bytes: ByteArray): Long
}
