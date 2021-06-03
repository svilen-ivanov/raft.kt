package dev.svilenivanov.raftkt

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.test.runBlockingTest
import kotlin.test.Test

private fun makeConfiguration(vararg voters: String) = Configuration(
    voters.mapTo(LinkedHashSet()) { voter -> Server(ServerSuffrage.VOTER, voter, ServerAddress(voter + "addr")) }
)

private fun voters(n: Int) = makeConfiguration(*(1..n).map { "s$it" }.toTypedArray())

internal class CommitmentTest {
    // Tests setVoters() keeps matchIndexes where possible.
    @Test
    fun setVoters() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, makeConfiguration("a", "b", "c"), 0)
        c.match("a", 10)
        c.match("b", 20)
        c.match("c", 30)
        c.getCommitIndex() shouldBe 20
        commitCh.assertAndDrain("commit notify")
        // c: 30, d: 0, e: 0
        c.setConfiguration(makeConfiguration("c", "d", "e"))
        c.match("e", 40)
        c.getCommitIndex() shouldBe 30
        commitCh.assertAndDrain("commit notify")
    }

    // Tests match() being called with smaller index than before.
    @Test
    fun matchMax() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, voters(5), 4)
        c.match("s1", 8)
        c.match("s2", 8)
        c.match("s2", 1)
        c.match("s3", 8)

        withClue("calling match with an earlier index should be ignored") { c.getCommitIndex() shouldBe 8 }
    }

    // Tests match() being called with non-voters.
    @Test
    fun matchNonVoting() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, voters(5), 4)

        c.match("s1", 8)
        c.match("s2", 8)
        c.match("s3", 8)

        commitCh.assertAndDrain("commit notify")

        c.match("s90", 10)
        c.match("s91", 10)
        c.match("s92", 10)

        withClue("non-voting servers shouldn't be able to commit") { c.getCommitIndex() shouldBe 8 }
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }
    }

    // Tests recalculate() algorithm.
    @Test
    fun recalculate() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, voters(5), 0)

        c.match("s1", 30)
        c.match("s2", 20)

        withClue("shouldn't commit after two of five servers") {
            c.getCommitIndex() shouldBe 0
        }

        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }

        c.match("s3", 10)
        c.getCommitIndex() shouldBe 10
        commitCh.assertAndDrain("commit notify")

        c.match("s4", 15)
        c.getCommitIndex() shouldBe 15
        commitCh.assertAndDrain("commit notify")

        c.setConfiguration(voters(3))
        // s1: 30, s2: 20, s3: 10
        c.getCommitIndex() shouldBe 20
        commitCh.assertAndDrain("commit notify")

        c.setConfiguration(voters(4))
        // s1: 30, s2: 20, s3: 10, s4: 0
        c.match("s2", 25)
        c.getCommitIndex() shouldBe 20
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }

        c.match("s4", 23)
        c.getCommitIndex() shouldBe 23

        commitCh.assertAndDrain("commit notify")
    }

    // Tests recalculate() respecting startIndex.
    @Test
    fun recalculateStartIndex() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, voters(5), 4)

        c.match("s1", 3)
        c.match("s2", 3)
        c.match("s3", 3)

        withClue("can't commit until startIndex is replicated to a quorum") { c.getCommitIndex() shouldBe 0 }
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }

        c.match("s1", 4)
        c.match("s2", 4)
        c.match("s3", 4)

        withClue("should be able to commit startIndex once replicated to a quorum") { c.getCommitIndex() shouldBe 4 }
        commitCh.assertAndDrain("commit notify")
    }

    // With no voting members in the cluster, the most sane behavior is probably to not mark anything committed.
    @Test
    fun noVoterSanity() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, makeConfiguration(), 4)
        c.match("s1", 10)
        c.setConfiguration(makeConfiguration())
        c.match("s1", 10)
        withClue("no voting servers: shouldn't be able to commit") { c.getCommitIndex() shouldBe 0 }
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }

        // add a voter so we can commit something and then remove it
        c.setConfiguration(voters(1))
        c.match("s1", 10)
        c.getCommitIndex() shouldBe 10
        commitCh.assertAndDrain("commit notify")

        c.setConfiguration(makeConfiguration())
        c.match("s1", 20)
        c.getCommitIndex() shouldBe 10
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }
    }

    // Single voter commits immediately.
    @Test
    fun singleVoter() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, voters(1), 4)

        c.match("s1", 10)
        c.getCommitIndex() shouldBe 10
        commitCh.assertAndDrain("commit notify")
        c.setConfiguration(voters(1))
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }
        c.match("s1", 12)
        c.getCommitIndex() shouldBe 12
        commitCh.assertAndDrain("commit notify")
    }
}

