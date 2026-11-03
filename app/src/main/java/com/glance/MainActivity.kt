package com.glance

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.glance.brightness.BrightnessController
import com.glance.content.ContentSchedulePolicy
import com.glance.content.IdleTimeoutTracker
import com.glance.dashboard.ColdStartGate
import com.glance.dashboard.DashboardPagerAdapter
import com.glance.dashboard.IdleMaintenanceReload
import com.glance.dashboard.LaunchWork
import com.glance.dashboard.MemoryPressure
import com.glance.dashboard.WebViewFragment
import com.glance.databinding.ActivityMainBinding
import com.glance.diagnostics.Diagnostics
import com.glance.diagnostics.MemorySample
import com.glance.kiosk.AppRestarter
import com.glance.kiosk.KioskService
import com.glance.kiosk.LockTaskHelper
import com.glance.kiosk.ServiceStarter
import com.glance.maintenance.NightlyMaintenanceAttempt
import com.glance.maintenance.NightlyMaintenancePolicy
import com.glance.screen.ScreenController
import com.glance.settings.SettingsActivity
import com.glance.watchdog.WatchdogService
import com.glance.watchdog.WebViewHealthChecker
import com.glance.watchdog.WebViewReachabilityChecker
import java.time.LocalDateTime

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: DashboardPagerAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val powerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private lateinit var brightnessController: BrightnessController
    private lateinit var screenController: ScreenController
    private var controlReceiverRegistered = false
    private val webViewHealthChecker = WebViewHealthChecker()
    private val idleMaintenanceReload = IdleMaintenanceReload()
    private val webViewReachabilityChecker = WebViewReachabilityChecker(
        fragments = { supportFragmentManager.fragments.filterIsInstance<WebViewFragment>() },
        // Settings can cover a still-loaded dashboard; keep observing its host until destroyed.
        canCheck = { powerManager.isInteractive },
        shouldCheck = { isReloadableWebView(it) },
        canReload = { dashboardResumed && screenController.isScreenOn }
    )

    private var settingsTapCount = 0
    private var lastSettingsTapTime = 0L
    private var dashboardResumed = false
    private var activeDashboardUrls: List<String> = emptyList()
    private var idleScreenActive = false
    private var consumeIdleDismissGesture = false
    private var idleTimeoutTracker: IdleTimeoutTracker? = null
    private var lastUserActivityElapsedMs: Long? = null

    /** Set until the first dashboard WebView of this process may be created. */
    private var coldStart: ColdStart? = null
    private var savedStateDiscarded = false

    private val coldStartRunnable = Runnable { checkColdStart() }
    private val coldStartMarkRunnable = Runnable {
        coldStart?.pressure?.mark(SystemClock.elapsedRealtime())
    }

    private val contentScheduleRunnable = object : Runnable {
        override fun run() {
            applyActiveContentProfile()
            scheduleNextContentCheck()
        }
    }

    private val autoRotateRunnable = object : Runnable {
        override fun run() {
            val config = GlanceApp.instance.appConfig
            if (dashboardResumed &&
                powerManager.isInteractive &&
                !idleScreenActive &&
                ::pagerAdapter.isInitialized &&
                pagerAdapter.itemCount > 1
            ) {
                val nextItem =
                    (binding.dashboardPager.currentItem + 1) % pagerAdapter.itemCount
                binding.dashboardPager.setCurrentItem(nextItem, true)
            }
            if (dashboardResumed && config.autoRotateEnabled) {
                handler.postDelayed(this, config.autoRotateIntervalSeconds * 1000L)
            }
        }
    }

    private val idleTimeoutRunnable = object : Runnable {
        override fun run() {
            val tracker = idleTimeoutTracker ?: return
            val now = SystemClock.elapsedRealtime()
            if (tracker.isExpired(now)) {
                showIdleScreen()
            } else {
                handler.postDelayed(this, tracker.remainingMs(now))
            }
        }
    }

    private val delayedIdleReloadRunnable = Runnable {
        if (idleMaintenanceReload.onDelayElapsed()) reloadDeferredIdlePage()
    }

    private val reloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WatchdogService.ACTION_RELOAD_WEBVIEW) {
                Log.i(TAG, "Maintenance reload broadcast received")
                reloadAllWebViews()
            } else if (intent?.action == WatchdogService.ACTION_HEALTH_CHECK) {
                webViewReachabilityChecker.check()
                webViewHealthChecker.check(currentWebViewFragment())
            }
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                KioskService.ACTION_CONTROL_SCREEN -> {
                    if (intent.getBooleanExtra(KioskService.EXTRA_SCREEN_ON, true)) {
                        screenController.screenOn()
                    } else {
                        screenController.screenOff()
                    }
                }
                KioskService.ACTION_CONTROL_BRIGHTNESS -> {
                    brightnessController.setBrightnessFromRemote(
                        intent.getIntExtra(
                            KioskService.EXTRA_BRIGHTNESS,
                            GlanceApp.instance.appConfig.minBrightness
                        )
                    )
                }
                ACTION_RELOAD_UI -> recreate()
                KioskService.ACTION_CONTROL_NIGHTLY_MAINTENANCE -> runNightlyMaintenance(
                    intent.getLongExtra(
                        KioskService.EXTRA_MAINTENANCE_REQUESTED_AT,
                        SystemClock.elapsedRealtime()
                    )
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val coldWebView = !ColdStartGate.webViewStarted
        // Fragments restored from the previous process would create their WebViews in onStart,
        // before the cold start gate could hold them back. A kiosk dashboard restores nothing.
        savedStateDiscarded = coldWebView && savedInstanceState != null
        super.onCreate(if (coldWebView) null else savedInstanceState)
        // Before the lock task request and the service starts, whose handling it waits for.
        if (coldWebView) coldStart = ColdStart(SystemClock.elapsedRealtime())

        // An explicit launch is the opt-in path back into kiosk mode after "Exit Kiosk Mode".
        GlanceApp.instance.appConfig.isKioskSuspended = false

        liveInstances.incrementAndGet()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Diagnostics.record("Dashboard activity created")

        enterImmersiveMode()
        blockBackButton()
        setupKioskMode()
        setupDashboard()
        setupWebViewHealthChecker()
        setupSettingsGesture()
        setupBrightness()
        setupScreenControl()
        setupIdleScreen()
        registerControlReceiver()
        registerReloadReceiver()
        startServices()
        reportCurrentState()
    }

    override fun onResume() {
        super.onResume()
        dashboardResumed = true
        enterImmersiveMode()
        applyActiveContentProfile()
        scheduleNextContentCheck()
        scheduleAutoRotate()
        recordUserActivity()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // Would hand the pager the state of fragments that onCreate did not restore.
        if (!savedStateDiscarded) super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onPause() {
        dashboardResumed = false
        handler.removeCallbacks(contentScheduleRunnable)
        handler.removeCallbacks(autoRotateRunnable)
        handler.removeCallbacks(idleTimeoutRunnable)
        hideIdleScreen(byTouch = false)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        // A stopped Activity lays nothing out, so the gate waits for this.
        if (coldStart != null) scheduleColdStartCheck(0L, measureMemory = true)
        if (::brightnessController.isInitialized) {
            brightnessController.start(window)
        }
    }

    override fun onStop() {
        if (::brightnessController.isInitialized) {
            brightnessController.stop()
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    // --- Immersive fullscreen ---

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // --- Kiosk / LockTask mode ---

    private fun setupKioskMode() {
        if (LockTaskHelper.isDeviceOwner(this)) {
            LockTaskHelper.setLockTaskPackages(this)
            // Recreating the Activity during a scheduled OFF window must not re-enable the
            // stay-awake policy, or the next charger event would light the tablet up for good.
            LockTaskHelper.configureKioskPolicies(
                this,
                stayAwakeWhilePlugged = GlanceApp.instance.appConfig.requestedScreenOn
            )

            val enteringLockTask = getSystemService(ActivityManager::class.java)
                .lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
            try {
                startLockTask()
                // A task still locked from the previous process gets no broadcast.
                coldStart?.work?.lockTaskBroadcastExpected = enteringLockTask
                Log.i(TAG, "LockTask mode started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start LockTask", e)
            }
        } else {
            Log.w(TAG, "Not device owner — kiosk mode limited. " +
                "Run: adb shell dpm set-device-owner com.glance/.AdminReceiver")
        }
    }

    // --- Dashboard setup ---

    private fun setupDashboard() {
        // onStart begins the checks.
        if (coldStart != null) return
        val config = GlanceApp.instance.appConfig
        val urls = ContentSchedulePolicy.activeUrls(
            now = LocalDateTime.now(),
            defaultUrls = config.dashboardUrls,
            scheduleEnabled = config.contentScheduleEnabled,
            profiles = config.contentProfiles
        )
        installDashboardUrls(urls)
    }

    /** Holds back the first WebView of this process; see [ColdStartGate]. */
    private fun checkColdStart() {
        val pending = coldStart ?: return
        val now = SystemClock.elapsedRealtime()
        var reading: MemoryPressure.Reading? = null
        val decision = pending.gate.check(
            nowMs = now,
            started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            startWorkSettled = pending.work.settled
        ) {
            pending.pressure.read(now).also { reading = it }.high
        }
        when (decision) {
            ColdStartGate.Decision.WaitForStart -> Unit
            is ColdStartGate.Decision.WaitForStartWork -> {
                scheduleColdStartCheck(decision.delayMs, measureMemory = false)
            }
            is ColdStartGate.Decision.WaitForMemory -> {
                val message = "First dashboard page held back while memory is short " +
                    "($reading); next check in ${decision.delayMs / 1000} s"
                Log.w(TAG, message)
                Diagnostics.record(message, includeMemory = !pending.heldForMemory)
                pending.heldForMemory = true
                binding.coldStartStatus.visibility = View.VISIBLE
                scheduleColdStartCheck(decision.delayMs, measureMemory = true)
            }
            is ColdStartGate.Decision.Open -> {
                coldStart = null
                pendingColdStartGate = null
                binding.coldStartStatus.visibility = View.GONE
                val message = "Creating the first dashboard page after " +
                    "${(now - pending.gate.createdAtMs) / 1000} s" +
                    (if (decision.gaveUp) ", no longer waiting" else "") + " ($reading)"
                Log.i(TAG, message)
                Diagnostics.record(message, includeMemory = pending.heldForMemory)
                setupDashboard()
                // The idle timeout counts from the dashboard appearing, not from the black screen.
                recordUserActivity()
            }
        }
    }

    /**
     * @param measureMemory the check may read memory pressure, so the swap-in window starts
     * [MemoryPressure.WINDOW_MS] before it, which also delays it by at least that much.
     */
    private fun scheduleColdStartCheck(delayMs: Long, measureMemory: Boolean) {
        handler.removeCallbacks(coldStartMarkRunnable)
        handler.removeCallbacks(coldStartRunnable)
        if (!measureMemory) {
            handler.postDelayed(coldStartRunnable, delayMs)
            return
        }
        val checkDelayMs = maxOf(delayMs, MemoryPressure.WINDOW_MS)
        handler.postDelayed(coldStartMarkRunnable, checkDelayMs - MemoryPressure.WINDOW_MS)
        handler.postDelayed(coldStartRunnable, checkDelayMs)
    }

    private fun installDashboardUrls(urls: List<String>) {
        if (::pagerAdapter.isInitialized && urls == activeDashboardUrls) return
        activeDashboardUrls = urls
        if (::pagerAdapter.isInitialized) {
            pagerAdapter.replaceUrls(urls)
            binding.dashboardPager.setCurrentItem(0, false)
            binding.dashboardPager.isUserInputEnabled = urls.size > 1
        } else {
            pagerAdapter = DashboardPagerAdapter(
                activity = this,
                urls = urls,
                allowedNavigationOrigins = GlanceApp.instance.appConfig.dashboardAllowedOrigins
            )
            binding.dashboardPager.apply {
                adapter = pagerAdapter
                offscreenPageLimit = 1 // Save memory on 3GB device
                isUserInputEnabled = urls.size > 1
            }
        }
        Log.i(TAG, "Active dashboard profile changed (${urls.size} URL(s))")
    }

    private fun applyActiveContentProfile() {
        if (!::binding.isInitialized || coldStart != null) return
        val config = GlanceApp.instance.appConfig
        installDashboardUrls(
            ContentSchedulePolicy.activeUrls(
                now = LocalDateTime.now(),
                defaultUrls = config.dashboardUrls,
                scheduleEnabled = config.contentScheduleEnabled,
                profiles = config.contentProfiles
            )
        )
    }

    private fun scheduleNextContentCheck() {
        handler.removeCallbacks(contentScheduleRunnable)
        if (!dashboardResumed || !GlanceApp.instance.appConfig.contentScheduleEnabled) return
        val nowEpochMs = System.currentTimeMillis()
        val delayMs = MINUTE_MS - (nowEpochMs % MINUTE_MS) + CLOCK_BOUNDARY_SLOP_MS
        handler.postDelayed(contentScheduleRunnable, delayMs)
    }

    private fun scheduleAutoRotate() {
        handler.removeCallbacks(autoRotateRunnable)
        val config = GlanceApp.instance.appConfig
        if (!dashboardResumed || !config.autoRotateEnabled) return
        handler.postDelayed(autoRotateRunnable, config.autoRotateIntervalSeconds * 1000L)
    }

    // --- 5x tap to open settings ---

    private fun setupSettingsGesture() {
        binding.settingsTapOverlay.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastSettingsTapTime > TAP_TIMEOUT_MS) {
                settingsTapCount = 0
            }
            settingsTapCount++
            lastSettingsTapTime = now

            if (settingsTapCount >= TAPS_TO_SETTINGS) {
                settingsTapCount = 0
                openSettings()
            }
        }
    }

    private fun openSettings() {
        Log.i(TAG, "Settings gesture triggered")
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // --- Brightness ---

    private fun setupBrightness() {
        val config = GlanceApp.instance.appConfig
        brightnessController = BrightnessController(this, config)
        brightnessController.setListener(object : BrightnessController.Listener {
            override fun onBrightnessChanged(brightness: Int) {
                reportBrightness(brightness)
            }
        })
    }

    // --- Screen control ---

    private fun setupScreenControl() {
        val config = GlanceApp.instance.appConfig
        screenController = ScreenController(this, brightnessController, binding.rootContainer)
        screenController.setListener(object : ScreenController.Listener {
            override fun onScreenStateChanged(isOn: Boolean) {
                Diagnostics.record("Screen turned ${if (isOn) "on" else "off"} by Glance")
                reportScreenState(isOn)
                if (isOn) {
                    recordUserActivity()
                } else {
                    handler.removeCallbacks(idleTimeoutRunnable)
                    hideIdleScreen(byTouch = false)
                }
            }
        })
        if (!config.requestedScreenOn) {
            // Reapply the logical OFF state after Activity/process recreation. This is essential
            // for the non-Device-Owner black-overlay fallback, whose View cannot be persisted.
            screenController.screenOff()
        }
    }

    // --- Idle screen ---

    private fun setupIdleScreen() {
        val config = GlanceApp.instance.appConfig
        // A manually tagged fragment can be restored across Activity recreation with the old URL.
        // Recreate it lazily so saved settings always take effect.
        idleScreenFragment()?.let { restoredFragment ->
            supportFragmentManager.beginTransaction()
                .remove(restoredFragment)
                .commitNow()
        }
        binding.idleScreenTouchOverlay.setOnClickListener {
            hideIdleScreen(byTouch = true)
        }
        if (!config.idleScreenEnabled || config.idleScreenUrl.isBlank()) return

        idleTimeoutTracker = IdleTimeoutTracker(config.idleTimeoutMinutes * MINUTE_MS)
        recordUserActivity()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && idleScreenActive) {
            consumeIdleDismissGesture = true
            hideIdleScreen(byTouch = true)
        }

        if (consumeIdleDismissGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                consumeIdleDismissGesture = false
            }
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            recordUserActivity()
        }
        return super.dispatchTouchEvent(event)
    }

    /** Touches, dismissing the idle screen, returning to the dashboard, and the display waking. */
    private fun recordUserActivity() {
        lastUserActivityElapsedMs = SystemClock.elapsedRealtime()
        val tracker = idleTimeoutTracker ?: return
        tracker.recordActivity(SystemClock.elapsedRealtime())
        scheduleIdleTimeout()
    }

    private fun scheduleIdleTimeout() {
        handler.removeCallbacks(idleTimeoutRunnable)
        val config = GlanceApp.instance.appConfig
        val tracker = idleTimeoutTracker ?: return
        if (!dashboardResumed ||
            idleScreenActive ||
            !config.idleScreenEnabled ||
            !::screenController.isInitialized ||
            !screenController.isScreenOn ||
            !powerManager.isInteractive
        ) {
            return
        }
        handler.postDelayed(
            idleTimeoutRunnable,
            tracker.remainingMs(SystemClock.elapsedRealtime())
        )
    }

    private fun showIdleScreen() {
        val config = GlanceApp.instance.appConfig
        if (idleScreenActive ||
            coldStart != null ||
            !dashboardResumed ||
            !config.idleScreenEnabled ||
            config.idleScreenUrl.isBlank() ||
            !::screenController.isInitialized ||
            !screenController.isScreenOn ||
            !powerManager.isInteractive ||
            supportFragmentManager.isStateSaved
        ) {
            return
        }

        if (supportFragmentManager.findFragmentByTag(IDLE_SCREEN_FRAGMENT_TAG) == null) {
            supportFragmentManager.beginTransaction()
                .add(
                    R.id.idle_screen_container,
                    WebViewFragment.newInstance(
                        config.idleScreenUrl,
                        config.dashboardAllowedOrigins,
                        diagnosticLabel = "idle",
                        // Straight onto the window: an offscreen layer would add a full-screen
                        // texture and a compositing pass to every frame of an all-day slideshow.
                        layerType = View.LAYER_TYPE_NONE
                    ),
                    IDLE_SCREEN_FRAGMENT_TAG
                )
                .commitNow()
            Diagnostics.record("Idle page created")
        }
        idleScreenActive = true
        handler.removeCallbacks(delayedIdleReloadRunnable)
        idleMaintenanceReload.onIdleShown()
        binding.idleScreenLayer.visibility = View.VISIBLE
        idleScreenFragment()?.setWebContentPaused(false)
        Log.i(TAG, "Idle screen shown")
        Diagnostics.record("Idle screen shown")
    }

    /** @param byTouch false for the screen turning off or the Activity pausing. */
    private fun hideIdleScreen(byTouch: Boolean) {
        if (idleScreenActive) {
            idleScreenFragment()?.setWebContentPaused(true)
            binding.idleScreenLayer.visibility = View.GONE
            idleScreenActive = false
            applyActiveContentProfile()
            Log.i(TAG, "Idle screen hidden")
            Diagnostics.record("Idle screen hidden")
        }
        // Also when already hidden: screen-off or pause runs a touch dismissal's delayed reload.
        when (idleMaintenanceReload.onIdleHidden(byTouch)) {
            IdleMaintenanceReload.Action.NONE -> Unit
            IdleMaintenanceReload.Action.RELOAD_NOW -> {
                handler.removeCallbacks(delayedIdleReloadRunnable)
                reloadDeferredIdlePage()
            }
            IdleMaintenanceReload.Action.RELOAD_AFTER_DELAY -> {
                // A stale runnable left by a settled request must not cut this delay short.
                handler.removeCallbacks(delayedIdleReloadRunnable)
                val delayMs = IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS
                handler.postDelayed(delayedIdleReloadRunnable, delayMs)
                val message =
                    "Idle page reload deferred during playback runs in ${delayMs / 1000} s"
                Log.i(TAG, message)
                Diagnostics.record(message)
            }
        }
        if (byTouch) recordUserActivity()
    }

    private fun reloadDeferredIdlePage() {
        Log.i(TAG, "Running the idle page reload deferred during playback")
        Diagnostics.record("Running the idle page reload deferred during playback")
        idleScreenFragment()?.reload()
    }

    // --- Reload WebViews ---

    private fun registerReloadReceiver() {
        val filter = IntentFilter().apply {
            addAction(WatchdogService.ACTION_RELOAD_WEBVIEW)
            addAction(WatchdogService.ACTION_HEALTH_CHECK)
        }
        ContextCompat.registerReceiver(
            this,
            reloadReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun registerControlReceiver() {
        val filter = IntentFilter().apply {
            addAction(KioskService.ACTION_CONTROL_SCREEN)
            addAction(KioskService.ACTION_CONTROL_BRIGHTNESS)
            addAction(KioskService.ACTION_CONTROL_NIGHTLY_MAINTENANCE)
            addAction(ACTION_RELOAD_UI)
        }
        ContextCompat.registerReceiver(
            this,
            controlReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        controlReceiverRegistered = true
    }

    /**
     * Only an observation: Android may recreate this Activity while the display is dark for
     * reasons of its own, such as the moment a maintenance restart brings the dashboard back,
     * and that must not be mistaken for a request to keep the screen off.
     */
    private fun reportCurrentState() {
        reportScreenState(screenController.isScreenOn, deliberate = false)
        brightnessController.currentBrightness
            .takeIf { it >= 0 }
            ?.let(::reportBrightness)
    }

    private fun reportScreenState(isOn: Boolean, deliberate: Boolean = true) {
        sendBroadcast(
            Intent(KioskService.ACTION_REPORT_SCREEN_STATE)
                .setPackage(packageName)
                .putExtra(KioskService.EXTRA_SCREEN_ON, isOn)
                .putExtra(KioskService.EXTRA_DELIBERATE, deliberate)
        )
    }

    private fun reportBrightness(brightness: Int) {
        sendBroadcast(
            Intent(KioskService.ACTION_REPORT_BRIGHTNESS)
                .setPackage(packageName)
                .putExtra(KioskService.EXTRA_BRIGHTNESS, brightness)
        )
    }

    /** @param includeIdleOnScreen also restarts idle playback; only for nightly maintenance. */
    private fun reloadAllWebViews(includeIdleOnScreen: Boolean = false) {
        val idleFragment = idleScreenFragment()
        val dashboards = supportFragmentManager.fragments.filterIsInstance<WebViewFragment>()
            .filter { it !== idleFragment }
        Diagnostics.record("Reload broadcast: reloading ${dashboards.size} dashboard page(s)")
        dashboards.forEach { it.reload() }
        if (idleFragment == null) return
        val idleOnScreen = idleScreenActive && !includeIdleOnScreen
        if (idleMaintenanceReload.onMaintenanceReload(idleOnScreen = idleOnScreen)) {
            Diagnostics.record(
                if (idleScreenActive) {
                    "Reloading the idle page on screen"
                } else {
                    "Reloading the hidden idle page"
                }
            )
            idleFragment.reload()
        } else {
            Log.i(TAG, "Idle page reload deferred until the idle screen is dismissed")
            Diagnostics.record("Idle page reload deferred until the idle screen is dismissed")
        }
    }

    // --- Nightly maintenance ---

    /** One attempt; [KioskService] repeats it while the tablet stays in use. */
    private fun runNightlyMaintenance(requestedAtMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val attempt = NightlyMaintenanceAttempt(
            deviceOwner = LockTaskHelper.isDeviceOwner(this),
            restart = { displayOn ->
                AppRestarter.restart(this, "nightly maintenance", displayOn = displayOn)
            },
            reloadEveryPage = {
                Diagnostics.record("Nightly maintenance: reloading every page without Device Owner")
                reloadAllWebViews(includeIdleOnScreen = true)
            },
            reportDone = {
                sendBroadcast(
                    Intent(KioskService.ACTION_REPORT_NIGHTLY_MAINTENANCE_DONE)
                        .setPackage(packageName)
                )
            }
        )
        val decision = attempt.run(
            screenVisible = screenController.isScreenOn,
            dashboardResumed = dashboardResumed,
            idleScreenActive = idleScreenActive,
            sinceLastActivityMs = lastUserActivityElapsedMs?.let { now - it } ?: Long.MAX_VALUE,
            postponedForMs = now - requestedAtMs
        )
        when (decision) {
            NightlyMaintenancePolicy.Decision.RUN -> Unit
            NightlyMaintenancePolicy.Decision.POSTPONE -> {
                Diagnostics.record("Nightly maintenance postponed; the tablet is in use")
            }
            NightlyMaintenancePolicy.Decision.GIVE_UP -> {
                Log.i(TAG, "Nightly maintenance skipped until the next night")
                Diagnostics.record("Nightly maintenance skipped; the tablet stayed in use")
            }
        }
    }

    private fun isReloadableWebView(fragment: WebViewFragment): Boolean =
        fragment !== idleScreenFragment() || idleScreenActive

    private fun setupWebViewHealthChecker() {
        webViewHealthChecker.onReloadNeeded = {
            Diagnostics.record("Health check failed; reloading the visible page")
            currentWebViewFragment()?.reload()
        }
        webViewHealthChecker.onRestartNeeded = {
            Log.e(TAG, "WebView health checks failed repeatedly — recreating dashboard")
            Diagnostics.record(
                "Health checks failed repeatedly; recreating the activity",
                includeMemory = true
            )
            recreate()
        }
    }

    private fun currentWebViewFragment(): WebViewFragment? {
        if (idleScreenActive) {
            return idleScreenFragment()
        }
        val idleFragment = idleScreenFragment()
        return supportFragmentManager.fragments
            .filterIsInstance<WebViewFragment>()
            .filterNot { it === idleFragment }
            .firstOrNull { it.isVisible }
            ?: supportFragmentManager.fragments
                .filterIsInstance<WebViewFragment>()
                .firstOrNull { it !== idleFragment }
    }

    private fun idleScreenFragment(): WebViewFragment? {
        return supportFragmentManager.findFragmentByTag(IDLE_SCREEN_FRAGMENT_TAG)
            as? WebViewFragment
    }

    // --- Services ---

    private fun startServices() {
        val token = coldStart?.work?.token ?: 0
        listOf(KioskService::class.java, WatchdogService::class.java).forEach { service ->
            ServiceStarter.startFromForeground(
                this,
                Intent(this, service).putExtra(LaunchWork.EXTRA_LAUNCH_TOKEN, token)
            )
        }
    }

    // --- Block back button ---

    private fun blockBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Swallowed: leaving the dashboard is not allowed in kiosk mode.
            }
        })
    }

    override fun onDestroy() {
        liveInstances.decrementAndGet()
        // Only a recreation takes over the wait; a later launch starts its own time limit.
        if (isFinishing) pendingColdStartGate = null
        handler.removeCallbacksAndMessages(null)
        webViewHealthChecker.reset()
        webViewReachabilityChecker.destroy()
        unregisterReceiver(reloadReceiver)
        if (controlReceiverRegistered) {
            unregisterReceiver(controlReceiver)
            controlReceiverRegistered = false
        }
        if (::brightnessController.isInitialized) brightnessController.setListener(null)
        if (::screenController.isInitialized) screenController.setListener(null)
        if (::brightnessController.isInitialized) brightnessController.stop()
        if (::screenController.isInitialized) screenController.release()
        super.onDestroy()
    }

    private inner class ColdStart(nowMs: Long) {
        // A recreation during the wait keeps the process's gate and its time limit.
        val gate = pendingColdStartGate?.also { it.onLaunch(nowMs) }
            ?: ColdStartGate(nowMs).also { pendingColdStartGate = it }
        val work = LaunchWork(
            token = LaunchWork.nextToken(),
            kioskTokenHandled = KioskService.launchTokenHandled::get,
            watchdogTokenHandled = WatchdogService.launchTokenHandled::get,
            lockTaskEntries = AdminReceiver.lockTaskEntriesHandled::get
        )
        val pressure = MemoryPressure(
            lowMemory = {
                ActivityManager.MemoryInfo()
                    .also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                    .lowMemory
            },
            swapIns = ::readSwapIns,
            nowMs = nowMs
        )
        var heldForMemory = false
    }

    private fun readSwapIns(): Long? = try {
        MemorySample.readSwapIns()
    } catch (e: Exception) {
        if (!swapInsUnreadableRecorded) {
            swapInsUnreadableRecorded = true
            // Without it the gate relies on the low-memory flag alone.
            val message = "Swap-in rate unavailable (${e.javaClass.simpleName} reading /proc/vmstat)"
            Log.w(TAG, message, e)
            Diagnostics.record(message)
        }
        null
    }

    companion object {
        /** Dashboards alive in this process; a service restarted in a new process sees none. */
        internal val liveInstances = java.util.concurrent.atomic.AtomicInteger()

        /** Outlives a recreation during the wait; see [ColdStartGate]. */
        private var pendingColdStartGate: ColdStartGate? = null
        private var swapInsUnreadableRecorded = false
        private const val TAG = "MainActivity"
        private const val TAPS_TO_SETTINGS = 5
        private const val TAP_TIMEOUT_MS = 2000L
        private const val MINUTE_MS = 60_000L
        private const val CLOCK_BOUNDARY_SLOP_MS = 100L
        private const val IDLE_SCREEN_FRAGMENT_TAG = "idle_screen"
        const val ACTION_RELOAD_UI = "com.glance.action.RELOAD_UI"
    }
}
