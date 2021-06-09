package dev.svilenivanov.raftkt

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel

class FollowerReplication {
    val triggerDeferErrorCh: Channel<Message<Unit, Unit>> = Channel()
    val nextIndex = atomic(0L)
}
