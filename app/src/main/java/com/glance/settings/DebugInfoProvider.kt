package com.glance.settings

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import com.glance.BuildConfig
import com.glance.config.AppConfig
import com.glance.update.UpdateSummary

/** An allowlisted, address-free summary shared by the tablet and authenticated remote panel. */
internal data class DebugInfoSnapshot(val fields: List<Pair<String, String>>) {
    fun asText(): String = fields.joinToString("\n") { (label, value) -> "$label: $value" }
}

/** Reads only; sampling never creates a WebView, changes settings, or starts a service. */
internal class DebugInfoProvider(
    private val context: Context,
    private val config: AppConfig,
    private val webViewVersion: () -> String? = { WebView.getCurrentWebViewPackage()?.versionName },
    private val deviceOwner: () -> Boolean? = {
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName)
    },
    private val exactAlarms: () -> Boolean? = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms()
    },
    private val heapBytes: () -> Pair<Long, Long> = {
        val runtime = Runtime.getRuntime()
        (runtime.totalMemory() - runtime.freeMemory()) to runtime.maxMemory()
    },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {
    fun snapshot(): DebugInfoSnapshot {
        val (used, limit) = heapBytes()
        val uptimeMinutes = elapsedRealtime() / 60_000
        return DebugInfoSnapshot(DeviceDebugInfo(context).fields() + listOf(
            "Application package" to context.packageName,
            "Version" to UpdateSummary.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            "Device Owner" to when (readCapability(deviceOwner)) {
                true -> "Yes"
                false -> "No (regular app)"
                null -> "Unavailable"
            },
            "Android System WebView version" to
                (readCapability(webViewVersion)?.takeIf { it.isNotBlank() } ?: "Unavailable"),
            "App Java heap used" to "${used / MIB} MiB",
            "App Java heap limit" to "${limit / MIB} MiB",
            "Device uptime (including sleep)" to "${uptimeMinutes / 60}h ${uptimeMinutes % 60}m",
            "Dashboard count" to config.dashboardUrls.size.toString(),
            "Scheduled content" to "${status(config.contentScheduleEnabled)} (profiles: ${config.contentProfiles.size})",
            "Idle screen" to "${status(config.idleScreenEnabled)} (timeout: ${config.idleTimeoutMinutes} min)",
            "Allowed login-origin count" to config.dashboardAllowedOrigins.size.toString(),
            "Screen schedule" to if (config.scheduleEnabled) {
                "Enabled (${config.screenOnTime}–${config.screenOffTime})"
            } else "Disabled",
            "Exact-alarm capability" to when (readCapability(exactAlarms)) {
                true -> "Allowed"
                false -> "Not allowed"
                null -> "Unavailable"
            },
            "Automatic brightness" to status(config.autoBrightnessEnabled),
            // Configuration status, not broker connectivity; never include broker or discovery addresses.
            "MQTT configuration" to status(config.mqttEnabled),
            "Remote configuration" to status(config.remoteConfigEnabled)
        ))
    }

    private fun status(enabled: Boolean): String = if (enabled) "Enabled" else "Disabled"

    // Some vendor builds lack a WebView provider or deny access to a platform service. Do not
    // expose exception messages: they can contain private platform/configuration details.
    private fun <T> readCapability(read: () -> T?): T? = try {
        read()
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MIB = 1024 * 1024
    }
}
