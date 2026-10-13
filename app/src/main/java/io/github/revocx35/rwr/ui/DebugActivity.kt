package io.github.revocx35.rwr.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.revocx35.rwr.Prefs
import io.github.revocx35.rwr.detection.DebugFeed
import io.github.revocx35.rwr.detection.DetectionMode
import io.github.revocx35.rwr.detection.FaceRole
import io.github.revocx35.rwr.detection.FaceVerdict
import io.github.revocx35.rwr.detection.OnlookerPolicy
import io.github.revocx35.rwr.detection.WatcherService.ServerState
import io.github.revocx35.rwr.detection.WatcherService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Shows what detection sees, live: the analysed frame, every face with its role (you, looking, not looking,
 * dropped as not a face, ...), head angles, gaze and the "laser" rays. Works for detection on the phone and on
 * the server (the server sends its per-frame decisions while this screen is open). Nothing is saved, and the
 * alert pop-up is held back while this screen is visible (the notification still appears).
 */
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DebugFeed.open()
    }

    override fun onStop() {
        DebugFeed.close()
        super.onStop()
    }

    @Composable
    private fun Screen() {
        val running by WatcherService.running.collectAsStateWithLifecycle()
        val server by WatcherService.serverState.collectAsStateWithLifecycle()
        val frame by DebugFeed.frame.collectAsStateWithLifecycle()
        val lastAlert by DebugFeed.lastAlertAt.collectAsStateWithLifecycle()
        val prefs = remember { Prefs(this) }

        // Clock for "stale frame" and the alert banner.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) { now = System.currentTimeMillis(); delay(250) }
        }
        // Analysed frames per second, smoothed.
        var fps by remember { mutableFloatStateOf(0f) }
        var prevTs by remember { mutableLongStateOf(0L) }
        LaunchedEffect(frame?.ts) {
            val ts = frame?.ts ?: return@LaunchedEffect
            if (prevTs in 1 until ts) fps = 0.7f * fps + 0.3f * (1000f / (ts - prevTs))
            prevTs = ts
        }

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Debug view", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (!running) {
                Text("Not watching. Start watching to see what detection sees.")
                Button(onClick = { WatcherService.start(this@DebugActivity) }) { Text("Start watching") }
                return@Column
            }

            val f = frame
            val stale = f == null || now - f.ts > STALE_MS
            Text(statusLine(f, server, fps, stale), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(
                "Settings: ${if (prefs.detectionMode == DetectionMode.EYE) "eye" else "head"} mode · head ≤ " +
                    "${prefs.maxHeadAngle.roundToInt()}° · gaze ≤ ${prefs.maxGazeAngle.roundToInt()}° · ignore my face " +
                    if (prefs.ignoreOwner) "on" else "OFF",
                style = MaterialTheme.typography.bodySmall,
                color = if (prefs.ignoreOwner) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )

            if (f != null) {
                Box {
                    FrameWithFaces(f, Modifier.fillMaxWidth().aspectRatio(f.image.width.toFloat() / f.image.height))
                    if (now - lastAlert < ALERT_BANNER_MS) {
                        Text(
                            "ALERT", color = Color.White, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                                .background(Color(0xFFD32F2F), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            } else {
                Text(waitingText(server), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Legend()
            f?.verdicts?.forEachIndexed { i, v -> FaceCard(i + 1, v) }
            Text(
                "The alert also needs the same person in about the same place in 2 of 3 analysed frames, and respects " +
                    "the minimum time between alerts. While this screen is open the pop-up is held back; the " +
                    "notification still appears.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    private fun statusLine(f: DebugFeed.Frame?, server: ServerState, fps: Float, stale: Boolean): String = buildString {
        append("detection on ")
        append(
            when (f?.source) {
                DebugFeed.Source.SERVER -> "server"
                DebugFeed.Source.PHONE -> "phone"
                null -> "—"
            }
        )
        if (f != null) append(" · ${if (f.mode == DetectionMode.EYE) "eye" else "head"} mode")
        if (server != ServerState.OFF) append("\nserver link: ${server.name.lowercase()}")
        if (f != null) {
            append("\n${f.image.width}×${f.image.height} · ${f.analysisMs} ms · %.1f fps · ".format(fps))
            append("${f.verdicts.size} face${if (f.verdicts.size == 1) "" else "s"}")
        }
        if (stale && f != null) append(" · no new frames")
    }

    private fun waitingText(server: ServerState) = when (server) {
        ServerState.CONNECTING -> "Waiting for the server…"
        ServerState.REJECTED -> "The server rejected this phone. Pair it again."
        else -> "Waiting for frames… (the camera is off while the screen is off)"
    }

    @Composable
    private fun FrameWithFaces(f: DebugFeed.Frame, modifier: Modifier) {
        val image = remember(f.image) { f.image.asImageBitmap() }
        val label = remember {
            Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        }
        val labelBg = remember { Paint().apply { color = android.graphics.Color.argb(170, 0, 0, 0) } }
        Canvas(modifier) {
            val scale = size.width / f.image.width
            drawImage(image, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            val stroke = 2.dp.toPx()
            label.textSize = 11.dp.toPx()
            f.verdicts.forEachIndexed { i, v ->
                val b = v.face
                val color = roleColor(v.role)
                val topLeft = Offset(b.left * scale, b.top * scale)
                drawRect(
                    color, topLeft, Size(b.width * scale, (b.bottom - b.top) * scale),
                    style = Stroke(
                        width = if (v.role == FaceRole.ONLOOKER) stroke * 1.6f else stroke,
                        pathEffect = if (v.role == FaceRole.NO_FACE || v.role == FaceRole.TOO_SMALL)
                            PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
                    ),
                )
                b.rays.forEach { r ->
                    drawLine(color.copy(alpha = 0.45f), Offset(r.x0 * scale, r.y0 * scale), Offset(r.x1 * scale, r.y1 * scale),
                        strokeWidth = stroke * 3, cap = StrokeCap.Round)
                    drawLine(color, Offset(r.x0 * scale, r.y0 * scale), Offset(r.x1 * scale, r.y1 * scale),
                        strokeWidth = stroke, cap = StrokeCap.Round)
                }
                val text = "${i + 1} ${v.role.label}"
                label.color = color.toArgb()
                val w = label.measureText(text)
                val y = (topLeft.y - 4).coerceAtLeast(label.textSize + 2)
                drawContext.canvas.nativeCanvas.apply {
                    drawRect(topLeft.x, y - label.textSize, topLeft.x + w + 8, y + 4, labelBg)
                    drawText(text, topLeft.x + 4, y, label)
                }
            }
        }
    }

    @Composable
    private fun Legend() {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(FaceRole.OWNER, FaceRole.ONLOOKER, FaceRole.NOT_LOOKING, FaceRole.TURNED_AWAY, FaceRole.NO_FACE).forEach {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(roleColor(it)))
                    Text(" ${it.label}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    @Composable
    private fun FaceCard(n: Int, v: FaceVerdict) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$n · ${v.role.label}", color = roleColor(v.role), fontWeight = FontWeight.SemiBold)
                val b = v.face
                val mono = MaterialTheme.typography.bodySmall
                Text("box ${b.width}×${b.bottom - b.top} px at ${b.left},${b.top}", style = mono, fontFamily = FontFamily.Monospace)
                Text("head yaw %+.1f° pitch %+.1f°".format(b.yaw, b.pitch), style = mono, fontFamily = FontFamily.Monospace)
                v.gaze?.let { g ->
                    Text("eyes yaw %+.1f° pitch %+.1f° (%s)".format(g.eyeYaw, g.eyePitch, if (g.eyesOpen) "open" else "closed"),
                        style = mono, fontFamily = FontFamily.Monospace)
                    Text("gaze yaw %+.1f° pitch %+.1f°".format(g.yaw, g.pitch), style = mono, fontFamily = FontFamily.Monospace)
                }
                Text(explain(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun explain(v: FaceVerdict): String = when (v.role) {
        FaceRole.OWNER -> "Treated as you: the largest face turned toward the camera, followed as you move."
        FaceRole.ONLOOKER -> if (v.gaze != null && v.face.width >= OnlookerPolicy.MIN_EYE_FACE_PX)
            "Counts as looking at your screen." else "Counts as looking (head pose; too small or no eye data)."
        FaceRole.NOT_LOOKING -> if (v.gaze != null) "Eyes or head point away from the screen, or eyes closed."
            else "Head turned away more than the allowed angle."
        FaceRole.TURNED_AWAY -> "Head turned more than ${OnlookerPolicy.EYE_PREFILTER_ANGLE.roundToInt()}°, so the eyes weren't checked."
        FaceRole.NO_FACE -> "The landmark model found no face here (or a different face): ignored as a false detection."
        FaceRole.TOO_SMALL -> "Smaller than the server's minimum face size."
    }

    private fun roleColor(role: FaceRole) = when (role) {
        FaceRole.OWNER -> Color(0xFF42A5F5)
        FaceRole.ONLOOKER -> Color(0xFFF44336)
        FaceRole.NOT_LOOKING -> Color(0xFFFFB300)
        FaceRole.TURNED_AWAY -> Color(0xFF9E9E9E)
        FaceRole.NO_FACE -> Color(0xFFE040FB)
        FaceRole.TOO_SMALL -> Color(0xFF9E9E9E)
    }

    private companion object {
        const val STALE_MS = 3000L
        const val ALERT_BANNER_MS = 2000L
    }
}
