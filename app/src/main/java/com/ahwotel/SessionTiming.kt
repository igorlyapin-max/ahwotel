package com.ahwotel

enum class SessionMode { TIMED, CONTINUOUS }

/** Process-local monotonic clock. Access to the active instance is serialized by MonitorApp.mutex. */
data class SessionTiming(val sessionId: String, val startedElapsedMs: Long,
    val continuous: Boolean, val durationSeconds: Long, val stopReason: String? = null) {
    val mode get() = if (continuous) SessionMode.CONTINUOUS else SessionMode.TIMED
    val deadline get() = if (continuous) Long.MAX_VALUE else startedElapsedMs + durationSeconds * 1000
    fun remaining(now: Long): Long? = if (continuous) null else ((deadline - now).coerceAtLeast(0) + 999) / 1000
    fun expired(now: Long) = !continuous && now >= deadline
    fun canRecord(id: String, now: Long) = id == sessionId && stopReason == null && !expired(now)
    fun stopped(reason: String) = if (stopReason == null) copy(stopReason = reason) else this

    /** Preserve explicit START/SOTI arguments when unrelated settings are saved. */
    fun changedSettings(old: Settings, next: Settings): SessionTiming = if (stopReason != null) this else copy(
        continuous = if (old.continuous != next.continuous) next.continuous else continuous,
        durationSeconds = if (old.durationSeconds != next.durationSeconds) next.durationSeconds else durationSeconds)

    fun runtime(paused: Boolean, now: Long) = RuntimeState(sessionId, paused, remaining(now), mode = mode)
}
