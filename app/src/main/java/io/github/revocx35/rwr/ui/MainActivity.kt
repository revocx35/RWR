package io.github.revocx35.rwr.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.revocx35.rwr.Prefs
import io.github.revocx35.rwr.R
import io.github.revocx35.rwr.alert.SnapshotStore
import io.github.revocx35.rwr.detection.WatcherService
import io.github.revocx35.rwr.tile.WatchTileService
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs

    /** Bumped on resume so permission rows re-read their state after returning from Settings. */
    private var resumeTick by mutableIntStateOf(0)
    private var startAfterGrant = false

    private val requestPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick++
        if (startAfterGrant && has(Manifest.permission.CAMERA)) WatcherService.start(this)
        startAfterGrant = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun runtimePermissions() = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startWatching() {
        val missing = runtimePermissions().filterNot(::has)
        if (missing.isEmpty()) {
            WatcherService.start(this)
        } else {
            startAfterGrant = true
            requestPerms.launch(missing.toTypedArray())
        }
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, WatchTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_eye),
            mainExecutor,
        ) { result ->
            val msg = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Tile is already in Quick Settings"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Tile not added"
                else -> "Add it manually: swipe down twice, tap the pencil, drag \"${getString(R.string.tile_label)}\""
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun Screen() {
        val running by WatcherService.running.collectAsStateWithLifecycle()
        @Suppress("UNUSED_VARIABLE") val tick = resumeTick // recompose permission rows on resume

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("RWR", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Uses the front camera to spot people looking at your screen from behind, then alerts you " +
                    "with a snapshot. Snapshots are saved to Pictures/${SnapshotStore.ALBUM}. Nothing leaves the device.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (running) "● Watching" else "○ Not watching",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { if (running) WatcherService.stop(this@MainActivity) else startWatching() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        else ButtonDefaults.buttonColors(),
                    ) { Text(if (running) "Stop" else "Start watching") }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlinedButton(onClick = ::requestTile, modifier = Modifier.fillMaxWidth()) {
                            Text("Add Quick Settings toggle")
                        }
                    }
                }
            }

            Section("Permissions") {
                PermissionRow("Camera", has(Manifest.permission.CAMERA)) {
                    requestPerms.launch(arrayOf(Manifest.permission.CAMERA))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow("Notifications", has(Manifest.permission.POST_NOTIFICATIONS)) {
                        requestPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
                PermissionRow("Display over other apps (pop-up)", Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
            }

            Section("Settings") {
                var ignoreOwner by remember { mutableStateOf(prefs.ignoreOwner) }
                SwitchRow("Ignore my face (largest face in view)", ignoreOwner) {
                    ignoreOwner = it; prefs.ignoreOwner = it
                }
                var overlay by remember { mutableStateOf(prefs.overlayPopup) }
                SwitchRow("Pop-up over other apps", overlay) { overlay = it; prefs.overlayPopup = it }

                var angle by remember { mutableFloatStateOf(prefs.maxHeadAngle) }
                Text("Sensitivity: head turned up to ${angle.roundToInt()}° counts as looking")
                Slider(
                    value = angle, onValueChange = { angle = it }, valueRange = 10f..45f,
                    onValueChangeFinished = { prefs.maxHeadAngle = angle },
                )
                var cooldown by remember { mutableFloatStateOf(prefs.cooldownSec.toFloat()) }
                Text("Minimum time between alerts: ${cooldown.roundToInt()} s")
                Slider(
                    value = cooldown, onValueChange = { cooldown = it }, valueRange = 5f..120f,
                    onValueChangeFinished = { prefs.cooldownSec = cooldown.roundToInt() },
                )
            }
            Text(
                "Tip: Android shows a green camera dot while RWR is watching. The camera is released " +
                    "automatically while the screen is off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text((if (granted) "✓ " else "✗ ") + label, Modifier.weight(1f))
            if (!granted) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onGrant) { Text("Grant") }
            }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
