package com.ahwotel

import android.os.Bundle
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.ahwotel.oem.OemCategory
import com.ahwotel.oem.OemSettings
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReviewRegressionAcceptanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val app get()=ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun prepare(): Unit=runBlocking {
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");profile.prepare()
        app.saveSettings(app.settings.value.copy(continuous=true,intervalMs=1000,diagnostic=DiagnosticLevel.BASIC))
        app.db.agentDao().expire(Long.MAX_VALUE) // isolated test history, after profile stopped the test worker
        ui.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        ui.waitUntil(15000) { ui.onAllNodesWithText("Start monitoring").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun restore(): Unit=runBlocking { device.wakeUp();app.managed.replace(Bundle());profile.restore() }
    private fun click(text: String)=ui.onNode(hasText(text) and hasClickAction()).performClick()
    private suspend fun start(): String {
        click("Start monitoring");withTimeout(10000) { while(app.state.value.sessionId==null) delay(50) }
        return app.state.value.sessionId!!
    }
    private suspend fun stop(id: String) {
        MonitoringService.stop(app,id);withTimeout(15000) { while(app.state.value.sessionId!=null) delay(50) }
    }
    private fun metric(title: String) {
        ui.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        ui.onNodeWithTag("agent_metric_selector").performScrollTo().performClick()
        ui.onNode(hasText(title) and hasClickAction()).performScrollTo().performClick()
    }
    private fun selectPoint(title: String) {
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription(title).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription(title).performScrollTo().performTouchInput { click(Offset(width*.92f,height*.5f)) }
        ui.waitForIdle()
    }
    private fun shot(name: String) {
        ui.waitForIdle();Thread.sleep(500)
        assertTrue(device.takeScreenshot(File(app.filesDir,name)))
    }
    private fun selectedText(text: String) {
        try {
            ui.waitUntil(10000) { ui.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty() }
        } catch(error: Throwable) {
            File(app.filesDir,"code9-chart-failure.txt").writeText(ui.onRoot().printToString())
            shot("code9-chart-failure.png")
            throw error
        }
        ui.onNodeWithText(text,substring=true).performScrollTo().assertIsDisplayed()
    }
    @Test fun systemBackClosesHelpThenReturnsToBothParentsInEnglishAndRussian(): Unit=runBlocking {
        for(language in listOf("en","ru")) {
            app.saveSettings(app.settings.value.copy(language=language))
            val monitor=if(language=="en") "Monitor" else "Мониторинг"
            val history=if(language=="en") "History" else "История"
            ui.waitUntil(15000) { ui.onAllNodesWithText(monitor).fetchSemanticsNodes().isNotEmpty() }
            for(parent in listOf(monitor,history)) for(child in if(language=="en") listOf("Battery","Self Telemetry") else listOf("Батарея","Телеметрия приложения")) {
                click(parent);click(child)
                ui.onNodeWithTag("agent_metric_help").performClick()
                ui.onNodeWithText(if(language=="en") "Close" else "Закрыть").assertIsDisplayed()
                device.waitForIdle()
                device.pressBack()
                ui.waitUntil(5000) { ui.onAllNodesWithText(if(language=="en") "Close" else "Закрыть").fetchSemanticsNodes().isEmpty() }
                ui.onNodeWithTag("agent_metric_help").assertIsDisplayed()
                device.pressBack()
                ui.waitUntil(5000) { ui.onAllNodesWithTag("agent_metric_help").fetchSemanticsNodes().isEmpty() }
                ui.onNodeWithTag("agent_metric_help").assertDoesNotExist()
                ui.onNode(hasText(child) and hasClickAction()).assertIsDisplayed()
                assertFalse(ui.activity.isFinishing)
            }
        }
    }
    @Test fun sumsMaximaAndNominalStatesHaveAccurateLocalizedPlotLabels(): Unit=runBlocking {
        val now=System.currentTimeMillis()
        fun row(metric: String,time: Long,value: Double)=TelemetryRecord(sessionId="chart-code9",stream="self",metric=metric,
            time=time,start=time,durationMs=0,segment=0,value=value,low=value,high=value,count=1,source="test_fixture",metadata="{}")
        app.db.agentDao().insert(listOf(row("CPU_DELTA",now-10000,100.0),row("CPU_DELTA",now-9900,200.0),row("CPU_DELTA",now-9800,300.0),
            row("WAKE_MAX",now-10000,200.0),row("WAKE_MAX",now-9900,300.0),
            row("PLUG",now-120000,1.0),row("PLUG",now-60000,2.0),row("PLUG",now-100,1.0),row("PLUG",now,2.0)))
        for(language in listOf("en","ru")) {
            app.saveSettings(app.settings.value.copy(language=language))
            val ru=language=="ru"
            val self=if(ru) "Телеметрия приложения" else "Self Telemetry"
            ui.waitUntil(15000) { ui.onAllNodesWithText(self).fetchSemanticsNodes().isNotEmpty() }
            click(self)
            val sumTitle=ui.activity.getString(AgentMetric.CPU_DELTA.title)
            metric(sumTitle)
            ui.onNodeWithText(if(ru) "5 минут" else "5 minutes").performScrollTo().performClick()
            selectPoint(sumTitle)
            selectedText(if(ru) "Сумма за интервал: 600" else "Sum for interval: 600")
            shot("code9-sum-$language.png")
            val maxTitle=ui.activity.getString(AgentMetric.WAKE_MAX.title)
            metric(maxTitle);selectPoint(maxTitle)
            selectedText(if(ru) "Максимум за интервал: 300" else "Maximum for interval: 300")
            device.pressBack();ui.waitForIdle();click(if(ru) "Батарея" else "Battery")
            val stateTitle=ui.activity.getString(AgentMetric.PLUG.title)
            metric(stateTitle)
            ui.onNodeWithText(if(ru) "5 минут" else "5 minutes").performScrollTo().performClick()
            selectPoint(stateTitle)
            selectedText(if(ru) "Несколько наблюдаемых состояний" else "Multiple observed states")
            ui.onNodeWithText(if(ru) "1 — Сеть" else "1 — AC power",substring=true).assertExists()
            shot("code9-state-$language.png")
            device.pressBack();ui.waitForIdle()
        }
    }
    @Test fun screenOffPauseDoesNotBecomeSchedulerDelay(): Unit=runBlocking {
        app.saveSettings(app.settings.value.copy(collectScreenOff=false,selfTelemetry=SelfTelemetrySettings(groups=setOf(SelfGroup.RUNTIME))))
        val id=start();delay(2000);device.sleep()
        withTimeout(6000) { while(!app.state.value.paused) delay(100) }
        delay(12000);device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        withTimeout(6000) { while(app.state.value.paused) delay(100) }
        delay(2000);stop(id)
        val rows=app.db.agentDao().page(0,0,Long.MAX_VALUE,id)
        assertTrue(rows.any { it.metric=="DELAY" })
        assertTrue(rows.filter { it.metric=="DELAY" }.all { (it.high ?: 0.0)<10000 })
        assertTrue(rows.filter { it.metric=="MISSED" }.all { (it.value ?: 0.0)<10 })
    }
    @Test fun localAndManagedUploadIntervalsReachOemOutbox(): Unit=runBlocking {
        for(managed in listOf(false,true)) for(interval in listOf(0,3600)) {
            app.managed.replace(Bundle())
            app.saveSettings(app.settings.value.copy(otlpEnabled=false))
            if(managed) assertTrue(app.managed.replace(Bundle().apply { putInt("upload_interval",interval) }))
            app.saveSettings(app.settings.value.copy(otlpEnabled=true,endpoint="https://127.0.0.1:9/v1/metrics",queueHours=1,
                uploadIntervalSeconds=if(managed) 0 else interval,
                oem=OemSettings(enabled=true,knox=false,categories=setOf(OemCategory.DEVICE))))
            val id=start()
            withTimeout(30000) { while(app.db.dao().outboxCount()==0L || app.db.oemDao().page(0,0,Long.MAX_VALUE,id).isEmpty()) delay(100) }
            val sql=app.db.openHelper.readableDatabase
            withTimeout(10000) {
                while(!sql.query("SELECT id FROM outbox WHERE stream='oem' LIMIT 1").use { it.moveToFirst() }) delay(100)
            }
            sql.query("SELECT createdAt,dueAt,attempts FROM outbox WHERE stream='oem'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                do { assertEquals(interval*1000L,cursor.getLong(1)-cursor.getLong(0)); if(interval>0) assertEquals(0,cursor.getInt(2)) } while(cursor.moveToNext())
            }
            stop(id)
        }
    }
}
