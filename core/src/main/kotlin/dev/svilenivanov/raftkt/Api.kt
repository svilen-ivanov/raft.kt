@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt

import dev.svilenivanov.raftkt.Observation.LeaderObservation
import dev.svilenivanov.raftkt.ServerSuffrage.VOTER
import dev.svilenivanov.raftkt.State.*
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.coroutineContext

// This is the current suggested max size of the data in a raft log entry.
// This is based on current architecture, default timing, etc. Clients can
// ignore this value if they want as there is no actual hard checking
// within the library. As the library is enhanced this value may change
// over time to reflect current suggested maximums.
//
// Increasing beyond this risks RPC IO taking too long and preventing
// timely heartbeat signals which are sent in serial in current transports,
// potentially causing leadership instability.
const val SuggestedMaxDataSize = 512 * 1024

sealed class RaftError(message: String) : Throwable(message) {
    class Unspecified(message: String) : RaftError(message)

    object ErrNotLeader : RaftError("node is not the leader")
    object ErrLeadershipLost : RaftError("leadership lost while committing log")
    object ErrAbortedByRestore : RaftError("snapshot restored while committing log")
    object ErrRaftShutdown : RaftError("raft is already shutdown")
    object ErrEnqueueTimeout : RaftError("timed out enqueuing operation")
    object ErrNothingNewToSnapshot : RaftError("nothing new to snapshot")
    object ErrUnsupportedProtocol : RaftError("operation not supported with current protocol version")
    object ErrCantBootstrap : RaftError("bootstrap only works on new clusters")
    object ErrLeadershipTransferInProgress : RaftError("leadership transfer in progress")
    object ErrLeadershipTransferTimeout : RaftError("leadership transfer timeout")
    object ErrLeadershipTransferLost : RaftError("lost leadership during transfer (expected)")
    object ErrCannotFindPeer : RaftError("cannot find peer")
    class GeneralException(cause: Throwable) : RaftError(cause.toString())
    class ErrUnexpectedRequest(request: Rpc.Request) : RaftError("expected heartbeat, got ${request::class}")
}

class Raft<T, E, R> private constructor(
    config: Config,
    // FSM is the client state machine to apply commands to
    private val fsm: Fsm<T, E, R>,
    // LogStore provides durable storage for logs
    private val logs: LogStore,
    // stable is a StableStore implementation for durable state
    // It provides stable storage for many fields in raftState
    private val stable: StableStore,
    // snapshots is used to store and retrieve snapshots
    private val snapshots: SnapshotStore,
    // The transport layer we use
    private val transport: Transport,
    // clock
    private val clock: Clock = Clock.System
) {
    companion object {
        @JvmStatic
        private val logger: Logger = LoggerFactory.getLogger(Raft::class.java)

        suspend fun <T, E, R> create(
            config: Config,
            fsm: Fsm<T, E, R>,
            logs: LogStore,
            stable: StableStore,
            snaps: SnapshotStore,
            transport: Transport
        ): Raft<T, E, R> {
            return Raft(config, fsm, logs, stable, snaps, transport).apply { init() }
        }
    }

    // protocolVersion is used to inter-operate with Raft servers running
    // different versions of the library. See comments in config.go for more
    // details.
    private lateinit var protocolVersion: ProtocolVersion;

    // applyCh is used to async send logs to the main thread to
    // be committed and applied to the FSM.
    private lateinit var applyCh: Channel<Message<Log, R>>

    // conf stores the current configuration to use. This is the most recent one
    // provided. All reads of config values should use the config() helper method
    // to read this safely.
    private val config = atomic(config)

    // confReloadMu ensures that only one thread can reload config at once since
    // we need to read-modify-write the atomic. It is NOT necessary to hold this
    // for any other operation e.g. reading config using config().
    private val confReloadMu = Mutex()

    // fsmMutateCh is used to send state-changing updates to the FSM. This
    // receives pointers to commitTuple structures when applying logs or
    // pointers to restoreFuture structures when restoring a snapshot. We
    // need control over the order of these operations when doing user
    // restores so that we finish applying any old log applies before we
    // take a user snapshot on the leader, otherwise we might restore the
    // snapshot and apply old logs to it that were in the pipe.
    private lateinit var fsmMutateCh: Channel<Sequence<Message<Log, R?>>>

    // fsmSnapshotCh is used to trigger a new snapshot being taken
    private lateinit var fsmSnapshotCh: Channel<Any>

    // lastContact is the last time we had contact from the
    // leader node. This can be used to gauge staleness.
    private val lastContact = atomic(Instant.DISTANT_PAST)

    // Leader is the current cluster leader
    private val leader = atomic<Peer?>(null)

    // leaderCh is used to notify of leadership changes
    private lateinit var leaderCh: Channel<Boolean>

    // leaderState used only while state is leader
    private var leaderState: LeaderState<R>? = null

    // candidateFromLeadershipTransfer is used to indicate that this server became
    // candidate because the leader tries to transfer leadership. This flag is
    // used in RequestVoteRequest to express that a leadership transfer is going
    // on.
    private var candidateFromLeadershipTransfer = false

    // Stores our local server ID, used to avoid sending RPCs to ourself
    private val localId: ServerId get() = config.value.localId

    // Stores our local addr
    private val localAddr: ServerAddress get() = transport.localAddr

    // Used to request the leader to make configuration changes.
    private lateinit var configurationChangeCh: Channel<Message<Configuration, Unit>>

    // Tracks the latest configuration and latest committed configuration from
    // the log/snapshot.
    private var configurations = Configurations.NONE

    // Holds a copy of the latest configuration which can be read
    // independently from main loop.
    private val latestConfiguration = atomic<Configuration?>(null)

    // Holds a copy of the latest configuration which can be read
    // independently from main loop.
    private lateinit var rpcCh: ReceiveChannel<Message<Rpc.Request, Rpc.Response>>

    // Shutdown channel to exit, protected to prevent concurrent exits
    private var shutdown: Boolean = false
    private val shutdownMutex = Mutex()

    // userSnapshotCh is used for user-triggered snapshots
    private lateinit var userSnapshotCh: Channel<Any>

    // userRestoreCh is used for user-triggered restores of external snapshots
    private lateinit var userRestoreCh: Channel<Message<Any, Any>>

    // verifyCh is used to async send verify futures to the main thread
    // to verify we are still the leader
    private lateinit var verifyCh: Channel<Message<Verify, Unit>>

    // configurationsCh is used to get the configuration data safely from
    // outside of the main thread.
    private lateinit var configurationsCh: Channel<Message<Unit, Configurations>>

    // bootstrapCh is used to attempt an initial bootstrap from outside of the main thread.
    private lateinit var bootstrapCh: Channel<Message<Configuration, Unit>>

    // List of observers and the mutex that protects them. The observers list
    // is indexed by an artificial ID which is used for deregistration.
//    private val observersLock = Mutex()
    private val observers = Observers()

    // leadershipTransferCh is used to start a leadership transfer from outside of
    // the main thread.
    private lateinit var leadershipTransferCh: Channel<Message<Peer?, Unit>>

    // NotifyCh is used to provide a channel that will be notified of leadership
    // changes. Raft will block writing to this channel, so it should either be
    // buffered or aggressively consumed.
    private var notifyCh: Channel<Boolean>? = null

    private val raftState = RaftState()
    private lateinit var rpc: Channel<Rpc>
    private lateinit var groupJob: Job
    private val header = RpcHeader(protocolVersion = config.protocolVersion)

    @Suppress("UNUSED_VARIABLE")
    suspend fun init() {
        val currentTerm = stable.get<Long>(Key.CURRENT_TERM) ?: 0
        val lastIndex = logs.lastIndex()
        val lastLog = lastIndex?.run {
            if (lastIndex > 0) {
                logs.getLog(lastIndex)?.position
                    ?: throw IllegalStateException("failed to get last log at index $lastIndex")
            } else {
                null
            }
        } ?: RaftState.ZERO_POSITION

        // Buffer applyCh to MaxAppendEntries if the option is enabled
        applyCh = Channel(if (config.value.batchApplyCh) config.value.maxAppendEntries else Channel.RENDEZVOUS)
        // Create Raft struct.
        protocolVersion = config.value.protocolVersion
        fsmMutateCh = Channel(128)
        fsmSnapshotCh = Channel()
        leaderCh = Channel(1)
        configurationChangeCh = Channel()
        rpcCh = transport.consumer
        userSnapshotCh = Channel()
        userRestoreCh = Channel()
        verifyCh = Channel(64)
        configurationsCh = Channel(8)
        bootstrapCh = Channel()
        leadershipTransferCh = Channel(1)
        notifyCh = Channel(64)

        raftState.apply {
            // Initialize as a follower.
            setState(FOLLOWER)
            // Restore the current term and the last log.
            setCurrentTerm(currentTerm)
            setLastLog(lastLog)
        }

        // Attempt to restore a snapshot if there are any.
        restoreSnapshot()
        val snapshotIndex = raftState.getLastSnapshot().index
        for (index in (snapshotIndex + 1).rangeTo(lastLog.index)) {
            val entry = logs.getLog(index) ?: throw IllegalStateException("failed to get log index=$index")
            processConfigLogEntry(entry)
        }
        logger.info(
            "initial configuration index={}, servers={}",
            configurations.latestIndex,
            configurations.latest
        )
        // Setup a heartbeat fast-path to avoid head-of-line
        // blocking where possible. It MUST be safe for this
        // to be called concurrently with a blocking RPC.
        transport.setHeartbeatHandler(this::processHeartbeat)
        if (config.value.skipStartup) return

        groupJob = supervisorScope {
            launch { run() }
            launch { runFsm() }
            launch { runSnapshots() }
        }
    }

    private fun runSnapshots() {
        TODO("Not yet implemented")
    }

    private fun runFsm() {
        TODO("Not yet implemented")
    }

    private fun processConfigLogEntry(entry: Log) {
        if (entry.data is Log.Data.Configuration) {
            configurations =
                Configurations(
                    committed = configurations.latest,
                    committedIndex = configurations.latestIndex,
                    latest = entry.data.configuration,
                    latestIndex = entry.position.index
                )

        }
    }

    private suspend fun restoreSnapshot() {
        val snapshot = snapshots.list().firstOrNull()
            ?: throw IllegalStateException("failed to load any existing snapshots")
        if (!config.value.noSnapshotRestoreOnStart) {
            val source = snapshots.open(snapshot.id)
            fsm.restore(source)
            logger.info("Restored from snapshot {}", source.meta.id)
        }
        raftState.setLastApplied(snapshot.position.index)
        raftState.setLastSnapshot(snapshot.position)
        configurations = Configurations(
            snapshot.configuration, snapshot.configurationIndex,
            snapshot.configuration, snapshot.configurationIndex
        )
        latestConfiguration.value = snapshot.configuration
    }

    private fun setCommittedConfiguration(configuration: Configuration, configurationIndex: Long) {
        configurations = configurations.copy(committed = configuration, committedIndex = configurationIndex)
    }

    private fun setLatestConfiguration(configuration: Configuration, configurationIndex: Long) {
        configurations = configurations.copy(latest = configuration, latestIndex = configurationIndex)
        latestConfiguration.value = configuration
    }

    private suspend fun run() {
        while (coroutineContext.isActive) {
            return when (raftState.getState()) {
                FOLLOWER -> runFollower()
                CANDIDATE -> runCandidate()
                LEADER -> runLeader()
                SHUTDOWN -> {
                    setLeader(null)
                    break
                }
            }
        }
    }

    private suspend fun runLeader() {
        logger.info("entering candidate state: node={}", localPeer)
        // Notify that we are the leader
        overrideNotifyBool(leaderCh, true)

        // Store the notify chan. It's not reloadable so shouldn't change before the
        // defer below runs, but this makes sure we always notify the same chan if
        // ever for both gaining and loosing leadership.
        val notify = notifyCh
        notify?.send(true)

        // setup leader state. This is only supposed to be accessed within the
        // leaderloop.
        setupLeadershipState()
        try {
            // Dispatch a no-op log entry first. This gets this leader up to the latest
            // possible commit index, even in the absence of client commands. This used
            // to append a configuration entry instead of a noop. However, that permits
            // an unbounded number of uncommitted configurations in the log. We now
            // maintain that there exists at most one uncommitted configuration entry in
            // any log, so we have to do proper no-ops here.
            dispatchLogs(listOf(Log.Data.Nop))
            leaderLoop()
        } finally {
            cleanupRunLeader()
        }
    }

    private suspend fun leaderLoop() {
// stepDown is used to track if there is an inflight log that
        // would cause us to lose leadership (specifically a RemovePeer of
        // ourselves). If this is the case, we must not allow any logs to
        // be processed in parallel, otherwise we are basing commit on
        // only a single peer (ourself) and replicating to an undefined set
        // of peers.
        var stepDown = false
        // This is only used for the first lease check, we reload lease below
        // based on the current config value.
        var lease = config.value.leaderLeaseTimeout

        val cleanup = mutableListOf<() -> Unit>()

        try {
            coroutineScope {
                val leaseChanel = Channel<Any>()
                withTimeout(config.value.electionTimeout) {
                    leaseChanel.send(true)
                }
                while (isActive && raftState.getState() == LEADER) {
                    select<Unit> {
                        rpcCh.onReceive { processRpc(it) }
                        leaderState!!.stepDown.onReceive { raftState.setState(FOLLOWER) }
                        leadershipTransferCh.onReceive { lt ->
                            if (leaderState!!.leadershipTransferInProgress.value) {
                                lt.response.completeExceptionally(RaftError.ErrLeadershipTransferInProgress)
                                return@onReceive
                            }
                            val req = lt.request!!
                            logger.debug("starting leadership transfer, id={}, address={}", req.id, req.address)
                            val leftLeaderLoop = Channel<Any>()
                            cleanup += { leftLeaderLoop.close() }
                            val stopCh = Channel<Unit>()
                            val doneCh = Channel<RaftError?>(1)

                            // This is intentionally being setup outside of the
                            // leadershipTransfer function. Because the TimeoutNow
                            // call is blocking and there is no way to abort that
                            // in case eg the timer expires.
                            // The leadershipTransfer function is controlled with
                            // the stopCh and doneCh.
                            launch {
                                withTimeout(config.value.electionTimeout) {
                                    stopCh.close()
                                    lt.response.completeExceptionally(RaftError.ErrLeadershipTransferTimeout)
                                    logger.debug("leadership transfer timeout")
                                    doneCh.send(RaftError.ErrLeadershipTransferTimeout)
                                }
                                select<Unit> {
                                    leftLeaderLoop.onReceiveCatching {
                                        stopCh.close()
                                        lt.response.completeExceptionally(RaftError.ErrLeadershipTransferLost)
                                        logger.debug("lost leadership during transfer (expected)")
                                        doneCh.send(RaftError.ErrLeadershipTransferLost)
                                    }
                                    doneCh.onReceiveCatching {
                                        lt.respond { }
                                    }
                                }
                            }

                            // leaderState.replState is accessed here before
                            // starting leadership transfer asynchronously because
                            // leaderState is only supposed to be accessed in the
                            // leaderloop.
                            val peer = lt.request.run { pickServer()?.peer }
                            if (peer == null) {
                                logger.error("cannot find peer")
                                doneCh.send(RaftError.ErrCannotFindPeer)
                                return@onReceive
                            }
                            val state = leaderState!!.replState[peer.id]
                            if (state == null) {
                                logger.error("cannot find replication state for {}", peer.id)
                                doneCh.send(RaftError.Unspecified("cannot find replication state for $peer.id"))
                                return@onReceive
                            }
                            launch {
                                leadershipTransfer(peer, state, stopCh, doneCh)
                            }
                        }

                        leaderState!!.commitCh.onReceiveCatching {
                            // Process the newly committed entries
                            val oldCommitIndex = raftState.getCommitIndex()
                            val commitIndex = leaderState!!.commitment.getCommitIndex()
                            raftState.setCommitIndex(commitIndex)

                            // New configration has been committed, set it as the committed
                            // value.
                            if (configurations.latestIndex > oldCommitIndex
                                && configurations.latestIndex <= commitIndex
                            ) {
                                setCommittedConfiguration(configurations.latest, configurations.latestIndex)
                                if (!configurations.committed.hasVote(localId)) {
                                    stepDown = true
                                }
                            }

                            val groupFutures = mutableMapOf<Long, Message<Log, R?>>()
                            var lastIdxGroup: Long = 0
                            for (commitLog in leaderState!!.inflight) {
                                val idx = commitLog.request.position.index
                                if (idx > commitIndex) {
                                    // Don't go past the committed index
                                    break
                                }
                                groupFutures[idx] = commitLog
                                lastIdxGroup = idx
                            }

                            if (groupFutures.isNotEmpty()) {
                                processLogs(lastIdxGroup, groupFutures)
                                leaderState!!.inflight.removeAll(groupFutures.values)
                            }

                            if (stepDown) {
                                if (config.value.shutdownOnRemove) {
                                    logger.info("removed ourself, shutting down")
                                    shutdownFn()
                                } else {
                                    logger.info("removed ourself, transitioning to follower")
                                    raftState.setState(FOLLOWER)
                                }
                            }
                        }

                        verifyCh.onReceive { r: Message<Verify, Unit> ->
                            val v = r.request
                            when {
                                v.quorumSize == 0 -> {
                                    // Just dispatched, start the verification
                                    verifyLeader(r)
                                }
                                v.votes < v.quorumSize -> {
                                    // Early return, means there must be a new leader
                                    logger.warn("new leader elected, stepping down")
                                    raftState.setState(FOLLOWER)
                                    leaderState!!.notify.remove(r)
                                    leaderState!!.replState.forEach { (_, _) ->
//                                        state.cleanupNotify(v)
                                    }
                                    r.response.completeExceptionally(RaftError.ErrNotLeader)
                                }
                                else -> {
                                    // Quorum of members agree, we are still leader
                                    leaderState!!.notify.remove(r)
                                    leaderState!!.replState.forEach { (_, _) ->
//                                        state.cleanupNotify(v)
                                    }
                                    r.respond { }
                                }
                            }
                        }

                    }
                }
            }
        } finally {
            cleanup.forEach { it() }
        }
    }

    // verifyLeader must be called from the main thread for safety.
    // Causes the followers to attempt an immediate heartbeat.

    private suspend fun verifyLeader(m: Message<Verify, Unit>) {
        val v = m.request
        // Current leader always votes for self
        v.votes = 1
        // Set the quorum size, hot-path for single node
        v.quorumSize = quorumSize()
        if (v.quorumSize == 1) {
            m.respond { }
            return
        }
//        leaderState!!.notify.add(m.request)


    }

    private suspend fun shutdownFn() = shutdownMutex.withLock {
        if (!shutdown) {
            shutdown = true
            raftState.setState(SHUTDOWN)
            throw CancellationException("shutting down")
        }
    }

    private suspend fun leadershipTransfer(
        peer: Peer,
        state: FollowerReplication,
        stopCh: Channel<Unit>,
        doneCh: Channel<RaftError?>
    ) {

        if (!stopCh.isEmpty) {
            stopCh.receiveCatching()
            return
        }
        try {
            // Step 1: set this field which stops this leader from responding to any client requests.
            leaderState!!.leadershipTransferInProgress.value = true

            while (state.nextIndex.value <= raftState.getLastIndex()) {
                val err = Message<Unit, Unit>(Unit)
                state.triggerDeferErrorCh.send(err)
                try {
                    select<Unit> {
                        err.response.onAwait {}
                        stopCh.onReceive {
                            doneCh.send(null)
                        }
                    }
                } catch (e: Exception) {
                    doneCh.send(RaftError.GeneralException(e))
                    break
                }
            }

            // Step ?: the thesis describes in chap 6.4.1: Using clocks to reduce
            // messaging for read-only queries. If this is implemented, the lease
            // has to be reset as well, in case leadership is transferred. This
            // implementation also has a lease, but it serves another purpose and
            // doesn't need to be reset. The lease mechanism in our raft lib, is
            // setup in a similar way to the one in the thesis, but in practice
            // it's a timer that just tells the leader how often to check
            // heartbeats are still coming in.

            // Step 3: send TimeoutNow message to target server.
            try {
                transport.timoutNow(peer)
                doneCh.send(null)
            } catch (e: Exception) {
                doneCh.send(RaftError.GeneralException(e))
            }
        } finally {
            leaderState!!.leadershipTransferInProgress.value = false
        }
    }

    // pickServer returns the follower that is most up to date and participating in quorum.
    // Because it accesses leaderstate, it should only be called from the leaderloop.
    private fun pickServer(): Server? {
        var current = 0L
        var pick: Server? = null
        for (server in configurations.latest.servers) {
            if (server.serverId == localId || server.suffrage != VOTER) continue
            val replState = leaderState!!.replState[server.serverId] ?: continue
            val nextIdx = replState.nextIndex.value
            if (nextIdx > current) {
                current = nextIdx
                pick = server
            }
        }
        return pick
    }

    private suspend fun dispatchLogs(applyLogs: List<Log.Data>): List<Message<Log, R?>> {
        val term = raftState.getCurrentTerm()
        var lastIndex = raftState.getLastIndex()
        val now = clock.now()

        val logList = applyLogs.map {
            lastIndex++
            Log(Position(term, lastIndex), now, it)
        }
        val messageList = logList.map { Message<Log, R?>(it) }
        leaderState!!.inflight.addAll(messageList)
        try {
            logs.storeLogs(logList.asSequence())
        } catch (e: Exception) {
            logger.error("failed to commit logs", e)
            messageList.forEach { it.response.completeExceptionally(e) }
        }
        leaderState!!.commitment.match(localId, lastIndex)
        // Update the last log since it's on disk now
        raftState.setLastLog(Position(term, lastIndex))
        // Notify the replicators of the new log
        leaderState!!.replState.forEach { (_, _) ->
            // TODO
            // 		asyncNotifyCh(f.triggerCh)
        }
        return messageList
    }

    private suspend fun cleanupRunLeader() {
        // Since we were the leader previously, we update our
        // last contact time when we step down, so that we are not
        // reporting a last contact time from before we were the
        // leader. Otherwise, to a client it would seem our data
        // is extremely stale.
        setLastContact()

        leaderState!!.inflight.forEach {
            it.response.completeExceptionally(RaftError.ErrLeadershipLost)
        }

        // Respond to any pending verify requests
//        leaderState!!.notify.consumeEach {
//            it.response.completeExceptionally(Error.ErrLeadershipLost)
//        }

        // Clear all the state
        leaderState = null

        // If we are stepping down for some reason, no known leader.
        // We may have stepped down due to an RPC call, which would
        // provide the leader, so we cannot always blank this out.
        leader.update {
            if (it != null && it.address == localAddr) null else it
        }
        overrideNotifyBool(leaderCh, false)

        if (notifyCh != null) {
            notifyCh!!.trySend(false)
        }
    }


    private suspend fun setupLeadershipState() {
        val commitCh = Channel<Unit>(1)
        leaderState = LeaderState(
            commitment = Commitment(
                commitCh,
                configurations.latest,
                raftState.getLastIndex() + 1 // first index that may be committed in this term
            ),
            inflight = mutableListOf(),
            replState = mutableMapOf(),
            notify = mutableSetOf<Message<Verify, Unit>>(),
            stepDown = Channel(1),
            commitCh = commitCh
        )
    }


    private suspend fun runCandidate() {
        logger.info("entering candidate state: node={}, term={}", localPeer, raftState.getCurrentTerm() + 1)
        val voteCh = electSelf()
        coroutineScope {
            val electionTimer = Channel<Unit>()
            randomTimeout(electionTimer, config.value.electionTimeout)
            // Tally the votes, need a simple majority
            var grantedVotes = 0
            val votesNeeded = quorumSize()
            logger.debug("votes, needed={}", votesNeeded)
            while (isActive && raftState.getState() == CANDIDATE) {
                select<Unit> {
                    rpcCh.onReceive { processRpc(it) }
                    voteCh.onReceive { (peer, vote) ->
                        // Check if the term is greater than ours, bail
                        if (vote.term > raftState.getCurrentTerm()) {
                            logger.debug("newer term discovered, fallback to follower, vote={}", vote)
                            raftState.setState(FOLLOWER)
                            raftState.setCurrentTerm(vote.term)
                            throw CancellationException("new term")
                        }
                        // Check if the vote is granted
                        if (vote.granted) {
                            grantedVotes++
                            logger.debug("vote granted, from={}, term={}, tally={}", peer, vote.term, grantedVotes)
                        }
                        // Check if we've become the leader
                        if (grantedVotes >= votesNeeded) {
                            logger.info("election won, tally={}", grantedVotes)
                            raftState.setState(LEADER)
                            setLeader(localPeer)
                        }
                    }
                    configurationChangeCh.onReceive(::respondNotLeader)
                    applyCh.onReceive(::respondNotLeader)
                    verifyCh.onReceive(::respondNotLeader)
                    userRestoreCh.onReceive(::respondNotLeader)
                    leadershipTransferCh.onReceive(::respondNotLeader)
                    configurationsCh.onReceive { it.respond { configurations } }
                    bootstrapCh.onReceive(::respondCannotBootstrap)
                    electionTimer.onReceive {
                        logger.warn("Election timeout reached, restarting election")
                        throw CancellationException("election timeout")
                    }
                }
            }
        }
        // Make sure the leadership transfer flag is reset after each run. Having this
        // flag will set the field LeadershipTransfer in a RequestVoteRequest to true,
        // which will make other servers vote even though they have a leader already.
        // It is important to reset that flag, because this privilege could be abused
        // otherwise.
        candidateFromLeadershipTransfer = false
    }

    private val localPeer = Peer(localId, localAddr)

    // quorumSize is used to return the quorum size. This must only be called on
    // the main thread.
    // TODO: revisit usage
    private fun quorumSize(): Int {
        val voters = configurations.latest.servers.count { it.suffrage == VOTER }
        return voters / 2 + 1
    }

    private suspend fun electSelf(): ReceiveChannel<VoterResponse> {
        // Create a response channel
        val respCh = Channel<VoterResponse>(configurations.latest.servers.size)
        raftState.incrementCurrentTerm()
        val lastEntry = raftState.getLastEntry()
        val req = Rpc.RequestVoteRequest(
            header = header,
            term = raftState.getCurrentTerm(),
            candidate = Peer(localId, localAddr),
            lastLogTerm = lastEntry.term,
            lastLogIndex = lastEntry.index,
            leadershipTransfer = candidateFromLeadershipTransfer
        )
        supervisorScope {
            for (server in configurations.latest.servers) {
                if (server.suffrage == VOTER) {
                    if (server.serverId == localId) {
                        persistVote(req.term, req.candidate)
                    }
                    respCh.send(
                        VoterResponse(
                            localPeer, Rpc.RequestVoteResponse(
                                header = header,
                                term = req.term,
                                granted = true
                            )
                        )
                    )
                } else {
                    askPeer(server.peer, req, respCh)
                }
            }
        }
        return respCh
    }

    private fun CoroutineScope.askPeer(peer: Peer, req: Rpc.RequestVoteRequest, respCh: SendChannel<VoterResponse>) {
        launch {
            val resp: Rpc.RequestVoteResponse = try {
                transport.requestVote(peer, req)
            } catch (e: Exception) {
                logger.error("failed to make requestVote RPC, target={}", peer, e)
                Rpc.RequestVoteResponse(
                    header = header,
                    term = req.term,
                    granted = false
                )
            }
            respCh.send(VoterResponse(peer, resp))
        }
    }

    private suspend fun runFollower() {
        logger.info("entering follower state: follower={}, leader={}", localPeer, this.leader.value)
        coroutineScope {
            val heartbeatTimer = Channel<Unit>()
            randomTimeout(heartbeatTimer, config.value.heartbeatTimeout)
            while (isActive && raftState.getState() == FOLLOWER) {
                select<Unit> {
                    rpcCh.onReceive { processRpc(it) }
                    configurationChangeCh.onReceive(::respondNotLeader)
                    applyCh.onReceive(::respondNotLeader)
                    verifyCh.onReceive(::respondNotLeader)
                    userRestoreCh.onReceive(::respondNotLeader)
                    leadershipTransferCh.onReceive(::respondNotLeader)
                    configurationsCh.onReceive { it.respond { configurations } }
                    bootstrapCh.onReceive { it.respond { liveBootstrap(it.request) } }
                    heartbeatTimer.onReceive {
                        val hbTimeout = config.value.heartbeatTimeout
                        randomTimeout(heartbeatTimer, hbTimeout)
                        if (clock.now().minus(lastContact.value) >= hbTimeout) {
                            val lastLeader = setLeader(null)
                            if (configurations.latestIndex == 0L) {
                                logger.warn("no known peers, aborting election")
                            } else if (configurations.latestIndex == configurations.committedIndex
                                && !configurations.latest.hasVote(localId)
                            ) {
                                logger.warn("not part of stable configuration, aborting election")
                            } else {
                                logger.warn("heartbeat timeout reached, starting election, last-leader={}", lastLeader)
                                raftState.setState(CANDIDATE)
                                cancel("heartbeat timeout reached, starting election")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setLeader(newLeader: Peer?): Peer? {
        val oldLeader = leader.getAndUpdate {
            newLeader
        }
        if (oldLeader != newLeader) {
            observers.observe(LeaderObservation(newLeader))
        }
        return oldLeader
    }


    private fun liveBootstrap(request: Configuration) {
        TODO("Not yet implemented")
    }

    private suspend fun processRpc(message: Message<Rpc.Request, Rpc.Response>) {
        return message.respond {
            checkRpcHeader(message.request.header)
            when (message.request) {
                is Rpc.AppendEntriesRequest -> appendEntries(message.request)
                is Rpc.InstallSnapshotRequest -> installSnapshot(message.request)
                is Rpc.RequestVoteRequest -> requestVote(message.request)
                is Rpc.TimeoutNowRequest -> timeoutNow(message.request)
            }
        }
    }

    // processHeartbeat is a special handler used just for heartbeat requests
    // so that they can be fast-pathed if a transport supports it. This must only
    // be called from the main thread.
    private suspend fun processHeartbeat(message: Message<Rpc.Request, Rpc.Response>) {
        return message.respond {
            checkRpcHeader(message.request.header)
            when (message.request) {
                is Rpc.AppendEntriesRequest -> appendEntries(message.request)
                else -> throw RaftError.ErrUnexpectedRequest(message.request)
            }
        }
    }


    private suspend fun timeoutNow(request: Rpc.TimeoutNowRequest): Rpc.TimeoutNowResponse {
        TODO("Not yet implemented")
    }

    private suspend fun requestVote(req: Rpc.RequestVoteRequest): Rpc.RequestVoteResponse {
        observers.observe(Observation.RequestVote(req))
        var resp = Rpc.RequestVoteResponse(
            header = req.header,
            term = raftState.getCurrentTerm(),
            granted = false
        )
        // Check if we have an existing leader [who's not the candidate] and also
        // check the LeadershipTransfer flag is set. Usually votes are rejected if
        // there is a known leader. But if the leader initiated a leadership transfer,
        // vote!
        val candidate = req.candidate
        val leader = leader.value
        if (leader != null && leader != candidate && !req.leadershipTransfer) {
            logger.warn("rejecting vote request since we have a leader, from={}, leader={}", candidate, leader)
            return resp
        }

        // Ignore an older term
        if (req.term < raftState.getCurrentTerm()) {
            return resp
        }

        // Increase the term if we see a newer one
        if (req.term > raftState.getCurrentTerm()) {
            // Ensure transition to follower
            logger.debug("lost leadership because received a requestVote with a newer term")
            raftState.setState(FOLLOWER)
            raftState.setCurrentTerm(req.term)
            resp = resp.copy(term = req.term)
        }
        // Check if we have voted yet
        val lastVoteTerm = try {
            stable.get<Long>(Key.LAST_VOTE_TERM)
        } catch (e: Exception) {
            logger.error("failed to get last vote term", e)
            return resp
        }

        val lastVoteCand = try {
            stable.get<Peer>(Key.LAST_VOTE_CAND)
        } catch (e: Exception) {
            logger.error("failed to get last vote candidate", e)
            return resp
        }
        if (lastVoteTerm == req.term && lastVoteCand != null) {
            logger.info("duplicate requestVote for same term", "term", req.term)
            if (lastVoteCand == req.candidate) {
                logger.warn("duplicate requestVote from, candidate={}", candidate)
                resp = resp.copy(granted = true)
            }
            return resp
        }

        val lastPos = raftState.getLastEntry()
        if (lastPos.term > req.lastLogTerm) {
            logger.warn(
                "rejecting vote request since our last term is greater, candidate={}, last-term={}, last-candidate-term={}",
                candidate,
                lastPos.term,
                req.lastLogTerm
            )
            return resp
        }
        if (lastPos.term == req.lastLogTerm && lastPos.index > req.lastLogIndex) {
            logger.warn(
                "rejecting vote request since our last index is greater, candidate={}, last-index={}, last-candidate-term={}",
                candidate,
                lastPos.index,
                req.lastLogTerm
            )
            return resp
        }
        try {
            persistVote(req.term, req.candidate)
        } catch (e: Exception) {
            logger.error("failed to persist vote", e)
            return resp
        }
        setLastContact()
        return resp.copy(granted = true)
    }

    private suspend fun persistVote(term: Long, candidate: Peer) {
        stable.set(Key.LAST_VOTE_TERM, term)
        stable.set(Key.LAST_VOTE_CAND, candidate)
    }

    private fun <U> deserialize(lastVoteCandBytes: ByteArray): U {
        TODO("Not yet implemented")
    }

    private suspend fun installSnapshot(request: Rpc.InstallSnapshotRequest): Rpc.InstallSnapshotResponse {
        TODO("Not yet implemented")
    }

    private suspend fun appendEntries(req: Rpc.AppendEntriesRequest): Rpc.AppendEntriesResponse {
        var resp = Rpc.AppendEntriesResponse(
            header = req.header,
            term = raftState.getCurrentTerm(),
            lastLog = raftState.getLastIndex(),
            success = false,
            noRetryBackoff = false
        )

        // Ignore an older term
        if (req.term < raftState.getCurrentTerm()) {
            return resp
        }
        // Increase the term if we see a newer one, also transition to follower
        // if we ever get an appendEntries call
        if (req.term > raftState.getCurrentTerm() || raftState.getState() != FOLLOWER) {
            raftState.setState(FOLLOWER)
            raftState.setCurrentTerm(req.term)
            resp = resp.copy(term = req.term)
        }

        // Save the current leader
        setLeader(req.leader)

        // Verify the last log entry
        if (req.prevLogEntry > 0) {
            val (lastIdx, lastTerm) = raftState.getLastEntry()
            val prevLogTerm = if (req.prevLogEntry == lastIdx) {
                lastTerm
            } else {
                val (prevLog, exception) = try {
                    Pair(logs.getLog(req.prevLogEntry), null)
                } catch (e: Exception) {
                    Pair(null, e)
                }
                if (prevLog == null || exception != null) {
                    logger.warn(
                        "failed to get previous log, previous-index={}, last-index={}, exception={}",
                        req.prevLogEntry,
                        lastIdx,
                        exception
                    )
                    return resp.copy(noRetryBackoff = true)
                }
                prevLog.position.term
            }
            if (req.prevLogTerm != prevLogTerm) {
                logger.warn("previous log term mis-match, ours={}, remote={}", prevLogTerm, req.prevLogTerm)
                return resp.copy(noRetryBackoff = true)
            }
        }

        // Process any new entries
        if (req.entries.isNotEmpty()) {
            val lastLogIdx = raftState.getLastLog().index
            var newEntries = emptyList<Log>()
            for ((i, entry) in req.entries.withIndex()) {
                if (entry.position.index > lastLogIdx) {
                    newEntries = req.entries.subList(i, req.entries.size)
                    break
                }
                val (storeEntry, exception) = try {
                    Pair(logs.getLog(entry.position.index), null)
                } catch (e: Exception) {
                    Pair(null, e)
                }
                if (storeEntry == null || exception != null) {
                    logger.warn(
                        "failed to get log entry, index={}, exception={}",
                        entry.position.index,
                        exception
                    )
                    return resp
                }
                if (entry.position.term != storeEntry.position.term) {
                    logger.warn("clearing log suffix, from={}, to={}", entry.position.index, lastLogIdx)
                    try {
                        logs.deleteRange(entry.position.index..lastLogIdx)
                    } catch (e: Exception) {
                        logger.error("failed to clear log suffix", e)
                        return resp
                    }
                    if (entry.position.index < configurations.latestIndex) {
                        setLatestConfiguration(configurations.committed, configurations.committedIndex)
                    }
                    newEntries = req.entries.subList(i, req.entries.size)
                    break
                }
            }

            if (newEntries.isNotEmpty()) {
                try {
                    logs.storeLogs(newEntries.asSequence())
                } catch (e: Exception) {
                    logger.error("failed to append to logs", e)
                    return resp
                }
                newEntries.forEach(::processConfigLogEntry)
                val last = newEntries.last()
                raftState.setLastLog(last.position)
            }
        }

        // Update the commit index
        if (req.leaderCommitIndex > 0 && req.leaderCommitIndex > raftState.getCommitIndex()) {
            val idx = minOf(req.leaderCommitIndex, raftState.getLastIndex())
            raftState.setCommitIndex(idx)
            if (configurations.latestIndex <= idx) {
                setCommittedConfiguration(configurations.latest, configurations.latestIndex)
            }
            processLogs(idx, emptyMap())
        }

        setLastContact()
        return Rpc.AppendEntriesResponse(
            header = req.header,
            term = req.term,
            lastLog = raftState.getLastIndex(),
            success = true,
            noRetryBackoff = false
        )
    }

    /**
     * processLogs is used to apply all the committed entries that haven't been applied up to the given index limit.
     * This can be called from both leaders and followers. Followers call this from AppendEntries, for n entries at a
     * time, and always pass [futures]=nil. Leaders call this when entries are committed. They pass the futures from any
     * inflight logs.
     */
    private suspend fun processLogs(index: Long, futures: Map<Long, Message<Log, R?>>) {
        val lastApplied = raftState.getLastApplied()
        if (index <= lastApplied) {
            logger.warn("skipping application of old log: index={}", index)
            return
        }
        // Store maxAppendEntries for this call in case it ever becomes reloadable. We need to use the same value for
        // all lines here to get the expected result.
        val maxAppendEntries = config.value.maxAppendEntries

        val batch = mutableListOf<Message<Log, R?>>()
        for (idx in (lastApplied + 1)..index) {
            val future = futures[idx]
            val preparedLog: Message<Log, R?> = if (future == null) {
                val log = logs.getLog(idx) ?: throw IllegalStateException("failed to get log, index=${idx}")
                Message(log)
            } else {
                if (future.request.data is Log.Data.Nop) {
                    future.response.complete(null)
                    continue
                }
                future
            }
            // If we have a log ready to send to the FSM add it to the batch.
            // The FSM thread will respond to the future.
            batch.add(preparedLog)
            if (batch.size >= maxAppendEntries) {
                // If we have filled up a batch, send it to the FSM
                applyBatch(batch)
                batch.clear()
            }
        }
        // If there are any remaining logs in the batch apply them
        if (batch.size > 0) applyBatch(batch)
        raftState.setLastApplied(index)
    }

    private suspend fun applyBatch(batch: List<Message<Log, R?>>) {
        try {
            fsmMutateCh.send(batch.asSequence())
        } catch (e: CancellationException) {
            batch.forEach { message -> message.response.completeExceptionally(RaftError.ErrRaftShutdown) }
            throw e
        }
    }

    private fun setLastContact() {
        lastContact.update { clock.now() }
    }

    private fun checkRpcHeader(header: RpcHeader) {
        if (header.protocolVersion != ProtocolVersion.VERSION_3) {
            throw RaftError.ErrUnsupportedProtocol
        }
    }

    private fun respondNotLeader(message: Message<*, *>) {
        message.response.completeExceptionally(RaftError.ErrNotLeader)
    }

    private fun respondCannotBootstrap(message: Message<Configuration, Unit>) {
        message.response.completeExceptionally(RaftError.ErrCantBootstrap)
    }

    suspend fun waitShutdown() {
        TODO("Not yet implemented")
    }

    suspend fun closeTransport() {
        if (transport is Closer) {
            transport.close()
        }
    }

}


data class VoterResponse(val peer: Peer, val peerResponse: Rpc.RequestVoteResponse)
data class Verify(
    var quorumSize: Int,
    var votes: Int,
    val voteLock: Mutex = Mutex()
)
