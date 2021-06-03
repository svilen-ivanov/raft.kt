package dev.svilenivanov.raftkt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Commitment is used to advance the leader's commit index. The leader and
// replication goroutines report in newly written entries with Match(), and
// this notifies on commitCh when the commit index has advanced.

// newCommitment returns a commitment struct that notifies the provided
// channel when log entries have been committed. A new commitment struct is
// created each time this server becomes leader for a particular term.
// 'configuration' is the servers in the cluster.
// 'startIndex' is the first index created in this term (see
// its description above).
class Commitment(
    private val commitCh: SendChannel<EmptyMessage>,
    configuration: Configuration,
    private val startIndex: Long
) {
    private val lock = Mutex()
    private var matchIndexes: MutableMap<ServerId, Long> =
        configuration.servers.filter { it.suffrage == ServerSuffrage.VOTER }
            .associateByTo(mutableMapOf(), { it.serverId }) { 0 }
    private var commitIndex: Long = 0

    suspend fun getCommitIndex() = lock.withLock { commitIndex }

    suspend fun setConfiguration(configuration: Configuration) = lock.withLock {
        matchIndexes = configuration.servers.filter { it.suffrage == ServerSuffrage.VOTER }
            .associateByTo(mutableMapOf(), { it.serverId }) { server ->
                matchIndexes[server.serverId] ?: 0
            }
        recalculate()
    }

    // Match is called once a server completes writing entries to disk: either the leader has written the new entry or a
    // follower has replied to an AppendEntries RPC. The given server's disk agrees with this server's log up through
    // the given index.
    suspend fun match(server: ServerId, matchIndex: Long) = lock.withLock {
        matchIndexes[server]?.also { prev ->
            if (matchIndex > prev) {
                matchIndexes[server] = matchIndex
                recalculate()
            }
        }
    }

    // Internal helper to calculate new commitIndex from matchIndexes. Must be called with lock held.
    private suspend fun recalculate() {
        if (matchIndexes.isEmpty()) return

        val matched = matchIndexes.values.sorted()
        val quorumMatchIndex = matched[(matched.size - 1) / 2]
        if (quorumMatchIndex > commitIndex && quorumMatchIndex >= startIndex) {
            commitIndex = quorumMatchIndex
            commitCh.trySend(EmptyMessage)
        }
    }
}

