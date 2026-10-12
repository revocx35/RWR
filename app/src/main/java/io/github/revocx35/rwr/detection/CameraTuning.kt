package io.github.revocx35.rwr.detection

/** Pure helpers for camera configuration, unit-tested on the JVM. */
object CameraTuning {
    val FPS_CHOICES = listOf(5, 10, 15, 30)

    /**
     * Picks the auto-exposure FPS range to request for [targetFps] from the camera's supported ranges.
     * Prefers the lowest upper bound that still reaches the target (frames we don't need cost power),
     * then the lowest lower bound (lets exposure slow down further in the dark). If nothing reaches
     * the target, the fastest range is used. Returns null when the camera reports no ranges.
     */
    fun chooseFpsRange(supported: List<IntRange>, targetFps: Int): IntRange? {
        if (supported.isEmpty()) return null
        val reaching = supported.filter { it.last >= targetFps }
        if (reaching.isEmpty()) return supported.maxWith(compareBy({ it.last }, { -it.first }))
        return reaching.minWith(compareBy({ it.last }, { it.first }))
    }
}
