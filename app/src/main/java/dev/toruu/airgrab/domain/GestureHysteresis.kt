package dev.toruu.airgrab.domain

import dev.toruu.airgrab.data.vision.GestureFrameResult
import dev.toruu.airgrab.data.vision.HandGesture

sealed interface GestureEvent {
    object None : GestureEvent
    object GrabTriggered : GestureEvent
    object DropTriggered : GestureEvent
}

class GestureHysteresis(
    private val grabThresholdMs: Long = 300L,
    private val dropThresholdMs: Long = 250L,
    private val minConfidence: Float = 0.72f,
    private val cooldownDurationMs: Long = 1500L
) {
    private var fistStartTimeMs: Long? = null
    private var palmStartTimeMs: Long? = null
    private var cooldownUntilMs: Long = 0L

    private var grabFired = false
    private var dropFired = false

    @Synchronized
    fun startCooldown(customDurationMs: Long? = null) {
        val duration = customDurationMs ?: cooldownDurationMs
        cooldownUntilMs = System.currentTimeMillis() + duration
        resetState()
    }

    @Synchronized
    fun isCoolingDown(): Boolean {
        return System.currentTimeMillis() < cooldownUntilMs
    }

    @Synchronized
    fun resetState() {
        fistStartTimeMs = null
        palmStartTimeMs = null
        grabFired = false
        dropFired = false
    }

    /**
     * Processes each incoming gesture frame and evaluates debounce/hysteresis conditions.
     */
    @Synchronized
    fun processFrame(frame: GestureFrameResult): GestureEvent {
        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs) {
            return GestureEvent.None
        }

        val gesture = frame.gesture
        val confidence = frame.confidence

        // Handle CLOSED_FIST (Grab)
        if (gesture == HandGesture.CLOSED_FIST && confidence >= minConfidence) {
            // Reset palm tracking
            palmStartTimeMs = null
            dropFired = false

            val startTime = fistStartTimeMs ?: run {
                fistStartTimeMs = now
                now
            }

            if (!grabFired && (now - startTime) >= grabThresholdMs) {
                grabFired = true
                return GestureEvent.GrabTriggered
            }
        } else {
            fistStartTimeMs = null
            grabFired = false
        }

        // Handle OPEN_PALM (Drop)
        if (gesture == HandGesture.OPEN_PALM && confidence >= minConfidence) {
            // Reset fist tracking
            fistStartTimeMs = null
            grabFired = false

            val startTime = palmStartTimeMs ?: run {
                palmStartTimeMs = now
                now
            }

            if (!dropFired && (now - startTime) >= dropThresholdMs) {
                dropFired = true
                return GestureEvent.DropTriggered
            }
        } else {
            palmStartTimeMs = null
            dropFired = false
        }

        return GestureEvent.None
    }
}
