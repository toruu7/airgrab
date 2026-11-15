package dev.toruu.airgrab.domain

import dev.toruu.airgrab.data.nearby.ControlMessage
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.payload.TransferPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

sealed interface SenderState {
    object Idle : SenderState
    data class Staged(val payload: TransferPayload) : SenderState
    data class Grabbed(
        val sessionId: String,
        val payload: TransferPayload,
        val expiresAtEpochMs: Long
    ) : SenderState
    data class Transferring(
        val sessionId: String,
        val receiverEndpointId: String,
        val payload: TransferPayload,
        val progress: Float
    ) : SenderState
    data class Sent(val sessionId: String, val displayName: String) : SenderState
}

sealed interface ReceiverState {
    object Listening : ReceiverState
    data class Armed(val grabReady: ControlMessage.GrabReady) : ReceiverState
    data class Receiving(
        val sessionId: String,
        val grabReady: ControlMessage.GrabReady,
        val progress: Float
    ) : ReceiverState
    data class Delivered(
        val sessionId: String,
        val result: PayloadSaver.SaveResult
    ) : ReceiverState
}

class GrabDropStateMachine {

    companion object {
        const val GRAB_READY_TTL_MS = 5000L
    }

    private val _senderState = MutableStateFlow<SenderState>(SenderState.Idle)
    val senderState: StateFlow<SenderState> = _senderState.asStateFlow()

    private val _receiverState = MutableStateFlow<ReceiverState>(ReceiverState.Listening)
    val receiverState: StateFlow<ReceiverState> = _receiverState.asStateFlow()

    // --- Sender Actions ---

    fun stagePayload(payload: TransferPayload) {
        _senderState.value = SenderState.Staged(payload)
    }

    fun unstagePayload() {
        _senderState.value = SenderState.Idle
    }

    fun onGrabTriggered(senderEndpointId: String, senderModelName: String): ControlMessage.GrabReady? {
        val current = _senderState.value
        if (current is SenderState.Staged) {
            val payload = current.payload
            val sessionId = UUID.randomUUID().toString()
            val expiresAt = System.currentTimeMillis() + GRAB_READY_TTL_MS

            val (payloadType, textPreview) = when (payload) {
                is TransferPayload.MediaItem -> "MEDIA" to null
                is TransferPayload.BinaryFile -> "FILE" to null
                is TransferPayload.FolderArchive -> "FOLDER_ZIP" to null
                is TransferPayload.TextOrLink -> "TEXT" to payload.content.take(120)
            }

            val grabReady = ControlMessage.GrabReady(
                sessionId = sessionId,
                senderEndpointId = senderEndpointId,
                senderName = senderModelName,
                payloadType = payloadType,
                fileName = payload.displayName,
                mimeType = when (payload) {
                    is TransferPayload.MediaItem -> payload.mimeType
                    is TransferPayload.BinaryFile -> payload.mimeType
                    is TransferPayload.FolderArchive -> "application/zip"
                    is TransferPayload.TextOrLink -> if (payload.isUrl) "text/uri-list" else "text/plain"
                },
                sizeBytes = payload.totalBytes,
                textPreview = textPreview,
                expiresAtEpochMs = expiresAt
            )

            _senderState.value = SenderState.Grabbed(
                sessionId = sessionId,
                payload = payload,
                expiresAtEpochMs = expiresAt
            )
            return grabReady
        }
        return null
    }

    fun onAcceptDropReceived(
        acceptDrop: ControlMessage.AcceptDrop
    ): Pair<TransferPayload, ControlMessage.SessionClaimed>? {
        val current = _senderState.value
        // First-come, first-served lock!
        if (current is SenderState.Grabbed && current.sessionId == acceptDrop.sessionId) {
            val lockedSessionId = current.sessionId
            val payload = current.payload
            _senderState.value = SenderState.Transferring(
                sessionId = lockedSessionId,
                receiverEndpointId = acceptDrop.receiverEndpointId,
                payload = payload,
                progress = 0f
            )

            val sessionClaimed = ControlMessage.SessionClaimed(
                sessionId = lockedSessionId,
                claimedByEndpointId = acceptDrop.receiverEndpointId
            )
            return payload to sessionClaimed
        }
        return null
    }

    fun updateSenderProgress(progress: Float) {
        val current = _senderState.value
        if (current is SenderState.Transferring) {
            _senderState.value = current.copy(progress = progress.coerceIn(0f, 1f))
        }
    }

    fun onSenderTransferComplete() {
        val current = _senderState.value
        if (current is SenderState.Transferring) {
            _senderState.value = SenderState.Sent(current.sessionId, current.payload.displayName)
        }
    }

    fun resetSenderToStagedOrIdle() {
        val current = _senderState.value
        when (current) {
            is SenderState.Grabbed -> _senderState.value = SenderState.Staged(current.payload)
            is SenderState.Transferring -> _senderState.value = SenderState.Staged(current.payload)
            is SenderState.Sent -> _senderState.value = SenderState.Idle
            else -> {}
        }
    }

    // --- Receiver Actions ---

    fun onGrabReadyReceived(grabReady: ControlMessage.GrabReady) {
        if (System.currentTimeMillis() <= grabReady.expiresAtEpochMs) {
            _receiverState.value = ReceiverState.Armed(grabReady)
        }
    }

    fun onDropTriggered(receiverEndpointId: String): ControlMessage.AcceptDrop? {
        val current = _receiverState.value
        if (current is ReceiverState.Armed) {
            if (System.currentTimeMillis() <= current.grabReady.expiresAtEpochMs) {
                val acceptDrop = ControlMessage.AcceptDrop(
                    sessionId = current.grabReady.sessionId,
                    receiverEndpointId = receiverEndpointId
                )
                _receiverState.value = ReceiverState.Receiving(
                    sessionId = current.grabReady.sessionId,
                    grabReady = current.grabReady,
                    progress = 0f
                )
                return acceptDrop
            } else {
                _receiverState.value = ReceiverState.Listening
            }
        }
        return null
    }

    fun onSessionClaimed(sessionClaimed: ControlMessage.SessionClaimed, myEndpointId: String) {
        val current = _receiverState.value
        if (current is ReceiverState.Armed && current.grabReady.sessionId == sessionClaimed.sessionId) {
            if (sessionClaimed.claimedByEndpointId != myEndpointId) {
                // Lost race to another receiver
                _receiverState.value = ReceiverState.Listening
            }
        }
    }

    fun updateReceiverProgress(progress: Float) {
        val current = _receiverState.value
        if (current is ReceiverState.Receiving) {
            _receiverState.value = current.copy(progress = progress.coerceIn(0f, 1f))
        }
    }

    fun onReceiverDelivered(result: PayloadSaver.SaveResult) {
        val current = _receiverState.value
        if (current is ReceiverState.Receiving) {
            _receiverState.value = ReceiverState.Delivered(current.sessionId, result)
        }
    }

    fun resetReceiverToListening() {
        _receiverState.value = ReceiverState.Listening
    }

    fun checkTtlExpiry() {
        val now = System.currentTimeMillis()
        val s = _senderState.value
        if (s is SenderState.Grabbed && now > s.expiresAtEpochMs) {
            _senderState.value = SenderState.Staged(s.payload)
        }
        val r = _receiverState.value
        if (r is ReceiverState.Armed && now > r.grabReady.expiresAtEpochMs) {
            _receiverState.value = ReceiverState.Listening
        }
    }
}
