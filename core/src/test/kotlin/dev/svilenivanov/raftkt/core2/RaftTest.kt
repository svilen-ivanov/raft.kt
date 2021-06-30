package dev.svilenivanov.raftkt.core2

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class RaftTest {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(RaftTest::class.java)
    }

    @Test
    fun testFlow() = runBlockingTest {
        val raft = Raft(InmemLogStore(), Config(), InmemTransport(), Cluster(), Clock.System, randomNode())
        raft.runNode(this)
        val response = raft.apply(StringCommandRequest("c1"))
        logger.debug("Response: {}", response)
    }


    @Test
    fun testCluster() = runBlockingTest {
        val testCluster = TestCluster()
        testCluster.start(this)
        delay(10000)
        testCluster.stop()
    }
}
