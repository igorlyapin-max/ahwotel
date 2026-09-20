package com.ahwotel

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
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
import com.ahwotel.oem.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HistoryNavigationAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MonitorApp>()
    private val profile by lazy { IsolatedTestProfile(app) }
    @Before fun prepare(): Unit = runBlocking {
        profile.prepare()
        ui.runOnUiThread { ui.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        withContext(Dispatchers.IO) { app.db.clearAllTables() }
    }
    @After fun restore(): Unit = runBlocking {
        ui.runOnUiThread { ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        profile.restore()
    }
    @Composable private fun Theme(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = darkColorScheme(primary=Accent, surface=Surface, onSurface=Ink)) {
            Surface { content() }
        }
    }
    private fun reveal(tag: String) {
        var lastFailure: Throwable? = null
        try {
            ui.waitUntil(15000) { runCatching {
                if(ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())
                    ui.onNodeWithTag("history_list").performScrollToNode(hasTestTag(tag))
                // A chart can grow when its asynchronous query replaces an empty plot.
                // Scroll to the control inside the composed card, not only to its lazy item.
                ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed(); true
            }.onFailure { lastFailure=it }.getOrDefault(false) }
        } catch(error: Throwable) {
            File(app.filesDir,"history-navigation-failure.txt").writeText("$tag\n$lastFailure\n"+runCatching { ui.onRoot().printToString() }.getOrDefault("No active Compose root"))
            shot("failure")
            throw error
        }
    }
    private fun period(metric: String): String {
        val tag = "history_period_$metric"
        reveal(tag)
        return ui.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    }
    private fun click(metric: String, action: String) {
        val tag = "history_${metric}_$action"
        reveal(tag); ui.onNodeWithTag(tag).performTouchInput { click() }; ui.waitForIdle()
    }
    private fun shot(name: String) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.filesDir,"history-$name.png"))
    }
    @Test fun chartButtonsShareWindowPreserveScrollAndHandleLimits(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        app.db.dao().sample(SampleRow(sessionId="navigation",time=now,elapsed=1,segment=1,
            memoryAvailable=1024.0,cpuWait=2.0,probeTime=now,screenOn=true,
            capabilities="CPU=UNSUPPORTED;CPU_WAIT=AVAILABLE"))
        ui.setContent { Theme { MetricHelpHost(app) { HistoryScreen(app) } } }
        reveal("unavailable_history")
        ui.onNodeWithTag("unavailable_history").performClick()
        reveal("history_CPU_zoom_in")
        ui.onNodeWithTag("history_CPU_zoom_in").assertIsDisplayed()
        reveal("history_MEMORY_AVAILABLE_later")
        ui.onNodeWithTag("history_MEMORY_AVAILABLE_later").assertIsNotEnabled()
        val original = period("MEMORY_AVAILABLE")
        reveal("history_MEMORY_AVAILABLE_zoom_in")
        val y = ui.onNodeWithTag("history_MEMORY_AVAILABLE_zoom_in").fetchSemanticsNode().boundsInRoot.top
        ui.onNodeWithTag("history_MEMORY_AVAILABLE_zoom_in").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("history_MEMORY_AVAILABLE_zoom_in").assertIsDisplayed()
        assertEquals(y, ui.onNodeWithTag("history_MEMORY_AVAILABLE_zoom_in").fetchSemanticsNode().boundsInRoot.top, 2f)
        val zoomed = period("MEMORY_AVAILABLE")
        assertNotEquals(original, zoomed)
        assertEquals(zoomed, period("BATTERY"))
        click("BATTERY", "earlier")
        val earlier = period("BATTERY")
        assertNotEquals(zoomed, earlier)
        assertEquals(earlier, period("MEMORY_AVAILABLE"))
        click("MEMORY_AVAILABLE", "later")
        assertEquals(zoomed, period("MEMORY_AVAILABLE"))
        click("MEMORY_AVAILABLE", "zoom_out")
        assertEquals(original, period("MEMORY_AVAILABLE"))
        repeat(15) { click("MEMORY_AVAILABLE", "zoom_in") }
        ui.onNodeWithTag("history_MEMORY_AVAILABLE_zoom_in").assertIsNotEnabled()
        repeat(18) { click("MEMORY_AVAILABLE", "zoom_out") }
        ui.onNodeWithTag("history_MEMORY_AVAILABLE_zoom_out").assertIsNotEnabled()
        shot("shared-window")
    }
    @Test fun emptyHistoryControlsAndNarrowBilingualLabels() {
        var language by mutableStateOf("en")
        ui.setContent {
            val base = LocalContext.current
            val registry = androidx.activity.compose.LocalActivityResultRegistryOwner.current
                ?: (base as androidx.activity.result.ActivityResultRegistryOwner)
            val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config), LocalConfiguration provides config,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides registry) {
                Theme { Box(Modifier.width(320.dp).fillMaxHeight()) { MetricHelpHost(app) { HistoryScreen(app) } } }
            }
        }
        for(lang in listOf("en", "ru")) {
            ui.runOnIdle { language = lang }
            reveal("history_CPU_earlier")
            val labels = if(lang == "en") listOf("Earlier","Later","Zoom in","Zoom out")
                else listOf("Раньше","Позже","Приблизить","Отдалить")
            for((action,label) in listOf("earlier","later","zoom_in","zoom_out").zip(labels)) {
                ui.onNodeWithTag("history_CPU_$action").assertIsDisplayed().assertContentDescriptionEquals(label)
            }
            click("CPU","earlier")
            ui.onNodeWithTag("history_CPU_later").assertIsEnabled()
            ui.onNodeWithTag("history_CPU_zoom_in").performTouchInput { longClick() }
            ui.onNodeWithText(labels[2]).assertIsDisplayed()
            shot("empty-$lang")
            ui.waitUntil(5000) { ui.onAllNodesWithText(labels[2]).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun chartKeepsTheLastSnapshotUntilRefreshCompletes(): Unit = runBlocking {
        var request by mutableIntStateOf(1)
        val first=CompletableDeferred<List<ChartBucket>>()
        val second=CompletableDeferred<List<ChartBucket>>()
        ui.setContent { Theme {
            val state=rememberRetainedLoad("stable",request,load={ key -> if(key==1) first.await() else second.await() })
            val snapshot=state.snapshot
            MetricPlot("Stable",snapshot?.value.orEmpty(),0,1000,"%",dataReady=snapshot!=null,
                loading=state.loading,failed=state.failed,testId="retained")
        } }
        first.complete(listOf(ChartBucket(0,0,"s",500,10.0,10.0,10.0,1)))
        ui.waitUntil(10000) { ui.onAllNodesWithTag("chart_plot_retained").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle { request=2 }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("chart_loading_retained").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("chart_plot_retained").assertIsDisplayed()
        ui.onAllNodesWithText(ui.activity.getString(R.string.no_data)).assertCountEquals(0)
        second.complete(emptyList())
        ui.waitUntil(10000) { ui.onAllNodesWithTag("chart_empty_retained").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(ui.activity.getString(R.string.no_data)).assertIsDisplayed()
    }

    @Test fun failedInitialChartLoadCanBeRetriedWithoutShowingNoData() {
        var retry by mutableIntStateOf(0)
        val attempts=AtomicInteger()
        ui.setContent { Theme {
            val state=rememberRetainedLoad("retry",1,retryKey=retry,load={
                if(attempts.getAndIncrement()==0) error("fixture_failure")
                listOf(ChartBucket(0,0,"s",500,10.0,10.0,10.0,1))
            })
            MetricPlot("Retry",state.snapshot?.value.orEmpty(),0,1000,"%",dataReady=state.snapshot!=null,
                loading=state.loading,failed=state.failed,retry={retry++},testId="retry")
        } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("chart_retry_retry").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(ui.activity.getString(R.string.no_data)).assertDoesNotExist()
        ui.onNodeWithTag("chart_retry_retry").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("chart_plot_retry").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("chart_retry_retry").assertDoesNotExist()
    }

    @Test fun currentAgentProbeFoldsUnsupportedMetricButRecordedValueKeepsItVisible(): Unit = runBlocking {
        app.saveSettings(app.settings.value.copy(batterySettings=app.settings.value.batterySettings.copy(enabled=true,current=true)))
        val now=System.currentTimeMillis()
        app.agentTelemetry.probes.value=listOf(TelemetryRecord(sessionId="",stream="battery",metric="LEVEL",time=now,
            start=now,durationMs=0,segment=0,source="sysfs",status="UNSUPPORTED",reason="NOT_FOUND",metadata="{}"))
        ui.setContent { Theme { MetricHelpHost(app) { AgentTelemetryScreen(app,true,back={}) } } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("unavailable_agent").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithTag("compact_metric_selected_LEVEL").assertCountEquals(0)
        ui.onNodeWithTag("unavailable_agent").performClick()
        ui.onNodeWithTag("agent_list").performScrollToNode(hasTestTag("compact_metric_unavailable_LEVEL"))
        ui.onNodeWithTag("compact_metric_unavailable_LEVEL").assertIsDisplayed()

        val session="agent-probe"
        app.db.dao().start(SessionRow(session,app.settings.value.deviceId,now-1000,reason="fixture",
            configuration=SettingsCodec.encode(app.settings.value),continuous=true,durationSeconds=300))
        app.db.agentDao().insert(listOf(TelemetryRecord(sessionId=session,stream="battery",metric="LEVEL",time=now,
            start=now,durationMs=0,segment=0,value=55.0,source="android_api",status="AVAILABLE",metadata="{}")))
        app.state.value=RuntimeState(sessionId=session)
        ui.waitUntil(10000) { ui.onAllNodesWithTag("compact_metric_selected_LEVEL").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("compact_metric_selected_LEVEL").assertIsDisplayed()
        app.state.value=RuntimeState()
        app.agentTelemetry.probes.value=emptyList()
    }

    @Test fun extendedHistoryGraphsExposeTheSameWindowControls(): Unit = runBlocking {
        var screen by mutableStateOf("battery")
        val windows=mapOf("battery" to HistoryWindowState(),"self" to HistoryWindowState(),"storage" to HistoryWindowState())
        ui.setContent { Theme { MetricHelpHost(app) { key(screen) {
                when(screen) {
                    "battery" -> AgentTelemetryScreen(app,true,history=true,historyWindow=windows.getValue(screen),back={})
                    "self" -> AgentTelemetryScreen(app,false,history=true,historyWindow=windows.getValue(screen),back={})
                    "storage" -> AgentTelemetryScreen(app,false,history=true,historyWindow=windows.getValue(screen),groupFilter="STORAGE",back={})
                    else -> OemScreen(app,{})
                }
            }
        } } }
        val cases = listOf(
            "battery" to "LEVEL",
            "self" to "CPU_TIME",
            "storage" to "DB_SIZE",
        )
        for((page,metric) in cases) {
            ui.runOnIdle { screen=page }
            val window=windows.getValue(page)
            val tag="history_agent_${metric}_earlier"
            ui.waitUntil(10000) { runCatching {
                ui.onNodeWithTag("agent_list").performScrollToNode(hasTestTag(tag))
                ui.onNodeWithTag(tag).assertIsDisplayed(); true
            }.getOrDefault(false) }
            val old=window.end
            ui.onNodeWithTag(tag).performClick()
            ui.runOnIdle { assertTrue(window.end<old) }
        }

        val now=System.currentTimeMillis()
        app.db.oemDao().observations(listOf(OemObservationRow.from(OemObservation(OemMetric.ANDROID_SDK,"android_standard",
            OemSource.ANDROID_STANDARD,OemScope.DEVICE,number=31.0,time=now),"oem-navigation",0)))
        ui.runOnIdle { screen="oem" }
        ui.onNodeWithTag("oem_screen").performScrollToNode(hasText(app.getString(R.string.oem_period_export)))
        ui.onNodeWithText(app.getString(R.string.oem_period_export)).performClick()
        ui.onNodeWithTag("oem_screen").performScrollToNode(hasText(app.getString(R.string.oem_graph)))
        ui.onNodeWithText(app.getString(R.string.oem_graph)).performClick()
        val oemTag="history_oem_ANDROID_SDK_android_standard_earlier"
        ui.onNodeWithTag("oem_screen").performScrollToNode(hasTestTag(oemTag))
        ui.onNodeWithTag(oemTag).assertIsDisplayed()
    }

    @Test fun liveExtendedScreensUseCompactCardsAndReserveGraphsForHistory() {
        runBlocking { app.saveSettings(app.settings.value.copy(oem=app.settings.value.oem.copy(enabled=true))) }
        var screen by mutableStateOf("battery")
        ui.setContent { Theme { MetricHelpHost(app) { key(screen) {
                when(screen) {
                    "battery" -> AgentTelemetryScreen(app,true,back={})
                    "self" -> AgentTelemetryScreen(app,false,back={})
                    "storage" -> AgentTelemetryScreen(app,false,groupFilter="STORAGE",back={})
                    else -> OemScreen(app,{})
                }
            }
        } } }
        for((page,metric) in listOf("battery" to "LEVEL","self" to "CPU_TIME","storage" to "DB_SIZE")) {
            ui.runOnIdle { screen=page }
            ui.waitUntil(10000) { ui.onAllNodesWithTag("compact_metric_selected_$metric").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("agent_list").performScrollToNode(hasTestTag("compact_metric_selected_$metric"))
            ui.onNodeWithTag("compact_metric_selected_$metric").assertIsDisplayed()
            ui.onAllNodesWithTag("chart_plot_agent_$metric").assertCountEquals(0)
            ui.onAllNodesWithTag("chart_empty_agent_$metric").assertCountEquals(0)
        }
        ui.runOnIdle { screen="oem" }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("oem_screen").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("oem_screen").performScrollToNode(hasTestTag("compact_metric_oem_MANUFACTURER"))
        val first=ui.onNodeWithTag("compact_metric_oem_MANUFACTURER").fetchSemanticsNode().boundsInRoot
        val second=ui.onNodeWithTag("compact_metric_oem_MODEL").fetchSemanticsNode().boundsInRoot
        assertTrue(first.width>0 && second.width>0);assertTrue(first.right<=second.left)
        ui.onAllNodesWithText(app.getString(R.string.oem_graph)).assertCountEquals(0)
    }

    @Test fun profileActionsFitCompactScreenWithLargeEnglishAndRussianText() {
        var language by mutableStateOf("en")
        ui.setContent {
            val base=LocalContext.current
            val registry=androidx.activity.compose.LocalActivityResultRegistryOwner.current
                ?: (base as androidx.activity.result.ActivityResultRegistryOwner)
            val config=Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val density=LocalDensity.current
            CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config),LocalConfiguration provides config,
                LocalDensity provides Density(density.density,1.5f),androidx.activity.compose.LocalActivityResultRegistryOwner provides registry) {
                Theme { MetricHelpHost(app) { Box(Modifier.width(320.dp).fillMaxHeight()) { SettingsScreen(app) } } }
            }
        }
        for(lang in listOf("en","ru")) {
            ui.runOnIdle { language=lang }
            val localized=Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
                .let { app.createConfigurationContext(it) }
            val export=localized.getString(R.string.export_profile)
            val import=localized.getString(R.string.import_profile)
            val scroll=ui.onNode(hasScrollAction())
            scroll.performScrollToNode(hasText(export))
            ui.onNodeWithText(export).assertIsDisplayed()
            scroll.performScrollToNode(hasText(import))
            ui.onNodeWithText(import).assertIsDisplayed()
            val a=ui.onNodeWithText(export).fetchSemanticsNode().boundsInRoot
            val b=ui.onNodeWithText(import).fetchSemanticsNode().boundsInRoot
            assertTrue(a.width>0 && b.width>0)
            assertTrue(a.bottom<=b.top)
        }
    }
}
