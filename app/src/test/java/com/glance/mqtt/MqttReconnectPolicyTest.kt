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
}
