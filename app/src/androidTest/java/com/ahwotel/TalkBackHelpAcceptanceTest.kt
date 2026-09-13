package com.ahwotel

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** Opt-in real screen-reader smoke; enable TalkBack on the emulator before this class. */
@RunWith(AndroidJUnit4::class)
class TalkBackHelpAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MonitorApp
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun nodes(node: AccessibilityNodeInfo? = automation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun label(node: AccessibilityNodeInfo?): String = node?.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        ?: node?.text?.toString()?.takeIf { it.isNotBlank() }
        ?: if (node == null) "" else (0 until node.childCount).joinToString(" ") { label(node.getChild(it)) }.trim()
    private fun focused(): AccessibilityNodeInfo? {
        automation.clearCache()
        return automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }
    private fun await(timeout: Long = 10000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "talkback_condition_timeout" }
            SystemClock.sleep(100)
        }
    }
    private fun find(value: String) = nodes().firstOrNull { it.isVisibleToUser && label(it) == value }
    private fun shortcut(key: Int, control: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or
            (if (control) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0)
        // Android 15 TestApi: ordinary injection bypasses the accessibility input filter.
        val inject = UiAutomation::class.java.getMethod("injectInputEventToInputFilter", InputEvent::class.java)
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject.invoke(automation, KeyEvent(start, SystemClock.uptimeMillis(), action, key, 0,
                meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD))
            SystemClock.sleep(40)
        }
        SystemClock.sleep(800) // Allow TalkBack's asynchronous automatic scrolling to settle.
    }
    private fun activate(value: String, backwards: Boolean = false) {
        val visited = linkedSetOf<String>()
        for (step in 0..80) {
            val current = label(focused())
            visited += current
            if (current == value || current == "$value ×") {
                shortcut(KeyEvent.KEYCODE_ENTER)
                return
            }
            shortcut(if (backwards) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        error("talkback_target_not_reached: $value; visited=$visited")
    }
    private fun shot(name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(app.filesDir, "talkback-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun realTalkBackReadsBothLanguagesAndRestoresFocus() = runBlocking {
        check(android.os.Build.HARDWARE in listOf("ranchu", "goldfish")) { "talkback_emulator_required" }
        check(android.os.Build.VERSION.SDK_INT == 35) { "talkback_harness_requires_api_35" }
        automation // Connect without disabling the real accessibility service.
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val services = app.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        check(services.any { it.id.contains("TalkBackService") }) { "enable_talkback_before_this_scenario" }
        val profile = IsolatedTestProfile(app)
        val evidence = JSONArray()
        try {
            profile.prepare()
            for (language in listOf("en", "ru")) {
                app.saveSettings(app.settings.value.copy(language = language))
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                val resources = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                })
                val trigger = resources.getString(R.string.help_guide)
                val close = resources.getString(R.string.help_close)
                val diagnostics = resources.getString(R.string.diagnostics)
                await { find(diagnostics) != null }
                // Establish the screen under test; help opening/reading/closing uses TalkBack.
                var navigation = requireNotNull(find(diagnostics))
                while (!navigation.isClickable) navigation = requireNotNull(navigation.parent)
                assertTrue(navigation.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { find(trigger) != null }
                shortcut(KeyEvent.KEYCODE_DPAD_LEFT, control = true) // First item: navigation may retain the previous tab's focus.
                activate(trigger)
                val metric = resources.getString(R.string.cpu_wait)
                await { find(metric) != null }
                activate(metric)
                await { find(close) != null }
                shot("$language-open")
                val seen = CopyOnWriteArraySet<String>()
                automation.setOnAccessibilityEventListener { event ->
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED && event.packageName == app.packageName) {
                        seen.addAll(event.text.map(CharSequence::toString))
                    }
                }
                val sections = metricHelp(Metric.CPU_WAIT).sections.map { resources.getString(it.text) }
                for (step in 0..100) {
                    seen += label(focused())
                    if (sections.all { it in seen }) break
                    shortcut(KeyEvent.KEYCODE_DPAD_RIGHT) // TalkBack next item, including automatic scrolling.
                }
                File(app.filesDir, "talkback-$language-traversal.json").writeText(JSONArray(seen.toList()).toString(2))
                assertTrue("All eight help paragraphs must be reachable through TalkBack: ${sections.filterNot { it in seen }}",
                    sections.all { it in seen })
                activate(close, backwards = true)
                await { find(close) == null }
                await { label(focused()) == trigger }
                shot("$language-return")
                evidence.put(JSONObject().put("language", language).put("paragraphs", sections.size)
                    .put("returnedFocus", label(focused())))
            }
        } catch (error: Throwable) {
            shot("failure")
            File(app.filesDir, "talkback-failure-nodes.json").writeText(JSONArray(nodes().map {
                JSONObject().put("label", label(it)).put("class", it.className).put("clickable", it.isClickable)
                    .put("focused", it.isAccessibilityFocused).put("actions", it.actionList.toString())
            }).toString(2))
            throw error
        } finally {
            automation.setOnAccessibilityEventListener(null)
            profile.restore()
        }
        File(app.filesDir, "talkback-result.json").writeText(JSONObject().put("services", services.map { it.id }.joinToString())
            .put("scenarios", evidence).toString(2))
    }
}
