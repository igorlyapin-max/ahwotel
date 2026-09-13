package com.ahwotel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class HelpContext { GENERAL, HISTORY, DIAGNOSTICS }
enum class HelpGroup {
    ALL, CPU, MEMORY, STORAGE, THERMAL, BATTERY, INDIRECT;
    val metrics: List<Metric> get() = when (this) {
        ALL -> Metric.entries
        CPU -> listOf(Metric.CPU, Metric.CPU_WAIT, Metric.PROBE_DELAY, Metric.CPU_PRESSURE)
        MEMORY -> listOf(Metric.MEMORY_TOTAL, Metric.MEMORY_AVAILABLE, Metric.MEMORY_PERCENT,
            Metric.MEMORY_LOW, Metric.MEMORY_THRESHOLD, Metric.MEMORY_PRESSURE)
        STORAGE -> listOf(Metric.STORAGE_TOTAL, Metric.STORAGE_AVAILABLE, Metric.STORAGE_USED,
            Metric.STORAGE_PERCENT, Metric.STORAGE_PRESSURE)
        THERMAL -> listOf(Metric.THERMAL, Metric.HEADROOM, Metric.THERMAL_PRESSURE)
        BATTERY -> listOf(Metric.BATTERY, Metric.CHARGING, Metric.TEMPERATURE)
        INDIRECT -> listOf(Metric.CPU_WAIT, Metric.PROBE_DELAY)
    }
}

data class HelpPage(val metric: Metric? = null, val group: HelpGroup? = null,
    val context: HelpContext = HelpContext.GENERAL) {
    companion object {
        val Saver = listSaver<HelpPage, String>(
            save = { listOf(it.metric?.name ?: "", it.group?.name ?: "", it.context.name) },
            restore = { HelpPage(it[0].takeIf(String::isNotEmpty)?.let(Metric::valueOf),
                it[1].takeIf(String::isNotEmpty)?.let(HelpGroup::valueOf), HelpContext.valueOf(it[2])) })
    }
}

private val LocalHelp = compositionLocalOf<(HelpPage) -> Unit> { error("metric_help_host_missing") }

@Composable fun MetricHelpButton(metric: Metric, context: HelpContext = HelpContext.GENERAL) {
    val open = LocalHelp.current
    HelpIcon(stringResource(R.string.help_about, metricTitle(metric))) { open(HelpPage(metric = metric, context = context)) }
}

@Composable fun HelpGroupButton(group: HelpGroup) {
    val open = LocalHelp.current
    HelpIcon(stringResource(R.string.help_group_about, helpGroupTitle(group))) { open(HelpPage(group = group)) }
}

@Composable fun MetricGuideButton() {
    val open = LocalHelp.current
    OutlinedButton(onClick = { open(HelpPage(group = HelpGroup.ALL)) }) { Text(stringResource(R.string.help_guide)) }
}

@Composable private fun HelpIcon(label: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp)
        .semantics { contentDescription = label }) { Text("ⓘ", style = MaterialTheme.typography.headlineSmall) }
}

@Composable private fun helpGroupTitle(group: HelpGroup): String = when (group) {
    HelpGroup.ALL -> stringResource(R.string.help_guide)
    HelpGroup.INDIRECT -> stringResource(R.string.indirect_cpu)
    else -> kindTitle(CollectorKind.valueOf(group.name))
}

/** Host is above all tabs; only enum IDs survive recreation, never resolved language strings. */
@Composable fun MetricHelpHost(app: MonitorApp, content: @Composable () -> Unit) {
    var page by rememberSaveable(stateSaver = HelpPage.Saver) { mutableStateOf(HelpPage()) }
    CompositionLocalProvider(LocalHelp provides { page = it }) {
        content()
        if (page.metric != null || page.group != null) {
            val reports by app.sources.reports.collectAsStateWithLifecycle()
            MetricHelpSheet(page, if (page.context == HelpContext.DIAGNOSTICS) reports[page.metric] else null,
                onPage = { page = it }, onClose = { page = HelpPage() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MetricHelpSheet(page: HelpPage, report: SourceResult?,
    onPage: (HelpPage) -> Unit, onClose: () -> Unit) {
    val back: () -> Unit = { if (page.metric != null && page.group != null) onPage(page.copy(metric = null)) else onClose() }
    val title = page.metric?.let { metricTitle(it) } ?: helpGroupTitle(page.group!!)
    val groupList = rememberLazyListState()
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).testTag("metric-help-sheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page.metric != null && page.group != null) {
                    val label = stringResource(R.string.help_back)
                    IconButton(back, Modifier.size(48.dp).semantics { contentDescription = label }) { Text("←") }
                }
                Text(title, Modifier.weight(1f).semantics { heading() }.testTag("help-title"), style = MaterialTheme.typography.titleLarge)
                val closeLabel = stringResource(R.string.help_close)
                IconButton(onClose, Modifier.size(48.dp).semantics { contentDescription = closeLabel }) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }
            HorizontalDivider()
            val metric = page.metric
            if (metric == null) {
                LazyColumn(Modifier.weight(1f).testTag("help-list"), state = groupList, contentPadding = PaddingValues(16.dp)) {
                    items(page.group!!.metrics, key = { it.name }) { item ->
                        TextButton(onClick = { onPage(page.copy(metric = item)) }, modifier = Modifier.fillMaxWidth().testTag("help-entry-${item.name}")) {
                            Text(metricTitle(item), Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        }
                    }
                }
            } else key(metric, page.context) {
                val scroll = rememberLazyListState()
                LazyColumn(Modifier.weight(1f).testTag("help-content"), state = scroll,
                    contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { Text(stringResource(if (page.context == HelpContext.HISTORY) R.string.help_history_reference else R.string.help_reference), style = MaterialTheme.typography.bodySmall) }
                    if (page.context == HelpContext.DIAGNOSTICS) item {
                        if (report == null) Text(stringResource(R.string.help_no_check)) else SourceHelpSummary(report)
                    }
                    items(metricHelp(metric).sections, key = { it.title }) { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(section.title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(section.text), Modifier.testTag("help-section-${section.title}"))
                            if (section.title == R.string.help_section_source) Text(metric.wire, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (page.context == HelpContext.HISTORY) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.help_chart_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(if (metric == Metric.STATE || metric.name.endsWith("_PRESSURE")) R.string.help_chart_states else R.string.help_chart_values))
                            if (metric.isProbe) Text(stringResource(R.string.help_chart_probe))
                        }
                    }
                    item { Text(stringResource(R.string.help_availability), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
                    item { Text(stringResource(R.string.help_no_data)) }
                    items(Availability.entries) { status ->
                        Text("${status.name}\n${stringResource(availabilityHelp(status))}")
                    }
                    item { Text(stringResource(R.string.help_reason_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
                    items(SourceReason.entries.filter { it != SourceReason.NONE }) { reason ->
                        Text("${reason.name.lowercase(java.util.Locale.ROOT)}\n${stringResource(sourceReasonHelp(reason))}")
                    }
                }
            }
        }
    }
}

@Composable fun SourceHelpSummary(report: SourceResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.help_checked_at, formatTime(report.checkedAt)), style = MaterialTheme.typography.labelSmall)
        Text("${report.status.name}\n${stringResource(availabilityHelp(report.status))}")
        Text(stringResource(R.string.help_source_label, stringResource(sourceNameHelp(report.source))))
        Text(stringResource(R.string.help_scope_label, stringResource(sourceScopeHelp(report.scope))))
        Text("${report.reason.name.lowercase(java.util.Locale.ROOT)}\n${stringResource(sourceReasonHelp(report.reason))}")
        Text("${report.source.name.lowercase(java.util.Locale.ROOT)} · ${report.scope.name.lowercase(java.util.Locale.ROOT)}", style = MaterialTheme.typography.labelSmall)
    }
}
