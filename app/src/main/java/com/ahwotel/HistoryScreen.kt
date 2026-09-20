package com.ahwotel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlin.math.abs

private data class HistoryAvailabilityRequest(val session: String?,val from: Long,val to: Long,
    val sampleRevision: Long,val agentRevision: Long)

@Composable fun HistoryScreen(app: MonitorApp) {
    var extraPage by remember { mutableStateOf<String?>(null) }
    val sessionsFlow = remember { app.db.dao().sessions() }
    val sessions by sessionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val settings by app.settings.collectAsStateWithLifecycle()
    val window = remember { HistoryWindowState() }
    var lastSelection by remember { mutableStateOf<String?>(null) }
    if (extraPage != null) {
        key("history:$extraPage") {
            AgentTelemetryScreen(app, extraPage == "battery", history=true, session=selected,
                historyWindow=window, groupFilter=if(extraPage=="storage") "STORAGE" else null) { extraPage = null }
        }
        return
    }
    val sampleUpdate by remember { app.db.dao().latest() }.collectAsStateWithLifecycle(initialValue=null)
    val agentUpdate by remember { app.db.agentDao().latest() }.collectAsStateWithLifecycle(initialValue=emptyList())
    LaunchedEffect(sampleUpdate?.id,agentUpdate,window.atLatest,selected) {
        if(window.atLatest) window.end=System.currentTimeMillis()
    }
    val from = window.from
    val end = window.end
    var availabilityRetry by remember { mutableIntStateOf(0) }
    val availabilityRequest=HistoryAvailabilityRequest(selected,from,end,sampleUpdate?.id ?: 0,
        agentUpdate.maxOfOrNull { it.id } ?: 0)
    val availabilityLoad=rememberRetainedLoad(selected,availabilityRequest,retryKey=availabilityRetry,load={ target ->
            val agentPresence=app.db.agentDao().presence(target.from,target.to,target.session)
            val last=app.db.dao().lastInRange(target.session,target.from,target.to)
            val agents=app.db.agentDao().rangeLatest(target.from,target.to,target.session)
            Metric.entries.associateWith { it.reading(last,null,agents) } to Metric.entries.filter { m ->
                if(m.batteryMetric()!=null) agentPresence.any { it.metric==m.batteryMetric()!!.name && (it.valid>0 || it.refusals<it.total) }
                else app.db.dao().hasValue(androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT EXISTS(SELECT 1 FROM samples WHERE time BETWEEN ? AND ? AND (? IS NULL OR sessionId=?) AND ("+m.column+" IS NOT NULL OR (capabilities NOT LIKE ? AND capabilities NOT LIKE ?)))",
                    arrayOf<Any?>(target.from,target.to,target.session,target.session,"%"+m.capabilityKey()+"=UNSUPPORTED%","%"+m.capabilityKey()+"=PERMISSION_DENIED%")))!=0
            }.toSet()
    },onError={app.logs.event("history_range_load_failed",error=true)})
    val availability=availabilityLoad.snapshot?.takeIf { it.request==availabilityRequest }?.value
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<Int?>(null) }
    var exportSpec by remember { mutableStateOf(ExportSpec(null, 0, Long.MAX_VALUE, false)) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            exportMessage = try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { Exporter.history(app, it, exportSpec) } ?: error("output_unavailable") }
                R.string.export_done
            } catch (_: Exception) { R.string.export_failed }
            exporting = false
        }
    }
    val metrics = listOf(Metric.CPU, Metric.CPU_WAIT, Metric.PROBE_DELAY, Metric.MEMORY_AVAILABLE, Metric.MEMORY_PERCENT, Metric.MEMORY_PRESSURE,
        Metric.STORAGE_PERCENT, Metric.STORAGE_PRESSURE, Metric.THERMAL, Metric.THERMAL_PRESSURE,
        Metric.HEADROOM, Metric.BATTERY, Metric.TEMPERATURE, Metric.STATE)
    val unavailableMetrics=availability?.let { loaded -> metrics.filter {
        it !in loaded.second && permanentlyUnavailable(loaded.first.getValue(it).status)
    } }.orEmpty()
    val availableMetrics=metrics-unavailableMetrics.toSet()
    val navigation: @Composable (Metric) -> Unit = { metric ->
        HistoryChartControls(metric.name, from, end, !window.atLatest, window.range > 10_000,
            window.range < settings.retentionDays * 86_400_000L,
            earlier = { window.earlier() },
            later = { window.later() },
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
                    DropdownMenuItem(text = { Text(stringResource(R.string.all_sessions)) }, onClick = { selected = null; expanded = false })
                    sessions.forEach { session ->
                        DropdownMenuItem(text = { Column { Text(formatTime(session.startedAt)); Text(session.id, style = MaterialTheme.typography.labelSmall) } },
                            onClick = { selected = session.id; window.end = session.endedAt ?: System.currentTimeMillis()
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
                settings.retentionDays * 86_400_000L to stringResource(R.string.all_history)).distinctBy { it.first }) { window.latest(it) }
            Text("${formatTime(from)} → ${formatTime(end)}", style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.chart_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.reading_export_scope),style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !exporting, onClick = { exportSpec = ExportSpec(selected, from, end, false); export.launch("ahwotel-history.csv") }) { Text(stringResource(R.string.export_csv)) }
                OutlinedButton(enabled = !exporting, onClick = { exportSpec = ExportSpec(selected, from, end, true); export.launch("ahwotel-history.json") }) { Text(stringResource(R.string.export_json)) }
            }
            if (exporting) Text(stringResource(R.string.exporting))
            exportMessage?.let { Text(stringResource(it)) }
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
        if(availabilityLoad.snapshot!=null || availability!=null) {
            items(availableMetrics,key={it.name}) { HistoryChart(app,it,selected,from,end) { navigation(it) } }
            if(unavailableMetrics.isNotEmpty()) item(key="unavailable_history") {
                UnavailableSection("history",unavailableMetrics.size) {
                    unavailableMetrics.forEach { HistoryChart(app,it,selected,from,end) { navigation(it) } }
                }
            }
        }
    }
}

private data class HistoryChartRequest(val metric: Metric,val session: String?,val from: Long,val to: Long,val revision: Long)

@Composable fun HistoryChart(app: MonitorApp, metric: Metric, session: String?, from: Long, to: Long,
    navigation: @Composable () -> Unit) {
    var retry by remember(metric,session) { mutableIntStateOf(0) }
    val sampleUpdate by remember { app.db.dao().latest() }.collectAsStateWithLifecycle(initialValue=null)
    val agentUpdate by remember { app.db.agentDao().latest() }.collectAsStateWithLifecycle(initialValue=emptyList())
    val request=HistoryChartRequest(metric,session,from,to,if(metric.batteryMetric()!=null) agentUpdate.maxOfOrNull { it.id } ?: 0 else sampleUpdate?.id ?: 0)
    val loaded=rememberRetainedLoad(metric to session,request,retryKey=retry,load={ target ->
        target.metric.batteryMetric()?.let { loadAgentChart(app.db.agentDao(),it,target.from,target.to,target.session).flatMap { it.points }.sortedBy { it.time } }
            ?: app.db.dao().chart(chartQuery(target.metric,target.session,target.from,target.to))
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
    Panel(title, help = metric, helpContext = HelpContext.HISTORY) {
        navigation()
        if (metric.isProbe) Text(stringResource(if (metric == Metric.CPU_WAIT) R.string.cpu_wait_hint else R.string.probe_delay_hint), style = MaterialTheme.typography.bodySmall)
        MetricPlot(title, points, plotFrom, plotTo, metric.unit,
            if (isState) 3.0 else if (metric == Metric.THERMAL) 6.0 else if (metric.unit == "%") 100.0 else null, states,
            dataReady=snapshot!=null,loading=loaded.loading,failed=loaded.failed,retry={retry++},testId="history_${metric.name}")
    }
}
