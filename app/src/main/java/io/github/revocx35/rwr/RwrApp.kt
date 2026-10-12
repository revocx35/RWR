package io.github.revocx35.rwr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class RwrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_WATCH, getString(R.string.channel_watch), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_ALERT, getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH),
            )
        )
    }

    companion object {
        const val CHANNEL_WATCH = "watch"
        const val CHANNEL_ALERT = "alert"
    }
}
