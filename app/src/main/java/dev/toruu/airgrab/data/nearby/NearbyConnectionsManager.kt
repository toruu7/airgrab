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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Single Nearby Connections transport for the whole process.
 *
 * Nearby caches one client per application context, so every part of the app (activity
 * ViewModel, ambient discovery service, wake activity) MUST share this instance. Creating a
 * second manager in the same process makes the two instances overwrite each other's
 * advertising / discovery callbacks and payload callbacks.
 *
 * The room is symmetric: every device advertises *and* discovers, so there are no sender or
 * receiver roles. Whoever has something to send grabs it; whoever shows a palm receives it.
 */
class NearbyConnectionsManager(
    private val context: Context,
    private val localDeviceId: String
) : NearbyTransport {

    companion object {
        private const val TAG = "AirGrab_Nearby"
        const val SERVICE_ID = "dev.toruu.airgrab.OPEN_ROOM_V1"

        /**
         * Symmetric room: every device advertises and discovers, so the topology is a mesh
         * rather than a star. P2P_STAR forbids a non-hub device from also accepting incoming
         * connections, which breaks when both phones dial.
         */
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER

        /**
         * Opens the bytes of a *received* FILE payload.
         *
         * On Android 10+ received files must be read through the content URI exposed by
         * [Payload.File.asUri]; [Payload.File.asParcelFileDescriptor] throws for payloads that
         * span multiple files (large transfers), and [Payload.File.asJavaFile] is null.
         */
        fun openPayloadInputStream(context: Context, payload: Payload): InputStream? {
            val file = try {
                payload.asFile()
            } catch (e: Exception) {
                Log.e(TAG, "Payload is not a file payload: ${e.message}", e)
                return null
            } ?: return null

            // 1. Scoped storage (Android 10+): read through the exposed content URI.
            try {
                val uri = file.asUri()
                if (uri != null) {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) return stream
                }
            } catch (e: Exception) {
                Log.w(TAG, "URI access failed, trying legacy file access: ${e.message}")
            }

            // 2. Pre-Q devices expose a plain File.
            try {
                val javaFile = file.asJavaFile()
                if (javaFile != null && javaFile.exists()) {
                    return java.io.FileInputStream(javaFile)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct file access failed: ${e.message}")
            }

            // 3. Last resort: a seekable descriptor, if the payload provides one. This throws
            //    for payloads that span multiple files (very large transfers).
            try {
                val pfd = file.asParcelFileDescriptor()
                return ParcelFileDescriptor.AutoCloseInputStream(pfd)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening payload input stream: ${e.message}", e)
            }
            return null
        }

        /** Removes Nearby's temporary copy of a received FILE payload (call after copying it). */
        fun deleteReceivedPayload(context: Context, payload: Payload) {
            try {
                val file = payload.asFile() ?: return
                val uri = file.asUri() ?: return
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete temporary payload copy: ${e.message}")
            }
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

    private val _transferFailed = MutableSharedFlow<Long>(extraBufferCapacity = 32)
    override val transferFailed: SharedFlow<Long> = _transferFailed.asSharedFlow()

    private val _transferProgress = MutableStateFlow<Float?>(null)
    override val transferProgress: StateFlow<Float?> = _transferProgress.asStateFlow()

    /** In-flight incoming FILE payloads (payload received, transfer not finished yet). */
    private val incomingPayloadMap = ConcurrentHashMap<Long, Pair<String, Payload>>()
    /** Incoming FILE payloads whose transfer finished before their FileHeader arrived. */
    private val completedPayloadMap = ConcurrentHashMap<Long, Pair<String, Payload>>()
    /** FileHeader messages keyed by payload id (BYTES/FILE ordering is not guaranteed). */
    private val fileHeaderMap = ConcurrentHashMap<Long, ControlMessage.FileHeader>()

    private val outgoingPayloadMap = ConcurrentHashMap<Long, String>()
    private val outgoingFdMap = ConcurrentHashMap<Long, ParcelFileDescriptor>()

    private val peerNames = ConcurrentHashMap<String, String>()

    private var activeDeviceName: String = Build.MODEL
    @Volatile
    private var isRoomActive: Boolean = false
    private var isAdvertising: Boolean = false
    private var isDiscovering: Boolean = false
    private var isDiscoveryPaused: Boolean = false

    /**
     * Name used for advertising. The suffix is a stable per-install discriminator so that two
     * phones which discover each other can deterministically decide who dials; without it both
     * devices would open a connection at the same time and one would fail.
     */
    private val advertisedName: String
        get() = "$activeDeviceName#${localDeviceId.replace("-", "").take(8)}"

    private fun displayName(raw: String): String {
        val hash = raw.lastIndexOf('#')
        return if (hash > 0) raw.substring(0, hash) else raw
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val controlMsg = ControlMessageSerializer.decode(bytes)
                    if (controlMsg == null) {
                        Log.w(TAG, "Dropping undecodable BYTES payload from $endpointId (${bytes.size} bytes)")
                        return
                    }
                    if (controlMsg is ControlMessage.FileHeader) {
                        fileHeaderMap[controlMsg.payloadId] = controlMsg
                        emitCompletedFile(controlMsg.payloadId)
                    } else {
                        Log.d(TAG, "Received control msg from $endpointId: $controlMsg")
                        scope.launch { _incomingControl.emit(endpointId to controlMsg) }
                    }
                }
                Payload.Type.FILE -> {
                    Log.d(TAG, "Receiving FILE payload ${payload.id} from $endpointId (holding until SUCCESS)")
                    _transferProgress.value = 0f
                    incomingPayloadMap[payload.id] = endpointId to payload
                }
                Payload.Type.STREAM -> {
                    Log.d(TAG, "Receiving STREAM payload ${payload.id} from $endpointId")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val isIncomingFile = incomingPayloadMap.containsKey(update.payloadId)
            val isOutgoing = outgoingPayloadMap.containsKey(update.payloadId)

            // Ignore control message (BYTES) progress so it doesn't prematurely trigger 100%
            if (!isIncomingFile && !isOutgoing) {
                return
            }

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

                    // Incoming file: keep it until the metadata header is also available.
                    val incoming = incomingPayloadMap.remove(update.payloadId)
                    if (incoming != null) {
                        completedPayloadMap[update.payloadId] = incoming
                        emitCompletedFile(update.payloadId)
                    }

                    // Outgoing upload finished: release the descriptor we opened for sending.
                    val outgoingTarget = outgoingPayloadMap.remove(update.payloadId)
                    if (outgoingTarget != null) {
                        closeOutgoingDescriptor(update.payloadId)
                        Log.d(TAG, "Outgoing upload complete for ${update.payloadId} to $outgoingTarget")
                        scope.launch { _outgoingComplete.emit(update.payloadId to outgoingTarget) }
                    }
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    Log.w(TAG, "Payload ${update.payloadId} transfer failed (${update.status})")
                    incomingPayloadMap.remove(update.payloadId)
                    fileHeaderMap.remove(update.payloadId)
                    outgoingPayloadMap.remove(update.payloadId)
                    closeOutgoingDescriptor(update.payloadId)
                    _transferProgress.value = null
                    resumeDiscovery()
                    scope.launch { _transferFailed.emit(update.payloadId) }
                }
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated from ${info.endpointName} ($endpointId), auto-accepting...")
            peerNames[endpointId] = displayName(info.endpointName)
            // Immediate PIN-free acceptance for open-room pairing
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to auto-accept connection from $endpointId: ${e.message}", e)
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                Log.d(TAG, "Connected to endpoint: $endpointId")
                val name = peerNames[endpointId] ?: "Peer-$endpointId"
                _connectedPeers.update { it + (endpointId to name) }
            } else {
                Log.w(TAG, "Connection result failed for $endpointId: ${resolution.status.statusCode}")
                _connectedPeers.update { it - endpointId }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Endpoint disconnected: $endpointId")
            peerNames.remove(endpointId)
            _connectedPeers.update { it - endpointId }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // NOTE: do not add to connectedPeers here - a discovered endpoint is not a
            // connected endpoint yet, and sending to it fails with STATUS_ENDPOINT_UNKNOWN.
            peerNames[endpointId] = displayName(info.endpointName)

            // Symmetric room: both sides advertise and discover. Only the device with the
            // lexicographically smaller advertised name dials, so exactly one connection is
            // opened per pair instead of two racing ones. Peers without the "#id" suffix are
            // older builds that only advertise, so we always dial them.
            val peerIsLegacy = !info.endpointName.contains('#')
            if (!peerIsLegacy && advertisedName > info.endpointName) {
                Log.d(TAG, "Discovered ${info.endpointName}; it will dial us")
                // Safety net: if the other side never dials (transient Nearby failure), dial
                // ourselves after a grace period instead of staying disconnected forever.
                scope.launch {
                    delay(3_000)
                    if (isRoomActive && !_connectedPeers.value.containsKey(endpointId)) {
                        Log.d(TAG, "Peer did not dial, connecting ourselves")
                        requestConnectionWithRetry(endpointId)
                    }
                }
                return
            }

            Log.d(TAG, "Discovered endpoint: ${info.endpointName} ($endpointId), requesting connection...")
            requestConnectionWithRetry(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            // Discovery is paused during every transfer, which drops endpoints from discovery
            // even though the connection stays alive. Only onDisconnected may remove a peer.
            Log.d(TAG, "Endpoint lost from discovery (connection unaffected): $endpointId")
        }
    }

    override fun startAutoRoom(deviceName: String) {
        if (deviceName.isNotBlank()) activeDeviceName = deviceName
        isRoomActive = true
        isDiscoveryPaused = false
        startAdvertisingInternal()
        startDiscoveryInternal()
    }

    private fun startAdvertisingInternal() {
        if (isAdvertising) {
            Log.d(TAG, "Already advertising as $advertisedName")
            return
        }
        // Set optimistically so two callers (service + activity) cannot both start it.
        isAdvertising = true
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startAdvertising(
            advertisedName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Started advertising as $advertisedName")
        }.addOnFailureListener { e ->
            isAdvertising = false
            Log.e(TAG, "Failed to start advertising: ${e.message}", e)
        }
    }

    private fun startDiscoveryInternal() {
        if (isDiscoveryPaused || isDiscovering) return
        isDiscovering = true
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Started discovery as $advertisedName")
        }.addOnFailureListener { e ->
            isDiscovering = false
            Log.e(TAG, "Failed to start discovery: ${e.message}", e)
        }
    }

    /**
     * Requests a connection, retrying a few times. A discovered endpoint is only announced once
     * per discovery session, so a single transient failure would otherwise leave the pair
     * permanently disconnected.
     */
    private fun requestConnectionWithRetry(endpointId: String, attempt: Int = 0) {
        if (_connectedPeers.value.containsKey(endpointId)) return

        connectionsClient.requestConnection(
            advertisedName,
            endpointId,
            connectionLifecycleCallback
        ).addOnFailureListener { e ->
            // ALREADY_CONNECTED is expected when discovery resumes after a transfer.
            Log.w(TAG, "Request connection to $endpointId failed (attempt $attempt): ${e.message}")
            if (attempt < 3) {
                scope.launch {
                    delay(1_500)
                    requestConnectionWithRetry(endpointId, attempt + 1)
                }
            }
        }
    }

    override fun stopRoom() {
        isRoomActive = false
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            isAdvertising = false
            isDiscovering = false
            isDiscoveryPaused = false
            _connectedPeers.value = emptyMap()
            _transferProgress.value = null
            peerNames.clear()
            incomingPayloadMap.clear()
            completedPayloadMap.clear()
            fileHeaderMap.clear()
            outgoingPayloadMap.keys.forEach { closeOutgoingDescriptor(it) }
            outgoingPayloadMap.clear()
            Log.d(TAG, "Stopped open room and disconnected all endpoints")
        } catch (e: Exception) {
            Log.e(TAG, "Error in stopRoom: ${e.message}", e)
        }
    }

    override fun cancelTransfer(payloadId: Long) {
        incomingPayloadMap.remove(payloadId)
        completedPayloadMap.remove(payloadId)
        fileHeaderMap.remove(payloadId)
        outgoingPayloadMap.remove(payloadId)
        closeOutgoingDescriptor(payloadId)
        _transferProgress.value = null
        resumeDiscovery()
        connectionsClient.cancelPayload(payloadId)
            .addOnSuccessListener {
                Log.d(TAG, "Cancelled payload $payloadId")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "cancelPayload($payloadId) failed: ${e.message}")
            }
    }

    override fun resetProgress() {
        _transferProgress.value = null
    }

    override fun pauseDiscovery() {
        if (!isDiscoveryPaused) {
            isDiscoveryPaused = true
            if (isDiscovering) {
                connectionsClient.stopDiscovery()
                isDiscovering = false
            }
            Log.d(TAG, "Paused BLE discovery to optimize Wi-Fi Direct streaming")
        }
    }

    override fun resumeDiscovery() {
        if (isDiscoveryPaused) {
            isDiscoveryPaused = false
            startDiscoveryInternal()
            Log.d(TAG, "Resumed BLE discovery after payload transfer")
        }
    }

    override suspend fun broadcastControl(message: ControlMessage): Int {
        return try {
            val bytes = ControlMessageSerializer.encode(message)
            val payload = Payload.fromBytes(bytes)
            val peers = _connectedPeers.value.keys.toList()
            if (peers.isEmpty()) {
                Log.w(TAG, "No connected peers, control message not delivered: $message")
                return 0
            }
            connectionsClient.sendPayload(peers, payload).await()
            Log.d(TAG, "Broadcasted control message to ${peers.size} peers: $message")
            peers.size
        } catch (e: Exception) {
            Log.e(TAG, "Error in broadcastControl: ${e.message}", e)
            0
        }
    }

    override suspend fun sendControl(endpointId: String, message: ControlMessage) {
        try {
            val bytes = ControlMessageSerializer.encode(message)
            val payload = Payload.fromBytes(bytes)
            connectionsClient.sendPayload(endpointId, payload).await()
            Log.d(TAG, "Sent control message to $endpointId: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendControl to $endpointId: ${e.message}", e)
        }
    }

    override suspend fun sendFile(
        endpointId: String,
        uri: Uri,
        fileName: String,
        mimeType: String,
        transferKind: String,
        sessionId: String
    ): Long {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open descriptor for $uri: ${e.message}", e)
            null
        } ?: throw IOException("Cannot open ParcelFileDescriptor for URI: $uri")

        val payload = Payload.fromFile(pfd)
        _transferProgress.value = 0f
        outgoingPayloadMap[payload.id] = endpointId
        outgoingFdMap[payload.id] = pfd

        try {
            // Metadata travels as its own BYTES payload; the receiver buffers it by payload id.
            sendControl(
                endpointId,
                ControlMessage.FileHeader(
                    sessionId = sessionId,
                    payloadId = payload.id,
                    fileName = fileName,
                    mimeType = mimeType,
                    payloadType = transferKind
                )
            )
            connectionsClient.sendPayload(endpointId, payload).await()
            Log.d(TAG, "Initiated file payload ${payload.id} ($fileName, $mimeType) to $endpointId")
            return payload.id
        } catch (e: Exception) {
            outgoingPayloadMap.remove(payload.id)
            closeOutgoingDescriptor(payload.id)
            _transferProgress.value = null
            throw e
        }
    }

    override suspend fun sendBytes(endpointId: String, bytes: ByteArray): Long {
        val payload = Payload.fromBytes(bytes)
        outgoingPayloadMap[payload.id] = endpointId
        try {
            connectionsClient.sendPayload(endpointId, payload).await()
            Log.d(TAG, "Sent bytes payload ${payload.id} (${bytes.size} bytes) to $endpointId")
            return payload.id
        } catch (e: Exception) {
            outgoingPayloadMap.remove(payload.id)
            throw e
        }
    }

    private fun emitCompletedFile(payloadId: Long) {
        val completed = completedPayloadMap.remove(payloadId) ?: return
        val header = fileHeaderMap.remove(payloadId)
        scope.launch {
            _incomingFiles.emit(
                IncomingFilePayload(
                    payloadId = payloadId,
                    endpointId = completed.first,
                    payload = completed.second,
                    fileName = header?.fileName ?: "airgrab_$payloadId",
                    mimeType = header?.mimeType ?: "application/octet-stream",
                    transferKind = header?.payloadType ?: "FILE",
                    sessionId = header?.sessionId ?: ""
                )
            )
        }
    }

    private fun closeOutgoingDescriptor(payloadId: Long) {
        outgoingFdMap.remove(payloadId)?.let { pfd ->
            try {
                pfd.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close descriptor for $payloadId: ${e.message}")
            }
        }
    }
}
