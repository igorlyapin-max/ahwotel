package com.ahwotel

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionTimingAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun prepare(): Unit = runBlocking {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        profile.prepare()
        app.saveSettings(app.settings.value.copy(intervalMs = 1000, durationSeconds = 12, diagnostic = DiagnosticLevel.BASIC))
        ui.waitUntil(15000) { ui.onAllNodesWithText("Start monitoring").fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun restore(): Unit = runBlocking { device.wakeUp(); profile.restore() }
    private fun list() = ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    private fun tab(name: String) = ui.onNode(hasText(name) and hasClickAction()).performClick()
    private fun toggle(label: String) {
        list().performScrollToNode(hasText(label))
        val bounds = ui.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        ui.onNode(isToggleable() and SemanticsMatcher("switch aligned with label") {
            it.boundsInRoot.center.y in bounds.top..bounds.bottom
        }).performClick()
    }
    private fun save(label: String) {
        list().performScrollToNode(hasText(label)); ui.onNodeWithText(label).performClick()
    }
    private suspend fun start(continuous: Boolean = false, seconds: Long = 12): String {
        app.saveSettings(app.settings.value.copy(continuous = continuous, durationSeconds = seconds))
        ui.onNodeWithText("Start monitoring").performClick()
        ui.waitUntil(10000) { app.state.value.sessionId != null }
        return app.state.value.sessionId!!
    }
    private fun screenshot(name: String) {
        ui.waitForIdle(); device.waitForIdle(); Thread.sleep(350)
        assertTrue(device.takeScreenshot(File(app.filesDir, name)))
    }

    @Test fun savedContinuousModeChangesVisibleTimerAndSurvivesOldDeadline(): Unit = runBlocking {
        val id = start()
        val original = app.db.dao().session(id)!!
        ui.onNodeWithTag("session_timing").assertIsDisplayed().assertTextContains("remaining", substring = true)
        tab("Settings"); toggle("Continuous until stopped"); save("Save settings")
        ui.waitUntil(10000) { app.state.value.mode == SessionMode.CONTINUOUS }
        tab("Monitor")
        ui.onNodeWithTag("session_timing").assertIsDisplayed().assertHeightIsAtLeast(16.dp).assertTextEquals("Continuous until stopped")
        screenshot("timer-continuous-en.png")
        val changed = app.db.dao().session(id)!!
        assertTrue(changed.continuous); assertTrue(JSONObject(changed.configuration).getBoolean("continuous"))
        assertEquals(original.startedAt, changed.startedAt)
        val old = JSONObject(original.configuration).apply { remove("continuous") }.toString()
        val new = JSONObject(changed.configuration).apply { remove("continuous") }.toString()
        assertEquals(old, new)
        ui.waitUntil(18000) { System.currentTimeMillis() > original.startedAt + 13_000 }
        val count = app.db.dao().page(0, id, 0, Long.MAX_VALUE).size
        ui.waitUntil(5000) { runBlocking { app.db.dao().page(0, id, 0, Long.MAX_VALUE).size > count } }
        assertEquals(id, app.state.value.sessionId)
        assertNull(app.state.value.remainingSeconds)
    }

    @Test fun timedModeUsesOriginalStartAndRussianUiRemainsConsistent(): Unit = runBlocking {
        val id = start(true, 120)
        val original = app.db.dao().session(id)!!
        delay(2200)
        app.saveSettings(app.settings.value.copy(language = "ru"))
        ui.waitUntil(10000) { ui.onAllNodesWithText("Настройки").fetchSemanticsNodes().isNotEmpty() }
        tab("Настройки"); toggle("Непрерывно до остановки"); save("Сохранить настройки")
        ui.waitUntil(10000) { app.state.value.mode == SessionMode.TIMED }
        tab("Мониторинг")
        ui.onNodeWithTag("session_timing").assertIsDisplayed().assertTextContains("Осталось", substring = true)
        assertEquals(id, app.state.value.sessionId)
        val remaining = app.state.value.remainingSeconds!!
        val expected = 120 - (System.currentTimeMillis() - original.startedAt) / 1000
        assertTrue("remaining=$remaining expected=$expected", kotlin.math.abs(remaining - expected) <= 2)
        assertTrue(remaining < 120)
        assertFalse(app.db.dao().session(id)!!.continuous)
        screenshot("timer-timed-ru.png")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("session_timing").assertIsDisplayed().assertTextContains("Осталось", substring = true)
        assertEquals(id, app.state.value.sessionId)
    }

    @Test fun unrelatedSettingsPreserveCommandTimingAndShorterLimitExpiresImmediately(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        MonitoringService.start(app, StartRequest(id, true, 90, 1000, app.settings.value.enabled))
        ui.waitUntil(10000) { app.state.value.sessionId == id }
        app.saveSettings(app.settings.value.copy(diagnostic = DiagnosticLevel.OFF))
        assertTrue(app.db.dao().session(id)!!.continuous)
        assertEquals(90L, app.db.dao().session(id)!!.durationSeconds)
        app.saveSettings(app.settings.value.copy(durationSeconds = 1))
        assertEquals(SessionMode.CONTINUOUS, app.state.value.mode)
        delay(1500)
        // The local switch was false; change it to true before testing the true -> false transition.
        app.saveSettings(app.settings.value.copy(continuous = true))
        app.saveSettings(app.settings.value.copy(continuous = false))
        ui.waitUntil(5000) { app.state.value.sessionId == null }
        val ended = app.db.dao().session(id)!!
        assertEquals("timeout", ended.endReason); assertFalse(ended.continuous); assertEquals(1L, ended.durationSeconds)
        val count = app.db.dao().sampleCount(); delay(1500); assertEquals(count, app.db.dao().sampleCount())
        app.saveSettings(app.settings.value.copy(continuous = true))
        assertNull(app.state.value.sessionId); assertFalse(app.db.dao().session(id)!!.continuous)
    }

    @Test fun screenOffPauseDoesNotResetDuration(): Unit = runBlocking {
        app.saveSettings(app.settings.value.copy(collectScreenOff = false))
        val id = start(true)
        device.sleep()
        ui.waitUntil(6000) { app.state.value.paused }
        val count = app.db.dao().sampleCount(); delay(2200)
        assertEquals(count, app.db.dao().sampleCount())
        app.saveSettings(app.settings.value.copy(continuous = false, durationSeconds = 1))
        ui.waitUntil(5000) { app.state.value.sessionId == null }
        assertEquals("timeout", app.db.dao().session(id)!!.endReason)
        device.wakeUp()
    }

    @Test fun failedPersistenceDoesNotPublishNewTimingOrSettings(): Unit = runBlocking {
        val id = start(true, 120)
        val before = app.settings.value
        app.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_timing BEFORE UPDATE OF continuous ON sessions BEGIN SELECT RAISE(ABORT, 'timing_test_failure'); END")
        try {
            val error = runCatching { app.saveSettings(before.copy(continuous = false)) }.exceptionOrNull()
            assertEquals("session_timing_apply_failed", error?.message)
            assertEquals(before, app.settings.value)
            assertEquals(before, app.config.changes.first())
            assertEquals(SessionMode.CONTINUOUS, app.state.value.mode)
            assertTrue(app.db.dao().session(id)!!.continuous)
            assertTrue(app.resumeGuard.blocked.value)
            assertTrue(app.config.resumeBlocked.first())
        } finally { app.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_timing") }
    }

    @Test fun shorterTimerAlsoStopsOemAndRejectsLateMeasurements(): Unit = runBlocking {
        app.saveSettings(app.settings.value.copy(oem = com.ahwotel.oem.OemSettings(enabled = true)))
        val before = app.db.oemDao().count()
        val id = start(true, 120)
        ui.waitUntil(20000) { runBlocking { app.db.oemDao().count() > before } }
        delay(1500)
        app.oem.generation.incrementAndGet()
        app.saveSettings(app.settings.value.copy(continuous = false, durationSeconds = 1))
        ui.waitUntil(6000) { app.state.value.sessionId == null }
        assertEquals("timeout", app.db.dao().session(id)!!.endReason)
        val oemCount = app.db.oemDao().count()
        val sampleCount = app.db.dao().sampleCount()
        delay(3500)
        assertEquals(oemCount, app.db.oemDao().count())
        assertEquals(sampleCount, app.db.dao().sampleCount())
    }

    @Test fun concurrentStopAndSaveCannotReviveOrMutateTheNextSession(): Unit = runBlocking {
        repeat(3) {
            val id = start(true, 120)
            coroutineScope {
                val save = async(Dispatchers.IO) { app.saveSettings(app.settings.value.copy(continuous = false)) }
                MonitoringService.stop(app, id)
                save.await()
            }
            ui.waitUntil(6000) { app.state.value.sessionId == null }
            assertEquals("FINISHED", app.db.dao().session(id)!!.status)
            assertEquals("manual_stop", app.db.dao().session(id)!!.endReason)
        }
        val next = start(true, 90)
        val before = app.db.dao().session(next)!!
        app.saveSettings(app.settings.value.copy(fileLogging = !app.settings.value.fileLogging))
        assertEquals(before, app.db.dao().session(next))
        assertEquals(SessionMode.CONTINUOUS, app.state.value.mode)
    }
}
