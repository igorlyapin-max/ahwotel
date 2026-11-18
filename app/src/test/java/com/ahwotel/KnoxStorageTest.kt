package com.ahwotel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ahwotel.oem.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class KnoxStorageTest {
    @Test fun storageIsSeparateAndNegativeOrDeniedValuesNeverBecomeZero() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val provider=SamsungKnoxProvider(context,object:KnoxBridge {
            override fun apiLevel()=40
            override fun call(call:KnoxCall):Any = when(call) {
                KnoxCall.INTERNAL_TOTAL -> 100000L
                KnoxCall.INTERNAL_AVAILABLE -> 0L
                KnoxCall.EXTERNAL_TOTAL -> -1L
                KnoxCall.EXTERNAL_AVAILABLE -> throw SecurityException("not logged")
                else -> error("retired API must not be called")
            }
        })
        val rows=provider.collect(OemCategory.DEVICE).observations.associateBy { it.metric }
        assertEquals(100000.0,rows.getValue(OemMetric.KNOX_INTERNAL_TOTAL).number!!,0.0)
        assertEquals(0.0,rows.getValue(OemMetric.KNOX_INTERNAL_AVAILABLE).number!!,0.0)
        assertNull(rows.getValue(OemMetric.KNOX_EXTERNAL_TOTAL).number)
        assertEquals(OemStatus.PERMISSION_DENIED,rows.getValue(OemMetric.KNOX_EXTERNAL_AVAILABLE).status)
        assertTrue(rows.values.all { it.provider=="samsung_knox" && it.scope==OemScope.DEVICE })
        assertTrue(provider.collect(OemCategory.RESOURCES).observations.all { it.number==null && it.status==OemStatus.UNSUPPORTED })
    }
    @Test fun keyValidationAndMissingRuntimeRemainSafe() {
        assertTrue(validKnoxKey("synthetic-fixture")); assertFalse(validKnoxKey(""))
        assertFalse(validKnoxKey("bad\nkey")); assertFalse(validKnoxKey("a".repeat(4097)))
        val provider=SamsungKnoxProvider(ApplicationProvider.getApplicationContext(),object:KnoxBridge {
            override fun apiLevel():Int=throw ClassNotFoundException()
            override fun call(call:KnoxCall):Any=error("must not run")
        })
        assertTrue(provider.collect(OemCategory.DEVICE).observations.all { it.number==null && it.status==OemStatus.UNAVAILABLE })
        assertFalse(SettingsCodec.encode(Settings()).contains("knox_license_key"))
    }
}
