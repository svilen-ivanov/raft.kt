package dev.svilenivanov.raftkt

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable


@Serializable
data class Position constructor(
    val index: Long,
    val term: Long,
)

interface LogProp {
    val position: Position
    val appendedAt: Instant
}

@Serializable
sealed class Log : LogProp {
    @Serializable
    data class Command<T>(
        override val position: Position,
        override val appendedAt: Instant,
        val data: T,
    ) : Log()

    @Serializable
    data class Nop(
        override val position: Position,
        override val appendedAt: Instant
    ) : Log()

    @Serializable
    data class Barrier(
        override val position: Position,
        override val appendedAt: Instant
    ) : Log()

    @Serializable
    data class Configuration(
        override val position: Position,
        override val appendedAt: Instant,
        val configuration: dev.svilenivanov.raftkt.Configuration
    ) : Log()
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

