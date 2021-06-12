package dev.svilenivanov.raftkt.inmem

import dev.svilenivanov.raftkt.*
import kotlinx.coroutines.channels.ReceiveChannel

class InmemTransport(
    override val consumerCh: ReceiveChannel<Message<Rpc.Request, Rpc.Response>>,
    override val localAddr: ServerAddress
) : Transport {
    override fun setHeartbeatHandler(f: suspend (rpc: Message<Rpc.Request, Rpc.Response>) -> Unit) {
        TODO("Not yet implemented")
    }

    override suspend fun requestVote(peer: Peer, req: Rpc.RequestVoteRequest): Rpc.RequestVoteResponse {
        TODO("Not yet implemented")
    }

    override suspend fun timoutNow(peer: Peer) {
        TODO("Not yet implemented")
    }

    override fun appendEntries(peer: Peer, req: Rpc.AppendEntriesRequest): Rpc.AppendEntriesResponse {
        TODO("Not yet implemented")
    }

}
