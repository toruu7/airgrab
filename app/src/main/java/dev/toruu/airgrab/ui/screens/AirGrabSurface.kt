package dev.toruu.airgrab.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.payload.PreviewBitmapState
import dev.toruu.airgrab.data.payload.TransferPayload
import dev.toruu.airgrab.data.payload.rememberPreviewBitmap
import dev.toruu.airgrab.data.vision.HandGesture
import dev.toruu.airgrab.domain.ReceiverState
import dev.toruu.airgrab.domain.SenderState
import dev.toruu.airgrab.service.AirGrabDiscoveryService
import dev.toruu.airgrab.ui.AirGrabViewModel
import dev.toruu.airgrab.ui.AppMode
import dev.toruu.airgrab.ui.components.PermissionHelper
import dev.toruu.airgrab.ui.components.SpatialCardPhase
import dev.toruu.airgrab.ui.components.SpatialGlowField
import dev.toruu.airgrab.ui.components.spatial3DGlowCard
import dev.toruu.airgrab.ui.components.spatialAura
import dev.toruu.airgrab.ui.theme.AirGrabMotion
import dev.toruu.airgrab.ui.theme.AirGrabPalette
import dev.toruu.airgrab.ui.theme.LocalAirGrabPalette

private enum class StageViewKind {
    STAGING,
    RECEIVING,
    DELIVERED,
    ARMED,
    TRANSFERRING,
    SENT,
    STAGED_OR_GRABBED,
    IDLE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirGrabSurface(
    viewModel: AirGrabViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = LocalAirGrabPalette.current

    val senderState by viewModel.senderState.collectAsState()
    val receiverState by viewModel.receiverState.collectAsState()
    val gestureResult by viewModel.currentGesture.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isStaging by viewModel.isStaging.collectAsState()

    var showAmbientSheet by remember { mutableStateOf(false) }

    // Ensure symmetric room discovery starts once PermissionGate passes.
    LaunchedEffect(Unit) {
        viewModel.setAppMode(AppMode.ACTIVE)
    }

    // Bind front camera whenever foregrounded; unbind cleanly via MainActivity.onStop.
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.bindCamera(lifecycleOwner)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.bindCamera(lifecycleOwner)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.stageUri(it) } }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.stageUri(it) } }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? -> treeUri?.let { viewModel.stageFolderTree(it) } }

    val isArmed = receiverState is ReceiverState.Armed
    val isReceiving = receiverState is ReceiverState.Receiving
    val delivered = receiverState as? ReceiverState.Delivered
    val isGrabbed = senderState is SenderState.Grabbed
    val isTransferring = senderState is SenderState.Transferring
    val sent = senderState as? SenderState.Sent

    // Tactile Haptics on gesture confirmation and completion.
    LaunchedEffect(isGrabbed) {
        if (isGrabbed) {
            runCatching { view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START) }
        }
    }

    LaunchedEffect(isReceiving, delivered, sent) {
        if (isReceiving || delivered != null || sent != null) {
            runCatching { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
        }
    }

    val auraColor = if (isArmed || isReceiving || delivered != null) {
        palette.accentReceiveAura
    } else {
        palette.accentSendAura
    }

    val auraIntensity = when {
        gestureResult.gesture != HandGesture.NONE && gestureResult.confidence > 0.15f ->
            gestureResult.confidence
        isGrabbed -> 0.55f
        isArmed || isReceiving -> 0.40f
        else -> 0f
    }

    val stageKind = when {
        isStaging -> StageViewKind.STAGING
        isReceiving -> StageViewKind.RECEIVING
        delivered != null -> StageViewKind.DELIVERED
        isArmed -> StageViewKind.ARMED
        isTransferring -> StageViewKind.TRANSFERRING
        sent != null -> StageViewKind.SENT
        senderState is SenderState.Staged || isGrabbed -> StageViewKind.STAGED_OR_GRABBED
        else -> StageViewKind.IDLE
    }

    val stagePhase = when {
        isReceiving || isTransferring -> SpatialCardPhase.RELEASING
        delivered != null || sent != null -> SpatialCardPhase.DELIVERED
        isGrabbed || isArmed -> SpatialCardPhase.GRABBED
        else -> SpatialCardPhase.RESTING
    }

    val effectTriggerKey: Any? = when {
        isReceiving -> "rec_rel_${(receiverState as ReceiverState.Receiving).sessionId}"
        delivered != null -> "rec_del_${delivered.sessionId}"
        isTransferring -> "send_rel_${(senderState as SenderState.Transferring).sessionId}"
        sent != null -> "send_done_${sent.sessionId}"
        isGrabbed -> "send_grab_${(senderState as SenderState.Grabbed).sessionId}"
        isArmed -> "rec_arm_${(receiverState as ReceiverState.Armed).grabReady.sessionId}"
        else -> null
    }
    val isOutwardEffect = isReceiving || delivered != null || isTransferring || sent != null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.canvas)
            .spatialAura(
                color = auraColor,
                intensity = auraIntensity,
                maxAlpha = if (palette.isDark) 0.28f else 0.20f
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- Top Hardware / Mesh Bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = CircleShape,
                    color = palette.surfaceGlass,
                    border = BorderStroke(1.dp, palette.borderHairline),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (connectedPeers.isNotEmpty()) palette.accentReceive
                                    else palette.accentSend
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                connectedPeers.size > 1 -> "${connectedPeers.size} devices nearby"
                                connectedPeers.size == 1 -> "Linked: ${connectedPeers.values.first()}"
                                else -> "AirGrab Ambient"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (senderState !is SenderState.Idle) {
                        IconButton(
                            onClick = {
                                when {
                                    isTransferring -> viewModel.cancelTransfer()
                                    sent != null -> viewModel.resetSenderForNextSend()
                                    else -> viewModel.clearPayload()
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceGlass)
                                .border(1.dp, palette.borderHairline, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear or cancel",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { showAmbientSheet = true },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(palette.surfaceGlass)
                            .border(1.dp, palette.borderHairline, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Ambient wake settings and peers",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Transient status notice banner
            AnimatedVisibility(
                visible = statusMessage != null,
                enter = fadeIn(if (palette.reduceMotion) snap() else AirGrabMotion.LayoutSettle),
                exit = fadeOut(if (palette.reduceMotion) snap() else AirGrabMotion.LayoutSettle)
            ) {
                statusMessage?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = palette.surfaceCard,
                        border = BorderStroke(1.dp, palette.borderHairline),
                        modifier = Modifier
                            .widthIn(max = 460.dp)
                            .padding(top = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textPrimary,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss notice",
                                tint = palette.textSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { viewModel.dismissStatusMessage() }
                            )
                        }
                    }
                }
            }

            // --- Unconstrained Full-Stage 3D Glow Field ---
            SpatialGlowField(
                phase = stagePhase,
                triggerKey = effectTriggerKey,
                isOutwardRelease = isOutwardEffect,
                glowColor = auraColor,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = stageKind,
                        transitionSpec = {
                            if (palette.reduceMotion) {
                                fadeIn(snap()) togetherWith fadeOut(snap())
                            } else {
                                (fadeIn(tween(240)) + scaleIn(
                                    initialScale = 0.94f,
                                    animationSpec = tween(260)
                                )) togetherWith fadeOut(tween(180))
                            }
                        },
                        label = "StageTransition"
                    ) { kind ->
                        when (kind) {
                            StageViewKind.STAGING -> StagingLoadingCard(palette = palette)

                            StageViewKind.RECEIVING -> {
                                val incomingState = receiverState as? ReceiverState.Receiving
                                if (incomingState != null) {
                                    TransferProgressCard(
                                        title = "Receiving",
                                        fileName = incomingState.grabReady.fileName,
                                        progress = transferProgress ?: incomingState.progress,
                                        accentColor = palette.accentReceive,
                                        palette = palette,
                                        onCancel = { viewModel.resetReceiverForNextDrop() }
                                    )
                                }
                            }

                            StageViewKind.DELIVERED -> {
                                if (delivered != null) {
                                    DeliveredCard(
                                        result = delivered.result,
                                        palette = palette,
                                        onDone = { viewModel.resetReceiverForNextDrop() }
                                    )
                                }
                            }

                            StageViewKind.ARMED -> {
                                val armedState = receiverState as? ReceiverState.Armed
                                if (armedState != null) {
                                    ArmedCatchCard(
                                        fileName = armedState.grabReady.fileName,
                                        senderName = armedState.grabReady.senderName,
                                        sizeBytes = armedState.grabReady.sizeBytes,
                                        textPreview = armedState.grabReady.textPreview,
                                        palette = palette
                                    )
                                }
                            }

                            StageViewKind.TRANSFERRING -> {
                                val transferringState = senderState as? SenderState.Transferring
                                if (transferringState != null) {
                                    TransferProgressCard(
                                        title = "Sending",
                                        fileName = transferringState.payload.displayName,
                                        progress = transferProgress ?: transferringState.progress,
                                        accentColor = palette.accentSend,
                                        palette = palette,
                                        onCancel = { viewModel.cancelTransfer() }
                                    )
                                }
                            }

                            StageViewKind.SENT -> {
                                if (sent != null) {
                                    SentConfirmationCard(
                                        displayName = sent.displayName,
                                        palette = palette,
                                        onDone = { viewModel.resetSenderForNextSend() }
                                    )
                                }
                            }

                            StageViewKind.STAGED_OR_GRABBED -> {
                                val payload = (senderState as? SenderState.Staged)?.payload
                                    ?: (senderState as? SenderState.Grabbed)?.payload
                                StagedPayloadCard(
                                    payload = payload,
                                    isGrabbed = isGrabbed,
                                    hasConnectedPeers = connectedPeers.isNotEmpty(),
                                    palette = palette
                                )
                            }

                            StageViewKind.IDLE -> IdleAmbientCanvas(palette = palette)
                        }
                    }
                }
            }

            // --- Floating Bottom Actions ---
            AnimatedVisibility(
                visible = senderState is SenderState.Idle &&
                    !isStaging && !isArmed && !isReceiving && delivered == null,
                enter = fadeIn(if (palette.reduceMotion) snap() else AirGrabMotion.LayoutSettle),
                exit = fadeOut(if (palette.reduceMotion) snap() else AirGrabMotion.LayoutSettle),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.widthIn(max = 460.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = palette.surfaceGlass,
                        border = BorderStroke(1.dp, palette.borderHairline),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .clickable { filePicker.launch("*/*") }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = palette.accentSend,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Stage File",
                                color = palette.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    FloatingQuickIcon(
                        icon = Icons.Default.PhotoLibrary,
                        contentDescription = "Stage Photo or Video",
                        palette = palette,
                        onClick = {
                            mediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        }
                    )

                    FloatingQuickIcon(
                        icon = Icons.Default.CreateNewFolder,
                        contentDescription = "Stage Folder",
                        palette = palette,
                        onClick = { folderPicker.launch(null) }
                    )

                    FloatingQuickIcon(
                        icon = Icons.Default.ContentPaste,
                        contentDescription = "Paste Clipboard",
                        palette = palette,
                        onClick = { viewModel.stageClipboard() }
                    )
                }
            }
        }
    }

    if (showAmbientSheet) {
        AmbientSettingsSheet(
            connectedPeers = connectedPeers,
            palette = palette,
            onDismiss = { showAmbientSheet = false }
        )
    }
}

@Composable
private fun FloatingQuickIcon(
    icon: ImageVector,
    contentDescription: String,
    palette: AirGrabPalette,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(palette.surfaceGlass)
            .border(1.dp, palette.borderHairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = palette.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmbientSettingsSheet(
    connectedPeers: Map<String, String>,
    palette: AirGrabPalette,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isServiceRunning by remember { mutableStateOf(AirGrabDiscoveryService.isServiceRunning) }
    var canDrawOverlay by remember { mutableStateOf(PermissionHelper.canDrawOverlays(context)) }
    var canFullScreen by remember { mutableStateOf(PermissionHelper.canUseFullScreenIntent(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceRunning = AirGrabDiscoveryService.isServiceRunning
                canDrawOverlay = PermissionHelper.canDrawOverlays(context)
                canFullScreen = PermissionHelper.canUseFullScreenIntent(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceElevated,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Ambient Wake & Mesh",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = palette.surfaceSubtle,
                border = BorderStroke(1.dp, palette.borderHairline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Screen-Off Wake Listener",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Wake lock screen automatically when a nearby device grabs",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = isServiceRunning,
                        onCheckedChange = { enable ->
                            isServiceRunning = enable
                            if (enable) AirGrabDiscoveryService.start(context)
                            else AirGrabDiscoveryService.stop(context)
                        }
                    )
                }
            }

            if (!canDrawOverlay || !canFullScreen) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.surfaceSubtle,
                    border = BorderStroke(1.dp, palette.borderHairline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Lock Screen Permissions",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        if (!canDrawOverlay) {
                            OutlinedButton(
                                onClick = { PermissionHelper.openOverlaySettings(context) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Enable \"Appear on Top\"", color = palette.accentSend)
                            }
                        }
                        if (!canFullScreen) {
                            OutlinedButton(
                                onClick = { PermissionHelper.openFullScreenIntentSettings(context) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Allow Full-Screen Wake Alerts", color = palette.accentSend)
                            }
                        }
                    }
                }
            }

            Text(
                text = if (connectedPeers.isEmpty()) {
                    "No nearby devices linked yet"
                } else {
                    "Linked Devices (${connectedPeers.size})"
                },
                style = MaterialTheme.typography.labelLarge,
                color = palette.textSecondary
            )

            connectedPeers.values.forEach { peerName ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(palette.accentReceive)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// RELIABLE MEDIA THUMBNAIL COMPONENT
// ---------------------------------------------------------------------------

@Composable
private fun MediaPreviewBox(
    uri: Uri,
    mimeType: String?,
    contentDescription: String,
    palette: AirGrabPalette,
    modifier: Modifier = Modifier
) {
    val previewState = rememberPreviewBitmap(uri = uri, mimeType = mimeType)
    val isVideo = mimeType?.startsWith("video/", ignoreCase = true) == true

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (previewState) {
            is PreviewBitmapState.Ready -> {
                Image(
                    bitmap = previewState.bitmap,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (isVideo) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.56f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Video",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
            is PreviewBitmapState.Loading -> {
                CircularProgressIndicator(
                    color = palette.accentSend,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
            }
            is PreviewBitmapState.Unavailable -> {
                Icon(
                    imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Image,
                    contentDescription = contentDescription,
                    tint = palette.accentSend,
                    modifier = Modifier.size(52.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// STAGE CARDS
// ---------------------------------------------------------------------------

@Composable
private fun IdleAmbientCanvas(palette: AirGrabPalette) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .widthIn(max = 420.dp)
            .padding(24.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(palette.surfaceGlass)
                .border(1.dp, palette.borderHairline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.NearMe,
                contentDescription = null,
                tint = palette.accentSend,
                modifier = Modifier.size(30.dp)
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "AirGrab",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
            letterSpacing = (-0.3).sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Stage something to grab, or open your palm when another device shares",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StagingLoadingCard(palette: AirGrabPalette) {
    Box(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .background(palette.surfaceCard, RoundedCornerShape(28.dp))
            .border(1.dp, palette.borderHairline, RoundedCornerShape(28.dp))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = palette.accentSend,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Preparing item...",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StagedPayloadCard(
    payload: TransferPayload?,
    isGrabbed: Boolean,
    hasConnectedPeers: Boolean,
    palette: AirGrabPalette
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 190.dp, max = 280.dp)
                .spatial3DGlowCard(
                    phase = if (isGrabbed) SpatialCardPhase.GRABBED else SpatialCardPhase.RESTING,
                    glowColor = palette.accentSend
                )
                .background(palette.surfaceCard, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            when (payload) {
                is TransferPayload.MediaItem -> {
                    MediaPreviewBox(
                        uri = payload.uri,
                        mimeType = payload.mimeType,
                        contentDescription = payload.displayName,
                        palette = palette,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is TransferPayload.FolderArchive -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = null,
                            tint = palette.accentSend,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "${payload.fileCount} files",
                            color = palette.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                is TransferPayload.TextOrLink -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = if (payload.isUrl) Icons.Default.Link else Icons.AutoMirrored.Filled.Notes,
                            contentDescription = null,
                            tint = palette.accentSend,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = payload.content,
                            color = palette.textPrimary,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is TransferPayload.BinaryFile, null -> {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = palette.accentSend,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = payload?.displayName ?: "Staged Item",
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )

        Spacer(modifier = Modifier.height(4.dp))

        val context = LocalContext.current
        val sizeString = if (payload != null && payload.totalBytes > 0) {
            Formatter.formatFileSize(context, payload.totalBytes)
        } else ""

        val statusLine = when {
            isGrabbed && hasConnectedPeers -> "Holding — open palm on peer to drop"
            isGrabbed -> "Holding — waiting for nearby device..."
            sizeString.isNotEmpty() -> "$sizeString • Close fist to grab"
            else -> "Close fist to grab"
        }

        Text(
            text = statusLine,
            color = if (isGrabbed) palette.accentSend else palette.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isGrabbed) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ArmedCatchCard(
    fileName: String,
    senderName: String,
    sizeBytes: Long,
    textPreview: String?,
    palette: AirGrabPalette
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .spatial3DGlowCard(
                phase = SpatialCardPhase.GRABBED,
                glowColor = palette.accentReceive
            )
            .background(palette.surfaceCard, RoundedCornerShape(28.dp))
            .padding(28.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(palette.accentReceive.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PanTool,
                    contentDescription = null,
                    tint = palette.accentReceive,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Incoming Drop",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = palette.accentReceive
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = fileName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            if (!textPreview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = textPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            if (sizeBytes > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = Formatter.formatFileSize(context, sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "From $senderName • Open palm to catch",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TransferProgressCard(
    title: String,
    fileName: String,
    progress: Float,
    accentColor: Color,
    palette: AirGrabPalette,
    onCancel: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (palette.reduceMotion) snap() else AirGrabMotion.LayoutSettle,
        label = "ProgressAnimation"
    )

    Box(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .spatial3DGlowCard(
                phase = SpatialCardPhase.RELEASING,
                glowColor = accentColor
            )
            .background(palette.surfaceCard, RoundedCornerShape(28.dp))
            .padding(28.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(22.dp))

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = accentColor,
                trackColor = palette.surfaceSubtle
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )

                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = palette.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun SentConfirmationCard(
    displayName: String,
    palette: AirGrabPalette,
    onDone: () -> Unit
) {
    Box(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .spatial3DGlowCard(
                phase = SpatialCardPhase.DELIVERED,
                glowColor = palette.accentSend
            )
            .background(palette.surfaceCard, RoundedCornerShape(28.dp))
            .padding(28.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(palette.accentSend.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = palette.accentSend,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Sent",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))

            Button(
                onClick = onDone,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentSend,
                    contentColor = palette.onAccent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("Done", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun DeliveredCard(
    result: PayloadSaver.SaveResult,
    palette: AirGrabPalette,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .spatial3DGlowCard(
                phase = SpatialCardPhase.DELIVERED,
                glowColor = palette.accentReceive
            )
            .background(palette.surfaceCard, RoundedCornerShape(28.dp))
            .animateContentSize(if (palette.reduceMotion) snap() else tween(240))
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (result is PayloadSaver.SaveResult.MediaSaved) {
                val resolvedMime = remember(result.uri) {
                    context.contentResolver.getType(result.uri)
                }
                MediaPreviewBox(
                    uri = result.uri,
                    mimeType = resolvedMime,
                    contentDescription = result.displayName,
                    palette = palette,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.surfaceSubtle)
                )
                Spacer(modifier = Modifier.height(18.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(palette.accentReceive.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = palette.accentReceive,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
            }

            Text(
                text = "Delivered",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            val detailText = when (result) {
                is PayloadSaver.SaveResult.MediaSaved -> "Saved to Pictures/AirGrab • ${result.displayName}"
                is PayloadSaver.SaveResult.FileSaved -> "Saved to Download/AirGrab • ${result.displayName}"
                is PayloadSaver.SaveResult.FolderExtracted ->
                    "Extracted ${result.extractedCount} files to Download/AirGrab/${result.folderName}"
                is PayloadSaver.SaveResult.TextCopied ->
                    if (result.isUrl) result.text else "Copied to Clipboard: ${result.text.take(80)}"
            }

            Text(
                text = detailText,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val primaryActionLabel = when (result) {
                    is PayloadSaver.SaveResult.MediaSaved -> "Open"
                    is PayloadSaver.SaveResult.FileSaved -> "Open"
                    is PayloadSaver.SaveResult.FolderExtracted -> "Downloads"
                    is PayloadSaver.SaveResult.TextCopied -> if (result.isUrl) "Open Link" else null
                }

                if (primaryActionLabel != null) {
                    Button(
                        onClick = { launchDeliveredResult(context, result) },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accentReceive,
                            contentColor = palette.onAccent
                        )
                    ) {
                        Text(primaryActionLabel, fontWeight = FontWeight.SemiBold)
                    }
                }

                OutlinedButton(
                    onClick = onDone,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, palette.borderHairline)
                ) {
                    Text("Done", color = palette.textPrimary)
                }
            }
        }
    }
}

private fun launchDeliveredResult(context: Context, result: PayloadSaver.SaveResult) {
    runCatching {
        when (result) {
            is PayloadSaver.SaveResult.MediaSaved -> {
                val resolvedMime = context.contentResolver.getType(result.uri) ?: "image/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(result.uri, resolvedMime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Open Media"))
            }
            is PayloadSaver.SaveResult.FileSaved -> {
                val resolvedMime = context.contentResolver.getType(result.uri) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(result.uri, resolvedMime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Open File"))
            }
            is PayloadSaver.SaveResult.FolderExtracted -> {
                context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            }
            is PayloadSaver.SaveResult.TextCopied -> {
                if (result.isUrl) {
                    val normalized = if (result.text.startsWith("http://") || result.text.startsWith("https://")) {
                        result.text
                    } else {
                        "https://${result.text}"
                    }
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
                }
            }
        }
    }
}
