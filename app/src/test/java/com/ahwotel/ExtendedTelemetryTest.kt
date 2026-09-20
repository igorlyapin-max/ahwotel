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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class ExtendedTelemetryTest {
    private fun process(n: Long) = listOf("read_bytes","write_bytes","cancelled_write_bytes","rchar","wchar","syscr","syscw").joinToString("\n") { "$it: $n" }
    @Test fun processRatesResetAfterFailureAndRestart() {
        var value=100L; var denied=false
        val io=IoTelemetry({ if(denied) throw SecurityException() else process(value) },{emptyList()})
        assertNull(io.sample(1000,true,false,false).first { it.metric==AgentMetric.IO_READ_BYTES_RATE }.value)
        value=220
        assertEquals(2.0,io.sample(61000,true,false,false).first { it.metric==AgentMetric.IO_READ_BYTES_RATE }.value!!,0.0)
        denied=true
        assertTrue(io.sample(121000,true,false,false).all { it.status=="PERMISSION_DENIED" && it.value==null })
        denied=false; value=900
        assertTrue(io.sample(181000,true,false,false).all { it.status=="PERMISSION_DENIED" })
        io.recheck()
        assertNull(io.sample(182000,true,false,false).first { it.metric==AgentMetric.IO_READ_BYTES_RATE }.value)
        value=1
        assertEquals("COUNTER_RESET",io.sample(242000,true,false,false).first { it.metric==AgentMetric.IO_READ_BYTES_RATE }.reason)
    }
    @Test fun systemFallbackSelectsOnlyWholePhysicalDevicesAndCorrectSectorUnits() {
        val io=IoTelemetry({ path -> when(path) {
            "/proc/pressure/io" -> "some avg10=0.2 avg60=0.1 avg300=0.0 total=100\nfull avg10=0.0 avg60=0.0 avg300=0.0 total=20"
            "/proc/diskstats" -> "179 0 mmcblk0 1 0 10 4 5 0 20 8 0 12 15"
            else -> throw SecurityException()
        } },{listOf("mmcblk0","mmcblk0p1","mmcblk0boot0","dm-0","loop0")})
        val rows=io.sample(1000,false,true,false)
        assertEquals(5120.0,rows.single { it.metric==AgentMetric.SYSTEM_READ_BYTES }.value!!,0.0)
        assertEquals("/proc/diskstats",rows.single { it.metric==AgentMetric.SYSTEM_READ_BYTES }.source)
        assertFalse(rows.any { it.component in listOf("dm-0","mmcblk0p1","mmcblk0boot0","loop0") })
        assertEquals(setOf("some","full"),rows.filter { it.metric==AgentMetric.SYSTEM_PSI_TOTAL }.map { it.component }.toSet())
        assertEquals("device",AgentMetric.SYSTEM_READ_BYTES.scope)
        assertEquals("agent_process",AgentMetric.IO_READ_BYTES.scope)
    }
    @Test fun deniedDeviceDiscoveryIsNotReportedAsMissingHardware() {
        val io=IoTelemetry({ if(it=="/proc/pressure/io") "some avg10=0 avg60=0 avg300=0 total=0" else throw SecurityException() },{throw SecurityException()})
        val rows=io.sample(1000,false,true,true)
        assertEquals("PERMISSION_DENIED",rows.first { it.metric==AgentMetric.SYSTEM_READ_BYTES }.status)
        assertEquals("PERMISSION_DENIED",rows.first { it.metric==AgentMetric.FLASH_LIFE }.status)
    }
    @Test fun readableDiskstatsSurvivesDeniedSysfsDirectoryDiscovery() {
        val io=IoTelemetry({ if(it=="/proc/diskstats") "179 0 mmcblk0 1 0 10 4 5 0 20 8 0 12 15" else throw SecurityException() },{throw SecurityException()})
        val rows=io.sample(1000,false,true,false)
        assertEquals(5120.0,rows.single { it.metric==AgentMetric.SYSTEM_READ_BYTES }.value!!,0.0)
        assertEquals("mmcblk0",rows.single { it.metric==AgentMetric.SYSTEM_READ_BYTES }.component)
    }
    @Test fun invalidSourcesAndFlashUnknownAreNeverZero() {
        val io=IoTelemetry({ path -> when {
            path.endsWith("life_time") -> "0x00 0x0b"
            path.endsWith("pre_eol_info") -> "0x02"
            else -> "bad data"
        } },{listOf("mmcblk0")})
        val rows=io.sample(1,true,true,true)
        assertTrue(rows.filter { it.metric.name.startsWith("IO_") }.all { it.value==null })
        assertNull(rows.single { it.metric==AgentMetric.FLASH_LIFE && it.component.endsWith(":A") }.value)
        assertEquals(11.0,rows.single { it.metric==AgentMetric.FLASH_LIFE && it.component.endsWith(":B") }.value!!,0.0)
        assertEquals(2.0,rows.single { it.metric==AgentMetric.FLASH_EOL }.value!!,0.0)
        assertTrue(io.sample(61000,false,false,true).isEmpty())
    }
    @Test fun foldsOnlyPermanentRefusalsWithoutValues() {
        assertTrue(shouldFold(listOf("PERMISSION_DENIED","UNSUPPORTED"),false))
        assertFalse(shouldFold(listOf("PERMISSION_DENIED"),true))
        for(status in listOf("ERROR","UNAVAILABLE","DISABLED","WARMING_UP"))
            assertFalse(shouldFold(listOf(status),false))
        assertFalse(shouldFold(emptyList(),false))
        assertTrue(shouldFoldLive(true,emptyList(),listOf("UNSUPPORTED"),false))
        assertTrue(shouldFoldLive(true,emptyList(),listOf("PERMISSION_DENIED"),false))
        assertFalse(shouldFoldLive(true,listOf("AVAILABLE"),listOf("UNSUPPORTED"),true))
        assertTrue(shouldFoldLive(false,listOf("AVAILABLE"),emptyList(),true))
    }
    private fun row(metric: String,time: Long,value: Double?=null,status: String="AVAILABLE",session: String="one",segment: Int=0)=
        TelemetryRecord(sessionId=session,stream="battery",metric=metric,time=time,start=time,durationMs=0,segment=segment,value=value,source="test",status=status,metadata="{}")
    @Test fun historyPresenceKeepsPastValuesAndHonoursSession()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),MonitorDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.agentDao().insert(listOf(row("ENERGY",1,42.0),row("ENERGY",2,status="PERMISSION_DENIED"),row("ENERGY",3,status="PERMISSION_DENIED",session="two")))
            assertEquals(1,db.agentDao().presence(0,4,"one").single().valid)
            assertEquals(0,db.agentDao().presence(0,4,"two").single().valid)
            assertTrue(db.agentDao().presence(5,6,null).isEmpty())
            assertEquals(2L,db.agentDao().rangeLatest(0,4,"one").single().time)
            assertEquals("one",loadAgentChart(db.agentDao(),AgentMetric.ENERGY,0,4,"one").single().points.first().sessionId)
        } finally { db.close() }
    }
    @Test fun summaryDoesNotBridgeGapsSessionChangesOrMissingFrames() {
        fun frame(t: Long,session: String="one")=listOf(row("TEMP",t,26.0,session=session),row("LEVEL",t,90.0,session=session),row("PLUG",t,0.0,session=session),row("STATUS",t,3.0,session=session))
        val summary=summarizeBattery(frame(0)+frame(60000)+frame(3600000)+frame(3660000,"two"),120000)
        assertEquals(60000,summary.observedMs)
        assertEquals(60000,summary.dischargeMs)
        assertEquals(0,summary.powerMs)
        val missing=frame(0)+frame(60000).map { if(it.metric=="TEMP") it.copy(value=null,status="UNAVAILABLE") else it }+frame(120000)
        assertEquals(0,summarizeBattery(missing,120000).observedMs)
    }
    @Test fun newSettingsRoundTripAndExistingSettingsSurviveUpgrade() {
        val s=Settings(deviceId="test",selfTelemetry=SelfTelemetrySettings(processIo=false,ioSeconds=120),batterySettings=BatterySettings(extended=false))
        assertEquals(s,SettingsCodec.decode(SettingsCodec.encode(s)))
        val old=org.json.JSONObject(SettingsCodec.encode(s))
        old.getJSONObject("selfTelemetry").apply { remove("processIo");remove("systemIo");remove("flashWear");remove("ioSeconds") }
        old.getJSONObject("batterySettings").remove("extended")
        val upgraded=SettingsCodec.decode(old.toString())
        assertTrue(upgraded.selfTelemetry.processIo); assertEquals(60,upgraded.selfTelemetry.ioSeconds)
        assertTrue(upgraded.batterySettings.extended)
        assertFalse(SelfTelemetrySettings(ioSeconds=0).valid())
    }
}
