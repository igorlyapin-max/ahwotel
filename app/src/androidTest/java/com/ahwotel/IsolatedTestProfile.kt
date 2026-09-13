package com.ahwotel

import android.os.Build
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Only test-owned settings and sessions are modified; safe even if preparation fails early. */
class IsolatedTestProfile(private val app: MonitorApp) {
    private var saved: Settings? = null

    suspend fun prepare() {
        check(app.packageName == "com.ahwotel.acceptance") { "unsafe_test_profile" }
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).apply {
            wakeUp(); executeShellCommand("wm dismiss-keyguard")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val output = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(
                "pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
        }
        app.ready.await()
        saved = app.settings.value
        stopSession()
        app.saveSettings(Settings(deviceId = app.settings.value.deviceId))
    }

    suspend fun restore() {
        val original = saved ?: return
        stopSession()
        app.saveSettings(original)
        saved = null
    }

    private suspend fun stopSession() {
        withTimeout(10000) {
            while (true) {
                val id = app.state.value.sessionId ?: break
                MonitoringService.stop(app, id)
                delay(100)
            }
        }
    }
}
