package dev.svilenivanov.raftkt.inmem

import dev.svilenivanov.raftkt.Log
import dev.svilenivanov.raftkt.LogStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InmemLogStore : LogStore {
    private val lock = Mutex()
    private var lowIndex: Long? = null
    private var highIndex: Long? = null
    private val logs = mutableMapOf<Long, Log>()

    override suspend fun firstIndex() = lock.withLock {
        lowIndex
    }

    override suspend fun lastIndex() = lock.withLock {
        highIndex
    }

    override suspend fun getLog(index: Long): Log? = lock.withLock {
        logs[index]
    }

    override suspend fun storeLog(log: Log) = storeLogs(sequenceOf(log))

    override suspend fun storeLogs(entries: Sequence<Log>) = lock.withLock {
        entries.forEach { l ->
            logs[l.position.index] = l
            if (lowIndex == null) lowIndex = l.position.index
            if (highIndex == null || l.position.index > highIndex!!) highIndex = l.position.index
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
