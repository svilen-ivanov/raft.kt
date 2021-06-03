package dev.svilenivanov.raftkt

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// ProtocolVersion is the version of the protocol (which includes RPC messages
// as well as Raft-specific log entries) that this server can _understand_. Use
// the ProtocolVersion member of the Config object to control the version of
// the protocol to use when _speaking_ to other servers. Note that depending on
// the protocol version being spoken, some otherwise understood RPC messages
// may be refused. See dispositionRPC for details of this logic.
//
// There are notes about the upgrade path in the description of the versions
// below. If you are starting a fresh cluster then there's no reason not to
// jump right to the latest protocol version. If you need to interoperate with
// older, version 0 Raft servers you'll need to drive the cluster through the
// different versions in order.
//
// The version details are complicated, but here's a summary of what's required
// to get from a version 0 cluster to version 3:
//
// 1. In version N of your app that starts using the new Raft library with
//    versioning, set ProtocolVersion to 1.
// 2. Make version N+1 of your app require version N as a prerequisite (all
//    servers must be upgraded). For version N+1 of your app set ProtocolVersion
//    to 2.
// 3. Similarly, make version N+2 of your app require version N+1 as a
//    prerequisite. For version N+2 of your app, set ProtocolVersion to 3.
//
// During this upgrade, older cluster members will still have Server IDs equal
// to their network addresses. To upgrade an older member and give it an ID, it
// needs to leave the cluster and re-enter:
//
// 1. Remove the server from the cluster with RemoveServer, using its network
//    address as its ServerID.
// 2. Update the server's config to use a UUID or something else that is
//	  not tied to the machine as the ServerID (restarting the server).
// 3. Add the server back to the cluster with AddVoter, using its new ID.
//
// You can do this during the rolling upgrade from N+1 to N+2 of your app, or
// as a rolling change at any time after the upgrade.
//
// Version History
//
// 0: Original Raft library before versioning was added. Servers running this
//    version of the Raft library use AddPeerDeprecated/RemovePeerDeprecated
//    for all configuration changes, and have no support for LogConfiguration.
// 1: First versioned protocol, used to interoperate with old servers, and begin
//    the migration path to newer versions of the protocol. Under this version
//    all configuration changes are propagated using the now-deprecated
//    RemovePeerDeprecated Raft log entry. This means that server IDs are always
//    set to be the same as the server addresses (since the old log entry type
//    cannot transmit an ID), and only AddPeer/RemovePeer APIs are supported.
//    Servers running this version of the protocol can understand the new
//    LogConfiguration Raft log entry but will never generate one so they can
//    remain compatible with version 0 Raft servers in the cluster.
// 2: Transitional protocol used when migrating an existing cluster to the new
//    server ID system. Server IDs are still set to be the same as server
//    addresses, but all configuration changes are propagated using the new
//    LogConfiguration Raft log entry type, which can carry full ID information.
//    This version supports the old AddPeer/RemovePeer APIs as well as the new
//    ID-based AddVoter/RemoveServer APIs which should be used when adding
//    version 3 servers to the cluster later. This version sheds all
//    interoperability with version 0 servers, but can interoperate with newer
//    Raft servers running with protocol version 1 since they can understand the
//    new LogConfiguration Raft log entry, and this version can still understand
//    their RemovePeerDeprecated Raft log entries. We need this protocol version
//    as an intermediate step between 1 and 3 so that servers will propagate the
//    ID information that will come from newly-added (or -rolled) servers using
//    protocol version 3, but since they are still using their address-based IDs
//    from the previous step they will still be able to track commitments and
//    their own voting status properly. If we skipped this step, servers would
//    be started with their new IDs, but they wouldn't see themselves in the old
//    address-based configuration, so none of the servers would think they had a
//    vote.
// 3: Protocol adding full support for server IDs and new ID-based server APIs
//    (AddVoter, AddNonvoter, etc.), old AddPeer/RemovePeer APIs are no longer
//    supported. Version 2 servers should be swapped out by removing them from
//    the cluster one-by-one and re-adding them with updated configuration for
//    this protocol version, along with their server ID. The remove/add cycle
//    is required to populate their server ID. Note that removing must be done
//    by ID, which will be the old server's address.
enum class ProtocolVersion(val version: Int) {
    VERSION_3(3)
}

val PROTOCOL_VERSION_MAX = ProtocolVersion.values().last()

typealias ServerId = String

// SnapshotVersion is the version of snapshots that this server can understand.
// Currently, it is always assumed that the server generates the latest version,
// though this may be changed in the future to include a configurable version.
//
// Version History
//
// 0: Original Raft library before versioning was added. The peers portion of
//    these snapshots is encoded in the legacy format which requires decodePeers
//    to parse. This version of snapshots should only be produced by the
//    unversioned Raft library.
// 1: New format which adds support for a full configuration structure and its
//    associated log index, with support for server IDs and non-voting server
//    modes. To ease upgrades, this also includes the legacy peers structure but
//    that will never be used by servers that understand version 1 snapshots.
//    Since the original Raft library didn't enforce any versioning, we must
//    include the legacy peers structure for this version, but we can deprecate
//    it in the next snapshot version.
enum class SnapshotVersion(val version: Int) {
    VERSION_1(1)
}

val SNAPSHOT_VERSION_MAX = SnapshotVersion.values().last()

/**
 * Config provides any necessary configuration for the Raft server.
 */
data class Config(
    // ProtocolVersion allows a Raft server to inter-operate with older
    // Raft servers running an older version of the code. This is used to
    // version the wire protocol as well as Raft-specific log entries that
    // the server uses when _speaking_ to other servers. There is currently
    // no auto-negotiation of versions so all servers must be manually
    // configured with compatible versions. See ProtocolVersionMin and
    // ProtocolVersionMax for the versions of the protocol that this server
    // can _understand_.
    val protocolVersion: ProtocolVersion = PROTOCOL_VERSION_MAX,

    // HeartbeatTimeout specifies the time in follower state without
    // a leader before we attempt an election.
    val heartbeatTimeout: Duration = milliseconds(1000),

    // ElectionTimeout specifies the time in candidate state without
    // a leader before we attempt an election.
    val electionTimeout: Duration = milliseconds(1000),

    // CommitTimeout controls the time without an Apply() operation
    // before we heartbeat to ensure a timely commit. Due to random
    // staggering, may be delayed as much as 2x this value.
    val commitTimeout: Duration = milliseconds(50),

    // MaxAppendEntries controls the maximum number of append entries
    // to send at once. We want to strike a balance between efficiency
    // and avoiding waste if the follower is going to reject because of
    // an inconsistent log.
    val maxAppendEntries: Int = 64,

    // BatchApplyCh indicates whether we should buffer applyCh
    // to size MaxAppendEntries. This enables batch log commitment,
    // but breaks the timeout guarantee on Apply. Specifically,
    // a log can be added to the applyCh buffer but not actually be
    // processed until after the specified timeout.
    val batchApplyCh: Boolean = false,

    // If we are a member of a cluster, and RemovePeer is invoked for the
    // local node, then we forget all peers and transition into the follower state.
    // If ShutdownOnRemove is set, we additional shutdown Raft. Otherwise,
    // we can become a leader of a cluster containing only this node.
    val shutdownOnRemove: Boolean = true,

    // TrailingLogs controls how many logs we leave after a snapshot. This is used
    // so that we can quickly replay logs on a follower instead of being forced to
    // send an entire snapshot. The value passed here is the initial setting used.
    // This can be tuned during operation using ReloadConfig.
    val trailingLogs: Long = 10240,


    // SnapshotInterval controls how often we check if we should perform a
    // snapshot. We randomly stagger between this value and 2x this value to avoid
    // the entire cluster from performing a snapshot at once. The value passed
    // here is the initial setting used. This can be tuned during operation using
    // ReloadConfig.
    val snapshotInterval: Duration = seconds(120),

    // SnapshotThreshold controls how many outstanding logs there must be before
    // we perform a snapshot. This is to prevent excessive snapshotting by
    // replaying a small set of logs instead. The value passed here is the initial
    // setting used. This can be tuned during operation using ReloadConfig.
    val snapshotThreshold: Long = 8192,

    // LeaderLeaseTimeout is used to control how long the "lease" lasts
    // for being the leader without being able to contact a quorum
    // of nodes. If we reach this interval without contact, we will
    // step down as leader.
    val leaderLeaseTimeout: Duration = milliseconds(500),

    // LocalID is a unique ID for this server across all time. When running with
    // ProtocolVersion < 3, you must set this to be the same as the network
    // address of your transport.
    val localId: ServerId,

    // NoSnapshotRestoreOnStart controls if raft will restore a snapshot to the
    // FSM on start. This is useful if your FSM recovers from other mechanisms
    // than raft snapshotting. Snapshot metadata will still be used to initialize
    // raft's configuration and index values.
    val noSnapshotRestoreOnStart: Boolean = false,

    // skipStartup allows NewRaft() to bypass all background work goroutines
    val skipStartup: Boolean = false,
) {
    init {
        validate()
    }

    private fun validate() {
        if (localId.isBlank()) {
            throw IllegalArgumentException("localId cannot be empty")
        }
        if (this.heartbeatTimeout < milliseconds(5)) {
            throw IllegalArgumentException("heartbeatTimeout is too low")
        }
        if (electionTimeout < milliseconds(5)) {
            throw IllegalArgumentException("electionTimeout is too low")
        }
        if (commitTimeout < milliseconds(1)) {
            throw IllegalArgumentException("commitTimeout is too low")
        }
        if (maxAppendEntries <= 0) {
            throw IllegalArgumentException("maxAppendEntries must be positive")
        }
        if (maxAppendEntries > 1024) {
            throw IllegalArgumentException("maxAppendEntries is too large")
        }
        if (snapshotInterval < milliseconds(5)) {
            throw IllegalArgumentException("snapshotInterval is too low")
        }
        if (leaderLeaseTimeout < milliseconds(5)) {
            throw IllegalArgumentException("leaderLeaseTimeout is too low")
        }
        if (leaderLeaseTimeout > heartbeatTimeout) {
            throw IllegalArgumentException("leaderLeaseTimeout cannot be larger than heartbeat timeout")
        }
        if (electionTimeout < heartbeatTimeout) {
            throw IllegalArgumentException("electionTimeout must be equal or greater than Heartbeat Timeout")
        }
    }
}

// ReloadableConfig is the subset of Config that may be reconfigured during
// runtime using raft.ReloadConfig. We choose to duplicate fields over embedding
// or accepting a Config but only using specific fields to keep the API clear.
// Reconfiguring some fields is potentially dangerous so we should only
// selectively enable it for fields where that is allowed.
data class ReloadableConfig(
    // TrailingLogs controls how many logs we leave after a snapshot. This is used
    // so that we can quickly replay logs on a follower instead of being forced to
    // send an entire snapshot. The value passed here updates the setting at runtime
    // which will take effect as soon as the next snapshot completes and truncation
    // occurs.
    val trailingLogs: Long,

    // SnapshotInterval controls how often we check if we should perform a snapshot.
    // We randomly stagger between this value and 2x this value to avoid the entire
    // cluster from performing a snapshot at once.
    val snapshotInterval: Duration,

    // SnapshotThreshold controls how many outstanding logs there must be before
    // we perform a snapshot. This is to prevent excessive snapshots when we can
    // just replay a small set of logs.
    val snapshotThreshold: Long,
) {
    fun apply(to: Config) = to.copy(
        trailingLogs = trailingLogs,
        snapshotThreshold = snapshotThreshold,
        snapshotInterval = snapshotInterval
    )

    companion object {
        fun fromConfig(from: Config) = ReloadableConfig(
            trailingLogs = from.trailingLogs,
            snapshotThreshold = from.snapshotThreshold,
            snapshotInterval = from.snapshotInterval
        )
    }
}

