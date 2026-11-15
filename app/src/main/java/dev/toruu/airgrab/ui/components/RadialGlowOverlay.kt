package dev.toruu.airgrab.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Composable
fun RadialGlowOverlay(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    glowColor: Color = Color(0xFF80D8FF)
) {
    if (!isActive) return

    val infiniteTransition = rememberInfiniteTransition(label = "RadialGlowTransition")

    val pulseRadiusFraction by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadiusFraction"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Alpha"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val maxDim = maxOf(size.width, size.height)
        val center = Offset(size.width / 2f, size.height / 2f)

        val currentRadius = maxDim * pulseRadiusFraction

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    glowColor.copy(alpha = pulseAlpha),
                    glowColor.copy(alpha = pulseAlpha * 0.5f),
                    Color.Transparent
                ),
                center = center,
                radius = currentRadius
            ),
            radius = currentRadius,
            center = center
        )
    }
}
