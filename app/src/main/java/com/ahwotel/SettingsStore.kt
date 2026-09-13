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
