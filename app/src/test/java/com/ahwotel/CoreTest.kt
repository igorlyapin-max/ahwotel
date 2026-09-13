package com.ahwotel

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun defaultsAreLocalEnglishAndTwoWeeks() {
        val settings = Settings(deviceId = "test-device")
        assertTrue(settings.valid())
        assertEquals("en", settings.language)
        assertEquals(14, settings.retentionDays)
        assertFalse(settings.otlpEnabled)
        assertFalse(settings.sotiEnabled)
        assertTrue(settings.collectScreenOff)
    }
    @Test fun disabledOtlpDoesNotNeedAnEndpoint() {
        assertTrue(Settings(deviceId = "test", endpoint = "").valid())
        assertFalse(Settings(deviceId = "test", otlpEnabled = true).valid())
    }
    @Test fun validatesEndpointWithoutCredentialsOrRedirectParameters() {
        assertTrue(Settings.validEndpoint("https://example.org/v1/metrics"))
        listOf("http://example.org/v1/metrics", "https://user:secret@example.org/v1/metrics", "https://example.org/v1/metrics?token=x", "https://example.org/metrics", "https:///v1/metrics").forEach {
            assertFalse(it, Settings.validEndpoint(it))
        }
    }
    @Test fun thresholdsMustBeOrderedAndFinite() {
        assertFalse(Thresholds(cpu = listOf(90.0, 80.0, 95.0)).valid())
        assertFalse(Thresholds(memory = listOf(5.0, 10.0, 15.0)).valid())
        assertFalse(Thresholds(cpu = listOf(Double.NaN, 90.0, 95.0)).valid())
        assertTrue(Thresholds().valid())
    }
    @Test fun ignoresShortSpikeThenRaisesAndRecoversWithWindows() {
        val window = PressureWindow(Thresholds(raiseSeconds = 3, recoverSeconds = 6))
        assertEquals(Severity.NORMAL, window.update(Severity.CRITICAL, 0, 5000))
        assertEquals(Severity.NORMAL, window.update(Severity.NORMAL, 1000, 5000))
        assertEquals(Severity.NORMAL, window.update(Severity.CRITICAL, 2000, 5000))
        assertEquals(Severity.CRITICAL, window.update(Severity.CRITICAL, 5000, 5000))
        assertEquals(Severity.CRITICAL, window.update(Severity.NORMAL, 6000, 5000))
        assertEquals(Severity.CRITICAL, window.update(Severity.NORMAL, 9000, 5000))
        assertEquals(Severity.NORMAL, window.update(Severity.NORMAL, 12000, 5000))
    }
    @Test fun missingSamplesBreakPressureWindows() {
        val window = PressureWindow(Thresholds(raiseSeconds = 3))
        window.update(Severity.CRITICAL, 0, 3000)
        assertNull(window.update(null, 2000, 3000))
        assertEquals(Severity.NORMAL, window.update(Severity.CRITICAL, 3000, 3000))
        assertEquals(Severity.NORMAL, window.update(Severity.CRITICAL, 9000, 3000))
    }
    @Test fun cpuExcludesGuestAndIncludesIoWaitInIdle() {
        val ticks = CpuTicks.parse("cpu 100 20 30 400 50 10 5 5 90 10")
        assertEquals(620L, ticks.total)
        assertEquals(450L, ticks.idle)
        assertEquals(50.0, CpuTicks.utilization(ticks, CpuTicks(720, 500))!!, 0.001)
        assertNull(CpuTicks.utilization(ticks, CpuTicks(10, 5)))
    }
    @Test fun missingResourceIsNotNormalOrZero() {
        assertNull(Pressure.severity(null, Thresholds().cpu))
        assertEquals(Severity.CRITICAL, Pressure.severity(3.0, Thresholds().memory, true))
    }
    @Test fun startRejectsBadIntervalsAndExcessDuration() {
        val s = Settings(deviceId = "test")
        assertFalse(StartRequest("s", false, 300, 10, s.enabled).valid(s))
        assertFalse(StartRequest("s", false, 90000, 2000, s.enabled).valid(s))
        assertTrue(StartRequest("s", false, 300, 2000, emptySet()).valid(s))
        val empty=s.copy(batterySettings=BatterySettings(enabled=false),selfTelemetry=SelfTelemetrySettings(enabled=false))
        assertFalse(StartRequest("s", false, 300, 2000, emptySet()).valid(empty))
    }
    @Test fun csvNeutralizesSpreadsheetFormula() {
        assertEquals("\"'=cmd()\"", Exporter.csv("=cmd()"))
        assertEquals("\"a\"\"b\"", Exporter.csv("a\"b"))
    }
}
