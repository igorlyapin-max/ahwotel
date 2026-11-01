package com.ahwotel

import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

    @Test fun calculatorResultIsReadableOnCompactScreenWithLargeText() {
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme { Box(Modifier.width(320.dp).fillMaxHeight()) {
                    MetricHelpHost(app) { SettingsScreen(app) }
                } }
            }
        }
        list().performScrollToNode(hasTestTag("help-topic-link-METHODOLOGY"))
        ui.onNodeWithTag("help-topic-link-METHODOLOGY").performClick()
        ui.onNodeWithTag("help-content").performScrollToNode(hasTestTag("methodology-population"))
        ui.onNodeWithTag("methodology-population").performScrollTo().performTextReplacement("1000")
        ui.onNodeWithTag("methodology-planned-result").performScrollTo().assertIsDisplayed()
            .assertWidthIsAtLeast(100.dp).assertHeightIsAtLeast(20.dp)
            .assertTextEquals("Planned recruitment: 328 devices")
    }

    @Test fun calculatorProducesPlannedSampleAndKeepsDraftAfterClosing() {
        ui.onNode(hasText("Settings") and hasClickAction()).performClick()
        list().performScrollToNode(hasText("Session duration, seconds"))
        ui.onNode(hasText("Session duration, seconds") and hasSetTextAction()).performTextReplacement("777")
        list().performScrollToNode(hasTestTag("help-topic-link-METHODOLOGY"))
        ui.onNodeWithTag("help-topic-link-METHODOLOGY").performClick()
        ui.onNodeWithTag("help-title").assertTextEquals("Measurement methodology")
        ui.onNodeWithTag("help-content").performScrollToNode(hasTestTag("methodology-population"))
        ui.onNodeWithTag("methodology-population").performTextReplacement("1000")
        ui.onNodeWithTag("methodology-base-result").performScrollTo().assertIsDisplayed().assertTextEquals("Base sample: 278 devices")
        ui.onNodeWithTag("methodology-planned-result").performScrollTo().assertIsDisplayed().assertTextEquals("Planned recruitment: 328 devices")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("methodology-population").performScrollTo().assertIsDisplayed().assertTextContains("1000")
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
        ui.onNodeWithTag("methodology-margin").performScrollTo().performTextReplacement("5,0")
        ui.onNodeWithTag("methodology-base-result").performScrollTo().assertIsDisplayed().assertTextEquals("Базовая выборка: 278 устройств")
        ui.onNodeWithTag("methodology-planned-result").performScrollTo().assertIsDisplayed().assertTextEquals("План набора: 328 устройств")
    }
}
