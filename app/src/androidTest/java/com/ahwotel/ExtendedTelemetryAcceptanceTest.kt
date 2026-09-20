package com.ahwotel

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExtendedTelemetryAcceptanceTest {
    @get:Rule val ui=createComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<MonitorApp>()
    private val profile by lazy { IsolatedTestProfile(app) }
    @Before fun prepare(): Unit=runBlocking { profile.prepare() }
    @After fun restore(): Unit=runBlocking { profile.restore() }
    private fun reveal(list: String, target: String) {
        ui.waitUntil(15000) { runCatching {
            ui.onNodeWithTag(list).performScrollToNode(hasTestTag(target))
            ui.onNodeWithTag(target).assertIsDisplayed()
            true
        }.getOrDefault(false) }
    }
    @Composable private fun TestUi(content: @Composable ()->Unit) {
        MaterialTheme(colorScheme=androidx.compose.material3.darkColorScheme(primary=Accent,surface=Surface,onSurface=Ink,onBackground=Ink,
            background=androidx.compose.ui.graphics.Color(0xFF0B141D))) {
            androidx.compose.material3.Surface { content() }
        }
    }
    private fun screenshot(name: String) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.filesDir,name+".png"))
    }
    @Test fun workerReadsBatteryAndIoWithActualAppCredentials(): Unit=runBlocking {
        app.saveSettings(app.settings.value.copy(continuous=true,diagnostic=DiagnosticLevel.BASIC,
            batterySettings=BatterySettings(currentSeconds=15),
            selfTelemetry=SelfTelemetrySettings(ioSeconds=15,fastSeconds=15,windowSeconds=60)))
        ui.setContent { TestUi { MetricHelpHost(app) { MonitorScreen(app) } } }
        MonitoringService.start(app,StartRequest(UUID.randomUUID().toString(),true,300,2000,app.settings.value.enabled,"extended_acceptance"))
        withTimeout(15000) { while(app.state.value.sessionId==null) delay(100) }
        val id=app.state.value.sessionId!!
        delay(67000)
        MonitoringService.stop(app,id)
        withTimeout(15000) { while(app.state.value.sessionId!=null) delay(100) }
        var after=0L; val rows=mutableListOf<TelemetryRecord>()
        while(true) {
            val page=app.db.agentDao().page(after,0,Long.MAX_VALUE,id)
            if(page.isEmpty()) break
            rows+=page; after=page.last().id
        }
        for(metric in listOf("CURRENT_AVERAGE","ENERGY","CHARGE_TIME","CHARGING_DETAIL","IO_READ_BYTES","IO_WRITE_BYTES","SYSTEM_PSI_AVG10"))
            assertTrue("missing $metric",rows.any { it.metric==metric })
        assertTrue(rows.any { it.metric=="LEVEL" && it.status=="AVAILABLE" && it.value!=null })
        assertTrue(rows.any { it.metric=="IO_READ_BYTES" && it.status=="AVAILABLE" && it.value!=null })
        assertTrue(rows.filter { it.status!="AVAILABLE" }.all { it.value==null })
        assertTrue(rows.any { it.metric=="IO_WRITE_BYTES_RATE" && it.value!=null })
        File(app.filesDir,"extended-worker.json").writeText(org.json.JSONArray(rows.map(AgentHistoryExport::json)).toString())
    }
    @Test fun batteryDisclosureAndHelpWorkInBothLanguages(): Unit=runBlocking {
        val now=System.currentTimeMillis()
        app.db.agentDao().insert(listOf(
            TelemetryRecord(sessionId="disclosure",stream="wear",metric="SOH",time=now,start=now,durationMs=0,segment=0,
                status="UNSUPPORTED",reason="NO_VERIFIED_SOURCE",source="test",metadata="{}"),
            TelemetryRecord(sessionId="disclosure",stream="battery",metric="LEVEL",time=now,start=now,durationMs=0,segment=0,value=80.0,source="test",metadata="{}")))
        var language by mutableStateOf("en")
        ui.setContent {
            val base=LocalContext.current
            val registry=androidx.activity.compose.LocalActivityResultRegistryOwner.current ?: (base as androidx.activity.result.ActivityResultRegistryOwner)
            val config=Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val context=base.createConfigurationContext(config)
            CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides config,androidx.activity.compose.LocalActivityResultRegistryOwner provides registry) {
                TestUi { AgentTelemetryScreen(app,true,history=true,session="disclosure",historyEnd=now+1) {} }
            }
        }
        reveal("agent_list","unavailable_agent_BATTERY")
        for(lang in listOf("en","ru")) {
            ui.runOnIdle { language=lang }
            val toggle=ui.onNodeWithTag("unavailable_agent_BATTERY")
            toggle.performScrollTo().assertIsDisplayed()
            ui.onAllNodesWithText(if(lang=="en") "State of health" else "Остаточное здоровье SOH").assertCountEquals(0)
            toggle.performClick()
            val title=ui.onNodeWithText(if(lang=="en") "State of health" else "Остаточное здоровье SOH")
            title.performScrollTo().assertIsDisplayed()
            screenshot("extended-disclosure-"+lang)
            // Collapse again, preserving access to all available metrics and the graph.
            toggle.performScrollTo().performClick()
        }
    }
    @Test fun cpuDisclosureIsConsistentInMonitorAndHistory(): Unit=runBlocking {
        val now=System.currentTimeMillis()
        app.db.dao().sample(SampleRow(sessionId="cpu-disclosure",time=now,elapsed=SystemClock.elapsedRealtime(),segment=0,
            cpuWait=4.0,probeTime=now,probeInterval=1000,screenOn=true,capabilities="CPU=UNSUPPORTED;CPU_WAIT=AVAILABLE"))
        var history by mutableStateOf(false)
        ui.setContent { TestUi { MetricHelpHost(app) { if(history) HistoryScreen(app) else MonitorScreen(app) } } }
        reveal("monitor_list","unavailable_monitor_CPU")
        ui.onNodeWithTag("unavailable_monitor_CPU").assertIsDisplayed().performClick()
        screenshot("extended-cpu-monitor")
        ui.runOnIdle { history=true }
        reveal("history_list","unavailable_history_CPU")
        ui.onNodeWithTag("unavailable_history_CPU").performScrollTo().assertIsDisplayed().performClick()
        screenshot("extended-cpu-history")
    }
}
