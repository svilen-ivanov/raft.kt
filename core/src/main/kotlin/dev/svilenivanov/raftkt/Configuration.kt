package dev.svilenivanov.raftkt

import dev.svilenivanov.raftkt.ConfigurationChangeCommand.*
import dev.svilenivanov.raftkt.ServerSuffrage.*
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class ServerAddress(private val address: String) {
    fun isBlank() = address.isBlank()
}

@Serializable
data class Configuration(
    val servers: Set<Server>
) {
    // hasVote returns true if the server identified by 'id' is a Voter in the provided Configuration.
    fun hasVote(serverId: ServerId) = servers.find { it.serverId == serverId }?.suffrage == VOTER

    // checkConfiguration tests a cluster membership configuration for common errors.
    fun check() {
        val idSet = HashMap<ServerId, Boolean>(servers.size)
        val addressSet = HashMap<ServerAddress, Boolean>(servers.size)

        var voters = 0
        servers.forEach { server ->
            if (server.serverId.isBlank()) {
                throw IllegalStateException("empty ID in configuration: $server")
            }
            if (server.address.isBlank()) {
                throw IllegalStateException("empty address in configuration: $server")
            }
            if (idSet.containsKey(server.serverId)) {
                throw IllegalStateException("found duplicate ID in configuration: $server")
            }
            idSet[server.serverId] = true
            if (addressSet.containsKey(server.address)) {
                throw IllegalStateException("found duplicate address in configuration: $server")
            }
            addressSet[server.address] = true
            if (server.suffrage == VOTER) {
                voters++
            }
        }
        if (voters == 0) {
            throw IllegalStateException("need at least one voter in configuration: $this")
        }
    }

    // nextConfiguration generates a new Configuration from the current one and a configuration change request. It's
    // split from appendConfigurationEntry so that it can be unit tested easily.
    fun next(currentIndex: Long, change: ConfigurationChangeRequest): Configuration {
        if (change.prevIndex > 0 && change.prevIndex != currentIndex) {
            throw IllegalStateException("configuration changed since ${change.prevIndex} (latest is $currentIndex)")
        }

        return copy(
            servers = when (change.command) {
                ADD_STAGING -> {
                    // TODO: This should add the server as Staging, to be automatically
                    // promoted to Voter later. However, the promotion to Voter is not yet
                    // implemented, and doing so is not trivial with the way the leader loop
                    // coordinates with the replication goroutines today. So, for now, the
                    // server will have a vote right away, and the Promote case below is
                    // unused.
                    val newServer = Server(VOTER, change.serverId, change.serverAddress)
                    val (found, result) = servers.fold(false to mutableSetOf<Server>()) { (prevFound, acc), it ->
                        val currFound = it.serverId == change.serverId
                        acc.add(
                            if (currFound) {
                                if (it.suffrage == VOTER) {
                                    it.copy(address = change.serverAddress)
                                } else {
                                    newServer
                                }
                            } else it
                        )
                        (prevFound || currFound) to acc
                    }
                    result.apply { if (!found) add(newServer) }
                }
                ADD_NONVOTER -> {
                    val newServer = Server(NON_VOTER, change.serverId, change.serverAddress)
                    val (found, result) = servers.fold(false to mutableSetOf<Server>()) { (prevFound, acc), it ->
                        val currFound = it.serverId == change.serverId
                        acc.add(
                            if (currFound) {
                                if (it.suffrage != NON_VOTER) {
                                    it.copy(address = change.serverAddress)
                                } else {
                                    newServer
                                }
                            } else it
                        )
                        (prevFound || currFound) to acc
                    }
                    result.apply { if (!found) add(newServer) }
                }
                DEMOTE_VOTER -> {
                    servers.map {
                        if (it.serverId == change.serverId) it.copy(suffrage = NON_VOTER)
                        else it
                    }.toSet()
                }
                REMOVE_SERVER -> {
                    servers.filter { it.serverId != change.serverId }.toSet()
                }
                PROMOTE -> {
                    servers.map {
                        if (it.serverId == change.serverId && it.suffrage == STAGING) it.copy(suffrage = VOTER)
                        else it
                    }.toSet()
                }
            }
        ).apply { check() }
    }
}

@Serializable
data class Server(
    // Suffrage determines whether the server gets a vote.
    val suffrage: ServerSuffrage,
    // ID is a unique string identifying this server for all time.
    val serverId: ServerId,
    // Address is its network address that a transport can contact.
    val address: ServerAddress
)

// ServerSuffrage determines whether a Server in a Configuration gets a vote.
@Serializable
enum class ServerSuffrage {
    // Voter is a server whose vote is counted in elections and whose match index
    // is used in advancing the leader's commit index.
    VOTER,

    // Nonvoter is a server that receives log entries but is not considered for
    // elections or commitment purposes.
    NON_VOTER,

    // Staging is a server that acts like a nonvoter with one exception: once a
    // staging server receives enough log entries to be sufficiently caught up to
    // the leader's log, the leader will invoke a  membership change to change
    // the Staging server to a Voter.
    STAGING,
}

enum class ConfigurationChangeCommand {
    // AddStaging makes a server Staging unless its Voter.
    ADD_STAGING,

    // AddNonvoter makes a server Nonvoter unless its Staging or Voter.
    ADD_NONVOTER,

    // DemoteVoter makes a server Nonvoter unless its absent.
    DEMOTE_VOTER,

    // RemoveServer removes a server entirely from the cluster membership.
    REMOVE_SERVER,

    // Promote is created automatically by a leader; it turns a Staging server
    // into a Voter.
    PROMOTE,
}

// configurationChangeRequest describes a change that a leader would like to
// make to its current configuration. It's used only within a single server
// (never serialized into the log), as part of `configurationChangeFuture`.
data class ConfigurationChangeRequest(
    val command: ConfigurationChangeCommand,
    val serverId: ServerId,
    val serverAddress: ServerAddress,
    // only present for AddStaging, AddNonvoter
    // prevIndex, if nonzero, is the index of the only configuration upon which
    // this change may be applied; if another configuration entry has been
    // added in the meantime, this request will fail.
    val prevIndex: Long = 0
)

// configurations is state tracked on every server about its Configurations.
// Note that, per Diego's dissertation, there can be at most one uncommitted
// configuration at a time (the next configuration may not be created until the
// prior one has been committed).
//
// One downside to storing just two configurations is that if you try to take a
// snapshot when your state machine hasn't yet applied the committedIndex, we
// have no record of the configuration that would logically fit into that
// snapshot. We disallow snapshots in that case now. An alternative approach,
// which LogCabin uses, is to track every configuration change in the
// log.
data class Configurations(
    // committed is the latest configuration in the log/snapshot that has been
    // committed (the one with the largest index).
    val committed: Configuration,
    // committedIndex is the log index where 'committed' was written.
    val committedIndex: Long,
    // latest is the latest configuration in the log/snapshot (may be committed
    // or uncommitted)
    val latest: Configuration,
    // latestIndex is the log index where 'latest' was written.
    val latestIndex: Long,
)


