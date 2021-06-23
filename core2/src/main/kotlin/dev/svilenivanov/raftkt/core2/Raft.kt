@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt.core2

import dev.svilenivanov.raftkt.core2.NodeRole.FOLLOWER
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory

class Raft(
    private val logStore: LogStore,
    private val config: Config,
    private val transport: Transport,
    private val cluster: Cluster,
    private val clock: Clock,
    private val node: Node
) {
    private val consensus = Consensus(
        PersistentState(logStore),
        VolatileState(),
        node.id,
        FOLLOWER
    )
    private val header = RpcHeader(config.protocolVersion)

    private val logger: Logger = LoggerFactory.getLogger(Raft::class.java)
    private val marker = MarkerFactory.getMarker(node.id.toString())
    private val loggingExceptionHandler = logger.asExceptionHandler(marker)
    private lateinit var job: Job

    private val ingress = Channel<Message<CommandRequest, CommandResponse>>()

    init {
//        transport.ingress = this::respond
    }

    suspend fun apply(command: CommandRequest): CommandResponse {
        val message = Message<CommandRequest, CommandResponse>(command)
        ingress.send(message)
        return message.response.await()
    }

    fun runNode(scope: CoroutineScope) {
        logger.info(marker, "Starting")
        job = scope.launch(loggingExceptionHandler) {
            var role: Role = Follower()
            while (isActive) {
                logger.info(marker, "Current role: ${role::class.simpleName}")
                role = role.run()
            }
        }
    }

    fun stop() {
        job.cancel("stop")
    }

    internal inner class Follower : Role() {
        override suspend fun next(): Role {
            return coroutineScope {
                consumeIngress()
//                respondRpc()
                flow {
                    while (currentCoroutineContext().isActive) {
                        delay(calcRandomTimeout(config.heartbeatTimeout))
                        emit(Unit)
                    }
                }.dropWhile {
                    consensus.use {
                        volatile.lastContact + config.heartbeatTimeout > clock.now()
                    }
                }.map { Candidate() }.onCompletion { if (it != null) emit(Candidate()) }.first()
            }
        }

        private fun CoroutineScope.consumeIngress() {
            launch {
                ingress.receiveAsFlow().collect {
                    throw IllegalStateException()
                }
            }
        }
    }

    private fun Consensus.requestVote(peer: Node): Flow<Rpc.RequestVoteResponse> {
        return flow {
            emit(
                transport.requestVote(
                    peer, Rpc.RequestVoteRequest(
                        header = header,
                        term = persistent.currentTerm,
                        candidate = node,
                        lastLog = Position.ZERO,
                        false
                    )
                )
            )
        }.flowOn(Dispatchers.IO)
    }

    private inner class Candidate : Role() {
//        val votes = atomic(1)
//        val voted = atomic(1)

        @OptIn(FlowPreview::class)
        override suspend fun next(): Role {
            coroutineScope {
                consumeIngress()
            }

            val quorum = 0
            var voteCount = 0
            val elected: Boolean = cluster.nodes.filter { it != node }.asFlow()
                .map {
                    consensus.use {
                        requestVote(it)
                    }
                }
                .flattenMerge(concurrency = cluster.nodes.size - 1)
                .catch { cause -> logger.error(marker, "Failed to collect vote", cause) }
                .onEach { resp ->
                    if (resp.granted) {
                        voteCount++
                    }
                }
                .takeWhile { voteCount < quorum }
                .fold(false) { _, _ -> true }
//            delay(500)
//            val quorum = cluster.nodes.size / 2 + 1
//            persistentState.incrementCurrentTerm()
//            try {
//                withTimeout(calcRandomTimeout(config.electionTimeout)) {
//                    supervisorScope {
//                        cluster.nodes.filter { it != node }.map { peer ->
//                            launch(logger.asExceptionHandler(marker)) {
//                                val resp = transport.requestVote(
//                                    peer, Rpc.RequestVoteRequest(
//                                        header = header,
//                                        term = persistentState.getCurrentTerm(),
//                                        candidate = node,
//                                        lastLogIndex = 0,
//                                        lastLogTerm = 0,
//                                        false
//                                    )
//                                )
//                                voted.incrementAndGet()
//                                if (resp.granted) {
//                                    votes.incrementAndGet()
//                                }
//                                if (votes.value >= quorum) {
//                                    this@withTimeout.cancel("reached quorum")
//                                }
//                            }
//                        }
//                    }
//                }
//            } catch (e: CancellationException) {
//                logger.info("Canceled ${e.message}")
//            }
//            logger.info("Received ${votes.value} from ${voted.value}, quorum=$quorum")
//            return if (votes.value >= quorum) Leader() else Follower()
            return Leader()
        }

        private fun CoroutineScope.consumeIngress() {
            launch {
                ingress.receiveAsFlow().collect {
                    throw IllegalStateException()
                }
            }
        }

    }

    private inner class Leader : Role() {
        override suspend fun next(): Role {
            return coroutineScope {
                delay(10000)
                consumeIngress()
                Follower()
            }
        }

        private fun CoroutineScope.consumeIngress() {
            launch {
                ingress.receiveAsFlow().collect {
                    it.respond { object : CommandResponse {} }
                }
            }
        }

    }
}

