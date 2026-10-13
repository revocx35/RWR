package io.github.revocx35.rwr.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class YuNetTest {
    /** Empty outputs for all strides, with one cell of [stride] set to a face. */
    private fun outputs(vararg faces: Triple<Int, Int, FloatArray>): List<YuNet.StrideOutput> = YuNet.STRIDES.map { s ->
        val n = (YuNet.INPUT_SIZE / s) * (YuNet.INPUT_SIZE / s)
        val o = YuNet.StrideOutput(s, FloatArray(n), FloatArray(n), FloatArray(4 * n), FloatArray(10 * n))
        for ((stride, cell, values) in faces) if (stride == s) {
            o.cls[cell] = values[0]; o.obj[cell] = values[1]
            for (k in 0 until 4) o.bbox[cell * 4 + k] = values[2 + k]
        }
        o
    }

    @Test fun decodesBoxFromCellOffsetsAndLogSize() {
        val cols = YuNet.INPUT_SIZE / 16
        val cell = 10 * cols + 7                                   // row 10, column 7 of the stride-16 grid
        val face = floatArrayOf(0.9f, 0.9f, 0.5f, 0.25f, ln(4f), ln(5f))  // centre (7.5, 10.25) cells, 4x5 cells
        val d = YuNet.decode(outputs(Triple(16, cell, face))).single()
        assertEquals(0.9f, d.score, 1e-4f)
        assertEquals(7.5f * 16 - 32f, d.x, 1e-3f)
        assertEquals(10.25f * 16 - 40f, d.y, 1e-3f)
        assertEquals(64f, d.w, 1e-3f)
        assertEquals(80f, d.h, 1e-3f)
    }

    @Test fun scoreIsGeometricMeanAndThresholded() {
        val weak = floatArrayOf(0.9f, 0.3f, 0f, 0f, 0f, 0f)        // sqrt(0.27) = 0.52 < 0.6
        assertTrue(YuNet.decode(outputs(Triple(8, 5, weak))).isEmpty())
    }

    @Test fun overlappingDetectionsAreMerged() {
        val cols = YuNet.INPUT_SIZE / 8
        val a = floatArrayOf(0.95f, 0.95f, 0f, 0f, ln(8f), ln(8f))
        val b = floatArrayOf(0.8f, 0.8f, 0f, 0f, ln(8f), ln(8f))   // one cell to the right: same face
        val far = floatArrayOf(0.8f, 0.8f, 0f, 0f, ln(4f), ln(4f))
        val dets = YuNet.decode(outputs(Triple(8, 20 * cols + 20, a), Triple(8, 20 * cols + 21, b), Triple(8, 60 * cols + 60, far)))
        assertEquals(2, dets.size)
        assertEquals(0.95f, dets[0].score, 1e-4f)
    }

    @Test fun roughPoseOfAFrontalFaceIsNearZero() {
        // right eye, left eye, nose, mouth right, mouth left
        val (yaw, pitch) = YuNet.roughPose(floatArrayOf(40f, 50f, 80f, 50f, 60f, 72f, 45f, 90f, 75f, 90f))
        assertEquals(0f, yaw, 1f)
        assertEquals(0f, pitch, 1f)
        val (turned, _) = YuNet.roughPose(floatArrayOf(40f, 50f, 80f, 50f, 72f, 72f, 45f, 90f, 75f, 90f))
        assertTrue(turned > 30f)
    }
}
