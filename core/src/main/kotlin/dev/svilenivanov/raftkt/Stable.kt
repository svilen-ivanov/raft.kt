package dev.svilenivanov.raftkt

interface StableStore {
    suspend fun set(key: ByteArray, value: ByteArray)
    suspend fun get(key: ByteArray): ByteArray?

    suspend fun setLong(key: ByteArray, value: Long)
    suspend fun getLong(key: ByteArray): Long?
}
