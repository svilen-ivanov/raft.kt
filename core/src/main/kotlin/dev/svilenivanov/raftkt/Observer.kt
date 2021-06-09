@file:Suppress("UNUSED_PARAMETER")

package dev.svilenivanov.raftkt

class Observer

sealed class Observation {
    class LeaderObservation(newLeader: Peer?) : Observation()
    class RequestVote(req: Rpc.RequestVoteRequest) : Observation()
}

class Observers {
    fun observe(observation: Observation) {
        TODO()
    }
}
