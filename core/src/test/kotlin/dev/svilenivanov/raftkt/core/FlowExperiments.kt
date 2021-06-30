package dev.svilenivanov.raftkt.core

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

internal class FlowExperiments {
    @OptIn(FlowPreview::class)
    @Test
    fun flatMapMerge() = runBlocking {
        val flow1 = flow<String> {
            (1..3).forEach {
                delay(200)
                emit("1: $it")
            }
        }

        val flow2 = flow<String> {
            (6..10).forEach {
                delay(100)
                emit("2: $it")
            }
        }

        flow1.map { flow2 }.flattenConcat().collect { println(it) }
    }

}
