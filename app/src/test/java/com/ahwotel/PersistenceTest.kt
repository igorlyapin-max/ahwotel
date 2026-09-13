package com.ahwotel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PersistenceTest {
    private lateinit var db: MonitorDatabase
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MonitorDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close() }
    @Test fun configurationRoundTripIncludesBothLanguagesAndDisabledFeatures() {
        val input = Settings(deviceId = "device-1", language = "ru", enabled = setOf(CollectorKind.MEMORY), collectScreenOff = false)
        assertEquals(input, SettingsCodec.decode(SettingsCodec.encode(input)))
    }
    @Test fun retentionDeletesOldestWithoutRemovingNewerReadings() = runBlocking {
        db.dao().sample(row(1000, 10.0)); db.dao().sample(row(2000, 20.0)); db.dao().sample(row(3000, null))
        assertEquals(1, db.dao().expire(2000))
        assertEquals(2L, db.dao().sampleCount())
        assertEquals(2000L, db.dao().earliest())
        val result = db.dao().chart(chartQuery(Metric.CPU, "s", 0, 10000, 10))
        assertEquals(20.0, result.first().mean!!, 0.0)
        assertNull(result.last().mean)
    }
    @Test fun chartRetainsPeaksAndDoesNotReturnEveryRawSample() = runBlocking {
        for (i in 1..2500) db.dao().sample(row(i * 2000L, if (i == 1250) 99.0 else 10.0))
        val result = db.dao().chart(chartQuery(Metric.CPU, null, 0, 5_002_000, 240))
        assertTrue(result.size <= 241)
        assertEquals(99.0, result.maxOf { it.high ?: 0.0 }, 0.0)
        assertEquals(2500, result.sumOf { it.count })
    }
    @Test fun restartMarksRunningSessionsInterruptedAtLastRealSample() = runBlocking {
        db.dao().start(SessionRow("s", "device", 100, reason = "", configuration = "{}", continuous = true, durationSeconds = 300))
        db.dao().sample(row(500, 1.0)); db.dao().interrupt()
        assertEquals("INTERRUPTED", db.dao().session("s")!!.status)
        assertEquals(500L, db.dao().session("s")!!.endedAt)
    }
    @Test fun twoWeeksAtTwoSecondsAreReducedInSqlWithoutLosingPeaks() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""WITH RECURSIVE sequence(n) AS (
            SELECT 0 UNION ALL SELECT n+1 FROM sequence WHERE n<604799
            ) INSERT INTO samples(sessionId,time,elapsed,segment,capabilities,screenOn,cpu)
            SELECT 's',n*2000,n*2000,0,'CPU=AVAILABLE',1,CASE WHEN n=302400 THEN 99.0 ELSE 15.0 END
            FROM sequence""".trimIndent())
        val buckets = db.dao().chart(chartQuery(Metric.CPU, "s", 0, 1_209_600_000, 240))
        assertTrue(buckets.size <= 241)
        assertEquals(604800, buckets.sumOf { it.count })
        assertEquals(99.0, buckets.maxOf { it.high ?: 0.0 }, 0.0)
    }
    @Test fun outboxPreservesBytesAndOriginalTimeAcrossRetry() = runBlocking {
        db.dao().enqueue(OutboxRow(createdAt = 1234, endpoint = "https://example.org/v1/metrics", payload = byteArrayOf(1, 2, 3)))
        val first = db.dao().firstPending()!!
        db.dao().retry(first.id, 1, 9999)
        val retry = db.dao().firstPending()!!
        assertArrayEquals(first.payload, retry.payload)
        assertEquals(first.createdAt, retry.createdAt)
        db.dao().clearOutbox()
        assertEquals(0L, db.dao().outboxCount())
    }
    @Test fun probeChartsUseTheirOwnTimeAndDoNotJoinSegments() = runBlocking {
        db.dao().sample(row(10000, null).copy(cpuWait = 2.0, probeDelay = 10.0, probeTime = 1000, probeInterval = 2000, probeSegment = 0))
        db.dao().sample(row(11000, null).copy(cpuWait = 30.0, probeDelay = 100.0, probeTime = 1500, probeInterval = 2000, probeSegment = 1))
        val bucket = db.dao().chart(chartQuery(Metric.CPU_WAIT, "s", 0, 20000, 10)).single()
        assertEquals(1000L, bucket.time); assertEquals(-1, bucket.segment)
        assertEquals(2.0, bucket.low!!, 0.0); assertEquals(30.0, bucket.high!!, 0.0)
        assertEquals(16.0, bucket.mean!!, 0.0)
    }
    private fun row(time: Long, cpu: Double?) = SampleRow(sessionId = "s", time = time, elapsed = time, segment = 0, cpu = cpu, screenOn = true)
}
