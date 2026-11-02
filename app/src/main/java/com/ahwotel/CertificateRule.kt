package com.ahwotel

import android.app.admin.DelegatedAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.security.KeyChain
import org.json.JSONObject
import java.math.BigInteger
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

data class CertificateRule(val templateOid: String = "", val policyOid: String = "", val sanUri: String = "",
    val issuer: String = "", val ou: String = "") {
    fun valid(): Boolean = listOf(templateOid, policyOid, sanUri, issuer, ou).all { it.length <= 512 && it.none(Char::isISOControl) } &&
        listOf(templateOid, policyOid).all { it.isEmpty() || validOid(it) } &&
        (templateOid.isNotBlank() || policyOid.isNotBlank() || sanUri.isNotBlank()) &&
        (sanUri.isEmpty() || runCatching { java.net.URI(sanUri).isAbsolute }.getOrDefault(false)) &&
        (issuer.isEmpty() || runCatching { X500Principal(issuer); true }.getOrDefault(false))
    fun checks(cert: X509Certificate): Map<String, Boolean> = linkedMapOf<String, Boolean>().apply {
        if (templateOid.isNotEmpty()) put("template", certificateTemplate(cert) == templateOid)
        if (policyOid.isNotEmpty()) put("policy", policyOid in certificatePolicies(cert))
        if (sanUri.isNotEmpty()) put("san", cert.subjectAlternativeNames?.any { it[0] == 6 && it[1] == sanUri } == true)
        if (issuer.isNotEmpty()) put("issuer", cert.issuerX500Principal == X500Principal(issuer))
        if (ou.isNotEmpty()) put("ou", ou in subjectOus(cert))
    }
    fun verify(cert: X509Certificate) {
        if (!valid() || runCatching { checks(cert).values.all { it } }.getOrDefault(false).not()) throw TlsFailure("tls_rule_mismatch")
    }
}

// Bounded DER reader: only definite canonical lengths, no recursive search through arbitrary extensions.
internal data class Der(val tag: Int, val value: ByteArray) {
    fun children(): List<Der> {
        var at = 0
        val result = mutableListOf<Der>()
        while (at < value.size) {
            val tag = value[at++].toInt() and 255
            require(at < value.size)
            var length = value[at++].toInt() and 255
            if (length >= 128) {
                val count = length and 127
                require(count in 1..3 && at + count <= value.size && value[at] != 0.toByte())
                length = 0
                repeat(count) { length = (length shl 8) or (value[at++].toInt() and 255) }
                require(length >= 128)
            }
            require(length <= value.size - at)
            result.add(Der(tag, value.copyOfRange(at, at + length))); at += length
            require(result.size <= 1024)
        }
        return result
    }
    fun oid(): String {
        require(tag == 6 && value.isNotEmpty() && value.size <= 256)
        val parts = mutableListOf<BigInteger>(); var current = BigInteger.ZERO; var start = true
        value.forEach { byte ->
            val n = byte.toInt() and 255
            require(!start || n != 128)
            current = current.shiftLeft(7).or(BigInteger.valueOf((n and 127).toLong()))
            start = n < 128
            if (start) { parts.add(current); current = BigInteger.ZERO }
        }
        require(start && parts.isNotEmpty())
        val first = parts.removeAt(0)
        val root = if (first < BigInteger.valueOf(40)) 0 else if (first < BigInteger.valueOf(80)) 1 else 2
        return (listOf(root.toString(), (first - BigInteger.valueOf(root * 40L)).toString()) + parts.map { it.toString() }).joinToString(".")
    }
}
internal fun validOid(value: String): Boolean = runCatching {
    require(value.matches(Regex("[0-2](\\.(0|[1-9][0-9]*)){1,30}")))
    val p = value.split('.'); require(p[0] == "2" || p[1].toBigInteger() < BigInteger.valueOf(40)); true
}.getOrDefault(false)
private fun extension(cert: X509Certificate, id: String): Der? = cert.getExtensionValue(id)?.let {
    require(it.size <= 65536)
    val outer = Der(0, it).children().single(); require(outer.tag == 4)
    outer.children().single().also { node -> require(node.tag == 48) }
}
internal fun certificateTemplate(cert: X509Certificate): String? = extension(cert, "1.3.6.1.4.1.311.21.7")?.children()?.firstOrNull()?.oid()
internal fun certificatePolicies(cert: X509Certificate): List<String> = extension(cert, "2.5.29.32")?.children()?.map {
    require(it.tag == 48); it.children().first().oid()
} ?: emptyList()
private fun subjectOus(cert: X509Certificate): List<String> = Der(0, cert.subjectX500Principal.encoded).children().single().children().flatMap { set ->
    require(set.tag == 49)
    set.children().mapNotNull { seq ->
        require(seq.tag == 48); val pair = seq.children(); require(pair.size == 2)
        if (pair[0].oid() != "2.5.4.11") null else when (pair[1].tag) {
            12 -> pair[1].value.toString(Charsets.UTF_8)
            19, 22 -> pair[1].value.toString(Charsets.US_ASCII)
            30 -> pair[1].value.toString(Charsets.UTF_16BE)
            else -> throw IllegalArgumentException("unsupported_dn_encoding")
        }
    }
}

object TlsCodec {
    fun encode(t: TlsSettings, portable: Boolean = false) = JSONObject().put("mode", t.mode)
        .put("alias", if (portable && t.selection == "rule") "" else t.alias).put("trust", t.trust).put("trustRef", t.trustRef)
        .put("selection", t.selection).put("delegated", t.delegated)
        .put("rule", JSONObject().put("templateOid", t.rule.templateOid).put("policyOid", t.rule.policyOid)
            .put("sanUri", t.rule.sanUri).put("issuer", t.rule.issuer).put("ou", t.rule.ou))
    fun decode(o: JSONObject): TlsSettings {
        val r = o.optJSONObject("rule") ?: JSONObject()
        return TlsSettings(o.getString("mode"), o.getString("alias"), o.getString("trust"), o.getString("trustRef"),
            o.optString("selection", "manual"), o.optBoolean("delegated", false), CertificateRule(r.optString("templateOid"),
                r.optString("policyOid"), r.optString("sanUri"), r.optString("issuer"), r.optString("ou")))
    }
}

internal fun resolveClientAlias(context: Context, tls: TlsSettings): String {
    if (tls.selection == "manual") return tls.alias.ifBlank { throw TlsFailure("tls_key_missing") }
    val app = context.applicationContext as? MonitorApp ?: throw TlsFailure("tls_binding_missing")
    val alias = app.managed.clientAlias(tls) ?: throw TlsFailure("tls_binding_missing")
    val policy = context.getSystemService(DevicePolicyManager::class.java)
    return resolveBoundAlias(tls, alias, object : ClientCertificateAccess {
        override fun chain() = KeyChain.getCertificateChain(context, alias)
        override fun canGrant() = Build.VERSION.SDK_INT >= 30 && DevicePolicyManager.DELEGATION_CERT_SELECTION in policy.getDelegatedScopes(null, context.packageName)
        override fun grant() = if (Build.VERSION.SDK_INT >= 30) policy.grantKeyPairToApp(null, alias, context.packageName) else false
        override fun revoke() { if (Build.VERSION.SDK_INT >= 30) policy.revokeKeyPairFromApp(null, alias, context.packageName) }
    })
}

internal interface ClientCertificateAccess {
    fun chain(): Array<X509Certificate>?
    fun canGrant(): Boolean
    fun grant(): Boolean
    fun revoke()
}

internal fun resolveBoundAlias(tls: TlsSettings, alias: String, access: ClientCertificateAccess): String {
    val existing = runCatching { access.chain() }.getOrNull()
    if (existing == null && tls.delegated) {
        if (!access.canGrant()) throw TlsFailure("tls_delegation_missing")
        if (!access.grant()) throw TlsFailure("tls_key_access_denied")
        try {
            val chain = access.chain() ?: throw TlsFailure("tls_certificate_missing")
            validateClientCertificate(chain); tls.rule.verify(chain.first())
        } catch (e: Exception) { access.revoke(); throw e }
    } else {
        val chain = existing ?: throw TlsFailure("tls_key_access_denied")
        validateClientCertificate(chain); tls.rule.verify(chain.first())
    }
    return alias
}

internal data class ClientSelection(val tls: TlsSettings, val alias: String, val chain: Array<X509Certificate>)

@androidx.annotation.RequiresApi(29)
class TlsDelegatedReceiver : DelegatedAdminReceiver() {
    override fun onChoosePrivateKeyAlias(context: Context, intent: Intent, uid: Int, uri: Uri?, alias: String?): String? {
        if (uid != context.applicationInfo.uid) return null
        val app = context.applicationContext as MonitorApp
        return runCatching {
            val tls = app.settings.value.tls
            if (tls.mode != "mtls" || tls.selection != "rule" || !tls.delegated) return null
            // Broadcast callbacks must not bind KeyChain on the main thread. IO validation
            // populates this cache at startup/configuration changes and before every upload.
            val selected = app.clientSelection ?: return null
            if (selected.tls != tls || app.managed.clientAlias(tls) != selected.alias) return null
            validateClientCertificate(selected.chain); tls.rule.verify(selected.chain.first()); selected.alias
        }.getOrNull()
    }
}
