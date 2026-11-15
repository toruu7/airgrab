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
    private var gestureRecognizer: GestureRecognizer? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    init {
        initGestureRecognizer()
    }

    private fun initGestureRecognizer() {
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
        cameraExecutor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

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

                imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    val frameTimeMs = SystemClock.uptimeMillis()
                    try {
                        if (frameRateController.shouldProcessFrame(frameTimeMs) && gestureRecognizer != null) {
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val bitmap = imageProxy.toBitmap()
                            val mpImage = BitmapImageBuilder(bitmap).build()

                            val imageProcessingOptions = ImageProcessingOptions.builder()
                                .setRotationDegrees(rotationDegrees)
                                .build()

                            gestureRecognizer?.recognizeAsync(mpImage, imageProcessingOptions, frameTimeMs)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in image analyzer: ${e.message}", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider?.unbindAll()

                if (previewView != null) {
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } else {
                    cameraProvider?.bindToLifecycle(
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

    override fun stop() {
        try {
            cameraProvider?.unbindAll()
            cameraExecutor?.shutdown()
            cameraExecutor = null
            gestureRecognizer?.close()
            gestureRecognizer = null
            frameRateController.reset()
            Log.d(TAG, "MediaPipeGestureDetector stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GestureDetector: ${e.message}", e)
        }
    }
}
