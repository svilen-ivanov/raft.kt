package dev.svilenivanov.raftkt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration

interface Reader {
    suspend fun read(b: ByteArray): Int
}

interface Writer {
    suspend fun write(b: ByteArray): Int
}

interface Closer {
    suspend fun close()
}

interface ReadCloser : Reader, Closer
interface WriteCloser : Writer, Closer

data class Message<REQ, RES>(
    val request: REQ,
    val response: CompletableDeferred<RES> = CompletableDeferred()
) {
    suspend fun respond(block: suspend () -> RES) {
        try {
            response.complete(block())
        } catch (t: Throwable) {
            response.completeExceptionally(t)
        }
    }
}

suspend fun overrideNotifyBool(leaderCh: Channel<Boolean>, b: Boolean) {
    if (leaderCh.trySend(b).isSuccess) return
    while (!leaderCh.isEmpty) {
        leaderCh.receive()
    }
    if (!leaderCh.trySend(b).isSuccess) {
        throw IllegalStateException("race: channel was sent concurrently")
    }
}

private fun calcRandomTimeout(timeout: Duration): Duration {
    return Duration.milliseconds(timeout.inWholeMilliseconds + Random.nextLong(timeout.inWholeMilliseconds))
}

// randomTimeout returns a value that is between the minVal and 2x minVal.
fun CoroutineScope.randomTimeout(channel: Channel<Unit>, minVal: Duration) {
    launch {
        delay(calcRandomTimeout(minVal))
        channel.send(Unit)
    }
}

// asyncNotifyCh is used to do an async channel send
// to a single channel without blocking.
fun <E> SendChannel<E>.asyncNotifyCh(value: E) =  trySend(value).isSuccess

// drainNotifyCh empties out a single-item notification channel without
// blocking, and returns whether it received anything.
fun <E> ReceiveChannel<E>.drainNotifyCh() = tryReceive().isSuccess

fun SendChannel<Unit>.asyncNotifyCh() = asyncNotifyCh(Unit)

// overrideNotifyBool is used to notify on a bool channel
// but override existing value if value is present.
// ch must be 1-item buffered channel.
//
// This method does not support multiple concurrent calls.
fun <E> Channel<E>.overrideNotify(value: E) {
    val result = trySend(value)
    when {
        result.isSuccess || result.isClosed -> return
        result.isFailure -> {
            drainNotifyCh()
            if (!asyncNotifyCh(value)) {
                throw IllegalStateException("race: channel was sent concurrently")
            }
        }
    }
}






