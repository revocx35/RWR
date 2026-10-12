package io.github.revocx35.rwr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.revocx35.rwr.detection.WatcherService

/**
 * Debug builds only. `adb shell am broadcast -n io.github.revocx35.rwr/.DebugInjectReceiver --es path x.jpg`
 * runs files/x.jpg (in the app's private files dir) through detection as if it were a camera frame.
 */
class DebugInjectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(WatcherService.EXTRA_PATH) ?: return
        context.startService(
            Intent(context, WatcherService::class.java)
                .setAction(WatcherService.ACTION_DEBUG_INJECT)
                .putExtra(WatcherService.EXTRA_PATH, path)
        )
    }
}
