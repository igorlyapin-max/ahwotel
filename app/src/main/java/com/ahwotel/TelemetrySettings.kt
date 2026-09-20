package com.ahwotel

import org.json.JSONArray
import org.json.JSONObject

enum class SelfGroup { CPU, MEMORY, NETWORK, RUNTIME, STORAGE, TASKS, BATTERY }
data class BatterySettings(val enabled: Boolean = true, val current: Boolean = true, val wear: Boolean = true,
    val passport: Boolean = true, val fallback: Boolean = true, val currentSeconds: Int = 60,
    val wearSeconds: Int = 86400, val passportSeconds: Int = 604800,
    val uploadSeconds: Int = 300, val wearUploadSeconds: Int = 86400, val extended: Boolean = true) {
    fun valid() = currentSeconds in 15..3600 && wearSeconds in 3600..604800 && passportSeconds in 86400..2592000 &&
        uploadSeconds in 300..604800 && wearUploadSeconds in 300..604800
}
data class SelfTelemetrySettings(val enabled: Boolean = true, val groups: Set<SelfGroup> = SelfGroup.entries.toSet(),
    val fastSeconds: Int = 60, val mediumSeconds: Int = 300, val slowSeconds: Int = 900,
    val uploadSeconds: Int = 300, val windowSeconds: Int = 300, val limitMiB: Int = 20,
    val warningSeconds: Int = 30, val criticalSeconds: Int = 120, val debugUntil: Long = 0,
    val processIo: Boolean = true, val systemIo: Boolean = true, val flashWear: Boolean = true, val ioSeconds: Int = 60) {
    fun valid() = fastSeconds in 15..3600 && mediumSeconds in 60..86400 && slowSeconds in 60..86400 &&
        uploadSeconds in 300..604800 && windowSeconds in 60..3600 && limitMiB in 10..50 &&
        warningSeconds in 1..3600 && criticalSeconds in (warningSeconds + 1)..7200 && debugUntil >= 0 && ioSeconds in 15..3600
    fun fast(now: Long) = if (now < debugUntil) 10 else fastSeconds
}
object ExtraSettingsCodec {
    fun battery(s: BatterySettings) = JSONObject().apply {
        put("enabled", s.enabled); put("current", s.current); put("wear", s.wear); put("passport", s.passport); put("fallback", s.fallback)
        put("currentSeconds", s.currentSeconds); put("wearSeconds", s.wearSeconds); put("passportSeconds", s.passportSeconds)
        put("uploadSeconds", s.uploadSeconds); put("wearUploadSeconds", s.wearUploadSeconds); put("extended",s.extended)
    }
    fun battery(o: JSONObject) = BatterySettings(o.getBoolean("enabled"), o.getBoolean("current"), o.getBoolean("wear"),
        o.getBoolean("passport"), o.getBoolean("fallback"), o.getInt("currentSeconds"), o.getInt("wearSeconds"),
        o.getInt("passportSeconds"), o.getInt("uploadSeconds"), o.getInt("wearUploadSeconds"),o.optBoolean("extended",true))
    fun self(s: SelfTelemetrySettings) = JSONObject().apply {
        put("enabled", s.enabled); put("groups", JSONArray(s.groups.sortedBy { it.name }.map { it.name }))
        put("fastSeconds", s.fastSeconds); put("mediumSeconds", s.mediumSeconds); put("slowSeconds", s.slowSeconds)
        put("uploadSeconds", s.uploadSeconds); put("windowSeconds", s.windowSeconds); put("limitMiB", s.limitMiB)
        put("warningSeconds", s.warningSeconds); put("criticalSeconds", s.criticalSeconds); put("debugUntil", s.debugUntil); put("processIo",s.processIo); put("systemIo",s.systemIo); put("flashWear",s.flashWear); put("ioSeconds",s.ioSeconds)
    }
    fun self(o: JSONObject) = SelfTelemetrySettings(o.getBoolean("enabled"), o.getJSONArray("groups").let { a ->
        (0 until a.length()).map { SelfGroup.valueOf(a.getString(it)) }.toSet() }, o.getInt("fastSeconds"), o.getInt("mediumSeconds"),
        o.getInt("slowSeconds"), o.getInt("uploadSeconds"), o.getInt("windowSeconds"), o.getInt("limitMiB"),
        o.getInt("warningSeconds"), o.getInt("criticalSeconds"), o.getLong("debugUntil"),o.optBoolean("processIo",true),o.optBoolean("systemIo",true),o.optBoolean("flashWear",true),o.optInt("ioSeconds",60))
}
