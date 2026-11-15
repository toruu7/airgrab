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
import dev.toruu.airgrab.data.nearby.ControlMessage
import dev.toruu.airgrab.data.nearby.NearbyConnectionsManager
import dev.toruu.airgrab.data.payload.PayloadResolver
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.payload.TransferPayload
import dev.toruu.airgrab.data.vision.GestureFrameResult
import dev.toruu.airgrab.data.vision.MediaPipeGestureDetector
import dev.toruu.airgrab.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.FileInputStream

enum class AppMode {
    HOME,
    SENDER,
    RECEIVER
}

class AirGrabViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AirGrab_VM"
    }

    private val context = application.applicationContext
    val gestureDetector = MediaPipeGestureDetector(context)
    val nearbyTransport = NearbyConnectionsManager(context)
    val stateMachine = GrabDropStateMachine()
    private val hysteresis = GestureHysteresis()
    private val payloadResolver = PayloadResolver(context)
    private val payloadSaver = PayloadSaver(context)

    private val _appMode = MutableStateFlow(AppMode.HOME)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    val connectedPeers: StateFlow<Map<String, String>> = nearbyTransport.connectedPeers
    val senderState: StateFlow<SenderState> = stateMachine.senderState
    val receiverState: StateFlow<ReceiverState> = stateMachine.receiverState
    val currentGesture: StateFlow<GestureFrameResult> = gestureDetector.gestures
    val transferProgress: StateFlow<Float?> = nearbyTransport.transferProgress

    private var ttlJob: Job? = null
    private var activeTransferPayloadId: Long? = null

    init {
        observeGestures()
        observeIncomingControl()
        observeIncomingFiles()
        observeOutgoingComplete()
        observeProgress()
        startTtlMonitor()
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
            AppMode.SENDER -> {
                _isHost.value = false
                nearbyTransport.startOpenRoom(Build.MODEL, isHost = false)
                gestureDetector.setPowerMode(active = false)
            }
            AppMode.RECEIVER -> {
                _isHost.value = true
                nearbyTransport.startOpenRoom(Build.MODEL, isHost = true)
                gestureDetector.setPowerMode(active = false)
            }
        }
    }

    fun toggleHostMode() {
        val newHost = !_isHost.value
        _isHost.value = newHost
        nearbyTransport.startOpenRoom(Build.MODEL, isHost = newHost)
    }

    fun handleIncomingIntent(intent: Intent) {
        viewModelScope.launch {
            val payload = payloadResolver.resolveIntent(intent)
            if (payload != null) {
                stagePayload(payload)
                setAppMode(AppMode.SENDER)
            }
        }
    }

    fun stagePayload(payload: TransferPayload) {
        stateMachine.stagePayload(payload)
    }

    fun stageUri(uri: Uri) {
        viewModelScope.launch {
            val payload = payloadResolver.resolveUri(uri)
            stagePayload(payload)
            setAppMode(AppMode.SENDER)
        }
    }

    fun stageFolderTree(treeUri: Uri) {
        viewModelScope.launch {
            val payload = payloadResolver.archiveFolderTree(treeUri)
            stagePayload(payload)
            setAppMode(AppMode.SENDER)
        }
    }

    fun stageClipboard() {
        val clip = payloadResolver.resolveClipboard()
        if (clip != null) {
            stagePayload(clip)
            setAppMode(AppMode.SENDER)
        }
    }

    fun cancelTransfer() {
        activeTransferPayloadId?.let { nearbyTransport.cancelTransfer(it) }
        activeTransferPayloadId = null
        stateMachine.resetSenderToStagedOrIdle()
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
        if (_appMode.value != AppMode.SENDER) return
        try {
            val myEndpointId = nearbyTransport.connectedPeers.value.keys.firstOrNull() ?: "local-sender"
            val grabReady = stateMachine.onGrabTriggered(myEndpointId, Build.MODEL)
            if (grabReady != null) {
                Log.d(TAG, "Grab gesture triggered, broadcasting GrabReady: $grabReady")
                viewModelScope.launch {
                    try {
                        nearbyTransport.broadcastControl(grabReady)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to broadcast GrabReady: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling onGrabGesture: ${e.message}", e)
        }
    }

    private fun onDropGesture() {
        if (_appMode.value != AppMode.RECEIVER) return
        try {
            val myEndpointId = nearbyTransport.connectedPeers.value.keys.firstOrNull() ?: "local-receiver"
            val acceptDrop = stateMachine.onDropTriggered(myEndpointId)
            if (acceptDrop != null) {
                Log.d(TAG, "Drop gesture triggered, sending AcceptDrop: $acceptDrop")
                viewModelScope.launch {
                    try {
                        val currentArmed = receiverState.value
                        val targetEndpoint = if (currentArmed is ReceiverState.Armed) {
                            currentArmed.grabReady.senderEndpointId
                        } else {
                            nearbyTransport.connectedPeers.value.keys.firstOrNull()
                        }

                        targetEndpoint?.let {
                            nearbyTransport.sendControl(it, acceptDrop)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send AcceptDrop: ${e.message}", e)
                    }
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

    private fun observeIncomingControl() {
        viewModelScope.launch {
            nearbyTransport.incomingControl.collect { (endpointId, message) ->
                when (message) {
                    is ControlMessage.GrabReady -> {
                        wakeScreen()
                        stateMachine.onGrabReadyReceived(message)
                        gestureDetector.setPowerMode(active = true) // Boost to 30 fps for drop
                    }
                    is ControlMessage.AcceptDrop -> {
                        val lockResult = stateMachine.onAcceptDropReceived(message)
                        if (lockResult != null) {
                            val (payload, sessionClaimed) = lockResult
                            // Broadcast claim to all other peers
                            nearbyTransport.broadcastControl(sessionClaimed)
                            // Transmit payload
                            transmitPayload(message.receiverEndpointId, payload)
                        }
                    }
                    is ControlMessage.SessionClaimed -> {
                        val myEndpointId = nearbyTransport.connectedPeers.value.keys.firstOrNull() ?: ""
                        stateMachine.onSessionClaimed(message, myEndpointId)
                        gestureDetector.setPowerMode(active = false)
                    }
                    is ControlMessage.TransferComplete -> {
                        if (_appMode.value == AppMode.SENDER) {
                            stateMachine.onSenderTransferComplete()
                            hysteresis.startCooldown()
                        }
                    }
                }
            }
        }
    }

    private fun transmitPayload(receiverEndpointId: String, payload: TransferPayload) {
        viewModelScope.launch {
            try {
                when (payload) {
                    is TransferPayload.MediaItem -> {
                        activeTransferPayloadId = nearbyTransport.sendFile(
                            endpointId = receiverEndpointId,
                            uri = payload.uri,
                            fileName = payload.displayName,
                            mimeType = payload.mimeType
                        )
                    }
                    is TransferPayload.BinaryFile -> {
                        activeTransferPayloadId = nearbyTransport.sendFile(
                            endpointId = receiverEndpointId,
                            uri = payload.uri,
                            fileName = payload.displayName,
                            mimeType = payload.mimeType
                        )
                    }
                    is TransferPayload.FolderArchive -> {
                        activeTransferPayloadId = nearbyTransport.sendFile(
                            endpointId = receiverEndpointId,
                            uri = payload.zipUri,
                            fileName = "${payload.displayName}.zip",
                            mimeType = "application/zip"
                        )
                    }
                    is TransferPayload.TextOrLink -> {
                        val bytes = payload.content.toByteArray(Charsets.UTF_8)
                        activeTransferPayloadId = nearbyTransport.sendBytes(receiverEndpointId, bytes)
                        stateMachine.onSenderTransferComplete()
                        hysteresis.startCooldown()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to transmit payload: ${e.message}", e)
                stateMachine.resetSenderToStagedOrIdle()
            }
        }
    }

    private fun observeIncomingFiles() {
        viewModelScope.launch {
            nearbyTransport.incomingFiles.collect { incoming ->
                val currentRecState = receiverState.value
                val grabReady = if (currentRecState is ReceiverState.Receiving) {
                    currentRecState.grabReady
                } else null

                val fileName = grabReady?.fileName ?: "received_file_${System.currentTimeMillis()}"
                val mimeType = grabReady?.mimeType ?: "application/octet-stream"
                val payloadType = grabReady?.payloadType ?: "FILE"

                try {
                    val inputStream = NearbyConnectionsManager.openPayloadInputStream(context, incoming.payload)
                    if (inputStream != null) {
                        val result = inputStream.use { stream ->
                            when (payloadType) {
                                "MEDIA" -> payloadSaver.saveMedia(fileName, mimeType, stream)
                                "FOLDER_ZIP" -> payloadSaver.saveAndExtractZip(fileName, stream)
                                else -> payloadSaver.saveDocumentOrBinary(fileName, stream)
                            }
                        }
                        Log.d(TAG, "File successfully processed and saved: $fileName ($result)")
                        stateMachine.onReceiverDelivered(result)
                        hysteresis.startCooldown()

                        // Send TransferComplete back to sender
                        val sessionId = grabReady?.sessionId ?: ""
                        nearbyTransport.sendControl(
                            incoming.endpointId,
                            ControlMessage.TransferComplete(sessionId = sessionId, success = true)
                        )
                    } else {
                        Log.e(TAG, "openPayloadInputStream returned null for payload ${incoming.payloadId}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving received file: ${e.message}", e)
                }
            }
        }
    }

    private fun observeOutgoingComplete() {
        viewModelScope.launch {
            nearbyTransport.outgoingComplete.collect { (payloadId, endpointId) ->
                if (_appMode.value == AppMode.SENDER) {
                    stateMachine.onSenderTransferComplete()
                    hysteresis.startCooldown()
                }
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
        nearbyTransport.stopRoom()
    }
}
