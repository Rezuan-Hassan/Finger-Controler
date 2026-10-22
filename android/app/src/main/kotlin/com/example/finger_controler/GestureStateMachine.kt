package com.example.finger_controler

import android.os.SystemClock
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot

/**
 * High-performance gesture recognition state machine.
 * Disambiguates gestures across consecutive frames and ensures exactly one action
 * per deliberate gesture.
 */
class GestureStateMachine(private val listener: Listener) {

    enum class State {
        IDLE,
        CURSOR_MOVE,
        PINCH_CANDIDATE,
        SCROLL_ACTIVE,
        THUMBS_UP_CANDIDATE,
        FIST_CANDIDATE,
        SCREENSHOT_CANDIDATE,
        PAUSED,
        COOLDOWN
    }

    enum class RecognizedGesture {
        NONE,
        POINTING,
        PINCH_TAP,
        SCROLL_UP,
        SCROLL_DOWN,
        OPEN_PALM_TOGGLE,
        THUMBS_UP_MEDIA,
        CLOSED_FIST_MUTE,
        THREE_FINGER_SCREENSHOT
    }

    interface Listener {
        fun onCursorMove(mappedRawX: Float, mappedRawY: Float, timestampMs: Long)
        fun onPinchTap(cursorX: Float, cursorY: Float)
        fun onScroll(scrollUp: Boolean, distanceNormalized: Float)
        fun onMediaPlayPause()
        fun onToggleMute()
        fun onScreenshot()
        fun onPauseStateChanged(isPaused: Boolean)
        fun onStateChanged(state: State, gesture: RecognizedGesture)
    }

    var currentState: State = State.IDLE
        private set

    var isPaused: Boolean = false
        private set

    // Palm pause/resume tracking
    private var consecutivePalmFrames = 0
    private var palmHeld = false
    private var lastPalmToggleTimestamp = 0L

    // Pinch tracking
    private var pinchStartTime = 0L
    private var pinchExceededMax = false
    private var lastPinchClickTimestamp = 0L

    // Two-finger continuous scroll tracking
    private var scrollAnchorY = -1f
    private var lastScrollActionTimestamp = 0L

    // Thumbs-up media tracking
    private var thumbsUpFrames = 0
    private var thumbsUpTriggered = false
    private var lastThumbsUpTimestamp = 0L

    // Closed-fist mute tracking
    private var fistFrames = 0
    private var fistTriggered = false
    private var lastFistTimestamp = 0L

    // Three-finger screenshot tracking
    private var threeFingerFrames = 0
    private var screenshotTriggered = false
    private var lastScreenshotTimestamp = 0L

    // Frame tracking
    private var lastHandSeenTimestamp = 0L

    fun processFrame(landmarks: List<NormalizedLandmark>, screenWidth: Int, screenHeight: Int) {
        val now = SystemClock.uptimeMillis()
        lastHandSeenTimestamp = now

        if (landmarks.size < 21) {
            handleTrackingLost(now)
            return
        }

        // Landmark references
        val wrist = landmarks[0]
        val thumbCmc = landmarks[1]
        val thumbMcp = landmarks[2]
        val thumbIp = landmarks[3]
        val thumbTip = landmarks[4]

        val indexMcp = landmarks[5]
        val indexPip = landmarks[6]
        val indexDip = landmarks[7]
        val indexTip = landmarks[8]

        val middleMcp = landmarks[9]
        val middlePip = landmarks[10]
        val middleDip = landmarks[11]
        val middleTip = landmarks[12]

        val ringMcp = landmarks[13]
        val ringPip = landmarks[14]
        val ringDip = landmarks[15]
        val ringTip = landmarks[16]

        val pinkyMcp = landmarks[17]
        val pinkyPip = landmarks[18]
        val pinkyDip = landmarks[19]
        val pinkyTip = landmarks[20]

        // Rotation-invariant finger extension calculation
        val isIndexExtended = isFingerExtended(wrist, indexPip, indexTip)
        val isMiddleExtended = isFingerExtended(wrist, middlePip, middleTip)
        val isRingExtended = isFingerExtended(wrist, ringPip, ringTip)
        val isPinkyExtended = isFingerExtended(wrist, pinkyPip, pinkyTip)
        val isThumbExtended = isThumbExtended(wrist, thumbMcp, thumbTip)

        val isIndexFolded = isFingerFolded(wrist, indexPip, indexTip)
        val isMiddleFolded = isFingerFolded(wrist, middlePip, middleTip)
        val isRingFolded = isFingerFolded(wrist, ringPip, ringTip)
        val isPinkyFolded = isFingerFolded(wrist, pinkyPip, pinkyTip)

        val areOtherFingersFolded = isMiddleFolded && isRingFolded && isPinkyFolded

        // ========================================================
        // PRIORITY 1: OPEN PALM DETECTION (Pause / Resume Toggle)
        // ========================================================
        val isAllFingersExtended = isIndexExtended && isMiddleExtended && isRingExtended && isPinkyExtended && isThumbExtended

        if (isAllFingersExtended) {
            consecutivePalmFrames++
            if (consecutivePalmFrames >= FingerControlerConfig.PALM_CONFIRMATION_FRAMES && !palmHeld) {
                if (now - lastPalmToggleTimestamp > FingerControlerConfig.PALM_COOLDOWN_MS) {
                    isPaused = !isPaused
                    lastPalmToggleTimestamp = now
                    palmHeld = true
                    currentState = if (isPaused) State.PAUSED else State.IDLE
                    listener.onPauseStateChanged(isPaused)
                    listener.onStateChanged(currentState, RecognizedGesture.OPEN_PALM_TOGGLE)
                    resetTransientStates()
                    return
                }
            }
        } else {
            consecutivePalmFrames = 0
            palmHeld = false
        }

        // In PAUSED mode: stop all cursor, click, scroll, media, and screenshot actions.
        if (isPaused) {
            currentState = State.PAUSED
            listener.onStateChanged(currentState, RecognizedGesture.NONE)
            return
        }

        // Calculate mapped screen coordinates for index fingertip (preserving cursor mapping)
        val mirrorX = (1.0f - indexTip.x()).coerceIn(0f, 1f)
        val mirrorY = indexTip.y().coerceIn(0f, 1f)
        val boundW = FingerControlerConfig.BOUND_RIGHT - FingerControlerConfig.BOUND_LEFT
        val boundH = FingerControlerConfig.BOUND_BOTTOM - FingerControlerConfig.BOUND_TOP
        val normMappedX = ((mirrorX - FingerControlerConfig.BOUND_LEFT) / boundW).coerceIn(0f, 1f)
        val normMappedY = ((mirrorY - FingerControlerConfig.BOUND_TOP) / boundH).coerceIn(0f, 1f)
        val rawScreenX = normMappedX * screenWidth
        val rawScreenY = normMappedY * screenHeight

        // ========================================================
        // PRIORITY 2: THUMB + INDEX PINCH DETECTION (Left Click)
        // ========================================================
        val pinchDist = hypot(thumbTip.x() - indexTip.x(), thumbTip.y() - indexTip.y())
        val isPinchEngaged = pinchDist < FingerControlerConfig.PINCH_ACTIVATION_THRESHOLD
        val isPinchReleased = pinchDist > FingerControlerConfig.PINCH_RELEASE_THRESHOLD

        if (isPinchEngaged && areOtherFingersFolded) {
            if (pinchStartTime == 0L) {
                if (now - lastPinchClickTimestamp > FingerControlerConfig.PINCH_COOLDOWN_MS) {
                    pinchStartTime = now
                    pinchExceededMax = false
                    currentState = State.PINCH_CANDIDATE
                    listener.onStateChanged(currentState, RecognizedGesture.NONE)
                }
            } else {
                val duration = now - pinchStartTime
                if (duration > FingerControlerConfig.PINCH_MAX_DURATION_MS) {
                    // Pinch held longer than 300ms - reserve for future drag, do not trigger quick tap
                    pinchExceededMax = true
                    currentState = State.IDLE
                }
            }
            // While engaged in pinch, do not trigger other gestures
            return
        } else if (pinchStartTime != 0L) {
            val duration = now - pinchStartTime
            if (isPinchReleased && !pinchExceededMax && duration in FingerControlerConfig.PINCH_MIN_DURATION_MS..FingerControlerConfig.PINCH_MAX_DURATION_MS) {
                // Confirmed deliberate quick tap!
                lastPinchClickTimestamp = now
                pinchStartTime = 0L
                pinchExceededMax = false
                currentState = State.COOLDOWN
                listener.onPinchTap(rawScreenX, rawScreenY)
                listener.onStateChanged(currentState, RecognizedGesture.PINCH_TAP)
                return
            } else {
                // Released past 300ms or noise spike (<40ms)
                pinchStartTime = 0L
                pinchExceededMax = false
            }
        }

        // ========================================================
        // PRIORITY 3: TWO-FINGER SCROLL DETECTION (Index + Middle)
        // ========================================================
        val isTwoFingerConfig = isIndexExtended && isMiddleExtended && isRingFolded && isPinkyFolded
        val fingerSeparation = hypot(indexTip.x() - middleTip.x(), indexTip.y() - middleTip.y())
        val isTwoFingersTogether = fingerSeparation <= FingerControlerConfig.SCROLL_FINGER_SEPARATION_THRESHOLD

        if (isTwoFingerConfig && isTwoFingersTogether) {
            val currentY = (indexTip.y() + middleTip.y()) / 2f

            if (scrollAnchorY < 0f) {
                scrollAnchorY = currentY
            } else {
                val deltaY = currentY - scrollAnchorY
                val absDisplacement = abs(deltaY)

                if (absDisplacement >= FingerControlerConfig.SCROLL_MIN_DISPLACEMENT_NORMALIZED) {
                    if (now - lastScrollActionTimestamp >= FingerControlerConfig.SCROLL_COOLDOWN_MS) {
                        // In camera coordinates: Y=0 is top, Y=1 is bottom.
                        // Moving fingers upward: currentY < scrollAnchorY (deltaY < 0).
                        val scrollUp = deltaY < 0
                        lastScrollActionTimestamp = now
                        scrollAnchorY = currentY
                        currentState = State.SCROLL_ACTIVE
                        val gesture = if (scrollUp) RecognizedGesture.SCROLL_UP else RecognizedGesture.SCROLL_DOWN
                        listener.onScroll(scrollUp, absDisplacement)
                        listener.onStateChanged(currentState, gesture)
                        return
                    }
                }
            }

            currentState = State.SCROLL_ACTIVE
            listener.onStateChanged(currentState, RecognizedGesture.NONE)
            return
        } else {
            scrollAnchorY = -1f
        }

        // ========================================================
        // PRIORITY 4: THREE EXTENDED FINGERS (Screenshot)
        // Index, Middle, Ring extended; Thumb and Pinky folded.
        // ========================================================
        val isThreeFingerScreenshotConfig = isIndexExtended && isMiddleExtended && isRingExtended &&
                isPinkyFolded && !isThumbExtended

        if (isThreeFingerScreenshotConfig) {
            threeFingerFrames++
            if (threeFingerFrames >= FingerControlerConfig.SCREENSHOT_CONFIRMATION_FRAMES && !screenshotTriggered) {
                if (now - lastScreenshotTimestamp > FingerControlerConfig.SCREENSHOT_COOLDOWN_MS) {
                    screenshotTriggered = true
                    lastScreenshotTimestamp = now
                    currentState = State.COOLDOWN
                    listener.onScreenshot()
                    listener.onStateChanged(currentState, RecognizedGesture.THREE_FINGER_SCREENSHOT)
                    return
                }
            }
            currentState = State.SCREENSHOT_CANDIDATE
            listener.onStateChanged(currentState, RecognizedGesture.NONE)
            return
        } else {
            threeFingerFrames = 0
            screenshotTriggered = false
        }

        // ========================================================
        // PRIORITY 5: THUMBS UP (Play / Pause Media)
        // Thumb extended & pointing upward; other four fingers folded.
        // ========================================================
        val isThumbPointingUp = thumbTip.y() < thumbIp.y() && thumbIp.y() < thumbMcp.y() &&
                thumbTip.y() < indexMcp.y() - 0.03f
        val isThumbsUpPose = isThumbExtended && isThumbPointingUp &&
                isIndexFolded && isMiddleFolded && isRingFolded && isPinkyFolded

        if (isThumbsUpPose) {
            thumbsUpFrames++
            if (thumbsUpFrames >= FingerControlerConfig.MEDIA_THUMBS_UP_CONFIRMATION_FRAMES && !thumbsUpTriggered) {
                if (now - lastThumbsUpTimestamp > FingerControlerConfig.MEDIA_THUMBS_UP_COOLDOWN_MS) {
                    thumbsUpTriggered = true
                    lastThumbsUpTimestamp = now
                    currentState = State.COOLDOWN
                    listener.onMediaPlayPause()
                    listener.onStateChanged(currentState, RecognizedGesture.THUMBS_UP_MEDIA)
                    return
                }
            }
            currentState = State.THUMBS_UP_CANDIDATE
            listener.onStateChanged(currentState, RecognizedGesture.NONE)
            return
        } else {
            thumbsUpFrames = 0
            thumbsUpTriggered = false
        }

        // ========================================================
        // PRIORITY 6: CLOSED FIST (Mute / Unmute System Audio)
        // All four fingers folded; thumb folded consistent with fist.
        // ========================================================
        val isThumbFoldedInFist = !isThumbExtended || thumbTip.y() >= thumbMcp.y() - 0.02f
        val isClosedFistPose = isIndexFolded && isMiddleFolded && isRingFolded && isPinkyFolded &&
                isThumbFoldedInFist && !isThumbsUpPose

        if (isClosedFistPose) {
            fistFrames++
            if (fistFrames >= FingerControlerConfig.MUTE_FIST_CONFIRMATION_FRAMES && !fistTriggered) {
                if (now - lastFistTimestamp > FingerControlerConfig.MUTE_FIST_COOLDOWN_MS) {
                    fistTriggered = true
                    lastFistTimestamp = now
                    currentState = State.COOLDOWN
                    listener.onToggleMute()
                    listener.onStateChanged(currentState, RecognizedGesture.CLOSED_FIST_MUTE)
                    return
                }
            }
            currentState = State.FIST_CANDIDATE
            listener.onStateChanged(currentState, RecognizedGesture.NONE)
            return
        } else {
            fistFrames = 0
            fistTriggered = false
        }

        // ========================================================
        // PRIORITY 7: INDEX FINGER ONLY (Move Cursor)
        // Index extended; middle, ring, pinky folded; thumb not pinching.
        // ========================================================
        if (isIndexExtended && areOtherFingersFolded && !isPinchEngaged) {
            currentState = State.CURSOR_MOVE
            listener.onCursorMove(rawScreenX, rawScreenY, now)
            listener.onStateChanged(currentState, RecognizedGesture.POINTING)
            return
        }

        // No confirmed active gesture
        currentState = State.IDLE
        listener.onStateChanged(currentState, RecognizedGesture.NONE)
    }

    fun handleTrackingLost(now: Long = SystemClock.uptimeMillis()) {
        resetTransientStates()
        if (!isPaused) {
            currentState = State.IDLE
            listener.onStateChanged(currentState, RecognizedGesture.NONE)
        }
    }

    private fun resetTransientStates() {
        consecutivePalmFrames = 0
        palmHeld = false
        pinchStartTime = 0L
        pinchExceededMax = false
        scrollAnchorY = -1f
        thumbsUpFrames = 0
        thumbsUpTriggered = false
        fistFrames = 0
        fistTriggered = false
        threeFingerFrames = 0
        screenshotTriggered = false
    }

    private fun isFingerExtended(
        wrist: NormalizedLandmark,
        pip: NormalizedLandmark,
        tip: NormalizedLandmark
    ): Boolean {
        val distTipToWrist = hypot(tip.x() - wrist.x(), tip.y() - wrist.y())
        val distPipToWrist = hypot(pip.x() - wrist.x(), pip.y() - wrist.y())
        return distTipToWrist > distPipToWrist * FingerControlerConfig.PALM_FINGER_EXTENSION_RATIO
    }

    private fun isFingerFolded(
        wrist: NormalizedLandmark,
        pip: NormalizedLandmark,
        tip: NormalizedLandmark
    ): Boolean {
        val distTipToWrist = hypot(tip.x() - wrist.x(), tip.y() - wrist.y())
        val distPipToWrist = hypot(pip.x() - wrist.x(), pip.y() - wrist.y())
        return distTipToWrist <= distPipToWrist * FingerControlerConfig.FINGER_FOLDED_MAX_EXTENSION_RATIO
    }

    private fun isThumbExtended(
        wrist: NormalizedLandmark,
        mcp: NormalizedLandmark,
        tip: NormalizedLandmark
    ): Boolean {
        val distTipToWrist = hypot(tip.x() - wrist.x(), tip.y() - wrist.y())
        val distMcpToWrist = hypot(mcp.x() - wrist.x(), mcp.y() - wrist.y())
        return distTipToWrist > distMcpToWrist * 1.18f
    }
}
