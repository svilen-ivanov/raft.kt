package dev.svilenivanov.raftkt

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant


sealed class LogType {
    object LogCommand : LogType()
    object LogNoop : LogType()
    object LogBarrier : LogType()
    object LogConfiguration : LogType()
}

@Serializable
data class Log<T, E> constructor(
    val index: Index,
    val type: Long,
    val data: T,
    val extensions: E,
    @Contextual
    val appendedAt: Instant
)

interface LogStore<T, E> {
    suspend fun firstIndex(): Long
    suspend fun lastIndex(): Long?
    suspend fun getLog(lastIndex: Long): Log<T, E>?
    suspend fun storeLog(log: Log<T, E>)
    suspend fun storeLogs(logs: Sequence<Log<T, E>>)
    // DeleteRange deletes a range of log entries. The range is inclusive.
    suspend fun deleteRange(range: LongRange)
}

