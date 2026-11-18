package com.ahwotel

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths

@RunWith(AndroidJUnit4::class)
class HardwareSourcesAcceptanceTest {
    @Test
    fun readsCpuPoliciesAndBatteryCyclesInAppWorker(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MonitorApp
        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        assertFalse("Probe must run outside Android's main thread", isMainThread)
        assertEquals("com.ahwotel.acceptance", app.packageName)
        assertEquals(app.applicationInfo.uid, Process.myUid())

        val cpuSamples = JSONArray()
        repeat(3) { index ->
            if (index > 0) delay(1_000)
            cpuSamples.put(readCpuPolicies())
        }

        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val extraPresent = Build.VERSION.SDK_INT >= 34 &&
            battery?.hasExtra(BatteryManager.EXTRA_CYCLE_COUNT) == true
        val rawCycles = if (Build.VERSION.SDK_INT >= 34 && extraPresent) {
            battery?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, Int.MIN_VALUE)
        } else null
        val cycles = app.battery.wear(fallback = false).single { it.metric == AgentMetric.CYCLES }
        val extraStatus = when {
            Build.VERSION.SDK_INT < 34 -> "API_UNAVAILABLE"
            battery == null -> "BROADCAST_MISSING"
            !extraPresent -> "EXTRA_MISSING"
            rawCycles == null || rawCycles < 0 -> "INVALID_VALUE"
            rawCycles == 0 -> "ZERO_REPORTED"
            else -> "AVAILABLE"
        }
        val report = JSONObject()
            .put("runId", InstrumentationRegistry.getArguments().getString("runId"))
            .put("model", Build.MODEL)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("package", app.packageName)
            .put("uid", Process.myUid())
            .put("isMainThread", isMainThread)
            .put("cpuSamples", cpuSamples)
            .put("batteryCycles", JSONObject()
                .put("extraPresent", extraPresent)
                .put("raw", rawCycles ?: JSONObject.NULL)
                .put("extraStatus", extraStatus)
                .put("collectorValue", cycles.value ?: JSONObject.NULL)
                .put("collectorStatus", cycles.status)
                .put("collectorReason", cycles.reason)
                .put("collectorSource", cycles.source))
        instrumentation.sendStatus(2, Bundle().apply { putString("hardware_sources", report.toString()) })

        val expected = rawCycles?.takeIf { it >= 0 }?.toDouble()
        assertEquals("BatteryTelemetry value must match the sticky intent", expected, cycles.value)
        if (expected != null) {
            assertEquals("AVAILABLE", cycles.status)
            assertEquals("android_api", cycles.source)
        } else {
            assertEquals("UNSUPPORTED", cycles.status)
            assertEquals("FALLBACK_DISABLED", cycles.reason)
        }
    }

    private fun readCpuPolicies(): JSONObject {
        val root = Paths.get("/sys/devices/system/cpu/cpufreq")
        val sample = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime())
        val policies = try {
            Files.newDirectoryStream(root).use { entries ->
                entries.map { it.fileName.toString() }
                    .filter { it.matches(Regex("policy[0-9]+")) }
                    .sorted()
            }
        } catch (_: NoSuchFileException) {
            return sample.put("discovery", "NOT_FOUND")
        } catch (_: AccessDeniedException) {
            return sample.put("discovery", "PERMISSION_DENIED")
        } catch (_: SecurityException) {
            return sample.put("discovery", "PERMISSION_DENIED")
        } catch (_: IOException) {
            return sample.put("discovery", "READ_FAILED")
        }
        return sample.put("discovery", if (policies.isEmpty()) "NO_POLICIES" else "AVAILABLE")
            .put("policies", JSONArray(policies.map { policy ->
                val dir = root.resolve(policy)
                JSONObject()
                    .put("policy", policy)
                    .put("related_cpus", readAttribute(dir.resolve("related_cpus").toString()) { text ->
                        text.takeIf { it.matches(Regex("[0-9]+(\\s+[0-9]+)*")) }
                    })
                    .put("scaling_cur_freq_khz", readFrequency(dir.resolve("scaling_cur_freq").toString()))
                    .put("cpuinfo_max_freq_khz", readFrequency(dir.resolve("cpuinfo_max_freq").toString()))
            }))
    }

    private fun readFrequency(path: String): JSONObject =
        readAttribute(path) { it.toLongOrNull()?.takeIf { value -> value > 0 } }

    private fun readAttribute(path: String, parse: (String) -> Any?): JSONObject {
        val raw = try {
            TelemetryFiles.read(path).trim()
        } catch (e: Exception) {
            return JSONObject().put("status", TelemetryFiles.failure(e).second)
        }
        val value = parse(raw) ?: return JSONObject().put("status", "INVALID_VALUE")
        return JSONObject().put("status", "AVAILABLE").put("value", value)
    }
}
