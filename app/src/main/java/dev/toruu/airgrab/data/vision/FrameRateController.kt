package dev.toruu.airgrab.data.vision

class FrameRateController(
    private var active: Boolean = false
) {
    private var lastProcessedFrameTimestampMs: Long = 0L

    fun setActive(isActive: Boolean) {
        active = isActive
    }

    fun isActive(): Boolean = active

    /**
     * Determines whether the current frame should be processed or dropped
     * based on the target frame rate (12 fps idle vs 30 fps active).
     */
    @Synchronized
    fun shouldProcessFrame(currentTimestampMs: Long): Boolean {
        val minIntervalMs = if (active) 33L else 83L // 30 fps vs ~12 fps
        if (currentTimestampMs - lastProcessedFrameTimestampMs >= minIntervalMs) {
            lastProcessedFrameTimestampMs = currentTimestampMs
            return true
        }
        return false
    }

    @Synchronized
    fun reset() {
        lastProcessedFrameTimestampMs = 0L
    }
}
