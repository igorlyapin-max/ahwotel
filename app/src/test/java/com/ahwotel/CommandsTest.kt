package com.ahwotel

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CommandsTest {
    @Test fun explicitCommandOverridesOnlyAllowedSessionParameters() {
        val config = Settings(deviceId = "device", endpoint = "https://example.org/v1/metrics")
        val request = MonitoringService.parse(Intent(MonitoringService.START).putExtra("sessionId", "soti-1")
            .putExtra("samplingIntervalMs", 5000L).putExtra("durationSeconds", 30L)
            .putExtra("metrics", "memory,battery").putExtra("endpoint", "http://attacker.invalid/v1/metrics"), config)
        assertEquals(5000L, request.intervalMs)
        assertEquals(30L, request.durationSeconds)
        assertEquals(setOf(CollectorKind.MEMORY, CollectorKind.BATTERY), request.metrics)
        assertEquals("https://example.org/v1/metrics", config.endpoint)
    }
    @Test fun unknownOnlyCollectorListAndInvalidTimingAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MonitoringService.parse(Intent(MonitoringService.START).putExtra("metrics", "unknown"), Settings())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonitoringService.parse(Intent(MonitoringService.START).putExtra("samplingIntervalMs", 0L), Settings())
        }
    }
}
