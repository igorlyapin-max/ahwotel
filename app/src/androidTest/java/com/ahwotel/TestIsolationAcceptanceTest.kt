package com.ahwotel

import android.app.Application
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

private var applicationConstructed = false
class GuardProbeApplication : Application() {
    init { applicationConstructed = true }
}

@RunWith(AndroidJUnit4::class)
class TestIsolationAcceptanceTest {
    @Test fun runnerRejectsWorkingAndUnknownPackagesBeforeApplicationConstruction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertEquals("com.ahwotel.acceptance", target.packageName)
        assertEquals("com.ahwotel.acceptance.test", instrumentation.context.packageName)
        for (name in listOf("com.ahwotel", "com.ahwotel.acceptance.other", "")) {
            val rejected = object : ContextWrapper(target) { override fun getPackageName() = name }
            applicationConstructed = false
            val failure = runCatching {
                SafeTestRunner().newApplication(target.classLoader, GuardProbeApplication::class.java.name, rejected)
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("unsafe_instrumentation_target", failure?.message)
            assertFalse(applicationConstructed)
        }
    }
}
