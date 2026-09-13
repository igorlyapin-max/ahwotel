package com.ahwotel.oem

/** The allowlist is also the capability contract. Missing policy/license information is unknown. */
object KnoxCapabilityRegistry {
    data class Entry(val id: String, val minimumAndroidSdk: Int, val minimumKnoxApi: Int,
        val requiredPermission: String?, val licenseRequirement: String, val requiredPolicy: String?, val call: KnoxCall)
    val entries = KnoxCall.entries.map { call -> Entry(call.metric.wire, 26, call.minimumApi,
        call.requiredPermission, if (call.licensed) "KNOX_AUTHORIZATION" else "NOT_DECLARED", null, call) }
    fun find(metric: OemMetric) = entries.find { it.call.metric == metric }
}
