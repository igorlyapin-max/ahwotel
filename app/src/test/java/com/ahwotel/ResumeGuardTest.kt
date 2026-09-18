package com.ahwotel

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ResumeGuardTest {
    @get:Rule val temporary = TemporaryFolder()
    private val settings = Settings(deviceId = "guard-test", continuous = true, resumeOnOpen = true, resumeOnBoot = true)
    private fun store(scope: CoroutineScope, name: String) = SettingsStore(PreferenceDataStoreFactory.create(
        scope = scope, produceFile = { java.io.File(temporary.root, "$name.preferences_pb") }))

    @Test fun missingGuardBlocksOldArmedRowUntilExplicitStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store(scope, "missing"); store.save(settings)
            val guard = ResumeGuard({ store }) { }; guard.restore()
            for (trigger in ResumeTrigger.entries) assertEquals("resume_blocked",
                ResumePolicy.rejection(ResumeRow(armed = true), settings, trigger, guard.blocked.value))
            guard.block(); guard.allow()
            for (trigger in ResumeTrigger.entries) assertNull(
                ResumePolicy.rejection(ResumeRow(armed = true), settings, trigger, guard.blocked.value))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun processDeathAtEachTimingCommitBoundaryCannotReviveContinuous() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (checkpoint in listOf("before_room", "during_room", "after_room")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val db = Room.inMemoryDatabaseBuilder(context, MonitorDatabase::class.java).build()
            try {
                val store = store(scope, checkpoint); store.save(settings)
                val guard = ResumeGuard({ store }) { }
                guard.block(); db.resumeDao().put(ResumeRow(armed = true, configuration = SettingsCodec.encode(settings))); guard.allow()
                val timed = settings.copy(continuous = false)
                guard.block(timed) // Both the configuration and the veto have reached DataStore.
                if (checkpoint != "before_room") {
                    try {
                        db.withTransaction {
                            db.resumeDao().put(ResumeRow(armed = false, configuration = SettingsCodec.encode(timed)))
                            if (checkpoint == "during_room") throw SimulatedDeath()
                        }
                    } catch (_: SimulatedDeath) { /* No application compensation, as with process death. */ }
                }
                scope.coroutineContext[Job]!!.cancelAndJoin()
                val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val freshStore = store(restartedScope, checkpoint)
                    val freshGuard = ResumeGuard({ freshStore }) { }; freshGuard.restore()
                    assertFalse(freshStore.changes.first().continuous)
                    for (trigger in ResumeTrigger.entries) assertEquals("resume_blocked",
                        ResumePolicy.rejection(db.resumeDao().get(), freshStore.changes.first(), trigger, freshGuard.blocked.value))
                } finally { restartedScope.coroutineContext[Job]!!.cancelAndJoin() }
            } finally { db.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun failedGuardWriteBlocksThisProcessWithoutClaimingDurability() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val persistent = store(scope, "failure"); persistent.save(settings); persistent.setResumeBlocked(false)
            var selected = persistent
            val guard = ResumeGuard({ selected }) { }; guard.restore(); assertFalse(guard.blocked.value)
            selected = SettingsStore(object : DataStore<Preferences> {
                override val data = persistent.store.data
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("injected")
            })
            assertTrue(runCatching { guard.block() }.isFailure)
            assertTrue(guard.blocked.value)
            assertEquals("resume_guard_write_failed", guard.issue.value)
            assertFalse(persistent.resumeBlocked.first()) // Cannot promise persistence when storage refused it.
            guard.failed("resume_state_write_failed")
            assertEquals("resume_guard_write_failed", guard.issue.value)
            selected = persistent; guard.block(); assertTrue(persistent.resumeBlocked.first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun settingsCompensationNeverLiftsVetoAndSotiOverrideRemainsValid() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store(scope, "override"); val guard = ResumeGuard({ store }) { }
            store.save(settings); guard.block(settings.copy(continuous = false))
            store.save(settings) // Compensation restores settings only.
            assertTrue(store.resumeBlocked.first())
            val defaults = settings.copy(continuous = false); store.save(defaults)
            guard.block(); guard.allow() // Explicit continuous Start committed in Room, despite timed defaults.
            for (trigger in ResumeTrigger.entries) assertNull(ResumePolicy.rejection(
                ResumeRow(armed = true, configuration = SettingsCodec.encode(settings)), defaults, trigger, guard.blocked.value))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    private class SimulatedDeath : Error()
}
