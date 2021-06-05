@file:Suppress("UNUSED_PARAMETER")

package dev.svilenivanov.raftkt

object Key {
    @JvmStatic
    val CURRENT_TERM = "CurrentTerm".toByteArray()

    @JvmStatic
    val LAST_VOTE_TERM = "LastVoteTerm".toByteArray()

    @JvmStatic
    val LAST_VOTE_CAND = "LastVoteCand".toByteArray()
}

class LeaderState
