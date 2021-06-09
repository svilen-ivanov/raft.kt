package dev.svilenivanov.raftkt

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 *  RaftState captures the state of a Raft node: Follower, Candidate, Leader, or Shutdown.
 */
enum class State {
    /**
     *  Follower is the initial state of a Raft node.
     */
    FOLLOWER,

    /**
     * Candidate is one of the valid states of a Raft node.
     */
    CANDIDATE,

    /**
     *  Leader is one of the valid states of a Raft node.
     */
    LEADER,

    /**
     *  Shutdown is the terminal state of a Raft node.
     */
    SHUTDOWN,
}

/**
 * raftState is used to maintain various state variables and provides an interface to set/get the variables in a
 * thread safe manner.
 */
class RaftState {
    /**
     * The current term, cache of StableStore
     */
    private val currentTerm = atomic(0L)

    /**
     * Highest committed log entry
     */
    private val commitIndex = atomic(0L)

    /**
     * Last applied log to the FSM
     */
    private val lastApplied = atomic(0L)

    // Cache the latest snapshot index/term
    // protects 4 next fields
    private val lastLock = Mutex()
    private var lastSnapshot: Position = ZERO_POSITION
    private var lastLog: Position = ZERO_POSITION

    companion object {
        val ZERO_POSITION = Position(0, 0)
    }

    /**
     * The current state
     */
    private val state = atomic(State.FOLLOWER)

    fun getState() = state.value
    fun setState(newState: State) {
        state.value = newState
    }

    fun getCurrentTerm() = currentTerm.value
    fun setCurrentTerm(newTerm: Long) {
        currentTerm.value = newTerm
    }
    fun incrementCurrentTerm() = currentTerm.incrementAndGet()

    suspend fun getLastLog() = lastLock.withLock { lastLog }
    suspend fun setLastLog(newLastLog: Position) = lastLock.withLock { lastLog = newLastLog }

    suspend fun getLastSnapshot() = lastLock.withLock { lastSnapshot }
    suspend fun setLastSnapshot(newLastSnapshot: Position) = lastLock.withLock { lastSnapshot = newLastSnapshot }

    fun getCommitIndex() = commitIndex.value
    fun setCommitIndex(newCommitIndex: Long) {
        commitIndex.value = newCommitIndex
    }

    fun getLastApplied() = lastApplied.value
    fun setLastApplied(newLastApplied: Long) {
        lastApplied.value = newLastApplied
    }

    /**
     * getLastIndex returns the last index in stable storage. Either from the last log or from the last snapshot.
     */
    suspend fun getLastIndex() = lastLock.withLock { maxOf(lastLog.index, lastSnapshot.index) }

    /**
     * getLastEntry returns the last index and term in stable storage. Either from the last log or from the last
     * snapshot.
     */
    suspend fun getLastEntry() = lastLock.withLock {
        if (lastLog.index >= lastSnapshot.index) {
            lastLog
        } else {
            lastSnapshot
        }
    }
}

