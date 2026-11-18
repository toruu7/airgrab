package dev.toruu.airgrab.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.toruu.airgrab.AirGrabTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.toruu.airgrab.AirGrabApp
import dev.toruu.airgrab.data.nearby.ControlMessage
import dev.toruu.airgrab.data.nearby.NearbyConnectionsManager
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.vision.HandGesture
import dev.toruu.airgrab.data.vision.MediaPipeGestureDetector
import dev.toruu.airgrab.domain.GestureEvent
import dev.toruu.airgrab.domain.GestureHysteresis
import dev.toruu.airgrab.ui.components.RadialGlowOverlay
import dev.toruu.airgrab.ui.components.spatialAura
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.FileInputStream

class ReceiverWakeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_SENDER_ENDPOINT_ID = "extra_sender_endpoint_id"
        const val EXTRA_SENDER_NAME = "extra_sender_name"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_SIZE_BYTES = "extra_size_bytes"
        const val EXTRA_EXPIRES_AT = "extra_expires_at"

        // The countdown starts when the screen appears, so leave time for the camera to warm up
        // and for the user to react. The sender keeps the session alive for 15s.
        private const val ACTIVE_DROP_WINDOW_MS = 12_000L
    }

    private var gestureDetector: MediaPipeGestureDetector? = null
    private val hysteresis = GestureHysteresis()
    private lateinit var payloadSaver: PayloadSaver

    private val transport by lazy { AirGrabApp.from(this).transport }
    private val deviceId by lazy { AirGrabApp.from(this).deviceId }

    private var sessionId: String = ""
    private var senderEndpointId: String = ""
    private var senderName: String = ""
    private var fileName: String = ""
    private var mimeType: String = ""
    private var sizeBytes: Long = 0L

    private val isReceivingState = mutableStateOf(false)
    private val transferProgressState = mutableStateOf<Float?>(null)
    private val deliveredResultState = mutableStateOf<PayloadSaver.SaveResult?>(null)
    private val remainingDropWindowMs = mutableStateOf(ACTIVE_DROP_WINDOW_MS)

    private var countdownJob: Job? = null
    private var previewViewRef: PreviewView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureLockScreenWindow()

        payloadSaver = PayloadSaver(applicationContext)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        senderEndpointId = intent.getStringExtra(EXTRA_SENDER_ENDPOINT_ID) ?: ""
        senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: "Nearby Peer"
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Shared Item"
        mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "application/octet-stream"
        sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L)

        // The in-app unified screen already owns this session - do not duplicate the drop UI.
        if (AirGrabApp.from(this).activeReceiverSessionId == sessionId) {
            finish()
            return
        }

        gestureDetector = MediaPipeGestureDetector(applicationContext)

        observeIncomingPayloads()
        observeControlMessages()

        setContent {
            AirGrabTheme {
                WakeScreenContent(
                    senderName = senderName,
                    fileName = fileName,
                    sizeBytes = sizeBytes,
                    remainingMs = remainingDropWindowMs.value,
                    isReceiving = isReceivingState.value,
                    progress = transferProgressState.value,
                    deliveredResult = deliveredResultState.value,
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun configureLockScreenWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
    }

    override fun onResume() {
        super.onResume()
        gestureDetector?.setPowerMode(active = true)
        gestureDetector?.start(this)

        startDropCountdown()
        observeGestures()
    }

    override fun onPause() {
        super.onPause()
        countdownJob?.cancel()
        gestureDetector?.stop()
    }

    private fun startDropCountdown() {
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = (ACTIVE_DROP_WINDOW_MS - elapsed).coerceAtLeast(0L)
                remainingDropWindowMs.value = remaining

                if (remaining <= 0L && !isReceivingState.value) {
                    // Time out, dismiss and let screen turn off
                    finish()
                    break
                }
                delay(100L)
            }
        }
    }

    private fun observeGestures() {
        val detector = gestureDetector ?: return
        lifecycleScope.launch {
            detector.gestures.collectLatest { frame ->
                val event = hysteresis.processFrame(frame)
                if (event is GestureEvent.DropTriggered && !isReceivingState.value) {
                    onDropAccepted()
                }
            }
        }
    }

    private fun onDropAccepted() {
        isReceivingState.value = true
        countdownJob?.cancel()

        lifecycleScope.launch {
            val acceptDrop = ControlMessage.AcceptDrop(
                sessionId = sessionId,
                receiverEndpointId = deviceId
            )
            transport.sendControl(senderEndpointId, acceptDrop)
        }
    }

    private fun observeControlMessages() {
        lifecycleScope.launch {
            transport.incomingControl.collectLatest { (_, message) ->
                when (message) {
                    is ControlMessage.SessionClaimed -> {
                        if (message.sessionId == sessionId && message.claimedByEndpointId != deviceId) {
                            // Another peer claimed the drop
                            finish()
                        }
                    }
                    is ControlMessage.TextContent -> {
                        if (message.sessionId != sessionId) return@collectLatest
                        val result = payloadSaver.saveTextToClipboard(message.text, message.isUrl)
                        deliveredResultState.value = result
                        isReceivingState.value = false
                        hysteresis.startCooldown()
                        transport.sendControl(
                            senderEndpointId,
                            ControlMessage.TransferComplete(sessionId = sessionId, success = true)
                        )
                    }
                    is ControlMessage.TransferComplete -> {
                        if (message.sessionId == sessionId) {
                            hysteresis.startCooldown()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeIncomingPayloads() {
        lifecycleScope.launch {
            transport.transferProgress.collectLatest { prog ->
                transferProgressState.value = prog
            }
        }

        lifecycleScope.launch {
            transport.incomingFiles.collectLatest { incoming ->
                // Only handle the session this activity accepted; the in-app receiver shares
                // the same transport and would otherwise save the file twice.
                if (sessionId.isNotBlank() && incoming.sessionId.isNotBlank() && incoming.sessionId != sessionId) {
                    android.util.Log.w("ReceiverWakeActivity", "Ignoring payload for another session")
                    return@collectLatest
                }
                try {
                    val inputStream = NearbyConnectionsManager.openPayloadInputStream(
                        applicationContext,
                        incoming.payload
                    ) ?: throw IllegalStateException("Cannot open stream for payload ${incoming.payloadId}")

                    val name = incoming.fileName.takeIf { it.isNotBlank() } ?: fileName
                    val mime = incoming.mimeType.takeIf { it.isNotBlank() } ?: mimeType
                    val kind = incoming.transferKind.takeIf { it.isNotBlank() }

                    val result = inputStream.use { stream ->
                        when {
                            kind == "FOLDER_ZIP" || name.endsWith(".zip", ignoreCase = true) ->
                                payloadSaver.saveAndExtractZip(name, stream)
                            kind == "MEDIA" || mime.startsWith("image/") || mime.startsWith("video/") ->
                                payloadSaver.saveMedia(name, mime, stream)
                            else -> payloadSaver.saveDocumentOrBinary(name, mime, stream)
                        }
                    }
                    deliveredResultState.value = result
                    isReceivingState.value = false // Stop pulsing animation on delivery

                    transport.sendControl(
                        senderEndpointId,
                        ControlMessage.TransferComplete(sessionId = sessionId, success = true)
                    )
                } catch (e: Exception) {
                    android.util.Log.e("ReceiverWakeActivity", "Failed to process incoming file: ${e.message}", e)
                    isReceivingState.value = false
                    transport.sendControl(
                        senderEndpointId,
                        ControlMessage.TransferComplete(
                            sessionId = sessionId,
                            success = false,
                            errorMessage = e.message
                        )
                    )
                } finally {
                    NearbyConnectionsManager.deleteReceivedPayload(applicationContext, incoming.payload)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gestureDetector?.stop()
        gestureDetector = null
    }
}

@Composable
fun WakeScreenContent(
    senderName: String,
    fileName: String,
    sizeBytes: Long,
    remainingMs: Long,
    isReceiving: Boolean,
    progress: Float?,
    deliveredResult: PayloadSaver.SaveResult?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    LaunchedEffect(isReceiving) {
        if (isReceiving) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    val auroraEmerald = Color(0xFF00FFA3)
    val voidBackground = Color(0xFF050608)
    val glassSurface = Color(0x0EFFFFFF)
    val glassBorder = Color(0x22FFFFFF)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(voidBackground)
            .spatialAura(
                color = auroraEmerald,
                intensity = { if (isReceiving) 0.9f else 0.45f }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Sender Badge & Dismiss
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = glassSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, glassBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(auroraEmerald)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = senderName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF5F5F7)
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color(0xFF8E8E93)
                    )
                }
            }

            // Center: File Card & Drop Countdown Arc
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Circular Countdown Arc
                val arcProgress = (remainingMs.toFloat() / 4000f).coerceIn(0f, 1f)
                val animatedArc by animateFloatAsState(targetValue = arcProgress, label = "Arc")

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(130.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { animatedArc },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 4.dp,
                        color = auroraEmerald,
                        trackColor = Color(0x1AFFFFFF)
                    )

                    Icon(
                        imageVector = Icons.Default.PanTool,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = auroraEmerald
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = glassSurface
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, auroraEmerald),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (sizeBytes > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = Formatter.formatFileSize(context, sizeBytes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        if (deliveredResult != null) {
                            Text(
                                text = "DELIVERED",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = auroraEmerald
                            )
                        } else if (isReceiving) {
                            val p = progress ?: 0f
                            LinearProgressIndicator(
                                progress = { p },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape),
                                color = auroraEmerald,
                                trackColor = Color(0x1AFFFFFF)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Receiving: ${(p * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFF5F5F7)
                            )
                        } else {
                            Text(
                                text = "Open palm to catch (${(remainingMs / 1000) + 1}s)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = auroraEmerald
                            )
                        }
                    }
                }
            }

            // Bottom Action / Open in Gallery
            if (deliveredResult != null && deliveredResult is PayloadSaver.SaveResult.MediaSaved) {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(deliveredResult.uri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open in Gallery"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = auroraEmerald)
                ) {
                    Text("Open in Gallery", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    text = "Raise open palm toward phone to accept",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E8E93),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
    }
}
