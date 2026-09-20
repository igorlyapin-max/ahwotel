package com.ahwotel

import android.content.*
import android.os.*
import androidx.core.content.ContextCompat
import java.io.File

/** An unavailable measurement always carries a reason; numeric zero is never a capability sentinel. */
data class AgentReading(val metric: AgentMetric, val value: Double? = null, val text: String? = null,
    val source: String = "android_api", val status: String = "AVAILABLE", val reason: String = "NONE",
    val quality: String = "MEASURED", val component: String = "", val checkedAt: Long? = null) {
    companion object {
        fun missing(metric: AgentMetric, reason: String, source: String = "android_api", status: String = "UNSUPPORTED") =
            AgentReading(metric, source = source, status = status, reason = reason)
    }
}
data class BatterySnapshot(val wall: Long, val elapsed: Long, val values: List<AgentReading>) {
    fun number(m: AgentMetric) = values.find { it.metric == m && it.status == "AVAILABLE" }?.value
}
class BatteryTelemetry(private val context: Context) {
    @Volatile private var cached: BatterySnapshot? = null
    @Volatile var generation = 0L; private set
    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { cached = null; generation++ }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    @Synchronized fun current(extended: Boolean = (context.applicationContext as? MonitorApp)?.settings?.value?.batterySettings?.extended ?: true): BatterySnapshot {
        val now = SystemClock.elapsedRealtime()
        cached?.takeIf { now - it.elapsed in 0..999 && (it.values.any { r -> r.metric==AgentMetric.ENERGY })==extended }?.let { return it }
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(BatteryManager::class.java)
        fun extra(key: String) = intent?.takeIf { it.hasExtra(key) }?.getIntExtra(key, -1)
        val level = extra(BatteryManager.EXTRA_LEVEL)
        val scale = extra(BatteryManager.EXTRA_SCALE)
        val status = extra(BatteryManager.EXTRA_STATUS)
        fun reading(m: AgentMetric, value: Double?, reason: String = "MISSING_VALUE") =
            if (value != null && value.isFinite()) AgentReading(m, value) else AgentReading.missing(m, reason, status = "UNAVAILABLE")
        val rawCharge = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrNull()
        val rawCurrent = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()
        // This firmware was observed to expose mA through an API specified in uA. Do not guess a multiplier.
        val unknownCurrentUnits = Build.MANUFACTURER.equals("samsung", true) && Build.MODEL == "SM-J260F"
        val values = mutableListOf(
            reading(AgentMetric.LEVEL, if (level != null && scale != null && scale > 0 && level in 0..scale) 100.0 * level / scale else null),
            reading(AgentMetric.TEMP, extra(BatteryManager.EXTRA_TEMPERATURE)?.takeIf { it in -500..1000 }?.div(10.0)),
            reading(AgentMetric.VOLTAGE, extra(BatteryManager.EXTRA_VOLTAGE)?.takeIf { it > 0 }?.div(1000.0)),
            reading(AgentMetric.HEALTH, extra(BatteryManager.EXTRA_HEALTH)?.takeIf { it in 2..7 }?.toDouble()),
            reading(AgentMetric.STATUS, status?.takeIf { it in 2..5 }?.toDouble()),
            reading(AgentMetric.PLUG, extra(BatteryManager.EXTRA_PLUGGED)?.takeIf { it >= 0 }?.toDouble()),
            reading(AgentMetric.CHARGING, status?.takeIf { it in 2..5 }?.let { if (it == 2 || it == 5) 1.0 else 0.0 }),
            reading(AgentMetric.POWER_SAVE, if (context.getSystemService(PowerManager::class.java).isPowerSaveMode) 1.0 else 0.0),
            reading(AgentMetric.CHARGE, rawCharge?.takeIf { it > 0 || it == 0 && level == 0 }?.toDouble(), "INVALID_VALUE"),
            if (unknownCurrentUnits) AgentReading.missing(AgentMetric.CURRENT, "UNVERIFIED_UNITS", status = "UNAVAILABLE") else
                reading(AgentMetric.CURRENT, rawCurrent?.takeIf { it != Int.MIN_VALUE }?.toDouble())
        )
        if (extended) {
            fun property(m: AgentMetric, id: Int, signed: Boolean = false): AgentReading = try {
                val v = if(m==AgentMetric.ENERGY) bm.getLongProperty(id) else bm.getIntProperty(id).toLong()
                if(v==Long.MIN_VALUE || v==Int.MIN_VALUE.toLong()) AgentReading.missing(m,"UNSUPPORTED_COUNTER")
                else if(!signed && v<0) AgentReading.missing(m,"INVALID_VALUE",status="UNAVAILABLE")
                else AgentReading(m,v.toDouble())
            } catch(e: SecurityException) { AgentReading.missing(m,"PERMISSION_DENIED",status="PERMISSION_DENIED") }
            catch(_: Exception) { AgentReading.missing(m,"READ_FAILED",status="ERROR") }
            values += if(unknownCurrentUnits) AgentReading.missing(AgentMetric.CURRENT_AVERAGE,"UNVERIFIED_UNITS",status="UNAVAILABLE")
                else property(AgentMetric.CURRENT_AVERAGE,BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,true)
            values += property(AgentMetric.ENERGY,BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            values += if(Build.VERSION.SDK_INT>=28) reading(AgentMetric.CHARGE_TIME,
                runCatching { bm.computeChargeTimeRemaining() }.getOrNull()?.takeIf { it>=0 }?.toDouble())
                else AgentReading.missing(AgentMetric.CHARGE_TIME,"API_UNAVAILABLE")
            values += if(Build.VERSION.SDK_INT>=34) reading(AgentMetric.CHARGING_DETAIL,
                extra(BatteryManager.EXTRA_CHARGING_STATUS)?.takeIf { it in 1..5 }?.toDouble())
                else AgentReading.missing(AgentMetric.CHARGING_DETAIL,"API_UNAVAILABLE")
        }
        return BatterySnapshot(System.currentTimeMillis(), now, values).also { cached = it }
    }
    fun wear(fallback: Boolean): List<AgentReading> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val cycle = if (Build.VERSION.SDK_INT >= 34 && intent?.hasExtra(BatteryManager.EXTRA_CYCLE_COUNT) == true)
            intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }?.toDouble() else null
        val cycles = cycle?.let { AgentReading(AgentMetric.CYCLES, it) } ?: sysfs(AgentMetric.CYCLES, "cycle_count", fallback, true)
        val full = sysfs(AgentMetric.FULL, "charge_full", fallback)
        val design = sysfs(AgentMetric.DESIGN, "charge_full_design", fallback)
        val soh = if (full.value != null && design.value != null && full.value / design.value in 0.0..1.2)
            AgentReading(AgentMetric.SOH, 100.0 * full.value / design.value, source = "full_design_ratio", quality = "ESTIMATED")
        else AgentReading.missing(AgentMetric.SOH, "NO_VERIFIED_SOURCE")
        // Discover Samsung-only nodes but never interpret undocumented counters or scales.
        if (Build.MANUFACTURER.equals("samsung", true) && fallback) {
            listOf("fg_asoc", "fg_fullcapnom", "fg_cycle", "battery_cycle").forEach { name ->
                val result = runCatching { File("/sys/class/power_supply/battery/$name").bufferedReader().use { it.readLine() } }
                (context.applicationContext as? MonitorApp)?.logs?.event("battery_oem_probe", diagnostic = true,
                    probeMetric = name, probeStatus = if (result.isSuccess) "UNVERIFIED_SEMANTICS" else "READ_DENIED_OR_FAILED")
            }
        }
        return listOf(cycles, full, soh)
    }
    fun passport(fallback: Boolean): List<AgentReading> {
        val value = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        return listOf(sysfs(AgentMetric.DESIGN, "charge_full_design", fallback),
            value?.takeIf { it.matches(Regex("[A-Za-z0-9 +._-]{1,32}")) }?.let { AgentReading(AgentMetric.TECHNOLOGY, text = it) }
                ?: AgentReading.missing(AgentMetric.TECHNOLOGY, "MISSING_VALUE"))
    }
    private fun sysfs(metric: AgentMetric, name: String, fallback: Boolean, zero: Boolean = false): AgentReading {
        if (!fallback) return AgentReading.missing(metric, "FALLBACK_DISABLED", "sysfs")
        return try {
            val raw = File("/sys/class/power_supply/battery/$name").bufferedReader().use { it.readLine()?.take(64) }
            val v = raw?.toDoubleOrNull()?.takeIf { it.isFinite() && (it > 0 || zero && it == 0.0) }
            if (v == null) AgentReading.missing(metric, "INVALID_VALUE", "sysfs", "UNAVAILABLE")
            else AgentReading(metric, v, source = "sysfs_$name")
        } catch (e: java.io.IOException) {
            val denied = e.message?.contains("Permission denied", true) == true
            AgentReading.missing(metric, if (denied) "PERMISSION_DENIED" else "SOURCE_MISSING_OR_UNREADABLE", "sysfs", if (denied) "PERMISSION_DENIED" else "UNSUPPORTED")
        } catch (_: SecurityException) { AgentReading.missing(metric, "PERMISSION_DENIED", "sysfs", "PERMISSION_DENIED") }
    }
}

/** Independent baselines: invalid charge counter never disables a valid percentage trend. */
class BatteryDrain {
    private val baselines = mutableMapOf<AgentMetric,Pair<Long,Double>>()
    private var last: BatterySnapshot? = null
    private var stableAfter = 0L
    fun reset() { baselines.clear(); last = null; stableAfter = 0 }
    fun update(s: BatterySnapshot, expectedMs: Long): List<AgentReading> {
        val old = last
        val transition = old == null || old.number(AgentMetric.PLUG) != s.number(AgentMetric.PLUG) ||
            old.number(AgentMetric.CHARGING) != s.number(AgentMetric.CHARGING)
        if (transition || old != null && (s.elapsed - old.elapsed !in 1..expectedMs * 3 ||
                    kotlin.math.abs((s.wall-old.wall)-(s.elapsed-old.elapsed)) > 2000)) {
            baselines.clear(); stableAfter = s.elapsed + 300_000
        }
        last = s
        fun absent(reason: String) = listOf(AgentMetric.DRAIN_PERCENT, AgentMetric.DRAIN_CHARGE).map {
            AgentReading.missing(it, reason, "derived", "UNAVAILABLE") }
        if (s.number(AgentMetric.PLUG) != 0.0 || s.number(AgentMetric.CHARGING) != 0.0) {
            baselines.clear(); return absent("EXTERNAL_POWER")
        }
        if (s.elapsed < stableAfter) return absent("STABILIZING")
        fun delta(m: AgentMetric, result: AgentMetric, divisor: Double): AgentReading {
            fun missing(reason: String) = AgentReading.missing(result,reason,"derived","UNAVAILABLE")
            val v = s.number(m)?.takeIf { it.isFinite() && it >= 0 }
            if (v == null) { baselines.remove(m); return missing("INVALID_VALUE") }
            val b = baselines[m]
            val previous = old?.number(m)
            if (previous != null && v > previous) {
                baselines[m] = s.elapsed to v
                return missing("COUNTER_RESET")
            }
            if (b == null) { baselines[m] = s.elapsed to v; return missing("BASELINE") }
            if (s.elapsed-b.first < 900_000) return missing("INSUFFICIENT_WINDOW")
            baselines[m] = s.elapsed to v
            return AgentReading(result,(b.second-v)/((s.elapsed-b.first)/3_600_000.0)/divisor,
                source="derived",quality="ESTIMATED")
        }
        return listOf(delta(AgentMetric.LEVEL, AgentMetric.DRAIN_PERCENT, 1.0), delta(AgentMetric.CHARGE, AgentMetric.DRAIN_CHARGE, 1000.0))
    }
}
