package dev.toruu.airgrab.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.toruu.airgrab.data.payload.PayloadSaver
import dev.toruu.airgrab.data.vision.HandGesture
import dev.toruu.airgrab.domain.ReceiverState
import dev.toruu.airgrab.ui.AirGrabViewModel
import dev.toruu.airgrab.ui.AppMode
import dev.toruu.airgrab.ui.components.RadialGlowOverlay

@Composable
fun ReceiverScreen(
    viewModel: AirGrabViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val receiverState by viewModel.receiverState.collectAsState()
    val gestureResult by viewModel.currentGesture.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()

    LaunchedEffect(lifecycleOwner) {
        viewModel.bindCamera(lifecycleOwner)
    }

    val isReceiving = receiverState is ReceiverState.Receiving

    // Trigger haptic feedback on drop trigger
    LaunchedEffect(isReceiving) {
        if (isReceiving) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Fullscreen Radial Glow Overlay during active drop
        RadialGlowOverlay(
            isActive = isReceiving,
            glowColor = MaterialTheme.colorScheme.primary
        )

        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Receiver Mode", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setAppMode(AppMode.HOME) }) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
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
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Touchless Gesture State Card (Headless Camera - No Camera Feed)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (gestureResult.gesture == HandGesture.OPEN_PALM)
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
                                    if (gestureResult.gesture == HandGesture.OPEN_PALM)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surface
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (gestureResult.gesture) {
                                    HandGesture.OPEN_PALM -> Icons.Default.PanTool
                                    HandGesture.CLOSED_FIST -> Icons.Default.FrontHand
                                    HandGesture.NONE -> Icons.Default.Visibility
                                },
                                contentDescription = null,
                                tint = if (gestureResult.gesture == HandGesture.OPEN_PALM)
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
                                        HandGesture.OPEN_PALM -> "OPEN PALM DETECTED (Drop)"
                                        HandGesture.CLOSED_FIST -> "FIST"
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
                                text = "Show Open Palm towards phone for 250ms to drop & accept payload.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Center Dynamic Content by State
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when (val state = receiverState) {
                        is ReceiverState.Listening -> {
                            val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
                            val pulseScale by infiniteTransition.animateFloat(
                                initialValue = 0.9f,
                                targetValue = 1.15f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1500, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "PulseScale"
                            )

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(110.dp)
                                        .scale(pulseScale)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sensors,
                                        contentDescription = null,
                                        modifier = Modifier.size(54.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                Text(
                                    text = "Ready to Receive",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "Waiting for sender to grab a payload...\nKeep app open or active.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        is ReceiverState.Armed -> {
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
                                        Text("INCOMING PAYLOAD DETECTED")
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
                                            text = Formatter.formatFileSize(context, state.grabReady.sizeBytes),
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
                                            text = "✋ OPEN YOUR PALM TO DROP & RECEIVE",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                        }

                        is ReceiverState.Receiving -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = { (transferProgress ?: 0f).coerceIn(0f, 1f) },
                                    modifier = Modifier.size(90.dp),
                                    strokeWidth = 8.dp
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Text(
                                    text = "Receiving File...",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = state.grabReady.fileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "${((transferProgress ?: 0f) * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        is ReceiverState.Delivered -> {
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
                                        text = "Payload Delivered!",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    when (val res = state.result) {
                                        is PayloadSaver.SaveResult.MediaSaved -> {
                                            Text(
                                                text = "Saved to Samsung Gallery (Pictures/AirGrab)",
                                                style = MaterialTheme.typography.bodyMedium,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(20.dp))
                                            Button(onClick = {
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(res.uri, "image/*")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Open Media"))
                                            }) {
                                                Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Open in Gallery")
                                            }
                                        }

                                        is PayloadSaver.SaveResult.FolderExtracted -> {
                                            Text(
                                                text = "Extracted ${res.extractedCount} files into Downloads/AirGrab/${res.folder.name}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                textAlign = TextAlign.Center
                                            )
                                        }

                                        is PayloadSaver.SaveResult.FileSaved -> {
                                            Text(
                                                text = "Saved to Downloads/AirGrab/${res.displayName}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                textAlign = TextAlign.Center
                                            )
                                        }

                                        is PayloadSaver.SaveResult.TextCopied -> {
                                            Text(
                                                text = "Copied text content to clipboard!",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            if (res.isUrl) {
                                                Spacer(modifier = Modifier.height(16.dp))
                                                Button(onClick = {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(res.text))
                                                    context.startActivity(intent)
                                                }) {
                                                    Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null)
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("Open Web Link")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Done / Reset Action
                AnimatedVisibility(visible = receiverState is ReceiverState.Delivered) {
                    Button(
                        onClick = { viewModel.setAppMode(AppMode.RECEIVER) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ready for Next Drop")
                    }
                }
            }
        }
    }
}
