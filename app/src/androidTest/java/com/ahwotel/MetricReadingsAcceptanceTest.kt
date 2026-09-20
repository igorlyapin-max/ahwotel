package com.ahwotel

import androidx.compose.material3.*
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
    @Test fun readingLifecycleAndStatusesFollowSelectedLanguage() {
        var language by mutableStateOf("en")
        ui.setContent {
            val base=LocalContext.current
            val config=Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val localized=base.createConfigurationContext(config)
            CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides config) {
                MaterialTheme { MetricHelpHost(app) { MonitorMetricCard(Metric.TEMPERATURE,MetricReading(28.6,1000,"AVAILABLE","android_api"),null,Settings(),RuntimeState(),200000) } }
            }
        }
        ui.onNodeWithText("Session ended · last recorded measurement").assertIsDisplayed()
        ui.runOnUiThread { language="ru" }
        ui.onNodeWithText("Сессия завершена · последний сохранённый замер").assertIsDisplayed()
        ui.onNodeWithTag("metric_value_TEMPERATURE").assertTextEquals(formatValue(28.6,Metric.TEMPERATURE))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.filesDir,"metric-localized-ru.png"))
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
        show("metric_value_BATTERY"); ui.onNodeWithTag("metric_value_BATTERY").assertTextEquals("68 %")
        show("metric_value_TEMPERATURE"); ui.onNodeWithTag("metric_value_TEMPERATURE").assertTextEquals(formatValue(28.6,Metric.TEMPERATURE))
        show("metric_value_HEADROOM"); ui.onNodeWithTag("metric_value_HEADROOM").assertTextEquals(formatValue(.4,Metric.HEADROOM))
        ui.onNodeWithTag("metric_time_HEADROOM").assertTextEquals(formatTime(now-10000))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.filesDir,"metric-headroom.png"))
        app.state.value=RuntimeState()
        show("metric_value_TEMPERATURE"); ui.onNodeWithTag("metric_value_TEMPERATURE").assertTextEquals(formatValue(28.6,Metric.TEMPERATURE))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.filesDir,"metric-battery.png"))
        app.state.value=RuntimeState(sessionId="new-session")
        ui.waitForIdle()
        show("metric_value_BATTERY"); ui.onNodeWithTag("metric_value_BATTERY").assertTextEquals("—")
        show("metric_value_HEADROOM"); ui.onNodeWithTag("metric_value_HEADROOM").assertTextEquals("—")
    }
}
