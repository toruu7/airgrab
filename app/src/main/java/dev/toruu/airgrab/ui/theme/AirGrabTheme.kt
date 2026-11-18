package dev.toruu.airgrab.ui.theme

import android.app.Activity
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Immutable
data class AirGrabPalette(
    val isDark: Boolean,
    val reduceMotion: Boolean,
    val canvas: Color,
    val surfaceGlass: Color,
    val surfaceElevated: Color,
    val surfaceSubtle: Color,
    val borderHairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentSend: Color,
    val accentSendAura: Color,
    val accentReceive: Color,
    val accentReceiveAura: Color,
    val onAccent: Color,
    val accentError: Color
)

private val DarkPalette = AirGrabPalette(
    isDark = true,
    reduceMotion = false,
    canvas = Color(0xFF050608),
    surfaceGlass = Color(0x14FFFFFF),
    surfaceElevated = Color(0xFF101217),
    surfaceSubtle = Color(0x1FFFFFFF),
    borderHairline = Color(0x28FFFFFF),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFA1A1AA),
    accentSend = Color(0xFF00E5FF),
    accentSendAura = Color(0xFF00F0FF),
    accentReceive = Color(0xFF00FFA3),
    accentReceiveAura = Color(0xFF00FFA3),
    onAccent = Color(0xFF050608),
    accentError = Color(0xFFFF6B6B)
)

private val LightPalette = AirGrabPalette(
    isDark = false,
    reduceMotion = false,
    canvas = Color(0xFFF4F6FA),
    surfaceGlass = Color(0xE6FFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceSubtle = Color(0x0D0B1220),
    borderHairline = Color(0x1C0B1220),
    textPrimary = Color(0xFF0D1117),
    textSecondary = Color(0xFF4B5565),
    accentSend = Color(0xFF0077B6),
    accentSendAura = Color(0xFF00A8E8),
    accentReceive = Color(0xFF047857),
    accentReceiveAura = Color(0xFF10B981),
    onAccent = Color(0xFFFFFFFF),
    accentError = Color(0xFFC81E1E)
)

val LocalAirGrabPalette = staticCompositionLocalOf { DarkPalette }

@Composable
fun AirGrabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }

    val palette = remember(darkTheme, reduceMotion) {
        (if (darkTheme) DarkPalette else LightPalette).copy(reduceMotion = reduceMotion)
    }

    val colorScheme = remember(darkTheme, palette) {
        if (darkTheme) {
            darkColorScheme(
                primary = palette.accentSend,
                onPrimary = palette.onAccent,
                primaryContainer = Color(0xFF0F2630),
                onPrimaryContainer = palette.textPrimary,
                secondary = palette.accentReceive,
                onSecondary = palette.onAccent,
                background = palette.canvas,
                onBackground = palette.textPrimary,
                surface = palette.surfaceElevated,
                onSurface = palette.textPrimary,
                surfaceVariant = palette.surfaceGlass,
                onSurfaceVariant = palette.textSecondary,
                outline = palette.borderHairline,
                error = palette.accentError
            )
        } else {
            lightColorScheme(
                primary = palette.accentSend,
                onPrimary = palette.onAccent,
                primaryContainer = Color(0xFFD9F0FF),
                onPrimaryContainer = palette.textPrimary,
                secondary = palette.accentReceive,
                onSecondary = palette.onAccent,
                background = palette.canvas,
                onBackground = palette.textPrimary,
                surface = palette.surfaceElevated,
                onSurface = palette.textPrimary,
                surfaceVariant = palette.surfaceGlass,
                onSurfaceVariant = palette.textSecondary,
                outline = palette.borderHairline,
                error = palette.accentError
            )
        }
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAirGrabPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content
        )
    }
}
