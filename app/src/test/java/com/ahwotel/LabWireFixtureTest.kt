package com.ahwotel

import com.ahwotel.oem.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Generates real APK wire payloads for scripts/otel-lab.sh smoke, never synthetic JSON equivalents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=android.app.Application::class)
class LabWireFixtureTest {
    @Test fun exportAllStreamsForRealCollectorAcceptance() {
        val dir=File("build/lab-wire").apply { mkdirs() }
        val now=System.currentTimeMillis()-10000
        val id="wire-${now}"
        val session=SessionRow(id,id,now-10000,reason="lab fixture",configuration="{}",continuous=true,durationSeconds=300)
        Telemetry(session).use { wire ->
            repeat(3) { i -> dir.resolve("base-$i.pb").writeBytes(wire.encode(SampleRow(sessionId=id,time=now+i*2000,
                elapsed=i*2000L,segment=0,screenOn=true,memoryAvailable=123456.0+i,capabilities="CPU=UNSUPPORTED;MEMORY=AVAILABLE"))) }
        }
        val metadata=JSONObject().put("device.id",id).put("device.model","Wire fixture").put("agent.version","test").toString()
        for ((stream,metric) in listOf("battery" to "LEVEL","self" to "CPU_DELTA")) {
            val rows=(0..1).map { i -> TelemetryRecord(sessionId=id,stream=stream,metric=metric,time=now+i*2000,
                start=now-2000+i*2000,durationMs=2000,segment=0,value=70.0+i,sum=if(stream=="self") 70.0+i else null,
                count=1,source="fixture",metadata=metadata) }
            dir.resolve("$stream.pb.gz").writeBytes(AgentTelemetryWire.gzip(AgentTelemetryWire.encode(rows)))
        }
        val unavailable=TelemetryRecord(sessionId=id,stream="wear",metric="SOH",time=now,start=now,durationMs=0,
            segment=0,status="UNSUPPORTED",reason="NO_SOURCE",source="fixture",metadata=metadata)
        dir.resolve("wear.pb.gz").writeBytes(AgentTelemetryWire.gzip(AgentTelemetryWire.encode(listOf(unavailable))))
        val knownWear=listOf("CYCLES" to 100.0,"FULL" to 2000000.0,"DESIGN" to 3000000.0).map { (metric,value) ->
            unavailable.copy(metric=metric,value=value,status="AVAILABLE",reason="NONE")
        }
        dir.resolve("wear-known.pb.gz").writeBytes(AgentTelemetryWire.gzip(AgentTelemetryWire.encode(knownWear)))
        val profile=OemProviderProfile("android_standard",ProviderState.AVAILABLE,now,emptyMap())
        val oem=(0..1).map { i -> OemObservation(OemMetric.MEMORY_PSS,"android_standard",status=OemStatus.AVAILABLE,
            number=234567.0+i,source=OemSource.ANDROID_STANDARD,scope=OemScope.AGENT_PROCESS,time=now+i*2000) }
        dir.resolve("oem.pb").writeBytes(OemTelemetry.encode(session,oem,profile,"lab"))
        dir.resolve("expected.json").writeText(JSONObject().put("deviceId",id).put("sessionId",id).put("time",now).toString())
        assertTrue(dir.resolve("self.pb.gz").length()>20)
    }
}
