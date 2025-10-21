package com.plcoding.recordscreen

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

class ScreenRecordService : Service() {

    companion object {
        const val CHANNEL_ID = "record_channel"
        const val NOTIF_ID = 1001
        const val START_RECORDING = "ACTION_START_RECORDING"
        const val STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val KEY_RECORDING_CONFIG = "KEY_RECORDING_CONFIG"

        val isServiceRunning = MutableStateFlow(false)
        private val _performanceMetrics = MutableStateFlow(PerformanceMetrics())
        val performanceMetrics: StateFlow<PerformanceMetrics> = _performanceMetrics.asStateFlow()
        private val _hazardStats = MutableStateFlow(HazardDetectionStats())
        val hazardStats: StateFlow<HazardDetectionStats> = _hazardStats.asStateFlow()
        private val _recentDetections = MutableStateFlow<List<Detection>>(emptyList())
        val recentDetections: StateFlow<List<Detection>> = _recentDetections.asStateFlow()
    }

    private var sessionStartTime = 0L
    private var totalDetections = 0
    private var confidenceSum = 0f
    private var frameCount = 0
    private val processingTimes = ArrayDeque<Float>(10)

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionManager: MediaProjectionManager? = null
    private var overlayView: OverlayView? = null
    private var windowManager: WindowManager? = null
    private var overlayAdded = false
    private var tflite: Interpreter? = null
    private var detectionJob: Job? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentModelName: String? = null
    private var isVoiceAlertEnabled: Boolean = true
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var lastAnnouncedHazards = setOf<String>()
    private var lastAnnounceTime = 0L
    private val announceDebounceMs = 3000L

    // ✅ NEW: Track capture dimensions for proper scaling
    private var captureWidth = 416
    private var captureHeight = 416
    private var letterboxOffsetX = 0f
    private var letterboxOffsetY = 0f
    private var letterboxScale = 1f

    private var reusableBitmap: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isTtsReady = true
                Log.d("ScreenRecordService", "✅ TTS initialized successfully")
            } else {
                Log.w("ScreenRecordService", "❌ TTS initialization failed")
            }
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START_RECORDING -> {
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(KEY_RECORDING_CONFIG, ScreenRecordConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(KEY_RECORDING_CONFIG)
                }
                if (config != null) {
                    if (!canDrawOverlays()) {
                        stopSelf()
                        return START_NOT_STICKY
                    }

                    isVoiceAlertEnabled = config.isVoiceAlertEnabled
                    loadModel(config.modelFileName)

                    startForegroundServiceWithNotification()
                    startProjectionAndDetection(config)
                    isServiceRunning.value = true
                }
            }
            STOP_RECORDING -> {
                stopProjectionAndDetection()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                isServiceRunning.value = false
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProjectionAndDetection()
        tflite?.close()
        tts?.stop()
        tts?.shutdown()
        tts = null
        reusableBitmap?.recycle()
        reusableBitmap = null
        coroutineScope.cancel()
        isServiceRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadModel(modelFileName: String) {
        tflite?.close()
        tflite = null

        try {
            loadModelFile(modelFileName)?.let { buffer ->
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                    setUseNNAPI(false)
                }
                tflite = Interpreter(buffer, options)
                currentModelName = modelFileName

                val inputTensor = tflite?.getInputTensor(0)
                val outputTensor = tflite?.getOutputTensor(0)

                Log.d("ScreenRecordService", "=".repeat(60))
                Log.d("ScreenRecordService", "📐 MODEL LOADED: $modelFileName")
                Log.d("ScreenRecordService", "Input: ${inputTensor?.shape()?.contentToString()}")
                Log.d("ScreenRecordService", "Output: ${outputTensor?.shape()?.contentToString()}")
                Log.d("ScreenRecordService", "=".repeat(60))

                Log.d("ScreenRecordService", "✅ Model loaded successfully")
            } ?: run {
                Log.e("ScreenRecordService", "❌ Model file not found: $modelFileName")
            }
        } catch (e: Exception) {
            Log.e("ScreenRecordService", "❌ Model load failed: ${e.message}", e)
        }
    }

    private fun startProjectionAndDetection(config: ScreenRecordConfig) {
        stopProjectionAndDetection()

        sessionStartTime = System.currentTimeMillis()
        totalDetections = 0
        confidenceSum = 0f
        frameCount = 0
        processingTimes.clear()
        _performanceMetrics.value = PerformanceMetrics()
        _hazardStats.value = HazardDetectionStats()
        _recentDetections.value = emptyList()

        mediaProjection = projectionManager?.getMediaProjection(config.resultCode, config.data)

        // ✅ FIXED: Calculate capture dimensions based on screen aspect ratio
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val targetSize = 416
        val aspectRatio = screenWidth.toFloat() / screenHeight.toFloat()

        if (screenWidth > screenHeight) {
            // Landscape
            captureWidth = targetSize
            captureHeight = (targetSize / aspectRatio).toInt()
        } else {
            // Portrait (most common)
            captureHeight = targetSize
            captureWidth = (targetSize * aspectRatio).toInt()
        }

        Log.d("ScreenRecordService", "📱 Screen: ${screenWidth}x${screenHeight}")
        Log.d("ScreenRecordService", "📷 Capture: ${captureWidth}x${captureHeight}")
        Log.d("ScreenRecordService", "📐 Aspect Ratio: $aspectRatio")

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.w("ScreenRecordService", "⚠️ MediaProjection stopped by system.")
                stopProjectionAndDetection()
            }
        }
        mediaProjection?.registerCallback(projectionCallback!!, null)

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DetectionDisplay",
            captureWidth, captureHeight,  // ✅ Use calculated dimensions
            resources.displayMetrics.densityDpi,
            0,
            imageReader?.surface, null, null
        )
        addOverlay()

        detectionJob = coroutineScope.launch {
            val reader = imageReader!!
            while (isActive && mediaProjection != null) {
                val frameStartTime = System.currentTimeMillis()

                val img = tryAcquire(reader)
                if (img != null) {
                    val bmp = imageToBitmapOptimized(img)
                    img.close()

                    val input = prepareInput(bmp)
                    val output = runModel(input)
                    val dets = parseModelOutput(output, captureWidth, captureHeight)

                    updatePerformanceMetrics(dets, frameStartTime)
                    announceDetections(dets)

                    withContext(Dispatchers.Main) {
                        overlayView?.setDetections(dets, captureWidth, captureHeight)
                    }

                    _recentDetections.value = dets
                }

                val processingTime = System.currentTimeMillis() - frameStartTime
                val targetDelay = if (processingTime < 50) 60L else 80L
                delay(targetDelay)
            }
        }
    }

    private fun updatePerformanceMetrics(detections: List<Detection>, frameStartTime: Long) {
        val processingTime = (System.currentTimeMillis() - frameStartTime).toFloat()
        val currentTime = System.currentTimeMillis()

        frameCount++
        totalDetections += detections.size

        processingTimes.addLast(processingTime)
        if (processingTimes.size > 10) {
            processingTimes.removeFirst()
        }

        detections.forEach { detection ->
            confidenceSum += detection.score
        }

        val sessionDuration = currentTime - sessionStartTime
        val currentFps = if (sessionDuration > 0) (frameCount.toFloat() * 1000f) / sessionDuration.toFloat() else 0f

        val avgConfidence = if (totalDetections > 0) confidenceSum / totalDetections.toFloat() else 0f
        val avgProcessingTime = if (processingTimes.isNotEmpty()) processingTimes.average().toFloat() else 0f

        _performanceMetrics.value = PerformanceMetrics(
            totalDetections = totalDetections,
            avgConfidence = avgConfidence,
            processingTimeMs = avgProcessingTime,
            fps = currentFps,
            sessionDurationMs = sessionDuration
        )

        updateHazardStats(detections)
    }

    private fun updateHazardStats(detections: List<Detection>) {
        val currentStats = _hazardStats.value
        var pedestrians = currentStats.pedestrians
        var potholes = currentStats.potholes
        var humps = currentStats.humps
        var animals = currentStats.animals
        var roadWorks = currentStats.roadWorks

        detections.forEach { detection ->
            when (detection.label) {
                "pedestrian" -> pedestrians++
                "pothole" -> potholes++
                "humps" -> humps++
                "animals" -> animals++
                "roadworks" -> roadWorks++
            }
        }

        _hazardStats.value = HazardDetectionStats(
            pedestrians = pedestrians,
            potholes = potholes,
            humps = humps,
            animals = animals,
            roadWorks = roadWorks
        )
    }

    private fun stopProjectionAndDetection() {
        detectionJob?.cancel()
        detectionJob = null
        removeOverlay()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        projectionCallback?.let {
            mediaProjection?.unregisterCallback(it)
            projectionCallback = null
        }

        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun tryAcquire(reader: ImageReader): Image? =
        try { reader.acquireLatestImage() } catch (e: Exception) { null }

    private fun imageToBitmapOptimized(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val width = image.width + rowPadding / pixelStride
        val height = image.height

        if (reusableBitmap == null ||
            reusableBitmap?.width != width ||
            reusableBitmap?.height != height) {
            reusableBitmap?.recycle()
            reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        reusableBitmap?.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            reusableBitmap!!
        } else {
            Bitmap.createBitmap(reusableBitmap!!, 0, 0, image.width, image.height)
        }
    }

    // ✅ FIXED: Letterbox resize to preserve aspect ratio
    private fun prepareInput(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val modelInputSize = 416
        val inputBmp = letterboxResize(bitmap, modelInputSize, modelInputSize)
        val input = Array(1) { Array(modelInputSize) { Array(modelInputSize) { FloatArray(3) } } }

        for (y in 0 until modelInputSize) {
            for (x in 0 until modelInputSize) {
                val px = inputBmp.getPixel(x, y)
                input[0][y][x][0] = ((px shr 16 and 0xFF) / 255f)
                input[0][y][x][1] = ((px shr 8 and 0xFF) / 255f)
                input[0][y][x][2] = ((px and 0xFF) / 255f)
            }
        }

        inputBmp.recycle()
        return input
    }

    // ✅ NEW: Letterbox resize function to preserve aspect ratio
    private fun letterboxResize(bitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcW = bitmap.width
        val srcH = bitmap.height

        // Calculate scale to fit within target while preserving aspect ratio
        val scale = minOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()

        // Scale the image
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)

        // Create letterboxed image with gray padding
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(114, 114, 114)) // Gray padding

        // Center the scaled image
        val left = (targetW - scaledW) / 2f
        val top = (targetH - scaledH) / 2f
        canvas.drawBitmap(scaled, left, top, null)

        // Store letterbox parameters for coordinate transformation
        letterboxOffsetX = left
        letterboxOffsetY = top
        letterboxScale = scale

        Log.d("ScreenRecordService", "📐 Letterbox: offset=($left, $top), scale=$scale")

        scaled.recycle()
        return result
    }

    private fun runModel(input: Array<Array<Array<FloatArray>>>): Array<Array<FloatArray>> {
        val output = Array(1) { Array(9) { FloatArray(3549) } }
        try {
            tflite?.run(input, output)
        } catch (e: Exception) {
            Log.e("ScreenRecordService", "❌ Inference failed: ${e.message}", e)
        }
        return output
    }

    // ✅ FIXED: Account for letterboxing when converting coordinates
    private fun parseModelOutput(
        output: Array<Array<FloatArray>>,
        imageW: Int,
        imageH: Int,
        confThreshold: Float = 0.50f,
        iouThreshold: Float = 0.50f
    ): List<Detection> {

        val preds = output[0]
        val numBoxes = 3549
        val rawDetections = ArrayList<Detection>(64)
        val classCount = 5
        val modelSize = 416f

        for (i in 0 until numBoxes) {
            val xc = preds[0][i]
            val yc = preds[1][i]
            val bw = preds[2][i]
            val bh = preds[3][i]

            var bestClass = 0
            var bestScore = preds[4][i]

            for (c in 1 until classCount) {
                val score = preds[4 + c][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }

            if (bestScore < confThreshold) continue

            // ✅ FIXED: Convert from model space to original capture space
            // accounting for letterboxing
            val left = ((xc - bw / 2f) * modelSize - letterboxOffsetX) / letterboxScale
            val top = ((yc - bh / 2f) * modelSize - letterboxOffsetY) / letterboxScale
            val right = ((xc + bw / 2f) * modelSize - letterboxOffsetX) / letterboxScale
            val bottom = ((yc + bh / 2f) * modelSize - letterboxOffsetY) / letterboxScale

            val l = left.coerceIn(0f, imageW.toFloat())
            val t = top.coerceIn(0f, imageH.toFloat())
            val r = right.coerceIn(0f, imageW.toFloat())
            val b = bottom.coerceIn(0f, imageH.toFloat())

            val boxWidth = r - l
            val boxHeight = b - t
            if (boxWidth < 15f || boxHeight < 15f) continue
            if (boxWidth > imageW * 0.9f || boxHeight > imageH * 0.9f) continue

            val labelNames = arrayOf("animals", "humps", "pedestrian", "pothole", "roadworks")
            val label = labelNames.getOrNull(bestClass) ?: "unknown"

            rawDetections.add(Detection(RectF(l, t, r, b), label, bestScore))
        }

        val final = nonMaxSuppression(rawDetections, iouThreshold)

        if (final.isNotEmpty()) {
            Log.d("ScreenRecordService", "✅ Detections: ${final.size} (filtered from ${rawDetections.size})")
        }

        return final
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interW = interRight - interLeft
        val interH = interBottom - interTop
        if (interW <= 0f || interH <= 0f) return 0f
        val interArea = interW * interH
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return interArea / (areaA + areaB - interArea + 1e-6f)
    }

    private fun nonMaxSuppression(boxes: List<Detection>, iouThreshold: Float = 0.50f): List<Detection> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Detection>()
        while (sorted.isNotEmpty()) {
            val a = sorted.removeAt(0)
            keep.add(a)
            val it = sorted.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (iou(a.box, b.box) > iouThreshold) {
                    it.remove()
                }
            }
        }
        return keep
    }

    private fun loadModelFile(filename: String): MappedByteBuffer? {
        return try {
            val afd = assets.openFd(filename)
            FileInputStream(afd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        } catch (e: Exception) {
            Log.e("ScreenRecordService", "❌ Failed to load model file: $filename", e)
            null
        }
    }

    private fun addOverlay() {
        if (overlayAdded) return
        overlayView = OverlayView(this)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager?.addView(overlayView, params)
            overlayAdded = true
            Log.d("ScreenRecordService", "✅ Overlay added successfully")
        } catch (e: Exception) {
            Log.w("ScreenRecordService", "Overlay add failed: ${e.message}")
        }
    }

    private fun removeOverlay() {
        if (!overlayAdded) return
        try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
        overlayView = null
        overlayAdded = false
    }

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startForegroundServiceWithNotification() {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply { action = STOP_RECORDING }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FocusNet Detection Active")
            .setContentText("Tap to stop")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun announceDetections(detections: List<Detection>) {
        if (!isVoiceAlertEnabled || !isTtsReady || detections.isEmpty()) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnnounceTime < announceDebounceMs) return

        val currentHazards = detections.map { it.label }.toSet()
        if (currentHazards == lastAnnouncedHazards) return

        lastAnnouncedHazards = currentHazards
        lastAnnounceTime = currentTime

        val message = when {
            currentHazards.size > 1 -> "Multiple hazards detected"
            currentHazards.size == 1 -> {
                val hazard = currentHazards.first()
                when (hazard) {
                    "pedestrian" -> "Pedestrian ahead"
                    "pothole" -> "Pothole ahead"
                    "humps" -> "Speed hump ahead"
                    "animals" -> "Animal on road"
                    "roadworks" -> "Road work ahead"
                    else -> "$hazard detected"
                }
            }
            else -> return
        }

        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
        Log.d("ScreenRecordService", "🔊 Announced: $message")
    }

    // ✅ FIXED: Overlay now properly scales from capture dimensions to screen
    class OverlayView(ctx: Context) : View(ctx) {

        private val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            style = Paint.Style.FILL
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }

        private val bgPaint = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }

        private var dets: List<Detection> = emptyList()
        private var captureW = 416
        private var captureH = 416

        fun setDetections(list: List<Detection>, capWidth: Int, capHeight: Int) {
            dets = list
            captureW = capWidth
            captureH = capHeight
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // ✅ FIXED: Scale from capture dimensions to actual screen dimensions
            val sx = width.toFloat() / captureW.toFloat()
            val sy = height.toFloat() / captureH.toFloat()

            Log.d("OverlayView", "Drawing ${dets.size} detections, scale=($sx, $sy)")

            for (d in dets) {
                // Draw box
                canvas.drawRect(
                    d.box.left * sx,
                    d.box.top * sy,
                    d.box.right * sx,
                    d.box.bottom * sy,
                    boxPaint
                )

                // Draw label with background
                val label = "${d.label} ${String.format("%.0f%%", d.score * 100)}"
                val textWidth = textPaint.measureText(label)
                val textHeight = textPaint.textSize + 8f

                val rectLeft = d.box.left * sx
                val rectTop = (d.box.top * sy - textHeight).coerceAtLeast(0f)
                val rectRight = (rectLeft + textWidth + 16f).coerceAtMost(width.toFloat())
                val rectBottom = d.box.top * sy

                canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, bgPaint)

                canvas.drawText(
                    label,
                    rectLeft + 8f,
                    rectBottom - 8f,
                    textPaint
                )
            }
        }
    }

    data class Detection(val box: RectF, val label: String, val score: Float)
}

data class PerformanceMetrics(
    val totalDetections: Int = 0,
    val avgConfidence: Float = 0f,
    val processingTimeMs: Float = 0f,
    val fps: Float = 0f,
    val sessionDurationMs: Long = 0L
)

data class HazardDetectionStats(
    val pedestrians: Int = 0,
    val potholes: Int = 0,
    val humps: Int = 0,
    val animals: Int = 0,
    val roadWorks: Int = 0
)