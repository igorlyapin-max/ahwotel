package com.ahwotel

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal val configurationKey = stringPreferencesKey("configuration")
internal val endpointRecoveryKey = booleanPreferencesKey("endpoint_port_recovered")
internal val resumeBlockedKey = booleanPreferencesKey("resume_blocked")
private val Context.dataStore by preferencesDataStore("settings", produceMigrations = { listOf(EndpointPortMigration) })

/** Recover only the invalid HTTPS ports accepted by code11; never reset unrelated settings. */
internal object EndpointPortMigration : DataMigration<Preferences> {
    fun repaired(raw: String?): String? = runCatching {
        if (raw == null) return null
        val json = JSONObject(raw)
        if (!json.getBoolean("otlpEnabled")) return null
        val uri = URI(json.getString("endpoint"))
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || !uri.path.endsWith("/v1/metrics") ||
            (uri.port != 0 && uri.port <= 65535)) return null
        val repaired = json.put("otlpEnabled", false).toString()
        SettingsCodec.decode(repaired) // The rest of the configuration must still be valid.
        repaired
    }.getOrNull()

    override suspend fun shouldMigrate(currentData: Preferences) = repaired(currentData[configurationKey]) != null
    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        repaired(currentData[configurationKey])?.let {
            this[configurationKey] = it
            this[endpointRecoveryKey] = true
        }
    }
    override suspend fun cleanUp() = Unit
}

class SettingsStore internal constructor(internal val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)
    private val key = configurationKey
    val changes: Flow<Settings> = store.data.map { SettingsCodec.decode(it[key]) }
    val endpointRecovered: Flow<Boolean> = store.data.map { it[endpointRecoveryKey] == true }
    val resumeBlocked: Flow<Boolean> = store.data.map { it[resumeBlockedKey] != false }
    suspend fun setResumeBlocked(blocked: Boolean) { store.edit { it[resumeBlockedKey] = blocked } }
    suspend fun save(settings: Settings, blockResume: Boolean = false) {
        require(settings.valid()) { "invalid_configuration" }
        store.edit {
            it[key] = SettingsCodec.encode(settings)
            if (blockResume) it[resumeBlockedKey] = true
            if (Settings.validEndpoint(settings.endpoint, settings.allowHttp)) it.remove(endpointRecoveryKey)
        }
    }
}

object SettingsCodec {
    fun encode(s: Settings): String = JSONObject().apply {
        put("batterySettings", ExtraSettingsCodec.battery(s.batterySettings)); put("selfTelemetry", ExtraSettingsCodec.self(s.selfTelemetry))
        put("monitoringEnabled", s.monitoringEnabled); put("uploadIntervalSeconds", s.uploadIntervalSeconds); put("backendEnvironment", s.backendEnvironment)
        put("oem", JSONObject().apply {
            put("enabled", s.oem.enabled); put("knox", s.oem.knox); put("profile", s.oem.profile)
            put("categories", JSONArray(s.oem.categories.map { it.name }))
            put("intervals", JSONObject().apply { s.oem.intervals.forEach { (c, seconds) -> put(c.name, seconds) } })
        })
        put("language", s.language); put("intervalMs", s.intervalMs)
        put("durationSeconds", s.durationSeconds); put("maxDurationSeconds", s.maxDurationSeconds)
        put("continuous", s.continuous); put("collectScreenOff", s.collectScreenOff)
        put("resumeOnBoot", s.resumeOnBoot); put("resumeOnOpen", s.resumeOnOpen)
        put("indirectCpu", s.indirectCpu)
        put("enabled", JSONArray(s.enabled.map { it.name }))
        put("retentionDays", s.retentionDays); put("storageMiB", s.storageMiB)
        put("otlpEnabled", s.otlpEnabled); put("endpoint", s.endpoint)
        put("allowHttp", s.allowHttp)
        put("queueHours", s.queueHours); put("queueMiB", s.queueMiB)
        put("sotiEnabled", s.sotiEnabled); put("deviceId", s.deviceId)
        put("diagnostic", s.diagnostic.name); put("verboseUntil", s.verboseUntil)
        put("fileLogging", s.fileLogging)
        put("thresholds", JSONObject().apply {
            put("cpu", JSONArray(s.thresholds.cpu)); put("memory", JSONArray(s.thresholds.memory))
            put("storage", JSONArray(s.thresholds.storage)); put("thermal", JSONArray(s.thresholds.thermal))
            put("raiseSeconds", s.thresholds.raiseSeconds); put("recoverSeconds", s.thresholds.recoverSeconds)
        })
    }.toString()

    fun decode(json: String?): Settings {
        if (json == null) return Settings()
        val o = JSONObject(json)
        val t = o.getJSONObject("thresholds")
        fun list(name: String) = t.getJSONArray(name).let { a -> (0 until a.length()).map { a.getDouble(it) } }
        return Settings(
            batterySettings = o.optJSONObject("batterySettings")?.let(ExtraSettingsCodec::battery) ?: BatterySettings(enabled = o.getJSONArray("enabled").let { a -> (0 until a.length()).any { a.getString(it) == "BATTERY" } }),
            selfTelemetry = o.optJSONObject("selfTelemetry")?.let(ExtraSettingsCodec::self) ?: SelfTelemetrySettings(),
            monitoringEnabled = o.optBoolean("monitoringEnabled", true), uploadIntervalSeconds = o.optInt("uploadIntervalSeconds", 0),
            backendEnvironment = o.optString("backendEnvironment", "local"),
            oem = o.optJSONObject("oem")?.let { e -> com.ahwotel.oem.OemSettings(e.getBoolean("enabled"), e.getBoolean("knox"),
                e.getJSONArray("categories").let { a -> (0 until a.length()).map { com.ahwotel.oem.OemCategory.valueOf(a.getString(it)) }.toSet() },
                e.getString("profile"), com.ahwotel.oem.OemCategory.entries.associateWith { e.getJSONObject("intervals").getInt(it.name) })
            } ?: com.ahwotel.oem.OemSettings(),
            language = o.getString("language"), intervalMs = o.getLong("intervalMs"),
            durationSeconds = o.getLong("durationSeconds"), maxDurationSeconds = o.getLong("maxDurationSeconds"),
            continuous = o.getBoolean("continuous"), collectScreenOff = o.getBoolean("collectScreenOff"),
            resumeOnBoot = o.optBoolean("resumeOnBoot", false), resumeOnOpen = o.optBoolean("resumeOnOpen", false),
            indirectCpu = o.optBoolean("indirectCpu", true),
            enabled = o.getJSONArray("enabled").let { a -> (0 until a.length()).map { CollectorKind.valueOf(a.getString(it)) }.toSet() },
            retentionDays = o.getInt("retentionDays"), storageMiB = o.getInt("storageMiB"),
            otlpEnabled = o.getBoolean("otlpEnabled"), endpoint = o.getString("endpoint"),
            allowHttp = o.optBoolean("allowHttp", false),
            queueHours = o.getInt("queueHours"), queueMiB = o.getInt("queueMiB"),
            sotiEnabled = o.getBoolean("sotiEnabled"), deviceId = o.getString("deviceId"),
            diagnostic = DiagnosticLevel.valueOf(o.getString("diagnostic")), verboseUntil = o.getLong("verboseUntil"),
            fileLogging = o.getBoolean("fileLogging"),
            thresholds = Thresholds(list("cpu"), list("memory"), list("storage"), list("thermal"), t.getInt("raiseSeconds"), t.getInt("recoverSeconds")),
        ).also { require(it.valid()) { "invalid_stored_configuration" } }
    }
}
