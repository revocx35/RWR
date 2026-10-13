package io.github.revocx35.rwr.alert

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.scale
import io.github.revocx35.rwr.Prefs
import io.github.revocx35.rwr.R
import io.github.revocx35.rwr.RwrApp
import java.util.Date

/**
 * Shows an onlooker alert: always a high-priority notification with the snapshot, plus a pop-up card
 * drawn over whatever app is open when "Display over other apps" is granted and enabled.
 * All methods must be called on the main thread.
 */
class AlertPresenter(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private val autoDismiss = Runnable { dismissOverlay() }
    private var nextNotificationId = ALERT_NOTIFICATION_BASE
    /** The alert currently waiting for its clip: (alert time, notification id, snapshot, subtitle). */
    private var pending: PendingClip? = null

    private class PendingClip(val at: Long, val notificationId: Int, val snapshot: Bitmap, val subtitle: String)

    /**
     * [savedUri]: the photo in the gallery, if saved. [clipPending]: a video clip is being recorded for this alert;
     * call [clipReady] when it is saved. [note] is appended to the subtitle (e.g. "sent to Immich").
     * [overlay] false: notification only (the debug view is open and would be covered by the pop-up).
     */
    fun show(
        snapshot: Bitmap, savedUri: Uri?, onlookerCount: Int, at: Long, clipPending: Boolean = false,
        note: String? = null, overlay: Boolean = true,
    ) {
        val subtitle = buildString {
            append(DateFormat.getTimeFormat(context).format(Date(at)))
            if (onlookerCount > 1) append(" · $onlookerCount people")
            if (savedUri != null) append(" · ").append(context.getString(R.string.alert_saved))
            if (clipPending) append(" · ").append(context.getString(R.string.alert_recording))
            if (note != null) append(" · ").append(note)
        }
        val id = nextNotificationId++
        if (clipPending) pending = PendingClip(at, id, snapshot, subtitle)
        postNotification(id, snapshot, savedUri, "image/jpeg", subtitle)
        if (overlay && Prefs(context).overlayPopup && Settings.canDrawOverlays(context)) {
            showOverlay(snapshot, savedUri, "image/jpeg", subtitle, clipPending)
        }
    }

    /** The clip for the alert at [at] finished (null uri = not saved to the gallery). */
    fun clipReady(at: Long, uri: Uri?, note: String?) {
        val p = pending?.takeIf { it.at == at } ?: return
        pending = null
        val subtitle = p.subtitle.replace(
            context.getString(R.string.alert_recording),
            if (uri != null) context.getString(R.string.alert_clip_saved) else note ?: context.getString(R.string.alert_clip_done),
        )
        postNotification(p.notificationId, p.snapshot, uri, "video/mp4", subtitle)
        overlay?.let { view ->
            view.findViewById<TextView>(R.id.alert_subtitle).text = subtitle
            view.findViewById<Button>(R.id.alert_open).apply {
                if (uri != null) {
                    visibility = View.VISIBLE
                    isEnabled = true
                    text = context.getString(R.string.alert_open_clip)
                    setOnClickListener { openMedia(uri, "video/mp4") }
                } else {
                    visibility = View.GONE
                }
            }
        }
    }

    private fun postNotification(id: Int, snapshot: Bitmap, savedUri: Uri?, mime: String, subtitle: String) {
        // Keep the binder payload small: big pictures over a few MB get dropped by the system.
        val preview = snapshot.fitWithin(720)
        val builder = NotificationCompat.Builder(context, RwrApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle(context.getString(R.string.alert_title))
            .setContentText(subtitle)
            .setLargeIcon(preview)
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(preview).bigLargeIcon(null as Bitmap?))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
        if (savedUri != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, id, viewIntent(savedUri, mime),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        }
        try {
            nm.notify(id, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission missing", e)
        }
    }

    @SuppressLint("InflateParams")
    private fun showOverlay(snapshot: Bitmap, savedUri: Uri?, mime: String, subtitle: String, clipPending: Boolean) {
        dismissOverlay()
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_alert, null)
        view.findViewById<ImageView>(R.id.alert_snapshot).setImageBitmap(snapshot)
        view.findViewById<TextView>(R.id.alert_subtitle).text = subtitle
        view.findViewById<Button>(R.id.alert_dismiss).setOnClickListener { dismissOverlay() }
        view.findViewById<Button>(R.id.alert_open).apply {
            when {
                clipPending -> {
                    visibility = View.VISIBLE
                    isEnabled = false
                    text = context.getString(R.string.alert_saving_clip)
                }
                savedUri != null -> {
                    visibility = View.VISIBLE
                    setOnClickListener { openMedia(savedUri, mime) }
                }
                else -> visibility = View.GONE
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touches outside the card go to the app underneath; the card itself is tappable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Centered: the heads-up notification occupies the top and draws above app overlays.
            gravity = Gravity.CENTER
            title = "RWR alert"
        }
        try {
            wm.addView(view, params)
            overlay = view
            main.postDelayed(autoDismiss, OVERLAY_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "cannot show overlay", e)
        }
    }

    fun dismissOverlay() {
        main.removeCallbacks(autoDismiss)
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun openMedia(uri: Uri, mime: String) {
        dismissOverlay()
        // Holding SYSTEM_ALERT_WINDOW exempts us from background-activity-start limits.
        runCatching { context.startActivity(viewIntent(uri, mime)) }
            .onFailure { Log.w(TAG, "cannot open $mime", it) }
    }

    private fun viewIntent(uri: Uri, mime: String) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun Bitmap.fitWithin(maxSide: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return this
        val f = maxSide.toFloat() / longest
        return scale((width * f).toInt(), (height * f).toInt())
    }

    private companion object {
        const val TAG = "AlertPresenter"
        const val ALERT_NOTIFICATION_BASE = 1000
        const val OVERLAY_TIMEOUT_MS = 30_000L
    }
}
