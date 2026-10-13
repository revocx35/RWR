package io.github.revocx35.rwr.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlookerPolicyTest {
    private fun face(size: Int, yaw: Float = 0f, pitch: Float = 0f, x: Int = 0, y: Int = 0) =
        FaceBox(x, y, x + size, y + size, yaw, pitch)

    private fun box(l: Int, t: Int, r: Int, b: Int) = FaceBox(l, t, r, b, 0f, 0f)

    private fun onlookers(faces: List<FaceBox>, ignoreOwner: Boolean = true) =
        OnlookerPolicy.onlookers(faces, if (ignoreOwner) OwnerTracker().pick(faces, 0) else null, 25f)

    @Test fun ownerAloneIsNotAnOnlooker() {
        assertTrue(onlookers(listOf(face(400))).isEmpty())
    }

    @Test fun secondFaceLookingIsAnOnlooker() {
        val behind = face(120, yaw = 10f, x = 500)
        assertEquals(listOf(behind), onlookers(listOf(face(400), behind)))
    }

    @Test fun secondFaceTurnedAwayIsIgnored() {
        val faces = listOf(face(400), face(120, yaw = 60f, x = 500), face(100, pitch = -40f, x = 700))
        assertTrue(onlookers(faces).isEmpty())
    }

    @Test fun withoutOwnerModeEveryLookingFaceCounts() {
        assertEquals(2, onlookers(listOf(face(400), face(120, x = 500)), ignoreOwner = false).size)
    }

    @Test fun ownerIsLargestEvenIfNotFirst() {
        val owner = face(400, x = 300)
        val other = face(150, yaw = -5f)
        assertEquals(listOf(other), onlookers(listOf(other, owner)))
    }

    @Test fun ownerStaysOwnerWhenABiggerFalseFaceAppears() {
        val tracker = OwnerTracker()
        assertSame(null, tracker.pick(emptyList(), 0))
        val me = box(200, 300, 450, 650)
        assertSame(me, tracker.pick(listOf(me), 0))
        // A blurry finger at the frame edge detected as a huge "face" must not make the owner an onlooker.
        val finger = box(0, 0, 400, 500)
        val meNow = box(210, 310, 455, 660)
        assertSame(meNow, tracker.pick(listOf(finger, meNow), 330))
        val meMoved = box(240, 330, 490, 680)
        assertSame(meMoved, tracker.pick(listOf(meMoved), 660))
        // Gone for a while: the largest face is the owner again.
        val big = box(600, 600, 700, 720)
        assertSame(big, tracker.pick(listOf(box(10, 10, 60, 70), big), 5000))
    }

    @Test fun ownerIsNotABiggerFaceTurnedAway() {
        // Seen on the emulator: a half face cut off by the frame edge, turned 56 degrees, is bigger than the owner.
        val edge = FaceBox(21, 227, 266, 544, 56f, -5f)
        val me = FaceBox(214, 292, 447, 524, 3.5f, -8.8f)
        assertSame(me, OwnerTracker().pick(listOf(me, edge), 0))
        assertTrue(OnlookerPolicy.onlookers(listOf(me, edge), OwnerTracker().pick(listOf(me, edge), 0), 25f).isEmpty())
        // Nobody facing the camera: the largest face is still picked.
        val turned = me.copy(yaw = 40f)
        assertSame(edge, OwnerTracker().pick(listOf(turned, edge), 0))
    }

    @Test fun landmarksOfANeighbouringFaceAreRejected() {
        // Same case as test_landmarks_of_a_neighbouring_face_are_rejected on the server.
        val ownerMesh = floatArrayOf(230f, 300f, 0f, 440f, 520f, 0f)
        assertFalse(OnlookerPolicy.landmarksMatch(FaceBox(21, 227, 266, 544, 56f, 0f), ownerMesh))
        assertTrue(OnlookerPolicy.landmarksMatch(FaceBox(214, 292, 447, 524, 3.5f, 0f), ownerMesh))
    }

    @Test fun bigFaceWithoutLandmarksIsDropped() {
        assertFalse(OnlookerPolicy.isVerified(face(80), landmarksFound = false))
        assertTrue(OnlookerPolicy.isVerified(face(80), landmarksFound = true))
        assertTrue(OnlookerPolicy.isVerified(face(20), landmarksFound = false))  // too small to check
    }

    private val onlooker = listOf(box(300, 40, 360, 110))

    @Test fun gateNeedsTwoOfThreeFrames() {
        val gate = AlertGate()
        assertFalse(gate.onFrame(onlooker, 0, 10_000))
        assertFalse(gate.onFrame(emptyList(), 300, 10_000))
        assertTrue(gate.onFrame(onlooker, 600, 10_000))
    }

    @Test fun gateRespectsCooldown() {
        val gate = AlertGate()
        gate.onFrame(onlooker, 0, 10_000)
        assertTrue(gate.onFrame(onlooker, 300, 10_000))
        gate.onFrame(onlooker, 600, 10_000)
        assertFalse(gate.onFrame(onlooker, 900, 10_000))
        gate.onFrame(onlooker, 10_000, 10_000)
        assertTrue(gate.onFrame(onlooker, 10_400, 10_000))
    }

    @Test fun gateIgnoresIsolatedFalsePositives() {
        val gate = AlertGate()
        var t = 0L
        repeat(10) {
            assertFalse(gate.onFrame(if (it % 3 == 0) onlooker else emptyList(), t, 0))
            t += 300
        }
    }

    @Test fun gateFollowsAMovingOnlookerButNotJumpingFalseFaces() {
        val gate = AlertGate()
        assertFalse(gate.onFrame(onlooker, 0, 0))
        assertTrue(gate.onFrame(listOf(box(320, 50, 380, 120)), 300, 0))
        // False detections in a different place each frame (seen on a car ceiling sticker and a seat).
        // Same sequence as test_alert_gate_follows_a_moving_onlooker_but_ignores_flicker on the server.
        val flicker = listOf(
            listOf(box(77, 13, 139, 88)), emptyList(), listOf(box(589, 878, 658, 960)),
            listOf(box(3, 2, 102, 116)), emptyList(), listOf(box(539, 901, 592, 960)),
        )
        val g2 = AlertGate()
        flicker.forEachIndexed { i, b -> assertFalse(g2.onFrame(b, i * 300L, 0)) }
    }
}
