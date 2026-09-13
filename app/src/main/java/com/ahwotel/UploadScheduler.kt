package com.ahwotel

import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A persisted chain with one uploader and at most one successor. Outbox owns deadlines. */
class UploadScheduler(private val app: MonitorApp) {
    private val gate = Mutex()
    internal val deliveryGate = Mutex()
    private val manager get() = WorkManager.getInstance(app)

    suspend fun recover() = withContext(Dispatchers.IO) {
        // WorkRequest automatically tags jobs with their worker class. Never cancel maintenance.
        manager.cancelAllWorkByTag("com.ahwotel.UploadWorker").result.get()
        reconcile()
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        gate.withLock { manager.cancelUniqueWork(NAME).result.get() }
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) { gate.withLock {
        val at = app.mutex.withLock {
            if (!app.settings.value.otlpEnabled) null else app.db.dao().nextPendingTime()
        }
        if (!app.settings.value.otlpEnabled) {
            manager.cancelUniqueWork(NAME).result.get()
            return@withLock
        }
        if (at == null) return@withLock
        val work = manager.getWorkInfos(activeQuery()).get()
        val waiting = work.firstOrNull { it.state != WorkInfo.State.RUNNING }
        if (waiting != null) {
            // updateWork preserves enqueue time and never interrupts an already running worker.
            // The worker checks the absolute outbox deadline again after dependencies.
            val oldAt = waiting.tags.firstOrNull { it.startsWith(DEADLINE) }?.removePrefix(DEADLINE)?.toLongOrNull()
            if (oldAt == null || at < oldAt) {
                val enqueueAt = waiting.tags.first { it.startsWith(ENQUEUED) }.removePrefix(ENQUEUED).toLong()
                manager.updateWork(request(at, enqueueAt, waiting.id)).get()
            }
        } else if (work.size < 2) {
            // APPEND makes the successor durable BEFORE this worker returns success. A request
            // arriving on the completion boundary cannot be lost to KEEP on a running job.
            manager.enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(at, System.currentTimeMillis())).result.get()
        }
    } }

    private fun request(at: Long, enqueuedAt: Long, id: UUID = UUID.randomUUID()) =
        OneTimeWorkRequestBuilder<OtlpUploadWorker>().setId(id)
            .addTag("$DEADLINE$at").addTag("$ENQUEUED$enqueuedAt")
            // Eligibility remains subject to dependency completion and Android scheduling.
            // The uploader always rechecks the absolute outbox deadline before sending.
            .setInitialDelay((at - enqueuedAt).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()

    companion object {
        const val NAME = "otlp-upload-v2"
        private const val DEADLINE = "deadline:"
        private const val ENQUEUED = "enqueued:"
        fun activeQuery(): WorkQuery = WorkQuery.Builder.fromUniqueWorkNames(listOf(NAME))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)).build()
    }
}
