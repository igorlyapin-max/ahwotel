package com.ahwotel.oem

import com.ahwotel.BuildConfig
import com.ahwotel.SessionRow
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

/** Numeric measurements and capability metadata only. Each point retains its observation time. */
object OemTelemetry {
    fun encode(session: SessionRow, rows: List<OemObservation>, profile: OemProviderProfile, environment: String): ByteArray {
        var current: OemObservation? = null
        val reader = object : MetricReader {
            lateinit var registration: CollectionRegistration
            override fun register(registration: CollectionRegistration) { this.registration = registration }
            override fun getAggregationTemporality(instrumentType: InstrumentType) = AggregationTemporality.CUMULATIVE
            override fun forceFlush() = CompletableResultCode.ofSuccess()
            override fun shutdown() = CompletableResultCode.ofSuccess()
        }
        val provider = SdkMeterProvider.builder().setClock(object : Clock {
            override fun now() = (current?.time ?: session.startedAt) * 1_000_000
            override fun nanoTime() = System.nanoTime()
        }).setResource(Resource.create(Attributes.builder().put("device.id", session.deviceId)
            .put("monitoring.session.id", session.id).put("agent.version", BuildConfig.VERSION_NAME)
            .put("oem.provider", profile.provider).put("oem.provider.state", profile.state.name)
            .put("oem.license.record", profile.license).put("deployment.environment.name", environment).build()))
            .registerMetricReader(reader).build()
        try {
            val meter = provider.get("com.ahwotel.oem")
            fun attributes(row: OemObservation) = Attributes.builder().put("source", row.source.name)
                .put("measurement.scope", row.scope.name).put("quality", row.quality.name)
                .put("status", row.status.name).put("reason", row.reason.name).put("capability", row.metric.category.name).build()
            rows.map { it.metric }.distinct().forEach { metric ->
                meter.gaugeBuilder("agent.oem.${metric.wire}").setUnit(metric.unit).buildWithCallback { observer ->
                    current?.takeIf { it.metric == metric && it.status == OemStatus.AVAILABLE }?.let { row ->
                        row.number?.let { observer.record(it, attributes(row)) }
                    }
                }
            }
            meter.gaugeBuilder("agent.oem.capability").buildWithCallback { observer -> current?.let { row ->
                observer.record(1.0, attributes(row).toBuilder().put("metric", row.metric.wire).build())
            } }
            val metrics = rows.flatMap { row -> current = row; reader.registration.collectAllMetrics().toList() }
            return ByteArrayOutputStream().use { out -> MetricsRequestMarshaler.create(metrics).writeBinaryTo(out); out.toByteArray() }
        } finally { provider.close() }
    }
}
