package com.ahwotel

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.UUID

val Accent = Color(0xFF52D8BE)
val Surface = Color(0xFF14222D)
val Ink = Color(0xFFE4EEF4)

class MainActivity : AppCompatActivity() {
    override fun onStart() {
        super.onStart()
        val app = application as MonitorApp
        app.scope.launch { app.requestResume(ResumeTrigger.OPEN) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppCompatDelegate.getApplicationLocales().isEmpty)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Color(0xFF8BB9FE),
                background = Color(0xFF0B141D), surface = Surface, onSurface = Ink, onBackground = Ink)) {
                MetricHelpHost(application as MonitorApp) { MonitorRoot(application as MonitorApp) }
            }
        }
    }
}

@Composable fun MonitorRoot(app: MonitorApp) {
    val settings by app.settings.collectAsStateWithLifecycle()
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { try { app.ready.await(); ready = true } catch (_: Exception) { failed = true } }
    LaunchedEffect(settings.language, ready) {
        if (ready && AppCompatDelegate.getApplicationLocales().toLanguageTags() != settings.language)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(settings.language))
    }
    var tab by rememberSaveableState(0)
    val tabs = listOf(R.string.monitoring, R.string.history, R.string.settings, R.string.diagnostics)
    Scaffold(bottomBar = {
        NavigationBar(containerColor = Surface) {
            tabs.forEachIndexed { index, label ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Text(listOf("◉", "▥", "⚙", "≡")[index], fontSize = 22.sp) },
                    label = { Text(stringResource(label), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) })
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!ready) Text(stringResource(if (failed) R.string.initialization_failed else R.string.loading), Modifier.padding(24.dp))
            else when (tab) {
                0 -> MonitorScreen(app)
                1 -> HistoryScreen(app)
                2 -> SettingsScreen(app)
                else -> DiagnosticsScreen(app)
            }
        }
    }
}

@Composable private fun rememberSaveableState(initial: Int) = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(initial) }

@Composable fun PageTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 16.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, color = Accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable fun Panel(title: String, help: Metric? = null, helpContext: HelpContext = HelpContext.GENERAL,
    content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                help?.let { MetricHelpButton(it, helpContext) }
            }
            content()
        }
    }
}

@Composable fun MonitorScreen(app: MonitorApp) {
    var extraPage by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    if (extraPage != null) { AgentTelemetryScreen(app, extraPage == "battery") { extraPage = null }; return }

    val settings by app.settings.collectAsStateWithLifecycle()
    val state by app.state.collectAsStateWithLifecycle()
    val resumeIssue by app.resumeGuard.issue.collectAsStateWithLifecycle()
    val latestFlow = remember { app.db.dao().latest() }
    val latest by latestFlow.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    var reason by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageTitle("AHWOTel", stringResource(R.string.local_first)) }
        resumeIssue?.let { code -> item { Text(stringResource(resumeIssueText(code)), color = MaterialTheme.colorScheme.error) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ extraPage = "battery" }) { Text(stringResource(R.string.at_battery_title)) }
            OutlinedButton({ extraPage = "self" }) { Text(stringResource(R.string.at_self_title)) }
        } }
        item {
            Panel(stringResource(if (state.paused) R.string.paused else if (state.sessionId != null) R.string.running else R.string.idle)) {
                Text(stringResource(if (settings.otlpEnabled) R.string.export_enabled else R.string.local_mode), color = Accent)
                if (state.sessionId != null) {
                    Text(state.sessionId!!, style = MaterialTheme.typography.bodySmall)
                    Text(when (state.mode) {
                        SessionMode.CONTINUOUS -> stringResource(R.string.continuous)
                        SessionMode.TIMED -> stringResource(R.string.remaining, state.remainingSeconds ?: 0L)
                        null -> stringResource(R.string.session_starting)
                    }, Modifier.testTag("session_timing"))
                    Button(onClick = { MonitoringService.stop(context, state.sessionId!!) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop)) }
                } else {
                    OutlinedTextField(reason, { if (it.length <= 256) reason = it }, label = { Text(stringResource(R.string.reason)) }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        try { MonitoringService.start(context, StartRequest(UUID.randomUUID().toString(), settings.continuous,
                            settings.durationSeconds, settings.intervalMs, settings.enabled, reason)); message = null }
                        catch (_: Exception) { message = "command_rejected" }
                    }, enabled = settings.monitoringEnabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start)) }
                    if (!settings.monitoringEnabled) Text(stringResource(R.string.oem_monitoring_disabled))
                }
                Toggle(stringResource(R.string.screen_off), settings.collectScreenOff) { checked ->
                    scope.launch { try { app.saveSettings(settings.copy(collectScreenOff = checked)) } catch (_: Exception) { message = "invalid_configuration" } }
                }
                Text(stringResource(R.string.screen_off_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.background_collection_hint), style = MaterialTheme.typography.bodySmall)
                (message ?: state.error)?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            val sample = latest?.takeIf { state.sessionId == null || it.sessionId == state.sessionId }
            Panel(stringResource(R.string.performance_state), help = Metric.STATE) {
                Text(severityText(sample?.state), fontSize = 28.sp, color = severityColor(sample?.state))
                Text(stringResource(R.string.partial_assessment), style = MaterialTheme.typography.bodySmall)
                sample?.let { Text(formatTime(it.time), color = Color(0xFF91A5B5)) }
            }
        }
        items(listOf(Metric.CPU, Metric.CPU_WAIT, Metric.PROBE_DELAY, Metric.MEMORY_AVAILABLE, Metric.MEMORY_PERCENT, Metric.STORAGE_PERCENT, Metric.THERMAL, Metric.HEADROOM)) { metric ->
            Panel(metricTitle(metric), help = metric) {
                val value = latest?.let { metric.value(it) }
                Text(formatValue(value, metric), style = MaterialTheme.typography.headlineMedium)
                latest?.capabilities?.let { Text(capabilityText(metric, it), style = MaterialTheme.typography.bodySmall, color = Accent) }
                if (metric.isProbe) {
                    Text(stringResource(if (metric == Metric.CPU_WAIT) R.string.cpu_wait_hint else R.string.probe_delay_hint), style = MaterialTheme.typography.bodySmall)
                    latest?.probeInterval?.let { Text(stringResource(R.string.probe_window, it), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable fun Toggle(title: String, checked: Boolean, helpGroup: HelpGroup? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(end = 8.dp))
        helpGroup?.let { HelpGroupButton(it) }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag("toggle:$title"))
    }
}

@Composable fun metricTitle(metric: Metric): String = stringResource(when(metric) {
    Metric.CPU -> R.string.cpu; Metric.MEMORY_AVAILABLE -> R.string.memory_available
    Metric.CPU_WAIT -> R.string.cpu_wait; Metric.PROBE_DELAY -> R.string.probe_delay
    Metric.MEMORY_TOTAL -> R.string.memory_total; Metric.MEMORY_LOW -> R.string.memory_low
    Metric.MEMORY_THRESHOLD -> R.string.memory_threshold; Metric.STORAGE_TOTAL -> R.string.storage_total
    Metric.STORAGE_AVAILABLE -> R.string.storage_available; Metric.STORAGE_USED -> R.string.storage_used
    Metric.MEMORY_PERCENT -> R.string.memory_percent; Metric.STORAGE_PERCENT -> R.string.storage_percent
    Metric.THERMAL -> R.string.thermal; Metric.HEADROOM -> R.string.headroom
    Metric.BATTERY -> R.string.battery; Metric.TEMPERATURE -> R.string.temperature
    Metric.CPU_PRESSURE -> R.string.cpu_pressure; Metric.MEMORY_PRESSURE -> R.string.memory_pressure
    Metric.STORAGE_PRESSURE -> R.string.storage_pressure; Metric.THERMAL_PRESSURE -> R.string.thermal_pressure
    Metric.STATE -> R.string.performance_state; Metric.CHARGING -> R.string.charging
    else -> if (metric.name.startsWith("MEMORY")) R.string.memory else R.string.storage
})

@Composable fun kindTitle(kind: CollectorKind) = stringResource(when(kind) {
    CollectorKind.CPU -> R.string.cpu; CollectorKind.MEMORY -> R.string.memory
    CollectorKind.STORAGE -> R.string.storage; CollectorKind.THERMAL -> R.string.thermal; CollectorKind.BATTERY -> R.string.battery
})

@Composable fun severityText(value: Double?) = stringResource(when(value?.toInt()) {
    0 -> R.string.normal; 1 -> R.string.warning; 2 -> R.string.degraded; 3 -> R.string.critical; else -> R.string.unavailable
})
fun severityColor(value: Double?) = when(value?.toInt()) {
    0 -> Accent; 1 -> Color(0xFFFFD078); 2 -> Color(0xFFFFA56C); 3 -> Color(0xFFFF6F7E); else -> Color.Gray
}
fun formatTime(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))
fun formatNumber(value: Double) = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(value)
fun formatValue(value: Double?, metric: Metric): String = if (value == null) "—" else
    if (metric.unit == "By") "${formatNumber(value / 1024 / 1024)} MiB" else "${formatNumber(value)} ${if (metric.unit == "1") "" else metric.unit}"

@Composable fun capabilityText(metric: Metric, value: String): String {
    val key = when (metric) {
        Metric.CPU -> "CPU"; Metric.CPU_WAIT -> "CPU_WAIT"; Metric.PROBE_DELAY -> "PROBE_DELAY"
        Metric.MEMORY_AVAILABLE, Metric.MEMORY_PERCENT -> "MEMORY"
        Metric.STORAGE_PERCENT -> "STORAGE"; Metric.THERMAL -> "THERMAL"; Metric.HEADROOM -> "HEADROOM"; else -> "BATTERY"
    }
    val status = value.split(';').find { it.startsWith("$key=") }?.substringAfter('=')
    return stringResource(when(status) {
        "AVAILABLE" -> R.string.available; "UNSUPPORTED" -> R.string.unsupported; "DISABLED" -> R.string.disabled
        "WARMING_UP" -> R.string.warming_up; "ERROR" -> R.string.error; else -> R.string.unavailable
    })
}
@Composable fun errorText(code: String): String = stringResource(when(code) {
    "identity_busy" -> R.string.identity_busy; "invalid_configuration" -> R.string.invalid_configuration
    "session_timing_apply_failed" -> R.string.session_timing_apply_failed
    "initialization_failed" -> R.string.initialization_failed; "storage_or_collection_failed" -> R.string.storage_or_collection_failed
    "no_collectors" -> R.string.no_collectors
    "soti_start_rejected" -> R.string.start_rejected; else -> R.string.command_rejected
})
