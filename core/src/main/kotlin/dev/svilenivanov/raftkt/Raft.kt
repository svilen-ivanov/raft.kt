package dev.svilenivanov.raftkt

import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Raft {
    private val log: Logger = LoggerFactory.getLogger(Raft::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        log.debug("Hello world!")
        println("Hello world!")
    }
}
