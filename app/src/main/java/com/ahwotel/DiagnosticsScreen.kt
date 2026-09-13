package com.ahwotel

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import java.io.File

@Composable fun DiagnosticsScreen(app: MonitorApp) {
    val stats by app.stats.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val reports by app.sources.reports.collectAsStateWithLifecycle()
    val checking by app.checkingSources.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { app.maintenance() } } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) scope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    val file = File.createTempFile("logs", ".jsonl", context.cacheDir)
                    try { app.logs.export(file); context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no_output") }
                    finally { file.delete() }
                }
                R.string.export_done
            } catch (_: Exception) { R.string.export_failed }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageTitle(stringResource(R.string.diagnostics)) }
        item { MetricGuideButton() }
        item { Panel(stringResource(R.string.source_availability)) {
            Text(stringResource(R.string.source_check_hint), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !checking, onClick = {
                scope.launch { try { app.checkSources() } catch (_: Exception) { message = R.string.source_check_failed } }
            }) { Text(stringResource(if (checking) R.string.checking_sources else R.string.check_sources)) }
            if (reports.isEmpty()) Text(stringResource(R.string.sources_not_checked))
        } }
        items(reports.values.sortedBy { it.metric.ordinal }, key = { it.metric.name }) { report ->
            Panel(metricTitle(report.metric), help = report.metric, helpContext = HelpContext.DIAGNOSTICS) {
                Text(report.metric.wire, style = MaterialTheme.typography.labelSmall)
                SourceHelpSummary(report)
            }
        }
        item { Panel(stringResource(R.string.device)) {
            Text("${Build.MANUFACTURER} ${Build.MODEL}")
            Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
            Text("AHWOTel ${BuildConfig.VERSION_NAME}")
            Text(settings.deviceId)
        } }
        item { Panel(stringResource(R.string.retention)) {
            Text(stringResource(R.string.database_size, formatNumber(stats.bytes / 1048576.0)))
            Text(stringResource(R.string.sample_count, stats.samples))
            Text(stringResource(R.string.oldest_reading, stats.earliest?.let { formatTime(it) } ?: "—"))
            Text(stringResource(R.string.queue_size, stats.queued, formatNumber(stats.queueBytes / 1048576.0)))
            stats.cleanupReason?.let { Text(stringResource(if (it == "retention") R.string.cleanup_retention else R.string.cleanup_storage_limit), color = Accent) }
        } }
        item { Panel(stringResource(R.string.diagnostics)) {
            Text(stringResource(R.string.logging_hint))
            Text(stringResource(R.string.last_event))
            Text(app.logs.lastEvent, style = MaterialTheme.typography.bodySmall)
            Button({ export.launch("ahwotel-diagnostics.jsonl") }) { Text(stringResource(R.string.export_logs)) }
            message?.let { Text(stringResource(it)) }
        } }
    }
}
