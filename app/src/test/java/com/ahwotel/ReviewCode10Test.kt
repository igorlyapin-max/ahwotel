package com.ahwotel

import android.content.Intent
import com.ahwotel.oem.OemSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class ReviewCode10Test {
    @Test fun oldProfilesCanStartBatteryOrSelfWithoutOrdinaryCollectors() {
        for(legacy in listOf(false,true)) for(battery in listOf(false,true)) {
            val old=JSONObject(SettingsCodec.encode(Settings(deviceId="test",
                enabled=if(legacy) setOf(CollectorKind.MEMORY,CollectorKind.BATTERY) else setOf(CollectorKind.MEMORY))))
            old.remove("batterySettings");old.remove("selfTelemetry")
            val loaded=SettingsCodec.decode(old.toString())
            val settings=loaded.copy(enabled=loaded.enabled-CollectorKind.MEMORY,
                batterySettings=BatterySettings(enabled=battery),selfTelemetry=SelfTelemetrySettings(enabled=!battery))
            assertTrue(settings.valid())
            val saved=SettingsCodec.decode(SettingsCodec.encode(settings))
            val intent=Intent(MonitoringService.START).putExtra("metrics","")
            assertTrue(MonitoringService.parse(intent,saved).metrics.isEmpty())
        }
    }
    @Test fun emptyProfilesAreSavableButCannotStartAndMalformedMetricsStayRejected() {
        val s=Settings(deviceId="test",enabled=setOf(CollectorKind.BATTERY),
            batterySettings=BatterySettings(enabled=false),selfTelemetry=SelfTelemetrySettings(enabled=false),oem=OemSettings(enabled=false))
        assertTrue(s.valid());assertFalse(s.hasCollectors())
        assertThrows(IllegalArgumentException::class.java) { MonitoringService.parse(Intent(MonitoringService.START).putExtra("metrics",""),s) }
        for(value in listOf("CPU,",",MEMORY","UNKNOWN")) assertThrows(IllegalArgumentException::class.java) {
            MonitoringService.parse(Intent(MonitoringService.START).putExtra("metrics",value),s)
        }
    }
    @Test fun sessionResetAndTrackingBoundaryNeverInventWakeTimeOrAcquisitions() {
        val c=AgentCosts();c.enabled=true;c.start();c.setWakeTracking(false,1000)
        c.wakeAcquire(1000,600000);c.wakeRelease(400000)
        c.start();assertEquals(0L,c.wakeMaximum());assertTrue(c.drain().isEmpty())
        c.wakeAcquire(1000,600000);c.wakeObserve(2000,30,120)
        c.setWakeTracking(false,2000);c.drain()
        c.setWakeTracking(true,5000);c.wakeObserve(8000,30,120)
        val rows=c.drain()
        assertEquals(3000.0,rows[AgentMetric.WAKE_DURATION to "AHWOTel:Monitoring"]!!.sum,0.0)
        assertFalse(rows.containsKey(AgentMetric.WAKE_COUNT to "AHWOTel:Monitoring"))
        assertEquals(3000L,c.wakeMaximum())
    }
    @Test fun pendingCsvExportRetainsScopeAndRejectsMissingRequest() {
        val request=PendingExport("oem",ExportSpec("session",1700000000123,1700000600123,false))
        assertEquals(request,PendingExport.decode(request.encode()))
        assertFalse(PendingExport.decode(request.encode()).spec.json)
        assertThrows(Exception::class.java) { PendingExport.decode("{}") }
    }
}
