package dev.svilenivanov.raftkt.core2

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InmemLogStore : LogStore {
    private val lock = Mutex()
    private val logs = mutableMapOf<Long, Log>()

    override suspend fun getLog(index: Long): Log? = lock.withLock {
        logs[index]
    }

    override suspend fun storeLog(log: Log) = lock.withLock {
        logs[log.position.index] = log
    }

    override suspend fun deleteRange(range: LongRange) = lock.withLock {
        range.forEach(logs::remove)
    }

    internal suspend fun storeLogs(logs: List<Log>) {
        logs.forEach { storeLog(it) }
    }
}
