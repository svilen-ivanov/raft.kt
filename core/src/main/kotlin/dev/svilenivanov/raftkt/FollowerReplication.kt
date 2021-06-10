@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")

package dev.svilenivanov.raftkt

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Instant

class FollowerReplication(
    var serverPeer: Server,
    val commitment: Commitment,
    val stopCh: Channel<Long>,
    val triggerCh: Channel<Unit>,
    val triggerDeferErrorCh: Channel<DeferError>,
    val currentTerm: Long,
    nextIndex: Long,
    lastContact: Instant,
    // notify is a map of futures to be resolved upon receipt of an
    // acknowledgement, then cleared from this map.
    val notify: MutableSet<VerifyFuture>,
    // notifyCh is notified to send out a heartbeat, which is used to check that
    // this server is still leader.
    val notifyCh: Channel<Unit>,
    val stepDown: Channel<Unit>
) {
    fun cleanupNotify(v: VerifyFuture) {
        TODO("Not yet implemented")
    }
    // notifyLock protects 'notify'.
    val notifyLock = Mutex()
    val nextIndex = atomic(nextIndex)
    val lastContact = atomic(lastContact)
}
