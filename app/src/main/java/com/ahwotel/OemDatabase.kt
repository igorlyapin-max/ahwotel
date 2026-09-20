package com.ahwotel

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.ahwotel.oem.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "oem_observations", indices = [Index("time"), Index(value = ["metric", "provider", "time"])])
data class OemObservationRow(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String, val time: Long, val segment: Int, val metric: String, val provider: String,
    val source: String, val scope: String, val unit: String, val number: Double?, val text: String?,
    val status: String, val reason: String, val quality: String) {
    fun observation() = OemObservation(OemMetric.valueOf(metric), provider, OemSource.valueOf(source), OemScope.valueOf(scope),
        number, text, OemStatus.valueOf(status), OemReason.valueOf(reason), Quality.valueOf(quality), time)
    companion object {
        fun from(o: OemObservation, session: String, segment: Int) = OemObservationRow(sessionId = session, time = o.time,
            segment = segment, metric = o.metric.name, provider = o.provider, source = o.source.name, scope = o.scope.name,
            unit = o.metric.unit, number = o.number, text = o.text, status = o.status.name, reason = o.reason.name, quality = o.quality.name)
    }
}
@Entity(tableName = "oem_profiles")
data class OemProfileRow(@PrimaryKey val provider: String, val time: Long, val payload: String)
@Entity(tableName = "oem_inventory", indices = [Index("time")])
data class OemInventoryRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: String,
    val time: Long, val provider: String, val complete: Boolean, val payload: String)
@Entity(tableName = "oem_events", indices = [Index("time")])
data class OemEventRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: String,
    val time: Long, val provider: String, val subject: String, val kind: String, val before: String?, val after: String?)

@Dao interface OemDao {
    @Query("SELECT * FROM oem_observations WHERE id IN (SELECT MAX(id) FROM oem_observations WHERE sessionId=:session GROUP BY metric,provider)")
    fun latestInSession(session: String): Flow<List<OemObservationRow>>

    @Query("SELECT metric, SUM(CASE WHEN status='AVAILABLE' AND (number IS NOT NULL OR text IS NOT NULL) THEN 1 ELSE 0 END) AS valid, SUM(CASE WHEN status IN ('UNSUPPORTED','PERMISSION_DENIED') THEN 1 ELSE 0 END) AS refusals, COUNT(*) AS total FROM oem_observations WHERE time BETWEEN :from AND :to AND (:session IS NULL OR sessionId=:session) GROUP BY metric")
    suspend fun presence(from: Long,to: Long,session: String?): List<MetricPresence>
    @Query("SELECT * FROM oem_observations WHERE id IN (SELECT MAX(id) FROM oem_observations WHERE time BETWEEN :from AND :to AND (:session IS NULL OR sessionId=:session) GROUP BY metric,provider)")
    suspend fun rangeLatest(from: Long,to: Long,session: String?): List<OemObservationRow>

    @Insert suspend fun observations(rows: List<OemObservationRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun profile(row: OemProfileRow)
    @Insert suspend fun inventory(row: OemInventoryRow)
    @Insert suspend fun events(rows: List<OemEventRow>)
    @Query("SELECT * FROM oem_observations WHERE id IN (SELECT MAX(id) FROM oem_observations GROUP BY metric,provider)") suspend fun latest(): List<OemObservationRow>
    @Query("SELECT * FROM oem_inventory WHERE provider=:provider ORDER BY id DESC LIMIT 1") suspend fun latestInventory(provider: String): OemInventoryRow?
    @Query("SELECT * FROM oem_inventory WHERE id IN (SELECT MAX(id) FROM oem_inventory GROUP BY provider)") fun inventories(): Flow<List<OemInventoryRow>>
    @Query("SELECT * FROM oem_events ORDER BY id DESC LIMIT 200") fun recentEvents(): Flow<List<OemEventRow>>
    @Query("SELECT * FROM oem_profiles") suspend fun profiles(): List<OemProfileRow>
    @Query("SELECT * FROM oem_observations WHERE id>:after AND time BETWEEN :from AND :to AND (:session IS NULL OR sessionId=:session) ORDER BY id LIMIT 500")
    suspend fun page(after: Long, from: Long, to: Long, session: String?): List<OemObservationRow>
    @Query("SELECT * FROM oem_inventory WHERE id>:after AND time BETWEEN :from AND :to AND (:session IS NULL OR sessionId=:session) ORDER BY id LIMIT 20") suspend fun inventoryPage(after: Long, from: Long, to: Long, session: String?): List<OemInventoryRow>
    @Query("SELECT * FROM oem_events WHERE id>:after AND time BETWEEN :from AND :to AND (:session IS NULL OR sessionId=:session) ORDER BY id LIMIT 500") suspend fun eventPage(after: Long, from: Long, to: Long, session: String?): List<OemEventRow>
    @Query("DELETE FROM oem_observations WHERE time<:cutoff") suspend fun expireObservations(cutoff: Long): Int
    @Query("DELETE FROM oem_inventory WHERE time<:cutoff") suspend fun expireInventory(cutoff: Long): Int
    @Query("DELETE FROM oem_events WHERE time<:cutoff") suspend fun expireEvents(cutoff: Long): Int
    @Query("DELETE FROM oem_profiles WHERE time<:cutoff") suspend fun expireProfiles(cutoff: Long): Int
    @Query("DELETE FROM oem_observations WHERE id IN (SELECT id FROM oem_observations ORDER BY time LIMIT 1000)") suspend fun trimObservations(): Int
    @Query("DELETE FROM oem_inventory WHERE id IN (SELECT id FROM oem_inventory ORDER BY time LIMIT 1)") suspend fun trimInventory(): Int
    @Query("DELETE FROM oem_events WHERE id IN (SELECT id FROM oem_events ORDER BY time LIMIT 1000)") suspend fun trimEvents(): Int
    @Query("SELECT COUNT(*) FROM oem_observations") suspend fun count(): Long
    @RawQuery suspend fun chart(query: SupportSQLiteQuery): List<ChartBucket>
}

fun oemChartQuery(metric: OemMetric, provider: String, session: String?, from: Long, to: Long): SimpleSQLiteQuery {
    val width = ((to - from).coerceAtLeast(1) / 240).coerceAtLeast(1)
    return SimpleSQLiteQuery("""SELECT ((time-?)/?) AS bucket,
        CASE WHEN COUNT(DISTINCT segment)=1 AND COUNT(DISTINCT sessionId)=1 AND COUNT(number)=COUNT(*) THEN MIN(segment) ELSE -1 END AS segment,
        CASE WHEN COUNT(DISTINCT sessionId)=1 THEN MIN(sessionId) ELSE '' END AS sessionId,
        MIN(time) AS time, MIN(number) AS low, MAX(number) AS high, AVG(number) AS mean, COUNT(number) AS count
        FROM oem_observations WHERE metric=? AND provider=? AND time BETWEEN ? AND ? AND (? IS NULL OR sessionId=?)
        GROUP BY bucket ORDER BY time""".trimIndent(), arrayOf(from, width, metric.name, provider, from, to, session, session))
}

object InventoryCodec {
    fun encode(entries: List<InventoryEntry>): String = JSONArray().apply { entries.forEach { e -> put(JSONObject()
        .put("package", e.packageName).put("version", e.versionName ?: JSONObject.NULL).put("versionCode", e.versionCode ?: JSONObject.NULL)
        .put("enabled", e.enabled ?: JSONObject.NULL).put("system", e.system ?: JSONObject.NULL).put("managed", e.managed ?: JSONObject.NULL)) } }.toString()
    fun decode(payload: String): List<InventoryEntry> = JSONArray(payload).let { a -> (0 until a.length()).map { index ->
        val o = a.getJSONObject(index)
        InventoryEntry(o.getString("package"), if (o.isNull("version")) null else o.getString("version"),
            if (o.isNull("versionCode")) null else o.getLong("versionCode"), if (o.isNull("enabled")) null else o.getBoolean("enabled"),
            if (o.isNull("system")) null else o.getBoolean("system"), if (o.isNull("managed")) null else o.getBoolean("managed"))
    } }
}
