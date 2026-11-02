package com.ahwotel

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class MtlsTest {
    companion object {
        private lateinit var directory: File
        private lateinit var key: PrivateKey
        private lateinit var chain: Array<X509Certificate>
        private lateinit var ca: ByteArray
        @JvmStatic @BeforeClass fun credentials() {
            directory = Files.createTempDirectory("ahwotel-tls-test-").toFile()
            val generator = File("../scripts/mtls_lab.py").canonicalFile
            check(ProcessBuilder("python3", generator.path, directory.path, "127.0.0.1").inheritIO().start().waitFor() == 0)
            val password = directory.resolve("client-password.txt").readText().toCharArray()
            val store = KeyStore.getInstance("PKCS12").apply { directory.resolve("client.p12").inputStream().use { load(it, password) } }
            key = store.getKey("ahwotel-client", password) as PrivateKey
            chain = store.getCertificateChain("ahwotel-client").map { it as X509Certificate }.toTypedArray()
            ca = directory.resolve("ca.crt").readBytes()
        }
        @JvmStatic @AfterClass fun cleanup() { if (::directory.isInitialized) directory.deleteRecursively() }
    }

    private val mtls = TlsSettings("mtls", "logical-alias", "custom", "a".repeat(64))
    private fun server(): MockWebServer {
        val held = HeldCertificate.decode(directory.resolve("server.crt").readText() + directory.resolve("server.key").readText())
        val tls = HandshakeCertificates.Builder().heldCertificate(held).addTrustedCertificate(chain.last()).build()
        return MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); requireClientAuth(); start(InetAddress.getByName("0.0.0.0"), 0) }
    }
    private fun row(url: String) = OutboxRow(createdAt = 1, endpoint = url, payload = byteArrayOf(10, 0))
    private fun endpoint(server: MockWebServer) = server.url("/v1/metrics").newBuilder().host("127.0.0.1").build().toString()

    @Test fun mutualTlsDeliversProtobufAndServerSeesClientCertificate() {
        server().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            val sender = OtlpSender(identity = { _, _ -> mtlsContext(key, chain, ca) })
            assertEquals(200, sender.send(row(endpoint(server)), tls = mtls))
            val request = server.takeRequest()
            assertEquals("/v1/metrics", request.path)
            assertEquals("application/x-protobuf", request.getHeader("Content-Type"))
            assertEquals(chain.first(), request.handshake!!.peerCertificates.first())
        }
    }

    @Test fun noneDoesNotAccessClientKeyAndCannotPassMandatoryMtls() {
        server().use { server ->
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(chain.last()).build()
            val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            val sender = OtlpSender(client, identity = { _, _ -> error("None must not read KeyChain") })
            assertThrows(java.io.IOException::class.java) { sender.send(row(endpoint(server))) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun missingKeyAndHttpNeverDowngradeOrSend() {
        MockWebServer().use { server ->
            val sender = OtlpSender(identity = { _, _ -> throw TlsFailure("tls_key_access_denied") })
            assertEquals("tls_https_required", assertThrows(TlsFailure::class.java) {
                sender.send(row(server.url("/v1/metrics").toString()), allowHttp = true, tls = mtls)
            }.code)
            assertEquals("tls_key_access_denied", assertThrows(TlsFailure::class.java) {
                sender.send(row("https://localhost:${server.port}/v1/metrics"), tls = mtls)
            }.code)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun unknownServerCaIsRejected() {
        server().use { server ->
            val other = HeldCertificate.Builder().certificateAuthority(0).commonName("Other CA").build().certificatePem().toByteArray()
            val sender = OtlpSender(identity = { _, _ -> mtlsContext(key, chain, other) })
            assertEquals("tls_handshake_failed", assertThrows(TlsFailure::class.java) {
                sender.send(row(endpoint(server)), tls = mtls)
            }.code)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun hostnameIsAlwaysVerified() {
        server().use { server ->
            val sender = OtlpSender(identity = { _, _ -> mtlsContext(key, chain, ca) })
            assertEquals("tls_hostname_failed", assertThrows(TlsFailure::class.java) {
                sender.send(row(server.url("/v1/metrics").newBuilder().host("127.0.0.2").build().toString()), tls = mtls)
            }.code)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun renewalReloadsIdentityOnEveryRequestAndKeepsNoConnection() {
        server().use { server ->
            var reads = 0
            val sender = OtlpSender(identity = { _, _ -> reads++; if (reads == 2) throw TlsFailure("tls_key_access_denied") else mtlsContext(key, chain, ca) })
            server.enqueue(MockResponse())
            val row = row(endpoint(server))
            assertEquals(200, sender.send(row, tls = mtls))
            assertThrows(TlsFailure::class.java) { sender.send(row, tls = mtls) }
            assertEquals(2, reads); assertEquals(1, server.requestCount)
        }
    }

    @Test fun invalidUsageValidityAndMismatchedKeyAreRejected() {
        assertEquals("tls_certificate_usage", assertThrows(TlsFailure::class.java) { validateClientCertificate(arrayOf(chain.last())) }.code)
        val serverCert = HeldCertificate.decode(directory.resolve("server.crt").readText() + directory.resolve("server.key").readText())
        assertEquals("tls_certificate_usage", assertThrows(TlsFailure::class.java) { validateClientCertificate(arrayOf(serverCert.certificate)) }.code)
        assertEquals("tls_certificate_expired", assertThrows(TlsFailure::class.java) {
            validateClientCertificate(chain, Date(chain.first().notAfter.time + 1000))
        }.code)
        assertEquals("tls_certificate_expired", assertThrows(TlsFailure::class.java) {
            validateClientCertificate(chain, Date(chain.first().notBefore.time - 1000))
        }.code)
        assertEquals("tls_key_access_denied", assertThrows(TlsFailure::class.java) { mtlsContext(serverCert.keyPair.private, chain, ca) }.code)
    }

    @Test fun cancellationDuringKeyLookupPreventsALaterRequestWithOldConfiguration() {
        server().use { server ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val sender = OtlpSender(identity = { _, _ ->
                entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                mtlsContext(key, chain, ca)
            })
            try {
                val job = executor.submit<Int> { sender.send(row(endpoint(server)), tls = mtls) }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                sender.cancel(); release.countDown()
                val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { job.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals("otlp_request_cancelled", failure.cause!!.message)
                assertEquals(0, server.requestCount)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun identityCommitBlocksNewSendUntilBindingIsPersisted() {
        server().use { server ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            val current = java.util.concurrent.atomic.AtomicReference("old")
            val sender = OtlpSender(identity = { _, _ ->
                assertEquals("new", current.get()); mtlsContext(key, chain, ca)
            })
            try {
                val update = executor.submit { sender.updateIdentity {
                    entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); current.set("new")
                } }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                server.enqueue(MockResponse())
                val send = executor.submit<Int> { sender.send(row(endpoint(server)), tls = mtls) }
                assertThrows(java.util.concurrent.TimeoutException::class.java) { send.get(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
                release.countDown(); update.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(200, send.get(5, java.util.concurrent.TimeUnit.SECONDS).toInt())
                assertThrows(IllegalStateException::class.java) { sender.updateIdentity { error("commit_failed") } }
                assertEquals("new", current.get())
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun extractionPreservesCurrentEditsAndRejectsChangedCertificateOrRule() {
        val original = TlsSettings("mtls", "reference", selection="rule", rule=CertificateRule(sanUri="urn:old"))
        val current = Settings(tls=original.copy(delegated=true), endpoint="https://new.example/v1/metrics")
        val extracted = CertificateRule(policyOid="1.2.3")
        val result = requireNotNull(applyExtractedRule(current,original,extracted))
        assertTrue(result.tls.delegated); assertEquals(current.endpoint,result.endpoint)
        assertEquals(extracted,result.tls.rule)
        for(tls in listOf(current.tls.copy(alias="other"), current.tls.copy(mode="none"),
            current.tls.copy(selection="manual"), current.tls.copy(rule=CertificateRule(sanUri="urn:edited")))) {
            assertNull(applyExtractedRule(current.copy(tls=tls),original,extracted))
        }
    }

    @Test fun profileAndSettingsRoundTripWithSeparatePublicCaProvisioning() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("managed_configuration", 0).edit().clear().commit()
        val ref = TlsTrustStore.reference(ca)
        val settings = Settings(deviceId = "source", otlpEnabled = true, endpoint = "https://collector.example/v1/metrics", tls = mtls.copy(trustRef = ref))
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
        val raw = DeploymentProfileCodec.encode(settings, "fleet", 1)
        assertFalse(raw.contains("BEGIN CERTIFICATE")); assertFalse(JSONObject(raw).getJSONObject("settings").has("tls"))
        assertEquals(settings.tls, DeploymentProfileCodec.decode(raw).applyTo(Settings(deviceId = "local")).tls)
        val managed = ManagedConfiguration(context, Diagnostics(context))
        val bundle = Bundle().apply {
            putString("management_action", "apply"); putString("deployment_profile", raw)
            putString("tls_ca_bundle", JSONObject().put("ref", ref).put("pem", ca.toString(Charsets.UTF_8)).toString())
        }
        assertTrue(managed.replace(bundle)); assertArrayEquals(ca, TlsTrustStore.read(context, ref))
        assertEquals(settings.tls, managed.apply(Settings(deviceId = "local")).tls)
        bundle.putString("tls_ca_bundle", JSONObject().put("ref", "b".repeat(64)).put("pem", ca.toString(Charsets.UTF_8)).toString())
        assertFalse(managed.replace(bundle)); assertEquals(settings.tls, managed.apply(Settings(deviceId = "local")).tls)
        assertThrows(IllegalArgumentException::class.java) { TlsTrustStore.certificates(chain.first().encoded) }
        assertThrows(IllegalArgumentException::class.java) { TlsTrustStore.certificates(ByteArray(65537)) }
        val old = JSONObject(SettingsCodec.encode(settings)).apply { remove("tls") }
        assertEquals(TlsSettings(), SettingsCodec.decode(old.toString()).tls)
    }
}
