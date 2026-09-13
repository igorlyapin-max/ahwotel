package com.ahwotel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SourceRegistryTest {
    @Test fun fallbackAndUnsupportedAreLoggedAtOffWithoutRepeatedRefusals() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "logs/current.jsonl").apply { parentFile!!.mkdirs(); writeText("") }
        val registry = SourceRegistry(Diagnostics(context))
        val cpu = SourceResult(Metric.CPU, SourceId.PROC_STAT, Availability.UNSUPPORTED, SourceReason.PERMISSION_DENIED)
        val wait = SourceResult(Metric.CPU_WAIT, SourceId.THREAD_SCHEDSTAT, Availability.AVAILABLE)
        val timer = SourceResult(Metric.PROBE_DELAY, SourceId.MONOTONIC_TIMER, Availability.AVAILABLE)
        repeat(10) { registry.record(listOf(cpu.copy(checkedAt = it.toLong()), wait, timer)) }
        var events = file.readLines().map(::JSONObject)
        assertEquals(3, events.count { it.getString("event") == "source_probe_result" })
        assertEquals(1, events.count { it.getString("event") == "fallback_selected" })
        assertEquals("thread_schedstat", events.last().getString("fallback"))
        registry.record(listOf(wait.copy(status = Availability.UNSUPPORTED, reason = SourceReason.NOT_FOUND)))
        events = file.readLines().map(::JSONObject)
        assertEquals("monotonic_timer", events.last().getString("fallback"))
        registry.beginCheck()
        registry.record(listOf(cpu.copy(status = Availability.WARMING_UP, reason = SourceReason.BASELINE)))
        registry.record(listOf(cpu.copy(status = Availability.AVAILABLE, reason = SourceReason.NONE)))
        assertTrue(file.readText().contains("source_recovered"))
    }
    @Test fun oldSettingsGetExplicitDefaultAndNewChoiceRoundTrips() {
        val s = Settings(deviceId = "test", language = "ru")
        val old = JSONObject(SettingsCodec.encode(s)).apply { remove("indirectCpu") }.toString()
        assertTrue(SettingsCodec.decode(old).indirectCpu)
        assertEquals(s.copy(indirectCpu = false), SettingsCodec.decode(SettingsCodec.encode(s.copy(indirectCpu = false))))
    }
}
