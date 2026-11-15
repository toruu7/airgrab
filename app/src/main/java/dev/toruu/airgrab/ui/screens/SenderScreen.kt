package dev.toruu.airgrab.ui.screens

import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import dev.toruu.airgrab.data.payload.TransferPayload
import dev.toruu.airgrab.data.vision.HandGesture
import dev.toruu.airgrab.domain.SenderState
import dev.toruu.airgrab.ui.AirGrabViewModel
import dev.toruu.airgrab.ui.AppMode

@Composable
fun SenderScreen(
    viewModel: AirGrabViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val senderState by viewModel.senderState.collectAsState()
    val gestureResult by viewModel.currentGesture.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()

    LaunchedEffect(lifecycleOwner) {
        viewModel.bindCamera(lifecycleOwner)
    }

    val isGrabbed = senderState is SenderState.Grabbed
    val animatedScale by animateFloatAsState(
        targetValue = if (isGrabbed) 0.94f else 1f,
        label = "GrabScale"
    )
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isGrabbed) 0.75f else 1f,
        label = "GrabAlpha"
    )

    // Trigger haptic tick on grab
    LaunchedEffect(isGrabbed) {
        if (isGrabbed) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } catch (e: Exception) {
                // Ignore if device haptics fail
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sender Mode", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.setAppMode(AppMode.HOME) }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.cancelTransfer() }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel/Reset")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Touchless Gesture Status Banner (Headless Camera - No Camera Feed)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (gestureResult.gesture == HandGesture.CLOSED_FIST)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
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
                                if (gestureResult.gesture == HandGesture.CLOSED_FIST)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surface
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (gestureResult.gesture) {
                                HandGesture.CLOSED_FIST -> Icons.Default.FrontHand
                                HandGesture.OPEN_PALM -> Icons.Default.PanTool
                                HandGesture.NONE -> Icons.Default.Visibility
                            },
                            contentDescription = null,
                            tint = if (gestureResult.gesture == HandGesture.CLOSED_FIST)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.primary,
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
                                text = when (gestureResult.gesture) {
                                    HandGesture.CLOSED_FIST -> "FIST DETECTED (Grab)"
                                    HandGesture.OPEN_PALM -> "OPEN PALM"
                                    HandGesture.NONE -> "Waiting for Hand..."
                                },
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            val confPercent = (gestureResult.confidence * 100).toInt()
                            if (confPercent > 0) {
                                Badge { Text("$confPercent%") }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Hold closed fist towards phone for 300ms to grab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Main Payload Display with Blur-on-Grab Effect
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                when (val state = senderState) {
                    is SenderState.Idle -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No payload staged",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Share via Samsung Gallery / Share Sheet, or pick a file on Home screen.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
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
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
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
                                                    modifier = Modifier.size(64.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = "${payload.fileCount} Files Zipped",
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        is TransferPayload.TextOrLink -> {
                                            Column(
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
                                        }
                                        is TransferPayload.BinaryFile, null -> {
                                            Icon(
                                                imageVector = Icons.Default.InsertDriveFile,
                                                contentDescription = null,
                                                modifier = Modifier.size(64.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = payload?.displayName ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (payload != null && payload.totalBytes > 0) {
                                Text(
                                    text = Formatter.formatFileSize(context, payload.totalBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Grabbed Overlay Notice
                        if (isGrabbed) {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.padding(horizontal = 24.dp)
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
                                        text = "GRAB ACTIVE!",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "Broadcasting to room. Receiver: open palm to release.",
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    is SenderState.Sent -> {
                        Column(
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Transfer Progress Bar & Action Bottom Area
            if (senderState is SenderState.Transferring) {
                val progress = transferProgress ?: 0f
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val animatedProgress by animateFloatAsState(targetValue = progress, label = "Progress")
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Transferring: ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.cancelTransfer() }) {
                        Text("Cancel Transfer")
                    }
                }
            }
        }
    }
}
