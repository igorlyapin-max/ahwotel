package com.ahwotel

import android.app.KeyguardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class LockPolicyAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun prepare(): Unit = runBlocking {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        profile.prepare()
        app.saveSettings(app.settings.value.copy(continuous = true, collectScreenOff = true,
            pauseWhenLocked = true, intervalMs = 1000))
    }
    @After fun restore(): Unit = runBlocking {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        profile.restore()
    }
    @Test fun wakingLockedDeviceDoesNotResumeSession(): Unit = runBlocking {
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        Assume.assumeFalse("Automated unlock requires a swipe-only lock screen", keyguard.isDeviceSecure)
        ui.waitUntil(15000) { ui.onAllNodesWithText("Start monitoring").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Start monitoring").performClick()
        withTimeout(15000) { while (app.state.value.sessionId == null) delay(50) }
        val id = app.state.value.sessionId!!
        withTimeout(15000) { while (app.db.dao().sampleCount() == 0L) delay(100) }
        device.sleep(); device.wakeUp()
        Assume.assumeTrue("Device must have an enabled lock screen", keyguard.isKeyguardLocked)
        withTimeout(5000) { while (!app.state.value.paused) delay(50) }
        val count = app.db.dao().sampleCount()
        delay(2200)
        assertEquals(count, app.db.dao().sampleCount())
        assertEquals(id, app.state.value.sessionId)
        device.executeShellCommand("wm dismiss-keyguard")
        withTimeout(15000) { while (app.state.value.paused || app.db.dao().sampleCount() == count) delay(100) }
        assertEquals(id, app.state.value.sessionId)
    }
}
