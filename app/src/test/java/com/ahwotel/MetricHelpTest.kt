package com.ahwotel

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class MetricHelpTest {
    private fun context(language: String): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
    }
    @Test fun everyMetricHasEightTranslatedNonemptySections() {
        val en = context("en"); val ru = context("ru")
        for (metric in Metric.entries) {
            val sections = metricHelp(metric).sections
            assertEquals(metric.name, 8, sections.size)
            assertEquals(8, sections.map { it.title }.distinct().size)
            for (id in sections.flatMap { listOf(it.title, it.text) }) {
                assertTrue(en.getString(id).isNotBlank()); assertTrue(ru.getString(id).isNotBlank())
                assertNotEquals("Missing Russian translation for ${en.resources.getResourceEntryName(id)}", en.getString(id), ru.getString(id))
            }
        }
        assertEquals(Metric.entries.toSet(), HelpGroup.ALL.metrics.toSet())
    }
    @Test fun everyAvailabilityReasonSourceAndScopeHasLocalizedExplanation() {
        val en = context("en"); val ru = context("ru")
        val ids = Availability.entries.map(::availabilityHelp) + SourceReason.entries.map(::sourceReasonHelp) +
            SourceScope.entries.map(::sourceScopeHelp) + SourceId.entries.map(::sourceNameHelp)
        for (id in ids) {
            assertTrue(en.getString(id).isNotBlank()); assertTrue(ru.getString(id).isNotBlank())
            assertNotEquals(en.getString(id), ru.getString(id))
        }
    }
}
