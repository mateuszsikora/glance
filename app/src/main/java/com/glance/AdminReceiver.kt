package com.glance

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

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

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        lockTaskEntriesHandled.incrementAndGet()
        Log.i(TAG, "Lock task mode entering")
    }

    companion object {
        private const val TAG = "AdminReceiver"

        /** Lock task entries handled in this process; see [com.glance.dashboard.LaunchWork]. */
        internal val lockTaskEntriesHandled = AtomicInteger()

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, AdminReceiver::class.java)
        }
    }
}
