package com.ahwotel

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import java.util.UUID

class DeploymentProfileAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp
    private val isolated by lazy { IsolatedTestProfile(app) }
    private var profileId = ""

    @Before fun prepare() = runBlocking {
        isolated.prepare()
        profileId = "acceptance-${UUID.randomUUID()}"
    }

    @After fun restore() = runBlocking {
        app.state.value.sessionId?.let { MonitoringService.stop(app, it, ActionOrigin.POLICY) }
        withTimeout(10000) { while (app.state.value.sessionId != null) delay(50) }
        if (app.managed.active.value) {
            app.applyManagedConfiguration(Bundle().apply {
                putString(ManagedConfiguration.ACTION_KEY, "release")
                putString(ManagedConfiguration.PROFILE_ID_KEY, app.managed.profileId.value)
                putInt(ManagedConfiguration.REVISION_KEY, (app.managed.revision.value + 1).toInt())
            })
        }
        isolated.restore()
    }

    @Test fun managedProfileLocksEveryUserEntryPointAndReleaseRestoresLocalControl() = runBlocking {
        val deviceId = app.settings.value.deviceId
        val policySettings = app.settings.value.copy(continuous = true, retentionDays = 23, sotiEnabled = true,
            otlpEnabled = true, endpoint = "https://collector-b.example/v1/metrics")
        app.db.dao().enqueue(OutboxRow(createdAt=System.currentTimeMillis(),endpoint="https://collector-a.example/v1/metrics",payload=byteArrayOf(1)))
        val initial = DeploymentProfileCodec.encode(policySettings, profileId, 1, DesiredCollectionState.STOPPED)
        assertTrue(app.applyManagedConfiguration(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "apply")
            putString(ManagedConfiguration.PROFILE_KEY, initial)
        }))
        assertEquals(0,app.db.dao().outboxCount())
        val raw = DeploymentProfileCodec.encode(policySettings, profileId, 2, DesiredCollectionState.RUNNING)
        assertTrue(app.applyManagedConfiguration(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "apply")
            putString(ManagedConfiguration.PROFILE_KEY, raw)
        }))
        assertTrue(app.managed.active.value)
        assertTrue(app.managed.isCurrentProfileApplied())
        assertEquals(deviceId, app.settings.value.deviceId)
        assertEquals(23, app.settings.value.retentionDays)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { app.saveSettings(app.settings.value.copy(retentionDays = 24)) }
        }

        withTimeout(10000) { while (app.state.value.sessionId == null) delay(50) }
        val managedSession = requireNotNull(app.state.value.sessionId)
        MonitoringService.start(app, StartRequest("user-start", true, 300, 2000, setOf(CollectorKind.MEMORY)))
        delay(800)
        assertEquals(managedSession, app.state.value.sessionId)
        MonitoringService.stop(app, managedSession)
        delay(500)
        assertEquals(managedSession, app.state.value.sessionId)

        val stopped = DeploymentProfileCodec.encode(policySettings, profileId, 3, DesiredCollectionState.STOPPED)
        assertTrue(app.applyManagedConfiguration(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "apply")
            putString(ManagedConfiguration.PROFILE_KEY, stopped)
        }))
        withTimeout(10000) { while (app.state.value.sessionId != null) delay(50) }
        assertTrue(app.managed.isCurrentProfileApplied())
        assertTrue(app.applyManagedConfiguration(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "apply")
            putString(ManagedConfiguration.PROFILE_KEY, JSONObject(stopped).toString())
        }))
        assertNull(app.state.value.sessionId)
        for(trigger in ResumeTrigger.entries) {
            app.db.resumeDao().put(ResumeRow(armed=true,configuration=SettingsCodec.encode(policySettings)))
            app.resumeGuard.allow()
            MonitoringService.resume(app,trigger)
            delay(500)
            assertNull(app.state.value.sessionId)
            assertFalse(app.db.resumeDao().get()!!.armed)
            assertEquals("managed_stopped",app.db.resumeDao().get()!!.result)
        }

        val statusBar = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd statusbar collapse")
        ParcelFileDescriptor.AutoCloseInputStream(statusBar).use { it.readBytes() }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15000) {
            ui.onAllNodes(hasText("Settings") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNode(hasText("Settings") and hasClickAction()).performClick()
        ui.onNodeWithText("Managed mode · profile $profileId · revision 3").assertIsDisplayed()
        ui.onNodeWithText("English").assertIsEnabled()
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("Save settings"))
        ui.onNodeWithText("Save settings").assertIsNotEnabled()

        assertTrue(app.applyManagedConfiguration(Bundle()))
        assertTrue(app.managed.active.value)
        assertTrue(app.applyManagedConfiguration(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "release")
            putString(ManagedConfiguration.PROFILE_ID_KEY, profileId)
            putInt(ManagedConfiguration.REVISION_KEY, 4)
        }))
        assertFalse(app.managed.active.value)
        app.saveSettings(app.settings.value.copy(retentionDays = 24))
        assertEquals(24, app.settings.value.retentionDays)
    }
}
