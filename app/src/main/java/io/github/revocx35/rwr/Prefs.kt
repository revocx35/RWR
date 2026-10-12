package io.github.revocx35.rwr

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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
    }
}
