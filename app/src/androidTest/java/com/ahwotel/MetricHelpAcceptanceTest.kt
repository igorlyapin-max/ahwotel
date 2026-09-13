package com.ahwotel

import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MetricHelpAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun prepare() = runBlocking {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        profile.prepare()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Start monitoring").fetchSemanticsNodes().isNotEmpty() }
        keepAwake()
    }
    @After fun restore() = runBlocking {
        profile.restore()
    }
    private fun keepAwake() { ui.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    private fun text(id: Int, language: String = "en"): String = ui.activity.createConfigurationContext(
        Configuration(ui.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }).getString(id)
    private fun mainList() = ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    private fun openCard(title: String) {
        val label = "About $title"
        mainList().performScrollToNode(hasContentDescription(label))
        ui.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithTag("help-title").assertTextEquals(title)
        ui.onNodeWithContentDescription("Close help").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
    }
    private fun close(language: String = "en") { ui.onNodeWithContentDescription(text(R.string.help_close, language)).performClick() }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        device.waitForIdle()
        // A clickable semantics node can still contain an invisible, clipped text glyph.
        // Verify that the dark header's close button actually draws its light affordance.
        val pixels = ui.onNodeWithContentDescription(text(R.string.help_close, app.settings.value.language))
            .captureToImage().toPixelMap()
        var visiblePixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.alpha > 0.8f && color.red > 0.7f && color.green > 0.7f && color.blue > 0.7f) visiblePixels++
        }
        assertTrue("The close button must draw a visible icon", visiblePixels >= 16)
        assertTrue(device.takeScreenshot(File(app.filesDir, "help-$name.png")))
    }
    private fun tab(id: Int, language: String = "en") { ui.onNode(hasText(text(id, language)) and hasClickAction()).performClick() }
    private fun guide() { tab(R.string.diagnostics); ui.onNodeWithText("Metric guide").performClick() }
    private fun select(metric: Metric) {
        ui.onNodeWithTag("help-list").performScrollToNode(hasTestTag("help-entry-${metric.name}"))
        ui.onNodeWithTag("help-entry-${metric.name}").performClick()
    }

    @Test fun guideCoversAllMetricsWithoutStartingCollection() {
        val before = runBlocking { app.db.dao().sampleCount() }
        val reports = app.sources.json()
        guide()
        for (metric in Metric.entries) {
            ui.onNodeWithTag("help-list").performScrollToNode(hasTestTag("help-entry-${metric.name}"))
            ui.onNodeWithTag("help-entry-${metric.name}").assertIsDisplayed()
        }
        select(Metric.HEADROOM)
        ui.onNodeWithText(text(R.string.help_headroom_meaning)).assertIsDisplayed()
        screenshot("headroom-en")
        ui.onNodeWithTag("help-content").performScrollToNode(hasText(text(R.string.help_headroom_source)))
        ui.onNodeWithText(text(R.string.help_headroom_source)).assertIsDisplayed()
        ui.onNodeWithContentDescription("Back to parameters").performClick()
        ui.onNodeWithTag("help-list").assertIsDisplayed()
        device.pressBack()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("metric-help-sheet").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("metric-help-sheet").assertDoesNotExist()
        assertNull(app.state.value.sessionId)
        assertEquals(before, runBlocking { app.db.dao().sampleCount() })
        assertEquals(reports, app.sources.json())
    }

    @Test fun cardsHistoryAndDisabledCollectorOpenTheSameReference() = runBlocking {
        app.saveSettings(app.settings.value.copy(enabled = setOf(CollectorKind.MEMORY), indirectCpu = false))
        openCard("CPU wait")
        ui.onNodeWithText(text(R.string.help_cpu_wait_meaning)).assertIsDisplayed()
        screenshot("cpu-wait-en")
        close()
        ui.onNodeWithContentDescription("About CPU wait").assertIsDisplayed()
        tab(R.string.history)
        openCard("CPU wait")
        ui.onNodeWithText(text(R.string.help_history_reference)).assertIsDisplayed()
        ui.onNodeWithText("Last source check:", substring = true).assertDoesNotExist()
        ui.onNodeWithTag("help-content").performScrollToNode(hasText(text(R.string.help_chart_probe)))
        ui.onNodeWithText(text(R.string.help_chart_probe)).assertIsDisplayed()
        close()
        tab(R.string.settings)
        mainList().performScrollToNode(hasContentDescription("Parameters: Indirect CPU signals"))
        ui.onNodeWithContentDescription("Parameters: Indirect CPU signals").performScrollTo().performClick()
        select(Metric.CPU_WAIT)
        ui.onNodeWithText(text(R.string.help_cpu_wait_meaning)).assertIsDisplayed()
        close()
        assertFalse(app.settings.value.indirectCpu)
        assertNull(app.state.value.sessionId)
    }

    @Test fun selectedLanguageAndOpenHelpSurviveRecreation() {
        switchLanguage("ru", "en")
        tab(R.string.diagnostics, "ru")
        ui.onNodeWithText(text(R.string.help_guide, "ru")).performClick()
        select(Metric.HEADROOM)
        ui.onNodeWithTag("help-title").assertTextEquals("Тепловой запас")
        ui.onNodeWithText(text(R.string.help_headroom_meaning, "ru")).assertIsDisplayed()
        screenshot("headroom-ru")
        ui.activityRule.scenario.recreate(); keepAwake()
        ui.onNodeWithTag("help-title").assertTextEquals("Тепловой запас")
        assertEquals("ru", app.settings.value.language)
        ui.onNodeWithContentDescription("Закрыть справку").assertIsDisplayed()
        close("ru")
        switchLanguage("en", "ru")
        guide(); select(Metric.STATE)
        ui.onNodeWithTag("help-title").assertTextEquals("Performance state")
        ui.onNodeWithTag("help-content").performScrollToNode(hasText(text(R.string.help_state_limits)))
        ui.onNodeWithText(text(R.string.help_state_limits)).assertIsDisplayed()
        screenshot("state-en")
        close()
    }
    private fun switchLanguage(to: String, from: String) {
        tab(R.string.settings, from)
        mainList().performScrollToNode(hasText(if (to == "ru") "Русский" else "English"))
        ui.onNodeWithText(if (to == "ru") "Русский" else "English").performClick()
        mainList().performScrollToNode(hasText(text(R.string.save, from)))
        ui.onNodeWithText(text(R.string.save, from)).performClick()
        ui.waitUntil(15000) { app.settings.value.language == to && ui.onAllNodesWithText(text(R.string.settings, to)).fetchSemanticsNodes().isNotEmpty() }
        keepAwake()
    }

    @Test fun sourceResultIsExplainedWithItsTimeAndReason() {
        val result = SourceResult(Metric.CPU, SourceId.PROC_STAT, Availability.UNSUPPORTED, SourceReason.PERMISSION_DENIED)
        app.sources.beginCheck(); app.sources.record(listOf(result))
        tab(R.string.diagnostics)
        openCard("System CPU")
        ui.onNode(hasText(String.format(text(R.string.help_checked_at), formatTime(result.checkedAt))) and
            hasAnyAncestor(hasTestTag("help-content"))).assertIsDisplayed()
        ui.onNodeWithTag("help-content").performScrollToNode(hasText("permission_denied\n" + text(R.string.help_reason_permission_denied)))
        ui.onNode(hasText("permission_denied\n" + text(R.string.help_reason_permission_denied)) and
            hasAnyAncestor(hasTestTag("help-content"))).assertIsDisplayed()
        screenshot("unsupported-en")
        close()
    }

    @Test fun readingHelpKeepsTheSameMonitoringSession() = runBlocking {
        app.saveSettings(app.settings.value.copy(continuous = true, intervalMs = 1000))
        ui.onNodeWithText("Start monitoring").performClick()
        ui.waitUntil(10000) { app.state.value.sessionId != null }
        val id = app.state.value.sessionId!!
        val settings = app.settings.value
        openCard("CPU wait")
        ui.waitUntil(10000) { runBlocking { app.db.dao().page(0, id, 0, Long.MAX_VALUE).size } >= 3 }
        assertEquals(id, app.state.value.sessionId)
        assertEquals(settings, app.settings.value)
        close()
    }

    @Test fun failedScenarioStopsItsSessionAndRestoresTestSettings() = runBlocking {
        val original = app.settings.value.copy(retentionDays = 90)
        app.saveSettings(original)
        val nested = IsolatedTestProfile(app)
        val failure = runCatching {
            try {
                nested.prepare()
                app.saveSettings(app.settings.value.copy(continuous = true))
                ui.onNodeWithText("Start monitoring").performClick()
                ui.waitUntil(10000) { app.state.value.sessionId != null }
                throw AssertionError("deliberate_scenario_failure")
            } finally { nested.restore() }
        }.exceptionOrNull()
        assertEquals("deliberate_scenario_failure", failure?.message)
        assertNull(app.state.value.sessionId)
        assertEquals(original, app.settings.value)
        IsolatedTestProfile(app).restore() // early preparation failure: nothing to restore
        assertEquals(original, app.settings.value)
    }

    @Test fun emptyHistoryHelpHasVisibleCloseAndReturnsToChart() = runBlocking {
        withContext(Dispatchers.IO) { app.db.clearAllTables() }
        tab(R.string.history)
        openCard("System CPU")
        ui.onNodeWithText(text(R.string.help_history_reference)).assertIsDisplayed()
        ui.onNodeWithContentDescription("Close help").assertHeightIsAtLeast(48.dp)
        screenshot("empty-history-en")
        close()
        ui.onNodeWithContentDescription("About System CPU").assertIsDisplayed()
        assertEquals(0L, app.db.dao().sampleCount())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardOpensClosesAndReturnsFocusToTrigger() {
        val trigger = ui.onNodeWithContentDescription("About CPU wait")
        mainList().performScrollToNode(hasContentDescription("About CPU wait"))
        repeat(20) {
            if (ui.onAllNodes(hasContentDescription("About CPU wait") and isFocused()).fetchSemanticsNodes().isEmpty()) {
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_TAB)
                ui.waitForIdle()
            }
        }
        trigger.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        ui.onNodeWithTag("help-title").assertTextEquals("CPU wait")
        val closeLabel = hasContentDescription("Close help")
        repeat(8) {
            if (ui.onAllNodes(closeLabel and isFocused()).fetchSemanticsNodes().isEmpty()) device.pressKeyCode(android.view.KeyEvent.KEYCODE_TAB)
        }
        ui.onNode(closeLabel).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        ui.onNodeWithTag("metric-help-sheet").assertDoesNotExist()
        trigger.assertIsDisplayed().assertIsFocused()
    }
}
