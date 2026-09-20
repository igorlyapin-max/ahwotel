package com.ahwotel

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.ahwotel.oem.OemCategory
import com.ahwotel.oem.OemSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DeploymentProfileTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun portableRoundTripPreservesPolicyAndKeepsInstallationIdentity() {
        val source = Settings(deviceId = "reference-device", language = "ru", continuous = true,
            intervalMs = 5000, retentionDays = 30, endpoint = "https://collector.example/v1/metrics",
            otlpEnabled = true, diagnostic = DiagnosticLevel.VERBOSE, verboseUntil = 999999,
            selfTelemetry = SelfTelemetrySettings(groups = setOf(SelfGroup.CPU, SelfGroup.STORAGE), debugUntil = 888888),
            oem = OemSettings(enabled = true, knox = false, categories = setOf(OemCategory.DEVICE, OemCategory.BATTERY)))
        val raw = DeploymentProfileCodec.encode(source, "fleet-a", 7)
        val root = JSONObject(raw)
        assertFalse(root.getJSONObject("settings").has("deviceId"))
        assertFalse(root.getJSONObject("settings").has("language"))
        assertFalse(root.getJSONObject("settings").has("verboseUntil"))
        assertFalse(root.getJSONObject("settings").getJSONObject("selfTelemetry").has("debugUntil"))
        assertEquals("none", root.getJSONObject("auth").getString("mode"))

        val parsed = DeploymentProfileCodec.decode(raw)
        val installed = parsed.applyTo(Settings(deviceId = "device-b", language = "en"))
        assertEquals("device-b", installed.deviceId)
        assertEquals("en", installed.language)
        assertEquals(5000, installed.intervalMs)
        assertEquals(30, installed.retentionDays)
        assertTrue(installed.otlpEnabled)
        assertEquals(DiagnosticLevel.OFF, installed.diagnostic)
        assertEquals(0, installed.selfTelemetry.debugUntil)
    }

    @Test fun rejectsUnknownDuplicateUnsupportedAuthAndTimedRunningProfile() {
        val raw = DeploymentProfileCodec.encode(Settings(deviceId = "reference"), "fleet", 1)
        assertThrows(IllegalArgumentException::class.java) {
            DeploymentProfileCodec.decode(JSONObject(raw).put("unknown", true).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeploymentProfileCodec.decode(raw.replaceFirst("\"profileId\": \"fleet\"", "\"profileId\":\"fleet\",\"profileId\":\"other\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeploymentProfileCodec.decode(JSONObject(raw).put("auth", JSONObject().put("mode", "mtls")).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeploymentProfileCodec.encode(Settings(deviceId = "reference", continuous = false), "fleet", 2, DesiredCollectionState.RUNNING)
        }
    }

    @Test fun strictDecoderRejectsCoercionNonStandardJsonAndTrailingData() {
        val raw = DeploymentProfileCodec.encode(Settings(deviceId = "reference"), "fleet", 3)
        val invalid = listOf(
            JSONObject(raw).put("schemaVersion", 1.9).toString(),
            JSONObject(raw).put("revision", 3.9).toString(),
            JSONObject(raw).apply { getJSONObject("settings").put("retentionDays", 14.9) }.toString(),
            JSONObject(raw).apply { getJSONObject("settings").put("monitoringEnabled", "true") }.toString(),
            JSONObject(raw).apply { getJSONObject("settings").put("monitoringEnabled", JSONObject.NULL) }.toString(),
            raw.replaceFirst("\"schemaVersion\"", "schemaVersion"),
            raw.replace('"', '\''),
            "$raw trailing",
        )
        invalid.forEach { candidate ->
            assertThrows(candidate.take(80), Exception::class.java) { DeploymentProfileCodec.decode(candidate) }
        }
    }

    @Test fun canonicalFingerprintIgnoresFormattingButNotContent() {
        val raw = DeploymentProfileCodec.encode(Settings(deviceId = "reference"), "fleet", 3)
        val root = JSONObject(raw)
        val reordered = JSONObject().put("settings",root.getJSONObject("settings")).put("auth",root.getJSONObject("auth"))
            .put("desiredState",root.getString("desiredState")).put("revision",root.getLong("revision"))
            .put("profileId",root.getString("profileId")).put("schemaVersion",root.getInt("schemaVersion")).toString()
        assertEquals(DeploymentProfileCodec.decode(raw).fingerprint, DeploymentProfileCodec.decode(reordered).fingerprint)
        val changed = JSONObject(raw).apply { getJSONObject("settings").put("retentionDays", 30) }.toString()
        assertNotEquals(DeploymentProfileCodec.decode(raw).fingerprint, DeploymentProfileCodec.decode(changed).fingerprint)
    }

    @Test fun importPreviewReportsMonitoringToggleAsSamplingChange() {
        val saved = Settings(deviceId="device",monitoringEnabled=true)
        assertEquals(listOf(ProfileChangeSection.SAMPLING),
            deploymentProfileChangedSections(saved,saved.copy(monitoringEnabled=false)))
    }

    @Test fun revisionFloorIsKeptPerProfileAndSameRevisionRetriesUntilMarkedApplied() {
        context.getSharedPreferences("managed_configuration", 0).edit().clear().commit()
        val managed = ManagedConfiguration(context, Diagnostics(context))
        fun apply(raw: String) = managed.replace(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "apply")
            putString(ManagedConfiguration.PROFILE_KEY, raw)
        })
        val a10 = DeploymentProfileCodec.encode(Settings(deviceId = "reference", retentionDays = 10), "A", 10)
        val b1 = DeploymentProfileCodec.encode(Settings(deviceId = "reference", retentionDays = 20), "B", 1)
        assertTrue(apply(a10))
        assertFalse(managed.isCurrentProfileApplied())
        assertTrue(apply(JSONObject(a10).toString()))
        assertEquals(ManagedTransition.APPLIED, managed.transition)
        assertFalse(managed.isCurrentProfileApplied())
        managed.markCurrentProfileApplied()
        assertTrue(managed.isCurrentProfileApplied())
        assertTrue(ManagedConfiguration(context, Diagnostics(context)).isCurrentProfileApplied())
        assertTrue(apply(JSONObject(a10).toString()))
        assertTrue(managed.isCurrentProfileApplied())
        assertTrue(apply(b1))
        assertFalse(apply(DeploymentProfileCodec.encode(Settings(deviceId = "reference"), "A", 1)))
        assertTrue(apply(a10))
        assertFalse(apply(DeploymentProfileCodec.encode(Settings(deviceId = "reference", retentionDays = 11), "A", 10)))
    }

    @Test fun managedProfilePersistsAcrossEmptyBundleAndRequiresMonotonicRelease() {
        context.getSharedPreferences("managed_configuration", 0).edit().clear().commit()
        val managed = ManagedConfiguration(context, Diagnostics(context))
        val raw = DeploymentProfileCodec.encode(Settings(deviceId = "reference", retentionDays = 30), "fleet", 4)
        assertTrue(managed.replace(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "apply")
            putString(ManagedConfiguration.PROFILE_KEY, raw)
        }))
        assertTrue(managed.active.value)
        assertEquals(ManagedTransition.APPLIED, managed.transition)
        assertEquals(30, managed.apply(Settings(deviceId = "unique", language = "ru")).retentionDays)
        assertEquals("unique", managed.apply(Settings(deviceId = "unique", language = "ru")).deviceId)

        assertTrue(managed.replace(Bundle()))
        assertTrue(managed.active.value)
        assertEquals(ManagedTransition.NONE, managed.transition)
        assertFalse(managed.replace(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "release")
            putString(ManagedConfiguration.PROFILE_ID_KEY, "fleet")
            putLong(ManagedConfiguration.REVISION_KEY, 4)
        }))
        assertFalse(managed.replace(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "release")
            putString(ManagedConfiguration.PROFILE_ID_KEY, "fleet")
            putDouble(ManagedConfiguration.REVISION_KEY, 5.9)
        }))
        assertTrue(managed.active.value)
        assertTrue(managed.replace(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "release")
            putString(ManagedConfiguration.PROFILE_ID_KEY, "fleet")
            putLong(ManagedConfiguration.REVISION_KEY, 5)
        }))
        assertFalse(managed.active.value)
        assertEquals(ManagedTransition.RELEASED, managed.transition)
        assertFalse(managed.replace(Bundle().apply {
            putString(ManagedConfiguration.ACTION_KEY, "apply")
            putString(ManagedConfiguration.PROFILE_KEY, raw)
        }))
    }
}
