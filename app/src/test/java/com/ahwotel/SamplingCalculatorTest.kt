package com.ahwotel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingCalculatorTest {
    @Test fun proportionUsesFinitePopulationCorrectionAndReserve() {
        assertEquals(
            SamplingOutcome.Success(base = 278, planned = 328),
            calculateProportionSample(1000, SamplingConfidence.C95, 50.0, 5.0, 15.0),
        )
    }

    @Test fun referenceTableMatchesPublishedUiValues() {
        val expected = mapOf(
            100 to (80 to 92), 500 to (218 to 341), 1000 to (278 to 517),
            5000 to (357 to 880), 10000 to (370 to 965), 100000 to (383 to 1056),
        )
        for ((population, samples) in expected) {
            val five = calculateProportionSample(population, SamplingConfidence.C95, 50.0, 5.0, 0.0) as SamplingOutcome.Success
            val three = calculateProportionSample(population, SamplingConfidence.C95, 50.0, 3.0, 0.0) as SamplingOutcome.Success
            assertEquals(population.toString(), samples.first, five.base)
            assertEquals(population.toString(), samples.second, three.base)
        }
    }

    @Test fun meanUsesPilotDeviationAbsoluteErrorAndPopulationCap() {
        assertEquals(
            SamplingOutcome.Success(base = 278, planned = 309),
            calculateMeanSample(1000, SamplingConfidence.C95, 10.0, 1.0, 10.0),
        )
        assertEquals(
            SamplingOutcome.Success(base = 3, planned = 3),
            calculateMeanSample(3, SamplingConfidence.C99, 100.0, 0.1, 50.0),
        )
        assertEquals(
            SamplingOutcome.Success(base = 100, planned = 100),
            calculateMeanSample(100, SamplingConfidence.C99, Double.MAX_VALUE, Double.MIN_VALUE, 0.0),
        )
    }

    @Test fun confidenceChangesRequiredSample() {
        val sizes = SamplingConfidence.entries.map {
            (calculateProportionSample(10000, it, 50.0, 5.0, 0.0) as SamplingOutcome.Success).base
        }
        assertTrue(sizes.zipWithNext().all { (lower, higher) -> lower < higher })
    }

    @Test fun parserAcceptsBothAppLocalesAndRejectsAmbiguousValues() {
        assertEquals(12.5, parseSamplingNumber("12.5")!!, 0.0)
        assertEquals(12.5, parseSamplingNumber(" 12,5 ")!!, 0.0)
        assertEquals(null, parseSamplingNumber("1,2.3"))
        assertEquals(null, parseSamplingNumber("NaN"))
    }

    @Test fun invalidInputsReturnSpecificErrors() {
        assertEquals(SamplingOutcome.Invalid(SamplingError.POPULATION), calculateProportionSample(0, SamplingConfidence.C95, 50.0, 5.0, 0.0))
        assertEquals(SamplingOutcome.Invalid(SamplingError.EXPECTED_PROPORTION), calculateProportionSample(100, SamplingConfidence.C95, 100.0, 5.0, 0.0))
        assertEquals(SamplingOutcome.Invalid(SamplingError.MARGIN), calculateProportionSample(100, SamplingConfidence.C95, 50.0, 0.0, 0.0))
        assertEquals(SamplingOutcome.Invalid(SamplingError.RESERVE), calculateProportionSample(100, SamplingConfidence.C95, 50.0, 5.0, 51.0))
        assertEquals(SamplingOutcome.Invalid(SamplingError.STANDARD_DEVIATION), calculateMeanSample(100, SamplingConfidence.C95, 0.0, 1.0, 0.0))
        assertEquals(SamplingOutcome.Invalid(SamplingError.ABSOLUTE_ERROR), calculateMeanSample(100, SamplingConfidence.C95, 1.0, 0.0, 0.0))
    }
}
