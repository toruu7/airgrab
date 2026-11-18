package dev.toruu.airgrab.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.payload.TransferPayload
import dev.toruu.airgrab.data.vision.HandGesture
import dev.toruu.airgrab.domain.ReceiverState
import dev.toruu.airgrab.domain.SenderState
import dev.toruu.airgrab.ui.AirGrabViewModel
import dev.toruu.airgrab.ui.AppMode
import dev.toruu.airgrab.ui.components.RadialGlowOverlay

/**
 * The single AirGrab mode.
 *
 * Every device advertises and discovers at the same time, so there is no sender / receiver
 * switch: a staged payload is sent with a closed fist, and an incoming payload is accepted with
 * an open palm - on the same screen, on either device.
 */
@Composable
fun UnifiedScreen(
    viewModel: AirGrabViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val senderState by viewModel.senderState.collectAsState()
    val receiverState by viewModel.receiverState.collectAsState()
    val gestureResult by viewModel.currentGesture.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    LaunchedEffect(lifecycleOwner) {
        viewModel.bindCamera(lifecycleOwner)
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.stageUri(it) } }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? -> treeUri?.let { viewModel.stageFolderTree(it) } }

    val isReceiving = receiverState is ReceiverState.Receiving
    val isArmed = receiverState is ReceiverState.Armed
    val delivered = receiverState as? ReceiverState.Delivered
    val isGrabbed = senderState is SenderState.Grabbed

    // Haptic confirmation when either gesture lands.
    LaunchedEffect(isGrabbed, isReceiving) {
        if (isGrabbed || isReceiving) {
            runCatching {
                view.performHapticFeedback(
                    if (isReceiving) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.LONG_PRESS
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        RadialGlowOverlay(
            isActive = isReceiving,
            glowColor = MaterialTheme.colorScheme.primary
        )

        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (connectedPeers.isNotEmpty())
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.outline
                                    )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("AirGrab", fontWeight = FontWeight.Bold)
                            if (connectedPeers.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Badge { Text("${connectedPeers.size}") }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setAppMode(AppMode.HOME) }) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (senderState !is SenderState.Idle) {
                            IconButton(onClick = {
                                if (senderState is SenderState.Transferring) {
                                    viewModel.cancelTransfer()
                                } else {
                                    viewModel.clearPayload()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel / start over"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GestureBanner(
                    gesture = gestureResult.gesture,
                    confidence = gestureResult.confidence,
                    hint = when {
                        isArmed -> "Open your palm for 250ms to drop & receive."
                        senderState is SenderState.Staged ->
                            "Hold a closed fist for 300ms to grab and send."
                        connectedPeers.isEmpty() ->
                            "Looking for another device on the same network..."
                        else -> "Pick something to send, or wait for an incoming grab."
                    }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isReceiving -> ReceivingContent(
                            fileName = (receiverState as ReceiverState.Receiving).grabReady.fileName,
                            progress = transferProgress ?: 0f
                        )

                        delivered != null -> DeliveredContent(
                            result = delivered.result,
                            onNextDrop = { viewModel.resetReceiverForNextDrop() }
                        )

                        isArmed -> ArmedContent(
                            state = receiverState as ReceiverState.Armed
                        )

                        else -> SenderContent(
                            state = senderState,
                            statusMessage = statusMessage,
                            connectedPeers = connectedPeers.isEmpty()
                        )
                    }
                }

                BottomActions(
                    senderState = senderState,
                    progress = transferProgress,
                    connectedPeers = connectedPeers.size,
                    onCancel = { viewModel.cancelTransfer() },
                    onChooseDifferent = { viewModel.clearPayload() },
                    onStagePhoto = { filePicker.launch("image/*") },
                    onStageFile = { filePicker.launch("*/*") },
                    onStageFolder = { folderPicker.launch(null) },
                    onStageClipboard = { viewModel.stageClipboard() }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun GestureBanner(
    gesture: HandGesture,
    confidence: Float,
    hint: String
) {
    val highlighted = gesture != HandGesture.NONE
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
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
                    .background(
                        if (highlighted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (gesture) {
                        HandGesture.CLOSED_FIST -> Icons.Default.FrontHand
                        HandGesture.OPEN_PALM -> Icons.Default.PanTool
                        HandGesture.NONE -> Icons.Default.Visibility
                    },
                    contentDescription = null,
                    tint = if (highlighted) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when (gesture) {
                            HandGesture.CLOSED_FIST -> "FIST (grab)"
                            HandGesture.OPEN_PALM -> "OPEN PALM (drop)"
                            HandGesture.NONE -> "Waiting for hand..."
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    val confPercent = (confidence * 100).toInt()
                    if (confPercent > 0) {
                        Badge { Text("$confPercent%") }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SenderContent(
    state: SenderState,
    statusMessage: String?,
    connectedPeers: Boolean
) {
    when (state) {
        is SenderState.Idle -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.SwapVert,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Ready to send or receive",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Share a file from any app, or pick one below.\nThe other phone just shows a palm to accept.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is SenderState.Staged,
        is SenderState.Grabbed,
        is SenderState.Transferring -> {
            val payload = when (state) {
                is SenderState.Staged -> state.payload
                is SenderState.Grabbed -> state.payload
                is SenderState.Transferring -> state.payload
                else -> null
            }

            val grabbed = state is SenderState.Grabbed
            val animatedScale by animateFloatAsState(
                targetValue = if (grabbed) 0.94f else 1f,
                label = "GrabScale"
            )
            val animatedAlpha by animateFloatAsState(
                targetValue = if (grabbed) 0.75f else 1f,
                label = "GrabAlpha"
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        alpha = animatedAlpha
                    }
            ) {
                PayloadPreview(payload)

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = payload?.displayName ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (payload != null && payload.totalBytes > 0) {
                    val context = LocalContext.current
                    Text(
                        text = Formatter.formatFileSize(context, payload.totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (grabbed) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "GRAB ACTIVE",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = statusMessage
                                    ?: if (connectedPeers) {
                                        "Broadcasting. The other device opens a palm to receive."
                                    } else {
                                        "No other device connected yet - waiting..."
                                    },
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        is SenderState.Sent -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Transfer Successful!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PayloadPreview(payload: TransferPayload?) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (payload) {
                is TransferPayload.MediaItem -> AsyncImage(
                    model = payload.uri,
                    contentDescription = payload.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                is TransferPayload.FolderArchive -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderZip,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${payload.fileCount} files zipped",
                        fontWeight = FontWeight.Bold
                    )
                }

                is TransferPayload.TextOrLink -> Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (payload.isUrl) Icons.Default.Link else Icons.Default.Notes,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = payload.content,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                is TransferPayload.BinaryFile, null -> Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ArmedContent(state: ReceiverState.Armed) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                Text("INCOMING PAYLOAD")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Icon(
                imageVector = Icons.Default.PanTool,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = state.grabReady.fileName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (state.grabReady.sizeBytes > 0) {
                Text(
                    text = Formatter.formatFileSize(
                        LocalContext.current,
                        state.grabReady.sizeBytes
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "From: ${state.grabReady.senderName}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = "OPEN YOUR PALM TO RECEIVE",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ReceivingContent(fileName: String, progress: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(90.dp),
            strokeWidth = 8.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Receiving...",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DeliveredContent(
    result: PayloadSaver.SaveResult,
    onNextDrop: () -> Unit
) {
    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Received!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            when (result) {
                is PayloadSaver.SaveResult.MediaSaved -> {
                    Text(
                        text = "Saved to Pictures/AirGrab",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        val mime = context.contentResolver.getType(result.uri) ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(result.uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open media"))
                    }) {
                        Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open in Gallery")
                    }
                }

                is PayloadSaver.SaveResult.FolderExtracted -> {
                    Text(
                        text = "Extracted ${result.extractedCount} files into Downloads/AirGrab/${result.folderName}",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }

                is PayloadSaver.SaveResult.FileSaved -> {
                    Text(
                        text = "Saved to Downloads/AirGrab/${result.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        val mime = context.contentResolver.getType(result.uri) ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(result.uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open file"))
                    }) {
                        Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open File")
                    }
                }

                is PayloadSaver.SaveResult.TextCopied -> {
                    Text(
                        text = "Copied to clipboard",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (result.isUrl) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.text)))
                        }) {
                            Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Web Link")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(onClick = onNextDrop, modifier = Modifier.fillMaxWidth()) {
                Text("Ready for Next Drop")
            }
        }
    }
}

@Composable
private fun BottomActions(
    senderState: SenderState,
    progress: Float?,
    connectedPeers: Int,
    onCancel: () -> Unit,
    onChooseDifferent: () -> Unit,
    onStagePhoto: () -> Unit,
    onStageFile: () -> Unit,
    onStageFolder: () -> Unit,
    onStageClipboard: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            senderState is SenderState.Transferring -> {
                val animatedProgress by animateFloatAsState(
                    targetValue = (progress ?: senderState.progress).coerceIn(0f, 1f),
                    label = "TransferProgress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sending: ${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel Transfer")
                }
            }

            senderState is SenderState.Sent -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("Send Another")
                    }
                }
            }

            senderState is SenderState.Idle -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onStagePhoto,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Photo")
                        }
                        FilledTonalButton(
                            onClick = onStageFile,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("File")
                        }
                        FilledTonalButton(
                            onClick = onStageFolder,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderZip,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Folder")
                        }
                    }

                    OutlinedButton(onClick = onStageClipboard, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Paste Text / Link from Clipboard")
                    }

                    if (connectedPeers == 0) {
                        Text(
                            text = "No other device connected yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            senderState is SenderState.Staged ||
            senderState is SenderState.Grabbed -> {
                Text(
                    text = "Hold a closed fist to send, or choose something else.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onChooseDifferent, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose a Different Payload")
                }
            }
        }
    }
}
