package dev.svilenivanov.raftkt

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class UtilTest {
    @Test
    fun testRandomTimeout() = runBlockingTest {
        val channel = Channel<Unit>(1)
        randomTimeout(channel, seconds(1))
        advanceTimeBy(milliseconds(950).inWholeMilliseconds)
        withClue("fired early") { channel.isEmpty shouldBe true }
        advanceTimeBy(seconds(2).inWholeMilliseconds)
        withClue("timeout") { channel.isEmpty shouldBe false }
    }

    @Test
    fun testOverrideNotify() = runBlockingTest {
        val ch = Channel<Boolean>(1)
        // sanity check - buffered channel don't have any values
        ch.isEmpty shouldBe true
        // simple case of a single push
        ch.overrideNotify(false)
        ch.tryReceive().getOrThrow() shouldBe false

        // assert that function never blocks and only last item is received
        repeat(4) {
            ch.overrideNotify(false)
        }
        ch.overrideNotify(true)
        ch.tryReceive().getOrThrow() shouldBe true

        ch.tryReceive().isSuccess shouldBe false
    }

    @Test
    fun testBackoff() {
        backoff(milliseconds(10), 1, 8) shouldBe milliseconds(10)
        backoff(milliseconds(20), 2, 8) shouldBe milliseconds(20)
        backoff(milliseconds(10), 8, 8) shouldBe milliseconds(640)
        backoff(milliseconds(10), 9, 8) shouldBe milliseconds(640)
    }


}
