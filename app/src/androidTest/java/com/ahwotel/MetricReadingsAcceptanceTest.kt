package com.ahwotel

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MetricReadingsAcceptanceTest {
    @get:Rule val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get()=ApplicationProvider.getApplicationContext<MonitorApp>()
    private val profile by lazy { IsolatedTestProfile(app) }
    @Before fun prepare()=runBlocking {
        profile.prepare()
        ui.runOnUiThread { ui.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        withContext(Dispatchers.IO) { app.db.clearAllTables() }
    }
    @After fun restore()=runBlocking {
        app.state.value=RuntimeState()
        ui.runOnUiThread { ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        profile.restore()
    }
    private fun show(tag: String) {
        ui.onNodeWithTag("monitor_list").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    @Test fun compactCardsKeepTwoColumnsAndPutHelpInBottomRightAtLargeFont() {
        var language by mutableStateOf("en")
        ui.setContent {
            val base=LocalContext.current
            val registry=androidx.activity.compose.LocalActivityResultRegistryOwner.current
                ?: (base as androidx.activity.result.ActivityResultRegistryOwner)
            val config=Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val localized=base.createConfigurationContext(config)
            val density=LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides config,
                LocalDensity provides Density(density.density,2f),
                androidx.activity.compose.LocalActivityResultRegistryOwner provides registry) {
                MaterialTheme { Box(Modifier.width(320.dp)) { Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    repeat(2) { index -> CompactMetricCard(
                        if(language=="ru") "Очень длинное название показателя" else "Very long performance metric title",
                        listOf(CompactMetricValue("1234567890.12 milliseconds","ACTIVITY_MANAGER_LONG_SOURCE")),
                        "layout_$index",Modifier.weight(1f),help={CompactInfoButton("info-$index",onClick={})})
                    } }
                } }
            }
        }
        fun verify(id: String) {
            val card=ui.onNodeWithTag("compact_metric_$id").fetchSemanticsNode().boundsInRoot
            val title=ui.onNodeWithTag("compact_title_$id").fetchSemanticsNode().boundsInRoot
            val value=ui.onNodeWithTag("compact_value_${id}_0").fetchSemanticsNode().boundsInRoot
            val index=id.substringAfterLast('_')
            val help=ui.onNodeWithContentDescription("info-$index").fetchSemanticsNode().boundsInRoot
            assertTrue(card.width>0 && title.width>0 && value.width>0 && help.width>=48f)
            assertTrue(title.left>=card.left && title.right<=card.right)
            assertTrue(value.left>=card.left && value.right<=card.right)
            assertTrue(help.right<=card.right && help.bottom<=card.bottom && help.top>=value.bottom)
        }
        ui.waitForIdle();verify("layout_0");verify("layout_1")
        val first=ui.onNodeWithTag("compact_metric_layout_0").fetchSemanticsNode().boundsInRoot
        val second=ui.onNodeWithTag("compact_metric_layout_1").fetchSemanticsNode().boundsInRoot
        assertTrue(first.right<=second.left)
        ui.runOnUiThread { language="ru" }
        ui.waitForIdle();verify("layout_0");verify("layout_1")
    }
    @Test fun compactCardKeepsOnlyValueHelpAndTechnicalSource() {
        ui.setContent {
            val base=LocalContext.current
            val registry=androidx.activity.compose.LocalActivityResultRegistryOwner.current
                ?: (base as androidx.activity.result.ActivityResultRegistryOwner)
            val config=Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            val localized=base.createConfigurationContext(config)
            CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides config,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides registry) {
                MaterialTheme { MetricHelpHost(app) { MonitorMetricCard(Metric.TEMPERATURE,
                    MetricReading(28.6,1000,"AVAILABLE","ACTIVITY_MANAGER"),Settings()) } }
            }
        }
        ui.waitUntil(10000) { runCatching { ui.onRoot().fetchSemanticsNode(); true }.getOrDefault(false) }
        ui.onNodeWithText("Session ended · last recorded measurement").assertDoesNotExist()
        ui.onNodeWithText("ACTIVITY_MANAGER").assertIsDisplayed()
        ui.onNodeWithTag("compact_value_TEMPERATURE_0").assertTextEquals(formatValue(28.6,Metric.TEMPERATURE))
        ui.onNodeWithTag("metric_help_TEMPERATURE").performClick()
        ui.onNodeWithTag("help-observation-TEMPERATURE").assertIsDisplayed()
        ui.onNodeWithText("Current observation").assertIsDisplayed()
        ui.onNodeWithText("Technical source: ACTIVITY_MANAGER").assertIsDisplayed()
    }
    @Test fun actualCardsRetainHeadroomUseBatteryStreamAndClearOnNewSession(): Unit = runBlocking {
        val now=System.currentTimeMillis()
        val settings=app.settings.value.copy(language="en")
        app.db.dao().start(SessionRow("readings",settings.deviceId,now-20000,reason="test",configuration=SettingsCodec.encode(settings),continuous=true,durationSeconds=300))
        app.db.dao().sample(SampleRow(sessionId="readings",time=now-10000,elapsed=1,segment=0,screenOn=true,
            headroom=.4,headroomTime=now-10000,capabilities="HEADROOM=AVAILABLE;BATTERY=DISABLED"))
        app.db.dao().sample(SampleRow(sessionId="readings",time=now,elapsed=2,segment=0,screenOn=true,
            capabilities="HEADROOM=WARMING_UP;BATTERY=DISABLED"))
        app.db.agentDao().insert(listOf(AgentMetric.LEVEL to 68.0,AgentMetric.TEMP to 28.6).map { (m,v) ->
            TelemetryRecord(sessionId="readings",stream="battery",metric=m.name,time=now,start=now,durationMs=0,segment=0,value=v,source="android_api",metadata="{}") })
        app.state.value=RuntimeState(sessionId="readings")
        ui.setContent { MaterialTheme { MetricHelpHost(app) { MonitorScreen(app) } } }
        ui.waitUntil(10000) { runCatching { ui.onNodeWithTag("monitor_list").assertExists();true }.getOrDefault(false) }
        show("compact_value_BATTERY_0"); ui.onNodeWithTag("compact_value_BATTERY_0").assertTextEquals("68 %")
        show("compact_value_TEMPERATURE_0"); ui.onNodeWithTag("compact_value_TEMPERATURE_0").assertTextEquals(formatValue(28.6,Metric.TEMPERATURE))
        show("compact_value_HEADROOM_0"); ui.onNodeWithTag("compact_value_HEADROOM_0").assertTextEquals(formatValue(.4,Metric.HEADROOM))
        show("monitor_last_measurement");ui.onNodeWithTag("monitor_last_measurement")
            .assertTextEquals(app.getString(R.string.last_measurement,formatTime(now)))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.filesDir,"metric-headroom.png"))
        app.state.value=RuntimeState()
        show("compact_value_TEMPERATURE_0"); ui.onNodeWithTag("compact_value_TEMPERATURE_0").assertTextEquals(formatValue(28.6,Metric.TEMPERATURE))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.filesDir,"metric-battery.png"))
        app.state.value=RuntimeState(sessionId="new-session")
        ui.waitForIdle()
        show("compact_value_BATTERY_0"); ui.onNodeWithTag("compact_value_BATTERY_0").assertTextEquals("—")
        show("compact_value_HEADROOM_0"); ui.onNodeWithTag("compact_value_HEADROOM_0").assertTextEquals("—")
    }

    @Test fun monitoringUsesTwoColumnsCompactLabelsAndScreenOffHelp(): Unit = runBlocking {
        val now=System.currentTimeMillis()-120000
        val settings=app.settings.value.copy(language="en")
        app.db.dao().start(SessionRow("compact",settings.deviceId,now-1000,reason="test",
            configuration=SettingsCodec.encode(settings),continuous=true,durationSeconds=300))
        app.db.dao().sample(SampleRow(sessionId="compact",time=now,elapsed=1,segment=0,screenOn=true,
            cpuWait=3.0,probeDelay=4.0,probeTime=now,probeInterval=2000,memoryAvailable=1024.0,
            memoryPercent=50.0,storagePercent=60.0,thermal=1.0,headroom=.5,headroomTime=now,state=0.0,
            capabilities="CPU=UNSUPPORTED;CPU_WAIT=AVAILABLE;PROBE_DELAY=AVAILABLE;MEMORY=AVAILABLE;STORAGE=AVAILABLE;THERMAL=AVAILABLE;HEADROOM=AVAILABLE",
            sources="[[\"ACTIVITY_MANAGER\",\"APP\",\"NONE\",[\"CPU_WAIT\"]]]"))
        app.state.value=RuntimeState(sessionId="compact")
        ui.setContent {
            val base=LocalContext.current
            val registry=androidx.activity.compose.LocalActivityResultRegistryOwner.current
                ?: (base as androidx.activity.result.ActivityResultRegistryOwner)
            val config=Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("en")) }
            val localized=base.createConfigurationContext(config)
            val density=LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides config,
                LocalDensity provides Density(density.density,1.5f),
                androidx.activity.compose.LocalActivityResultRegistryOwner provides registry) {
                MaterialTheme { Box(Modifier.width(360.dp)) {
                    MetricHelpHost(app) { MonitorScreen(app) }
                } }
            }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("monitor_list").fetchSemanticsNodes().isNotEmpty() }

        show("monitor_metric_cell_PROBE_DELAY")
        val probe=ui.onNodeWithTag("monitor_metric_cell_PROBE_DELAY").fetchSemanticsNode().boundsInRoot
        val memory=ui.onNodeWithTag("monitor_metric_cell_MEMORY_AVAILABLE").fetchSemanticsNode().boundsInRoot
        assertTrue(probe.width>0 && memory.width>0);assertTrue(probe.right<=memory.left)
        ui.onNodeWithTag("compact_title_PROBE_DELAY").assertExists()
        ui.onNodeWithTag("compact_value_PROBE_DELAY_0").assertExists()
        ui.onAllNodesWithText("Available").assertCountEquals(0)
        ui.onAllNodesWithText(app.getString(R.string.probe_window,2000)).assertCountEquals(0)
        ui.onAllNodesWithText(app.getString(R.string.ext_stale)).assertCountEquals(0)
        ui.onAllNodesWithText(app.getString(R.string.cpu_wait_hint)).assertCountEquals(0)
        show("compact_source_CPU_WAIT_0")
        ui.onNodeWithTag("compact_source_CPU_WAIT_0").assertTextEquals("ACTIVITY_MANAGER")

        show("monitor_last_measurement")
        ui.onAllNodesWithTag("monitor_last_measurement").assertCountEquals(1)
        ui.onNodeWithTag("monitor_last_measurement").assertTextEquals(app.getString(R.string.last_measurement,formatTime(now)))
        ui.onAllNodesWithText(app.getString(R.string.partial_assessment)).assertCountEquals(0)
        show("unavailable_monitor")
        ui.onAllNodesWithTag("unavailable_monitor").assertCountEquals(1)
        ui.onNodeWithTag("unavailable_monitor").performClick()
        show("compact_metric_CPU")
        ui.onNodeWithTag("monitor_list").performScrollToNode(hasText(app.getString(R.string.screen_off)))
        val helpLabel=app.getString(R.string.help_about,app.getString(R.string.screen_off))
        ui.onNodeWithContentDescription(helpLabel).assertIsDisplayed().performClick()
        ui.onNodeWithText(app.getString(R.string.screen_off_hint)).assertIsDisplayed()
        ui.onNodeWithTag("help-content").performScrollToNode(hasText(app.getString(R.string.background_collection_hint)))
        ui.onNodeWithText(app.getString(R.string.background_collection_hint)).assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.filesDir,"monitor-compact-columns.png"))
    }
}
