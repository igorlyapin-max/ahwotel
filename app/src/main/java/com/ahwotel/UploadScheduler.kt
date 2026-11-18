package com.ahwotel

import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Two independent WorkManager slots. Room owns the packets and their deadlines. */
class UploadScheduler(private val app: MonitorApp) {
    private val gate = Mutex()
    private val finishing = LinkedHashSet<UUID>()
    internal val deliveryGate = Mutex()
    private val manager get() = WorkManager.getInstance(app)

    suspend fun recover() = withContext(Dispatchers.IO) {
        // WorkRequest automatically tags jobs with their worker class. Never cancel maintenance.
        manager.cancelAllWorkByTag("com.ahwotel.UploadWorker").result.get()
        manager.cancelUniqueWork(OLD_NAME).result.get()
        reconcile()
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        gate.withLock { NAMES.forEach { manager.cancelUniqueWork(it).result.get() } }
    }

    /** False asks the currently running worker to retry if neither slot can take over. */
    suspend fun reconcile(workerId: UUID? = null): Boolean = withContext(Dispatchers.IO) { gate.withLock schedule@{
        fun handingOff(): Boolean {
            if (workerId != null) {
                finishing.add(workerId)
                if (finishing.size > NAMES.size) finishing.remove(finishing.first())
            }
            return true
        }
        val at = app.mutex.withLock {
            if (!app.settings.value.otlpEnabled) null else app.db.dao().nextPendingTime()
        }
        if (!app.settings.value.otlpEnabled) {
            NAMES.forEach { manager.cancelUniqueWork(it).result.get() }
            return@schedule true
        }
        if (at == null) return@schedule handingOff()
        val slots = NAMES.associateWith { name ->
            manager.getWorkInfosForUniqueWork(name).get().firstOrNull { !it.state.isFinished }
        }
        val waiting = slots.values.filterNotNull().firstOrNull {
            it.id != workerId && it.state == WorkInfo.State.ENQUEUED
        }
        if (waiting != null) {
            // updateWork preserves enqueue time and never interrupts an already running worker.
            // The worker checks the absolute outbox deadline again after dependencies.
            val oldAt = waiting.tags.firstOrNull { it.startsWith(DEADLINE) }?.removePrefix(DEADLINE)?.toLongOrNull()
            if (oldAt == null || at < oldAt) {
                val enqueueAt = waiting.tags.first { it.startsWith(ENQUEUED) }.removePrefix(ENQUEUED).toLong()
                manager.updateWork(request(at, enqueueAt, waiting.id)).get()
            }
            return@schedule handingOff()
        }
        val free = slots.entries.firstOrNull { it.value == null }?.key
        if (free == null) {
            // A RUNNING peer that has not handed off is waiting for deliveryGate.
            // A peer already returning success cannot be relied on for a new deadline.
            return@schedule if (workerId != null && slots.values.any {
                it != null && it.id != workerId && it.state == WorkInfo.State.RUNNING && it.id !in finishing
            }) handingOff() else false
        }
        // No prerequisite edge. Reusing the name removes the slot's finished record.
        manager.enqueueUniqueWork(free, ExistingWorkPolicy.KEEP,
            request(at, System.currentTimeMillis())).result.get()
        handingOff()
    } }

    private fun request(at: Long, enqueuedAt: Long, id: UUID = UUID.randomUUID()) =
        OneTimeWorkRequestBuilder<OtlpUploadWorker>().setId(id)
            .addTag("$DEADLINE$at").addTag("$ENQUEUED$enqueuedAt")
            // The uploader rechecks the absolute outbox deadline before sending.
            .setInitialDelay((at - enqueuedAt).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()

    companion object {
        internal const val OLD_NAME = "otlp-upload-v2"
        internal val NAMES = listOf("otlp-upload-v3-a", "otlp-upload-v3-b")
        private const val DEADLINE = "deadline:"
        private const val ENQUEUED = "enqueued:"
        fun activeQuery(): WorkQuery = WorkQuery.Builder.fromUniqueWorkNames(NAMES)
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)).build()
    }
}
