package com.example.finger_controler

import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs

/**
 * Velocity-Aware Adaptive Cursor Smoothing using the 1-Euro Filter algorithm.
 * Eliminates jitter at low velocities (pointing/hovering) while minimizing latency
 * and preventing overshoot during rapid intentional movements.
 */
class AdaptiveCursorFilter(
    private val minCutoff: Double = FingerControlerConfig.ONE_EURO_MIN_CUTOFF,
    private val beta: Double = FingerControlerConfig.ONE_EURO_BETA,
    private val dCutoff: Double = FingerControlerConfig.ONE_EURO_DCUTOFF
) {

    private class LowPassFilter(private var alpha: Double = 0.0) {
        private var hatXPrev: Double = 0.0
        private var initialized: Boolean = false

        fun filter(x: Double, alpha: Double): Double {
            this.alpha = alpha
            hatXPrev = if (initialized) {
                alpha * x + (1.0 - alpha) * hatXPrev
            } else {
                initialized = true
                x
            }
            return hatXPrev
        }

        fun last(): Double = hatXPrev

        fun reset() {
            initialized = false
            hatXPrev = 0.0
        }
    }

    private class OneEuroAxis(
        private val minCutoff: Double,
        private val beta: Double,
        private val dCutoff: Double
    ) {
        private val xFilter = LowPassFilter()
        private val dxFilter = LowPassFilter()
        private var lastTimestampMs: Long = -1L

        private fun alpha(rate: Double, cutoff: Double): Double {
            val tau = 1.0 / (2.0 * PI * cutoff)
            val te = 1.0 / rate
            return 1.0 / (1.0 + tau / te)
        }

        fun filter(x: Double, timestampMs: Long): Double {
            if (lastTimestampMs < 0L) {
                lastTimestampMs = timestampMs
                return xFilter.filter(x, 1.0)
            }

            val dt = (timestampMs - lastTimestampMs) / 1000.0
            lastTimestampMs = timestampMs

            // Guard against divide-by-zero or timestamp anomalies
            if (dt <= 0.0001 || dt > 1.0) {
                return xFilter.filter(x, 1.0)
            }

            val rate = 1.0 / dt
            val dx = (x - xFilter.last()) * rate
            val edx = dxFilter.filter(dx, alpha(rate, dCutoff))
            val cutoff = minCutoff + beta * abs(edx)
            return xFilter.filter(x, alpha(rate, cutoff))
        }

        fun reset() {
            lastTimestampMs = -1L
            xFilter.reset()
            dxFilter.reset()
        }
    }

    private val xAxis = OneEuroAxis(minCutoff, beta, dCutoff)
    private val yAxis = OneEuroAxis(minCutoff, beta, dCutoff)
    private var lastSeenTimestampMs: Long = 0L

    /**
     * Filters the raw screen coordinates with velocity adaptation.
     * If tracking was lost for longer than TRACKING_LOSS_RESET_TIMEOUT_MS,
     * the filter resets to prevent sudden jump interpolation.
     */
    fun filter(rawX: Float, rawY: Float, timestampMs: Long = SystemClock.uptimeMillis()): Pair<Float, Float> {
        if (lastSeenTimestampMs > 0 && (timestampMs - lastSeenTimestampMs > FingerControlerConfig.TRACKING_LOSS_RESET_TIMEOUT_MS)) {
            reset()
        }
        lastSeenTimestampMs = timestampMs

        val filteredX = xAxis.filter(rawX.toDouble(), timestampMs).toFloat()
        val filteredY = yAxis.filter(rawY.toDouble(), timestampMs).toFloat()

        return Pair(filteredX, filteredY)
    }

    fun reset() {
        xAxis.reset()
        yAxis.reset()
        lastSeenTimestampMs = 0L
    }
}
