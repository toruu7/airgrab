package dev.toruu.airgrab.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.toruu.airgrab.ui.theme.AirGrabMotion
import dev.toruu.airgrab.ui.theme.LocalAirGrabPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class SpatialCardPhase {
    RESTING,
    GRABBED,
    RELEASING,
    DELIVERED
}

/**
 * Applies true GPU perspective 3D depth, floating shadow bloom, and an animated
 * specular rim highlight for Grabbed and Released states.
 *
 * Avoids [RenderEffect] blur modifiers so it stays crash-free across all OEM GPU drivers
 * while delivering responsive physical depth in both Light and Dark modes.
 */
fun Modifier.spatial3DGlowCard(
    phase: SpatialCardPhase,
    glowColor: Color,
    cornerRadius: Dp = 28.dp,
    shape: Shape = RoundedCornerShape(cornerRadius)
): Modifier = composed {
    val palette = LocalAirGrabPalette.current
    val reduceMotion = palette.reduceMotion

    val targetScale = when (phase) {
        SpatialCardPhase.RESTING -> 1.0f
        SpatialCardPhase.GRABBED -> 0.85f
        SpatialCardPhase.RELEASING -> 1.04f
        SpatialCardPhase.DELIVERED -> 1.0f
    }
    val targetRotationX = when (phase) {
        SpatialCardPhase.RESTING -> 0f
        SpatialCardPhase.GRABBED -> 11f
        SpatialCardPhase.RELEASING -> -10f
        SpatialCardPhase.DELIVERED -> 0f
    }
    val targetLiftDp = when (phase) {
        SpatialCardPhase.RESTING -> 0f
        SpatialCardPhase.GRABBED -> -10f
        SpatialCardPhase.RELEASING -> -18f
        SpatialCardPhase.DELIVERED -> 0f
    }
    val targetGlow = when (phase) {
        SpatialCardPhase.RESTING -> 0.08f
        SpatialCardPhase.GRABBED -> 0.95f
        SpatialCardPhase.RELEASING -> 1.0f
        SpatialCardPhase.DELIVERED -> 0.55f
    }

    val motionSpec = when {
        reduceMotion -> snap<Float>()
        phase == SpatialCardPhase.GRABBED -> AirGrabMotion.GrabCompression
        phase == SpatialCardPhase.RELEASING -> AirGrabMotion.DropUnpack
        else -> AirGrabMotion.GestureInterruption
    }

    val scale by animateFloatAsState(targetScale, animationSpec = motionSpec, label = "3dScale")
    val rotX by animateFloatAsState(targetRotationX, animationSpec = motionSpec, label = "3dRotX")
    val liftDp by animateFloatAsState(targetLiftDp, animationSpec = motionSpec, label = "3dLift")
    val glowStrength by animateFloatAsState(
        targetGlow,
        animationSpec = if (reduceMotion) snap() else tween(260, easing = FastOutSlowInEasing),
        label = "3dGlow"
    )

    val isActivePhase = phase == SpatialCardPhase.GRABBED || phase == SpatialCardPhase.RELEASING

    // Subtle 3D float/tilt oscillation + rotating specular light sweep while held or releasing.
    val orbitAngle: Float
    val tiltY: Float
    if (isActivePhase && !reduceMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "Spatial3DOrbit")
        val sweep by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "SpecularSweepAngle"
        )
        val sway by infiniteTransition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "3dSwayY"
        )
        orbitAngle = sweep
        tiltY = sway * if (phase == SpatialCardPhase.GRABBED) 5.5f else 3.5f
    } else {
        orbitAngle = 45f
        tiltY = 0f
    }

    val darkMultiplier = if (palette.isDark) 1.0f else 0.72f
    val cardGlowAlpha = (glowStrength * 0.45f * darkMultiplier).coerceIn(0f, 0.55f)
    val rimAlpha = (glowStrength * (if (palette.isDark) 0.95f else 0.85f)).coerceIn(0f, 1f)

    this
        .graphicsLayer {
            cameraDistance = 14f * density
            scaleX = scale
            scaleY = scale
            rotationX = if (reduceMotion) 0f else rotX
            rotationY = if (reduceMotion) 0f else tiltY
            translationY = if (reduceMotion) 0f else liftDp.dp.toPx()
            shadowElevation = if (isActivePhase) 28.dp.toPx() else 8.dp.toPx()
            this.shape = shape
            clip = false
            ambientShadowColor = glowColor.copy(alpha = (0.35f + glowStrength * 0.55f).coerceAtMost(1f))
            spotShadowColor = glowColor.copy(alpha = (0.45f + glowStrength * 0.55f).coerceAtMost(1f))
        }
        .drawWithContent {
            val radiusPx = cornerRadius.toPx()

            // Outer volumetric 3D halo behind the card
            if (cardGlowAlpha > 0.02f) {
                val maxRadius = maxOf(size.width, size.height) * 0.82f
                val radAngle = (orbitAngle * PI / 180.0).toFloat()
                val lightOffset = Offset(
                    x = center.x + cos(radAngle) * size.width * 0.12f,
                    y = center.y + sin(radAngle) * size.height * 0.12f
                )
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = cardGlowAlpha),
                            glowColor.copy(alpha = cardGlowAlpha * 0.42f),
                            Color.Transparent
                        ),
                        center = lightOffset,
                        radius = maxRadius
                    ),
                    topLeft = Offset(-20.dp.toPx(), -14.dp.toPx()),
                    size = Size(size.width + 40.dp.toPx(), size.height + 40.dp.toPx()),
                    cornerRadius = CornerRadius(radiusPx * 1.4f, radiusPx * 1.4f)
                )
            }

            drawContent()

            // Luminous 3D specular bevel stroke gliding across the card edges
            if (rimAlpha > 0.05f && isActivePhase) {
                val highlightSpec = if (palette.isDark) Color.White else glowColor.copy(alpha = 0.95f)
                val strokeWidthPx = 2.2.dp.toPx()
                val rad = (orbitAngle * PI / 180.0).toFloat()
                val span = maxOf(size.width, size.height) * 0.65f
                val dx = cos(rad) * span
                val dy = sin(rad) * span

                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            glowColor.copy(alpha = rimAlpha * 0.16f),
                            glowColor.copy(alpha = rimAlpha * 0.72f),
                            highlightSpec.copy(alpha = rimAlpha),
                            glowColor.copy(alpha = rimAlpha * 0.30f)
                        ),
                        start = Offset(center.x - dx, center.y - dy),
                        end = Offset(center.x + dx, center.y + dy)
                    ),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = strokeWidthPx)
                )

                // Crisp traveling specular reflection glint along the edge
                val glintPoint = Offset(
                    x = center.x + cos(rad) * (size.width * 0.48f),
                    y = center.y + sin(rad) * (size.height * 0.48f)
                )
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            highlightSpec.copy(alpha = rimAlpha * 0.95f),
                            glowColor.copy(alpha = rimAlpha * 0.45f),
                            Color.Transparent
                        ),
                        center = glintPoint,
                        radius = minOf(size.width, size.height) * 0.48f
                    ),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = strokeWidthPx * 1.15f)
                )
            }
        }
}

/**
 * Renders an expanding 3D tilted luminous orbital ring and glow wave when a payload
 * is grabbed (inward pulse) or released / caught (outward volumetric burst).
 */
@Composable
fun SpatialGlowField(
    triggerKey: Any?,
    isOutwardRelease: Boolean,
    glowColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val palette = LocalAirGrabPalette.current
    val waveProgress = remember { Animatable(1f) }

    LaunchedEffect(triggerKey) {
        if (triggerKey == null || palette.reduceMotion) {
            waveProgress.snapTo(1f)
        } else {
            waveProgress.snapTo(0f)
            waveProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 680, easing = FastOutSlowInEasing)
            )
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val progress = waveProgress.value
        if (progress < 0.99f && !palette.reduceMotion) {
            val fade = (1f - progress).coerceIn(0f, 1f)
            val baseAlpha = (if (palette.isDark) 0.72f else 0.56f) * fade

            // Tilted 3D orbital shockwave plane behind the card
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        cameraDistance = 12f * density
                        rotationX = if (isOutwardRelease) 54f - (progress * 14f) else 48f
                    }
            ) {
                val baseRadius = minOf(size.width, size.height) * 0.44f
                val ringScale = if (isOutwardRelease) {
                    0.68f + (progress * 0.78f)
                } else {
                    1.22f - (progress * 0.44f)
                }
                val outerRadius = baseRadius * ringScale
                val innerRadius = outerRadius * 0.76f

                // Volumetric radial bloom on the tilted plane
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = baseAlpha * 0.35f),
                            glowColor.copy(alpha = baseAlpha * 0.14f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = outerRadius * 1.25f
                    ),
                    radius = outerRadius * 1.25f,
                    center = center
                )

                // Primary sharp 3D orbital shockwave ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            glowColor.copy(alpha = baseAlpha * 0.9f),
                            if (palette.isDark) Color.White.copy(alpha = baseAlpha * 0.8f) else glowColor.copy(alpha = baseAlpha),
                            Color.Transparent
                        ),
                        center = center,
                        radius = outerRadius
                    ),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = (3.5f * fade + 1f).dp.toPx())
                )

                // Secondary trailing echo wave
                if (isOutwardRelease) {
                    drawCircle(
                        color = glowColor.copy(alpha = baseAlpha * 0.45f),
                        radius = innerRadius,
                        center = center,
                        style = Stroke(width = (1.5f * fade + 0.5f).dp.toPx())
                    )
                }
            }
        }

        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}
