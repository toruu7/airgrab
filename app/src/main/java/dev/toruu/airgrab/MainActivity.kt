package dev.toruu.airgrab

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.toruu.airgrab.ui.AirGrabViewModel
import dev.toruu.airgrab.ui.AppMode
import dev.toruu.airgrab.ui.components.PermissionGate
import dev.toruu.airgrab.ui.screens.AirGrabSurface

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_RECEIVER = "dev.toruu.airgrab.ACTION_RECEIVER_MODE"
    }

    private val viewModel: AirGrabViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        handleIntent(intent)
        viewModel.setAppMode(AppMode.ACTIVE)

        setContent {
            AirGrabTheme {
                PermissionGate {
                    AirGrabMainNavigation(viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegroundChanged(true)
    }

    override fun onStop() {
        viewModel.onAppForegroundChanged(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            ACTION_OPEN_RECEIVER -> {
                viewModel.setAppMode(AppMode.ACTIVE)
            }
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE -> {
                viewModel.handleIncomingIntent(intent)
            }
        }
    }
}

@Composable
fun AirGrabMainNavigation(viewModel: AirGrabViewModel) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF050608)
    ) {
        AirGrabSurface(viewModel = viewModel)
    }
}

@Composable
fun AirGrabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFF00F0FF),
        onPrimary = Color(0xFF050608),
        primaryContainer = Color(0x1AFFFFFF),
        onPrimaryContainer = Color(0xFFF5F5F7),
        secondary = Color(0xFF00FFA3),
        onSecondary = Color(0xFF050608),
        background = Color(0xFF050608),
        onBackground = Color(0xFFF5F5F7),
        surface = Color(0xFF0B0C10),
        onSurface = Color(0xFFF5F5F7),
        surfaceVariant = Color(0x14FFFFFF),
        onSurfaceVariant = Color(0xFF8E8E93)
    )

    MaterialTheme(
        colorScheme = darkColorScheme,
        typography = Typography(),
        content = content
    )
}
