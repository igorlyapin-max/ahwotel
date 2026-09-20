package com.ahwotel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch

internal val LocalSettingsEditingEnabled = staticCompositionLocalOf { true }
internal enum class ProfileChangeSection { SAMPLING, RETENTION, BATTERY, SELF, OEM, THRESHOLDS, OTLP, SOTI, DIAGNOSTICS }

internal fun deploymentProfileChangedSections(saved: Settings, preview: Settings) = buildList {
    if (preview.monitoringEnabled != saved.monitoringEnabled || preview.intervalMs != saved.intervalMs ||
        preview.durationSeconds != saved.durationSeconds || preview.maxDurationSeconds != saved.maxDurationSeconds ||
        preview.continuous != saved.continuous || preview.resumeOnBoot != saved.resumeOnBoot ||
        preview.resumeOnOpen != saved.resumeOnOpen || preview.collectScreenOff != saved.collectScreenOff ||
        preview.indirectCpu != saved.indirectCpu || preview.enabled != saved.enabled) add(ProfileChangeSection.SAMPLING)
    if (preview.retentionDays != saved.retentionDays || preview.storageMiB != saved.storageMiB) add(ProfileChangeSection.RETENTION)
    if (preview.batterySettings != saved.batterySettings) add(ProfileChangeSection.BATTERY)
    if (preview.selfTelemetry != saved.selfTelemetry) add(ProfileChangeSection.SELF)
    if (preview.oem != saved.oem || preview.uploadIntervalSeconds != saved.uploadIntervalSeconds ||
        preview.backendEnvironment != saved.backendEnvironment) add(ProfileChangeSection.OEM)
    if (preview.thresholds != saved.thresholds) add(ProfileChangeSection.THRESHOLDS)
    if (preview.otlpEnabled != saved.otlpEnabled || preview.endpoint != saved.endpoint || preview.allowHttp != saved.allowHttp ||
        preview.queueHours != saved.queueHours || preview.queueMiB != saved.queueMiB) add(ProfileChangeSection.OTLP)
    if (preview.sotiEnabled != saved.sotiEnabled) add(ProfileChangeSection.SOTI)
    if (preview.diagnostic != saved.diagnostic || preview.fileLogging != saved.fileLogging) add(ProfileChangeSection.DIAGNOSTICS)
}

@Composable fun SettingsScreen(app: MonitorApp) {
    val saved by app.settings.collectAsStateWithLifecycle()
    val endpointRecovered by app.config.endpointRecovered.collectAsStateWithLifecycle(initialValue = false)
    val managedKeys by app.managed.keys.collectAsStateWithLifecycle()
    val managedVersion by app.managed.version.collectAsStateWithLifecycle()
    val managedRejected by app.managed.rejected.collectAsStateWithLifecycle()
    val managedActive by app.managed.active.collectAsStateWithLifecycle()
    val managedProfileId by app.managed.profileId.collectAsStateWithLifecycle()
    val managedRevision by app.managed.revision.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    var interval by remember(saved) { mutableStateOf(saved.intervalMs) }
    var duration by remember(saved) { mutableStateOf(saved.durationSeconds.toString()) }
    var maximum by remember(saved) { mutableStateOf(saved.maxDurationSeconds.toString()) }
    var days by remember(saved) { mutableStateOf(saved.retentionDays.toString()) }
    var size by remember(saved) { mutableStateOf(saved.storageMiB.toString()) }
    var queueHours by remember(saved) { mutableStateOf(saved.queueHours.toString()) }
    var queueSize by remember(saved) { mutableStateOf(saved.queueMiB.toString()) }
    var cpu by remember(saved) { mutableStateOf(saved.thresholds.cpu.joinToString(",")) }
    var memory by remember(saved) { mutableStateOf(saved.thresholds.memory.joinToString(",")) }
    var storage by remember(saved) { mutableStateOf(saved.thresholds.storage.joinToString(",")) }
    var thermal by remember(saved) { mutableStateOf(saved.thresholds.thermal.joinToString(",")) }
    var raise by remember(saved) { mutableStateOf(saved.thresholds.raiseSeconds.toString()) }
    var recover by remember(saved) { mutableStateOf(saved.thresholds.recoverSeconds.toString()) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var profileName by rememberSaveable { mutableStateOf("ahwotel") }
    var profileRevision by rememberSaveable { mutableStateOf("1") }
    var pendingImport by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val exportProfile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = try {
                val raw = app.exportDeploymentProfile(profileName, profileRevision.toLong())
                context.contentResolver.openOutputStream(uri)?.use { it.write(raw.toByteArray()) } ?: error("no_output")
                "profile_exported"
            } catch (_: Exception) { "profile_export_failed" }
        }
    }
    val importProfile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.use(DeploymentProfileCodec::read)
                    ?: error("no_input")
                DeploymentProfileCodec.decode(raw)
                pendingImport = raw
            } catch (_: Exception) { message = "profile_import_failed" }
        }
    }
    CompositionLocalProvider(LocalSettingsEditingEnabled provides !managedActive) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageTitle(stringResource(R.string.settings)) }
        if (endpointRecovered) item { Text(stringResource(R.string.otlp_port_recovered), color = MaterialTheme.colorScheme.error) }
        item {
            if (managedActive) Text(stringResource(R.string.managed_mode_hint, managedProfileId, managedRevision))
            else if (managedKeys.isNotEmpty()) Text(stringResource(R.string.oem_managed_hint, managedVersion, managedKeys.sorted().joinToString(", ")))
            if (managedRejected) Text(stringResource(R.string.oem_managed_rejected), color = MaterialTheme.colorScheme.error)
        }
        item { Panel(stringResource(R.string.language)) {
            CompositionLocalProvider(LocalSettingsEditingEnabled provides true) {
                Choice(draft.language, listOf("en" to "English", "ru" to "Русский")) { selected ->
                    draft = draft.copy(language = selected)
                    if (managedActive) scope.launch { runCatching { app.saveSettings(saved.copy(language = selected)) } }
                }
            }
        } }
        item { Panel(stringResource(R.string.deployment_profile)) {
            Text(stringResource(R.string.deployment_profile_hint), style = MaterialTheme.typography.bodySmall)
            Field(stringResource(R.string.profile_id), profileName) { profileName = it.trim() }
            Field(stringResource(R.string.profile_revision), profileRevision) { profileRevision = it }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !managedActive, onClick = { exportProfile.launch("ahwotel-profile.json") }) {
                    Text(stringResource(R.string.export_profile))
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !managedActive, onClick = { importProfile.launch(arrayOf("application/json", "text/json", "text/plain")) }) {
                    Text(stringResource(R.string.import_profile))
                }
            }
            if (managedActive) Text(stringResource(R.string.managed_read_only), color = Accent)
        } }
        item { Panel(stringResource(R.string.sampling)) {
            Toggle(stringResource(R.string.continuous), draft.continuous) { draft = draft.copy(continuous = it) }
            Toggle(stringResource(R.string.resume_boot), draft.resumeOnBoot) { draft = draft.copy(resumeOnBoot = it) }
            Toggle(stringResource(R.string.resume_open), draft.resumeOnOpen) { draft = draft.copy(resumeOnOpen = it) }
            Text(stringResource(R.string.resume_help), style = MaterialTheme.typography.bodySmall)
            Field(stringResource(R.string.duration), duration) { duration = it }
            Field(stringResource(R.string.maximum_duration), maximum) { maximum = it }
            Text(stringResource(R.string.session_timing_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.interval))
            Choice(interval, Settings.INTERVALS.map { it to stringResource(R.string.seconds, it / 1000) }) { interval = it }
            Toggle(stringResource(R.string.screen_off), draft.collectScreenOff) { draft = draft.copy(collectScreenOff = it) }
            Text(stringResource(R.string.screen_off_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.wake_hint), style = MaterialTheme.typography.bodySmall)
            CollectorKind.entries.filter { it != CollectorKind.BATTERY }.forEach { kind ->
                Toggle(kindTitle(kind), kind in draft.enabled, HelpGroup.valueOf(kind.name)) {
                    draft = draft.copy(enabled = if (it) draft.enabled + kind else draft.enabled - kind)
                }
            }
            Toggle(stringResource(R.string.indirect_cpu), draft.indirectCpu, HelpGroup.INDIRECT) { draft = draft.copy(indirectCpu = it) }
            Text(stringResource(R.string.indirect_cpu_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.session_snapshot), style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel(stringResource(R.string.retention)) {
            Field(stringResource(R.string.retention_days), days) { days = it }
            Field(stringResource(R.string.storage_limit), size) { size = it }
            Text(stringResource(R.string.storage_hint), style = MaterialTheme.typography.bodySmall)
        } }
        item { BatterySettingsPanel(draft) { draft = it } }
        item { SelfSettingsPanel(draft) { draft = it } }
        item { OemSettingsPanel(draft, managedKeys) { draft = it } }
        item { Panel(stringResource(R.string.thresholds)) {
            Text(stringResource(R.string.threshold_hint), style = MaterialTheme.typography.bodySmall)
            Field(stringResource(R.string.cpu), cpu) { cpu = it }
            Field(stringResource(R.string.memory_percent), memory) { memory = it }
            Field(stringResource(R.string.storage_percent), storage) { storage = it }
            Field(stringResource(R.string.thermal), thermal) { thermal = it }
            Field(stringResource(R.string.raise_window), raise) { raise = it }
            Field(stringResource(R.string.recover_window), recover) { recover = it }
        } }
        item { Panel(stringResource(R.string.otlp)) {
            Toggle(stringResource(R.string.otlp), draft.otlpEnabled) { draft = draft.copy(otlpEnabled = it) }
            Text(stringResource(R.string.otlp_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.otlp_auth_none), style = MaterialTheme.typography.bodySmall)
            Toggle(stringResource(R.string.otlp_allow_http), draft.allowHttp) { draft = draft.withHttpAllowed(it) }
            Text(stringResource(R.string.otlp_http_help), style = MaterialTheme.typography.bodySmall)
            Field(stringResource(R.string.endpoint), draft.endpoint) { draft = draft.copy(endpoint = it.trim()) }
            Field(stringResource(R.string.queue_hours), queueHours) { queueHours = it }
            Field(stringResource(R.string.queue_mib), queueSize) { queueSize = it }
            Field(stringResource(R.string.device_id), draft.deviceId) { draft = draft.copy(deviceId = it.trim()) }
        } }
        item { Panel(stringResource(R.string.soti)) {
            Toggle(stringResource(R.string.soti), draft.sotiEnabled) { draft = draft.copy(sotiEnabled = it) }
            Text(stringResource(R.string.soti_hint), style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel(stringResource(R.string.diagnostics)) {
            Text(stringResource(R.string.debug_level))
            Choice(draft.diagnostic, listOf(DiagnosticLevel.OFF to stringResource(R.string.off),
                DiagnosticLevel.BASIC to stringResource(R.string.basic), DiagnosticLevel.VERBOSE to stringResource(R.string.verbose)), enabled = "debug_mode" !in managedKeys) {
                draft = draft.copy(diagnostic = it)
            }
            Toggle(stringResource(R.string.file_logging), draft.fileLogging) { draft = draft.copy(fileLogging = it) }
            Text(stringResource(R.string.logging_hint), style = MaterialTheme.typography.bodySmall)
        } }
        item {
            message?.let {
                val success = it in setOf("saved", "profile_exported", "profile_imported")
                val text = when (it) {
                    "saved" -> stringResource(R.string.saved)
                    "profile_exported" -> stringResource(R.string.profile_exported)
                    "profile_imported" -> stringResource(R.string.profile_imported)
                    else -> errorText(it)
                }
                Text(text, color = if (success) Accent else MaterialTheme.colorScheme.error)
            }
            Button(enabled = !saving && !managedActive, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    saving = true
                    try {
                        fun thresholds(text: String) = text.split(',').map { it.trim().toDouble() }
                        val next = draft.copy(intervalMs = interval, durationSeconds = duration.toLong(), maxDurationSeconds = maximum.toLong(),
                            retentionDays = days.toInt(), storageMiB = size.toInt(), queueHours = queueHours.toInt(), queueMiB = queueSize.toInt(),
                            thresholds = Thresholds(thresholds(cpu), thresholds(memory), thresholds(storage), thresholds(thermal), raise.toInt(), recover.toInt()),
                            verboseUntil = if (draft.diagnostic == DiagnosticLevel.VERBOSE) System.currentTimeMillis() + 900_000 else 0)
                        app.saveSettings(next)
                        message = "saved"
                    } catch (e: Exception) { message = when (e.message) {
                        "identity_busy" -> "identity_busy"
                        "session_timing_apply_failed" -> "session_timing_apply_failed"
                        "managed_read_only" -> "managed_read_only"
                        else -> "invalid_configuration"
                    } }
                    finally { saving = false }
                }
            }) { Text(stringResource(R.string.save)) }
        }
    }
    }
    pendingImport?.let { raw ->
        val profile = remember(raw) { DeploymentProfileCodec.decode(raw) }
        val preview = remember(raw, saved) { profile.applyTo(saved) }
        val changes = deploymentProfileChangedSections(saved,preview).map { section -> stringResource(when(section) {
            ProfileChangeSection.SAMPLING -> R.string.sampling
            ProfileChangeSection.RETENTION -> R.string.retention
            ProfileChangeSection.BATTERY -> R.string.at_battery_title
            ProfileChangeSection.SELF -> R.string.at_self_title
            ProfileChangeSection.OEM -> R.string.oem_title
            ProfileChangeSection.THRESHOLDS -> R.string.thresholds
            ProfileChangeSection.OTLP -> R.string.otlp
            ProfileChangeSection.SOTI -> R.string.soti
            ProfileChangeSection.DIAGNOSTICS -> R.string.diagnostics
        }) }
        AlertDialog(onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.import_profile)) },
            text = { Text(stringResource(R.string.profile_import_confirmation, "${profile.profileId}:${profile.revision}") + "\n\n" +
                stringResource(R.string.profile_changed_sections, changes.ifEmpty { listOf(stringResource(R.string.profile_no_changes)) }.joinToString(", "))) },
            dismissButton = { TextButton({ pendingImport = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton({
                pendingImport = null
                scope.launch {
                    message = try { app.importDeploymentProfile(raw); "profile_imported" }
                    catch (_: Exception) { "profile_import_failed" }
                }
            }) { Text(stringResource(R.string.apply_profile)) } })
    }
}

@Composable fun Field(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled && LocalSettingsEditingEnabled.current)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun <T> Choice(selected: T, choices: List<Pair<T, String>>, enabled: Boolean = true, onChoose: (T) -> Unit) {
    val editing = enabled && LocalSettingsEditingEnabled.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (key, label) -> FilterChip(selected == key, { onChoose(key) }, label = { Text(label) }, enabled = editing) }
    }
}
