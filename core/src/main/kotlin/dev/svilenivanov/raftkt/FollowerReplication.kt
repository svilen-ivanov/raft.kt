@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.milliseconds

const val MAX_FAILURE_SCALE = 12L
val FAILURE_WAIT = milliseconds(10)

// ErrLogNotFound indicates a given log entry is not available.
object ErrLogNotFound : RaftError("log not found")

// ErrPipelineReplicationNotSupported can be returned by the transport to
// signal that pipeline replication is not supported in general, and that
// no error message should be produced.
object ErrPipelineReplicationNotSupported : RaftError("pipeline replication not supported")


/**
 * followerReplication is in charge of sending snapshots and log entries from
 *  this leader during this particular term to a remote follower.
 */
class FollowerReplication(
    // currentTerm and nextIndex must be kept at the top of the struct so
    // they're 64 bit aligned which is a requirement for atomic ops on 32 bit
    // platforms.
    val currentTerm: Long,

    // currentTerm is the term of this leader, to be included in AppendEntries
    // requests.
    nextIndex: Long,

    // peer contains the network address and ID of the remote follower.
    var serverPeer: Server,

    // commitment tracks the entries acknowledged by followers so that the
    // leader's commit index can advance. It is updated on successful
    // AppendEntries responses.
    val commitment: Commitment,

    // stopCh is notified/closed when this leader steps down or the follower is
    // removed from the cluster. In the follower removed case, it carries a log
    // index; replication should be attempted with a best effort up through that
    // index, before exiting.
    val stopCh: Channel<Long>,

    // triggerCh is notified every time new entries are appended to the log.
    val triggerCh: Channel<Unit>,

    // triggerDeferErrorCh is used to provide a backchannel. By sending a
    // deferErr, the sender can be notifed when the replication is done.
    val triggerDeferErrorCh: Channel<DeferError>,

    // lastContact is updated to the current time whenever any response is
    // received from the follower (successful or not). This is used to check
    // whether the leader should step down (Raft.checkLeaderLease()).
    lastContact: Instant,

    // failures counts the number of failed RPCs since the last success, which is
    // used to apply backoff.
    var failures: Long = 0,


    // notifyCh is notified to send out a heartbeat, which is used to check that
    // this server is still leader.
    val notifyCh: Channel<Unit>,
    // notify is a map of futures to be resolved upon receipt of an
    // acknowledgement, then cleared from this map.
    var notify: MutableSet<VerifyFuture>,

    // stepDown is used to indicate to the leader that we
    // should step down based on information from a follower.
    val stepDown: Channel<Unit>,

    // allowPipeline is used to determine when to pipeline the AppendEntries RPCs.
    // It is private to this replication goroutine.
    var allowPipeline: Boolean = false,

    private val clock: Clock
) {


    // notifyLock protects 'notify'.
    val notifyLock = Mutex()

    val nextIndex = atomic(nextIndex)
    val lastContact = atomic(lastContact)

    suspend fun notifyAll(leader: Boolean) {
        // Clear the waiting notifies minimizing lock time
        val n = notifyLock.withLock {
            val oldNotify = notify
            notify = mutableSetOf()
            oldNotify
        }
        n.forEach { v -> v.vote(leader) }
    }

    suspend fun cleanupNotify(v: VerifyFuture) = notifyLock.withLock {
        notify.remove(v)
    }

    fun setLastContact() = lastContact.update { clock.now() }

    suspend fun replicate() {

    }

}
