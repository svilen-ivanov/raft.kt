@file:Suppress("UNUSED_PARAMETER")

package dev.svilenivanov.raftkt

class Observer

sealed class Observation {
    class LeaderObservation(newLeader: Peer?) : Observation()
    class RequestVote(req: Rpc.RequestVoteRequest) : Observation()
    class PeerObservation(peer: Server, removed: Boolean) : Observation()
}

class Observers {
    fun observe(observation: Observation) {
    }
}
