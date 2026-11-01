package com.ahwotel

import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MethodologyAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun list() = ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))

    @Before fun prepare(): Unit = runBlocking {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        profile.prepare()
        ui.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun restore(): Unit = runBlocking { profile.restore() }

    @Test fun calculatorProducesPlannedSampleAndKeepsDraftAfterClosing() {
        ui.onNode(hasText("Settings") and hasClickAction()).performClick()
        list().performScrollToNode(hasText("Session duration, seconds"))
        ui.onNode(hasText("Session duration, seconds") and hasSetTextAction()).performTextReplacement("777")
        list().performScrollToNode(hasTestTag("help-topic-link-METHODOLOGY"))
        ui.onNodeWithTag("help-topic-link-METHODOLOGY").performClick()
        ui.onNodeWithTag("help-title").assertTextEquals("Measurement methodology")
        ui.onNodeWithTag("help-content").performScrollToNode(hasTestTag("methodology-population"))
        ui.onNodeWithTag("methodology-population").performTextReplacement("1000")
        ui.onNodeWithTag("methodology-base-result").assertTextEquals("Base sample: 278 devices")
        ui.onNodeWithTag("methodology-planned-result").assertTextEquals("Planned recruitment: 328 devices")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("methodology-population").assertTextContains("1000")
        ui.onNodeWithContentDescription("Close help").performClick()
        list().performScrollToNode(hasText("Session duration, seconds"))
        ui.onNode(hasText("Session duration, seconds") and hasSetTextAction()).assertTextContains("777")
    }

    @Test fun russianMethodologyIsLocalizedAndAcceptsDecimalComma() = runBlocking {
        app.saveSettings(app.settings.value.copy(language = "ru"))
        ui.waitUntil(15000) { ui.onAllNodesWithText("Настройки").fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasText("Настройки") and hasClickAction()).performClick()
        list().performScrollToNode(hasTestTag("help-topic-link-METHODOLOGY"))
        ui.onNodeWithTag("help-topic-link-METHODOLOGY").performClick()
        ui.onNodeWithTag("help-title").assertTextEquals("Методология измерений")
        ui.onNodeWithTag("help-content").performScrollToNode(hasTestTag("methodology-population"))
        ui.onNodeWithTag("methodology-population").performTextReplacement("1000")
        ui.onNodeWithTag("methodology-margin").performTextReplacement("5,0")
        ui.onNodeWithTag("methodology-base-result").assertTextEquals("Базовая выборка: 278 устройств")
        ui.onNodeWithTag("methodology-planned-result").assertTextEquals("План набора: 328 устройств")
    }
}
