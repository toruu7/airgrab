package dev.toruu.airgrab.domain

import dev.toruu.airgrab.data.vision.GestureFrameResult
import dev.toruu.airgrab.data.vision.HandGesture

sealed interface GestureEvent {
    object None : GestureEvent
    object GrabTriggered : GestureEvent
    object DropTriggered : GestureEvent
}

/**
 * Debounces raw MediaPipe frames into discrete grab/drop events.
 *
 * Important: the camera runs at ~12 fps when idle, so a 250 ms hold is only ~3 frames. Requiring
 * an unbroken streak of confident frames made the palm gesture unreliable - a single frame where
 * MediaPipe momentarily reported `None` (motion blur, hand partially out of frame, a 0.7 score)
 * used to clear the hold timer and restart the countdown from zero.
 *
 * Instead, a gesture stays "in progress" as long as it was seen within [gapToleranceMs]. Short
 * dropouts are ignored; only a real absence of the gesture resets the timer.
 */
class GestureHysteresis(
    private val grabThresholdMs: Long = 300L,
    private val dropThresholdMs: Long = 250L,
    /** Confidence needed to start/continue a closed-fist (grab). */
    private val minConfidence: Float = 0.72f,
    /** Open palms score lower than fists at an angle, so the drop entry bar is more forgiving. */
    private val minDropConfidence: Float = 0.60f,
    /** How long a gesture may disappear before the hold timer is considered abandoned. */
    private val gapToleranceMs: Long = 350L,
    private val cooldownDurationMs: Long = 1500L
) {
    private var fistStartTimeMs: Long? = null
    private var fistLastSeenMs: Long = 0L
    private var palmStartTimeMs: Long? = null
    private var palmLastSeenMs: Long = 0L
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
        fistLastSeenMs = 0L
        palmStartTimeMs = null
        palmLastSeenMs = 0L
        grabFired = false
        dropFired = false
    }

    /**
     * Allows an already-visible open palm to trigger a drop on the next frame.
     *
     * Used when a drop event arrived while this device was not armed yet (e.g. the user was
     * holding the pose before the grab signal landed), so the event was ignored downstream.
     */
    @Synchronized
    fun rearmPalm() {
        dropFired = false
    }

    /**
     * Allows an already-visible closed fist to trigger a grab on the next frame, e.g. when the
     * user was holding the pose while a payload was still being staged.
     */
    @Synchronized
    fun rearmFist() {
        grabFired = false
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

        val fistVisible =
            frame.gesture == HandGesture.CLOSED_FIST && frame.confidence >= minConfidence
        val palmVisible =
            frame.gesture == HandGesture.OPEN_PALM && frame.confidence >= minDropConfidence

        if (fistVisible) {
            // Switching gestures abandons the other one immediately.
            palmStartTimeMs = null
            palmLastSeenMs = 0L
            dropFired = false

            val startTime = fistStartTimeMs ?: now.also { fistStartTimeMs = it }
            fistLastSeenMs = now

            if (!grabFired && (now - startTime) >= grabThresholdMs) {
                grabFired = true
                return GestureEvent.GrabTriggered
            }
        } else if (fistStartTimeMs != null && now - fistLastSeenMs > gapToleranceMs) {
            // Truly gone (not just a one-frame flicker) - restart the countdown next time.
            fistStartTimeMs = null
            grabFired = false
        }

        if (palmVisible) {
            fistStartTimeMs = null
            fistLastSeenMs = 0L
            grabFired = false

            val startTime = palmStartTimeMs ?: now.also { palmStartTimeMs = it }
            palmLastSeenMs = now

            if (!dropFired && (now - startTime) >= dropThresholdMs) {
                dropFired = true
                return GestureEvent.DropTriggered
            }
        } else if (palmStartTimeMs != null && now - palmLastSeenMs > gapToleranceMs) {
            palmStartTimeMs = null
            dropFired = false
        }

        return GestureEvent.None
    }
}
