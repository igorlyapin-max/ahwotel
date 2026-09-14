package com.ahwotel

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class HttpPolicyTest {
    @Test fun existingSettingsStayHttpsOnlyAndHttpChoiceSurvivesReload() {
        val old = JSONObject(SettingsCodec.encode(Settings(deviceId="lab"))).apply { remove("allowHttp") }
        assertFalse(SettingsCodec.decode(old.toString()).allowHttp)
        val http = Settings(deviceId="lab",otlpEnabled=true,allowHttp=true,endpoint="http://192.168.1.10:4318/v1/metrics")
        assertTrue(http.valid())
        assertEquals(http,SettingsCodec.decode(SettingsCodec.encode(http)))
        val disabled=http.withHttpAllowed(false)
        assertFalse(disabled.otlpEnabled);assertFalse(disabled.allowHttp);assertTrue(disabled.valid())
        assertTrue(http.copy(endpoint="https://example.org/v1/metrics").withHttpAllowed(false).otlpEnabled)
    }
    @Test fun httpPermissionDoesNotAllowCredentialsQueriesBadPortsOrOtherSchemes() {
        listOf("http://a:secret@host/v1/metrics", "http://host/v1/metrics?x=y", "http://host/v1/metrics#x",
            "http://host:0/v1/metrics", "http://host:99999/v1/metrics", "ftp://host/v1/metrics").forEach {
            assertFalse(it,Settings.validEndpoint(it,true))
        }
    }
    @Test fun everyRetryNeedsPermissionAndRedirectCannotMovePayload() {
        MockWebServer().use { server ->
            server.start()
            val row=OutboxRow(createdAt=1,endpoint=server.url("/v1/metrics").toString(),payload=byteArrayOf(10,0))
            val sender=OtlpSender()
            assertThrows(HttpPolicyException::class.java) { sender.send(row) }
            assertEquals(0,server.requestCount)
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(503,sender.send(row,true))
            val request=server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertArrayEquals(row.payload,request.body.readByteArray())
            assertThrows(HttpPolicyException::class.java) { sender.send(row.copy(attempts=1),false) }
            assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location",server.url("/elsewhere")))
            assertEquals(307,sender.send(row,true))
            assertEquals(2,server.requestCount)
        }
    }
}
