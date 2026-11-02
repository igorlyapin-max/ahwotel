package com.ahwotel

import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class TaskBoundaryAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun prepare(): Unit = runBlocking {
        profile.prepare()
        app.saveSettings(app.settings.value.copy(continuous = true, diagnostic = DiagnosticLevel.BASIC, fileLogging = true))
        ui.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun restore(): Unit = runBlocking { profile.restore() }

    @Test fun systemDocumentPickersReturnToSettingsWithTaskHardening() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertTrue(info.taskAffinity.isNullOrEmpty())
        assertEquals(0, info.flags and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING)
        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, info.launchMode)
        ui.onNode(hasText("Settings") and hasClickAction()).performClick()
        for (id in listOf(R.string.export_profile, R.string.import_profile)) {
            val label = ui.activity.getString(id)
            ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            ui.onNodeWithText(label).performScrollTo().performClick()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10000) ||
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 2000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.pkg(app.packageName)), 10000))
            ui.onNode(hasText("Settings") and hasClickAction()).assertIsDisplayed()
            assertFalse(ui.activity.isFinishing)
        }
    }

    @Test fun launcherAndMonitoringNotificationReturnToApplication(): Unit = runBlocking {
        device.pressHome()
        device.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${app.packageName}/com.ahwotel.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.pkg(app.packageName)), 10000))
        ui.onNodeWithText("Start monitoring").performClick()
        withTimeout(15000) { while (app.state.value.sessionId == null) delay(100) }
        val manager = app.getSystemService(NotificationManager::class.java)
        withTimeout(10000) { while (manager.activeNotifications.none { it.notification.contentIntent != null }) delay(100) }
        val notification = manager.activeNotifications.first { it.notification.contentIntent != null }
        device.pressHome()
        notification.notification.contentIntent.send()
        assertTrue(device.wait(Until.hasObject(By.pkg(app.packageName)), 10000))
        assertNotNull(app.state.value.sessionId)
    }
}
