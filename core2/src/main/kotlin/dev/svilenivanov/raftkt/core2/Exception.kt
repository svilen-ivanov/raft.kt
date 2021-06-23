package dev.svilenivanov.raftkt.core2

sealed class RaftException: Exception() {
    class StepDown : RaftException()
}

