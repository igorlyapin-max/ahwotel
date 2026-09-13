package com.ahwotel

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock

class Collectors(private val context: Context, private val settings: Settings,
    private val registry: SourceRegistry? = null, reader: ProcReader = AndroidProcReader) {
    private var previousCpu: CpuTicks? = null
    private val cpuAccess = ProcAccess(reader)
    private var lastHeadroom = Long.MIN_VALUE
    private val windows = (0..3).map { PressureWindow(settings.thresholds) }
    fun reset() { previousCpu = null; windows.forEach { it.reset() } }
    fun recheck() { reset(); cpuAccess.reset(); lastHeadroom = Long.MIN_VALUE }

    fun collect(sessionId: String, time: Long, elapsed: Long, segment: Int, screenOn: Boolean): SampleRow {
        var row = SampleRow(sessionId = sessionId, time = time, elapsed = elapsed, segment = segment, screenOn = screenOn)
        val capabilities = mutableMapOf<String, Availability>()
        val reasons = mutableMapOf<String, SourceReason>()
        fun run(kind: CollectorKind, action: () -> Unit) {
            if (kind !in settings.enabled) { capabilities[kind.name] = Availability.DISABLED; return }
            val began = SystemClock.elapsedRealtime()
            var successful = false
            try { capabilities[kind.name] = Availability.AVAILABLE; action(); successful = true }
            catch (e: SourceFailure) {
                capabilities[kind.name] = e.availability; reasons[kind.name] = e.reason
                if (kind == CollectorKind.CPU) previousCpu = null
            }
            catch (_: SecurityException) { capabilities[kind.name] = Availability.UNSUPPORTED; reasons[kind.name] = SourceReason.PERMISSION_DENIED }
            catch (_: UnsupportedOperationException) { capabilities[kind.name] = Availability.UNSUPPORTED; reasons[kind.name] = SourceReason.API_UNAVAILABLE }
            catch (_: Exception) { capabilities[kind.name] = Availability.ERROR; reasons[kind.name] = SourceReason.READ_FAILED }
            finally { (context.applicationContext as? MonitorApp)?.costs?.operation("${kind.name.lowercase()}_collector", began, successful, samples = if (successful) 1 else 0) }
        }
        run(CollectorKind.MEMORY) {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            row = row.copy(memoryTotal = info.totalMem.toDouble(), memoryAvailable = info.availMem.toDouble(),
                memoryPercent = if (info.totalMem > 0) 100.0 * info.availMem / info.totalMem else null,
                memoryLow = if (info.lowMemory) 1.0 else 0.0, memoryThreshold = info.threshold.toDouble())
        }
        run(CollectorKind.STORAGE) {
            val stat = StatFs(context.filesDir.absolutePath)
            row = row.copy(storageTotal = stat.totalBytes.toDouble(), storageAvailable = stat.availableBytes.toDouble(),
                storageUsed = (stat.totalBytes - stat.availableBytes).toDouble(),
                storagePercent = if (stat.totalBytes > 0) 100.0 * stat.availableBytes / stat.totalBytes else null)
        }
        run(CollectorKind.CPU) {
            val ticks = cpuAccess.read("/proc/stat", elapsed, CpuTicks::parse)
            val value = previousCpu?.let { CpuTicks.utilization(it, ticks) }
            if (value == null) {
                capabilities["CPU"] = Availability.WARMING_UP
                reasons["CPU"] = if (previousCpu == null) SourceReason.BASELINE else SourceReason.COUNTER_RESET
            }
            previousCpu = ticks
            row = row.copy(cpu = value)
        }
        run(CollectorKind.THERMAL) {
            val power = context.getSystemService(PowerManager::class.java)
            if (Build.VERSION.SDK_INT >= 29) row = row.copy(thermal = power.currentThermalStatus.toDouble())
            else capabilities["THERMAL"] = Availability.UNSUPPORTED
            if (Build.VERSION.SDK_INT >= 30) {
                if (lastHeadroom == Long.MIN_VALUE || elapsed - lastHeadroom >= 10000) {
                    lastHeadroom = elapsed
                    val headroom = power.getThermalHeadroom(0).toDouble()
                    if (headroom.isFinite()) {
                        row = row.copy(headroom = headroom, headroomTime = time)
                        capabilities["HEADROOM"] = Availability.AVAILABLE
                    } else capabilities["HEADROOM"] = Availability.ERROR
                } else capabilities["HEADROOM"] = Availability.WARMING_UP
            } else capabilities["HEADROOM"] = Availability.UNSUPPORTED
        }
        if (CollectorKind.THERMAL !in settings.enabled) capabilities["HEADROOM"] = Availability.DISABLED
        run(CollectorKind.BATTERY) {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: throw UnsupportedOperationException()
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            row = row.copy(battery = if (level >= 0 && scale > 0) 100.0 * level / scale else null,
                charging = if (status < 0) null else if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) 1.0 else 0.0,
                temperature = if (intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0 else null)
        }
        val t = settings.thresholds
        val raw = listOf(Pressure.severity(row.cpu, t.cpu),
            if (row.memoryLow == 1.0) Severity.CRITICAL else Pressure.severity(row.memoryPercent, t.memory, true),
            Pressure.severity(row.storagePercent, t.storage, true), Pressure.severity(row.thermal, t.thermal))
        val evaluated = raw.mapIndexed { i, v -> windows[i].update(v, elapsed, settings.intervalMs * 3).let { it?.ordinal?.toDouble() } }
        row = row.copy(cpuPressure = evaluated[0], memoryPressure = evaluated[1], storagePressure = evaluated[2],
            thermalPressure = evaluated[3], state = evaluated.filterNotNull().maxOrNull(),
            capabilities = capabilities.entries.joinToString(";") { "${it.key}=${it.value.name}" })
        val reports = Metric.entries.filterNot { it.isProbe }.map { metric ->
            val source = when(metric) {
                Metric.CPU -> SourceId.PROC_STAT
                Metric.THERMAL -> SourceId.THERMAL_API
                Metric.HEADROOM -> SourceId.HEADROOM_API
                Metric.BATTERY, Metric.CHARGING, Metric.TEMPERATURE -> SourceId.BATTERY_BROADCAST
                Metric.MEMORY_TOTAL, Metric.MEMORY_AVAILABLE, Metric.MEMORY_PERCENT, Metric.MEMORY_LOW, Metric.MEMORY_THRESHOLD -> SourceId.ACTIVITY_MANAGER
                Metric.STORAGE_TOTAL, Metric.STORAGE_AVAILABLE, Metric.STORAGE_USED, Metric.STORAGE_PERCENT -> SourceId.STAT_FS
                else -> SourceId.DERIVED
            }
            val key = when(source) {
                SourceId.PROC_STAT -> "CPU"; SourceId.ACTIVITY_MANAGER -> "MEMORY"; SourceId.STAT_FS -> "STORAGE"
                SourceId.THERMAL_API -> "THERMAL"; SourceId.HEADROOM_API -> "HEADROOM"; SourceId.BATTERY_BROADCAST -> "BATTERY"
                else -> when(metric) { Metric.CPU_PRESSURE -> "CPU"; Metric.MEMORY_PRESSURE -> "MEMORY"
                    Metric.STORAGE_PRESSURE -> "STORAGE"; Metric.THERMAL_PRESSURE -> "THERMAL"; else -> "STATE" }
            }
            var status = capabilities[key] ?: if (metric.value(row) != null) Availability.AVAILABLE else Availability.WARMING_UP
            var reason = reasons[key] ?: when(status) {
                Availability.DISABLED -> SourceReason.DISABLED
                Availability.UNSUPPORTED -> SourceReason.API_UNAVAILABLE
                Availability.ERROR -> SourceReason.READ_FAILED
                Availability.WARMING_UP -> SourceReason.BASELINE
                else -> SourceReason.NONE
            }
            if (status == Availability.AVAILABLE && metric.value(row) == null) {
                status = Availability.UNSUPPORTED; reason = SourceReason.MISSING_VALUE
            }
            SourceResult(metric, source, status, reason, time)
        }.filterNot { it.metric == Metric.HEADROOM && it.status == Availability.WARMING_UP }
        registry?.record(reports)
        return row
    }
}
