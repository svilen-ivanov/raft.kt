package dev.svilenivanov.raftkt.inmem

import dev.svilenivanov.raftkt.*
import dev.svilenivanov.raftkt.inmem.InmemTransport.Companion.MAX_PROTOCOL_HEADER
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

internal class InmemTransportTest {
    @Test
    fun writeTimeout() = runBlockingTest {
        val timeout = seconds(1)
        val t1 = InmemTransport(timeout = timeout, clock = Clock.System)
        val a1 = Peer(ServerId("1"), t1.localAddr)
        val t2 = InmemTransport(timeout = timeout, clock = Clock.System)
        val a2 = Peer(ServerId("2"), t2.localAddr)

        t1.connect(t2)

        val t2Worker = launch(CoroutineName("t2Worker")) {
            var i = 0L
            while (isActive) select<Unit> {
                t2.consumerCh.onReceive { rpc ->
                    i++
                    rpc.respond {
                        Rpc.AppendEntriesResponse(
                            header = MAX_PROTOCOL_HEADER,
                            term = 0,
                            lastLog = i,
                            success = true,
                            false
                        )
                    }
                }
            }
        }

        val resp = t1.appendEntries(
            a2, Rpc.AppendEntriesRequest(
                header = MAX_PROTOCOL_HEADER,
                term = 0,
                leader = a1,
                prevLogEntry = 0,
                prevLogTerm = 0,
                entries = emptyList(),
                leaderCommitIndex = 0
            )
        )
        resp.lastLog shouldBe 1
        t2Worker.cancel("finished")

        shouldThrow<TimeoutCancellationException> {
            t1.appendEntries(
                a2, Rpc.AppendEntriesRequest(
                    header = MAX_PROTOCOL_HEADER,
                    term = 0,
                    leader = a1,
                    prevLogEntry = 0,
                    prevLogTerm = 0,
                    entries = emptyList(),
                    leaderCommitIndex = 0
                )
            )
        }
    }

    @Test
    fun testScopes() = runBlockingTest {
        class TestScope(val name: String, val scope: CoroutineScope) {
            lateinit var job: Job
            fun launch() {
                job = scope.launch {
                    try {
                        println("starting $name")
                        delay(seconds(1))
                        println("done $name")
                    } catch (e: CancellationException) {
                        println("e: $name canceled")
                    }
                }
            }

            fun cancel() {
                job.cancel()
                println("canceled $name")
            }
        }
        coroutineScope {
            val t1 = TestScope("t1", this + SupervisorJob())
            t1.launch()
            val t2 = TestScope("t2", this + SupervisorJob())
            t2.launch()
        }
    }

    @Test
    fun appendEntriesPipeline() = runBlockingTest {
        val timeout = seconds(1)
        val t1 = InmemTransport(timeout = timeout, clock = Clock.System)
        val a1 = Peer(ServerId("1"), t1.localAddr)
        val t2 = InmemTransport(timeout = timeout, clock = Clock.System)
        val a2 = Peer(ServerId("2"), t2.localAddr)

        t1.connect(t2)

        val t2Worker = launch(CoroutineName("t2Worker")) {
            var i = 0L
            while (isActive) select<Unit> {
                t2.consumerCh.onReceive { rpc ->
                    i++
                    rpc.respond {
                        Rpc.AppendEntriesResponse(
                            header = MAX_PROTOCOL_HEADER,
                            term = 0,
                            lastLog = i,
                            success = true,
                            false
                        )
                    }
                }
            }
        }

        val pipeline = t1.appendEntriesPipeline(a2)
        pipeline.appendEntries(
            Rpc.AppendEntriesRequest(
                header = MAX_PROTOCOL_HEADER,
                term = 0,
                leader = a1,
                prevLogEntry = 0,
                prevLogTerm = 0,
                entries = emptyList(),
                leaderCommitIndex = 0
            )
        )
        val resp = select<AppendFuture> {
            pipeline.consumer.onReceive { it }
            onTimeout(timeout.inWholeMilliseconds) { fail("timeout") }
        }
        resp.resp!!.lastLog shouldBe 1L
        t1.close()
        t2Worker.cancel()
    }
}
