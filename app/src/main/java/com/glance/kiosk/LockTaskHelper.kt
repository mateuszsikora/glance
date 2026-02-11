package com.glance.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
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

    fun configureKioskPolicies(context: Context) {
        if (!isDeviceOwner(context)) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = AdminReceiver.getComponentName(context)

        try {
            dpm.setGlobalSetting(admin, "stay_on_while_plugged_in", "3")
            dpm.setKeyguardDisabled(admin, true)
            dpm.setStatusBarDisabled(admin, true)
            Log.i(TAG, "Kiosk policies configured")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure kiosk policies", e)
        }
    }
}
