package com.ahwotel

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionRow(@PrimaryKey val id: String, val deviceId: String, val startedAt: Long,
    val endedAt: Long? = null, val status: String = "RUNNING", val reason: String,
    val configuration: String, val continuous: Boolean, val durationSeconds: Long,
    val endReason: String? = null)

@Entity(tableName = "samples", indices = [Index("time"), Index("probeTime"), Index(value = ["sessionId", "time"])])
data class SampleRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String, val time: Long, val elapsed: Long, val segment: Int,
    val cpu: Double? = null,
    val cpuWait: Double? = null, val probeDelay: Double? = null,
    val probeTime: Long? = null, val probeInterval: Long? = null, val probeSegment: Int? = null,
    val sources: String? = null,
    val memoryTotal: Double? = null, val memoryAvailable: Double? = null,
    val memoryPercent: Double? = null, val memoryLow: Double? = null, val memoryThreshold: Double? = null,
    val storageTotal: Double? = null, val storageAvailable: Double? = null,
    val storageUsed: Double? = null, val storagePercent: Double? = null,
    val thermal: Double? = null, val headroom: Double? = null, val headroomTime: Long? = null,
    val battery: Double? = null, val charging: Double? = null, val temperature: Double? = null,
    val cpuPressure: Double? = null, val memoryPressure: Double? = null,
    val storagePressure: Double? = null, val thermalPressure: Double? = null,
    val state: Double? = null, val capabilities: String = "", val screenOn: Boolean,
)

@Entity(tableName = "outbox", indices = [Index("createdAt")])
data class OutboxRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val createdAt: Long,
    val endpoint: String, val payload: ByteArray, val attempts: Int = 0, val nextAttempt: Long = 0)

data class ChartBucket(val bucket: Long, val segment: Int, val sessionId: String,
    val time: Long, val low: Double?, val high: Double?, val mean: Double?, val count: Int)

enum class Metric(val column: String, val wire: String, val unit: String) {
    CPU("cpu", "device.cpu.utilization", "%"),
    CPU_WAIT("cpuWait", "agent.cpu.runqueue_wait", "ms"),
    PROBE_DELAY("probeDelay", "agent.scheduling.delay", "ms"),
    MEMORY_TOTAL("memoryTotal", "device.memory.total_bytes", "By"),
    MEMORY_AVAILABLE("memoryAvailable", "device.memory.available_bytes", "By"),
    MEMORY_PERCENT("memoryPercent", "device.memory.available_percent", "%"),
    MEMORY_LOW("memoryLow", "device.memory.low", "1"),
    MEMORY_THRESHOLD("memoryThreshold", "device.memory.low_threshold_bytes", "By"),
    STORAGE_TOTAL("storageTotal", "device.storage.total_bytes", "By"),
    STORAGE_AVAILABLE("storageAvailable", "device.storage.available_bytes", "By"),
    STORAGE_USED("storageUsed", "device.storage.used_bytes", "By"),
    STORAGE_PERCENT("storagePercent", "device.storage.available_percent", "%"),
    THERMAL("thermal", "device.thermal.status", "1"),
    HEADROOM("headroom", "device.thermal.headroom", "1"),
    BATTERY("battery", "device.battery.level", "%"),
    CHARGING("charging", "device.battery.charging", "1"),
    TEMPERATURE("temperature", "device.battery.temperature", "Cel"),
    CPU_PRESSURE("cpuPressure", "performance.pressure.cpu", "1"),
    MEMORY_PRESSURE("memoryPressure", "performance.pressure.memory", "1"),
    STORAGE_PRESSURE("storagePressure", "performance.pressure.storage", "1"),
    THERMAL_PRESSURE("thermalPressure", "performance.pressure.thermal", "1"),
    STATE("state", "performance.state", "1");
    fun value(s: SampleRow): Double? = when(this) {
        CPU -> s.cpu; CPU_WAIT -> s.cpuWait; PROBE_DELAY -> s.probeDelay
        MEMORY_TOTAL -> s.memoryTotal; MEMORY_AVAILABLE -> s.memoryAvailable
        MEMORY_PERCENT -> s.memoryPercent; MEMORY_LOW -> s.memoryLow; MEMORY_THRESHOLD -> s.memoryThreshold
        STORAGE_TOTAL -> s.storageTotal; STORAGE_AVAILABLE -> s.storageAvailable; STORAGE_USED -> s.storageUsed
        STORAGE_PERCENT -> s.storagePercent; THERMAL -> s.thermal; HEADROOM -> s.headroom
        BATTERY -> s.battery; CHARGING -> s.charging; TEMPERATURE -> s.temperature
        CPU_PRESSURE -> s.cpuPressure; MEMORY_PRESSURE -> s.memoryPressure
        STORAGE_PRESSURE -> s.storagePressure; THERMAL_PRESSURE -> s.thermalPressure; STATE -> s.state
    }
    val isProbe get() = this == CPU_WAIT || this == PROBE_DELAY
}

@Dao
interface MonitorDao {
    @Insert suspend fun start(row: SessionRow)
    @Insert suspend fun sample(row: SampleRow): Long
    @Insert suspend fun enqueue(row: OutboxRow)
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 200") fun sessions(): Flow<List<SessionRow>>
    @Query("SELECT * FROM sessions WHERE id=:id") suspend fun session(id: String): SessionRow?
    @Query("UPDATE sessions SET endedAt=:end, status='FINISHED', endReason=:reason WHERE id=:id AND status='RUNNING'")
    suspend fun finish(id: String, end: Long, reason: String)
    @Query("UPDATE sessions SET status='INTERRUPTED', endReason='process_interrupted', endedAt=COALESCE((SELECT MAX(time) FROM samples WHERE samples.sessionId=sessions.id), startedAt) WHERE status='RUNNING'")
    suspend fun interrupt()
    @Query("SELECT * FROM samples ORDER BY id DESC LIMIT 1") fun latest(): Flow<SampleRow?>
    @Query("SELECT * FROM samples WHERE id>:after AND (:session IS NULL OR sessionId=:session) AND time BETWEEN :from AND :to ORDER BY id LIMIT 500")
    suspend fun page(after: Long, session: String?, from: Long, to: Long): List<SampleRow>
    @Query("DELETE FROM samples WHERE time < :cutoff") suspend fun expire(cutoff: Long): Int
    @Query("DELETE FROM samples WHERE id IN (SELECT id FROM samples ORDER BY time LIMIT :count)") suspend fun trim(count: Int): Int
    @Query("DELETE FROM sessions WHERE status!='RUNNING' AND COALESCE(endedAt,startedAt)<:cutoff AND id NOT IN (SELECT DISTINCT sessionId FROM samples)") suspend fun expireSessions(cutoff: Long)
    @Query("SELECT COUNT(*) FROM samples") suspend fun sampleCount(): Long
    @Query("SELECT MIN(time) FROM samples") suspend fun earliest(): Long?
    @Query("SELECT * FROM outbox ORDER BY id LIMIT 1") suspend fun firstPending(): OutboxRow?
    @Query("DELETE FROM outbox WHERE id=:id") suspend fun deletePending(id: Long)
    @Query("DELETE FROM outbox") suspend fun clearOutbox()
    @Query("DELETE FROM outbox WHERE createdAt<:cutoff") suspend fun expireOutbox(cutoff: Long): Int
    @Query("SELECT COALESCE(SUM(length(payload)),0) FROM outbox") suspend fun outboxBytes(): Long
    @Query("SELECT COUNT(*) FROM outbox") suspend fun outboxCount(): Long
    @Query("UPDATE outbox SET attempts=:attempts, nextAttempt=:next WHERE id=:id") suspend fun retry(id: Long, attempts: Int, next: Long)
    @RawQuery suspend fun chart(query: SupportSQLiteQuery): List<ChartBucket>
}

@Database(entities = [SessionRow::class, SampleRow::class, OutboxRow::class], version = 2, exportSchema = true)
abstract class MonitorDatabase : RoomDatabase() {
    abstract fun dao(): MonitorDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("cpuWait REAL", "probeDelay REAL", "probeTime INTEGER", "probeInterval INTEGER", "probeSegment INTEGER", "sources TEXT")
                    .forEach { db.execSQL("ALTER TABLE samples ADD COLUMN $it") }
                db.execSQL("CREATE INDEX IF NOT EXISTS index_samples_probeTime ON samples(probeTime)")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, MonitorDatabase::class.java, "monitor.db")
            .addMigrations(MIGRATION_1_2).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
    }
}

fun chartQuery(metric: Metric, session: String?, from: Long, to: Long, buckets: Int = 240): SimpleSQLiteQuery {
    val width = ((to - from).coerceAtLeast(1) / buckets.coerceIn(10, 1000)).coerceAtLeast(1)
    val col = metric.column // enum only; never user-supplied SQL.
    val clock = if (metric.isProbe) "probeTime" else "time"
    val segment = if (metric.isProbe) "probeSegment" else "segment"
    return SimpleSQLiteQuery("""SELECT (($clock-?)/?) AS bucket,
        CASE WHEN COUNT(DISTINCT $segment)=1 AND COUNT(DISTINCT sessionId)=1 AND COUNT($col)=COUNT(*) THEN MIN($segment) ELSE -1 END AS segment,
        CASE WHEN COUNT(DISTINCT sessionId)=1 THEN MIN(sessionId) ELSE '' END AS sessionId,
        MIN($clock) AS time, MIN($col) AS low, MAX($col) AS high, AVG($col) AS mean, COUNT($col) AS count
        FROM samples WHERE $clock BETWEEN ? AND ? AND (? IS NULL OR sessionId=?)
        GROUP BY bucket ORDER BY time""".trimIndent(),
        arrayOf<Any?>(from, width, from, to, session, session))
}
