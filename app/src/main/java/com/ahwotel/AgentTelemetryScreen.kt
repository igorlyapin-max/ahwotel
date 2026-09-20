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

private fun agentTechnicalLine(record: TelemetryRecord): String =
    listOf(record.component,record.source).filter(String::isNotEmpty).distinct().joinToString(" · ")

@Composable private fun agentCompactValue(metric: AgentMetric, record: TelemetryRecord): String {
    val value=if(metric.aggregation==MetricAggregation.STATE && record.value!=null)
        agentStateName(metric,record.value.toInt())
    else record.value?.let(::formatNumber) ?: record.text ?: "—"
    return if(value=="—" || metric.unit=="1") value else "$value ${metric.unit}"
}

@Composable private fun AgentCompactMetricCard(metric: AgentMetric, values: List<TelemetryRecord>, probes: List<TelemetryRecord>,
    id: String, modifier: Modifier=Modifier, onHelp: () -> Unit) {
    val fallback=probes.filter { it.metric==metric.name }.maxByOrNull { it.time }
    val entries=if(values.isEmpty()) listOf(CompactMetricValue("—",fallback?.let(::agentTechnicalLine).orEmpty()))
        else values.map { CompactMetricValue(agentCompactValue(metric,it),agentTechnicalLine(it)) }
    CompactMetricCard(stringResource(metric.title),entries,id,modifier,help={
        CompactInfoButton(stringResource(R.string.help_about,stringResource(metric.title)),
            if(id.startsWith("selected_")) "agent_metric_help" else "agent_metric_help_$id",onHelp)
    })
}
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
    var rangeRetry by remember(history,querySession) { mutableIntStateOf(0) }
    val rangeLoad=if(history) rememberRetainedLoad(history to querySession,request,retryKey=rangeRetry,load={ target ->
            app.db.agentDao().presence(target.first-target.second,target.first,target.third) to
                app.db.agentDao().rangeLatest(target.first-target.second,target.first,target.third)
        },onError={app.logs.event("history_range_load_failed",error=true)})
        else RetainedLoadState<Triple<Long,Long,String?>,Pair<List<MetricPresence>,List<TelemetryRecord>>>()
    val currentRange=rangeLoad.snapshot?.takeIf { it.request==request }?.value
    val loaded=rangeLoad.snapshot?.value
    val latest=(if(history) loaded?.second.orEmpty() else liveLatest.filter { it.sessionId==liveSession })
        .filter { AgentMetric.valueOf(it.metric).acceptsStream(it.stream) }
    val folded=all.filter { m ->
        if(history) currentRange?.first?.find { it.metric==m.name }?.let { it.valid==0 && it.refusals==it.total }==true
        else {
            val recorded=latest.filter { it.metric==m.name }
            val checks=probeRows.filter { it.metric==m.name }
            shouldFoldLive(m.enabled(settings),recorded.map { it.status },checks.map { it.status },
                recorded.any { it.status=="AVAILABLE" && (it.value!=null || it.text!=null) })
        }
    }
    var explicitlySelected by remember { mutableStateOf(false) }
    val summary by produceState<BatteryDaySummary?>(null,now,querySession,battery) {
        if(battery) value=withContext(Dispatchers.IO) { summarizeBattery(app.db.agentDao().batterySummaryRows(now-86_400_000,now,querySession),settings.batterySettings.currentSeconds*2000L) }
    }
    val chartRequest=AgentChartRequest(metric,range,now,querySession,liveLatest.maxOfOrNull { it.id } ?: 0)
    var chartRetry by remember(metric,querySession) { mutableIntStateOf(0) }
    val chartLoad=if(history) rememberRetainedLoad(metric to querySession,chartRequest,retryKey=chartRetry,load={ target ->
            loadAgentChart(app.db.agentDao(),target.metric,target.end-target.range,target.end,target.session)
        },onError={app.logs.event("history_chart_load_failed",error=true,probeMetric=metric.name)})
        else RetainedLoadState<AgentChartRequest,List<AgentChartSeries>>()
    val chartSnapshot=chartLoad.snapshot
    val points=chartSnapshot?.value.orEmpty()
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
    LazyColumn(Modifier.fillMaxSize().testTag("agent_list").padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item { OutlinedButton(back) { Text(stringResource(R.string.at_back)) }; PageTitle(stringResource(if(groupFilter=="STORAGE") R.string.storage else if(battery) R.string.at_battery_title else R.string.at_self_title)) }
        if(history) item { Text(stringResource(R.string.at_history_hint)) }
        if(history && rangeLoad.loading && currentRange==null) item {
            Text(stringResource(if(rangeLoad.snapshot==null) R.string.loading else R.string.refreshing_data),
                Modifier.testTag("agent_range_loading"))
        }
        if(history && rangeLoad.failed) item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.load_failed),Modifier.weight(1f),color=MaterialTheme.colorScheme.error)
                TextButton({rangeRetry++},Modifier.testTag("agent_range_retry")) { Text(stringResource(R.string.retry)) }
            }
        }
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
        if(!history || rangeLoad.snapshot!=null) item { Box { OutlinedButton({ expanded=true },Modifier.testTag("agent_metric_selector")) { Text(stringResource(metric.title)) }
            DropdownMenu(expanded,{expanded=false}) { (all-folded).forEach { m->DropdownMenuItem(text={Text(stringResource(m.title))},onClick={metric=m;explicitlySelected=true;expanded=false}) } }
        } }
        if((!history || rangeLoad.snapshot!=null) && (metric !in folded || explicitlySelected)) item {
            val disabled=!history && !metric.enabled(settings)
            val values=if(disabled) emptyList() else latest.filter { it.metric==metric.name }
            if(!history) AgentCompactMetricCard(metric,values,probeRows,"selected_${metric.name}",onHelp={help=metric})
            else Panel(stringResource(metric.title)) {
                TextButton({help=metric},Modifier.testTag("agent_metric_help")) { Text(stringResource(R.string.at_help)) }
                Text(stringResource(if(metric.scope=="device") R.string.ext_scope_device else R.string.ext_scope_process))
                if(disabled) Text(stringResource(R.string.disabled))
                if(values.isEmpty() && !disabled) Text(stringResource(R.string.no_data))
                values.forEach { r->
                    Text(agentCompactValue(metric,r),style=MaterialTheme.typography.headlineSmall)
                    if(r.component.isNotEmpty()) Text(r.component)
                    Text("${formatTime(r.time)} · ${r.source}",style=MaterialTheme.typography.bodySmall)
                    Text(agentStatus(r.status)+" · "+agentReason(r.reason))
                    Text(qualityText(r.quality),style=MaterialTheme.typography.bodySmall)
                    if(r.count>1) Text(stringResource(R.string.at_summary,r.count,formatNumber(r.low ?: 0.0),formatNumber(r.high ?: 0.0),formatNumber(r.p95 ?: 0.0)))
                }
                Choice(range,listOf(300_000L to stringResource(R.string.at_five_minutes),3_600_000L to stringResource(R.string.hour),86_400_000L to stringResource(R.string.day),604_800_000L to stringResource(R.string.week),1_209_600_000L to stringResource(R.string.two_weeks))) {window.range=it}
                HistoryChartControls("agent_${metric.name}",now-range,now,!window.atLatest,range>10_000,
                range<settings.retentionDays*86_400_000L,
                earlier={window.earlier()},later={window.later()},zoomIn={window.zoomIn()},
                zoomOut={window.zoomOut(settings.retentionDays*86_400_000L)})
                val plotFrom=chartSnapshot?.request?.let { it.end-it.range } ?: now-range
                val plotTo=chartSnapshot?.request?.end ?: now
                // Components are separate series. Never join collector costs into one line.
                if(!disabled && points.isEmpty()) MetricPlot(stringResource(metric.title),emptyList(),plotFrom,plotTo,metric.unit,
                    fixedMaximum=if(metric==AgentMetric.LEVEL) 100.0 else null,aggregation=metric.aggregation,
                    dataReady=chartSnapshot!=null,loading=chartLoad.loading,failed=chartLoad.failed,
                    retry={chartRetry++},testId="agent_${metric.name}")
                (if(disabled) emptyList() else points).forEachIndexed { index,series ->
                    val component=series.component
                    if(component.isNotEmpty()) Text(component)
                    val stateLabels=if(metric.aggregation==MetricAggregation.STATE) series.points.mapNotNull { it.mean?.toInt() }.distinct()
                        .associateWith { agentStateName(metric,it) } else emptyMap()
                    MetricPlot(stringResource(metric.title),series.points,plotFrom,plotTo,metric.unit,
                        fixedMaximum=if(metric==AgentMetric.LEVEL) 100.0 else null,
                        aggregation=metric.aggregation, nominalStates=stateLabels,mixedBuckets=series.mixed,gapBuckets=series.gaps,
                        dataReady=true,loading=chartLoad.loading,failed=chartLoad.failed,
                        retry={chartRetry++},testId="agent_${metric.name}_$index")
                }
            }
        }
        if(!history) item {
            Choice(range,listOf(300_000L to stringResource(R.string.at_five_minutes),3_600_000L to stringResource(R.string.hour),
                86_400_000L to stringResource(R.string.day),604_800_000L to stringResource(R.string.week),
                1_209_600_000L to stringResource(R.string.two_weeks))) {window.range=it}
        }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton({pendingExport=PendingExport(if(battery) "battery" else "self",ExportSpec(querySession,now-range,now,true)).encode();export.launch(if(battery) "battery.json" else "self-telemetry.json")}) {Text(stringResource(R.string.export_json))}
            OutlinedButton({pendingExport=PendingExport(if(battery) "battery" else "self",ExportSpec(querySession,now-range,now,false)).encode();export.launch(if(battery) "battery.csv" else "self-telemetry.csv")}) {Text(stringResource(R.string.export_csv))}
        };exportMessage?.let { Text(stringResource(it)) } }
        if((!history || rangeLoad.snapshot!=null) && folded.isNotEmpty()) item(key="unavailable_agent") {
            UnavailableSection("agent",folded.size) {
                if(history) folded.forEach { m ->
                    TextButton({metric=m;explicitlySelected=true}) { Text(stringResource(m.title)) }
                    CompactInfoButton(stringResource(R.string.help_about,stringResource(m.title)),onClick={help=m})
                } else folded.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        row.forEach { m -> Box(Modifier.weight(1f)) {
                            AgentCompactMetricCard(m,latest.filter { it.metric==m.name && m.enabled(settings) },probeRows,
                                "unavailable_${m.name}",onHelp={help=m})
                        } }
                        if(row.size==1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    help?.let { m->
        val records=(latest+probeRows).filter { it.metric==m.name }.distinctBy { it.id to it.stream }
        AlertDialog(onDismissRequest={help=null},title={Text(stringResource(m.title))},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(m.help))
                Text(stringResource(if(m.scope=="device") R.string.ext_scope_device else R.string.ext_scope_process))
                Text(stringResource(R.string.reading_help))
                records.forEach { r ->
                    Text(listOf(agentTechnicalLine(r),formatTime(r.time)).filter(String::isNotEmpty).joinToString(" · "),
                        style=MaterialTheme.typography.bodySmall)
                    Text(agentStatus(r.status)+" · "+agentReason(r.reason))
                    Text(qualityText(r.quality),style=MaterialTheme.typography.bodySmall)
                }
            }
        },confirmButton={TextButton({help=null}) {Text(stringResource(R.string.at_close))}})
    }
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
