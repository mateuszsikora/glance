package com.glance.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class MqttReconnectPolicyTest {
    @Test
    fun usesExponentialBackoffWithThirtySecondCap() {
        assertEquals(1_000L, MqttReconnectPolicy.delayForAttempt(0))
        assertEquals(2_000L, MqttReconnectPolicy.delayForAttempt(1))
        assertEquals(8_000L, MqttReconnectPolicy.delayForAttempt(3))
        assertEquals(30_000L, MqttReconnectPolicy.delayForAttempt(5))
        assertEquals(30_000L, MqttReconnectPolicy.delayForAttempt(100))
    }

    @Test
    fun retryAlarmBacksOffFromOneMinuteToFifteen() {
        assertEquals(60_000L, MqttReconnectPolicy.alarmDelayForAttempt(0))
        assertEquals(120_000L, MqttReconnectPolicy.alarmDelayForAttempt(1))
        assertEquals(480_000L, MqttReconnectPolicy.alarmDelayForAttempt(3))
        assertEquals(900_000L, MqttReconnectPolicy.alarmDelayForAttempt(4))
        assertEquals(900_000L, MqttReconnectPolicy.alarmDelayForAttempt(100))
    }
}
