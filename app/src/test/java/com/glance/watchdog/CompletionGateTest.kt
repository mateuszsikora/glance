package com.glance.watchdog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionGateTest {
    @Test
    fun acceptsOnlyTheFirstHealthCheckResult() {
        val completion = CompletionGate()

        assertTrue(completion.tryComplete())
        assertFalse(completion.tryComplete())
    }
}
