package dev.svilenivanov.raftkt

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runBlockingTest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.test.Test

@Suppress("UNUSED_VARIABLE")
internal class RaftTest {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(RaftTest::class.java)
    }
    @Test
    fun main() = runBlockingTest {
        val cluster = TestCluster<String, String>().create(
            MakeClusterOpts(fsmFactory = {
                object : Fsm<String, String> {
                    override suspend fun apply(log: Log): String {
                        TODO("Not yet implemented")
                    }

                    override suspend fun snapshot(): FsmSnapshot {
                        TODO("Not yet implemented")
                    }

                    override suspend fun restore(snapshot: SnapshotSource) {
                        TODO("Not yet implemented")
                    }

                }
            })
        )
        logger.info("Done! 1")
        cluster.shutdown()
        logger.info("Done! 2")
    }
}
