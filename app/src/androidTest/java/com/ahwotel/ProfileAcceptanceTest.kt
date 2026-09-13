package com.ahwotel

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ahwotel.oem.OemSettings
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ProfileAcceptanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val app get()=ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    @Before fun prepare(): Unit=runBlocking { profile.prepare()
        ui.activityRule.scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun restore(): Unit=runBlocking { profile.restore() }
    private fun click(text: String)=ui.onNode(hasText(text) and hasClickAction()).performClick()
    @Test fun visibleBatteryAndSelfOnlySelectionsSaveAndCollectForBothOldProfiles(): Unit=runBlocking {
        for(legacy in listOf(false,true)) for(battery in listOf(false,true)) {
            app.saveSettings(app.settings.value.copy(continuous=true,
                enabled=if(legacy) setOf(CollectorKind.MEMORY,CollectorKind.BATTERY) else setOf(CollectorKind.MEMORY),
                batterySettings=BatterySettings(enabled=battery,wear=false,passport=false),
                selfTelemetry=SelfTelemetrySettings(enabled=!battery,groups=setOf(SelfGroup.CPU,SelfGroup.RUNTIME)),oem=OemSettings(enabled=false)))
            ui.waitUntil(10000) { ui.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() }
            click("Settings")
            ui.onNodeWithTag("toggle:Memory").performScrollTo().performClick()
            ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Save settings"))
            ui.onNodeWithText("Save settings").performClick()
            ui.waitUntil(10000) { CollectorKind.MEMORY !in app.settings.value.enabled }
            assertTrue(app.settings.value.hasCollectors())
            click("Monitor");click("Start monitoring")
            withTimeout(10000) { while(app.state.value.sessionId==null) delay(50) }
            val id=app.state.value.sessionId!!
            delay(2000)
            MonitoringService.stop(app,id)
            withTimeout(15000) { while(app.state.value.sessionId!=null) delay(50) }
            val rows=app.db.agentDao().page(0,0,Long.MAX_VALUE,id)
            assertTrue(rows.any { it.stream==if(battery) "battery" else "self" })
            if(!battery) assertEquals(1.0,rows.filter { it.metric=="WAKE_COUNT" }.sumOf { it.value ?: 0.0 },0.0)
        }
    }
}
