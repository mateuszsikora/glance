package com.glance.mqtt

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.internal.ClientComms
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MqttKeepAliveTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun brokerGraceCoversAPingDelayedByAlarmBatching() {
        val keepAlive = MqttStateManager.KEEP_ALIVE_SECONDS
        // The slack keeps an on-time alarm from re-arming, so one batch delay is the worst case.
        val latestPing = keepAlive + MqttStateManager.MAX_ALARM_DELAY_SECONDS +
            AlarmPingSender.PING_SLACK_MS / 1_000.0

        // The broker disconnects after 1.5x the keep-alive without any packet.
        assertTrue(latestPing < keepAlive * 1.5)
        // Paho drops the connection itself when nothing was sent for 2x the keep-alive.
        assertTrue(latestPing < keepAlive * 2.0)
    }

    @Test
    fun clientMeasuresKeepAliveWithTheSuspendAwareClock() {
        val client = MqttStateManager.createClient(
            "tcp://localhost:1883",
            "glance_tablet",
            AlarmPingSender(context, "glance_tablet")
        )

        // Paho silently falls back to System.nanoTime, which stops during suspend, if a refactor
        // drops the timer argument; only its internals reveal which clock is in use.
        val comms = MqttAsyncClient::class.java.getDeclaredField("comms")
            .apply { isAccessible = true }
            .get(client) as ClientComms
        val clientState = comms.clientState
        val timer = clientState.javaClass.getDeclaredField("highResolutionTimer")
            .apply { isAccessible = true }
            .get(clientState)

        assertSame(ElapsedRealtimeTimer, timer)
        client.close(true)
    }
}
