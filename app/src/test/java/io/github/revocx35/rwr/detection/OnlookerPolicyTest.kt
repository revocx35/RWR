package io.github.revocx35.rwr.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlookerPolicyTest {
    private fun face(size: Int, yaw: Float = 0f, pitch: Float = 0f, x: Int = 0) =
        FaceBox(x, 0, x + size, size, yaw, pitch)

    @Test fun ownerAloneIsNotAnOnlooker() {
        assertTrue(OnlookerPolicy.onlookers(listOf(face(400)), ignoreOwner = true, maxHeadAngle = 25f).isEmpty())
    }

    @Test fun secondFaceLookingIsAnOnlooker() {
        val behind = face(120, yaw = 10f, x = 500)
        val result = OnlookerPolicy.onlookers(listOf(face(400), behind), ignoreOwner = true, maxHeadAngle = 25f)
        assertEquals(listOf(behind), result)
    }

    @Test fun secondFaceTurnedAwayIsIgnored() {
        val faces = listOf(face(400), face(120, yaw = 60f, x = 500), face(100, pitch = -40f, x = 700))
        assertTrue(OnlookerPolicy.onlookers(faces, ignoreOwner = true, maxHeadAngle = 25f).isEmpty())
    }

    @Test fun withoutOwnerModeEveryLookingFaceCounts() {
        val faces = listOf(face(400), face(120, x = 500))
        assertEquals(2, OnlookerPolicy.onlookers(faces, ignoreOwner = false, maxHeadAngle = 25f).size)
    }

    @Test fun ownerIsLargestEvenIfNotFirst() {
        val owner = face(400, x = 300)
        val other = face(150, yaw = -5f)
        assertEquals(listOf(other), OnlookerPolicy.onlookers(listOf(other, owner), true, 25f))
    }

    @Test fun gateNeedsTwoOfThreeFrames() {
        val gate = AlertGate()
        assertFalse(gate.onFrame(true, 0, 10_000))
        assertFalse(gate.onFrame(false, 300, 10_000))
        assertTrue(gate.onFrame(true, 600, 10_000))
    }

    @Test fun gateRespectsCooldown() {
        val gate = AlertGate()
        gate.onFrame(true, 0, 10_000)
        assertTrue(gate.onFrame(true, 300, 10_000))
        gate.onFrame(true, 600, 10_000)
        assertFalse(gate.onFrame(true, 900, 10_000))
        gate.onFrame(true, 10_000, 10_000)
        assertTrue(gate.onFrame(true, 10_400, 10_000))
    }

    @Test fun gateIgnoresIsolatedFalsePositives() {
        val gate = AlertGate()
        var t = 0L
        repeat(10) {
            assertFalse(gate.onFrame(it % 3 == 0, t, 0))
            t += 300
        }
    }
}
