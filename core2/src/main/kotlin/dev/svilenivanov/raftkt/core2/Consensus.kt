package dev.svilenivanov.raftkt.core2

import dev.svilenivanov.raftkt.core2.NodeRole.FOLLOWER
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Consensus(
    val persistent: PersistentState,
    val volatile: VolatileState,
    var leader: ServerId?,
    var role: NodeRole = FOLLOWER
) {
    private val lock = Mutex()

    suspend fun <T> use(block: suspend Consensus.() -> T) = lock.withLock { block() }
}
