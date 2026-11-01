package com.ahwotel

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.ahwotel.oem.BoundedProviderWorker
import com.ahwotel.oem.ProviderBatch
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=android.app.Application::class)
class ReviewCode31Test {
    @Test fun lockingBetweenWorkerDispatchAndReadPreventsNextProviderCall() = runBlocking {
        val locked = AtomicBoolean(false)
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        var reads = 0
        val worker = BoundedProviderWorker("review31")
        val task = async(Dispatchers.Default) { worker.run {
            entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS))
            readIfAllowed({ locked.get() }) { reads++; ProviderBatch(emptyList()) } ?: ProviderBatch(emptyList())
        } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            locked.set(true)
        } finally { proceed.countDown() }
        assertNotNull(task.await().first); assertEquals(0, reads)
        locked.set(false)
        readIfAllowed({ locked.get() }) { reads++ }
        assertEquals(1, reads)
    }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs() = context.getSharedPreferences("managed_configuration", 0)
    private fun managed() = ManagedConfiguration(context, Diagnostics(context))
    private fun installV1(raw: String) {
        val identity = DeploymentProfileCodec.storedIdentity(raw)
        val floor = JSONObject().put(identity.profileId, JSONObject().put("revision", identity.revision).put("fingerprint", identity.fingerprint))
        prefs().edit().clear().putString("deploymentProfile", raw).putString("revisionFloors", floor.toString()).commit()
    }
    private fun release(id: String="fleet", revision: Int=8) = Bundle().apply {
        putString("management_action", "release"); putString("profile_id", id); putInt("profile_revision", revision)
    }
    @Test fun storedV1CanReleaseWithoutApplyingV2AndCannotReplay() {
        val v1 = JSONObject(DeploymentProfileCodec.encode(Settings(deviceId="test"), "fleet", 7)).put("schemaVersion", 1).toString()
        installV1(v1)
        val manager = managed()
        assertTrue(manager.active.value); assertNull(manager.currentProfile())
        assertEquals("fleet", manager.profileId.value)
        assertFalse(manager.replace(release("other")))
        assertFalse(manager.replace(release(revision=7)))
        assertTrue(manager.replace(release()))
        assertFalse(manager.active.value)
        val restarted = managed()
        assertFalse(restarted.active.value)
        assertFalse(restarted.replace(release()))
        val local = Settings(deviceId="test", pauseWhenLocked=true, intervalMs=5000)
        assertEquals(local, restarted.apply(local))
    }
    @Test fun corruptedStoredEnvelopeCannotAuthorizeRelease() {
        val v1 = JSONObject(DeploymentProfileCodec.encode(Settings(deviceId="test"), "fleet", 7)).put("schemaVersion", 1).toString()
        installV1(v1)
        prefs().edit().putString("deploymentProfile", JSONObject(v1).put("profileId", "tampered").toString()).commit()
        assertFalse(managed().replace(release("tampered")))
        assertTrue(managed().active.value)
    }
    @Test fun lockBetweenReadingAndCommitSkipsThenResumesSameSession() = runBlocking {
        val ready = CompletableDeferred<Unit>(); val finishRead = CompletableDeferred<Unit>()
        var locked = false; var writes = 0
        val timing = SessionTiming("same-session", 0, true, 300)
        val pending = async {
            ready.complete(Unit); finishRead.await()
            commitSample({ locked }, { if (timing.canRecord("same-session", 100)) null else "expired" }, { writes++ })
        }
        ready.await(); locked = true; finishRead.complete(Unit)
        assertEquals(SampleCommit.Paused, pending.await()); assertEquals(0, writes)
        locked = false
        assertEquals(SampleCommit.Recorded, commitSample({ locked }, { null }, { writes++ }))
        assertEquals(1, writes); assertTrue(timing.canRecord("same-session", 200))
        assertEquals(SampleCommit.Finished("timer_expired"), commitSample({ true }, { "timer_expired" }, { writes++ }))
        assertEquals(1, writes)
    }
    @Test fun primitiveManagedLockSettingReachesRuntimePolicy() {
        prefs().edit().clear().commit()
        val manager = managed()
        assertTrue(manager.replace(Bundle().apply { putBoolean("pause_when_locked", true) }))
        val effective = manager.apply(Settings(deviceId="test"))
        assertEquals(CollectionPauseReason.LOCKED, collectionPauseReason(effective, true, true))
        assertNull(collectionPauseReason(effective, true, false))
        assertTrue(manager.keepLocalManagedFields(effective.copy(pauseWhenLocked=false), effective).pauseWhenLocked)
    }
    @Test fun invalidDraftCanRoundTripWithoutWeakeningSavedSettingsValidation() {
        val raw = SettingsCodec.encode(Settings(deviceId="test", endpoint="broken", otlpEnabled=true))
        assertEquals("broken", SettingsCodec.decode(raw, validate=false).endpoint)
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.decode(raw) }
    }
}
