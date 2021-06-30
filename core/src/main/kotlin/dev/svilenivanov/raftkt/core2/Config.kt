package dev.svilenivanov.raftkt.core2

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class ProtocolVersion(val version: Int) {
    VERSION_1(1)
}

val PROTOCOL_VERSION_MAX = ProtocolVersion.values().last()

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
)
