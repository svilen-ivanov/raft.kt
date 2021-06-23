package dev.svilenivanov.raftkt.core2

import java.util.concurrent.ConcurrentHashMap


class InmemTransport : Transport {
    override lateinit var ingress: suspend (Rpc.Request) -> Rpc.Response

    private val connections = ConcurrentHashMap<Node, Connection>()

    override suspend fun appendEntries(peer: Node, req: Rpc.AppendEntriesRequest): Rpc.AppendEntriesResponse {
        return request(peer, req) as Rpc.AppendEntriesResponse
    }

    override suspend fun requestVote(peer: Node, req: Rpc.RequestVoteRequest): Rpc.RequestVoteResponse {
        return request(peer, req) as Rpc.RequestVoteResponse
    }

    private suspend fun request(
        peer: Node,
        req: Rpc.Request
    ): Rpc.Response {
        val connection = connections[peer]!!
        return connection.ingress(req)
    }

    fun connectPeer(peer: Node, transport: Transport) {
        connections[peer] = Connection(transport.ingress)
    }
}

private data class Connection(
    val ingress: suspend (Rpc.Request) -> Rpc.Response
)
