package com.ahwotel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

enum class ResumeTrigger { OPEN, BOOT, PROCESS }

fun resumeIssueText(code: String): Int = when (code) {
    "resume_guard_write_failed" -> R.string.resume_guard_write_failed
    "resume_state_write_failed" -> R.string.resume_state_write_failed
    else -> R.string.resume_change_incomplete
}

@Entity(tableName = "resume_state")
data class ResumeRow(@PrimaryKey val id: Int = 1, val armed: Boolean = false,
    val configuration: String = "", val trigger: String = "none", val result: String = "not_armed")

@Dao interface ResumeDao {
    @Query("SELECT * FROM resume_state WHERE id=1") suspend fun get(): ResumeRow?
    @Query("SELECT * FROM resume_state WHERE id=1") fun observe(): Flow<ResumeRow?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: ResumeRow)
}

object ResumePolicy {
    fun rejection(row: ResumeRow?, settings: Settings, trigger: ResumeTrigger, blocked: Boolean): String? = when {
        !settings.monitoringEnabled -> "monitoring_disabled"
        blocked -> "resume_blocked"
        row?.armed != true -> "not_armed"
        trigger == ResumeTrigger.OPEN && !settings.resumeOnOpen -> "option_disabled"
        trigger == ResumeTrigger.BOOT && !settings.resumeOnBoot -> "option_disabled"
        trigger == ResumeTrigger.PROCESS && !settings.resumeOnOpen && !settings.resumeOnBoot -> "option_disabled"
        else -> null
    }
}

/** DataStore veto survives a Room rollback. All operations are serialized by MonitorApp.mutex. */
class ResumeGuard(private val store: () -> SettingsStore, private val log: (String) -> Unit) {
    val blocked = MutableStateFlow(true)
    val issue = MutableStateFlow<String?>(null)
    suspend fun restore() { blocked.value = store().resumeBlocked.first() }
    fun blockInMemory() { blocked.value = true }
    fun failed(code: String) {
        // A failed durable veto must not be hidden by a secondary Room error.
        if (issue.value != "resume_guard_write_failed") issue.value = code
        log(code)
    }
    suspend fun block(settings: Settings? = null) {
        blockInMemory()
        try {
            if (settings == null) store().setResumeBlocked(true) else store().save(settings, blockResume = true)
            if (issue.value == "resume_guard_write_failed") issue.value = null
        } catch (e: Exception) { failed("resume_guard_write_failed"); throw e }
    }
    suspend fun allow() {
        try {
            store().setResumeBlocked(false)
            blocked.value = false
            issue.value = null
        } catch (e: Exception) { failed("resume_guard_write_failed"); throw e }
    }
}

/** Only explicit lifecycle entrypoints call this; uploader/Application startup never does. */
suspend fun MonitorApp.requestResume(trigger: ResumeTrigger) {
    try {
        ready.await()
        val allowed = mutex.withLock {
            if (state.value.sessionId != null) return@withLock false
            val row = db.resumeDao().get() ?: ResumeRow()
            val rejection = ResumePolicy.rejection(row, settings.value, trigger, resumeGuard.blocked.value)
            db.resumeDao().put(row.copy(armed = row.armed && settings.value.monitoringEnabled,
                trigger = trigger.name.lowercase(), result = rejection ?: "requested"))
            rejection == null
        }
        if (allowed) MonitoringService.resume(this, trigger)
    } catch (_: Exception) {
        logs.event("resume_start_failed", error = true)
        runCatching { mutex.withLock {
            val row = db.resumeDao().get() ?: ResumeRow()
            db.resumeDao().put(row.copy(trigger = trigger.name.lowercase(), result = "start_failed"))
        } }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as MonitorApp
        app.scope.launch { try { app.requestResume(ResumeTrigger.BOOT) } finally { pending.finish() } }
    }
}
