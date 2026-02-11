package com.glance.settings

import android.os.Bundle
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.glance.GlanceApp
import com.glance.R
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
    private lateinit var switchHaEnabled: SwitchCompat
    private lateinit var editHaUrl: EditText
    private lateinit var editHaToken: EditText
    private lateinit var editHaEntityScreen: EditText
    private lateinit var editHaEntityBrightness: EditText
    private lateinit var editPin: EditText
    private lateinit var textDebugInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        switchHaEnabled = findViewById(R.id.switchHaEnabled)
        editHaUrl = findViewById(R.id.editHaUrl)
        editHaToken = findViewById(R.id.editHaToken)
        editHaEntityScreen = findViewById(R.id.editHaEntityScreen)
        editHaEntityBrightness = findViewById(R.id.editHaEntityBrightness)
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
        switchHaEnabled.isChecked = config.haIntegrationEnabled
        editHaUrl.setText(config.haBaseUrl)
        editHaToken.setText(config.haAccessToken)
        editHaEntityScreen.setText(config.haEntityScreen)
        editHaEntityBrightness.setText(config.haEntityBrightness)
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

        config.dashboardUrls = urls
        config.autoRotateEnabled = switchAutoRotate.isChecked
        config.autoRotateIntervalSeconds = editAutoRotateInterval.text.toString().toIntOrNull() ?: 30
        config.autoBrightnessEnabled = switchAutoBrightness.isChecked
        config.minBrightness = (editMinBrightness.text.toString().toIntOrNull() ?: 5).coerceIn(0, 255)
        config.maxBrightness = (editMaxBrightness.text.toString().toIntOrNull() ?: 255).coerceIn(0, 255)
        config.scheduleEnabled = switchSchedule.isChecked
        config.screenOnTime = editScreenOnTime.text.toString().ifBlank { "06:00" }
        config.screenOffTime = editScreenOffTime.text.toString().ifBlank { "23:00" }
        config.haIntegrationEnabled = switchHaEnabled.isChecked
        config.haBaseUrl = editHaUrl.text.toString().trim()
        config.haAccessToken = editHaToken.text.toString().trim()
        config.haEntityScreen = editHaEntityScreen.text.toString().trim().ifBlank { "input_boolean.tablet_screen" }
        config.haEntityBrightness = editHaEntityBrightness.text.toString().trim().ifBlank { "input_number.tablet_brightness" }

        val newPin = editPin.text.toString().trim()
        if (newPin.isNotBlank()) {
            config.settingsPin = newPin
        }

        Toast.makeText(this, "Settings saved. Restart app to apply.", Toast.LENGTH_LONG).show()
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
            appendLine("HA integration: ${config.haIntegrationEnabled}")
            appendLine("HA entities: ${config.haEntityScreen}, ${config.haEntityBrightness}")
        }

        textDebugInfo.text = info
    }
}
