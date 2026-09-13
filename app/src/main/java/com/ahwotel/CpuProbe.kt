package com.ahwotel

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

data class SchedTicks(val run: Long, val wait: Long, val slices: Long) {
    companion object {
        fun parse(text: String): SchedTicks {
            val parts = text.trim().split(Regex("\\s+")).map(String::toLong)
            require(parts.size == 3 && parts.all { it >= 0 })
            return SchedTicks(parts[0], parts[1], parts[2])
        }
    }
    fun waitMsSince(previous: SchedTicks): Double? =
        if (run < previous.run || wait < previous.wait || slices < previous.slices) null else (wait - previous.wait) / 1_000_000.0
}

object ProbeTiming {
    fun next(due: Long, now: Long, interval: Long) = due + ((now - due).coerceAtLeast(0) / interval + 1) * interval
    fun slept(previousUp: Long, up: Long, previousElapsed: Long, elapsed: Long) =
        (elapsed - previousElapsed) - (up - previousUp) > 50
}

data class ProbeReading(val sequence: Long, val time: Long, val elapsed: Long, val interval: Long?, val segment: Int,
    val waitMs: Double?, val delayMs: Double?, val tid: Int, val sources: List<SourceResult>)

/** A stable, ordinary-priority TID. No database, exporter or logging work runs here. */
class CpuProbe(private val intervalMs: Long, reader: ProcReader = AndroidProcReader) : AutoCloseable {
    private val thread = HandlerThread("ahwotel-cpu-probe", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    private val handler = Handler(thread.looper)
    private val access = ProcAccess(reader)
    private val latest = AtomicReference<ProbeReading?>(null)
    @Volatile private var closed = false
    private var paused = false // Handler thread only below.
    private var previous: SchedTicks? = null
    private var previousUp: Long? = null
    private var previousElapsed: Long? = null
    private var due = 0L
    private var segment = 0
    private var sequence = 0L
    private var consumed = 0L // Consumer only.
    private var consumedSourceSegment: Int? = null
    private var deliverySegment = 0

    private fun baseline() { previous = null; previousUp = null; previousElapsed = null; latest.set(null); segment++ }
    fun setPaused(value: Boolean) {
        handler.post {
            if (paused != value && !closed) {
                paused = value; handler.removeCallbacks(tick); baseline()
                if (!paused) { due = SystemClock.uptimeMillis(); tick.run() }
            }
        }
    }
    fun recheck() { handler.post {
        if (!closed) {
            access.reset(); baseline(); handler.removeCallbacks(tick)
            if (!paused) { due = SystemClock.uptimeMillis(); tick.run() }
        }
    } }
    fun snapshot(): ProbeReading? = latest.get()
    fun take(): ProbeReading? {
        val reading = latest.get()?.takeIf { it.sequence > consumed } ?: return null
        if (consumed > 0 && (reading.sequence != consumed + 1 || consumedSourceSegment != reading.segment)) deliverySegment++
        consumed = reading.sequence; consumedSourceSegment = reading.segment
        return reading.copy(segment = deliverySegment)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed || paused) return
            val up = SystemClock.uptimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val sleepGap = previousUp?.let { ProbeTiming.slept(it, up, previousElapsed!!, elapsed) } == true
            if (sleepGap) baseline()
            val window = previousUp?.let { up - it }
            val delay = if (window == null) null else (up - due).coerceAtLeast(0).toDouble()
            val tid = Process.myTid()
            var wait: Double? = null
            val waitSource = try {
                val current = access.read("/proc/self/task/$tid/schedstat", elapsed, SchedTicks::parse)
                wait = previous?.let { current.waitMsSince(it) }
                val reset = previous != null && wait == null
                if (reset) segment++
                previous = current
                SourceResult(Metric.CPU_WAIT, SourceId.THREAD_SCHEDSTAT,
                    if (wait == null) Availability.WARMING_UP else Availability.AVAILABLE,
                    when { reset -> SourceReason.COUNTER_RESET; sleepGap -> SourceReason.SLEEP_GAP
                        wait == null -> SourceReason.BASELINE; else -> SourceReason.NONE }, wall)
            } catch (e: SourceFailure) {
                previous = null
                SourceResult(Metric.CPU_WAIT, SourceId.THREAD_SCHEDSTAT, e.availability, e.reason, wall)
            }
            latest.set(ProbeReading(++sequence, wall, elapsed, window, segment, wait, delay, tid, listOf(waitSource,
                SourceResult(Metric.PROBE_DELAY, SourceId.MONOTONIC_TIMER,
                    if (delay == null) Availability.WARMING_UP else Availability.AVAILABLE,
                    when { sleepGap -> SourceReason.SLEEP_GAP; delay == null -> SourceReason.BASELINE; else -> SourceReason.NONE }, wall))))
            previousUp = up; previousElapsed = elapsed
            due = ProbeTiming.next(if (sleepGap) up else due, SystemClock.uptimeMillis(), intervalMs)
            handler.postAtTime(this, due)
        }
    }

    init { handler.post { due = SystemClock.uptimeMillis(); tick.run() } }

    override fun close() {
        closed = true; handler.removeCallbacksAndMessages(null); thread.quitSafely()
        if (Thread.currentThread() !== thread) thread.join(2000)
        latest.set(null)
    }
}

fun SampleRow.withProbe(reading: ProbeReading?, nowElapsed: Long, intervalMs: Long): SampleRow {
    if (reading == null || nowElapsed - reading.elapsed > intervalMs * 2 || nowElapsed < reading.elapsed) return this
    return copy(cpuWait = reading.waitMs, probeDelay = reading.delayMs, probeTime = reading.time,
        probeInterval = reading.interval, probeSegment = reading.segment)
}
