@file:Suppress("UNUSED_PARAMETER")

package dev.svilenivanov.raftkt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlin.coroutines.coroutineContext

// FSM provides an interface that can be implemented by
// clients to make use of the replicated log.
interface Fsm<T, R> {
    // Apply log is invoked once a log entry is committed.
    // It returns a value which will be made available in the
    // ApplyFuture returned by Raft.Apply method if that
    // method was called on the same Raft node as the FSM.
    suspend fun apply(log: Log): R

    // Snapshot is used to support log compaction. This call should
    // return an FSMSnapshot which can be used to save a point-in-time
    // snapshot of the FSM. Apply and Snapshot are not called in multiple
    // threads, but Apply will be called concurrently with Persist. This means
    // the FSM should be implemented in a fashion that allows for concurrent
    // updates while a snapshot is happening.
    suspend fun snapshot(): FsmSnapshot

    // Restore is used to restore an FSM from a snapshot. It is not called
    // concurrently with any other command. The FSM must discard all previous
    // state.
    suspend fun restore(snapshot: SnapshotSource)
}

// BatchingFSM extends the FSM interface to add an ApplyBatch function. This can
// optionally be implemented by clients to enable multiple logs to be applied to
// the FSM in batches. Up to MaxAppendEntries could be sent in a batch.
interface BatchingFSM<T, R> : Fsm<T, R> {
    // ApplyBatch is invoked once a batch of log entries has been committed and
    // are ready to be applied to the FSM. ApplyBatch will take in an array of
    // log entries. These log entries will be in the order they were committed,
    // will not have gaps, and could be of a few log types. Clients should check
    // the log type prior to attempting to decode the data attached. Presently
    // the LogCommand and LogConfiguration types will be sent.
    //
    // The returned slice must be the same length as the input and each response
    // should correlate to the log at the same index of the input. The returned
    // values will be made available in the ApplyFuture returned by Raft.Apply
    // method if that method was called on the same Raft node as the FSM.
    suspend fun applyBatch(logs: Sequence<Log>): Iterable<R>
}

// FSMSnapshot is returned by an FSM in response to a Snapshot
// It must be safe to invoke FSMSnapshot methods with concurrent
// calls to Apply.
interface FsmSnapshot {
    // Persist should dump all necessary state to the WriteCloser 'sink',
    // and call sink.Close() when finished or call sink.Cancel() on error.
    suspend fun persist(sink: SnapshotSink)

    // Release is invoked when we are finished with the snapshot.
    suspend fun release()
}

class FsmRunner<T, R>(
    // FSM is the client state machine to apply commands to
    private val fsm: Fsm<T, R>,

    // fsmMutateCh is used to send state-changing updates to the FSM. This
    // receives pointers to commitTuple structures when applying logs or
    // pointers to restoreFuture structures when restoring a snapshot. We
    // need control over the order of these operations when doing user
    // restores so that we finish applying any old log applies before we
    // take a user snapshot on the leader, otherwise we might restore the
    // snapshot and apply old logs to it that were in the pipe.
    private val fsmMutateCh: ReceiveChannel<CompletableDeferred<Log>>,
    // fsmSnapshotCh is used to trigger a new snapshot being taken
    private val fsmSnapshotCh: ReceiveChannel<Any>,
) {
    suspend fun run() {
        while (coroutineContext.isActive) {
            select<Unit> {
                fsmMutateCh.onReceive { commitSingle(it) }
                fsmSnapshotCh.onReceive {

                }
            }
        }
    }

    private suspend fun commitSingle(req: CompletableDeferred<Log>) {
        TODO("Not yet implemented")
    }

}

