package com.ahwotel

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

suspend fun loadAgentChart(dao: AgentTelemetryDao,metric: AgentMetric,from: Long,to: Long,session: String? = null): List<AgentChartSeries> {
    val through=dao.latestId()
    val accumulator=AgentChartAccumulator(metric,from,to)
    var afterTime=from; var afterId=0L
    while(true) {
        val page=dao.chartPage(metric.name,from,to,afterTime,afterId,through,session)
        if(page.isEmpty()) break
        page.filter { metric.acceptsStream(it.stream) }.forEach(accumulator::add)
        afterTime=page.last().time; afterId=page.last().id
    }
    return accumulator.finish()
}
