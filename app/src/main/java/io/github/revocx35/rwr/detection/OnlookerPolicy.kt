package io.github.revocx35.rwr.detection

import kotlin.math.abs

/** A detected face in snapshot-bitmap pixel coordinates, plus its head pose in degrees. */
data class FaceBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** Head yaw (left/right turn); 0 = facing the camera. */
    val yaw: Float,
    /** Head pitch (up/down tilt); 0 = facing the camera. */
    val pitch: Float,
    /** Eye mode: "laser" lines from the irises along the gaze, drawn on the snapshot. */
    val rays: List<Ray> = emptyList(),
) {
    val area: Int get() = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
    val width: Int get() = right - left
}

enum class DetectionMode { HEAD, EYE }

/**
 * Decides which faces in a frame count as onlookers. Pure Kotlin so it is unit-tested on the JVM.
 *
 * The front camera faces the same way as the screen, so anyone it sees with their head turned
 * towards it is looking (roughly) at the screen. The phone's owner is normally the largest,
 * closest face, so with [ignoreOwner] that face is never an onlooker.
 */
object OnlookerPolicy {
    fun onlookers(faces: List<FaceBox>, ignoreOwner: Boolean, maxHeadAngle: Float): List<FaceBox> {
        val owner = if (ignoreOwner) faces.maxByOrNull { it.area } else null
        return faces.filter { it !== owner && isLookingAtCamera(it, maxHeadAngle) }
    }

    fun isLookingAtCamera(face: FaceBox, maxHeadAngle: Float): Boolean =
        abs(face.yaw) <= maxHeadAngle && abs(face.pitch) <= maxHeadAngle

    /** Faces that may be onlookers, before the per-face eye check. */
    fun candidates(faces: List<FaceBox>, ignoreOwner: Boolean): List<FaceBox> {
        val owner = if (ignoreOwner) faces.maxByOrNull { it.area } else null
        return faces.filter { it !== owner }
    }

    /** Eye tracking only runs on faces turned at most this far (beyond it the eyes can't reach the screen). */
    const val EYE_PREFILTER_ANGLE = 60f
    /** Below this face width the irises are a few pixels wide: decide by head pose instead. */
    const val MIN_EYE_FACE_PX = 40

    /**
     * Eye mode decision for one face. With a usable [gaze], the eyes decide (head pose + eye rotation);
     * otherwise (face too small, landmarks not found) it falls back to the head rule.
     */
    fun isLookingEyeMode(face: FaceBox, gaze: Gaze?, maxHeadAngle: Float, maxGazeAngle: Float): Boolean =
        if (gaze != null && face.width >= MIN_EYE_FACE_PX) GazeEstimator.isLooking(gaze, maxGazeAngle)
        else isLookingAtCamera(face, maxHeadAngle)
}

/**
 * Turns per-frame "onlooker seen" results into alerts: an onlooker must appear in [required] of the
 * last [window] analysed frames (filters single-frame false positives), and alerts are spaced at
 * least the cooldown apart so one person standing behind you does not spam the gallery.
 */
class AlertGate(private val window: Int = 3, private val required: Int = 2) {
    private val recent = ArrayDeque<Boolean>()
    private var lastAlertAt = Long.MIN_VALUE

    /** Returns true when this frame should raise an alert. */
    fun onFrame(onlookerSeen: Boolean, nowMs: Long, cooldownMs: Long): Boolean {
        recent.addLast(onlookerSeen)
        while (recent.size > window) recent.removeFirst()
        if (recent.count { it } < required) return false
        if (lastAlertAt != Long.MIN_VALUE && nowMs - lastAlertAt < cooldownMs) return false
        lastAlertAt = nowMs
        recent.clear()
        return true
    }

    fun reset() {
        recent.clear()
    }
}
