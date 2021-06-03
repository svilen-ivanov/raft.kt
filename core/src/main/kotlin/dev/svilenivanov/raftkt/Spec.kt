package dev.svilenivanov.raftkt
//
//import dev.svilenivanov.raftkt.State.*
//import kotlinx.serialization.Serializable
//import java.lang.IllegalStateException
//
//typealias ServerId = Int
//// TODO: Log is 1-based
//typealias Log = List<LogEntry>
//typealias Index = Int
//typealias Term = Int
//typealias Servers = Set<ServerId>
//
//typealias FollowerIndex = MutableMap<ServerId, Index>
//
//enum class State { FOLLOWER, CANDIDATE, LEADER }
//
//data class LogEntry(
//    val term: Term,
//    val data: Any?
//)
//
//@Serializable
//data class ServerVars(
//    var i: ServerId,
//    var currentTerm: Term,
//    var state: State,
//    var votedFor: ServerId?
//)
//
//data class LogVars(
//    var log: Log,
//    var commitIndex: Index
//)
//
//data class CandidateVars(
//    var votesResponded: Set<ServerId>,
//    var votesGranted: Set<ServerId>,
//)
//
//data class LeaderVars(
//    var nextIndex: FollowerIndex,
//    var matchIndex: FollowerIndex,
//)
//
//// helpers
//
//fun quorum(servers: Servers): Set<Set<ServerId>> {
//    return servers.powerset().filter { i -> cardinality(i) * 2 > cardinality(servers) }.toSet()
//}
//
//fun lastTerm(log: Log) = if (log.isEmpty()) 0 else log.last().term
//fun len(log: Log): Index = log.size
//
////fun <T : Comparable<T>> min(s: Set<T>) = s.minOrNull() ?: throw IllegalStateException("empty set")
////fun <T : Comparable<T>> max(s: Set<T>) = s.maxOrNull() ?: throw IllegalStateException("empty set")
//
//fun initServerVars(serverId: ServerId, serverVars: ServerVars) {
//    serverVars.apply {
//        i = serverId
//        currentTerm = 1
//        state = FOLLOWER
//        votedFor = null
//    }
//}
//
//fun initCandidateVars(servers: Servers, candidateVars: CandidateVars) {
//    servers.forEach { j ->
//        candidateVars.apply {
//            votesResponded = mutableSetOf()
//            votesGranted = mutableSetOf()
//        }
//    }
//}
//
//fun initLeaderVars(servers: Servers, leaderVars: LeaderVars) {
//    servers.forEach { j ->
//        leaderVars.apply {
//            nextIndex[j] = 1
//            matchIndex[j] = 0
//        }
//    }
//}
//
//fun initLog(logVars: LogVars, newLog: Log) {
//    logVars.apply {
//        log = newLog
//        commitIndex = 0
//    }
//}
//
//// ----
//
//fun init(
//    i: ServerId,
//    servers: Servers,
//    serverVars: ServerVars,
//    candidateVars: CandidateVars,
//    leaderVars: LeaderVars,
//    logVars: LogVars,
//    newLog: Log
//) {
//    initServerVars(i, serverVars)
//    initCandidateVars(servers, candidateVars)
//    initLeaderVars(servers, leaderVars)
//    initLog(logVars, newLog)
//}
//
//fun restart(
//    servers: Servers,
//    serverVars: ServerVars,
//    candidateVars: CandidateVars,
//    leaderVars: LeaderVars,
//    logVars: LogVars
//) {
//    serverVars.state = FOLLOWER
//    initCandidateVars(servers, candidateVars)
//    initLeaderVars(servers, leaderVars)
//    logVars.commitIndex = 0
//}
//
//fun timeout(
//    serverVars: ServerVars,
//    candidateVars: CandidateVars,
//) {
//    if (!setOf(FOLLOWER, CANDIDATE).contains(serverVars.state)) {
//        throw IllegalStateException("unexpected state: ${serverVars.state}")
//    }
//    serverVars.apply {
//        state = CANDIDATE
//        currentTerm++
//        votedFor = null
//    }
//    candidateVars.apply {
//        votesResponded = emptySet()
//        votesGranted = emptySet()
//    }
//}
//
//interface Message
//
//data class RequestVoteRequest(
//    val term: Term,
//    val lastLogTerm: Term,
//    val lastLogIndex: Index,
//    val source: ServerId,
//    val dest: ServerId
//) : Message
//
//fun requestVote(
//    servers: Servers,
//    serverVars: ServerVars,
//    candidateVars: CandidateVars,
//    leaderVars: LeaderVars,
//    logVars: LogVars,
//    log: Log,
//    j: ServerId
//) {
//    if (serverVars.state == CANDIDATE && !candidateVars.votesResponded.contains(j)) {
//        send(
//            RequestVoteRequest(
//                term = serverVars.currentTerm,
//                lastLogTerm = lastTerm(log),
//                lastLogIndex = len(log),
//                source = serverVars.i,
//                dest = j
//            )
//        )
//    } else {
//        throw IllegalStateException()
//    }
//}
//
//data class AppendEntriesRequest(
//    val term: Term,
//    val prevLogIndex: Index,
//    val prevLogTerm: Term,
//    val entries: Log,
//    val commitIndex: Index,
//    val source: ServerId,
//    val dest: ServerId
//) : Message
//
//
//fun appendEntries(
//    servers: Servers,
//    serverVars: ServerVars,
//    candidateVars: CandidateVars,
//    leaderVars: LeaderVars,
//    logVars: LogVars,
//    log: Log,
//    j: ServerId
//) {
//    if (serverVars.i != j && serverVars.state == LEADER) {
//        val prevLogIndex = leaderVars.nextIndex[j]!! - 1
//        val prevLogTerm = if (prevLogIndex > 0) log[prevLogIndex].term else 0
//        val lastEntry = minOf(len(log), leaderVars.nextIndex[j]!!)
//        val entries = log.subList(leaderVars.nextIndex[j]!!, lastEntry)
//        send(
//            AppendEntriesRequest(
//                term = serverVars.currentTerm,
//                prevLogIndex = prevLogIndex,
//                prevLogTerm = prevLogTerm,
//                entries = entries,
//                commitIndex = minOf(logVars.commitIndex, lastEntry),
//                source = serverVars.i,
//                dest = j
//            )
//        )
//    } else {
//        throw IllegalStateException()
//    }
//}
//
//fun send(requestVoteRequest: Message) {
//    TODO("Not yet implemented")
//}
