package com.ahwotel

import org.junit.Assert.*
import org.junit.Test

class CpuSourcesTest {
    @Test fun schedstatUsesWaitCounterAndAllowsZeroWait() {
        val before = SchedTicks.parse("1000000 2000000 3")
        assertEquals(5.0, SchedTicks.parse("2000000 7000000 4").waitMsSince(before)!!, 0.0)
        assertEquals(0.0, SchedTicks.parse("2000000 2000000 4").waitMsSince(before)!!, 0.0)
        assertNull(SchedTicks(0, 10, 1).waitMsSince(before))
        listOf("1 2", "1 2 3 4", "1 -1 3", "bad").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SchedTicks.parse(value) }
        }
    }
    @Test fun absoluteDeadlinesSkipMissedIntervalsAndSleepIsSeparate() {
        assertEquals(3000L, ProbeTiming.next(1000, 1300, 2000))
        assertEquals(7000L, ProbeTiming.next(1000, 6400, 2000))
        assertTrue(ProbeTiming.slept(100, 2100, 100, 6100))
        assertFalse(ProbeTiming.slept(100, 2100, 100, 2101))
    }
    @Test fun permanentPermissionFailureIsCachedUntilExplicitRecheck() {
        var calls = 0
        var allowed = false
        val access = ProcAccess(ProcReader {
            calls++
            if (!allowed) throw SourceFailure(SourceReason.PERMISSION_DENIED, Availability.UNSUPPORTED)
            "cpu 100 0 0 100"
        })
        repeat(3) { n -> assertThrows(SourceFailure::class.java) { access.read("/proc/stat", n * 40000L, CpuTicks::parse) } }
        assertEquals(1, calls)
        access.reset(); allowed = true
        assertEquals(200L, access.read("/proc/stat", 120000, CpuTicks::parse).total)
        assertEquals(2, calls)
    }
    @Test fun invalidFormatIsErrorAndRecoveryWaitsThirtySeconds() {
        var calls = 0
        val access = ProcAccess(ProcReader { calls++; if (calls == 1) "bad" else "cpu 100 0 0 100" })
        val error = assertThrows(SourceFailure::class.java) { access.read("/proc/stat", 0, CpuTicks::parse) }
        assertEquals(Availability.ERROR, error.availability)
        assertEquals(SourceReason.INVALID_FORMAT, error.reason)
        assertThrows(SourceFailure::class.java) { access.read("/proc/stat", 29000, CpuTicks::parse) }
        assertEquals(1, calls)
        assertEquals(200L, access.read("/proc/stat", 30000, CpuTicks::parse).total)
    }
    @Test fun staleProbeIsMissingAndIndirectSignalsCannotChangeCpuOrPerformanceState() {
        val row = SampleRow(sessionId = "s", time = 100, elapsed = 100, segment = 0, state = 0.0, cpuPressure = null, screenOn = true)
        val probe = ProbeReading(1, 99, 99, 2000, 0, 999.0, 5000.0, 1, emptyList())
        val current = row.withProbe(probe, 100, 2000)
        assertNull(current.cpu); assertNull(current.cpuPressure); assertEquals(0.0, current.state!!, 0.0)
        assertEquals(999.0, current.cpuWait!!, 0.0)
        assertNull(row.withProbe(probe, 5000, 2000).cpuWait)
        assertNull(row.withProbe(null, 100, 2000).probeDelay)
    }
}
