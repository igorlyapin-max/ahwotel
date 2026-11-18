package com.ahwotel

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class KnoxAcceptanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val app get()=ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    @Before fun before():Unit=runBlocking { profile.prepare() }
    @After fun after():Unit=runBlocking { profile.restore() }
    @Test fun localKeyIsEncryptedExcludedFromExportsAndManagedActivationIsIdempotent():Unit=runBlocking {
        val prefs=app.getSharedPreferences("knox_private",Context.MODE_PRIVATE)
        val saved=prefs.all.toMap()
        try {
            prefs.edit().clear().commit()
            var calls=0
            val controller=KnoxController(app,{40},{false}) { value,callback,result ->
                assertEquals("synthetic-knox-fixture",value); assertTrue(callback); calls++
                val completed=java.util.concurrent.CountDownLatch(1)
                Thread { result(false,123,emptyList()); completed.countDown() }.start()
                assertTrue(completed.await(5,java.util.concurrent.TimeUnit.SECONDS))
            }
            withContext(Dispatchers.IO) {
                controller.managed(null,true)
                assertEquals(KnoxState.WAITING_KEY, controller.view.value.state)
                assertEquals(0, calls)
                controller.managed("synthetic-knox-fixture",true)
                assertEquals(KnoxState.ERROR,controller.view.value.state)
                controller.managed("synthetic-knox-fixture",true)
                assertEquals(1,calls)
                controller.managed(null,false); controller.managed(null,true)
                assertEquals(2,calls)
            }
            assertFalse(prefs.all.values.any { it.toString().contains("synthetic-knox-fixture") })
            assertTrue(app.applyManagedConfiguration(android.os.Bundle().apply { putString("knox_license_key","synthetic-knox-fixture") }))
            assertFalse(app.managed.keys.value.contains("knox_license_key"))
            assertFalse(app.exportDeploymentProfile("knox-fixture",1).contains("synthetic-knox-fixture"))
        } finally {
            restoreKnoxPreferences(prefs, saved)
        }
    }

    @Test fun oldHintAndInterruptedDispatchRequireFreshEvidence(): Unit = runBlocking(Dispatchers.IO) {
        val prefs = app.getSharedPreferences("knox_private", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        try {
            check(prefs.edit().clear().commit())
            var recorded = false
            val old = KnoxController(app, { 32 }, { recorded }) { _, callback, _ -> assertFalse(callback) }
            old.managed("old-key-fixture", true)
            old.broadcastHint()
            assertEquals(KnoxState.PENDING, old.view.value.state)
            old.managed(null, false)

            val interrupted = KnoxController(app, { 40 }, { false }) { _, _, _ -> }
            interrupted.managed("retry-key-fixture", true)
            assertEquals(KnoxState.PENDING, interrupted.view.value.state)
            val staged = prefs.all.toMap()
            interrupted.managed(null, false) // Discard the old controller's timer.
            restoreKnoxPreferences(prefs, staged)
            val retries = java.util.concurrent.atomic.AtomicInteger()
            val restarted = KnoxController(app, { 40 }, { false }) { _, _, done ->
                retries.incrementAndGet()
                done(true, 0, emptyList())
            }
            // Both instances use the same preferences, as they would across process restart.
            restarted.managed("retry-key-fixture", true)
            withTimeout(15_000) { while (restarted.view.value.state != KnoxState.ACTIVE) delay(25) }
            restarted.managed("retry-key-fixture", true)
            assertEquals(1, retries.get())

            val afterException = KnoxController(app, { 40 }, { false }) { _, _, done ->
                if (retries.incrementAndGet() == 2) throw IllegalStateException("fixture_dispatch")
                done(true, 0, emptyList())
            }
            afterException.managed("exception-key-fixture", true)
            withTimeout(15_000) { while (afterException.view.value.state != KnoxState.ACTIVE) delay(25) }
            assertEquals(3, retries.get())
        } finally {
            restoreKnoxPreferences(prefs, saved)
        }
    }

    @Test fun delayedLocalImportCannotReplaceManagedKey(): Unit = runBlocking {
        val prefs = app.getSharedPreferences("knox_private", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        try {
            check(prefs.edit().clear().commit())
            val keyRead = CompletableDeferred<String>()
            val import = async { runCatching { app.importLocalKnoxKey(keyRead.await()) } }
            assertTrue(app.applyManagedConfiguration(android.os.Bundle().apply {
                putString("knox_license_key", "administrator-fixture")
            }))
            val ciphertext = prefs.getString("cipher", null)
            keyRead.complete("late-local-fixture")
            assertTrue(import.await().isFailure)
            assertNotNull(ciphertext)
            assertEquals(ciphertext, prefs.getString("cipher", null))
        } finally {
            restoreKnoxPreferences(prefs, saved)
            app.knox.refresh()
        }
    }

    private fun restoreKnoxPreferences(prefs: android.content.SharedPreferences, saved: Map<String, *>) {
        val editor = prefs.edit().clear()
        saved.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
        } }
        check(editor.commit())
    }
    @Test fun storageFixtureReachesRoomHistoryAndOtlp():Unit=runBlocking {
        val now=System.currentTimeMillis(); val id="knox-fixture-$now"
        val session=SessionRow(id,app.settings.value.deviceId,now,reason="test",configuration="{}",continuous=false,durationSeconds=1)
        app.db.dao().start(session); app.db.dao().finish(id,now+1,"test")
        val metric=com.ahwotel.oem.OemMetric.KNOX_INTERNAL_AVAILABLE
        val observation=com.ahwotel.oem.OemObservation(metric,"samsung_knox",com.ahwotel.oem.OemSource.OEM,
            com.ahwotel.oem.OemScope.DEVICE,number=123456.0,time=now)
        app.db.oemDao().observations(listOf(OemObservationRow.from(observation,id,0)))
        assertEquals(123456.0,app.db.oemDao().page(0,now,now,id).single().number!!,0.0)
        for(lang in listOf("en","ru")) {
            val out=java.io.ByteArrayOutputStream()
            HistoryArchiveExporter.write(app,out,HistoryArchiveSpec(id,now,now+1,false,setOf(HistoryExportGroup.OEM),lang,"UTC"))
            var found=false
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(out.toByteArray())).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    if(entry.name=="oem.csv") {
                        val text=zip.readBytes().toString(Charsets.UTF_8)
                        assertTrue(text.contains(metric.wire)); assertTrue(text.contains("123456")); assertTrue(text.contains("samsung_knox")); found=true
                    }
                }
            }
            assertTrue(found)
        }
        val provider=com.ahwotel.oem.OemProviderProfile("samsung_knox",com.ahwotel.oem.ProviderState.READY,now,emptyMap())
        val wire=com.ahwotel.oem.OemTelemetry.encode(session,listOf(observation),provider,"test").toString(Charsets.ISO_8859_1)
        assertTrue(wire.contains(metric.wire)); assertFalse(wire.contains("synthetic-knox-fixture"))

        app.saveSettings(app.settings.value.copy(oem = app.settings.value.oem.copy(enabled = true, knox = true)))
        val previous = app.oem.latest.value
        try {
            app.oem.latest.value = previous + (com.ahwotel.oem.OemCoordinator.key(observation) to observation)
            app.oem.waitingForKnox.value = emptySet() // The fixture stands for a completed worker read.
            ui.onNodeWithText(ui.activity.getString(R.string.diagnostics)).performClick()
            ui.onNodeWithText(ui.activity.getString(R.string.oem_title)).performClick()
            val list = ui.onNodeWithTag("oem_screen")
            val card = hasTestTag("compact_metric_oem_${metric.name}")
            list.performScrollToNode(card)
            ui.onNode(card).assertIsDisplayed()
            app.saveSettings(app.settings.value.copy(oem = app.settings.value.oem.copy(knox = false)))
            ui.onNode(card).assertDoesNotExist()
            assertEquals(123456.0, app.db.oemDao().page(0, now, now, id).single().number!!, 0.0)
            app.saveSettings(app.settings.value.copy(oem = app.settings.value.oem.copy(knox = true)))
            list.performScrollToIndex(0)
            list.performScrollToNode(card)
            ui.onNode(card).assertIsDisplayed()
            ui.onAllNodes(hasText("123,456", substring = true)).assertCountEquals(0)
            assertTrue(ui.onAllNodesWithText(ui.activity.getString(R.string.oem_no_observation)).fetchSemanticsNodes().isNotEmpty())
            assertTrue(metric.category in app.oem.waitingForKnox.value)
        } finally {
            app.oem.latest.value = previous
        }
    }
    @Test fun knoxSettingsUseSelectedLanguageAndDoNotRequireSamsung():Unit=runBlocking {
        for(lang in listOf("en","ru")) {
            app.saveSettings(app.settings.value.copy(language=lang))
            val tab=if(lang=="en") "Settings" else "Настройки"
            ui.waitUntil(10000) { ui.onAllNodes(hasText(tab) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasText(tab) and hasClickAction()).performClick()
            ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Samsung Knox"))
            ui.onNodeWithText("Samsung Knox").performScrollTo().assertIsDisplayed()
            val label=ui.activity.getString(R.string.knox_refresh)
            ui.onNodeWithText(label).performScrollTo().performClick()
            ui.onNodeWithText(ui.activity.getString(R.string.knox_import)).assertExists()
        }
    }
}
