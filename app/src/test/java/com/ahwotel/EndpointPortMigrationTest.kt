package com.ahwotel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class EndpointPortMigrationTest {
    @Test fun persistedOldPortsRecoverAndRemainCorrectableAfterReopen() = runBlocking {
        for (port in listOf(0, 65536)) {
            val file = Files.createTempDirectory("endpoint-migration").resolve("settings.preferences_pb").toFile()
            val original = Settings(deviceId = "preserved", language = "ru", continuous = true, retentionDays = 21,
                otlpEnabled = true, endpoint = "https://collector:$port/v1/metrics")
            var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            store.edit { it[configurationKey] = JSONObject(SettingsCodec.encode(original)).apply { remove("allowHttp") }.toString() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            store = PreferenceDataStoreFactory.create(scope = scope, migrations = listOf(EndpointPortMigration), produceFile = { file })
            val loaded = store.data.first()
            assertEquals(original.copy(otlpEnabled = false), SettingsCodec.decode(loaded[configurationKey]))
            assertEquals(true, loaded[endpointRecoveryKey])
            assertFalse(EndpointPortMigration.shouldMigrate(loaded))
            val corrected = original.copy(endpoint = "https://collector:4318/v1/metrics")
            store.edit { it[configurationKey] = SettingsCodec.encode(corrected) }
            assertEquals(corrected, SettingsCodec.decode(store.data.first()[configurationKey]))
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun recoveryDoesNotHideOtherInvalidSettingsOrEndpoints() {
        val base = Settings(deviceId = "test", otlpEnabled = true, endpoint = "https://collector:0/v1/metrics")
        assertNull(EndpointPortMigration.repaired(SettingsCodec.encode(base.copy(retentionDays = 0))))
        for (endpoint in listOf("http://collector:0/v1/metrics", "https://u:p@collector:0/v1/metrics",
            "https://collector:0/other", "https://collector:4318/v1/metrics")) {
            assertNull(EndpointPortMigration.repaired(SettingsCodec.encode(base.copy(endpoint = endpoint))))
        }
        assertNull(EndpointPortMigration.repaired("broken"))
        assertNull(EndpointPortMigration.repaired(SettingsCodec.encode(base.copy(otlpEnabled = false))))
        assertFalse(base.valid())
    }
}
