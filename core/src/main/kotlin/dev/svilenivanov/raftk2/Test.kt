package dev.svilenivanov.raftk2

import dev.svilenivanov.raftkt.Config
import dev.svilenivanov.raftkt.Rpc
import dev.svilenivanov.raftkt.RpcHeader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Suppress("UNUSED_PARAMETER")
class Rpc(config: Config) {
    val header = RpcHeader(config.protocolVersion)

    fun Flow<Rpc.Request>.respond(): Flow<Rpc.Response?> = map {
        when (it) {
            is Rpc.AppendEntriesRequest -> appendEntries(it)
            else -> null
        }
    }

    private suspend fun appendEntries(req: Rpc.AppendEntriesRequest): Rpc.AppendEntriesResponse {
        return Rpc.AppendEntriesResponse(
            header = header,
            term = 0L,
            lastLog = 0L,
            success = false,
            noRetryBackoff = false
        )
    }
}




