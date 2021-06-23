package dev.svilenivanov.raftkt.inmem

import dev.svilenivanov.raftkt.*

class InmemSnapshotStore: SnapshotStore {
    override fun create(
        version: SnapshotVersion,
        position: Position,
        configuration: Configuration,
        configurationIndex: Long,
        transport: Transport
    ): SnapshotSink {
        TODO("Not yet implemented")
    }

    override fun list(): Sequence<SnapshotMeta> {
        return emptySequence()
    }

    override fun open(id: String): SnapshotSource {
        TODO("Not yet implemented")
    }
}
