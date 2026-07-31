package com.glance.watchdog

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.glance.GlanceApp
import com.glance.MainActivity
import com.glance.R

/**
 * Foreground service monitoring WebView health and app stability.
 */
class WatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastReloadTime = System.currentTimeMillis()
    private var loopsStarted = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WatchdogService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!loopsStarted) {
            loopsStarted = true
            startHealthCheckLoop()
            startPeriodicReloadLoop()
        }
        Log.i(TAG, "WatchdogService started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHealthCheckLoop() {
        val config = GlanceApp.instance.appConfig
        val intervalMs = config.healthCheckIntervalSeconds * 1000L

        handler.postDelayed(object : Runnable {
            override fun run() {
                performHealthCheck()
                handler.postDelayed(this, intervalMs)
            }
        }, intervalMs)
    }

    private fun startPeriodicReloadLoop() {
        val config = GlanceApp.instance.appConfig
        val intervalMs = config.webviewReloadIntervalHours * 3600 * 1000L

        handler.postDelayed(object : Runnable {
            override fun run() {
                Log.i(TAG, "Periodic WebView reload triggered")
                triggerWebViewReload()
                lastReloadTime = System.currentTimeMillis()
                handler.postDelayed(this, intervalMs)
            }
        }, intervalMs)
    }

    private fun performHealthCheck() {
        val memoryInfo = getMemoryInfo()
        Log.d(TAG, "Health check — free: ${memoryInfo.freeMemMB}MB, " +
            "total: ${memoryInfo.totalMemMB}MB, used: ${memoryInfo.usedPercent}%")

        if (memoryInfo.usedPercent > MEMORY_THRESHOLD_PERCENT) {
            Log.w(TAG, "Memory critically low (${memoryInfo.usedPercent}%), forcing reload")
            triggerWebViewReload()
        }
        sendBroadcast(Intent(ACTION_HEALTH_CHECK).setPackage(packageName))
    }

    private fun triggerWebViewReload() {
        // Explicitly scoped to our own package — the receiver is registered at
        // runtime and not exported, so an implicit broadcast would be unsafe.
        val intent = Intent(ACTION_RELOAD_WEBVIEW).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun getMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val totalMem = runtime.maxMemory()
        val freeMem = runtime.freeMemory()
        val usedMem = runtime.totalMemory() - freeMem
        val usedPercent = (usedMem * 100 / totalMem).toInt()

        return MemoryInfo(
            freeMemMB = (freeMem / 1024 / 1024).toInt(),
            totalMemMB = (totalMem / 1024 / 1024).toInt(),
            usedPercent = usedPercent
        )
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, GlanceApp.CHANNEL_WATCHDOG)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_watchdog_running))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loopsStarted = false
        super.onDestroy()
    }

    private data class MemoryInfo(
        val freeMemMB: Int,
        val totalMemMB: Int,
        val usedPercent: Int
    )

    companion object {
        private const val TAG = "WatchdogService"
        private const val NOTIFICATION_ID = 1002
        private const val MEMORY_THRESHOLD_PERCENT = 85

        const val ACTION_RELOAD_WEBVIEW = "com.glance.ACTION_RELOAD_WEBVIEW"
        const val ACTION_HEALTH_CHECK = "com.glance.ACTION_HEALTH_CHECK"
    }
}
