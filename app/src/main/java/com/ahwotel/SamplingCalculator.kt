package com.ahwotel

import kotlin.math.ceil

enum class SamplingMode { PROPORTION, MEAN }

enum class SamplingConfidence(val percent: Int, val z: Double) {
    C90(90, 1.6448536269514722),
    C95(95, 1.959963984540054),
    C99(99, 2.5758293035489004),
}

enum class SamplingError { POPULATION, EXPECTED_PROPORTION, MARGIN, STANDARD_DEVIATION, ABSOLUTE_ERROR, RESERVE }

sealed interface SamplingOutcome {
    data class Success(val base: Int, val planned: Int) : SamplingOutcome
    data class Invalid(val error: SamplingError) : SamplingOutcome
}

fun parseSamplingNumber(value: String): Double? {
    val normalized = value.trim().replace(',', '.')
    if (normalized.isEmpty() || normalized.count { it == '.' } > 1) return null
    return normalized.toDoubleOrNull()?.takeIf(Double::isFinite)
}

fun calculateProportionSample(
    population: Int,
    confidence: SamplingConfidence,
    expectedPercent: Double,
    marginPercent: Double,
    reservePercent: Double,
): SamplingOutcome {
    if (population < 1) return SamplingOutcome.Invalid(SamplingError.POPULATION)
    if (expectedPercent <= 0.0 || expectedPercent >= 100.0) return SamplingOutcome.Invalid(SamplingError.EXPECTED_PROPORTION)
    if (marginPercent <= 0.0 || marginPercent >= 100.0) return SamplingOutcome.Invalid(SamplingError.MARGIN)
    if (reservePercent < 0.0 || reservePercent > 50.0) return SamplingOutcome.Invalid(SamplingError.RESERVE)
    val p = expectedPercent / 100.0
    val error = marginPercent / 100.0
    val unlimited = confidence.z * confidence.z * p * (1.0 - p) / (error * error)
    return finitePopulationResult(population, unlimited, reservePercent)
}

fun calculateMeanSample(
    population: Int,
    confidence: SamplingConfidence,
    standardDeviation: Double,
    absoluteError: Double,
    reservePercent: Double,
): SamplingOutcome {
    if (population < 1) return SamplingOutcome.Invalid(SamplingError.POPULATION)
    if (standardDeviation <= 0.0) return SamplingOutcome.Invalid(SamplingError.STANDARD_DEVIATION)
    if (absoluteError <= 0.0) return SamplingOutcome.Invalid(SamplingError.ABSOLUTE_ERROR)
    if (reservePercent < 0.0 || reservePercent > 50.0) return SamplingOutcome.Invalid(SamplingError.RESERVE)
    val unlimited = confidence.z * confidence.z * standardDeviation * standardDeviation / (absoluteError * absoluteError)
    return finitePopulationResult(population, unlimited, reservePercent)
}

private fun finitePopulationResult(population: Int, unlimited: Double, reservePercent: Double): SamplingOutcome.Success {
    if (!unlimited.isFinite()) return SamplingOutcome.Success(population, population)
    val corrected = population * unlimited / (population + unlimited - 1.0)
    val base = ceil(corrected).toInt().coerceIn(1, population)
    val planned = ceil(base / (1.0 - reservePercent / 100.0)).toInt().coerceAtMost(population)
    return SamplingOutcome.Success(base, planned)
}
