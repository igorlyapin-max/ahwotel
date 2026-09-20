package com.ahwotel

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay

/** Presentation only. Never write held values back to history or export them as new samples. */
data class MetricReading(val value: Double? = null, val time: Long? = null,
    val status: String = "WARMING_UP", val source: String = "", val reason: String = "NONE")

fun Metric.batteryMetric(): AgentMetric? = when(this) {
    Metric.BATTERY -> AgentMetric.LEVEL
    Metric.TEMPERATURE -> AgentMetric.TEMP
    Metric.CHARGING -> AgentMetric.CHARGING
    else -> null
}
fun Metric.enabled(s: Settings): Boolean = batteryMetric()?.enabled(s) ?: when {
    isProbe -> CollectorKind.CPU in s.enabled && s.indirectCpu
    this == Metric.STATE -> s.enabled.any { it != CollectorKind.BATTERY }
    else -> capabilityKey().let { key -> CollectorKind.entries.any { it.name == key && it in s.enabled } ||
        key == "HEADROOM" && CollectorKind.THERMAL in s.enabled }
}
fun Metric.intervalMs(s: Settings): Long = when {
    batteryMetric()!=null -> s.batterySettings.currentSeconds*1000L
    this==Metric.HEADROOM -> maxOf(10000L,s.intervalMs)
    else -> s.intervalMs
}
fun AgentMetric.intervalMs(s: Settings): Long = when {
    group=="WEAR" -> s.batterySettings.wearSeconds*1000L
    group=="PASSPORT" -> s.batterySettings.passportSeconds*1000L
    battery -> s.batterySettings.currentSeconds*1000L
    name.startsWith("IO_") || name.startsWith("SYSTEM_") || name.startsWith("FLASH_") -> s.selfTelemetry.ioSeconds*1000L
    this in setOf(AgentMetric.DB_SIZE,AgentMetric.SELF_BYTES,AgentMetric.BUFFER,AgentMetric.PENDING) ->
        maxOf(s.selfTelemetry.slowSeconds,s.selfTelemetry.windowSeconds)*1000L
    this in setOf(AgentMetric.TX,AgentMetric.RX,AgentMetric.TX_DELTA,AgentMetric.RX_DELTA) ->
        maxOf(s.selfTelemetry.mediumSeconds,s.selfTelemetry.windowSeconds)*1000L
    this in setOf(AgentMetric.CPU_TIME,AgentMetric.CPU_DELTA,AgentMetric.CPU_PERCENT,AgentMetric.CPU_MINUTE,
        AgentMetric.CPU_HOUR,AgentMetric.SELF_OVERHEAD,AgentMetric.PSS,AgentMetric.RSS,AgentMetric.JAVA_USED,
        AgentMetric.JAVA_MAX,AgentMetric.NATIVE,AgentMetric.GC_COUNT,AgentMetric.GC_TIME,AgentMetric.OOM) ->
        maxOf(s.selfTelemetry.fastSeconds,s.selfTelemetry.windowSeconds)*1000L
    else -> s.selfTelemetry.windowSeconds*1000L
}
fun AgentMetric.staleAfterMs(s: Settings, status: String?): Long = when {
    name.startsWith("FLASH_") && status == "AVAILABLE" -> 26 * 3_600_000L
    name.startsWith("FLASH_") -> 30 * 60_000L
    else -> intervalMs(s) * 2
}
fun AgentMetric.acceptsStream(stream: String): Boolean = stream==when(group) {
    "BATTERY" -> "battery"
    "WEAR","PASSPORT" -> "wear"
    else -> "self"
}
fun TelemetryRecord.reading() = MetricReading(value?.takeIf { status=="AVAILABLE" && it.isFinite() },time,status,source,reason)
fun Metric.reading(sample: SampleRow?, headroom: SampleRow?, battery: List<TelemetryRecord>): MetricReading {
    batteryMetric()?.let { m -> return battery.lastOrNull { it.stream=="battery" && it.metric==m.name }?.reading() ?: MetricReading() }
    val row = if(this==Metric.HEADROOM) headroom ?: sample else sample
    if(row==null) return MetricReading()
    val info=runCatching {
        val entries=org.json.JSONArray(row.sources ?: "[]")
        (0 until entries.length()).map { entries.getJSONArray(it) }.firstOrNull { entry ->
            val metrics=entry.getJSONArray(3)
            (0 until metrics.length()).any { metrics.getString(it)==name }
        }
    }.getOrNull()
    val value=value(row)?.takeIf { it.isFinite() }
    val status=when {
        value!=null -> "AVAILABLE"
        this==Metric.STATE -> "UNAVAILABLE"
        else -> capability(row).let { if(it=="AVAILABLE" || it=="NO_DATA") "WARMING_UP" else it }
    }
    val time=when { isProbe -> row.probeTime; this==Metric.HEADROOM -> row.headroomTime; else -> row.time }
    return MetricReading(value,time,status,info?.optString(0).orEmpty(),info?.optString(2)?.uppercase() ?: "NONE")
}
fun readingStale(time: Long?, now: Long, intervalMs: Long): Boolean =
    time!=null && (now<time || now-time>intervalMs*2)

@Composable fun measurementClock(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while(true) { value=System.currentTimeMillis(); delay(1000) }
    }
    return now
}
@Composable fun ReadingLifecycle(time: Long?, now: Long, intervalMs: Long, enabled: Boolean, state: RuntimeState,
    staleAfterMs: Long = intervalMs * 2) {
    if(!enabled) Text(stringResource(R.string.disabled))
    else {
        if(state.paused) Text(stringResource(R.string.paused))
        else if(state.sessionId==null && time!=null) Text(stringResource(R.string.reading_session_finished))
        if(time!=null && (now<time || now-time>staleAfterMs)) Text(stringResource(R.string.ext_stale))
    }
}

@Composable fun metricReadingReason(metric: Metric, reason: String): String {
    val core=if(metric.batteryMetric()==null) runCatching { SourceReason.valueOf(reason) }.getOrNull() else null
    return if(core!=null) stringResource(sourceReasonHelp(core)) else agentReason(reason)
}
