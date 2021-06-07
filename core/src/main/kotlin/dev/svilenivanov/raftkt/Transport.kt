package dev.svilenivanov.raftkt

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable

@Serializable
data class Peer(val id: ServerId, val address: ServerAddress)

interface Transport {
    fun setHeartbeatHandler(f: suspend (rpc: Message<Rpc.Request, Rpc.Response>) -> Unit)

    val consumer: ReceiveChannel<Message<Rpc.Request, Rpc.Response>>
    val localAddr: ServerAddress
}
