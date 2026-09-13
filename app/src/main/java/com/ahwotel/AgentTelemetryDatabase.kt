package com.ahwotel

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "telemetry_records", indices = [Index("time"), Index(value = ["metric", "time"]), Index("sessionId")])
data class TelemetryRecord(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: String,
    val stream: String, val metric: String, val component: String = "", val time: Long, val start: Long,
    val durationMs: Long, val segment: Int, val value: Double? = null, val low: Double? = null, val high: Double? = null,
    val sum: Double? = null, val count: Long = 0, val p50: Double? = null, val p95: Double? = null,
    val text: String? = null, val status: String = "AVAILABLE", val reason: String = "NONE",
    val source: String, val quality: String = "MEASURED", val metadata: String)
@Entity(tableName = "telemetry_schedule")
data class TelemetrySchedule(@PrimaryKey val name: String, val last: Long)
@Dao interface AgentTelemetryDao {
    @Insert suspend fun insert(rows: List<TelemetryRecord>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun schedule(row: TelemetrySchedule)
    @Query("SELECT * FROM telemetry_schedule") suspend fun schedules(): List<TelemetrySchedule>
    @Query("SELECT * FROM telemetry_records WHERE id IN (SELECT MAX(id) FROM telemetry_records GROUP BY metric,component)")
    fun latest(): Flow<List<TelemetryRecord>>
    @Query("SELECT COALESCE(MAX(id),0) FROM telemetry_records") suspend fun latestId(): Long
    @Query("SELECT * FROM telemetry_records WHERE metric=:metric AND time BETWEEN :from AND :to AND time>=:afterTime AND (time>:afterTime OR id>:afterId) AND id<=:throughId ORDER BY time,id LIMIT 300")
    suspend fun chartPage(metric: String, from: Long, to: Long, afterTime: Long, afterId: Long, throughId: Long): List<TelemetryRecord>
    @Query("SELECT * FROM telemetry_records WHERE id>:after AND time BETWEEN :from AND :to AND (:session IS NULL OR sessionId=:session) ORDER BY id LIMIT 300")
    suspend fun page(after: Long, from: Long, to: Long, session: String? = null): List<TelemetryRecord>
    @Query("DELETE FROM telemetry_records WHERE time<:cutoff") suspend fun expire(cutoff: Long): Int
    @Query("SELECT COALESCE(SUM(length(metadata)+length(COALESCE(text,''))+400),0) FROM telemetry_records WHERE stream='self'") suspend fun selfBytes(): Long
    @Query("DELETE FROM telemetry_records WHERE id IN (SELECT id FROM telemetry_records WHERE (:stream IS NULL OR stream=:stream) ORDER BY time LIMIT 500)")
    suspend fun trim(stream: String? = null): Int
    @Query("SELECT COUNT(*) FROM telemetry_records") suspend fun count(): Long
}
