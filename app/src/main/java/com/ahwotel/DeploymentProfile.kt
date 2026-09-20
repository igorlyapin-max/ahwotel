package com.ahwotel

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

enum class DesiredCollectionState { RUNNING, STOPPED }

data class DeploymentProfile(
    val profileId: String,
    val revision: Long,
    val desiredState: DesiredCollectionState,
    val settings: Settings,
    internal val fingerprint: String,
) {
    fun applyTo(local: Settings, verboseUntil: Long = 0): Settings = settings.copy(
        language = local.language,
        deviceId = local.deviceId,
        diagnostic = if (settings.diagnostic == DiagnosticLevel.VERBOSE && System.currentTimeMillis() >= verboseUntil)
            DiagnosticLevel.OFF else settings.diagnostic,
        verboseUntil = if (settings.diagnostic == DiagnosticLevel.VERBOSE) verboseUntil else 0,
        selfTelemetry = settings.selfTelemetry.copy(debugUntil = 0),
    ).also { require(it.valid()) { "invalid_configuration" } }
}

private sealed interface StrictJson {
    data class ObjectValue(val values: LinkedHashMap<String, StrictJson>) : StrictJson
    data class ArrayValue(val values: List<StrictJson>) : StrictJson
    data class StringValue(val value: String) : StrictJson
    data class NumberValue(val raw: String) : StrictJson
    data class BooleanValue(val value: Boolean) : StrictJson
    data object NullValue : StrictJson
}

/** Public, versioned deployment format. It intentionally excludes installation identity and runtime state. */
object DeploymentProfileCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_BYTES = 128 * 1024
    private val profileId = Regex("[A-Za-z0-9._-]{1,64}")
    private val topKeys = setOf("schemaVersion", "profileId", "revision", "desiredState", "auth", "settings")
    private val integerSettingFields = setOf(
        "currentSeconds", "wearSeconds", "passportSeconds", "uploadSeconds", "wearUploadSeconds",
        "fastSeconds", "mediumSeconds", "slowSeconds", "windowSeconds", "limitMiB", "warningSeconds",
        "criticalSeconds", "ioSeconds", "uploadIntervalSeconds", "intervalMs", "durationSeconds",
        "maxDurationSeconds", "retentionDays", "storageMiB", "queueHours", "queueMiB", "raiseSeconds",
        "recoverSeconds", "DEVICE", "BATTERY", "THERMAL", "INVENTORY", "SECURITY", "NETWORK",
    )
    private val integerPattern = Regex("-?(0|[1-9][0-9]*)")

    fun encode(settings: Settings, id: String, revision: Long,
        desiredState: DesiredCollectionState = DesiredCollectionState.STOPPED): String {
        require(id.matches(profileId)) { "invalid_profile_id" }
        require(revision in 1..Int.MAX_VALUE.toLong()) { "invalid_profile_revision" }
        validateDesiredState(desiredState, settings)
        val portable = portableSettings(settings)
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("profileId", id)
            .put("revision", revision)
            .put("desiredState", desiredState.name.lowercase())
            .put("auth", JSONObject().put("mode", "none"))
            .put("settings", portable)
            .toString(2)
            .also { require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "profile_too_large" } }
    }

    fun decode(raw: String): DeploymentProfile {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "profile_too_large" }
        val root = parseStrict(raw) as? StrictJson.ObjectValue ?: error("invalid_profile_json")
        require(root.values.keys == topKeys) { "invalid_profile_fields" }
        require(root.integer("schemaVersion") == SCHEMA_VERSION.toLong()) { "unsupported_profile_schema" }
        val id = root.string("profileId").also { require(it.matches(profileId)) { "invalid_profile_id" } }
        val revision = root.integer("revision").also { require(it in 1..Int.MAX_VALUE.toLong()) { "invalid_profile_revision" } }
        val desired = when (root.string("desiredState")) {
            "running" -> DesiredCollectionState.RUNNING
            "stopped" -> DesiredCollectionState.STOPPED
            else -> error("invalid_desired_state")
        }
        val auth = root.objectValue("auth")
        require(auth.values.keys == setOf("mode") && auth.string("mode") == "none") { "unsupported_auth_mode" }
        val portable = root.objectValue("settings")
        val template = parseStrict(portableSettings(Settings(deviceId = "template")).toString()) as StrictJson.ObjectValue
        validateShape(portable, template, "settings")
        val complete = JSONObject(toJson(portable))
            .put("language", "en")
            .put("deviceId", "profile-placeholder")
            .put("verboseUntil", 0)
        complete.getJSONObject("selfTelemetry").put("debugUntil", 0)
        val settings = SettingsCodec.decode(complete.toString())
        validateDesiredState(desired, settings)
        return DeploymentProfile(id, revision, desired, settings, fingerprint(root))
    }

    private fun validateDesiredState(desired: DesiredCollectionState, settings: Settings) {
        if (desired == DesiredCollectionState.RUNNING) {
            require(settings.continuous) { "managed_running_requires_continuous" }
            require(settings.monitoringEnabled && settings.hasCollectors()) { "managed_running_requires_collection" }
        }
    }

    fun read(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BYTES) { "profile_too_large" }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun portableSettings(source: Settings): JSONObject {
        val safe = source.copy(
            language = "en",
            deviceId = "profile-placeholder",
            diagnostic = DiagnosticLevel.OFF,
            verboseUntil = 0,
            selfTelemetry = source.selfTelemetry.copy(debugUntil = 0),
        )
        return JSONObject(SettingsCodec.encode(safe)).apply {
            remove("language")
            remove("deviceId")
            remove("verboseUntil")
            getJSONObject("selfTelemetry").remove("debugUntil")
        }
    }

    private fun validateShape(value: StrictJson, template: StrictJson, path: String) {
        when (template) {
            is StrictJson.ObjectValue -> {
                val actual = value as? StrictJson.ObjectValue ?: error("invalid_profile_settings")
                require(actual.values.keys == template.values.keys) { "invalid_profile_settings" }
                template.values.forEach { (key, expected) ->
                    validateShape(actual.values.getValue(key), expected, "$path.$key")
                }
            }
            is StrictJson.ArrayValue -> {
                val actual = value as? StrictJson.ArrayValue ?: error("invalid_profile_settings")
                require(actual.values.isNotEmpty() || template.values.isEmpty()) { "invalid_profile_settings" }
                val expected = template.values.firstOrNull()
                if (expected != null) actual.values.forEachIndexed { index, item ->
                    validateShape(item, expected, "$path[$index]")
                }
            }
            is StrictJson.StringValue -> require(value is StrictJson.StringValue) { "invalid_profile_settings" }
            is StrictJson.BooleanValue -> require(value is StrictJson.BooleanValue) { "invalid_profile_settings" }
            is StrictJson.NumberValue -> {
                val actual = value as? StrictJson.NumberValue ?: error("invalid_profile_settings")
                val field = path.substringAfterLast('.')
                if (field in integerSettingFields) require(actual.raw.matches(integerPattern)) { "invalid_profile_integer" }
                BigDecimal(actual.raw)
            }
            StrictJson.NullValue -> error("invalid_profile_settings")
        }
    }

    private fun parseStrict(raw: String): StrictJson {
        val reader = JsonReader(StringReader(raw)).apply { isLenient = false }
        val result = readValue(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT) { "trailing_profile_data" }
        reader.close()
        return result
    }

    private fun readValue(reader: JsonReader): StrictJson = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val values = linkedMapOf<String, StrictJson>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(name !in values) { "duplicate_profile_field" }
                values[name] = readValue(reader)
            }
            reader.endObject()
            StrictJson.ObjectValue(values)
        }
        JsonToken.BEGIN_ARRAY -> {
            val values = mutableListOf<StrictJson>()
            reader.beginArray()
            while (reader.hasNext()) values += readValue(reader)
            reader.endArray()
            StrictJson.ArrayValue(values)
        }
        JsonToken.STRING -> StrictJson.StringValue(reader.nextString())
        JsonToken.NUMBER -> StrictJson.NumberValue(reader.nextString())
        JsonToken.BOOLEAN -> StrictJson.BooleanValue(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); StrictJson.NullValue }
        else -> error("invalid_profile_json")
    }

    private fun StrictJson.ObjectValue.string(name: String) =
        (values[name] as? StrictJson.StringValue)?.value ?: error("invalid_profile_type")
    private fun StrictJson.ObjectValue.integer(name: String): Long {
        val raw = (values[name] as? StrictJson.NumberValue)?.raw ?: error("invalid_profile_type")
        require(raw.matches(integerPattern)) { "invalid_profile_integer" }
        return raw.toLong()
    }
    private fun StrictJson.ObjectValue.objectValue(name: String) =
        values[name] as? StrictJson.ObjectValue ?: error("invalid_profile_type")

    private fun fingerprint(root: StrictJson): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(root).toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun canonical(value: StrictJson): String = when (value) {
        is StrictJson.ObjectValue -> value.values.toSortedMap().entries.joinToString(",", "{", "}") {
            JSONObject.quote(it.key) + ":" + canonical(it.value)
        }
        is StrictJson.ArrayValue -> value.values.joinToString(",", "[", "]", transform = ::canonical)
        is StrictJson.StringValue -> JSONObject.quote(value.value)
        is StrictJson.NumberValue -> BigDecimal(value.raw).stripTrailingZeros().toPlainString()
        is StrictJson.BooleanValue -> value.value.toString()
        StrictJson.NullValue -> "null"
    }

    private fun toJson(value: StrictJson): String = when (value) {
        is StrictJson.ObjectValue -> value.values.entries.joinToString(",", "{", "}") {
            JSONObject.quote(it.key) + ":" + toJson(it.value)
        }
        is StrictJson.ArrayValue -> value.values.joinToString(",", "[", "]", transform = ::toJson)
        is StrictJson.StringValue -> JSONObject.quote(value.value)
        is StrictJson.NumberValue -> value.raw
        is StrictJson.BooleanValue -> value.value.toString()
        StrictJson.NullValue -> "null"
    }
}
