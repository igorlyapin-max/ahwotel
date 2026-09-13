package com.ahwotel

import org.junit.Assert.*
import org.junit.Test

class SessionTimingTest {
    private val settings = Settings(deviceId = "test", durationSeconds = 30)
    @Test fun continuousCancelsOldDeadlineWithoutChangingSessionStart() {
        val timer = SessionTiming("s", 10_000, false, 30)
        val updated = timer.changedSettings(settings, settings.copy(continuous = true))
        assertEquals("s", updated.sessionId)
        assertEquals(10_000L, updated.startedElapsedMs)
        assertNull(updated.remaining(100_000)); assertFalse(updated.expired(100_000))
        assertEquals(SessionMode.CONTINUOUS, updated.runtime(false, 100_000).mode)
    }
    @Test fun switchingBackCountsFromOriginalStartIncludingPauses() {
        val timer = SessionTiming("s", 10_000, true, 30)
        val updated = timer.changedSettings(settings.copy(continuous = true), settings)
        assertEquals(20L, updated.remaining(20_000))
        assertEquals(20L, updated.runtime(true, 20_000).remainingSeconds)
        assertTrue(updated.expired(40_000)); assertEquals(0L, updated.remaining(45_000))
    }
    @Test fun editsExtendOrShortenDeadlineWithoutRestartingClock() {
        val timer = SessionTiming("s", 10_000, false, 30)
        val shortened = timer.changedSettings(settings, settings.copy(durationSeconds = 5))
        assertTrue(shortened.expired(16_000))
        val extended = timer.changedSettings(settings, settings.copy(durationSeconds = 60))
        assertEquals(30L, extended.remaining(40_000))
        assertEquals(1L, extended.remaining(69_999)); assertTrue(extended.expired(70_000))
        assertEquals(extended, extended.changedSettings(settings.copy(durationSeconds = 60), settings.copy(durationSeconds = 60)))
    }
    @Test fun unrelatedSavesPreserveExplicitStartArgumentsAndOnlyChangedFieldsApply() {
        val timer = SessionTiming("remote", 10_000, true, 90)
        assertEquals(timer, timer.changedSettings(settings, settings.copy(language = "ru")))
        assertEquals(90L, timer.changedSettings(settings, settings.copy(continuous = true)).durationSeconds)
        assertTrue(timer.changedSettings(settings, settings.copy(durationSeconds = 40)).continuous)
    }
    @Test fun stopClaimIsTerminalAndCannotBeRevived() {
        val stopped = SessionTiming("s", 10_000, false, 30).stopped("timeout")
        assertEquals(stopped, stopped.changedSettings(settings, settings.copy(continuous = true, durationSeconds = 100)))
        assertEquals("timeout", stopped.stopped("manual_stop").stopReason)
        assertFalse(stopped.canRecord("s", 11_000))
        val running = SessionTiming("s", 10_000, false, 30)
        assertTrue(running.canRecord("s", 39_999))
        assertFalse(running.canRecord("s", 40_000))
        assertFalse(running.canRecord("old-session", 20_000))
    }
}
