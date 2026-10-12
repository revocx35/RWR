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
) {
    val area: Int get() = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
}

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
