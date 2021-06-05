package dev.svilenivanov.raftkt

import kotlinx.coroutines.CompletableDeferred

object EmptyMessage

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

open class Message<REQ, RES>(
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




