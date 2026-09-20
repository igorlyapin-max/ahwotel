package com.ahwotel

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class MetricReadingsTest {
    private fun sample(time: Long=1000,session: String="one")=SampleRow(sessionId=session,time=time,elapsed=time,segment=0,screenOn=true,
        battery=1.0,temperature=99.0,capabilities="BATTERY=DISABLED;HEADROOM=WARMING_UP;MEMORY=AVAILABLE;CPU=UNSUPPORTED")
    private fun record(m: AgentMetric,value: Double?,time: Long=1000,session: String="one",stream: String="battery",status: String="AVAILABLE")=
        TelemetryRecord(sessionId=session,stream=stream,metric=m.name,time=time,start=time,durationMs=0,segment=0,value=value,
            source="android_api",metadata="{}",status=status)
    @Test fun allMetricsHaveExplicitSourceCadenceAndEnablement() {
        val settings=Settings()
        Metric.entries.forEach { m ->
            assertTrue(m.name,m.intervalMs(settings)>0)
            assertTrue(m.name,m.enabled(settings))
            assertFalse(m.name,m.enabled(settings.copy(enabled=emptySet(),batterySettings=BatterySettings(enabled=false))))
        }
        AgentMetric.entries.forEach { m ->
            assertTrue(m.name,m.intervalMs(settings)>0)
            assertTrue(m.name,m.enabled(settings))
            assertFalse(m.name,m.enabled(settings.copy(batterySettings=BatterySettings(enabled=false),selfTelemetry=SelfTelemetrySettings(enabled=false))))
        }
    }
    @Test fun freshnessUsesProducerCadenceAndPublicationWindow() {
        val s=Settings(selfTelemetry=SelfTelemetrySettings(fastSeconds=600,mediumSeconds=900,slowSeconds=1800,windowSeconds=300))
        assertEquals(600000L,AgentMetric.PSS.intervalMs(s))
        assertEquals(900000L,AgentMetric.RX.intervalMs(s))
        assertEquals(300000L,AgentMetric.WAKE_MAX.intervalMs(s))
        assertEquals(1800000L,AgentMetric.DB_SIZE.intervalMs(s))
        assertEquals(60000L,AgentMetric.IO_READ_BYTES.intervalMs(s))
        assertEquals(86400000L,AgentMetric.CYCLES.intervalMs(s))
        assertEquals(604800000L,AgentMetric.DESIGN.intervalMs(s))
        assertEquals(26*3600000L,AgentMetric.FLASH_LIFE.staleAfterMs(s,"AVAILABLE"))
        assertEquals(30*60000L,AgentMetric.FLASH_LIFE.staleAfterMs(s,"PERMISSION_DENIED"))
        assertEquals(120000L,AgentMetric.IO_READ_BYTES.staleAfterMs(s,"AVAILABLE"))
    }
    @Test fun batteryCardsIgnoreOldFastFieldsAndMatchDedicatedMeasurements() {
        val rows=listOf(record(AgentMetric.LEVEL,68.0),record(AgentMetric.TEMP,28.6),record(AgentMetric.CHARGING,1.0))
        for((m,v) in listOf(Metric.BATTERY to 68.0,Metric.TEMPERATURE to 28.6,Metric.CHARGING to 1.0)) {
            val r=m.reading(sample(),null,rows)
            assertEquals(v,r.value!!,0.0); assertEquals("AVAILABLE",r.status); assertEquals(1000L,r.time)
        }
        assertNull(Metric.TEMPERATURE.reading(sample(),null,emptyList()).value)
        assertNull(Metric.TEMPERATURE.reading(sample(),null,listOf(record(AgentMetric.TEMP,44.0,stream="self"))).value)
    }
    @Test fun heldHeadroomKeepsItsTimestampAndErrorDoesNotLookAvailable() {
        val measured=sample().copy(headroom=.4,headroomTime=1000,capabilities="HEADROOM=AVAILABLE")
        val r=Metric.HEADROOM.reading(sample(9000),measured,emptyList())
        assertEquals(.4,r.value!!,0.0); assertEquals(1000L,r.time)
        assertEquals("ERROR",Metric.HEADROOM.reading(sample(),measured.copy(headroom=null,capabilities="HEADROOM=ERROR"),emptyList()).status)
        assertFalse(readingStale(1000,21000,10000))
        assertTrue(readingStale(1000,21001,10000))
        assertTrue(readingStale(1000,999,10000))
        assertFalse(readingStale(null,1000,10000))
    }
    @Test fun probesAndDerivedMetricsUseTheirOwnTimeAndCapability() {
        val s=sample().copy(cpuWait=3.0,probeTime=900,probeInterval=2000,state=0.0)
        assertEquals(900L,Metric.CPU_WAIT.reading(s,null,emptyList()).time)
        assertEquals("AVAILABLE",Metric.STATE.reading(s,null,emptyList()).status)
        assertEquals("MEMORY",Metric.MEMORY_PRESSURE.capabilityKey())
        assertEquals("CPU",Metric.CPU_PRESSURE.capabilityKey())
        assertEquals("STATE",Metric.STATE.capabilityKey())
    }
    @Test fun monitoringTimestampUsesNewestEnabledMetricWithAValue() {
        val settings=Settings()
        val readings=mapOf(
            Metric.CPU to MetricReading(10.0,1000,"AVAILABLE","proc"),
            Metric.TEMPERATURE to MetricReading(28.0,3000,"AVAILABLE","android_api"),
            Metric.BATTERY to MetricReading(null,9000,"ERROR","android_api"),
        )
        assertEquals(3000L,latestMonitorMeasurement(readings,settings))
        assertEquals(1000L,latestMonitorMeasurement(readings,settings.copy(batterySettings=BatterySettings(enabled=false))))
        assertNull(latestMonitorMeasurement(readings.mapValues { (_,r)->r.copy(value=null) },settings))
    }
    @Test fun monitoringGridPairsAcrossCategoriesAndAggregatesUnavailableMetrics() {
        val readings=monitorMetrics.associateWith { MetricReading(1.0,1000,"AVAILABLE","test") }.toMutableMap()
        val all=splitMonitorMetrics(readings,Settings())
        assertEquals(listOf(Metric.STATE,Metric.CPU),all.first.chunked(2).first())
        assertTrue(all.second.isEmpty())

        readings[Metric.CPU]=MetricReading(status="UNSUPPORTED",source="proc")
        readings[Metric.BATTERY]=MetricReading(status="PERMISSION_DENIED",source="battery_manager")
        val split=splitMonitorMetrics(readings,Settings())
        assertEquals(listOf(Metric.CPU,Metric.BATTERY),split.second)
        assertFalse(split.first.contains(Metric.CPU));assertFalse(split.first.contains(Metric.BATTERY))
        assertTrue(split.first.chunked(2).all { it.size in 1..2 })
    }
    @Test fun databaseSelectsSessionBeforeLatestAndHeadroomHasRealPointsOnly()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MonitorDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.dao().sample(sample().copy(headroom=.4,headroomTime=1000))
            db.dao().sample(sample(3000))
            db.dao().sample(sample(5000))
            db.dao().sample(sample(11000).copy(headroom=.5,headroomTime=11000))
            db.dao().sample(sample(13000))
            db.dao().sample(sample(14000,"two"))
            assertEquals(11000L,db.dao().latestHeadroom("one").first()!!.headroomTime)
            assertNull(db.dao().latestHeadroom("two").first())
            val points=db.dao().chart(chartQuery(Metric.HEADROOM,"one",0,15000))
            assertEquals(2,points.sumOf { it.count }); assertTrue(points.all { it.mean!=null })
            db.agentDao().insert(listOf(record(AgentMetric.LEVEL,68.0),record(AgentMetric.LEVEL,70.0,session="two"),record(AgentMetric.LEVEL,90.0,stream="self")))
            assertEquals(68.0,db.agentDao().latestInSession("one").first().single { it.stream=="battery" }.value!!,0.0)
            assertEquals(1,loadAgentChart(db.agentDao(),AgentMetric.LEVEL,0,2000,"one").sumOf { s -> s.points.sumOf { it.count } })
            assertTrue(db.agentDao().latestInSession("new").first().isEmpty())
        } finally { db.close() }
    }
}
