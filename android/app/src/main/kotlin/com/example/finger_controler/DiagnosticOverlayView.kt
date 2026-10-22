package com.example.finger_controler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Diagnostic and visual feedback overlay showing:
 * - FPS and End-to-End Processing Latency
 * - System State (ACTIVE / PAUSED)
 * - Currently recognized gesture (POINTING, CLICK, SCROLL ↑/↓, MEDIA, MUTE, SCREENSHOT)
 */
class DiagnosticOverlayView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    private var fps: Int = 30
    private var latencyMs: Long = 0L
    private var isPaused: Boolean = false
    private var currentStateName: String = "IDLE"
    private var lastGestureName: String = "NONE"
    private var gestureFlashUntil: Long = 0L

    private val bgRect = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E61E1D1B") // Sleek translucent dark surface
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FF6F00")
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }

    private val statusIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(FingerControlerConfig.CURSOR_COLOR_ACTIVE)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEAE4")
        textSize = 11.5f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val accentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(FingerControlerConfig.CURSOR_COLOR_ACTIVE)
        textSize = 11.5f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (280f * density).toInt()
        val height = (32f * density).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = 16f * density

        bgRect.set(1f, 1f, w - 1f, h - 1f)

        // Draw pill background
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        // Border color accentuates active/paused state
        borderPaint.color = if (isPaused) {
            Color.parseColor("#33888888")
        } else {
            Color.parseColor("#66FF6F00")
        }
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, borderPaint)

        // Status indicator dot
        val indicatorX = 14f * density
        val indicatorY = h / 2f
        statusIndicatorPaint.color = if (isPaused) {
            Color.parseColor(FingerControlerConfig.CURSOR_COLOR_PAUSED)
        } else {
            Color.parseColor(FingerControlerConfig.CURSOR_COLOR_ACTIVE)
        }
        canvas.drawCircle(indicatorX, indicatorY, 3.5f * density, statusIndicatorPaint)

        // Text summary: "30 FPS · 12ms | ACTIVE | POINTING"
        val textY = indicatorY - ((textPaint.descent() + textPaint.ascent()) / 2f)
        var curX = indicatorX + 8f * density

        val fpsText = "$fps FPS"
        canvas.drawText(fpsText, curX, textY, textPaint)
        curX += textPaint.measureText(fpsText) + (4f * density)

        val sep = "·"
        canvas.drawText(sep, curX, textY, textPaint)
        curX += textPaint.measureText(sep) + (4f * density)

        val latText = "${latencyMs}ms"
        canvas.drawText(latText, curX, textY, textPaint)
        curX += textPaint.measureText(latText) + (6f * density)

        val bar = "|"
        canvas.drawText(bar, curX, textY, textPaint)
        curX += textPaint.measureText(bar) + (6f * density)

        val stateText = if (isPaused) "PAUSED" else "ACTIVE"
        val statePaint = if (isPaused) textPaint else accentTextPaint
        canvas.drawText(stateText, curX, textY, statePaint)
        curX += statePaint.measureText(stateText) + (6f * density)

        if (!isPaused) {
            val gestureDisplay = if (lastGestureName != "NONE" && System.currentTimeMillis() < gestureFlashUntil) {
                lastGestureName
            } else if (currentStateName.isNotEmpty() && currentStateName != "IDLE") {
                currentStateName
            } else {
                null
            }

            if (gestureDisplay != null) {
                canvas.drawText(bar, curX, textY, textPaint)
                curX += textPaint.measureText(bar) + (6f * density)
                canvas.drawText(gestureDisplay, curX, textY, accentTextPaint)
            }
        }
    }

    fun updateMetrics(fps: Int, latencyMs: Long, isPaused: Boolean, stateName: String) {
        this.fps = fps
        this.latencyMs = latencyMs
        this.isPaused = isPaused
        this.currentStateName = stateName
        postInvalidate()
    }

    fun flashGesture(gestureName: String) {
        this.lastGestureName = gestureName
        this.gestureFlashUntil = System.currentTimeMillis() + 900L
        postInvalidate()
    }
}
