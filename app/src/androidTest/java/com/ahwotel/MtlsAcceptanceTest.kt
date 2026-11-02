package com.ahwotel

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import android.security.KeyChain
import android.os.Build
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/** Test identity is generated into the test APK. Real KeyChain enrollment/grants are separate. */
class MtlsAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    @Before fun prepare(): Unit = runBlocking {
        profile.prepare()
        ui.activityRule.scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun restore(): Unit = runBlocking { profile.restore() }

    @Test fun nativeTlsWorkerSendsClientIdentityAndActualProtobuf(): Unit = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun bytes(name: String) = assets.open("mtls/$name").use { it.readBytes() }
        val password = bytes("client-password.txt").toString(Charsets.UTF_8).toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply { load(bytes("client.p12").inputStream(), password) }
        val key = keys.getKey("ahwotel-client", password) as PrivateKey
        val chain = keys.getCertificateChain("ahwotel-client").map { it as X509Certificate }.toTypedArray()
        val held = HeldCertificate.decode(bytes("server.crt").toString(Charsets.UTF_8) + bytes("server.key").toString(Charsets.UTF_8))
        val serverTls = HandshakeCertificates.Builder().heldCertificate(held).addTrustedCertificate(chain.last()).build()
        val original = app.sender
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false); server.requireClientAuth(); server.start()
            try {
                app.sender = OtlpSender(identity = { _, _ -> mtlsContext(key, chain, bytes("ca.crt")) })
                val endpoint = "https://127.0.0.1:${server.port}/v1/metrics"
                app.saveSettings(app.settings.value.copy(otlpEnabled = true, endpoint = endpoint, tls = TlsSettings("mtls", "test-key")))
                server.enqueue(MockResponse())
                val session = SessionRow("tls-native", app.settings.value.deviceId, System.currentTimeMillis(), reason = "test", configuration = "{}", continuous = false, durationSeconds = 1)
                val payload = Telemetry(session).use { wire -> wire.encode(SampleRow(sessionId = session.id,
                    time = session.startedAt, elapsed = 0, segment = 0, screenOn = true, memoryAvailable = 123456.0)) }
                app.db.dao().enqueue(OutboxRow(createdAt = System.currentTimeMillis(), endpoint = endpoint, payload = payload))
                app.scheduleUpload()
                val request = requireNotNull(server.takeRequest(30, TimeUnit.SECONDS))
                assertEquals(chain.first(), request.handshake!!.peerCertificates.first())
                assertArrayEquals(payload, request.body.readByteArray())
                withTimeout(10000) { while (app.db.dao().outboxCount() != 0L) delay(100) }
            } finally { app.saveSettings(app.settings.value.copy(otlpEnabled = false)); app.sender = original }
        }
    }

    @Test fun authenticationChangesClearQueueButNotLocalCollection(): Unit = runBlocking {
        val endpoint = "https://127.0.0.1:9/v1/metrics"
        app.saveSettings(app.settings.value.copy(otlpEnabled = true, endpoint = endpoint))
        val future = System.currentTimeMillis() + 3600000
        suspend fun enqueue() { app.db.dao().enqueue(OutboxRow(id = -32001, createdAt = System.currentTimeMillis(), endpoint = endpoint, payload = byteArrayOf(10, 0), dueAt = future)) }
        suspend fun remains() = app.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox WHERE id=-32001").use { it.moveToFirst(); it.getInt(0) }
        enqueue()
        app.saveSettings(app.settings.value.copy(tls = TlsSettings("mtls", "missing-device-key")))
        assertEquals(0, remains())
        enqueue()
        // Same alias/trust, including a renewed identity, keeps persisted packets.
        app.saveSettings(app.settings.value.copy(retentionDays = 15)); assertEquals(1, remains())
        app.saveSettings(app.settings.value.copy(tls = app.settings.value.tls.copy(alias = "different-device-key")))
        assertEquals(0, remains())
        enqueue()
        app.saveSettings(app.settings.value.copy(tls = app.settings.value.tls.copy(trust = "custom", trustRef = "a".repeat(64))))
        assertEquals(0, remains())
        app.saveSettings(app.settings.value.copy(continuous = true, enabled = setOf(CollectorKind.MEMORY),
            batterySettings = BatterySettings(enabled = false), selfTelemetry = SelfTelemetrySettings(enabled = false)))
        ui.onNodeWithText("Start monitoring").performClick()
        withTimeout(10000) { while (app.state.value.sessionId == null) delay(100) }
        val id = app.state.value.sessionId
        val before = app.db.dao().sampleCount()
        withTimeout(15000) { while (app.db.dao().sampleCount() <= before) delay(100) }
        assertEquals(id, app.state.value.sessionId)
        assertTrue(app.db.dao().outboxCount() > 0) // Key failure does not erase measurements or downgrade.
    }

    @Test fun certificateSettingsAndErrorsFollowInterfaceLanguage(): Unit = runBlocking {
        for (lang in listOf("en", "ru")) {
            app.saveSettings(app.settings.value.copy(language = lang, endpoint = "https://127.0.0.1:9/v1/metrics", tls = TlsSettings("mtls", "missing-key")))
            val ru = lang == "ru"
            val settings = if (ru) "Настройки" else "Settings"
            ui.waitUntil(10000) { ui.onAllNodes(hasText(settings) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasText(settings) and hasClickAction()).performClick()
            val list = ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            val chooser = if (ru) "Выбрать клиентский сертификат" else "Choose client certificate"
            list.performScrollToNode(hasText(chooser)); ui.onNodeWithText(chooser).assertIsDisplayed().assertIsEnabled()
            val button = if (ru) "Проверить соединение" else "Check connection"
            list.performScrollToNode(hasText(button)); ui.onNodeWithText(button).performScrollTo().assertIsDisplayed().performClick()
            val error = if (ru) "Клиентский ключ отсутствует" else "Client key is missing"
            try { ui.waitUntil(10000) { ui.onAllNodes(hasText(error, substring = true)).fetchSemanticsNodes().isNotEmpty() } }
            catch (e: Throwable) {
                val dump = ui.onRoot().printToString()
                java.io.File(app.filesDir, "tls-ui-failure.txt").writeText(dump)
                throw e
            }
            list.performScrollToNode(hasText(error, substring = true)); ui.onNode(hasText(error, substring = true)).assertIsDisplayed()
        }
    }

    @Test fun ruleEditorAndPortableExportFollowInterfaceLanguage(): Unit = runBlocking {
        for (lang in listOf("en", "ru")) {
            app.saveSettings(app.settings.value.copy(language = lang, tls = TlsSettings("mtls", "unique-reference",
                selection = "rule", rule = CertificateRule(sanUri = "urn:ahwotel:purpose"))))
            val settings = if(lang == "ru") "Настройки" else "Settings"
            ui.waitUntil(10000) { ui.onAllNodes(hasText(settings) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasText(settings) and hasClickAction()).performClick()
            val list = ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            val template = if(lang == "ru") "OID шаблона AD CS" else "AD CS template OID"
            list.performScrollToNode(hasText(template)); ui.onNodeWithText(template).performScrollTo().assertIsDisplayed()
            val uri = if(lang == "ru") "Точный SAN URI назначения" else "Exact purpose SAN URI"
            ui.onNode(hasText(uri) and hasSetTextAction()).performScrollTo().performTextReplacement("urn:ahwotel:shared-purpose")
            val save = if(lang == "ru") "Сохранить настройки" else "Save settings"
            list.performScrollToNode(hasText(save)); ui.onNodeWithText(save).performScrollTo().performClick()
            ui.waitUntil(10000) { app.settings.value.tls.rule.sanUri == "urn:ahwotel:shared-purpose" }
            val raw = DeploymentProfileCodec.encode(app.settings.value, "shared-policy", 1)
            assertFalse(raw.contains("unique-reference"))
            assertEquals("urn:ahwotel:shared-purpose", DeploymentProfileCodec.decode(raw).settings.tls.rule.sanUri)
        }
    }

    @Test fun manualKeyChainSelectionGrantsRealKeyAccessOnEmulator(): Unit = runBlocking {
        // Never install credentials or interact with a real user's certificate store.
        Assume.assumeTrue("Credential enrollment test is emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun bytes(name: String) = assets.open("mtls/$name").use { it.readBytes() }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // A failed prior enrollment can leave the old password dialog alive.
        device.executeShellCommand("am force-stop com.android.certinstaller")
        // Stable test-owned alias avoids an unbounded credential list on repeated runs.
        val alias = "000-ahwotel-acceptance-test"
        val password = bytes("client-password.txt").toString(Charsets.UTF_8)
        val held = HeldCertificate.decode(bytes("server.crt").toString(Charsets.UTF_8) + bytes("server.key").toString(Charsets.UTF_8))
        val ca = TlsTrustStore.certificates(bytes("ca.crt")).single()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(held).addTrustedCertificate(ca).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false); server.requireClientAuth(); server.start()
            val ref = TlsTrustStore.install(app, bytes("ca.crt"))
            app.saveSettings(app.settings.value.copy(language = "en", endpoint = "https://127.0.0.1:${server.port}/v1/metrics",
                tls = TlsSettings("mtls", "", "custom", ref)))
            ui.runOnUiThread {
                ui.activity.startActivity(KeyChain.createInstallIntent().putExtra(KeyChain.EXTRA_PKCS12, bytes("client.p12"))
                    .putExtra(KeyChain.EXTRA_NAME, alias))
            }
            val passwordField = device.wait(Until.findObject(By.pkg("com.android.certinstaller").clazz("android.widget.EditText")), 10000)
            assertNotNull("Certificate installer must ask for the test P12 password", passwordField)
            passwordField!!.text = password
            device.findObject(By.res("android", "button1")).click()
            val usage = device.wait(Until.findObject(By.res("com.android.certinstaller", "user_certificate")), 5000)
            if (usage != null) {
                usage.click()
                device.findObject(By.res("android", "button1")).click()
            }
            val nameField = device.wait(Until.findObject(By.res("com.android.certinstaller", "certificate_name")), 10000)
            if (nameField != null) {
                nameField.text = alias
                device.findObject(By.res("android", "button1")).click()
            }
            val returned = device.wait(Until.hasObject(By.pkg(app.packageName)), 10000)
            if (!returned) device.dumpWindowHierarchy(java.io.File(app.filesDir, "tls-installer.xml"))
            assertTrue("Certificate installer must return to the app", returned)
            ui.onNode(hasText("Settings") and hasClickAction()).performClick()
            val list = ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            list.performScrollToNode(hasText("Choose client certificate"))
            ui.onNodeWithText("Choose client certificate").performScrollTo().performClick()
            // Repeated runs retain test identities in KeyChain; the new alias may be off screen.
            device.wait(Until.hasObject(By.pkg("com.android.keychain")), 10000)
            UiScrollable(UiSelector().scrollable(true)).apply { setMaxSearchSwipes(30) }
                .scrollIntoView(UiSelector().text(alias))
            val selection = device.wait(Until.findObject(By.text(alias)), 10000)
            assertNotNull("KeyChain must offer the installed VPN/apps identity", selection)
            selection!!.click()
            device.findObject(By.res("android", "button1")).click()
            ui.waitForIdle()
            list.performScrollToNode(hasText("Save settings")); ui.onNodeWithText("Save settings").performClick()
            ui.waitUntil(10000) { app.settings.value.tls.alias == alias }
            server.enqueue(MockResponse())
            list.performScrollToNode(hasText("Check connection"))
            ui.onNodeWithText("Check connection").performScrollTo().performClick()
            val request = requireNotNull(server.takeRequest(30, TimeUnit.SECONDS))
            val client = request.handshake!!.peerCertificates.first() as X509Certificate
            assertEquals("1.3.6.1.5.5.7.3.2", client.extendedKeyUsage.single())
            assertTrue(client.issuerX500Principal == ca.subjectX500Principal)
            ui.waitUntil(10000) { ui.onAllNodes(hasText("TLS/HTTP connection accepted", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            val manual = app.settings.value
            val preview = manual.copy(tls=manual.tls.copy(selection="rule", rule=CertificateRule(sanUri="urn:ahwotel:test-device")))
            app.saveSettings(preview)
            ui.waitForIdle()
            ui.waitUntil(10000) { ui.onAllNodesWithText(ui.activity.getString(R.string.tls_preview_help)).fetchSemanticsNodes().isNotEmpty() }
            server.enqueue(MockResponse())
            list.performScrollToNode(hasText("Check connection")); ui.onNodeWithText("Check connection").performScrollTo().assertIsDisplayed().performClick()
            val previewRequest = server.takeRequest(30, TimeUnit.SECONDS)
            assertNotNull(ui.onRoot().printToString(), previewRequest)
            ui.waitUntil(10000) { ui.onAllNodes(hasText("TLS/HTTP connection accepted", substring=true)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("tls_binding_missing", assertThrows(TlsFailure::class.java) {
                runBlocking(Dispatchers.IO) { androidMtlsContext(app,preview.tls) }
            }.code)
            val policyId = "tls-rule-${System.currentTimeMillis()}"
            val policySettings = manual.copy(otlpEnabled = true, tls = manual.tls.copy(alias = "", selection = "rule",
                rule = CertificateRule(sanUri = "urn:ahwotel:test-device")))
            val portable = DeploymentProfileCodec.encode(policySettings, policyId, 1)
            assertFalse(portable.contains(alias))
            try {
                assertTrue(app.applyManagedConfiguration(android.os.Bundle().apply {
                    putString("management_action", "apply"); putString("deployment_profile", portable)
                    putString("tls_client_alias", alias); putString("tls_client_profile_id", policyId); putInt("tls_client_profile_revision", 1)
                }))
                server.enqueue(MockResponse())
                assertEquals(200, withContext(Dispatchers.IO) {
                    app.sender.send(OutboxRow(createdAt = System.currentTimeMillis(), endpoint = manual.endpoint, payload = byteArrayOf()),
                        context = app, tls = app.settings.value.tls)
                })
                app.db.dao().enqueue(OutboxRow(id = -33001, createdAt = System.currentTimeMillis(), endpoint = manual.endpoint,
                    payload = byteArrayOf(), dueAt = System.currentTimeMillis() + 3600000))
                assertTrue(app.applyManagedConfiguration(android.os.Bundle().apply {
                    putString("tls_client_alias", "renewal-pending"); putString("tls_client_profile_id", policyId); putInt("tls_client_profile_revision", 1)
                }))
                assertEquals(1L, app.db.dao().outboxCount())
                assertEquals("tls_key_access_denied", assertThrows(TlsFailure::class.java) {
                    runBlocking(Dispatchers.IO) { androidMtlsContext(app, app.settings.value.tls) }
                }.code)
            } finally {
                app.applyManagedConfiguration(android.os.Bundle().apply {
                    putString("management_action", "release"); putString("profile_id", policyId); putInt("profile_revision", 2)
                })
                app.saveSettings(manual)
            }
            InstrumentationRegistry.getArguments().getString("mtlsLabEndpoint")?.let { endpoint ->
                val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("runId"))
                val resultFile = java.io.File(app.filesDir, "mtls-lab-result.json")
                resultFile.delete()
                val fleetSettings = app.settings.value.copy(endpoint = endpoint, otlpEnabled = true, continuous = true,
                    intervalMs = 1000, durationSeconds = 6, enabled = setOf(CollectorKind.MEMORY),
                    tls = manual.tls.copy(alias = "", selection = "rule", rule = CertificateRule(sanUri = "urn:ahwotel:test-device")),
                    batterySettings = BatterySettings(enabled = false), selfTelemetry = SelfTelemetrySettings(enabled = false))
                val fleetId = "rule-worker-${System.currentTimeMillis()}"
                suspend fun policy(revision: Int, state: DesiredCollectionState) = app.applyManagedConfiguration(android.os.Bundle().apply {
                    putString("management_action", "apply")
                    putString("deployment_profile", DeploymentProfileCodec.encode(fleetSettings, fleetId, revision.toLong(), state))
                    putString("tls_client_alias", alias); putString("tls_client_profile_id", fleetId); putInt("tls_client_profile_revision", revision)
                })
                try {
                assertTrue(policy(1, DesiredCollectionState.RUNNING))
                withTimeout(10000) { while (app.state.value.sessionId == null) delay(100) }
                val session = app.state.value.sessionId!!
                withTimeout(30000) {
                    while (app.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM samples WHERE sessionId=?",arrayOf(session)).use { it.moveToFirst(); it.getLong(0) } < 6) delay(100)
                }
                assertTrue(policy(2, DesiredCollectionState.STOPPED))
                withTimeout(60000) { while (app.state.value.sessionId != null || app.db.dao().outboxCount() > 0) delay(100) }
                app.db.openHelper.readableDatabase.query("SELECT time,memoryAvailable FROM samples WHERE sessionId=? AND memoryAvailable IS NOT NULL ORDER BY time",
                    arrayOf(session)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    val points = org.json.JSONArray()
                    do { points.put(org.json.JSONObject().put("time", cursor.getLong(0)).put("value", cursor.getDouble(1))) } while (cursor.moveToNext())
                    assertTrue(points.length() >= 6)
                    resultFile.writeText(org.json.JSONObject().put("runId", runId).put("endpoint", endpoint)
                        .put("deviceId", app.settings.value.deviceId).put("sessionId", session).put("points", points).toString())
                }
                } finally {
                    app.applyManagedConfiguration(android.os.Bundle().apply {
                        putString("management_action", "release"); putString("profile_id", fleetId); putInt("profile_revision", 3)
                    })
                    app.saveSettings(manual)
                }
            }
        }
    }
}
