package io.github.revocx35.rwr.tile

import android.app.Activity
import android.os.Bundle
import io.github.revocx35.rwr.detection.WatcherService

/**
 * Invisible trampoline used by the Quick Settings tile. Being a (briefly) foreground activity is what
 * allows starting a camera-type foreground service on Android 14+.
 */
class StartWatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatcherService.start(this)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
