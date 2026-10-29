package com.ahwotel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class HistoryExportUiState(val running: Boolean = false, val result: String? = null)

class HistoryScreenViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app get() = getApplication<MonitorApp>()
    private val _export = MutableStateFlow(HistoryExportUiState(result = saved[RESULT]))
    val export = _export.asStateFlow()

    var selectedSession by mutableStateOf(saved.get<String>(SESSION))
        private set
    var selectedGroups by mutableStateOf(decodeGroups(saved[GROUPS]))
        private set
    var exportJson by mutableStateOf(saved[JSON] ?: false)
        private set
    var pendingRequest by mutableStateOf(saved.get<String>(PENDING))
        private set

    val window = HistoryWindowState(
        initialRange = saved[RANGE] ?: 86_400_000L,
        initialEnd = saved[END] ?: System.currentTimeMillis(),
        initialAtLatest = saved[LATEST] ?: true,
    ) { range, end, latest -> saved[RANGE] = range; saved[END] = end; saved[LATEST] = latest }

    init {
        if (saved.get<Boolean>(ACTIVE) == true) {
            _export.value = HistoryExportUiState(result = "interrupted")
            saved[RESULT] = "interrupted"
            viewModelScope.launch { cleanupInterrupted() }
        }
    }

    fun selectSession(value: String?) { selectedSession = value; saved[SESSION] = value }
    fun selectGroups(value: Set<HistoryExportGroup>) {
        selectedGroups = value
        saved[GROUPS] = HistoryExportGroup.entries.filter(value::contains).joinToString(",", transform = HistoryExportGroup::name)
    }
    fun selectJson(value: Boolean) { exportJson = value; saved[JSON] = value }
    fun prepare(request: HistoryArchiveSpec) {
        pendingRequest = request.encode(); saved[PENDING] = pendingRequest
        saved[RESULT] = null; _export.value = HistoryExportUiState()
    }
    fun cancelPicker() { pendingRequest = null; saved[PENDING] = null }

    fun exportTo(uri: Uri) {
        val raw = pendingRequest ?: return
        cancelPicker()
        if (_export.value.running) return
        val spec = runCatching { HistoryArchiveSpec.decode(raw) }.getOrElse {
            _export.value = HistoryExportUiState(result = "failed"); return
        }
        val resolver = app.contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        saved[ACTIVE] = true; saved[URI] = uri.toString(); saved[RESULT] = null
        _export.value = HistoryExportUiState(running = true)
        viewModelScope.launch {
            var temp: File? = null
            var tempDirectory: File? = null
            try {
                app.logs.event("history_export_started", number = spec.groups.size.toLong())
                tempDirectory = withContext(Dispatchers.IO) {
                    File(app.cacheDir, "history-export-${UUID.randomUUID()}").also { directory ->
                        check(directory.mkdir()) { "temporary_directory_unavailable" }
                    }
                }
                saved[TEMP] = requireNotNull(tempDirectory).absolutePath
                temp = File(requireNotNull(tempDirectory), "archive.zip")
                withContext(Dispatchers.IO) {
                    FileOutputStream(requireNotNull(temp)).use { out ->
                        HistoryArchiveExporter.write(app, out, spec, requireNotNull(tempDirectory))
                        out.fd.sync()
                    }
                }
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { out ->
                        requireNotNull(temp).inputStream().use { it.copyTo(out) }
                        out.flush()
                    }
                        ?: error("output_unavailable")
                }
                app.logs.event("history_export_completed")
                saved[RESULT] = "done"; _export.value = HistoryExportUiState(result = "done")
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) } }
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) } }
                app.logs.event("history_export_failed", error = true)
                saved[RESULT] = "failed"; _export.value = HistoryExportUiState(result = "failed")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { tempDirectory?.deleteRecursively() }
                saved[TEMP] = null
                saved[ACTIVE] = false; saved[URI] = null
                runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                if (_export.value.running) _export.value = HistoryExportUiState(result = saved[RESULT])
            }
        }
    }

    private suspend fun cleanupInterrupted() {
        val tempPath = saved.get<String>(TEMP)
        val uri = saved.get<String>(URI)?.let(Uri::parse)
        withContext(Dispatchers.IO) {
            tempPath?.let(::File)?.takeIf { it.parentFile == app.cacheDir && it.name.startsWith("history-export-") }
                ?.deleteRecursively()
            uri?.let { value -> runCatching { app.contentResolver.delete(value, null, null) }
                runCatching { app.contentResolver.releasePersistableUriPermission(value, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        }
        saved[ACTIVE] = false; saved[TEMP] = null; saved[URI] = null
        app.logs.event("history_export_interrupted", error = true)
    }

    companion object {
        private const val SESSION="history.session"; private const val GROUPS="history.export.groups"
        private const val JSON="history.export.json"; private const val PENDING="history.export.pending"
        private const val RANGE="history.range"; private const val END="history.end"; private const val LATEST="history.latest"
        private const val ACTIVE="history.export.active"; private const val URI="history.export.uri"
        private const val TEMP="history.export.temp"; private const val RESULT="history.export.result"
        private fun decodeGroups(raw: String?): Set<HistoryExportGroup> = raw?.split(',')?.mapNotNull {
            runCatching { HistoryExportGroup.valueOf(it) }.getOrNull()
        }?.toSet() ?: HistoryExportGroup.entries.toSet()
    }
}
