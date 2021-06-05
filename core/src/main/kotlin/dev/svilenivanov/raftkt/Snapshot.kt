package dev.svilenivanov.raftkt

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

// SnapshotStore interface is used to allow for flexible implementations
// of snapshot storage and retrieval. For example, a client could implement
// a shared state store such as S3, allowing new nodes to restore snapshots
// without streaming from the leader.
interface SnapshotStore {
    // Create is used to begin a snapshot at a given index and term, and with
    // the given committed configuration. The version parameter controls
    // which snapshot version to create.
    fun create(
        version: SnapshotVersion,
        position: Position,
        configuration: Configuration,
        configurationIndex: Long,
        transport: Transport
    ): SnapshotSink

    // List is used to list the available snapshots in the store.
    // It should return then in descending order, with the highest index first.
    fun list(): Sequence<SnapshotMeta>

    // Open takes a snapshot ID and provides a ReadCloser. Once close is
    // called it is assumed the snapshot is no longer needed.
    fun open(id: String): SnapshotSource
}


data class SnapshotMeta(
    // Version is the version number of the snapshot metadata. This does not cover
    // the application's data in the snapshot, that should be versioned
    // separately.
    val version: SnapshotVersion,
    // ID is opaque to the store, and is used for opening.
    val id: String,
    // Index and Term store when the snapshot was taken.
    val position: Position,
    // Configuration and ConfigurationIndex are present in version 1
    // snapshots and later.
    val configuration: Configuration,
    val configurationIndex: Long,
    // Size is the size of the snapshot in bytes.
    val size: Long,
)

interface SnapshotSink

data class SnapshotSource(
    val meta: SnapshotMeta,
    val reader: ReadCloser
)
