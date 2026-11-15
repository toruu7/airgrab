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
import dev.toruu.airgrab.data.nearby.ControlMessage
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.vision.HandGesture
import dev.toruu.airgrab.data.vision.MediaPipeGestureDetector
import dev.toruu.airgrab.domain.GestureEvent
import dev.toruu.airgrab.domain.GestureHysteresis
import dev.toruu.airgrab.service.AirGrabDiscoveryService
import dev.toruu.airgrab.ui.components.RadialGlowOverlay
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

        private const val ACTIVE_DROP_WINDOW_MS = 4000L
    }

    private var gestureDetector: MediaPipeGestureDetector? = null
    private val hysteresis = GestureHysteresis()
    private lateinit var payloadSaver: PayloadSaver

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

        val sharedManager = AirGrabDiscoveryService.sharedNearbyManager
        if (sharedManager != null) {
            lifecycleScope.launch {
                val myEndpointId = sharedManager.connectedPeers.value.keys.firstOrNull() ?: "local-receiver"
                val acceptDrop = ControlMessage.AcceptDrop(
                    sessionId = sessionId,
                    receiverEndpointId = myEndpointId
                )
                sharedManager.sendControl(senderEndpointId, acceptDrop)
            }
        }
    }

    private fun observeControlMessages() {
        val sharedManager = AirGrabDiscoveryService.sharedNearbyManager ?: return
        lifecycleScope.launch {
            sharedManager.incomingControl.collectLatest { (_, message) ->
                when (message) {
                    is ControlMessage.SessionClaimed -> {
                        val myEndpointId = sharedManager.connectedPeers.value.keys.firstOrNull() ?: ""
                        if (message.sessionId == sessionId && message.claimedByEndpointId != myEndpointId) {
                            // Another peer claimed drop
                            finish()
                        }
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
        val sharedManager = AirGrabDiscoveryService.sharedNearbyManager ?: return
        lifecycleScope.launch {
            sharedManager.transferProgress.collectLatest { prog ->
                transferProgressState.value = prog
            }
        }

        lifecycleScope.launch {
            sharedManager.incomingFiles.collectLatest { incoming ->
                val inputStream = dev.toruu.airgrab.data.nearby.NearbyConnectionsManager.openPayloadInputStream(
                    applicationContext,
                    incoming.payload
                )
                if (inputStream != null) {
                    val result = inputStream.use { stream ->
                        if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                            payloadSaver.saveMedia(fileName, mimeType, stream)
                        } else if (fileName.endsWith(".zip")) {
                            payloadSaver.saveAndExtractZip(fileName, stream)
                        } else {
                            payloadSaver.saveDocumentOrBinary(fileName, stream)
                        }
                    }
                    deliveredResultState.value = result
                    isReceivingState.value = false // Stop pulsing animation on delivery

                    sharedManager.sendControl(
                        senderEndpointId,
                        ControlMessage.TransferComplete(sessionId = sessionId, success = true)
                    )
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141218))
    ) {
        RadialGlowOverlay(
            isActive = isReceiving,
            glowColor = MaterialTheme.colorScheme.primary
        )

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
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = senderName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onBackground
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
                    modifier = Modifier.size(140.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { animatedArc },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 6.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Icon(
                        imageVector = Icons.Default.PanTool,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
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
                                text = "TRANSFER COMPLETE!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (isReceiving) {
                            val p = progress ?: 0f
                            LinearProgressIndicator(
                                progress = { p },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Receiving: ${(p * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                text = "OPEN PALM TO CATCH (${(remainingMs / 1000) + 1}s)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Bottom: Touchless Catch Guidance (Headless Camera - No Video Feed)
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PanTool,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = "Touchless Catch Active",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Raise an open palm toward phone to drop payload.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
