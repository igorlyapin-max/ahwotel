package com.ahwotel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStream

@Composable private fun IntField(label: Int, value: Int, change: (Int)->Unit) {
    Field(stringResource(label), value.takeIf { it>=0 }?.toString().orEmpty()) { change(it.toIntOrNull() ?: -1) }
}
@Composable fun BatterySettingsPanel(draft: Settings, change: (Settings)->Unit) {
    val b=draft.batterySettings
    fun set(v: BatterySettings)=change(draft.copy(batterySettings=v))
    Panel(stringResource(R.string.at_battery_title)) {
        Toggle(stringResource(R.string.at_enable),b.enabled) { set(b.copy(enabled=it)) }
        Text(stringResource(R.string.at_battery_hint))
        Toggle(stringResource(R.string.at_current),b.current) { set(b.copy(current=it)) }
        IntField(R.string.at_current_interval,b.currentSeconds) { set(b.copy(currentSeconds=it)) }
        Toggle(stringResource(R.string.at_wear),b.wear) { set(b.copy(wear=it)) }
        IntField(R.string.at_wear_interval,b.wearSeconds) { set(b.copy(wearSeconds=it)) }
        Toggle(stringResource(R.string.at_passport),b.passport) { set(b.copy(passport=it)) }
        IntField(R.string.at_passport_interval,b.passportSeconds) { set(b.copy(passportSeconds=it)) }
        Toggle(stringResource(R.string.at_fallback),b.fallback) { set(b.copy(fallback=it)) }
        IntField(R.string.at_upload_interval,b.uploadSeconds) { set(b.copy(uploadSeconds=it)) }
        IntField(R.string.at_wear_upload_interval,b.wearUploadSeconds) { set(b.copy(wearUploadSeconds=it)) }
        Text(stringResource(R.string.at_upload_hint),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable fun SelfSettingsPanel(draft: Settings, change: (Settings)->Unit) {
    val s=draft.selfTelemetry
    fun set(v: SelfTelemetrySettings)=change(draft.copy(selfTelemetry=v))
    Panel(stringResource(R.string.at_self_title)) {
        Toggle(stringResource(R.string.at_enable),s.enabled) { set(s.copy(enabled=it)) }
        Text(stringResource(R.string.at_self_hint))
        SelfGroup.entries.forEach { group->
            Toggle(selfGroupTitle(group),group in s.groups) { set(s.copy(groups=if(it) s.groups+group else s.groups-group)) }
        }
        IntField(R.string.at_fast_interval,s.fastSeconds) { set(s.copy(fastSeconds=it)) }
        IntField(R.string.at_medium_interval,s.mediumSeconds) { set(s.copy(mediumSeconds=it)) }
        IntField(R.string.at_slow_interval,s.slowSeconds) { set(s.copy(slowSeconds=it)) }
        IntField(R.string.at_window_interval,s.windowSeconds) { set(s.copy(windowSeconds=it)) }
        IntField(R.string.at_upload_interval,s.uploadSeconds) { set(s.copy(uploadSeconds=it)) }
        IntField(R.string.at_limit,s.limitMiB) { set(s.copy(limitMiB=it)) }
        IntField(R.string.at_warning,s.warningSeconds) { set(s.copy(warningSeconds=it)) }
        IntField(R.string.at_critical,s.criticalSeconds) { set(s.copy(criticalSeconds=it)) }
        Toggle(stringResource(R.string.at_debug),s.debugUntil>System.currentTimeMillis()) { set(s.copy(debugUntil=if(it) System.currentTimeMillis()+900_000 else 0)) }
        Text(stringResource(R.string.at_self_limits),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.at_upload_hint),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun selfGroupTitle(g: SelfGroup)=stringResource(when(g) {
    SelfGroup.CPU->R.string.cpu; SelfGroup.MEMORY->R.string.memory; SelfGroup.BATTERY->R.string.at_battery_context
    SelfGroup.NETWORK->R.string.at_network; SelfGroup.RUNTIME->R.string.at_runtime; SelfGroup.STORAGE->R.string.storage
    SelfGroup.TASKS->R.string.at_tasks
})
@Composable fun AgentTelemetryScreen(app: MonitorApp, battery: Boolean, back: ()->Unit) {
    androidx.activity.compose.BackHandler { back() }
    val flow=remember { app.db.agentDao().latest() }
    val latest by flow.collectAsStateWithLifecycle(initialValue=emptyList())
    val all=AgentMetric.entries.filter { it.battery==battery }
    var metric by remember { mutableStateOf(all.first()) }
    var expanded by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<AgentMetric?>(null) }
    var range by remember { mutableLongStateOf(86_400_000) }
    val emptyHistoryTime=remember { System.currentTimeMillis() }
    val now=latest.maxOfOrNull { it.time } ?: emptyHistoryTime
    val chartRequest=Triple(metric,range,now)
    var loadedChart by remember { mutableStateOf<Pair<Triple<AgentMetric,Long,Long>,List<AgentChartSeries>>?>(null) }
    val points=loadedChart?.takeIf { it.first==chartRequest }?.second.orEmpty()
    var exportMessage by remember { mutableStateOf<Int?>(null) }
    var pendingExport by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope(); val context=LocalContext.current
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri->
        val savedRequest=pendingExport; pendingExport=null
        if(uri!=null) scope.launch { exportMessage=try {
            val request=PendingExport.decode(requireNotNull(savedRequest))
            withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { AgentHistoryExport.write(app,it,request.spec.json,request.kind=="battery") } ?: error("output_unavailable")
        }; R.string.export_done } catch (_: Exception) { R.string.export_failed } }
    }
    LaunchedEffect(chartRequest) {
        val result=withContext(Dispatchers.IO) { loadAgentChart(app.db.agentDao(),metric,now-range,now) }
        loadedChart=chartRequest to result
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item { OutlinedButton(back) { Text(stringResource(R.string.at_back)) }; PageTitle(stringResource(if(battery) R.string.at_battery_title else R.string.at_self_title)) }
        item { Text(stringResource(R.string.at_history_hint)) }
        item { Box { OutlinedButton({ expanded=true },Modifier.testTag("agent_metric_selector")) { Text(stringResource(metric.title)) }
            DropdownMenu(expanded,{expanded=false}) { all.forEach { m->DropdownMenuItem(text={Text(stringResource(m.title))},onClick={metric=m;expanded=false}) } }
        } }
        item { Panel(stringResource(metric.title)) {
            TextButton({help=metric},Modifier.testTag("agent_metric_help")) { Text(stringResource(R.string.at_help)) }
            val values=latest.filter { it.metric==metric.name }
            if(values.isEmpty()) Text(stringResource(R.string.no_data))
            values.forEach { r->
                Text((if(metric.aggregation==MetricAggregation.STATE && r.value!=null) agentStateName(metric,r.value.toInt())
                    else r.value?.let(::formatNumber) ?: r.text ?: "—")+" "+metric.unit,style=MaterialTheme.typography.headlineSmall)
                if(r.component.isNotEmpty()) Text(r.component)
                Text("${formatTime(r.time)} · ${r.source}",style=MaterialTheme.typography.bodySmall)
                Text(agentStatus(r.status)+" · "+agentReason(r.reason))
                Text(qualityText(r.quality),style=MaterialTheme.typography.bodySmall)
                if(r.count>1) Text(stringResource(R.string.at_summary,r.count,formatNumber(r.low ?: 0.0),formatNumber(r.high ?: 0.0),formatNumber(r.p95 ?: 0.0)))
            }
            Choice(range,listOf(300_000L to stringResource(R.string.at_five_minutes),3_600_000L to stringResource(R.string.hour),86_400_000L to stringResource(R.string.day),604_800_000L to stringResource(R.string.week),1_209_600_000L to stringResource(R.string.two_weeks))) {range=it}
            if(loadedChart?.first!=chartRequest) Text(stringResource(R.string.loading))
            // Components are separate series. Never join collector costs into one line.
            points.forEach { series ->
                val component=series.component
                if(component.isNotEmpty()) Text(component)
                val stateLabels=if(metric.aggregation==MetricAggregation.STATE) series.points.mapNotNull { it.mean?.toInt() }.distinct()
                    .associateWith { agentStateName(metric,it) } else emptyMap()
                MetricPlot(stringResource(metric.title),series.points,now-range,now,metric.unit,
                    fixedMaximum=if(metric==AgentMetric.LEVEL) 100.0 else null,
                    aggregation=metric.aggregation, nominalStates=stateLabels,mixedBuckets=series.mixed,gapBuckets=series.gaps)
            }
        } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton({pendingExport=PendingExport(if(battery) "battery" else "self",ExportSpec(null,0,Long.MAX_VALUE,true)).encode();export.launch(if(battery) "battery.json" else "self-telemetry.json")}) {Text(stringResource(R.string.export_json))}
            OutlinedButton({pendingExport=PendingExport(if(battery) "battery" else "self",ExportSpec(null,0,Long.MAX_VALUE,false)).encode();export.launch(if(battery) "battery.csv" else "self-telemetry.csv")}) {Text(stringResource(R.string.export_csv))}
        };exportMessage?.let { Text(stringResource(it)) } }
    }
    help?.let { m->AlertDialog(onDismissRequest={help=null},title={Text(stringResource(m.title))},
        text={Text(stringResource(m.help),Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton({help=null}) {Text(stringResource(R.string.at_close))}}) }
}
@Composable internal fun agentStateName(metric: AgentMetric, value: Int): String {
    val resource = when(metric) {
        AgentMetric.HEALTH -> mapOf(2 to R.string.at_health_good,3 to R.string.at_health_hot,4 to R.string.at_health_dead,
            5 to R.string.at_health_voltage,6 to R.string.at_health_failure,7 to R.string.at_health_cold)[value]
        AgentMetric.STATUS -> mapOf(2 to R.string.at_status_charging,3 to R.string.at_status_discharging,
            4 to R.string.at_status_idle,5 to R.string.at_status_full)[value]
        AgentMetric.PLUG -> mapOf(0 to R.string.at_plug_none,1 to R.string.at_plug_ac,2 to R.string.at_plug_usb,4 to R.string.at_plug_wireless)[value]
        AgentMetric.CHARGING,AgentMetric.POWER_SAVE -> mapOf(0 to R.string.at_state_no,1 to R.string.at_state_yes)[value]
        else -> null
    }
    return if(resource!=null) stringResource(resource) else stringResource(R.string.at_state_unknown,value)
}
@Composable private fun agentStatus(s: String)=stringResource(when(s) {
    "AVAILABLE"->R.string.available; "UNSUPPORTED"->R.string.unsupported; "DISABLED"->R.string.disabled
    "PERMISSION_DENIED"->R.string.at_permission; "ERROR"->R.string.error; else->R.string.unavailable
})
@Composable private fun qualityText(s: String)=stringResource(when(s) {"MEASURED"->R.string.at_measured;"ESTIMATED"->R.string.at_estimated;else->R.string.at_incomplete})
@Composable private fun agentReason(s: String)=stringResource(when(s) {
    "NONE"->R.string.at_ok; "OPERATION_FAILED"->R.string.at_operation_failed; "PERMISSION_DENIED"->R.string.at_permission; "FALLBACK_DISABLED"->R.string.at_fallback_off
    "UNVERIFIED_UNITS"->R.string.at_units_unknown; "INVALID_VALUE"->R.string.at_invalid
    "EXTERNAL_POWER"->R.string.at_power; "STABILIZING"->R.string.at_stabilizing
    "BASELINE","INSUFFICIENT_WINDOW"->R.string.at_baseline; "COUNTER_RESET"->R.string.at_reset
    else->R.string.at_no_source
})
object AgentHistoryExport {
    fun json(r: TelemetryRecord)=JSONObject().put("session.id",r.sessionId).put("stream",r.stream).put("metric",AgentMetric.valueOf(r.metric).wire)
        .put("unit",AgentMetric.valueOf(r.metric).unit).put("component",r.component).put("timestamp",r.time).put("start",r.start)
        .put("duration_ms",r.durationMs).put("segment",r.segment).put("value",r.value ?: JSONObject.NULL).put("min",r.low ?: JSONObject.NULL)
        .put("max",r.high ?: JSONObject.NULL).put("sum",r.sum ?: JSONObject.NULL).put("count",r.count).put("p50",r.p50 ?: JSONObject.NULL)
        .put("p95",r.p95 ?: JSONObject.NULL).put("text",r.text ?: JSONObject.NULL).put("status",r.status).put("reason",r.reason)
        .put("source",r.source).put("quality",r.quality).put("resource",JSONObject(r.metadata))
    suspend fun write(app: MonitorApp,out: OutputStream,json: Boolean,battery: Boolean) {
        out.bufferedWriter().use { w->
            if(json) w.write("{\"version\":1,\"records\":[") else w.write("metric,component,timestamp,value,unit,status,reason,source,quality,record_json\n")
            var after=0L; var first=true
            while(true) {
                val rows=app.db.agentDao().page(after,0,Long.MAX_VALUE); if(rows.isEmpty()) break
                for(r in rows) { after=r.id; if(AgentMetric.valueOf(r.metric).battery!=battery) continue
                    val obj=json(r)
                    if(json) { if(!first) w.write(","); w.write(obj.toString()) }
                    else w.write(listOf(AgentMetric.valueOf(r.metric).wire,r.component,r.time.toString(),r.value?.toString().orEmpty(),AgentMetric.valueOf(r.metric).unit,r.status,r.reason,r.source,r.quality,obj.toString()).joinToString(",",transform=Exporter::csv)+"\n")
                    first=false
                }
            }
            if(json) w.write("]}")
        }
    }
}
