package io.github.revocx35.rwr

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.revocx35.rwr.detection.DetectionMode

/** User settings, stored in SharedPreferences. Read per frame by the service, so keep it cheap. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("rwr", Context.MODE_PRIVATE)

    /** Treat the largest face as the phone's owner and never alert on it. */
    var ignoreOwner: Boolean
        get() = sp.getBoolean(KEY_IGNORE_OWNER, true)
        set(v) = sp.edit { putBoolean(KEY_IGNORE_OWNER, v) }

    /** Show the snapshot pop-up over other apps (needs "Display over other apps"). */
    var overlayPopup: Boolean
        get() = sp.getBoolean(KEY_OVERLAY, true)
        set(v) = sp.edit { putBoolean(KEY_OVERLAY, v) }

    /** Max head yaw/pitch (degrees) for a face to count as looking at the screen. */
    var maxHeadAngle: Float
        get() = sp.getFloat(KEY_ANGLE, 25f)
        set(v) = sp.edit { putFloat(KEY_ANGLE, v) }

    /** Minimum seconds between two alerts. */
    var cooldownSec: Int
        get() = sp.getInt(KEY_COOLDOWN, 15)
        set(v) = sp.edit { putInt(KEY_COOLDOWN, v) }

    /** Camera frame rate requested from the sensor; the closest supported range is used. */
    var cameraFps: Int
        get() = sp.getInt(KEY_FPS, 5)
        set(v) = sp.edit { putInt(KEY_FPS, v) }

    /** Analyse at 640x480 instead of 1280x720: less power, softer snapshots. */
    var lowResolution: Boolean
        get() = sp.getBoolean(KEY_LOW_RES, true)
        set(v) = sp.edit { putBoolean(KEY_LOW_RES, v) }

    /**
     * Receive raw YUV frames and convert only the ones we analyse. Off = CameraX converts every frame
     * to RGBA (the v1.0.0 behaviour), kept as a troubleshooting fallback.
     */
    var efficientConversion: Boolean
        get() = sp.getBoolean(KEY_EFFICIENT, true)
        set(v) = sp.edit { putBoolean(KEY_EFFICIENT, v) }

    /** Head pose only, or head pose + iris gaze (more accurate, more CPU on the phone). */
    var detectionMode: DetectionMode
        get() = if (sp.getString(KEY_MODE, "head") == "eye") DetectionMode.EYE else DetectionMode.HEAD
        set(v) = sp.edit { putString(KEY_MODE, if (v == DetectionMode.EYE) "eye" else "head") }

    /** Eye mode: max gaze angle (head + eyes, degrees) that counts as looking at the screen. */
    var maxGazeAngle: Float
        get() = sp.getFloat(KEY_GAZE, 12f)
        set(v) = sp.edit { putFloat(KEY_GAZE, v) }

    /** Save a still photo, or a video clip from [preSec] before to [postSec] after the alert. */
    var mediaType: MediaType
        get() = if (sp.getString(KEY_MEDIA, "photo") == "video") MediaType.VIDEO else MediaType.PHOTO
        set(v) = sp.edit { putString(KEY_MEDIA, if (v == MediaType.VIDEO) "video" else "photo") }

    var preSec: Int
        get() = sp.getInt(KEY_PRE, 10)
        set(v) = sp.edit { putInt(KEY_PRE, v) }

    var postSec: Int
        get() = sp.getInt(KEY_POST, 10)
        set(v) = sp.edit { putInt(KEY_POST, v) }

    /** Where snapshots/clips go. Immich needs a paired RWR server. */
    var saveTo: SaveTo
        get() = when (sp.getString(KEY_SAVE_TO, "gallery")) {
            "immich" -> SaveTo.IMMICH
            "both" -> SaveTo.BOTH
            else -> SaveTo.GALLERY
        }
        set(v) = sp.edit { putString(KEY_SAVE_TO, v.name.lowercase()) }

    /** Run detection on the phone, or stream frames to the paired RWR server. */
    var processOnServer: Boolean
        get() = sp.getBoolean(KEY_SERVER, false)
        set(v) = sp.edit { putBoolean(KEY_SERVER, v) }

    /** Server mode: detect on the phone while the server can't be reached. */
    var fallbackLocal: Boolean
        get() = sp.getBoolean(KEY_FALLBACK, true)
        set(v) = sp.edit { putBoolean(KEY_FALLBACK, v) }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        /** Keys whose change requires rebinding the camera. */
        val CAMERA_KEYS = setOf("camera_fps", "low_resolution", "efficient_conversion")
        private const val KEY_FPS = "camera_fps"
        private const val KEY_LOW_RES = "low_resolution"
        private const val KEY_EFFICIENT = "efficient_conversion"
        private const val KEY_IGNORE_OWNER = "ignore_owner"
        private const val KEY_OVERLAY = "overlay_popup"
        private const val KEY_ANGLE = "max_head_angle"
        private const val KEY_COOLDOWN = "cooldown_sec"
        private const val KEY_MODE = "detection_mode"
        private const val KEY_GAZE = "max_gaze_angle"
        private const val KEY_MEDIA = "media_type"
        private const val KEY_PRE = "pre_sec"
        private const val KEY_POST = "post_sec"
        private const val KEY_SAVE_TO = "save_to"
        const val KEY_SERVER = "process_on_server"
        private const val KEY_FALLBACK = "fallback_local"
        /** Keys the server needs to know about (sent again when they change). */
        val STREAM_KEYS = setOf(
            KEY_MODE, KEY_GAZE, KEY_MEDIA, KEY_PRE, KEY_POST, KEY_SAVE_TO, KEY_IGNORE_OWNER, KEY_ANGLE, KEY_COOLDOWN,
        )
    }
}

enum class MediaType { PHOTO, VIDEO }

enum class SaveTo {
    GALLERY, IMMICH, BOTH;

    val gallery: Boolean get() = this != IMMICH
    val immich: Boolean get() = this != GALLERY
}
