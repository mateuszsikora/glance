package com.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.glance.brightness.BrightnessController
import com.glance.dashboard.DashboardPagerAdapter
import com.glance.dashboard.WebViewFragment
import com.glance.databinding.ActivityMainBinding
import com.glance.kiosk.KioskService
import com.glance.kiosk.LockTaskHelper
import com.glance.screen.ScheduleManager
import com.glance.screen.ScreenController
import com.glance.ha.HAStateManager
import com.glance.settings.SettingsActivity
import com.glance.watchdog.WatchdogService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: DashboardPagerAdapter
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var brightnessController: BrightnessController
    private lateinit var screenController: ScreenController
    private lateinit var scheduleManager: ScheduleManager
    private lateinit var haStateManager: HAStateManager

    private var settingsTapCount = 0
    private var lastSettingsTapTime = 0L

    private val reloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WatchdogService.ACTION_RELOAD_WEBVIEW) {
                Log.i(TAG, "Reload broadcast received — reloading all WebViews")
                reloadAllWebViews()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterImmersiveMode()
        blockBackButton()
        setupKioskMode()
        setupDashboard()
        setupSettingsGesture()
        startServices()
        registerReloadReceiver()
        setupBrightness()
        setupScreenSchedule()
        setupHAIntegration()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    // --- Immersive fullscreen ---

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
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
                val nextItem = (binding.dashboardPager.currentItem + 1) % pagerAdapter.itemCount
                binding.dashboardPager.setCurrentItem(nextItem, true)
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
        brightnessController.start(window)
    }

    // --- Screen schedule ---

    private fun setupScreenSchedule() {
        val config = GlanceApp.instance.appConfig
        screenController = ScreenController(this)
        scheduleManager = ScheduleManager(this, config, screenController)
        scheduleManager.start()
    }

    // --- HA Integration ---

    private fun setupHAIntegration() {
        val config = GlanceApp.instance.appConfig
        haStateManager = HAStateManager(this, config, screenController, brightnessController)
        haStateManager.start()
    }

    // --- Reload WebViews ---

    private fun registerReloadReceiver() {
        val filter = IntentFilter(WatchdogService.ACTION_RELOAD_WEBVIEW)
        registerReceiver(reloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun reloadAllWebViews() {
        for (fragment in supportFragmentManager.fragments) {
            if (fragment is WebViewFragment) {
                fragment.reload()
            }
        }
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
        unregisterReceiver(reloadReceiver)
        if (::haStateManager.isInitialized) haStateManager.stop()
        if (::brightnessController.isInitialized) brightnessController.stop()
        if (::scheduleManager.isInitialized) scheduleManager.stop()
        if (::screenController.isInitialized) screenController.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val TAPS_TO_SETTINGS = 5
        private const val TAP_TIMEOUT_MS = 2000L
    }
}
