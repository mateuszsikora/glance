package com.glance.mqtt

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.glance.config.AppConfig
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MqttStateManagerRetryTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager get() = shadowOf(alarmManager)
    private val journal = mutableListOf<String>()
    private lateinit var fake: FakeClient
    private lateinit var manager: MqttStateManager

    @Before
    fun startManager() {
        shadowOf(context).grantPermissions("${context.packageName}.$NOT_EXPORTED_PERMISSION")
        val config = AppConfig(context).apply {
            mqttEnabled = true
            mqttBrokerHost = "broker.example.invalid"
        }
        manager = MqttStateManager(
            context,
            config,
            stateProvider = { MqttReportedState(screenOn = true, brightness = 128) },
            commandHandler = {},
            keepAliveSettings = MqttKeepAliveSettings(context, "Google"),
            clientFactory = { uri, id, ping -> FakeClient(uri, id, ping).also { fake = it } },
            journal = { journal += it }
        )
        manager.start()
    }

    @After
    fun stopManager() {
        manager.stop()
    }

    @Test
    fun theRetryAlarmIsArmedFromTheStartAndCancelledOnceConnected() {
        assertEquals(1, fake.connects.size)
        assertEquals(SystemClock.elapsedRealtime() + 60_000L, retryAlarm().getTriggerAtMs())

        fake.connectionSucceeds(reconnect = false)

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun aFailedFirstAttemptIsRetriedOnTheAlarm() {
        fake.connects.last().onFailure(null, MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt()))

        fireRetryAlarm()

        assertEquals(2, fake.connects.size)
        assertEquals(listOf("MQTT retry alarm: reconnecting"), journal)
        // The alarm replaced the pending Handler retry instead of running beside it.
        fake.connects.last().onFailure(null, MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertEquals(2, fake.connects.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertEquals(3, fake.connects.size)
    }

    @Test
    fun aLostSessionIsReconnectedOnTheAlarmUntilItIsUp() {
        fake.connectionSucceeds(reconnect = false)

        fake.connectionLost()
        val armed = retryAlarm()
        assertEquals(SystemClock.elapsedRealtime() + 60_000L, armed.getTriggerAtMs())
        fireRetryAlarm()
        fireRetryAlarm()

        assertEquals(2, fake.reconnects)
        assertEquals(1, fake.connects.size)
        fake.connectionSucceeds(reconnect = true)
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun aFirstAttemptThatNeverAnswersIsAbandonedOnTheSecondAlarm() {
        fireRetryAlarm()
        assertEquals(1, fake.connects.size)
        assertEquals(0, fake.forcedDisconnects)

        fireRetryAlarm()

        assertEquals(1, fake.forcedDisconnects)
        assertEquals(2, fake.connects.size)
        assertEquals(
            listOf(
                "MQTT retry alarm: attempt in progress",
                "MQTT retry alarm: abandoning a stuck connection attempt",
                "MQTT retry alarm: reconnecting"
            ),
            journal
        )
    }

    @Test
    fun aReconnectThatNeverAnswersIsAbandonedOnTheSecondAlarm() {
        fake.connectionSucceeds(reconnect = false)
        fake.connectionLost()
        fake.reconnectFailure = MqttException(MqttException.REASON_CODE_CONNECT_IN_PROGRESS.toInt())

        fireRetryAlarm()
        assertEquals(0, fake.forcedDisconnects)
        fireRetryAlarm()

        assertEquals(1, fake.forcedDisconnects)
        // Once to find the attempt still running on each alarm, then once after abandoning it.
        assertEquals(3, fake.reconnects)
    }

    @Test
    fun anAttemptThatRunsOnlyOnceAtAnAlarmIsLeftAlone() {
        fireRetryAlarm()
        fake.connects.last().onFailure(null, MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt()))
        fireRetryAlarm()
        // A new attempt, so the count of alarms that found one running starts again.
        fireRetryAlarm()

        assertEquals(0, fake.forcedDisconnects)
    }

    @Test
    fun stopCancelsTheRetryAlarm() {
        manager.stop()

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    private fun retryAlarm(): ShadowAlarmManager.ScheduledAlarm =
        shadowAlarmManager.scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action?.startsWith(RETRY_ACTION_PREFIX) == true
        }

    private fun fireRetryAlarm() {
        shadowAlarmManager.fireAlarm(retryAlarm())
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Paho client that never opens a socket; the test plays the broker. */
    private class FakeClient(
        serverUri: String,
        clientId: String,
        pingSender: AlarmPingSender
    ) : MqttAsyncClient(serverUri, clientId, MemoryPersistence(), pingSender, null, ElapsedRealtimeTimer) {

        val connects = mutableListOf<IMqttActionListener>()
        var reconnects = 0
        var forcedDisconnects = 0
        var reconnectFailure: MqttException? = null
        private var connected = false
        private var callback: MqttCallbackExtended? = null

        fun connectionSucceeds(reconnect: Boolean) {
            connected = true
            reconnectFailure = null
            if (!reconnect) connects.last().onSuccess(null)
            callback?.connectComplete(reconnect, "tcp://broker.example.invalid:1883")
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun connectionLost() {
            connected = false
            callback?.connectionLost(MqttException(MqttException.REASON_CODE_CONNECTION_LOST.toInt()))
        }

        override fun setCallback(callback: MqttCallback?) {
            this.callback = callback as MqttCallbackExtended?
        }

        override fun connect(
            options: MqttConnectOptions?,
            userContext: Any?,
            callback: IMqttActionListener
        ): IMqttToken? {
            connects += callback
            return null
        }

        override fun reconnect() {
            reconnects++
            reconnectFailure?.let { throw it }
        }

        override fun disconnectForcibly(
            quiesceTimeout: Long,
            disconnectTimeout: Long,
            sendDisconnectPacket: Boolean
        ) {
            forcedDisconnects++
            reconnectFailure = null
        }

        override fun isConnected(): Boolean = connected

        override fun subscribe(topicFilters: Array<String>?, qos: IntArray?): IMqttToken? = null

        override fun publish(topic: String?, message: MqttMessage?): IMqttDeliveryToken? = null

        override fun close(force: Boolean) = Unit
    }

    private companion object {
        const val NOT_EXPORTED_PERMISSION = "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        const val RETRY_ACTION_PREFIX = "com.glance.action.MQTT_RECONNECT"
    }
}
