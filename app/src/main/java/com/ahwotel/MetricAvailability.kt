package com.ahwotel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

fun permanentlyUnavailable(status: String) = status in setOf("UNSUPPORTED","PERMISSION_DENIED")
fun shouldFold(statuses: List<String>, hasValue: Boolean) =
    !hasValue && statuses.isNotEmpty() && statuses.all(::permanentlyUnavailable)
fun shouldFoldLive(enabled: Boolean, recordedStatuses: List<String>, checkStatuses: List<String>,
    hasRecordedValue: Boolean) = !enabled || shouldFold(recordedStatuses+checkStatuses,hasRecordedValue)

fun Metric.category(): String = when {
    name.startsWith("CPU") || this==Metric.PROBE_DELAY -> "CPU"
    name.startsWith("STORAGE") -> "STORAGE"
    this in listOf(Metric.BATTERY,Metric.TEMPERATURE,Metric.CHARGING) -> "BATTERY"
    else -> name
}
fun Metric.capabilityKey() = when(category()) {
    "CPU" -> if(this==Metric.CPU_WAIT) "CPU_WAIT" else if(this==Metric.PROBE_DELAY) "PROBE_DELAY" else "CPU"
    "STORAGE" -> "STORAGE"
    "BATTERY" -> "BATTERY"
    else -> if(name.startsWith("MEMORY")) "MEMORY" else if(this==Metric.HEADROOM) "HEADROOM" else if(this==Metric.STATE) "STATE" else "THERMAL"
}
fun Metric.capability(sample: SampleRow?) = sample?.capabilities?.split(';')
    ?.find { it.startsWith(capabilityKey()+"=") }?.substringAfter('=') ?: "NO_DATA"

@Composable fun availabilityCategory(key: String): String = when(key) {
    "CPU" -> stringResource(R.string.cpu)
    "STORAGE" -> stringResource(R.string.storage)
    "MEMORY" -> stringResource(R.string.memory)
    "NETWORK" -> stringResource(R.string.at_network)
    "RUNTIME" -> stringResource(R.string.at_runtime)
    "TASKS" -> stringResource(R.string.at_tasks)
    "BATTERY","WEAR","PASSPORT" -> stringResource(R.string.battery)
    else -> Metric.entries.firstOrNull { it.name==key }?.let { metricTitle(it) } ?: key
}

/** Disclosure is presentation only: observations, export and diagnostics remain complete. */
@Composable fun UnavailableSection(id: String, count: Int, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val label=stringResource(R.string.ext_unavailable,count)
    val state=stringResource(if(expanded) R.string.ext_expanded else R.string.ext_collapsed)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton({ expanded=!expanded },Modifier.fillMaxWidth().testTag("unavailable_$id")
            .semantics { stateDescription=state }) {
            Text((if(expanded) "⌄ " else "› ")+label,Modifier.weight(1f))
        }
        if(expanded) content()
    }
}
fun AgentMetric.enabled(s: Settings): Boolean {
    if(battery) {
        val b=s.batterySettings
        val groupEnabled=when(group) { "WEAR" -> b.wear; "PASSPORT" -> b.passport; else -> b.current }
        val extended=this in setOf(AgentMetric.CURRENT_AVERAGE,AgentMetric.ENERGY,AgentMetric.CHARGE_TIME,AgentMetric.CHARGING_DETAIL)
        return b.enabled && groupEnabled && (!extended || b.extended)
    }
    val self=s.selfTelemetry
    return self.enabled && group in self.groups.map { it.name } &&
        (!name.startsWith("IO_") || self.processIo) &&
        (!name.startsWith("SYSTEM_") || self.systemIo) &&
        (!name.startsWith("FLASH_") || self.flashWear)
}

@Composable fun RecordedSource(metric: Metric, sample: SampleRow?) {
    val info=remember(metric,sample?.sources) {
        runCatching {
            val array=org.json.JSONArray(sample?.sources ?: "[]")
            (0 until array.length()).map { array.getJSONArray(it) }.firstOrNull { row ->
                val metrics=row.getJSONArray(3)
                (0 until metrics.length()).any { metrics.getString(it)==metric.name }
            }?.let { row -> org.json.JSONObject().put("reason",row.getString(2)).put("source",row.getString(0)).put("checkedAt",sample?.time ?: 0) }
        }.getOrNull()
    }
    if(info!=null) {
        val reason=runCatching { SourceReason.valueOf(info.optString("reason").uppercase()) }.getOrNull()
        if(reason!=null && reason!=SourceReason.NONE) Text(stringResource(sourceReasonHelp(reason)))
        Text(info.optString("source")+" · "+formatTime(info.optLong("checkedAt",sample?.time ?: 0)),style=MaterialTheme.typography.bodySmall)
    }
}
