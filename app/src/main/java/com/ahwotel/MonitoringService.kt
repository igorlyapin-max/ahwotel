package com.ahwotel

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class ActionOrigin { USER, POLICY, RESUME, SOTI }

class MonitoringService : Service() {
    private val app get() = application as MonitorApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var active: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val paused = intent.action == Intent.ACTION_SCREEN_OFF && !app.settings.value.collectScreenOff
            scope.launch { app.mutex.withLock {
                active?.let { app.updateSessionRuntimeLocked(it, paused, SystemClock.elapsedRealtime()) }
                app.timingChanges.trySend(Unit)
            } }
        }
    }
    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.monitoring), NotificationManager.IMPORTANCE_LOW))
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(42, notification)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            try {
                app.ready.await()
                val origin = intent?.getStringExtra("actionOrigin")?.let(ActionOrigin::valueOf) ?: ActionOrigin.USER
                if (intent?.action == REFRESH_POLICY) {
                    refreshNotification()
                    return@launch
                }
                if (intent?.action == STOP) {
                    if (app.managed.active.value && origin != ActionOrigin.POLICY) {
                        app.logs.event("managed_action_blocked", error = true)
                        if (active == null) stopSelf()
                        return@launch
                    }
                    val id = intent.getStringExtra("sessionId")
                    app.mutex.withLock {
                        if (id != null && id == active) {
                            val reason = if (origin == ActionOrigin.POLICY) "managed_policy" else "manual_stop"
                            if (app.requestSessionStopLocked(id, reason)) job?.cancel()
                            app.persistResumeStopLocked(reason)
                        }
                        else app.logs.event("stop_session_mismatch", error = true)
                        if (active == null) stopSelf()
                    }
                    return@launch
                }
                app.mutex.withLock {
                    val trigger = if (intent == null) ResumeTrigger.PROCESS else
                        intent.getStringExtra("resumeTrigger")?.let { ResumeTrigger.valueOf(it) }
                    val effectiveOrigin = if (trigger != null) ActionOrigin.RESUME else origin
                    if (app.managed.active.value && effectiveOrigin !in setOf(ActionOrigin.POLICY, ActionOrigin.RESUME)) {
                        app.logs.event("managed_action_blocked", error = true)
                        if (active == null) stopSelf()
                        return@withLock
                    }
                    if (active != null) { app.logs.event("start_conflict", error = true); return@withLock }
                    val previous = app.db.resumeDao().get() ?: ResumeRow()
                    val request = if (trigger != null) {
                        val desired = app.managed.desiredState.value
                        val rejection = if (app.managed.active.value && desired == DesiredCollectionState.STOPPED) "managed_stopped"
                            else if (app.managed.active.value && desired == DesiredCollectionState.RUNNING) null
                            else ResumePolicy.rejection(previous, app.settings.value, trigger, app.resumeGuard.blocked.value)
                        if (rejection != null) {
                            app.db.resumeDao().put(previous.copy(armed = false,
                                trigger = trigger.name.lowercase(), result = rejection))
                            stopSelf(); return@withLock
                        }
                        if (app.managed.active.value) {
                            val current = app.settings.value
                            StartRequest(UUID.randomUUID().toString(), true, current.durationSeconds, current.intervalMs,
                                current.enabled, "managed_policy").also { require(it.valid(current)) }
                        } else {
                            val saved = SettingsCodec.decode(previous.configuration)
                            check(saved.continuous)
                            StartRequest(UUID.randomUUID().toString(), true, saved.durationSeconds, saved.intervalMs,
                                saved.enabled, "resume_" + trigger.name.lowercase()).also { require(it.valid(app.settings.value)) }
                        }
                    } else parse(intent, app.settings.value)
                    if (app.db.dao().session(request.id) != null) {
                        app.logs.event("start_duplicate")
                        if (active == null) stopSelf()
                        return@launch
                    }
                    if (active != null) { app.logs.event("start_conflict", error = true); return@launch }
                    val snapshot = app.settings.value.copy(intervalMs = request.intervalMs, enabled = request.metrics,
                        durationSeconds = request.durationSeconds, continuous = request.continuous)
                    val session = SessionRow(request.id, snapshot.deviceId, System.currentTimeMillis(),
                        reason = request.reason, configuration = SettingsCodec.encode(snapshot),
                        continuous = request.continuous, durationSeconds = request.durationSeconds)
                    app.resumeGuard.block()
                    app.db.withTransaction {
                        app.db.dao().start(session)
                        app.db.resumeDao().put(ResumeRow(armed = snapshot.continuous,
                            configuration = session.configuration, trigger = trigger?.name?.lowercase() ?: "manual",
                            result = "started"))
                    }
                    if (snapshot.continuous) runCatching { app.resumeGuard.allow() }
                    else app.resumeGuard.issue.value = null
                    active = request.id
                    val timing = SessionTiming(session.id, SystemClock.elapsedRealtime(), session.continuous, session.durationSeconds)
                    app.sessionTiming = timing
                    app.state.value = timing.runtime(false, timing.startedElapsedMs)
                    refreshNotification()
                    app.logs.event("session_started")
                    if (trigger != null) app.logs.event("session_resumed_" + trigger.name.lowercase())
                    job = scope.launch(start = CoroutineStart.ATOMIC) { runSession(session, snapshot, timing.startedElapsedMs) }
                    app.sessionJob = job
                }
            } catch (e: Exception) {
                val code = if (e.message == "no_collectors") "no_collectors" else "command_rejected"
                app.logs.event(code, error = true)
                if (active == null) {
                    runCatching { app.mutex.withLock {
                        val row = app.db.resumeDao().get() ?: ResumeRow()
                        app.db.resumeDao().put(row.copy(result = "start_failed"))
                    } }
                    app.state.value = RuntimeState(error = code); stopSelf()
                }
            }
        }
        // A null restart intent rechecks durable intent; timed or stopped sessions never restart.
        return START_STICKY
    }

    private suspend fun runSession(session: SessionRow, snapshot: Settings, start: Long) {
        val collectors = Collectors(this, snapshot, app.sources, separateBattery=true)
        val probe = if (CollectorKind.CPU in snapshot.enabled && snapshot.indirectCpu) CpuProbe(snapshot.intervalMs) else null
        app.sources.beginCheck()
        var checkGeneration = app.sourceCheckGeneration.get()
        val power = getSystemService(PowerManager::class.java)
        var next = start
        var previous: Long? = null
        var previousWall: Long? = null
        var segment = 0
        var paused = false
        var count = 0
        var cleanupAt = start
        var endReason = "process_interrupted"
        var telemetry: Telemetry? = null
        var lastCapabilities = ""
        var notificationLanguage = snapshot.language
        try {
            app.agentTelemetry.start(session, snapshot)
            app.oem.start(session)
            while (currentCoroutineContext().isActive) {
                val now = SystemClock.elapsedRealtime()
                val shouldPause = !power.isInteractive && !app.settings.value.collectScreenOff
                val completion = app.mutex.withLock { app.updateSessionRuntimeLocked(session.id, shouldPause, now) }
                if (completion != null) { endReason = completion; break }
                if (shouldPause != paused) {
                    paused = shouldPause
                    probe?.setPaused(paused)
                    collectors.reset(); segment++; previous = null; previousWall = null
                    if (!paused) next = now
                    app.logs.event(if (paused) "screen_off_paused" else "screen_on_resumed")
                }
                updateWakeLock(!paused && app.settings.value.collectScreenOff)
                if (notificationLanguage != app.settings.value.language) {
                    notificationLanguage = app.settings.value.language
                    refreshNotification()
                }
                if (checkGeneration != app.sourceCheckGeneration.get()) {
                    checkGeneration = app.sourceCheckGeneration.get()
                    collectors.recheck(); probe?.recheck(); app.agentTelemetry.recheck(); app.sources.beginCheck(); next = now
                    app.logs.event("source_check_started", diagnostic = true)
                }
                app.agentTelemetry.tick(paused, now)
                if (!paused && now >= next) {
                    val lateness = (now - next).coerceAtLeast(0)
                    app.costs.add(AgentMetric.SCHEDULED, 1.0 + lateness / snapshot.intervalMs)
                    app.costs.add(AgentMetric.EXECUTED, 1.0)
                    app.costs.add(AgentMetric.MISSED, (lateness / snapshot.intervalMs).toDouble())
                    app.costs.add(AgentMetric.DELAY, lateness.toDouble())
                    if (lateness > 100) app.costs.add(AgentMetric.DELAYED, 1.0)
                    val wall = System.currentTimeMillis()
                    val gap = previous?.let { now - it > snapshot.intervalMs * 3 } == true
                    val clockChanged = previousWall?.let { w -> kotlin.math.abs((wall - w) - (now - (previous ?: now))) > 2000 } == true
                    if (gap || clockChanged) { segment++; collectors.reset(); app.logs.event("sampling_gap") }
                    val raw = collectors.collect(session.id, wall, now, segment, power.isInteractive)
                    val reading = probe?.take()
                    val probeElapsed = SystemClock.elapsedRealtime()
                    app.recordProbe(probe, snapshot, probeElapsed)
                    val probeStatuses = app.sources.reports.value.values.filter { it.metric.isProbe }
                        .joinToString(";") { "${it.metric.name}=${it.status.name}" }
                    val sample = raw.withProbe(reading, probeElapsed, snapshot.intervalMs).copy(
                        sources = app.sources.sampleJson(), capabilities = raw.capabilities + ";" + probeStatuses)
                    val recorded = app.mutex.withLock {
                        val stop = app.updateSessionRuntimeLocked(session.id, paused, SystemClock.elapsedRealtime())
                        if (stop != null) { endReason = stop; return@withLock false }
                        if (app.settings.value.otlpEnabled && telemetry == null) telemetry = Telemetry(session)
                        if (!app.settings.value.otlpEnabled) { telemetry?.close(); telemetry = null }
                        val writeStarted = SystemClock.elapsedRealtime()
                        app.db.dao().sample(sample)
                        app.costs.operation("database_write", writeStarted, true, samples = 1)
                        try {
                            telemetry?.let { otel ->
                                val encodeStarted = SystemClock.elapsedRealtime()
                                val payload = otel.encode(sample)
                                app.costs.operation("serialization", encodeStarted, true, bytes = payload.size.toLong())
                                if (payload.size <= 1024 * 1024) app.db.dao().enqueue(OutboxRow(
                                    createdAt = wall, endpoint = app.settings.value.endpoint, payload = payload, dueAt = wall + app.settings.value.uploadIntervalSeconds * 1000L))
                                else app.logs.event("otlp_request_too_large", error = true)
                            }
                        } catch (_: Exception) { app.logs.event("otlp_encode_failed", error = true) }
                        if (now >= cleanupAt) { app.cleanupLocked(); cleanupAt = now + 60_000 }
                        true
                    }
                    if (!recorded) break
                    if (sample.capabilities != lastCapabilities) {
                        app.logs.event("capabilities_changed", diagnostic = true)
                        if (sample.capabilities.contains("UNSUPPORTED")) app.logs.event("collector_unsupported")
                        if (sample.capabilities.contains("ERROR")) app.logs.event("collector_error", error = true)
                        lastCapabilities = sample.capabilities
                    }
                    count++
                    app.logs.event("sample_collected", diagnostic = true, verbose = true, number = count.toLong())
                    probe?.snapshot()?.let { app.logs.event("probe_thread", diagnostic = true, verbose = true, number = it.tid.toLong()) }
                    if (count % 5 == 0) app.scheduleUpload()
                    previous = now; previousWall = wall
                    next = now + snapshot.intervalMs
                }
                val wait = app.mutex.withLock {
                    val deadline = app.sessionTiming?.takeIf { it.sessionId == session.id }?.deadline ?: 0L
                    minOf(1000L, if (paused) 1000L else (next - SystemClock.elapsedRealtime()).coerceAtLeast(100L),
                        (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                }
                withTimeoutOrNull(wait) { app.timingChanges.receive() }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { endReason = "storage_or_collection_failed"; app.logs.event(endReason, error = true) }
        finally {
            probe?.close()
            updateWakeLock(false)
            telemetry?.close()
            withContext(NonCancellable) {
                app.oem.stop(session.id)
                runCatching { app.agentTelemetry.stop() }.onFailure { app.logs.event("self_telemetry_flush_failed", error = true) }
                app.mutex.withLock {
                    app.sessionTiming?.takeIf { it.sessionId == session.id }?.stopReason?.let { endReason = it }
                    if (endReason != "process_interrupted") app.persistResumeStopLocked(endReason)
                    runCatching {
                        app.db.dao().finish(session.id, System.currentTimeMillis(), endReason,
                            if (endReason == "process_interrupted") "INTERRUPTED" else "FINISHED")
                    }
                        .onFailure { app.logs.event("session_finish_write_failed", error = true) }
                    active = null
                    app.sessionTiming = null
                    app.sessionJob = null
                    app.state.value = RuntimeState(error = if (endReason == "storage_or_collection_failed") endReason else null)
                }
                if (endReason == "timeout") app.logs.event("session_timer_expired", diagnostic = true)
                app.logs.event("session_finished")
                app.scheduleUpload()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Synchronized private fun updateWakeLock(enabled: Boolean) {
        if (enabled) {
            if (wakeLock?.isHeld != true) wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AHWOTel:Monitoring").apply {
                    setReferenceCounted(false); acquire(10 * 60_000L)
                    app.costs.wakeAcquire(SystemClock.elapsedRealtime(), 10 * 60_000L)
                }
        } else { app.costs.wakeRelease(SystemClock.elapsedRealtime()); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    }

    private fun notification(): Notification {
        val localized = createConfigurationContext(android.content.res.Configuration(resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(app.settings.value.language))
        })
        val stop = Intent(this, MonitoringService::class.java).setAction(STOP).putExtra("sessionId", active)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle(localized.getString(R.string.app_name)).setContentText(localized.getString(R.string.notification_text))
            .setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (!app.managed.active.value) builder.addAction(0, localized.getString(R.string.stop),
            PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return builder.build()
    }
    private fun refreshNotification() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager::class.java).notify(42, notification())
    }
    override fun onDestroy() { unregisterReceiver(screenReceiver); job?.cancel(); scope.cancel(); updateWakeLock(false); super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) {
        app.logs.event("monitoring_task_removed", diagnostic = true)
        super.onTaskRemoved(rootIntent)
    }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopSelf() }

    companion object {
        fun resume(context: Context, trigger: ResumeTrigger) {
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java)
                .putExtra("resumeTrigger", trigger.name).putExtra("actionOrigin", ActionOrigin.RESUME.name))
        }
        const val START = "com.ahwotel.START"
        const val STOP = "com.ahwotel.STOP"
        const val REFRESH_POLICY = "com.ahwotel.REFRESH_POLICY"
        const val CHANNEL = "monitoring"
        fun parse(intent: Intent?, settings: Settings): StartRequest {
            require(settings.monitoringEnabled)
            require(intent?.action == START)
            val raw = intent.getStringExtra("metrics")
            val metrics = when {
                raw == null -> settings.enabled
                raw.isBlank() -> emptySet()
                else -> raw.split(',').map { CollectorKind.valueOf(it.trim().uppercase()) }.toSet()
            }
            require(settings.hasCollectors(metrics)) { "no_collectors" }
            return StartRequest(intent.getStringExtra("sessionId") ?: UUID.randomUUID().toString(),
                intent.getBooleanExtra("continuous", settings.continuous),
                intent.getLongExtra("durationSeconds", settings.durationSeconds),
                intent.getLongExtra("samplingIntervalMs", settings.intervalMs), metrics,
                intent.getStringExtra("reason") ?: "").also { require(it.valid(settings)) }
        }
        fun start(context: Context, request: StartRequest, origin: ActionOrigin = ActionOrigin.USER) {
            val intent = Intent(context, MonitoringService::class.java).setAction(START)
                .putExtra("sessionId", request.id).putExtra("continuous", request.continuous)
                .putExtra("durationSeconds", request.durationSeconds).putExtra("samplingIntervalMs", request.intervalMs)
                .putExtra("metrics", request.metrics.joinToString(",") { it.name }).putExtra("reason", request.reason)
                .putExtra("actionOrigin", origin.name)
            ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context, id: String, origin: ActionOrigin = ActionOrigin.USER) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(STOP)
                .putExtra("sessionId", id).putExtra("actionOrigin", origin.name))
        }
        fun refreshPolicy(context: Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(REFRESH_POLICY)
                .putExtra("actionOrigin", ActionOrigin.POLICY.name))
        }
    }
}

class SotiCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MonitorApp
        val pending = goAsync()
        app.scope.launch {
            try {
                app.ready.await()
                if (!app.settings.value.sotiEnabled) { app.logs.event("soti_disabled"); pending.resultCode = 403; return@launch }
                if (app.managed.active.value) { app.logs.event("managed_action_blocked", error = true); pending.resultCode = 403; return@launch }
                app.logs.event("soti_command_received")
                require(intent.getStringExtra("sessionId") != null)
                when (intent.action) {
                    MonitoringService.START -> {
                        val request = MonitoringService.parse(intent, app.settings.value)
                        val existing = app.mutex.withLock { app.db.dao().session(request.id) }
                        when {
                            existing != null -> { app.logs.event("start_duplicate"); pending.resultCode = 200 }
                            app.state.value.sessionId != null -> { app.logs.event("start_conflict"); pending.resultCode = 409 }
                            else -> { MonitoringService.start(context, request, ActionOrigin.SOTI); pending.resultCode = 202 }
                        }
                    }
                    MonitoringService.STOP -> {
                        val id = intent.getStringExtra("sessionId")!!
                        if (app.state.value.sessionId == id) { MonitoringService.stop(context, id, ActionOrigin.SOTI); pending.resultCode = 202 }
                        else { app.logs.event("stop_session_mismatch", error = true); pending.resultCode = 409 }
                    }
                    else -> { app.logs.event("soti_invalid_command", error = true); pending.resultCode = 400 }
                }
            } catch (_: Exception) {
                app.logs.event("soti_start_rejected", error = true)
                pending.resultCode = 400
                app.state.value = app.state.value.copy(error = "soti_start_rejected")
                val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(app.settings.value.language))
                })
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("commands", localized.getString(R.string.soti), NotificationManager.IMPORTANCE_DEFAULT))
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    manager.notify(43, NotificationCompat.Builder(context, "commands").setSmallIcon(R.drawable.ic_monitor)
                        .setContentTitle(localized.getString(R.string.start_rejected))
                        .setContentText(localized.getString(R.string.open_to_start))
                        .setContentIntent(PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                        .setAutoCancel(true).build())
                }
            } finally { pending.finish() }
        }
    }
}
