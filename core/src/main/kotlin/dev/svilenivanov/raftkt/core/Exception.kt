package dev.svilenivanov.raftkt.core

sealed class RaftException: Exception() {
    class StepDown : RaftException()
}

