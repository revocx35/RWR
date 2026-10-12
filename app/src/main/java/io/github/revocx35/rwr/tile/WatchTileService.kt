package io.github.revocx35.rwr.tile

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.revocx35.rwr.detection.WatcherService
import io.github.revocx35.rwr.ui.MainActivity

/** Quick Settings toggle. The user adds it from the QS edit screen (or via the in-app button on 13+). */
class WatchTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (WatcherService.running.value) {
            WatcherService.stop(this)
            updateTile(forceRunning = false)
            return
        }
        val target = if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            StartWatchActivity::class.java
        } else {
            // First run: the app UI asks for the camera permission.
            MainActivity::class.java
        }
        val launch = { launchActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        if (isLocked) unlockAndRun(launch) else launch()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchActivity(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(forceRunning: Boolean = WatcherService.running.value) {
        val tile = qsTile ?: return
        tile.state = if (forceRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (forceRunning) "Watching" else "Off"
        }
        tile.updateTile()
    }
}
