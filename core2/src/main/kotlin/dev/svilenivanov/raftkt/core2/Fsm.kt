package dev.svilenivanov.raftkt.core2

interface Fsm {
    fun apply(command: CommandRequest): CommandResponse
}
