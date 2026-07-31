package com.glance.kiosk

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.glance.GlanceApp
import com.glance.MainActivity
import com.glance.R
import com.glance.config.AppConfig
import com.glance.mqtt.MqttLightCommand
import com.glance.mqtt.MqttReportedState
import com.glance.mqtt.MqttStateManager
import com.glance.screen.ScheduleManager

/**
 * Foreground owner of all control-plane work: MQTT, screen commands and scheduling.
 *
 * Keeping this state outside MainActivity means an MQTT ON command can wake and recreate the
 * dashboard even after Android has destroyed the activity while the display was off.
 */
class KioskService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var config: AppConfig
    private lateinit var powerManager: PowerManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private var mqttStateManager: MqttStateManager? = null
    private var scheduleManager: ScheduleManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var initialized = false

    @Volatile private var reportedScreenOn = true
    @Volatile private var reportedBrightness = 5

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> updateScreenState(true)
                Intent.ACTION_SCREEN_OFF -> updateScreenState(false)
            }
        }
    }

    private val activityStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REPORT_SCREEN_STATE -> {
                    updateScreenState(intent.getBooleanExtra(EXTRA_SCREEN_ON, reportedScreenOn))
                }
                ACTION_REPORT_BRIGHTNESS -> {
                    updateBrightness(intent.getIntExtra(EXTRA_BRIGHTNESS, reportedBrightness))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = GlanceApp.instance.appConfig
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        devicePolicyManager =
            getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        reportedScreenOn = powerManager.isInteractive
        reportedBrightness = clampBrightness(config.lastKnownBrightness)
        Log.i(TAG, "KioskService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val wasInitialized = initialized
        ensureInitialized()

        when (intent?.action) {
            ACTION_RELOAD_CONFIG -> if (wasInitialized) reloadConfig()
            ACTION_COMMAND_SCREEN -> {
                handleLightCommand(
                    MqttLightCommand(
                        screenOn = intent.getBooleanExtra(EXTRA_SCREEN_ON, true),
                        brightness = null
                    )
                )
            }
        }

        Log.i(TAG, "KioskService running")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed, restarting dashboard")
        launchDashboard()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        mqttStateManager?.stop()
        mqttStateManager = null
        releaseWakeLock()
        if (initialized) {
            unregisterReceiver(screenStateReceiver)
            unregisterReceiver(activityStateReceiver)
        }
        initialized = false
        super.onDestroy()
    }

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true

        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            activityStateReceiver,
            IntentFilter().apply {
                addAction(ACTION_REPORT_SCREEN_STATE)
                addAction(ACTION_REPORT_BRIGHTNESS)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        startMqtt()
        scheduleManager = ScheduleManager(this, config).also { it.start() }
    }

    private fun startMqtt() {
        mqttStateManager = MqttStateManager(
            config = config,
            stateProvider = {
                MqttReportedState(
                    screenOn = reportedScreenOn,
                    brightness = reportedBrightness
                )
            },
            commandHandler = ::handleLightCommand
        ).also { it.start() }
    }

    private fun reloadConfig() {
        Log.i(TAG, "Reloading kiosk configuration")
        mqttStateManager?.removeDiscovery()
        mqttStateManager?.stop()
        mqttStateManager = null

        scheduleManager?.stop()
        reportedBrightness = clampBrightness(config.lastKnownBrightness)
        startMqtt()
        scheduleManager = ScheduleManager(this, config).also { it.start() }
    }

    private fun handleLightCommand(command: MqttLightCommand) {
        val brightness = command.brightness?.let(::clampBrightness)
        if (brightness != null) {
            updateBrightness(brightness, publish = false)
            sendActivityControl(
                Intent(ACTION_CONTROL_BRIGHTNESS)
                    .putExtra(EXTRA_BRIGHTNESS, brightness)
            )
        }

        val targetScreenOn = command.screenOn ?: if (brightness != null) true else null
        if (targetScreenOn == null) {
            mqttStateManager?.publishCurrentState()
            return
        }

        val wasScreenOn = reportedScreenOn
        reportedScreenOn = targetScreenOn
        mqttStateManager?.publishCurrentState()

        if (targetScreenOn) {
            wakeDisplay()
            if (!wasScreenOn) {
                launchDashboard()
            }
            sendActivityControl(
                Intent(ACTION_CONTROL_SCREEN).putExtra(EXTRA_SCREEN_ON, true)
            )
        } else {
            if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                try {
                    releaseWakeLock()
                    devicePolicyManager.lockNow()
                    Log.i(TAG, "Screen turned OFF directly from kiosk service")
                } catch (e: Exception) {
                    Log.e(TAG, "Device Owner screen-off failed", e)
                    reportedScreenOn = powerManager.isInteractive
                    mqttStateManager?.publishCurrentState()
                }
            } else {
                // Ensure the activity exists so its reversible black-overlay fallback can run.
                launchDashboard()
                sendActivityControl(
                    Intent(ACTION_CONTROL_SCREEN).putExtra(EXTRA_SCREEN_ON, false)
                )
                mainHandler.postDelayed(
                    {
                        sendActivityControl(
                            Intent(ACTION_CONTROL_SCREEN).putExtra(EXTRA_SCREEN_ON, false)
                        )
                    },
                    ACTIVITY_CONTROL_RETRY_MS
                )
            }
        }
    }

    private fun updateScreenState(isOn: Boolean) {
        if (reportedScreenOn == isOn) return
        reportedScreenOn = isOn
        mqttStateManager?.publishCurrentState()
        Log.i(TAG, "Reported hardware screen state: $isOn")
    }

    private fun updateBrightness(brightness: Int, publish: Boolean = true) {
        val clamped = clampBrightness(brightness)
        if (reportedBrightness == clamped) return
        reportedBrightness = clamped
        config.lastKnownBrightness = clamped
        if (publish) {
            mqttStateManager?.publishCurrentState()
        }
    }

    private fun sendActivityControl(intent: Intent) {
        sendBroadcast(intent.setPackage(packageName))
    }

    private fun clampBrightness(brightness: Int): Int {
        val lower = minOf(config.minBrightness, config.maxBrightness)
        val upper = maxOf(config.minBrightness, config.maxBrightness)
        return brightness.coerceIn(lower, upper)
    }

    private fun wakeDisplay() {
        if (powerManager.isInteractive) return
        try {
            releaseWakeLock()
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG
            ).apply {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            mainHandler.postDelayed(::releaseWakeLock, WAKE_LOCK_RELEASE_DELAY_MS)
            Log.i(TAG, "Display wake requested by kiosk service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake display", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun launchDashboard() {
        val restartIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(restartIntent)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
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
        private const val WAKE_LOCK_TAG = "glance:kiosk_screen_wake"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
        private const val WAKE_LOCK_RELEASE_DELAY_MS = 3_000L
        private const val ACTIVITY_CONTROL_RETRY_MS = 750L

        const val ACTION_RELOAD_CONFIG = "com.glance.action.RELOAD_CONFIG"
        const val ACTION_COMMAND_SCREEN = "com.glance.action.COMMAND_SCREEN"
        const val ACTION_CONTROL_SCREEN = "com.glance.action.CONTROL_SCREEN"
        const val ACTION_CONTROL_BRIGHTNESS = "com.glance.action.CONTROL_BRIGHTNESS"
        const val ACTION_REPORT_SCREEN_STATE = "com.glance.action.REPORT_SCREEN_STATE"
        const val ACTION_REPORT_BRIGHTNESS = "com.glance.action.REPORT_BRIGHTNESS"
        const val EXTRA_SCREEN_ON = "screen_on"
        const val EXTRA_BRIGHTNESS = "brightness"
    }
}
