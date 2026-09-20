package com.ahwotel

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

data class LoadSnapshot<K, V>(val request: K, val value: V)

data class RetainedLoadState<K, V>(
    val snapshot: LoadSnapshot<K, V>? = null,
    val pending: K? = null,
    val failedRequest: K? = null,
) {
    val loading: Boolean get() = pending != null
    val failed: Boolean get() = failedRequest != null
    fun isCurrent(request: K): Boolean = snapshot?.request == request
}

fun <K, V> loadStarted(state: RetainedLoadState<K, V>, request: K) =
    state.copy(pending = request, failedRequest = null)

fun <K, V> loadSucceeded(state: RetainedLoadState<K, V>, request: K, value: V) =
    state.copy(snapshot = LoadSnapshot(request, value), pending = null, failedRequest = null)

fun <K, V> loadFailed(state: RetainedLoadState<K, V>, request: K? = state.pending) =
    state.copy(pending = null, failedRequest = request)

/** Keeps the last completed snapshot while refreshes are serialized and conflated. */
@Composable
fun <K : Any, V> rememberRetainedLoad(
    resetKey: Any?,
    request: K,
    retryKey: Any? = null,
    load: suspend (K) -> V,
    onError: (Throwable) -> Unit = {},
): RetainedLoadState<K, V> {
    var state by remember(resetKey) { mutableStateOf(RetainedLoadState<K, V>(pending = request)) }
    val currentRequest by rememberUpdatedState(request)
    val currentLoad by rememberUpdatedState(load)
    val currentError by rememberUpdatedState(onError)
    LaunchedEffect(resetKey,retryKey) {
        snapshotFlow { currentRequest }.distinctUntilChanged().conflate().collect { target ->
            state = loadStarted(state, target)
            try {
                val value = withContext(Dispatchers.IO) { currentLoad(target) }
                state = loadSucceeded(state, target, value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                state = loadFailed(state,target)
                currentError(error)
            }
        }
    }
    return state
}
