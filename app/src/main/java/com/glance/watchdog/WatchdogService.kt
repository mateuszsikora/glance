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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.glance.GlanceApp
import com.glance.MainActivity
import com.glance.R
import com.glance.content.ContentSchedulePolicy
import java.time.LocalTime
import java.util.concurrent.Executors

/**
 * Foreground service monitoring WebView health and app stability.
 */
class WatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastMemoryReloadElapsedMs = 0L
    private var loopsStarted = false
    private var reloadPendingUntilScreenOn = false
    private var destroyed = false
    private val stalePolicy = StaleDashboardPolicy()
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private var probeInFlight = false
    private val powerManager by lazy {
        getSystemService(POWER_SERVICE) as PowerManager
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WatchdogService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (GlanceApp.instance.appConfig.isKioskSuspended) {
            Log.i(TAG, "Kiosk is suspended; stopping watchdog")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
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
        val intervalMs = config.healthCheckIntervalSeconds.toLong() * 1000L

        handler.postDelayed(object : Runnable {
            override fun run() {
                performHealthCheck()
                handler.postDelayed(this, intervalMs)
            }
        }, intervalMs)
    }

    private fun startPeriodicReloadLoop() {
        val config = GlanceApp.instance.appConfig
        val intervalMs = config.webviewReloadIntervalHours.toLong() * 3600L * 1000L

        handler.postDelayed(object : Runnable {
            override fun run() {
                if (powerManager.isInteractive) {
                    Log.i(TAG, "Periodic WebView reload triggered")
                    triggerWebViewReload()
                } else {
                    reloadPendingUntilScreenOn = true
                    Log.i(TAG, "Periodic reload deferred until the screen wakes")
                }
                handler.postDelayed(this, intervalMs)
            }
        }, intervalMs)
    }

    private fun performHealthCheck() {
        if (!powerManager.isInteractive) {
            stalePolicy.onScreenOff(SystemClock.elapsedRealtime())
            Log.d(TAG, "Screen is off; skipping WebView health check")
            return
        }
        if (stalePolicy.onScreenOn(SystemClock.elapsedRealtime())) {
            Log.i(TAG, "Dashboard slept through a long screen-off window; reload required")
            reloadPendingUntilScreenOn = true
        }
        if (reloadPendingUntilScreenOn) {
            reloadPendingUntilScreenOn = false
            Log.i(TAG, "Running deferred WebView reload")
            triggerWebViewReload()
            return
        }

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
        probeDashboardReachability()
        sendBroadcast(Intent(ACTION_HEALTH_CHECK).setPackage(packageName))
    }

    /**
     * The in-page health check cannot see a dashboard whose live connection died while the
     * page itself stayed loaded, so the host is probed out of band instead.
     */
    private fun probeDashboardReachability() {
        if (probeInFlight || destroyed) return
        val url = activeDashboardUrl() ?: return
        probeInFlight = true
        probeExecutor.execute {
            val reachable = DashboardReachabilityProbe.isReachable(url)
            handler.post {
                probeInFlight = false
                if (destroyed) return@post
                if (!stalePolicy.onProbeResult(SystemClock.elapsedRealtime(), reachable)) return@post
                if (powerManager.isInteractive) {
                    Log.i(TAG, "Dashboard host answered again, reloading the stale page")
                    triggerWebViewReload()
                } else {
                    reloadPendingUntilScreenOn = true
                }
            }
        }
    }

    private fun activeDashboardUrl(): String? {
        val config = GlanceApp.instance.appConfig
        return ContentSchedulePolicy.activeUrls(
            now = LocalTime.now(),
            defaultUrls = config.dashboardUrls,
            scheduleEnabled = config.contentScheduleEnabled,
            profiles = config.contentProfiles
        ).firstOrNull()?.takeIf(String::isNotBlank)
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

    // These callbacks still matter on the supported Android 8-14 devices even though
    // Android 15 deprecated the legacy trim levels for newer platform behavior.
    @Suppress("DEPRECATION")
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
        if (!powerManager.isInteractive) {
            reloadPendingUntilScreenOn = true
            Log.w(TAG, "Memory recovery deferred while screen is off ($reason)")
            return
        }
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
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        probeExecutor.shutdownNow()
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
