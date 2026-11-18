package dev.toruu.airgrab.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
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
 * Applies smooth GPU perspective 3D depth (scale, X/Y tilt, Z-lift) and a hairline
 * traveling specular glass rim for Grabbed and Released states.
 *
 * Intentionally omits Android elevation shadows and bounded outer gradient rects so there
 * are zero muddy inner shadow boxes or clipped glow edges.
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
        SpatialCardPhase.GRABBED -> 0.87f
        SpatialCardPhase.RELEASING -> 1.02f
        SpatialCardPhase.DELIVERED -> 1.0f
    }
    val targetRotationX = when (phase) {
        SpatialCardPhase.RESTING -> 0f
        SpatialCardPhase.GRABBED -> 7.5f
        SpatialCardPhase.RELEASING -> -6.5f
        SpatialCardPhase.DELIVERED -> 0f
    }
    val targetLiftDp = when (phase) {
        SpatialCardPhase.RESTING -> 0f
        SpatialCardPhase.GRABBED -> -8f
        SpatialCardPhase.RELEASING -> -14f
        SpatialCardPhase.DELIVERED -> 0f
    }
    val targetRimStrength = when (phase) {
        SpatialCardPhase.RESTING -> 0f
        SpatialCardPhase.GRABBED -> 0.92f
        SpatialCardPhase.RELEASING -> 1.0f
        SpatialCardPhase.DELIVERED -> 0.48f
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
    val rimStrength by animateFloatAsState(
        targetRimStrength,
        animationSpec = if (reduceMotion) snap() else tween(280, easing = FastOutSlowInEasing),
        label = "3dRim"
    )
    val smoothColor by animateColorAsState(
        targetValue = glowColor,
        animationSpec = if (reduceMotion) snap() else tween(280, easing = FastOutSlowInEasing),
        label = "3dColor"
    )

    val isActivePhase = phase == SpatialCardPhase.GRABBED || phase == SpatialCardPhase.RELEASING

    val orbitAngle: Float
    val tiltY: Float
    if (isActivePhase && !reduceMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "Spatial3DOrbit")
        val sweep by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "SpecularSweepAngle"
        )
        val sway by infiniteTransition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "3dSwayY"
        )
        orbitAngle = sweep
        tiltY = sway * if (phase == SpatialCardPhase.GRABBED) 4.0f else 2.5f
    } else {
        orbitAngle = 315f
        tiltY = 0f
    }

    val baseBorderColor = palette.borderHairline

    this
        .graphicsLayer {
            cameraDistance = 16f * density
            scaleX = scale
            scaleY = scale
            rotationX = if (reduceMotion) 0f else rotX
            rotationY = if (reduceMotion) 0f else tiltY
            translationY = if (reduceMotion) 0f else liftDp.dp.toPx()
            shadowElevation = 0f
            this.shape = shape
            clip = false
        }
        .drawWithContent {
            drawContent()

            val radiusPx = cornerRadius.toPx()
            val strokePx = if (isActivePhase) 1.35.dp.toPx() else 1.dp.toPx()

            if (rimStrength > 0.03f) {
                val glintColor = if (palette.isDark) Color.White else smoothColor
                val rad = (orbitAngle * PI / 180.0).toFloat()
                val span = maxOf(size.width, size.height) * 0.55f
                val dx = cos(rad) * span
                val dy = sin(rad) * span

                // Continuous thin bevel rim
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            smoothColor.copy(alpha = rimStrength * 0.14f),
                            smoothColor.copy(alpha = rimStrength * 0.55f),
                            glintColor.copy(alpha = rimStrength * (if (palette.isDark) 0.85f else 0.72f)),
                            smoothColor.copy(alpha = rimStrength * 0.22f)
                        ),
                        start = Offset(center.x - dx, center.y - dy),
                        end = Offset(center.x + dx, center.y + dy)
                    ),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = strokePx)
                )

                // Soft moving specular highlight along edge during Grab/Release
                if (isActivePhase) {
                    val glintCenter = Offset(
                        x = center.x + cos(rad) * (size.width * 0.45f),
                        y = center.y + sin(rad) * (size.height * 0.45f)
                    )
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            0.0f to glintColor.copy(alpha = rimStrength * 0.90f),
                            0.45f to smoothColor.copy(alpha = rimStrength * 0.35f),
                            1.0f to Color.Transparent,
                            center = glintCenter,
                            radius = minOf(size.width, size.height) * 0.55f
                        ),
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                        style = Stroke(width = strokePx * 1.3f)
                    )
                }
            } else {
                drawRoundRect(
                    color = baseBorderColor,
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }
}

/**
 * Full-stage volumetric 3D backdrop bloom + tilted orbital energy ring for Grabbed and
 * Released animations. Draws across the unconstrained stage so radial light dissolves
 * cleanly to zero without ever hitting a rectangular clip boundary.
 */
@Composable
fun SpatialGlowField(
    phase: SpatialCardPhase,
    triggerKey: Any?,
    isOutwardRelease: Boolean,
    glowColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val palette = LocalAirGrabPalette.current
    val waveProgress = remember { Animatable(1f) }

    val targetBloom = when (phase) {
        SpatialCardPhase.RESTING -> 0f
        SpatialCardPhase.GRABBED -> 0.85f
        SpatialCardPhase.RELEASING -> 1.0f
        SpatialCardPhase.DELIVERED -> 0.42f
    }

    val bloomStrength by animateFloatAsState(
        targetValue = targetBloom,
        animationSpec = if (palette.reduceMotion) snap() else tween(420, easing = FastOutSlowInEasing),
        label = "StageBloomStrength"
    )
    val smoothColor by animateColorAsState(
        targetValue = glowColor,
        animationSpec = if (palette.reduceMotion) snap() else tween(320, easing = FastOutSlowInEasing),
        label = "StageBloomColor"
    )

    LaunchedEffect(triggerKey) {
        if (triggerKey == null || palette.reduceMotion) {
            waveProgress.snapTo(1f)
        } else {
            waveProgress.snapTo(0f)
            waveProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 760, easing = FastOutSlowInEasing)
            )
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Unconstrained stage backdrop layer (never clipped to card bounds)
        Canvas(modifier = Modifier.matchParentSize()) {
            val darkFactor = if (palette.isDark) 0.34f else 0.22f
            val peakAlpha = bloomStrength * darkFactor

            if (peakAlpha > 0.01f) {
                val maxR = maxOf(size.width, size.height) * 0.56f
                scale(scaleX = 1.1f, scaleY = 0.92f, pivot = center) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to smoothColor.copy(alpha = peakAlpha),
                            0.25f to smoothColor.copy(alpha = peakAlpha * 0.58f),
                            0.52f to smoothColor.copy(alpha = peakAlpha * 0.22f),
                            0.78f to smoothColor.copy(alpha = peakAlpha * 0.05f),
                            1.0f to Color.Transparent,
                            center = center,
                            radius = maxR
                        ),
                        radius = maxR,
                        center = center
                    )
                }
            }
        }

        // Tilted 3D orbital shockwave plane for Grab (inward) / Release (outward)
        val progress = waveProgress.value
        if (progress < 0.99f && !palette.reduceMotion) {
            val fade = (1f - progress) * (1f - progress) // Smooth quadratic fade
            val waveAlpha = (if (palette.isDark) 0.65f else 0.45f) * fade

            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        cameraDistance = 14f * density
                        rotationX = if (isOutwardRelease) 56f - (progress * 12f) else 48f
                    }
            ) {
                val baseRadius = minOf(size.width, size.height) * 0.34f
                val ringScale = if (isOutwardRelease) {
                    0.55f + (progress * 0.85f)
                } else {
                    1.15f - (progress * 0.45f)
                }
                val outerRadius = baseRadius * ringScale
                val ringColor = if (palette.isDark) Color.White else smoothColor

                // Primary thin 3D orbital wave
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to Color.Transparent,
                        0.72f to Color.Transparent,
                        0.88f to smoothColor.copy(alpha = waveAlpha * 0.65f),
                        0.95f to ringColor.copy(alpha = waveAlpha * 0.85f),
                        1.0f to Color.Transparent,
                        center = center,
                        radius = outerRadius
                    ),
                    radius = outerRadius,
                    center = center
                )

                // Crisp core hairline ring
                drawCircle(
                    color = smoothColor.copy(alpha = waveAlpha * 0.72f),
                    radius = outerRadius * 0.94f,
                    center = center,
                    style = Stroke(width = (2.2f * fade + 0.6f).dp.toPx())
                )
            }
        }

        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}
