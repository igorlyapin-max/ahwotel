package com.ahwotel

import android.content.Context
import android.security.KeyChain
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.*

data class TlsSettings(val mode: String = "none", val alias: String = "", val trust: String = "system", val trustRef: String = "",
    val selection: String = "manual", val delegated: Boolean = false, val rule: CertificateRule = CertificateRule()) {
    fun valid() = mode in setOf("none", "mtls") && trust in setOf("system", "custom") &&
        selection in setOf("manual", "rule") && (mode == "none" || selection != "rule" || rule.valid()) && alias.length <= 256 && alias.none { it.isISOControl() } &&
        (trustRef.isEmpty() || trustRef.matches(Regex("[a-f0-9]{64}")))
}

class TlsFailure(val code: String) : Exception(code)

object TlsTrustStore {
    fun reference(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun certificates(bytes: ByteArray): List<X509Certificate> {
        require(bytes.size in 1..65536) { "tls_ca_invalid" }
        require(!bytes.toString(Charsets.ISO_8859_1).contains("PRIVATE KEY")) { "tls_ca_invalid" }
        return CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes))
            .map { it as X509Certificate }.also { certs -> require(certs.isNotEmpty() && certs.all { it.basicConstraints >= 0 }) { "tls_ca_invalid" } }
    }
    fun install(context: Context, bytes: ByteArray): String {
        certificates(bytes)
        val ref = reference(bytes)
        val directory = File(context.filesDir, "tls-trust").apply { mkdirs() }
        val target = File(directory, "$ref.pem")
        if (!target.exists()) {
            val temporary = File.createTempFile("ca-", ".tmp", directory)
            try { temporary.writeBytes(bytes); check(temporary.renameTo(target)) } finally { temporary.delete() }
        }
        return ref
    }
    fun read(context: Context, ref: String): ByteArray {
        if (!ref.matches(Regex("[a-f0-9]{64}"))) throw TlsFailure("tls_ca_missing")
        val file = File(context.filesDir, "tls-trust/$ref.pem")
        if (!file.isFile || file.length() > 65536) throw TlsFailure("tls_ca_missing")
        return file.readBytes().also { if (reference(it) != ref) throw TlsFailure("tls_ca_invalid") }
    }
}

internal fun validateClientCertificate(chain: Array<X509Certificate>, now: Date = Date()) {
    if (chain.isEmpty()) throw TlsFailure("tls_certificate_missing")
    try { chain.forEach { it.checkValidity(now) } } catch (_: Exception) { throw TlsFailure("tls_certificate_expired") }
    val leaf = chain.first()
    if (leaf.basicConstraints >= 0 || leaf.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.2") != true ||
        leaf.keyUsage?.let { it.isEmpty() || !it[0] } == true) throw TlsFailure("tls_certificate_usage")
    if (leaf.publicKey.algorithm !in setOf("RSA", "EC")) throw TlsFailure("tls_key_algorithm")
}

internal fun mtlsContext(key: PrivateKey, chain: Array<X509Certificate>, ca: ByteArray?): Pair<SSLContext, X509TrustManager> {
    validateClientCertificate(chain)
    // Prove that the key is usable by this app and matches the leaf, without exporting it.
    try {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val algorithm = if (chain.first().publicKey.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
        val signature = Signature.getInstance(algorithm).run { initSign(key); update(challenge); sign() }
        check(Signature.getInstance(algorithm).run { initVerify(chain.first()); update(challenge); verify(signature) })
    } catch (_: Exception) { throw TlsFailure("tls_key_access_denied") }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    val store = ca?.let { bytes -> KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null); TlsTrustStore.certificates(bytes).forEachIndexed { i, cert -> setCertificateEntry("ca-$i", cert) }
    } }
    tmf.init(store)
    val trust = tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
    val manager = object : X509ExtendedKeyManager() {
        private fun suitable(types: Array<out String>?, issuers: Array<out Principal>?) =
            types?.any { it == key.algorithm || it.startsWith(key.algorithm + "_") } == true &&
                (issuers.isNullOrEmpty() || chain.any { it.issuerX500Principal in issuers })
        override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = if (suitable(types, issuers)) "client" else null
        override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = if (suitable(types, issuers)) "client" else null
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = if (type != null && suitable(arrayOf(type), issuers)) arrayOf("client") else null
        override fun getCertificateChain(alias: String?) = if (alias == "client") chain else null
        override fun getPrivateKey(alias: String?) = if (alias == "client") key else null
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = null
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    }
    return SSLContext.getInstance("TLS").apply { init(arrayOf(manager), arrayOf(trust), null) } to trust
}

internal fun androidMtlsContext(context: Context, settings: TlsSettings): Pair<SSLContext, X509TrustManager> {
    val alias = resolveClientAlias(context, settings)
    return keyChainContext(context, settings, alias, true)
}

internal fun androidMtlsPreviewContext(context: Context, settings: TlsSettings): Pair<SSLContext, X509TrustManager> {
    check((context.applicationContext as? MonitorApp)?.managed?.active?.value != true)
    return keyChainContext(context, settings, settings.alias.ifBlank { throw TlsFailure("tls_key_missing") }, false)
}

private fun keyChainContext(context: Context, settings: TlsSettings, alias: String, cache: Boolean): Pair<SSLContext, X509TrustManager> {
    val chain = try { KeyChain.getCertificateChain(context, alias) } catch (_: Exception) { null }
        ?: throw TlsFailure("tls_certificate_missing")
    validateClientCertificate(chain)
    if (settings.selection == "rule") settings.rule.verify(chain.first())
    val key = try { KeyChain.getPrivateKey(context, alias) } catch (_: Exception) { null }
        ?: throw TlsFailure("tls_key_access_denied")
    return mtlsContext(key, chain, if (settings.trust == "custom") TlsTrustStore.read(context, settings.trustRef) else null).also {
        (context.applicationContext as? MonitorApp)?.let { app ->
            if (cache && settings.selection == "rule") {
                app.clientSelection = ClientSelection(settings, alias, chain)
                if (settings == app.settings.value.tls) app.tlsStatus.value = "tls_rule_ok"
            }
        }
    }
}
