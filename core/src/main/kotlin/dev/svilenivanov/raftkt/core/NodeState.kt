package dev.svilenivanov.raftkt.core

import kotlinx.coroutines.*
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 *  State captures the state of a Raft node: Follower, Candidate, Leader.
 */


interface CommandRequest
interface CommandResponse

data class Message<REQ, RES>(
    val request: REQ,
    val response: Deferred<RES> = CompletableDeferred()
) {
    suspend fun respond(block: suspend (req: REQ) -> RES) {
        response as CompletableDeferred
        try {
            response.complete(block(request))
        } catch (t: Throwable) {
            response.completeExceptionally(t)
        }
    }
}

enum class NodeRole {
    FOLLOWER,
    CANDIDATE,
    LEADER
}

sealed class Role {
    private lateinit var newRole: Deferred<Role>

    abstract suspend fun next(): Role

    suspend fun run(): Role {
        return coroutineScope {
            newRole = async { next() }
            newRole.await()
        }
    }

    fun stepDown() {
        newRole.cancel("step down")
    }

}

@Serializable
data class Position constructor(
    val index: Long,
    val term: Long,
) {
    companion object {
        val ZERO = Position(0, 0)
    }
}

@JvmInline
@Serializable
value class ServerId(private val id: String) {
    fun isBlank() = id.isBlank()
    override fun toString() = id
}

@JvmInline
@Serializable
value class ServerAddress(private val address: String) {
    fun isBlank() = address.isBlank()
    override fun toString() = address
}

@Serializable
data class Node(
    val id: ServerId,
    val address: ServerAddress,
)

@Serializable
data class VoteFor(
    val id: ServerId,
    val term: Long
)

class PersistentState(val log: LogStore) {
    var currentTerm: Long = 0L
    var votedFor: VoteFor? = null
    var lastLog: Position = Position.ZERO
}

open class VolatileState {
    private lateinit var role: Role

    var commitIndex = 0L
    var lastApplied = 0L
    var lastContact = Instant.DISTANT_PAST

//    companion object {
//        fun from(leaderState: LeaderState): VolatileState {
//            val newState = VolatileState()
//            newState.setCommitIndex(leaderState.getCommitIndex())
//            newState.setLastApplied(leaderState.getLastApplied())
//            return newState
//        }
//    }
}

class LeaderState : VolatileState() {
    val nextIndex = mutableMapOf<ServerId, Long>()
    val matchIndex = mutableMapOf<ServerId, Long>()

//    companion object {
//        fun from(volatileState: VolatileState): LeaderState {
//            val newState = LeaderState()
//            newState.setCommitIndex(volatileState.getCommitIndex())
//            newState.setLastApplied(volatileState.getLastApplied())
//            return newState
//        }
//    }

//    fun toVolatileState(): VolatileState {
//        return VolatileState.from(this)
//    }
}

