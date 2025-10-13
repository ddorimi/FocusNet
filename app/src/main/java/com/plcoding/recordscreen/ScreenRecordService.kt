package com.plcoding.recordscreen

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableStateFlow
import androidx.lifecycle.asStateFlow
import kotlinx.coroutines.*
import org.pytorch.*
import org.pytorch.torchvision.TensorImageUtils
import java.nio.FloatBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * ScreenRecordService
 * Handles screen capture, ML inference (PyTorch .ptl model),
 * and drawing hazard overlays on top of the screen.
 */
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

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- Live data for DevMode screen ---
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
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START_RECORDING -> startRecording(intent)
            STOP_RECORDING -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (_isServiceRunning.value) return

        config = intent.getParcelableExtra(KEY_RECORDING_CONFIG) ?: return

        startForeground(NOTIF_ID, buildNotification("Initializing detection..."))
        _isServiceRunning.value = true

        loadModel(config.modelFileName)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = projectionManager.getMediaProjection(config.resultCode, config.data)

        startImageReader()
        startOverlay()
        startInferenceLoop()
    }

    private fun stopRecording() {
        _isServiceRunning.value = false
        inferenceJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        overlayView?.remove()
        projection?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        scope.cancel()
    }

    // ---------------- MODEL ---------------- //

    private fun loadModel(modelFile: String) {
        try {
            val filePath = assetFilePath(this, modelFile)
            model = LiteModuleLoader.load(filePath)
            Log.d("FocusNet", "Loaded model: $modelFile")
        } catch (e: Exception) {
            Log.e("FocusNet", "Error loading model", e)
        }
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) return file.absolutePath

        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }

    // ---------------- CAPTURE + INFERENCE ---------------- //

    private fun startImageReader() {
        val width = Resources.getSystem().displayMetrics.widthPixels
        val height = Resources.getSystem().displayMetrics.heightPixels
        val density = Resources.getSystem().displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun startInferenceLoop() {
        val reader = imageReader ?: return
        val threshold = confidenceThreshold.value

        inferenceJob = scope.launch {
            while (_isServiceRunning.value) {
                val image = reader.acquireLatestImage() ?: continue

                val bitmap = Bitmap.createBitmap(
                    image.width, image.height, Bitmap.Config.ARGB_8888
                )
                image.use {
                    val plane = it.planes[0]
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                }

                val resized = Bitmap.createScaledBitmap(bitmap, 320, 320, true)
                val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                    resized,
                    TensorImageUtils.TORCHVISION_NORM_MEAN_RGB,
                    TensorImageUtils.TORCHVISION_NORM_STD_RGB
                )

                val start = System.nanoTime()
                val output = model?.forward(IValue.from(inputTensor))?.toTensor()
                val inferenceMs = (System.nanoTime() - start) / 1_000_000f

                // Dummy output parser (replace with real SSD output decoding)
                val detections = DummyDetectionParser.parse(output, threshold)

                withContext(Dispatchers.Main) {
                    overlayView?.updateDetections(detections)
                    _recentDetections.value = detections.takeLast(10)
                    _performanceMetrics.value = _performanceMetrics.value.copy(
                        fps = 1000f / max(inferenceMs, 1f),
                        processingTimeMs = inferenceMs,
                        totalDetections = _performanceMetrics.value.totalDetections + detections.size
                    )
                    announceHazards(detections)
                    updateHazardStats(detections)
                }
            }
        }
    }

    private fun announceHazards(detections: List<Detection>) {
        if (!config.isVoiceAlertEnabled || !ttsReady) return
        val now = System.currentTimeMillis()

        detections.firstOrNull()?.let { det ->
            if (det.label != lastSpokenLabel || now - lastSpokenTime > 4000) {
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

    // ---------------- OVERLAY ---------------- //

    private fun startOverlay() {
        if (overlayView != null) return
        overlayView = OverlayView(this)
        overlayView?.show()
    }

    // ---------------- NOTIFICATION ---------------- //

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
            .setSmallIcon(R.drawable.fnlogo)
            .addAction(R.drawable.ic_stop, "Stop", pendingStop)
            .setOngoing(true)
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

    // ---------------- TTS ---------------- //

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        }
    }
}

// ---------------- DATA CLASSES ---------------- //

data class ScreenRecordConfig(
    val resultCode: Int,
    val data: Intent,
    val modelFileName: String,
    val isVoiceAlertEnabled: Boolean
) : Parcelable {
    constructor(parcel: android.os.Parcel) : this(
        parcel.readInt(),
        parcel.readParcelable(Intent::class.java.classLoader)!!,
        parcel.readString()!!,
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: android.os.Parcel, flags: Int) {
        parcel.writeInt(resultCode)
        parcel.writeParcelable(data, flags)
        parcel.writeString(modelFileName)
        parcel.writeByte(if (isVoiceAlertEnabled) 1 else 0)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<ScreenRecordConfig> {
        override fun createFromParcel(parcel: android.os.Parcel) = ScreenRecordConfig(parcel)
        override fun newArray(size: Int): Array<ScreenRecordConfig?> = arrayOfNulls(size)
    }
}

data class Detection(val label: String, val score: Float, val rect: RectF)

data class PerformanceMetrics(
    val fps: Float = 0f,
    val processingTimeMs: Float = 0f,
    val totalDetections: Int = 0
)

data class HazardStats(
    val pedestrians: Int = 0,
    val potholes: Int = 0,
    val humps: Int = 0,
    val animals: Int = 0,
    val roadWorks: Int = 0
)

// Dummy detection parser placeholder
object DummyDetectionParser {
    fun parse(output: Tensor?, threshold: Float): List<Detection> {
        // Replace this with actual SSD output parsing
        return emptyList()
    }
}

// Simple overlay drawing detected hazards
class OverlayView(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var view: SurfaceView? = null
    private var detections: List<Detection> = emptyList()

    fun show() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view = SurfaceView(context).apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(view, params)
    }

    fun updateDetections(newDetections: List<Detection>) {
        detections = newDetections
        draw()
    }

    private fun draw() {
        val canvas = view?.holder?.lockCanvas() ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val paint = Paint().apply {
            color = Color.RED
            strokeWidth = 6f
            style = Paint.Style.STROKE
            textSize = 40f
        }
        detections.forEach {
            canvas.drawRect(it.rect, paint)
            canvas.drawText("${it.label} ${(it.score * 100).toInt()}%", it.rect.left, it.rect.top - 10, paint)
        }
        view?.holder?.unlockCanvasAndPost(canvas)
    }

    fun remove() {
        windowManager?.removeView(view)
        view = null
    }
}
