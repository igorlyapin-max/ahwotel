package com.ahwotel

import com.ahwotel.oem.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/** Paged export under the caller's IO dispatcher. No arbitrary managed restrictions or identifiers. */
object OemExporter {
    suspend fun history(app: MonitorApp, output: OutputStream, spec: ExportSpec) {
        val dao = app.db.oemDao()
        output.bufferedWriter().use { w ->
            if (spec.json) {
                w.write("{\"schema\":1,\"kind\":\"ahwotel.oem\",\"from\":${spec.from},\"to\":${spec.to},\"profiles\":[")
                w.write(dao.profiles().joinToString(",") { it.payload })
                w.write("],\"observations\":[")
            } else w.write("session.id,device.id,timestamp,segment,metric,provider,source,scope,unit,value,status,reason,quality\n")
            var after = 0L; var first = true
            var session: SessionRow? = null
            while (true) {
                val page = dao.page(after, spec.from, spec.to, spec.session)
                if (page.isEmpty()) break
                for (row in page) {
                    if (session?.id != row.sessionId) session = app.db.dao().session(row.sessionId)
                    if (spec.json) {
                        if (!first) w.write(",")
                        w.write(JSONObject().put("session.id", row.sessionId).put("device.id", session?.deviceId ?: JSONObject.NULL)
                            .put("timestamp", row.time).put("segment", row.segment).put("metric", OemMetric.valueOf(row.metric).wire)
                            .put("provider", row.provider).put("source", row.source).put("scope", row.scope).put("unit", row.unit)
                            .put("number", row.number ?: JSONObject.NULL).put("text", row.text ?: JSONObject.NULL)
                            .put("status", row.status).put("reason", row.reason).put("quality", row.quality).toString())
                        first = false
                    } else if (OemMetric.valueOf(row.metric).kind in setOf(ValueKind.NUMBER, ValueKind.BOOLEAN)) {
                        w.write(listOf(row.sessionId, session?.deviceId.orEmpty(), row.time.toString(), row.segment.toString(),
                            OemMetric.valueOf(row.metric).wire, row.provider, row.source, row.scope, row.unit, row.number?.toString().orEmpty(),
                            row.status, row.reason, row.quality).joinToString(",", transform = Exporter::csv) + "\n")
                    }
                }
                after = page.last().id
            }
            if (spec.json) {
                w.write("],\"inventory\":["); after = 0; first = true
                while (true) {
                    val page = dao.inventoryPage(after, spec.from, spec.to, spec.session)
                    if (page.isEmpty()) break
                    page.forEach { row ->
                        if (!first) w.write(",")
                        w.write(JSONObject().put("session.id", row.sessionId).put("timestamp", row.time).put("provider", row.provider)
                            .put("complete", row.complete).put("entries", JSONArray(row.payload)).toString()); first = false
                    }
                    after = page.last().id
                }
                w.write("],\"events\":["); after = 0; first = true
                while (true) {
                    val page = dao.eventPage(after, spec.from, spec.to, spec.session)
                    if (page.isEmpty()) break
                    page.forEach { row ->
                        if (!first) w.write(",")
                        w.write(JSONObject().put("session.id", row.sessionId).put("timestamp", row.time).put("provider", row.provider)
                            .put("subject", row.subject).put("kind", row.kind).put("before", row.before ?: JSONObject.NULL)
                            .put("after", row.after ?: JSONObject.NULL).toString()); first = false
                    }
                    after = page.last().id
                }
                w.write("]}")
            }
        }
    }
}
