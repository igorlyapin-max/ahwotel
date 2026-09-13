package com.ahwotel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.util.TimeZone
import kotlin.math.abs

@Composable fun MetricPlot(title: String, points: List<ChartBucket>, from: Long, to: Long,
    unit: String, fixedMaximum: Double? = null, states: List<String> = emptyList(),
    aggregation: MetricAggregation = MetricAggregation.GAUGE, nominalStates: Map<Int,String> = emptyMap(),
    mixedBuckets: Set<Long> = emptySet(), gapBuckets: Set<Long> = emptySet()) {
    val nominal = aggregation == MetricAggregation.STATE
    val stepped = nominal || states.isNotEmpty()
    val rangeVisible = !stepped && aggregation == MetricAggregation.GAUGE
    val mixedLabel=stringResource(R.string.chart_mixed)
    val gapLabel=stringResource(R.string.chart_gap)
    val unknownLabel=stringResource(R.string.chart_unknown_state)
    fun stateLabel(v: Double?) = if(nominal) nominalStates[v?.toInt()] ?: unknownLabel
        else states.getOrNull(v?.toInt() ?: -1) ?: unknownLabel
    fun pointValue(p: ChartBucket) = if(states.isNotEmpty()) p.high else p.mean
    val locale = LocalConfiguration.current.locales[0]
    val zone = TimeZone.getDefault()
    val scale = remember(points, unit, fixedMaximum, aggregation, stepped) {
        ChartScale.create(if(rangeVisible) points.flatMap { listOfNotNull(it.low,it.high) } else points.mapNotNull(::pointValue),unit,fixedMaximum,nominal)
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = Ink)
    val labels = scale.ticks.reversed().map { scale.label(it, locale) }
    val measured = labels.map { measurer.measure(it, style) }
    val yWidth = measured.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
    val labelHeight = measured.maxOfOrNull { it.size.height }?.toFloat() ?: 0f
    val density = LocalDensity.current
    val gap = with(density) { 10.dp.toPx() }
    val xLeft = yWidth + gap
    val top = labelHeight / 2 + gap
    var selected by remember(points, from, to) { mutableStateOf<ChartBucket?>(null) }
    val yTitle = stringResource(R.string.chart_axis_value, scale.unit)
    val xTitle = stringResource(R.string.chart_axis_time, zone.getDisplayName(zone.inDaylightTime(java.util.Date(from)), TimeZone.SHORT, locale))
    val description = "$yTitle: ${labels.reversed().joinToString(", ")}. $xTitle: " +
        listOf(from, from + (to - from) / 2, to).joinToString("; ") { chartTimeLabel(it, to - from, locale, zone).replace('\n', ' ') }
    val nextPoint = stringResource(R.string.chart_next_point)
    val previousPoint = stringResource(R.string.chart_previous_point)
    val valid = points.filter { it.mean != null || it.bucket in mixedBuckets || it.bucket in gapBuckets }
    val selectedDescription = selected?.let { p -> "${formatTime(p.time)}: " +
        (if(p.bucket in gapBuckets) gapLabel else if(p.bucket in mixedBuckets) mixedLabel
        else if(stepped) stateLabel(pointValue(p)) else "${scale.label(p.mean!!, locale)} ${scale.unit}") }.orEmpty()
    fun select(delta: Int): Boolean {
        if (valid.isEmpty()) return false
        selected = valid[(valid.indexOf(selected) + delta).coerceIn(0, valid.lastIndex)]
        return true
    }
    Text(yTitle, style = MaterialTheme.typography.labelMedium)
    if (valid.isEmpty()) Text(stringResource(R.string.no_data), Modifier.padding(vertical = 20.dp))
    else {
        Canvas(Modifier.fillMaxWidth().height(with(density) { (180.dp.toPx() + labelHeight * 3 + gap * 3).toDp() })
            .semantics {
                contentDescription = title
                stateDescription = "$description. $selectedDescription"
                customActions = listOf(CustomAccessibilityAction(previousPoint) { select(-1) }, CustomAccessibilityAction(nextPoint) { select(1) })
            }
            .pointerInput(points, from, to, xLeft, top) { detectTapGestures { position ->
                val right = size.width - gap
                val bottom = size.height - labelHeight * 2 - gap * 2
                if (position.x in xLeft..right && position.y in top..bottom) {
                    val t = chartTimeAtFraction(from,to,(position.x-xLeft)/(right-xLeft))
                    selected = valid.minByOrNull { abs(it.time - t) }
                }
            } }) {
            val right = size.width - gap
            val bottom = size.height - labelHeight * 2 - gap * 2
            fun x(t: Long) = xLeft + ((t - from).toDouble() / (to - from).coerceAtLeast(1) * (right - xLeft)).toFloat()
            fun y(v: Double) = bottom - ((v - scale.low) / (scale.high - scale.low) * (bottom - top)).toFloat()
            scale.ticks.reversed().forEachIndexed { i, tick ->
                val py = y(tick)
                drawLine(Color(0xFF344954), Offset(xLeft, py), Offset(right, py), 1.dp.toPx())
                drawText(measured[i], topLeft = Offset(yWidth - measured[i].size.width, py - measured[i].size.height / 2))
            }
            drawLine(Ink, Offset(xLeft, top), Offset(xLeft, bottom), 1.dp.toPx())
            drawLine(Ink, Offset(xLeft, bottom), Offset(right, bottom), 1.dp.toPx())
            val count = if ((right - xLeft) > 550.dp.toPx() && density.fontScale <= 1.1f) 5 else 3
            repeat(count) { i ->
                val t = from + (to - from) * i / (count - 1)
                val px = x(t)
                val label = measurer.measure(chartTimeLabel(t, to - from, locale, zone), style)
                drawLine(Ink, Offset(px, bottom), Offset(px, bottom + 4.dp.toPx()), 1.dp.toPx())
                drawText(label, topLeft = Offset((px - label.size.width / 2).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f)), bottom + gap))
            }
            clipRect(xLeft, top, right, bottom) {
                var previous: ChartBucket? = null
                points.forEach { p ->
                    if(p.bucket in gapBuckets || p.bucket in mixedBuckets) {
                        val center=Offset(x(p.time),(top+bottom)/2)
                        drawLine(Ink,center-Offset(3.dp.toPx(),3.dp.toPx()),center+Offset(3.dp.toPx(),3.dp.toPx()),2.dp.toPx())
                        drawLine(Ink,center-Offset(3.dp.toPx(),-3.dp.toPx()),center+Offset(3.dp.toPx(),-3.dp.toPx()),2.dp.toPx())
                    }
                    val mean = p.mean; val low = p.low; val high = p.high
                    if (mean != null && low != null && high != null) {
                        val value = pointValue(p)!!
                        if (rangeVisible) drawLine(Accent.copy(alpha = 0.35f), Offset(x(p.time), y(low)), Offset(x(p.time), y(high)), 3.dp.toPx())
                        val old = previous
                        // Gaps are recorded as nulls or new segments. Empty aggregation buckets
                        // also occur when zooming closer than the sample interval; those are not gaps.
                        if (old?.mean != null && p.segment >= 0 && p.sessionId == old.sessionId && p.segment == old.segment) {
                            val oldValue = pointValue(old) ?: old.mean
                            if (!stepped) drawLine(Accent, Offset(x(old.time), y(oldValue)), Offset(x(p.time), y(value)), 2.dp.toPx())
                            else {
                                drawLine(Accent, Offset(x(old.time), y(oldValue)), Offset(x(p.time), y(oldValue)), 2.dp.toPx())
                                drawLine(Accent, Offset(x(p.time), y(oldValue)), Offset(x(p.time), y(value)), 2.dp.toPx())
                            }
                        }
                        drawCircle(Accent, 2.dp.toPx(), Offset(x(p.time), y(value)))
                    }
                    previous = p
                }
                selected?.let { p ->
                    val value = pointValue(p)
                    value?.let { drawCircle(Color.White, 4.dp.toPx(), Offset(x(p.time), y(it))) }
                }
            }
        }
        Text(xTitle, style = MaterialTheme.typography.labelMedium)
        Text(stringResource(when {
            nominal -> R.string.chart_nominal_legend
            states.isNotEmpty() -> R.string.chart_state_legend
            aggregation==MetricAggregation.SUM -> R.string.chart_sum_legend
            aggregation==MetricAggregation.MAX -> R.string.chart_max_legend
            else -> R.string.chart_numeric_legend
        }), style = MaterialTheme.typography.bodySmall)
        if(mixedBuckets.isNotEmpty() || gapBuckets.isNotEmpty()) Text(stringResource(R.string.chart_break_legend),style=MaterialTheme.typography.bodySmall)
        if(nominalStates.isNotEmpty()) Text(nominalStates.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key} — ${it.value}" },style=MaterialTheme.typography.bodySmall)
        if (states.isNotEmpty()) Text(states.mapIndexed { i, s -> "$i — $s" }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        selected?.let { p ->
            val value = when {
                p.bucket in gapBuckets -> gapLabel
                p.bucket in mixedBuckets -> mixedLabel
                stepped -> stateLabel(pointValue(p))
                aggregation==MetricAggregation.SUM -> stringResource(R.string.chart_selected_sum,scale.label(p.mean!!,locale),scale.unit)
                aggregation==MetricAggregation.MAX -> stringResource(R.string.chart_selected_max,scale.label(p.mean!!,locale),scale.unit)
                else -> stringResource(R.string.chart_selected_values, scale.label(p.low!!, locale), scale.label(p.mean!!, locale), scale.label(p.high!!, locale), scale.unit)
            }
            Text("${formatTime(p.time)}\n$value", style = MaterialTheme.typography.bodySmall)
        }
    }
}
