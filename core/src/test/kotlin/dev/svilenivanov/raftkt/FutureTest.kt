package dev.svilenivanov.raftkt

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Test

internal class FutureTest {
    @Test
    fun futureSuccess() = runBlockingTest {
        val f = DeferError()
        f.init()
        f.respond(null)
        f.error() shouldBe null
        f.error() shouldBe null
    }

    @Test
    fun futureError() = runBlockingTest {
        val want = RaftError.Unspecified("x")
        val f = DeferError()
        f.init()
        f.respond(want)
        f.error() shouldBe want
        f.error() shouldBe want
    }

    @Test
    fun futureConcurrent() = runBlockingTest {
        val want = RaftError.Unspecified("x")
        val f = DeferError()
        f.init()
        launch { f.error() shouldBe want }
        launch { f.respond(want) }
    }
}
