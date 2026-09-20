package com.ahwotel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class CompactMetricValue(val value: String, val technical: String = "")

@Composable
fun CompactInfoButton(label: String, tag: String? = null, onClick: () -> Unit) {
    IconButton(onClick,Modifier.size(48.dp).then(if(tag==null) Modifier else Modifier.testTag(tag))
        .semantics { contentDescription=label }) {
        Text("ⓘ",style=MaterialTheme.typography.headlineSmall)
    }
}

@Composable
fun AdaptiveSingleLineText(
    text: String,
    style: TextStyle,
    minFontSize: TextUnit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maximum = style.fontSize.takeIf { it.isSp } ?: 14.sp
        val width = with(density) { maxWidth.roundToPx() }
        val fitted = remember(text, style, minFontSize, maximum, width, density.fontScale) {
            if (text.isEmpty() || width <= 0) maximum else {
                var low = minFontSize.value
                var high = maximum.value
                repeat(8) {
                    val candidate = (low + high) / 2f
                    val measured = measurer.measure(
                        text = text,
                        style = style.copy(fontSize = candidate.sp),
                        maxLines = 1,
                        softWrap = false,
                        constraints = Constraints(maxWidth = width),
                    )
                    if (measured.didOverflowWidth) high = candidate else low = candidate
                }
                low.coerceIn(minFontSize.value, maximum.value).sp
            }
        }
        Text(
            text,
            Modifier.fillMaxWidth().then(if (tag == null) Modifier else Modifier.testTag(tag))
                .semantics { contentDescription = text },
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = style.copy(fontSize = fitted),
        )
    }
}

@Composable
fun CompactMetricCard(
    title: String,
    values: List<CompactMetricValue>,
    id: String,
    modifier: Modifier = Modifier,
    help: @Composable () -> Unit,
) {
    Card(
        modifier.fillMaxWidth().testTag("compact_metric_$id"),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AdaptiveSingleLineText(
                title,
                MaterialTheme.typography.titleMedium,
                10.sp,
                tag = "compact_title_$id",
            )
            val displayed = values.ifEmpty { listOf(CompactMetricValue("—")) }
            displayed.forEachIndexed { index, reading ->
                AdaptiveSingleLineText(
                    reading.value,
                    MaterialTheme.typography.headlineMedium,
                    12.sp,
                    tag = "compact_value_${id}_$index",
                )
                if (index != displayed.lastIndex && reading.technical.isNotEmpty()) AdaptiveSingleLineText(
                    reading.technical,
                    MaterialTheme.typography.labelSmall,
                    8.sp,
                    tag = "compact_source_${id}_$index",
                )
            }
            val finalReading = displayed.last()
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("compact_footer_$id")) {
                if (finalReading.technical.isNotEmpty()) AdaptiveSingleLineText(
                    finalReading.technical,
                    MaterialTheme.typography.labelSmall,
                    8.sp,
                    Modifier.align(Alignment.CenterStart).padding(end = 48.dp),
                    "compact_source_${id}_${displayed.lastIndex}",
                )
                Box(Modifier.align(Alignment.BottomEnd).testTag("compact_help_anchor_$id")) { help() }
            }
        }
    }
}
