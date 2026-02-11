package com.glance

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Owner receiver.
 * Enable via: adb shell dpm set-device-owner com.glance/.AdminReceiver
 *
 * Huawei BAH2-L09: Remove ALL accounts (Google + Huawei ID) before running dpm command.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device admin disabled")
    }

    companion object {
        private const val TAG = "AdminReceiver"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, AdminReceiver::class.java)
        }
    }
}
