package dev.toruu.airgrab

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.toruu.airgrab.ui.AirGrabViewModel
import dev.toruu.airgrab.ui.AppMode
import dev.toruu.airgrab.ui.components.PermissionGate
import dev.toruu.airgrab.ui.screens.AirGrabSurface
import dev.toruu.airgrab.ui.theme.AirGrabTheme
import dev.toruu.airgrab.ui.theme.LocalAirGrabPalette

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_RECEIVER = "dev.toruu.airgrab.ACTION_RECEIVER_MODE"
    }

    private val viewModel: AirGrabViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )

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
    val palette = LocalAirGrabPalette.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = palette.canvas
    ) {
        AirGrabSurface(viewModel = viewModel)
    }
}
