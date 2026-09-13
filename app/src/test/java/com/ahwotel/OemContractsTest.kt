package com.ahwotel

import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ahwotel.oem.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class OemContractsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun configuration(): ManagedConfiguration {
        context.getSharedPreferences("managed_configuration", 0).edit().clear().commit()
        return ManagedConfiguration(context, Diagnostics(context))
    }
    @Test fun overlayRejectsWholeInvalidUpdateAndRemovalRestoresLocalValues() {
        val managed = configuration()
        val local = Settings(deviceId = "test-device", oem = OemSettings(enabled = false), diagnostic = DiagnosticLevel.OFF)
        assertTrue(managed.replace(Bundle().apply { putBoolean("oem_telemetry_enabled", true); putBoolean("monitoring_enabled", false) }))
        val effective = managed.apply(local)
        assertTrue(effective.oem.enabled); assertFalse(effective.monitoringEnabled)
        assertEquals(local, managed.keepLocalManagedFields(effective, local))
        assertFalse(managed.replace(Bundle().apply { putBoolean("monitoring_enabled", true); putString("upload_interval", "60") }))
        assertEquals(effective, managed.apply(local))
        assertTrue(managed.replace(Bundle()))
        assertEquals(local, managed.apply(local))
        assertTrue(managed.keys.value.isEmpty())
    }
    @Test fun managedVerboseCannotRearmOnRestartAndUnknownSecretsAreRejected() {
        val managed = configuration()
        assertTrue(managed.replace(Bundle().apply { putString("debug_mode", "verbose"); putString("config_version", "v1") }))
        val local = Settings(deviceId = "test-device")
        val first = managed.apply(local)
        assertEquals(DiagnosticLevel.VERBOSE, first.diagnostic)
        assertEquals(first.verboseUntil, ManagedConfiguration(context, Diagnostics(context)).apply(local).verboseUntil)
        context.getSharedPreferences("managed_configuration", 0).edit().putLong("verboseUntil", 1).commit()
        val expired = ManagedConfiguration(context, Diagnostics(context))
        assertEquals(DiagnosticLevel.OFF, expired.apply(local).diagnostic)
        assertTrue(expired.replace(Bundle().apply { putString("config_version", "v1"); putString("debug_mode", "verbose") }))
        assertEquals(DiagnosticLevel.OFF, expired.apply(local).diagnostic)
        assertFalse(expired.replace(Bundle().apply { putString("token", "SECRET_DO_NOT_LOG") }))
        assertFalse(java.io.File(context.filesDir, "logs/current.jsonl").readText().contains("SECRET_DO_NOT_LOG"))
    }
    @Test fun oldSettingsKeepIdentityAndNewDefaultsAreOff() {
        val original = Settings(deviceId = "existing-device", language = "ru", retentionDays = 30)
        val json = JSONObject(SettingsCodec.encode(original))
        listOf("oem", "monitoringEnabled", "uploadIntervalSeconds", "backendEnvironment").forEach { json.remove(it) }
        assertEquals(original, SettingsCodec.decode(json.toString()))
        val custom = original.copy(oem = OemSettings(enabled = true, categories = setOf(OemCategory.NETWORK), profile = "custom"))
        assertEquals(custom, SettingsCodec.decode(SettingsCodec.encode(custom)))
        assertFalse(custom.copy(oem = custom.oem.copy(intervals = emptyMap())).valid())
    }
    @Test fun knoxDiscoveryClassifiesFailuresWithoutCallingThemZeroOrMissingLicense() {
        assertEquals(OemStatus.UNAVAILABLE to OemReason.LIBRARY_MISSING, classifyKnoxFailure(ClassNotFoundException()))
        assertEquals(OemStatus.UNSUPPORTED to OemReason.METHOD_MISSING, classifyKnoxFailure(NoSuchMethodException()))
        assertEquals(OemStatus.PERMISSION_DENIED to OemReason.PERMISSION, classifyKnoxFailure(InvocationTargetException(SecurityException("secret"))))
        val provider = SamsungKnoxProvider(context, object : KnoxBridge {
            override fun apiLevel() = 26
            override fun call(call: KnoxCall): Any? = when(call) {
                KnoxCall.INSTALL -> 1; KnoxCall.CAMERA -> throw SecurityException(); KnoxCall.PACKAGES -> null; else -> true
            }
        })
        val rows = provider.collect(OemCategory.POLICY).observations
        assertEquals("ALLOWED", rows.single { it.metric == OemMetric.INSTALLATION_POLICY }.text)
        assertNull(rows.single { it.metric == OemMetric.CAMERA_POLICY }.number)
        assertEquals(ProviderState.DEGRADED, providerState(rows.map { it.status }))
        assertEquals("3.2", provider.collect(OemCategory.DEVICE).observations.single { it.metric == OemMetric.KNOX_VERSION }.text)
        assertTrue(provider.collect(OemCategory.RESOURCES).observations.all { it.status == OemStatus.UNSUPPORTED && it.number == null })
        assertEquals(OemStatus.UNAVAILABLE, provider.collect(OemCategory.INVENTORY).observations.single().status)
    }
    @Test fun noKnoxLibraryDoesNotPreventAndroidCollection() {
        val knox = SamsungKnoxProvider(context, object : KnoxBridge {
            override fun apiLevel(): Int = throw ClassNotFoundException()
            override fun call(call: KnoxCall): Any? = error("must_not_call")
        })
        assertTrue(knox.collect(OemCategory.DEVICE).observations.all { it.status == OemStatus.UNAVAILABLE })
        val provider = AndroidStandardProvider(context)
        val first = provider.collect(OemCategory.RESOURCES).observations
        assertEquals(OemReason.BASELINE, first.single { it.metric == OemMetric.CPU_ESTIMATE }.reason)
        assertEquals(OemScope.AGENT_PROCESS, first.single { it.metric == OemMetric.CPU_TIME }.scope)
        assertEquals(OemStatus.UNSUPPORTED, first.single { it.metric == OemMetric.MEMORY_RSS }.status)
        assertTrue(provider.collect(OemCategory.DEVICE).observations.none { it.metric == OemMetric.KNOX_API })
    }
    @Test fun oemHistoryKeepsProviderAndNullGapsAndRetentionCoversEveryTable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MonitorDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.oemDao()
            fun reading(time: Long, value: Double?, provider: String = "android_standard", segment: Int = 0) = OemObservationRow.from(
                OemObservation(OemMetric.CPU_ESTIMATE, provider, OemSource.DERIVED, OemScope.AGENT_PROCESS, number = value,
                    status = if (value == null) OemStatus.UNAVAILABLE else OemStatus.AVAILABLE, time = time), "s", segment)
            db.dao().start(SessionRow("s", "device", 0, reason = "", configuration = "{}", continuous = true, durationSeconds = 300))
            db.dao().finish("s", 5, "test")
            dao.observations(listOf(reading(100, 10.0), reading(200, null), reading(300, 200.0, segment = 1), reading(100, 99.0, "samsung_knox")))
            val buckets = dao.chart(oemChartQuery(OemMetric.CPU_ESTIMATE, "android_standard", "s", 0, 24000))
            assertEquals(3, buckets.size); assertEquals(10.0, buckets.first().mean!!, 0.0); assertNull(buckets[1].mean)
            assertEquals(1, buckets.last().segment)
            dao.inventory(OemInventoryRow(sessionId = "s", time = 100, provider = "android_standard", complete = false, payload = "[]"))
            dao.events(listOf(OemEventRow(sessionId = "s", time = 100, provider = "android_standard", subject = "test", kind = "PACKAGE_ADDED", before = null, after = "[]")))
            dao.profile(OemProfileRow("android_standard", 100, "{}"))
            db.dao().expireSessions(1000); assertNotNull(db.dao().session("s"))
            assertEquals(4, dao.expireObservations(1000)); assertEquals(1, dao.expireInventory(1000))
            assertEquals(1, dao.expireEvents(1000)); assertEquals(1, dao.expireProfiles(1000))
            db.dao().expireSessions(1000); assertNull(db.dao().session("s"))
        } finally { db.close() }
    }
    @Test fun everyOemMetricHasEightResolvedSectionsInEachAppLanguage() {
        for (language in listOf("en", "ru")) {
            val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
            OemMetric.entries.forEach { metric ->
                for (section in listOf("meaning", "units", "impact", "reading", "example", "limits", "state", "source")) {
                    val id = oemStringId(localized, "oem_${metric.key}_$section")
                    assertTrue("${metric.name}:$section:$language", id != 0)
                    assertTrue(localized.getString(id).length > 20)
                }
            }
        }
    }
}
