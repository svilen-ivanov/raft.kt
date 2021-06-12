package dev.svilenivanov.raftkt.inmem

import dev.svilenivanov.raftkt.Message
import dev.svilenivanov.raftkt.Rpc
import dev.svilenivanov.raftkt.ServerAddress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

internal class InmemTransportTest {
    @Test
    fun writeTimeout() = runBlockingTest {
        val timeout = seconds(1)
        val t1 = InmemTransport(
            consumerCh = Channel(),
            localAddr = ServerAddress(Random.nextS.randomUUID())
        )
    }
}
