package dev.svilenivanov.raftkt.core2

import kotlinx.coroutines.*
import kotlinx.datetime.Clock

class TestCluster(size: Int = 3) {
    private lateinit var job: Job
    private val raft: Map<Node, Raft>

    init {
        val nodes = (1..size).map { randomNode() }
        val config = Config()
        val cluster = Cluster(nodes)
        val transports = nodes.associateWith { InmemTransport() }
        transports.values.forEach { t1 ->
            transports.forEach { (node, t2) ->
                t1.connectPeer(node, t2)
            }
        }
        raft = nodes.associateWith {
            Raft(InmemLogStore(), config.copy(), transports[it]!!, cluster, Clock.System, it)
        }
    }

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            raft.values.forEach { it.runNode(this) }
        }
    }

    fun stop() {
        job.cancel("stop")
    }

}
