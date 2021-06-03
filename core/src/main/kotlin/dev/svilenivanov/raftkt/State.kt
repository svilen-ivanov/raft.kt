package dev.svilenivanov.raftkt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

// raftState is used to maintain various state variables
// and provides an interface to set/get the variables in a
// thread safe manner.

// RaftState captures the state of a Raft node: Follower, Candidate, Leader, or Shutdown.
sealed class State {
    // Follower is the initial state of a Raft node.
    object Follower : State()

    // Candidate is one of the valid states of a Raft node.
    object Candidate : State()

    // Leader is one of the valid states of a Raft node.
    object Leader : State()

    // Shutdown is the terminal state of a Raft node.
    object Shutdown : State()
}


@Serializable
data class Index(val index: Long, val term: Long)

open class RaftState {

//    // The current term, cache of StableStore
//    var currentTerm: Long
//
//    // Highest committed log entry
//    var commitIndex: Long
//
//    // Last applied log to the FSM
//    var lastApplied: Long
//
//    // protects 2 next fields
//    private val lastLock = Mutex()
//
//    // Cache the latest snapshot index/term
//    private var lastSnapshot: Index
//
//    // Cache the latest log from LogStore
//    private var lastLog: Index
//
    // The current state
    var state: State = State.Follower
//
//    constructor(
//        currentTerm: Long,
//        lastLog: Index
//    )
//
//    suspend fun getLastLog(): Index {
//        lastLock.withLock {
//            return lastSnapshot
//        }
//    }
//
//    suspend fun setLastLog(lastLog: Index) {
//        lastLock.withLock {
//            this.lastLog = lastLog
//        }
//    }
//
//    suspend fun getLastSnapshot(): Index {
//        lastLock.withLock {
//            return lastSnapshot
//        }
//    }
//
//    suspend fun setLastSnapshot(lastLog: Index) {
//        lastLock.withLock {
//            this.lastLog = lastLog
//        }
//    }
//
//    suspend fun getLastIndex(): Long {
//        lastLock.withLock {
//            return maxOf(lastLog.index, lastSnapshot.index)
//        }
//    }
//
//    suspend fun getLastEntry(): Index {
//        lastLock.withLock {
//            return if (lastLog.index >= lastSnapshot.index) {
//                this.lastLog
//            } else {
//                this.lastSnapshot
//            }
//        }
//    }
}

