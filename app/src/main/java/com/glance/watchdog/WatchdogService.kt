package com.glance.watchdog

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
    private var lastMemoryReloadElapsedMs = 0L
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
                handler.postDelayed(this, intervalMs)
            }
        }, intervalMs)
    }

    private fun performHealthCheck() {
        val memoryInfo = getMemoryInfo()
        Log.d(
            TAG,
            "Health check — heap: ${memoryInfo.heapUsedMB}/${memoryInfo.heapMaxMB}MB " +
                "(${memoryInfo.heapUsedPercent}%), system available: " +
                "${memoryInfo.systemAvailableMB}/${memoryInfo.systemTotalMB}MB"
        )

        if (memoryInfo.systemLowMemory ||
            memoryInfo.heapUsedPercent > MEMORY_THRESHOLD_PERCENT
        ) {
            requestMemoryRecovery(
                "lowMemory=${memoryInfo.systemLowMemory}, " +
                    "heap=${memoryInfo.heapUsedPercent}%"
            )
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
        val heapMax = runtime.maxMemory()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val system = ActivityManager.MemoryInfo().also {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }

        return MemoryInfo(
            heapUsedMB = (heapUsed / BYTES_PER_MB).toInt(),
            heapMaxMB = (heapMax / BYTES_PER_MB).toInt(),
            heapUsedPercent = (heapUsed * 100 / heapMax).toInt(),
            systemAvailableMB = (system.availMem / BYTES_PER_MB).toInt(),
            systemTotalMB = (system.totalMem / BYTES_PER_MB).toInt(),
            systemLowMemory = system.lowMemory
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        ) {
            requestMemoryRecovery("trim level=$level")
        }
    }

    private fun requestMemoryRecovery(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMemoryReloadElapsedMs < MEMORY_RELOAD_COOLDOWN_MS) return
        lastMemoryReloadElapsedMs = now
        Log.w(TAG, "Memory pressure ($reason), forcing WebView reload")
        triggerWebViewReload()
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
        val heapUsedMB: Int,
        val heapMaxMB: Int,
        val heapUsedPercent: Int,
        val systemAvailableMB: Int,
        val systemTotalMB: Int,
        val systemLowMemory: Boolean
    )

    companion object {
        private const val TAG = "WatchdogService"
        private const val NOTIFICATION_ID = 1002
        private const val MEMORY_THRESHOLD_PERCENT = 85
        private const val MEMORY_RELOAD_COOLDOWN_MS = 60_000L
        private const val BYTES_PER_MB = 1024L * 1024L

        const val ACTION_RELOAD_WEBVIEW = "com.glance.ACTION_RELOAD_WEBVIEW"
        const val ACTION_HEALTH_CHECK = "com.glance.ACTION_HEALTH_CHECK"
    }
}
