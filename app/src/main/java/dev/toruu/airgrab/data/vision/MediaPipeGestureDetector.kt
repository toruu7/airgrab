package dev.toruu.airgrab.data.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MediaPipeGestureDetector(
    private val context: Context
) : GestureDetector {

    companion object {
        private const val TAG = "AirGrab_Vision"
        private const val MODEL_NAME = "gesture_recognizer.task"
    }

    private val _gestures = MutableStateFlow(
        GestureFrameResult(HandGesture.NONE, 0f, SystemClock.uptimeMillis())
    )
    override val gestures: StateFlow<GestureFrameResult> = _gestures.asStateFlow()

    private val frameRateController = FrameRateController(active = false)
    private val recognizerLock = Any()
    private var gestureRecognizer: GestureRecognizer? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile
    private var activeSessionToken: Long = 0L

    init {
        initGestureRecognizer()
    }

    private fun initGestureRecognizer() {
        synchronized(recognizerLock) {
            if (gestureRecognizer != null) return
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_NAME)
                    .build()

                val options = GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { result: GestureRecognizerResult, _: MPImage ->
                        processGestureResult(result)
                    }
                    .setErrorListener { error: RuntimeException ->
                        Log.e(TAG, "MediaPipe GestureRecognizer error", error)
                    }
                    .build()

                gestureRecognizer = GestureRecognizer.createFromOptions(context, options)
                Log.d(TAG, "MediaPipe GestureRecognizer initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize GestureRecognizer: ${e.message}", e)
            }
        }
    }

    private fun processGestureResult(result: GestureRecognizerResult) {
        val now = SystemClock.uptimeMillis()
        val gesturesList = result.gestures()

        if (gesturesList.isNotEmpty() && gesturesList[0].isNotEmpty()) {
            val topCategory = gesturesList[0][0]
            val score = topCategory.score()
            val categoryName = topCategory.categoryName()

            val detectedGesture = when {
                categoryName.equals("Closed_Fist", ignoreCase = true) -> HandGesture.CLOSED_FIST
                categoryName.equals("Open_Palm", ignoreCase = true) -> HandGesture.OPEN_PALM
                else -> HandGesture.NONE
            }

            _gestures.value = GestureFrameResult(
                gesture = detectedGesture,
                confidence = score,
                timestampMs = now
            )
        } else {
            _gestures.value = GestureFrameResult(
                gesture = HandGesture.NONE,
                confidence = 0f,
                timestampMs = now
            )
        }
    }

    override fun setPowerMode(active: Boolean) {
        frameRateController.setActive(active)
    }

    override fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView?) {
        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            return
        }

        // Re-initialize MediaPipe if stop() was previously called (e.g. onPause -> onResume).
        initGestureRecognizer()

        // Never leak executors if start() is called multiple times across recompositions/resumes.
        cameraExecutor?.shutdownNow()
        val executor = Executors.newSingleThreadExecutor()
        cameraExecutor = executor

        val sessionToken = ++activeSessionToken
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            if (sessionToken != activeSessionToken ||
                lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED
            ) {
                return@addListener
            }

            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    val frameTimeMs = SystemClock.uptimeMillis()
                    try {
                        if (sessionToken == activeSessionToken && frameRateController.shouldProcessFrame(frameTimeMs)) {
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val bitmap = imageProxy.toBitmap()
                            val mpImage = BitmapImageBuilder(bitmap).build()

                            val imageProcessingOptions = ImageProcessingOptions.builder()
                                .setRotationDegrees(rotationDegrees)
                                .build()

                            synchronized(recognizerLock) {
                                gestureRecognizer?.recognizeAsync(mpImage, imageProcessingOptions, frameTimeMs)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in image analyzer: ${e.message}", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                provider.unbindAll()

                if (previewView != null) {
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } else {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        imageAnalysis
                    )
                }
                Log.d(TAG, "CameraX pipeline started with front camera")
            } catch (e: Exception) {
                Log.e(TAG, "CameraX start failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun unbindCamera() {
        activeSessionToken++
        try {
            cameraProvider?.unbindAll()
            cameraExecutor?.shutdownNow()
            cameraExecutor = null
            frameRateController.reset()
            _gestures.value = GestureFrameResult(HandGesture.NONE, 0f, SystemClock.uptimeMillis())
            Log.d(TAG, "CameraX unbound safely (model retained)")
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding CameraX: ${e.message}", e)
        }
    }

    override fun stop() {
        unbindCamera()
        synchronized(recognizerLock) {
            try {
                gestureRecognizer?.close()
                gestureRecognizer = null
                Log.d(TAG, "MediaPipeGestureDetector stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping GestureDetector: ${e.message}", e)
            }
        }
    }
}
