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

    private val CAPTURE_SIZE = 416

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
    private var currentModelType: ModelType = ModelType.FOCUSNET
    private var isVoiceAlertEnabled: Boolean = true
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var lastAnnouncedHazards = setOf<String>()
    private var lastAnnounceTime = 0L
    private val announceDebounceMs = 3000L

    private var letterboxOffsetX = 0f
    private var letterboxOffsetY = 0f
    private var letterboxScale = 1f

    private var reusableBitmap: Bitmap? = null
    private var userConfidenceThreshold = 0.45f

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isTtsReady = true
                Log.d("FocusNet", "✅ TTS initialized")
            } else {
                Log.w("FocusNet", "⚠️ TTS failed")
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
                        Log.e("FocusNet", "❌ No overlay permission")
                        stopSelf()
                        return START_NOT_STICKY
                    }

                    isVoiceAlertEnabled = config.isVoiceAlertEnabled
                    userConfidenceThreshold = config.confidenceThreshold
                    currentModelType = config.modelType

                    Log.d("FocusNet", "=".repeat(70))
                    Log.d("FocusNet", "🎯 Model: ${config.modelType.name}")
                    Log.d("FocusNet", "🎯 File: ${config.modelFileName}")
                    Log.d("FocusNet", "🎯 Confidence: ${(userConfidenceThreshold * 100).toInt()}%")
                    Log.d("FocusNet", "🔊 Voice: ${if (isVoiceAlertEnabled) "ON" else "OFF"}")
                    Log.d("FocusNet", "=".repeat(70))

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

                Log.d("FocusNet", "📐 Input: ${inputTensor?.shape()?.contentToString()}")
                Log.d("FocusNet", "📐 Output: ${outputTensor?.shape()?.contentToString()}")
                Log.d("FocusNet", "✅ Model loaded: $modelFileName")
            } ?: run {
                Log.e("FocusNet", "❌ Model file not found: $modelFileName")
            }
        } catch (e: Exception) {
            Log.e("FocusNet", "❌ Model load error: ${e.message}", e)
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

        Log.d("FocusNet", "📷 Capture: ${CAPTURE_SIZE}x${CAPTURE_SIZE} (square)")

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.w("FocusNet", "⚠️ MediaProjection stopped")
                stopProjectionAndDetection()
            }
        }
        mediaProjection?.registerCallback(projectionCallback!!, null)

        imageReader = ImageReader.newInstance(CAPTURE_SIZE, CAPTURE_SIZE, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FocusNetDisplay",
            CAPTURE_SIZE,
            CAPTURE_SIZE,
            resources.displayMetrics.densityDpi,
            0,
            imageReader?.surface,
            null,
            null
        )

        addOverlay()

        detectionJob = coroutineScope.launch {
            val reader = imageReader!!
            while (isActive && mediaProjection != null) {
                val frameStart = System.currentTimeMillis()

                val img = tryAcquire(reader)
                if (img != null) {
                    val bmp = imageToBitmapOptimized(img)
                    img.close()

                    val input = prepareInput(bmp)
                    val output = runModel(input)
                    val dets = parseModelOutput(output, bmp.width, bmp.height)

                    updatePerformanceMetrics(dets, frameStart)
                    announceDetections(dets)

                    withContext(Dispatchers.Main) {
                        overlayView?.setDetections(dets, CAPTURE_SIZE)
                    }

                    _recentDetections.value = dets
                }

                val elapsed = System.currentTimeMillis() - frameStart
                val delay = if (elapsed < 50) 60L else 80L
                delay(delay)
            }
        }
    }

    private fun updatePerformanceMetrics(detections: List<Detection>, frameStart: Long) {
        val processingTime = (System.currentTimeMillis() - frameStart).toFloat()

        frameCount++
        totalDetections += detections.size

        processingTimes.addLast(processingTime)
        if (processingTimes.size > 10) processingTimes.removeFirst()

        detections.forEach { confidenceSum += it.score }

        val duration = System.currentTimeMillis() - sessionStartTime
        val fps = if (duration > 0) (frameCount * 1000f) / duration else 0f
        val avgConf = if (totalDetections > 0) confidenceSum / totalDetections else 0f
        val avgTime = if (processingTimes.isNotEmpty()) processingTimes.average().toFloat() else 0f

        _performanceMetrics.value = PerformanceMetrics(
            totalDetections = totalDetections,
            avgConfidence = avgConf,
            processingTimeMs = avgTime,
            fps = fps,
            sessionDurationMs = duration
        )

        updateHazardStats(detections)
    }

    private fun updateHazardStats(detections: List<Detection>) {
        val current = _hazardStats.value
        var ped = current.pedestrians
        var pot = current.potholes
        var hum = current.humps
        var ani = current.animals
        var road = current.roadWorks

        detections.forEach {
            when (it.label) {
                "pedestrian" -> ped++
                "pothole" -> pot++
                "humps" -> hum++
                "animals" -> ani++
                "roadworks" -> road++
            }
        }

        _hazardStats.value = HazardDetectionStats(ped, pot, hum, ani, road)
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
        try { reader.acquireLatestImage() } catch (_: Exception) { null }

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

    private fun prepareInput(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val modelSize = 416
        val processed = letterboxResize(bitmap, modelSize, modelSize)
        val input = Array(1) { Array(modelSize) { Array(modelSize) { FloatArray(3) } } }

        for (y in 0 until modelSize) {
            for (x in 0 until modelSize) {
                val px = processed.getPixel(x, y)
                input[0][y][x][0] = ((px shr 16 and 0xFF) / 255f)
                input[0][y][x][1] = ((px shr 8 and 0xFF) / 255f)
                input[0][y][x][2] = ((px and 0xFF) / 255f)
            }
        }

        processed.recycle()
        return input
    }

    private fun letterboxResize(bitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcW = bitmap.width
        val srcH = bitmap.height

        val scale = minOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)

        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val left = (targetW - scaledW) / 2f
        val top = (targetH - scaledH) / 2f
        canvas.drawBitmap(scaled, left, top, null)

        letterboxOffsetX = left
        letterboxOffsetY = top
        letterboxScale = scale

        scaled.recycle()
        return result
    }

    private fun runModel(input: Array<Array<Array<FloatArray>>>): Array<Array<FloatArray>> {
        val output = Array(1) { Array(9) { FloatArray(3549) } }
        try {
            tflite?.run(input, output)
        } catch (e: Exception) {
            Log.e("FocusNet", "❌ Inference error: ${e.message}", e)
        }
        return output
    }

    private fun parseModelOutput(
        output: Array<Array<FloatArray>>,
        imageW: Int,
        imageH: Int
    ): List<Detection> {

        val confThreshold = userConfidenceThreshold
        val iouThreshold = 0.45f
        val preds = output[0]
        val numBoxes = 3549
        val rawDetections = ArrayList<Detection>(64)
        val classCount = 5
        val modelSize = 416f

        for (i in 0 until numBoxes) {
            val xc_norm = preds[0][i]
            val yc_norm = preds[1][i]
            val w_norm = preds[2][i]
            val h_norm = preds[3][i]

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

            val xc_model = xc_norm * modelSize
            val yc_model = yc_norm * modelSize
            val w_model = w_norm * modelSize
            val h_model = h_norm * modelSize

            val xc_unpadded = xc_model - letterboxOffsetX
            val yc_unpadded = yc_model - letterboxOffsetY

            val xc_original = xc_unpadded / letterboxScale
            val yc_original = yc_unpadded / letterboxScale
            val w_original = w_model / letterboxScale
            val h_original = h_model / letterboxScale

            val left = (xc_original - w_original / 2f).coerceIn(0f, imageW.toFloat())
            val top = (yc_original - h_original / 2f).coerceIn(0f, imageH.toFloat())
            val right = (xc_original + w_original / 2f).coerceIn(0f, imageW.toFloat())
            val bottom = (yc_original + h_original / 2f).coerceIn(0f, imageH.toFloat())

            val boxWidth = right - left
            val boxHeight = bottom - top

            if (boxWidth < 20f || boxHeight < 20f) continue
            if (boxWidth > imageW * 0.85f || boxHeight > imageH * 0.85f) continue

            val aspectRatio = boxWidth / boxHeight
            if (aspectRatio < 0.15f || aspectRatio > 6.0f) continue

            val labelNames = arrayOf("animals", "humps", "pedestrian", "pothole", "roadworks")
            val label = labelNames.getOrNull(bestClass) ?: "unknown"

            rawDetections.add(Detection(RectF(left, top, right, bottom), label, bestScore))
        }

        val final = nonMaxSuppression(rawDetections, iouThreshold)

        if (final.isNotEmpty()) {
            Log.d("FocusNet", "✅ ${final.size} detections @ ${(confThreshold * 100).toInt()}% conf")
        }

        return final
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)

        if (interW <= 0f || interH <= 0f) return 0f

        val interArea = interW * interH
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)

        return interArea / (areaA + areaB - interArea + 1e-6f)
    }

    private fun nonMaxSuppression(boxes: List<Detection>, iouThreshold: Float): List<Detection> {
        if (boxes.isEmpty()) return emptyList()

        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)

            val it = sorted.iterator()
            while (it.hasNext()) {
                val current = it.next()
                if (iou(best.box, current.box) > iouThreshold) {
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
            Log.e("FocusNet", "❌ Model file error: $filename", e)
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
            Log.d("FocusNet", "✅ Overlay added")
        } catch (e: Exception) {
            Log.e("FocusNet", "❌ Overlay error: ${e.message}")
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
                NotificationChannel(CHANNEL_ID, "FocusNet Detection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startForegroundServiceWithNotification() {
        val modelName = when (currentModelType) {
            ModelType.FOCUSNET -> "FocusNet"
            ModelType.BASELINE -> "Baseline"
        }

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply { action = STOP_RECORDING }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$modelName Active")
            .setContentText("Conf: ${(userConfidenceThreshold * 100).toInt()}%")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    private fun announceDetections(detections: List<Detection>) {
        if (!isVoiceAlertEnabled || !isTtsReady || detections.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastAnnounceTime < announceDebounceMs) return

        val hazards = detections.map { it.label }.toSet()
        if (hazards == lastAnnouncedHazards) return

        lastAnnouncedHazards = hazards
        lastAnnounceTime = now

        val msg = when {
            hazards.size > 1 -> "Multiple hazards"
            hazards.size == 1 -> when (hazards.first()) {
                "pedestrian" -> "Pedestrian ahead"
                "pothole" -> "Pothole ahead"
                "humps" -> "Speed hump"
                "animals" -> "Animal on road"
                "roadworks" -> "Road work"
                else -> "Hazard detected"
            }
            else -> return
        }

        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    class OverlayView(ctx: Context) : View(ctx) {

        private val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 48f
            style = Paint.Style.FILL
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }

        private val bgPaint = Paint().apply {
            color = Color.argb(220, 0, 0, 0)
            style = Paint.Style.FILL
        }

        private var dets: List<Detection> = emptyList()
        private var captureSize = 416

        fun setDetections(list: List<Detection>, capSize: Int) {
            dets = list
            captureSize = capSize
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (dets.isEmpty() || width == 0 || height == 0) return

            val scaleX = width.toFloat() / captureSize
            val scaleY = height.toFloat() / captureSize

            for (det in dets) {
                // Scale coordinates
                val l = det.box.left * scaleX
                val t = det.box.top * scaleY
                val r = det.box.right * scaleX
                val b = det.box.bottom * scaleY

                // Draw box
                canvas.drawRect(l, t, r, b, boxPaint)

                // Draw label
                val label = "${det.label} ${(det.score * 100).toInt()}%"
                val textW = textPaint.measureText(label)
                val textH = textPaint.textSize + 16f

                val labelTop = (t - textH).coerceAtLeast(0f)
                val labelBottom = t
                val labelRight = (l + textW + 24f).coerceAtMost(width.toFloat())

                canvas.drawRect(l, labelTop, labelRight, labelBottom, bgPaint)
                canvas.drawText(label, l + 12f, labelBottom - 12f, textPaint)
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