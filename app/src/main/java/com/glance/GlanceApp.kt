package com.glance

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.glance.config.AppConfig
import com.glance.watchdog.CrashLogger
import kotlin.system.exitProcess

class GlanceApp : Application() {

    lateinit var appConfig: AppConfig
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        appConfig = AppConfig(this)
        installCrashLogger()
        createNotificationChannels()
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLogger.log(this, "FATAL", "Uncaught exception on ${thread.name}", throwable)
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                exitProcess(10)
            }
        }
    }

    private fun createNotificationChannels() {
        val kioskChannel = NotificationChannel(
            CHANNEL_KIOSK,
            getString(R.string.kiosk_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }

        val watchdogChannel = NotificationChannel(
            CHANNEL_WATCHDOG,
            getString(R.string.watchdog_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(kioskChannel)
        nm.createNotificationChannel(watchdogChannel)
    }

    companion object {
        const val CHANNEL_KIOSK = "kiosk_service"
        const val CHANNEL_WATCHDOG = "watchdog_service"

        lateinit var instance: GlanceApp
            private set
    }
}
