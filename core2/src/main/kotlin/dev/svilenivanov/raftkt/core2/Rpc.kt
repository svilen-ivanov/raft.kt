@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt.core2

import dev.svilenivanov.raftkt.core2.NodeRole.FOLLOWER
import kotlinx.coroutines.awaitAll
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker

@Serializable
data class RpcHeader(val protocolVersion: ProtocolVersion)

interface WithHeader {
    val header: RpcHeader
}

sealed class Rpc : WithHeader {
    sealed class Request : Rpc() {
        abstract val term: Long
    }

    sealed class Response : Rpc()

    @Serializable
    data class AppendEntriesRequest(
        override val header: RpcHeader,

        // Provide the current term and leader
        override val term: Long,
        val leader: Node,
        // Provide the previous entries for integrity checking
        val prevLog: Position,
        // New entries to commit
        val entries: List<Log>,
        // Commit index on the leader
        val leaderCommitIndex: Long
    ) : Rpc.Request()

    @Serializable
    data class AppendEntriesResponse(
        override val header: RpcHeader,

        // Newer term if leader is out of date
        val term: Long,
        // Last Log is a hint to help accelerate rebuilding slow nodes
        val lastLog: Position,
        // We may not succeed if we have a conflicting entry
        val success: Boolean,
        // There are scenarios where this request didn't succeed
        // but there's no need to wait/back-off the next attempt.
        val noRetryBackoff: Boolean
    ) : Rpc.Response()

    @Serializable
    data class RequestVoteRequest(
        override val header: RpcHeader,

        // Provide the term and our id
        override val term: Long,
        val candidate: Node,

        // Used to ensure safety
        val lastLog: Position,

        // Used to indicate to peers if this vote was triggered by a leadership
        // transfer. It is required for leadership transfer to work, because servers
        // wouldn't vote otherwise if they are aware of an existing leader.
        val leadershipTransfer: Boolean
    ) : Rpc.Request()

    @Serializable
    data class RequestVoteResponse(
        override val header: RpcHeader,

        // Newer term if leader is out of date.
        val term: Long,
        // Is the vote granted.
        val granted: Boolean
    ) : Rpc.Response()

}

class RpcHandler(
    private val consensus: Consensus,
    private val fsmApply: FsmApply,
    private val marker: Marker,
    private val header: RpcHeader,
    private val clock: Clock
) {
    private val logger: Logger = LoggerFactory.getLogger(RpcHandler::class.java)

    suspend fun respond(req: Rpc.Request): Rpc.Response = consensus.use {
        try {
            when (req) {
                is Rpc.AppendEntriesRequest -> appendEntries(req)
                is Rpc.RequestVoteRequest -> requestVote(req)
            }
        } finally {
            volatile.lastContact = clock.now()
        }
    }

    private suspend fun Consensus.appendEntries(req: Rpc.AppendEntriesRequest): Rpc.AppendEntriesResponse {
        if (req.term > persistent.currentTerm || role != FOLLOWER) {
            persistent.currentTerm = req.term
            logger.info(marker, "Stepping down due to receiving append entries with term {}", req.term)
            throw RaftException.StepDown()
        }
        leader = req.leader.id
        // Ignore an older term
        if (req.term < persistent.currentTerm) {
            logger.debug(
                marker,
                "rejecting append from leader {} for term {}, our term {} is newer",
                req.leader,
                req.term,
                persistent.currentTerm
            )
            return Rpc.AppendEntriesResponse(
                header = header,
                term = persistent.currentTerm,
                lastLog = persistent.lastLog,
                success = false,
                noRetryBackoff = false
            )
        }

        if (req.prevLog.index > 0) {
            val prevLogTerm = if (req.prevLog == persistent.lastLog) {
                persistent.lastLog.term
            } else {
                persistent.log.getLog(req.prevLog.index)?.position?.term
            }
            if (prevLogTerm != req.prevLog.term) {
                logger.warn(
                    marker,
                    "previous log term mis-match at index {}, our log entry term {}, remote log entry term {}",
                    req.prevLog.index,
                    prevLogTerm,
                    req.prevLog.term
                )
                return Rpc.AppendEntriesResponse(
                    header = header,
                    term = persistent.currentTerm,
                    lastLog = persistent.lastLog,
                    success = false,
                    noRetryBackoff = true
                )
            }
        }

        var newEntries: List<Log> = emptyList()
        for ((i, entry) in req.entries.withIndex()) {
            if (entry.position.index > persistent.lastLog.index) {
                newEntries = req.entries.subList(i, req.entries.size)
                break
            }
            val storeEntry = persistent.log.getLog(entry.position.index)
            if (storeEntry == null) {
                logger.warn(marker, "log entry at position {} is not found", entry.position.index)
                return Rpc.AppendEntriesResponse(
                    header = header,
                    term = persistent.currentTerm,
                    lastLog = persistent.lastLog,
                    success = false,
                    noRetryBackoff = true
                )
            }

            if (entry.position.term != storeEntry.position.term) {
                logger.warn(marker, "clearing log suffix from {} to {}", entry.position.index, persistent.lastLog.index)
                persistent.log.deleteRange(entry.position.index..persistent.lastLog.index)
                newEntries = req.entries.subList(i, req.entries.size)
                break
            }
        }
        newEntries.forEach { persistent.log.storeLog(it) }
        newEntries.lastOrNull()?.also { persistent.lastLog = it.position }

        if (req.leaderCommitIndex > 0 && req.leaderCommitIndex > volatile.commitIndex) {
            val idx = minOf(req.leaderCommitIndex, persistent.lastLog.index)
            volatile.commitIndex = idx
            fsmApply.processLogs(this, idx)
        }

        return Rpc.AppendEntriesResponse(
            header = header,
            term = persistent.currentTerm,
            lastLog = persistent.lastLog,
            success = true,
            noRetryBackoff = false
        )
    }


    private fun Consensus.requestVote(req: Rpc.RequestVoteRequest): Rpc.RequestVoteResponse {
        val votedFor = persistent.votedFor
        val granted = when {
            // Check if we have an existing leader [who's not the candidate] and also
            // check the LeadershipTransfer flag is set. Usually votes are rejected if
            // there is a known leader. But if the leader initiated a leadership transfer,
            // vote!
            leader != null && leader != req.candidate.id && !req.leadershipTransfer -> {
                logger.warn(marker, "rejecting vote request from {} since we have a leader {}", req.candidate, leader)
                false
            }
            req.term > persistent.currentTerm -> {
                persistent.currentTerm = req.term
                logger.info(marker, "Stepping down due to receiving new term {}", req.term)
                throw RaftException.StepDown()
            }
            req.term < persistent.currentTerm -> {
                logger.debug(
                    marker,
                    "rejecting vote to candidate {} for term {}, our term {} is newer",
                    req.candidate,
                    req.term,
                    persistent.currentTerm
                )
                false
            }
            votedFor == null -> {
                logger.debug(marker, "granting vote to candidate {} for term {}", req.candidate, req.term)
                persistent.votedFor = VoteFor(req.candidate.id, req.term)
                true
            }
            votedFor.term == req.term -> {
                logger.warn(marker, "duplicate requestVote for same term {}", req.term)
                val granted = votedFor.id == req.candidate.id
                if (granted) {
                    logger.warn(marker, "duplicate requestVote from candidate {}", req.candidate)
                }
                granted
            }
            persistent.lastLog.term > req.lastLog.term
                    || (persistent.lastLog.term == req.lastLog.term && persistent.lastLog.index > req.lastLog.index) -> {
                logger.warn(
                    marker,
                    "rejecting vote request since our position {} is greater than candidate's {} position {}",
                    persistent.lastLog,
                    req.candidate,
                    req.lastLog
                )
                false
            }
            else -> true
        }
        return Rpc.RequestVoteResponse(
            header = header,
            term = persistent.currentTerm,
            granted = granted
        )
    }
}

