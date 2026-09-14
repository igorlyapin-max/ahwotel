package com.ahwotel

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

class HttpLabAcceptanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val app get()=ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    @Before fun prepare(): Unit=runBlocking { profile.prepare()
        ui.activityRule.scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun restore(): Unit=runBlocking { profile.restore() }
    private fun list()=ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    private fun toggle(label: String) {
        list().performScrollToNode(hasText(label))
        val bounds=ui.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        ui.onNode(isToggleable() and SemanticsMatcher("aligned switch") { it.boundsInRoot.center.y in bounds.top..bounds.bottom }).performClick()
    }
    @Test fun httpPermissionSavesFromUiAndRevocationStopsPendingDelivery(): Unit=runBlocking {
        MockWebServer().use { server ->
            server.start(); val endpoint=server.url("/v1/metrics").toString()
            app.saveSettings(app.settings.value.copy(endpoint=endpoint))
            ui.onNode(hasText("Settings") and hasClickAction()).performClick()
            toggle("Allow HTTP for a test lab")
            list().performScrollToNode(hasText("Save settings"));ui.onNodeWithText("Save settings").performClick()
            ui.waitUntil(10000) { app.settings.value.allowHttp }
            app.saveSettings(app.settings.value.copy(otlpEnabled=true))
            server.enqueue(MockResponse().setResponseCode(200))
            val now=System.currentTimeMillis()
            app.db.dao().enqueue(OutboxRow(createdAt=now,endpoint=endpoint,payload=byteArrayOf(10,0)))
            app.scheduleUpload()
            withTimeout(45000) { while(server.requestCount==0 || app.db.dao().outboxCount()!=0L) delay(100) }
            assertNull(server.takeRequest().getHeader("Authorization"))
            val before=app.db.dao().sampleCount()
            app.db.dao().enqueue(OutboxRow(createdAt=now,endpoint=endpoint,payload=byteArrayOf(10,0),dueAt=now+3600000))
            app.saveSettings(app.settings.value.copy(allowHttp=false))
            assertFalse(app.settings.value.otlpEnabled);assertFalse(app.settings.value.allowHttp)
            assertEquals(0L,app.db.dao().outboxCount());assertEquals(before,app.db.dao().sampleCount())
            delay(1000);assertEquals(1,server.requestCount)
        }
    }
    @Test fun revokingHttpCancelsInFlightRequestAndPreservesCollection(): Unit=runBlocking {
        MockWebServer().use { server ->
            server.start()
            val endpoint=server.url("/v1/metrics").toString()
            app.saveSettings(app.settings.value.copy(allowHttp=true,otlpEnabled=true,endpoint=endpoint,
                continuous=true,enabled=setOf(CollectorKind.MEMORY),
                batterySettings=BatterySettings(enabled=false),selfTelemetry=SelfTelemetrySettings(enabled=false)))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            ui.onNodeWithText("Start monitoring").performClick()
            withTimeout(10000) { while(app.state.value.sessionId==null) delay(100) }
            val session=app.state.value.sessionId
            val now=System.currentTimeMillis()
            app.db.dao().enqueue(OutboxRow(createdAt=now,endpoint=endpoint,payload=byteArrayOf(10,0)))
            app.scheduleUpload()
            assertNotNull(server.takeRequest(30,TimeUnit.SECONDS)) // The request is already on the wire.
            val before=app.db.dao().sampleCount()
            withTimeout(10000) { app.saveSettings(app.settings.value.copy(allowHttp=false)) }
            assertFalse(app.settings.value.otlpEnabled)
            assertEquals(0L,app.db.dao().outboxCount())
            app.scheduleUpload()
            withTimeout(10000) { while(app.db.dao().sampleCount()<=before) delay(100) }
            delay(1500)
            assertEquals(session,app.state.value.sessionId)
            assertEquals(1,server.requestCount)
            assertEquals(0L,app.db.dao().outboxCount())
        }
    }
    @Test fun realWorkerDeliversMeasuredMemoryToLabCollector(): Unit=runBlocking {
        val endpoint=InstrumentationRegistry.getArguments().getString("labEndpoint")
        Assume.assumeTrue("Requires --lab-endpoint",endpoint!=null)
        app.saveSettings(app.settings.value.copy(allowHttp=true,otlpEnabled=true,endpoint=endpoint!!,
            enabled=setOf(CollectorKind.MEMORY,CollectorKind.CPU,CollectorKind.STORAGE,CollectorKind.THERMAL),
            batterySettings=BatterySettings(enabled=false),selfTelemetry=SelfTelemetrySettings(enabled=false),
            continuous=false,durationSeconds=30,diagnostic=DiagnosticLevel.BASIC))
        ui.onNodeWithText("Start monitoring").performClick()
        withTimeout(10000) { while(app.state.value.sessionId==null) delay(100) }
        val session=app.state.value.sessionId!!
        withTimeout(60000) { while(app.state.value.sessionId!=null || app.db.dao().outboxCount()>0) delay(200) }
        app.db.openHelper.readableDatabase.query("SELECT time,memoryAvailable FROM samples WHERE sessionId=? AND memoryAvailable IS NOT NULL ORDER BY time LIMIT 1",arrayOf(session)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            File(app.filesDir,"lab-result.json").writeText(JSONObject().put("deviceId",app.settings.value.deviceId)
                .put("sessionId",session).put("time",cursor.getLong(0)).put("memoryAvailable",cursor.getDouble(1))
                .put("endpoint",endpoint).toString())
        }
    }
}
