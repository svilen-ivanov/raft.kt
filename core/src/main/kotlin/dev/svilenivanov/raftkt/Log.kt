package dev.svilenivanov.raftkt

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable


enum class LogType {
    // LogCommand is applied to a user FSM.
    LOG_COMMAND,
    // LogNoop is used to assert leadership.
    LOG_NOOP,
    // LogBarrier is used to ensure all preceding operations have been
    // applied to the FSM. It is similar to LogNoop, but instead of returning
    // once committed, it only returns once the FSM manager acks it. Otherwise
    // it is possible there are operations committed but not yet applied to
    // the FSM.
    LOG_BARRIER,
    // LogConfiguration establishes a membership change configuration. It is
    // created when a server is added, removed, promoted, etc. Only used
    // when protocol version 1 or greater is in use.
    LOG_CONFIGURATION
}

@Serializable
data class Log<T, E> constructor(
    val index: Long,
    val term: Long,
    val type: LogType,
    val data: T,
    val extensions: E,
    val appendedAt: Instant
)

interface LogStore<T, E> {
    suspend fun firstIndex(): Long?
    suspend fun lastIndex(): Long?
    suspend fun getLog(index: Long): Log<T, E>?
    suspend fun storeLog(log: Log<T, E>)
    suspend fun storeLogs(entries: Sequence<Log<T, E>>)

    // DeleteRange deletes a range of log entries. The range is inclusive.
    suspend fun deleteRange(range: LongRange)
}

