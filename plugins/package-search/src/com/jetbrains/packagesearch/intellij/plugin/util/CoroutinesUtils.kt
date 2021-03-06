package com.jetbrains.packagesearch.intellij.plugin.util

import com.intellij.openapi.application.AppUIExecutor
import com.intellij.openapi.application.impl.coroutineDispatchingContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Duration

internal fun CoroutineScope.launchLoop(delay: Duration, function: suspend () -> Unit) = launch {
    while (true) {
        function()
        delay(delay)
    }
}

@Suppress("unused") // The receiver is technically unused
internal val Dispatchers.AppUI
    get() = AppUIExecutor.onUiThread().coroutineDispatchingContext()

internal fun <T> Flow<T>.replayOnSignal(signal: Flow<Any>) = channelFlow {
    var lastValue: T? = null
    onEach { send(it) }
        .onEach { lastValue = it }
        .launchIn(this)

    signal.mapNotNull { lastValue }
        .onEach { send(it) }
        .launchIn(this)
}

@Suppress("UNCHECKED_CAST")
fun <T1, T2, T3, T4, T5, T6, T7, R> combine(
    flow: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    flow6: Flow<T6>,
    flow7: Flow<T7>,
    transform: suspend (T1, T2, T3, T4, T5, T6, T7) -> R
): Flow<R> = combine(flow, flow2, flow3, flow4, flow5, flow6, flow7) { args: Array<Any?> ->
    transform(
        args[0] as T1,
        args[1] as T2,
        args[2] as T3,
        args[3] as T4,
        args[4] as T5,
        args[5] as T6,
        args[6] as T7,
    )
}
