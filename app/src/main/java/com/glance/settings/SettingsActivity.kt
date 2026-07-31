package com.glance.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.glance.GlanceApp
import com.glance.MainActivity
import com.glance.R
import com.glance.kiosk.KioskService
import com.glance.kiosk.LockTaskHelper
import com.google.android.material.button.MaterialButton

/**
 * Hidden settings screen accessible via 5x tap gesture.
 * Protected by a PIN dialog before showing content.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var config: com.glance.config.AppConfig

    // Views
    private lateinit var editDashboardUrls: EditText
    private lateinit var switchAutoRotate: SwitchCompat
    private lateinit var editAutoRotateInterval: EditText
    private lateinit var switchAutoBrightness: SwitchCompat
    private lateinit var editMinBrightness: EditText
    private lateinit var editMaxBrightness: EditText
    private lateinit var switchSchedule: SwitchCompat
    private lateinit var editScreenOnTime: EditText
    private lateinit var editScreenOffTime: EditText
    private lateinit var switchMqttEnabled: SwitchCompat
    private lateinit var editMqttBrokerHost: EditText
    private lateinit var editMqttBrokerPort: EditText
    private lateinit var editMqttUsername: EditText
    private lateinit var editMqttPassword: EditText
    private lateinit var editMqttDeviceName: EditText
    private lateinit var editMqttDiscoveryPrefix: EditText
    private lateinit var editPin: EditText
    private lateinit var textDebugInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        config = GlanceApp.instance.appConfig

        showPinDialog()
    }

    private fun showPinDialog() {
        val pinInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter PIN"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Settings PIN")
            .setView(pinInput)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                if (pinInput.text.toString() == config.settingsPin) {
                    initSettingsUI()
                } else {
                    Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun initSettingsUI() {
        setContentView(R.layout.activity_settings)
        bindViews()
        loadConfig()
        setupButtons()
        showDebugInfo()
    }

    private fun bindViews() {
        editDashboardUrls = findViewById(R.id.editDashboardUrls)
        switchAutoRotate = findViewById(R.id.switchAutoRotate)
        editAutoRotateInterval = findViewById(R.id.editAutoRotateInterval)
        switchAutoBrightness = findViewById(R.id.switchAutoBrightness)
        editMinBrightness = findViewById(R.id.editMinBrightness)
        editMaxBrightness = findViewById(R.id.editMaxBrightness)
        switchSchedule = findViewById(R.id.switchSchedule)
        editScreenOnTime = findViewById(R.id.editScreenOnTime)
        editScreenOffTime = findViewById(R.id.editScreenOffTime)
        switchMqttEnabled = findViewById(R.id.switchMqttEnabled)
        editMqttBrokerHost = findViewById(R.id.editMqttBrokerHost)
        editMqttBrokerPort = findViewById(R.id.editMqttBrokerPort)
        editMqttUsername = findViewById(R.id.editMqttUsername)
        editMqttPassword = findViewById(R.id.editMqttPassword)
        editMqttDeviceName = findViewById(R.id.editMqttDeviceName)
        editMqttDiscoveryPrefix = findViewById(R.id.editMqttDiscoveryPrefix)
        editPin = findViewById(R.id.editPin)
        textDebugInfo = findViewById(R.id.textDebugInfo)
    }

    private fun loadConfig() {
        editDashboardUrls.setText(config.dashboardUrls.joinToString("\n"))
        switchAutoRotate.isChecked = config.autoRotateEnabled
        editAutoRotateInterval.setText(config.autoRotateIntervalSeconds.toString())
        switchAutoBrightness.isChecked = config.autoBrightnessEnabled
        editMinBrightness.setText(config.minBrightness.toString())
        editMaxBrightness.setText(config.maxBrightness.toString())
        switchSchedule.isChecked = config.scheduleEnabled
        editScreenOnTime.setText(config.screenOnTime)
        editScreenOffTime.setText(config.screenOffTime)
        switchMqttEnabled.isChecked = config.mqttEnabled
        editMqttBrokerHost.setText(
            config.mqttBrokerHost.ifBlank {
                config.dashboardUrls.firstOrNull()
                    ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                    .orEmpty()
            }
        )
        editMqttBrokerPort.setText(config.mqttBrokerPort.toString())
        editMqttUsername.setText(config.mqttUsername)
        editMqttPassword.setText(config.mqttPassword)
        editMqttDeviceName.setText(config.mqttDeviceName)
        editMqttDiscoveryPrefix.setText(config.mqttDiscoveryPrefix)
        editPin.setText(config.settingsPin)
    }

    private fun saveConfig() {
        val urls = editDashboardUrls.text.toString()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (urls.isEmpty()) {
            Toast.makeText(this, "At least one dashboard URL required", Toast.LENGTH_SHORT).show()
            return
        }
        if (urls.any { !ConfigValidator.isValidDashboardUrl(it) }) {
            Toast.makeText(this, "Dashboard URLs must use http:// or https://", Toast.LENGTH_LONG).show()
            return
        }

        val rotateInterval = editAutoRotateInterval.text.toString().toIntOrNull()
        if (!ConfigValidator.isValidRotateInterval(rotateInterval)) {
            Toast.makeText(
                this,
                "Rotate interval must be ${ConfigValidator.MIN_ROTATE_SECONDS}-" +
                    "${ConfigValidator.MAX_ROTATE_SECONDS} seconds",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val minBrightness = editMinBrightness.text.toString().toIntOrNull()
        val maxBrightness = editMaxBrightness.text.toString().toIntOrNull()
        if (!ConfigValidator.isValidBrightnessRange(minBrightness, maxBrightness)) {
            Toast.makeText(
                this,
                "Brightness must satisfy 0 ≤ min ≤ max ≤ 255",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val screenOnTime = editScreenOnTime.text.toString().ifBlank { "06:00" }
        val screenOffTime = editScreenOffTime.text.toString().ifBlank { "23:00" }
        if (!ConfigValidator.isValidTime(screenOnTime) ||
            !ConfigValidator.isValidTime(screenOffTime)
        ) {
            Toast.makeText(this, "Schedule times must use HH:mm (00:00-23:59)", Toast.LENGTH_LONG)
                .show()
            return
        }

        val mqttHost = editMqttBrokerHost.text.toString().trim()
        val mqttPort = editMqttBrokerPort.text.toString().toIntOrNull()
        if (switchMqttEnabled.isChecked && mqttHost.isBlank()) {
            Toast.makeText(this, "MQTT broker host is required", Toast.LENGTH_SHORT).show()
            return
        }
        if (switchMqttEnabled.isChecked && !ConfigValidator.isValidMqttHost(mqttHost)) {
            Toast.makeText(
                this,
                "Use a host/IP, tcp://host[:port], or ssl://host[:port]",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (mqttPort == null || mqttPort !in 1..65535) {
            Toast.makeText(this, "MQTT port must be between 1 and 65535", Toast.LENGTH_SHORT).show()
            return
        }

        val discoveryPrefix = editMqttDiscoveryPrefix.text.toString()
            .trim()
            .trim('/')
            .ifBlank { "homeassistant" }
        if (discoveryPrefix.any { it.isWhitespace() } ||
            discoveryPrefix.contains('#') ||
            discoveryPrefix.contains('+')
        ) {
            Toast.makeText(this, "MQTT discovery prefix contains invalid characters", Toast.LENGTH_LONG)
                .show()
            return
        }

        val newPin = editPin.text.toString().trim()
        if (newPin.isNotEmpty() && (newPin.length !in 4..12 || newPin.any { !it.isDigit() })) {
            Toast.makeText(this, "PIN must contain 4-12 digits", Toast.LENGTH_LONG).show()
            return
        }

        // All validation has succeeded. Persist only now to avoid partial configuration updates.
        config.dashboardUrls = urls
        config.autoRotateEnabled = switchAutoRotate.isChecked
        config.autoRotateIntervalSeconds = requireNotNull(rotateInterval)
        config.autoBrightnessEnabled = switchAutoBrightness.isChecked
        config.minBrightness = requireNotNull(minBrightness)
        config.maxBrightness = requireNotNull(maxBrightness)
        config.scheduleEnabled = switchSchedule.isChecked
        config.screenOnTime = screenOnTime
        config.screenOffTime = screenOffTime
        config.mqttEnabled = switchMqttEnabled.isChecked
        config.mqttBrokerHost = mqttHost
        config.mqttBrokerPort = mqttPort
        config.mqttUsername = editMqttUsername.text.toString().trim()
        config.mqttPassword = editMqttPassword.text.toString()
        config.mqttDeviceName = editMqttDeviceName.text.toString().trim().ifBlank { "Glance Tablet" }
        config.mqttDiscoveryPrefix = discoveryPrefix

        if (newPin.isNotBlank()) {
            config.settingsPin = newPin
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, KioskService::class.java).setAction(KioskService.ACTION_RELOAD_CONFIG)
        )
        sendBroadcast(Intent(MainActivity.ACTION_RELOAD_UI).setPackage(packageName))
        Toast.makeText(this, "Settings saved and applied", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun setupButtons() {
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveConfig() }
        findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnExitKiosk).setOnClickListener { exitKioskMode() }
    }

    private fun exitKioskMode() {
        AlertDialog.Builder(this)
            .setTitle("Exit Kiosk Mode")
            .setMessage("This will stop LockTask mode. Are you sure?")
            .setPositiveButton("Exit") { _, _ ->
                try {
                    if (LockTaskHelper.isDeviceOwner(this)) {
                        stopLockTask()
                    }
                    Toast.makeText(this, "Kiosk mode exited", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDebugInfo() {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val totalMem = runtime.maxMemory() / 1024 / 1024

        val webViewPackage = WebView.getCurrentWebViewPackage()
        val webViewVersion = webViewPackage?.versionName ?: "unknown"

        val uptimeMs = android.os.SystemClock.elapsedRealtime()
        val uptimeHours = uptimeMs / 1000 / 3600
        val uptimeMinutes = (uptimeMs / 1000 / 60) % 60

        val isDeviceOwner = LockTaskHelper.isDeviceOwner(this)

        val info = buildString {
            appendLine("Package: ${packageName}")
            appendLine("Device Owner: $isDeviceOwner")
            appendLine("WebView: $webViewVersion")
            appendLine("Memory: ${usedMem}MB / ${totalMem}MB")
            appendLine("Uptime: ${uptimeHours}h ${uptimeMinutes}m")
            appendLine("Dashboard URLs: ${config.dashboardUrls.size}")
            appendLine("Schedule: ${if (config.scheduleEnabled) "${config.screenOnTime}-${config.screenOffTime}" else "disabled"}")
            appendLine("Auto brightness: ${config.autoBrightnessEnabled}")
            appendLine("MQTT: ${if (config.mqttEnabled) "${config.mqttBrokerHost}:${config.mqttBrokerPort}" else "disabled"}")
            appendLine("MQTT discovery: ${config.mqttDiscoveryPrefix}")
        }

        textDebugInfo.text = info
    }

}
