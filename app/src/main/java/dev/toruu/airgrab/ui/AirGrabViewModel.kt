package dev.toruu.airgrab.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.toruu.airgrab.AirGrabApp
import dev.toruu.airgrab.data.nearby.ControlMessage
import dev.toruu.airgrab.data.nearby.NearbyConnectionsManager
import dev.toruu.airgrab.data.payload.PayloadResolver
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.payload.TransferPayload
import dev.toruu.airgrab.data.vision.GestureFrameResult
import dev.toruu.airgrab.data.vision.MediaPipeGestureDetector
import dev.toruu.airgrab.domain.*
import dev.toruu.airgrab.service.AirGrabDiscoveryService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class AppMode {
    HOME,
    /** Single symmetric mode: this device can both send (grab) and receive (palm) at once. */
    ACTIVE
}

class AirGrabViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AirGrab_VM"
    }

    private val app = application as AirGrabApp
    private val context = application.applicationContext
    private val deviceId: String = app.deviceId

    val gestureDetector = MediaPipeGestureDetector(context)
    val nearbyTransport: NearbyConnectionsManager = app.transport
    val stateMachine = GrabDropStateMachine()
    private val hysteresis = GestureHysteresis()
    private val payloadResolver = PayloadResolver(context)
    private val payloadSaver = PayloadSaver(context)

    private val _appMode = MutableStateFlow(AppMode.HOME)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    /** Human readable status shown on the active screen (e.g. "no peer connected"). */
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val connectedPeers: StateFlow<Map<String, String>> = nearbyTransport.connectedPeers
    val senderState: StateFlow<SenderState> = stateMachine.senderState
    val receiverState: StateFlow<ReceiverState> = stateMachine.receiverState
    val currentGesture: StateFlow<GestureFrameResult> = gestureDetector.gestures
    val transferProgress: StateFlow<Float?> = nearbyTransport.transferProgress

    private var ttlJob: Job? = null
    private var activeTransferPayloadId: Long? = null

    /** Grab broadcast that could not be delivered yet because no peer was connected. */
    private var pendingGrab: ControlMessage.GrabReady? = null

    init {
        observeGestures()
        observeIncomingControl()
        observeIncomingFiles()
        observeOutgoingComplete()
        observeTransferFailures()
        observeProgress()
        observePeerAvailability()
        observeReceiverOwnership()
        startTtlMonitor()
    }

    /** Publishes which session the in-app receiver screen owns (used by the wake activity). */
    private fun observeReceiverOwnership() {
        viewModelScope.launch {
            combine(receiverState, appMode) { state, mode -> state to mode }
                .collect { (state, mode) ->
                    // While the app is on the Home screen the ambient wake activity is the one
                    // that handles drops, so it must not be blocked by invisible ownership.
                    app.activeReceiverSessionId =
                        if (mode != AppMode.ACTIVE) null
                        else when (state) {
                            is ReceiverState.Armed -> state.grabReady.sessionId
                            is ReceiverState.Receiving -> state.sessionId
                            is ReceiverState.Delivered -> state.sessionId
                            is ReceiverState.Listening -> null
                        }
                }
        }
    }

    /**
     * Called by the activity when it becomes visible / invisible. Backgrounding releases the
     * receiver state so the ambient wake activity can take over the next incoming grab.
     */
    fun onAppForegroundChanged(foreground: Boolean) {
        app.isAppForeground = foreground
        if (!foreground) {
            val state = stateMachine.receiverState.value
            if (state is ReceiverState.Armed || state is ReceiverState.Delivered) {
                stateMachine.resetReceiverToListening()
                hysteresis.resetState()
            }
        }
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView? = null) {
        gestureDetector.start(lifecycleOwner, previewView)
    }

    fun setAppMode(mode: AppMode) {
        _appMode.value = mode
        when (mode) {
            AppMode.HOME -> {
                gestureDetector.setPowerMode(active = false)
            }
            AppMode.ACTIVE -> {
                // Symmetric room: advertise + discover, so the other device is found no matter
                // which one of us has something to send.
                nearbyTransport.startAutoRoom(Build.MODEL)
                gestureDetector.setPowerMode(active = false)
                // A stale delivered/armed state from a previous visit would hide the stage UI.
                if (stateMachine.receiverState.value is ReceiverState.Delivered) {
                    stateMachine.resetReceiverToListening()
                }
            }
        }
    }

    fun handleIncomingIntent(intent: Intent) {
        viewModelScope.launch {
            val payload = payloadResolver.resolveIntent(intent)
            if (payload != null) {
                stagePayload(payload)
                setAppMode(AppMode.ACTIVE)
            }
        }
    }

    fun stagePayload(payload: TransferPayload) {
        stateMachine.stagePayload(payload)
        _statusMessage.value = null
    }

    fun stageUri(uri: Uri) {
        viewModelScope.launch {
            val payload = payloadResolver.resolveUri(uri)
            stagePayload(payload)
            setAppMode(AppMode.ACTIVE)
        }
    }

    fun stageFolderTree(treeUri: Uri) {
        viewModelScope.launch {
            val payload = payloadResolver.archiveFolderTree(treeUri)
            stagePayload(payload)
            setAppMode(AppMode.ACTIVE)
        }
    }

    fun stageClipboard() {
        val clip = payloadResolver.resolveClipboard()
        if (clip != null) {
            stagePayload(clip)
            setAppMode(AppMode.ACTIVE)
        }
    }

    fun cancelTransfer() {
        activeTransferPayloadId?.let { nearbyTransport.cancelTransfer(it) }
        activeTransferPayloadId = null
        stateMachine.resetSenderToStagedOrIdle()
        _statusMessage.value = null
    }

    /** Cancels any transfer and unstages the payload, returning to the picker. */
    fun clearPayload() {
        activeTransferPayloadId?.let { nearbyTransport.cancelTransfer(it) }
        activeTransferPayloadId = null
        stateMachine.unstagePayload()
        _statusMessage.value = null
    }

    /**
     * "Ready for next drop" in the receiver card.
     *
     * The old button just re-selected Receiver mode, which was already active and therefore did
     * nothing - the state machine stayed in [ReceiverState.Delivered] forever.
     */
    fun resetReceiverForNextDrop() {
        stateMachine.resetReceiverToListening()
        hysteresis.resetState()
        _statusMessage.value = null
        nearbyTransport.startAutoRoom(Build.MODEL)
    }

    private fun observeGestures() {
        viewModelScope.launch {
            gestureDetector.gestures.collect { frame ->
                val event = hysteresis.processFrame(frame)
                when (event) {
                    is GestureEvent.GrabTriggered -> onGrabGesture()
                    is GestureEvent.DropTriggered -> onDropGesture()
                    is GestureEvent.None -> {}
                }
            }
        }
    }

    private fun onGrabGesture() {
        if (_appMode.value != AppMode.ACTIVE) {
            hysteresis.rearmFist()
            return
        }
        try {
            if (stateMachine.senderState.value !is SenderState.Staged) {
                // Keep the fist eligible: the payload may be staged a moment later.
                hysteresis.rearmFist()
                return
            }

            if (nearbyTransport.connectedPeers.value.isEmpty()) {
                // No peer yet - keep the grab intent for a few seconds and retry as soon as
                // somebody connects instead of silently dropping the gesture.
                Log.w(TAG, "Grab triggered but no peer is connected yet")
            }

            val grabReady = stateMachine.onGrabTriggered(deviceId, Build.MODEL) ?: return
            pendingGrab = grabReady
            _statusMessage.value = null

            viewModelScope.launch {
                val delivered = nearbyTransport.broadcastControl(grabReady)
                if (delivered > 0) {
                    pendingGrab = null
                } else {
                    _statusMessage.value = "No other device connected yet - waiting for one..."
                    Log.w(TAG, "GrabReady not delivered, will retry when a peer connects")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling onGrabGesture: ${e.message}", e)
        }
    }

    private fun onDropGesture() {
        if (_appMode.value != AppMode.ACTIVE) {
            hysteresis.rearmPalm()
            return
        }
        try {
            val dropResult = stateMachine.onDropTriggered(deviceId)
            if (dropResult == null) {
                // Not armed (yet). Keep the palm eligible so a pose that was already held when
                // the grab signal arrived still triggers as soon as this device arms.
                hysteresis.rearmPalm()
                return
            }
            val (targetEndpoint, acceptDrop) = dropResult
            Log.d(TAG, "Drop gesture triggered, sending AcceptDrop to $targetEndpoint")
            viewModelScope.launch {
                try {
                    nearbyTransport.sendControl(targetEndpoint, acceptDrop)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send AcceptDrop: ${e.message}", e)
                    stateMachine.resetReceiverToListening()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling onDropGesture: ${e.message}", e)
        }
    }

    private fun wakeScreen() {
        try {
            val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager?.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "airgrab:receiver_wake"
            )
            wakeLock?.acquire(7000L)
            Log.d(TAG, "Screen wakeLock acquired for incoming drop")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire screen wakeLock: ${e.message}")
        }
    }

    private fun observePeerAvailability() {
        viewModelScope.launch {
            nearbyTransport.connectedPeers.collect { peers ->
                val pending = pendingGrab ?: return@collect
                if (peers.isEmpty()) return@collect

                val current = stateMachine.senderState.value
                if (current !is SenderState.Grabbed) {
                    pendingGrab = null
                    return@collect
                }
                Log.d(TAG, "Peer connected, re-broadcasting pending GrabReady")
                viewModelScope.launch {
                    val delivered = nearbyTransport.broadcastControl(pending)
                    if (delivered > 0) {
                        pendingGrab = null
                        _statusMessage.value = null
                    }
                }
            }
        }
    }

    private fun observeIncomingControl() {
        viewModelScope.launch {
            nearbyTransport.incomingControl.collect { (endpointId, message) ->
                when (message) {
                    is ControlMessage.GrabReady -> {
                        wakeScreen()
                        if (app.isAppForeground) {
                            // Foreground: switch to the single transfer screen and let the user
                            // answer with a palm right here.
                            if (_appMode.value != AppMode.ACTIVE) setAppMode(AppMode.ACTIVE)
                            stateMachine.onGrabReadyReceived(message, endpointId)
                            gestureDetector.setPowerMode(active = true) // Boost to 30 fps
                        } else {
                            // Backgrounded: the ambient service launches the wake activity.
                            Log.d(TAG, "Grab received in background; wake activity takes over")
                        }
                    }
                    is ControlMessage.AcceptDrop -> {
                        val lockResult = stateMachine.onAcceptDropReceived(message, endpointId)
                        if (lockResult != null) {
                            val (payload, sessionClaimed) = lockResult
                            pendingGrab = null
                            // Broadcast claim to all other peers
                            nearbyTransport.broadcastControl(sessionClaimed)
                            // Transmit payload directly to the endpoint that requested the drop!
                            transmitPayload(endpointId, payload, message.sessionId)
                        }
                    }
                    is ControlMessage.SessionClaimed -> {
                        stateMachine.onSessionClaimed(message, deviceId)
                        gestureDetector.setPowerMode(active = false)
                    }
                    is ControlMessage.TransferComplete -> {
                        if (_appMode.value == AppMode.ACTIVE) {
                            stateMachine.onSenderTransferComplete()
                            hysteresis.startCooldown()
                        }
                    }
                    is ControlMessage.TextContent -> {
                        val receiving = receiverState.value as? ReceiverState.Receiving
                        if (receiving == null) {
                            Log.w(TAG, "Ignoring text payload: this device did not accept the drop")
                            return@collect
                        }
                        if (message.sessionId.isNotBlank() && message.sessionId != receiving.sessionId) {
                            Log.w(TAG, "Ignoring text payload: session mismatch")
                            return@collect
                        }
                        val result = payloadSaver.saveTextToClipboard(message.text, message.isUrl)
                        stateMachine.onReceiverDelivered(result)
                        hysteresis.startCooldown()
                        gestureDetector.setPowerMode(active = false)
                        nearbyTransport.sendControl(
                            endpointId,
                            ControlMessage.TransferComplete(sessionId = message.sessionId, success = true)
                        )
                    }
                    is ControlMessage.FileHeader -> {
                        // Handled inside the transport (buffered until the file completes).
                    }
                }
            }
        }
    }

    private fun transmitPayload(
        receiverEndpointId: String,
        payload: TransferPayload,
        sessionId: String
    ) {
        viewModelScope.launch {
            try {
                when (payload) {
                    is TransferPayload.MediaItem -> {
                        activeTransferPayloadId = nearbyTransport.sendFile(
                            endpointId = receiverEndpointId,
                            uri = payload.uri,
                            fileName = payload.displayName,
                            mimeType = payload.mimeType,
                            transferKind = "MEDIA",
                            sessionId = sessionId
                        )
                    }
                    is TransferPayload.BinaryFile -> {
                        activeTransferPayloadId = nearbyTransport.sendFile(
                            endpointId = receiverEndpointId,
                            uri = payload.uri,
                            fileName = payload.displayName,
                            mimeType = payload.mimeType,
                            transferKind = "FILE",
                            sessionId = sessionId
                        )
                    }
                    is TransferPayload.FolderArchive -> {
                        activeTransferPayloadId = nearbyTransport.sendFile(
                            endpointId = receiverEndpointId,
                            uri = payload.zipUri,
                            fileName = "${payload.displayName}.zip",
                            mimeType = "application/zip",
                            transferKind = "FOLDER_ZIP",
                            sessionId = sessionId
                        )
                    }
                    is TransferPayload.TextOrLink -> {
                        nearbyTransport.sendControl(
                            receiverEndpointId,
                            ControlMessage.TextContent(
                                sessionId = sessionId,
                                text = payload.content,
                                isUrl = payload.isUrl
                            )
                        )
                        stateMachine.onSenderTransferComplete()
                        hysteresis.startCooldown()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to transmit payload: ${e.message}", e)
                stateMachine.resetSenderToStagedOrIdle()
                nearbyTransport.sendControl(
                    receiverEndpointId,
                    ControlMessage.TransferComplete(
                        sessionId = sessionId,
                        success = false,
                        errorMessage = e.message
                    )
                )
            }
        }
    }

    private fun observeIncomingFiles() {
        viewModelScope.launch {
            nearbyTransport.incomingFiles.collect { incoming ->
                val receiving = receiverState.value as? ReceiverState.Receiving

                // The wake activity and this ViewModel both observe the shared transport. Only
                // the component that owns the accepted session may consume the payload.
                if (receiving == null) {
                    Log.w(TAG, "Ignoring payload ${incoming.payloadId}: this device did not accept the drop")
                    return@collect
                }
                if (incoming.sessionId.isNotBlank() && incoming.sessionId != receiving.sessionId) {
                    Log.w(TAG, "Ignoring payload ${incoming.payloadId}: session mismatch")
                    return@collect
                }

                val grabReady = receiving.grabReady

                val fileName = incoming.fileName.takeIf { it.isNotBlank() }
                    ?: grabReady?.fileName
                    ?: "received_file_${System.currentTimeMillis()}"
                val mimeType = incoming.mimeType.takeIf { it.isNotBlank() }
                    ?: grabReady?.mimeType
                    ?: "application/octet-stream"
                val transferKind = incoming.transferKind.takeIf { it.isNotBlank() }
                    ?: grabReady?.payloadType
                    ?: "FILE"
                val sessionId = incoming.sessionId.takeIf { it.isNotBlank() }
                    ?: grabReady?.sessionId
                    ?: ""

                try {
                    val inputStream = NearbyConnectionsManager.openPayloadInputStream(context, incoming.payload)
                        ?: throw IllegalStateException("Cannot open stream for payload ${incoming.payloadId}")

                    val result = inputStream.use { stream ->
                        when (transferKind) {
                            "MEDIA" -> payloadSaver.saveMedia(fileName, mimeType, stream)
                            "FOLDER_ZIP" -> payloadSaver.saveAndExtractZip(fileName, stream)
                            else -> payloadSaver.saveDocumentOrBinary(fileName, mimeType, stream)
                        }
                    }

                    Log.d(TAG, "Payload ${incoming.payloadId} saved: $result")
                    stateMachine.onReceiverDelivered(result)
                    hysteresis.startCooldown()
                    gestureDetector.setPowerMode(active = false)

                    nearbyTransport.sendControl(
                        incoming.endpointId,
                        ControlMessage.TransferComplete(sessionId = sessionId, success = true)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving received file: ${e.message}", e)
                    stateMachine.resetReceiverToListening()
                    hysteresis.startCooldown()
                    gestureDetector.setPowerMode(active = false)
                    nearbyTransport.sendControl(
                        incoming.endpointId,
                        ControlMessage.TransferComplete(
                            sessionId = sessionId,
                            success = false,
                            errorMessage = e.message
                        )
                    )
                } finally {
                    // Free Nearby's temporary copy of the file.
                    NearbyConnectionsManager.deleteReceivedPayload(context, incoming.payload)
                }
            }
        }
    }

    private fun observeOutgoingComplete() {
        viewModelScope.launch {
            nearbyTransport.outgoingComplete.collect { (payloadId, _) ->
                Log.d(TAG, "Outgoing payload $payloadId completed")
                activeTransferPayloadId = null
                if (_appMode.value == AppMode.ACTIVE) {
                    stateMachine.onSenderTransferComplete()
                    hysteresis.startCooldown()
                }
            }
        }
    }

    private fun observeTransferFailures() {
        viewModelScope.launch {
            nearbyTransport.transferFailed.collect { payloadId ->
                Log.w(TAG, "Payload $payloadId failed")
                if (activeTransferPayloadId == payloadId) {
                    activeTransferPayloadId = null
                }
                if (senderState.value is SenderState.Transferring) {
                    stateMachine.resetSenderToStagedOrIdle()
                }
                if (receiverState.value is ReceiverState.Receiving) {
                    stateMachine.resetReceiverToListening()
                }
                hysteresis.startCooldown()
            }
        }
    }

    private fun observeProgress() {
        viewModelScope.launch {
            nearbyTransport.transferProgress.collect { progress ->
                if (progress != null) {
                    stateMachine.updateSenderProgress(progress)
                    stateMachine.updateReceiverProgress(progress)
                }
            }
        }
    }

    private fun startTtlMonitor() {
        ttlJob = viewModelScope.launch {
            while (true) {
                delay(400)
                stateMachine.checkTtlExpiry()
                if (pendingGrab != null && stateMachine.senderState.value !is SenderState.Grabbed) {
                    pendingGrab = null
                }
                val r = receiverState.value
                if (r is ReceiverState.Listening) {
                    gestureDetector.setPowerMode(active = false)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttlJob?.cancel()
        gestureDetector.stop()
        // Keep the room alive while the ambient wake service still needs it.
        if (!AirGrabDiscoveryService.isServiceRunning) {
            nearbyTransport.stopRoom()
        }
    }
}
