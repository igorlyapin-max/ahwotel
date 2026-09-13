package com.ahwotel

import org.json.JSONObject

/** Saved with the Activity while the system document picker owns the screen. */
data class PendingExport(val kind: String, val spec: ExportSpec) {
    fun encode(): String = JSONObject().put("kind", kind).put("session", spec.session ?: JSONObject.NULL)
        .put("from", spec.from).put("to", spec.to).put("json", spec.json).toString()
    companion object {
        fun decode(value: String): PendingExport = JSONObject(value).let { o ->
            val kind = o.getString("kind")
            require(kind in setOf("battery", "self", "oem"))
            PendingExport(kind, ExportSpec(if(o.isNull("session")) null else o.getString("session"),
                o.getLong("from"), o.getLong("to"), o.getBoolean("json")))
        }
    }
}
