package com.ahwotel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Stable class HistoryWindowState(
    initialRange: Long = 86_400_000L,
    initialEnd: Long = System.currentTimeMillis(),
    initialAtLatest: Boolean = true,
) {
    var range by mutableLongStateOf(initialRange)
    var end by mutableLongStateOf(initialEnd)
    var atLatest by mutableStateOf(initialAtLatest)
    val from get() = end - range

    fun earlier() { end -= range / 2; atLatest = false }
    fun later(now: Long = System.currentTimeMillis()) {
        end = minOf(now, end + range / 2)
        atLatest = end == now
    }
    fun zoomIn(minimum: Long = 10_000L) { range = (range / 2).coerceAtLeast(minimum) }
    fun zoomOut(maximum: Long) { range = (range * 2).coerceAtMost(maximum) }
    fun latest(selectedRange: Long = range) {
        range = selectedRange
        end = System.currentTimeMillis()
        atLatest = true
    }
}

/** Shared-window controls live outside the plot's point-selection hit area. */
@Composable fun HistoryChartControls(id: String, from: Long, to: Long,
    canGoLater: Boolean, canZoomIn: Boolean, canZoomOut: Boolean,
    earlier: () -> Unit, later: () -> Unit, zoomIn: () -> Unit, zoomOut: () -> Unit) {
    Row(Modifier.testTag("history_controls_$id"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ChartControl(id, "earlier", R.string.earlier, true, earlier)
        ChartControl(id, "later", R.string.later, canGoLater, later)
        ChartControl(id, "zoom_in", R.string.zoom_in, canZoomIn, zoomIn)
        ChartControl(id, "zoom_out", R.string.zoom_out, canZoomOut, zoomOut)
    }
    Text("${formatTime(from)} → ${formatTime(to)}", Modifier.testTag("history_period_$id"),
        style = MaterialTheme.typography.labelSmall)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ChartControl(id: String, action: String, labelId: Int, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(labelId)
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, Modifier.size(48.dp).testTag("history_${id}_$action")
            .semantics { contentDescription = label }, enabled = enabled) {
            val color = LocalContentColor.current
            Canvas(Modifier.size(24.dp)) {
                val stroke = 2.dp.toPx()
                fun point(x: Float, y: Float) = Offset(size.width * x / 24, size.height * y / 24)
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                    drawLine(color, point(x1,y1), point(x2,y2), stroke, StrokeCap.Round)
                if(action == "earlier" || action == "later") {
                    val edge = if(action == "earlier") 15f else 9f
                    val tip = 24f - edge
                    line(edge,6f,tip,12f); line(tip,12f,edge,18f)
                } else {
                    drawCircle(color, size.width * 7 / 24, point(10f,10f), style = Stroke(stroke))
                    line(15f,15f,21f,21f)
                    line(7f,10f,13f,10f)
                    if(action == "zoom_in") line(10f,7f,10f,13f)
                }
            }
        }
    }
}
