@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable

@Serializable
data class Peer(val id: ServerId, val address: ServerAddress)

interface Transport {
    fun setHeartbeatHandler(f: suspend (rpc: Message<Rpc.Request, Rpc.Response>) -> Unit)
    suspend fun requestVote(peer: Peer, req: Rpc.RequestVoteRequest): Rpc.RequestVoteResponse
    suspend fun timoutNow(peer: Peer)
    fun appendEntriesPipeline(peer: Peer): AppendPipeline {
        TODO()
    }

    fun appendEntries(peer: Peer, req: Rpc.AppendEntriesRequest): Rpc.AppendEntriesResponse
    fun installSnapshot(
        peer: Peer,
        req: Rpc.InstallSnapshotRequest,
        snapshot: ReadCloser
    ): Rpc.InstallSnapshotResponse {
        TODO("Not yet implemented")
    }

    val consumer: ReceiveChannel<Message<Rpc.Request, Rpc.Response>>
    val localAddr: ServerAddress
}

class AppendPipeline {
    suspend fun close() {}
    suspend fun appendEntries(req: Rpc.AppendEntriesRequest) {
            TODO("Not yet implemented")
    }
    // Consumer returns a channel that can be used to consume
    // response futures when they are ready.
    val consumer = Channel<AppendFuture>()
}
