@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt.core

import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Marker
import org.slf4j.MarkerFactory

internal class RpcRequestVoteTest {
    private val marker: Marker = MarkerFactory.getMarker("RpcRequestVoteTest")
    private val clock = TestClock()

    private val leader = randomNode("leader")
    private val candidate = randomNode("candidate")

    private lateinit var logStore: InmemLogStore
    private lateinit var consensus: Consensus
    private lateinit var fsmApply: FsmApply
    private lateinit var handler: RpcHandler
    private lateinit var fsm: Fsm

    @BeforeEach
    fun setup() {
        logStore = spyk(InmemLogStore())
        consensus = Consensus(PersistentState(logStore), VolatileState(), leader.id, NodeRole.FOLLOWER)
        fsm = spyk(EchoFsm())
        fsmApply = FsmApply(marker, fsm)
        handler = RpcHandler(consensus, fsmApply, marker, header, clock)
    }

    @Test
    fun requestVote() = runBlockingTest {
        with(consensus) {
            persistent.currentTerm = 1
            persistent.lastLog = Position(1, 1)
            leader = null
        }

        val response = handler.respond(
            Rpc.RequestVoteRequest(
                header = header,
                term = 2,
                candidate = candidate,
                lastLog = Position(1, 1),
                leadershipTransfer = false
            )
        )

        response shouldBe Rpc.RequestVoteResponse(
            header = header,
            term = 2,
            granted = true
        )

        with(consensus) {
            persistent.currentTerm shouldBe 2
            persistent.lastLog shouldBe Position(1, 1)
            persistent.votedFor shouldBe VoteFor(candidate.id, 2)
        }
    }

    @Test
    fun fartherCandidate() = runBlockingTest {
        with(consensus) {
            persistent.currentTerm = 1
            persistent.lastLog = Position(1, 1)
            leader = null
        }

        val response = handler.respond(
            Rpc.RequestVoteRequest(
                header = header,
                term = 2,
                candidate = candidate,
                lastLog = Position(3, 1),
                leadershipTransfer = false
            )
        )

        response shouldBe Rpc.RequestVoteResponse(
            header = header,
            term = 2,
            granted = true
        )

        with(consensus) {
            persistent.currentTerm shouldBe 2
            persistent.lastLog shouldBe Position(1, 1)
            persistent.votedFor shouldBe VoteFor(candidate.id, 2)
        }
    }

    @Test
    fun laggingCandidate() = runBlockingTest {
        with(consensus) {
            persistent.currentTerm = 1
            persistent.lastLog = Position(3, 1)
            leader = null
        }

        val response = handler.respond(
            Rpc.RequestVoteRequest(
                header = header,
                term = 2,
                candidate = candidate,
                lastLog = Position(1, 1),
                leadershipTransfer = false
            )
        )

        response shouldBe Rpc.RequestVoteResponse(
            header = header,
            term = 2,
            granted = false
        )

        with(consensus) {
            persistent.currentTerm shouldBe 2
            persistent.lastLog shouldBe Position(3, 1)
            persistent.votedFor shouldBe null
        }
    }

    @Test
    fun newerTerm() = runBlockingTest {
        with(consensus) {
            persistent.currentTerm = 2
            persistent.lastLog = Position(3, 2)
            leader = null
        }

        val response = handler.respond(
            Rpc.RequestVoteRequest(
                header = header,
                term = 1,
                candidate = candidate,
                lastLog = Position(3, 1),
                leadershipTransfer = false
            )
        )

        response shouldBe Rpc.RequestVoteResponse(
            header = header,
            term = 2,
            granted = false
        )

        with(consensus) {
            persistent.currentTerm shouldBe 2
            persistent.lastLog shouldBe Position(3, 2)
            persistent.votedFor shouldBe null
        }
    }

    @Test
    fun duplicateVote() = runBlockingTest {
        with(consensus) {
            persistent.currentTerm = 1
            persistent.lastLog = Position(1, 1)
            leader = null
        }

        val response = handler.respond(
            Rpc.RequestVoteRequest(
                header = header,
                term = 2,
                candidate = candidate,
                lastLog = Position(1, 1),
                leadershipTransfer = false
            )
        )

        response shouldBe Rpc.RequestVoteResponse(
            header = header,
            term = 2,
            granted = true
        )

        with(consensus) {
            persistent.currentTerm shouldBe 2
            persistent.lastLog shouldBe Position(1, 1)
            persistent.votedFor shouldBe VoteFor(candidate.id, 2)
        }

        val duplicateSameCandidate = handler.respond(
            Rpc.RequestVoteRequest(
                header = header,
                term = 2,
                candidate = candidate,
                lastLog = Position(1, 1),
                leadershipTransfer = false
            )
        )
        duplicateSameCandidate shouldBe response

        with(consensus) {
            persistent.currentTerm shouldBe 2
            persistent.lastLog shouldBe Position(1, 1)
            persistent.votedFor shouldBe VoteFor(candidate.id, 2)
        }

        val duplicateDifferentCandidate = handler.respond(
            Rpc.RequestVoteRequest(
                header = header,
                term = 2,
                candidate = randomNode("another candidate"),
                lastLog = Position(1, 1),
                leadershipTransfer = false
            )
        )
        duplicateDifferentCandidate shouldBe Rpc.RequestVoteResponse(
            header = header,
            term = 2,
            granted = false
        )

        with(consensus) {
            persistent.currentTerm shouldBe 2
            persistent.lastLog shouldBe Position(1, 1)
            persistent.votedFor shouldBe VoteFor(candidate.id, 2)
        }
    }

    @Test
    fun leadershipTransfer() = runBlockingTest {
        with(consensus) {
            persistent.currentTerm = 1
            persistent.lastLog = Position(1, 1)
        }

        val response = handler.respond(
            Rpc.RequestVoteRequest(
                header = header,
                term = 2,
                candidate = candidate,
                lastLog = Position(1, 1),
                leadershipTransfer = true
            )
        )

        response shouldBe Rpc.RequestVoteResponse(
            header = header,
            term = 2,
            granted = true
        )

        with(consensus) {
            persistent.currentTerm shouldBe 2
            persistent.lastLog shouldBe Position(1, 1)
            persistent.votedFor shouldBe VoteFor(candidate.id, 2)
        }
    }
}

