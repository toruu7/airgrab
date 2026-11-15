package dev.toruu.airgrab.data.nearby

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class NearbyConnectionsManager(
    private val context: Context
) : NearbyTransport {

    companion object {
        private const val TAG = "AirGrab_Nearby"
        const val SERVICE_ID = "dev.toruu.airgrab.OPEN_ROOM_V1"
        val STRATEGY: Strategy = Strategy.P2P_STAR

        fun openPayloadInputStream(context: Context, payload: Payload): java.io.InputStream? {
            val file = payload.asFile() ?: return null
            val javaFile = file.asJavaFile()
            if (javaFile != null && javaFile.exists() && javaFile.length() > 0) {
                return java.io.FileInputStream(javaFile)
            }
            val uri = file.asUri()
            if (uri != null) {
                return context.contentResolver.openInputStream(uri)
            }
            return null
        }
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectedPeers = MutableStateFlow<Map<String, String>>(emptyMap())
    override val connectedPeers: StateFlow<Map<String, String>> = _connectedPeers.asStateFlow()

    private val _incomingControl = MutableSharedFlow<Pair<String, ControlMessage>>(extraBufferCapacity = 64)
    override val incomingControl: SharedFlow<Pair<String, ControlMessage>> = _incomingControl.asSharedFlow()

    private val _incomingFiles = MutableSharedFlow<IncomingFilePayload>(extraBufferCapacity = 32)
    override val incomingFiles: SharedFlow<IncomingFilePayload> = _incomingFiles.asSharedFlow()

    private val _outgoingComplete = MutableSharedFlow<Pair<Long, String>>(extraBufferCapacity = 32)
    override val outgoingComplete: SharedFlow<Pair<Long, String>> = _outgoingComplete.asSharedFlow()

    private val _transferProgress = MutableStateFlow<Float?>(null)
    override val transferProgress: StateFlow<Float?> = _transferProgress.asStateFlow()

    private val incomingPayloadMap = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, Payload>>()
    private val outgoingPayloadMap = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private var activeDeviceName: String = Build.MODEL
    private var isHosting: Boolean = false

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val controlMsg = ControlMessageSerializer.decode(bytes)
                    if (controlMsg != null) {
                        Log.d(TAG, "Received control msg from $endpointId: $controlMsg")
                        scope.launch {
                            _incomingControl.emit(endpointId to controlMsg)
                        }
                    }
                }
                Payload.Type.FILE -> {
                    Log.d(TAG, "Starting to receive FILE payload ${payload.id} from $endpointId (holding until SUCCESS)")
                    incomingPayloadMap[payload.id] = endpointId to payload
                }
                Payload.Type.STREAM -> {
                    Log.d(TAG, "Receiving STREAM payload ${payload.id} from $endpointId")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Pause discovery to prevent antenna coexistence contention on 2.4 GHz
                    pauseDiscovery()
                    val total = update.totalBytes
                    if (total > 0) {
                        val progress = update.bytesTransferred.toFloat() / total.toFloat()
                        _transferProgress.value = progress.coerceIn(0f, 1f)
                    }
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    Log.d(TAG, "Payload ${update.payloadId} transfer SUCCESS with $endpointId")
                    _transferProgress.value = 1f
                    resumeDiscovery()

                    // Check if this was an incoming file payload
                    val incoming = incomingPayloadMap.remove(update.payloadId)
                    if (incoming != null) {
                        Log.d(TAG, "Emitting fully-downloaded FILE payload ${update.payloadId} from ${incoming.first}")
                        scope.launch {
                            _incomingFiles.emit(IncomingFilePayload(update.payloadId, incoming.first, incoming.second))
                        }
                    }

                    // Check if this was an outgoing upload
                    val outgoingTarget = outgoingPayloadMap.remove(update.payloadId)
                    if (outgoingTarget != null) {
                        Log.d(TAG, "Emitting outgoing upload completion for ${update.payloadId} to $outgoingTarget")
                        scope.launch {
                            _outgoingComplete.emit(update.payloadId to outgoingTarget)
                        }
                    }
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    Log.w(TAG, "Payload ${update.payloadId} transfer failed/cancelled (${update.status})")
                    incomingPayloadMap.remove(update.payloadId)
                    outgoingPayloadMap.remove(update.payloadId)
                    _transferProgress.value = null
                    resumeDiscovery()
                }
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated from ${info.endpointName} ($endpointId), auto-accepting...")
            // Immediate PIN-free acceptance for open-room pairing
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to auto-accept connection from $endpointId: ${e.message}", e)
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                Log.d(TAG, "Connected to endpoint: $endpointId")
                // Store endpoint mapping
                _connectedPeers.update { current ->
                    current + (endpointId to (current[endpointId] ?: "Peer-$endpointId"))
                }
            } else {
                Log.w(TAG, "Connection result failed for $endpointId: ${resolution.status.statusCode}")
                _connectedPeers.update { it - endpointId }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Endpoint disconnected: $endpointId")
            _connectedPeers.update { it - endpointId }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Discovered endpoint: ${info.endpointName} ($endpointId), requesting connection...")
            _connectedPeers.update { current ->
                current + (endpointId to info.endpointName)
            }
            connectionsClient.requestConnection(
                activeDeviceName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener { e ->
                Log.e(TAG, "Request connection failed to $endpointId: ${e.message}", e)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Discovered endpoint lost: $endpointId")
            _connectedPeers.update { it - endpointId }
        }
    }

    override fun startOpenRoom(deviceName: String, isHost: Boolean) {
        this.activeDeviceName = if (deviceName.isNotBlank()) deviceName else Build.MODEL
        this.isHosting = isHost
        stopRoom()

        if (isHost) {
            val advertisingOptions = AdvertisingOptions.Builder()
                .setStrategy(STRATEGY)
                .build()

            connectionsClient.startAdvertising(
                activeDeviceName,
                SERVICE_ID,
                connectionLifecycleCallback,
                advertisingOptions
            ).addOnSuccessListener {
                Log.d(TAG, "Started advertising open room as host: $activeDeviceName")
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to start advertising: ${e.message}", e)
            }
        } else {
            val discoveryOptions = DiscoveryOptions.Builder()
                .setStrategy(STRATEGY)
                .build()

            connectionsClient.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                discoveryOptions
            ).addOnSuccessListener {
                Log.d(TAG, "Started discovery for open room as client: $activeDeviceName")
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to start discovery: ${e.message}", e)
            }
        }
    }

    override fun stopRoom() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            _connectedPeers.value = emptyMap()
            _transferProgress.value = null
            Log.d(TAG, "Stopped open room and disconnected all endpoints")
        } catch (e: Exception) {
            Log.e(TAG, "Error in stopRoom: ${e.message}", e)
        }
    }

    override fun cancelTransfer(payloadId: Long) {
        connectionsClient.cancelPayload(payloadId)
            .addOnSuccessListener {
                Log.d(TAG, "Cancelled payload $payloadId")
                _transferProgress.value = null
                resumeDiscovery()
            }
    }

    private var isDiscoveryPaused: Boolean = false

    override fun pauseDiscovery() {
        if (!isHosting && !isDiscoveryPaused) {
            isDiscoveryPaused = true
            connectionsClient.stopDiscovery()
            Log.d(TAG, "Paused BLE discovery to optimize Wi-Fi Direct streaming")
        }
    }

    override fun resumeDiscovery() {
        if (!isHosting && isDiscoveryPaused) {
            isDiscoveryPaused = false
            val discoveryOptions = DiscoveryOptions.Builder()
                .setStrategy(STRATEGY)
                .build()
            connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            Log.d(TAG, "Resumed BLE discovery after payload transfer")
        }
    }

    override suspend fun broadcastControl(message: ControlMessage) {
        val bytes = ControlMessageSerializer.encode(message)
        val payload = Payload.fromBytes(bytes)
        val peers = _connectedPeers.value.keys.toList()
        if (peers.isNotEmpty()) {
            connectionsClient.sendPayload(peers, payload).await()
            Log.d(TAG, "Broadcasted control message to ${peers.size} peers: $message")
        }
    }

    override suspend fun sendControl(endpointId: String, message: ControlMessage) {
        val bytes = ControlMessageSerializer.encode(message)
        val payload = Payload.fromBytes(bytes)
        connectionsClient.sendPayload(endpointId, payload).await()
        Log.d(TAG, "Sent control message to $endpointId: $message")
    }

    override suspend fun sendFile(
        endpointId: String,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): Long {
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open ParcelFileDescriptor for URI: $uri")

        val payload = Payload.fromFile(pfd)
        outgoingPayloadMap[payload.id] = endpointId
        connectionsClient.sendPayload(endpointId, payload).await()
        Log.d(TAG, "Initiated file payload ${payload.id} ($fileName) to $endpointId")
        return payload.id
    }

    override suspend fun sendBytes(endpointId: String, bytes: ByteArray): Long {
        val payload = Payload.fromBytes(bytes)
        outgoingPayloadMap[payload.id] = endpointId
        connectionsClient.sendPayload(endpointId, payload).await()
        Log.d(TAG, "Sent bytes payload ${payload.id} (${bytes.size} bytes) to $endpointId")
        return payload.id
    }
}
