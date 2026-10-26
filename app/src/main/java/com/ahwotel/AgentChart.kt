package com.ahwotel

import androidx.sqlite.db.SimpleSQLiteQuery

data class AgentChartSeries(val component: String, val points: List<ChartBucket>,
    val mixed: Set<Long>, val gaps: Set<Long>)

/** Bounded display summaries over the whole range; no raw history is changed. */
class AgentChartAccumulator(private val metric: AgentMetric, private val from: Long, private val to: Long,
    private val buckets: Int = 240) {
    private class Cell {
        var session: String? = null; var segment = 0; var gap = false; var mixed = false
        var state: Double? = null; var time = Long.MIN_VALUE
        var count = 0L; var total = 0.0; var low = Double.POSITIVE_INFINITY; var high = Double.NEGATIVE_INFINITY
        var maximum = Double.NEGATIVE_INFINITY
    }
    private val components = linkedMapOf<String,MutableMap<Long,Cell>>()
    fun add(r: TelemetryRecord) {
        if (r.time !in from..to) return
        val bucket = (((r.time-from).toDouble()/(to-from).coerceAtLeast(1))*buckets).toLong().coerceIn(0,buckets-1L)
        val c = components.getOrPut(r.component+if(r.source.startsWith("/")) " · "+r.source else "") { sortedMapOf() }.getOrPut(bucket) { Cell() }
        if (c.session == null) { c.session=r.sessionId; c.segment=r.segment }
        else if (c.session!=r.sessionId || c.segment!=r.segment) c.gap=true
        c.time=maxOf(c.time,r.time)
        val value=r.value
        if (r.status!="AVAILABLE" || value==null || !value.isFinite()) { c.gap=true; return }
        if (c.state!=null && c.state!=value) c.mixed=true
        c.state=value
        val n=r.count.coerceAtLeast(1)
        c.count+=n
        c.total+=if(metric.aggregation==MetricAggregation.SUM) value else r.sum ?: value*n
        c.low=minOf(c.low,r.low ?: value); c.high=maxOf(c.high,r.high ?: value)
        c.maximum=maxOf(c.maximum,value)
    }
    fun finish(): List<AgentChartSeries> = components.map { (component,cells) ->
        val mixed=mutableSetOf<Long>(); val gaps=mutableSetOf<Long>()
        val points=cells.map { (bucket,c) ->
            val isMixed=metric.aggregation==MetricAggregation.STATE && c.mixed
            if (isMixed) mixed+=bucket
            if (c.gap) gaps+=bucket
            val value=if(c.count==0L || c.gap || isMixed) null else when(metric.aggregation) {
                MetricAggregation.SUM -> c.total
                MetricAggregation.MAX -> c.maximum
                MetricAggregation.STATE -> c.state
                MetricAggregation.GAUGE -> c.total/c.count
            }
            ChartBucket(bucket,if(c.gap || isMixed) -1 else c.segment,c.session.orEmpty(),c.time,
                if(value==null) null else c.low,if(value==null) null else c.high,value,c.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        AgentChartSeries(component,points,mixed,gaps)
    }
}

suspend fun loadAgentChart(dao: AgentTelemetryDao,metric: AgentMetric,from: Long,to: Long,session: String? = null,
    throughId: Long? = null): List<AgentChartSeries> {
    val through=throughId ?: dao.latestId()
    return dao.chart(agentChartQuery(metric,from,to,session,through)).groupBy { it.component }.map { (component,rows) ->
        val mixed=mutableSetOf<Long>();val gaps=mutableSetOf<Long>()
        val points=rows.map { row ->
            val stateMixed=metric.aggregation==MetricAggregation.STATE && row.stateLow!=row.stateHigh
            val gap=row.invalid>0 || row.segment<0
            if(stateMixed) mixed+=row.bucket
            if(gap) gaps+=row.bucket
            val value=if(gap || stateMixed || row.sampleCount==0L) null else when(metric.aggregation) {
                MetricAggregation.SUM -> row.sumTotal
                MetricAggregation.MAX -> row.maximum
                MetricAggregation.STATE -> row.stateLow
                MetricAggregation.GAUGE -> row.weightedTotal/row.sampleCount
            }
            ChartBucket(row.bucket,if(gap || stateMixed) -1 else row.segment,row.sessionId,row.time,
                if(value==null) null else row.low,if(value==null) null else row.high,value,
                row.sampleCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        AgentChartSeries(component,points,mixed,gaps)
    }
}

internal fun agentChartQuery(metric: AgentMetric,from: Long,to: Long,session: String?,throughId: Long,
    buckets: Int = 240): SimpleSQLiteQuery {
    val bucketCount=buckets.coerceIn(10,1000)
    val span=(to-from).coerceAtLeast(1)
    val stream=when(metric.group) { "BATTERY" -> "battery";"WEAR","PASSPORT" -> "wear";else -> "self" }
    return SimpleSQLiteQuery("""SELECT component || CASE WHEN source LIKE '/%' THEN ' · ' || source ELSE '' END AS component,
        MIN(((time-?)*?)/?,?) AS bucket,
        CASE WHEN COUNT(DISTINCT segment)=1 AND COUNT(DISTINCT sessionId)=1 THEN MIN(segment) ELSE -1 END AS segment,
        CASE WHEN COUNT(DISTINCT sessionId)=1 THEN MIN(sessionId) ELSE '' END AS sessionId,
        MAX(time) AS time,
        MIN(CASE WHEN status='AVAILABLE' AND value IS NOT NULL AND value=value AND ABS(value)<=1.0e308 THEN COALESCE(low,value) END) AS low,
        MAX(CASE WHEN status='AVAILABLE' AND value IS NOT NULL AND value=value AND ABS(value)<=1.0e308 THEN COALESCE(high,value) END) AS high,
        COALESCE(SUM(CASE WHEN status='AVAILABLE' AND value IS NOT NULL AND value=value AND ABS(value)<=1.0e308 THEN COALESCE(`sum`,value*CASE WHEN count>0 THEN count ELSE 1 END) ELSE 0 END),0) AS weightedTotal,
        COALESCE(SUM(CASE WHEN status='AVAILABLE' AND value IS NOT NULL AND value=value AND ABS(value)<=1.0e308 THEN value ELSE 0 END),0) AS sumTotal,
        MAX(CASE WHEN status='AVAILABLE' AND value IS NOT NULL AND value=value AND ABS(value)<=1.0e308 THEN value END) AS maximum,
        MIN(CASE WHEN status='AVAILABLE' AND value IS NOT NULL AND value=value AND ABS(value)<=1.0e308 THEN value END) AS stateLow,
        MAX(CASE WHEN status='AVAILABLE' AND value IS NOT NULL AND value=value AND ABS(value)<=1.0e308 THEN value END) AS stateHigh,
        COALESCE(SUM(CASE WHEN status='AVAILABLE' AND value IS NOT NULL AND value=value AND ABS(value)<=1.0e308 THEN CASE WHEN count>0 THEN count ELSE 1 END ELSE 0 END),0) AS sampleCount,
        SUM(CASE WHEN status!='AVAILABLE' OR value IS NULL OR value!=value OR ABS(value)>1.0e308 THEN 1 ELSE 0 END) AS invalid
        FROM telemetry_records WHERE metric=? AND stream=? AND time BETWEEN ? AND ? AND id<=? AND (? IS NULL OR sessionId=?)
        GROUP BY component || CASE WHEN source LIKE '/%' THEN ' · ' || source ELSE '' END,bucket ORDER BY component,time""".trimIndent(),
        arrayOf<Any?>(from,bucketCount,span,bucketCount-1,metric.name,stream,from,to,throughId,session,session))
}
