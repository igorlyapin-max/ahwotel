package com.ahwotel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class AgentTelemetryTest {
    private fun snapshot(t: Long,level: Double=90.0,charge: Double?=null,plug: Double=0.0)=BatterySnapshot(1700000000000+t,t,
        listOf(AgentReading(AgentMetric.LEVEL,level), AgentReading(AgentMetric.PLUG,plug),AgentReading(AgentMetric.CHARGING,if(plug>0) 1.0 else 0.0),
            charge?.let { AgentReading(AgentMetric.CHARGE,it) } ?: AgentReading.missing(AgentMetric.CHARGE,"INVALID_VALUE")))
    @Test fun drainNeedsStableUnpluggedWindowAndKeepsPercentWithoutCharge() {
        val d=BatteryDrain()
        assertTrue(d.update(snapshot(0),60000).all { it.value==null })
        var last=emptyList<AgentReading>()
        for(i in 1..20) last=d.update(snapshot(i*60000L,90.0-i*.1),60000)
        assertEquals(6.0,last.first { it.metric==AgentMetric.DRAIN_PERCENT }.value!!,.0001)
        assertNull(last.first { it.metric==AgentMetric.DRAIN_CHARGE }.value)
        assertTrue(d.update(snapshot(1260000,87.0,plug=2.0),60000).all { it.reason=="EXTERNAL_POWER" })
    }
    @Test fun gapOrClockJumpCannotBecomeHugeDrain() {
        val d=BatteryDrain(); for(i in 0..10) d.update(snapshot(i*60000L),60000)
        assertTrue(d.update(snapshot(3600000,20.0),60000).all { it.value==null })
        val wrong=snapshot(3660000,19.0).copy(wall=1900000000000)
        assertTrue(d.update(wrong,60000).all { it.value==null })
    }
    @Test fun counterIncreaseResetsBaseline() {
        val d=BatteryDrain(); for(i in 0..10) d.update(snapshot(i*60000L,charge=1000.0),60000)
        assertTrue(d.update(snapshot(660000,charge=2000.0),60000).all { it.value==null })
    }
    @Test fun momentsAreExactAndQuantilesRemainBounded() {
        val a=MetricAccumulator(); (1..10000).forEach { a.add(it.toDouble()) }; a.add(Double.NaN)
        assertEquals(10000,a.count); assertEquals(50005000.0,a.sum,0.0)
        assertEquals(1.0,a.low,0.0); assertEquals(10000.0,a.high,0.0)
        assertTrue(a.percentile(.95)!! in 9000.0..9999.0)
        val b=MetricAccumulator(); listOf(1.0,2.0,100.0).forEach(b::add); assertEquals(2.0,b.percentile(.5)!!,0.0)
    }
    @Test fun ownedWakeLockStopsAtTimeoutWithoutReleaseAndDoesNotDoubleCount() {
        val c=AgentCosts(); c.enabled=true; c.wakeAcquire(1000,10000)
        c.wakeObserve(4000,30,120); c.wakeObserve(15000,30,120); c.wakeRelease(20000)
        val rows=c.drain()
        assertEquals(10000.0,rows[AgentMetric.WAKE_DURATION to "AHWOTel:Monitoring"]!!.sum,0.0)
        assertEquals(1.0,rows[AgentMetric.WAKE_COUNT to "AHWOTel:Monitoring"]!!.sum,0.0)
        assertEquals(10000,c.wakeMaximum())
        c.enabled=false; c.add(AgentMetric.CALLS,1.0); assertTrue(c.drain().isEmpty())
    }
    @Test fun configurationRoundTripAndExpiredDebug() {
        val s=Settings(deviceId="test",batterySettings=BatterySettings(wearSeconds=604800),selfTelemetry=SelfTelemetrySettings(fastSeconds=120,debugUntil=900000))
        assertEquals(s,SettingsCodec.decode(SettingsCodec.encode(s)))
        assertEquals(10,s.selfTelemetry.fast(10)); assertEquals(120,s.selfTelemetry.fast(900000))
        assertTrue(s.copy(otlpEnabled=true,endpoint="https://example.org/v1/metrics",selfTelemetry=s.selfTelemetry.copy(uploadSeconds=604800)).valid())
        val old=JSONObject(SettingsCodec.encode(s)).apply { remove("batterySettings");remove("selfTelemetry") }
        assertEquals(14,SettingsCodec.decode(old.toString()).retentionDays)
        assertEquals(s.queueHours,SettingsCodec.decode(old.toString()).queueHours)
    }
    @Test fun aFutureOrRetryingPacketDoesNotBlockAnotherStream()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MonitorDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.dao().enqueue(OutboxRow(createdAt=1,endpoint="x",payload=byteArrayOf(1),stream="wear",dueAt=10000))
            db.dao().enqueue(OutboxRow(createdAt=2,endpoint="x",payload=byteArrayOf(2),stream="self",nextAttempt=20000))
            db.dao().enqueue(OutboxRow(createdAt=3,endpoint="x",payload=byteArrayOf(3),stream="main"))
            assertEquals("main",db.dao().readyPending(100)!!.stream)
            db.dao().deletePending(db.dao().readyPending(100)!!.id)
            assertNull(db.dao().readyPending(100)); assertEquals(10000L,db.dao().nextPendingTime())
            assertEquals("wear",db.dao().readyPending(10000)!!.stream)
        } finally {db.close()}
    }
    @Test fun oldShortQueueSettingsLoadWithoutChangingUserValues() {
        for(hours in listOf(1,23,24)) for(otlp in listOf(false,true)) for(battery in listOf(false,true)) {
            val saved=Settings(deviceId="device",queueHours=hours,otlpEnabled=otlp,endpoint="https://example.org/v1/metrics",
                enabled=if(battery) CollectorKind.entries.toSet() else setOf(CollectorKind.CPU))
            val json=JSONObject(SettingsCodec.encode(saved)).apply { remove("batterySettings"); remove("selfTelemetry") }
            val decoded=SettingsCodec.decode(json.toString())
            assertEquals(hours,decoded.queueHours); assertEquals(otlp,decoded.otlpEnabled)
            assertEquals(battery,decoded.batterySettings.enabled)
            assertEquals(saved.enabled,decoded.enabled); assertEquals(saved.endpoint,decoded.endpoint)
        }
    }
    @Test fun cleanupAllowsOneHourAfterDueAndRetriesCannotExtendExpiry()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MonitorDatabase::class.java).allowMainThreadQueries().build()
        try {
            val created=1700000000000L; val due=created+86400000L
            val bytes=byteArrayOf(9,8,7)
            db.dao().enqueue(OutboxRow(createdAt=created,endpoint="x",payload=bytes,stream="wear",dueAt=due,compressed=true))
            assertEquals(0,db.dao().expireOutbox(due+1,86400000L))
            val row=db.dao().readyPending(due+1)!!
            assertArrayEquals(bytes,row.payload)
            db.dao().retry(row.id,1,due+7200000L)
            assertEquals(0,db.dao().expireOutbox(due+3599999,3600000L))
            assertEquals(1,db.dao().expireOutbox(due+3600000,3600000L))
            db.dao().enqueue(OutboxRow(createdAt=due,endpoint="x",payload=bytes))
            assertEquals(0,db.dao().expireOutbox(due+3600000,86400000L))
            assertEquals(1,db.dao().expireOutbox(due+86400000,86400000L))
        } finally { db.close() }
    }
    @Test fun counterReversalBelowBaselineRestartsOnlyChargeWindow() {
        val d=BatteryDrain(); var rows=emptyList<AgentReading>()
        for(i in 0..26) {
            val q=if(i<=10) 1000.0-i*2 else 990.0-(i-11)*2
            rows=d.update(snapshot(i*60000L,90.0-i*.1,q),60000)
            if(i==11) assertEquals("COUNTER_RESET",rows.last().reason)
            if(i==20) { assertNotNull(rows.first().value); assertNull(rows.last().value) }
        }
        assertNotNull(rows.last().value)
        d.update(snapshot(27*60000L,87.3,null),60000)
        assertNull(d.update(snapshot(28*60000L,87.2,900.0),60000).last().value)
    }
    @Test fun effectiveConfigurationTracksStartSnapshotAndLiveSettings() {
        val start=Settings(deviceId="d")
        fun hash(live: Settings, snapshot: Settings=start)=MeasurementConfiguration.signature(snapshot,live,1000)
        val initial=hash(start)
        assertEquals(initial,hash(start.copy(intervalMs=60000,enabled=setOf(CollectorKind.STORAGE),indirectCpu=false)))
        assertEquals(initial,hash(start.copy(language="ru",diagnostic=DiagnosticLevel.BASIC)))
        assertNotEquals(initial,hash(start,start.copy(intervalMs=60000)))
        assertNotEquals(initial,hash(start,start.copy(enabled=setOf(CollectorKind.STORAGE))))
        assertNotEquals(initial,hash(start,start.copy(indirectCpu=false)))
        assertNotEquals(initial,hash(start.copy(batterySettings=start.batterySettings.copy(currentSeconds=30))))
        assertNotEquals(initial,hash(start.copy(selfTelemetry=start.selfTelemetry.copy(fastSeconds=120))))
    }
    @Test fun gzipPacketHasIdentityAndDoesNotInventUnavailableNumericValue() {
        val base=TelemetryRecord(sessionId="s",stream="self",metric="PSS",time=1700000000000,start=1699999700000,durationMs=300000,segment=0,
            value=1500.0,source="process_api",metadata="{\"agent.build\":8,\"installation.id\":\"i\",\"config.version\":\"v\"}")
        val rows=listOf(base,base.copy(metric="SOH",value=null,status="UNSUPPORTED",reason="NO_VERIFIED_SOURCE"))
        val raw=AgentTelemetryWire.encode(rows)
        assertArrayEquals(raw,GZIPInputStream(AgentTelemetryWire.gzip(raw).inputStream()).readBytes())
        val wire=raw.toString(Charsets.ISO_8859_1)
        assertTrue(wire.contains("agent.memory.pss"));assertTrue(wire.contains("NO_VERIFIED_SOURCE"));assertTrue(wire.contains("installation.id"))
        assertFalse(wire.contains("device.battery.soh.min"))
    }
}
