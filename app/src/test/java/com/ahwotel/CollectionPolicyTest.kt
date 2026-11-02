package com.ahwotel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ahwotel.oem.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CollectionPolicyTest {
    @Test fun independentRestrictionsCoverEveryCombination() {
        for (screenOffAllowed in listOf(false, true)) for (pauseLocked in listOf(false, true))
            for (screenOn in listOf(false, true)) for (locked in listOf(false, true)) {
                val settings = Settings(collectScreenOff = screenOffAllowed, pauseWhenLocked = pauseLocked)
                assertEquals((!screenOffAllowed && !screenOn) || (pauseLocked && locked),
                    collectionPauseReason(settings, screenOn, locked) != null)
            }
        assertEquals(CollectionPauseReason.LOCKED, collectionPauseReason(Settings(pauseWhenLocked = true), true, true))
        assertNull(collectionPauseReason(Settings(pauseWhenLocked = true), true, false))
    }

    @Test fun storedSettingsMigrateWithoutResettingPerformanceConfiguration() {
        val raw = JSONObject(SettingsCodec.encode(Settings(deviceId = "test", intervalMs = 5000, retentionDays = 30)))
        raw.remove("pauseWhenLocked")
        raw.getJSONObject("oem").put("categories", JSONArray(OemCategory.entries.map { it.name }))
        val restored = SettingsCodec.decode(raw.toString())
        assertFalse(restored.pauseWhenLocked)
        assertEquals(5000L, restored.intervalMs)
        assertEquals(30, restored.retentionDays)
        assertTrue(restored.oem.categories.all { it.active })
        assertTrue(SettingsCodec.decode(SettingsCodec.encode(restored.copy(pauseWhenLocked = true))).pauseWhenLocked)
    }

    @Test fun currentProfileCarriesLockPolicyAndRejectsOldSchemas() {
        val raw = DeploymentProfileCodec.encode(Settings(deviceId = "test", pauseWhenLocked = true), "fleet", 2)
        assertTrue(DeploymentProfileCodec.decode(raw).settings.pauseWhenLocked)
        assertEquals(4, JSONObject(raw).getInt("schemaVersion"))
        assertThrows(IllegalArgumentException::class.java) { DeploymentProfileCodec.decode(JSONObject(raw).put("schemaVersion", 1).toString()) }
        assertThrows(IllegalArgumentException::class.java) { DeploymentProfileCodec.decode(JSONObject(raw).put("schemaVersion", 2).toString()) }
        val retired = JSONObject(raw)
        retired.getJSONObject("settings").getJSONObject("oem").put("categories", JSONArray(listOf("INVENTORY")))
        assertThrows(IllegalArgumentException::class.java) { DeploymentProfileCodec.decode(retired.toString()) }
    }

    @Test fun retiredCategoriesNeverReadAndroidOrKnoxInventory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = object : KnoxBridge {
            override fun apiLevel(): Int = error("retired category must not probe Knox")
            override fun call(call: KnoxCall): Any? = error("must not call retired API")
        }
        for (category in listOf(OemCategory.SECURITY, OemCategory.POLICY, OemCategory.INVENTORY)) {
            assertTrue(AndroidStandardProvider(context).collect(category).observations.isEmpty())
            assertTrue(SamsungKnoxProvider(context, bridge).collect(category).observations.isEmpty())
        }
        assertTrue(KnoxCapabilityRegistry.entries.isEmpty())
        assertFalse(OemMetric.PACKAGE_COUNT.active)
        assertFalse(OemMetric.SCREEN_LOCK.active)
    }
}
