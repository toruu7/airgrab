package dev.toruu.airgrab.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.payload.TransferPayload
import dev.toruu.airgrab.data.vision.HandGesture
import dev.toruu.airgrab.domain.ReceiverState
import dev.toruu.airgrab.domain.SenderState
import dev.toruu.airgrab.ui.AirGrabViewModel
import dev.toruu.airgrab.ui.components.spatialAura
import dev.toruu.airgrab.ui.theme.AirGrabMotion

private val VoidBackground = Color(0xFF050608)
private val GlassSurface = Color(0x0EFFFFFF)
private val GlassBorder = Color(0x22FFFFFF)
private val ElectricCyan = Color(0xFF00F0FF)
private val AuroraEmerald = Color(0xFF00FFA3)
private val TextPrimary = Color(0xFFF5F5F7)
private val TextSecondary = Color(0xFF8E8E93)

@Composable
fun AirGrabSurface(
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

    LaunchedEffect(lifecycleOwner) {
        viewModel.bindCamera(lifecycleOwner)
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.stageUri(it) } }

    val isArmed = receiverState is ReceiverState.Armed
    val isReceiving = receiverState is ReceiverState.Receiving
    val delivered = receiverState as? ReceiverState.Delivered
    val isGrabbed = senderState is SenderState.Grabbed
    val isTransferring = senderState is SenderState.Transferring

    // Tactile Haptics on gesture confirmation
    LaunchedEffect(isGrabbed) {
        if (isGrabbed) {
            runCatching {
                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            }
        }
    }

    LaunchedEffect(isReceiving) {
        if (isReceiving) {
            runCatching {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        }
    }

    // Determine spatial aura color based on whether sending or receiving
    val auraColor = if (isArmed || isReceiving) AuroraEmerald else ElectricCyan

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VoidBackground)
            .spatialAura(
                color = auraColor,
                intensity = {
                    if (gestureResult.confidence > 0.15f) gestureResult.confidence else 0f
                }
            )
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        // --- Top Hardware / Mesh Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Ambient Network Status Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(GlassSurface)
                    .border(1.dp, GlassBorder, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (connectedPeers.isNotEmpty()) AuroraEmerald else ElectricCyan)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (connectedPeers.isNotEmpty()) {
                        "Linked: ${connectedPeers.values.first()}"
                    } else {
                        "AirGrab Ambient"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }

            // Dismiss / Cancel Staged Payload Action
            if (senderState !is SenderState.Idle) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(GlassSurface)
                        .border(1.dp, GlassBorder, CircleShape)
                        .clickable {
                            if (isTransferring) viewModel.cancelTransfer()
                            else viewModel.clearPayload()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // --- Center Canvas Content ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                // 1. Receiving File Transfer
                isReceiving -> {
                    val incomingState = receiverState as ReceiverState.Receiving
                    TransferProgressCard(
                        title = "Receiving File",
                        fileName = incomingState.grabReady.fileName,
                        progress = transferProgress ?: 0f,
                        accentColor = AuroraEmerald,
                        onCancel = { viewModel.cancelTransfer() }
                    )
                }

                // 2. File Delivered
                delivered != null -> {
                    DeliveredCard(
                        result = delivered.result,
                        onDone = { viewModel.resetReceiverForNextDrop() }
                    )
                }

                // 3. Incoming Drop Detected (Armed)
                isArmed -> {
                    val armedState = receiverState as ReceiverState.Armed
                    ArmedCatchCard(
                        fileName = armedState.grabReady.fileName,
                        senderName = armedState.grabReady.senderName,
                        sizeBytes = armedState.grabReady.sizeBytes
                    )
                }

                // 4. Sender Transferring
                isTransferring -> {
                    val transferringState = senderState as SenderState.Transferring
                    TransferProgressCard(
                        title = "Projecting File",
                        fileName = transferringState.payload.displayName,
                        progress = transferProgress ?: transferringState.progress,
                        accentColor = ElectricCyan,
                        onCancel = { viewModel.cancelTransfer() }
                    )
                }

                // 5. File Staged (Ready to Grab)
                senderState is SenderState.Staged || isGrabbed -> {
                    val payload = (senderState as? SenderState.Staged)?.payload
                        ?: (senderState as? SenderState.Grabbed)?.payload

                    StagedPayloadCard(
                        payload = payload,
                        isGrabbed = isGrabbed
                    )
                }

                // 6. Idle Ambient State (Empty Canvas)
                else -> {
                    IdleAmbientCanvas()
                }
            }
        }

        // --- Floating Bottom Actions ---
        AnimatedVisibility(
            visible = senderState is SenderState.Idle && !isArmed && !isReceiving && delivered == null,
            enter = fadeIn(AirGrabMotion.LayoutSettle),
            exit = fadeOut(AirGrabMotion.LayoutSettle),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Primary Floating Action: Stage File
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(GlassSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(32.dp))
                        .clickable { filePicker.launch("*/*") }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Stage File",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                }

                // Quick Paste Clipboard
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(GlassSurface)
                        .border(1.dp, GlassBorder, CircleShape)
                        .clickable { viewModel.stageClipboard() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Paste Clipboard",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SUB-COMPONENTS
// ---------------------------------------------------------------------------

@Composable
private fun IdleAmbientCanvas() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(GlassSurface)
                .border(1.dp, GlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.NearMe,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "AirGrab",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Hold fist to grab • Open palm to catch",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StagedPayloadCard(
    payload: TransferPayload?,
    isGrabbed: Boolean
) {
    val animatedScale by animateFloatAsState(
        targetValue = if (isGrabbed) 0.84f else 1.0f,
        animationSpec = if (isGrabbed) AirGrabMotion.GrabCompression else AirGrabMotion.GestureInterruption,
        label = "GrabCompression"
    )

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isGrabbed) 0.92f else 1.0f,
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
        // Elevated Glass Card
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height(300.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(GlassSurface)
                .border(
                    width = if (isGrabbed) 2.dp else 1.dp,
                    color = if (isGrabbed) ElectricCyan else GlassBorder,
                    shape = RoundedCornerShape(28.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            when (payload) {
                is TransferPayload.MediaItem -> {
                    AsyncImage(
                        model = payload.uri,
                        contentDescription = payload.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is TransferPayload.FolderArchive -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "${payload.fileCount} Files Zipped",
                            color = TextPrimary,
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
                            imageVector = if (payload.isUrl) Icons.Default.Link else Icons.Default.Notes,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = payload.content,
                            color = TextPrimary,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is TransferPayload.BinaryFile, null -> {
                    Icon(
                        imageVector = Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Title & Monospace Metadata
        Text(
            text = payload?.displayName ?: "Staged Payload",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(4.dp))

        val context = LocalContext.current
        val sizeString = if (payload != null && payload.totalBytes > 0) {
            Formatter.formatFileSize(context, payload.totalBytes)
        } else ""

        Text(
            text = if (isGrabbed) {
                "Holding • Move near peer to release"
            } else if (sizeString.isNotEmpty()) {
                sizeString
            } else {
                "Ready to Grab"
            },
            color = if (isGrabbed) ElectricCyan else TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isGrabbed) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = if (!isGrabbed && sizeString.isNotEmpty()) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun ArmedCatchCard(
    fileName: String,
    senderName: String,
    sizeBytes: Long
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(28.dp))
            .background(GlassSurface)
            .border(1.5.dp, AuroraEmerald, RoundedCornerShape(28.dp))
            .padding(28.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(AuroraEmerald.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PanTool,
                contentDescription = null,
                tint = AuroraEmerald,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Incoming Drop",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = AuroraEmerald
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = fileName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val context = LocalContext.current
        if (sizeBytes > 0) {
            Text(
                text = Formatter.formatFileSize(context, sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "From: $senderName • Open palm to catch",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun TransferProgressCard(
    title: String,
    fileName: String,
    progress: Float,
    accentColor: Color,
    onCancel: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(28.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(28.dp))
            .padding(28.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = fileName,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Slim Minimalist Progress Bar
        val animatedProgress by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            label = "ProgressAnimation"
        )

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = accentColor,
            trackColor = Color(0x1AFFFFFF)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = accentColor,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Cancel",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onCancel() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DeliveredCard(
    result: PayloadSaver.SaveResult,
    onDone: () -> Unit
) {
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(28.dp))
            .background(GlassSurface)
            .border(1.5.dp, AuroraEmerald, RoundedCornerShape(28.dp))
            .padding(28.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(AuroraEmerald.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = AuroraEmerald,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Delivered",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        val detailText = when (result) {
            is PayloadSaver.SaveResult.MediaSaved -> "Saved to Gallery • ${result.displayName}"
            is PayloadSaver.SaveResult.FileSaved -> "Saved to Downloads • ${result.displayName}"
            is PayloadSaver.SaveResult.FolderExtracted -> "Extracted ${result.extractedCount} files"
            is PayloadSaver.SaveResult.TextCopied -> "Copied to Clipboard"
        }

        Text(
            text = detailText,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (result is PayloadSaver.SaveResult.MediaSaved) {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(result.uri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open in Gallery"))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AuroraEmerald)
                ) {
                    Text("Open", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.weight(1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
            ) {
                Text("Done", color = TextPrimary)
            }
        }
    }
}
