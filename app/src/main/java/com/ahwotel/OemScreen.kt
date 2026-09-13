package com.ahwotel

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahwotel.oem.*
import kotlinx.coroutines.*

fun oemStringId(context: Context, key: String): Int = OemTextResources[key] ?: 0
@Composable fun oemCategoryTitle(category: OemCategory) = stringResource(oemStringId(LocalContext.current, "oem_category_${category.name.lowercase()}"))
@Composable fun oemMetricTitle(metric: OemMetric) = stringResource(oemStringId(LocalContext.current, "oem_${metric.key}_title"))
@Composable fun oemCode(value: String): String {
    val id = oemStringId(LocalContext.current, "oem_code_${value.lowercase()}")
    return if (id != 0) stringResource(id) else stringResource(R.string.oem_code_unknown)
}
@Composable fun oemProvider(value: String) = stringResource(oemStringId(LocalContext.current, "oem_provider_$value"))
@Composable fun oemValue(row: OemObservation): String = when {
    row.status != OemStatus.AVAILABLE -> oemCode(row.status.name)
    row.metric.kind == ValueKind.BOOLEAN -> oemCode(if (row.number == 1.0) "TRUE" else "FALSE")
    row.metric.kind == ValueKind.STATE -> oemCode(row.text ?: "UNKNOWN")
    row.text != null -> row.text
    else -> "${row.number?.let(::formatNumber) ?: "—"} ${if (row.metric.unit == "1") "" else row.metric.unit}"
}

@Composable fun OemScreen(app: MonitorApp, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val latest by app.oem.latest.collectAsStateWithLifecycle()
    val profiles by app.oem.profiles.collectAsStateWithLifecycle()
    val checking by app.oem.checking.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val inventoryFlow = remember { app.db.oemDao().inventories() }
    val inventoryRows by inventoryFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val inventories by produceState<Map<String, List<InventoryEntry>>>(emptyMap(), inventoryRows) {
        value = withContext(Dispatchers.IO) { inventoryRows.associate { it.provider to InventoryCodec.decode(it.payload) } }
    }
    val eventsFlow = remember { app.db.oemDao().recentEvents() }
    val events by eventsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessionFlow = remember { app.db.dao().sessions() }
    val sessions by sessionFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var session by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionMenu by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("values") }
    var showProfiles by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var categoryName by rememberSaveable { mutableStateOf(OemCategory.DEVICE.name) }
    val category = OemCategory.valueOf(categoryName)
    var inventoryProvider by rememberSaveable { mutableStateOf("android_standard") }
    var help by rememberSaveable { mutableStateOf<String?>(null) }
    var graph by rememberSaveable { mutableStateOf<String?>(null) }
    var range by rememberSaveable { mutableLongStateOf(86_400_000L) }
    var end by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    val from = end - range
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val savedRequest = pendingExport; pendingExport = null
        if (uri != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { OemExporter.history(app, it, PendingExport.decode(requireNotNull(savedRequest)).spec) } ?: error("no_output") }
                message = R.string.export_done
            } catch (_: Exception) { message = R.string.export_failed }
            finally { exporting = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("oem_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TextButton(onBack) { Text(stringResource(R.string.oem_back)) }
            PageTitle(stringResource(R.string.oem_title))
            Text(stringResource(R.string.oem_hint), style = MaterialTheme.typography.bodySmall)
            if (!settings.oem.enabled) Text(oemCode("DISABLED"), color = Accent)
            Button(enabled = !checking, onClick = { scope.launch { app.oem.checkNow() } }) {
                Text(stringResource(if (checking) R.string.checking_sources else R.string.check_sources))
            }
            Choice(mode, listOf("values" to stringResource(R.string.oem_values), "inventory" to stringResource(R.string.oem_inventory), "events" to stringResource(R.string.oem_events))) { mode = it }
        }
        item { Panel(stringResource(R.string.oem_profiles)) {
            if (profiles.isEmpty()) Text(stringResource(R.string.oem_no_observation))
            profiles.values.forEach { profile ->
                Text("${oemProvider(profile.provider)}: ${oemCode(profile.state.name)}", style = MaterialTheme.typography.titleSmall)
                if (showProfiles) {
                Text(stringResource(R.string.oem_observed, formatTime(profile.checkedAt)), style = MaterialTheme.typography.bodySmall)
                if (profile.provider == "samsung_knox") Text(stringResource(R.string.oem_license, oemCode(profile.license)), style = MaterialTheme.typography.bodySmall)
                profile.capabilities.forEach { (c, status) -> Text("${oemCategoryTitle(c)}: ${oemCode(status.name)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            OutlinedButton({ showProfiles = !showProfiles }) { Text(stringResource(R.string.oem_details)) }
            if (showProfiles) Text(stringResource(R.string.oem_profile_hint), style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel(stringResource(R.string.history)) {
            Text("${formatTime(from)} — ${formatTime(end)}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ showHistory = !showHistory }) { Text(stringResource(R.string.oem_period_export)) }
            if (showHistory) {
            Text(stringResource(R.string.oem_history_hint), style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton({ sessionMenu = true }) { Text(session ?: stringResource(R.string.all_sessions)) }
                DropdownMenu(sessionMenu, { sessionMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.all_sessions)) }, onClick = { session = null; sessionMenu = false })
                    sessions.forEach { row -> DropdownMenuItem(text = { Text("${formatTime(row.startedAt)}\n${row.id}") }, onClick = {
                        session = row.id; end = row.endedAt ?: System.currentTimeMillis(); range = (end - row.startedAt + 2000).coerceAtLeast(60_000); sessionMenu = false
                    }) }
                }
            }
            Choice(range, listOf(3_600_000L to stringResource(R.string.hour), 86_400_000L to stringResource(R.string.day),
                604_800_000L to stringResource(R.string.week), settings.retentionDays * 86_400_000L to stringResource(R.string.all_history))) { range = it; end = System.currentTimeMillis() }
            OutlinedButton({ end = System.currentTimeMillis() }) { Text(stringResource(R.string.oem_refresh)) }
            Text("${formatTime(from)} — ${formatTime(end)}", style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.oem_export_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !exporting, onClick = { pendingExport = PendingExport("oem", ExportSpec(session, from, end, true)).encode(); export.launch("ahwotel-oem.json") }) { Text(stringResource(R.string.oem_full_json)) }
            OutlinedButton(enabled = !exporting, onClick = { pendingExport = PendingExport("oem", ExportSpec(session, from, end, false)).encode(); export.launch("ahwotel-oem.csv") }) { Text(stringResource(R.string.oem_numeric_csv)) }
            message?.let { Text(stringResource(it)) }
            }
        } }
        if (mode == "values") {
            item { Choice(categoryName, OemCategory.entries.map { it.name to oemCategoryTitle(it) }) { categoryName = it } }
            items(OemMetric.entries.filter { it.category == category }, key = { it.name }) { metric ->
                val title = oemMetricTitle(metric)
                Panel(title) {
                    Text(metric.wire, style = MaterialTheme.typography.labelSmall)
                    TextButton({ help = metric.name }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.oem_help, title)) }
                    val rows = latest.values.filter { it.metric == metric }.sortedBy { it.provider }
                    if (rows.isEmpty()) Text(stringResource(R.string.oem_no_observation))
                    rows.forEach { row ->
                        Text(oemProvider(row.provider), style = MaterialTheme.typography.titleSmall)
                        Text(oemValue(row), color = if (row.status == OemStatus.AVAILABLE) Accent else Ink)
                        Text(stringResource(R.string.oem_observed, formatTime(row.time)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.oem_provenance, oemCode(row.source.name), oemCode(row.scope.name), oemCode(row.quality.name)), style = MaterialTheme.typography.bodySmall)
                        if (row.reason != OemReason.NONE) Text(oemCode(row.reason.name), style = MaterialTheme.typography.bodySmall)
                        if (metric.kind == ValueKind.NUMBER || metric.kind == ValueKind.BOOLEAN) {
                            val key = OemCoordinator.key(row)
                            OutlinedButton({ graph = if (graph == key) null else key }) { Text(stringResource(R.string.oem_graph)) }
                            if (graph == key) {
                                val points by produceState<List<ChartBucket>>(emptyList(), metric, row.provider, from, end, session, row.time) {
                                    value = withContext(Dispatchers.IO) { app.db.oemDao().chart(oemChartQuery(metric, row.provider, session, from, end)) }
                                }
                                MetricPlot(title, points, from, end, metric.unit,
                                    fixedMaximum = if (metric.kind == ValueKind.BOOLEAN) 1.0 else if (metric == OemMetric.OEM_SYSTEM_CPU) 100.0 else null,
                                    states = if (metric.kind == ValueKind.BOOLEAN) listOf(oemCode("FALSE"), oemCode("TRUE")) else emptyList())
                            }
                        }
                    }
                }
            }
        } else if (mode == "inventory") {
            item { Choice(inventoryProvider, listOf("android_standard", "samsung_knox").map { it to oemProvider(it) }) { inventoryProvider = it } }
            val snapshot = inventoryRows.find { it.provider == inventoryProvider }
            val entries = inventories[inventoryProvider].orEmpty()
            item {
                if (snapshot == null) Text(stringResource(R.string.no_data))
                else Text(stringResource(R.string.oem_inventory_count, entries.size, stringResource(if (snapshot.complete) R.string.oem_complete else R.string.oem_partial), formatTime(snapshot.time)))
            }
            items(entries, key = { "package:${it.packageName}" }) { entry -> Panel(entry.packageName) {
                fun Boolean?.code() = when(this) { true -> "TRUE"; false -> "FALSE"; null -> "UNKNOWN" }
                Text(stringResource(R.string.oem_package_details, entry.versionName ?: "—", entry.versionCode?.toString() ?: "—",
                    oemCode(entry.enabled.code()), oemCode(entry.system.code()), oemCode(entry.managed.code())))
            } }
        } else {
            item { Text(stringResource(R.string.oem_changes_limit)) }
            items(events.filter { it.time in from..end && (session == null || it.sessionId == session) }, key = { "event:${it.id}" }) { event ->
                Panel(oemCode(event.kind)) {
                    val metric = OemMetric.entries.find { it.wire == event.subject }
                    Text(metric?.let { oemMetricTitle(it) } ?: event.subject)
                    Text("${formatTime(event.time)} · ${oemProvider(event.provider)}", style = MaterialTheme.typography.bodySmall)
                    if (event.kind == "STATE_CHANGED") Text(stringResource(R.string.oem_change_values,
                        eventValue(event.before, metric), eventValue(event.after, metric)))
                    else {
                        val before = event.before?.let { runCatching { InventoryCodec.decode(it).firstOrNull()?.versionName }.getOrNull() }
                        val after = event.after?.let { runCatching { InventoryCodec.decode(it).firstOrNull()?.versionName }.getOrNull() }
                        Text(stringResource(R.string.oem_change_values, before ?: "—", after ?: "—"))
                    }
                }
            }
        }
    }
    help?.let { key -> OemHelpDialog(OemMetric.valueOf(key)) { help = null } }
}

@Composable private fun eventValue(value: String?, metric: OemMetric?): String = when {
    value == null -> "—"
    metric?.kind == ValueKind.BOOLEAN && value.toDoubleOrNull() != null -> oemCode(if (value.toDouble() == 1.0) "TRUE" else "FALSE")
    metric?.kind == ValueKind.TEXT -> value
    else -> oemCode(value)
}

@Composable fun OemHelpDialog(metric: OemMetric, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = standardScope(metric)
    val knoxScope = KnoxCapabilityRegistry.find(metric)?.call?.scope
    val scopeText = if (knoxScope != null && knoxScope != scope) stringResource(R.string.oem_scope_compare, oemCode(scope.name), oemCode(knoxScope.name)) else oemCode(scope.name)
    Dialog(onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = Surface) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp)) {
                Text(oemMetricTitle(metric), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("oem_help_body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("meaning" to R.string.help_section_meaning, "units" to R.string.help_section_units,
                        "impact" to R.string.help_section_impact, "reading" to R.string.help_section_reading,
                        "example" to R.string.help_section_example, "limits" to R.string.help_section_limits,
                        "state" to R.string.help_section_state, "source" to R.string.help_section_source).forEach { (key, heading) ->
                        Text(stringResource(heading), style = MaterialTheme.typography.titleSmall)
                        val id = oemStringId(context, "oem_${metric.key}_$key")
                        Text(if (key == "units") stringResource(id, metric.unit, scopeText) else stringResource(id))
                    }
                    Text(metric.wire, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.help_close)) }
            }
        }
    }
}
