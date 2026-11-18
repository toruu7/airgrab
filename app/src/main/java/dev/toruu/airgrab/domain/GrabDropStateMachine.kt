package dev.toruu.airgrab.domain

import android.util.Log
import dev.toruu.airgrab.data.nearby.ControlMessage
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.payload.TransferPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.abs

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
    data class Armed(
        val grabReady: ControlMessage.GrabReady,
        val senderEndpointId: String
    ) : ReceiverState
    data class Receiving(
        val sessionId: String,
        val grabReady: ControlMessage.GrabReady,
        val senderEndpointId: String,
        val progress: Float
    ) : ReceiverState
    data class Delivered(
        val sessionId: String,
        val result: PayloadSaver.SaveResult
    ) : ReceiverState
}

class GrabDropStateMachine {

    companion object {
        const val GRAB_READY_TTL_MS = 15_000L
        const val ACTIVE_DROP_WINDOW_MS = 4000L
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

    fun onGrabTriggered(senderDeviceId: String, senderModelName: String): ControlMessage.GrabReady? {
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
                // NOTE: this is the sender's stable device id, not a Nearby endpoint id. The
                // receiver always replies to the endpoint the message arrived from.
                senderEndpointId = senderDeviceId,
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
        acceptDrop: ControlMessage.AcceptDrop,
        actualEndpointId: String
    ): Pair<TransferPayload, ControlMessage.SessionClaimed>? {
        val current = _senderState.value
        // First-come, first-served lock!
        if (current is SenderState.Grabbed && current.sessionId == acceptDrop.sessionId) {
            val lockedSessionId = current.sessionId
            val payload = current.payload
            _senderState.value = SenderState.Transferring(
                sessionId = lockedSessionId,
                receiverEndpointId = actualEndpointId,
                payload = payload,
                progress = 0f
            )

            val sessionClaimed = ControlMessage.SessionClaimed(
                sessionId = lockedSessionId,
                // Stable per-install device id of the winner (endpoint ids are local-only views)
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

    fun onGrabReadyReceived(grabReady: ControlMessage.GrabReady, senderEndpointId: String) {
        // Never clobber an in-flight receive: the payload consumer checks that the state is
        // Receiving, so replacing it would silently drop the file that is being saved.
        if (_receiverState.value is ReceiverState.Receiving) {
            Log.d("AirGrab_State", "Busy receiving; ignoring new grab ${grabReady.fileName}")
            return
        }

        val now = System.currentTimeMillis()

        // The deadline was computed with the *sender's* clock. If the two phones are off by a
        // few seconds (very common), a fresh request can look already expired and the receiver
        // never arms - the palm gesture then silently does nothing. Re-base the deadline on the
        // local receipt time and ignore the sender's absolute timestamp.
        val senderSkewMs = grabReady.expiresAtEpochMs - GRAB_READY_TTL_MS - now
        if (abs(senderSkewMs) > 2_000L) {
            Log.w(
                "AirGrab_State",
                "Device clocks differ by ${senderSkewMs}ms; using local ${GRAB_READY_TTL_MS}ms TTL"
            )
        }

        _receiverState.value = ReceiverState.Armed(
            grabReady = grabReady.copy(expiresAtEpochMs = now + GRAB_READY_TTL_MS),
            senderEndpointId = senderEndpointId
        )
    }

    fun onDropTriggered(receiverDeviceId: String): Pair<String, ControlMessage.AcceptDrop>? {
        val current = _receiverState.value
        if (current is ReceiverState.Armed) {
            if (System.currentTimeMillis() <= current.grabReady.expiresAtEpochMs) {
                val acceptDrop = ControlMessage.AcceptDrop(
                    sessionId = current.grabReady.sessionId,
                    // Stable device id of this receiver; endpoint ids are only meaningful
                    // for the peer that owns them.
                    receiverEndpointId = receiverDeviceId
                )
                _receiverState.value = ReceiverState.Receiving(
                    sessionId = current.grabReady.sessionId,
                    grabReady = current.grabReady,
                    senderEndpointId = current.senderEndpointId,
                    progress = 0f
                )
                return current.senderEndpointId to acceptDrop
            } else {
                _receiverState.value = ReceiverState.Listening
            }
        }
        return null
    }

    fun onSessionClaimed(sessionClaimed: ControlMessage.SessionClaimed, myDeviceId: String) {
        val current = _receiverState.value
        if (current is ReceiverState.Armed && current.grabReady.sessionId == sessionClaimed.sessionId) {
            if (sessionClaimed.claimedByEndpointId != myDeviceId) {
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
