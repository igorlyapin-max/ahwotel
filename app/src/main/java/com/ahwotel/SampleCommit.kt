package com.ahwotel

internal sealed interface SampleCommit {
    data object Recorded : SampleCommit
    data object Paused : SampleCommit
    data class Finished(val reason: String) : SampleCommit
}

/** Called under the session mutex. Pausing skips a sample, never finishes the session. */
internal suspend fun commitSample(
    paused: () -> Boolean,
    completion: suspend (Boolean) -> String?,
    write: suspend () -> Unit,
): SampleCommit {
    val pause = paused()
    completion(pause)?.let { return SampleCommit.Finished(it) }
    if (pause) return SampleCommit.Paused
    write()
    return SampleCommit.Recorded
}
