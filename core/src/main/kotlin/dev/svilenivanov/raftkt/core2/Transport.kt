package dev.svilenivanov.raftkt.core2

interface Transport {
    var ingress: suspend (Rpc.Request) -> Rpc.Response

    suspend fun appendEntries(peer: Node, req: Rpc.AppendEntriesRequest): Rpc.AppendEntriesResponse
    suspend fun requestVote(peer: Node, req: Rpc.RequestVoteRequest): Rpc.RequestVoteResponse
}
