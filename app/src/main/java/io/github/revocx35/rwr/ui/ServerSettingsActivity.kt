package io.github.revocx35.rwr.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.revocx35.rwr.server.ServerAccount
import io.github.revocx35.rwr.server.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Edits the RWR server's settings (Immich, event history, detector) through the device API. */
class ServerSettingsActivity : ComponentActivity() {
    private var loaded by mutableStateOf<JSONObject?>(null)
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

    // Form state
    private var immichEnabled by mutableStateOf(false)
    private var immichUrl by mutableStateOf("")
    private var immichKey by mutableStateOf("")
    private var keySet by mutableStateOf(false)
    private var album by mutableStateOf("")
    private var allowHttp by mutableStateOf(false)
    private var keepEvents by mutableStateOf(true)
    private var retention by mutableStateOf("7")
    private var minFace by mutableStateOf("16")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = ServerAccount.load(this) ?: run { finish(); return }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(account) }
            }
        }
        run(account) { ServerApi.getConfig(account).also { fill(it) } }
    }

    private fun fill(c: JSONObject) {
        loaded = c
        immichEnabled = c.optBoolean("immich_enabled")
        immichUrl = c.optString("immich_url")
        keySet = c.optBoolean("immich_api_key_set")
        immichKey = ""
        album = c.optString("immich_album")
        allowHttp = c.optBoolean("immich_allow_http")
        keepEvents = c.optBoolean("keep_events", true)
        retention = c.optInt("retention_days", 7).toString()
        minFace = c.optInt("min_face_px", 16).toString()
    }

    private fun run(account: ServerAccount, block: () -> Any?) {
        busy = true
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            busy = false
            r.onFailure { message = it.message ?: it.javaClass.simpleName }
        }
    }

    @Composable
    private fun Screen(account: ServerAccount) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Server settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(account.baseUrl, style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (loaded == null) {
                Text(if (busy) "Loading…" else "Could not load the settings.")
                return@Column
            }
            Text("Immich", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Upload to Immich", immichEnabled) { immichEnabled = it }
            OutlinedTextField(immichUrl, { immichUrl = it.trim() }, Modifier.fillMaxWidth(),
                label = { Text("Immich URL (https://…)") }, singleLine = true)
            OutlinedTextField(
                immichKey, { immichKey = it.trim() }, Modifier.fillMaxWidth(),
                label = { Text(if (keySet) "API key (saved, leave empty to keep)" else "API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text(
                "Key permissions: asset.upload, plus album.read, album.create, albumAsset.create for the album. " +
                    "The key is stored on your server only and is never shown again.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(album, { album = it }, Modifier.fillMaxWidth(), label = { Text("Album (empty = none)") }, singleLine = true)
            SwitchRow("Allow plain HTTP (insecure, LAN only)", allowHttp) { allowHttp = it }

            Text("Event history", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Keep snapshots and clips on the server", keepEvents) { keepEvents = it }
            OutlinedTextField(retention, { retention = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(),
                label = { Text("Delete after (days)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

            Text("Detector", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(minFace, { minFace = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(),
                label = { Text("Ignore faces narrower than (pixels)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { save(account) }) { Text("Save") }
                OutlinedButton(enabled = !busy, onClick = {
                    run(account) {
                        val (ok, msg) = ServerApi.testImmich(account)
                        message = (if (ok) "✓ " else "✗ ") + msg
                    }
                }) { Text("Test Immich") }
            }
        }
    }

    private fun save(account: ServerAccount) {
        val changes = JSONObject()
            .put("immich_enabled", immichEnabled)
            .put("immich_url", immichUrl)
            .put("immich_album", album)
            .put("immich_allow_http", allowHttp)
            .put("keep_events", keepEvents)
            .put("retention_days", retention.toIntOrNull() ?: 7)
            .put("min_face_px", minFace.toIntOrNull() ?: 16)
        if (immichKey.isNotEmpty()) changes.put("immich_api_key", immichKey)
        run(account) {
            val updated = ServerApi.putConfig(account, changes)
            runOnUiThread {
                fill(updated)
                message = "Saved."
                Toast.makeText(this, "Server settings saved", Toast.LENGTH_SHORT).show()
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
