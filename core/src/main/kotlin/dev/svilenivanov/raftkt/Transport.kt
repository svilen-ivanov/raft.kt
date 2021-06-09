package dev.svilenivanov.raftkt

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable

@Serializable
data class Peer(val id: ServerId, val address: ServerAddress)

interface Transport {
    fun setHeartbeatHandler(f: suspend (rpc: Message<Rpc.Request, Rpc.Response>) -> Unit)
    suspend fun requestVote(peer: Peer, req: Rpc.RequestVoteRequest): Rpc.RequestVoteResponse
    suspend fun timoutNow(peer: Peer)

    val consumer: ReceiveChannel<Message<Rpc.Request, Rpc.Response>>
    val localAddr: ServerAddress
}
