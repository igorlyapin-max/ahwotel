package com.ahwotel

import androidx.annotation.StringRes

/** Resource IDs only: descriptions always follow the active application locale. */
data class MetricHelp(val sections: List<HelpSection>)
data class HelpSection(@StringRes val title: Int, @StringRes val text: Int)

fun metricHelp(metric: Metric): MetricHelp = when (metric) {
    Metric.CPU -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_cpu_meaning),
        HelpSection(R.string.help_section_units, R.string.help_cpu_units),
        HelpSection(R.string.help_section_impact, R.string.help_cpu_impact),
        HelpSection(R.string.help_section_reading, R.string.help_cpu_reading),
        HelpSection(R.string.help_section_example, R.string.help_cpu_example),
        HelpSection(R.string.help_section_limits, R.string.help_cpu_limits),
        HelpSection(R.string.help_section_state, R.string.help_cpu_state),
        HelpSection(R.string.help_section_source, R.string.help_cpu_source),
    ))
    Metric.CPU_WAIT -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_cpu_wait_meaning),
        HelpSection(R.string.help_section_units, R.string.help_cpu_wait_units),
        HelpSection(R.string.help_section_impact, R.string.help_cpu_wait_impact),
        HelpSection(R.string.help_section_reading, R.string.help_cpu_wait_reading),
        HelpSection(R.string.help_section_example, R.string.help_cpu_wait_example),
        HelpSection(R.string.help_section_limits, R.string.help_cpu_wait_limits),
        HelpSection(R.string.help_section_state, R.string.help_cpu_wait_state),
        HelpSection(R.string.help_section_source, R.string.help_cpu_wait_source),
    ))
    Metric.PROBE_DELAY -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_probe_delay_meaning),
        HelpSection(R.string.help_section_units, R.string.help_probe_delay_units),
        HelpSection(R.string.help_section_impact, R.string.help_probe_delay_impact),
        HelpSection(R.string.help_section_reading, R.string.help_probe_delay_reading),
        HelpSection(R.string.help_section_example, R.string.help_probe_delay_example),
        HelpSection(R.string.help_section_limits, R.string.help_probe_delay_limits),
        HelpSection(R.string.help_section_state, R.string.help_probe_delay_state),
        HelpSection(R.string.help_section_source, R.string.help_probe_delay_source),
    ))
    Metric.MEMORY_TOTAL -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_memory_total_meaning),
        HelpSection(R.string.help_section_units, R.string.help_memory_total_units),
        HelpSection(R.string.help_section_impact, R.string.help_memory_total_impact),
        HelpSection(R.string.help_section_reading, R.string.help_memory_total_reading),
        HelpSection(R.string.help_section_example, R.string.help_memory_total_example),
        HelpSection(R.string.help_section_limits, R.string.help_memory_total_limits),
        HelpSection(R.string.help_section_state, R.string.help_memory_total_state),
        HelpSection(R.string.help_section_source, R.string.help_memory_total_source),
    ))
    Metric.MEMORY_AVAILABLE -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_memory_available_meaning),
        HelpSection(R.string.help_section_units, R.string.help_memory_available_units),
        HelpSection(R.string.help_section_impact, R.string.help_memory_available_impact),
        HelpSection(R.string.help_section_reading, R.string.help_memory_available_reading),
        HelpSection(R.string.help_section_example, R.string.help_memory_available_example),
        HelpSection(R.string.help_section_limits, R.string.help_memory_available_limits),
        HelpSection(R.string.help_section_state, R.string.help_memory_available_state),
        HelpSection(R.string.help_section_source, R.string.help_memory_available_source),
    ))
    Metric.MEMORY_PERCENT -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_memory_percent_meaning),
        HelpSection(R.string.help_section_units, R.string.help_memory_percent_units),
        HelpSection(R.string.help_section_impact, R.string.help_memory_percent_impact),
        HelpSection(R.string.help_section_reading, R.string.help_memory_percent_reading),
        HelpSection(R.string.help_section_example, R.string.help_memory_percent_example),
        HelpSection(R.string.help_section_limits, R.string.help_memory_percent_limits),
        HelpSection(R.string.help_section_state, R.string.help_memory_percent_state),
        HelpSection(R.string.help_section_source, R.string.help_memory_percent_source),
    ))
    Metric.MEMORY_LOW -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_memory_low_meaning),
        HelpSection(R.string.help_section_units, R.string.help_memory_low_units),
        HelpSection(R.string.help_section_impact, R.string.help_memory_low_impact),
        HelpSection(R.string.help_section_reading, R.string.help_memory_low_reading),
        HelpSection(R.string.help_section_example, R.string.help_memory_low_example),
        HelpSection(R.string.help_section_limits, R.string.help_memory_low_limits),
        HelpSection(R.string.help_section_state, R.string.help_memory_low_state),
        HelpSection(R.string.help_section_source, R.string.help_memory_low_source),
    ))
    Metric.MEMORY_THRESHOLD -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_memory_threshold_meaning),
        HelpSection(R.string.help_section_units, R.string.help_memory_threshold_units),
        HelpSection(R.string.help_section_impact, R.string.help_memory_threshold_impact),
        HelpSection(R.string.help_section_reading, R.string.help_memory_threshold_reading),
        HelpSection(R.string.help_section_example, R.string.help_memory_threshold_example),
        HelpSection(R.string.help_section_limits, R.string.help_memory_threshold_limits),
        HelpSection(R.string.help_section_state, R.string.help_memory_threshold_state),
        HelpSection(R.string.help_section_source, R.string.help_memory_threshold_source),
    ))
    Metric.STORAGE_TOTAL -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_storage_total_meaning),
        HelpSection(R.string.help_section_units, R.string.help_storage_total_units),
        HelpSection(R.string.help_section_impact, R.string.help_storage_total_impact),
        HelpSection(R.string.help_section_reading, R.string.help_storage_total_reading),
        HelpSection(R.string.help_section_example, R.string.help_storage_total_example),
        HelpSection(R.string.help_section_limits, R.string.help_storage_total_limits),
        HelpSection(R.string.help_section_state, R.string.help_storage_total_state),
        HelpSection(R.string.help_section_source, R.string.help_storage_total_source),
    ))
    Metric.STORAGE_AVAILABLE -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_storage_available_meaning),
        HelpSection(R.string.help_section_units, R.string.help_storage_available_units),
        HelpSection(R.string.help_section_impact, R.string.help_storage_available_impact),
        HelpSection(R.string.help_section_reading, R.string.help_storage_available_reading),
        HelpSection(R.string.help_section_example, R.string.help_storage_available_example),
        HelpSection(R.string.help_section_limits, R.string.help_storage_available_limits),
        HelpSection(R.string.help_section_state, R.string.help_storage_available_state),
        HelpSection(R.string.help_section_source, R.string.help_storage_available_source),
    ))
    Metric.STORAGE_USED -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_storage_used_meaning),
        HelpSection(R.string.help_section_units, R.string.help_storage_used_units),
        HelpSection(R.string.help_section_impact, R.string.help_storage_used_impact),
        HelpSection(R.string.help_section_reading, R.string.help_storage_used_reading),
        HelpSection(R.string.help_section_example, R.string.help_storage_used_example),
        HelpSection(R.string.help_section_limits, R.string.help_storage_used_limits),
        HelpSection(R.string.help_section_state, R.string.help_storage_used_state),
        HelpSection(R.string.help_section_source, R.string.help_storage_used_source),
    ))
    Metric.STORAGE_PERCENT -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_storage_percent_meaning),
        HelpSection(R.string.help_section_units, R.string.help_storage_percent_units),
        HelpSection(R.string.help_section_impact, R.string.help_storage_percent_impact),
        HelpSection(R.string.help_section_reading, R.string.help_storage_percent_reading),
        HelpSection(R.string.help_section_example, R.string.help_storage_percent_example),
        HelpSection(R.string.help_section_limits, R.string.help_storage_percent_limits),
        HelpSection(R.string.help_section_state, R.string.help_storage_percent_state),
        HelpSection(R.string.help_section_source, R.string.help_storage_percent_source),
    ))
    Metric.THERMAL -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_thermal_meaning),
        HelpSection(R.string.help_section_units, R.string.help_thermal_units),
        HelpSection(R.string.help_section_impact, R.string.help_thermal_impact),
        HelpSection(R.string.help_section_reading, R.string.help_thermal_reading),
        HelpSection(R.string.help_section_example, R.string.help_thermal_example),
        HelpSection(R.string.help_section_limits, R.string.help_thermal_limits),
        HelpSection(R.string.help_section_state, R.string.help_thermal_state),
        HelpSection(R.string.help_section_source, R.string.help_thermal_source),
    ))
    Metric.HEADROOM -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_headroom_meaning),
        HelpSection(R.string.help_section_units, R.string.help_headroom_units),
        HelpSection(R.string.help_section_impact, R.string.help_headroom_impact),
        HelpSection(R.string.help_section_reading, R.string.help_headroom_reading),
        HelpSection(R.string.help_section_example, R.string.help_headroom_example),
        HelpSection(R.string.help_section_limits, R.string.help_headroom_limits),
        HelpSection(R.string.help_section_state, R.string.help_headroom_state),
        HelpSection(R.string.help_section_source, R.string.help_headroom_source),
    ))
    Metric.BATTERY -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_battery_meaning),
        HelpSection(R.string.help_section_units, R.string.help_battery_units),
        HelpSection(R.string.help_section_impact, R.string.help_battery_impact),
        HelpSection(R.string.help_section_reading, R.string.help_battery_reading),
        HelpSection(R.string.help_section_example, R.string.help_battery_example),
        HelpSection(R.string.help_section_limits, R.string.help_battery_limits),
        HelpSection(R.string.help_section_state, R.string.help_battery_state),
        HelpSection(R.string.help_section_source, R.string.help_battery_source),
    ))
    Metric.CHARGING -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_charging_meaning),
        HelpSection(R.string.help_section_units, R.string.help_charging_units),
        HelpSection(R.string.help_section_impact, R.string.help_charging_impact),
        HelpSection(R.string.help_section_reading, R.string.help_charging_reading),
        HelpSection(R.string.help_section_example, R.string.help_charging_example),
        HelpSection(R.string.help_section_limits, R.string.help_charging_limits),
        HelpSection(R.string.help_section_state, R.string.help_charging_state),
        HelpSection(R.string.help_section_source, R.string.help_charging_source),
    ))
    Metric.TEMPERATURE -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_temperature_meaning),
        HelpSection(R.string.help_section_units, R.string.help_temperature_units),
        HelpSection(R.string.help_section_impact, R.string.help_temperature_impact),
        HelpSection(R.string.help_section_reading, R.string.help_temperature_reading),
        HelpSection(R.string.help_section_example, R.string.help_temperature_example),
        HelpSection(R.string.help_section_limits, R.string.help_temperature_limits),
        HelpSection(R.string.help_section_state, R.string.help_temperature_state),
        HelpSection(R.string.help_section_source, R.string.help_temperature_source),
    ))
    Metric.CPU_PRESSURE -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_cpu_pressure_meaning),
        HelpSection(R.string.help_section_units, R.string.help_cpu_pressure_units),
        HelpSection(R.string.help_section_impact, R.string.help_cpu_pressure_impact),
        HelpSection(R.string.help_section_reading, R.string.help_cpu_pressure_reading),
        HelpSection(R.string.help_section_example, R.string.help_cpu_pressure_example),
        HelpSection(R.string.help_section_limits, R.string.help_cpu_pressure_limits),
        HelpSection(R.string.help_section_state, R.string.help_cpu_pressure_state),
        HelpSection(R.string.help_section_source, R.string.help_cpu_pressure_source),
    ))
    Metric.MEMORY_PRESSURE -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_memory_pressure_meaning),
        HelpSection(R.string.help_section_units, R.string.help_memory_pressure_units),
        HelpSection(R.string.help_section_impact, R.string.help_memory_pressure_impact),
        HelpSection(R.string.help_section_reading, R.string.help_memory_pressure_reading),
        HelpSection(R.string.help_section_example, R.string.help_memory_pressure_example),
        HelpSection(R.string.help_section_limits, R.string.help_memory_pressure_limits),
        HelpSection(R.string.help_section_state, R.string.help_memory_pressure_state),
        HelpSection(R.string.help_section_source, R.string.help_memory_pressure_source),
    ))
    Metric.STORAGE_PRESSURE -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_storage_pressure_meaning),
        HelpSection(R.string.help_section_units, R.string.help_storage_pressure_units),
        HelpSection(R.string.help_section_impact, R.string.help_storage_pressure_impact),
        HelpSection(R.string.help_section_reading, R.string.help_storage_pressure_reading),
        HelpSection(R.string.help_section_example, R.string.help_storage_pressure_example),
        HelpSection(R.string.help_section_limits, R.string.help_storage_pressure_limits),
        HelpSection(R.string.help_section_state, R.string.help_storage_pressure_state),
        HelpSection(R.string.help_section_source, R.string.help_storage_pressure_source),
    ))
    Metric.THERMAL_PRESSURE -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_thermal_pressure_meaning),
        HelpSection(R.string.help_section_units, R.string.help_thermal_pressure_units),
        HelpSection(R.string.help_section_impact, R.string.help_thermal_pressure_impact),
        HelpSection(R.string.help_section_reading, R.string.help_thermal_pressure_reading),
        HelpSection(R.string.help_section_example, R.string.help_thermal_pressure_example),
        HelpSection(R.string.help_section_limits, R.string.help_thermal_pressure_limits),
        HelpSection(R.string.help_section_state, R.string.help_thermal_pressure_state),
        HelpSection(R.string.help_section_source, R.string.help_thermal_pressure_source),
    ))
    Metric.STATE -> MetricHelp(listOf(
        HelpSection(R.string.help_section_meaning, R.string.help_state_meaning),
        HelpSection(R.string.help_section_units, R.string.help_state_units),
        HelpSection(R.string.help_section_impact, R.string.help_state_impact),
        HelpSection(R.string.help_section_reading, R.string.help_state_reading),
        HelpSection(R.string.help_section_example, R.string.help_state_example),
        HelpSection(R.string.help_section_limits, R.string.help_state_limits),
        HelpSection(R.string.help_section_state, R.string.help_state_state),
        HelpSection(R.string.help_section_source, R.string.help_state_source),
    ))
}

@StringRes fun availabilityHelp(value: Availability): Int = when (value) {
    Availability.AVAILABLE -> R.string.help_status_available
    Availability.UNSUPPORTED -> R.string.help_status_unsupported
    Availability.ERROR -> R.string.help_status_error
    Availability.DISABLED -> R.string.help_status_disabled
    Availability.WARMING_UP -> R.string.help_status_warming_up
}

@StringRes fun sourceReasonHelp(value: SourceReason): Int = when (value) {
    SourceReason.NONE -> R.string.help_reason_none
    SourceReason.PERMISSION_DENIED -> R.string.help_reason_permission_denied
    SourceReason.NOT_FOUND -> R.string.help_reason_not_found
    SourceReason.API_UNAVAILABLE -> R.string.help_reason_api_unavailable
    SourceReason.INVALID_FORMAT -> R.string.help_reason_invalid_format
    SourceReason.READ_FAILED -> R.string.help_reason_read_failed
    SourceReason.COUNTER_RESET -> R.string.help_reason_counter_reset
    SourceReason.DISABLED -> R.string.help_reason_disabled
    SourceReason.BASELINE -> R.string.help_reason_baseline
    SourceReason.PAUSED -> R.string.help_reason_paused
    SourceReason.SLEEP_GAP -> R.string.help_reason_sleep_gap
    SourceReason.STALE_SAMPLE -> R.string.help_reason_stale_sample
    SourceReason.MISSING_VALUE -> R.string.help_reason_missing_value
}

@StringRes fun sourceScopeHelp(value: SourceScope): Int = when (value) {
    SourceScope.DEVICE -> R.string.help_scope_device
    SourceScope.PROBE_THREAD -> R.string.help_scope_probe_thread
    SourceScope.APP_STORAGE -> R.string.help_scope_app_storage
    SourceScope.DERIVED -> R.string.help_scope_derived
}

@StringRes fun sourceNameHelp(value: SourceId): Int = when (value) {
    SourceId.PROC_STAT -> R.string.help_source_proc_stat
    SourceId.THREAD_SCHEDSTAT -> R.string.help_source_thread_schedstat
    SourceId.MONOTONIC_TIMER -> R.string.help_source_monotonic_timer
    SourceId.ACTIVITY_MANAGER -> R.string.help_source_activity_manager
    SourceId.STAT_FS -> R.string.help_source_stat_fs
    SourceId.THERMAL_API -> R.string.help_source_thermal_api
    SourceId.HEADROOM_API -> R.string.help_source_headroom_api
    SourceId.BATTERY_BROADCAST -> R.string.help_source_battery_broadcast
    SourceId.DERIVED -> R.string.help_source_derived
}
