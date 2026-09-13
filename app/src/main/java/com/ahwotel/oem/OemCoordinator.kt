package com.ahwotel.oem

import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.ahwotel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One process-lifetime worker per provider. An uninterruptible OEM call cannot create more threads. */
class BoundedProviderWorker(private val name: String) {
    private val busy = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadFactory { r -> Thread(r, "AHWOTel-$name").apply { isDaemon = true } })
    suspend fun run(timeoutMs: Long = 3000, block: () -> ProviderBatch): Pair<ProviderBatch?, OemReason?> {
        if (!busy.compareAndSet(false, true)) return null to OemReason.BUSY
        val result = CompletableDeferred<ProviderBatch>()
        // Interrupt the running thread instead of cancelling an unstarted FutureTask. The accepted
        // runnable always executes its finally block, so rapid Stop cannot leave busy stuck forever.
        val thread = java.util.concurrent.atomic.AtomicReference<Thread?>()
        val cancelled = AtomicBoolean(false)
        try { executor.execute {
            thread.set(Thread.currentThread())
            if (cancelled.get()) Thread.currentThread().interrupt()
            try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) }
            finally { thread.set(null); Thread.interrupted(); busy.set(false) }
        } } catch (_: RejectedExecutionException) { busy.set(false); return null to OemReason.BUSY }
        return try { withTimeout(timeoutMs) { result.await() } to null }
        catch (_: TimeoutCancellationException) { cancelled.set(true); thread.get()?.interrupt(); null to OemReason.TIMEOUT }
        catch (e: kotlinx.coroutines.CancellationException) { cancelled.set(true); thread.get()?.interrupt(); throw e }
        catch (_: Exception) { null to OemReason.READ_FAILED }
    }
}

class OemCoordinator(private val app: MonitorApp) {
    val latest = MutableStateFlow<Map<String, OemObservation>>(emptyMap())
    val profiles = MutableStateFlow<Map<String, OemProviderProfile>>(emptyMap())
    val checking = MutableStateFlow(false)
    val generation = AtomicLong(0)
    private val providers = listOf(AndroidStandardProvider(app), SamsungKnoxProvider(app))
    private val workers = providers.associate { it.id to BoundedProviderWorker(it.id) }
    private val checkMutex = Mutex()
    @Volatile private var sessionId: String? = null
    private var job: Job? = null
    private val changed = ConcurrentHashMap.newKeySet<OemCategory>()
    private val failures = mutableSetOf<String>()
    private val fallbackCategories = mutableSetOf<OemCategory>()

    suspend fun restore() {
        latest.value = app.db.oemDao().latest().associate { key(it.observation()) to it.observation() }
        profiles.value = app.db.oemDao().profiles().mapNotNull { row -> runCatching { decodeProfile(row.payload) }.getOrNull() }
            .associateBy { it.provider }
    }

    fun start(session: SessionRow) {
        check(sessionId == null)
        sessionId = session.id
        job = app.scope.launch {
            try { runSession(session) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { app.logs.event("oem_collection_failed", error = true) }
        }
    }
    suspend fun stop(id: String) {
        if (sessionId != id) return
        sessionId = null; job?.cancelAndJoin(); job = null
    }
    suspend fun checkNow() {
        app.ready.await()
        if (sessionId != null) { generation.incrementAndGet(); return }
        checkMutex.withLock {
            if (sessionId != null) { generation.incrementAndGet(); return }
            checking.value = true
            app.logs.event("oem_check_started", diagnostic = true)
            try {
                val settings = app.settings.value.oem
                for (category in OemCategory.entries.filter { it != OemCategory.BATTERY }) {
                    if (sessionId != null) { generation.incrementAndGet(); break }
                    collectCategory(category, settings, null, 0)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { app.logs.event("oem_check_failed", error = true) }
            finally { checking.value = false; app.logs.event("oem_check_finished", diagnostic = true) }
        }
    }

    private suspend fun runSession(session: SessionRow) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                changed += if (intent.action?.startsWith("android.intent.action.PACKAGE_") == true) OemCategory.INVENTORY else OemCategory.BATTERY
            }
        }
        val packageReceiver = receiver
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val network = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { changed += OemCategory.NETWORK }
            override fun onLost(network: Network) { changed += OemCategory.NETWORK }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { changed += OemCategory.NETWORK }
        }
        var networkRegistered = false
        ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(app, packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            runCatching { cm.registerDefaultNetworkCallback(network); networkRegistered = true }
            val due = OemCategory.entries.associateWith { 0L }.toMutableMap()
            val lastCollected = OemCategory.entries.associateWith { 0L }.toMutableMap()
            var lastSettings: OemSettings? = null
            var lastGeneration = generation.get()
            var segment = 0
            var paused = false
            var lastElapsed = SystemClock.elapsedRealtime()
            var lastWall = System.currentTimeMillis()
            while (currentCoroutineContext().isActive && sessionId == session.id) {
                val s = app.settings.value
                if (!s.monitoringEnabled) break
                val now = SystemClock.elapsedRealtime(); val wall = System.currentTimeMillis()
                val shouldPause = !s.collectScreenOff && !app.getSystemService(PowerManager::class.java).isInteractive
                if (shouldPause != paused || now - lastElapsed > 5000 || kotlin.math.abs((wall - lastWall) - (now - lastElapsed)) > 2000) {
                    paused = shouldPause; segment++; due.keys.forEach { due[it] = 0 }
                    providers.forEach { it.reset() }
                }
                if (s.oem != lastSettings || generation.get() != lastGeneration) {
                    segment++; due.keys.forEach { due[it] = 0 }; lastGeneration = generation.get(); lastSettings = s.oem
                    providers.forEach { it.reset() }
                }
                if (!paused) {
                    for (category in OemCategory.entries.filter { it != OemCategory.BATTERY }) {
                        if (SystemClock.elapsedRealtime() >= due.getValue(category) ||
                            (s.oem.enabled && category in s.oem.categories && category in changed && SystemClock.elapsedRealtime() - lastCollected.getValue(category) >= 15_000)) {
                            changed.remove(category)
                            collectCategory(category, s.oem, session, segment)
                            lastCollected[category] = SystemClock.elapsedRealtime()
                            due[category] = if (!s.oem.enabled || category !in s.oem.categories) Long.MAX_VALUE
                                else SystemClock.elapsedRealtime() + s.oem.seconds(category) * 1000L
                        }
                    }
                }
                lastElapsed = SystemClock.elapsedRealtime(); lastWall = System.currentTimeMillis()
                delay(1000)
            }
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
            if (networkRegistered) runCatching { cm.unregisterNetworkCallback(network) }
            changed.clear()
        }
    }

    private suspend fun collectCategory(category: OemCategory, config: OemSettings, session: SessionRow?, segment: Int) = coroutineScope {
        // Standard and Knox have separate bounded workers; results are consumed independently.
        providers.map { provider -> async {
            val allowed = config.enabled && category in config.categories && (provider.id != "samsung_knox" || config.knox)
            val (batch, error) = if (allowed) workers.getValue(provider.id).run {
                val began = SystemClock.elapsedRealtime()
                var ok = false; var count = 0L
                try { provider.collect(category).also { ok = true; count = it.observations.size.toLong() } }
                finally { app.costs.operation("${provider.id}_${category.name.lowercase()}", began, ok, samples = count) }
            } else null to OemReason.DISABLED
            val data = batch ?: ProviderBatch(metricsFor(provider.id, category).map { m ->
                OemObservation(m, provider.id, if (provider.id == "samsung_knox") OemSource.OEM else OemSource.ANDROID_STANDARD,
                    if (provider.id == "samsung_knox") KnoxCapabilityRegistry.find(m)?.call?.scope ?: OemScope.DEVICE else standardScope(m),
                    status = if (error == OemReason.DISABLED) OemStatus.DISABLED else OemStatus.ERROR, reason = error ?: OemReason.READ_FAILED)
            })
            currentCoroutineContext().ensureActive()
            val current = app.settings.value
            if (session != null && (sessionId != session.id || !current.monitoringEnabled ||
                (!current.collectScreenOff && !app.getSystemService(PowerManager::class.java).isInteractive))) return@async
            if (current.oem != config) return@async
            if (session == null && sessionId != null) return@async
            val status = if (!allowed) ProviderState.NOT_CONFIGURED else if (data.observations.isEmpty()) ProviderState.API_NOT_SUPPORTED
                else providerState(data.observations.map { it.status })
            publish(provider, category, status, data, session, segment, config)
        } }.awaitAll()
    }

    private suspend fun publish(provider: OemTelemetryProvider, category: OemCategory, status: ProviderState, batch: ProviderBatch, session: SessionRow?, segment: Int, config: OemSettings) {
        app.mutex.withLock {
            if (session != null && sessionId != session.id) return
            if (session != null && app.sessionTiming?.canRecord(session.id, SystemClock.elapsedRealtime()) != true) return
            if (app.settings.value.oem != config || (session == null && sessionId != null)) return
            if (session != null && (!app.settings.value.monitoringEnabled ||
                (!app.settings.value.collectScreenOff && !app.getSystemService(PowerManager::class.java).isInteractive))) return
            val old = latest.value
            val next = old.toMutableMap()
            val events = mutableListOf<OemEventRow>()
            batch.observations.forEach { row ->
                val key = key(row); val previous = old[key]
                if (previous == null || previous.status != row.status || previous.reason != row.reason) {
                    app.logs.event(if (previous == null) "oem_probe_result" else "oem_capability_changed", oem = row)
                    if (row.status == OemStatus.AVAILABLE && failures.remove(key)) app.logs.event("oem_source_recovered", oem = row)
                    if (row.status !in setOf(OemStatus.AVAILABLE, OemStatus.DISABLED)) failures += key
                }
                if (session != null && row.metric.kind != ValueKind.NUMBER && previous != null &&
                    (previous.text != row.text || previous.number != row.number || previous.status != row.status))
                    events += OemEventRow(sessionId = session.id, time = row.time, provider = row.provider, subject = row.metric.wire,
                        kind = "STATE_CHANGED", before = stateValue(previous), after = stateValue(row))
                next[key] = row
            }
            latest.value = next
            val caps = profiles.value[provider.id]?.capabilities.orEmpty() + (category to status)
            val libraryMissing = next.values.any { it.provider == provider.id && it.metric == OemMetric.KNOX_API && it.reason == OemReason.LIBRARY_MISSING }
            val activeRows = next.values.filter { it.provider == provider.id && it.metric.category in config.categories }
            val profile = OemProviderProfile(provider.id, if (libraryMissing) ProviderState.UNAVAILABLE else providerState(activeRows.map { it.status }),
                System.currentTimeMillis(), caps, (provider as? SamsungKnoxProvider)?.license ?: "NOT_APPLICABLE")
            if (profiles.value[provider.id]?.state != profile.state) app.logs.event("oem_provider_${provider.id}_${profile.state.name.lowercase()}")
            profiles.value = profiles.value + (provider.id to profile)
            val standardAvailable = next.values.any { it.provider != "samsung_knox" && it.metric.category == category && it.status == OemStatus.AVAILABLE }
            val knoxUnavailable = next.values.any { it.provider == "samsung_knox" && it.metric.category == category && it.status !in setOf(OemStatus.AVAILABLE, OemStatus.DISABLED) }
            if (knoxUnavailable && standardAvailable && config.knox) {
                if (fallbackCategories.add(category)) app.logs.event("oem_fallback_${category.name.lowercase()}")
            } else if (provider.id == "samsung_knox" && status == ProviderState.READY) fallbackCategories.remove(category)
            app.db.withTransaction {
                app.db.oemDao().profile(OemProfileRow(provider.id, profile.checkedAt, encodeProfile(profile)))
                if (session != null && status != ProviderState.NOT_CONFIGURED) {
                    app.db.oemDao().observations(batch.observations.map { OemObservationRow.from(it, session.id, segment) })
                    batch.inventory?.let { inventory ->
                        val previous = app.db.oemDao().latestInventory(inventory.provider)
                        val before = previous?.let { InventoryCodec.decode(it.payload) }.orEmpty().associateBy { it.packageName }
                        val after = inventory.entries.associateBy { it.packageName }
                        if (previous != null) {
                            after.forEach { (name, entry) -> if (before[name] != entry) events += OemEventRow(sessionId = session.id, time = inventory.time,
                                provider = inventory.provider, subject = name, kind = if (name in before) "PACKAGE_CHANGED" else "PACKAGE_ADDED",
                                before = before[name]?.let { InventoryCodec.encode(listOf(it)) }, after = InventoryCodec.encode(listOf(entry))) }
                            if (previous.complete && inventory.complete) (before.keys - after.keys).forEach { name -> events += OemEventRow(
                                sessionId = session.id, time = inventory.time, provider = inventory.provider, subject = name, kind = "PACKAGE_REMOVED",
                                before = InventoryCodec.encode(listOf(before.getValue(name))), after = null) }
                        }
                        app.db.oemDao().inventory(OemInventoryRow(sessionId = session.id, time = inventory.time, provider = inventory.provider,
                            complete = inventory.complete, payload = InventoryCodec.encode(inventory.entries)))
                    }
                    if (events.isNotEmpty()) app.db.oemDao().events(events)
                    if (app.settings.value.otlpEnabled) runCatching {
                        val payload = OemTelemetry.encode(session, batch.observations, profile, app.settings.value.backendEnvironment)
                        val createdAt = System.currentTimeMillis()
                        if (payload.size <= 1024 * 1024) app.db.dao().enqueue(OutboxRow(createdAt = createdAt,
                            endpoint = app.settings.value.endpoint, payload = payload, stream = "oem",
                            dueAt = createdAt + app.settings.value.uploadIntervalSeconds * 1000L))
                    }.onFailure { app.logs.event("oem_otlp_encode_failed", error = true) }
                }
            }
        }
    }

    companion object {
        fun key(row: OemObservation) = "${row.provider}:${row.metric.name}"
        fun stateValue(row: OemObservation) = row.text ?: row.number?.toString() ?: row.status.name
        fun metricsFor(provider: String, category: OemCategory) = if (provider == "samsung_knox")
            (KnoxCall.entries.filter { it.metric.category == category }.map { it.metric } + when(category) {
                OemCategory.DEVICE -> listOf(OemMetric.KNOX_API, OemMetric.KNOX_VERSION)
                OemCategory.SECURITY -> listOf(OemMetric.INTEGRITY)
                OemCategory.RESOURCES -> listOf(OemMetric.OEM_SYSTEM_CPU, OemMetric.OEM_THERMAL)
                else -> emptyList()
            }).distinct() else OemMetric.entries.filter { it.category == category && it !in setOf(OemMetric.KNOX_API, OemMetric.KNOX_VERSION, OemMetric.OEM_SYSTEM_CPU, OemMetric.OEM_THERMAL) }
        fun encodeProfile(p: OemProviderProfile) = JSONObject().put("provider", p.provider).put("state", p.state.name)
            .put("checkedAt", p.checkedAt).put("license", p.license).put("capabilities", JSONObject().apply { p.capabilities.forEach { (c, s) -> put(c.name, s.name) } }).toString()
        fun decodeProfile(raw: String): OemProviderProfile = JSONObject(raw).let { o -> OemProviderProfile(o.getString("provider"), ProviderState.valueOf(o.getString("state")),
            o.getLong("checkedAt"), o.getJSONObject("capabilities").let { c -> c.keys().asSequence().associate { OemCategory.valueOf(it) to ProviderState.valueOf(c.getString(it)) } }, o.getString("license")) }
    }
}
