package dev.svilenivanov.raftkt.inmem

import dev.svilenivanov.raftkt.StableStore
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class InmemStableStore : StableStore {
    private val kv = ConcurrentHashMap<Int, Any>()

    override suspend fun <T> set(key: ByteArray, value: T) {
        kv[key.contentHashCode()] = value as Any
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> get(key: ByteArray) = kv[key.contentHashCode()] as T?
}
