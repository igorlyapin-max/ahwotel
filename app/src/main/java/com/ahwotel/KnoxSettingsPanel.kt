package com.ahwotel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun KnoxSettingsPanel(app: MonitorApp, managed: Boolean) {
    val state by app.knox.view.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit) { app.scope.launch { app.knox.refresh() } }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && !app.managed.active.value) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { it.readBoundedKnoxKey() } ?: error("no_input")
                }
                val value=bytes.toString(Charsets.UTF_8).trim()
                app.importLocalKnoxKey(value)
                failed=false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { failed=true }
        }
    }
    Panel(stringResource(R.string.knox_title)) {
        Text(stringResource(R.string.knox_api,state.api?.toString() ?: "—"))
        Text(stringResource(if(state.hasKey) R.string.knox_key_present else R.string.knox_key_missing))
        Text(stringResource(when(state.state) {
            KnoxState.NOT_CHECKED -> R.string.knox_not_checked; KnoxState.UNAVAILABLE -> R.string.knox_unavailable
            KnoxState.NOT_CONFIGURED -> R.string.knox_key_missing; KnoxState.READY -> R.string.knox_ready
            KnoxState.WAITING_KEY -> R.string.knox_waiting_key
            KnoxState.PENDING -> R.string.knox_pending; KnoxState.ACTIVE -> R.string.knox_active
            KnoxState.RECORDED -> R.string.knox_recorded; KnoxState.ERROR -> R.string.knox_error; KnoxState.TIMED_OUT -> R.string.knox_timeout
        }))
        state.error?.let { Text(stringResource(R.string.knox_error_code,it)) }
        Text(stringResource(R.string.knox_permissions,state.granted.size))
        if (state.managedKey) Text(stringResource(R.string.knox_managed_key))
        if(failed) Text(stringResource(R.string.knox_import_error))
        OutlinedButton(enabled=!managed && !state.managedKey && state.state!=KnoxState.PENDING,onClick={importer.launch(arrayOf("*/*"))}) { Text(stringResource(R.string.knox_import)) }
        OutlinedButton(enabled=!managed && !state.managedKey && state.hasKey && state.api!=null && state.state!=KnoxState.PENDING,
            onClick={app.scope.launch { app.knox.activate() }}) { Text(stringResource(R.string.knox_activate)) }
        OutlinedButton(onClick={ app.scope.launch { app.knox.refresh() } }) { Text(stringResource(R.string.knox_refresh)) }
        Text(stringResource(R.string.knox_help))
    }
}

private fun java.io.InputStream.readBoundedKnoxKey(): ByteArray {
    val bytes = ByteArray(4097)
    var size = 0
    while (size < bytes.size) {
        val count = read(bytes, size, bytes.size - size)
        if (count < 0) break
        check(count > 0) { "knox_key_read_failed" }
        size += count
    }
    check(size <= 4096) { "knox_key_oversize" }
    return bytes.copyOf(size)
}
