@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable

@Serializable
data class Peer(val id: ServerId, val address: ServerAddress)

interface Transport {
    suspend fun appendEntriesPipeline(target: Peer): AppendPipeline
    suspend fun appendEntries(peer: Peer, req: Rpc.AppendEntriesRequest): Rpc.AppendEntriesResponse
    suspend fun requestVote(peer: Peer, req: Rpc.RequestVoteRequest): Rpc.RequestVoteResponse
    suspend fun installSnapshot(
        peer: Peer,
        req: Rpc.InstallSnapshotRequest,
        snapshot: ReadCloser
    ): Rpc.InstallSnapshotResponse

    suspend fun timoutNow(peer: Peer, req: Rpc.TimeoutNowRequest): Rpc.TimeoutNowResponse
    fun setHeartbeatHandler(f: suspend (rpc: Message<Rpc.Request, Rpc.Response>) -> Unit)

    val consumerCh: Channel<Message<Rpc.Request, Rpc.Response>>
    val localAddr: ServerAddress
}

interface AppendPipeline {
    suspend fun close()
    suspend fun appendEntries(req: Rpc.AppendEntriesRequest): AppendFuture

    // Consumer returns a channel that can be used to consume
    // response futures when they are ready.
    val consumer: ReceiveChannel<AppendFuture>
}


// WithClose is an interface that a transport may provide which
// allows a transport to be shut down cleanly when a Raft instance
// shuts down.
//
// It is defined separately from Transport as unfortunately it wasn't in the
// original interface specification.
interface WithClose {
    // Close permanently closes a transport, stopping
    // any associated goroutines and freeing other resources.
    suspend fun close()
}

// WithPeers is an interface that a transport may provide which allows for connection and
// disconnection. Unless the transport is a loopback transport, the transport specified to
// "Connect" is likely to be nil.
interface WithPeers {
    suspend fun connect(t: Transport) // Connect a peer
    suspend fun disconnect(t: Transport)            // Disconnect a given peer
    suspend fun disconnectAll()                   // Disconnect all peers, possibly to reconnect them later
}

// LoopbackTransport is an interface that provides a loopback transport suitable for testing
// e.g. InmemTransport. It's there so we don't have to rewrite tests.
interface LoopbackTransport : Transport, WithClose, WithPeers
