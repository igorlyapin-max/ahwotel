package com.ahwotel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import kotlinx.coroutines.*
import java.util.TimeZone
import kotlin.math.abs

internal data class HistoryAvailabilityRequest(val session: String?,val from: Long,val to: Long,
    val sampleRevision: Long,val agentRevision: Long)
internal fun historyExportReady(revision: HistoryRevision?, current: HistoryAvailabilityRequest,
    loaded: HistoryAvailabilityRequest?, rangeLoading: Boolean, refreshLoading: Boolean) =
    revision != null && loaded == current && !rangeLoading && !refreshLoading
private data class HistoryRangeData(val readings: Map<Metric,MetricReading>,val available: Set<Metric>,
    val charts: Map<Metric,List<ChartBucket>>)
private val historyMetrics=listOf(Metric.CPU,Metric.CPU_WAIT,Metric.PROBE_DELAY,Metric.MEMORY_AVAILABLE,
    Metric.MEMORY_PERCENT,Metric.MEMORY_PRESSURE,Metric.STORAGE_PERCENT,Metric.STORAGE_PRESSURE,
    Metric.THERMAL,Metric.THERMAL_PRESSURE,Metric.HEADROOM,Metric.BATTERY,Metric.TEMPERATURE,Metric.STATE)

@Composable fun HistoryScreen(app: MonitorApp) {
    var extraPage by remember { mutableStateOf<String?>(null) }
    val refresh = rememberHistoryRefreshController(app)
    val screenModel: HistoryScreenViewModel = viewModel()
    val revision = refresh.revision
    val sessions = revision?.sessions.orEmpty()
    val selected = screenModel.selectedSession
    var expanded by remember { mutableStateOf(false) }
    val settings by app.settings.collectAsStateWithLifecycle()
    val window = screenModel.window
    if (extraPage != null) {
        key("history:$extraPage") {
            AgentTelemetryScreen(app, extraPage == "battery", history=true, session=selected,
                historyWindow=window, historyRefresh=refresh,
                groupFilter=if(extraPage=="storage") "STORAGE" else null) { extraPage = null }
        }
        return
    }
    LaunchedEffect(revision?.capturedAt,window.atLatest) {
        if(window.atLatest && revision != null) window.end=revision.capturedAt
    }
    val end = if(window.atLatest) revision?.capturedAt ?: window.end else window.end
    val from = end-window.range
    var availabilityRetry by remember { mutableIntStateOf(0) }
    val availabilityRequest=HistoryAvailabilityRequest(selected,from,end,revision?.sampleThroughId ?: 0,
        revision?.agentThroughId ?: 0)
    val availabilityLoad=if(revision!=null) rememberRetainedLoad("history",availabilityRequest,retryKey=availabilityRetry,load={ target -> app.db.withTransaction {
            val agentPresence=app.db.agentDao().presenceThrough(target.from,target.to,target.session,target.agentRevision)
            val coreMetrics=historyMetrics.filter { it.batteryMetric()==null }
            val corePresence=app.db.dao().presence(samplePresenceQuery(coreMetrics,target.session,target.from,target.to,target.sampleRevision))
                .associateBy { it.metric }
            val charts=app.db.dao().charts(sampleChartsQuery(historyMetrics.filter { it.batteryMetric()==null },target.session,
                target.from,target.to,throughId=target.sampleRevision)).groupBy { Metric.valueOf(it.metric) }
                .mapValuesTo(mutableMapOf()) { (_,rows)->rows.map { it.chartBucket() } }
            historyMetrics.filter { it.batteryMetric()!=null }.forEach { metric ->
                charts[metric]=loadAgentChart(app.db.agentDao(),metric.batteryMetric()!!,target.from,target.to,target.session,
                    target.agentRevision).flatMap { it.points }.sortedBy { it.time }
            }
            val last=app.db.dao().lastInRangeThrough(target.session,target.from,target.to,target.sampleRevision)
            val agents=app.db.agentDao().rangeLatestThrough(target.from,target.to,target.session,target.agentRevision)
            val readings=Metric.entries.associateWith { it.reading(last,null,agents) }
            val available=Metric.entries.filter { m ->
                if(m.batteryMetric()!=null) agentPresence.any { it.metric==m.batteryMetric()!!.name && (it.valid>0 || it.refusals<it.total) }
                else corePresence[m.name]?.let { it.valid>0 || it.refusals<it.total }==true
            }.toSet()
            HistoryRangeData(readings,available,charts)
        }
    },onError={app.logs.event("history_range_load_failed",error=true)})
        else RetainedLoadState<HistoryAvailabilityRequest,HistoryRangeData>()
    val availabilitySnapshot=availabilityLoad.snapshot
    val availability=availabilitySnapshot?.value
    val displayedRequest=availabilitySnapshot?.request ?: availabilityRequest
    val displaySession=displayedRequest.session
    val displayFrom=displayedRequest.from
    val displayEnd=displayedRequest.to
    val displayRevision=revision?.copy(sampleThroughId=displayedRequest.sampleRevision,
        agentThroughId=displayedRequest.agentRevision)
    val exportState by screenModel.export.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) screenModel.cancelPicker() else screenModel.exportTo(uri)
    }
    val exportReady = historyExportReady(revision, availabilityRequest, availabilitySnapshot?.request,
        availabilityLoad.loading, refresh.loading)
    val unavailableMetrics=availability?.let { loaded -> historyMetrics.filter {
        it !in loaded.available && permanentlyUnavailable(loaded.readings.getValue(it).status)
    } }.orEmpty()
    val availableMetrics=historyMetrics-unavailableMetrics.toSet()
    val navigation: @Composable (Metric) -> Unit = { metric ->
        HistoryChartControls(metric.name, displayFrom, displayEnd, !window.atLatest, window.range > 10_000,
            window.range < settings.retentionDays * 86_400_000L,
            earlier = { window.earlier() },
            later = { window.later(revision?.capturedAt ?: System.currentTimeMillis()) },
            zoomIn = { window.zoomIn() },
            zoomOut = { window.zoomOut(settings.retentionDays * 86_400_000L) })
    }
    LazyColumn(Modifier.fillMaxSize().testTag("history_list").padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageTitle(stringResource(R.string.history)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ extraPage = "battery" }) { Text(stringResource(R.string.at_battery_title)) }
            OutlinedButton({ extraPage = "self" }) { Text(stringResource(R.string.at_self_title)) }
        } }
        item { OutlinedButton({ extraPage="storage" }) { Text(stringResource(R.string.storage)) } }
        item { Panel(stringResource(R.string.sessions)) {
            Box {
                OutlinedButton({ expanded = true }) { Text(selected ?: stringResource(R.string.all_sessions)) }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.all_sessions)) }, onClick = {
                        screenModel.selectSession(null); expanded = false
                    })
                    sessions.forEach { session ->
                        DropdownMenuItem(text = { Column { Text(formatTime(session.startedAt)); Text(session.id, style = MaterialTheme.typography.labelSmall) } },
                            onClick = { screenModel.selectSession(session.id); window.end = session.endedAt ?: System.currentTimeMillis()
                                window.atLatest = session.endedAt == null
                                window.range = (window.end - session.startedAt + 2000).coerceAtLeast(60_000); expanded = false })
                    }
                }
            }
            sessions.find { it.id == selected }?.let { session ->
                Text(stringResource(when(session.status) { "RUNNING" -> R.string.running; "FINISHED" -> R.string.finished; else -> R.string.interrupted }))
                Text("${formatTime(session.startedAt)} → ${session.endedAt?.let { formatTime(it) } ?: "…"}", style = MaterialTheme.typography.bodySmall)
                Text(session.deviceId, style = MaterialTheme.typography.bodySmall)
            }
            Choice(window.range, listOf(3_600_000L to stringResource(R.string.hour), 86_400_000L to stringResource(R.string.day),
                604_800_000L to stringResource(R.string.week), 1_209_600_000L to stringResource(R.string.two_weeks),
                settings.retentionDays * 86_400_000L to stringResource(R.string.all_history)).distinctBy { it.first }) {
                    window.latest(it,revision?.capturedAt ?: System.currentTimeMillis())
                }
            Text("${formatTime(displayFrom)} → ${formatTime(displayEnd)}", style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.chart_hint), style = MaterialTheme.typography.bodySmall)
            HistoryRefreshControls(refresh,availabilitySnapshot?.completedAt,availabilityLoad.loading)
            Text(stringResource(R.string.reading_export_scope),style=MaterialTheme.typography.bodySmall)
            val selectedGroups = screenModel.selectedGroups
            HistoryExportGroup.entries.forEach { group ->
                val label = stringResource(when(group) {
                    HistoryExportGroup.DEVICE -> R.string.export_group_device
                    HistoryExportGroup.AGENT_SIGNALS -> R.string.export_group_agent_signals
                    HistoryExportGroup.PERFORMANCE -> R.string.export_group_performance
                    HistoryExportGroup.BATTERY -> R.string.export_group_battery
                    HistoryExportGroup.SELF_TELEMETRY -> R.string.export_group_self
                    HistoryExportGroup.OEM -> R.string.export_group_oem
                })
                Row(Modifier.fillMaxWidth().testTag("export_group_${group.name.lowercase()}").toggleable(
                    value = group in selectedGroups,
                    role = Role.Checkbox,
                    onValueChange = { checked ->
                        val changed = if (checked) selectedGroups + group else selectedGroups - group
                        screenModel.selectGroups(changed)
                    },
                ).semantics(mergeDescendants = true) { contentDescription = label },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = group in selectedGroups, onCheckedChange = null)
                    Text(label)
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val csvLabel = stringResource(R.string.export_format_csv)
                val jsonLabel = stringResource(R.string.export_format_json)
                Row(Modifier.selectable(selected = !screenModel.exportJson, role = Role.RadioButton) { screenModel.selectJson(false) }
                    .semantics(mergeDescendants = true) { contentDescription = csvLabel },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = !screenModel.exportJson, onClick = null); Text(csvLabel)
                }
                Row(Modifier.selectable(selected = screenModel.exportJson, role = Role.RadioButton) { screenModel.selectJson(true) }
                    .semantics(mergeDescendants = true) { contentDescription = jsonLabel },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = screenModel.exportJson, onClick = null); Text(jsonLabel)
                }
            }
            if (selectedGroups.isEmpty()) Text(stringResource(R.string.export_select_group), color = MaterialTheme.colorScheme.error)
            Button(enabled = exportReady && !exportState.running && selectedGroups.isNotEmpty(), onClick = {
                val current = requireNotNull(revision)
                screenModel.prepare(HistoryArchiveSpec(availabilityRequest.session, availabilityRequest.from,
                    availabilityRequest.to, screenModel.exportJson, selectedGroups, settings.language,
                    TimeZone.getDefault().id, capturedAt = current.capturedAt,
                    sampleThroughId = current.sampleThroughId, agentThroughId = current.agentThroughId,
                    oemThroughId = current.oemThroughId, oemInventoryThroughId = current.oemInventoryThroughId,
                    oemEventThroughId = current.oemEventThroughId))
                export.launch("ahwotel-history.zip")
            }, modifier = Modifier.testTag("history_export")) {
                Text(stringResource(R.string.export_action))
            }
            if (exportState.running) Text(stringResource(R.string.exporting))
            exportState.result?.let { result -> Text(stringResource(when(result) {
                "done" -> R.string.export_done
                "interrupted" -> R.string.export_interrupted
                else -> R.string.export_failed
            })) }
        } }
        if(availabilityLoad.loading && availability==null) item {
            Text(stringResource(if(availabilityLoad.snapshot==null) R.string.loading else R.string.refreshing_data),
                Modifier.testTag("history_availability_loading"))
        }
        if(availabilityLoad.failed) item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.load_failed),Modifier.weight(1f),color=MaterialTheme.colorScheme.error)
                TextButton({availabilityRetry++},Modifier.testTag("history_availability_retry")) { Text(stringResource(R.string.retry)) }
            }
        }
        if(revision != null && (availabilityLoad.snapshot!=null || availability!=null)) {
            items(availableMetrics,key={it.name}) { metric ->
                HistoryChart(app,metric,displaySession,displayFrom,displayEnd,observation=availability?.readings?.get(metric),revision=displayRevision,
                    preloaded=availability?.charts?.get(metric).orEmpty()) { navigation(metric) }
            }
            if(unavailableMetrics.isNotEmpty()) item(key="unavailable_history") {
                UnavailableSection("history",unavailableMetrics.size) {
                    unavailableMetrics.forEach { metric ->
                        HistoryChart(app,metric,displaySession,displayFrom,displayEnd,observation=availability?.readings?.get(metric),revision=displayRevision,
                            preloaded=availability?.charts?.get(metric).orEmpty()) { navigation(metric) }
                    }
                }
            }
        }
    }
}

private data class HistoryChartRequest(val metric: Metric,val session: String?,val from: Long,val to: Long,val revision: Long)

@Composable internal fun HistoryChart(app: MonitorApp, metric: Metric, session: String?, from: Long, to: Long,
    observation: MetricReading? = null,
    revision: HistoryRevision? = null,
    preloaded: List<ChartBucket>? = null,
    navigation: @Composable () -> Unit) {
    var retry by remember(metric,session) { mutableIntStateOf(0) }
    val request=HistoryChartRequest(metric,session,from,to,if(metric.batteryMetric()!=null) revision?.agentThroughId ?: 0 else revision?.sampleThroughId ?: 0)
    val loaded=if(preloaded!=null) RetainedLoadState(snapshot=LoadSnapshot(request,preloaded))
        else rememberRetainedLoad(metric,request,retryKey=retry,load={ target ->
        target.metric.batteryMetric()?.let { loadAgentChart(app.db.agentDao(),it,target.from,target.to,target.session,target.revision).flatMap { it.points }.sortedBy { it.time } }
            ?: app.db.dao().chart(chartQuery(target.metric,target.session,target.from,target.to,throughId=target.revision))
    },onError={ app.logs.event("history_chart_load_failed",error=true,probeMetric=metric.name) })
    val snapshot=loaded.snapshot
    val points=snapshot?.value.orEmpty()
    val plotFrom=snapshot?.request?.from ?: from
    val plotTo=snapshot?.request?.to ?: to
    val title = metricTitle(metric)
    val isState = metric == Metric.STATE || metric.name.endsWith("_PRESSURE")
    val states = when {
        isState -> (0..3).map { severityText(it.toDouble()) }
        metric == Metric.THERMAL -> stringResource(R.string.chart_thermal_states).split("|")
        else -> emptyList()
    }
    val helpObservation=observation?.let {
        MetricObservationContext(it.time,it.status,it.reason,it.source,staleAfterMs=null)
    }
    Panel(title, help = metric, helpContext = HelpContext.HISTORY,helpObservation=helpObservation) {
        navigation()
        if (metric.isProbe) Text(stringResource(if (metric == Metric.CPU_WAIT) R.string.cpu_wait_hint else R.string.probe_delay_hint), style = MaterialTheme.typography.bodySmall)
        MetricPlot(title, points, plotFrom, plotTo, metric.unit,
            if (isState) 3.0 else if (metric == Metric.THERMAL) 6.0 else if (metric.unit == "%") 100.0 else null, states,
            dataReady=snapshot!=null,loading=loaded.loading,failed=loaded.failed,retry={retry++},testId="history_${metric.name}")
    }
}
