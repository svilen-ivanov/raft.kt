package dev.svilenivanov.raftkt.core

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Log(
    val position: Position,
    val appendedAt: Instant,
    val data: Data
) {
    @Serializable
    sealed class Data {
        @Serializable
        data class CommandData(
            val command: CommandRequest,
        ) : Data()

        @Serializable
        object Noop : Data()

        @Serializable
        object Barrier : Data()

        @Serializable
        data class Configuration(
            val configuration: String
        ) : Data()
    }
}

interface LogStore {
    suspend fun getLog(index: Long): Log?
    suspend fun storeLog(log: Log)

    // DeleteRange deletes a range of log entries. The range is inclusive.
    suspend fun deleteRange(range: LongRange)
}
