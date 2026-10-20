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
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.lifecycle.lifecycleScope
import io.github.revocx35.rwr.MediaType
import io.github.revocx35.rwr.Prefs
import io.github.revocx35.rwr.R
import io.github.revocx35.rwr.SaveTo
import io.github.revocx35.rwr.alert.SnapshotStore
import io.github.revocx35.rwr.detection.CameraTuning
import io.github.revocx35.rwr.detection.DetectionMode
import io.github.revocx35.rwr.detection.WatcherService
import io.github.revocx35.rwr.server.AuthentikRequiredException
import io.github.revocx35.rwr.server.RwrAuthentik
import io.github.revocx35.rwr.server.ServerAccount
import io.github.revocx35.rwr.server.ServerApi
import io.github.revocx35.rwr.tile.WatchTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs

    /** Bumped on resume so permission rows re-read their state after returning from Settings. */
    private var resumeTick by mutableIntStateOf(0)
    private var startAfterGrant = false
    private var account by mutableStateOf<ServerAccount?>(null)

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
        account = ServerAccount.load(this)
        refreshDevicePermission()
    }

    /** The admin may have changed this phone's "can configure" permission in the web UI. */
    private fun refreshDevicePermission() {
        val a = account ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ServerApi.deviceInfo(a) } }
            if (result.exceptionOrNull() is AuthentikRequiredException) RwrAuthentik.markNeeded()
            val info = result.getOrNull() ?: return@launch
            val can = info.optBoolean("can_configure")
            if (can != a.canConfigure) {
                ServerAccount.updateCanConfigure(this@MainActivity, can)
                account = ServerAccount.load(this@MainActivity)
            }
        }
    }

    private fun unpair() {
        val a = account ?: return
        lifecycleScope.launch {
            // Best effort: revoke the token on the server too, so it is useless even if copied.
            withContext(Dispatchers.IO) {
                runCatching { ServerApi.unpair(a) }
                RwrAuthentik.signOut()
            }
            ServerAccount.clear(this@MainActivity)
            prefs.processOnServer = false
            if (prefs.saveTo.immich) prefs.saveTo = SaveTo.GALLERY
            account = null
            WatcherService.reloadServer(this@MainActivity)
            Toast.makeText(this@MainActivity, "Unpaired", Toast.LENGTH_SHORT).show()
        }
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
                    "with a snapshot or clip. They are saved to Pictures/${SnapshotStore.ALBUM}. Nothing leaves the " +
                    "phone unless you pair your own RWR server.",
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

            Section("Detection") {
                var mode by remember { mutableStateOf(prefs.detectionMode) }
                ChoiceRow(listOf(DetectionMode.HEAD, DetectionMode.EYE), mode, {
                    if (it == DetectionMode.HEAD) "Head tracking" else "Eye tracking"
                }) { mode = it; prefs.detectionMode = it }
                Hint(
                    if (mode == DetectionMode.HEAD) "Alerts when a head is turned toward the screen. Lightest on battery."
                    else "Follows the irises: only alerts when the eyes point at the screen, even if the head is " +
                        "turned. More accurate, more CPU on the phone (none in server mode)."
                )
                var ignoreOwner by remember { mutableStateOf(prefs.ignoreOwner) }
                SwitchRow("Ignore my face (largest face turned toward the camera)", ignoreOwner) {
                    ignoreOwner = it; prefs.ignoreOwner = it
                }
                var angle by remember { mutableFloatStateOf(prefs.maxHeadAngle) }
                Text("Head turned up to ${angle.roundToInt()}° counts as looking" +
                    if (mode == DetectionMode.EYE) " (used when a face is too small for eye tracking)" else "")
                Slider(
                    value = angle, onValueChange = { angle = it }, valueRange = 10f..45f,
                    onValueChangeFinished = { prefs.maxHeadAngle = angle },
                )
                if (mode == DetectionMode.EYE) {
                    var gaze by remember { mutableFloatStateOf(prefs.maxGazeAngle) }
                    Text("Eyes within ${gaze.roundToInt()}° of the screen count as looking")
                    Slider(
                        value = gaze, onValueChange = { gaze = it }, valueRange = 5f..30f,
                        onValueChangeFinished = { prefs.maxGazeAngle = gaze },
                    )
                }
                var cooldown by remember { mutableFloatStateOf(prefs.cooldownSec.toFloat()) }
                Text("Minimum time between alerts: ${cooldown.roundToInt()} s")
                Slider(
                    value = cooldown, onValueChange = { cooldown = it }, valueRange = 5f..120f,
                    onValueChangeFinished = { prefs.cooldownSec = cooldown.roundToInt() },
                )
            }

            Section("Snapshots") {
                var overlay by remember { mutableStateOf(prefs.overlayPopup) }
                SwitchRow("Pop-up over other apps", overlay) { overlay = it; prefs.overlayPopup = it }

                var media by remember { mutableStateOf(prefs.mediaType) }
                ChoiceRow(listOf(MediaType.PHOTO, MediaType.VIDEO), media, {
                    if (it == MediaType.PHOTO) "Photo" else "Video clip"
                }) { media = it; prefs.mediaType = it }
                if (media == MediaType.VIDEO) {
                    var pre by remember { mutableFloatStateOf(prefs.preSec.toFloat()) }
                    var post by remember { mutableFloatStateOf(prefs.postSec.toFloat()) }
                    Text("Clip: ${pre.roundToInt()} s before to ${post.roundToInt()} s after the alert")
                    Text("Before", style = MaterialTheme.typography.bodySmall)
                    Slider(value = pre, onValueChange = { pre = it }, valueRange = 0f..30f, steps = 29,
                        onValueChangeFinished = { prefs.preSec = pre.roundToInt() })
                    Text("After", style = MaterialTheme.typography.bodySmall)
                    Slider(value = post, onValueChange = { post = it }, valueRange = 0f..30f, steps = 29,
                        onValueChangeFinished = { prefs.postSec = post.roundToInt() })
                    Hint("Clips keep the last seconds of camera frames in memory, which costs some battery. " +
                        "The clip frame rate follows the camera frame rate under Battery.")
                }

                val paired = account != null
                var saveTo by remember { mutableStateOf(prefs.saveTo) }
                Text("Save to")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(SaveTo.GALLERY, SaveTo.IMMICH, SaveTo.BOTH).forEach { option ->
                        FilterChip(
                            selected = option == saveTo,
                            enabled = option == SaveTo.GALLERY || paired,
                            onClick = { saveTo = option; prefs.saveTo = option },
                            label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Hint(if (paired) "Immich uploads go through your RWR server (set Immich up in Server settings)."
                    else "Immich needs a paired RWR server (see Server below).")
            }

            ServerSection()
            Section("Battery") {
                var fps by remember { mutableIntStateOf(prefs.cameraFps) }
                Text("Camera frame rate")
                ChoiceRow(CameraTuning.FPS_CHOICES, fps, { "$it fps" }) { fps = it; prefs.cameraFps = it }
                Hint("Lower uses less battery. If your phone can't go that low, the closest rate it supports is used.")

                var lowRes by remember { mutableStateOf(prefs.lowResolution) }
                Text("Camera resolution")
                ChoiceRow(listOf(true, false), lowRes, { if (it) "Low 640×480" else "High 1280×720" }) {
                    lowRes = it; prefs.lowResolution = it
                }
                Hint("High gives sharper snapshots but uses more power.")

                var efficient by remember { mutableStateOf(prefs.efficientConversion) }
                SwitchRow("Convert only analysed frames", efficient) { efficient = it; prefs.efficientConversion = it }
                Hint("Recommended. Turning it off converts every camera frame (the old behaviour). Only useful for troubleshooting.")
            }
            Section("Troubleshooting") {
                OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, DebugActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("Debug view") }
                Hint("Live view of what detection sees: every face, why it counts or not, angles and gaze. Works with " +
                    "detection on the phone and on the server. Nothing is saved.")
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
    private fun ServerSection() {
        val a = account
        val state by WatcherService.serverState.collectAsStateWithLifecycle()
        val running by WatcherService.running.collectAsStateWithLifecycle()
        val viewers by WatcherService.webViewers.collectAsStateWithLifecycle()
        Section("Server") {
            if (a == null) {
                Text("Run detection on your own RWR server (Docker) instead of the phone: max-quality " +
                    "detection without the CPU cost, plus saving to Immich.")
                OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, PairActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("Pair with server") }
                return@Section
            }
            Text("Paired with ${a.baseUrl} as “${a.deviceName}”", style = MaterialTheme.typography.bodyMedium)
            val authentikNeeded by RwrAuthentik.needed.collectAsStateWithLifecycle()
            if (authentikNeeded) {
                AuthentikPrompt(this@MainActivity, a.baseUrl, a.pin) {
                    WatcherService.reloadServer(this@MainActivity)
                    refreshDevicePermission()
                }
            }
            var onServer by remember { mutableStateOf(prefs.processOnServer) }
            Text("Detection runs")
            ChoiceRow(listOf(false, true), onServer, { if (it) "On server" else "On this phone" }) {
                onServer = it; prefs.processOnServer = it
            }
            if (onServer && running) {
                Text(
                    when (state) {
                        WatcherService.ServerState.CONNECTED -> "● Connected, frames go to the server"
                        WatcherService.ServerState.CONNECTING -> "○ Connecting…"
                        WatcherService.ServerState.FALLBACK -> "○ Server unreachable, detecting on the phone"
                        WatcherService.ServerState.REJECTED -> "✗ The server removed this phone. Unpair and pair again."
                        WatcherService.ServerState.OFF -> "○ Paused"
                    },
                    color = if (state == WatcherService.ServerState.CONNECTED) Color(0xFF2E7D32)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (viewers > 0 && state == WatcherService.ServerState.CONNECTED) {
                    Text("● Live view open in the server's web UI: someone is watching this camera",
                        color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
            if (onServer) {
                var fallback by remember { mutableStateOf(prefs.fallbackLocal) }
                SwitchRow("Detect on the phone while the server is unreachable", fallback) {
                    fallback = it; prefs.fallbackLocal = it
                }
                Hint("Frames are sent at the camera frame rate (Battery section) over an encrypted connection " +
                    "locked to your server's certificate.")
            }
            if (a.canConfigure) {
                OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, ServerSettingsActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("Server settings (Immich, history…)") }
            }
            var confirm by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Unpair") }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Unpair from the server?") },
                    text = { Text("This phone's access is revoked on the server. You can pair again with a new QR code.") },
                    confirmButton = { TextButton(onClick = { confirm = false; unpair() }) { Text("Unpair") } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                )
            }
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
    private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
