package com.ahwotel

import java.net.URI

enum class CollectorKind { MEMORY, CPU, STORAGE, THERMAL, BATTERY }
enum class Availability { AVAILABLE, UNSUPPORTED, ERROR, DISABLED, WARMING_UP }
enum class Severity { NORMAL, WARNING, DEGRADED, CRITICAL }
enum class DiagnosticLevel { OFF, BASIC, VERBOSE }

data class Thresholds(
    val cpu: List<Double> = listOf(80.0, 90.0, 95.0),
    val memory: List<Double> = listOf(15.0, 10.0, 5.0),
    val storage: List<Double> = listOf(15.0, 10.0, 5.0),
    val thermal: List<Double> = listOf(1.0, 2.0, 3.0),
    val raiseSeconds: Int = 30,
    val recoverSeconds: Int = 60,
) {
    fun valid(): Boolean = listOf(cpu, memory, storage, thermal).all { it.size == 3 && it.all(Double::isFinite) } &&
        cpu.zipWithNext().all { (a, b) -> a < b } && cpu.all { it in 0.0..100.0 } &&
        memory.zipWithNext().all { (a, b) -> a > b } && memory.all { it in 0.0..100.0 } &&
        storage.zipWithNext().all { (a, b) -> a > b } && storage.all { it in 0.0..100.0 } &&
        thermal.zipWithNext().all { (a, b) -> a < b } && thermal.all { it in 0.0..6.0 && it % 1.0 == 0.0 } &&
        raiseSeconds in 1..3600 && recoverSeconds in 1..3600
}

data class Settings(
    val monitoringEnabled: Boolean = true,
    val batterySettings: BatterySettings = BatterySettings(),
    val selfTelemetry: SelfTelemetrySettings = SelfTelemetrySettings(),
    val oem: com.ahwotel.oem.OemSettings = com.ahwotel.oem.OemSettings(),
    val uploadIntervalSeconds: Int = 0,
    val backendEnvironment: String = "local",
    val language: String = "en",
    val intervalMs: Long = 2000,
    val durationSeconds: Long = 300,
    val maxDurationSeconds: Long = 86400,
    val continuous: Boolean = false,
    val collectScreenOff: Boolean = true,
    val indirectCpu: Boolean = true,
    val enabled: Set<CollectorKind> = CollectorKind.entries.toSet(),
    val retentionDays: Int = 14,
    val storageMiB: Int = 512,
    val thresholds: Thresholds = Thresholds(),
    val otlpEnabled: Boolean = false,
    val allowHttp: Boolean = false,
    val endpoint: String = "",
    val queueHours: Int = 24,
    val queueMiB: Int = 32,
    val sotiEnabled: Boolean = false,
    val deviceId: String = "",
    val diagnostic: DiagnosticLevel = DiagnosticLevel.OFF,
    val verboseUntil: Long = 0,
    val fileLogging: Boolean = true,
) {
    fun valid(): Boolean = batterySettings.valid() && selfTelemetry.valid() && selfTelemetry.limitMiB <= storageMiB / 2 &&
        oem.valid() && uploadIntervalSeconds in 0..86400 && backendEnvironment.matches(Regex("[A-Za-z0-9._-]{1,64}")) && language in setOf("en", "ru") && intervalMs in INTERVALS &&
        durationSeconds in 1..maxDurationSeconds && maxDurationSeconds in 1..604800 &&
        retentionDays in 1..90 && storageMiB in 64..4096 &&
        queueHours in 1..720 && queueMiB in 1..(storageMiB / 2) && thresholds.valid() &&
        deviceId.matches(Regex("[A-Za-z0-9._:-]{1,128}")) && (!otlpEnabled || validEndpoint(endpoint, allowHttp))

    fun withHttpAllowed(allowed: Boolean) = copy(allowHttp = allowed,
        otlpEnabled = otlpEnabled && (allowed || !endpoint.startsWith("http:", ignoreCase = true)))

    fun hasCollectors(metrics: Set<CollectorKind> = enabled): Boolean =
        metrics.any { it != CollectorKind.BATTERY } ||
        batterySettings.let { it.enabled && (it.current || it.wear || it.passport) } ||
        selfTelemetry.let { it.enabled && it.groups.isNotEmpty() } ||
        oem.let { it.enabled && it.categories.any { c -> c != com.ahwotel.oem.OemCategory.BATTERY } }

    companion object {
        val INTERVALS = listOf(1000L, 2000L, 5000L, 10000L, 30000L, 60000L)
        fun validEndpoint(value: String, allowHttp: Boolean = false): Boolean = runCatching {
            val uri = URI(value)
            (uri.scheme == "https" || (allowHttp && uri.scheme == "http")) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null && (uri.port == -1 || uri.port in 1..65535) &&
                uri.query == null && uri.fragment == null && uri.path.endsWith("/v1/metrics")
        }.getOrDefault(false)
    }
}

data class StartRequest(val id: String, val continuous: Boolean, val durationSeconds: Long,
    val intervalMs: Long, val metrics: Set<CollectorKind>, val reason: String = "") {
    fun valid(settings: Settings) = settings.monitoringEnabled && id.matches(Regex("[A-Za-z0-9._:-]{1,128}")) &&
        intervalMs in Settings.INTERVALS && durationSeconds in 1..settings.maxDurationSeconds &&
        settings.hasCollectors(metrics) && reason.length <= 256 && reason.none { it.isISOControl() }
}

/** Uses monotonic time; missing readings break a window instead of implying recovery. */
class PressureWindow(private val thresholds: Thresholds) {
    private var state = Severity.NORMAL
    private var candidate: Severity? = null
    private var since = 0L
    private var last: Long? = null

    fun update(raw: Severity?, elapsedMs: Long, maxGapMs: Long): Severity? {
        if (raw == null) { reset(); return null }
        if (last?.let { elapsedMs - it > maxGapMs || elapsedMs < it } == true) reset()
        last = elapsedMs
        if (raw == state) { candidate = null; return state }
        if (candidate != raw) { candidate = raw; since = elapsedMs }
        val wait = if (raw > state) thresholds.raiseSeconds else thresholds.recoverSeconds
        if (elapsedMs - since >= wait * 1000L) { state = raw; candidate = null }
        return state
    }
    fun reset() { state = Severity.NORMAL; candidate = null; last = null }
}

object Pressure {
    fun severity(value: Double?, thresholds: List<Double>, descending: Boolean = false): Severity? {
        if (value == null || !value.isFinite()) return null
        val level = thresholds.indexOfLast { if (descending) value <= it else value >= it } + 1
        return Severity.entries[level]
    }
}

data class CpuTicks(val total: Long, val idle: Long) {
    companion object {
        fun parse(line: String): CpuTicks {
            val fields = line.trim().split(Regex("\\s+"))
            require(fields.first() == "cpu" && fields.size >= 5)
            // guest/guest_nice are already included in user/nice.
            val values = fields.drop(1).take(8).map(String::toLong)
            require(values.all { it >= 0 })
            return CpuTicks(values.sum(), values[3] + values.getOrElse(4) { 0 })
        }
        fun utilization(previous: CpuTicks, current: CpuTicks): Double? {
            val total = current.total - previous.total
            val idle = current.idle - previous.idle
            if (total <= 0 || idle < 0 || idle > total) return null
            return 100.0 * (total - idle) / total
        }
    }
}
