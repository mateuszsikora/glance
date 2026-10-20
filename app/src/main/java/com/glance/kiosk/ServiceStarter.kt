package com.glance.kiosk

import android.content.Context
import android.content.Intent

/**
 * Starts one of Glance's foreground services from a visible Activity.
 *
 * `startForegroundService()` obliges the service to call `startForeground()` within a few seconds,
 * and Android 8 and later bring the service down and crash the whole app when it does not. Both services
 * do so first thing in `onStartCommand()`, but that runs on the main thread, which can be busy for
 * five seconds initialising the WebView of a freshly created dashboard on a slow tablet. A visible
 * Activity may use plain `startService()` instead, which carries no deadline; the service still
 * promotes itself to the foreground. Receivers and alarms, which run in the background, must keep
 * using `startForegroundService()`.
 */
object ServiceStarter {

    fun startFromForeground(context: Context, intent: Intent) {
        try {
            context.startService(intent)
        } catch (_: IllegalStateException) {
            // Thrown when Android already considers the app to be in the background.
            context.startForegroundService(intent)
        }
    }
}
