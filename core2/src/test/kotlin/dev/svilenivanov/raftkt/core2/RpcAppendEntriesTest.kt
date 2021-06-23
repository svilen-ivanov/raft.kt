@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt.core2

import io.kotest.assertions.withClue
import io.kotest.fp.success
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.datetime.Clock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Marker
import org.slf4j.MarkerFactory

internal class RpcAppendEntriesTest {
    private val marker: Marker = MarkerFactory.getMarker("RpcAppendEntriesTest")
    private val clock = TestClock()

    private lateinit var logStore: InmemLogStore
    private lateinit var leader: Node
    private lateinit var consensus: Consensus
    private lateinit var follower: Node
    private lateinit var fsmApply: FsmApply
    private lateinit var handler: RpcHandler
    private lateinit var fsm: Fsm

    @BeforeEach
    fun setup() {
        logStore = spyk(InmemLogStore())
        leader = randomNode("leader")
        consensus = Consensus(PersistentState(logStore), VolatileState(), leader.id, NodeRole.FOLLOWER)
        follower = randomNode("follower")
        fsm = spyk(EchoFsm())
        fsmApply = FsmApply(marker, fsm)
        handler = RpcHandler(consensus, fsmApply, marker, header, clock)
    }

    @Test
    fun appendEntries() = runBlockingTest {
        consensus.persistent.currentTerm = 1
        val entries = 3.entriesFrom(Position(1, 1))

        val response = handler.respond(
            Rpc.AppendEntriesRequest(
                header = header,
                term = 1,
                leader = leader,
                prevLog = Position.ZERO,
                entries = entries,
                leaderCommitIndex = 3
            )
        )

        response shouldBe Rpc.AppendEntriesResponse(
            header = header,
            success = true,
            lastLog = Position(3, 1),
            noRetryBackoff = false,
            term = 1
        )

        with(consensus) {
            persistent.currentTerm shouldBe 1
            persistent.lastLog shouldBe Position(3, 1)
            volatile.lastApplied shouldBe 3
            volatile.commitIndex shouldBe 3
            volatile.lastContact shouldBe clock.now()
        }

        entries.forEach { log ->
            withClue("entry: $log") { logStore.getLog(log.position.index) shouldBe log }
        }

        verifyOrder {
            entries.forEach { fsm.apply((it.data as Log.Data.CommandData).command) }
        }
    }

    @Test
    fun appendEntriesOldTerm() = runBlockingTest {
        consensus.persistent.currentTerm = 2

        val response = handler.respond(
            Rpc.AppendEntriesRequest(
                header = header,
                term = 1,
                leader = leader,
                prevLog = Position.ZERO,
                entries = emptyList(),
                leaderCommitIndex = 0
            )
        )

        response shouldBe Rpc.AppendEntriesResponse(
            header = header,
            success = false,
            lastLog = Position.ZERO,
            noRetryBackoff = false,
            term = 2
        )

        with(consensus) {
            persistent.currentTerm shouldBe 2
            persistent.lastLog shouldBe Position.ZERO
            volatile.lastApplied shouldBe 0
            volatile.commitIndex shouldBe 0
            volatile.lastContact shouldBe clock.now()
        }

        verify {
            logStore wasNot called
            fsm wasNot called
        }
    }

    @Test
    fun appendEntriesPrevLogNoEntry() = runBlockingTest {
        consensus.persistent.currentTerm = 1

        val response = handler.respond(
            Rpc.AppendEntriesRequest(
                header = header,
                term = 1,
                leader = leader,
                prevLog = Position(1, 1),
                entries = emptyList(),
                leaderCommitIndex = 1
            )
        )

        response shouldBe Rpc.AppendEntriesResponse(
            header = header,
            success = false,
            lastLog = Position.ZERO,
            noRetryBackoff = true,
            term = 1
        )

        with(consensus) {
            persistent.currentTerm shouldBe 1
            persistent.lastLog shouldBe Position.ZERO
            volatile.lastApplied shouldBe 0
            volatile.commitIndex shouldBe 0
            volatile.lastContact shouldBe clock.now()
        }

        coVerify {
            logStore.getLog(1L)
            fsm wasNot called
        }
        confirmVerified(logStore, fsm)
    }

    @Test
    fun appendEntriesPrevLogTermMismatch() = runBlockingTest {
        consensus.persistent.currentTerm = 1
        logStore.storeLogs(1.entriesFrom(Position(1, 2)))
        clearAllMocks()

        val response = handler.respond(
            Rpc.AppendEntriesRequest(
                header = header,
                term = 1,
                leader = leader,
                prevLog = Position(1, 1),
                entries = emptyList(),
                leaderCommitIndex = 1
            )
        )

        response shouldBe Rpc.AppendEntriesResponse(
            header = header,
            success = false,
            lastLog = Position.ZERO,
            noRetryBackoff = true,
            term = 1
        )

        with(consensus) {
            persistent.currentTerm shouldBe 1
            persistent.lastLog shouldBe Position.ZERO
            volatile.lastApplied shouldBe 0
            volatile.commitIndex shouldBe 0
            volatile.lastContact shouldBe clock.now()
        }

        coVerify {
            logStore.getLog(1L)
            fsm wasNot called
        }
        confirmVerified(logStore, fsm)
    }

    @Test
    fun appendEntriesTruncate() = runBlockingTest {
        val logEntries = listOf(
            Position(1, 1),
            Position(2, 1),
            Position(3, 1),
        ).map { it.asLog() }
        logStore.storeLogs(logEntries)
        consensus.persistent.currentTerm = 3
        consensus.persistent.lastLog = logEntries.last().position
        consensus.volatile.commitIndex = 2
        consensus.volatile.lastApplied = 2
        clearAllMocks()

        val newEntries = listOf(
            Position(2, 1),
            Position(3, 2), // <-- new term for this index
            Position(4, 2),
            Position(5, 3),
            Position(6, 3),
        ).map { it.asLog() }

        val response = handler.respond(
            Rpc.AppendEntriesRequest(
                header = header,
                term = 3,
                leader = leader,
                prevLog = Position(1, 1),
                entries = newEntries,
                leaderCommitIndex = 4
            )
        )

        response shouldBe Rpc.AppendEntriesResponse(
            header = header,
            success = true,
            lastLog = newEntries.last().position,
            noRetryBackoff = false,
            term = 3
        )

        with(consensus) {
            persistent.currentTerm shouldBe 3
            persistent.lastLog shouldBe newEntries.last().position
            volatile.lastApplied shouldBe 4
            volatile.commitIndex shouldBe 4
            volatile.lastContact shouldBe clock.now()
        }

        coVerify {
            logStore.getLog(range(1L, 4L))
        }
        coVerifyOrder {
            logStore.deleteRange(3L..3L)
            newEntries.drop(1).forEach {
                logStore.storeLog(it)
            }
            newEntries.slice(1..2).forEach {
                fsm.apply(it.command())
            }
        }
        confirmVerified(logStore, fsm)
    }

    private fun Int.entriesFrom(start: Position) = (0 until this).map { i ->
        start.copy(index = start.index + i).asLog()
    }

    private fun Position.asLog() = Log(
        this,
        clock.now(),
        Log.Data.CommandData(StringCommandRequest("Log at $this"))
    )

    private fun Log.command() = (this.data as Log.Data.CommandData).command

}

