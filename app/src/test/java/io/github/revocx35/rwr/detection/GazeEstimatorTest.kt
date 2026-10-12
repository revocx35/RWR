package io.github.revocx35.rwr.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class GazeEstimatorTest {
    /** Minimal 478-point face (same construction as the server's test_logic.py). */
    private fun face(headYawDeg: Float = 0f, irisShift: Float = 0f): FloatArray {
        val p = FloatArray(GazeEstimator.LANDMARKS * 3)
        val a = Math.toRadians(-headYawDeg.toDouble())  // positive = face turns toward image right
        fun put(i: Int, x: Float, y: Float, z: Float = 0f) {
            val xr = x * cos(a) + z * sin(a)
            val zr = -x * sin(a) + z * cos(a)
            p[i * 3] = (500 + xr).toFloat(); p[i * 3 + 1] = 500 + y; p[i * 3 + 2] = zr.toFloat()
        }
        put(GazeEstimator.CHEEK_A, -100f, 0f, 60f)
        put(GazeEstimator.CHEEK_B, 100f, 0f, 60f)
        put(GazeEstimator.FOREHEAD, 0f, -120f, 30f)
        put(GazeEstimator.CHIN, 0f, 120f, 30f)
        GazeEstimator.EYES.forEachIndexed { e, idx ->
            val cx = if (e == 0) -45f else 45f
            put(idx[0], cx - 15, -30f); put(idx[1], cx + 15, -30f)
            put(idx[2], cx, -36f); put(idx[3], cx, -24f)
            put(GazeEstimator.IRIS_CENTERS[e], cx + irisShift, -30f)
        }
        return p
    }

    @Test fun frontalFaceLooksAtCamera() {
        val g = GazeEstimator.estimate(face())!!
        assertTrue(abs(g.yaw) < 1 && abs(g.pitch) < 1 && g.eyesOpen)
        assertTrue(GazeEstimator.isLooking(g, 10f))
    }

    @Test fun eyesLookingSideways() {
        val g = GazeEstimator.estimate(face(irisShift = 6f))!!
        assertTrue(g.eyeYaw > 15)
        assertFalse(GazeEstimator.isLooking(g, 10f))
    }

    @Test fun headTurnCompensatedByEyes() {
        val turned = GazeEstimator.estimate(face(headYawDeg = 25f))!!
        assertEquals(25f, turned.headYaw, 1f)
        val compensated = GazeEstimator.estimate(face(headYawDeg = 25f, irisShift = -5f))!!
        assertTrue(abs(compensated.yaw) < abs(turned.yaw))
    }

    @Test fun closedEyesAreNotLooking() {
        val p = face()
        GazeEstimator.EYES.forEach { p[it[2] * 3 + 1] = 470f; p[it[3] * 3 + 1] = 470f }
        val g = GazeEstimator.estimate(p)!!
        assertFalse(g.eyesOpen)
        assertFalse(GazeEstimator.isLooking(g, 45f))
    }

    @Test fun tooFewLandmarks() = assertNull(GazeEstimator.estimate(FloatArray(468 * 3)))

    @Test fun laserRaysStartAtIrises() {
        val g = GazeEstimator.estimate(face(irisShift = 6f))!!
        val rays = GazeEstimator.laserRays(g, 100f)
        assertEquals(2, rays.size)
        assertTrue(rays.all { it.x1 > it.x0 })  // looking toward image right
    }

    @Test fun eyeModeFallsBackToHeadForSmallFaces() {
        val small = FaceBox(0, 0, 30, 30, yaw = 5f, pitch = 0f)
        val sideGaze = GazeEstimator.estimate(face(irisShift = 6f))
        // Too small for eyes: the head rule decides (looking).
        assertTrue(OnlookerPolicy.isLookingEyeMode(small, sideGaze, 25f, 10f))
        val big = small.copy(right = 200, bottom = 200)
        assertFalse(OnlookerPolicy.isLookingEyeMode(big, sideGaze, 25f, 10f))
        assertTrue(OnlookerPolicy.isLookingEyeMode(big, null, 25f, 10f))
    }
}
