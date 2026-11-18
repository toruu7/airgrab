package dev.toruu.airgrab.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

object AirGrabMotion {
    /**
     * Critically damped spring for non-distracting layout transitions.
     */
    val LayoutSettle: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /**
     * Physical compression spring when holding a fist under tension.
     */
    val GrabCompression: SpringSpec<Float> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow
    )

    /**
     * Rebound spring when interrupting/releasing a gesture.
     */
    val GestureInterruption: SpringSpec<Float> = spring(
        dampingRatio = 0.90f,
        stiffness = Spring.StiffnessMedium
    )

    /**
     * Expanding spring when dropping and unpacking a payload on the receiver.
     */
    val DropUnpack: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )
}
