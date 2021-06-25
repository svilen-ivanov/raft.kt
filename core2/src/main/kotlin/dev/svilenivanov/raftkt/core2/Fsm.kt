package dev.svilenivanov.raftkt.core2

interface Fsm {
    suspend fun apply(command: CommandRequest): CommandResponse
}
