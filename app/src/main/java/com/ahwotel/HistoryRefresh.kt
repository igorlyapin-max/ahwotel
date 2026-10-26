package com.ahwotel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.room.withTransaction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectLatest

internal enum class HistoryRefreshReason { INITIAL, MANUAL, RETRY }

internal data class HistoryRevision(
    val capturedAt: Long,
    val sampleThroughId: Long,
    val agentThroughId: Long,
    val oemThroughId: Long,
    val oemInventoryThroughId: Long,
    val oemEventThroughId: Long,
    val sessions: List<SessionRow>,
)

@Stable
internal class HistoryRefreshController {
    private val requests = Channel<HistoryRefreshReason>(Channel.CONFLATED)
    internal val requestFlow = requests.receiveAsFlow()
    var revision by mutableStateOf<HistoryRevision?>(null)
        internal set
    var loading by mutableStateOf(false)
        internal set
    var failed by mutableStateOf(false)
        internal set

    fun refresh(reason: HistoryRefreshReason = HistoryRefreshReason.MANUAL) {
        requests.trySend(reason)
    }
}

@Composable
internal fun rememberHistoryRefreshController(
    app: MonitorApp,
    enabled: Boolean = true,
): HistoryRefreshController {
    val controller = remember { HistoryRefreshController() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(controller, app) {
        controller.requestFlow.collectLatest { reason ->
            val started = System.currentTimeMillis()
            controller.loading = true
            controller.failed = false
            app.logs.event("history_refresh_started", diagnostic = true, probeStatus = reason.name.lowercase())
            try {
                val next = app.db.withTransaction {
                    HistoryRevision(
                        capturedAt = System.currentTimeMillis(),
                        sampleThroughId = app.db.dao().latestId(),
                        agentThroughId = app.db.agentDao().latestId(),
                        oemThroughId = app.db.oemDao().latestId(),
                        oemInventoryThroughId = app.db.oemDao().latestInventoryId(),
                        oemEventThroughId = app.db.oemDao().latestEventId(),
                        sessions = app.db.dao().sessionsSnapshot(),
                    )
                }
                controller.revision = next
                controller.loading = false
                app.logs.event("history_refresh_completed", diagnostic = true,
                    number = System.currentTimeMillis() - started, probeStatus = reason.name.lowercase())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                controller.loading = false
                controller.failed = true
                app.logs.event("history_refresh_failed", error = true, probeStatus = reason.name.lowercase())
            }
        }
    }
    LaunchedEffect(controller, enabled, lifecycleOwner) {
        if (!enabled) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (controller.revision == null) controller.refresh(HistoryRefreshReason.INITIAL)
        }
    }
    return controller
}

@Composable
internal fun HistoryRefreshControls(controller: HistoryRefreshController,
    completedAt: Long? = controller.revision?.capturedAt,
    contentLoading: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            enabled = !controller.loading && !contentLoading,
            onClick = { controller.refresh() },
            modifier = Modifier.testTag("history_refresh"),
        ) { Text(stringResource(R.string.history_refresh)) }
        completedAt?.let {
            Text(stringResource(R.string.history_last_refreshed, formatTime(it)))
        }
        if (controller.loading || contentLoading) LinearProgressIndicator(Modifier.testTag("history_refresh_loading"))
        if (controller.failed) {
            Text(stringResource(R.string.history_refresh_failed))
            OutlinedButton(
                onClick = { controller.refresh(HistoryRefreshReason.RETRY) },
                modifier = Modifier.testTag("history_refresh_retry"),
            ) { Text(stringResource(R.string.retry)) }
        }
    }
}
