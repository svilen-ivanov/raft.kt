@file:Suppress("UNUSED_PARAMETER")

package dev.svilenivanov.raftkt

class Observer

sealed class Observation {
    class LeaderObservation(newLeader: ServerAddress?) : Observation()
}

class Observers {
    fun observe(observation: Observation) {
        TODO()
    }
}
