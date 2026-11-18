package dev.toruu.airgrab.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.toruu.airgrab.AirGrabApp
import dev.toruu.airgrab.data.nearby.ControlMessage
import dev.toruu.airgrab.data.nearby.NearbyConnectionsManager
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.vision.HandGesture
import dev.toruu.airgrab.data.vision.MediaPipeGestureDetector
import dev.toruu.airgrab.domain.GestureEvent
import dev.toruu.airgrab.domain.GestureHysteresis
import dev.toruu.airgrab.ui.components.SpatialCardPhase
import dev.toruu.airgrab.ui.components.SpatialGlowField
import dev.toruu.airgrab.ui.components.spatial3DGlowCard
import dev.toruu.airgrab.ui.components.spatialAura
import dev.toruu.airgrab.ui.screens.DeliveredCard
import dev.toruu.airgrab.ui.theme.AirGrabMotion
import dev.toruu.airgrab.ui.theme.AirGrabTheme
import dev.toruu.airgrab.ui.theme.LocalAirGrabPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReceiverWakeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_SENDER_ENDPOINT_ID = "extra_sender_endpoint_id"
        const val EXTRA_SENDER_NAME = "extra_sender_name"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_SIZE_BYTES = "extra_size_bytes"
        const val EXTRA_EXPIRES_AT = "extra_expires_at"

        private const val ACTIVE_DROP_WINDOW_MS = 12_000L
    }

    private var gestureDetector: MediaPipeGestureDetector? = null
    private val hysteresis = GestureHysteresis()
    private lateinit var payloadSaver: PayloadSaver

    private val transport by lazy { AirGrabApp.from(this).transport }
    private val deviceId by lazy { AirGrabApp.from(this).deviceId }

    private var sessionId: String = ""
    private var senderEndpointId: String = ""
    private val senderNameState = mutableStateOf("Nearby Peer")
    private val fileNameState = mutableStateOf("Shared Item")
    private var mimeType: String = "application/octet-stream"
    private val sizeBytesState = mutableStateOf(0L)

    private val isReceivingState = mutableStateOf(false)
    private val transferProgressState = mutableStateOf<Float?>(null)
    private val deliveredResultState = mutableStateOf<PayloadSaver.SaveResult?>(null)
    private val errorMessageState = mutableStateOf<String?>(null)
    private val remainingDropWindowMs = mutableStateOf(ACTIVE_DROP_WINDOW_MS)
    private val gestureConfidenceState = mutableStateOf(0f)
    private val handPresentState = mutableStateOf(false)

    private var countdownJob: Job? = null
    private var gestureJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        configureLockScreenWindow()

        payloadSaver = PayloadSaver(applicationContext)
        if (!readExtras(intent)) return

        gestureDetector = MediaPipeGestureDetector(applicationContext)

        observeIncomingPayloads()
        observeControlMessages()

        setContent {
            AirGrabTheme {
                WakeScreenContent(
                    senderName = senderNameState.value,
                    fileName = fileNameState.value,
                    sizeBytes = sizeBytesState.value,
                    remainingMs = remainingDropWindowMs.value,
                    totalWindowMs = ACTIVE_DROP_WINDOW_MS,
                    isReceiving = isReceivingState.value,
                    progress = transferProgressState.value,
                    deliveredResult = deliveredResultState.value,
                    errorMessage = errorMessageState.value,
                    gestureConfidence = if (handPresentState.value) gestureConfidenceState.value else 0f,
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (readExtras(intent)) {
            startDropCountdown()
        }
    }

    private fun readExtras(intent: Intent?): Boolean {
        if (intent == null) return false
        val newSessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        if (AirGrabApp.from(this).activeReceiverSessionId == newSessionId && newSessionId.isNotBlank()) {
            finish()
            return false
        }

        sessionId = newSessionId
        senderEndpointId = intent.getStringExtra(EXTRA_SENDER_ENDPOINT_ID) ?: ""
        senderNameState.value = intent.getStringExtra(EXTRA_SENDER_NAME) ?: "Nearby Peer"
        fileNameState.value = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Shared Item"
        mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "application/octet-stream"
        sizeBytesState.value = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L)

        isReceivingState.value = false
        transferProgressState.value = null
        deliveredResultState.value = null
        errorMessageState.value = null
        remainingDropWindowMs.value = ACTIVE_DROP_WINDOW_MS
        hysteresis.resetState()
        return true
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
        gestureJob?.cancel()
        gestureDetector?.unbindCamera()
    }

    private fun startDropCountdown() {
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = (ACTIVE_DROP_WINDOW_MS - elapsed).coerceAtLeast(0L)
                remainingDropWindowMs.value = remaining

                if (remaining <= 0L && !isReceivingState.value && deliveredResultState.value == null) {
                    finish()
                    break
                }
                delay(100L)
            }
        }
    }

    private fun observeGestures() {
        val detector = gestureDetector ?: return
        gestureJob?.cancel()
        gestureJob = lifecycleScope.launch {
            detector.gestures.collect { frame ->
                handPresentState.value = frame.gesture != HandGesture.NONE
                gestureConfidenceState.value = frame.confidence
                val event = hysteresis.processFrame(frame)
                if (event is GestureEvent.DropTriggered &&
                    !isReceivingState.value &&
                    deliveredResultState.value == null
                ) {
                    onDropAccepted()
                }
            }
        }
    }

    private fun onDropAccepted() {
        isReceivingState.value = true
        errorMessageState.value = null
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
            transport.incomingControl.collect { (_, message) ->
                when (message) {
                    is ControlMessage.SessionClaimed -> {
                        if (message.sessionId == sessionId && message.claimedByEndpointId != deviceId) {
                            finish()
                        }
                    }
                    is ControlMessage.TextContent -> {
                        if (message.sessionId != sessionId) return@collect
                        val result = payloadSaver.saveTextToClipboard(message.text, message.isUrl)
                        deliveredResultState.value = result
                        isReceivingState.value = false
                        hysteresis.startCooldown()
                        transport.resetProgress()
                        transport.sendControl(
                            senderEndpointId,
                            ControlMessage.TransferComplete(sessionId = sessionId, success = true)
                        )
                    }
                    is ControlMessage.TransferComplete -> {
                        if (message.sessionId == sessionId) {
                            if (!message.success) {
                                isReceivingState.value = false
                                errorMessageState.value = message.errorMessage ?: "Transfer interrupted"
                            }
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
            transport.transferFailed.collect {
                if (isReceivingState.value && deliveredResultState.value == null) {
                    isReceivingState.value = false
                    errorMessageState.value = "Transfer interrupted"
                    transport.resetProgress()
                }
            }
        }

        lifecycleScope.launch {
            transport.incomingFiles.collectLatest { incoming ->
                if (sessionId.isNotBlank() && incoming.sessionId.isNotBlank() && incoming.sessionId != sessionId) {
                    return@collectLatest
                }
                try {
                    val inputStream = NearbyConnectionsManager.openPayloadInputStream(
                        applicationContext,
                        incoming.payload
                    ) ?: throw IllegalStateException("Cannot open stream for payload ${incoming.payloadId}")

                    val name = incoming.fileName.takeIf { it.isNotBlank() } ?: fileNameState.value
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
                    isReceivingState.value = false
                    transport.resetProgress()

                    transport.sendControl(
                        senderEndpointId,
                        ControlMessage.TransferComplete(sessionId = sessionId, success = true)
                    )
                } catch (e: Exception) {
                    android.util.Log.e("ReceiverWakeActivity", "Failed to process incoming file: ${e.message}", e)
                    isReceivingState.value = false
                    errorMessageState.value = "Could not save file"
                    transport.resetProgress()
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
        countdownJob?.cancel()
        gestureJob?.cancel()
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
    totalWindowMs: Long,
    isReceiving: Boolean,
    progress: Float?,
    deliveredResult: PayloadSaver.SaveResult?,
    errorMessage: String?,
    gestureConfidence: Float,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val palette = LocalAirGrabPalette.current

    LaunchedEffect(isReceiving, deliveredResult) {
        if (isReceiving || deliveredResult != null) {
            runCatching { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
        }
    }

    val wakeIntensity = when {
        gestureConfidence > 0.15f -> gestureConfidence
        isReceiving -> 0.55f
        else -> 0.28f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .spatialAura(
                color = palette.accentReceiveAura,
                intensity = wakeIntensity,
                maxAlpha = if (palette.isDark) 0.28f else 0.20f
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar: Sender Badge & 48dp Dismiss Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = palette.surfaceGlass,
                    border = BorderStroke(1.dp, palette.borderHairline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(palette.accentReceive)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = senderName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(palette.surfaceGlass)
                        .border(1.dp, palette.borderHairline, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss incoming drop",
                        tint = palette.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Adaptive Scrollable Center Stage
            val wakeTriggerKey: Any = when {
                deliveredResult != null -> "wake_delivered"
                isReceiving -> "wake_receiving"
                else -> "wake_armed"
            }
            val wakePhase = when {
                deliveredResult != null -> SpatialCardPhase.DELIVERED
                isReceiving -> SpatialCardPhase.RELEASING
                else -> SpatialCardPhase.GRABBED
            }

            SpatialGlowField(
                phase = wakePhase,
                triggerKey = wakeTriggerKey,
                isOutwardRelease = isReceiving || deliveredResult != null,
                glowColor = palette.accentReceiveAura,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    if (deliveredResult != null) {
                        DeliveredCard(
                            result = deliveredResult,
                            palette = palette,
                            onDone = onDismiss
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .widthIn(max = 440.dp)
                                .fillMaxWidth()
                        ) {
                            val arcProgress = (remainingMs.toFloat() / totalWindowMs.toFloat()).coerceIn(0f, 1f)
                            val animatedArc by animateFloatAsState(
                                targetValue = arcProgress,
                                animationSpec = if (palette.reduceMotion) snap() else AirGrabMotion.LayoutSettle,
                                label = "WakeArc"
                            )

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(120.dp)
                            ) {
                                CircularProgressIndicator(
                                    progress = { if (isReceiving) (progress ?: 0f) else animatedArc },
                                    modifier = Modifier.fillMaxSize(),
                                    strokeWidth = 4.dp,
                                    color = palette.accentReceive,
                                    trackColor = palette.surfaceSubtle
                                )

                                Icon(
                                    imageVector = Icons.Default.PanTool,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                    tint = palette.accentReceive
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .spatial3DGlowCard(
                                        phase = if (isReceiving) SpatialCardPhase.RELEASING else SpatialCardPhase.GRABBED,
                                        glowColor = palette.accentReceive
                                    )
                                    .background(palette.surfaceCard, RoundedCornerShape(28.dp))
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )

                                if (sizeBytes > 0) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = Formatter.formatFileSize(context, sizeBytes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.textSecondary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.height(18.dp))

                                if (errorMessage != null) {
                                    Text(
                                        text = errorMessage,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.accentError,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center
                                    )
                                } else if (isReceiving) {
                                    val p = (progress ?: 0f).coerceIn(0f, 1f)
                                    LinearProgressIndicator(
                                        progress = { p },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape),
                                        color = palette.accentReceive,
                                        trackColor = palette.surfaceSubtle
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Receiving ${(p * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontFamily = FontFamily.Monospace,
                                        color = palette.accentReceive,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Text(
                                        text = "Open palm to catch (${(remainingMs / 1000) + 1}s)",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.accentReceive
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}
