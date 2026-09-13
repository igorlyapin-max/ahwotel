package com.ahwotel

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TransportTest {
    @Test fun oemWireRetainsEachTimestampAndNeverExportsTextOrUnsupportedNumericValues() {
        val time = 1_700_000_000_000L
        val session = SessionRow("oem-session", "oem-device", time, reason = "", configuration = "{}", continuous = false, durationSeconds = 300)
        val rows = listOf(
            com.ahwotel.oem.OemObservation(com.ahwotel.oem.OemMetric.MEMORY_PSS, "android_standard", com.ahwotel.oem.OemSource.ANDROID_STANDARD, com.ahwotel.oem.OemScope.AGENT_PROCESS, number = 1234.0, time = time + 10),
            com.ahwotel.oem.OemObservation(com.ahwotel.oem.OemMetric.CPU_ESTIMATE, "android_standard", com.ahwotel.oem.OemSource.DERIVED, com.ahwotel.oem.OemScope.AGENT_PROCESS, number = 150.0, time = time + 20),
            com.ahwotel.oem.OemObservation(com.ahwotel.oem.OemMetric.OEM_SYSTEM_CPU, "samsung_knox", com.ahwotel.oem.OemSource.OEM, com.ahwotel.oem.OemScope.DEVICE, status = com.ahwotel.oem.OemStatus.UNSUPPORTED, time = time + 30),
            com.ahwotel.oem.OemObservation(com.ahwotel.oem.OemMetric.MODEL, "android_standard", com.ahwotel.oem.OemSource.ANDROID_STANDARD, com.ahwotel.oem.OemScope.DEVICE, text = "PRIVATE_TEXT_VALUE", time = time + 40))
        val profile = com.ahwotel.oem.OemProviderProfile("android_standard", com.ahwotel.oem.ProviderState.DEGRADED, time, emptyMap())
        val payload = com.ahwotel.oem.OemTelemetry.encode(session, rows, profile, "test")
        assertFalse(payload.toString(Charsets.ISO_8859_1).contains("PRIVATE_TEXT_VALUE"))
        val metrics = fields(payload).filter { it.id == 1 }.flatMap { fields(it.bytes).filter { f -> f.id == 2 } }
            .flatMap { fields(it.bytes).filter { f -> f.id == 2 } }.map { fields(it.bytes) }
        val names = metrics.map { it.first { f -> f.id == 1 }.bytes.toString(Charsets.UTF_8) }
        assertFalse(names.contains("agent.oem.system.cpu.utilization"))
        assertFalse(names.contains("agent.oem.device.model"))
        for ((name, offset) in mapOf("agent.oem.agent.memory.pss" to 10, "agent.oem.agent.cpu.percent_estimate" to 20)) {
            val data = metrics.single { it.first { f -> f.id == 1 }.bytes.toString(Charsets.UTF_8) == name }
            val point = fields(data.first { it.id == 5 }.bytes).first { it.id == 1 }
            val actual = fields(point.bytes).first { it.id == 3 }.bytes
            assertEquals((time + offset) * 1_000_000, java.nio.ByteBuffer.wrap(actual).order(java.nio.ByteOrder.LITTLE_ENDIAN).long)
        }
    }
    @Test fun sendsSelfBatchWithGzipAndRetainsOriginalPayloadOnRetry() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setResponseCode(200)); server.start()
            val sender = OtlpSender(OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
            val raw = AgentTelemetryWire.encode(listOf(TelemetryRecord(sessionId="s",stream="self",metric="CPU_DELTA",
                time=1700000000000,start=1699999700000,durationMs=300000,segment=0,value=123.0,sum=123.0,count=5,
                source="process_api",metadata="{\"agent.build\":8}")))
            val payload = AgentTelemetryWire.gzip(raw)
            val row = OutboxRow(createdAt=1,endpoint=server.url("/v1/metrics").toString(),payload=payload,stream="self",compressed=true)
            assertEquals(503,sender.send(row)); assertEquals(200,sender.send(row.copy(attempts=1)))
            repeat(2) {
                val request=server.takeRequest()
                assertEquals("gzip",request.getHeader("Content-Encoding"))
                assertEquals("application/x-protobuf",request.getHeader("Content-Type"))
                assertNull(request.getHeader("Authorization"))
                val bytes=request.body.readByteArray(); assertArrayEquals(payload,bytes)
                assertArrayEquals(raw,java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes())
            }
        }
    }
    @Test fun sendsProtobufWithoutAuthorizationOverVerifiedTls() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            val sender = OtlpSender(OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
            val payload = byteArrayOf(10, 0)
            assertEquals(200, sender.send(OutboxRow(createdAt = 1, endpoint = server.url("/v1/metrics").toString(), payload = payload)))
            val request = server.takeRequest()
            assertEquals("application/x-protobuf", request.getHeader("Content-Type"))
            assertNull(request.getHeader("Authorization"))
            assertArrayEquals(payload, request.body.readByteArray())
        }
    }
    @Test fun rejectsUntrustedServerCertificate() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false); server.start()
            assertThrows(javax.net.ssl.SSLHandshakeException::class.java) {
                OtlpSender().send(OutboxRow(createdAt = 1, endpoint = server.url("/v1/metrics").toString(), payload = byteArrayOf(10, 0)))
            }
        }
    }
    @Test fun sdkSnapshotContainsDeviceSessionAndOriginalTimestamp() {
        val time = 1_700_000_000_123L
        val session = SessionRow("session-wire", "device-wire", time, reason = "test", configuration = "{}", continuous = false, durationSeconds = 300)
        Telemetry(session).use { telemetry ->
            val bytes = telemetry.encode(SampleRow(sessionId = session.id, time = time, elapsed = 1, segment = 0, cpu = 42.0, screenOn = true))
            val strings = bytes.toString(Charsets.ISO_8859_1)
            assertTrue(strings.contains("device-wire")); assertTrue(strings.contains("session-wire"))
            assertTrue(strings.contains("device.cpu.utilization"))
            val timestamp = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(time * 1_000_000).array()
            assertTrue(bytes.toList().windowed(8).any { it == timestamp.toList() })
        }
    }
    @Test fun probeMetricsCarryOwnTimestampWithoutSubstitutingSystemCpu() {
        val time = 1_700_000_010_000L
        val session = SessionRow("s", "d", time - 10000, reason = "", configuration = "{}", continuous = false, durationSeconds = 300)
        Telemetry(session).use { telemetry ->
            val payload = telemetry.encode(SampleRow(sessionId = "s", time = time, elapsed = 1, segment = 0, screenOn = true,
                memoryAvailable = 1000.0, cpuWait = 12.5, probeDelay = 25.0, probeTime = time - 321, probeInterval = 2000, probeSegment = 0))
            val metrics = fields(payload).filter { it.id == 1 }.flatMap { fields(it.bytes).filter { f -> f.id == 2 } }
                .flatMap { fields(it.bytes).filter { f -> f.id == 2 } }.associate { metric ->
                    val data = fields(metric.bytes)
                    data.first { it.id == 1 }.bytes.toString(Charsets.UTF_8) to data
                }
            assertFalse(metrics.containsKey("device.cpu.utilization"))
            for ((name, timestamp) in mapOf("agent.cpu.runqueue_wait" to time - 321, "agent.scheduling.delay" to time - 321,
                "device.memory.available_bytes" to time)) {
                val gauge = metrics[name]!!.first { it.id == 5 }
                val point = fields(gauge.bytes).first { it.id == 1 }
                val actual = fields(point.bytes).first { it.id == 3 }.bytes
                assertEquals(timestamp * 1_000_000, java.nio.ByteBuffer.wrap(actual).order(java.nio.ByteOrder.LITTLE_ENDIAN).long)
            }
        }
    }
    private data class Field(val id: Int, val bytes: ByteArray)
    private fun fields(bytes: ByteArray): List<Field> {
        var at = 0
        fun varint(): Long { var value = 0L; var shift = 0
            while (true) { val b = bytes[at++].toInt() and 255; value = value or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return value; shift += 7 }
        }
        val result = mutableListOf<Field>()
        while (at < bytes.size) {
            val tag = varint().toInt()
            val data = when (tag and 7) {
                0 -> { varint(); byteArrayOf() }
                1 -> bytes.copyOfRange(at, at + 8).also { at += 8 }
                2 -> { val n = varint().toInt(); bytes.copyOfRange(at, at + n).also { at += n } }
                5 -> bytes.copyOfRange(at, at + 4).also { at += 4 }
                else -> error("Invalid protobuf wire type")
            }
            result.add(Field(tag ushr 3, data))
        }
        return result
    }
}
