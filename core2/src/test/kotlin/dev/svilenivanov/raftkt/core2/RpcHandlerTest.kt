@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt.core2

import kotlinx.coroutines.test.runBlockingTest
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import org.slf4j.Marker
import org.slf4j.MarkerFactory

internal class RpcHandlerTest {
    private val marker: Marker = MarkerFactory.getMarker("test")
    private val clock = Clock.System

    @Test
    fun handleRequestVote() = runBlockingTest {
        val logStore = InmemLogStore()
        val leader = randomNode("leader")
        val consensus = Consensus(PersistentState(logStore), VolatileState(), leader.id, NodeRole.FOLLOWER)
        val follower = randomNode("follower")

        val handler = RpcHandler(consensus, marker, header, clock)

        val response = handler.respond(
            Rpc.RequestVoteRequest(
                header,
                1,
                follower,
                Position.ZERO,
                false
            )
        ) as Rpc.RequestVoteResponse
    }
}

