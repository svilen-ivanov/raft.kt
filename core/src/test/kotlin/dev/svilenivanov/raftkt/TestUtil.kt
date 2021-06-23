package dev.svilenivanov.raftkt

import dev.svilenivanov.raftkt.inmem.InmemLogStore
import dev.svilenivanov.raftkt.inmem.InmemSnapshotStore
import dev.svilenivanov.raftkt.inmem.InmemStableStore
import dev.svilenivanov.raftkt.inmem.InmemTransport
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.*
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

suspend fun <E> Channel<E>.assertAndDrain(msg: String) {
    withClue("$msg (expected but found empty channel)") { isEmpty shouldBe false }
    while (!isEmpty) {
        receive()
    }
    withClue("INTERNAL TEST ERROR: expected to drain the channel") { isEmpty shouldBe true }
}

data class MakeClusterOpts<T, R>(
    val fsmFactory: (suspend () -> Fsm<T, R>),
    val peers: Int = 3,
    val bootstrap: Boolean = true,
    val defaultConf: Config = Config(localId = ServerId(UUID.randomUUID().toString())),
    val longstopTimeout: Duration = seconds(1)
)

class TestCluster<T, R> {
    private val clock = Clock.System
    private val nodes = mutableListOf<Raft<T, R>>()

    suspend fun create(opts: MakeClusterOpts<T, R>): TestCluster<T, R> {
        val peers = (1..opts.peers).map {
            Peer(ServerId("ServerId $it"), ServerAddress("ServerAddress $it"))
        }
        val transports = peers.associateWith {
            InmemTransport(localAddr = it.address, timeout = seconds(1))
        }
        fullyConnect(transports)

        nodes += peers.map {
            val transport = transports[it]!!
            val logs = InmemLogStore()
            val stable = InmemStableStore()
            val snaps = InmemSnapshotStore()
            val conf = opts.defaultConf.copy(localId = it.id)

            if (opts.bootstrap) {
                val configuration = Configuration(servers = peers.map { server ->
                    Server(ServerSuffrage.VOTER, server.id, server.address)
                }.toSet())
                Raft.bootstrapCluster(
                    logs = logs,
                    stable = stable,
                    snaps = snaps,
                    configuration = configuration,
                    clock = clock,
                    conf = conf,
                    trans = transport
                )
            }
            Raft.create(
                config = conf,
                fsm = opts.fsmFactory(),
                logs = logs,
                stable = stable,
                snaps = snaps,
                transport = transport,
                coroutineContext
            )
        }
        return this
    }

    private suspend fun fullyConnect(transports: Map<Peer, InmemTransport>) {
        transports.values.forEach { t1 ->
            transports.values.forEach { t2 ->
                t1.connect(t2)
                t2.connect(t1)
            }
        }
    }

    suspend fun shutdown() {
        coroutineScope {
            nodes.forEach { node -> launch { node.shutdownFn().error() } }
        }
    }
}
