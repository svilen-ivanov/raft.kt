package dev.svilenivanov.raftkt.inmem

import dev.svilenivanov.raftkt.StableStore
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class InmemStableStore : StableStore {
    private val kv = ConcurrentHashMap<Int, ByteArray>()

    override suspend fun set(key: ByteArray, value: ByteArray) {
        kv[key.contentHashCode()] = value
    }

    override suspend fun get(key: ByteArray) = kv[key.contentHashCode()]

    override suspend fun setLong(key: ByteArray, value: Long) {
        val buffer = ByteBuffer.allocate(Long.SIZE_BYTES)
        buffer.putLong(value)
        set(key, buffer.array())
    }

    override suspend fun getLong(key: ByteArray): Long? {
        return get(key)?.run {
            ByteBuffer.wrap(this).long
        }
    }
}
