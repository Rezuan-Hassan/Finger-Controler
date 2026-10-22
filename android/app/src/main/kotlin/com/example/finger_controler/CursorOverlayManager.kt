package com.example.finger_controler

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.roundToInt

class CursorOverlayManager private constructor(context: Context) {

    companion object {
        private const val TAG = "CursorOverlayManager"

        @Volatile
        private var instance: CursorOverlayManager? = null

        fun getInstance(context: Context): CursorOverlayManager {
            return instance ?: synchronized(this) {
                instance ?: CursorOverlayManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    private var cursorView: CursorOverlayView? = null
    private var cursorLayoutParams: WindowManager.LayoutParams? = null

    private var diagnosticView: DiagnosticOverlayView? = null
    private var diagnosticLayoutParams: WindowManager.LayoutParams? = null

    private var isCursorAttached = false
    private var isDiagnosticAttached = false

    var isCursorActive = false
        private set

    private var screenWidth = 1080
    private var screenHeight = 2400

    // Velocity-aware 1-Euro adaptive filter
    private val cursorFilter = AdaptiveCursorFilter()

    private var currentFilteredX = 540f
    private var currentFilteredY = 1200f

    init {
        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenDimensions()
    }

    fun updateScreenDimensions() {
        val wm = windowManager ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    fun getScreenDimensions(): Pair<Int, Int> {
        updateScreenDimensions()
        return Pair(screenWidth, screenHeight)
    }

    private fun getOverlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Settings.canDrawOverlays(appContext)) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    fun showCursor() {
        isCursorActive = true
        mainHandler.post {
            try {
                updateScreenDimensions()
                val wm = windowManager ?: return@post
                val density = appContext.resources.displayMetrics.density
                val windowType = getOverlayWindowType()

                // 1. Setup small orange dot cursor view
                if (cursorView == null) {
                    cursorView = CursorOverlayView(appContext)
                }
                val cView = cursorView ?: return@post
                val cursorSize = (56f * density).toInt()

                if (cursorLayoutParams == null) {
                    currentFilteredX = screenWidth / 2f
                    currentFilteredY = screenHeight / 2f

                    cursorLayoutParams = WindowManager.LayoutParams(
                        cursorSize,
                        cursorSize,
                        windowType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = (currentFilteredX - cursorSize / 2f).roundToInt()
                        y = (currentFilteredY - cursorSize / 2f).roundToInt()
                    }
                } else {
                    cursorLayoutParams?.type = windowType
                }

                if (!isCursorAttached) {
                    wm.addView(cView, cursorLayoutParams)
                    isCursorAttached = true
                    Log.d(TAG, "Small orange dot cursor overlay attached")
                }
                cView.animateSpawn()

                // 2. Setup HUD Diagnostics Overlay
                if (FingerControlerConfig.SHOW_DIAGNOSTICS_OVERLAY) {
                    if (diagnosticView == null) {
                        diagnosticView = DiagnosticOverlayView(appContext)
                    }
                    val dView = diagnosticView ?: return@post
                    val diagW = (280f * density).toInt()
                    val diagH = (32f * density).toInt()

                    if (diagnosticLayoutParams == null) {
                        diagnosticLayoutParams = WindowManager.LayoutParams(
                            diagW,
                            diagH,
                            windowType,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT
                        ).apply {
                            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            y = (45f * density).toInt() // Clear status bar notch
                        }
                    } else {
                        diagnosticLayoutParams?.type = windowType
                    }

                    if (!isDiagnosticAttached) {
                        wm.addView(dView, diagnosticLayoutParams)
                        isDiagnosticAttached = true
                        Log.d(TAG, "Diagnostics HUD overlay attached")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show cursor overlay", e)
            }
        }
    }

    fun hideCursor() {
        isCursorActive = false
        mainHandler.post {
            try {
                val wm = windowManager ?: return@post
                if (isCursorAttached && cursorView != null) {
                    wm.removeView(cursorView)
                    isCursorAttached = false
                }
                if (isDiagnosticAttached && diagnosticView != null) {
                    wm.removeView(diagnosticView)
                    isDiagnosticAttached = false
                }
                cursorFilter.reset()
                Log.d(TAG, "Cursor overlays removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide cursor overlay", e)
            }
        }
    }

    fun updatePosition(newRawX: Float, newRawY: Float, timestampMs: Long) {
        if (!isCursorActive) return

        val padding = FingerControlerConfig.CURSOR_SCREEN_PADDING_PX
        val clampedTargetX = newRawX.coerceIn(padding, screenWidth - padding)
        val clampedTargetY = newRawY.coerceIn(padding, screenHeight - padding)

        // Apply 1-Euro velocity-aware filter
        val (filteredX, filteredY) = cursorFilter.filter(clampedTargetX, clampedTargetY, timestampMs)
        currentFilteredX = filteredX.coerceIn(padding, screenWidth - padding)
        currentFilteredY = filteredY.coerceIn(padding, screenHeight - padding)

        mainHandler.post {
            if (!isCursorAttached) {
                showCursor()
                return@post
            }
            val view = cursorView ?: return@post
            val params = cursorLayoutParams ?: return@post
            val wm = windowManager ?: return@post

            val halfWidth = view.measuredWidth / 2f
            val halfHeight = view.measuredHeight / 2f

            params.x = (currentFilteredX - halfWidth).roundToInt()
            params.y = (currentFilteredY - halfHeight).roundToInt()

            try {
                wm.updateViewLayout(view, params)
            } catch (e: Exception) {
                // Layout update ignored during transitions
            }
        }
    }

    fun setPaused(isPaused: Boolean) {
        mainHandler.post {
            cursorView?.setPaused(isPaused)
            diagnosticView?.updateMetrics(30, 0, isPaused, if (isPaused) "PAUSED" else "IDLE")
            diagnosticView?.flashGesture(if (isPaused) "PAUSED ⏸" else "ACTIVE ▶")
        }
    }

    fun resetTrackingFilter() {
        cursorFilter.reset()
    }

    fun animateTap() {
        mainHandler.post {
            cursorView?.animateTap()
            diagnosticView?.flashGesture("CLICK")
        }
    }

    fun animateScroll(scrollUp: Boolean) {
        mainHandler.post {
            cursorView?.animateScroll(scrollUp)
            diagnosticView?.flashGesture(if (scrollUp) "SCROLL ↑" else "SCROLL ↓")
        }
    }

    fun animateMedia() {
        mainHandler.post {
            cursorView?.animateMedia()
            diagnosticView?.flashGesture("MEDIA ⏯")
        }
    }

    fun animateMute() {
        mainHandler.post {
            cursorView?.animateMute()
            diagnosticView?.flashGesture("MUTE 🔇")
        }
    }

    fun animateScreenshot() {
        mainHandler.post {
            cursorView?.animateScreenshot()
            diagnosticView?.flashGesture("SCREENSHOT 📷")
        }
    }

    fun flashGesture(label: String) {
        mainHandler.post {
            diagnosticView?.flashGesture(label)
        }
    }

    fun updateDiagnostics(fps: Int, latencyMs: Long, isPaused: Boolean, stateName: String) {
        if (!FingerControlerConfig.SHOW_DIAGNOSTICS_OVERLAY) return
        mainHandler.post {
            diagnosticView?.updateMetrics(fps, latencyMs, isPaused, stateName)
        }
    }

    fun getCurrentCoordinates(): Pair<Float, Float> {
        return Pair(currentFilteredX, currentFilteredY)
    }
}
