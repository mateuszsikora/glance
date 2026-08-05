package com.glance.settings

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.text.format.DateFormat
import android.webkit.WebView
import android.view.View
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
import com.glance.content.ContentProfile
import com.glance.content.WeekDays
import com.glance.kiosk.KioskService
import com.glance.kiosk.LockTaskHelper
import com.glance.remote.RemoteConfigAddress
import com.glance.screen.ScheduleManager
import com.glance.update.UpdateChecker
import com.glance.update.UpdateSummary
import com.glance.watchdog.WatchdogService
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale

/**
 * Hidden settings screen accessible via 5x tap gesture.
 * Protected by a PIN dialog before showing content.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var config: AppConfig

    // Views
    private lateinit var editDashboardUrls: EditText
    private lateinit var editDashboardAllowedOrigins: EditText
    private lateinit var switchAutoRotate: SwitchCompat
    private lateinit var editAutoRotateInterval: EditText
    private lateinit var switchContentSchedule: SwitchCompat
    private lateinit var containerContentProfiles: LinearLayout
    private lateinit var textContentProfilesEmpty: TextView
    private lateinit var switchIdleScreen: SwitchCompat
    private lateinit var editIdleScreenUrl: EditText
    private lateinit var editIdleTimeoutMinutes: EditText
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
    private lateinit var switchRemoteConfig: SwitchCompat
    private lateinit var textRemoteConfigAddress: TextView
    private lateinit var editUpdateUrl: EditText
    private lateinit var switchAutoUpdate: SwitchCompat
    private lateinit var textUpdateStatus: TextView
    private lateinit var buttonInstallUpdate: MaterialButton
    private lateinit var editPin: EditText
    private lateinit var textDebugInfo: TextView
    private var awaitingExactAlarmAccess = false
    private var mqttPasswordUnreadable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        config = GlanceApp.instance.appConfig
        awaitingExactAlarmAccess = savedInstanceState?.getBoolean(
            STATE_AWAITING_EXACT_ALARM_ACCESS,
            false
        ) ?: false

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
                    if (config.needsLegacyPinUpgrade) {
                        showCreatePinDialog("Replace the legacy PIN")
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
        editDashboardAllowedOrigins = findViewById(R.id.editDashboardAllowedOrigins)
        switchAutoRotate = findViewById(R.id.switchAutoRotate)
        editAutoRotateInterval = findViewById(R.id.editAutoRotateInterval)
        switchContentSchedule = findViewById(R.id.switchContentSchedule)
        containerContentProfiles = findViewById(R.id.containerContentProfiles)
        textContentProfilesEmpty = findViewById(R.id.textContentProfilesEmpty)
        switchIdleScreen = findViewById(R.id.switchIdleScreen)
        editIdleScreenUrl = findViewById(R.id.editIdleScreenUrl)
        editIdleTimeoutMinutes = findViewById(R.id.editIdleTimeoutMinutes)
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
        switchRemoteConfig = findViewById(R.id.switchRemoteConfig)
        textRemoteConfigAddress = findViewById(R.id.textRemoteConfigAddress)
        editUpdateUrl = findViewById(R.id.editUpdateUrl)
        switchAutoUpdate = findViewById(R.id.switchAutoUpdate)
        textUpdateStatus = findViewById(R.id.textUpdateStatus)
        buttonInstallUpdate = findViewById(R.id.btnInstallUpdate)
        findViewById<MaterialButton>(R.id.btnCheckForUpdate).setOnClickListener {
            requestUpdate(installNow = false)
        }
        buttonInstallUpdate.setOnClickListener { requestUpdate(installNow = true) }
        editPin = findViewById(R.id.editPin)
        textDebugInfo = findViewById(R.id.textDebugInfo)
    }

    private fun loadConfig() {
        editDashboardUrls.setText(config.dashboardUrls.joinToString("\n"))
        editDashboardAllowedOrigins.setText(config.dashboardAllowedOrigins.joinToString("\n"))
        switchAutoRotate.isChecked = config.autoRotateEnabled
        editAutoRotateInterval.setText(config.autoRotateIntervalSeconds.toString())
        switchContentSchedule.isChecked = config.contentScheduleEnabled
        renderContentProfiles(config.contentProfiles)
        switchIdleScreen.isChecked = config.idleScreenEnabled
        editIdleScreenUrl.setText(config.idleScreenUrl)
        editIdleTimeoutMinutes.setText(
            String.format(Locale.ROOT, "%d", config.idleTimeoutMinutes)
        )
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
        val storedPassword = config.readMqttPassword()
        mqttPasswordUnreadable = storedPassword.decryptionFailed
        editMqttPassword.setText(storedPassword.value)
        if (mqttPasswordUnreadable) {
            editMqttPassword.error =
                "Stored password cannot be decrypted. Enter the MQTT password again."
        }
        editMqttDeviceName.setText(config.mqttDeviceName)
        editMqttDiscoveryPrefix.setText(config.mqttDiscoveryPrefix)
        switchRemoteConfig.isChecked = config.remoteConfigEnabled
        showRemoteConfigAddress()
        switchRemoteConfig.setOnCheckedChangeListener { _, enabled ->
            showRemoteConfigAddress(enabled)
        }
        editUpdateUrl.setText(config.updateUrl)
        switchAutoUpdate.isChecked = config.autoUpdateEnabled
        showUpdateStatus()
        editPin.setText("")
    }

    private fun saveConfig() {
        saveConfig(allowInexactSchedule = false)
    }

    private fun saveConfig(
        allowInexactSchedule: Boolean,
        allowClearUnreadablePassword: Boolean = false
    ) {
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

        val allowedOrigins = editDashboardAllowedOrigins.text.toString()
            .lines()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (allowedOrigins.any { !ConfigValidator.isValidDashboardUrl(it) }) {
            Toast.makeText(
                this,
                "Allowed login origins must use http:// or https://",
                Toast.LENGTH_LONG
            ).show()
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

        val contentProfilesResult = ConfigValidator.buildContentProfiles(contentProfileDrafts())
        if (contentProfilesResult.error != null) {
            Toast.makeText(this, contentProfilesResult.error, Toast.LENGTH_LONG).show()
            return
        }
        if (switchContentSchedule.isChecked && contentProfilesResult.profiles.isEmpty()) {
            Toast.makeText(
                this,
                "Add at least one scheduled content profile",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val idleScreenUrl = editIdleScreenUrl.text.toString().trim()
        if (switchIdleScreen.isChecked && !ConfigValidator.isValidDashboardUrl(idleScreenUrl)) {
            Toast.makeText(
                this,
                "Idle screen URL must use http:// or https://",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val idleTimeoutMinutes = editIdleTimeoutMinutes.text.toString().toIntOrNull()
        if (!ConfigValidator.isValidIdleTimeout(idleTimeoutMinutes)) {
            Toast.makeText(
                this,
                "Idle timeout must be ${AppConfig.MIN_IDLE_TIMEOUT_MINUTES}-" +
                    "${AppConfig.MAX_IDLE_TIMEOUT_MINUTES} minutes",
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

        val updateUrl = editUpdateUrl.text.toString().trim()
        if (!ConfigValidator.isValidUpdateUrl(updateUrl)) {
            Toast.makeText(this, R.string.update_url_invalid, Toast.LENGTH_LONG).show()
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

        val mqttPassword = editMqttPassword.text.toString()
        if (switchMqttEnabled.isChecked &&
            mqttPasswordUnreadable &&
            mqttPassword.isBlank() &&
            !allowClearUnreadablePassword
        ) {
            AlertDialog.Builder(this)
                .setTitle("Stored MQTT password is unavailable")
                .setMessage(
                    "Enter the password again, or explicitly clear the unreadable credential " +
                        "if this broker does not require a password."
                )
                .setPositiveButton("Clear and save") { _, _ ->
                    saveConfig(
                        allowInexactSchedule = allowInexactSchedule,
                        allowClearUnreadablePassword = true
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        // Preserve unreadable ciphertext until the user explicitly supplies a replacement.
        if (!mqttPasswordUnreadable ||
            mqttPassword.isNotBlank() ||
            allowClearUnreadablePassword
        ) {
            try {
                config.mqttPassword = mqttPassword
                mqttPasswordUnreadable = false
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to encrypt MQTT password", Toast.LENGTH_LONG).show()
                return
            }
        }

        // All validation and credential encryption have succeeded. Persist the validated values.
        config.dashboardUrls = urls
        config.dashboardAllowedOrigins = allowedOrigins
        config.autoRotateEnabled = switchAutoRotate.isChecked
        config.autoRotateIntervalSeconds = requireNotNull(rotateInterval)
        config.contentScheduleEnabled = switchContentSchedule.isChecked
        config.contentProfiles = contentProfilesResult.profiles
        config.idleScreenEnabled = switchIdleScreen.isChecked
        config.idleScreenUrl = idleScreenUrl
        config.idleTimeoutMinutes = requireNotNull(idleTimeoutMinutes)
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
        config.remoteConfigEnabled = switchRemoteConfig.isChecked
        config.updateUrl = updateUrl
        config.autoUpdateEnabled = switchAutoUpdate.isChecked

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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_AWAITING_EXACT_ALARM_ACCESS, awaitingExactAlarmAccess)
        super.onSaveInstanceState(outState)
    }

    private fun renderContentProfiles(profiles: List<ContentProfile>) {
        containerContentProfiles.removeAllViews()
        profiles.forEach(::addContentProfileRow)
        showContentProfilesEmptyState()
    }

    private fun addContentProfileRow(profile: ContentProfile) {
        val row = layoutInflater.inflate(
            R.layout.item_content_profile,
            containerContentProfiles,
            false
        )

        val timeButton = row.findViewById<MaterialButton>(R.id.btnProfileTime)
        timeButton.text = profile.startTime
        timeButton.setOnClickListener { showProfileTimePicker(timeButton) }

        val dayGroup = row.findViewById<ChipGroup>(R.id.chipGroupDays)
        WeekDays.ALL.forEach { day ->
            val chip = layoutInflater.inflate(
                R.layout.item_content_profile_day,
                dayGroup,
                false
            ) as Chip
            chip.text = WeekDays.shortName(day)
            chip.tag = day
            chip.isChecked = day in profile.days
            dayGroup.addView(chip)
        }

        row.findViewById<EditText>(R.id.editProfileUrls).setText(profile.urls.joinToString("\n"))
        row.findViewById<MaterialButton>(R.id.btnRemoveProfile).setOnClickListener {
            containerContentProfiles.removeView(row)
            showContentProfilesEmptyState()
        }

        containerContentProfiles.addView(row)
    }

    private fun showContentProfilesEmptyState() {
        textContentProfilesEmpty.visibility =
            if (containerContentProfiles.childCount == 0) View.VISIBLE else View.GONE
    }

    private fun showProfileTimePicker(timeButton: MaterialButton) {
        val current = runCatching { LocalTime.parse(timeButton.text.toString()) }.getOrNull()
            ?: DEFAULT_PROFILE_TIME
        TimePickerDialog(
            this,
            { _, hour, minute ->
                timeButton.text = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
            },
            current.hour,
            current.minute,
            DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun contentProfileDrafts(): List<ContentProfileDraft> {
        return (0 until containerContentProfiles.childCount).map { index ->
            val row = containerContentProfiles.getChildAt(index)
            val dayGroup = row.findViewById<ChipGroup>(R.id.chipGroupDays)
            val days = (0 until dayGroup.childCount)
                .map { dayGroup.getChildAt(it) as Chip }
                .filter { it.isChecked }
                .map { it.tag as DayOfWeek }
                .toSet()

            ContentProfileDraft(
                startTime = row.findViewById<MaterialButton>(R.id.btnProfileTime).text
                    .toString()
                    .trim(),
                urls = row.findViewById<EditText>(R.id.editProfileUrls).text
                    .toString()
                    .lines()
                    .map(String::trim)
                    .filter(String::isNotBlank),
                days = WeekDays.normalize(days)
            )
        }
    }

    private fun setupButtons() {
        findViewById<MaterialButton>(R.id.btnAddContentProfile).setOnClickListener {
            addContentProfileRow(
                ContentProfile(
                    getString(R.string.content_profile_default_time),
                    emptyList()
                )
            )
            showContentProfilesEmptyState()
        }
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
                    // Persist this before removing the task. KioskService.onTaskRemoved() must
                    // observe the flag and avoid immediately launching the dashboard again.
                    config.isKioskSuspended = true
                    ScheduleManager(this, config).stop()
                    if (LockTaskHelper.isDeviceOwner(this)) {
                        stopLockTask()
                        LockTaskHelper.clearKioskPolicies(this)
                    }
                    stopService(Intent(this, WatchdogService::class.java))
                    stopService(Intent(this, KioskService::class.java))
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
            appendLine("Version: ${UpdateSummary.of(config).installedVersion}")
            appendLine("Device Owner: $isDeviceOwner")
            appendLine("WebView: $webViewVersion")
            appendLine("Memory: ${usedMem}MB / ${totalMem}MB")
            appendLine("Uptime: ${uptimeHours}h ${uptimeMinutes}m")
            appendLine("Dashboard URLs: ${config.dashboardUrls.size}")
            appendLine("Content profiles: ${if (config.contentScheduleEnabled) config.contentProfiles.size else "disabled"}")
            appendLine("Idle screen: ${if (config.idleScreenEnabled) "${config.idleTimeoutMinutes} min" else "disabled"}")
            appendLine("Allowed login origins: ${config.dashboardAllowedOrigins.size}")
            appendLine("Schedule: ${if (config.scheduleEnabled) "${config.screenOnTime}-${config.screenOffTime}" else "disabled"}")
            appendLine("Exact alarms: ${canScheduleExactAlarms()}")
            appendLine("Auto brightness: ${config.autoBrightnessEnabled}")
            appendLine("MQTT: ${if (config.mqttEnabled) "${config.mqttBrokerHost}:${config.mqttBrokerPort}" else "disabled"}")
            appendLine("MQTT discovery: ${config.mqttDiscoveryPrefix}")
            appendLine("Remote configuration: ${if (config.remoteConfigEnabled) "enabled" else "disabled"}")
        }

        textDebugInfo.text = info
    }

    private fun showRemoteConfigAddress(enabled: Boolean = switchRemoteConfig.isChecked) {
        val urls = RemoteConfigAddress.localUrls()
        textRemoteConfigAddress.text = when {
            !enabled && urls.isEmpty() -> getString(R.string.remote_config_disabled_help)
            urls.isEmpty() ->
                getString(R.string.remote_config_no_address_help)
            !enabled -> getString(R.string.remote_config_ready_help, urls.joinToString("\n"))
            else -> urls.joinToString("\n")
        }
    }

    /**
     * Reads the stored URL rather than the text field: contacting the server is a separate action
     * from saving, so an unsaved edit must not silently decide where the tablet fetches an APK
     * from. [installNow] overrides the automatic-update switch as well as the failure guards.
     */
    private fun requestUpdate(installNow: Boolean) {
        if (config.updateUrl.isBlank()) {
            Toast.makeText(this, R.string.update_check_needs_url, Toast.LENGTH_LONG).show()
            return
        }
        val message =
            if (installNow) R.string.update_install_started else R.string.update_check_started
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Thread {
            runCatching {
                val checker = UpdateChecker(applicationContext)
                if (installNow) checker.installNow() else checker.checkNow(force = true)
            }
        }.start()
    }

    private fun showUpdateStatus() {
        val summary = UpdateSummary.of(config)
        textUpdateStatus.text = buildString {
            appendLine(getString(R.string.update_installed_version, summary.installedVersion))
            if (summary.serverState == null) {
                append(getString(R.string.update_status_disabled))
            } else {
                append(getString(R.string.update_server_state, summary.serverState))
                summary.lastOutcome?.let {
                    appendLine()
                    append(getString(R.string.update_status_last, it))
                }
            }
        }
        // Only offered while automatic installation is off; with it on there is nothing waiting.
        val pending = summary.pendingVersion
        buttonInstallUpdate.visibility = if (pending == null) View.GONE else View.VISIBLE
        if (pending != null) {
            buttonInstallUpdate.text = getString(R.string.update_install_version, pending)
        }
    }

    companion object {
        private const val STATE_AWAITING_EXACT_ALARM_ACCESS =
            "awaiting_exact_alarm_access"
        private val DEFAULT_PROFILE_TIME: LocalTime = LocalTime.of(6, 0)
    }

}
