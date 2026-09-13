package com.ahwotel

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp

    @Before fun initialize() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).readBytes()
            }
        }
        app.ready.await()
        app.state.value.sessionId?.let { MonitoringService.stop(app, it) }
        while (app.state.value.sessionId != null) kotlinx.coroutines.delay(100)
        app.saveSettings(Settings(deviceId = app.settings.value.deviceId, durationSeconds = 4, intervalMs = 1000))
    }
    @After fun stop() = runBlocking {
        app.state.value.sessionId?.let { MonitoringService.stop(app, it) }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wakeUp()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("wm dismiss-keyguard")
        ).bufferedReader().use { it.readText() }
        kotlinx.coroutines.withTimeout(10_000) {
            while (app.state.value.sessionId != null) kotlinx.coroutines.delay(100)
        }
        app.saveSettings(Settings(deviceId = app.settings.value.deviceId))
    }

    @Test fun timedSessionCollectsAndAutoStopsWithVisibleHistory() {
        ui.onNodeWithText("Start monitoring").performClick()
        ui.waitUntil(10_000) { app.state.value.sessionId != null }
        val id = app.state.value.sessionId!!
        ui.waitUntil(12_000) { app.state.value.sessionId == null }
        runBlocking {
            val session = app.db.dao().session(id)!!
            assertEquals("timeout", session.endReason)
            assertTrue(app.db.dao().page(0, id, 0, Long.MAX_VALUE).size >= 2)
            assertEquals(0L, app.db.dao().outboxCount())
            val json = java.io.ByteArrayOutputStream()
            Exporter.history(app, json, ExportSpec(id, 0, Long.MAX_VALUE, true))
            val exported = org.json.JSONObject(json.toString("UTF-8")).getJSONArray("samples")
            assertTrue(exported.length() >= 2)
            assertEquals(id, exported.getJSONObject(0).getString("session.id"))
            val csv = java.io.ByteArrayOutputStream()
            Exporter.history(app, csv, ExportSpec(id, 0, Long.MAX_VALUE, false))
            assertTrue(csv.toString("UTF-8").startsWith("session.id,device.id,timestamp"))
        }
        ui.onNodeWithText("History").performClick()
        ui.onNodeWithText("Sessions").assertIsDisplayed()
        ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasContentDescription("System CPU") or hasText("System CPU"))
        ui.onNodeWithText("System CPU").assertIsDisplayed()
    }

    @Test fun languageSwitchPersistsWithoutStoppingActiveSession() {
        runBlocking { app.saveSettings(app.settings.value.copy(continuous = true)) }
        ui.onNodeWithText("Start monitoring").performClick()
        ui.waitUntil(10_000) { app.state.value.sessionId != null }
        val id = app.state.value.sessionId
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithText("Русский").performClick()
        ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText("Save settings"))
        ui.onNodeWithText("Save settings").performClick()
        ui.waitUntil(10_000) { app.settings.value.language == "ru" }
        ui.onAllNodesWithText("Настройки").onFirst().assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(app.filesDir, "acceptance-ru.png"))
        assertEquals(id, app.state.value.sessionId)
        ui.activityRule.scenario.recreate()
        ui.onAllNodesWithText("Мониторинг").onFirst().assertIsDisplayed()
        assertEquals(id, app.state.value.sessionId)
    }

    @Test fun screenOffPauseResumesWithoutRestartingSession() {
        runBlocking { app.saveSettings(app.settings.value.copy(continuous = true, collectScreenOff = false)) }
        ui.onNodeWithText("Start monitoring").performClick()
        ui.waitUntil(10_000) { app.state.value.sessionId != null }
        val id = app.state.value.sessionId
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.sleep()
        ui.waitUntil(10_000) { app.state.value.paused }
        val before = runBlocking { app.db.dao().sampleCount() }
        Thread.sleep(2200)
        assertEquals(before, runBlocking { app.db.dao().sampleCount() })
        device.wakeUp()
        ui.waitUntil(10_000) { !app.state.value.paused }
        ui.waitUntil(10_000) { runBlocking { app.db.dao().sampleCount() > before } }
        assertEquals(id, app.state.value.sessionId)
    }

    @Test fun remoteCommandContractRejectsConflictsAndIsIdempotent() {
        val component = android.content.ComponentName(app, SotiCommandReceiver::class.java)
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            app.packageManager.getComponentEnabledSetting(component))
        runBlocking { app.saveSettings(app.settings.value.copy(sotiEnabled = true)) }
        fun command(action: String, id: String): Int = runBlocking {
            val result = kotlinx.coroutines.CompletableDeferred<Int>()
            val response = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) { result.complete(resultCode) }
            }
            app.sendOrderedBroadcast(android.content.Intent(action).setComponent(component).putExtra("sessionId", id)
                .putExtra("durationSeconds", 30L).putExtra("samplingIntervalMs", 1000L), null, response, null, 0, null, null)
            kotlinx.coroutines.withTimeout(10_000) { result.await() }
        }
        val id = "test-${java.util.UUID.randomUUID()}"
        assertEquals(202, command(MonitoringService.START, id))
        ui.waitUntil(10_000) { app.state.value.sessionId == id }
        assertEquals(200, command(MonitoringService.START, id))
        assertEquals(409, command(MonitoringService.START, "conflict"))
        assertEquals(409, command(MonitoringService.STOP, "mismatch"))
        assertEquals(202, command(MonitoringService.STOP, id))
        ui.waitUntil(10_000) { app.state.value.sessionId == null }
    }

    @Test fun diagnosticsUseFileAndSystemSinksAndVerboseExpires() {
        val suffix = System.nanoTime().toString()
        val basic = "basic_probe_$suffix"
        val verbose = "verbose_probe_$suffix"
        val expired = "expired_probe_$suffix"
        val stderr = "stderr_probe_$suffix"
        runBlocking { app.saveSettings(app.settings.value.copy(diagnostic = DiagnosticLevel.BASIC)) }
        app.logs.event(basic, diagnostic = true)
        app.logs.event(stderr, error = true)
        runBlocking { app.saveSettings(app.settings.value.copy(diagnostic = DiagnosticLevel.VERBOSE,
            verboseUntil = System.currentTimeMillis() + 1500)) }
        app.logs.event(verbose, diagnostic = true, verbose = true)
        ui.waitUntil(10_000) { app.settings.value.diagnostic == DiagnosticLevel.OFF }
        app.logs.event(expired, diagnostic = true, verbose = true)
        val file = java.io.File(app.filesDir, "logs/current.jsonl").readText()
        assertTrue(file.contains(basic)); assertTrue(file.contains(verbose)); assertFalse(file.contains(expired))
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "logcat -d --pid=${android.os.Process.myPid()} -s AHWOTel:I System.out:I System.err:W")
        val systemLog = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertTrue(systemLog.contains("AHWOTel")); assertTrue(systemLog.contains("System.out"))
        assertTrue(systemLog.contains("System.err")); assertTrue(systemLog.contains(stderr))
        assertTrue(systemLog.contains(basic)); assertTrue(systemLog.contains(verbose)); assertFalse(systemLog.contains(expired))
    }
}
