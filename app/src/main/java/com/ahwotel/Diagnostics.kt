package com.ahwotel

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

class Diagnostics(context: Context) {
    private val directory = File(context.filesDir, "logs").apply { mkdirs() }
    @Volatile var settings = Settings()
    @Volatile var lastEvent: String = ""
        private set

    /** Structured event codes and bounded numeric metadata only. Never raw intents/URLs/errors. */
    @Synchronized fun event(code: String, error: Boolean = false, diagnostic: Boolean = false,
        verbose: Boolean = false, number: Long? = null, source: SourceResult? = null, fallback: SourceId? = null,
        oem: com.ahwotel.oem.OemObservation? = null, timing: SessionTiming? = null, probeMetric: String? = null, probeStatus: String? = null) {
        val s = settings
        if (diagnostic && s.diagnostic == DiagnosticLevel.OFF) return
        if (verbose && (s.diagnostic != DiagnosticLevel.VERBOSE || System.currentTimeMillis() >= s.verboseUntil)) return
        require(code.matches(Regex("[a-z0-9_]+")))
        val json = JSONObject().put("time", System.currentTimeMillis()).put("event", code)
            .put("level", if (error) "ERROR" else "INFO").apply {
                number?.let { put("value", it) }
                probeMetric?.let { put("metric", it.take(100)) }; probeStatus?.let { put("status", it.take(80)) }
                timing?.let { put("timing_mode", it.mode.name); put("duration_seconds", it.durationSeconds) }
                source?.let {
                    put("metric", it.metric.wire); put("source", it.source.name.lowercase()); put("scope", it.scope.name.lowercase())
                    put("status", it.status.name); put("reason", it.reason.name.lowercase())
                }
                fallback?.let { put("fallback", it.name.lowercase()) }
                oem?.let {
                    put("metric", it.metric.wire); put("provider", it.provider); put("source", it.source.name)
                    put("scope", it.scope.name); put("status", it.status.name); put("reason", it.reason.name)
                    put("quality", it.quality.name)
                    if (it.provider == "samsung_knox") com.ahwotel.oem.KnoxCapabilityRegistry.find(it.metric)?.let { entry ->
                        put("api", "${entry.call.accessor}.${entry.call.method}")
                        put("minimum_android_sdk", entry.minimumAndroidSdk); put("minimum_knox_api", entry.minimumKnoxApi)
                        put("required_permission", entry.requiredPermission ?: JSONObject.NULL)
                        put("license_requirement", entry.licenseRequirement)
                    }
                }
            }.toString()
        lastEvent = code
        if (error) System.err.println(json) else System.out.println(json)
        if (error) Log.e("AHWOTel", json) else Log.i("AHWOTel", json)
        if (s.fileLogging) runCatching {
            val current = File(directory, "current.jsonl")
            if (current.length() > 1024 * 1024) {
                File(directory, "previous.jsonl").delete()
                current.renameTo(File(directory, "previous.jsonl"))
            }
            current.appendText(json + "\n")
        }.onFailure { Log.e("AHWOTel", "{\"event\":\"log_file_write_failed\"}") }
    }
    @Synchronized fun export(target: File) {
        target.outputStream().use { out ->
            listOf("previous.jsonl", "current.jsonl").map { File(directory, it) }.filter { it.exists() }
                .forEach { file -> file.inputStream().use { it.copyTo(out) } }
        }
    }
    fun bytes() = directory.listFiles()?.sumOf { it.length() } ?: 0
}
