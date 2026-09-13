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

@Composable fun HistoryScreen(app: MonitorApp) {
    var extraPage by remember { mutableStateOf<String?>(null) }
    if (extraPage != null) { AgentTelemetryScreen(app, extraPage == "battery") { extraPage = null }; return }
    val sessionsFlow = remember { app.db.dao().sessions() }
    val sessions by sessionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val settings by app.settings.collectAsStateWithLifecycle()
    var range by remember { mutableLongStateOf(86_400_000L) }
    var end by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastSelection by remember { mutableStateOf<String?>(null) }
    val from = end - range
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageTitle(stringResource(R.string.history)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ extraPage = "battery" }) { Text(stringResource(R.string.at_battery_title)) }
            OutlinedButton({ extraPage = "self" }) { Text(stringResource(R.string.at_self_title)) }
        } }
        item { Panel(stringResource(R.string.sessions)) {
            Box {
                OutlinedButton({ expanded = true }) { Text(selected ?: stringResource(R.string.all_sessions)) }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.all_sessions)) }, onClick = { selected = null; expanded = false })
                    sessions.forEach { session ->
                        DropdownMenuItem(text = { Column { Text(formatTime(session.startedAt)); Text(session.id, style = MaterialTheme.typography.labelSmall) } },
                            onClick = { selected = session.id; end = session.endedAt ?: System.currentTimeMillis()
                                range = (end - session.startedAt + 2000).coerceAtLeast(60_000); expanded = false })
                    }
                }
            }
            sessions.find { it.id == selected }?.let { session ->
                Text(stringResource(when(session.status) { "RUNNING" -> R.string.running; "FINISHED" -> R.string.finished; else -> R.string.interrupted }))
                Text("${formatTime(session.startedAt)} → ${session.endedAt?.let { formatTime(it) } ?: "…"}", style = MaterialTheme.typography.bodySmall)
                Text(session.deviceId, style = MaterialTheme.typography.bodySmall)
            }
            Choice(range, listOf(3_600_000L to stringResource(R.string.hour), 86_400_000L to stringResource(R.string.day),
                604_800_000L to stringResource(R.string.week), 1_209_600_000L to stringResource(R.string.two_weeks),
                settings.retentionDays * 86_400_000L to stringResource(R.string.all_history)).distinctBy { it.first }) { range = it; end = System.currentTimeMillis() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ end -= range / 2 }) { Text(stringResource(R.string.earlier)) }
                OutlinedButton({ end = minOf(System.currentTimeMillis(), end + range / 2) }) { Text(stringResource(R.string.later)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ range = (range / 2).coerceAtLeast(10_000) }) { Text(stringResource(R.string.zoom_in)) }
                OutlinedButton({ range = (range * 2).coerceAtMost(settings.retentionDays * 86_400_000L) }) { Text(stringResource(R.string.zoom_out)) }
            }
            Text("${formatTime(from)} → ${formatTime(end)}", style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.chart_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !exporting, onClick = { exportSpec = ExportSpec(selected, from, end, false); export.launch("ahwotel-history.csv") }) { Text(stringResource(R.string.export_csv)) }
                OutlinedButton(enabled = !exporting, onClick = { exportSpec = ExportSpec(selected, from, end, true); export.launch("ahwotel-history.json") }) { Text(stringResource(R.string.export_json)) }
            }
            if (exporting) Text(stringResource(R.string.exporting))
            exportMessage?.let { Text(stringResource(it)) }
        } }
        items(metrics, key = { it.name }) { metric ->
            HistoryChart(app, metric, selected, from, end)
        }
    }
}

@Composable fun HistoryChart(app: MonitorApp, metric: Metric, session: String?, from: Long, to: Long) {
    val points by produceState<List<ChartBucket>>(emptyList(), metric, session, from, to) {
        value = withContext(Dispatchers.IO) { app.db.dao().chart(chartQuery(metric, session, from, to)) }
    }
    val title = metricTitle(metric)
    val isState = metric == Metric.STATE || metric.name.endsWith("_PRESSURE")
    val states = when {
        isState -> (0..3).map { severityText(it.toDouble()) }
        metric == Metric.THERMAL -> stringResource(R.string.chart_thermal_states).split("|")
        else -> emptyList()
    }
    Panel(title, help = metric, helpContext = HelpContext.HISTORY) {
        if (metric.isProbe) Text(stringResource(if (metric == Metric.CPU_WAIT) R.string.cpu_wait_hint else R.string.probe_delay_hint), style = MaterialTheme.typography.bodySmall)
        MetricPlot(title, points, from, to, metric.unit,
            if (isState) 3.0 else if (metric == Metric.THERMAL) 6.0 else if (metric.unit == "%") 100.0 else null, states)
    }
}
