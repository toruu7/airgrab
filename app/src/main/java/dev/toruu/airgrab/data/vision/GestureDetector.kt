package dev.toruu.airgrab.data.vision

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow

enum class HandGesture {
    NONE,
    CLOSED_FIST,
    OPEN_PALM
}

data class GestureFrameResult(
    val gesture: HandGesture,
    val confidence: Float,
    val timestampMs: Long
)

interface GestureDetector {
    val gestures: StateFlow<GestureFrameResult>
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView?)
    fun stop()
    fun setPowerMode(active: Boolean) // 12 fps idle vs 30 fps active
}
