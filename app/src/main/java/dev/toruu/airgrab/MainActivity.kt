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
import dev.toruu.airgrab.ui.screens.HomeScreen
import dev.toruu.airgrab.ui.screens.ReceiverScreen
import dev.toruu.airgrab.ui.screens.SenderScreen

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

        setContent {
            AirGrabTheme {
                PermissionGate {
                    AirGrabMainNavigation(viewModel)
                }
            }
        }
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
                viewModel.setAppMode(AppMode.RECEIVER)
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
    val mode by viewModel.appMode.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (mode) {
            AppMode.HOME -> HomeScreen(viewModel = viewModel)
            AppMode.SENDER -> SenderScreen(viewModel = viewModel)
            AppMode.RECEIVER -> ReceiverScreen(viewModel = viewModel)
        }
    }
}

@Composable
fun AirGrabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = Color(0xFFEADDFF),
        secondary = Color(0xFFCCC2DC),
        onSecondary = Color(0xFF332D41),
        background = Color(0xFF141218),
        onBackground = Color(0xFFE6E0E9),
        surface = Color(0xFF1D1B20),
        onSurface = Color(0xFFE6E0E9),
        surfaceVariant = Color(0xFF2B2930),
        onSurfaceVariant = Color(0xFFCAC4D0)
    )

    MaterialTheme(
        colorScheme = darkColorScheme,
        typography = Typography(),
        content = content
    )
}
