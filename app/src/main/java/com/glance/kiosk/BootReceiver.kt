package com.glance.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.glance.MainActivity

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "System startup/update received, launching kiosk")
                startServices(context, reloadConfig = false)
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED -> {
                Log.i(TAG, "Clock or alarm access changed, rescheduling")
                startServices(context, reloadConfig = true)
            }
        }
    }

    private fun startServices(context: Context, reloadConfig: Boolean) {
        val serviceIntent = Intent(context, KioskService::class.java)
        if (reloadConfig) {
            serviceIntent.action = KioskService.ACTION_RELOAD_CONFIG
        }
        ContextCompat.startForegroundService(
            context,
            serviceIntent
        )
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
