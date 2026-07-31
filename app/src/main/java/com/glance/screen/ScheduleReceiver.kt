package com.glance.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glance.config.AppConfig

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        ScheduleManager(context.applicationContext, AppConfig(context.applicationContext))
            .handleAlarm(action)
    }
}
