package dev.toruu.airgrab.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * High-performance, zero-recomposition Spatial Aura edge vignette.
 * Evaluates [intensity] and [color] strictly during the draw phase to eliminate
 * Compose layout/composition overhead on 60/120fps high-refresh displays.
 */
fun Modifier.spatialAura(
    color: Color,
    intensity: () -> Float,
    bezelDepth: Dp = 56.dp
): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()

        val alpha = intensity().coerceIn(0f, 1f)
        if (alpha > 0.01f) {
            val auraColor = color.copy(alpha = alpha * 0.42f)
            val auraMid = color.copy(alpha = alpha * 0.15f)
            val transparent = color.copy(alpha = 0f)
            val depthPx = bezelDepth.toPx()

            // Top Bezel Aura
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(auraColor, auraMid, transparent),
                    startY = 0f,
                    endY = depthPx
                )
            )

            // Bottom Bezel Aura
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(transparent, auraMid, auraColor),
                    startY = size.height - depthPx,
                    endY = size.height
                )
            )

            // Left Bezel Aura
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(auraColor, auraMid, transparent),
                    startX = 0f,
                    endX = depthPx
                )
            )

            // Right Bezel Aura
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(transparent, auraMid, auraColor),
                    startX = size.width - depthPx,
                    endX = size.width
                )
            )
        }
    }
)
