package dev.svilenivanov.raftkt.inmem

import dev.svilenivanov.raftkt.Log
import dev.svilenivanov.raftkt.LogType.LOG_COMMAND
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.datetime.Clock
import kotlin.test.Test

internal class InmemLogStoreTest {

    @Test
    fun init() = runBlockingTest {
        val store = InmemLogStore<String, String>()
        store.firstIndex() shouldBe null
        store.lastIndex() shouldBe null
        store.deleteRange(1L..10L)
        store.firstIndex() shouldBe null
        store.lastIndex() shouldBe null
        store.getLog(1L) shouldBe null
    }

    @Test
    fun store() = runBlockingTest {
        val store = InmemLogStore<String, String>()
        val log1 = Log(1, 1, LOG_COMMAND, "data1", "ext1", Clock.System.now())
        store.storeLog(log1)
        store.firstIndex() shouldBe 1
        store.lastIndex() shouldBe 1
        val log2 = Log(2, 1, LOG_COMMAND, "data2", "ext2", Clock.System.now())
        store.storeLog(log2)
        store.firstIndex() shouldBe 1
        store.lastIndex() shouldBe 2
        val log3 = Log(3, 1, LOG_COMMAND, "data3", "ext3", Clock.System.now())
        store.storeLog(log3)
        store.firstIndex() shouldBe 1
        store.lastIndex() shouldBe 3
        store.deleteRange(1L..2L)
        store.firstIndex() shouldBe 3
        store.lastIndex() shouldBe 3
        store.getLog(1) shouldBe null
        store.getLog(2) shouldBe null
        store.getLog(3) shouldBe log3
        store.deleteRange(0L..3L)
        store.firstIndex() shouldBe null
        store.lastIndex() shouldBe null
        store.getLog(3) shouldBe null
    }
}
