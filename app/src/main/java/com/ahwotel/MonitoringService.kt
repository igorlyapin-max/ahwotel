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

class MonitoringService : Service() {
    private val app get() = application as MonitorApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var active: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val paused = intent.action == Intent.ACTION_SCREEN_OFF && !app.settings.value.collectScreenOff
            if (active != null) app.state.value = app.state.value.copy(paused = paused)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            try {
                app.ready.await()
                if (intent?.action == STOP) {
                    val id = intent.getStringExtra("sessionId")
                    if (id == active) job?.cancel()
                    else app.logs.event("stop_session_mismatch", error = true)
                    if (active == null) stopSelf()
                    return@launch
                }
                val request = parse(intent, app.settings.value)
                app.mutex.withLock {
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
                    app.db.dao().start(session)
                    active = request.id
                    app.state.value = RuntimeState(sessionId = active)
                    refreshNotification()
                    app.logs.event("session_started")
                    job = scope.launch { runSession(session, snapshot) }
                }
            } catch (_: Exception) {
                app.logs.event("command_rejected", error = true)
                if (active == null) { app.state.value = RuntimeState(error = "command_rejected"); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runSession(session: SessionRow, snapshot: Settings) {
        val collectors = Collectors(this, snapshot, app.sources)
        val probe = if (CollectorKind.CPU in snapshot.enabled && snapshot.indirectCpu) CpuProbe(snapshot.intervalMs) else null
        app.sources.beginCheck()
        var checkGeneration = app.sourceCheckGeneration.get()
        val power = getSystemService(PowerManager::class.java)
        val start = SystemClock.elapsedRealtime()
        val deadline = if (session.continuous) Long.MAX_VALUE else start + session.durationSeconds * 1000
        var next = start
        var previous: Long? = null
        var previousWall: Long? = null
        var segment = 0
        var paused = false
        var count = 0
        var cleanupAt = start
        var endReason = "manual_stop"
        var telemetry: Telemetry? = null
        var lastCapabilities = ""
        var notificationLanguage = snapshot.language
        try {
            while (currentCoroutineContext().isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now >= deadline) { endReason = "timeout"; break }
                val shouldPause = !power.isInteractive && !app.settings.value.collectScreenOff
                if (shouldPause != paused) {
                    paused = shouldPause
                    probe?.setPaused(paused)
                    collectors.reset(); segment++; previous = null; previousWall = null
                    app.logs.event(if (paused) "screen_off_paused" else "screen_on_resumed")
                }
                updateWakeLock(!paused && app.settings.value.collectScreenOff)
                if (notificationLanguage != app.settings.value.language) {
                    notificationLanguage = app.settings.value.language
                    refreshNotification()
                }
                app.state.value = RuntimeState(session.id, paused,
                    if (session.continuous) null else ((deadline - now) / 1000).coerceAtLeast(0))
                if (checkGeneration != app.sourceCheckGeneration.get()) {
                    checkGeneration = app.sourceCheckGeneration.get()
                    collectors.recheck(); probe?.recheck(); app.sources.beginCheck(); next = now
                    app.logs.event("source_check_started", diagnostic = true)
                }
                if (!paused && now >= next) {
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
                    app.mutex.withLock {
                        if (app.settings.value.otlpEnabled && telemetry == null) telemetry = Telemetry(session)
                        if (!app.settings.value.otlpEnabled) { telemetry?.close(); telemetry = null }
                        app.db.dao().sample(sample)
                        try {
                            telemetry?.let { otel ->
                                val payload = otel.encode(sample)
                                if (payload.size <= 1024 * 1024) app.db.dao().enqueue(OutboxRow(
                                    createdAt = wall, endpoint = app.settings.value.endpoint, payload = payload))
                                else app.logs.event("otlp_request_too_large", error = true)
                            }
                        } catch (_: Exception) { app.logs.event("otlp_encode_failed", error = true) }
                        if (now >= cleanupAt) { app.cleanupLocked(); cleanupAt = now + 60_000 }
                    }
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
                delay(minOf(1000L, if (paused) 1000L else (next - SystemClock.elapsedRealtime()).coerceAtLeast(100L),
                    (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { endReason = "storage_or_collection_failed"; app.logs.event(endReason, error = true) }
        finally {
            probe?.close()
            updateWakeLock(false)
            telemetry?.close()
            withContext(NonCancellable) {
                app.mutex.withLock {
                    runCatching { app.db.dao().finish(session.id, System.currentTimeMillis(), endReason) }
                        .onFailure { app.logs.event("session_finish_write_failed", error = true) }
                    active = null
                    app.state.value = RuntimeState(error = if (endReason == "storage_or_collection_failed") endReason else null)
                }
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
                }
        } else { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    }

    private fun notification(): Notification {
        val localized = createConfigurationContext(android.content.res.Configuration(resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(app.settings.value.language))
        })
        val stop = Intent(this, MonitoringService::class.java).setAction(STOP).putExtra("sessionId", active)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle(localized.getString(R.string.app_name)).setContentText(localized.getString(R.string.notification_text))
            .setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, localized.getString(R.string.stop), PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    }
    private fun refreshNotification() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager::class.java).notify(42, notification())
    }
    override fun onDestroy() { unregisterReceiver(screenReceiver); job?.cancel(); scope.cancel(); updateWakeLock(false); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopSelf() }

    companion object {
        const val START = "com.ahwotel.START"
        const val STOP = "com.ahwotel.STOP"
        const val CHANNEL = "monitoring"
        fun parse(intent: Intent?, settings: Settings): StartRequest {
            require(intent?.action == START)
            val metrics = intent.getStringExtra("metrics")?.split(',')?.map { CollectorKind.valueOf(it.trim().uppercase()) }?.toSet() ?: settings.enabled
            return StartRequest(intent.getStringExtra("sessionId") ?: UUID.randomUUID().toString(),
                intent.getBooleanExtra("continuous", settings.continuous),
                intent.getLongExtra("durationSeconds", settings.durationSeconds),
                intent.getLongExtra("samplingIntervalMs", settings.intervalMs), metrics,
                intent.getStringExtra("reason") ?: "").also { require(it.valid(settings)) }
        }
        fun start(context: Context, request: StartRequest) {
            val intent = Intent(context, MonitoringService::class.java).setAction(START)
                .putExtra("sessionId", request.id).putExtra("continuous", request.continuous)
                .putExtra("durationSeconds", request.durationSeconds).putExtra("samplingIntervalMs", request.intervalMs)
                .putExtra("metrics", request.metrics.joinToString(",") { it.name }).putExtra("reason", request.reason)
            ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context, id: String) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(STOP).putExtra("sessionId", id))
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
                app.logs.event("soti_command_received")
                require(intent.getStringExtra("sessionId") != null)
                when (intent.action) {
                    MonitoringService.START -> {
                        val request = MonitoringService.parse(intent, app.settings.value)
                        val existing = app.mutex.withLock { app.db.dao().session(request.id) }
                        when {
                            existing != null -> { app.logs.event("start_duplicate"); pending.resultCode = 200 }
                            app.state.value.sessionId != null -> { app.logs.event("start_conflict"); pending.resultCode = 409 }
                            else -> { MonitoringService.start(context, request); pending.resultCode = 202 }
                        }
                    }
                    MonitoringService.STOP -> {
                        val id = intent.getStringExtra("sessionId")!!
                        if (app.state.value.sessionId == id) { MonitoringService.stop(context, id); pending.resultCode = 202 }
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
