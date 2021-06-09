package dev.svilenivanov.raftkt

import kotlinx.serialization.Serializable

interface StableStore {
    suspend fun <T> set(key: ByteArray, value: T)
    suspend fun <T> get(key: ByteArray): T?
}
