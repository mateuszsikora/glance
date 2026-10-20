package com.glance.mqtt

import android.os.SystemClock
import org.eclipse.paho.client.mqttv3.internal.HighResolutionTimer

/**
 * Keep-alive clock for Paho that keeps counting while the tablet is suspended.
 *
 * Paho decides whether a ping is due by comparing [nanoTime] with the time of the last packet.
 * Its default clock is [System.nanoTime], which on Android stops during suspend: a wakeup alarm
 * that fires after minutes of screen-off sleep would then see only the few seconds the CPU was
 * awake, judge the ping not yet due and send nothing, while the broker's wall-clock deadline
 * keeps running.
 */
object ElapsedRealtimeTimer : HighResolutionTimer {
    override fun nanoTime(): Long = SystemClock.elapsedRealtimeNanos()
}
