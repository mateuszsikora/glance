package com.glance.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.glance.MainActivity

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, launching kiosk")
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
