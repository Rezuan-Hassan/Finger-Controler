package com.example.finger_controler

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * High-performance cursor view rendering a sleek small orange dot.
 * Provides micro-animations for clicks, scroll movements, media control,
 * audio mute, screenshot confirmation, and ACTIVE/PAUSED differentiation.
 */
class CursorOverlayView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    // Small orange dot radius: 8dp (16dp diameter)
    private val baseRadius = FingerControlerConfig.CURSOR_RADIUS_DP * density

    private var currentScale = 0.0f
    private var tapPulseRadius = 0f
    private var tapPulseAlpha = 0
    private var pulseColor = Color.parseColor(FingerControlerConfig.CURSOR_COLOR_ACTIVE)
    private var isPaused = false

    // Vibrant Electric Orange (#FF6F00)
    private val activeFillColor = Color.parseColor(FingerControlerConfig.CURSOR_COLOR_ACTIVE)
    // Muted slate gray when paused
    private val pausedFillColor = Color.parseColor(FingerControlerConfig.CURSOR_COLOR_PAUSED)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = activeFillColor
        style = Paint.Style.FILL
    }

    // Outer subtle ambient glow
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = activeFillColor
        alpha = 70
        style = Paint.Style.FILL
    }

    // Crisp white border for high contrast against dark & light backgrounds
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 245
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    // Precision micro center point
    private val centerPipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 230
        style = Paint.Style.FILL
    }

    // Expanding ripple for gesture feedback
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = activeFillColor
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalSize = (56f * density).toInt()
        setMeasuredDimension(totalSize, totalSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        // Draw gesture ripple wave
        if (tapPulseAlpha > 0) {
            ripplePaint.alpha = tapPulseAlpha
            ripplePaint.color = pulseColor
            canvas.drawCircle(cx, cy, tapPulseRadius, ripplePaint)
        }

        val r = baseRadius * currentScale

        // Ambient glow
        if (!isPaused && r > 0f) {
            glowPaint.color = activeFillColor
            glowPaint.alpha = 55
            canvas.drawCircle(cx, cy, r + 3f * density, glowPaint)
        }

        // Main dot fill
        dotPaint.color = if (isPaused) pausedFillColor else activeFillColor
        dotPaint.alpha = if (isPaused) 180 else 255
        canvas.drawCircle(cx, cy, r, dotPaint)

        // White border ring
        canvas.drawCircle(cx, cy, r, borderPaint)

        // Subtle center dot pip
        if (r > 3f) {
            canvas.drawCircle(cx, cy, 1.8f * density * currentScale, centerPipPaint)
        }
    }

    fun setPaused(paused: Boolean) {
        if (isPaused != paused) {
            isPaused = paused
            postInvalidate()
        }
    }

    fun animateSpawn() {
        post {
            ValueAnimator.ofFloat(0.0f, 1.0f).apply {
                duration = 260
                interpolator = OvershootInterpolator(1.6f)
                addUpdateListener { animator ->
                    currentScale = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    fun animateTap() {
        post {
            pulseColor = Color.parseColor(FingerControlerConfig.CURSOR_COLOR_CLICK)
            ValueAnimator.ofFloat(1.0f, 0.65f, 1.15f, 1.0f).apply {
                duration = 200
                interpolator = OvershootInterpolator(2.0f)
                addUpdateListener { animator ->
                    currentScale = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }

            ValueAnimator.ofFloat(baseRadius, baseRadius * 2.5f).apply {
                duration = 220
                addUpdateListener { animator ->
                    tapPulseRadius = animator.animatedValue as Float
                    tapPulseAlpha = ((1f - animator.animatedFraction) * 220).toInt()
                    invalidate()
                }
                start()
            }
        }
    }

    fun animateScroll(isUp: Boolean) {
        post {
            ValueAnimator.ofFloat(1.0f, 1.25f, 1.0f).apply {
                duration = 160
                addUpdateListener { animator ->
                    currentScale = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    fun animateMedia() {
        post {
            pulseColor = Color.parseColor("#00E676") // Vibrant spring green
            ValueAnimator.ofFloat(1.0f, 1.35f, 1.0f).apply {
                duration = 220
                interpolator = OvershootInterpolator(1.8f)
                addUpdateListener { animator ->
                    currentScale = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }

            ValueAnimator.ofFloat(baseRadius, baseRadius * 2.8f).apply {
                duration = 260
                addUpdateListener { animator ->
                    tapPulseRadius = animator.animatedValue as Float
                    tapPulseAlpha = ((1f - animator.animatedFraction) * 230).toInt()
                    invalidate()
                }
                start()
            }
        }
    }

    fun animateMute() {
        post {
            pulseColor = Color.parseColor("#FF5252") // Coral red
            ValueAnimator.ofFloat(1.0f, 1.3f, 0.9f, 1.0f).apply {
                duration = 240
                addUpdateListener { animator ->
                    currentScale = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }

            ValueAnimator.ofFloat(baseRadius, baseRadius * 2.6f).apply {
                duration = 250
                addUpdateListener { animator ->
                    tapPulseRadius = animator.animatedValue as Float
                    tapPulseAlpha = ((1f - animator.animatedFraction) * 240).toInt()
                    invalidate()
                }
                start()
            }
        }
    }

    fun animateScreenshot() {
        post {
            pulseColor = Color.WHITE
            ValueAnimator.ofFloat(1.0f, 1.45f, 1.0f).apply {
                duration = 280
                interpolator = OvershootInterpolator(2.2f)
                addUpdateListener { animator ->
                    currentScale = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }

            ValueAnimator.ofFloat(baseRadius, baseRadius * 3.4f).apply {
                duration = 320
                addUpdateListener { animator ->
                    tapPulseRadius = animator.animatedValue as Float
                    tapPulseAlpha = ((1f - animator.animatedFraction) * 255).toInt()
                    invalidate()
                }
                start()
            }
        }
    }
}
