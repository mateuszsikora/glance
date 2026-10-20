package com.glance.mqtt

import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MqttKeepAliveTest {

    @Test
    fun brokerGraceCoversAPingDelayedByAlarmBatching() {
        val keepAlive = MqttStateManager.KEEP_ALIVE_SECONDS
        val latestPing = keepAlive + MqttStateManager.MAX_ALARM_DELAY_SECONDS

        // The broker disconnects after 1.5x the keep-alive without any packet.
        assertTrue(latestPing < keepAlive * 3 / 2)
        // Paho drops the connection itself when nothing was sent for 2x the keep-alive.
        assertTrue(latestPing < keepAlive * 2)
    }

    @Test
    fun keepAliveClockFollowsElapsedRealtime() {
        val before = ElapsedRealtimeTimer.nanoTime()

        SystemClock.sleep(TimeUnit.MINUTES.toMillis(5))

        assertEquals(SystemClock.elapsedRealtimeNanos(), ElapsedRealtimeTimer.nanoTime())
        assertTrue(ElapsedRealtimeTimer.nanoTime() - before >= TimeUnit.MINUTES.toNanos(5))
    }
}
