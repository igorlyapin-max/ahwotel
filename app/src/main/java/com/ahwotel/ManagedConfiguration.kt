package com.ahwotel

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import com.ahwotel.oem.OemCategory
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

enum class ManagedTransition { NONE, APPLIED, RELEASED }

/** Accepts either the legacy primitive overlay or one authoritative deployment profile. */
class ManagedConfiguration(private val context: Context, private val logs: Diagnostics) {
    private val preferences = context.getSharedPreferences("managed_configuration", Context.MODE_PRIVATE)
    private var accepted = runCatching { JSONObject(preferences.getString("accepted", "{}")!!) }.getOrDefault(JSONObject())
    private var profileRaw = preferences.getString("deploymentProfile", null)
    private var profile = profileRaw?.let { runCatching { DeploymentProfileCodec.decode(it) }.getOrNull() }
    private var verboseUntil = preferences.getLong("verboseUntil", 0)
    private var floors = loadFloors()
    var transition: ManagedTransition = ManagedTransition.NONE
        private set
    val active = MutableStateFlow(profileRaw != null)
    val keys = MutableStateFlow(if (profileRaw != null) setOf(PROFILE_KEY) else accepted.keys().asSequence().toSet())
    val version = MutableStateFlow(profile?.let { "${it.profileId}:${it.revision}" } ?: accepted.optString("config_version", ""))
    val profileId = MutableStateFlow(profile?.profileId.orEmpty())
    val revision = MutableStateFlow(profile?.revision ?: 0)
    val desiredState = MutableStateFlow(profile?.desiredState)
    val rejected = MutableStateFlow(profileRaw != null && profile == null)

    init {
        migrateLegacyFloor()
    }

    fun refresh(): Boolean = try {
        replace(context.getSystemService(RestrictionsManager::class.java).applicationRestrictions)
    } catch (_: Exception) {
        transition = ManagedTransition.NONE
        rejected.value = true
        logs.event("managed_configuration_read_failed", error = true)
        false
    }

    fun replace(bundle: Bundle): Boolean {
        transition = ManagedTransition.NONE
        return try {
            if (bundle.keySet().any { it in managementKeys }) replaceProfile(bundle) else replaceLegacy(bundle)
            publish()
            rejected.value = false
            logs.event("managed_configuration_applied", diagnostic = true)
            true
        } catch (_: Exception) {
            transition = ManagedTransition.NONE
            rejected.value = true
            logs.event("managed_configuration_rejected", error = true)
            false
        }
    }

    private fun replaceProfile(bundle: Bundle) {
        require(bundle.keySet().all { it in managementKeys }) { "mixed_managed_formats" }
        when (bundle.getString(ACTION_KEY)) {
            "apply" -> {
                require(bundle.keySet() == setOf(ACTION_KEY, PROFILE_KEY)) { "invalid_managed_apply" }
                val raw = bundle.getString(PROFILE_KEY) ?: error("missing_deployment_profile")
                val next = DeploymentProfileCodec.decode(raw)
                floors[next.profileId]?.let { floor ->
                    require(next.revision >= floor.revision) { "stale_profile_revision" }
                    if (next.revision == floor.revision) {
                        val migratedMatch = floor.fingerprint == LEGACY_FINGERPRINT &&
                            profile?.let { it.profileId == next.profileId && it.revision == next.revision } == true
                        require(next.fingerprint == floor.fingerprint || migratedMatch) { "changed_profile_revision" }
                    }
                }
                val sameCurrent = profile?.let { it.profileId == next.profileId && it.revision == next.revision &&
                    it.fingerprint == next.fingerprint } == true
                val nextExpiry = if (sameCurrent) verboseUntil else if (next.settings.diagnostic == DiagnosticLevel.VERBOSE)
                    System.currentTimeMillis() + 900_000 else 0
                if (floors[next.profileId] != RevisionFloor(next.revision, next.fingerprint)) {
                    floors[next.profileId] = RevisionFloor(next.revision, next.fingerprint)
                }
                val editor = preferences.edit().putString("deploymentProfile", raw)
                    .putString(FLOORS_KEY, encodeFloors()).putLong("verboseUntil", nextExpiry).putString("accepted", "{}")
                if (!sameCurrent) editor.remove(APPLIED_ID_KEY).remove(APPLIED_REVISION_KEY).remove(APPLIED_FINGERPRINT_KEY)
                check(editor.commit())
                accepted = JSONObject()
                profileRaw = raw
                profile = next
                verboseUntil = nextExpiry
                transition = ManagedTransition.APPLIED
            }
            "release" -> {
                require(bundle.keySet() == setOf(ACTION_KEY, PROFILE_ID_KEY, REVISION_KEY)) { "invalid_managed_release" }
                val current = profile ?: error("management_not_active")
                val id = bundle.getString(PROFILE_ID_KEY)
                val revisionValue = bundle.get(REVISION_KEY)
                require(revisionValue is Int || revisionValue is Long) { "invalid_managed_release" }
                val releaseRevision = (revisionValue as Number).toLong()
                val floor = floors[id]?.revision ?: 0
                require(id == current.profileId && releaseRevision > maxOf(current.revision, floor)) { "invalid_managed_release" }
                floors[id] = RevisionFloor(releaseRevision, RELEASE_FINGERPRINT)
                check(preferences.edit().remove("deploymentProfile").putString(FLOORS_KEY, encodeFloors())
                    .remove(APPLIED_ID_KEY).remove(APPLIED_REVISION_KEY).remove(APPLIED_FINGERPRINT_KEY)
                    .putLong("verboseUntil", 0).commit())
                profileRaw = null
                profile = null
                verboseUntil = 0
                transition = ManagedTransition.RELEASED
            }
            else -> error("invalid_management_action")
        }
    }

    private fun replaceLegacy(bundle: Bundle) {
        if (profileRaw != null && bundle.isEmpty) {
            require(profile != null) { "stored_managed_profile_invalid" }
            return
        }
        require(profileRaw == null) { "managed_profile_active" }
        val json = validate(bundle)
        if (json.toString() != accepted.toString()) {
            val nextExpiry = if (json.optString("debug_mode") == "verbose") System.currentTimeMillis() + 900_000 else 0
            check(preferences.edit().putString("accepted", json.toString()).putLong("verboseUntil", nextExpiry).commit())
            accepted = json
            verboseUntil = nextExpiry
        }
    }

    private fun publish() {
        active.value = profileRaw != null
        keys.value = if (profileRaw != null) setOf(PROFILE_KEY) else accepted.keys().asSequence().toSet()
        version.value = profile?.let { "${it.profileId}:${it.revision}" } ?: accepted.optString("config_version", "")
        profileId.value = profile?.profileId.orEmpty()
        revision.value = profile?.revision ?: 0
        desiredState.value = profile?.desiredState
    }

    fun apply(local: Settings): Settings {
        profile?.let { return it.applyTo(local, verboseUntil) }
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

    fun currentProfile(): DeploymentProfile? = profile

    fun isCurrentProfileApplied(): Boolean = profile?.let {
        preferences.getString(APPLIED_ID_KEY, null) == it.profileId &&
            preferences.getLong(APPLIED_REVISION_KEY, 0) == it.revision &&
            preferences.getString(APPLIED_FINGERPRINT_KEY, null) == it.fingerprint
    } == true

    fun markCurrentProfileApplied() {
        val current = requireNotNull(profile) { "management_not_active" }
        check(preferences.edit().putString(APPLIED_ID_KEY, current.profileId)
            .putLong(APPLIED_REVISION_KEY, current.revision)
            .putString(APPLIED_FINGERPRINT_KEY, current.fingerprint).commit())
    }

    fun markCurrentProfilePending() {
        if (profile == null) return
        check(preferences.edit().remove(APPLIED_ID_KEY).remove(APPLIED_REVISION_KEY)
            .remove(APPLIED_FINGERPRINT_KEY).commit())
    }

    private data class RevisionFloor(val revision: Long, val fingerprint: String)

    private fun loadFloors(): MutableMap<String, RevisionFloor> = runCatching {
        val root = JSONObject(preferences.getString(FLOORS_KEY, "{}")!!)
        root.keys().asSequence().associateWith { id ->
            val value = root.getJSONObject(id)
            RevisionFloor(value.getLong("revision"), value.getString("fingerprint"))
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun migrateLegacyFloor() {
        if (floors.isNotEmpty()) return
        val id = preferences.getString("lastProfileId", null)?.takeIf { it.isNotEmpty() } ?: return
        val revision = preferences.getLong("lastRevision", 0).takeIf { it > 0 } ?: return
        val fingerprint = profile?.takeIf { it.profileId == id && it.revision == revision }?.fingerprint
            ?: LEGACY_FINGERPRINT
        floors[id] = RevisionFloor(revision, fingerprint)
        preferences.edit().putString(FLOORS_KEY, encodeFloors()).remove("lastProfileId").remove("lastRevision").commit()
    }

    private fun encodeFloors(): String = JSONObject().apply {
        floors.toSortedMap().forEach { (id, floor) ->
            put(id, JSONObject().put("revision", floor.revision).put("fingerprint", floor.fingerprint))
        }
    }.toString()

    fun keepLocalManagedFields(next: Settings, old: Settings): Settings {
        if (profile != null) return next
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
        const val PROFILE_KEY = "deployment_profile"
        const val ACTION_KEY = "management_action"
        const val PROFILE_ID_KEY = "profile_id"
        const val REVISION_KEY = "profile_revision"
        private val managementKeys = setOf(PROFILE_KEY, ACTION_KEY, PROFILE_ID_KEY, REVISION_KEY)
        private const val FLOORS_KEY = "revisionFloors"
        private const val APPLIED_ID_KEY = "appliedProfileId"
        private const val APPLIED_REVISION_KEY = "appliedRevision"
        private const val APPLIED_FINGERPRINT_KEY = "appliedFingerprint"
        private const val RELEASE_FINGERPRINT = "released"
        private const val LEGACY_FINGERPRINT = "legacy"
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
