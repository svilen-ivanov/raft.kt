package dev.svilenivanov.raftkt

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import org.slf4j.Logger
import org.slf4j.LoggerFactory

suspend fun <E> Channel<E>.assertAndDrain(msg: String) {
    withClue("$msg (expected but found empty channel)") { isEmpty shouldBe false }
    while (!isEmpty) {
        receive()
    }
    withClue("INTERNAL TEST ERROR: expected to drain the channel") { isEmpty shouldBe true }
}

