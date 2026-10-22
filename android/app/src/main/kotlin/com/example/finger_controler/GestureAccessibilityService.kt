package com.example.finger_controler

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

class GestureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GestureA11yService"
        var instance: GestureAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isScrollDispatching = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "GestureAccessibilityService connected and ready for synthetic events")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Synthetic gesture injection only
    }

    override fun onInterrupt() {
        Log.d(TAG, "GestureAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "GestureAccessibilityService destroyed")
    }

    /**
     * Injects a precise synthetic click at (tapX, tapY).
     */
    fun performTap(tapX: Float, tapY: Float) {
        CursorOverlayManager.getInstance(this).animateTap()

        val clickPath = Path().apply {
            moveTo(tapX, tapY)
        }

        // 45ms duration simulates an instant, crisp physical tap
        val stroke = GestureDescription.StrokeDescription(clickPath, 0, 45)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap dispatched successfully at ($tapX, $tapY)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap dispatch cancelled at ($tapX, $tapY)")
            }
        }, null)
    }

    /**
     * Injects a smooth, continuous vertical scroll gesture at the current cursor location.
     * scrollUp: true = fingers moved upward (mouse wheel up equivalent)
     *           false = fingers moved downward (mouse wheel down equivalent)
     */
    fun performScroll(scrollUp: Boolean, distanceNormalized: Float, cursorX: Float, cursorY: Float) {
        if (isScrollDispatching) return

        CursorOverlayManager.getInstance(this).animateScroll(scrollUp)

        val (screenWidth, screenHeight) = CursorOverlayManager.getInstance(this).getScreenDimensions()
        val marginX = screenWidth * 0.15f
        val marginY = screenHeight * 0.15f
        val startX = cursorX.coerceIn(marginX, screenWidth - marginX)
        val centerY = cursorY.coerceIn(marginY, screenHeight - marginY)

        // Calculate dynamic stroke distance based on hand displacement & sensitivity
        val baseDistance = screenHeight * 0.16f * FingerControlerConfig.SCROLL_SENSITIVITY
        val displacementScale = (distanceNormalized / FingerControlerConfig.SCROLL_MIN_DISPLACEMENT_NORMALIZED).coerceIn(1.0f, 2.5f)
        val totalDistance = baseDistance * displacementScale

        // Direction mapping:
        // By default on touch screen, rolling wheel upward / scrolling upward corresponds to dragging downward.
        // If SCROLL_INVERT_DIRECTION is true, dragging upward is performed instead.
        val isDragUp = if (FingerControlerConfig.SCROLL_INVERT_DIRECTION) scrollUp else !scrollUp

        val startY = if (isDragUp) centerY + (totalDistance / 2f) else centerY - (totalDistance / 2f)
        val endY = if (isDragUp) startY - totalDistance else startY + totalDistance

        val scrollPath = Path().apply {
            moveTo(startX, startY.coerceIn(12f, screenHeight - 12f))
            lineTo(startX, endY.coerceIn(12f, screenHeight - 12f))
        }

        val stroke = GestureDescription.StrokeDescription(scrollPath, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        isScrollDispatching = true
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                isScrollDispatching = false
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                isScrollDispatching = false
            }
        }, null)
    }

    /**
     * Triggers the operating system's media play/pause command via AudioManager key event.
     */
    fun performMediaPlayPause() {
        CursorOverlayManager.getInstance(this).animateMedia()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            audioManager.dispatchMediaKeyEvent(eventDown)
            audioManager.dispatchMediaKeyEvent(eventUp)
            Log.d(TAG, "Media play/pause command dispatched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch media play/pause", e)
        }
    }

    /**
     * Toggles system audio mute/unmute via AudioManager.
     */
    fun performToggleMute() {
        CursorOverlayManager.getInstance(this).animateMute()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            } else {
                @Suppress("DEPRECATION")
                audioManager.adjustVolume(
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            Log.d(TAG, "System mute toggle dispatched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle system mute", e)
        }
    }

    /**
     * Captures a system screenshot using the platform's global accessibility action.
     */
    fun performScreenshot() {
        CursorOverlayManager.getInstance(this).animateScreenshot()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val success = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            Log.d(TAG, "System screenshot dispatched: success=$success")
        } else {
            Log.w(TAG, "System screenshot global action requires Android 9 (API 28) or higher")
        }
    }
}
