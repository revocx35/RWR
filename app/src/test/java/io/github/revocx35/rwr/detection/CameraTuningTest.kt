package io.github.revocx35.rwr.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraTuningTest {
    // A typical phone front camera.
    private val phone = listOf(15..15, 7..30, 15..30, 30..30)

    @Test fun lowTargetGetsSlowestRangeThatReachesIt() {
        assertEquals(15..15, CameraTuning.chooseFpsRange(phone, 5))
        assertEquals(15..15, CameraTuning.chooseFpsRange(phone, 10))
        assertEquals(15..15, CameraTuning.chooseFpsRange(phone, 15))
    }

    @Test fun thirtyPrefersVariableRangeForDarkScenes() {
        assertEquals(7..30, CameraTuning.chooseFpsRange(phone, 30))
    }

    @Test fun exactLowRangeIsUsedWhenAvailable() {
        assertEquals(5..5, CameraTuning.chooseFpsRange(listOf(5..5, 5..30, 30..30), 5))
    }

    @Test fun targetAboveEverythingFallsBackToFastest() {
        assertEquals(15..30, CameraTuning.chooseFpsRange(listOf(15..15, 15..30), 60))
    }

    @Test fun noRangesReturnsNull() {
        assertNull(CameraTuning.chooseFpsRange(emptyList(), 5))
    }
}
