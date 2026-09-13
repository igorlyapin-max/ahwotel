package com.ahwotel

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UploadAcceptanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val app get()=ui.activity.application as MonitorApp
    private val profile by lazy { IsolatedTestProfile(app) }
    @Before fun prepare(): Unit=runBlocking { profile.prepare()
        ui.activityRule.scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun restore(): Unit=runBlocking { profile.restore() }

    @Test fun backlogRetriesStayBoundedAndFutureDeadlineCanMoveEarlier(): Unit=runBlocking {
        val original=app.sender
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1")
            .validityInterval(0,4102444800000).build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server=MockWebServer()
        val calls=AtomicInteger()
        server.useHttps(serverTls.sslSocketFactory(),false)
        server.protocols=listOf(Protocol.HTTP_1_1)
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest)=MockResponse().setResponseCode(if(calls.incrementAndGet()<=100) 503 else 200)
        }
        server.start()
        try {
            app.sender=OtlpSender(OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager)
                .protocols(listOf(Protocol.HTTP_1_1)).callTimeout(5,TimeUnit.SECONDS).build())
            val endpoint="https://127.0.0.1:${server.port}/v1/metrics"
            app.saveSettings(app.settings.value.copy(otlpEnabled=true,endpoint=endpoint))
            val now=System.currentTimeMillis()
            app.mutex.lock()
            try { repeat(1000) { app.db.dao().enqueue(OutboxRow(createdAt=now,endpoint=endpoint,payload=byteArrayOf(1),dueAt=now-2000000+it*2000)) } }
            finally { app.mutex.unlock() }
            app.uploads.reconcile()
            var maximum=0
            withTimeout(240000) {
                while(app.db.dao().outboxCount()>0) {
                    val active=WorkManager.getInstance(app).getWorkInfos(UploadScheduler.activeQuery()).get()
                    maximum=maxOf(maximum,active.size)
                    assertTrue("Unfinished upload work: ${active.size}",active.size<=2)
                    app.scheduleUpload() // producer races with worker completion
                    delay(30)
                }
            }
            assertTrue(calls.get()>=1100);assertTrue(maximum>0)
            // A future packet must not hold a newly ready stream behind its deadline.
            val before=calls.get();val future=System.currentTimeMillis()+3600000
            app.db.dao().enqueue(OutboxRow(createdAt=System.currentTimeMillis(),endpoint=endpoint,payload=byteArrayOf(2),dueAt=future))
            app.uploads.reconcile();delay(1000);assertEquals(before,calls.get())
            app.db.dao().enqueue(OutboxRow(createdAt=System.currentTimeMillis(),endpoint=endpoint,payload=byteArrayOf(3)))
            app.uploads.reconcile()
            withTimeout(20000) { while(calls.get()==before) delay(50) }
            assertEquals(1L,app.db.dao().outboxCount())
            // Startup recovery is idempotent and derives scheduling from the persisted outbox.
            val manager=WorkManager.getInstance(app)
            val legacy=OneTimeWorkRequestBuilder<OtlpUploadWorker>().addTag("com.ahwotel.UploadWorker")
                .setInitialDelay(1,TimeUnit.DAYS).build()
            manager.enqueueUniqueWork("upload-code10-legacy-fixture",ExistingWorkPolicy.KEEP,legacy).result.get()
            UploadScheduler(app).recover();app.uploads.recover()
            assertEquals(WorkInfo.State.CANCELLED,requireNotNull(manager.getWorkInfoById(legacy.id).get()).state)
            assertTrue(WorkManager.getInstance(app).getWorkInfos(UploadScheduler.activeQuery()).get().size<=2)
            app.saveSettings(app.settings.value.copy(endpoint="https://127.0.0.1:9/v1/metrics"))
            assertEquals(0L,app.db.dao().outboxCount())
            withTimeout(10000) { while(manager.getWorkInfos(UploadScheduler.activeQuery()).get().isNotEmpty()) delay(50) }
            app.db.dao().enqueue(OutboxRow(createdAt=System.currentTimeMillis(),endpoint=app.settings.value.endpoint,payload=byteArrayOf(4),dueAt=future))
            app.uploads.reconcile()
            app.saveSettings(app.settings.value.copy(otlpEnabled=false))
            withTimeout(10000) { while(WorkManager.getInstance(app).getWorkInfos(UploadScheduler.activeQuery()).get().isNotEmpty()) delay(50) }
            assertEquals(0L,app.db.dao().outboxCount())
        } finally {
            app.saveSettings(app.settings.value.copy(otlpEnabled=false));app.sender=original;server.shutdown()
        }
    }
}
