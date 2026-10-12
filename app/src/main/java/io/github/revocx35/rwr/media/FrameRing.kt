package io.github.revocx35.rwr.media

/** A camera frame as JPEG in sensor orientation; [rotation] degrees clockwise make it upright (then mirror). */
class EncodedFrame(val seq: Int, val tsMs: Long, val jpeg: ByteArray, val rotation: Int)

/**
 * The last [seconds] of frames (capped at [maxBytes]), for clips that start before the alert and for
 * looking up the frame a server alert refers to. Pure Kotlin; not thread-safe (analysis thread only).
 */
class FrameRing(var seconds: Int, private val maxBytes: Long = 48L * 1024 * 1024) {
    private val frames = ArrayDeque<EncodedFrame>()
    private var bytes = 0L

    fun add(f: EncodedFrame) {
        frames.addLast(f)
        bytes += f.jpeg.size
        val horizon = f.tsMs - seconds * 1000L
        while (frames.isNotEmpty() && (frames.first().tsMs < horizon || bytes > maxBytes)) {
            bytes -= frames.removeFirst().jpeg.size
        }
    }

    fun since(tsMs: Long): List<EncodedFrame> = frames.filter { it.tsMs >= tsMs }

    fun bySeq(seq: Int): EncodedFrame? = frames.lastOrNull { it.seq == seq }

    fun clear() {
        frames.clear()
        bytes = 0
    }

    val size: Int get() = frames.size
}

/** Constant-frame-rate resampling for clips (same as the server's clips.resample). */
object ClipTiming {
    const val MAX_FPS = 30

    fun fps(timestamps: List<Long>): Int {
        if (timestamps.size < 2) return 1
        val span = (timestamps.last() - timestamps.first()) / 1000.0
        if (span <= 0) return 1
        return Math.round((timestamps.size - 1) / span).toInt().coerceIn(1, MAX_FPS)
    }

    /** Indices into [timestamps] for each output frame; frames repeat over gaps. */
    fun resample(timestamps: List<Long>, fps: Int): List<Int> {
        if (timestamps.isEmpty()) return emptyList()
        val start = timestamps.first()
        val n = ((timestamps.last() - start) * fps / 1000).toInt() + 1
        val out = ArrayList<Int>(n)
        var j = 0
        for (k in 0 until n) {
            val t = start + k * 1000.0 / fps
            while (j + 1 < timestamps.size && timestamps[j + 1] <= t) j++
            out += j
        }
        return out
    }
}
