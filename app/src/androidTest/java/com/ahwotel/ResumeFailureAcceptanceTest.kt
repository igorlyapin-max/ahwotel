package com.ahwotel

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.util.UUID

/** Failure injection is confined to the acceptance package by SafeTestRunner/IsolatedTestProfile. */
class ResumeFailureAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    private lateinit var savedStore: SettingsStore
    @Before fun prepare(): Unit = runBlocking {
        profile.prepare(); savedStore = app.config
        app.saveSettings(app.settings.value.copy(continuous = true, resumeOnBoot = true, resumeOnOpen = true,
            enabled = setOf(CollectorKind.MEMORY), batterySettings = BatterySettings(enabled = false),
            selfTelemetry = SelfTelemetrySettings(enabled = false)))
    }
    @After fun restore(): Unit = runBlocking {
        if (::savedStore.isInitialized) app.config = savedStore
        app.db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_resume_write")
        profile.restore()
        app.mutex.withLock { app.persistResumeStopLocked("manual_stop") }
    }
    private suspend fun start(continuous: Boolean = true): String {
        val id = UUID.randomUUID().toString()
        MonitoringService.start(app, StartRequest(id, continuous, 300, 2000, setOf(CollectorKind.MEMORY), "test"))
        withTimeout(15000) { while (app.state.value.sessionId != id) delay(50) }
        return id
    }
    private suspend fun stopped() { withTimeout(15000) { while (app.state.value.sessionId != null) delay(50) } }
    private fun rejectRoom() { app.db.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER reject_resume_write BEFORE INSERT ON resume_state BEGIN SELECT RAISE(ABORT, 'resume_test_failure'); END") }
    private suspend fun assertNoMoreSamplesOrRecovery() {
        val count = app.db.dao().sampleCount(); delay(2500)
        assertEquals(count, app.db.dao().sampleCount())
        for (trigger in ResumeTrigger.entries) app.requestResume(trigger)
        delay(500); assertNull(app.state.value.sessionId)
    }
    @Test fun stopCancelsCollectionEvenWhenResumeRowCannotBeWritten(): Unit = runBlocking {
        val id = start(); rejectRoom()
        MonitoringService.stop(app, id); stopped()
        assertTrue(app.resumeGuard.blocked.value); assertTrue(savedStore.resumeBlocked.first())
        assertTrue(app.db.resumeDao().get()!!.armed) // Failed Room update is real, not a no-op fixture.
        assertEquals("resume_state_write_failed", app.resumeGuard.issue.value)
        ui.onNodeWithText(ui.activity.getString(R.string.resume_state_write_failed)).assertExists()
        app.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_resume_write")
        assertNoMoreSamplesOrRecovery()
    }
    @Test fun administrativeProhibitionAppliesBeforeFailingPersistence(): Unit = runBlocking {
        start(); rejectRoom()
        app.mutex.withLock { app.publishSettings(app.settings.value.copy(monitoringEnabled = false)) }
        assertFalse(app.settings.value.monitoringEnabled); stopped()
        assertTrue(app.resumeGuard.blocked.value); assertTrue(savedStore.resumeBlocked.first())
        app.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_resume_write")
        app.mutex.withLock { app.publishSettings(app.settings.value.copy(monitoringEnabled = true)) }
        assertNoMoreSamplesOrRecovery()
    }
    @Test fun bothStoresFailStopStillWorksAndDurabilityWarningIsVisible(): Unit = runBlocking {
        val id = start(); rejectRoom()
        app.config = SettingsStore(object : DataStore<Preferences> {
            override val data = savedStore.store.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("injected")
        })
        MonitoringService.stop(app, id); stopped()
        assertTrue(app.resumeGuard.blocked.value)
        assertFalse(savedStore.resumeBlocked.first()); assertTrue(app.db.resumeDao().get()!!.armed)
        assertEquals("resume_guard_write_failed", app.resumeGuard.issue.value)
        ui.onNodeWithText(ui.activity.getString(R.string.resume_guard_write_failed)).assertExists()
        app.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_resume_write")
        assertNoMoreSamplesOrRecovery()
        app.config = savedStore
        val next = start(); MonitoringService.stop(app, next); stopped()
        assertTrue(savedStore.resumeBlocked.first())
    }
    @Test fun explicitContinuousCommandResumesDespiteTimedDefaults(): Unit = runBlocking {
        app.saveSettings(app.settings.value.copy(continuous = false))
        val old = start(true)
        assertFalse(app.settings.value.continuous)
        app.stopService(android.content.Intent(app, MonitoringService::class.java)); stopped()
        app.requestResume(ResumeTrigger.OPEN)
        withTimeout(15000) { while (app.state.value.sessionId == null) delay(50) }
        assertNotEquals(old, app.state.value.sessionId)
        assertEquals(SessionMode.CONTINUOUS, app.state.value.mode)
        assertFalse(app.resumeGuard.blocked.value)
    }
}
