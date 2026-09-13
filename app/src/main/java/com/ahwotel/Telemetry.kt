package com.ahwotel

import android.os.Build
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Synchronous SDK snapshot before persistence; retries send these exact protobuf bytes. */
class Telemetry(session: SessionRow) : AutoCloseable {
    private var current: SampleRow? = null
    private var probePhase = false
    private val reader = SnapshotReader()
    private val provider = SdkMeterProvider.builder().setClock(object : Clock {
        override fun now(): Long = ((if (probePhase) current?.probeTime else current?.time) ?: session.startedAt) * 1_000_000
        override fun nanoTime(): Long = System.nanoTime()
    }).setResource(Resource.create(Attributes.builder()
        .put("device.id", session.deviceId).put("device.manufacturer", Build.MANUFACTURER)
        .put("device.model", Build.MODEL).put("os.name", "Android").put("os.version", Build.VERSION.RELEASE)
        .put("agent.version", BuildConfig.VERSION_NAME).put("monitoring.session.id", session.id)
        .put("monitoring.reason", session.reason).build())).registerMetricReader(reader).build()
    private val meter = provider.meterBuilder("com.ahwotel.collectors").setInstrumentationVersion(BuildConfig.VERSION_NAME).build()
    private val instruments = Metric.entries.map { metric ->
        meter.gaugeBuilder(metric.wire).setUnit(metric.unit).buildWithCallback { observer ->
            if (metric.isProbe == probePhase) current?.let { metric.value(it) }?.takeIf { it.isFinite() }?.let {
                if (metric.isProbe) observer.record(it, Attributes.builder().put("measurement.scope", "probe_thread")
                    .put("source", if (metric == Metric.CPU_WAIT) "thread_schedstat" else "monotonic_timer").build())
                else observer.record(it)
            }
        }
    } + CollectorKind.entries.filter { it != CollectorKind.BATTERY }.map { kind ->
        meter.gaugeBuilder("device.${kind.name.lowercase()}.supported").buildWithCallback { observer ->
            val status = current?.capabilities?.split(';')?.find { it.startsWith("${kind.name}=") }?.substringAfter('=')
            if (!probePhase) when (status) {
                "UNSUPPORTED" -> observer.record(0.0)
                "AVAILABLE", "WARMING_UP" -> observer.record(1.0)
            }
        }
    }

    fun encode(sample: SampleRow): ByteArray {
        current = sample
        val probeNames = Metric.entries.filter { it.isProbe }.map { it.wire }.toSet()
        probePhase = false
        val normal = reader.registration.collectAllMetrics().filter { it.name !in probeNames }
        probePhase = true
        val probes = if (sample.probeTime != null) reader.registration.collectAllMetrics().filter { it.name in probeNames } else emptyList()
        probePhase = false
        // Pinned OTel 1.50.0 internal serializer, isolated here and covered by wire-format tests.
        return ByteArrayOutputStream().use { out ->
            MetricsRequestMarshaler.create(normal + probes).writeBinaryTo(out)
            out.toByteArray()
        }
    }
    override fun close() { instruments.forEach { it.close() }; provider.close() }
    private class SnapshotReader : MetricReader {
        lateinit var registration: CollectionRegistration
        override fun register(registration: CollectionRegistration) { this.registration = registration }
        override fun getAggregationTemporality(instrumentType: InstrumentType) = AggregationTemporality.CUMULATIVE
        override fun forceFlush() = CompletableResultCode.ofSuccess()
        override fun shutdown() = CompletableResultCode.ofSuccess()
    }
}

class OtlpSender(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    fun send(row: OutboxRow): Int {
        require(Settings.validEndpoint(row.endpoint))
        require(row.payload.size <= 1024 * 1024)
        val request = Request.Builder().url(row.endpoint)
            .apply { if (row.compressed) header("Content-Encoding", "gzip") }
            .post(row.payload.toRequestBody("application/x-protobuf".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            // A nonempty OTLP response may contain partial_success; do not silently call it success.
            if (response.isSuccessful && response.body?.source()?.exhausted() == false) 299 else response.code
        }
    }
    fun cancel() = client.dispatcher.cancelAll()
}
