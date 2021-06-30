package dev.svilenivanov.raftkt.core

interface Fsm {
    suspend fun apply(command: CommandRequest): CommandResponse
}
