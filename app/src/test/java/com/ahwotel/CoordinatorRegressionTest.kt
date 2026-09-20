package com.ahwotel

import android.os.SystemClock
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.robolectric.util.ReflectionHelpers
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class CoordinatorRegressionTest {
    private fun application(): MonitorApp {
        // Attach a context, but do not start application workers or the foreground service.
        val app=MonitorApp()
        ReflectionHelpers.callInstanceMethod<Void>(app,"attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java,ApplicationProvider.getApplicationContext<Context>()))
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions("com.ahwotel.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        app.db=Room.inMemoryDatabaseBuilder(app,MonitorDatabase::class.java).allowMainThreadQueries().build()
        app.logs=Diagnostics(app); app.sources=SourceRegistry(app.logs); app.battery=BatteryTelemetry(app)
        return app
    }
    @Test fun realCoordinatorPersistsMaximumAcrossWindowAndFinalFlush()=runBlocking {
        val app=application()
        val settings=Settings(deviceId="test",continuous=true,batterySettings=BatterySettings(enabled=false),
            selfTelemetry=SelfTelemetrySettings(groups=setOf(SelfGroup.RUNTIME)))
        app.settings.value=settings
        val now=SystemClock.elapsedRealtime()
        val session=SessionRow("max-window","test",System.currentTimeMillis(),reason="",configuration=SettingsCodec.encode(settings),continuous=true,durationSeconds=300)
        app.db.dao().start(session); app.sessionTiming=SessionTiming(session.id,now,true,300)
        val coordinator=AgentTelemetryCoordinator(app)
        try {
            coordinator.start(session,settings);app.costs.wakeAcquire(now,600000)
            coordinator.tick(false)
            repeat(300) { ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); coordinator.tick(false) }
            val rows=app.db.agentDao().page(0,0,Long.MAX_VALUE,session.id).filter { it.metric=="WAKE_MAX" }
            assertEquals(300000.0,rows.single().value!!,0.0)
            coordinator.stop()
            assertEquals(1.0,app.db.agentDao().page(0,0,Long.MAX_VALUE,session.id)
                .filter { it.metric=="WAKE_COUNT" }.sumOf { it.value ?: 0.0 },0.0)
            assertEquals(300000.0,app.db.agentDao().page(0,0,Long.MAX_VALUE,session.id).first { it.metric=="WAKE_MAX" }.value!!,0.0)
        } finally { app.db.close() }
    }
    @Test fun batteryPersistsAtOwnCadenceWithoutWaitingForSelfWindow()=runBlocking {
        val app=application()
        val settings=Settings(deviceId="battery",continuous=true,batterySettings=BatterySettings(wear=false,passport=false),
            selfTelemetry=SelfTelemetrySettings(enabled=false))
        app.settings.value=settings
        val now=SystemClock.elapsedRealtime()
        val session=SessionRow("battery-cadence","battery",System.currentTimeMillis(),reason="",configuration=SettingsCodec.encode(settings),continuous=true,durationSeconds=300)
        app.db.dao().start(session); app.sessionTiming=SessionTiming(session.id,now,true,300)
        val coordinator=AgentTelemetryCoordinator(app)
        try {
            coordinator.start(session,settings); coordinator.tick(false)
            val first=app.db.agentDao().page(0,0,Long.MAX_VALUE,session.id).filter { it.stream=="battery" }
            assertTrue(first.any { it.metric=="TEMP" }); assertTrue(first.any { it.metric=="LEVEL" })
            assertNotEquals(Availability.DISABLED,app.sources.reports.value[Metric.TEMPERATURE]?.status)
            repeat(30) { ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); coordinator.tick(false) }
            assertEquals(first.size,app.db.agentDao().page(0,0,Long.MAX_VALUE,session.id).size)
            repeat(30) { ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); coordinator.tick(false) }
            assertEquals(first.size*2,app.db.agentDao().page(0,0,Long.MAX_VALUE,session.id).size)
            coordinator.stop()
            assertEquals(first.size*2,app.db.agentDao().page(0,0,Long.MAX_VALUE,session.id).size)
        } finally { app.db.close() }
    }
    @Test fun byteQuotaStillRemovesOldestFuturePacket()=runBlocking {
        val app=application()
        app.settings.value=Settings(deviceId="quota",queueMiB=1,queueHours=1)
        val now=System.currentTimeMillis()
        try {
            for(i in 0..1) app.db.dao().enqueue(OutboxRow(createdAt=now+i,endpoint="x",payload=ByteArray(600000),
                stream="wear",dueAt=now+86400000))
            app.maintenance()
            assertEquals(1L,app.db.dao().outboxCount())
            assertEquals(now+1,app.db.dao().firstPending()!!.createdAt)
        } finally { app.db.close() }
    }
}
