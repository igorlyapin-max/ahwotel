package com.ahwotel

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.cert.X509Certificate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30], application=android.app.Application::class)
class CertificateRuleTest {
    @Test fun sharedPurposeMatchesDifferentUniqueIdentitiesAndGrantSucceeds() {
        val folders=mutableListOf<java.io.File>()
        try {
            val chains=(1..2).map {
                val folder=java.nio.file.Files.createTempDirectory("rule-identity-").toFile();folders.add(folder)
                assertEquals(0,ProcessBuilder("python3","../scripts/mtls_lab.py",folder.path,"127.0.0.1").start().waitFor())
                val password=folder.resolve("client-password.txt").readText().toCharArray()
                val store=java.security.KeyStore.getInstance("PKCS12").apply { folder.resolve("client.p12").inputStream().use { load(it,password) } }
                folder.resolve("template.ext").writeText("basicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=clientAuth\nsubjectAltName=URI:urn:ahwotel:test-device\ncertificatePolicies=1.2.3.4\n1.3.6.1.4.1.311.21.7=ASN1:SEQUENCE:template\n[template]\nid=OID:1.2.3.5\nmajor=INTEGER:1\nminor=INTEGER:0\n")
                assertEquals(0,ProcessBuilder("openssl","x509","-req","-in","client.csr","-CA","ca.crt","-CAkey","ca.key","-set_serial",it.toString(),"-days","1","-extfile","template.ext","-out","templated.crt").directory(folder).start().waitFor())
                val templated=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(folder.resolve("templated.crt").inputStream()) as X509Certificate
                CertificateRule(templateOid="1.2.3.5",policyOid="1.2.3.4",sanUri="urn:ahwotel:test-device",issuer=templated.issuerX500Principal.name).verify(templated)
                assertThrows(TlsFailure::class.java) { CertificateRule(templateOid="1.2.3.6").verify(templated) }
                assertThrows(TlsFailure::class.java) { CertificateRule(policyOid="1.2.3.6").verify(templated) }
                store.getCertificateChain("ahwotel-client").map { it as X509Certificate }.toTypedArray()
            }
            assertNotEquals(chains[0][0].serialNumber,chains[1][0].serialNumber)
            val rule=CertificateRule(sanUri="urn:ahwotel:test-device")
            chains.forEach { chain ->
                rule.verify(chain.first())
                assertThrows(TlsFailure::class.java) { rule.copy(issuer="CN=Wrong issuer").verify(chain.first()) }
                var granted=false
                val access=object:ClientCertificateAccess {
                    override fun chain()=if(granted) chain else null
                    override fun canGrant()=true
                    override fun grant():Boolean { granted=true;return true }
                    override fun revoke() { fail("matching identity must keep grant") }
                }
                assertEquals("unique-local",resolveBoundAlias(TlsSettings(selection="rule",delegated=true,rule=rule),"unique-local",access))
                assertTrue(granted)
            }
        } finally { folders.forEach { it.deleteRecursively() } }
    }
    @Test fun exactAndConditionsAndOidValidation() {
        val cert=HeldCertificate.Builder().commonName("individual-device").organizationalUnit("telemetry").addSubjectAlternativeName("host.example").build().certificate
        assertFalse(CertificateRule(ou="telemetry").valid())
        assertFalse(CertificateRule(templateOid="1.40.2").valid())
        assertTrue(CertificateRule(templateOid="2.999.123").valid())
        assertThrows(TlsFailure::class.java) { CertificateRule(policyOid="1.2.3",ou="telemetry").verify(cert) }
        assertThrows(TlsFailure::class.java) { CertificateRule(sanUri="https://host.example").verify(cert) } // DNS SAN is not URI SAN.
        assertThrows(IllegalArgumentException::class.java) { Der(0, byteArrayOf(48,128.toByte(),0,0)).children() }
        assertThrows(IllegalArgumentException::class.java) { Der(6,byteArrayOf(128.toByte(),0)).oid() }
        assertEquals("2.999.3",Der(6,byteArrayOf(136.toByte(),55,3)).oid())
    }

    @Test fun exportsOnlyPolicyAndAcceptsBindingWithoutChangingProfileRevision() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("managed_configuration",0).edit().clear().commit()
        val tls=TlsSettings("mtls","unique-reference-alias", selection="rule",delegated=true,rule=CertificateRule(templateOid="1.2.3"))
        val settings=Settings(deviceId="reference",tls=tls,endpoint="https://collector.example/v1/metrics",otlpEnabled=true)
        val raw=DeploymentProfileCodec.encode(settings,"fleet",1)
        assertFalse(raw.contains("unique-reference-alias")); assertEquals(4,JSONObject(raw).getInt("schemaVersion"))
        val profile=DeploymentProfileCodec.decode(raw)
        val managed=ManagedConfiguration(context,Diagnostics(context))
        assertTrue(managed.replace(Bundle().apply { putString("management_action","apply");putString("deployment_profile",raw) }))
        assertNull(managed.clientAlias(profile.settings.tls))
        fun bind(alias:String,revision:Int=1)=managed.replace(Bundle().apply {
            putString("tls_client_alias",alias);putString("tls_client_profile_id","fleet");putInt("tls_client_profile_revision",revision)
        })
        assertTrue(bind("device-one"));assertEquals("device-one",managed.clientAlias(profile.settings.tls))
        assertTrue(bind("renewed"));assertEquals("renewed",managed.clientAlias(profile.settings.tls))
        assertTrue(bind("stale",2));assertNull(managed.clientAlias(profile.settings.tls))
        assertFalse(bind("%CertificateTemplate_unexpanded%"))
        val old=JSONObject(SettingsCodec.encode(settings.copy(tls=TlsSettings("mtls","manual-key")))).getJSONObject("tls")
        old.remove("selection");old.remove("delegated");old.remove("rule")
        assertEquals("manual",TlsCodec.decode(old).selection)
    }

    @Test fun delegatedGrantIsRevokedWhenCertificateDoesNotMatch() {
        val cert=HeldCertificate.Builder().build().certificate
        var granted=false;var revoked=false
        val access=object:ClientCertificateAccess {
            override fun chain():Array<X509Certificate>? = if(granted) arrayOf(cert) else null
            override fun canGrant()=true
            override fun grant():Boolean { granted=true;return true }
            override fun revoke() { revoked=true }
        }
        assertThrows(TlsFailure::class.java) { resolveBoundAlias(TlsSettings(selection="rule",delegated=true,rule=CertificateRule(policyOid="1.2.3")),"bound-key",access) }
        assertTrue(granted);assertTrue(revoked)
        granted=false;revoked=false
        assertThrows(TlsFailure::class.java) { resolveBoundAlias(TlsSettings(selection="rule",rule=CertificateRule(policyOid="1.2.3")),"bound-key",access) }
        assertFalse(granted);assertFalse(revoked)
    }
}
