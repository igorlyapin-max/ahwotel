package com.ahwotel

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class AgentTelemetryAcceptanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val app get()=ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    @Before fun prepare(): Unit=runBlocking {
        profile.prepare(); device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        app.saveSettings(app.settings.value.copy(intervalMs=1000,durationSeconds=12,diagnostic=DiagnosticLevel.BASIC,
            selfTelemetry=SelfTelemetrySettings(fastSeconds=15,mediumSeconds=60,slowSeconds=60,windowSeconds=60),
            batterySettings=BatterySettings(currentSeconds=15)))
        ui.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        ui.waitUntil(15000) { ui.onAllNodesWithText("Start monitoring").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun restore(): Unit=runBlocking { profile.restore() }
    private suspend fun start(): String {
        ui.onNodeWithText("Start monitoring").performClick()
        withTimeout(10000) { while(app.state.value.sessionId==null) delay(50) }
        return app.state.value.sessionId!!
    }
    private suspend fun stop(id: String) {
        MonitoringService.stop(app,id)
        withTimeout(15000) { while(app.state.value.sessionId!=null) delay(50) }
    }
    @Test fun realWorkerCollectsLocallyAndLiveSettingsPreserveTimer(): Unit=runBlocking {
        val id=start()
        val started=app.db.dao().session(id)!!.startedAt
        app.saveSettings(app.settings.value.copy(continuous=true,batterySettings=app.settings.value.batterySettings.copy(currentSeconds=30)))
        delay(17000)
        assertEquals(id,app.state.value.sessionId); assertEquals(SessionMode.CONTINUOUS,app.state.value.mode)
        assertEquals(started,app.db.dao().session(id)!!.startedAt)
        app.saveSettings(app.settings.value.copy(batterySettings=app.settings.value.batterySettings.copy(currentSeconds=15)))
        delay(18000); stop(id)
        val rows=app.db.agentDao().page(0,0,Long.MAX_VALUE,id)
        assertTrue(rows.any { it.metric=="LEVEL" && it.value!=null })
        assertTrue(rows.any { it.metric=="PSS" && (it.value ?: 0.0)>0 })
        assertTrue(rows.any { it.metric=="CPU_DELTA" && it.value!=null })
        assertTrue(rows.any { it.metric=="CALLS" && it.component=="memory_collector" })
        assertEquals(0L,app.db.dao().outboxCount())
        val count=rows.size; delay(1500); assertEquals(count,app.db.agentDao().page(0,0,Long.MAX_VALUE,id).size)
        File(app.filesDir,"agent-code8-worker.json").writeText(org.json.JSONArray(rows.map(AgentHistoryExport::json)).toString())
        ui.onNode(hasText("Self Telemetry") and hasClickAction()).performClick()
        ui.onNodeWithTag("agent_metric_help").assertIsDisplayed().performClick()
        ui.onNodeWithText("Close").assertIsDisplayed()
        device.takeScreenshot(File(app.filesDir,"agent-code8-help-en.png"))
        ui.onNodeWithText("Close").performClick()
        ui.waitForIdle(); delay(600)
        ui.onNodeWithText("5 minutes").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Process CPU time").performScrollTo()
        ui.waitForIdle(); delay(600)
        device.takeScreenshot(File(app.filesDir,"agent-code8-self-en.png"))
    }
    @Test fun disabledSelfLeavesBatteryIndependentAndHelpIsRussian(): Unit=runBlocking {
        app.saveSettings(app.settings.value.copy(selfTelemetry=app.settings.value.selfTelemetry.copy(enabled=false),continuous=true))
        val id=start(); delay(3000);stop(id)
        val rows=app.db.agentDao().page(0,0,Long.MAX_VALUE,id)
        assertTrue(rows.any { it.metric=="LEVEL" }); assertFalse(rows.any { it.stream=="self" })
        app.saveSettings(app.settings.value.copy(language="ru"))
        ui.waitUntil(15000) { ui.onAllNodesWithText("Батарея").fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasText("Батарея") and hasClickAction()).performClick()
        ui.onNodeWithTag("agent_metric_help").performClick()
        ui.onNodeWithText("Закрыть").assertIsDisplayed()
        device.takeScreenshot(File(app.filesDir,"agent-code8-help-ru.png"))
        ui.onNodeWithText("Закрыть").performClick()
        ui.waitForIdle(); delay(600)
        ui.onNodeWithText("5 минут").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Уровень заряда").performScrollTo()
        ui.waitForIdle(); delay(600)
        device.takeScreenshot(File(app.filesDir,"agent-code8-battery-ru.png"))
    }
    @Test fun debugBatteryContextUsesFastIntervalAndExpires(): Unit=runBlocking {
        val expiry=System.currentTimeMillis()+18000
        app.saveSettings(app.settings.value.copy(continuous=true,batterySettings=BatterySettings(currentSeconds=60),
            selfTelemetry=SelfTelemetrySettings(debugUntil=expiry)))
        val id=start(); delay(22000); stop(id)
        val rows=app.db.agentDao().page(0,0,Long.MAX_VALUE,id)
        val levels=rows.filter { it.metric=="LEVEL" }
        assertTrue(levels.size>=2)
        assertTrue(levels.any { it.stream=="self" })
        assertEquals(60,app.settings.value.selfTelemetry.fast(System.currentTimeMillis()))
    }
    @Test fun overheadExperimentProducesComparableEvidence(): Unit=runBlocking {
        val results=org.json.JSONArray()
        for(enabled in listOf(false,true,false,true)) {
            app.saveSettings(app.settings.value.copy(continuous=true,diagnostic=DiagnosticLevel.OFF,batterySettings=BatterySettings(enabled=false),
                selfTelemetry=SelfTelemetrySettings(enabled=enabled)))
            val id=start(); delay(3000)
            val cpu=android.os.Process.getElapsedCpuTime(); val elapsed=SystemClock.elapsedRealtime()
            delay(20000)
            val dt=SystemClock.elapsedRealtime()-elapsed; val used=android.os.Process.getElapsedCpuTime()-cpu
            results.put(JSONObject().put("self_enabled",enabled).put("cpu_ms",used).put("elapsed_ms",dt).put("session",id))
            stop(id)
        }
        File(app.filesDir,"agent-code8-overhead.json").writeText(JSONObject().put("runs",results)
            .put("scope","20-second steady intervals; excludes startup and final flush; short experiment, not full budget proof").toString())
    }
}
