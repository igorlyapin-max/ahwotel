package com.ahwotel

import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.ahwotel.oem.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class OemAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun prepare() = runBlocking {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        profile.prepare()
        ui.waitUntil(15000) { ui.onAllNodesWithText("Start monitoring").fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        Unit
    }
    @After fun restore() = runBlocking { device.wakeUp(); app.managed.replace(android.os.Bundle()); profile.restore() }
    private fun list() = ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    private fun screenshot(name: String) {
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        device.waitForIdle()
        // Platform Dialog fade-in is outside Compose's test clock.
        Thread.sleep(350)
        assertTrue(device.takeScreenshot(File(app.filesDir, name)))
    }
    private fun openOem() {
        ui.onNode(hasText("Diagnostics") and hasClickAction()).performClick()
        ui.onNodeWithText("Extended telemetry / OEM").performClick()
        ui.onNodeWithTag("oem_screen").assertIsDisplayed()
    }
    @Test fun managedOverlayStopsSessionAndLocksItsUiFields(): Unit = runBlocking {
        app.saveSettings(app.settings.value.copy(continuous = true))
        val before = app.db.oemDao().count()
        ui.onNodeWithText("Start monitoring").performClick()
        ui.waitUntil(10000) { app.state.value.sessionId != null }
        delay(1800)
        assertEquals("OEM disabled by default must not record measurements", before, app.db.oemDao().count())
        assertTrue(app.managed.replace(android.os.Bundle().apply {
            putBoolean("monitoring_enabled", false); putBoolean("oem_telemetry_enabled", true); putString("config_version", "acceptance")
        }))
        app.saveSettings(app.settings.value)
        ui.waitUntil(6000) { app.state.value.sessionId == null }
        ui.onNodeWithText("Start monitoring").assertIsNotEnabled()
        ui.onNode(hasText("Settings") and hasClickAction()).performClick()
        list().performScrollToNode(hasText("Collect extended telemetry"))
        val labelBounds = ui.onNodeWithText("Collect extended telemetry").fetchSemanticsNode().boundsInRoot
        // Rows without their own semantics are flattened into the panel; align with this label.
        ui.onNode(isToggleable() and SemanticsMatcher("aligned with extended telemetry label") {
            it.boundsInRoot.center.y in labelBounds.top..labelBounds.bottom
        }).assertIsDisplayed().assertIsNotEnabled()
    }
    @Test fun realProviderDiscoveryRunsInsideApkAndDoesNotCreateHistory() = runBlocking {
        val before = app.db.oemDao().count()
        app.saveSettings(app.settings.value.copy(oem = OemSettings(enabled = true), diagnostic = DiagnosticLevel.BASIC))
        openOem()
        ui.onNodeWithText("Check availability").performClick()
        ui.waitUntil(40000) { app.oem.latest.value.values.any { it.metric == OemMetric.VPN_POLICY } && !app.oem.checking.value }
        assertEquals(before, app.db.oemDao().count()); assertNull(app.state.value.sessionId)
        val standard = app.oem.latest.value.values.filter { it.provider == "android_standard" }
        assertTrue(standard.any { it.metric == OemMetric.MODEL && it.status == OemStatus.AVAILABLE })
        val knox = app.oem.latest.value.values.filter { it.provider == "samsung_knox" }
        assertTrue(knox.any { it.metric == OemMetric.KNOX_API })
        assertTrue(knox.none { it.metric == OemMetric.OEM_SYSTEM_CPU && it.number != null })
        assertTrue(app.oem.latest.value.values.filter { it.status != OemStatus.AVAILABLE }.all { it.number == null && it.text == null })
        File(app.filesDir, "oem-capabilities.json").writeText(JSONObject().put("package", app.packageName)
            .put("profiles", JSONArray(app.oem.profiles.value.values.map { JSONObject(OemCoordinator.encodeProfile(it)) }))
            .put("knox", JSONArray(knox.map { JSONObject().put("metric", it.metric.name).put("status", it.status.name)
                .put("reason", it.reason.name).put("number", it.number ?: JSONObject.NULL).put("text", it.text ?: JSONObject.NULL) })).toString(2))
        assertTrue(File(app.filesDir, "logs/current.jsonl").readText().contains("oem_probe_result"))
        screenshot("oem-discovery.png")
    }
    @Test fun sessionStoresExportsPausesAndStopsWithoutLosingNormalSampling() = runBlocking {
        app.saveSettings(app.settings.value.copy(continuous = true, intervalMs = 1000, collectScreenOff = false,
            oem = OemSettings(enabled = true, profile = "custom", intervals = OemCategory.entries.associateWith {
                if (it in setOf(OemCategory.DEVICE, OemCategory.INVENTORY)) 3600 else 15 })))
        ui.onNodeWithText("Start monitoring").performClick()
        ui.waitUntil(10000) { app.state.value.sessionId != null }
        val id = app.state.value.sessionId!!
        ui.waitUntil(40000) { runBlocking { app.db.oemDao().page(0, 0, Long.MAX_VALUE, id).any { it.metric == OemMetric.VPN_POLICY.name } } }
        ui.waitUntil(25000) { app.oem.latest.value.values.any { it.metric == OemMetric.CPU_ESTIMATE && it.status == OemStatus.AVAILABLE } }
        val normal = app.db.dao().page(0, id, 0, Long.MAX_VALUE)
        assertTrue(normal.size >= 3)
        device.sleep()
        ui.waitUntil(5000) { app.state.value.paused }
        delay(1500)
        val pausedCount = app.db.oemDao().count()
        app.oem.generation.incrementAndGet()
        delay(3500)
        assertEquals(pausedCount, app.db.oemDao().count())
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        val resumeAt = System.currentTimeMillis()
        app.oem.generation.incrementAndGet()
        ui.waitUntil(20000) { app.oem.latest.value.values.any { it.metric == OemMetric.CPU_DELTA && it.time >= resumeAt && it.reason == OemReason.BASELINE } }
        MonitoringService.stop(app, id)
        ui.waitUntil(6000) { app.state.value.sessionId == null }
        val stoppedCount = app.db.oemDao().count(); delay(1500); assertEquals(stoppedCount, app.db.oemDao().count())
        val json = ByteArrayOutputStream()
        OemExporter.history(app, json, ExportSpec(id, 0, Long.MAX_VALUE, true))
        val export = JSONObject(json.toString("UTF-8"))
        assertTrue(export.getJSONArray("observations").length() > 0)
        assertTrue(export.getJSONArray("inventory").length() > 0)
        assertFalse(json.toString("UTF-8").contains("SSID"))
        for (i in 0 until export.getJSONArray("observations").length()) assertEquals(id, export.getJSONArray("observations").getJSONObject(i).getString("session.id"))
        val csv = ByteArrayOutputStream(); OemExporter.history(app, csv, ExportSpec(id, 0, Long.MAX_VALUE, false))
        assertTrue(csv.toString("UTF-8").contains("agent.memory.pss")); assertFalse(csv.toString("UTF-8").contains("device.model"))
        File(app.filesDir, "oem-export.json").writeBytes(json.toByteArray())
        File(app.filesDir, "oem-export.csv").writeBytes(csv.toByteArray())
    }
    @Test fun helpIsLocalizedScrollableAndRetainedAcrossRecreationWithoutCollection() = runBlocking {
        openOem()
        list().performScrollToNode(hasText("About Manufacturer"))
        ui.onNodeWithText("About Manufacturer").performClick()
        ui.onNodeWithTag("oem_help_body").assertIsDisplayed()
        ui.onNodeWithText("Close help").assertHeightIsAtLeast(48.dp)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("oem_help_body").assertIsDisplayed()
        ui.onNodeWithText("Close help").performClick()
        app.saveSettings(app.settings.value.copy(language = "ru"))
        ui.waitUntil(15000) { ui.onAllNodesWithText("О показателе: Производитель").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("О показателе: Производитель").performClick()
        ui.onNodeWithText("Build.MANUFACTURER указывает производителя устройства.").assertIsDisplayed()
        ui.onNodeWithText("Закрыть справку").assertIsDisplayed()
        screenshot("oem-help-ru.png")
        ui.onNodeWithTag("oem_help_body").performTouchInput { swipeUp() }
        ui.onNodeWithText("Закрыть справку").performClick()
        assertNull(app.state.value.sessionId)
    }
    @Test fun graphDisplaysTimeAndValueAxesAndAccessibleSelectedReading() = runBlocking {
        val now = System.currentTimeMillis()
        val id = "oem-chart-${now}"
        app.db.dao().start(SessionRow(id, app.settings.value.deviceId, now - 60_000, reason = "chart fixture", configuration = "{}", continuous = false, durationSeconds = 60))
        repeat(30) { i -> app.db.dao().sample(SampleRow(sessionId = id, time = now - 60_000 + i * 2000, elapsed = i * 2000L, segment = 0,
            screenOn = true, cpuWait = 10.0 + i * 2, probeTime = now - 60_000 + i * 2000, probeInterval = 2000, probeSegment = 0)) }
        app.db.dao().finish(id, now, "fixture")
        ui.onNode(hasText("History") and hasClickAction()).performClick()
        ui.onNodeWithText("All sessions").performClick(); ui.onNodeWithText(id).performClick()
        list().performScrollToNode(hasContentDescription("CPU wait"))
        val plot = ui.onNodeWithContentDescription("CPU wait")
        plot.assertIsDisplayed().assertWidthIsAtLeast(200.dp).assertHeightIsAtLeast(180.dp)
        val description = plot.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(description.contains("ms")); assertTrue(description.contains("0, 20, 40, 60, 80")); assertTrue(description.contains("Time"))
        val pixels = plot.captureToImage().toPixelMap()
        var leftInk = 0; var bottomInk = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x,y]
            if (c.red > 0.75 && c.green > 0.75 && c.blue > 0.75) {
                if (x < pixels.width / 6 && y < pixels.height * 3 / 4) leftInk++
                if (y > pixels.height * 84 / 100) bottomInk++
            }
        }
        assertTrue("Y labels must draw", leftInk > 20)
        assertTrue("X labels must draw", bottomInk > 20)
        val action = plot.fetchSemanticsNode().config[SemanticsActions.CustomActions].last()
        ui.runOnIdle { assertTrue(action.action()) }
        ui.waitForIdle()
        assertTrue(plot.fetchSemanticsNode().config[SemanticsProperties.StateDescription].length > description.length)
        screenshot("axes-en.png")
    }
}
