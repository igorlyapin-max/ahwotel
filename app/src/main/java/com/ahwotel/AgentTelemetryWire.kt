package com.ahwotel

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.export.CollectionRegistration
import io.opentelemetry.sdk.metrics.export.MetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

object AgentTelemetryWire {
    fun encode(rows: List<TelemetryRecord>): ByteArray {
        require(rows.isNotEmpty() && rows.size<=128)
        var current=rows.first()
        val reader=object : MetricReader {
            lateinit var registration: CollectionRegistration
            override fun register(r: CollectionRegistration) { registration=r }
            override fun getAggregationTemporality(i: InstrumentType)=AggregationTemporality.CUMULATIVE
            override fun forceFlush()=CompletableResultCode.ofSuccess()
            override fun shutdown()=CompletableResultCode.ofSuccess()
        }
        val metadata=JSONObject(current.metadata)
        val attributes=Attributes.builder()
        metadata.keys().forEach { k -> attributes.put(k,metadata.get(k).toString()) }
        val provider=SdkMeterProvider.builder().setResource(Resource.create(attributes.build())).setClock(object: Clock {
            override fun now()=current.time*1_000_000
            override fun nanoTime()=System.nanoTime()
        }).registerMetricReader(reader).build()
        try {
            val meter=provider.get("com.ahwotel.agent_telemetry")
            fun attrs()=Attributes.builder().put("monitoring.session.id",current.sessionId).put("stream",current.stream)
                .put("measurement.scope",if(AgentMetric.valueOf(current.metric).battery) "device" else "agent_process")
                .put("source",current.source).put("status",current.status).put("reason",current.reason)
                .put("quality",current.quality).put("component",current.component).put("window.start",current.start)
                .put("window.duration_ms",current.durationMs).put("segment",current.segment.toLong())
                .apply { current.text?.let { put("state",it) } }.build()
            rows.map { AgentMetric.valueOf(it.metric) }.distinct().forEach { m ->
                val fields=mapOf<String,(TelemetryRecord)->Double?>("" to {it.value},".min" to {it.low},".max" to {it.high},
                    ".sum" to {it.sum},".count" to {it.count.takeIf { n->n>0 }?.toDouble()},".p50" to {it.p50},".p95" to {it.p95})
                fields.forEach { (suffix,getter) -> meter.gaugeBuilder(m.wire+suffix).setUnit(if(suffix==".count") "1" else m.unit).buildWithCallback { o ->
                    if(current.metric==m.name && current.status=="AVAILABLE") getter(current)?.takeIf { it.isFinite() }?.let { o.record(it,attrs()) }
                } }
            }
            meter.gaugeBuilder("agent.telemetry.availability").buildWithCallback { o -> o.record(1.0,attrs().toBuilder().put("metric",AgentMetric.valueOf(current.metric).wire).build()) }
            val metrics=rows.flatMap { current=it; reader.registration.collectAllMetrics().toList() }
            return ByteArrayOutputStream().use { MetricsRequestMarshaler.create(metrics).writeBinaryTo(it); it.toByteArray() }
        } finally { provider.close() }
    }
    fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { out-> GZIPOutputStream(out).use { it.write(bytes) }; out.toByteArray() }
}
