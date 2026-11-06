package com.glance.kiosk

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.glance.AdminReceiver
import com.glance.diagnostics.Diagnostics

/**
 * Reboots the tablet: the only way to release the kernel's unreclaimable memory, which some vendor
 * kernels leak over months (see [com.glance.diagnostics.KernelMemoryTrend]). Restarting Glance's
 * process, as [AppRestarter] does, leaves it in place.
 *
 * Requires Device Owner ([DevicePolicyManager.reboot]). Android refuses the reboot during a phone
 * call; then, and when the tablet has not begun to shut down three minutes later, Glance restarts
 * its process instead, which releases at least its own memory. Once Android announces the
 * shutdown, it is left to finish however long it takes: a restart in the middle would drop the
 * marker the next boot needs.
 *
 * After the boot [BootReceiver] opens the dashboard, which enters lock task mode and starts the
 * services, as after any boot. A marker tells [KioskService] that this boot was the scheduled
 * reboot, so it keeps the persisted requested screen state, including a manual MQTT ON inside the
 * OFF window, and turns the display off again if that state is off. Any other boot, such as a
 * power cut or a reboot by hand, applies the screen schedule.
 */
object TabletRebooter {

    private const val TAG = "TabletRebooter"
    private const val PREFS_NAME = "glance_reboot_state"
    private const val KEY_MARKED_AT = "marked_at_wall"
    private const val KEY_BOOT_COUNT = "boot_count"
    /** Lets the journal thread and pending preference writes reach storage first. */
    internal const val REBOOT_DELAY_MS = 1_000L
    /**
     * Without a shutdown broadcast for this long, the reboot did not happen. Shutting down usually
     * takes 20-40 seconds, but the broadcast itself can come late on a tablet that swaps hard.
     */
    internal const val REBOOT_TIMEOUT_MS = 3 * 60_000L
    /**
     * EMUI can deliver BOOT_COMPLETED minutes after the boot. A marker older than this was left by
     * a reboot that never came back to Glance, and the boot that finds it is not the scheduled one.
     */
    private const val MARKER_VALID_MS = 30 * 60_000L

    /** Set once the tablet is about to reboot; its display-off report is not a real state. */
    @Volatile
    var rebootInProgress = false
        internal set

    private val handler = Handler(Looper.getMainLooper())
    private var fallback: Runnable? = null
    private var shutdownReceiver: BroadcastReceiver? = null

    /**
     * @param displayOn whether the display is on now; used only when Glance restarts instead.
     */
    fun reboot(
        context: Context,
        reason: String,
        displayOn: Boolean,
        rebootDevice: (Context) -> Unit = ::rebootWithDevicePolicy,
        restartApp: (Context, String, Boolean) -> Unit = AppRestarter::restart
    ) {
        val app = context.applicationContext
        Log.i(TAG, "Rebooting the tablet ($reason)")
        Diagnostics.record("Rebooting the tablet ($reason)", includeMemory = true)
        rebootInProgress = true
        // The tablet starts without today's samples; judge them now.
        (app as? com.glance.GlanceApp)?.memoryTrend?.evaluateNow(SystemClock.elapsedRealtime())
        markReboot(app)
        val didNotReboot = Runnable {
            restartInstead(app, reason, displayOn, "the tablet did not reboot", restartApp)
        }
        fallback = didNotReboot
        watchForShutdown(app)
        handler.postDelayed(
            {
                try {
                    rebootDevice(app)
                } catch (e: RuntimeException) {
                    // IllegalStateException during a call; SecurityException without Device Owner.
                    Log.w(TAG, "The tablet refused to reboot", e)
                    restartInstead(app, reason, displayOn, "the tablet refused to reboot", restartApp)
                    return@postDelayed
                }
                // Unless the shutdown has already been announced.
                if (fallback === didNotReboot) handler.postDelayed(didNotReboot, REBOOT_TIMEOUT_MS)
            },
            REBOOT_DELAY_MS
        )
    }

    /** Registered before the reboot is requested: the call can return after the broadcast. */
    private fun watchForShutdown(context: Context) {
        stopWatchingForShutdown(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SHUTDOWN) return
                Log.i(TAG, "Shutdown under way")
                fallback?.let(handler::removeCallbacks)
                fallback = null
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SHUTDOWN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        shutdownReceiver = receiver
    }

    /**
     * @return true once, in the first start after [reboot]; false otherwise, including when the
     * reboot did not happen or Glance came back more than half an hour later.
     */
    fun consumePendingReboot(
        context: Context,
        nowWallMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        bootCount: Int = bootCount(context)
    ): Boolean {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_MARKED_AT)) return false
        val markedAt = prefs.getLong(KEY_MARKED_AT, 0L)
        val markedBoot = prefs.getInt(KEY_BOOT_COUNT, -1)
        clearMarker(context)
        return isNextBoot(markedAt, markedBoot, nowWallMs, nowElapsedMs, bootCount)
    }

    /**
     * The tablet booted since [markedAtWallMs] when [Settings.Global.BOOT_COUNT] changed, or, where
     * the platform does not count boots, when the time since the marker exceeds the uptime.
     */
    internal fun isNextBoot(
        markedAtWallMs: Long,
        markedBoot: Int,
        nowWallMs: Long,
        nowElapsedMs: Long,
        nowBoot: Int
    ): Boolean {
        val sinceMark = nowWallMs - markedAtWallMs
        if (sinceMark < 0 || sinceMark > MARKER_VALID_MS) return false
        return if (markedBoot >= 0 && nowBoot >= 0) nowBoot != markedBoot else nowElapsedMs < sinceMark
    }

    internal fun markReboot(
        context: Context,
        nowWallMs: Long = System.currentTimeMillis(),
        bootCount: Int = bootCount(context)
    ) {
        // commit(), not apply(): the marker must be on storage before the tablet goes down.
        prefs(context).edit()
            .putLong(KEY_MARKED_AT, nowWallMs)
            .putInt(KEY_BOOT_COUNT, bootCount)
            .commit()
    }

    private fun restartInstead(
        context: Context,
        reason: String,
        displayOn: Boolean,
        failure: String,
        restartApp: (Context, String, Boolean) -> Unit
    ) {
        fallback?.let(handler::removeCallbacks)
        fallback = null
        stopWatchingForShutdown(context)
        clearMarker(context)
        rebootInProgress = false
        Diagnostics.record("Tablet reboot failed: $failure; restarting Glance instead")
        restartApp(context, "$reason, $failure", displayOn)
    }

    private fun stopWatchingForShutdown(context: Context) {
        shutdownReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        shutdownReceiver = null
    }

    private fun clearMarker(context: Context) {
        prefs(context).edit().remove(KEY_MARKED_AT).remove(KEY_BOOT_COUNT).commit()
    }

    private fun rebootWithDevicePolicy(context: Context) {
        context.getSystemService(DevicePolicyManager::class.java)
            .reboot(AdminReceiver.getComponentName(context))
    }

    /** -1 when the platform does not say. */
    private fun bootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
