package com.glance.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.view.Display
import java.util.Locale

/** Public hardware/OS facts only: no serial, Android ID, fingerprint, hostname or network details. */
internal class DeviceDebugInfo(context: Context) {
    // Both settings surfaces describe the default tablet display, not the settings Activity window.
    private val context = context.applicationContext

    fun fields(): List<Pair<String, String>> {
        val display = available {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }
        val mode = available { display?.mode }
        val memory = available {
            context.getSystemService(ActivityManager::class.java)?.let { manager ->
                ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            }
        }
        val storage = available { StatFs(context.filesDir.absolutePath) }
        // A null receiver only reads the sticky battery snapshot; no listener or service is started.
        val battery = available {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        return listOf(
            "Manufacturer" to text { Build.MANUFACTURER },
            "Tablet model" to text { Build.MODEL },
            "Android version" to "${text { Build.VERSION.RELEASE }} (API ${Build.VERSION.SDK_INT})",
            "Android security patch" to text { Build.VERSION.SECURITY_PATCH },
            "Android OS build" to text { Build.DISPLAY },
            "Hardware platform" to text { Build.HARDWARE },
            "System-on-chip" to text {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                        .filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ")
                } else null
            },
            "Supported CPU ABIs" to text { Build.SUPPORTED_ABIS.joinToString(", ") },
            "CPU cores available to app" to text { Runtime.getRuntime().availableProcessors().toString() },
            "Display resolution (active mode)" to text {
                mode?.takeIf { it.physicalWidth > 0 && it.physicalHeight > 0 }
                    ?.let { "${it.physicalWidth} × ${it.physicalHeight} px" }
            },
            "Display refresh rate" to text {
                mode?.refreshRate?.takeIf { it.isFinite() && it > 0 }?.let { "${decimal(it)} Hz" }
            },
            "Display density (logical)" to text {
                display?.let { context.createDisplayContext(it).resources.configuration.densityDpi }
                    ?.takeIf { it > 0 }?.let { "$it dpi" }
            },
            "Device RAM total" to text { memory?.totalMem?.takeIf { it > 0 }?.let(::mib) },
            "Device RAM available" to text { memory?.availMem?.takeIf { it >= 0 }?.let(::mib) },
            "System low-memory pressure" to text { memory?.lowMemory?.let(::yesNo) },
            "App data volume total" to text { storage?.totalBytes?.takeIf { it > 0 }?.let(::mib) },
            "App data volume available" to text { storage?.availableBytes?.takeIf { it >= 0 }?.let(::mib) },
            "Battery level" to text {
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (scale > 0 && level in 0..scale) "${level * 100L / scale}%" else null
            },
            "Battery status" to text {
                when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "Full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                    else -> null
                }
            },
            "Battery temperature" to text {
                if (battery?.hasExtra(BatteryManager.EXTRA_TEMPERATURE) == true) {
                    "${decimal(battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f)} °C"
                } else null
            },
            "Power saving mode" to text {
                context.getSystemService(PowerManager::class.java)?.isPowerSaveMode?.let(::yesNo)
            },
            "Battery optimization exemption" to text {
                context.getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(context.packageName)?.let(::yesNo)
            },
            "Ambient light sensor" to text {
                context.getSystemService(SensorManager::class.java)?.let {
                    if (it.getDefaultSensor(Sensor.TYPE_LIGHT) != null) "Present" else "Absent"
                }
            }
        )
    }

    private fun text(read: () -> String?): String =
        available(read)?.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: "Unavailable"

    private fun <T> available(read: () -> T?): T? = try {
        read()
    } catch (_: Exception) {
        // Exception messages can include private paths or vendor details.
        null
    }

    private fun decimal(value: Float): String = String.format(Locale.ROOT, "%.1f", value)
    private fun mib(bytes: Long): String = "${bytes / (1024 * 1024)} MiB"
    private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"
}
