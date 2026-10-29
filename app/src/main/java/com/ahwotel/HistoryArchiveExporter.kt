package com.ahwotel

import com.ahwotel.oem.OemMetric
import com.ahwotel.oem.ValueKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class HistoryExportGroup(val fileName: String) {
    DEVICE("device"), AGENT_SIGNALS("agent-signals"), PERFORMANCE("performance"),
    BATTERY("battery"), SELF_TELEMETRY("self-telemetry"), OEM("oem"),
}

data class HistoryArchiveSpec(
    val session: String?, val from: Long, val to: Long, val json: Boolean,
    val groups: Set<HistoryExportGroup>, val language: String, val timeZoneId: String,
    val capturedAt: Long = to,
    val sampleThroughId: Long = Long.MAX_VALUE,
    val agentThroughId: Long = Long.MAX_VALUE,
    val oemThroughId: Long = Long.MAX_VALUE,
    val oemInventoryThroughId: Long = Long.MAX_VALUE,
    val oemEventThroughId: Long = Long.MAX_VALUE,
) {
    init {
        require(from <= to); require(groups.isNotEmpty()); require(language in setOf("en", "ru"))
        require(TimeZone.getTimeZone(timeZoneId).id != "GMT" || timeZoneId in setOf("GMT", "UTC"))
        require(listOf(sampleThroughId, agentThroughId, oemThroughId, oemInventoryThroughId, oemEventThroughId).all { it >= 0 })
    }
    fun encode(): String = JSONObject().put("session", session ?: JSONObject.NULL)
        .put("from", from).put("to", to).put("json", json)
        .put("groups", JSONArray(HistoryExportGroup.entries.filter(groups::contains).map(HistoryExportGroup::name)))
        .put("language", language).put("timeZoneId", timeZoneId).put("capturedAt", capturedAt)
        .put("sampleThroughId", sampleThroughId).put("agentThroughId", agentThroughId)
        .put("oemThroughId", oemThroughId).put("oemInventoryThroughId", oemInventoryThroughId)
        .put("oemEventThroughId", oemEventThroughId).toString()

    companion object {
        fun decode(raw: String): HistoryArchiveSpec = JSONObject(raw).let { json ->
            val groups = json.getJSONArray("groups")
            HistoryArchiveSpec(
                if (json.isNull("session")) null else json.getString("session"), json.getLong("from"), json.getLong("to"),
                json.getBoolean("json"), (0 until groups.length()).map { HistoryExportGroup.valueOf(groups.getString(it)) }.toSet(),
                json.getString("language"), json.getString("timeZoneId"), json.optLong("capturedAt", json.getLong("to")),
                json.optLong("sampleThroughId", Long.MAX_VALUE), json.optLong("agentThroughId", Long.MAX_VALUE),
                json.optLong("oemThroughId", Long.MAX_VALUE), json.optLong("oemInventoryThroughId", Long.MAX_VALUE),
                json.optLong("oemEventThroughId", Long.MAX_VALUE),
            )
        }
    }
}

data class HistoryArchiveResult(val files: Map<String, Long>)

/** Presentation follows AHWOTel's selected language, never Android's unrelated system locale. */
class ExportPresentation(language: String, timeZoneId: String) {
    val delimiter = if (language == "ru") ';' else ','
    private val decimalSeparator = if (language == "ru") ',' else '.'
    private val formatter = SimpleDateFormat(
        if (language == "ru") "dd.MM.yyyy HH:mm:ss.SSS XXX" else "MM/dd/yyyy HH:mm:ss.SSS XXX",
        Locale.forLanguageTag(language),
    ).apply { timeZone = TimeZone.getTimeZone(timeZoneId) }
    fun datetime(value: Long): String = synchronized(formatter) { formatter.format(Date(value)) }
    fun number(value: Double): String = BigDecimal.valueOf(value).toPlainString().replace('.', decimalSeparator)
    fun row(vararg fields: Any?): String = row(fields.toList())
    fun row(fields: List<Any?>): String = fields.joinToString(delimiter.toString()) { field(it) } + "\n"
    private fun field(value: Any?): String = when (value) {
        null -> quote("")
        is Double -> quote(number(value))
        is Float -> quote(number(value.toDouble()))
        is Number -> quote(value.toString())
        else -> Exporter.csv(value.toString())
    }
    private fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
}

private data class OemCounts(val observations: Long, val inventory: Long, val events: Long, val profiles: Long) {
    val records get() = observations + inventory + events
    fun json() = JSONObject().put("observations", observations).put("inventory", inventory)
        .put("events", events).put("contextProfiles", profiles)
}

object HistoryArchiveExporter {
    suspend fun write(app: MonitorApp, output: OutputStream, spec: HistoryArchiveSpec,
        stagingDirectory: File = app.cacheDir): HistoryArchiveResult {
        val presentation = ExportPresentation(spec.language, spec.timeZoneId)
        val files = linkedMapOf<String, Long>(); val details = mutableMapOf<String, JSONObject>()
        ZipOutputStream(output.buffered()).use { zip ->
            for (group in HistoryExportGroup.entries) {
                if (group !in spec.groups) continue
                val name = "${group.fileName}.${if (spec.json) "json" else "csv"}"
                val staged = File.createTempFile("history-group-", ".tmp", stagingDirectory)
                try {
                    var oemCounts: OemCounts? = null
                    val count = FileOutputStream(staged).use { target -> when (group) {
                        HistoryExportGroup.DEVICE, HistoryExportGroup.AGENT_SIGNALS, HistoryExportGroup.PERFORMANCE ->
                            writeCore(app, target, spec, presentation, group)
                        HistoryExportGroup.BATTERY -> writeAgent(app, target, spec, presentation, true)
                        HistoryExportGroup.SELF_TELEMETRY -> writeAgent(app, target, spec, presentation, false)
                        HistoryExportGroup.OEM -> writeOem(app, target, spec, presentation).let { result ->
                            oemCounts = result; result.records
                        }
                    } }
                    if (count == 0L) continue
                    zip.putNextEntry(ZipEntry(name)); staged.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    files[name] = count
                    oemCounts?.let { details[name] = it.json() }
                } finally {
                    staged.delete()
                }
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            val manifest = JSONObject().put("schema", 2).put("kind", "ahwotel.history.archive")
                .put("format", if (spec.json) "json" else "csv").put("language", spec.language)
                .put("timeZone", spec.timeZoneId).put("from_datetime", presentation.datetime(spec.from))
                .put("to_datetime", presentation.datetime(spec.to)).put("snapshot_datetime", presentation.datetime(spec.capturedAt))
                .put("session.id", spec.session ?: JSONObject.NULL)
                .put("selectedGroups", JSONArray(HistoryExportGroup.entries.filter(spec.groups::contains).map { it.name.lowercase() }))
                .put("files", JSONArray(files.map { (name, count) -> JSONObject().put("name", name).put("records", count)
                    .apply { details[name]?.let { put("counts", it) } } }))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        return HistoryArchiveResult(files)
    }

    private fun coreMetrics(group: HistoryExportGroup) = Metric.entries.filter { metric -> when (group) {
        HistoryExportGroup.DEVICE -> metric.wire.startsWith("device.")
        HistoryExportGroup.AGENT_SIGNALS -> metric.wire.startsWith("agent.")
        HistoryExportGroup.PERFORMANCE -> metric.wire.startsWith("performance.")
        else -> false
    } }
    private fun relevant(row: SampleRow, metrics: List<Metric>) = metrics.any { metric ->
        metric.value(row) != null || metric.capability(row) != "NO_DATA" || sourceMentions(row.sources, metric)
    }
    private fun sourceMentions(raw: String?, metric: Metric) = runCatching {
        val sources = JSONArray(raw ?: "[]")
        (0 until sources.length()).any { index -> sources.getJSONArray(index).getJSONArray(3).let { names ->
            (0 until names.length()).any { names.getString(it) == metric.name }
        } }
    }.getOrDefault(false)

    private suspend fun writeCore(app: MonitorApp, out: OutputStream, spec: HistoryArchiveSpec,
        presentation: ExportPresentation, group: HistoryExportGroup): Long {
        val metrics = coreMetrics(group); var count = 0L; var after = 0L; var first = true; var session: SessionRow? = null
        out.bufferedWriter().use { writer ->
            if (spec.json) writer.write("{\"schema\":4,\"kind\":\"ahwotel.${group.fileName}\",\"samples\":[")
            else writer.write(presentation.row(listOf("session.id", "device.id", "datetime", "capabilities", "screenOn", "segment") +
                (if (group == HistoryExportGroup.DEVICE) listOf("headroom.datetime") else if (group == HistoryExportGroup.AGENT_SIGNALS)
                    listOf("probe.datetime", "probe.interval_ms", "probe.segment", "sources") else emptyList()) + metrics.map { it.wire }))
            while (true) {
                val page = app.db.dao().pageThrough(after, spec.sampleThroughId, spec.session, spec.from, spec.to)
                if (page.isEmpty()) break
                for (row in page) {
                    after = row.id; if (!relevant(row, metrics)) continue
                    if (session?.id != row.sessionId) session = app.db.dao().session(row.sessionId)
                    if (spec.json) {
                        if (!first) writer.write(",")
                        val json = JSONObject().put("session.id", row.sessionId).put("device.id", session?.deviceId ?: JSONObject.NULL)
                            .put("datetime", presentation.datetime(row.time)).put("capabilities", row.capabilities)
                            .put("screenOn", row.screenOn).put("segment", row.segment)
                        if (group == HistoryExportGroup.DEVICE) json.put("headroom.datetime", row.headroomTime?.let(presentation::datetime) ?: JSONObject.NULL)
                        if (group == HistoryExportGroup.AGENT_SIGNALS) json.put("probe.datetime", row.probeTime?.let(presentation::datetime) ?: JSONObject.NULL)
                            .put("probe.interval_ms", row.probeInterval ?: JSONObject.NULL).put("probe.segment", row.probeSegment ?: JSONObject.NULL)
                            .put("sources", row.sources?.let(::JSONArray) ?: JSONObject.NULL)
                        metrics.forEach { json.put(it.wire, it.value(row) ?: JSONObject.NULL) }; writer.write(json.toString())
                    } else {
                        val common = listOf(row.sessionId, session?.deviceId.orEmpty(), presentation.datetime(row.time), row.capabilities, row.screenOn, row.segment)
                        val extras = if (group == HistoryExportGroup.DEVICE) listOf(row.headroomTime?.let(presentation::datetime))
                            else if (group == HistoryExportGroup.AGENT_SIGNALS) listOf(row.probeTime?.let(presentation::datetime), row.probeInterval, row.probeSegment, row.sources)
                            else emptyList()
                        writer.write(presentation.row(common + extras + metrics.map { it.value(row) }))
                    }
                    count++; first = false
                }
            }
            if (spec.json) writer.write("]}")
        }
        return count
    }

    private suspend fun writeAgent(app: MonitorApp, out: OutputStream, spec: HistoryArchiveSpec,
        presentation: ExportPresentation, battery: Boolean): Long {
        var count = 0L; var after = 0L; var first = true
        out.bufferedWriter().use { writer ->
            if (spec.json) writer.write("{\"schema\":3,\"kind\":\"ahwotel.${if (battery) "battery" else "self-telemetry"}\",\"records\":[")
            else writer.write(presentation.row("session.id", "stream", "metric", "component", "datetime", "start_datetime", "duration_ms",
                "segment", "value", "min", "max", "sum", "count", "p50", "p95", "text", "unit", "status", "reason", "source", "quality", "resource"))
            while (true) {
                val rows = app.db.agentDao().pageThrough(after, spec.agentThroughId, spec.from, spec.to, spec.session)
                if (rows.isEmpty()) break
                for (row in rows) {
                    after = row.id; val metric = AgentMetric.valueOf(row.metric)
                    if (metric.battery != battery || !metric.acceptsStream(row.stream)) continue
                    val resource = normalizedResource(row.metadata, presentation)
                    if (spec.json) {
                        if (!first) writer.write(",")
                        writer.write(JSONObject().put("session.id", row.sessionId).put("stream", row.stream).put("metric", metric.wire)
                            .put("scope", metric.scope).put("unit", metric.unit).put("component", row.component)
                            .put("datetime", presentation.datetime(row.time)).put("start_datetime", presentation.datetime(row.start))
                            .put("duration_ms", row.durationMs).put("segment", row.segment).put("value", row.value ?: JSONObject.NULL)
                            .put("min", row.low ?: JSONObject.NULL).put("max", row.high ?: JSONObject.NULL).put("sum", row.sum ?: JSONObject.NULL)
                            .put("count", row.count).put("p50", row.p50 ?: JSONObject.NULL).put("p95", row.p95 ?: JSONObject.NULL)
                            .put("text", row.text ?: JSONObject.NULL).put("status", row.status).put("reason", row.reason)
                            .put("source", row.source).put("quality", row.quality).put("resource", resource).toString())
                    } else writer.write(presentation.row(row.sessionId, row.stream, metric.wire, row.component, presentation.datetime(row.time),
                        presentation.datetime(row.start), row.durationMs, row.segment, row.value, row.low, row.high, row.sum, row.count,
                        row.p50, row.p95, row.text, metric.unit, row.status, row.reason, row.source, row.quality, resource.toString()))
                    count++; first = false
                }
            }
            if (spec.json) writer.write("]}")
        }
        return count
    }

    private fun normalizedResource(raw: String, presentation: ExportPresentation) = JSONObject(raw).apply {
        if (has("source.checked_at") && !isNull("source.checked_at")) {
            put("source.checked_at_datetime", presentation.datetime(getLong("source.checked_at"))); remove("source.checked_at")
        }
    }
    private fun normalizedProfile(raw: String, presentation: ExportPresentation) = JSONObject(raw).apply {
        if (has("checkedAt") && !isNull("checkedAt")) {
            put("checkedAt_datetime", presentation.datetime(getLong("checkedAt"))); remove("checkedAt")
        }
    }

    private suspend fun writeOem(app: MonitorApp, out: OutputStream, spec: HistoryArchiveSpec,
        presentation: ExportPresentation): OemCounts {
        val dao = app.db.oemDao(); var observations = 0L; var inventory = 0L; var events = 0L
        var after = 0L; var first = true; var session: SessionRow? = null; val providers = linkedSetOf<String>()
        out.bufferedWriter().use { writer ->
            if (spec.json) writer.write("{\"schema\":3,\"kind\":\"ahwotel.oem\",\"observations\":[")
            else writer.write(presentation.row("session.id", "device.id", "datetime", "segment", "metric", "provider", "source", "scope", "unit", "value", "status", "reason", "quality"))
            while (true) {
                val page = dao.pageThrough(after, spec.oemThroughId, spec.from, spec.to, spec.session); if (page.isEmpty()) break
                for (row in page) {
                    after = row.id; val metric = OemMetric.valueOf(row.metric)
                    if (!spec.json && metric.kind !in setOf(ValueKind.NUMBER, ValueKind.BOOLEAN)) continue
                    providers += row.provider
                    if (session?.id != row.sessionId) session = app.db.dao().session(row.sessionId)
                    if (spec.json) {
                        if (!first) writer.write(",")
                        writer.write(JSONObject().put("session.id", row.sessionId).put("device.id", session?.deviceId ?: JSONObject.NULL)
                            .put("datetime", presentation.datetime(row.time)).put("segment", row.segment).put("metric", metric.wire)
                            .put("provider", row.provider).put("source", row.source).put("scope", row.scope).put("unit", row.unit)
                            .put("number", row.number ?: JSONObject.NULL).put("text", row.text ?: JSONObject.NULL)
                            .put("status", row.status).put("reason", row.reason).put("quality", row.quality).toString()); first = false
                    } else writer.write(presentation.row(row.sessionId, session?.deviceId, presentation.datetime(row.time), row.segment,
                        metric.wire, row.provider, row.source, row.scope, row.unit, row.number, row.status, row.reason, row.quality))
                    observations++
                }
            }
            if (spec.json) {
                writer.write("],\"inventory\":["); after = 0; first = true
                while (true) {
                    val page = dao.inventoryPageThrough(after, spec.oemInventoryThroughId, spec.from, spec.to, spec.session); if (page.isEmpty()) break
                    page.forEach { row -> if (!first) writer.write(","); providers += row.provider
                        writer.write(JSONObject().put("session.id", row.sessionId).put("datetime", presentation.datetime(row.time))
                            .put("provider", row.provider).put("complete", row.complete).put("entries", JSONArray(row.payload)).toString())
                        first = false; inventory++ }; after = page.last().id
                }
                writer.write("],\"events\":["); after = 0; first = true
                while (true) {
                    val page = dao.eventPageThrough(after, spec.oemEventThroughId, spec.from, spec.to, spec.session); if (page.isEmpty()) break
                    page.forEach { row -> if (!first) writer.write(","); providers += row.provider
                        writer.write(JSONObject().put("session.id", row.sessionId).put("datetime", presentation.datetime(row.time))
                            .put("provider", row.provider).put("subject", row.subject).put("kind", row.kind)
                            .put("before", row.before ?: JSONObject.NULL).put("after", row.after ?: JSONObject.NULL).toString())
                        first = false; events++ }; after = page.last().id
                }
                val profiles = dao.profiles().filter { it.provider in providers }.map { normalizedProfile(it.payload, presentation) }
                writer.write("],\"contextProfiles\":"); writer.write(JSONArray(profiles).toString()); writer.write("}")
                return OemCounts(observations, inventory, events, profiles.size.toLong())
            }
        }
        return OemCounts(observations, 0, 0, 0)
    }
}
