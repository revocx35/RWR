package io.github.revocx35.rwr.detection

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import io.github.revocx35.rwr.MediaType
import io.github.revocx35.rwr.Prefs
import io.github.revocx35.rwr.R
import io.github.revocx35.rwr.RwrApp
import io.github.revocx35.rwr.alert.AlertPresenter
import io.github.revocx35.rwr.alert.SnapshotStore
import io.github.revocx35.rwr.media.ClipEncoder
import io.github.revocx35.rwr.media.EncodedFrame
import io.github.revocx35.rwr.media.FrameCodec
import io.github.revocx35.rwr.media.FrameRing
import io.github.revocx35.rwr.server.ServerAccount
import io.github.revocx35.rwr.server.ServerApi
import io.github.revocx35.rwr.server.StreamClient
import io.github.revocx35.rwr.tile.WatchTileService
import io.github.revocx35.rwr.ui.MainActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service (type camera) that reads low-rate frames from the front camera, finds faces
 * looking at the screen, and raises alerts with a snapshot or clip. Camera is released while the screen is off.
 *
 * Detection runs either here (ML Kit for faces/head pose, plus MediaPipe iris tracking in eye mode) or on the
 * paired RWR server, which gets every frame over a pinned wss:// stream and sends back alerts.
 *
 * Must be started while the app is in the foreground (activity or the tile's trampoline activity):
 * Android 14+ refuses to start a camera foreground service from the background.
 */
class WatcherService : LifecycleService() {
    private lateinit var prefs: Prefs
    private lateinit var presenter: AlertPresenter
    private val gate = AlertGate()
    private val owner = OwnerTracker()
    private var finder: FaceFinder? = null
    private var eyeTracker: EyeTracker? = null
    private var eyeTrackerFailed = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastAnalyzedAt = 0L

    /** All frame analysis (camera, server alerts, debug injection) runs on this one thread, in order. */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    /** Clip encoding, gallery writes of clips and uploads: slow, so kept off the analysis thread. */
    private val mediaExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // --- analysis-thread state ---
    private val ring = FrameRing(RING_MIN_SECONDS)
    private var nextSeq = 1
    private var clip: ClipJob? = null
    /** Onlookers per frame seq, so clips can show boxes on the frames where they were detected. */
    private val boxesBySeq = LinkedHashMap<Int, List<FaceBox>>()

    // --- server ---
    @Volatile private var account: ServerAccount? = null
    @Volatile private var stream: StreamClient? = null

    private class ClipJob(
        val alertTs: Long,
        val endTs: Long,
        val frames: MutableList<EncodedFrame>,
        val onlookers: Int,
        val toGallery: Boolean,
        val toUpload: Boolean,
        val mode: String,
    )

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
        finder = runCatching { FaceFinder(this) }.onFailure { Log.e(TAG, "face detector unavailable", it) }.getOrNull()
        account = ServerAccount.load(this)
        updateRingLength()
        prefs.register(prefListener)
        // The debug view opened or closed: tell the server whether to send its per-frame decisions.
        lifecycleScope.launch {
            DebugFeed.viewerCount.map { it > 0 }.distinctUntilChanged().drop(1).collect {
                stream?.updateSettings(streamSettings())
            }
        }
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
            ACTION_RELOAD_SERVER -> {
                if (_running.value) {
                    account = ServerAccount.load(this)
                    stopStream()
                    if (isInteractive()) startStreamIfNeeded()
                    updateStatusNotification()
                }
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
            if (isInteractive()) startCamera() else pauseCamera()
        }
        // Not sticky: a system restart would happen from the background, where camera FGS is forbidden.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        prefs.unregister(prefListener)
        unregisterReceiver(screenReceiver)
        cameraProvider?.unbindAll()
        stopStream()
        presenter.dismissOverlay()
        analysisExecutor.execute {
            clip?.let(::finishClip)
            finder?.close(); finder = null
            eyeTracker?.close(); eyeTracker = null
        }
        analysisExecutor.shutdown()
        mediaExecutor.shutdown()  // lets a running clip finish
        refreshTile()
        super.onDestroy()
    }

    private fun isInteractive() = getSystemService(PowerManager::class.java).isInteractive

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!_running.value) return@addListener
            val provider = runCatching { future.get() }.getOrElse {
                Log.e(TAG, "camera provider unavailable", it); stopSelf(); return@addListener
            }
            cameraProvider = provider
            val analysis = buildAnalysis(provider)
            analysis.setAnalyzer(analysisExecutor, ::analyze)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                analysisExecutor.execute { gate.reset(); owner.reset() }
                Log.i(TAG, "camera bound: ${analysis.resolutionInfo?.resolution}, fps=$appliedFpsRange, " +
                    if (prefs.efficientConversion) "YUV" else "RGBA")
                startStreamIfNeeded()
                updateStatusNotification(paused = false)
            } catch (e: Exception) {
                Log.e(TAG, "cannot bind front camera", e)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Last FPS range requested from the camera, for logging. */
    private var appliedFpsRange: IntRange? = null

    /** Builds the analysis use case from the battery settings (frame rate, resolution, frame format). */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildAnalysis(provider: ProcessCameraProvider): ImageAnalysis {
        val size = if (prefs.lowResolution) Size(640, 480) else Size(1280, 720)
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // YUV is the camera's native format; toBitmap() converts only the frames we analyse.
            // RGBA makes CameraX convert every delivered frame, even the ones we throw away.
            .setOutputImageFormat(
                if (prefs.efficientConversion) ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
                else ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
            )
        val supported = CameraSelector.DEFAULT_FRONT_CAMERA.filter(provider.availableCameraInfos).firstOrNull()
            ?.let { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) }
            ?.map { it.lower..it.upper }
            .orEmpty()
        appliedFpsRange = CameraTuning.chooseFpsRange(supported, prefs.cameraFps)?.also {
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.last))
        }
        return builder.build()
    }

    /** Settings changed in the UI: apply them right away. */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!_running.value) return@OnSharedPreferenceChangeListener
        when (key) {
            in Prefs.CAMERA_KEYS -> if (isInteractive()) startCamera()
            Prefs.KEY_SERVER -> {
                stopStream()
                if (isInteractive()) startStreamIfNeeded()
                updateStatusNotification()
            }
            in Prefs.STREAM_KEYS -> {
                updateRingLength()
                stream?.updateSettings(streamSettings())
            }
        }
    }

    private fun pauseCamera() {
        cameraProvider?.unbindAll()
        stopStream()
        analysisExecutor.execute { clip?.let(::finishClip); ring.clear() }
        updateStatusNotification(paused = true)
        Log.i(TAG, "camera paused (screen off)")
    }

    private fun updateRingLength() {
        val seconds = maxOf(RING_MIN_SECONDS, if (prefs.mediaType == MediaType.VIDEO) prefs.preSec + 1 else 0)
        analysisExecutor.execute { ring.seconds = seconds }
    }

    // ------------------------------------------------------------------ frames

    /** Runs on [analysisExecutor]. */
    private fun analyze(image: ImageProxy) {
        image.use {
            val now = System.currentTimeMillis()
            val s = stream
            val streaming = s?.isConnected == true
            val buffering = streaming || prefs.mediaType == MediaType.VIDEO
            // Frames are JPEG-encoded only when something needs them: the server, or a clip's rolling buffer.
            if (buffering) {
                val rotation = image.imageInfo.rotationDegrees
                val frame = EncodedFrame(nextSeq++, now, FrameCodec.toJpeg(image), rotation)
                ring.add(frame)
                clip?.let { job ->
                    job.frames += frame
                    if (now >= job.endTs) finishClip(job)
                }
                if (streaming) s!!.sendFrame(frame.seq, now, rotation / 90, frame.jpeg)
            }
            if (!detectLocally(streaming) || now - lastAnalyzedAt < FRAME_INTERVAL_MS) return
            lastAnalyzedAt = now
            val upright = toUprightMirrored(image)
            if (BuildConfig.DEBUG) dumpFrameIfRequested(upright)
            process(upright, now, if (buffering) nextSeq - 1 else null)
        }
    }

    /** On the phone unless server mode is on and the server is reachable (or fallback is off). */
    private fun detectLocally(streaming: Boolean): Boolean {
        if (!prefs.processOnServer || account == null) return true
        return !streaming && prefs.fallbackLocal
    }

    /** Runs on [analysisExecutor]. */
    private fun process(upright: Bitmap, now: Long, seq: Int?) {
        val started = SystemClock.elapsedRealtime()
        val faces = runCatching { finder?.find(upright) }
            .onFailure { Log.w(TAG, "face detection failed", it) }
            .getOrNull() ?: return
        val ownerFace = if (prefs.ignoreOwner) owner.pick(faces, now) else null
        val verdicts = faces.map { if (it === ownerFace) FaceVerdict(it, FaceRole.OWNER) else judge(upright, it) }
        val onlookers = verdicts.filter { it.role == FaceRole.ONLOOKER }.map { it.face }
        if (DebugFeed.active) {
            DebugFeed.publish(DebugFeed.Frame(upright, verdicts, DebugFeed.Source.PHONE, prefs.detectionMode,
                SystemClock.elapsedRealtime() - started, now))
        }
        if (BuildConfig.DEBUG && faces.isNotEmpty()) {
            Log.d(TAG, "frame ${upright.width}x${upright.height}: faces=$faces onlookers=${onlookers.size}")
        }
        if (seq != null && onlookers.isNotEmpty()) rememberBoxes(seq, onlookers)
        if (clip != null) return  // still recording the previous alert's clip
        if (!gate.onFrame(onlookers, now, prefs.cooldownSec * 1000L)) return
        Log.i(TAG, "onlooker alert: ${onlookers.size} of ${faces.size} faces (${prefs.detectionMode})")
        raiseAlert(upright, onlookers, now, serverDetected = false)
    }

    /** Created on first use; null if MediaPipe can't load (then faces are not verified and eye mode uses head pose). */
    private fun landmarker(): EyeTracker? = eyeTracker ?: if (eyeTrackerFailed) null else runCatching { EyeTracker(this) }
        .onFailure { eyeTrackerFailed = true; Log.e(TAG, "eye tracker unavailable, using head pose", it) }
        .getOrNull()?.also { eyeTracker = it }

    /**
     * Decides one non-owner face. Faces too small to be reading the screen are skipped first
     * ([OnlookerPolicy.isTooSmall]). Face Landmarker runs only on non-owner faces turned roughly toward the camera
     * (so it stays idle while only the owner is in view); it verifies the face ([OnlookerPolicy.isVerified]) and
     * measures the head pose and gaze that decide.
     */
    private fun judge(upright: Bitmap, face: FaceBox): FaceVerdict {
        if (OnlookerPolicy.isTooSmall(face, upright.width)) return FaceVerdict(face, FaceRole.TOO_SMALL)
        val eye = prefs.detectionMode == DetectionMode.EYE
        val maxHead = prefs.maxHeadAngle
        // YuNet's five-point head pose is rough (pitch especially), so it only prefilters on yaw; faces that pass
        // are measured again with Face Landmarker, which decides.
        val limit = if (eye) OnlookerPolicy.EYE_PREFILTER_ANGLE else maxOf(OnlookerPolicy.HEAD_PREFILTER_ANGLE, maxHead + 20f)
        if (abs(face.yaw) > limit) return FaceVerdict(face, if (eye) FaceRole.TURNED_AWAY else FaceRole.NOT_LOOKING)
        val tracker = landmarker()
        val gaze = tracker?.let { t -> runCatching { t.gaze(upright, face) }.getOrNull() }
        if (tracker != null && !OnlookerPolicy.isVerified(face, gaze != null)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "dropped ${face.width}px face at ${face.left},${face.top}: no landmarks")
            return FaceVerdict(face, FaceRole.NO_FACE)
        }
        // Landmark head pose replaces the rough one (also what the debug view shows).
        val measured = if (gaze != null) face.copy(yaw = gaze.headYaw, pitch = gaze.headPitch) else face
        val drawn = if (gaze != null && face.width >= OnlookerPolicy.MIN_EYE_FACE_PX) {
            measured.copy(rays = GazeEstimator.laserRays(gaze, face.width * 0.8f))
        } else measured
        if (!eye) {
            val looking = OnlookerPolicy.isLookingAtCamera(measured, maxHead)
            return FaceVerdict(drawn, if (looking) FaceRole.ONLOOKER else FaceRole.NOT_LOOKING, gaze)
        }
        if (BuildConfig.DEBUG && gaze != null) {
            Log.d(TAG, "gaze %d,%d: head %.1f/%.1f eyes %.1f/%.1f -> %.1f/%.1f open=%b".format(
                face.left, face.top, gaze.headYaw, gaze.headPitch, gaze.eyeYaw, gaze.eyePitch,
                gaze.yaw, gaze.pitch, gaze.eyesOpen))
        }
        val looking = OnlookerPolicy.isLookingEyeMode(measured, gaze, maxHead, prefs.maxGazeAngle)
        return FaceVerdict(drawn, if (looking) FaceRole.ONLOOKER else FaceRole.NOT_LOOKING, gaze)
    }

    private fun rememberBoxes(seq: Int, onlookers: List<FaceBox>) {
        boxesBySeq[seq] = onlookers
        while (boxesBySeq.size > 300) boxesBySeq.remove(boxesBySeq.keys.first())
    }

    /**
     * Shows the pop-up and saves to the chosen places. Runs on [analysisExecutor].
     * [serverDetected]: the server found the onlooker and saves to Immich itself, so the phone never uploads.
     */
    private fun raiseAlert(upright: Bitmap, onlookers: List<FaceBox>, ts: Long, serverDetected: Boolean) {
        DebugFeed.alerted(ts)
        val snapshot = annotate(upright, onlookers)
        val saveTo = prefs.saveTo
        val acct = account
        val upload = saveTo.immich && !serverDetected && acct != null
        val note = when {
            !saveTo.immich -> null
            serverDetected -> getString(R.string.alert_immich_server)
            acct == null -> "Immich needs a paired server"
            else -> null
        }
        val mode = if (prefs.detectionMode == DetectionMode.EYE) "eye" else "head"
        if (prefs.mediaType == MediaType.PHOTO) {
            val uri = if (saveTo.gallery) SnapshotStore.save(this, snapshot, ts) else null
            if (upload) mediaExecutor.execute { uploadPhoto(acct!!, snapshot, ts, onlookers.size, mode) }
            onMain { presenter.show(snapshot, uri, onlookers.size, ts, clipPending = false, note = note, overlay = !DebugFeed.active) }
            return
        }
        val recordHere = saveTo.gallery || upload
        if (recordHere) {
            val pre = ring.since(ts - prefs.preSec * 1000L).toMutableList()
            val job = ClipJob(ts, ts + prefs.postSec * 1000L, pre, onlookers.size, saveTo.gallery, upload, mode)
            clip = job
            if (prefs.postSec == 0) finishClip(job)
        }
        onMain { presenter.show(snapshot, null, onlookers.size, ts, clipPending = recordHere, note = note, overlay = !DebugFeed.active) }
    }

    /** Runs on [analysisExecutor]: hands the collected frames to [mediaExecutor] for encoding. */
    private fun finishClip(job: ClipJob) {
        if (clip === job) clip = null
        val frames = job.frames.sortedBy { it.tsMs }.distinctBy { it.seq }
        val boxes = HashMap(boxesBySeq)
        val acct = account
        mediaExecutor.execute {
            val dir = File(cacheDir, "clips").apply { mkdirs() }
            val file = File(dir, "RWR_${job.alertTs}.mp4")
            var lastBoxes: List<FaceBox> = emptyList()
            var lastBoxTs = Long.MIN_VALUE / 2
            val ok = ClipEncoder.encode(frames.map { it.tsMs }, { i ->
                val f = frames[i]
                boxes[f.seq]?.let { lastBoxes = it; lastBoxTs = f.tsMs }
                FrameCodec.uprightMirrored(f)?.let { bmp ->
                    // Keep boxes visible between analysed frames (~3 per second).
                    if (lastBoxes.isNotEmpty() && f.tsMs - lastBoxTs < 700) annotate(bmp, lastBoxes) else bmp
                }
            }, file)
            Log.i(TAG, "clip ${if (ok) "encoded" else "FAILED"}: ${frames.size} frames, ${file.length()} bytes")
            var uri: Uri? = null
            var note: String? = null
            if (ok) {
                if (job.toGallery) uri = SnapshotStore.saveVideo(this, file, job.alertTs)
                if (job.toUpload && acct != null) {
                    note = runCatching {
                        ServerApi.uploadMedia(acct, file, video = true, takenAt = job.alertTs, onlookers = job.onlookers, mode = job.mode)
                        getString(R.string.alert_sent_immich)
                    }.getOrElse { Log.w(TAG, "clip upload failed", it); "Immich upload failed: ${it.message}" }
                }
            } else note = "clip could not be encoded"
            file.delete()
            onMain { presenter.clipReady(job.alertTs, uri, note) }
        }
    }

    private fun uploadPhoto(acct: ServerAccount, snapshot: Bitmap, ts: Long, onlookers: Int, mode: String) {
        val dir = File(cacheDir, "clips").apply { mkdirs() }
        val file = File(dir, "RWR_$ts.jpg")
        try {
            file.outputStream().use { snapshot.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            ServerApi.uploadMedia(acct, file, video = false, takenAt = ts, onlookers = onlookers, mode = mode)
            Log.i(TAG, "photo sent to server for Immich")
        } catch (e: Exception) {
            Log.w(TAG, "photo upload failed", e)
        } finally {
            file.delete()
        }
    }

    // ------------------------------------------------------------------ server stream

    private fun streamSettings(): JSONObject = JSONObject()
        .put("mode", if (prefs.detectionMode == DetectionMode.EYE) "eye" else "head")
        .put("ignore_owner", prefs.ignoreOwner)
        .put("max_head_angle", prefs.maxHeadAngle.toDouble())
        .put("max_gaze_angle", prefs.maxGazeAngle.toDouble())
        .put("cooldown_sec", prefs.cooldownSec)
        .put("media", if (prefs.mediaType == MediaType.VIDEO) "video" else "photo")
        .put("pre_sec", prefs.preSec)
        .put("post_sec", prefs.postSec)
        .put("save_immich", prefs.saveTo.immich)
        .put("debug", DebugFeed.active)

    private fun startStreamIfNeeded() {
        val acct = account
        if (!_running.value || !prefs.processOnServer || acct == null || stream != null) return
        _serverState.value = ServerState.CONNECTING
        stream = StreamClient(acct, streamListener).also { it.start(streamSettings()) }
    }

    private fun stopStream() {
        stream?.stop()
        stream = null
        _webViewers.value = 0
        _serverState.value = ServerState.OFF
    }

    private val streamListener = object : StreamClient.Listener {
        override fun onConnected() {
            Log.i(TAG, "server stream connected")
            _serverState.value = ServerState.CONNECTED
            analysisExecutor.execute { gate.reset(); owner.reset() }
            onMain { updateStatusNotification() }
        }

        override fun onDisconnected(reason: String) {
            _webViewers.value = 0
            _serverState.value = if (prefs.fallbackLocal) ServerState.FALLBACK else ServerState.CONNECTING
            onMain { updateStatusNotification() }
        }

        override fun onRejected(reason: String) {
            Log.w(TAG, "server rejected this device: $reason")
            _serverState.value = ServerState.REJECTED
            onMain { updateStatusNotification() }
        }

        override fun onAlert(alert: JSONObject) {
            analysisExecutor.execute { handleServerAlert(alert) }
        }

        override fun onViewers(count: Int) {
            Log.i(TAG, "web UI live viewers: $count")
            _webViewers.value = count
            onMain { updateStatusNotification() }
        }

        override fun onDebug(msg: JSONObject) {
            if (DebugFeed.active) analysisExecutor.execute { handleServerDebug(msg) }
        }
    }

    private var lastServerDebugAt = 0L

    /** Runs on [analysisExecutor]: the server's per-frame decisions, shown in the debug view (~6 per second). */
    private fun handleServerDebug(msg: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        if (!DebugFeed.active || now - lastServerDebugAt < SERVER_DEBUG_INTERVAL_MS) return
        val frame = ring.bySeq(msg.optInt("seq")) ?: return
        lastServerDebugAt = now
        val upright = FrameCodec.uprightMirrored(frame) ?: return
        val arr = msg.optJSONArray("faces")
        val verdicts = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val f = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val box = parseFace(f) ?: return@mapNotNull null
            val role = FaceRole.fromWire(f.optString("role")) ?: return@mapNotNull null
            val gaze = f.optJSONArray("gaze")?.takeIf { it.length() == 2 }?.let {
                val gy = it.getDouble(0).toFloat()
                val gp = it.getDouble(1).toFloat()
                Gaze(box.yaw, box.pitch, gy - box.yaw, gp - box.pitch, f.optBoolean("eyes_open", true), emptyList())
            }
            FaceVerdict(box, role, gaze)
        }
        val mode = if (msg.optString("mode") == "eye") DetectionMode.EYE else DetectionMode.HEAD
        DebugFeed.publish(DebugFeed.Frame(upright, verdicts, DebugFeed.Source.SERVER, mode, msg.optLong("ms"), frame.tsMs))
    }

    /** Runs on [analysisExecutor]. The server's boxes are in the same upright, mirrored frame space as ours. */
    private fun handleServerAlert(alert: JSONObject) {
        if (!_running.value || clip != null) return
        val seq = alert.optInt("seq")
        val frame = ring.bySeq(seq) ?: run { Log.w(TAG, "alert for frame $seq no longer buffered"); return }
        val upright = FrameCodec.uprightMirrored(frame) ?: return
        val faces = parseFaces(alert)
        rememberBoxes(seq, faces)
        Log.i(TAG, "server alert: ${faces.size} onlooker(s) in frame $seq")
        raiseAlert(upright, faces, frame.tsMs, serverDetected = true)
    }

    private fun parseFaces(alert: JSONObject): List<FaceBox> {
        val arr = alert.optJSONArray("faces") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseFace) }
    }

    private fun parseFace(f: JSONObject): FaceBox? {
        val b = f.optJSONArray("box") ?: return null
        if (b.length() != 4) return null
        val rays = f.optJSONArray("rays")?.let { r ->
            (0 until r.length()).mapNotNull { j ->
                r.optJSONArray(j)?.takeIf { it.length() == 4 }?.let {
                    Ray(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
                }
            }
        }.orEmpty()
        return FaceBox(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3),
            f.optDouble("yaw", 0.0).toFloat(), f.optDouble("pitch", 0.0).toFloat(), rays)
    }

    // ------------------------------------------------------------------ drawing & helpers

    /** Front-camera frames arrive sensor-oriented; rotate upright and mirror like a selfie preview. */
    private fun toUprightMirrored(image: ImageProxy): Bitmap =
        FrameCodec.uprightMirrored(image.toBitmap(), image.imageInfo.rotationDegrees)

    /** Red box around each onlooker; in eye mode also "laser" rays from the irises along the gaze. */
    private fun annotate(src: Bitmap, onlookers: List<FaceBox>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val stroke = maxOf(4f, out.width / 160f)
        val box = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = stroke }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 255, 0, 0); strokeWidth = stroke * 3; strokeCap = Paint.Cap.ROUND
        }
        val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 70, 70); strokeWidth = stroke * 0.8f; strokeCap = Paint.Cap.ROUND
        }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 225, 225) }
        val canvas = Canvas(out)
        onlookers.forEach { f ->
            canvas.drawRect(f.left.toFloat(), f.top.toFloat(), f.right.toFloat(), f.bottom.toFloat(), box)
            f.rays.forEach { r ->
                canvas.drawLine(r.x0, r.y0, r.x1, r.y1, glow)
                canvas.drawCircle(r.x0, r.y0, stroke * 2f, glow)
                canvas.drawLine(r.x0, r.y0, r.x1, r.y1, beam)
                canvas.drawCircle(r.x0, r.y0, stroke * 0.7f, core)
            }
        }
        return out
    }

    private fun onMain(block: () -> Unit) = ContextCompat.getMainExecutor(this).execute {
        if (_running.value) block()
    }

    /** Debug builds only: feed an image from the app's files dir through the real pipeline (emulator tests). */
    private fun injectDebugFrame(relativePath: String) {
        analysisExecutor.execute {
            val file = File(filesDir, relativePath)
            val bmp = BitmapFactory.decodeFile(file.path) ?: run { Log.w(TAG, "cannot decode $file"); return@execute }
            Log.i(TAG, "debug frame injected: ${bmp.width}x${bmp.height}")
            process(bmp, System.currentTimeMillis(), null)
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
        val state = _serverState.value
        val text = when {
            paused -> R.string.notif_paused_text
            !prefs.processOnServer || account == null -> R.string.notif_watching_text
            state == ServerState.CONNECTED -> R.string.notif_server_text
            (state == ServerState.FALLBACK || state == ServerState.REJECTED) && prefs.fallbackLocal ->
                R.string.notif_fallback_text
            else -> R.string.notif_connecting_text
        }
        val watched = !paused && state == ServerState.CONNECTED && _webViewers.value > 0
        return NotificationCompat.Builder(this, RwrApp.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle(getString(if (watched) R.string.notif_live_view_title else R.string.notif_watching_title))
            .setContentText(getString(if (watched) R.string.notif_live_view_text else text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateStatusNotification(paused: Boolean = !isInteractive()) {
        if (!_running.value) return
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildStatusNotification(paused))
        }
    }

    private fun refreshTile() =
        TileService.requestListeningState(this, ComponentName(this, WatchTileService::class.java))

    enum class ServerState { OFF, CONNECTING, CONNECTED, FALLBACK, REJECTED }

    companion object {
        private const val TAG = "WatcherService"
        private const val NOTIFICATION_ID = 1
        /** ~3 analysed frames per second on the phone: responsive enough, light on battery. */
        private const val FRAME_INTERVAL_MS = 330L
        /** Frames kept even in photo mode, so a server alert can find the frame it refers to. */
        private const val RING_MIN_SECONDS = 4
        private const val SERVER_DEBUG_INTERVAL_MS = 150L
        const val ACTION_STOP = "io.github.revocx35.rwr.STOP"
        const val ACTION_RELOAD_SERVER = "io.github.revocx35.rwr.RELOAD_SERVER"
        const val ACTION_DEBUG_INJECT = "io.github.revocx35.rwr.DEBUG_INJECT"
        const val EXTRA_PATH = "path"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _webViewers = MutableStateFlow(0)
        /** People watching this phone's frames live in the server's web UI (server mode only). */
        val webViewers: StateFlow<Int> = _webViewers.asStateFlow()
        private val _serverState = MutableStateFlow(ServerState.OFF)
        val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

        /** Call only while the app is in the foreground (see class doc). */
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, WatcherService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, WatcherService::class.java))
        }

        /** The paired server changed (paired/unpaired): reconnect if running. */
        fun reloadServer(context: Context) {
            if (_running.value) {
                context.startService(Intent(context, WatcherService::class.java).setAction(ACTION_RELOAD_SERVER))
            }
        }
    }
}
