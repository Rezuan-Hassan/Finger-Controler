package com.example.finger_controler

/**
 * Centralized configuration parameters for Hand Tracking, Cursor Smoothing,
 * Gesture Recognition State Machine, and Visual Feedback.
 */
object FingerControlerConfig {

    // ==========================================
    // 1. Camera & Processing Pipeline (Section 1)
    // ==========================================
    const val TARGET_FPS: Int = 30
    const val CAMERA_WIDTH: Int = 640
    const val CAMERA_HEIGHT: Int = 480

    // MediaPipe HandLandmarker confidence thresholds
    const val MIN_HAND_DETECTION_CONFIDENCE: Float = 0.50f
    const val MIN_TRACKING_CONFIDENCE: Float = 0.50f
    const val MIN_HAND_PRESENCE_CONFIDENCE: Float = 0.50f
    const val MAX_NUM_HANDS: Int = 1

    // ==========================================
    // 2. Cursor Tracking & Mapping (Section 2)
    // ==========================================
    // Active interaction region in normalized coordinates [0.0 .. 1.0]
    // Margins allow comfortable reaching of screen edges without extreme hand travel
    const val BOUND_LEFT: Float = 0.15f
    const val BOUND_RIGHT: Float = 0.85f
    const val BOUND_TOP: Float = 0.12f
    const val BOUND_BOTTOM: Float = 0.85f

    // Screen padding to prevent cursor from clipping outside visible screen
    const val CURSOR_SCREEN_PADDING_PX: Float = 8f

    // 1-Euro Filter Parameters for Adaptive Velocity-Aware Smoothing:
    // - minCutoff: smoothing at zero/low speed (lower = less jitter, higher = less lag)
    // - beta: speed coefficient (higher = faster response during fast motion)
    // - dCutoff: cutoff frequency for derivative
    const val ONE_EURO_MIN_CUTOFF: Double = 1.2
    const val ONE_EURO_BETA: Double = 0.035
    const val ONE_EURO_DCUTOFF: Double = 1.0

    // Temporary landmark loss timeout: if hand missing longer than this, reset cursor filter
    const val TRACKING_LOSS_RESET_TIMEOUT_MS: Long = 400L

    // ==========================================
    // 3. Open Palm Pause / Resume (Section 3)
    // ==========================================
    const val PALM_CONFIRMATION_FRAMES: Int = 6
    const val PALM_COOLDOWN_MS: Long = 1200L

    // Extension ratio: tip-to-wrist distance / pip-to-wrist distance
    const val PALM_FINGER_EXTENSION_RATIO: Float = 1.25f

    // ==========================================
    // 4. Pinch Left Click (Section 4)
    // ==========================================
    // Hysteresis thresholds for pinch
    const val PINCH_ACTIVATION_THRESHOLD: Float = 0.055f
    const val PINCH_RELEASE_THRESHOLD: Float = 0.080f

    // Duration requirements: quick pinch-and-release < 300 ms, min 40 ms to debounce
    const val PINCH_MIN_DURATION_MS: Long = 40L
    const val PINCH_MAX_DURATION_MS: Long = 300L
    const val PINCH_COOLDOWN_MS: Long = 350L

    // Other fingers stability threshold: folded fingers must not be extended
    const val PINCH_FOLDED_FINGER_MAX_EXT_RATIO: Float = 1.15f

    // ==========================================
    // 5. Two-Finger Continuous Scroll (Section 5)
    // ==========================================
    // Finger proximity threshold: Index (8) and Middle (12) must remain close together
    const val SCROLL_FINGER_SEPARATION_THRESHOLD: Float = 0.065f

    // Scroll movement thresholds and cooldown
    const val SCROLL_MIN_DISPLACEMENT_NORMALIZED: Float = 0.016f
    const val SCROLL_SENSITIVITY: Float = 1.0f
    const val SCROLL_COOLDOWN_MS: Long = 100L
    const val SCROLL_INVERT_DIRECTION: Boolean = false // false = natural mouse wheel (fingers up -> wheel up)

    // ==========================================
    // 6. Thumbs Up Media Play / Pause (Section 6)
    // ==========================================
    const val MEDIA_THUMBS_UP_CONFIRMATION_FRAMES: Int = 5
    const val MEDIA_THUMBS_UP_COOLDOWN_MS: Long = 800L

    // ==========================================
    // 7. Closed Fist Mute / Unmute (Section 7)
    // ==========================================
    const val MUTE_FIST_CONFIRMATION_FRAMES: Int = 6
    const val MUTE_FIST_COOLDOWN_MS: Long = 800L
    const val FIST_MAX_EXTENSION_RATIO: Float = 1.10f

    // ==========================================
    // 8. Three Extended Fingers Screenshot (Section 8)
    // ==========================================
    const val SCREENSHOT_CONFIRMATION_FRAMES: Int = 8
    const val SCREENSHOT_COOLDOWN_MS: Long = 1500L

    // Folded finger detection ratio threshold
    const val FINGER_FOLDED_MAX_EXTENSION_RATIO: Float = 1.15f

    // ==========================================
    // 9. Visual Feedback & Cursor Design (Section 9)
    // ==========================================
    // Small orange dot design specifications
    const val CURSOR_RADIUS_DP: Float = 8.0f // Small 16dp diameter precision dot
    const val CURSOR_COLOR_ACTIVE: String = "#FF6F00" // Vibrant Electric Orange
    const val CURSOR_COLOR_PAUSED: String = "#787672" // Neutral Muted Grey when paused
    const val CURSOR_COLOR_CLICK: String = "#FFA040" // Flash highlight on click
    const val CURSOR_BORDER_COLOR: String = "#FFFFFF" // Crisp white ring

    // Diagnostic HUD overlay
    const val SHOW_DIAGNOSTICS_OVERLAY: Boolean = true
}
