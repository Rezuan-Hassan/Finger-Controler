package com.example.finger_controler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HandTrackingService : Service(), GestureStateMachine.Listener {

    companion object {
        private const val TAG = "HandTrackingService"
        private const val CHANNEL_ID = "finger_controler_tracking_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set
    }

    // Lifecycle owner kept permanently in RESUMED state so background tracking persists
    private val cameraLifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        init {
            registry.currentState = Lifecycle.State.CREATED
        }

        fun start() {
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }

        override val lifecycle: Lifecycle
            get() = registry
    }

    private var handLandmarker: HandLandmarker? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Gesture State Machine
    private val gestureStateMachine = GestureStateMachine(this)

    // Concurrency guard to prioritize the freshest frame and drop queued stale frames
    private val isAnalyzingFrame = AtomicBoolean(false)

    // Strictly monotonic timestamp tracker for MediaPipe LiveStream
    private var lastFrameTimestampMs = 0L

    // Performance metrics
    private var frameCount = 0
    private var currentFps = 30
    private var lastFpsTimestamp = 0L
    private var lastFrameStartTimeMs = 0L

    // Reusable buffers to eliminate per-frame allocations & GC pauses
    private var reusableSourceBitmap: Bitmap? = null
    private var reusableRotatedBitmap: Bitmap? = null
    private var reusableCanvas: Canvas? = null
    private val reusableMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var cleanByteBuffer: ByteBuffer? = null
    private var cleanByteArray: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Starting HandTrackingService")
        isRunning = true

        // Display cursor immediately upon activation
        CursorOverlayManager.getInstance(this).showCursor()

        cameraExecutor = Executors.newSingleThreadExecutor()
        startInForeground()
        initMediaPipe()
        cameraLifecycleOwner.start()
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CursorOverlayManager.getInstance(this).showCursor()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Stopping HandTrackingService")
        isRunning = false

        try {
            cameraLifecycleOwner.stop()
            cameraProvider?.unbindAll()
            handLandmarker?.close()
            cameraExecutor?.shutdown()
            CursorOverlayManager.getInstance(this).hideCursor()
            reusableSourceBitmap?.recycle()
            reusableRotatedBitmap?.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error during HandTrackingService cleanup", e)
        }
    }

    private fun startInForeground() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Finger Controler Active")
            .setContentText("Target 30 FPS gesture tracking running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Finger Controler Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active background camera tracking status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun initMediaPipe() {
        try {
            val optionsBuilder = HandLandmarker.HandLandmarkerOptions.builder()
                .setMinHandDetectionConfidence(FingerControlerConfig.MIN_HAND_DETECTION_CONFIDENCE)
                .setMinTrackingConfidence(FingerControlerConfig.MIN_TRACKING_CONFIDENCE)
                .setMinHandPresenceConfidence(FingerControlerConfig.MIN_HAND_PRESENCE_CONFIDENCE)
                .setNumHands(FingerControlerConfig.MAX_NUM_HANDS)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result: HandLandmarkerResult, _: MPImage ->
                    try {
                        onLandmarksDetected(result)
                    } finally {
                        isAnalyzingFrame.set(false)
                    }
                }
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe error: ${error.message}", error)
                    isAnalyzingFrame.set(false)
                }

            // Attempt 1: Load directly from assets
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build()
                optionsBuilder.setBaseOptions(baseOptions)
                handLandmarker = HandLandmarker.createFromOptions(this, optionsBuilder.build())
                Log.d(TAG, "MediaPipe initialized directly from assets")
                return
            } catch (assetEx: Exception) {
                Log.w(TAG, "Direct asset load failed, trying file copy fallback: ${assetEx.message}")
            }

            // Attempt 2: Fallback to cached file copy
            val cachedModel = File(cacheDir, "hand_landmarker.task")
            if (!cachedModel.exists() || cachedModel.length() == 0L) {
                assets.open("hand_landmarker.task").use { input ->
                    FileOutputStream(cachedModel).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val fallbackBaseOptions = BaseOptions.builder()
                .setModelAssetPath(cachedModel.absolutePath)
                .build()
            optionsBuilder.setBaseOptions(fallbackBaseOptions)
            handLandmarker = HandLandmarker.createFromOptions(this, optionsBuilder.build())
            Log.d(TAG, "MediaPipe initialized from cached file fallback")

        } catch (e: Exception) {
            Log.e(TAG, "Fatal: Failed to initialize MediaPipe HandLandmarker", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CameraProvider", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val executor = cameraExecutor ?: return

        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        val imageAnalysisBuilder = ImageAnalysis.Builder()
            .setTargetResolution(Size(FingerControlerConfig.CAMERA_WIDTH, FingerControlerConfig.CAMERA_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

        // Request 30 FPS target frame rate from Camera2 HAL
        val camera2Interop = Camera2Interop.Extender(imageAnalysisBuilder)
        camera2Interop.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(FingerControlerConfig.TARGET_FPS, FingerControlerConfig.TARGET_FPS)
        )

        val imageAnalysis = imageAnalysisBuilder.build()

        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            processCameraFrame(imageProxy)
        }

        try {
            provider.bindToLifecycle(cameraLifecycleOwner, cameraSelector, imageAnalysis)
            Log.d(TAG, "CameraX successfully bound targeting 30 FPS at ${FingerControlerConfig.CAMERA_WIDTH}x${FingerControlerConfig.CAMERA_HEIGHT}")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }

    private fun processCameraFrame(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        // Drop frame immediately if inference is currently executing to avoid lag accumulation
        if (!isAnalyzingFrame.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        lastFrameStartTimeMs = SystemClock.uptimeMillis()

        try {
            val bitmapToInfer = getProcessedBitmap(imageProxy)
            if (bitmapToInfer != null) {
                val mpImage = BitmapImageBuilder(bitmapToInfer).build()
                val timestampMs = getNextTimestamp()

                // Calculate real-time FPS
                frameCount++
                val now = SystemClock.uptimeMillis()
                if (now - lastFpsTimestamp >= 1000L) {
                    currentFps = frameCount
                    frameCount = 0
                    lastFpsTimestamp = now
                }

                landmarker.detectAsync(mpImage, timestampMs)
            } else {
                isAnalyzingFrame.set(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processCameraFrame", e)
            isAnalyzingFrame.set(false)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Efficient zero-allocation frame copy and rotation using pre-allocated buffers.
     */
    private fun getProcessedBitmap(imageProxy: ImageProxy): Bitmap? {
        val width = imageProxy.width
        val height = imageProxy.height
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        // Ensure source bitmap is allocated once
        var srcBitmap = reusableSourceBitmap
        if (srcBitmap == null || srcBitmap.width != width || srcBitmap.height != height) {
            srcBitmap?.recycle()
            srcBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            reusableSourceBitmap = srcBitmap
        }

        if (rowStride == width * pixelStride) {
            buffer.rewind()
            srcBitmap.copyPixelsFromBuffer(buffer)
        } else {
            // Reusable clean direct buffer
            val bufferSize = width * height * 4
            var cleanBuf = cleanByteBuffer
            if (cleanBuf == null || cleanBuf.capacity() < bufferSize) {
                cleanBuf = ByteBuffer.allocateDirect(bufferSize)
                cleanByteBuffer = cleanBuf
                cleanByteArray = ByteArray(width * 4)
            }
            cleanBuf.rewind()
            val rowBytes = cleanByteArray ?: ByteArray(width * 4).also { cleanByteArray = it }

            buffer.rewind()
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(rowBytes, 0, width * 4)
                cleanBuf.put(rowBytes)
            }
            cleanBuf.rewind()
            srcBitmap.copyPixelsFromBuffer(cleanBuf)
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees == 0) {
            return srcBitmap
        }

        // Rotate using pre-allocated canvas and bitmap
        val isSwapped = rotationDegrees == 90 || rotationDegrees == 270
        val targetWidth = if (isSwapped) height else width
        val targetHeight = if (isSwapped) width else height

        var rotBitmap = reusableRotatedBitmap
        if (rotBitmap == null || rotBitmap.width != targetWidth || rotBitmap.height != targetHeight) {
            rotBitmap?.recycle()
            rotBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            reusableRotatedBitmap = rotBitmap
            reusableCanvas = Canvas(rotBitmap)
        }

        val canvas = reusableCanvas ?: return srcBitmap
        reusableMatrix.reset()
        reusableMatrix.postRotate(rotationDegrees.toFloat(), width / 2f, height / 2f)

        if (isSwapped) {
            val delta = (height - width) / 2f
            reusableMatrix.postTranslate(-delta, delta)
        }

        canvas.drawBitmap(srcBitmap, reusableMatrix, bitmapPaint)
        return rotBitmap
    }

    @Synchronized
    private fun getNextTimestamp(): Long {
        var now = SystemClock.uptimeMillis()
        if (now <= lastFrameTimestampMs) {
            now = lastFrameTimestampMs + 1
        }
        lastFrameTimestampMs = now
        return now
    }

    private fun onLandmarksDetected(result: HandLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        val latencyMs = now - lastFrameStartTimeMs

        val allLandmarks = result.landmarks()
        if (allLandmarks.isEmpty()) {
            gestureStateMachine.handleTrackingLost(now)
            CursorOverlayManager.getInstance(this).resetTrackingFilter()
            CursorOverlayManager.getInstance(this).updateDiagnostics(
                currentFps,
                latencyMs,
                gestureStateMachine.isPaused,
                gestureStateMachine.currentState.name
            )
            return
        }

        val landmarks = allLandmarks[0]
        val (screenWidth, screenHeight) = CursorOverlayManager.getInstance(this).getScreenDimensions()

        gestureStateMachine.processFrame(landmarks, screenWidth, screenHeight)

        CursorOverlayManager.getInstance(this).updateDiagnostics(
            currentFps,
            latencyMs,
            gestureStateMachine.isPaused,
            gestureStateMachine.currentState.name
        )
    }

    // ==========================================
    // GestureStateMachine.Listener Callbacks
    // ==========================================

    override fun onCursorMove(mappedRawX: Float, mappedRawY: Float, timestampMs: Long) {
        CursorOverlayManager.getInstance(this).updatePosition(mappedRawX, mappedRawY, timestampMs)
    }

    override fun onPinchTap(cursorX: Float, cursorY: Float) {
        GestureAccessibilityService.instance?.performTap(cursorX, cursorY)
    }

    override fun onScroll(scrollUp: Boolean, distanceNormalized: Float) {
        val (curX, curY) = CursorOverlayManager.getInstance(this).getCurrentCoordinates()
        GestureAccessibilityService.instance?.performScroll(scrollUp, distanceNormalized, curX, curY)
    }

    override fun onMediaPlayPause() {
        GestureAccessibilityService.instance?.performMediaPlayPause()
    }

    override fun onToggleMute() {
        GestureAccessibilityService.instance?.performToggleMute()
    }

    override fun onScreenshot() {
        GestureAccessibilityService.instance?.performScreenshot()
    }

    override fun onPauseStateChanged(isPaused: Boolean) {
        CursorOverlayManager.getInstance(this).setPaused(isPaused)
    }

    override fun onStateChanged(state: GestureStateMachine.State, gesture: GestureStateMachine.RecognizedGesture) {
        if (gesture != GestureStateMachine.RecognizedGesture.NONE) {
            val label = when (gesture) {
                GestureStateMachine.RecognizedGesture.POINTING -> "POINTING"
                GestureStateMachine.RecognizedGesture.PINCH_TAP -> "CLICK"
                GestureStateMachine.RecognizedGesture.SCROLL_UP -> "SCROLL ↑"
                GestureStateMachine.RecognizedGesture.SCROLL_DOWN -> "SCROLL ↓"
                GestureStateMachine.RecognizedGesture.OPEN_PALM_TOGGLE -> if (gestureStateMachine.isPaused) "PAUSED ⏸" else "ACTIVE ▶"
                GestureStateMachine.RecognizedGesture.THUMBS_UP_MEDIA -> "MEDIA ⏯"
                GestureStateMachine.RecognizedGesture.CLOSED_FIST_MUTE -> "MUTE 🔇"
                GestureStateMachine.RecognizedGesture.THREE_FINGER_SCREENSHOT -> "SCREENSHOT 📷"
                GestureStateMachine.RecognizedGesture.NONE -> ""
            }
            CursorOverlayManager.getInstance(this).flashGesture(label)
        }
    }
}

