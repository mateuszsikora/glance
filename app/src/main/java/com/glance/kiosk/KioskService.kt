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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.glance.GlanceApp
import com.glance.MainActivity
import com.glance.R
import com.glance.battery.BatteryMonitor
import com.glance.battery.BatteryStatus
import com.glance.config.AppConfig
import com.glance.dashboard.LaunchWork
import com.glance.diagnostics.Diagnostics
import com.glance.maintenance.MaintenanceSettings
import com.glance.maintenance.NightlyMaintenancePolicy
import com.glance.mqtt.MqttLightCommand
import com.glance.mqtt.MqttReportedState
import com.glance.mqtt.MqttStateManager
import com.glance.remote.RemoteConfigServer
import com.glance.screen.PowerWakePolicy
import com.glance.screen.ScheduleManager
import com.glance.update.UpdateChecker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

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
    private var remainingPowerWakeChecks = 0
    /** Between a maintenance restart and turning its darkened display back on. */
    private var displayRestorePending = false
    /** Elapsed realtime tonight's maintenance was due at, while it still waits; null otherwise. */
    private var maintenanceRequestedAtMs: Long? = null
    /** What tonight's maintenance does once the tablet is left alone. */
    private var maintenanceAction = NightlyMaintenancePolicy.Action.RESTART

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    // Until the first sleep after a boot Android has announced no screen state,
                    // so it sends SCREEN_ON before that sleep's SCREEN_OFF; the display is
                    // already dark by then. Home Assistant should not see it light up at 23:20.
                    if (!powerManager.isInteractive) return
                    Diagnostics.record("Display turned on")
                    reportScreenState(true)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Diagnostics.record("Display turned off")
                    reportScreenState(false)
                }
            }
        }
    }

    private val powerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> {
                    val connected = intent.action == Intent.ACTION_POWER_CONNECTED
                    Diagnostics.record(
                        "Charger ${if (connected) "connected" else "disconnected"}",
                        includeMemory = true
                    )
                    restoreScreenOffAfterPowerEvent()
                }
            }
        }
    }

    private val activityStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REPORT_SCREEN_STATE -> {
                    val isOn = intent.getBooleanExtra(EXTRA_SCREEN_ON, reportedScreenOn)
                    if (intent.getBooleanExtra(EXTRA_DELIBERATE, true)) {
                        updateScreenState(isOn)
                    } else {
                        reportScreenState(isOn)
                    }
                }
                ACTION_REPORT_BRIGHTNESS -> {
                    updateBrightness(intent.getIntExtra(EXTRA_BRIGHTNESS, reportedBrightness))
                }
                ACTION_REPORT_NIGHTLY_MAINTENANCE_DONE -> finishNightlyMaintenance()
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
        LaunchWork.recordHandled(
            launchTokenHandled,
            intent?.getLongExtra(LaunchWork.EXTRA_LAUNCH_TOKEN, 0L) ?: 0L
        )
        startForeground(NOTIFICATION_ID, buildNotification())
        if (config.isKioskSuspended) {
            Log.i(TAG, "Kiosk is suspended; ignoring service start")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val wasInitialized = initialized
        ensureInitialized(
            dashboardLaunchedBySender = intent?.getBooleanExtra(EXTRA_DASHBOARD_LAUNCHED, false) == true
        )

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
            ACTION_NIGHTLY_MAINTENANCE -> startNightlyMaintenance(
                lateMs = intent.getLongExtra(EXTRA_MAINTENANCE_LATE_MS, 0L).coerceAtLeast(0L),
                tooLate = intent.getBooleanExtra(EXTRA_MAINTENANCE_TOO_LATE, false)
            )
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

    /** @param dashboardLaunchedBySender the starter, such as the boot receiver, opens it itself. */
    private fun ensureInitialized(dashboardLaunchedBySender: Boolean = false) {
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
                addAction(ACTION_REPORT_NIGHTLY_MAINTENANCE_DONE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val relaunch = AppRestarter.consumePendingRelaunch(this)
        // Consumed on every first start, so a marker never outlives the boot after it.
        val rebooted = TabletRebooter.consumePendingReboot(this) && relaunch == null
        val restoreDisplay = relaunch?.wakeDisplay == true && config.requestedScreenOn
        if (restoreDisplay) {
            // The display is only dark for the restart; Home Assistant should not see it go off.
            reportedScreenOn = true
            displayRestorePending = true
        }
        startMqtt()
        startBatteryMonitor()
        // After a maintenance restart or reboot the persisted requested state stands. Recomputing
        // it from the schedule would cancel a manual MQTT override until the next scheduled
        // transition.
        scheduleManager = ScheduleManager(this, config).also {
            it.start(applyCurrentState = relaunch == null && !rebooted)
        }
        reconcileRemoteConfigServer()
        if (rebooted) {
            Log.i(TAG, "First start after a scheduled reboot, restoring the requested screen state")
            Diagnostics.record("First start after a scheduled tablet reboot")
            // The boot lit the display; inside the OFF window it goes dark again, and Home
            // Assistant hears the state either way.
            handleLightCommand(MqttLightCommand(screenOn = config.requestedScreenOn, brightness = null))
        }
        if (relaunch == null && !dashboardLaunchedBySender && MainActivity.liveInstances.get() == 0) {
            // Android restarts this sticky service after the process died, but not the Activity
            // it lost; Glance is usually not the home app, so the launcher would stay in front.
            Log.i(TAG, "Started without a dashboard in this process, launching it")
            Diagnostics.record("Service started without a dashboard; launching it")
            launchDashboard()
        }
        if (relaunch != null) {
            Log.i(TAG, "First start after a maintenance restart, launching dashboard")
            launchDashboard()
            AppRestarter.cancelRelaunchAlarm(this)
            if (restoreDisplay) {
                // Lit only once the dashboard has had a moment to cover the home screen.
                mainHandler.postDelayed(
                    {
                        displayRestorePending = false
                        // An OFF from MQTT or the schedule may have arrived in the meantime.
                        if (config.requestedScreenOn) wakeDisplay()
                    },
                    RELAUNCH_WAKE_DELAY_MS
                )
            }
        }
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

    /**
     * @param lateMs how long after its time the alarm arrived; the postponement counts from then.
     * @param tooLate skip it for the night ([NightlyMaintenancePolicy.skipForTheNight]); journaled
     *   with what was due, so a night that lost a reboot reads differently from one with none due.
     */
    private fun startNightlyMaintenance(lateMs: Long = 0L, tooLate: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val settings = MaintenanceSettings(this)
        val intervalDays = settings.restartIntervalDays
        val deviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
        val action = NightlyMaintenancePolicy.action(
            restartEnabled = settings.nightlyRestartEnabled,
            restartIntervalDays = intervalDays,
            processUptimeMs = now - GlanceApp.instance.processStartElapsedMs,
            rebootEnabled = settings.tabletRebootEnabled,
            rebootIntervalDays = settings.rebootIntervalDays,
            deviceUptimeMs = now,
            deviceOwner = deviceOwner
        )
        if (action == null) {
            val reasons = listOfNotNull(
                when {
                    !settings.nightlyRestartEnabled -> null
                    intervalDays <= 1 -> "the app started less than an hour ago"
                    else -> "the restart is not due yet in its $intervalDays-day interval"
                },
                when {
                    !settings.tabletRebootEnabled -> null
                    !deviceOwner -> "a tablet reboot needs Device Owner"
                    else -> "the tablet reboot is not due yet in its ${settings.rebootIntervalDays}-day interval"
                }
            )
            Diagnostics.record("Nightly maintenance skipped; " + reasons.joinToString("; "))
            return
        }
        if (tooLate) {
            val due = if (action == NightlyMaintenancePolicy.Action.REBOOT) "tablet reboot" else "restart"
            // Without an exact alarm its due time is unknown; only the OFF window told it was late.
            val came = if (lateMs > 0L) "came ${lateMs / 60_000L} min late" else "came after the OFF window"
            Diagnostics.record(
                "Nightly maintenance skipped for tonight; its alarm $came, " +
                    "and the $due that was due waits for the next night"
            )
            return
        }
        Diagnostics.record(
            if (action == NightlyMaintenancePolicy.Action.REBOOT) "Nightly maintenance requested: tablet reboot"
            else "Nightly maintenance requested"
        )
        maintenanceAction = action
        maintenanceRequestedAtMs = now - lateMs
        mainHandler.removeCallbacks(nightlyMaintenanceRunnable)
        attemptNightlyMaintenance()
    }

    private val nightlyMaintenanceRunnable = Runnable { attemptNightlyMaintenance() }

    /**
     * With the display off nobody sees the restart and the Activity may already be gone, so the
     * service restarts the app itself. Otherwise the Activity decides, because only it knows
     * whether someone is using the tablet. The retries live here rather than in the Activity,
     * which a settings save recreates and which may not exist at all. They are timed in uptime,
     * which stands still in deep sleep; with the display off the next one may come late, but then
     * it restarts at once, and how long it was postponed is measured in elapsed realtime.
     */
    private fun attemptNightlyMaintenance() {
        val requestedAt = maintenanceRequestedAtMs ?: return
        val reboot = maintenanceAction == NightlyMaintenancePolicy.Action.REBOOT
        if (devicePolicyManager.isDeviceOwnerApp(packageName) && !powerManager.isInteractive) {
            finishNightlyMaintenance()
            if (reboot) {
                TabletRebooter.reboot(this, "nightly maintenance, display off", displayOn = false)
            } else {
                AppRestarter.restart(this, "nightly maintenance, display off", displayOn = false)
            }
            return
        }
        sendActivityControl(
            Intent(ACTION_CONTROL_NIGHTLY_MAINTENANCE)
                .putExtra(EXTRA_MAINTENANCE_REQUESTED_AT, requestedAt)
                .putExtra(EXTRA_MAINTENANCE_REBOOT, reboot)
        )
        val postponedForMs = SystemClock.elapsedRealtime() - requestedAt
        if (postponedForMs < NightlyMaintenancePolicy.MAX_POSTPONE_MS) {
            mainHandler.postDelayed(nightlyMaintenanceRunnable, NightlyMaintenancePolicy.RETRY_MS)
        } else {
            // The Activity gives up with this last attempt unless the tablet is free by now.
            finishNightlyMaintenance()
        }
    }

    private fun finishNightlyMaintenance() {
        maintenanceRequestedAtMs = null
        mainHandler.removeCallbacks(nightlyMaintenanceRunnable)
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
        // A restart darkens the display only until the next process turns it back on, a reboot
        // until the tablet is back up.
        if (AppRestarter.restartInProgress || TabletRebooter.rebootInProgress || displayRestorePending) return
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
        remainingPowerWakeChecks = POWER_EVENT_CHECKS
        mainHandler.postDelayed(restoreScreenOffRunnable, POWER_EVENT_SETTLE_MS)
    }

    private val restoreScreenOffRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            remainingPowerWakeChecks--
            if (PowerWakePolicy.shouldRestoreScreenOff(config.requestedScreenOn, displayIsOn())) {
                Log.i(TAG, "Display woke on a power event while the schedule asks for OFF")
                handleLightCommand(MqttLightCommand(screenOn = false, brightness = null))
                return
            }
            // Android promises neither the timing nor the order of the wake it performs around a
            // power event, so a single look at the display can be too early.
            if (!config.requestedScreenOn && remainingPowerWakeChecks > 0) {
                mainHandler.postDelayed(this, POWER_EVENT_RECHECK_MS)
            }
        }
    }

    /**
     * Whether the display is lit. As Device Owner the hardware answers directly; the soft-off
     * fallback leaves the display interactive behind a black overlay, so there the Activity's
     * report is the only truthful source.
     */
    private fun displayIsOn(): Boolean {
        return if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            powerManager.isInteractive
        } else {
            reportedScreenOn
        }
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
        // does not stay lit. The wake can also arrive later than the power broadcast, so the
        // display is examined a few times before the event is given up on.
        private const val POWER_EVENT_SETTLE_MS = 2_000L
        private const val POWER_EVENT_RECHECK_MS = 3_000L
        private const val POWER_EVENT_CHECKS = 3
        private const val REMOTE_SERVER_RELOAD_DELAY_MS = 750L
        private const val RELAUNCH_WAKE_DELAY_MS = 2_000L

        const val ACTION_RELOAD_CONFIG = "com.glance.action.RELOAD_CONFIG"
        const val ACTION_COMMAND_SCREEN = "com.glance.action.COMMAND_SCREEN"
        const val ACTION_CONTROL_SCREEN = "com.glance.action.CONTROL_SCREEN"
        const val ACTION_CONTROL_BRIGHTNESS = "com.glance.action.CONTROL_BRIGHTNESS"
        const val ACTION_NIGHTLY_MAINTENANCE = "com.glance.action.RUN_NIGHTLY_MAINTENANCE"
        const val ACTION_CONTROL_NIGHTLY_MAINTENANCE =
            "com.glance.action.CONTROL_NIGHTLY_MAINTENANCE"
        const val ACTION_REPORT_SCREEN_STATE = "com.glance.action.REPORT_SCREEN_STATE"
        const val ACTION_REPORT_BRIGHTNESS = "com.glance.action.REPORT_BRIGHTNESS"
        const val ACTION_REPORT_NIGHTLY_MAINTENANCE_DONE =
            "com.glance.action.REPORT_NIGHTLY_MAINTENANCE_DONE"
        const val EXTRA_MAINTENANCE_REQUESTED_AT = "maintenance_requested_at"
        /** Whether tonight's maintenance reboots the tablet rather than restarting Glance. */
        const val EXTRA_MAINTENANCE_REBOOT = "maintenance_reboot"
        /** How long after its time the nightly maintenance alarm arrived. */
        const val EXTRA_MAINTENANCE_LATE_MS = "maintenance_late_ms"
        /** The alarm came too late to run tonight; see [NightlyMaintenancePolicy.skipForTheNight]. */
        const val EXTRA_MAINTENANCE_TOO_LATE = "maintenance_too_late"
        const val EXTRA_SCREEN_ON = "screen_on"
        /** False when the Activity merely observed the screen state rather than changing it. */
        const val EXTRA_DELIBERATE = "deliberate"

        /** The newest [LaunchWork] token this service has run in this process. */
        internal val launchTokenHandled = AtomicLong()

        /** Set by a starter that opens the dashboard itself, so the service does not open it too. */
        const val EXTRA_DASHBOARD_LAUNCHED = "dashboard_launched"
        const val EXTRA_BRIGHTNESS = "brightness"
    }
}
