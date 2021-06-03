package dev.svilenivanov.raftkt

interface StableStore {
    fun getCurrentTerm(): Long?
}
