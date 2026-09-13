package com.ahwotel

import com.ahwotel.oem.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OemWorkerTest {
    @Test fun timedOutUninterruptibleProviderCannotQueueOrCreateMoreWorkers() = runBlocking {
        val worker = BoundedProviderWorker("blocked-test")
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val first = worker.run(50) {
            calls.incrementAndGet()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            ProviderBatch(emptyList())
        }
        try {
            assertEquals(OemReason.TIMEOUT, first.second)
            repeat(20) { assertEquals(OemReason.BUSY, worker.run { calls.incrementAndGet(); ProviderBatch(emptyList()) }.second) }
            assertEquals(1, calls.get())
        } finally { release.countDown() }
        withTimeout(2000) { while (worker.run { ProviderBatch(emptyList()) }.second == OemReason.BUSY) delay(10) }
    }
    @Test fun cancellationReturnsPromptlyWhileAnotherProviderStillWorks() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = BoundedProviderWorker("cancel-test")
        val job = launch(Dispatchers.Default) { worker.run { entered.countDown(); while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }; ProviderBatch(emptyList()) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            withTimeout(500) { job.cancelAndJoin() }
            assertNotNull(BoundedProviderWorker("healthy-test").run { ProviderBatch(emptyList()) }.first)
        } finally { release.countDown() }
    }
}
