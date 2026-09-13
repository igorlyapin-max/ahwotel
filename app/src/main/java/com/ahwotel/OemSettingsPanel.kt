package com.ahwotel

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.ahwotel.oem.*

@Composable fun OemSettingsPanel(draft: Settings, managed: Set<String>, onChange: (Settings) -> Unit) {
    val oem = draft.oem
    fun set(value: OemSettings) = onChange(draft.copy(oem = value))
    Panel(stringResource(R.string.oem_title)) {
        Text(stringResource(R.string.oem_hint), style = MaterialTheme.typography.bodySmall)
        Toggle(stringResource(R.string.oem_enabled), oem.enabled, enabled = "oem_telemetry_enabled" !in managed) { set(oem.copy(enabled = it)) }
        Toggle(stringResource(R.string.oem_knox), oem.knox, enabled = "knox_telemetry_enabled" !in managed) { set(oem.copy(knox = it)) }
        Text(stringResource(R.string.oem_profile))
        Choice(oem.profile, listOf("balanced" to stringResource(R.string.oem_balanced), "custom" to stringResource(R.string.oem_custom)),
            enabled = "sampling_profile" !in managed) { set(oem.copy(profile = it)) }
        OemCategory.entries.filter { it != OemCategory.BATTERY }.forEach { category ->
            val locked = managed.any { ManagedConfiguration.categoryKey(it) == category }
            Toggle(oemCategoryTitle(category), category in oem.categories, enabled = !locked) {
                set(oem.copy(categories = if (it) oem.categories + category else oem.categories - category))
            }
            if (oem.profile == "custom") {
                // Empty/non-numeric values are rejected by Settings.valid() when saving.
                val text = oem.intervals.getValue(category).takeIf { it >= 0 }?.toString().orEmpty()
                Field(stringResource(R.string.oem_interval, oemCategoryTitle(category)), text) { value ->
                    set(oem.copy(intervals = oem.intervals + (category to (value.toIntOrNull() ?: -1))))
                }
            } else Text(stringResource(R.string.seconds, oem.seconds(category).toLong()), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.oem_interval_hint), style = MaterialTheme.typography.bodySmall)
        val upload = draft.uploadIntervalSeconds.takeIf { it >= 0 }?.toString().orEmpty()
        Field(stringResource(R.string.oem_upload_interval), upload, enabled = "upload_interval" !in managed) {
            onChange(draft.copy(uploadIntervalSeconds = it.toIntOrNull() ?: -1))
        }
        Field(stringResource(R.string.oem_environment), draft.backendEnvironment, enabled = "backend_environment" !in managed) {
            onChange(draft.copy(backendEnvironment = it))
        }
    }
}
