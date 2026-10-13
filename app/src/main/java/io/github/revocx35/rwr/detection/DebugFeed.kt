package io.github.revocx35.rwr.detection

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live detection data for the debug view ([io.github.revocx35.rwr.ui.DebugActivity]). [WatcherService]
 * publishes only while the view is open, and nothing is stored: frames live in memory until the next one
 * arrives.
 */
object DebugFeed {
    enum class Source { PHONE, SERVER }

    data class Frame(
        /** Upright, selfie-mirrored; face boxes are in its pixel coordinates. */
        val image: Bitmap,
        val verdicts: List<FaceVerdict>,
        val source: Source,
        val mode: DetectionMode,
        /** Time spent on detection for this frame (phone or server). */
        val analysisMs: Long,
        val ts: Long,
    )

    private val viewers = MutableStateFlow(0)
    private val _frame = MutableStateFlow<Frame?>(null)
    private val _lastAlertAt = MutableStateFlow(0L)

    /** True while a debug view is visible. */
    val active: Boolean get() = viewers.value > 0
    val viewerCount: StateFlow<Int> = viewers.asStateFlow()
    val frame: StateFlow<Frame?> = _frame.asStateFlow()
    val lastAlertAt: StateFlow<Long> = _lastAlertAt.asStateFlow()

    fun open() = viewers.update { it + 1 }

    fun close() {
        viewers.update { (it - 1).coerceAtLeast(0) }
        if (!active) _frame.value = null
    }

    fun publish(frame: Frame) {
        if (active) _frame.value = frame
    }

    fun alerted(ts: Long) {
        if (active) _lastAlertAt.value = ts
    }
}
