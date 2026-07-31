package com.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.glance.brightness.BrightnessController
import com.glance.dashboard.DashboardPagerAdapter
import com.glance.dashboard.WebViewFragment
import com.glance.databinding.ActivityMainBinding
import com.glance.kiosk.KioskService
import com.glance.kiosk.LockTaskHelper
import com.glance.screen.ScreenController
import com.glance.settings.SettingsActivity
import com.glance.watchdog.WatchdogService
import com.glance.watchdog.WebViewHealthChecker

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

    private var settingsTapCount = 0
    private var lastSettingsTapTime = 0L
    private var dashboardResumed = false

    private val reloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WatchdogService.ACTION_RELOAD_WEBVIEW) {
                Log.i(TAG, "Reload broadcast received — reloading all WebViews")
                reloadAllWebViews()
            } else if (intent?.action == WatchdogService.ACTION_HEALTH_CHECK) {
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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // An explicit launch is the opt-in path back into kiosk mode after "Exit Kiosk Mode".
        GlanceApp.instance.appConfig.isKioskSuspended = false

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterImmersiveMode()
        blockBackButton()
        setupKioskMode()
        setupDashboard()
        setupWebViewHealthChecker()
        setupSettingsGesture()
        setupBrightness()
        setupScreenControl()
        registerControlReceiver()
        registerReloadReceiver()
        startServices()
        reportCurrentState()
    }

    override fun onResume() {
        super.onResume()
        dashboardResumed = true
        enterImmersiveMode()
    }

    override fun onPause() {
        dashboardResumed = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
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
            LockTaskHelper.configureKioskPolicies(this)

            try {
                startLockTask()
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
        val config = GlanceApp.instance.appConfig
        val urls = config.dashboardUrls

        pagerAdapter = DashboardPagerAdapter(this, urls)
        binding.dashboardPager.apply {
            adapter = pagerAdapter
            offscreenPageLimit = 1 // Save memory on 3GB device
            isUserInputEnabled = urls.size > 1
        }

        // Auto-rotate if enabled
        if (config.autoRotateEnabled && urls.size > 1) {
            startAutoRotate(config.autoRotateIntervalSeconds)
        }
    }

    private fun startAutoRotate(intervalSeconds: Int) {
        val intervalMs = intervalSeconds * 1000L
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (dashboardResumed && powerManager.isInteractive) {
                    val nextItem =
                        (binding.dashboardPager.currentItem + 1) % pagerAdapter.itemCount
                    binding.dashboardPager.setCurrentItem(nextItem, true)
                }
                handler.postDelayed(this, intervalMs)
            }
        }, intervalMs)
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
        screenController = ScreenController(this, brightnessController, binding.rootContainer)
        screenController.setListener(object : ScreenController.Listener {
            override fun onScreenStateChanged(isOn: Boolean) {
                reportScreenState(isOn)
            }
        })
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

    private fun reportCurrentState() {
        reportScreenState(screenController.isScreenOn)
        brightnessController.currentBrightness
            .takeIf { it >= 0 }
            ?.let(::reportBrightness)
    }

    private fun reportScreenState(isOn: Boolean) {
        sendBroadcast(
            Intent(KioskService.ACTION_REPORT_SCREEN_STATE)
                .setPackage(packageName)
                .putExtra(KioskService.EXTRA_SCREEN_ON, isOn)
        )
    }

    private fun reportBrightness(brightness: Int) {
        sendBroadcast(
            Intent(KioskService.ACTION_REPORT_BRIGHTNESS)
                .setPackage(packageName)
                .putExtra(KioskService.EXTRA_BRIGHTNESS, brightness)
        )
    }

    private fun reloadAllWebViews() {
        for (fragment in supportFragmentManager.fragments) {
            if (fragment is WebViewFragment) {
                fragment.reload()
            }
        }
    }

    private fun setupWebViewHealthChecker() {
        webViewHealthChecker.onReloadNeeded = {
            currentWebViewFragment()?.reload()
        }
        webViewHealthChecker.onRestartNeeded = {
            Log.e(TAG, "WebView health checks failed repeatedly — recreating dashboard")
            recreate()
        }
    }

    private fun currentWebViewFragment(): WebViewFragment? {
        return supportFragmentManager.fragments
            .filterIsInstance<WebViewFragment>()
            .firstOrNull { it.isVisible }
            ?: supportFragmentManager.fragments.filterIsInstance<WebViewFragment>().firstOrNull()
    }

    // --- Services ---

    private fun startServices() {
        startForegroundService(Intent(this, KioskService::class.java))
        startForegroundService(Intent(this, WatchdogService::class.java))
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
        handler.removeCallbacksAndMessages(null)
        webViewHealthChecker.reset()
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

    companion object {
        private const val TAG = "MainActivity"
        private const val TAPS_TO_SETTINGS = 5
        private const val TAP_TIMEOUT_MS = 2000L
        const val ACTION_RELOAD_UI = "com.glance.action.RELOAD_UI"
    }
}
