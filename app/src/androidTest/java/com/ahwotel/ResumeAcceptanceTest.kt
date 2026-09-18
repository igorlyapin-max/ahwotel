package com.ahwotel

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class ResumeAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    @Before fun prepare(): Unit = runBlocking {
        profile.prepare()
        app.db.resumeDao().put(ResumeRow())
        app.saveSettings(app.settings.value.copy(continuous = true, resumeOnBoot = true, resumeOnOpen = true,
            enabled = setOf(CollectorKind.MEMORY), batterySettings = BatterySettings(enabled = false),
            selfTelemetry = SelfTelemetrySettings(enabled = false)))
    }
    @After fun restore(): Unit = runBlocking { profile.restore(); app.db.resumeDao().put(ResumeRow()) }
    private suspend fun started(): String {
        withTimeout(10000) { while (app.state.value.sessionId == null) delay(50) }
        return app.state.value.sessionId!!
    }
    private suspend fun stopped() { withTimeout(10000) { while (app.state.value.sessionId != null) delay(50) } }
    @Test fun openingAndBootDoNotStartUntilExplicitStartAndStopPersists(): Unit = runBlocking {
        app.requestResume(ResumeTrigger.OPEN); app.requestResume(ResumeTrigger.BOOT)
        delay(500); assertNull(app.state.value.sessionId)
        ui.onNodeWithText("Start monitoring").performClick()
        val id = started(); assertTrue(app.db.resumeDao().get()!!.armed)
        MonitoringService.stop(app, id); stopped()
        for (trigger in ResumeTrigger.entries) app.requestResume(trigger)
        delay(500); assertNull(app.state.value.sessionId); assertFalse(app.db.resumeDao().get()!!.armed)
    }
    @Test fun interruptedServiceRestartsExactlyOnceWithNewSession(): Unit = runBlocking {
        ui.onNodeWithText("Start monitoring").performClick(); val old = started()
        withTimeout(10000) { while(app.db.dao().sampleCount() == 0L) delay(50) }
        app.stopService(android.content.Intent(app, MonitoringService::class.java)); stopped()
        assertEquals("INTERRUPTED", app.db.dao().session(old)!!.status)
        assertTrue(app.db.resumeDao().get()!!.armed)
        coroutineScope { ResumeTrigger.entries.forEach { trigger -> launch { app.requestResume(trigger) } } }
        val fresh = started(); assertNotEquals(old, fresh)
        delay(1500); assertEquals(fresh, app.state.value.sessionId)
        app.db.openHelper.readableDatabase.query("SELECT count(*) FROM sessions WHERE status='RUNNING'").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
    }
    @Test fun administrativeDisableAndTimedModeDisarm(): Unit = runBlocking {
        ui.onNodeWithText("Start monitoring").performClick(); started()
        app.saveSettings(app.settings.value.copy(monitoringEnabled = false)); stopped()
        app.saveSettings(app.settings.value.copy(monitoringEnabled = true))
        app.requestResume(ResumeTrigger.BOOT); delay(300); assertNull(app.state.value.sessionId)
        MonitoringService.start(app, StartRequest(UUID.randomUUID().toString(), true, 300, 2000, setOf(CollectorKind.MEMORY), "test"))
        started()
        app.saveSettings(app.settings.value.copy(continuous = false))
        assertFalse(app.db.resumeDao().get()!!.armed)
        app.stopService(android.content.Intent(app, MonitoringService::class.java)); stopped()
        app.requestResume(ResumeTrigger.OPEN); delay(300); assertNull(app.state.value.sessionId)
    }
    @Test fun invalidRecoveryReportsFailureWithoutInventingASession(): Unit = runBlocking {
        app.db.resumeDao().put(ResumeRow(armed = true, configuration = "broken"))
        app.resumeGuard.allow()
        app.requestResume(ResumeTrigger.OPEN)
        withTimeout(10000) { while (app.db.resumeDao().get()?.result != "start_failed") delay(50) }
        assertNull(app.state.value.sessionId)
    }
}
