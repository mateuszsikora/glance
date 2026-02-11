package com.glance.kiosk

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.glance.GlanceApp
import com.glance.MainActivity
import com.glance.R

/**
 * Foreground service keeping the kiosk app alive.
 * Critical for EMUI which aggressively kills background processes.
 */
class KioskService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KioskService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "KioskService started in foreground")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed, restarting...")
        val restartIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(restartIntent)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, GlanceApp.CHANNEL_KIOSK)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_kiosk_running))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "KioskService"
        private const val NOTIFICATION_ID = 1001
    }
}
