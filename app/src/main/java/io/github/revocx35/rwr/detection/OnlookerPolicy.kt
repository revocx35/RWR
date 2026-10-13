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

/** Why a face did or didn't count, for the debug view. Same names as the server's debug messages. */
enum class FaceRole(val wire: String, val label: String) {
    OWNER("owner", "you"),
    ONLOOKER("onlooker", "LOOKING"),
    NOT_LOOKING("not_looking", "not looking"),
    /** Eye mode: head turned past the prefilter angle, so the eyes weren't checked. */
    TURNED_AWAY("turned_away", "turned away"),
    /** Face Landmarker found no face (or another face) there: a false detection. */
    NO_FACE("no_face", "not a face"),
    /** Narrower than [OnlookerPolicy.MIN_FACE_FRACTION] of the frame (or the server's pixel minimum). */
    TOO_SMALL("too_small", "too small");

    companion object {
        fun fromWire(s: String): FaceRole? = entries.firstOrNull { it.wire == s }
    }
}

/** One face and the decision about it; [gaze] is set when Face Landmarker ran on it. */
data class FaceVerdict(val face: FaceBox, val role: FaceRole, val gaze: Gaze? = null)

/**
 * Decides which faces in a frame count as onlookers. Pure Kotlin so it is unit-tested on the JVM.
 *
 * The front camera faces the same way as the screen, so anyone it sees with their head turned
 * towards it is looking (roughly) at the screen. The phone's owner (picked by [OwnerTracker]) is
 * never an onlooker.
 */
object OnlookerPolicy {
    fun onlookers(faces: List<FaceBox>, owner: FaceBox?, maxHeadAngle: Float): List<FaceBox> =
        faces.filter { it !== owner && isLookingAtCamera(it, maxHeadAngle) }

    fun isLookingAtCamera(face: FaceBox, maxHeadAngle: Float): Boolean =
        abs(face.yaw) <= maxHeadAngle && abs(face.pitch) <= maxHeadAngle

    /** Faces that may be onlookers, before the per-face eye check. */
    fun candidates(faces: List<FaceBox>, owner: FaceBox?): List<FaceBox> = faces.filter { it !== owner }

    /**
     * Faces narrower than this fraction of the frame width are never onlookers: at about 7% the person is ~2 m
     * away, too far to read a phone screen, and nearly all false detections (ceiling fixtures, bags, hands,
     * distant crowds) are that small. Same as MIN_FACE_FRACTION in server/rwr_server/policy.py.
     */
    const val MIN_FACE_FRACTION = 0.07f

    fun isTooSmall(face: FaceBox, frameWidth: Int): Boolean = face.width < MIN_FACE_FRACTION * frameWidth

    /** A detection where Face Landmarker finds no face (or another face) is a false detection and is dropped. */
    fun isVerified(@Suppress("UNUSED_PARAMETER") face: FaceBox, landmarksFound: Boolean): Boolean = landmarksFound

    /**
     * Face Landmarker runs on an enlarged crop around [face], which can contain a neighbouring face. Landmarks
     * ([pts]: x, y, z per point, frame pixels) only belong to [face] if they cover it well enough.
     */
    fun landmarksMatch(face: FaceBox, pts: FloatArray): Boolean {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in pts.indices step 3) {
            l = minOf(l, pts[i]); r = maxOf(r, pts[i]); t = minOf(t, pts[i + 1]); b = maxOf(b, pts[i + 1])
        }
        return iou(face, FaceBox(l.toInt(), t.toInt(), r.toInt(), b.toInt(), 0f, 0f)) >= LANDMARKS_MIN_IOU
    }

    /** Same as LANDMARKS_MIN_IOU in server/rwr_server/policy.py. */
    const val LANDMARKS_MIN_IOU = 0.3f

    /** Eye tracking only runs on faces turned at most this far (beyond it the eyes can't reach the screen). */
    const val EYE_PREFILTER_ANGLE = 60f
    /** Head mode: faces whose rough (detector) yaw is beyond this are not measured further. */
    const val HEAD_PREFILTER_ANGLE = 45f
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

/** Intersection over union of two boxes. */
fun iou(a: FaceBox, b: FaceBox): Float {
    val iw = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val ih = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    if (iw <= 0 || ih <= 0) return 0f
    val inter = iw.toLong() * ih
    val union = a.area.toLong() + b.area - inter
    return if (union > 0) inter.toFloat() / union else 0f
}

/** Same person in consecutive frames: centres at most one face size apart. */
fun near(a: FaceBox, b: FaceBox): Boolean {
    val dx = (a.left + a.right) / 2f - (b.left + b.right) / 2f
    val dy = (a.top + a.bottom) / 2f - (b.top + b.bottom) / 2f
    val size = maxOf(maxOf(a.width, a.bottom - a.top), maxOf(b.width, b.bottom - b.top)).toFloat()
    return dx * dx + dy * dy <= size * size
}

/**
 * The owner is the largest face turned toward the camera when watching starts, then whichever face keeps
 * overlapping them (a half face at the frame edge, turned away, can be bigger than the owner's). A
 * momentary bigger false detection (a finger or hand close to the lens) can't take the owner's place,
 * which would turn the owner into an "onlooker". Same rules as OwnerTracker in server/rwr_server/policy.py.
 */
class OwnerTracker(private val keepMs: Long = 2000) {
    private var last: FaceBox? = null
    private var lastAt = 0L

    fun pick(faces: List<FaceBox>, nowMs: Long): FaceBox? {
        if (faces.isEmpty()) return null
        val prev = last?.takeIf { nowMs - lastAt <= keepMs }
        val tracked = prev?.let { p -> faces.maxBy { iou(it, p) }.takeIf { iou(it, p) >= MIN_IOU } }
        val owner = tracked ?: (faces.filter { abs(it.yaw) <= MAX_YAW }.ifEmpty { faces }).maxBy { it.area }
        last = owner
        lastAt = nowMs
        return owner
    }

    fun reset() {
        last = null
    }

    companion object {
        const val MIN_IOU = 0.3f
        /** Not checking pitch: phones are often held low, so the owner looks down at the camera. */
        const val MAX_YAW = 35f
    }
}

/**
 * Turns per-frame onlooker boxes into alerts: an onlooker must be seen at about the same place over at least
 * [minSpanMs], in at least [minRatio] of the analysed frames in that span. Time-based, so it means the same at
 * 3 fps (phone) and at 15 fps (server). False detections flicker and jump around; a person reading your screen
 * stays put. Alerts are spaced at least the cooldown apart. Same rules as AlertGate in server/rwr_server/policy.py.
 */
class AlertGate(
    private val minSpanMs: Long = 500,
    private val minRatio: Float = 0.6f,
    private val historyMs: Long = 3000,
) {
    private val recent = ArrayDeque<Pair<Long, List<FaceBox>>>()
    private var lastAlertAt = Long.MIN_VALUE

    /** Returns true when this frame should raise an alert. */
    fun onFrame(onlookers: List<FaceBox>, nowMs: Long, cooldownMs: Long): Boolean {
        recent.addLast(nowMs to onlookers)
        while (recent.isNotEmpty() && recent.first().first < nowMs - historyMs) recent.removeFirst()
        if (onlookers.isEmpty() || onlookers.none { confirmed(it, nowMs) }) return false
        if (lastAlertAt != Long.MIN_VALUE && nowMs - lastAlertAt < cooldownMs) return false
        lastAlertAt = nowMs
        recent.clear()
        return true
    }

    private fun confirmed(box: FaceBox, nowMs: Long): Boolean {
        var hits = 0
        var total = 0
        for ((ts, boxes) in recent.reversed()) {  // newest first
            total++
            if (boxes.any { near(box, it) }) {
                hits++
                if (nowMs - ts >= minSpanMs && hits >= 2 && hits.toFloat() / total >= minRatio) return true
            }
        }
        return false
    }

    fun reset() {
        recent.clear()
    }
}
