package com.ahwotel

import androidx.compose.ui.semantics.SemanticsActions
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

@RunWith(AndroidJUnit4::class)
class CpuAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp

    @Before fun initialize() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        UiDevice.getInstance(instrumentation).wakeUp()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard"))
            .bufferedReader().use { it.readText() }
        ui.activity.runOnUiThread { ui.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        app.ready.await()
        app.state.value.sessionId?.let { MonitoringService.stop(app, it) }
        withTimeout(10000) { while (app.state.value.sessionId != null) delay(100) }
        app.saveSettings(Settings(deviceId = app.settings.value.deviceId, intervalMs = 1000, durationSeconds = 8))
    }
    @After fun restore() = runBlocking {
        app.state.value.sessionId?.let { MonitoringService.stop(app, it) }
        withTimeout(10000) { while (app.state.value.sessionId != null) delay(100) }
        app.saveSettings(Settings(deviceId = app.settings.value.deviceId))
    }
    @Test fun availabilityIsCheckedInsideApkWithoutCreatingHistory() {
        val before = runBlocking { app.db.dao().sampleCount() }
        ui.onNodeWithText("Diagnostics").performClick()
        ui.onNodeWithText("Check availability").performClick()
        ui.waitUntil(15000) { app.sources.reports.value[Metric.PROBE_DELAY]?.status == Availability.AVAILABLE && !app.checkingSources.value }
        assertEquals(before, runBlocking { app.db.dao().sampleCount() })
        assertNull(app.state.value.sessionId)
        val reports = app.sources.reports.value
        assertTrue(reports[Metric.CPU]!!.status in setOf(Availability.AVAILABLE, Availability.UNSUPPORTED))
        assertTrue(reports[Metric.CPU_WAIT]!!.status in setOf(Availability.AVAILABLE, Availability.UNSUPPORTED))
        File(app.filesDir, "cpu-source-check.json").writeText(app.sources.json())
        val log = File(app.filesDir, "logs/current.jsonl").readText()
        assertTrue(log.contains("source_probe_result"))
        if (reports[Metric.CPU]!!.status == Availability.UNSUPPORTED) assertTrue(log.contains("fallback_selected"))
    }
    @Test fun probeUsesStableOwnTidAndStopsPublishingWhilePaused() {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        CpuProbe(300, ProcReader { path -> paths.add(path); AndroidProcReader.read(path) }).use { probe ->
            ui.waitUntil(10000) { (probe.snapshot()?.sequence ?: 0) >= 3 }
            val first = probe.take()!!
            assertNotEquals(android.os.Process.myTid(), first.tid)
            assertEquals(setOf("/proc/self/task/${first.tid}/schedstat"), paths.toSet())
            assertNull(probe.take())
            probe.setPaused(true)
            ui.waitUntil(5000) { probe.snapshot() == null }
            Thread.sleep(700)
            assertNull(probe.snapshot())
            probe.setPaused(false)
            ui.waitUntil(5000) { probe.snapshot() != null }
            assertTrue(probe.snapshot()!!.segment > first.segment)
        }
    }
    @Test fun newSignalsHaveVisibleChartsAndTheirOwnExportTime() {
        ui.onNodeWithText("Start monitoring").performClick()
        ui.waitUntil(10000) { app.state.value.sessionId != null }
        val id = app.state.value.sessionId!!
        ui.waitUntil(10000) { app.sources.reports.value[Metric.PROBE_DELAY]?.status == Availability.AVAILABLE }
        runBlocking { app.checkSources() }
        assertEquals(id, app.state.value.sessionId)
        ui.waitUntil(15000) { app.state.value.sessionId == null }
        val rows = runBlocking { app.db.dao().page(0, id, 0, Long.MAX_VALUE) }
        assertTrue(rows.any { it.probeDelay != null && it.probeTime != null && it.probeInterval != null })
        assertTrue(rows.any { (it.probeSegment ?: 0) > 0 })
        val cpuAvailable = rows.any { it.cpuWait != null }
        if (!rows.any { it.cpu != null }) assertTrue(rows.all { it.cpuPressure == null })
        val output = java.io.ByteArrayOutputStream()
        runBlocking { Exporter.history(app, output, ExportSpec(id, 0, Long.MAX_VALUE, true)) }
        val exported = org.json.JSONObject(output.toString("UTF-8"))
        assertEquals(2, exported.getInt("version"))
        assertTrue(output.toString("UTF-8").contains("probe.timestamp"))
        assertTrue(output.toString("UTF-8").contains("agent.scheduling.delay"))
        ui.onNodeWithText("History").performClick()
        ui.onNodeWithText("All sessions").performClick()
        ui.onNodeWithText(id).performClick()
        val list = ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        if (cpuAvailable) {
            list.performScrollToNode(hasContentDescription("CPU wait"))
            ui.onNodeWithContentDescription("CPU wait").assertIsDisplayed().performClick()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.filesDir, "cpu-wait-chart.png"))
        }
        list.performScrollToNode(hasContentDescription("Probe scheduling delay"))
        ui.onNodeWithContentDescription("Probe scheduling delay").assertIsDisplayed().performClick()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.filesDir, "probe-delay-chart.png"))
    }
    @Test fun disablingIndirectSignalsOrCpuSuppressesProbeWithoutStoppingOtherCollectors() = runBlocking {
        for (s in listOf(app.settings.value.copy(indirectCpu = false), app.settings.value.copy(enabled = setOf(CollectorKind.MEMORY)))) {
            app.saveSettings(s)
            app.checkSources()
            assertEquals(Availability.DISABLED, app.sources.reports.value[Metric.CPU_WAIT]!!.status)
            assertEquals(Availability.DISABLED, app.sources.reports.value[Metric.PROBE_DELAY]!!.status)
            assertEquals(Availability.AVAILABLE, app.sources.reports.value[Metric.MEMORY_AVAILABLE]!!.status)
        }
    }
}
