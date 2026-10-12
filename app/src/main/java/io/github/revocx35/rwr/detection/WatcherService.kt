package io.github.revocx35.rwr.detection

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import io.github.revocx35.rwr.BuildConfig
import io.github.revocx35.rwr.Prefs
import io.github.revocx35.rwr.R
import io.github.revocx35.rwr.RwrApp
import io.github.revocx35.rwr.alert.AlertPresenter
import io.github.revocx35.rwr.alert.SnapshotStore
import io.github.revocx35.rwr.tile.WatchTileService
import io.github.revocx35.rwr.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service (type camera) that streams low-rate frames from the front camera, finds faces
 * looking at the screen, and raises alerts with a snapshot. Camera is released while the screen is off.
 *
 * Must be started while the app is in the foreground (activity or the tile's trampoline activity):
 * Android 14+ refuses to start a camera foreground service from the background.
 */
class WatcherService : LifecycleService() {
    private lateinit var prefs: Prefs
    private lateinit var presenter: AlertPresenter
    private val gate = AlertGate()
    private var finder: FaceFinder? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastAnalyzedAt = 0L

    /** All frame analysis (camera and debug injection) runs on this one thread, in order. */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pauseCamera()
                Intent.ACTION_SCREEN_ON -> startCamera()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        presenter = AlertPresenter(this)
        finder = FaceFinder()
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DEBUG_INJECT -> {
                if (BuildConfig.DEBUG && _running.value) {
                    intent.getStringExtra(EXTRA_PATH)?.let(::injectDebugFrame)
                }
                return START_NOT_STICKY
            }
        }
        if (!_running.value) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildStatusNotification(paused = false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
            } catch (e: Exception) {
                // SecurityException (no camera permission) or, on 12+, started from the background.
                val fromBackground = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                Log.e(TAG, "cannot start foreground (background=$fromBackground)", e)
                stopSelf()
                return START_NOT_STICKY
            }
            _running.value = true
            refreshTile()
            if (getSystemService(PowerManager::class.java).isInteractive) startCamera() else pauseCamera()
        }
        // Not sticky: a system restart would happen from the background, where camera FGS is forbidden.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        unregisterReceiver(screenReceiver)
        cameraProvider?.unbindAll()
        presenter.dismissOverlay()
        analysisExecutor.execute { finder?.close(); finder = null }
        analysisExecutor.shutdown()
        refreshTile()
        super.onDestroy()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!_running.value) return@addListener
            val provider = runCatching { future.get() }.getOrElse {
                Log.e(TAG, "camera provider unavailable", it); stopSelf(); return@addListener
            }
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor, ::analyze)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                gate.reset()
                updateStatusNotification(paused = false)
                Log.i(TAG, "camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "cannot bind front camera", e)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun pauseCamera() {
        cameraProvider?.unbindAll()
        updateStatusNotification(paused = true)
        Log.i(TAG, "camera paused (screen off)")
    }

    /** Runs on [analysisExecutor]. */
    private fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalyzedAt < FRAME_INTERVAL_MS) {
            image.close()
            return
        }
        lastAnalyzedAt = now
        val upright = image.use { toUprightMirrored(it) }
        if (BuildConfig.DEBUG) dumpFrameIfRequested(upright)
        process(upright, now)
    }

    /** Runs on [analysisExecutor]. */
    private fun process(upright: Bitmap, now: Long) {
        val faces = runCatching { finder?.find(upright) }
            .onFailure { Log.w(TAG, "face detection failed", it) }
            .getOrNull() ?: return
        val onlookers = OnlookerPolicy.onlookers(faces, prefs.ignoreOwner, prefs.maxHeadAngle)
        if (BuildConfig.DEBUG && faces.isNotEmpty()) {
            Log.d(TAG, "frame ${upright.width}x${upright.height}: faces=$faces onlookers=${onlookers.size}")
        }
        if (!gate.onFrame(onlookers.isNotEmpty(), now, prefs.cooldownSec * 1000L)) return
        Log.i(TAG, "onlooker alert: ${onlookers.size} of ${faces.size} faces")
        val snapshot = annotate(upright, onlookers)
        val uri = SnapshotStore.save(this, snapshot, now)
        ContextCompat.getMainExecutor(this).execute {
            if (_running.value) presenter.show(snapshot, uri, onlookers.size, now)
        }
    }

    /** Front-camera frames arrive sensor-oriented; rotate upright and mirror like a selfie preview. */
    private fun toUprightMirrored(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val m = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    /** Draws a red box around each onlooker so the snapshot shows who triggered the alert. */
    private fun annotate(src: Bitmap, onlookers: List<FaceBox>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = maxOf(4f, out.width / 160f)
        }
        val canvas = Canvas(out)
        onlookers.forEach { canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), paint) }
        return out
    }

    /** Debug builds only: feed an image from the app's files dir through the real pipeline (emulator tests). */
    private fun injectDebugFrame(relativePath: String) {
        analysisExecutor.execute {
            val file = File(filesDir, relativePath)
            val bmp = BitmapFactory.decodeFile(file.path) ?: run { Log.w(TAG, "cannot decode $file"); return@execute }
            Log.i(TAG, "debug frame injected: ${bmp.width}x${bmp.height}")
            process(bmp, System.currentTimeMillis())
        }
    }

    /** Debug builds only: `touch files/dump_frame` makes the next camera frame land in files/frame.jpg. */
    private fun dumpFrameIfRequested(frame: Bitmap) {
        val flag = File(filesDir, "dump_frame")
        if (!flag.delete()) return
        File(filesDir, "frame.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun buildStatusNotification(paused: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatcherService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, RwrApp.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle(getString(R.string.notif_watching_title))
            .setContentText(getString(if (paused) R.string.notif_paused_text else R.string.notif_watching_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateStatusNotification(paused: Boolean) {
        if (!_running.value) return
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildStatusNotification(paused))
        }
    }

    private fun refreshTile() =
        TileService.requestListeningState(this, ComponentName(this, WatchTileService::class.java))

    companion object {
        private const val TAG = "WatcherService"
        private const val NOTIFICATION_ID = 1
        /** ~3 analysed frames per second: responsive enough, light on battery. */
        private const val FRAME_INTERVAL_MS = 330L
        const val ACTION_STOP = "io.github.revocx35.rwr.STOP"
        const val ACTION_DEBUG_INJECT = "io.github.revocx35.rwr.DEBUG_INJECT"
        const val EXTRA_PATH = "path"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Call only while the app is in the foreground (see class doc). */
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, WatcherService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, WatcherService::class.java))
        }
    }
}
