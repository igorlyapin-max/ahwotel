package com.ahwotel

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ChartScaleTest {
    @Test fun nominalAxesContainOnlyObservedCodes() {
        for(codes in listOf(emptyList(),listOf(2.0),listOf(1.0,2.0),listOf(2.0,4.0,7.0))) {
            val scale=ChartScale.create(codes,"1",nominal=true)
            assertEquals(codes,scale.ticks)
            assertTrue(scale.high>scale.low)
        }
    }
    @Test fun tapSelectionKeepsMillisecondPrecisionAtDeviceAndCurrentEpochs() {
        for (from in listOf(1_514_931_484_968L,1_789_300_000_000L)) {
            val to=from+300_000L
            for ((fraction,offset) in listOf(0f to 0L,.25f to 75_000L,.5f to 150_000L,.98f to 294_000L,1f to 300_000L)) {
                assertEquals((from+offset).toDouble(),chartTimeAtFraction(from,to,fraction),1.0)
            }
            val selected=listOf(to-120_000L,to-60_000L,to).minByOrNull {
                kotlin.math.abs(it-chartTimeAtFraction(from,to,.98f))
            }
            assertEquals(to,selected)
        }
    }
    @Test fun percentagesAndStatesHaveStableAxes() {
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0), ChartScale.create(listOf(40.0), "%", 100.0).ticks)
        assertEquals((0..6).map(Int::toDouble), ChartScale.create(emptyList(), "1", 6.0).ticks)
        assertTrue(ChartScale.create(listOf(120.0, 250.0), "%").high >= 250)
    }
    @Test fun finiteFlatEmptyAndSignedRangesRemainReadable() {
        for (values in listOf(emptyList(), listOf(0.0), listOf(42.0), listOf(-200.0, 100.0), listOf(Double.NaN, Double.POSITIVE_INFINITY))) {
            val scale = ChartScale.create(values, "mA")
            assertTrue(scale.high > scale.low); assertTrue(scale.low.isFinite()); assertTrue(scale.high.isFinite())
            assertTrue(scale.ticks.size in 3..7)
            assertEquals(scale.ticks.size, scale.ticks.map { scale.label(it, Locale.US) }.distinct().size)
            values.filter(Double::isFinite).forEach { assertTrue(it in scale.low..scale.high) }
        }
        assertEquals("MiB", ChartScale.create(listOf(1048576.0), "By").unit)
    }
    @Test fun timeLabelsHonorSelectedLanguageZoneAndRange() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("00:00:00", chartTimeLabel(0, 60_000, Locale.US, utc))
        assertEquals("03:00", chartTimeLabel(0, 3_600_000, Locale.US, TimeZone.getTimeZone("Europe/Moscow")))
        assertTrue(chartTimeLabel(0, 86_400_000, Locale.US, utc).contains("Jan"))
        assertFalse(chartTimeLabel(0, 86_400_000, Locale.forLanguageTag("ru"), utc).contains("Jan"))
    }
}
