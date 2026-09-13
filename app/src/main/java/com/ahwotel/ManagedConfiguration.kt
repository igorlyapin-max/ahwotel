package com.ahwotel

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import com.ahwotel.oem.OemCategory
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Only the declared primitive keys are accepted. Arbitrary app restrictions never enter logs/exports. */
class ManagedConfiguration(private val context: Context, private val logs: Diagnostics) {
    private val preferences = context.getSharedPreferences("managed_configuration", Context.MODE_PRIVATE)
    private var accepted = runCatching { JSONObject(preferences.getString("accepted", "{}")!!) }.getOrDefault(JSONObject())
    private var verboseUntil = preferences.getLong("verboseUntil", 0)
    val keys = MutableStateFlow(accepted.keys().asSequence().toSet())
    val version = MutableStateFlow(accepted.optString("config_version", ""))
    val rejected = MutableStateFlow(false)

    fun refresh(): Boolean = try { replace(context.getSystemService(RestrictionsManager::class.java).applicationRestrictions) }
        catch (_: Exception) { rejected.value = true; logs.event("managed_configuration_read_failed", error = true); false }
    fun replace(bundle: Bundle): Boolean {
        return try {
            val json = validate(bundle)
            if (json.toString() != accepted.toString()) {
                val nextExpiry = if (json.optString("debug_mode") == "verbose") System.currentTimeMillis() + 900_000 else 0
                check(preferences.edit().putString("accepted", json.toString()).putLong("verboseUntil", nextExpiry).commit())
                accepted = json
                verboseUntil = nextExpiry
            }
            keys.value = accepted.keys().asSequence().toSet(); version.value = accepted.optString("config_version", "")
            rejected.value = false
            logs.event("managed_configuration_applied", diagnostic = true)
            true
        } catch (_: Exception) {
            rejected.value = true; logs.event("managed_configuration_rejected", error = true); false
        }
    }

    fun apply(local: Settings): Settings {
        var s = local
        for (key in accepted.keys()) {
            s = when (key) {
                "monitoring_enabled" -> s.copy(monitoringEnabled = accepted.getBoolean(key))
                "oem_telemetry_enabled" -> s.copy(oem = s.oem.copy(enabled = accepted.getBoolean(key)))
                "knox_telemetry_enabled" -> s.copy(oem = s.oem.copy(knox = accepted.getBoolean(key)))
                "sampling_profile" -> s.copy(oem = s.oem.copy(profile = accepted.getString(key)))
                "upload_interval" -> s.copy(uploadIntervalSeconds = accepted.getInt(key))
                "backend_environment" -> s.copy(backendEnvironment = accepted.getString(key))
                "debug_mode" -> {
                    val level = DiagnosticLevel.valueOf(accepted.getString(key).uppercase())
                    s.copy(diagnostic = if (level == DiagnosticLevel.VERBOSE && System.currentTimeMillis() >= verboseUntil) DiagnosticLevel.OFF else level,
                        verboseUntil = if (level == DiagnosticLevel.VERBOSE) verboseUntil else 0)
                }
                else -> categoryKey(key)?.let { c -> s.copy(oem = s.oem.copy(categories = if (accepted.getBoolean(key)) s.oem.categories + c else s.oem.categories - c)) } ?: s
            }
        }
        return s.also { require(it.valid()) }
    }

    fun keepLocalManagedFields(next: Settings, old: Settings): Settings {
        val k = keys.value
        var categories = next.oem.categories
        k.mapNotNull(::categoryKey).forEach { c -> categories = if (c in old.oem.categories) categories + c else categories - c }
        return next.copy(monitoringEnabled = if ("monitoring_enabled" in k) old.monitoringEnabled else next.monitoringEnabled,
            oem = next.oem.copy(enabled = if ("oem_telemetry_enabled" in k) old.oem.enabled else next.oem.enabled,
                knox = if ("knox_telemetry_enabled" in k) old.oem.knox else next.oem.knox,
                profile = if ("sampling_profile" in k) old.oem.profile else next.oem.profile, categories = categories),
            uploadIntervalSeconds = if ("upload_interval" in k) old.uploadIntervalSeconds else next.uploadIntervalSeconds,
            backendEnvironment = if ("backend_environment" in k) old.backendEnvironment else next.backendEnvironment,
            diagnostic = if ("debug_mode" in k) old.diagnostic else next.diagnostic,
            verboseUntil = if ("debug_mode" in k) old.verboseUntil else next.verboseUntil)
    }

    companion object {
        private val boolKeys = setOf("monitoring_enabled", "oem_telemetry_enabled", "knox_telemetry_enabled", "application_inventory_enabled", "security_telemetry_enabled", "network_telemetry_enabled")
        private val stringKeys = setOf("sampling_profile", "debug_mode", "backend_environment", "config_version")
        fun categoryKey(key: String) = when(key) { "application_inventory_enabled" -> OemCategory.INVENTORY; "security_telemetry_enabled" -> OemCategory.SECURITY; "network_telemetry_enabled" -> OemCategory.NETWORK; else -> null }
        fun validate(bundle: Bundle): JSONObject {
            require(bundle.keySet().all { it in boolKeys || it in stringKeys || it == "upload_interval" })
            val json = JSONObject()
            for (key in bundle.keySet().sorted()) {
                val value = bundle.get(key)
                when {
                    key in boolKeys -> require(value is Boolean)
                    key == "upload_interval" -> require(value is Int && value in 0..86400)
                    key == "sampling_profile" -> require(value in setOf("balanced", "custom"))
                    key == "debug_mode" -> require(value in setOf("off", "basic", "verbose"))
                    key == "config_version" || key == "backend_environment" -> require(value is String && value.matches(Regex("[A-Za-z0-9._-]{1,64}")))
                }
                json.put(key, value)
            }
            return json
        }
    }
}
