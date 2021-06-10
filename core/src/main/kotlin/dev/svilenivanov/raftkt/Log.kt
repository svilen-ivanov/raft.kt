package dev.svilenivanov.raftkt

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable


@Serializable
data class Position constructor(
    val index: Long,
    val term: Long,
)

@Serializable
data class Log(
    val position: Position,
    val appendedAt: Instant,
    val data: Data
) {
    @Serializable
    sealed class Data {
        @Serializable
        data class Command<T>(
            val data: T,
        ) : Data()

        @Serializable
        object Noop : Data()

        @Serializable
        object Barrier : Data()

        @Serializable
        data class Configuration(
            val configuration: dev.svilenivanov.raftkt.Configuration
        ) : Data()
    }
}


interface LogStore {
    suspend fun firstIndex(): Long?
    suspend fun lastIndex(): Long?
    suspend fun getLog(index: Long): Log?
    suspend fun storeLog(log: Log)
    suspend fun storeLogs(entries: Sequence<Log>)

    // DeleteRange deletes a range of log entries. The range is inclusive.
    suspend fun deleteRange(range: LongRange)
}

