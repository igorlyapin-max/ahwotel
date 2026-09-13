package com.ahwotel

import com.ahwotel.oem.OemCategory
import org.json.JSONObject
import java.security.MessageDigest

/** Provenance of the running collectors, not the editable next-session defaults. */
object MeasurementConfiguration {
    fun signature(start: Settings, live: Settings, now: Long): String {
        val self = ExtraSettingsCodec.self(live.selfTelemetry).apply {
            remove("debugUntil")
            put("fastSeconds", live.selfTelemetry.fast(now))
        }
        val categories = live.oem.categories.filter { it != OemCategory.BATTERY }.sortedBy { it.name }
        val json = JSONObject().put("hashVersion", 2)
            .put("interval", start.intervalMs)
            .put("collectors", start.enabled.filter { it != CollectorKind.BATTERY }.sortedBy { it.name }.joinToString())
            .put("indirectCpu", CollectorKind.CPU in start.enabled && start.indirectCpu)
            .put("thresholds", JSONObject(SettingsCodec.encode(start)).getJSONObject("thresholds"))
            .put("screenOff", live.collectScreenOff)
            .put("battery", ExtraSettingsCodec.battery(live.batterySettings)).put("self", self)
            .put("oem", JSONObject().put("enabled", live.oem.enabled).put("knox", live.oem.knox)
                .put("categories", categories.joinToString()).put("intervals", JSONObject().apply {
                    categories.forEach { put(it.name, live.oem.seconds(it)) }
                }))
            .put("otlp", live.otlpEnabled).put("uploadInterval", live.uploadIntervalSeconds)
            .put("retentionDays", live.retentionDays).put("storageMiB", live.storageMiB)
            .put("queueHours", live.queueHours).put("queueMiB", live.queueMiB)
        return MessageDigest.getInstance("SHA-256").digest(json.toString().toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
    }
}
