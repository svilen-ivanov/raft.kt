@file:Suppress("UNUSED_PARAMETER")

package dev.svilenivanov.raftkt

import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel

object Key {
    @JvmStatic
    val CURRENT_TERM = "CurrentTerm".toByteArray()

    @JvmStatic
    val LAST_VOTE_TERM = "LastVoteTerm".toByteArray()

    @JvmStatic
    val LAST_VOTE_CAND = "LastVoteCand".toByteArray()
}

class LeaderState<R>(
    val leadershipTransferInProgress: AtomicBoolean = atomic(false),
    val commitCh: Channel<Unit>,
    val commitment: Commitment,
    val inflight: MutableList<LogFuture<R>>,
    val replState: MutableMap<ServerId, FollowerReplication>,
    val notify: MutableSet<VerifyFuture>,
    val stepDown: Channel<Unit>,
)
