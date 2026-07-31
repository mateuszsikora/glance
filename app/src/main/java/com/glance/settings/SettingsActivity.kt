package com.glance.settings

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.webkit.WebView
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.glance.GlanceApp
import com.glance.MainActivity
import com.glance.R
import com.glance.config.AppConfig
import com.glance.kiosk.KioskService
import com.glance.kiosk.LockTaskHelper
import com.google.android.material.button.MaterialButton

/**
 * Hidden settings screen accessible via 5x tap gesture.
 * Protected by a PIN dialog before showing content.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var config: AppConfig

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
    private var awaitingExactAlarmAccess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        config = GlanceApp.instance.appConfig

        if (config.hasSettingsPin) {
            showPinDialog()
        } else {
            showCreatePinDialog("Create a settings PIN")
        }
    }

    private fun showPinDialog() {
        val lockRemainingMs = config.pinLockRemainingMs()
        if (lockRemainingMs > 0L) {
            val seconds = (lockRemainingMs + 999L) / 1000L
            Toast.makeText(this, "Too many attempts. Try again in ${seconds}s", Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter PIN"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Settings PIN")
            .setView(pinInput)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                if (config.verifySettingsPin(pinInput.text.toString())) {
                    config.clearPinFailures()
                    if (config.usesLegacyDefaultPin) {
                        showCreatePinDialog("Replace the default PIN")
                    } else {
                        initSettingsUI()
                    }
                } else {
                    val lockedForMs = config.recordFailedPinAttempt()
                    val message = if (lockedForMs > 0L) {
                        "Too many attempts. Settings locked for 30 seconds"
                    } else {
                        "Wrong PIN"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun showCreatePinDialog(title: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val newPinInput = pinInput("New PIN")
        val confirmPinInput = pinInput("Confirm new PIN")
        container.addView(newPinInput)
        container.addView(confirmPinInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Use 4-12 digits. For security, 1234 is not allowed.")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = newPinInput.text.toString()
                when {
                    !ConfigValidator.isValidSettingsPin(pin) -> {
                        newPinInput.error = "Use 4-12 digits and choose a value other than 1234"
                    }
                    pin != confirmPinInput.text.toString() -> {
                        confirmPinInput.error = "PINs do not match"
                    }
                    else -> {
                        config.setSettingsPin(pin)
                        config.clearPinFailures()
                        dialog.dismiss()
                        initSettingsUI()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun pinInput(fieldHint: String): EditText {
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = fieldHint
            setPadding(0, 24, 0, 8)
        }
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
        editPin.setText("")
    }

    private fun saveConfig() {
        saveConfig(allowInexactSchedule = false)
    }

    private fun saveConfig(allowInexactSchedule: Boolean) {
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
        if (switchSchedule.isChecked && screenOnTime == screenOffTime) {
            Toast.makeText(
                this,
                "Wake and turn-off times must be different",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (switchSchedule.isChecked && !allowInexactSchedule && !canScheduleExactAlarms()) {
            showExactAlarmAccessDialog()
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
        if (newPin.isNotEmpty() && !ConfigValidator.isValidSettingsPin(newPin)) {
            Toast.makeText(
                this,
                "PIN must contain 4-12 digits and cannot be 1234",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Encrypt the only fallible value before updating the remaining SharedPreferences fields.
        try {
            config.mqttPassword = editMqttPassword.text.toString()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to encrypt MQTT password", Toast.LENGTH_LONG).show()
            return
        }

        // All validation and credential encryption have succeeded. Persist the validated values.
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
        config.mqttDeviceName = editMqttDeviceName.text.toString().trim().ifBlank { "Glance Tablet" }
        config.mqttDiscoveryPrefix = discoveryPrefix

        if (newPin.isNotBlank()) {
            config.setSettingsPin(newPin)
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, KioskService::class.java).setAction(KioskService.ACTION_RELOAD_CONFIG)
        )
        sendBroadcast(Intent(MainActivity.ACTION_RELOAD_UI).setPackage(packageName))
        Toast.makeText(this, "Settings saved and applied", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun showExactAlarmAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("Allow precise screen scheduling")
            .setMessage(
                "Android has not granted exact alarm access. Without it, wake and turn-off " +
                    "events can be delayed. You can grant access now or keep an approximate schedule."
            )
            .setPositiveButton("Grant access") { _, _ -> requestExactAlarmAccess() }
            .setNeutralButton("Use approximate") { _, _ ->
                saveConfig(allowInexactSchedule = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        awaitingExactAlarmAccess = true
        try {
            if (LockTaskHelper.isDeviceOwner(this)) {
                stopLockTask()
            }
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            awaitingExactAlarmAccess = false
            runCatching {
                if (LockTaskHelper.isDeviceOwner(this)) startLockTask()
            }
            Toast.makeText(
                this,
                "Unable to open exact alarm settings: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingExactAlarmAccess) return
        awaitingExactAlarmAccess = false
        runCatching {
            if (LockTaskHelper.isDeviceOwner(this)) startLockTask()
        }
        val message = if (canScheduleExactAlarms()) {
            "Exact alarm access granted. Tap Save to apply the schedule."
        } else {
            "Exact alarm access was not granted. The approximate schedule remains available."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
                        LockTaskHelper.clearKioskPolicies(this)
                    }
                    Toast.makeText(this, "Kiosk mode exited", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
                    finishAndRemoveTask()
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
            appendLine("Exact alarms: ${canScheduleExactAlarms()}")
            appendLine("Auto brightness: ${config.autoBrightnessEnabled}")
            appendLine("MQTT: ${if (config.mqttEnabled) "${config.mqttBrokerHost}:${config.mqttBrokerPort}" else "disabled"}")
            appendLine("MQTT discovery: ${config.mqttDiscoveryPrefix}")
        }

        textDebugInfo.text = info
    }

}
