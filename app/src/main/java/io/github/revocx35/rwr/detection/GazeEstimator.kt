package io.github.revocx35.rwr.detection

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a face's eyes point, in degrees. Same conventions as server/rwr_server/gaze.py. */
data class Gaze(
    val headYaw: Float,
    val headPitch: Float,
    val eyeYaw: Float,
    val eyePitch: Float,
    val eyesOpen: Boolean,
    /** Iris centres (x, y) in frame pixels, for drawing the "laser" rays. */
    val irises: List<Pair<Float, Float>>,
) {
    val yaw: Float get() = headYaw + eyeYaw
    val pitch: Float get() = headPitch + eyePitch
}

/** A line from an iris along the gaze direction, in frame pixels. */
data class Ray(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/**
 * Head pose and iris gaze from MediaPipe Face Landmarker's 478 points. Pure Kotlin (unit-tested); keep it in sync
 * with the server's gaze.py: same indices, constants and signs.
 *
 * Image space, y down: yaw > 0 = turned toward image right, pitch > 0 = up, 0/0 = looking into the camera.
 * The gaze is head pose plus eye rotation: a person who turns their head away but keeps their eyes on the
 * screen still counts as looking.
 */
object GazeEstimator {
    const val CHEEK_A = 234
    const val CHEEK_B = 454
    const val FOREHEAD = 10
    const val CHIN = 152
    /** (corner, corner, upper lid, lower lid) per eye. Irises are matched to eyes by distance. */
    val EYES = listOf(intArrayOf(33, 133, 159, 145), intArrayOf(362, 263, 386, 374))
    val IRIS_CENTERS = intArrayOf(468, 473)
    const val LANDMARKS = 478

    /** Eye opening ~30 mm wide, eyeball radius ~12 mm. */
    const val EYE_GAIN = 1.25f
    /** Lid gap / eye width below this = closed. */
    const val CLOSED_RATIO = 0.10f

    /** [p] holds x, y, z per landmark in frame pixels (z scaled like x), so its size is 478 * 3. */
    fun estimate(p: FloatArray): Gaze? {
        if (p.size < LANDMARKS * 3) return null
        val (headYaw, headPitch) = headPose(p)
        val yaws = ArrayList<Float>(2)
        val pitches = ArrayList<Float>(2)
        val weights = ArrayList<Float>(2)
        val opens = ArrayList<Float>(2)
        val irises = ArrayList<Pair<Float, Float>>(2)
        for (eye in EYES) {
            var (ax, ay) = xy(p, eye[0])
            var (bx, by) = xy(p, eye[1])
            if (ax > bx) { val tx = ax; ax = bx; bx = tx; val ty = ay; ay = by; by = ty }
            val width = hypot(bx - ax, by - ay)
            if (width < 1e-6f) continue
            val exX = (bx - ax) / width
            val exY = (by - ay) / width
            val eyX = -exY
            val eyY = exX
            val cx = (ax + bx) / 2
            val cy = (ay + by) / 2
            val iris = IRIS_CENTERS.map { xy(p, it) }.minBy { (x, y) -> hypot(x - cx, y - cy) }
            val (tx, ty) = xy(p, eye[2])
            val (lx, ly) = xy(p, eye[3])
            val midX = (tx + lx) / 2
            val midY = (ty + ly) / 2
            val half = width / 2
            val h = ((iris.first - cx) * exX + (iris.second - cy) * exY) / half
            val v = ((iris.first - midX) * eyX + (iris.second - midY) * eyY) / half
            yaws += asinDeg(EYE_GAIN * h)
            pitches += -asinDeg(EYE_GAIN * v)
            weights += width
            opens += hypot(tx - lx, ty - ly) / width
            irises += iris
        }
        if (weights.isEmpty()) return null
        val total = weights.sum()
        return Gaze(
            headYaw = headYaw,
            headPitch = headPitch,
            eyeYaw = yaws.indices.sumOf { (yaws[it] * weights[it]).toDouble() }.toFloat() / total,
            eyePitch = pitches.indices.sumOf { (pitches[it] * weights[it]).toDouble() }.toFloat() / total,
            eyesOpen = opens.max() >= CLOSED_RATIO,
            irises = irises,
        )
    }

    fun headPose(p: FloatArray): Pair<Float, Float> {
        val r = sub(p, CHEEK_B, CHEEK_A)
        val u = sub(p, FOREHEAD, CHIN)
        // normal = right x up: points toward the camera (negative z) on a frontal face
        val nx = r[1] * u[2] - r[2] * u[1]
        val ny = r[2] * u[0] - r[0] * u[2]
        val nz = r[0] * u[1] - r[1] * u[0]
        val n = sqrt(nx * nx + ny * ny + nz * nz)
        if (n == 0f) return 0f to 0f
        val fx = nx / n
        val fy = ny / n
        val fz = nz / n
        val yaw = Math.toDegrees(atan2(fx, -fz).toDouble()).toFloat()
        val pitch = Math.toDegrees(atan2(-fy, hypot(fx, fz)).toDouble()).toFloat()
        return yaw to pitch
    }

    fun isLooking(g: Gaze, maxGazeAngle: Float): Boolean =
        g.eyesOpen && kotlin.math.abs(g.yaw) <= maxGazeAngle && kotlin.math.abs(g.pitch) <= maxGazeAngle

    fun laserRays(g: Gaze, length: Float): List<Ray> {
        val dx = sin(Math.toRadians(g.yaw.toDouble())).toFloat() * length
        val dy = -sin(Math.toRadians(g.pitch.toDouble())).toFloat() * length
        return g.irises.map { (x, y) -> Ray(x, y, x + dx, y + dy) }
    }

    private fun xy(p: FloatArray, i: Int) = p[i * 3] to p[i * 3 + 1]

    private fun sub(p: FloatArray, a: Int, b: Int) =
        floatArrayOf(p[a * 3] - p[b * 3], p[a * 3 + 1] - p[b * 3 + 1], p[a * 3 + 2] - p[b * 3 + 2])

    private fun asinDeg(x: Float) = Math.toDegrees(asin(x.coerceIn(-1f, 1f).toDouble())).toFloat()
}
