package dev.svilenivanov.raftkt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

// Future is used to represent an action that may occur in the future.
private interface Future {
    // Error blocks until the future arrives and then returns the error status
    // of the future. This may be called any number of times - all calls will
    // return the same value, however is not OK to call this method twice
    // concurrently on the same Future instance.
    // Error will only return generic errors related to raft, such
    // as ErrLeadershipLost, or ErrRaftShutdown. Some operations, such as
    // ApplyLog, may also return errors from other methods.
    suspend fun error(): RaftError?
}

// IndexFuture is used for future actions that can result in a raft log entry
// being created.
private interface IndexFuture : Future {
    // Index holds the index of the newly applied log entry.
    // This must not be called until after the Error method has returned.
    fun index(): Long
}

private interface ApplyFuture<T> : IndexFuture {
    // Response returns the FSM response as returned by the FSM.Apply method. This
    // must not be called until after the Error method has returned.
    // Note that if FSM.Apply returns an error, it will be returned by Response,
    // and not by the Error method, so it is always important to check Response
    // for errors from the FSM.
    fun response(): T
}

// ConfigurationFuture is used for GetConfiguration and can return the
// latest configuration in use by Raft.
private interface ConfigurationFuture : IndexFuture {

    // Configuration contains the latest configuration. This must
    // not be called until after the Error method has returned.
    fun configuration(): Configuration
}

// SnapshotFuture is used for waiting on a user-triggered snapshot to complete.
private interface SnapshotFuture : Future {
    // Open is a function you can call to access the underlying snapshot and
    // its metadata. This must not be called until after the Error method
    // has returned.
    fun open(): SnapshotOpenReturn

    data class SnapshotOpenReturn(val snapshotMeta: SnapshotMeta, val reader: Reader, val error: RaftError)
}

class ErrorFuture<T>(private val err: RaftError) : Future, IndexFuture, ApplyFuture<T?> {
    override suspend fun error() = err
    override fun response(): Nothing? = null
    override fun index() = 0L
}

open class DeferError(
    private var err: RaftError? = null,
    private var errCh: Channel<RaftError?>? = null,
    private var responded: Boolean = false,
    private val shutdownCh: Channel<Unit>? = null
) {

    val errChan: Channel<RaftError?>
        get() = errCh!!

    fun init() {
        errCh = Channel(1)
    }

    suspend fun error(): RaftError? {
        if (err != null) return err
        checkNotNull(errCh) { "waiting for response on null channel" }
        select<Unit> {
            errCh?.onReceiveCatching {
                err = it.getOrNull()
            }
            shutdownCh?.onReceiveCatching {
                err = RaftError.ErrRaftShutdown
            }
        }
        return err
    }

    suspend fun respond(err: RaftError?) {
        if (errCh == null || responded) return
        errCh!!.run {
            send(err)
            close()
            responded = true
        }
    }
}

// logFuture is used to apply a log entry and waits until
// the log is considered committed.
open class LogFuture<R> : DeferError(), ApplyFuture<R> {
    var log: Log? = null
    var response: R? = null
    var dispach: Instant? = null

    override fun index() = log!!.position.index
    override fun response(): R = response!!
}

// There are several types of requests that cause a configuration entry to
// be appended to the log. These are encoded here for leaderLoop() to process.
// This is internal to a single server.
class ConfigurationChangeFuture<R>(
    val req: ConfigurationChangeRequest
) : LogFuture<R>()


// bootstrapFuture is used to attempt a live bootstrap of the cluster. See the
// Raft object's BootstrapCluster member function for more details.
class BootstrapFuture<R>(
    // configuration is the proposed bootstrap configuration to apply.
    val configuration: Configuration
) : LogFuture<R>()

class ShutdownFuture<T, R>(val raft: Raft<T, R>? = null) : Future {

    override suspend fun error(): RaftError? {
        if (raft == null) return null
        raft.run {
            waitShutdown()
            closeTransport()
        }
        return null
    }

}

// userSnapshotFuture is used for waiting on a user-triggered snapshot to
// complete.
class UserSnapshotFuture(
    // opener is a function used to open the snapshot. This is filled in
    // once the future returns with no error.
    private var opener: (suspend () -> OpenerResult)? = null
) : DeferError() {
    sealed class OpenerResult(
        private val meta: SnapshotMeta?,
        private val readCloser: ReadCloser?,
        private val error: RaftError?
    ) {
        operator fun component1() = meta
        operator fun component2() = readCloser
        operator fun component3() = error
    }

    object ErrNoSnapshot : RaftError("no snapshot available")
    object NoSnapshot : OpenerResult(null, null, ErrNoSnapshot)

    suspend fun open(): OpenerResult {
        return try {
            opener?.invoke() ?: NoSnapshot
        } finally {
            opener = null
        }
    }
}

// userRestoreFuture is used for waiting on a user-triggered restore of an
// external snapshot to complete.
class UserRestoreFuture(var meta: SnapshotMeta? = null, var reader: Reader? = null) : DeferError()

// reqSnapshotFuture is used for requesting a snapshot start.
// It is only used internally.
class ReqSnapshotFuture(var position: Position?, var snapshot: FsmSnapshot?) : DeferError()

// restoreFuture is used for requesting an FSM to perform a
// snapshot restore. Used internally only.
class RestoreFuture(var id: String?) : DeferError()

// verifyFuture is used to verify the current node is still
// the leader. This is to prevent a stale read.
class VerifyFuture(
    var notifyCh: Channel<VerifyFuture>?,
    var quorumSize: Int = 0,
    var votes: Int = 0,
    private val voteLock: Mutex = Mutex()
) : DeferError() {
    suspend fun vote(leader: Boolean) = voteLock.withLock {
        if (notifyCh != null) {
            if (leader) {
                votes++
                if (votes >= quorumSize) {
                    notifyCh!!.send(this)
                    notifyCh = null
                }
            } else {
                notifyCh!!.send(this)
                notifyCh = null
            }
        }
    }
}

// leadershipTransferFuture is used to track the progress of a leadership
// transfer internally.
class LeadershipTransferFuture(var peer: Peer?) : DeferError(), Future

// configurationsFuture is used to retrieve the current configurations. This is
// used to allow safe access to this information outside of the main thread.
class ConfigurationsFuture(var configurations: Configurations?) : DeferError() {
    // Configuration returns the latest configuration in use by Raft.
    fun configuration() = configurations!!.latest

    // Index returns the index of the latest configuration in use by Raft.
    fun index() = configurations!!.latestIndex
}

class AppendFuture(
    var start: Instant?,
    var args: Rpc.AppendEntriesRequest?,
    var resp: Rpc.AppendEntriesResponse? = null
) : DeferError() {
    fun request() = args!!
    fun response() = resp!!

    operator fun component1() = request()
    operator fun component2() = response()
}
