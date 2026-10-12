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

    fun show(snapshot: Bitmap, savedUri: Uri?, onlookerCount: Int, at: Long) {
        val subtitle = buildString {
            append(DateFormat.getTimeFormat(context).format(Date(at)))
            if (onlookerCount > 1) append(" · $onlookerCount people")
            if (savedUri != null) append(" · ").append(context.getString(R.string.alert_saved))
        }
        postNotification(snapshot, savedUri, subtitle)
        if (Prefs(context).overlayPopup && Settings.canDrawOverlays(context)) showOverlay(snapshot, savedUri, subtitle)
    }

    private fun postNotification(snapshot: Bitmap, savedUri: Uri?, subtitle: String) {
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
                    context, nextNotificationId, viewImageIntent(savedUri),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        }
        try {
            nm.notify(nextNotificationId++, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission missing", e)
        }
    }

    @SuppressLint("InflateParams")
    private fun showOverlay(snapshot: Bitmap, savedUri: Uri?, subtitle: String) {
        dismissOverlay()
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_alert, null)
        view.findViewById<ImageView>(R.id.alert_snapshot).setImageBitmap(snapshot)
        view.findViewById<TextView>(R.id.alert_subtitle).text = subtitle
        view.findViewById<Button>(R.id.alert_dismiss).setOnClickListener { dismissOverlay() }
        view.findViewById<Button>(R.id.alert_open).apply {
            visibility = if (savedUri != null) View.VISIBLE else View.GONE
            setOnClickListener {
                dismissOverlay()
                // Holding SYSTEM_ALERT_WINDOW exempts us from background-activity-start limits.
                runCatching { context.startActivity(viewImageIntent(savedUri!!)) }
                    .onFailure { Log.w(TAG, "cannot open snapshot", it) }
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

    private fun viewImageIntent(uri: Uri) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "image/jpeg")
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
        const val OVERLAY_TIMEOUT_MS = 20_000L
    }
}
