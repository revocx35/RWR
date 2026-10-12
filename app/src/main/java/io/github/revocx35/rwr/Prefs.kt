package io.github.revocx35.rwr

import android.content.Context
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

    private companion object {
        const val KEY_IGNORE_OWNER = "ignore_owner"
        const val KEY_OVERLAY = "overlay_popup"
        const val KEY_ANGLE = "max_head_angle"
        const val KEY_COOLDOWN = "cooldown_sec"
    }
}
