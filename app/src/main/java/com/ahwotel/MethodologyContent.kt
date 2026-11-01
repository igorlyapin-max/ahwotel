package com.ahwotel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

internal fun LazyListScope.methodologyItems() {
    methodologySection(R.string.methodology_modes_title, R.string.methodology_modes_text)
    methodologySection(R.string.methodology_workflow_title, R.string.methodology_workflow_text)
    methodologySection(R.string.methodology_strata_title, R.string.methodology_strata_text)
    item { SamplingCalculator() }
    methodologySection(R.string.methodology_reference_title, R.string.methodology_reference_text)
    methodologySection(R.string.methodology_technical_title, R.string.methodology_technical_intro)
    methodologySection(R.string.methodology_performance_title, R.string.methodology_performance_text)
    methodologySection(R.string.methodology_cpu_title, R.string.methodology_cpu_text)
    methodologySection(R.string.methodology_memory_title, R.string.methodology_memory_text)
    methodologySection(R.string.methodology_storage_title, R.string.methodology_storage_text)
    methodologySection(R.string.methodology_thermal_title, R.string.methodology_thermal_text)
    methodologySection(R.string.methodology_battery_title, R.string.methodology_battery_text)
    methodologySection(R.string.methodology_agent_title, R.string.methodology_agent_text)
    methodologySection(R.string.methodology_intervals_title, R.string.methodology_intervals_text)
    methodologySection(R.string.methodology_business_title, R.string.methodology_business_text)
    methodologySection(R.string.methodology_limits_title, R.string.methodology_limits_text)
}

private fun LazyListScope.methodologySection(title: Int, text: Int) = item {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(text))
    }
}

@Composable private fun SamplingCalculator() {
    var modeName by rememberSaveable { mutableStateOf(SamplingMode.PROPORTION.name) }
    var confidenceName by rememberSaveable { mutableStateOf(SamplingConfidence.C95.name) }
    var population by rememberSaveable { mutableStateOf("") }
    var expected by rememberSaveable { mutableStateOf("50") }
    var margin by rememberSaveable { mutableStateOf("5") }
    var deviation by rememberSaveable { mutableStateOf("") }
    var absoluteError by rememberSaveable { mutableStateOf("") }
    var reserve by rememberSaveable { mutableStateOf("15") }
    val mode = SamplingMode.valueOf(modeName)
    val confidence = SamplingConfidence.valueOf(confidenceName)
    val outcome = remember(mode, confidence, population, expected, margin, deviation, absoluteError, reserve) {
        val n = population.trim().toIntOrNull()
        val loss = parseSamplingNumber(reserve)
        if (n == null) SamplingOutcome.Invalid(SamplingError.POPULATION)
        else if (loss == null) SamplingOutcome.Invalid(SamplingError.RESERVE)
        else when (mode) {
            SamplingMode.PROPORTION -> {
                val p = parseSamplingNumber(expected)
                val e = parseSamplingNumber(margin)
                when {
                    p == null -> SamplingOutcome.Invalid(SamplingError.EXPECTED_PROPORTION)
                    e == null -> SamplingOutcome.Invalid(SamplingError.MARGIN)
                    else -> calculateProportionSample(n, confidence, p, e, loss)
                }
            }
            SamplingMode.MEAN -> {
                val sigma = parseSamplingNumber(deviation)
                val error = parseSamplingNumber(absoluteError)
                when {
                    sigma == null -> SamplingOutcome.Invalid(SamplingError.STANDARD_DEVIATION)
                    error == null -> SamplingOutcome.Invalid(SamplingError.ABSOLUTE_ERROR)
                    else -> calculateMeanSample(n, confidence, sigma, error, loss)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("sampling-calculator"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.methodology_calculator_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.methodology_calculator_intro), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SamplingMode.entries.forEach { value ->
                FilterChip(mode == value, { modeName = value.name }, label = { Text(stringResource(if (value == SamplingMode.PROPORTION) R.string.methodology_mode_proportion else R.string.methodology_mode_mean)) })
            }
        }
        MethodologyField(R.string.methodology_population, population, "methodology-population") { population = it }
        Text(stringResource(R.string.methodology_confidence))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SamplingConfidence.entries.forEach { value ->
                FilterChip(confidence == value, { confidenceName = value.name }, label = { Text("${value.percent}%") })
            }
        }
        if (mode == SamplingMode.PROPORTION) {
            MethodologyField(R.string.methodology_expected, expected, "methodology-expected") { expected = it }
            MethodologyField(R.string.methodology_margin, margin, "methodology-margin") { margin = it }
        } else {
            MethodologyField(R.string.methodology_deviation, deviation, "methodology-deviation") { deviation = it }
            MethodologyField(R.string.methodology_absolute_error, absoluteError, "methodology-absolute-error") { absoluteError = it }
        }
        MethodologyField(R.string.methodology_reserve, reserve, "methodology-reserve") { reserve = it }
        when (outcome) {
            is SamplingOutcome.Success -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.methodology_base_result, outcome.base), Modifier.testTag("methodology-base-result"), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.methodology_planned_result, outcome.planned), Modifier.testTag("methodology-planned-result"), style = MaterialTheme.typography.titleMedium)
                }
            }
            is SamplingOutcome.Invalid -> Text(stringResource(errorText(outcome.error)), Modifier.testTag("methodology-error"), color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.methodology_normal_warning), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun MethodologyField(label: Int, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag(tag), label = { Text(stringResource(label)) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

private fun errorText(error: SamplingError) = when (error) {
    SamplingError.POPULATION -> R.string.methodology_error_population
    SamplingError.EXPECTED_PROPORTION -> R.string.methodology_error_expected
    SamplingError.MARGIN -> R.string.methodology_error_margin
    SamplingError.STANDARD_DEVIATION -> R.string.methodology_error_deviation
    SamplingError.ABSOLUTE_ERROR -> R.string.methodology_error_absolute
    SamplingError.RESERVE -> R.string.methodology_error_reserve
}
