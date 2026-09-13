package com.ahwotel

import java.io.OutputStream
import org.json.JSONObject

data class ExportSpec(val session: String?, val from: Long, val to: Long, val json: Boolean)
object Exporter {
    suspend fun history(app: MonitorApp, output: OutputStream, spec: ExportSpec) {
        output.bufferedWriter().use { writer ->
            if (spec.json) writer.write("{\"version\":2,\"samples\":[")
            else writer.write((listOf("session.id", "device.id", "timestamp", "capabilities", "screenOn", "probe.timestamp", "probe.interval_ms", "probe.segment", "sources") + Metric.entries.map { it.wire }).joinToString(",") + "\n")
            var after = 0L
            var first = true
            var lastSession: SessionRow? = null
            while (true) {
                val page = app.db.dao().page(after, spec.session, spec.from, spec.to)
                if (page.isEmpty()) break
                for (row in page) {
                    if (lastSession?.id != row.sessionId) lastSession = app.db.dao().session(row.sessionId)
                    if (spec.json) {
                        if (!first) writer.write(",")
                        val json = JSONObject().put("session.id", row.sessionId).put("device.id", lastSession?.deviceId)
                            .put("timestamp", row.time).put("capabilities", row.capabilities).put("screenOn", row.screenOn)
                            .put("segment", row.segment).put("headroom.timestamp", row.headroomTime ?: JSONObject.NULL)
                            .put("probe.timestamp", row.probeTime ?: JSONObject.NULL).put("probe.interval_ms", row.probeInterval ?: JSONObject.NULL)
                            .put("probe.segment", row.probeSegment ?: JSONObject.NULL)
                            .put("sources", row.sources?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
                        Metric.entries.forEach { json.put(it.wire, it.value(row) ?: JSONObject.NULL) }
                        writer.write(json.toString())
                    } else {
                        val fields = listOf(row.sessionId, lastSession?.deviceId ?: "", row.time.toString(), row.capabilities, row.screenOn.toString(),
                            row.probeTime?.toString() ?: "", row.probeInterval?.toString() ?: "", row.probeSegment?.toString() ?: "", row.sources ?: "") +
                            Metric.entries.map { it.value(row)?.toString() ?: "" }
                        writer.write(fields.joinToString(",") { csv(it) } + "\n")
                    }
                    first = false
                    after = row.id
                }
            }
            if (spec.json) writer.write("]}")
        }
    }
    fun csv(value: String): String {
        val safe = if (value.toDoubleOrNull() == null && value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
