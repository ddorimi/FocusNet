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
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScreenRecordService : Service() {

    companion object {
        const val CHANNEL_ID = "record_channel"
        const val NOTIF_ID = 1001
        const val START_RECORDING = "ACTION_START_RECORDING"
        const val STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val KEY_RECORDING_CONFIG = "KEY_RECORDING_CONFIG"

        const val MODEL_INPUT_SIZE = 640
        const val MODEL_OUTPUT_BOXES = 8400

        val isServiceRunning = MutableStateFlow(false)

        // ✨ NEW: Performance tracking StateFlows for Dev Mode
        private val _performanceMetrics = MutableStateFlow(PerformanceMetrics())
        val performanceMetrics: StateFlow<PerformanceMetrics> = _performanceMetrics.asStateFlow()

        private val _hazardStats = MutableStateFlow(HazardDetectionStats())
        val hazardStats: StateFlow<HazardDetectionStats> = _hazardStats.asStateFlow()

        private val _recentDetections = MutableStateFlow<List<Detection>>(emptyList())
        val recentDetections: StateFlow<List<Detection>> = _recentDetections.asStateFlow()
    }

    // ✨ NEW: Performance tracking variables
    private var sessionStartTime = 0L
    private var totalDetections = 0
    private var confidenceSum = 0f
    private var frameCount = 0
    private var lastFrameTime = 0L
    private var processingTimes = mutableListOf<Float>()

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

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        try {
            loadModelFile("best_integer_quant.tflite")?.let { buffer ->
                val options = Interpreter.Options().apply { setNumThreads(4) }
                tflite = Interpreter(buffer, options)
                Log.d("ScreenRecordService", "✅ Model loaded successfully.")
            }
        } catch (e: Exception) {
            Log.w("ScreenRecordService", "❌ Model load failed: ${e.message}")
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START_RECORDING -> {
                val config = intent.getParcelableExtra<ScreenRecordConfig>(KEY_RECORDING_CONFIG)
                if (config != null) {
                    if (!canDrawOverlays()) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    startForegroundServiceWithNotification()
                    startProjectionAndDetection(config)
                    isServiceRunning.value = true
                }
            }
            STOP_RECORDING -> {
                stopProjectionAndDetection()
                stopForeground(true)
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
        coroutineScope.cancel()
        isServiceRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProjectionAndDetection(config: ScreenRecordConfig) {
        stopProjectionAndDetection()

        // Reset performance metrics when starting detection
        sessionStartTime = System.currentTimeMillis()
        totalDetections = 0
        confidenceSum = 0f
        frameCount = 0
        lastFrameTime = sessionStartTime
        processingTimes.clear()
        _performanceMetrics.value = PerformanceMetrics()
        _hazardStats.value = HazardDetectionStats()
        _recentDetections.value = emptyList()

        mediaProjection = projectionManager?.getMediaProjection(config.resultCode, config.data)
        val width = MODEL_INPUT_SIZE
        val height = MODEL_INPUT_SIZE

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.w("ScreenRecordService", "⚠️ MediaProjection stopped by system.")
                stopProjectionAndDetection()
            }
        }
        mediaProjection?.registerCallback(projectionCallback!!, null)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DetectionDisplay",
            width, height,
            resources.displayMetrics.densityDpi,
            0,
            imageReader?.surface, null, null
        )

        addOverlay()

        detectionJob = coroutineScope.launch {
            val reader = imageReader!!
            while (isActive && mediaProjection != null) {
                val frameStartTime = System.currentTimeMillis() // ✨ Track processing time

                val img = tryAcquire(reader)
                if (img != null) {
                    val bmp = imageToBitmap(img)
                    img.close()
                    val input = prepareInput(bmp)
                    val output = runModel(input)
                    val dets = parseModelOutput(output, bmp.width, bmp.height)

                    // Update performance metrics with real detection data
                    updatePerformanceMetrics(dets, frameStartTime)

                    withContext(Dispatchers.Main) {
                        overlayView?.setDetections(dets)
                    }

                    // Update recent detections for real-time Dev Mode display
                    _recentDetections.value = dets
                }
                delay(200)
            }
        }
    }

    // Method to track real-time performance metrics
    private fun updatePerformanceMetrics(detections: List<Detection>, frameStartTime: Long) {
        val processingTime = (System.currentTimeMillis() - frameStartTime).toFloat()
        val currentTime = System.currentTimeMillis()

        frameCount++
        totalDetections += detections.size

        // Track processing times (keep last 10 for rolling average)
        processingTimes.add(processingTime)
        if (processingTimes.size > 10) {
            processingTimes.removeAt(0)
        }

        // Calculate average confidence from actual detections
        detections.forEach { detection ->
            confidenceSum += detection.score
        }

        // Calculate real FPS based on session data
        val sessionDuration = currentTime - sessionStartTime
        val currentFps = if (sessionDuration > 0) (frameCount * 1000f) / sessionDuration else 0f

        // Update performance metrics with real data
        val avgConfidence = if (totalDetections > 0) confidenceSum / totalDetections else 0f
        val avgProcessingTime = if (processingTimes.isNotEmpty()) processingTimes.average().toFloat() else 0f

        _performanceMetrics.value = PerformanceMetrics(
            totalDetections = totalDetections,
            avgConfidence = avgConfidence,
            processingTimeMs = avgProcessingTime,
            fps = currentFps,
            sessionDurationMs = sessionDuration
        )

        // Update hazard statistics with actual detection counts
        updateHazardStats(detections)
    }

    // Method to track hazard type statistics
    private fun updateHazardStats(detections: List<Detection>) {
        val currentStats = _hazardStats.value
        var pedestrians = currentStats.pedestrians
        var potholes = currentStats.potholes
        var animals = currentStats.animals
        var roadWorks = currentStats.roadWorks

        // Count detections by your model's label types
        detections.forEach { detection ->
            when (detection.label) {
                "pedestrian" -> pedestrians++
                "pothole" -> potholes++
                "animals/humps" -> animals++
                "roadworks" -> roadWorks++
            }
        }

        _hazardStats.value = HazardDetectionStats(
            pedestrians = pedestrians,
            potholes = potholes,
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

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
    }

    private fun prepareInput(bitmap: Bitmap): Array<Any> {
        val inputBmp = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
        val input = Array(1) { Array(MODEL_INPUT_SIZE) { Array(MODEL_INPUT_SIZE) { FloatArray(3) } } }
        for (y in 0 until MODEL_INPUT_SIZE) for (x in 0 until MODEL_INPUT_SIZE) {
            val px = inputBmp.getPixel(x, y)
            input[0][y][x][0] = ((px shr 16 and 0xFF) / 255f)
            input[0][y][x][1] = ((px shr 8 and 0xFF) / 255f)
            input[0][y][x][2] = ((px and 0xFF) / 255f)
        }
        return arrayOf(input)
    }

    private fun runModel(input: Array<Any>): Array<Array<FloatArray>> {
        val output = Array(1) { Array(9) { FloatArray(MODEL_OUTPUT_BOXES) } }
        try {
            tflite?.run(input[0], output)
            Log.d("ScreenRecordService", "Output[0][0][0..5]: ${output[0][0].take(6)}")
        } catch (e: Exception) {
            Log.w("ScreenRecordService", "Inference failed: ${e.message}")
        }
        return output
    }

    private fun parseModelOutput(
        output: Array<Array<FloatArray>>,
        imageW: Int,
        imageH: Int,
        confThreshold: Float = 0.3f,
        iouThreshold: Float = 0.45f
    ): List<Detection> {
        val preds = output[0]
        val numBoxes = preds[0].size
        val rawDetections = ArrayList<Detection>(64)
        val labelNames = arrayOf("animals/humps", "pedestrian", "pothole", "roadworks")

        for (i in 0 until numBoxes) {
            val xc = preds[0][i]
            val yc = preds[1][i]
            val bw = preds[2][i]
            val bh = preds[3][i]
            val obj = sigmoid(preds[4][i])
            if (obj < 0.03f) continue

            val classCount = 4
            val classProbs = FloatArray(classCount)
            for (c in 0 until classCount) {
                classProbs[c] = sigmoid(preds[5 + c][i])
            }

            var bestClass = 0
            var bestClassProb = classProbs[0]
            for (c in 1 until classCount) {
                if (classProbs[c] > bestClassProb) {
                    bestClassProb = classProbs[c]
                    bestClass = c
                }
            }

            val combinedConf = obj * bestClassProb
            if (combinedConf < confThreshold) continue

            val left = (xc - bw / 2f) * imageW
            val top = (yc - bh / 2f) * imageH
            val right = (xc + bw / 2f) * imageW
            val bottom = (yc + bh / 2f) * imageH

            val l = left.coerceIn(0f, imageW.toFloat())
            val t = top.coerceIn(0f, imageH.toFloat())
            val r = right.coerceIn(0f, imageW.toFloat())
            val b = bottom.coerceIn(0f, imageH.toFloat())

            val label = labelNames.getOrNull(bestClass) ?: "cls$bestClass"
            rawDetections.add(Detection(RectF(l, t, r, b), label, combinedConf))
        }

        Log.d("ScreenRecordService", "Raw detections before NMS: ${rawDetections.size}")
        val final = nonMaxSuppression(rawDetections, iouThreshold)
        Log.d("ScreenRecordService", "Detections after NMS: ${final.size}")
        return final
    }

    private fun sigmoid(x: Float): Float = (1f / (1f + kotlin.math.exp(-x)))

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = interRight - interLeft
        val interH = interBottom - interTop
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
            val a = sorted.removeAt(0)
            keep.add(a)
            val it = sorted.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (iou(a.box, b.box) > iouThreshold) it.remove()
            }
        }
        return keep
    }

    private fun loadModelFile(filename: String): MappedByteBuffer? {
        return try {
            val afd = assets.openFd(filename)
            FileInputStream(afd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        } catch (e: Exception) { null }
    }

    private fun addOverlay() {
        if (overlayAdded) return
        overlayView = OverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager?.addView(overlayView, params)
            overlayAdded = true
        } catch (e: Exception) {
            Log.w("ScreenRecordService", "Overlay add failed: ${e.message}")
        }
    }

    private fun removeOverlay() {
        if (!overlayAdded) return
        try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
        overlayView = null; overlayAdded = false
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
            .setContentTitle("Screen recording with detection")
            .setContentText("Tap to stop")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    class OverlayView(ctx: Context) : View(ctx) {
        private val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        private val textPaint = Paint().apply {
            color = Color.RED
            textSize = 42f
            style = Paint.Style.FILL
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        private var dets: List<Detection> = emptyList()
        private var lastDets: List<Detection> = emptyList()
        private var frameCount = 0

        fun setDetections(list: List<Detection>) {
            frameCount++
            if (frameCount % 3 == 0 || list.isNotEmpty()) {
                lastDets = list
            }
            dets = lastDets
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sx = width / MODEL_INPUT_SIZE.toFloat()
            val sy = height / MODEL_INPUT_SIZE.toFloat()

            for (d in dets) {
                canvas.drawRect(
                    d.box.left * sx,
                    d.box.top * sy,
                    d.box.right * sx,
                    d.box.bottom * sy,
                    boxPaint
                )

                val label = "${d.label} (${String.format("%.2f", d.score)})"
                val textWidth = textPaint.measureText(label)
                val textHeight = textPaint.textSize + 8f

                val rectLeft = d.box.left * sx
                val rectTop = d.box.top * sy - textHeight
                val rectRight = rectLeft + textWidth + 16f
                val rectBottom = d.box.top * sy

                val bgPaint = Paint().apply {
                    color = Color.argb(160, 0, 0, 0)
                    style = Paint.Style.FILL
                }

                canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, bgPaint)
                canvas.drawText(label, rectLeft + 8f, rectBottom - 8f, textPaint)
            }
        }
    }

    data class Detection(val box: RectF, val label: String, val score: Float)

    // ✨ NEW: Data classes for performance tracking
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
        val animals: Int = 0,
        val roadWorks: Int = 0
    )
}