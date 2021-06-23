package dev.svilenivanov.raftkt.core2

import kotlinx.atomicfu.atomic

private object IdGenerator {
    private val id = atomic(0L)

    fun nextId() = id.incrementAndGet()
}

fun randomNode(id: String? = IdGenerator.nextId().toString()): Node {
    return Node(ServerId("ID #$id"), ServerAddress("Address #$id"))
}

@JvmInline
value class StringCommandRequest(val str: String) : CommandRequest

@JvmInline
value class StringCommandResponese(val str: String) : CommandResponse

val header = RpcHeader(ProtocolVersion.VERSION_1)
