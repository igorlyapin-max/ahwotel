package com.ahwotel

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

data class ChartScale(val low: Double, val high: Double, val ticks: List<Double>, val divisor: Double, val unit: String) {
    fun label(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = if (ticks.size > 1) (ceil(-log10(abs(ticks[1] - ticks[0]) / divisor)) + 1).toInt().coerceIn(0, 8) else 2
    }.format(value / divisor)

    companion object {
        fun create(values: List<Double>, unit: String, fixedMaximum: Double? = null, nominal: Boolean = false): ChartScale {
            val finite = values.filter(Double::isFinite)
            if (nominal) {
                val codes=finite.distinct().sorted()
                val first=codes.firstOrNull() ?: 0.0
                val last=codes.lastOrNull() ?: 1.0
                return ChartScale(if(first==last) first-.5 else first,if(first==last) last+.5 else last,
                    codes,1.0,if(unit=="1") "" else unit)
            }
            var low = finite.minOrNull() ?: 0.0
            var high = finite.maxOrNull() ?: 1.0
            if (fixedMaximum != null) { low = 0.0; high = fixedMaximum }
            else {
                if (unit in setOf("By", "ms", "s", "count") && low >= 0) low = 0.0
                if (low == high) {
                    val pad = max(abs(low) * 0.1, 0.01)
                    if (low != 0.0 || unit !in setOf("By", "ms", "s", "count")) low -= pad
                    high += pad
                }
                val rough = (high - low) / 4
                val power = 10.0.pow(floor(log10(rough)))
                val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * power >= rough } * power
                low = floor(low / step) * step; high = ceil(high / step) * step
                val ticks = (0..((high - low) / step).roundToInt()).map { low + it * step }
                val (divisor, displayUnit) = displayUnit(unit, max(abs(low), abs(high)))
                return ChartScale(low, high, ticks, divisor, displayUnit)
            }
            val ticks = if (fixedMaximum in listOf(1.0, 3.0, 6.0)) (0..high.toInt()).map(Int::toDouble)
                else (0..4).map { low + (high - low) * it / 4 }
            val (divisor, displayUnit) = displayUnit(unit, high)
            return ChartScale(low, high, ticks, divisor, displayUnit)
        }

        private fun displayUnit(unit: String, max: Double): Pair<Double, String> = when (unit) {
            "By" -> when { max >= 1024.0.pow(3) -> 1024.0.pow(3) to "GiB"; max >= 1024.0.pow(2) -> 1024.0.pow(2) to "MiB"; else -> 1024.0 to "KiB" }
            "Cel" -> 1.0 to "°C"
            "1" -> 1.0 to ""
            else -> 1.0 to unit
        }
    }
}

fun chartTimeLabel(time: Long, range: Long, locale: Locale, zone: TimeZone): String =
    SimpleDateFormat(when { range < 300_000 -> "HH:mm:ss"; range < 86_400_000 -> "HH:mm"; else -> "dd MMM\nHH:mm" }, locale)
        .apply { timeZone = zone }.format(Date(time))

/** Keep epoch milliseconds out of Float arithmetic (its precision is minutes at current dates). */
fun chartTimeAtFraction(from: Long, to: Long, fraction: Float): Double =
    from.toDouble() + fraction.toDouble() * (to - from).toDouble()
