package com.ahwotel

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class SourceId { PROC_STAT, THREAD_SCHEDSTAT, MONOTONIC_TIMER, ACTIVITY_MANAGER, STAT_FS, THERMAL_API, HEADROOM_API, BATTERY_BROADCAST, DERIVED }
enum class SourceReason { NONE, PERMISSION_DENIED, NOT_FOUND, API_UNAVAILABLE, INVALID_FORMAT, READ_FAILED, COUNTER_RESET, DISABLED, BASELINE, PAUSED, SLEEP_GAP, STALE_SAMPLE, MISSING_VALUE }
enum class SourceScope { DEVICE, PROBE_THREAD, APP_STORAGE, DERIVED }

data class SourceResult(val metric: Metric, val source: SourceId, val status: Availability,
    val reason: SourceReason = SourceReason.NONE, val checkedAt: Long = System.currentTimeMillis()) {
    val scope get() = when (source) {
        SourceId.THREAD_SCHEDSTAT, SourceId.MONOTONIC_TIMER -> SourceScope.PROBE_THREAD
        SourceId.STAT_FS -> SourceScope.APP_STORAGE
        SourceId.DERIVED -> SourceScope.DERIVED
        else -> SourceScope.DEVICE
    }
    fun sameState(other: SourceResult) = source == other.source && status == other.status && reason == other.reason
    fun json() = JSONObject().put("metric", metric.wire).put("source", source.name.lowercase())
        .put("scope", scope.name.lowercase()).put("status", status.name).put("reason", reason.name.lowercase()).put("checkedAt", checkedAt)
}

class SourceFailure(val reason: SourceReason, val availability: Availability) : Exception(reason.name)
fun interface ProcReader { fun read(path: String): String }

/** Bounded reads and errno classification, executed with the APK's own credentials. */
object AndroidProcReader : ProcReader {
    override fun read(path: String): String = try {
        val flags = OsConstants.O_RDONLY or if (android.os.Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0
        val fd = Os.open(path, flags, 0)
        try {
            val buffer = ByteArray(2048)
            val count = Os.read(fd, buffer, 0, buffer.size)
            String(buffer, 0, count.coerceAtLeast(0), Charsets.US_ASCII).lineSequence().first()
        } finally { Os.close(fd) }
    } catch (e: ErrnoException) {
        throw when (e.errno) {
            OsConstants.EACCES, OsConstants.EPERM -> SourceFailure(SourceReason.PERMISSION_DENIED, Availability.UNSUPPORTED)
            OsConstants.ENOENT, OsConstants.ENOTDIR -> SourceFailure(SourceReason.NOT_FOUND, Availability.UNSUPPORTED)
            else -> SourceFailure(SourceReason.READ_FAILED, Availability.ERROR)
        }
    } catch (_: SecurityException) { throw SourceFailure(SourceReason.PERMISSION_DENIED, Availability.UNSUPPORTED) }
}

/** Refusals are retried after 15 minutes or explicit recheck; transient errors after 30 seconds. */
class ProcAccess(private val reader: ProcReader = AndroidProcReader) {
    private var failure: SourceFailure? = null
    private var retryAt = 0L
    fun reset() { failure = null; retryAt = 0 }
    fun <T> read(path: String, elapsed: Long, parse: (String) -> T): T {
        failure?.let { if (elapsed < retryAt) throw it }
        try { return parse(reader.read(path)).also { failure = null } }
        catch (e: Exception) {
            val f = when(e) {
                is SourceFailure -> e
                is IllegalArgumentException -> SourceFailure(SourceReason.INVALID_FORMAT, Availability.ERROR)
                else -> SourceFailure(SourceReason.READ_FAILED, Availability.ERROR)
            }
            failure = f; retryAt = elapsed + if(f.availability==Availability.UNSUPPORTED) 900_000 else 30_000
            throw f
        }
    }
}

class SourceRegistry(private val logs: Diagnostics) {
    private val values = MutableStateFlow<Map<Metric, SourceResult>>(emptyMap())
    val reports = values.asStateFlow()
    private var fallback: SourceId? = null
    private val failed = mutableSetOf<Metric>()
    @Synchronized fun beginCheck() { values.value = emptyMap(); fallback = null }
    @Synchronized fun record(results: List<SourceResult>) {
        val next = values.value.toMutableMap()
        for (r in results) {
            val old = next[r.metric]
            if (r.status in setOf(Availability.ERROR, Availability.UNSUPPORTED)) failed.add(r.metric)
            if (old == null || !old.sameState(r)) {
                logs.event(if (old == null) "source_probe_result" else "capability_changed", source = r)
                if (r.status == Availability.AVAILABLE && failed.remove(r.metric))
                    logs.event("source_recovered", source = r)
            }
            next[r.metric] = r
        }
        values.value = next
        val cpu = next[Metric.CPU]
        val selected = if (cpu?.status in setOf(Availability.UNSUPPORTED, Availability.ERROR)) when {
            next[Metric.CPU_WAIT]?.status == Availability.AVAILABLE -> SourceId.THREAD_SCHEDSTAT
            next[Metric.PROBE_DELAY]?.status == Availability.AVAILABLE -> SourceId.MONOTONIC_TIMER
            else -> null
        } else null
        if (selected != null && selected != fallback) logs.event("fallback_selected", source = cpu, fallback = selected)
        fallback = selected
    }
    fun json(): String = JSONArray().apply { values.value.values.forEach { put(it.json()) } }.toString()
    /** Per-sample provenance without repeated field names/timestamps; ordered entries use stable enum names. */
    fun sampleJson(): String = JSONArray().apply {
        values.value.values.groupBy { Triple(it.source, it.status, it.reason) }.forEach { (key, rows) ->
            put(JSONArray().put(key.first.name).put(key.second.name).put(key.third.name)
                .put(JSONArray(rows.map { it.metric.name })))
        }
    }.toString()
}
