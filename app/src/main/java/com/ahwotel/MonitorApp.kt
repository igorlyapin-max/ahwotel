package com.ahwotel

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RuntimeState(val sessionId: String? = null, val paused: Boolean = false,
    val remainingSeconds: Long? = null, val error: String? = null)
data class StorageStats(val bytes: Long = 0, val samples: Long = 0, val earliest: Long? = null,
    val queued: Long = 0, val queueBytes: Long = 0, val cleanupReason: String? = null)

class MonitorApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val mutex = Mutex()
    val exportGate = Mutex()
    val state = MutableStateFlow(RuntimeState())
    val settings = MutableStateFlow(Settings())
    val stats = MutableStateFlow(StorageStats())
    lateinit var db: MonitorDatabase
    lateinit var config: SettingsStore
    lateinit var logs: Diagnostics
    lateinit var sources: SourceRegistry
    val sourceCheckGeneration = java.util.concurrent.atomic.AtomicLong(0)
    val checkingSources = MutableStateFlow(false)
    val sender = OtlpSender()
    val ready = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        db = MonitorDatabase.create(this)
        config = SettingsStore(this)
        logs = Diagnostics(this)
        sources = SourceRegistry(logs)
        scope.launch {
            try {
                var initial = config.changes.first()
                if (initial.deviceId.isEmpty()) {
                    initial = initial.copy(deviceId = UUID.randomUUID().toString())
                    config.save(initial)
                }
                settings.value = initial
                logs.settings = initial
                db.dao().interrupt()
                configureReceiver(initial.sotiEnabled)
                if (!initial.otlpEnabled) db.dao().clearOutbox()
                logs.event("agent_started")
                logs.event("diagnostic_enabled", diagnostic = true)
                ready.complete(Unit)
                maintenance()
            } catch (_: Exception) {
                logs.event("initialization_failed", error = true)
                state.value = RuntimeState(error = "initialization_failed")
                ready.completeExceptionally(IllegalStateException("initialization_failed"))
            }
        }
        scope.launch {
            runCatching { ready.await() }.getOrElse { return@launch }
            while (isActive) {
                delay(1000)
                val s = settings.value
                if (s.diagnostic == DiagnosticLevel.VERBOSE && System.currentTimeMillis() >= s.verboseUntil) {
                    saveSettings(s.copy(diagnostic = DiagnosticLevel.OFF, verboseUntil = 0))
                }
            }
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("maintenance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES).build())
    }

    suspend fun checkSources() = withContext(Dispatchers.IO) {
        ready.await()
        if (state.value.sessionId != null) { sourceCheckGeneration.incrementAndGet(); return@withContext }
        mutex.withLock {
            if (state.value.sessionId != null) { sourceCheckGeneration.incrementAndGet(); return@withLock }
            checkingSources.value = true
            val s = settings.value
            val collectors = Collectors(this@MonitorApp, s, sources)
            val probe = if (CollectorKind.CPU in s.enabled && s.indirectCpu) CpuProbe(1000) else null
            sources.beginCheck()
            logs.event("source_check_started", diagnostic = true)
            try {
                repeat(2) {
                    collectors.collect("", System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), 0, true)
                    if (it == 0) delay(1200)
                }
                if (probe != null) withTimeout(5000) { while ((probe.snapshot()?.sequence ?: 0) < 2) delay(100) }
                recordProbe(probe, s, android.os.SystemClock.elapsedRealtime())
                logs.event("source_check_finished", diagnostic = true)
            } finally { probe?.close(); checkingSources.value = false }
        }
    }

    fun recordProbe(probe: CpuProbe?, snapshot: Settings, elapsed: Long) {
        val reading = probe?.snapshot()
        val disabled = CollectorKind.CPU !in snapshot.enabled || !snapshot.indirectCpu
        val results = if (disabled) listOf(
            SourceResult(Metric.CPU_WAIT, SourceId.THREAD_SCHEDSTAT, Availability.DISABLED, SourceReason.DISABLED),
            SourceResult(Metric.PROBE_DELAY, SourceId.MONOTONIC_TIMER, Availability.DISABLED, SourceReason.DISABLED)
        ) else if (reading != null && elapsed - reading.elapsed > snapshot.intervalMs * 2) reading.sources.map {
            it.copy(status = Availability.ERROR, reason = SourceReason.STALE_SAMPLE)
        } else reading?.sources.orEmpty()
        sources.record(results)
    }

    suspend fun saveSettings(next: Settings) = withContext(Dispatchers.IO) {
        ready.await()
        if (!next.otlpEnabled || next.endpoint != settings.value.endpoint) sender.cancel()
        exportGate.withLock { mutex.withLock {
            val old = settings.value
            require(next.valid()) { "invalid_configuration" }
            if (old.deviceId != next.deviceId && (state.value.sessionId != null || db.dao().outboxCount() > 0))
                throw IllegalStateException("identity_busy")
            if (old.endpoint != next.endpoint || !next.otlpEnabled) { sender.cancel(); db.dao().clearOutbox() }
            config.save(next)
            settings.value = next
            logs.settings = next
            configureReceiver(next.sotiEnabled)
            logs.event("configuration_saved", diagnostic = true)
        } }
        maintenance()
    }

    private fun configureReceiver(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(ComponentName(this, SotiCommandReceiver::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    suspend fun maintenance() = withContext(Dispatchers.IO) { mutex.withLock { cleanupLocked() } }

    /** Caller holds mutex, serializing retention with inserts, enrollment identity and queue changes. */
    suspend fun cleanupLocked() {
        val s = settings.value
        val dao = db.dao()
        val now = System.currentTimeMillis()
        var reason = stats.value.cleanupReason
        if (dao.expire(now - s.retentionDays * 86_400_000L) > 0) reason = "retention"
        dao.expireSessions(now - s.retentionDays * 86_400_000L)
        if (dao.expireOutbox(now - s.queueHours * 3_600_000L) > 0) logs.event("queue_age_limit")
        while (dao.outboxBytes() > s.queueMiB * 1024L * 1024L) {
            dao.firstPending()?.let { dao.deletePending(it.id) } ?: break
            logs.event("queue_size_limit")
        }
        val sql = db.openHelper.writableDatabase
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val limit = s.storageMiB * 1024L * 1024L
        var size = storageBytes()
        if (size > limit) {
            var count = dao.sampleCount()
            // Delete enough pages, then compact once. Queue/log budgets cannot consume the full quota.
            while (count > 0 && logicalBytes() + logs.bytes() > limit * 8 / 10) {
                val deleted = dao.trim((count / 10).coerceIn(1000, 50000).toInt())
                if (deleted == 0) break
                count -= deleted
            }
            sql.execSQL("VACUUM")
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            size = storageBytes()
            reason = "storage_limit"
            logs.event("storage_limit_cleanup")
        }
        if (size > limit || filesDir.usableSpace < 8L * 1024 * 1024) throw IllegalStateException("storage_full")
        stats.value = StorageStats(size, dao.sampleCount(), dao.earliest(), dao.outboxCount(), dao.outboxBytes(), reason)
    }

    private fun logicalBytes(): Long {
        val sql = db.openHelper.writableDatabase
        fun pragma(name: String): Long = sql.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }
        return (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
    }
    private fun storageBytes(): Long {
        val file = getDatabasePath("monitor.db")
        return listOf(file, java.io.File(file.path + "-wal"), java.io.File(file.path + "-shm")).sumOf { it.length() } + logs.bytes()
    }

    fun scheduleUpload() {
        if (!settings.value.otlpEnabled) return
        WorkManager.getInstance(this).enqueueUniqueWork("upload", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

class MaintenanceWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MonitorApp
        return try { app.ready.await(); app.maintenance(); app.scheduleUpload(); Result.success() }
        catch (_: Exception) { app.logs.event("maintenance_failed", error = true); Result.retry() }
    }
}

class UploadWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MonitorApp
        app.ready.await()
        return try {
            app.maintenance()
            repeat(100) {
                app.exportGate.withLock {
                val row = app.mutex.withLock {
                    if (!app.settings.value.otlpEnabled) return Result.success()
                    app.db.dao().firstPending()
                } ?: return Result.success()
                if (row.nextAttempt > System.currentTimeMillis()) return Result.retry()
                val code = try { app.sender.send(row) } catch (_: Exception) { 0 }
                app.mutex.withLock {
                    if (!app.settings.value.otlpEnabled) return Result.success()
                    when {
                        code in 200..298 -> app.db.dao().deletePending(row.id)
                        code == 299 -> { app.logs.event("otlp_partial_success", error = true); app.db.dao().deletePending(row.id) }
                        code in 400..499 && code !in listOf(408, 429) -> {
                            app.logs.event(if (code in listOf(401, 403)) "otlp_auth_rejected" else "otlp_request_rejected", error = true, number = code.toLong())
                            app.db.dao().deletePending(row.id)
                        }
                        else -> {
                            val backoff = (1000L shl row.attempts.coerceAtMost(12)).coerceAtMost(3_600_000L)
                            app.db.dao().retry(row.id, row.attempts + 1, System.currentTimeMillis() + backoff)
                            app.logs.event("otlp_retry", error = true, number = code.toLong())
                            return Result.retry()
                        }
                    }
                }
                }
            }
            Result.retry()
        } catch (_: Exception) { app.logs.event("upload_failed", error = true); Result.retry() }
    }
}
