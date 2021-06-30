package dev.svilenivanov.raftkt.core

import kotlinx.coroutines.CoroutineExceptionHandler
import org.slf4j.Logger
import org.slf4j.Marker
import kotlin.random.Random
import kotlin.time.Duration

fun Logger.asExceptionHandler(marker: Marker) = CoroutineExceptionHandler { _, e ->
    error(marker, "Uncaught exception in coroutine", e)
}

internal fun calcRandomTimeout(timeout: Duration): Duration {
    return Duration.milliseconds(timeout.inWholeMilliseconds + Random.nextLong(timeout.inWholeMilliseconds))
}
