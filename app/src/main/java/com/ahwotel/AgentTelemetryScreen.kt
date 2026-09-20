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
        Toggle(stringResource(R.string.ext_battery),b.extended) { set(b.copy(extended=it)) }
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
        Toggle(stringResource(R.string.ext_process_io),s.processIo) { set(s.copy(processIo=it)) }
        Toggle(stringResource(R.string.ext_system_io),s.systemIo) { set(s.copy(systemIo=it)) }
        Toggle(stringResource(R.string.ext_flash),s.flashWear) { set(s.copy(flashWear=it)) }
        IntField(R.string.ext_io_interval,s.ioSeconds) { set(s.copy(ioSeconds=it)) }
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
private data class AgentChartRequest(val metric: AgentMetric,val range: Long,val end: Long,val session: String?,val revision: Long)

@Composable fun AgentTelemetryScreen(app: MonitorApp, battery: Boolean, history: Boolean = false, session: String? = null,
    historyEnd: Long? = null, historyWindow: HistoryWindowState? = null, groupFilter: String? = null, back: ()->Unit) {
    androidx.activity.compose.BackHandler { back() }
    val probeRows by app.agentTelemetry.probes.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val active by app.state.collectAsStateWithLifecycle()
    val sessions by remember { app.db.dao().sessions() }.collectAsStateWithLifecycle(initialValue=emptyList())
    val liveSession=active.sessionId ?: sessions.firstOrNull()?.id ?: ""
    val liveLatest by remember(liveSession) { app.db.agentDao().latestInSession(liveSession) }.collectAsStateWithLifecycle(initialValue=emptyList())
    val clock=measurementClock()
    val all=AgentMetric.entries.filter { it.battery==battery && (groupFilter==null || it.group==groupFilter) }
    var metric by remember { mutableStateOf(all.first()) }
    var expanded by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<AgentMetric?>(null) }
    val localWindow=remember(historyEnd) { HistoryWindowState(initialEnd=historyEnd ?: System.currentTimeMillis(),initialAtLatest=historyEnd==null) }
    val window=historyWindow ?: localWindow
    val range=window.range
    val now=if(history) window.end else maxOf(liveLatest.filter { it.sessionId==liveSession }.maxOfOrNull { it.time } ?: 0,remember(liveSession) { System.currentTimeMillis() })
    val querySession=if(history) session else liveSession
    val request=Triple(now,range,querySession)
    val rangeData by produceState<Pair<Triple<Long,Long,String?>,Pair<List<MetricPresence>,List<TelemetryRecord>>>?>(null,request,liveLatest,history) {
        value=withContext(Dispatchers.IO) { request to (app.db.agentDao().presence(now-range,now,querySession) to
            app.db.agentDao().rangeLatest(now-range,now,querySession)) }
    }
    val loaded=rangeData?.takeIf { it.first==request }?.second
    val latest=(if(history) loaded?.second.orEmpty() else liveLatest.filter { it.sessionId==liveSession })
        .filter { AgentMetric.valueOf(it.metric).acceptsStream(it.stream) }
    val folded=all.filter { m ->
        if(!history && !m.enabled(settings)) false
        else if(history) loaded?.first?.find { it.metric==m.name }?.let { it.valid==0 && it.refusals==it.total }==true
        else shouldFold(latest.filter { it.metric==m.name }.map { it.status },latest.any { it.metric==m.name && it.status=="AVAILABLE" && (it.value!=null || it.text!=null) })
    }
    var explicitlySelected by remember { mutableStateOf(false) }
    val summary by produceState<BatteryDaySummary?>(null,now,querySession,battery) {
        if(battery) value=withContext(Dispatchers.IO) { summarizeBattery(app.db.agentDao().batterySummaryRows(now-86_400_000,now,querySession),settings.batterySettings.currentSeconds*2000L) }
    }
    val chartRequest=AgentChartRequest(metric,range,now,querySession,liveLatest.maxOfOrNull { it.id } ?: 0)
    var loadedChart by remember { mutableStateOf<Pair<AgentChartRequest,List<AgentChartSeries>>?>(null) }
    val points=loadedChart?.takeIf { it.first==chartRequest }?.second.orEmpty()
    var exportMessage by remember { mutableStateOf<Int?>(null) }
    var pendingExport by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope(); val context=LocalContext.current
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri->
        val savedRequest=pendingExport; pendingExport=null
        if(uri!=null) scope.launch { exportMessage=try {
            val request=PendingExport.decode(requireNotNull(savedRequest))
            withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { AgentHistoryExport.write(app,it,request.spec.json,request.kind=="battery",request.spec) } ?: error("output_unavailable")
        }; R.string.export_done } catch (_: Exception) { R.string.export_failed } }
    }
    LaunchedEffect(chartRequest,querySession,liveLatest) {
        val result=withContext(Dispatchers.IO) { loadAgentChart(app.db.agentDao(),metric,now-range,now,querySession) }
        loadedChart=chartRequest to result
    }
    LazyColumn(Modifier.fillMaxSize().testTag("agent_list").padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item { OutlinedButton(back) { Text(stringResource(R.string.at_back)) }; PageTitle(stringResource(if(groupFilter=="STORAGE") R.string.storage else if(battery) R.string.at_battery_title else R.string.at_self_title)) }
        item { Text(stringResource(R.string.at_history_hint)) }
        if(!history) item { OutlinedButton({ scope.launch { app.checkSources() } }) { Text(stringResource(R.string.ext_recheck)) } }
        if(battery && summary!=null) item {
            val day=summary!!
            Panel(stringResource(R.string.ext_summary)) {
                fun span(low: Double?,high: Double?)=if(low==null || high==null) "—" else formatNumber(low)+"–"+formatNumber(high)
                Text(stringResource(R.string.ext_summary_values,span(day.minTemp,day.maxTemp),span(day.minLevel,day.maxLevel)))
                Text(stringResource(R.string.ext_summary_time,formatNumber(day.observedMs/3600000.0),formatNumber(day.powerMs/3600000.0),formatNumber(day.dischargeMs/3600000.0)))
                Text(stringResource(R.string.ext_summary_hint),style=MaterialTheme.typography.bodySmall)
            }
        }
        folded.groupBy { if(it.battery) "BATTERY" else it.group }.forEach { (group,metrics) ->
            item { UnavailableSection("agent_"+group,availabilityCategory(group),metrics.size) {
                metrics.forEach { m ->
                    TextButton({ metric=m; explicitlySelected=true }) { Text(stringResource(m.title)) }
                    latest.filter { it.metric==m.name }.forEach { r ->
                        Text(agentStatus(r.status)+" · "+agentReason(r.reason))
                        Text(r.source+" · "+formatTime(r.time),style=MaterialTheme.typography.bodySmall)
                    }
                    TextButton({help=m}) { Text(stringResource(R.string.at_help)) }
                }
            } }
        }
        item { Box { OutlinedButton({ expanded=true },Modifier.testTag("agent_metric_selector")) { Text(stringResource(metric.title)) }
            DropdownMenu(expanded,{expanded=false}) { (all-folded).forEach { m->DropdownMenuItem(text={Text(stringResource(m.title))},onClick={metric=m;explicitlySelected=true;expanded=false}) } }
        } }
        if(metric !in folded || explicitlySelected) item { Panel(stringResource(metric.title)) {
            TextButton({help=metric},Modifier.testTag("agent_metric_help")) { Text(stringResource(R.string.at_help)) }
            Text(stringResource(if(metric.scope=="device") R.string.ext_scope_device else R.string.ext_scope_process))
            val disabled=!history && !metric.enabled(settings)
            val values=if(disabled) emptyList() else latest.filter { it.metric==metric.name }
            if(disabled) Text(stringResource(R.string.disabled))
            if(!history) ReadingLifecycle(values.maxOfOrNull { it.time },clock,metric.intervalMs(settings),!disabled,active,
                metric.staleAfterMs(settings,if(values.any { it.status=="AVAILABLE" }) "AVAILABLE" else values.maxByOrNull { it.time }?.status))
            if(!history && values.isEmpty() && !disabled) {
                probeRows.filter { it.metric==metric.name }.forEach { r ->
                    Text(stringResource(R.string.reading_check_only))
                    Text(agentStatus(r.status)+" · "+agentReason(r.reason))
                }
            }
            if(values.isEmpty() && !disabled) Text(stringResource(R.string.no_data))
            values.forEach { r->
                Text((if(metric.aggregation==MetricAggregation.STATE && r.value!=null) agentStateName(metric,r.value.toInt())
                    else r.value?.let(::formatNumber) ?: r.text ?: "—")+" "+metric.unit,style=MaterialTheme.typography.headlineSmall)
                if(r.component.isNotEmpty()) Text(r.component)
                Text("${formatTime(r.time)} · ${r.source}",style=MaterialTheme.typography.bodySmall)
                Text(agentStatus(r.status)+" · "+agentReason(r.reason))
                Text(qualityText(r.quality),style=MaterialTheme.typography.bodySmall)
                if(r.count>1) Text(stringResource(R.string.at_summary,r.count,formatNumber(r.low ?: 0.0),formatNumber(r.high ?: 0.0),formatNumber(r.p95 ?: 0.0)))
            }
            Choice(range,listOf(300_000L to stringResource(R.string.at_five_minutes),3_600_000L to stringResource(R.string.hour),86_400_000L to stringResource(R.string.day),604_800_000L to stringResource(R.string.week),1_209_600_000L to stringResource(R.string.two_weeks))) {window.range=it}
            if(history) HistoryChartControls("agent_${metric.name}",now-range,now,!window.atLatest,range>10_000,
                range<settings.retentionDays*86_400_000L,
                earlier={window.earlier()},later={window.later()},zoomIn={window.zoomIn()},
                zoomOut={window.zoomOut(settings.retentionDays*86_400_000L)})
            if(loadedChart?.first!=chartRequest) Text(stringResource(R.string.loading))
            // Components are separate series. Never join collector costs into one line.
            (if(disabled) emptyList() else points).forEach { series ->
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
            OutlinedButton({pendingExport=PendingExport(if(battery) "battery" else "self",ExportSpec(querySession,now-range,now,true)).encode();export.launch(if(battery) "battery.json" else "self-telemetry.json")}) {Text(stringResource(R.string.export_json))}
            OutlinedButton({pendingExport=PendingExport(if(battery) "battery" else "self",ExportSpec(querySession,now-range,now,false)).encode();export.launch(if(battery) "battery.csv" else "self-telemetry.csv")}) {Text(stringResource(R.string.export_csv))}
        };exportMessage?.let { Text(stringResource(it)) } }
    }
    help?.let { m->AlertDialog(onDismissRequest={help=null},title={Text(stringResource(m.title))},
        text={Text(stringResource(m.help)+"\n\n"+stringResource(R.string.reading_help),Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton({help=null}) {Text(stringResource(R.string.at_close))}}) }
}
@Composable internal fun agentStateName(metric: AgentMetric, value: Int): String {
    if(metric==AgentMetric.FLASH_LIFE) return when(value) {
        in 1..10 -> stringResource(R.string.ext_flash_range,(value-1)*10,value*10)
        11 -> stringResource(R.string.ext_flash_exceeded)
        else -> stringResource(R.string.at_state_unknown,value)
    }
    val resource = when(metric) {
        AgentMetric.CHARGING_DETAIL -> mapOf(1 to R.string.ext_charge_normal,2 to R.string.ext_charge_cold,3 to R.string.ext_charge_hot,4 to R.string.ext_charge_life,5 to R.string.ext_charge_adaptive)[value]
        AgentMetric.FLASH_EOL -> mapOf(1 to R.string.ext_flash_normal,2 to R.string.ext_flash_warning,3 to R.string.ext_flash_urgent)[value]
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
@Composable internal fun agentStatus(s: String)=stringResource(when(s) {
    "WARMING_UP"->R.string.warming_up; "AVAILABLE"->R.string.available; "UNSUPPORTED"->R.string.unsupported; "DISABLED"->R.string.disabled
    "PERMISSION_DENIED"->R.string.at_permission; "ERROR"->R.string.error; else->R.string.unavailable
})
@Composable private fun qualityText(s: String)=stringResource(when(s) {"MEASURED"->R.string.at_measured;"ESTIMATED"->R.string.at_estimated;else->R.string.at_incomplete})
@Composable internal fun agentReason(s: String)=stringResource(when(s) {
    "DISABLED"->R.string.disabled; "NOT_FOUND","API_UNAVAILABLE","UNSUPPORTED_COUNTER"->R.string.unsupported; "READ_FAILED"->R.string.error; "MISSING_VALUE"->R.string.no_data; "NONE"->R.string.at_ok; "OPERATION_FAILED"->R.string.at_operation_failed; "PERMISSION_DENIED"->R.string.at_permission; "FALLBACK_DISABLED"->R.string.at_fallback_off
    "UNVERIFIED_UNITS"->R.string.at_units_unknown; "INVALID_VALUE"->R.string.at_invalid
    "EXTERNAL_POWER"->R.string.at_power; "STABILIZING"->R.string.at_stabilizing
    "BASELINE","INSUFFICIENT_WINDOW"->R.string.at_baseline; "COUNTER_RESET"->R.string.at_reset
    else->R.string.at_no_source
})
object AgentHistoryExport {
    fun json(r: TelemetryRecord)=JSONObject().put("session.id",r.sessionId).put("stream",r.stream).put("metric",AgentMetric.valueOf(r.metric).wire)
        .put("scope",AgentMetric.valueOf(r.metric).scope).put("unit",AgentMetric.valueOf(r.metric).unit).put("component",r.component).put("timestamp",r.time).put("start",r.start)
        .put("duration_ms",r.durationMs).put("segment",r.segment).put("value",r.value ?: JSONObject.NULL).put("min",r.low ?: JSONObject.NULL)
        .put("max",r.high ?: JSONObject.NULL).put("sum",r.sum ?: JSONObject.NULL).put("count",r.count).put("p50",r.p50 ?: JSONObject.NULL)
        .put("p95",r.p95 ?: JSONObject.NULL).put("text",r.text ?: JSONObject.NULL).put("status",r.status).put("reason",r.reason)
        .put("source",r.source).put("quality",r.quality).put("resource",JSONObject(r.metadata))
    suspend fun write(app: MonitorApp,out: OutputStream,json: Boolean,battery: Boolean,spec: ExportSpec = ExportSpec(null,0,Long.MAX_VALUE,json)) {
        out.bufferedWriter().use { w->
            if(json) w.write("{\"version\":1,\"records\":[") else w.write("metric,component,timestamp,value,unit,status,reason,source,quality,record_json\n")
            var after=0L; var first=true
            while(true) {
                val rows=app.db.agentDao().page(after,spec.from,spec.to,spec.session); if(rows.isEmpty()) break
                for(r in rows) { after=r.id; if(AgentMetric.valueOf(r.metric).battery!=battery || !AgentMetric.valueOf(r.metric).acceptsStream(r.stream)) continue
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

internal fun sourceCheckedAt(r: TelemetryRecord): Long = runCatching { JSONObject(r.metadata).optLong("source.checked_at",r.time) }.getOrDefault(r.time)
