package dev.svilenivanov.raftkt.core2

import kotlinx.atomicfu.atomic
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private object IdGenerator {
    private val id = atomic(0L)

    fun nextId() = id.incrementAndGet()
}

fun randomNode(id: String? = IdGenerator.nextId().toString()): Node {
    return Node(ServerId("<id $id>"), ServerAddress("<address $id>"))
}

@JvmInline
value class StringCommandRequest(val str: String) : CommandRequest

@JvmInline
value class StringCommandResponse(val str: String) : CommandResponse

val header = RpcHeader(ProtocolVersion.VERSION_1)

class EchoFsm : Fsm {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(EchoFsm::class.java)
    }

    override suspend fun apply(command: CommandRequest): CommandResponse {
        command as StringCommandRequest
        logger.debug("Echo: {}", command.str)
        return StringCommandResponse(command.str)
    }
}

class TestClock(private var now: Instant = Clock.System.now()) : Clock {
    override fun now() = now

    fun tick() {
        now = now.plus(seconds(1))
    }

}
