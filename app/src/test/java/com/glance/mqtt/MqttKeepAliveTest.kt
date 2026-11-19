package com.glance.mqtt

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.glance.config.AppConfig
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.internal.ClientComms
import org.junit.Assert.assertEquals
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
    fun theAlarmClockKeepAliveIsLongerAndStillInsideTheBrokersGrace() {
        assertEquals(MqttStateManager.KEEP_ALIVE_SECONDS, MqttStateManager.keepAliveSeconds(false))
        val keepAlive = MqttStateManager.keepAliveSeconds(true)
        assertEquals(MqttStateManager.ALARM_CLOCK_KEEP_ALIVE_SECONDS, keepAlive)
        assertTrue(keepAlive > MqttStateManager.KEEP_ALIVE_SECONDS)

        // An alarm clock is not batched, but the display may turn on and leave an ordinary alarm,
        // which EMUI can batch; that ping must still beat the broker's 1.5x cutoff.
        val latestPing = keepAlive + MqttStateManager.MAX_ALARM_DELAY_SECONDS +
            AlarmPingSender.PING_SLACK_MS / 1_000.0
        assertTrue(latestPing < keepAlive * 1.5)
    }

    @Test
    fun theConnectionNegotiatesTheKeepAliveTheSwitchAsksFor() {
        val config = AppConfig(context)

        fun keepAlive(manufacturer: String, stored: Boolean? = null): Int {
            val settings = MqttKeepAliveSettings(context, manufacturer)
            stored?.let { settings.alarmClockWhileDisplayOff = it }
            return MqttStateManager(
                context,
                config,
                stateProvider = { MqttReportedState(screenOn = true, brightness = 128) },
                commandHandler = {},
                keepAliveSettings = settings
            ).buildConnectOptions().keepAliveInterval
        }

        assertEquals(MqttStateManager.ALARM_CLOCK_KEEP_ALIVE_SECONDS, keepAlive("HUAWEI"))
        assertEquals(MqttStateManager.KEEP_ALIVE_SECONDS, keepAlive("HUAWEI", stored = false))
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
