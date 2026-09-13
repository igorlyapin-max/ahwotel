package com.ahwotel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.ahwotel.oem.OemCoordinator
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RuntimeState(val sessionId: String? = null, val paused: Boolean = false,
    val remainingSeconds: Long? = null, val error: String? = null, val mode: SessionMode? = null)
data class StorageStats(val bytes: Long = 0, val samples: Long = 0, val earliest: Long? = null,
    val queued: Long = 0, val queueBytes: Long = 0, val cleanupReason: String? = null)

class MonitorApp : Application() {
    val costs = AgentCosts()
    lateinit var battery: BatteryTelemetry
    lateinit var agentTelemetry: AgentTelemetryCoordinator
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val mutex = Mutex()
    val exportGate = Mutex()
    val state = MutableStateFlow(RuntimeState())
    internal var sessionTiming: SessionTiming? = null // guarded by mutex
    internal val timingChanges = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val settings = MutableStateFlow(Settings())
    private var localSettings = Settings()
    lateinit var managed: ManagedConfiguration
    lateinit var oem: OemCoordinator
    val stats = MutableStateFlow(StorageStats())
    lateinit var db: MonitorDatabase
    lateinit var config: SettingsStore
    lateinit var logs: Diagnostics
    lateinit var sources: SourceRegistry
    val sourceCheckGeneration = java.util.concurrent.atomic.AtomicLong(0)
    val checkingSources = MutableStateFlow(false)
    var sender = OtlpSender()
        internal set
    val uploads = UploadScheduler(this)
    val ready = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(costs)
        battery = BatteryTelemetry(this)
        agentTelemetry = AgentTelemetryCoordinator(this)
        db = MonitorDatabase.create(this)
        config = SettingsStore(this)
        logs = Diagnostics(this)
        sources = SourceRegistry(logs)
        managed = ManagedConfiguration(this, logs)
        oem = OemCoordinator(this)
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scope.launch {
                    ready.await()
                    mutex.withLock { managed.refresh(); publishSettings(managed.apply(localSettings)) }
                }
            }
        }, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            try {
                var initial = config.changes.first()
                if (initial.deviceId.isEmpty()) {
                    initial = initial.copy(deviceId = UUID.randomUUID().toString())
                    config.save(initial)
                }
                localSettings = initial
                managed.refresh()
                publishSettings(managed.apply(initial))
                db.dao().interrupt()
                if (!initial.otlpEnabled) db.dao().clearOutbox()
                oem.restore()
                logs.event("agent_started")
                logs.event("diagnostic_enabled", diagnostic = true)
                uploads.recover()
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
                    mutex.withLock {
                        if ("debug_mode" !in managed.keys.value) {
                            localSettings = localSettings.copy(diagnostic = DiagnosticLevel.OFF, verboseUntil = 0)
                            config.save(localSettings)
                        }
                        publishSettings(managed.apply(localSettings))
                    }
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
        val previousEndpoint = settings.value.endpoint
        if (!next.otlpEnabled || next.endpoint != previousEndpoint) sender.cancel()
        exportGate.withLock { mutex.withLock {
            val old = settings.value
            require(next.valid()) { "invalid_configuration" }
            if (old.deviceId != next.deviceId && (state.value.sessionId != null || db.dao().outboxCount() > 0))
                throw IllegalStateException("identity_busy")
            if (old.endpoint != next.endpoint || !next.otlpEnabled) { sender.cancel(); db.dao().clearOutbox() }
            val local = managed.keepLocalManagedFields(next, localSettings)
            val effective = managed.apply(local)
            val timing = sessionTiming
            val updated = timing?.changedSettings(old, effective)
            if (updated != null && updated != timing) {
                // DataStore and Room are separate stores. Publish runtime only after both succeed;
                // compensate DataStore on a failed Room commit. Process death interrupts the session.
                try {
                    db.withTransaction {
                        val row = db.dao().session(updated.sessionId) ?: error("session_missing")
                        val configuration = org.json.JSONObject(row.configuration)
                            .put("continuous", updated.continuous).put("durationSeconds", updated.durationSeconds).toString()
                        check(db.dao().updateTiming(updated.sessionId, updated.continuous, updated.durationSeconds, configuration) == 1)
                        config.save(local)
                    }
                } catch (e: Exception) {
                    withContext(NonCancellable) {
                        runCatching { config.save(localSettings) }.onFailure { logs.event("settings_restore_failed", error = true) }
                        logs.event("session_timing_apply_failed", error = true)
                    }
                    if (e is CancellationException) throw e
                    throw IllegalStateException("session_timing_apply_failed", e)
                }
                sessionTiming = updated
            } else config.save(local)
            localSettings = local
            publishSettings(effective)
            if (updated != null && updated != timing) {
                updateSessionRuntimeLocked(updated.sessionId, state.value.paused, android.os.SystemClock.elapsedRealtime())
                logs.event("session_timing_changed", diagnostic = true, timing = updated)
                timingChanges.trySend(Unit)
            }
            logs.event("configuration_saved", diagnostic = true)
        } }
        if (!next.otlpEnabled || next.endpoint != previousEndpoint) uploads.cancel()
        maintenance()
        scheduleUpload()
    }

    /** Caller holds mutex. Claim completion before a settings save can modify an ending session. */
    internal fun updateSessionRuntimeLocked(id: String, paused: Boolean, now: Long): String? {
        val timing = sessionTiming?.takeIf { it.sessionId == id } ?: return "manual_stop"
        val reason = timing.stopReason ?: when {
            !settings.value.monitoringEnabled -> "monitoring_disabled"
            timing.expired(now) -> "timeout"
            else -> null
        }
        sessionTiming = if (reason == null) timing else timing.stopped(reason)
        state.value = timing.runtime(paused, now)
        return reason
    }

    internal fun requestSessionStopLocked(id: String, reason: String): Boolean {
        val timing = sessionTiming?.takeIf { it.sessionId == id } ?: return false
        sessionTiming = timing.stopped(reason)
        timingChanges.trySend(Unit)
        return true
    }

    private fun publishSettings(value: Settings) {
        settings.value = value
        logs.settings = value
        configureReceiver(value.sotiEnabled)
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
        val cleanupStarted = android.os.SystemClock.elapsedRealtime()
        val cutoff = now - s.retentionDays * 86_400_000L
        val oemDao = db.oemDao()
        val expired = db.agentDao().expire(cutoff) + dao.expire(cutoff) + oemDao.expireObservations(cutoff) + oemDao.expireInventory(cutoff) +
            oemDao.expireEvents(cutoff) + oemDao.expireProfiles(cutoff)
        if (expired > 0) reason = "retention"
        costs.operation("database_delete", cleanupStarted, true, samples = expired.toLong())
        dao.expireSessions(cutoff)
        while (db.agentDao().selfBytes() > s.selfTelemetry.limitMiB * 1024L * 1024L) {
            val removed = db.agentDao().trim("self"); if (removed == 0) break
            costs.add(AgentMetric.DROPPED, removed.toDouble(), "self_quota")
        }
        val expiredPackets = dao.expireOutbox(now, s.queueHours * 3_600_000L)
        if (expiredPackets > 0) { costs.add(AgentMetric.DROPPED, expiredPackets.toDouble(), "queue_expiry"); logs.event("queue_age_limit") }
        while (dao.outboxBytes() > s.queueMiB * 1024L * 1024L) {
            dao.firstPending()?.let { dao.deletePending(it.id) } ?: break
            costs.add(AgentMetric.DROPPED, 1.0, "queue_quota")
            logs.event("queue_size_limit")
        }
        val sql = db.openHelper.writableDatabase
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val limit = s.storageMiB * 1024L * 1024L
        var size = storageBytes()
        if (size > limit) {
            // Delete enough pages, then compact once. Queue/log budgets cannot consume the full quota.
            while (logicalBytes() + logs.bytes() > limit * 8 / 10) {
                val deleted = db.agentDao().trim() + dao.trim(1000) + oemDao.trimObservations() + oemDao.trimInventory() + oemDao.trimEvents()
                if (deleted == 0) break
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
        scope.launch {
            try { uploads.reconcile() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { logs.event("upload_schedule_failed", error = true) }
        }
    }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); costs.add(AgentMetric.PRESSURE, 1.0, "trim_memory") }

}

class MaintenanceWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MonitorApp
        return try { app.ready.await(); app.maintenance(); app.scheduleUpload(); Result.success() }
        catch (_: Exception) { app.logs.event("maintenance_failed", error = true); Result.retry() }
    }
}

class OtlpUploadWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MonitorApp
        app.ready.await()
        return app.uploads.deliveryGate.withLock delivery@{ try {
            app.maintenance()
            val stopAt = android.os.SystemClock.elapsedRealtime() + 20_000
            var exhausted = false
            repeat(100) {
                currentCoroutineContext().ensureActive()
                if (exhausted || android.os.SystemClock.elapsedRealtime() >= stopAt) return@repeat
                app.exportGate.withLock {
                val row = app.mutex.withLock {
                    if (!app.settings.value.otlpEnabled) return@delivery Result.success()
                    app.db.dao().readyPending(System.currentTimeMillis())
                } ?: run { exhausted = true; return@repeat }
                val began = android.os.SystemClock.elapsedRealtime()
                val code = try { app.sender.send(row) } catch (_: Exception) { 0 }
                app.costs.add(AgentMetric.UPLOADS, 1.0, row.stream)
                if (row.stream == "self") app.costs.add(AgentMetric.SELF_UPLOAD, row.payload.size.toDouble())
                app.costs.add(if (code in 200..298) AgentMetric.UPLOAD_OK else AgentMetric.UPLOAD_FAIL, 1.0, row.stream)
                if (row.attempts > 0) app.costs.add(AgentMetric.UPLOAD_RETRY, 1.0, row.stream)
                app.costs.operation("uploader", began, code in 200..298, bytes = row.payload.size.toLong(), internal = row.stream == "self")
                app.mutex.withLock {
                    if (!app.settings.value.otlpEnabled) return@delivery Result.success()
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
                        }
                    }
                }
                }
            }
            app.uploads.reconcile()
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { app.logs.event("upload_failed", error = true); Result.retry() } }
    }
}
