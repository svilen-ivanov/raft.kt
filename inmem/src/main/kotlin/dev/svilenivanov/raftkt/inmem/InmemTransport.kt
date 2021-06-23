package dev.svilenivanov.raftkt.inmem

import dev.svilenivanov.raftkt.*
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

private object IdGenerator {
    val id = atomic(0)
    fun next() = id.incrementAndGet()
}

private class InmemPipeline(
    val timeout: Duration,
    val peer: InmemTransport,
    val address: ServerAddress,
    override val consumer: Channel<AppendFuture>,
    val clock: Clock,
    parentContext: CoroutineContext
) : AppendPipeline {
    private val scope = CoroutineScope(parentContext + SupervisorJob())
    private val semaphore = Semaphore(16)

    override suspend fun close() {
        scope.cancel()
    }

    override suspend fun appendEntries(req: Rpc.AppendEntriesRequest): AppendFuture {
        val now = clock.now()
        val f = AppendFuture(now, req)
        f.init()
        semaphore.acquire()
        scope.launch {
            try {
                withTimeout(timeout) {
                    val message = Message<Rpc.Request, Rpc.Response>(req)
                    peer.consumerCh.send(message)
                    f.resp = message.response.await() as Rpc.AppendEntriesResponse
                    f.respond(null)
                    consumer.send(f)
                }
            } catch (e: Exception) {
                f.respond(RaftError.Unspecified(e))
            }
        }.invokeOnCompletion { semaphore.release() }

        return f
    }
}

class InmemTransport(
    override val localAddr: ServerAddress = ServerAddress("server #${IdGenerator.next()}"),
    override val consumerCh: Channel<Message<Rpc.Request, Rpc.Response>> = Channel(),
    private val timeout: Duration,
    private val clock: Clock = Clock.System
) : LoopbackTransport {

    companion object {
        val MAX_PROTOCOL_HEADER = RpcHeader(PROTOCOL_VERSION_MAX)
        private val logger = LoggerFactory.getLogger(InmemTransport::class.java)
    }

    private val lock = Mutex()
    private val peers = mutableMapOf<ServerAddress, InmemTransport>()
    private val pipelines = mutableListOf<InmemPipeline>()

    override suspend fun appendEntriesPipeline(target: Peer): AppendPipeline = lock.withLock {
        val peer = peers[target.address] ?: throw IllegalStateException("failed to connect to peer: $target")
        InmemPipeline(timeout, peer, target.address, Channel(), clock, coroutineContext).also {
            pipelines.add(it)
        }
    }

    override suspend fun appendEntries(peer: Peer, req: Rpc.AppendEntriesRequest): Rpc.AppendEntriesResponse {
        return send(peer, req) as Rpc.AppendEntriesResponse
    }

    private suspend fun send(
        peer: Peer,
        req: Rpc.Request
    ): Rpc.Response = lock.withLock {
        val message = Message<Rpc.Request, Rpc.Response>(req)
        val peerTransport = peers[peer.address] ?: throw IllegalStateException("Unknown peer $peer")
        withTimeout(timeout) { peerTransport.consumerCh.send(message) }
        return withTimeout(timeout) { message.response.await() }
    }

    override suspend fun requestVote(peer: Peer, req: Rpc.RequestVoteRequest): Rpc.RequestVoteResponse {
        return send(peer, req) as Rpc.RequestVoteResponse
    }

    override suspend fun installSnapshot(
        peer: Peer,
        req: Rpc.InstallSnapshotRequest,
        snapshot: ReadCloser
    ): Rpc.InstallSnapshotResponse {
        return send(peer, req) as Rpc.InstallSnapshotResponse
    }

    override fun setHeartbeatHandler(f: suspend (rpc: Message<Rpc.Request, Rpc.Response>) -> Unit) {
        logger.info("this transport does not support heartbeat handler")
    }

    override suspend fun timoutNow(peer: Peer, req: Rpc.TimeoutNowRequest): Rpc.TimeoutNowResponse {
        return send(peer, req) as Rpc.TimeoutNowResponse
    }

    override suspend fun close() {
        disconnectAll()
    }

    override suspend fun connect(t: Transport) = lock.withLock {
        peers[t.localAddr] = t as InmemTransport
    }

    override suspend fun disconnect(t: Transport) = lock.withLock {
        peers.remove(t.localAddr)
        val toRemove = pipelines.mapNotNull {
            if (it.address == t.localAddr) {
                it.close()
                it
            } else null
        }
        pipelines.removeAll(toRemove)
        Unit
    }

    override suspend fun disconnectAll() {
        val transports = lock.withLock { peers.values.toList() }
        transports.forEach { t -> disconnect(t) }
    }
}
