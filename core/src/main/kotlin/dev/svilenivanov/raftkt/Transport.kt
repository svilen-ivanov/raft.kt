package dev.svilenivanov.raftkt

import kotlinx.coroutines.channels.ReceiveChannel

interface Transport {
    fun setHeartbeatHandler(f: (rpc: Rpc) -> Unit)

    val consumer: ReceiveChannel<Message<Rpc.Request, Rpc.Response>>
    val localAddr: ServerAddress
}
