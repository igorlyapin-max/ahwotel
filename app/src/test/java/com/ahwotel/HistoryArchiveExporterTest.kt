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
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=android.app.Application::class)
class HistoryArchiveExporterTest {
    private fun application(): MonitorApp {
        val app = MonitorApp()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, ApplicationProvider.getApplicationContext<Context>()))
        app.db = Room.inMemoryDatabaseBuilder(app, MonitorDatabase::class.java).allowMainThreadQueries().build()
        app.logs = Diagnostics(app)
        return app
    }

    private fun entries(bytes: ByteArray): Map<String,String> {
        val result = linkedMapOf<String,String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return result
    }

    @Test fun russianCsvUsesSelectedGroupsLocalizedDatetimeAndDecimalComma() = runBlocking {
        val app = application(); val time = 1_609_459_200_123L
        try {
            app.db.dao().start(SessionRow("s", "d", time, reason="", configuration="{}", continuous=false, durationSeconds=60))
            app.db.dao().sample(SampleRow(sessionId="s", time=time, elapsed=1, segment=0,
                memoryAvailable=12.5, state=2.5, capabilities="MEMORY=AVAILABLE", screenOn=true))
            val output = ByteArrayOutputStream()
            val result = HistoryArchiveExporter.write(app, output, HistoryArchiveSpec("s", time-1, time+1, false,
                setOf(HistoryExportGroup.DEVICE, HistoryExportGroup.PERFORMANCE), "ru", "UTC"))
            val files = entries(output.toByteArray())
            assertEquals(setOf("device.csv", "performance.csv"), result.files.keys)
            assertEquals(setOf("device.csv", "performance.csv", "manifest.json"), files.keys)
            assertTrue(files.getValue("device.csv").startsWith("\"session.id\";\"device.id\";\"datetime\";"))
            assertTrue(files.getValue("device.csv").lineSequence().first().contains("\"device.memory.available_bytes\""))
            assertTrue(files.getValue("device.csv").lineSequence().first().contains("\"headroom.datetime\""))
            assertTrue(files.getValue("device.csv").contains("\"01.01.2021 00:00:00.123 Z\""))
            assertTrue(files.getValue("device.csv").contains("\"12,5\""))
            assertFalse(files.containsKey("agent-signals.csv"))
            assertEquals("ru", JSONObject(files.getValue("manifest.json")).getString("language"))
        } finally { app.db.close() }
    }

    @Test fun jsonKeepsNumbersAndIncludesOnlyNonEmptySelectedFiles() = runBlocking {
        val app = application(); val time = 1_609_459_200_123L
        try {
            app.db.dao().start(SessionRow("s", "d", time, reason="", configuration="{}", continuous=false, durationSeconds=60))
            app.db.agentDao().insert(listOf(TelemetryRecord(sessionId="s", stream="battery", metric="TEMP", time=time,
                start=time-1000, durationMs=1000, segment=0, value=36.75, source="BATTERY_MANAGER", metadata="{}")))
            val output = ByteArrayOutputStream()
            HistoryArchiveExporter.write(app, output, HistoryArchiveSpec("s", time-2000, time+1, true,
                setOf(HistoryExportGroup.BATTERY, HistoryExportGroup.OEM), "ru", "UTC"))
            val files = entries(output.toByteArray())
            assertEquals(setOf("battery.json", "manifest.json"), files.keys)
            val record = JSONObject(files.getValue("battery.json")).getJSONArray("records").getJSONObject(0)
            assertEquals(36.75, record.getDouble("value"), 0.0)
            assertEquals("01.01.2021 00:00:00.123 Z", record.getString("datetime"))
            assertFalse(files.getValue("battery.json").contains("36,75"))
        } finally { app.db.close() }
    }

    @Test fun archiveSpecRoundTripsAndRejectsEmptySelection() {
        val spec = HistoryArchiveSpec(null, 1, 2, true, setOf(HistoryExportGroup.OEM), "en", "Europe/Moscow",
            capturedAt=3, sampleThroughId=4, agentThroughId=5, oemThroughId=6, oemInventoryThroughId=7,
            oemEventThroughId=8)
        assertEquals(spec, HistoryArchiveSpec.decode(spec.encode()))
        assertThrows(IllegalArgumentException::class.java) { HistoryArchiveSpec(null, 1, 2, false, emptySet(), "en", "UTC") }
    }

    @Test fun exportIsDisabledUntilLoadedSnapshotMatchesCurrentScope() {
        val revision=HistoryRevision(10,1,2,3,4,5,emptyList())
        val old=HistoryAvailabilityRequest("old",0,10,1,2)
        val current=HistoryAvailabilityRequest("new",0,10,1,2)
        assertFalse(historyExportReady(revision,current,old,false,false))
        assertFalse(historyExportReady(revision,current,current,true,false))
        assertFalse(historyExportReady(revision,current,current,false,true))
        assertTrue(historyExportReady(revision,current,current,false,false))
    }

    @Test fun typedRussianCsvDoesNotTreatNegativeNumberAsFormula() {
        val row = ExportPresentation("ru", "UTC").row(-12.5, "-12.5", "=SUM(A1:A2)")
        assertEquals("\"-12,5\";\"-12.5\";\"'=SUM(A1:A2)\"\n", row)
    }

    @Test fun snapshotCeilingsExcludeRowsInsertedAfterRefresh() = runBlocking {
        val app=application(); val time=1_609_459_200_123L
        try {
            app.db.dao().start(SessionRow("s","d",time,reason="",configuration="{}",continuous=false,durationSeconds=60))
            val first=app.db.dao().sample(SampleRow(sessionId="s",time=time,elapsed=1,segment=0,
                memoryAvailable=10.0,capabilities="MEMORY=AVAILABLE",screenOn=true))
            app.db.dao().sample(SampleRow(sessionId="s",time=time,elapsed=2,segment=0,
                memoryAvailable=99.0,capabilities="MEMORY=AVAILABLE",screenOn=true))
            val output=ByteArrayOutputStream()
            HistoryArchiveExporter.write(app,output,HistoryArchiveSpec("s",time-1,time+1,false,
                setOf(HistoryExportGroup.DEVICE),"en","UTC",sampleThroughId=first))
            val csv=entries(output.toByteArray()).getValue("device.csv")
            assertTrue(csv.contains("\"10.0\"")); assertFalse(csv.contains("\"99.0\""))
        } finally { app.db.close() }
    }

    @Test fun relevantCoreRowsAreFoundAfterAnEmptyPage() = runBlocking {
        val app=application(); val time=1_609_459_200_123L
        try {
            app.db.dao().start(SessionRow("s","d",time,reason="",configuration="{}",continuous=false,durationSeconds=60))
            repeat(500) { offset -> app.db.dao().sample(SampleRow(sessionId="s",time=time+offset,elapsed=offset.toLong(),
                segment=0,capabilities="MEMORY=AVAILABLE",screenOn=true)) }
            app.db.dao().sample(SampleRow(sessionId="s",time=time+500,elapsed=500,segment=0,cpuWait=4.0,
                capabilities="CPU=UNSUPPORTED",screenOn=true))
            val output=ByteArrayOutputStream()
            val result=HistoryArchiveExporter.write(app,output,HistoryArchiveSpec("s",time,time+500,false,
                setOf(HistoryExportGroup.AGENT_SIGNALS),"en","UTC"))
            assertEquals(1L,result.files.getValue("agent-signals.csv"))
            assertEquals(2,entries(output.toByteArray()).getValue("agent-signals.csv").lineSequence().filter(String::isNotBlank).count())
        } finally { app.db.close() }
    }

    @Test fun profileOnlyOemSelectionIsOmittedAndContextIsAttachedToRecords() = runBlocking {
        val app=application(); val time=1_609_459_200_123L
        try {
            app.db.dao().start(SessionRow("s","d",time,reason="",configuration="{}",continuous=false,durationSeconds=60))
            app.db.oemDao().profile(OemProfileRow("android_standard",time,
                "{\"provider\":\"android_standard\",\"checkedAt\":$time,\"state\":\"AVAILABLE\"}"))
            val empty=ByteArrayOutputStream()
            HistoryArchiveExporter.write(app,empty,HistoryArchiveSpec("s",time-1,time+1,true,
                setOf(HistoryExportGroup.OEM),"en","UTC"))
            assertEquals(setOf("manifest.json"),entries(empty.toByteArray()).keys)

            app.db.oemDao().observations(listOf(OemObservationRow(sessionId="s",time=time,segment=0,
                metric="ANDROID_SDK",provider="android_standard",source="ANDROID_STANDARD",scope="DEVICE",unit="1",
                number=35.0,text=null,status="AVAILABLE",reason="NONE",quality="AUTHORITATIVE")))
            val output=ByteArrayOutputStream()
            HistoryArchiveExporter.write(app,output,HistoryArchiveSpec("s",time-1,time+1,true,
                setOf(HistoryExportGroup.OEM),"en","UTC"))
            val oem=JSONObject(entries(output.toByteArray()).getValue("oem.json"))
            assertEquals(1,oem.getJSONArray("contextProfiles").length())
            val profile=oem.getJSONArray("contextProfiles").getJSONObject(0)
            assertFalse(profile.has("checkedAt")); assertTrue(profile.has("checkedAt_datetime"))
        } finally { app.db.close() }
    }

    @Test fun agentResourceTimestampIsNormalized() = runBlocking {
        val app=application(); val time=1_609_459_200_123L
        try {
            app.db.agentDao().insert(listOf(TelemetryRecord(sessionId="s",stream="battery",metric="TEMP",time=time,
                start=time,durationMs=1,segment=0,value=30.0,source="BATTERY_MANAGER",
                metadata="{\"source.checked_at\":$time}")))
            val output=ByteArrayOutputStream()
            HistoryArchiveExporter.write(app,output,HistoryArchiveSpec("s",time-1,time+1,true,
                setOf(HistoryExportGroup.BATTERY),"en","UTC"))
            val resource=JSONObject(entries(output.toByteArray()).getValue("battery.json"))
                .getJSONArray("records").getJSONObject(0).getJSONObject("resource")
            assertFalse(resource.has("source.checked_at")); assertTrue(resource.has("source.checked_at_datetime"))
        } finally { app.db.close() }
    }
}
