package dev.svilenivanov.raftkt

import kotlinx.serialization.Serializable

@Serializable
data class RpcHeader(val protocolVersion: ProtocolVersion)

interface RpcHeaderProp {
    val header: RpcHeader
}

sealed class Rpc : RpcHeaderProp {
    @Serializable
    data class AppendEntriesRequest<T, E>(
        override val header: RpcHeader,

        // Provide the current term and leader
        val term: Long,
        val leader: ServerId,
        // Provide the previous entries for integrity checking
        val prevLogEntry: Long,
        val prevLogTerm: Long,
        // New entries to commit
        val entries: Iterable<Log<T, E>>,
        // Commit index on the leader
        val leaderCommitIndex: Long
    ) : Rpc()

    @Serializable
    data class AppendEntriesResponse(
        override val header: RpcHeader,

        // Newer term if leader is out of date
        val term: Long,
        // Last Log is a hint to help accelerate rebuilding slow nodes
        val lastLog: Long,
        // We may not succeed if we have a conflicting entry
        val success: Boolean,
        // There are scenarios where this request didn't succeed
        // but there's no need to wait/back-off the next attempt.
        val noRetryBackoff: Boolean
    ) : Rpc()

    @Serializable
    data class RequestVoteRequest(
        override val header: RpcHeader,

        // Provide the term and our id
        val term: Long,
        val candidate: ServerId,

        // Used to ensure safety
        val lastLogIndex: Long,
        val lastLogTerm: Long,

        // Used to indicate to peers if this vote was triggered by a leadership
        // transfer. It is required for leadership transfer to work, because servers
        // wouldn't vote otherwise if they are aware of an existing leader.
        val leadershipTransfer: Boolean
    ) : Rpc()

    @Serializable
    data class RequestVoteResponse(
        override val header: RpcHeader,

        // Newer term if leader is out of date.
        val term: Long,
        // Is the vote granted.
        val granted: Boolean
    ) : Rpc()

    @Serializable
    data class InstallSnapshotRequest(
        override val header: RpcHeader,

        val snapshotVersion: SnapshotVersion,
        // Provide the current term and leader
        val term: Long,
        val leader: ServerId,
        // These are the last index/term included in the snapshot
        val lastLogIndex: Long,
        val lastLogTerm: Long,

        // Cluster membership.
        val configuration: Configuration,
        // Log index where 'Configuration' entry was originally written.
        val configurationIndex: Long,

        // Size of the snapshot
        val size: Long,
    ) : Rpc()

    @Serializable
    data class InstallSnapshotResponse(
        override val header: RpcHeader,

        val term: Long,
        val success: Boolean,
    ) : Rpc()

    @Serializable
    data class TimeoutNowRequest(
        override val header: RpcHeader,
    ) : Rpc()

    @Serializable
    data class TimeoutNowResponse(
        override val header: RpcHeader,
    ) : Rpc()
}
