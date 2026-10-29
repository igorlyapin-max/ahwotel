package com.ahwotel

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

class ExportAcceptanceTest {
    @get:Rule val ui=createAndroidComposeRule<ExportRecreationActivity>()
    private val app get()=ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    @Before fun prepare(): Unit=runBlocking { profile.prepare();ExportRecreationActivity.requestCode=null }
    @After fun restore(): Unit=runBlocking { profile.restore() }

    private fun recreate(kind: String) {
        ui.activityRule.scenario.onActivity { it.intent.putExtra("kind",kind) }
        ui.activityRule.scenario.recreate();ui.waitForIdle()
    }
    private fun finishPicker(name: String): File {
        val code=requireNotNull(ExportRecreationActivity.requestCode)
        ui.activityRule.scenario.recreate();ui.waitForIdle()
        val file=File(app.cacheDir,"exports/$name");file.parentFile!!.mkdirs();file.writeText("")
        val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",file)
        ui.activityRule.scenario.onActivity {
            assertTrue(ExportRecreationActivity.registry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri)))
        }
        ui.activityRule.scenario.recreate();ui.waitForIdle()
        ui.waitUntil(30000) { file.length()>0 }
        // A nonempty file may still be writing. Keep its item composed, then await completion.
        val csv=ui.activity.getString(when(ui.activity.intent.getStringExtra("kind")) {
            "oem" -> R.string.oem_numeric_csv
            "history" -> R.string.export_action
            else -> R.string.export_csv
        })
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(csv))
        ui.onNodeWithText(csv).performScrollTo()
        val done=ui.activity.getString(R.string.export_done)
        ui.waitUntil(30000) { ui.onAllNodesWithText(done).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(done).performScrollTo().assertIsDisplayed()
        return file
    }
    private fun zipEntries(file: File): Map<String,String> {
        val result=linkedMapOf<String,String>()
        ZipInputStream(ByteArrayInputStream(file.readBytes())).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break; result[entry.name]=zip.readBytes().toString(Charsets.UTF_8) }
        }
        return result
    }
    @Test fun batteryCsvSurvivesActivityRecreation() { recreate("battery");
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(ui.activity.getString(R.string.export_csv)))
        ui.onNodeWithText(ui.activity.getString(R.string.export_csv)).performClick()
        assertTrue(finishPicker("battery-code10.csv").readText().startsWith("metric,component,timestamp,"))
    }
    @Test fun selfCsvSurvivesActivityRecreation() { recreate("self");
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(ui.activity.getString(R.string.export_csv)))
        ui.onNodeWithText(ui.activity.getString(R.string.export_csv)).performClick()
        assertTrue(finishPicker("self-code10.csv").readText().startsWith("metric,component,timestamp,"))
    }
    @Test fun unifiedHistoryZipKeepsSessionAndPeriodAfterActivityRecreation(): Unit=runBlocking {
        val id="export-${UUID.randomUUID()}";val now=System.currentTimeMillis()
        app.db.dao().start(SessionRow(id,app.settings.value.deviceId,now-60000,reason="",configuration=SettingsCodec.encode(app.settings.value),continuous=false,durationSeconds=60))
        app.db.dao().finish(id,now,"manual_stop")
        fun row(session: String,time: Long,value: Double)=OemObservationRow(sessionId=session,time=time,segment=0,metric="ANDROID_SDK",
            provider="android_standard",source="ANDROID_STANDARD",scope="DEVICE",unit="1",number=value,text=null,status="AVAILABLE",reason="NONE",quality="AUTHORITATIVE")
        app.db.oemDao().observations(listOf(row(id,now-1000,27.0),row(id,now-86400000,77.0),row("other-session",now-1000,99.0)))
        recreate("history")
        ui.onNodeWithText(ui.activity.getString(R.string.all_sessions)).performScrollTo().performClick()
        ui.onNodeWithText(id,substring=true).performScrollTo().performClick()
        listOf("device","agent_signals","performance","battery","self_telemetry").forEach { group ->
            ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("export_group_$group"))
            ui.onNodeWithTag("export_group_$group").performScrollTo().performClick()
        }
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("history_export"))
        ui.waitUntil(30000) {
            runCatching { ui.onNodeWithTag("history_export").assertIsEnabled(); true }.getOrDefault(false)
        }
        ui.onNodeWithTag("history_export").performScrollTo().performClick()
        val entries=zipEntries(finishPicker("history-code28.zip"))
        assertEquals(setOf("oem.csv","manifest.json"),entries.keys)
        val result=entries.getValue("oem.csv")
        assertTrue(result.startsWith("\"session.id\",\"device.id\",\"datetime\","))
        assertFalse(result.contains("timestamp"))
        assertEquals(2,result.lineSequence().filter { it.isNotBlank() }.count())
        assertTrue(result.contains(id));assertFalse(result.contains("other-session"))
    }
}
