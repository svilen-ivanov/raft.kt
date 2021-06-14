@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt

import dev.svilenivanov.raftkt.Observation.LeaderObservation
import dev.svilenivanov.raftkt.RaftError.*
import dev.svilenivanov.raftkt.ReplicateStateMachine.*
import dev.svilenivanov.raftkt.ReplicateToStateMachine.*
import dev.svilenivanov.raftkt.ServerSuffrage.VOTER
import dev.svilenivanov.raftkt.State.*
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

// This is the current suggested max size of the data in a raft log entry.
// This is based on current architecture, default timing, etc. Clients can
// ignore this value if they want as there is no actual hard checking
// within the library. As the library is enhanced this value may change
// over time to reflect current suggested maximums.
//
// Increasing beyond this risks RPC IO taking too long and preventing
// timely heartbeat signals which are sent in serial in current transports,
// potentially causing leadership instability.
const val SUGGESTED_MAX_DATA_SIZE = 512 * 1024
val MIN_CHECK_INTERVAL = milliseconds(10)

sealed class RaftError(message: String) : Throwable(message) {
    class Unspecified : RaftError {
        constructor(message: String) : super(message)
        constructor(cause: Throwable) : super(cause.toString())
    }

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
    class ErrUnexpectedRequest(request: Rpc.Request) : RaftError("expected heartbeat, got ${request::class}")
}

class Raft<T, R> private constructor(
    config: Config,
    // FSM is the client state machine to apply commands to
    private val fsm: Fsm<T, R>,
    // LogStore provides durable storage for logs
    private val logs: LogStore,
    // stable is a StableStore implementation for durable state
    // It provides stable storage for many fields in raftState
    private val stable: StableStore,
    // snapshots is used to store and retrieve snapshots
    private val snapshots: SnapshotStore,
    // The transport layer we use
    private val transport: Transport,

    private val context: CoroutineContext,
    // clock
    private val clock: Clock = Clock.System
) {
    private val scope = CoroutineScope(context)

    companion object {
        @JvmStatic
        private val logger: Logger = LoggerFactory.getLogger(Raft::class.java)

        suspend fun <T, R> create(
            config: Config,
            fsm: Fsm<T, R>,
            logs: LogStore,
            stable: StableStore,
            snaps: SnapshotStore,
            transport: Transport,
            context: CoroutineContext
        ): Raft<T, R> {
            return Raft(config, fsm, logs, stable, snaps, transport, context).apply { init() }
        }

        /**
         * BootstrapCluster initializes a server's storage with the given cluster
         * configuration. This should only be called at the beginning of time for the
         * cluster with an identical configuration listing all Voter servers. There is
         * no need to bootstrap Nonvoter and Staging servers.
         *
         * A cluster can only be bootstrapped once from a single participating Voter
         * server. Any further attempts to bootstrap will return an error that can be
         * safely ignored.
         *
         * One approach is to bootstrap a single server with a configuration
         * listing just itself as a Voter, then invoke AddVoter() on it to add other
         * servers to the cluster.
         */
        suspend fun bootstrapCluster(
            conf: Config,
            logs: LogStore,
            stable: StableStore,
            snaps: SnapshotStore,
            trans: Transport,
            configuration: Configuration,
            clock: Clock
        ) {
            configuration.check()

            // Make sure the cluster is in a clean state.
            val hasState = hasExistingState(logs, stable, snaps)
            if (hasState) {
                throw ErrCantBootstrap
            }
            stable.set(Key.CURRENT_TERM, 1L)
            // Append configuration entry to log.
            val entry = Log(
                position = Position(1, 1),
                appendedAt = clock.now(),
                data = Log.Data.Configuration(configuration)
            )
            logs.storeLog(entry)
        }

        /**
         *  HasExistingState returns true if the server has any existing state (logs,
         *  knowledge of a current term, or any snapshots).
         */
        private suspend fun hasExistingState(logs: LogStore, stable: StableStore, snaps: SnapshotStore): Boolean {
            val currentTerm = stable.get<Long>(Key.CURRENT_TERM)
            when {
                currentTerm == null -> return false
                currentTerm > 0 -> return true
            }
            val lastIndex = logs.lastIndex()
            when {
                lastIndex == null -> return false
                lastIndex > 0 -> return true
            }
            val snapshots = snaps.list()
            val firstSnap = snapshots.firstOrNull()
            return firstSnap == null
        }
    }

    // protocolVersion is used to inter-operate with Raft servers running
    // different versions of the library. See comments in config.go for more
    // details.
    private lateinit var protocolVersion: ProtocolVersion;

    // applyCh is used to async send logs to the main thread to
    // be committed and applied to the FSM.
    private lateinit var applyCh: Channel<LogFuture<R>>

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
    private lateinit var fsmMutateCh: Channel<List<CommitTuple<R>>>

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
    private lateinit var configurationChangeCh: Channel<ConfigurationChangeFuture<R>>

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
    private val shutdownCh = Channel<Unit>()
    private var shutdown: Boolean = false
    private val shutdownLock = Mutex()

    // userSnapshotCh is used for user-triggered snapshots
    private lateinit var userSnapshotCh: Channel<Any>

    // userRestoreCh is used for user-triggered restores of external snapshots
    private lateinit var userRestoreCh: Channel<UserRestoreFuture>

    // verifyCh is used to async send verify futures to the main thread
    // to verify we are still the leader
    private lateinit var verifyCh: Channel<VerifyFuture>

    // configurationsCh is used to get the configuration data safely from
    // outside of the main thread.
    private lateinit var configurationsCh: Channel<ConfigurationsFuture>

    // bootstrapCh is used to attempt an initial bootstrap from outside of the main thread.
    private lateinit var bootstrapCh: Channel<BootstrapFuture<R>>

    // List of observers and the mutex that protects them. The observers list
    // is indexed by an artificial ID which is used for deregistration.
//    private val observersLock = Mutex()
    private val observers = Observers()

    // leadershipTransferCh is used to start a leadership transfer from outside of
    // the main thread.
    private lateinit var leadershipTransferCh: Channel<LeadershipTransferFuture>

    // NotifyCh is used to provide a channel that will be notified of leadership
    // changes. Raft will block writing to this channel, so it should either be
    // buffered or aggressively consumed.
    private var notifyCh: Channel<Boolean>? = null

    private val raftState = RaftState()

    private lateinit var rpc: Channel<Rpc>
    private lateinit var groupJob: Job

    private val header = RpcHeader(protocolVersion = config.protocolVersion)


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
        rpcCh = transport.consumerCh
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
        while (context.isActive) {
            if (shutdownCh.tryReceive().isSuccess) {
                setLeader(null)
                return
            }
            when (raftState.getState()) {
                FOLLOWER -> runFollower()
                CANDIDATE -> runCandidate()
                LEADER -> runLeader()
                SHUTDOWN -> {
                    setLeader(null)
                    return
                }
            }
        }
    }

    private suspend fun runFollower() {
        var didWarn = false
        logger.info("entering follower state: follower={}, leader={}", localPeer, this.leader.value)

        coroutineScope {
            val heartbeatTimer = Channel<Unit>()
            randomTimeout(heartbeatTimer, config.value.heartbeatTimeout)
            while (isActive && raftState.getState() == FOLLOWER) {
                select<Unit> {
                    rpcCh.onReceive { processRpc(it) }
                    configurationChangeCh.onReceive { respondNotLeader(it) }
                    applyCh.onReceive { respondNotLeader(it) }
                    verifyCh.onReceive { respondNotLeader(it) }
                    userRestoreCh.onReceive { respondNotLeader(it) }
                    leadershipTransferCh.onReceive { respondNotLeader(it) }
                    configurationsCh.onReceive { c ->
                        c.configurations = configurations
                    }
                    bootstrapCh.onReceive { b ->
                        b.respond(
                            try {
                                liveBootstrap(b.configuration)
                                null
                            } catch (e: Exception) {
                                Unspecified(e)
                            }
                        )
                    }
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
                    shutdownCh.onReceive {
                        cancel("shutting down")
                    }
                }
            }
        }
    }

    private suspend fun runCandidate() {
        logger.info("entering candidate state: node={}, term={}", localPeer, raftState.getCurrentTerm() + 1)
        val voteCh = electSelf()
        try {
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
                                cancel("new term")
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
                        configurationChangeCh.onReceive { respondNotLeader(it) }
                        applyCh.onReceive { respondNotLeader(it) }
                        verifyCh.onReceive { respondNotLeader(it) }
                        userRestoreCh.onReceive { respondNotLeader(it) }
                        leadershipTransferCh.onReceive { respondNotLeader(it) }
                        configurationsCh.onReceive { c ->
                            c.configurations = configurations
                        }
                        bootstrapCh.onReceive { respondCannotBootstrap(it) }
                        electionTimer.onReceive {
                            logger.warn("Election timeout reached, restarting election")
                            cancel("election timeout")
                        }
                        shutdownCh.onReceive {
                            cancel("shutting down")
                        }
                    }
                }
            }
        } finally {
            // Make sure the leadership transfer flag is reset after each run. Having this
            // flag will set the field LeadershipTransfer in a RequestVoteRequest to true,
            // which will make other servers vote even though they have a leader already.
            // It is important to reset that flag, because this privilege could be abused
            // otherwise.
            candidateFromLeadershipTransfer = false
        }
    }


    private suspend fun runLeader() {
        logger.info("entering candidate state: node={}", localPeer)
        // Notify that we are the leader
        leaderCh.overrideNotify(true)

        // Store the notify chan. It's not reloadable so shouldn't change before the
        // defer below runs, but this makes sure we always notify the same chan if
        // ever for both gaining and loosing leadership.
        val notify = notifyCh
        if (notify != null) {
            select<Unit> {
                notify.onSend(true) {}
                shutdownCh.onReceive {}
            }
        }

        // setup leader state. This is only supposed to be accessed within the
        // leaderloop.
        setupLeadershipState()
        try {
            // Start a replication routine for each peer
            startStopReplication()

            // Dispatch a no-op log entry first. This gets this leader up to the latest
            // possible commit index, even in the absence of client commands. This used
            // to append a configuration entry instead of a noop. However, that permits
            // an unbounded number of uncommitted configurations in the log. We now
            // maintain that there exists at most one uncommitted configuration entry in
            // any log, so we have to do proper no-ops here.
            dispatchLogs(listOf(LogFuture()), listOf(Log.Data.Noop))
            leaderLoop()
        } finally {
            cleanupRunLeader(notify)
        }
    }

    /**
     *  startStopReplication will set up state and start asynchronous replication to
     *  new peers, and stop replication to removed peers. Before removing a peer,
     *  it'll instruct the replication routines to try to replicate to the current
     *  index. This must only be called from the main thread.
     */
    private suspend fun startStopReplication() {
        val inConfig = mutableMapOf<ServerId, Boolean>()
        val lastIdx = raftState.getLastIndex()

        for (server in configurations.latest.servers) {
            if (server.peer.id == localPeer.id) continue
            inConfig[server.peer.id] = true
            var s = leaderState!!.replState[server.peer.id]
            if (s == null) {
                s = FollowerReplication(
                    serverPeer = server,
                    commitment = leaderState!!.commitment,
                    stopCh = Channel(1),
                    triggerCh = Channel(1),
                    triggerDeferErrorCh = Channel(1),
                    currentTerm = raftState.getCurrentTerm(),
                    nextIndex = lastIdx,
                    lastContact = clock.now(),
                    notify = mutableSetOf(),
                    notifyCh = Channel(1),
                    stepDown = leaderState!!.stepDown,
                    clock = clock
                )
                leaderState!!.replState[server.peer.id] = s
                scope.launch { replicate(s) }
            } else if (s.serverPeer != server) {
                logger.info("updating peer, peer={}", s.serverPeer)
                s.serverPeer = server
            }
        }

        // Stop replication goroutines that need stopping
        for ((serverId, repl) in leaderState!!.replState.entries.toList()) {
            if (inConfig.containsKey(serverId)) continue
            logger.info("removed peer, stopping replication, peer={}, last-index={}", serverId, lastIdx)
            repl.stopCh.send(lastIdx)
            repl.stopCh.close()
            leaderState!!.replState.remove(serverId)
            observers.observe(Observation.PeerObservation(repl.serverPeer, true))
        }

    }

    /**
     * replicate is a long running routine that replicates log entries to a single
     * follower.
     */
    private suspend fun replicate(s: FollowerReplication) {
        // Start an async heartbeating routing
        val stopHeartbeat = Channel<Unit>()
        try {
            scope.launch { heartbeat(s, stopHeartbeat) }

            var sm: ReplicateStateMachine = RPC
            SM@ while (true) when (sm) {
                is RPC -> {
                    var shouldStop = false
                    while (!shouldStop) {
                        val done = select<Boolean> {
                            s.stopCh.onReceive { maxIndex ->
                                if (maxIndex > 0) {
                                    replicateTo(s, maxIndex)
                                }
                                true
                            }
                            s.triggerDeferErrorCh.onReceive { deferErr ->
                                val lastLogIdx = raftState.getLastLog().index
                                shouldStop = replicateTo(s, lastLogIdx)
                                if (!shouldStop) {
                                    deferErr.respond(null)
                                } else {
                                    deferErr.respond(Unspecified("replication failed"))
                                }
                                false
                            }
                            s.triggerCh.onReceive {
                                val lastLogIdx = raftState.getLastLog().index
                                shouldStop = replicateTo(s, lastLogIdx)
                                false
                            }
                            // This is _not_ our heartbeat mechanism but is to ensure
                            // followers quickly learn the leader's commit index when
                            // raft commits stop flowing naturally. The actual heartbeats
                            // can't do this to keep them unblocked by disk IO on the
                            // follower. See https://github.com/hashicorp/raft/issues/282.
                            onTimeout(calcRandomTimeout(config.value.commitTimeout)) {
                                val lastLogIdx = raftState.getLastLog().index
                                shouldStop = replicateTo(s, lastLogIdx)
                                false
                            }
                        }
                        if (done) return
                        // If things looks healthy, switch to pipeline mode
                        if (!shouldStop && s.allowPipeline) {
                            sm = PIPELINE
                            continue@SM
                        }
                    }
                    return
                }
                is PIPELINE -> {
                    // Disable until re-enabled
                    s.allowPipeline = false
                    // Replicates using a pipeline for high performance. This method
                    // is not able to gracefully recover from errors, and so we fall back
                    // to standard mode on failure.
                    try {
                        pipelineReplicate(s)
                    } catch (e: ErrPipelineReplicationNotSupported) {
                        logger.error("failed to start pipeline replication to peer={}", s.serverPeer, e)
                    } catch (e: Exception) {
                        logger.error("unexpected pipelineReplicate exception", e)
                    }
                    sm = RPC
                    continue@SM
                }
            }
        } finally {
            stopHeartbeat.close()
        }
    }


    // pipelineSend is used to send data over a pipeline. It is a helper to
    // pipelineReplicate.
    private suspend fun pipelineSend(
        s: FollowerReplication,
        p: AppendPipeline,
        nextIdx: MutableLong,
        lastIndex: Long
    ): Boolean {
        return try {
            val req = try {
                setupAppendEntries(s, nextIdx.value, lastIndex)
            } catch (e: ErrLogNotFound) {
                return true
            }
            p.appendEntries(req)
            // Increase the next send log to avoid re-sending old logs
            req.entries.lastOrNull()?.run {
                nextIdx.value = position.index + 1
            }
            false
        } catch (e: Exception) {
            logger.error("failed to pipeline appendEntries", e)
            true
        }
    }

    // pipelineDecode is used to decode the responses of pipelined requests.
    private suspend fun pipelineDecode(
        s: FollowerReplication,
        p: AppendPipeline,
        stopCh: Channel<Unit>,
        finishCh: Channel<Unit>
    ) {
        try {
            val respCh = p.consumer
            while (true) {
                val ret = select<Boolean> {
                    respCh.onReceive { ready ->
                        val (req, response) = ready
                        // Check for a newer term, stop running
                        if (response.term > req.term) {
                            handleStaleTerm(s)
                            return@onReceive true
                        }
                        // Update the last contact
                        s.setLastContact()
                        // Abort pipeline if not successful
                        if (!response.success) {
                            return@onReceive true
                        }
                        updateLastAppended(s, req)

                        false
                    }
                    stopCh.onReceive {
                        true
                    }
                }
                if (ret) return
            }
        } finally {
            finishCh.close()
        }
    }

    // setupAppendEntries is used to setup an append entries request.
    private suspend fun setupAppendEntries(
        s: FollowerReplication,
        nextIdx: Long,
        lastIndex: Long
    ): Rpc.AppendEntriesRequest {
        val prevPos = calcPreviousLog(nextIdx)
        return Rpc.AppendEntriesRequest(
            header = header,
            term = s.currentTerm,
            leader = localPeer,
            leaderCommitIndex = raftState.getCommitIndex(),
            prevLogTerm = prevPos.term,
            prevLogEntry = prevPos.index,
            entries = newLogs(nextIdx, lastIndex)
        )
    }

    // setPreviousLog is used to setup the PrevLogEntry and PrevLogTerm for an
    // AppendEntriesRequest given the next index to replicate.
    private suspend fun calcPreviousLog(nextIdx: Long): Position {
        // Guard for the first index, since there is no 0 log entry
        // Guard against the previous index being a snapshot as well
        val lastSnap = raftState.getLastSnapshot()
        if (nextIdx == 1L) {
            return RaftState.ZERO_POSITION
        } else if ((nextIdx - 1) == lastSnap.index) {
            return lastSnap
        } else {
            val getLogIdx = nextIdx - 1
            val log = logs.getLog(getLogIdx)
            checkNotNull(log) { "failed to get log, index=${getLogIdx}" }
            return log.position
        }
    }

    // setNewLogs is used to setup the logs which should be appended for a request.
    private suspend fun newLogs(nextIdx: Long, lastIndex: Long): List<Log> {
        // Append up to MaxAppendEntries or up to the lastIndex. we need to use a
        // consistent value for maxAppendEntries in the lines below in case it ever
        // becomes reloadable.
        val maxAppendEntries = config.value.maxAppendEntries
        val maxIndex = minOf(nextIdx + maxAppendEntries - 1, lastIndex)
        return (nextIdx..maxIndex).map { i -> logs.getLog(i) ?: throw ErrLogNotFound }
    }

    // handleStaleTerm is used when a follower indicates that we have a stale term.
    private suspend fun handleStaleTerm(s: FollowerReplication) {
        logger.error("peer has newer term, stopping replication, peer={}", s.serverPeer.peer)
        s.notifyAll(false) // No longer leader
        s.stepDown.asyncNotifyCh()
    }

    // updateLastAppended is used to update follower replication state after a
    // successful AppendEntries RPC.
    // TODO: This isn't used during InstallSnapshot, but the code there is similar.

    private suspend fun updateLastAppended(s: FollowerReplication, req: Rpc.AppendEntriesRequest) {
        req.entries.lastOrNull()?.run {
            s.nextIndex.value = position.index + 1
            s.commitment.match(s.serverPeer.serverId, position.index)
        }
        // Notify still leader
        s.notifyAll(true)
    }


    // replicateTo is a helper to replicate(), used to replicate the logs up to a
    // given last index.
    // If the follower log is behind, we take care to bring them up to date.
    // Create the base request
    private suspend fun replicateTo(s: FollowerReplication, lastIndex: Long): Boolean {
        var sm: ReplicateToStateMachine = START
        SM@ while (true) when (sm) {
            is START -> {
                if (s.failures > 0) {
                    select<Unit> {
                        onTimeout(backoff(FAILURE_WAIT, s.failures, MAX_FAILURE_SCALE).inWholeMilliseconds) {}
                        shutdownCh.onReceive {}
                    }
                }
                val req = try {
                    setupAppendEntries(s, s.nextIndex.value, lastIndex)
                } catch (e: ErrLogNotFound) {
                    sm = SEND_SNAP
                    continue@SM
                } catch (e: Exception) {
                    return false
                }

                val resp = try {
                    transport.appendEntries(s.serverPeer.peer, req)
                } catch (e: Exception) {
                    logger.error("failed to appendEntries to peer={}", s.serverPeer, e)
                    s.failures++
                    return false
                }
                // Check for a newer term, stop running
                if (resp.term > req.term) {
                    handleStaleTerm(s)
                    return true
                }

                // Update the last contact
                s.setLastContact()

                // Update s based on success
                if (resp.success) {
                    // Update our replication state
                    updateLastAppended(s, req)

                    // Clear any failures, allow pipelining
                    s.failures = 0
                    s.allowPipeline = true
                } else {
                    s.nextIndex.update { maxOf(minOf(it - 1, resp.lastLog + 1), 1) }
                    if (resp.noRetryBackoff) {
                        s.failures = 0
                    } else {
                        s.failures++
                    }
                    logger.warn(
                        "appendEntries rejected, sending older logs, peer={}, next={}",
                        s.serverPeer,
                        s.nextIndex.value
                    )
                }
                sm = CHECK_MORE
                continue@SM
            }
            is CHECK_MORE -> {
                // Poll the stop channel here in case we are looping and have been asked
                // to stop, or have stepped down as leader. Even for the best effort case
                // where we are asked to replicate to a given index and then shutdown,
                // it's better to not loop in here to send lots of entries to a straggler
                // that's leaving the cluster anyways.
                if (s.stopCh.tryReceive().isSuccess) {
                    return true
                }

                if (s.nextIndex.value <= lastIndex) {
                    sm = START
                    continue@SM
                }
                return false
            }
            is SEND_SNAP -> {
                // SEND_SNAP is used when we fail to get a log, usually because the follower
                // is too far behind, and we must ship a snapshot down instead
                val stop = try {
                    sendLatestSnapshot(s)
                } catch (e: Exception) {
                    logger.error("failed to send snapshot to, peer={}", s.serverPeer, e)
                    return false
                }
                if (stop) return true
                sm = CHECK_MORE
                continue@SM
            }
        }
    }

    // sendLatestSnapshot is used to send the latest snapshot we have
    // down to our follower.
    private suspend fun sendLatestSnapshot(s: FollowerReplication): Boolean {
        val snapList = snapshots.list()
        val snapId = snapList.firstOrNull()
        if (snapId == null) {
            logger.warn("no snapshots found")
            return false
        }
        val (meta, snapshot) = snapshots.open(snapId.id)
        return try {
            val req = Rpc.InstallSnapshotRequest(
                header = header,
                snapshotVersion = meta.version,
                term = s.currentTerm,
                leader = localPeer,
                lastLogIndex = meta.position.index,
                lastLogTerm = meta.position.term,
                configuration = meta.configuration,
                configurationIndex = meta.configurationIndex,
                size = meta.size
            )
            val resp = try {
                transport.installSnapshot(s.serverPeer.peer, req, snapshot)
            } catch (e: Exception) {
                logger.error("failed to install snapshot, id={}", snapId, e)
                s.failures++
                return false
            }
            // Check for a newer term, stop running
            if (resp.term > req.term) {
                handleStaleTerm(s)
                return true
            }
            setLastContact()
            // Check for success
            if (resp.success) {
                // Update the indexes
                s.nextIndex.value = meta.position.index + 1
                s.commitment.match(s.serverPeer.serverId, meta.position.index)

                // Clear any failures
                s.failures = 0

                // Notify we are still leader
                s.notifyAll(true)
            } else {
                s.failures++
                logger.warn("installSnapshot rejected to, peer={}", s.serverPeer)
            }

            false
        } finally {
            snapshot.close()
        }
    }

    // heartbeat is used to periodically invoke AppendEntries on a peer
// to ensure they don't time out. This is done async of replicate(),
// since that routine could potentially be blocked on disk IO.
    suspend fun heartbeat(s: FollowerReplication, stopCh: Channel<Unit>) {
        var failures: Long = 0
        val req = Rpc.AppendEntriesRequest(
            header = header,
            term = s.currentTerm,
            leader = localPeer,
            prevLogEntry = 0,
            prevLogTerm = 0,
            entries = emptyList(),
            leaderCommitIndex = 0
        )
        while (true) {
            val ret = select<Boolean> {
                s.notifyCh.onReceive { false }
                onTimeout((config.value.heartbeatTimeout / 10).inWholeMilliseconds) { false }
                stopCh.onReceive { true }
            }
            if (ret) return
            try {
                val resp = transport.appendEntries(s.serverPeer.peer, req)
                setLastContact()
                failures = 0
                s.notifyAll(resp.success)
            } catch (e: Exception) {
                logger.error("failed to heartbeat to, peer={}", s.serverPeer)
                failures++
                select<Unit> {
                    onTimeout(backoff(FAILURE_WAIT, failures, MAX_FAILURE_SCALE).inWholeMilliseconds) {}
                    stopCh.onReceive {}
                }
            }
        }
    }

    private suspend fun pipelineReplicate(s: FollowerReplication) {
        // Create a new pipeline
        var pipeline: AppendPipeline? = null
        try {
            pipeline = transport.appendEntriesPipeline(s.serverPeer.peer)
            // Log start of pipeline
            logger.info("pipelining replication, peer={}", s.serverPeer.peer)

            // Create a shutdown and finish channel
            val stopCh = Channel<Unit>()
            val finishCh = Channel<Unit>()

            // Start a dedicated decoder
            scope.launch { pipelineDecode(s, pipeline, stopCh, finishCh) }

            // Start pipeline sends at the last good nextIndex
            val nextIdx = MutableLong(s.nextIndex.value)

            var shouldStop = false
            SEND@ while (!shouldStop) {
                val done = select<Boolean> {
                    finishCh.onReceive { true }
                    s.stopCh.onReceive { maxIndex ->
                        if (maxIndex > 0) {
                            pipelineSend(s, pipeline, nextIdx, maxIndex)
                        }
                        true
                    }
                    s.triggerDeferErrorCh.onReceive { deferErr ->
                        val lastLogIdx = raftState.getLastLog().index
                        shouldStop = pipelineSend(s, pipeline, nextIdx, lastLogIdx)
                        if (!shouldStop) {
                            deferErr.respond(null)
                        } else {
                            deferErr.respond(Unspecified("replication failed"))
                        }
                        false
                    }
                    s.triggerCh.onReceive {
                        val lastLogIdx = raftState.getLastLog().index
                        shouldStop = pipelineSend(s, pipeline, nextIdx, lastLogIdx)
                        false
                    }
                    onTimeout(calcRandomTimeout(config.value.commitTimeout)) {
                        val lastLogIdx = raftState.getLastLog().index
                        shouldStop = pipelineSend(s, pipeline, nextIdx, lastLogIdx)
                        false
                    }

                }
                if (done) break
            }
            stopCh.close()
            // Stop our decoder, and wait for it to finish
            select<Unit> {
                finishCh.onReceive {}
                shutdownCh.onReceive {}
            }
        } finally {
            pipeline?.close()
            // Log stop of pipeline
            logger.info("aborting pipeline replication, peer={}", s.serverPeer.peer)
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
        val cleanup = mutableListOf<() -> Unit>()

        try {
            coroutineScope {
                val lease = Channel<Unit>().also { fixedTimeout(it, config.value.electionTimeout) }
                while (isActive && raftState.getState() == LEADER) {
                    select<Unit> {
                        rpcCh.onReceive { processRpc(it) }
                        leaderState!!.stepDown.onReceive { raftState.setState(FOLLOWER) }
                        leadershipTransferCh.onReceive { future ->
                            if (leaderState!!.leadershipTransferInProgress.value) {
                                logger.debug("(leadershipTransfer) leadership transfer in progress, aborting")
                                future.respond(ErrLeadershipTransferInProgress)
                                return@onReceive
                            }
                            val newPeer = future.peer
                            logger.debug(
                                "starting leadership transfer, id={}, address={}",
                                newPeer?.id,
                                newPeer?.address
                            )
                            val leftLeaderLoop = Channel<Unit>()
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
                                select<Unit> {
                                    onTimeout(config.value.electionTimeout) {
                                        stopCh.close()
                                        logger.debug("leadership transfer timeout")
                                        future.respond(ErrLeadershipTransferTimeout)
                                        doneCh.send(null)
                                    }
                                    leftLeaderLoop.onReceive {
                                        stopCh.close()
                                        logger.debug("lost leadership during transfer (expected)")
                                        future.respond(null)
                                        doneCh.send(null)
                                    }
                                    doneCh.onReceive {
                                        if (it != null) {
                                            logger.debug("doneCh: {}", it)
                                        }
                                        future.respond(it)
                                    }
                                }
                            }

                            // leaderState.replState is accessed here before
                            // starting leadership transfer asynchronously because
                            // leaderState is only supposed to be accessed in the
                            // leaderloop.

                            val transferPeer = newPeer?.run { pickServer()?.peer }
                            if (transferPeer == null) {
                                logger.error("cannot find peer")
                                doneCh.send(ErrCannotFindPeer)
                                return@onReceive
                            }
                            val state = leaderState!!.replState[transferPeer.id]
                            if (state == null) {
                                logger.error("cannot find replication state for {}", transferPeer.id)
                                doneCh.send(Unspecified("cannot find replication state for $transferPeer.id"))
                                return@onReceive
                            }
                            launch {
                                leadershipTransfer(transferPeer, state, stopCh, doneCh)
                            }
                        }
                        leaderState!!.commitCh.onReceive {
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

                            val groupFutures = LinkedHashMap<Long, LogFuture<R>>()
                            var lastIdxGroup: Long = 0

                            // Pull all inflight logs that are committed off the queue.
                            for (commitLog in leaderState!!.inflight) {
                                val idx = commitLog.log!!.position.index
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
                        verifyCh.onReceive { v: VerifyFuture ->
                            when {
                                v.quorumSize == 0 -> {
                                    // Just dispatched, start the verification
                                    verifyLeader(v)
                                }
                                v.votes < v.quorumSize -> {
                                    // Early return, means there must be a new leader
                                    logger.warn("new leader elected, stepping down")
                                    raftState.setState(FOLLOWER)
                                    leaderState!!.notify.remove(v)
                                    leaderState!!.replState.forEach { (_, repl) ->
                                        repl.cleanupNotify(v)
                                    }
                                    v.respond(ErrNotLeader)
                                }
                                else -> {
                                    // Quorum of members agree, we are still leader
                                    leaderState!!.notify.remove(v)
                                    leaderState!!.replState.forEach { (_, repl) ->
                                        repl.cleanupNotify(v)
                                    }
                                    v.respond(null)
                                }
                            }
                        }
                        userRestoreCh.onReceive { future ->
                            if (leaderState!!.leadershipTransferInProgress.value) {
                                logger.debug("(userRestore) leadership transfer in progress, aborting")
                                future.respond(ErrLeadershipTransferInProgress)
                                return@onReceive
                            }
                            try {
                                restoreUserSnapshot(future.meta, future.reader)
                            } catch (e: Exception) {
                                future.respond(Unspecified(e))
                            }
                        }
                        configurationsCh.onReceive { future ->
                            if (leaderState!!.leadershipTransferInProgress.value) {
                                logger.debug("(configurations) leadership transfer in progress, aborting")
                                future.respond(ErrLeadershipTransferInProgress)
                                return@onReceive
                            }
                            future.configurations = configurations
                            future.respond(null)
                        }
                        configurationChangeChIfStable()?.onReceive { future ->
                            if (leaderState!!.leadershipTransferInProgress.value) {
                                logger.debug("(configurationChangeChIfStable) leadership transfer in progress, aborting")
                                future.respond(ErrLeadershipTransferInProgress)
                                return@onReceive
                            }
                            appendConfigurationEntry(future)
                        }
                        bootstrapCh.onReceive { b -> b.respond(ErrCantBootstrap) }
                        applyCh.onReceive { newLog ->
                            if (leaderState!!.leadershipTransferInProgress.value) {
                                logger.debug("(apply) leadership transfer in progress, aborting")
                                newLog.respond(ErrLeadershipTransferInProgress)
                                return@onReceive
                            }
                            // Group commit, gather all the ready commits
                            val ready = mutableListOf(newLog)
                            for (i in 1..config.value.maxAppendEntries) {
                                val more = applyCh.tryReceive()
                                if (more.isSuccess) {
                                    ready.add(more.getOrThrow())
                                } else {
                                    break
                                }
                            }
                            // Dispatch the logs
                            if (stepDown) {
                                ready.forEach { it.respond(ErrNotLeader) }
                            } else {
                                dispatchLogs(ready, ready.map { it.log!!.data })
                            }
                        }
                        lease.onReceive {
                            // Check if we've exceeded the lease, potentially stepping down
                            val maxDiff = checkLeaderLease()
                            // Next check interval should adjust for the last node we've
                            // contacted, without going negative
                            val checkInterval = maxOf(MIN_CHECK_INTERVAL, config.value.leaderLeaseTimeout - maxDiff)
                            fixedTimeout(lease, checkInterval)
                        }
                        shutdownCh.onReceive { }
                    }
                }
            }
        } finally {
            cleanup.forEach { it() }
        }
    }

    /**
     * checkLeaderLease is used to check if we can contact a quorum of nodes
     * within the last leader lease interval. If not, we need to step down,
     * as we may have lost connectivity. Returns the maximum duration without
     * contact. This must only be called from the main thread.
     */
    private fun checkLeaderLease(): Duration {
        var contacted = 0
        val leaseTimeout = config.value.leaderLeaseTimeout
        var maxDiff: Duration = ZERO
        val now = clock.now()
        for (server in configurations.latest.servers) {
            if (server.suffrage == VOTER) {
                if (server.peer.id == localPeer.id) {
                    contacted++
                    continue
                }
                val f = leaderState!!.replState[server.peer.id]!!
                val diff = now - f.lastContact.value
                if (diff <= leaseTimeout) {
                    contacted++
                    if (diff > maxDiff) {
                        maxDiff = diff
                    }
                } else {
                    // Log at least once at high value, then debug. Otherwise it gets very verbose.
                    if (diff <= leaseTimeout * 3) {
                        logger.warn("failed to contact, server-id={}, time={}", server.serverId, diff)
                    } else {
                        logger.debug("failed to contact, server-id={}, time={}", server.serverId, diff)
                    }
                }
            }
        }
        val quorum = quorumSize()
        if (contacted < quorum) {
            logger.warn("failed to contact quorum of nodes, stepping down")
            raftState.setState(FOLLOWER)
        }
        return maxDiff
    }

    private suspend fun appendConfigurationEntry(future: ConfigurationChangeFuture<R>) {
        val configuration = try {
            configurations.latest.next(configurations.latestIndex, future.req)
        } catch (e: Exception) {
            future.respond(Unspecified(e))
            return
        }
        logger.info(
            "Updating configuration, command={}, server-id={}, server-addr={}, servers={}",
            future.req.command,
            future.req.serverId,
            future.req.serverAddress,
            configuration.servers
        )
        val data = Log.Data.Configuration(configuration)

        dispatchLogs(listOf(future), listOf(data))
        val index = future.log!!.position.index
        configurations = configurations.copy(latest = configuration, latestIndex = index)
        leaderState!!.commitment.setConfiguration(configuration)
        startStopReplication()
    }

    private fun restoreUserSnapshot(meta: SnapshotMeta?, reader: Reader?) {
        TODO("Not yet implemented")
    }

    // verifyLeader must be called from the main thread for safety.
    // Causes the followers to attempt an immediate heartbeat.

    private suspend fun verifyLeader(v: VerifyFuture) {
        // Current leader always votes for self
        v.votes = 1
        // Set the quorum size, hot-path for single node
        v.quorumSize = quorumSize()
        if (v.quorumSize == 1) {
            v.respond(null)
            return
        }
        // Track this request
        v.notifyCh = verifyCh
        leaderState!!.notify.add(v)
        leaderState!!.replState.forEach { (_, repl) ->
            repl.notifyLock.withLock {
                repl.notify.add(v)
            }
            repl.notify
        }


    }

    private suspend fun shutdownFn() = shutdownLock.withLock {
        if (!shutdown) {
            shutdownCh.close()
            shutdown = true
            raftState.setState(SHUTDOWN)
            ShutdownFuture(this)
        } else {
            ShutdownFuture(null)
        }
    }

    private suspend fun leadershipTransfer(
        peer: Peer,
        repl: FollowerReplication,
        stopCh: Channel<Unit>,
        doneCh: Channel<RaftError?>
    ) {

        if (stopCh.tryReceive().isSuccess) {
            doneCh.send(null)
        }

        try {
            // Step 1: set this field which stops this leader from responding to any client requests.
            leaderState!!.leadershipTransferInProgress.value = true


            while (repl.nextIndex.value <= raftState.getLastIndex()) {
                val err = DeferError()
                err.init()
                repl.triggerDeferErrorCh.send(err)
                val ret = select<Boolean> {
                    err.errChan.onReceive { err ->
                        if (err != null) {
                            doneCh.send(err)
                            true
                        } else false
                    }
                    stopCh.onReceive {
                        doneCh.send(null)
                        true
                    }
                }
                if (ret) return
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
                transport.timoutNow(peer, Rpc.TimeoutNowRequest(header))
                doneCh.send(null)
            } catch (e: Exception) {
                doneCh.send(Unspecified(e))
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

    private suspend fun dispatchLogs(applyLogs: List<LogFuture<R>>, logData: List<Log.Data>) {
        check(applyLogs.size == logData.size) {
            "(applyLogs.size) ${applyLogs.size} != logData.size (${logData.size}) "
        }

        val now = clock.now()
        val term = raftState.getCurrentTerm()
        var lastIndex = raftState.getLastIndex()

        val logsList = applyLogs.zip(logData).map { (future, data) ->
            lastIndex++
            Log(Position(term, lastIndex), now, data).also {
                future.log = it
                leaderState!!.inflight.add(future)
            }
        }

        try {
            logs.storeLogs(logsList.asSequence())
        } catch (e: Exception) {
            logger.error("failed to commit logs", e)
            applyLogs.forEach { it.respond(Unspecified("failed to commit logs")) }
            raftState.setState(FOLLOWER)
        }
        leaderState!!.commitment.match(localId, lastIndex)

        // Update the last log since it's on disk now
        raftState.setLastLog(logsList.lastOrNull()?.position ?: Position(term, lastIndex))

        // Notify the replicators of the new log
        leaderState!!.replState.forEach { (_, f) ->
            f.triggerCh.asyncNotifyCh()
        }
    }

    private suspend fun cleanupRunLeader(notify: Channel<Boolean>?) {
        // Since we were the leader previously, we update our
        // last contact time when we step down, so that we are not
        // reporting a last contact time from before we were the
        // leader. Otherwise, to a client it would seem our data
        // is extremely stale.
        setLastContact()

        // Respond to any pending verify requests
        leaderState!!.inflight.forEach { it.respond(ErrLeadershipLost) }

        // Respond to any pending verify requests
        leaderState!!.notify.forEach { it.respond(ErrLeadershipLost) }

        // Clear all the state
        leaderState = null

        // If we are stepping down for some reason, no known leader.
        // We may have stepped down due to an RPC call, which would
        // provide the leader, so we cannot always blank this out.
        leader.update {
            if (it != null && it.address == localAddr) null else it
        }
        leaderCh.overrideNotify(false)

        if (notify != null) {
            select<Unit> {
                notify.onSend(false) {}
                shutdownCh.onReceive {
                    // On shutdown, make a best effort but do not block
                    notify.trySend(false)
                }
            }
        }
    }


    private suspend fun setupLeadershipState() {
        val commitCh = Channel<Unit>(1)
        leaderState = LeaderState(
            commitCh = commitCh,
            commitment = Commitment(
                commitCh,
                configurations.latest,
                raftState.getLastIndex() + 1 // first index that may be committed in this term
            ),
            inflight = mutableListOf(),
            replState = mutableMapOf(),
            notify = mutableSetOf(),
            stepDown = Channel(1),
        )
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
                else -> throw ErrUnexpectedRequest(message.request)
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
    private suspend fun processLogs(index: Long, futures: Map<Long, LogFuture<R>>) {
        val lastApplied = raftState.getLastApplied()
        if (index <= lastApplied) {
            logger.warn("skipping application of old log: index={}", index)
            return
        }
        // Store maxAppendEntries for this call in case it ever becomes reloadable. We need to use the same value for
        // all lines here to get the expected result.
        val maxAppendEntries = config.value.maxAppendEntries
        val batch = mutableListOf<CommitTuple<R>>()

        for (idx in (lastApplied + 1)..index) {
            val future = futures[idx]
            val preparedLog = if (future != null) {
                prepareLog(future.log!!, future)
            } else {
                val log = logs.getLog(idx)
                check(log != null) { "failed to get log index=$idx" }
                prepareLog(log, null)
            }
            when {
                preparedLog != null -> {
                    batch.add(preparedLog)
                    if (batch.size >= maxAppendEntries) {
                        applyBatch(batch)
                        batch.clear()
                    }
                }
                future != null -> future.respond(null) // Invoke the future if given. (Noop)
            }
        }
        // If there are any remaining logs in the batch apply them
        if (batch.size != 0) applyBatch(batch)

        // Update the lastApplied index and term
        raftState.setLastApplied(index)
    }

    private fun prepareLog(log: Log, future: LogFuture<R>?): CommitTuple<R>? {
        return when (log.data) {
            is Log.Data.Noop -> null
            is Log.Data.Barrier, is Log.Data.Command<*>, is Log.Data.Configuration -> {
                CommitTuple(log, future)
            }
        }
    }

    private suspend fun applyBatch(batch: List<CommitTuple<R>>) {
        select<Unit> {
            fsmMutateCh.onSend(batch) {}
            shutdownCh.onReceive {
                batch.forEach { cl -> cl.future?.respond(ErrRaftShutdown) }
            }
        }
    }

    private fun setLastContact() {
        lastContact.update { clock.now() }
    }

    private fun checkRpcHeader(header: RpcHeader) {
        if (header.protocolVersion != ProtocolVersion.VERSION_3) {
            throw ErrUnsupportedProtocol
        }
    }

    private suspend fun respondNotLeader(future: DeferError) {
        future.respond(ErrNotLeader)
    }

    private suspend fun respondCannotBootstrap(future: DeferError) {
        future.respond(ErrCantBootstrap)
    }

    suspend fun waitShutdown() {
        TODO("Not yet implemented")
    }

    suspend fun closeTransport() {
        if (transport is Closer) {
            transport.close()
        }
    }

    // configurationChangeChIfStable returns r.configurationChangeCh if it's safe
    // to process requests from it, or nil otherwise. This must only be called
    // from the main thread.
    //
    // Note that if the conditions here were to change outside of leaderLoop to take
    // this from nil to non-nil, we would need leaderLoop to be kicked.
    fun configurationChangeChIfStable() =
    // Have to wait until:
    // 1. The latest configuration is committed, and
    // 2. This leader has committed some entry (the noop) in this term
        //    https://groups.google.com/forum/#!msg/raft-dev/t4xj6dJTP6E/d2D9LrWRza8J
        if (configurations.latestIndex == configurations.committedIndex &&
            raftState.getCommitIndex() >= leaderState!!.commitment.startIndex
        ) {
            configurationChangeCh
        } else {
            null
        }
}


data class VoterResponse(
    val peer: Peer,
    val peerResponse: Rpc.RequestVoteResponse
)

data class Verify(
    var quorumSize: Int,
    var votes: Int,
    val voteLock: Mutex = Mutex()
)

data class CommitTuple<R>(val log: Log, val future: LogFuture<R>?)

sealed class ReplicateStateMachine {
    object RPC : ReplicateStateMachine()
    object PIPELINE : ReplicateStateMachine()
}

private sealed class ReplicateToStateMachine {
    object START : ReplicateToStateMachine()
    object CHECK_MORE : ReplicateToStateMachine()
    object SEND_SNAP : ReplicateToStateMachine()
}

private class MutableLong(value: Long) {
    private val inner = atomic(value)

    var value: Long
        get() = inner.value
        set(value) {
            inner.value = value
        }
}
