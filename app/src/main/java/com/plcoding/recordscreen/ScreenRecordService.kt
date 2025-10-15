package com.plcoding.recordscreen

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pytorch.*
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.math.max

class ScreenRecordService : Service(), TextToSpeech.OnInitListener {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var inferenceJob: Job? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenTime = 0L
    private var lastSpokenLabel: String? = null

    private var overlayView: OverlayView? = null

    private lateinit var config: ScreenRecordConfig
    private var model: Module? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "FocusNetService"
        const val NOTIF_ID = 1
        const val START_RECORDING = "START_RECORDING"
        const val STOP_RECORDING = "STOP_RECORDING"
        const val KEY_RECORDING_CONFIG = "KEY_RECORDING_CONFIG"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        private val _performanceMetrics = MutableStateFlow(PerformanceMetrics())
        val performanceMetrics = _performanceMetrics.asStateFlow()

        private val _hazardStats = MutableStateFlow(HazardStats())
        val hazardStats = _hazardStats.asStateFlow()

        private val _recentDetections = MutableStateFlow<List<Detection>>(emptyList())
        val recentDetections = _recentDetections.asStateFlow()

        private val _confidenceThreshold = MutableStateFlow(0.5f)
        val confidenceThreshold = _confidenceThreshold.asStateFlow()

        fun setConfidenceThreshold(value: Float) {
            _confidenceThreshold.value = value
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("FocusNet", "Service onCreate")
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FocusNet", "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            START_RECORDING -> startRecording(intent)
            STOP_RECORDING -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (_isServiceRunning.value) {
            Log.w("FocusNet", "Service already running")
            return
        }

        try {
            config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(KEY_RECORDING_CONFIG, ScreenRecordConfig::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(KEY_RECORDING_CONFIG)
            } ?: run {
                Log.e("FocusNet", "No config found in intent")
                stopSelf()
                return
            }

            Log.d("FocusNet", "Starting recording with model: ${config.modelFileName}")
            startForeground(NOTIF_ID, buildNotification("Loading model..."))
            _isServiceRunning.value = true

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = projectionManager.getMediaProjection(config.resultCode, config.data)

            if (projection == null) {
                Log.e("FocusNet", "Failed to get MediaProjection")
                stopRecording()
                return
            }

            // Show overlay
            mainHandler.post {
                startOverlay()
                Log.d("FocusNet", "Overlay started")
            }

            // Load model first
            scope.launch {
                loadModel(config.modelFileName)

                delay(300) // Give overlay time to initialize

                // Start capture and inference
                withContext(Dispatchers.Main) {
                    startImageReader()

                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotification("Detection active"))
                }

                // Start real inference loop
                startInferenceLoop()
            }

        } catch (e: Exception) {
            Log.e("FocusNet", "Error starting recording", e)
            stopRecording()
        }
    }

    private fun startInferenceLoop() {
        Log.d("FocusNet", "Starting inference loop")

        inferenceJob = scope.launch {
            var frameCount = 0
            val fpsCalculator = FpsCalculator()

            while (_isServiceRunning.value && imageReader != null) {
                try {
                    val startTime = System.currentTimeMillis()

                    // Acquire latest image
                    val image = imageReader?.acquireLatestImage()

                    if (image != null) {
                        frameCount++

                        // Convert image to bitmap
                        val bitmap = imageToBitmap(image)
                        image.close()

                        if (bitmap != null) {
                            // Run inference
                            val detections = if (model != null) {
                                runInference(bitmap)
                            } else {
                                Log.w("FocusNet", "Model not loaded, skipping frame")
                                emptyList()
                            }

                            val processingTime = System.currentTimeMillis() - startTime
                            val fps = fpsCalculator.tick()

                            // Update UI on main thread
                            withContext(Dispatchers.Main) {
                                overlayView?.updateDetections(detections)

                                _performanceMetrics.value = _performanceMetrics.value.copy(
                                    fps = fps,
                                    processingTimeMs = processingTime.toFloat(),
                                    totalDetections = _performanceMetrics.value.totalDetections + detections.size
                                )

                                if (detections.isNotEmpty()) {
                                    Log.d("FocusNet", "Detected ${detections.size} objects")
                                    _recentDetections.value = (detections + _recentDetections.value).take(10)
                                    announceHazards(detections)
                                    updateHazardStats(detections)
                                }
                            }

                            bitmap.recycle()
                        }
                    }

                    // Target ~10 FPS
                    val elapsed = System.currentTimeMillis() - startTime
                    val delayTime = max(100 - elapsed, 10)
                    delay(delayTime)

                } catch (e: Exception) {
                    Log.e("FocusNet", "Error in inference loop", e)
                    delay(500)
                }
            }
            Log.d("FocusNet", "Inference loop stopped")
        }
    }

    private fun runInference(bitmap: Bitmap): List<Detection> {
        return try {
            // Model expects 320x320 input (from your training config)
            val inputSize = 320
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

            // Convert to tensor with ImageNet normalization (from your export script)
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                resizedBitmap,
                floatArrayOf(0.485f, 0.456f, 0.406f),  // ImageNet mean
                floatArrayOf(0.229f, 0.224f, 0.225f)   // ImageNet std
            )

            Log.d("FocusNet", "Running inference on ${bitmap.width}x${bitmap.height} image")

            // Run model - returns tuple (boxes, labels, scores)
            val outputIValue = model?.forward(IValue.from(inputTensor))

            resizedBitmap.recycle()

            if (outputIValue == null) {
                Log.w("FocusNet", "Model returned null output")
                return emptyList()
            }

            // Parse output - boxes are in 320x320 space, will be scaled to original dimensions
            val threshold = _confidenceThreshold.value
            val detections = SSDOutputParser.parse(
                outputIValue,
                threshold,
                bitmap.width.toFloat(),
                bitmap.height.toFloat()
            )

            if (detections.isEmpty()) {
                Log.d("FocusNet", "No detections above threshold $threshold")
            } else {
                Log.d("FocusNet", "✅ ${detections.size} detections found")
            }

            detections

        } catch (e: Exception) {
            Log.e("FocusNet", "Error during inference", e)
            e.printStackTrace()
            emptyList()
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop if needed
            if (rowPadding != 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e("FocusNet", "Error converting image to bitmap", e)
            null
        }
    }

    private fun stopRecording() {
        Log.d("FocusNet", "Stopping recording")
        _isServiceRunning.value = false
        inferenceJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()

        mainHandler.post {
            overlayView?.remove()
        }

        projection?.stop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("FocusNet", "Service onDestroy")
        tts?.shutdown()
        scope.cancel()
    }

    private fun loadModel(modelFile: String) {
        try {
            val filePath = assetFilePath(this, modelFile)
            val file = File(filePath)
            Log.d("FocusNet", "Loading model from: $filePath")
            Log.d("FocusNet", "File exists: ${file.exists()}, Size: ${file.length() / 1024 / 1024}MB")

            model = LiteModuleLoader.load(filePath)
            Log.d("FocusNet", "✅ Successfully loaded model: $modelFile")

            // Test model with dummy input
            try {
                val testInput = Tensor.fromBlob(FloatArray(3 * 320 * 320) { 0.5f }, longArrayOf(1, 3, 320, 320))
                val testOutput = model?.forward(IValue.from(testInput))
                if (testOutput != null) {
                    val tuple = testOutput.toTuple()
                    Log.d("FocusNet", "✅ Model test passed - outputs tuple of ${tuple.size} elements")
                }
            } catch (e: Exception) {
                Log.e("FocusNet", "⚠️ Model test failed", e)
            }
        } catch (e: Exception) {
            Log.e("FocusNet", "❌ Error loading model: $modelFile", e)
            e.printStackTrace()
        }
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            Log.d("FocusNet", "Model file exists: ${file.absolutePath}")
            return file.absolutePath
        }

        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("FocusNet", "Copied model from assets: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("FocusNet", "Failed to copy model from assets: ${e.message}")
            throw e
        }
        return file.absolutePath
    }

    private fun startImageReader() {
        try {
            val width = Resources.getSystem().displayMetrics.widthPixels
            val height = Resources.getSystem().displayMetrics.heightPixels
            val density = Resources.getSystem().displayMetrics.densityDpi

            Log.d("FocusNet", "Creating ImageReader: ${width}x${height} @ ${density}dpi")

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            Log.d("FocusNet", "✅ ImageReader and VirtualDisplay created")
        } catch (e: Exception) {
            Log.e("FocusNet", "❌ Error creating ImageReader", e)
        }
    }

    private fun announceHazards(detections: List<Detection>) {
        if (!config.isVoiceAlertEnabled || !ttsReady) return
        val now = System.currentTimeMillis()

        detections.firstOrNull()?.let { det ->
            if (det.label != lastSpokenLabel || now - lastSpokenTime > 4000) {
                Log.d("FocusNet", "🔊 Speaking: ${det.label}")
                tts?.speak("${det.label} ahead!", TextToSpeech.QUEUE_FLUSH, null, null)
                lastSpokenLabel = det.label
                lastSpokenTime = now
            }
        }
    }

    private fun updateHazardStats(detections: List<Detection>) {
        var stats = _hazardStats.value
        detections.forEach {
            stats = when (it.label.lowercase()) {
                "pedestrian", "pedestrians" -> stats.copy(pedestrians = stats.pedestrians + 1)
                "pothole", "potholes" -> stats.copy(potholes = stats.potholes + 1)
                "hump", "humps" -> stats.copy(humps = stats.humps + 1)
                "animal", "animals" -> stats.copy(animals = stats.animals + 1)
                "road works", "roadworks" -> stats.copy(roadWorks = stats.roadWorks + 1)
                else -> stats
            }
        }
        _hazardStats.value = stats
    }

    private fun startOverlay() {
        try {
            if (overlayView != null) {
                Log.w("FocusNet", "Overlay already exists")
                return
            }
            overlayView = OverlayView(this)
            overlayView?.show()
            Log.d("FocusNet", "Overlay view created and shown")
        } catch (e: Exception) {
            Log.e("FocusNet", "Error starting overlay", e)
        }
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = STOP_RECORDING
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FocusNet")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "FocusNet Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            Log.d("FocusNet", "TTS initialized successfully")
        } else {
            Log.e("FocusNet", "TTS initialization failed with status: $status")
        }
    }
}

// Helper class for FPS calculation
class FpsCalculator {
    private var frameCount = 0
    private var lastTime = System.currentTimeMillis()

    fun tick(): Float {
        frameCount++
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastTime

        return if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            frameCount = 0
            lastTime = currentTime
            fps
        } else {
            0f
        }
    }
}

// FocusNet Mobile Output Parser
object SSDOutputParser {
    // Class labels matching your training script
    // Index 0 = background (excluded by model), 1-5 = actual classes
    private val labels = mapOf(
        1 to "Animal",
        2 to "Hump",
        3 to "Pedestrian",
        4 to "Pothole",
        5 to "Road Works"
    )

    fun parse(output: IValue?, threshold: Float, imgWidth: Float, imgHeight: Float): List<Detection> {
        if (output == null) return emptyList()

        try {
            // FocusNetMobile returns a tuple: (boxes, labels, scores)
            val outputTuple = output.toTuple()
            if (outputTuple.size != 3) {
                Log.e("FocusNet", "Expected 3 outputs, got ${outputTuple.size}")
                return emptyList()
            }

            // Extract tensors from tuple
            val boxesTensor = outputTuple[0].toTensor()
            val labelsTensor = outputTuple[1].toTensor()
            val scoresTensor = outputTuple[2].toTensor()

            // Convert to arrays
            val boxes = boxesTensor.dataAsFloatArray
            val labelsLong = labelsTensor.dataAsLongArray
            val scores = scoresTensor.dataAsFloatArray

            val numDetections = scores.size
            Log.d("FocusNet", "Model output: $numDetections detections (already filtered by model at 0.5)")

            val detections = mutableListOf<Detection>()

            // Scale factor from model's 320x320 input to actual screen size
            val scaleX = imgWidth / 320f
            val scaleY = imgHeight / 320f

            for (i in 0 until numDetections) {
                val score = scores[i]
                val labelId = labelsLong[i].toInt()

                // Apply user's confidence threshold (model already filtered at 0.5)
                if (score >= threshold && labelId in labels.keys) {
                    val boxOffset = i * 4

                    // Boxes are in 320x320 coordinate space, need to scale to screen size
                    val xMin = boxes[boxOffset] * scaleX
                    val yMin = boxes[boxOffset + 1] * scaleY
                    val xMax = boxes[boxOffset + 2] * scaleX
                    val yMax = boxes[boxOffset + 3] * scaleY

                    // Validate box dimensions
                    if (xMax > xMin && yMax > yMin) {
                        detections.add(
                            Detection(
                                label = labels[labelId] ?: "Unknown",
                                score = score,
                                rect = RectF(xMin, yMin, xMax, yMax)
                            )
                        )
                        Log.d("FocusNet", "✓ ${labels[labelId]}: ${(score * 100).toInt()}% at [$xMin, $yMin, $xMax, $yMax]")
                    }
                }
            }

            Log.d("FocusNet", "Final: ${detections.size} detections passed threshold $threshold")
            return detections

        } catch (e: Exception) {
            Log.e("FocusNet", "Error parsing model output", e)
            e.printStackTrace()
            return emptyList()
        }
    }
}

data class Detection(val label: String, val score: Float, val rect: RectF)
data class PerformanceMetrics(val fps: Float = 0f, val processingTimeMs: Float = 0f, val totalDetections: Int = 0)
data class HazardStats(val pedestrians: Int = 0, val potholes: Int = 0, val humps: Int = 0, val animals: Int = 0, val roadWorks: Int = 0)

class OverlayView(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var detections: List<Detection> = emptyList()

    private val paint = Paint().apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val bgPaint = Paint().apply {
        color = Color.argb(180, 255, 0, 0)
        style = Paint.Style.FILL
    }

    fun show() {
        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            overlayView = object : View(context) {
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    drawDetections(canvas)
                }
            }.apply {
                setBackgroundColor(Color.TRANSPARENT)
                setWillNotDraw(false)
            }

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            windowManager?.addView(overlayView, params)
            Log.d("FocusNet", "✅ Overlay added to WindowManager")

        } catch (e: Exception) {
            Log.e("FocusNet", "❌ Error showing overlay", e)
        }
    }

    fun updateDetections(newDetections: List<Detection>) {
        detections = newDetections
        overlayView?.post {
            overlayView?.invalidate()
        }
    }

    private fun drawDetections(canvas: Canvas) {
        detections.forEach { detection ->
            canvas.drawRect(detection.rect, paint)

            val label = "${detection.label} ${(detection.score * 100).toInt()}%"
            val textWidth = textPaint.measureText(label)
            val bgRect = RectF(
                detection.rect.left,
                detection.rect.top - 60f,
                detection.rect.left + textWidth + 20f,
                detection.rect.top
            )
            canvas.drawRect(bgRect, bgPaint)
            canvas.drawText(label, detection.rect.left + 10f, detection.rect.top - 15f, textPaint)
        }
    }

    fun remove() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
            overlayView = null
        } catch (e: Exception) {
            Log.e("FocusNet", "Error removing overlay", e)
        }
    }
}