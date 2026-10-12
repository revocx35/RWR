package io.github.revocx35.rwr.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.revocx35.rwr.detection.WatcherService
import io.github.revocx35.rwr.server.PairingLink
import io.github.revocx35.rwr.server.ServerAccount
import io.github.revocx35.rwr.server.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Pairs the app with an RWR server by scanning the QR code from its web UI (Devices -> Pair a phone). */
class PairActivity : ComponentActivity() {
    private var found by mutableStateOf<PairingLink?>(null)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var cameraGranted by mutableStateOf(false)
    /** Stops the scanner after a failed attempt, so the same QR code isn't re-read in a loop. */
    private var scanning by mutableStateOf(true)
    private val scanExecutor = Executors.newSingleThreadExecutor()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The watcher holds the front camera; scanning needs a camera too.
        if (WatcherService.running.value) {
            WatcherService.stop(this)
            Toast.makeText(this, "Watching paused while pairing", Toast.LENGTH_SHORT).show()
        }
        cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) requestCamera.launch(Manifest.permission.CAMERA)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onDestroy() {
        scanExecutor.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pair with your RWR server", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("In the server's web UI open Devices → Create pairing code, then point the camera at the QR code.")
            if (cameraGranted && found == null && scanning) {
                AndroidView(
                    factory = { ctx -> PreviewView(ctx).also { startScanner(it) } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                )
            }
            var pasted by remember { mutableStateOf("") }
            OutlinedTextField(
                value = pasted, onValueChange = { pasted = it.trim() }, modifier = Modifier.fillMaxWidth(),
                label = { Text("…or paste the pairing link (rwr://pair?…)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            Button(onClick = {
                val link = PairingLink.parse(pasted)
                if (link == null) error = "That isn't a valid RWR pairing link (it must point to an https:// server)."
                else found = link
            }, enabled = pasted.isNotEmpty() && !busy) { Text("Use link") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!scanning && cameraGranted) {
                OutlinedButton(onClick = { error = null; scanning = true }) { Text("Scan again") }
            }
        }
        found?.let { ConfirmDialog(it) }
    }

    @Composable
    private fun ConfirmDialog(link: PairingLink) {
        var name by remember { mutableStateOf(Build.MODEL.take(60)) }
        AlertDialog(
            onDismissRequest = { if (!busy) found = null },
            title = { Text("Pair with this server?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(link.baseUrl, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (link.pin != null) "The connection is locked to this server's certificate key " +
                            "(${link.pin.take(12)}…). Nobody else can impersonate it."
                        else "The server's certificate will be checked against the phone's trusted authorities.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Name for this phone") })
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && name.isNotBlank(), onClick = { pair(link, name) }) {
                    Text(if (busy) "Pairing…" else "Pair")
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { found = null }) { Text("Cancel") } },
        )
    }

    private fun pair(link: PairingLink, name: String) {
        busy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ServerApi.pair(link, name.trim()) } }
            busy = false
            result.onSuccess { account ->
                ServerAccount.save(this@PairActivity, account)
                WatcherService.reloadServer(this@PairActivity)
                Toast.makeText(this@PairActivity, "Paired as “${account.deviceName}”", Toast.LENGTH_LONG).show()
                finish()
            }.onFailure {
                Log.w(TAG, "pairing failed", it)
                scanning = false
                found = null
                error = "Pairing failed: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun startScanner(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val reader = QRCodeReader()
            val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
            analysis.setAnalyzer(scanExecutor) { image ->
                val text = image.use { decode(reader, hints, it) }
                val link = text?.let(PairingLink::parse)
                if (link != null) ContextCompat.getMainExecutor(this).execute {
                    if (found == null && !busy) {
                        provider.unbindAll()
                        found = link
                    }
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { error = "Camera unavailable: ${it.message}. Paste the link instead." }
        }, ContextCompat.getMainExecutor(this))
    }

    private companion object {
        const val TAG = "PairActivity"
    }

    private fun decode(reader: QRCodeReader, hints: Map<DecodeHintType, Any>, image: ImageProxy): String? {
        val plane = image.planes[0]
        val buf = plane.buffer
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
