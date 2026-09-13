package com.ahwotel

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val key = stringPreferencesKey("configuration")
    val changes: Flow<Settings> = context.dataStore.data.map { SettingsCodec.decode(it[key]) }
    suspend fun save(settings: Settings) {
        require(settings.valid()) { "invalid_configuration" }
        context.dataStore.edit { it[key] = SettingsCodec.encode(settings) }
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
        put("indirectCpu", s.indirectCpu)
        put("enabled", JSONArray(s.enabled.map { it.name }))
        put("retentionDays", s.retentionDays); put("storageMiB", s.storageMiB)
        put("otlpEnabled", s.otlpEnabled); put("endpoint", s.endpoint)
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
            indirectCpu = o.optBoolean("indirectCpu", true),
            enabled = o.getJSONArray("enabled").let { a -> (0 until a.length()).map { CollectorKind.valueOf(a.getString(it)) }.toSet() },
            retentionDays = o.getInt("retentionDays"), storageMiB = o.getInt("storageMiB"),
            otlpEnabled = o.getBoolean("otlpEnabled"), endpoint = o.getString("endpoint"),
            queueHours = o.getInt("queueHours"), queueMiB = o.getInt("queueMiB"),
            sotiEnabled = o.getBoolean("sotiEnabled"), deviceId = o.getString("deviceId"),
            diagnostic = DiagnosticLevel.valueOf(o.getString("diagnostic")), verboseUntil = o.getLong("verboseUntil"),
            fileLogging = o.getBoolean("fileLogging"),
            thresholds = Thresholds(list("cpu"), list("memory"), list("storage"), list("thermal"), t.getInt("raiseSeconds"), t.getInt("recoverSeconds")),
        ).also { require(it.valid()) { "invalid_stored_configuration" } }
    }
}
