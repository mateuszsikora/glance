package com.glance.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.glance.AdminReceiver

/**
 * Helper to manage LockTask (kiosk) mode via Device Owner APIs.
 */
object LockTaskHelper {

    private const val TAG = "LockTaskHelper"

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun setLockTaskPackages(context: Context) {
        if (!isDeviceOwner(context)) {
            Log.w(TAG, "Not device owner, cannot set lock task packages")
            return
        }

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = AdminReceiver.getComponentName(context)

        try {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            Log.i(TAG, "Lock task packages set successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to set lock task packages", e)
        }
    }

    fun configureKioskPolicies(context: Context, stayAwakeWhilePlugged: Boolean = true) {
        if (!isDeviceOwner(context)) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = AdminReceiver.getComponentName(context)

        try {
            rememberStayAwakeSetting(context)
            dpm.setGlobalSetting(
                admin,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                stayAwakeValue(stayAwakeWhilePlugged)
            )
            val keyguardDisabled = dpm.setKeyguardDisabled(admin, true)
            val statusBarDisabled = dpm.setStatusBarDisabled(admin, true)
            if (!keyguardDisabled || !statusBarDisabled) {
                Log.w(
                    TAG,
                    "Some kiosk policies were rejected: " +
                        "keyguard=$keyguardDisabled, statusBar=$statusBarDisabled"
                )
            }
            Log.i(TAG, "Kiosk policies configured")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure kiosk policies", e)
        }
    }

    /**
     * Keeping the tablet awake while charging is what an always-on dashboard wants during the
     * day. Overnight it is the opposite: it would hold the display on after any wake the platform
     * performs on its own, so the policy follows the requested screen state.
     */
    fun setStayAwakeWhilePlugged(context: Context, enabled: Boolean) {
        if (!isDeviceOwner(context)) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try {
            rememberStayAwakeSetting(context)
            dpm.setGlobalSetting(
                AdminReceiver.getComponentName(context),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                stayAwakeValue(enabled)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update the stay-awake-while-plugged policy", e)
        }
    }

    fun clearKioskPolicies(context: Context) {
        if (!isDeviceOwner(context)) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = AdminReceiver.getComponentName(context)
        try {
            val policyPrefs = context.getSharedPreferences(POLICY_PREFS, Context.MODE_PRIVATE)
            val previousStayAwake = policyPrefs.getInt(KEY_PREVIOUS_STAY_AWAKE, 0)
            dpm.setStatusBarDisabled(admin, false)
            dpm.setKeyguardDisabled(admin, false)
            dpm.setGlobalSetting(
                admin,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                previousStayAwake.toString()
            )
            policyPrefs.edit().remove(KEY_PREVIOUS_STAY_AWAKE).apply()
            Log.i(TAG, "Kiosk policies cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear kiosk policies", e)
            throw e
        }
    }

    private fun stayAwakeValue(enabled: Boolean): String {
        return if (enabled) STAY_AWAKE_ANY_CHARGER else STAY_AWAKE_NEVER
    }

    private fun rememberStayAwakeSetting(context: Context) {
        val prefs = context.getSharedPreferences(POLICY_PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_PREVIOUS_STAY_AWAKE)) return

        val current = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            0
        )
        // Existing Glance installations already set this value to 3 before this bookkeeping
        // existed. Their most conservative restore target is Android's default value, 0.
        val previous = if (current == STAY_AWAKE_ANY_CHARGER.toInt()) 0 else current
        prefs.edit().putInt(KEY_PREVIOUS_STAY_AWAKE, previous).apply()
    }

    private const val POLICY_PREFS = "glance_kiosk_policy_state"
    private const val KEY_PREVIOUS_STAY_AWAKE = "previous_stay_on_while_plugged_in"
    /** Android's plug-type bit mask: AC and USB, the two a wall-mounted tablet is powered by. */
    private const val STAY_AWAKE_ANY_CHARGER = "3"
    private const val STAY_AWAKE_NEVER = "0"
}
