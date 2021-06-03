package dev.svilenivanov.raftkt.inmem

import dev.svilenivanov.raftkt.Log
import dev.svilenivanov.raftkt.LogStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InmemLogStore<T, E> : LogStore<T, E> {
    private val lock = Mutex()
    private var lowIndex: Long? = null
    private var highIndex: Long? = null
    private val logs = mutableMapOf<Long, Log<T, E>>()

    override suspend fun firstIndex() = lock.withLock {
        lowIndex
    }

    override suspend fun lastIndex() = lock.withLock {
        highIndex
    }

    override suspend fun getLog(index: Long): Log<T, E>? = lock.withLock {
        logs[index]
    }

    override suspend fun storeLog(log: Log<T, E>) = storeLogs(sequenceOf(log))

    override suspend fun storeLogs(entries: Sequence<Log<T, E>>) = lock.withLock {
        entries.forEach { l ->
            logs[l.index] = l
            if (lowIndex == null) lowIndex = l.index
            if (highIndex == null || l.index > highIndex!!) highIndex = l.index
        }
    }

    override suspend fun deleteRange(range: LongRange) = lock.withLock {
        range.forEach(logs::remove)
        if (lowIndex == null || range.first <= lowIndex!!) {
            lowIndex = range.last + 1
        }
        if (highIndex == null || range.last >= highIndex!!) {
            highIndex = range.first - 1
        }
        if (lowIndex != null && highIndex != null && lowIndex!! > highIndex!!) {
            lowIndex = null
            highIndex = null
        }
    }
}
