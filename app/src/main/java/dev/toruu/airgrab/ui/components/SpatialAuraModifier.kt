package dev.toruu.airgrab.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.toruu.airgrab.ui.theme.LocalAirGrabPalette

/**
 * Smooth, corner-seam-free peripheral atmospheric aura anchored to the top front camera sensor
 * and bottom horizon. Interpolates 12fps gesture confidence to 60/120fps fluid light transitions.
 */
fun Modifier.spatialAura(
    color: Color,
    intensity: Float,
    maxAlpha: Float = 0.28f
): Modifier = composed {
    val palette = LocalAirGrabPalette.current
    val smoothIntensity by animateFloatAsState(
        targetValue = intensity.coerceIn(0f, 1f),
        animationSpec = if (palette.reduceMotion) snap() else tween(260, easing = FastOutSlowInEasing),
        label = "SpatialAuraIntensity"
    )
    val smoothColor by animateColorAsState(
        targetValue = color,
        animationSpec = if (palette.reduceMotion) snap() else tween(320, easing = FastOutSlowInEasing),
        label = "SpatialAuraColor"
    )

    this.drawWithContent {
        drawContent()

        val strength = smoothIntensity
        if (strength > 0.01f) {
            val peakAlpha = strength * maxAlpha
            val topCenter = Offset(size.width * 0.5f, -size.height * 0.05f)
            val topRadius = size.width * 0.92f

            // Seamless atmospheric wash anchored around the top front camera sensor
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to smoothColor.copy(alpha = peakAlpha),
                    0.28f to smoothColor.copy(alpha = peakAlpha * 0.48f),
                    0.56f to smoothColor.copy(alpha = peakAlpha * 0.16f),
                    0.82f to smoothColor.copy(alpha = peakAlpha * 0.03f),
                    1.0f to Color.Transparent,
                    center = topCenter,
                    radius = topRadius
                ),
                center = topCenter,
                radius = topRadius
            )

            // Soft horizon counterweight near bottom gesture bar
            val bottomCenter = Offset(size.width * 0.5f, size.height * 1.08f)
            val bottomRadius = size.width * 0.78f
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to smoothColor.copy(alpha = peakAlpha * 0.55f),
                    0.35f to smoothColor.copy(alpha = peakAlpha * 0.20f),
                    0.75f to smoothColor.copy(alpha = peakAlpha * 0.04f),
                    1.0f to Color.Transparent,
                    center = bottomCenter,
                    radius = bottomRadius
                ),
                center = bottomCenter,
                radius = bottomRadius
            )
        }
    }
}
