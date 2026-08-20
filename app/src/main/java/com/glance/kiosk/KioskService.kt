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
import com.glance.battery.BatteryMonitor
import com.glance.battery.BatteryStatus
import com.glance.config.AppConfig
import com.glance.mqtt.MqttLightCommand
import com.glance.mqtt.MqttReportedState
import com.glance.mqtt.MqttStateManager
import com.glance.remote.RemoteConfigServer
import com.glance.screen.PowerWakePolicy
import com.glance.screen.ScheduleManager
import com.glance.update.UpdateChecker
import java.util.concurrent.Executors

/**
 * Foreground owner of all control-plane work: MQTT, screen commands and scheduling.
 *
 * Keeping this state outside MainActivity means an MQTT ON command can wake and recreate the
 * dashboard even after Android has destroyed the activity while the display was off.
 */
class KioskService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateCheckExecutor = Executors.newSingleThreadExecutor()

    private lateinit var config: AppConfig
    private lateinit var powerManager: PowerManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private var mqttStateManager: MqttStateManager? = null
    private var batteryMonitor: BatteryMonitor? = null
    private var scheduleManager: ScheduleManager? = null
    private var remoteConfigServer: RemoteConfigServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var initialized = false
    private var configReloadInProgress = false
    private var configReloadRequested = false
    private var destroyed = false

    @Volatile private var reportedScreenOn = true
    @Volatile private var reportedBrightness = 5
    @Volatile private var reportedBattery: BatteryStatus? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> reportScreenState(true)
                Intent.ACTION_SCREEN_OFF -> reportScreenState(false)
            }
        }
    }

    private val powerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> restoreScreenOffAfterPowerEvent()
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
        reportedScreenOn = config.requestedScreenOn && powerManager.isInteractive
        reportedBrightness = clampBrightness(config.lastKnownBrightness)
        Log.i(TAG, "KioskService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (config.isKioskSuspended) {
            Log.i(TAG, "Kiosk is suspended; ignoring service start")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
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
        if (config.isKioskSuspended) {
            Log.i(TAG, "Task removed while kiosk is suspended")
            return
        }
        Log.w(TAG, "Task removed, restarting dashboard")
        launchDashboard()
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        batteryMonitor?.stop()
        batteryMonitor = null
        mqttStateManager?.stop()
        mqttStateManager = null
        remoteConfigServer?.stop()
        remoteConfigServer = null
        // shutdown(), not shutdownNow(): an in-flight installation should be allowed to finish.
        updateCheckExecutor.shutdown()
        releaseWakeLock()
        if (initialized) {
            unregisterReceiver(screenStateReceiver)
            unregisterReceiver(powerStateReceiver)
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
            powerStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
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
        startBatteryMonitor()
        scheduleManager = ScheduleManager(this, config).also { it.start() }
        reconcileRemoteConfigServer()
    }

    private fun startMqtt() {
        mqttStateManager = MqttStateManager(
            context = this,
            config = config,
            stateProvider = {
                MqttReportedState(
                    screenOn = reportedScreenOn,
                    brightness = reportedBrightness,
                    battery = reportedBattery
                )
            },
            commandHandler = ::handleLightCommand
        ).also { it.start() }
    }

    private fun startBatteryMonitor() {
        batteryMonitor = BatteryMonitor(this) { status ->
            reportedBattery = status
            mqttStateManager?.publishBatteryState()
        }.also {
            it.start()
            reportedBattery = it.status
        }
    }

    private fun reloadConfig() {
        mainHandler.removeCallbacks(reconcileRemoteConfigRunnable)
        mainHandler.postDelayed(reconcileRemoteConfigRunnable, REMOTE_SERVER_RELOAD_DELAY_MS)
        if (configReloadInProgress) {
            configReloadRequested = true
            Log.i(TAG, "Configuration reload already running; another reload was queued")
            return
        }

        Log.i(TAG, "Reloading kiosk configuration")
        configReloadInProgress = true
        scheduleManager?.stop()
        scheduleManager = null

        val previousManager = mqttStateManager
        if (previousManager == null) {
            finishConfigReload(null)
            return
        }

        previousManager.removeDiscovery {
            finishConfigReload(previousManager)
        }
    }

    private fun finishConfigReload(previousManager: MqttStateManager?) {
        if (destroyed) return

        if (previousManager == null || mqttStateManager === previousManager) {
            previousManager?.stop()
            mqttStateManager = null
        }

        reportedBrightness = clampBrightness(config.lastKnownBrightness)
        startMqtt()
        scheduleManager = ScheduleManager(this, config).also { it.start() }
        configReloadInProgress = false

        if (configReloadRequested) {
            configReloadRequested = false
            reloadConfig()
        }
    }

    private val reconcileRemoteConfigRunnable = Runnable { reconcileRemoteConfigServer() }

    private fun reconcileRemoteConfigServer() {
        if (!config.remoteConfigEnabled) {
            remoteConfigServer?.stop()
            remoteConfigServer = null
            return
        }
        if (remoteConfigServer != null) return

        val server = RemoteConfigServer(
            config = config,
            onConfigChanged = {
                mainHandler.post {
                    if (destroyed) return@post
                    sendBroadcast(Intent(MainActivity.ACTION_RELOAD_UI).setPackage(packageName))
                    reloadConfig()
                }
            },
            onUpdateRequested = { installNow ->
                // Off the HTTP worker: the check downloads and installs, which must not hold a
                // request open or occupy one of the server's few threads.
                updateCheckExecutor.execute {
                    runCatching {
                        val checker = UpdateChecker(applicationContext)
                        if (installNow) checker.installNow() else checker.checkNow(force = true)
                    }.onFailure { Log.w(TAG, "Manual update check failed", it) }
                }
            }
        )
        try {
            server.start()
            remoteConfigServer = server
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start remote configuration server", e)
            server.stop()
        }
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

        mainHandler.removeCallbacks(restoreScreenOffRunnable)
        config.requestedScreenOn = targetScreenOn
        applyStayAwakePolicy(targetScreenOn)
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

    /** A deliberate state change inside Glance also redefines what the kiosk is asking for. */
    private fun updateScreenState(isOn: Boolean) {
        if (config.requestedScreenOn != isOn) {
            config.requestedScreenOn = isOn
            applyStayAwakePolicy(isOn)
        }
        reportScreenState(isOn)
    }

    /**
     * A hardware transition only describes what the display is doing. Android wakes the display
     * for reasons of its own — a charger connecting is the common one on a wall-mounted tablet —
     * and such a wake must not be mistaken for a request to abandon the screen schedule.
     */
    private fun reportScreenState(isOn: Boolean) {
        if (reportedScreenOn == isOn) return
        reportedScreenOn = isOn
        mqttStateManager?.publishCurrentState()
        Log.i(TAG, "Reported hardware screen state: $isOn")
    }

    /**
     * Undoes a display wake caused by the charger. Many devices light up whenever power is
     * connected or removed, which turns a smart plug that maintains the battery overnight into a
     * light in the room. The platform behaviour cannot be disabled, so it is reverted instead.
     */
    private fun restoreScreenOffAfterPowerEvent() {
        if (config.requestedScreenOn) return
        // The display may not have woken yet, so the decision waits for the platform to settle.
        mainHandler.removeCallbacks(restoreScreenOffRunnable)
        mainHandler.postDelayed(restoreScreenOffRunnable, POWER_EVENT_SETTLE_MS)
    }

    private val restoreScreenOffRunnable = Runnable {
        if (destroyed) return@Runnable
        if (!PowerWakePolicy.shouldRestoreScreenOff(config.requestedScreenOn, reportedScreenOn)) {
            return@Runnable
        }
        Log.i(TAG, "Display woke on a power event while the schedule asks for OFF")
        handleLightCommand(MqttLightCommand(screenOn = false, brightness = null))
    }

    private fun applyStayAwakePolicy(screenOn: Boolean) {
        // The kiosk keeps the tablet awake while it is plugged in, which is exactly wrong once the
        // screen is meant to be off: any stray wake would then last until the next ON transition.
        LockTaskHelper.setStayAwakeWhilePlugged(this, screenOn)
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
        // Long enough for Android to finish its own charger handling, short enough that the room
        // does not stay lit.
        private const val POWER_EVENT_SETTLE_MS = 2_000L
        private const val REMOTE_SERVER_RELOAD_DELAY_MS = 750L

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
