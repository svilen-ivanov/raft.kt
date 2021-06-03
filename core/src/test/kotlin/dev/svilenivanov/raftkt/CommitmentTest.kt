package dev.svilenivanov.raftkt

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.test.runBlockingTest
import kotlin.test.Test

private fun makeConfiguration(voters: Iterable<ServerId> = emptyList()) = Configuration(
    voters.mapTo(LinkedHashSet()) { voter -> Server(ServerSuffrage.VOTER, voter, ServerAddress("${voter}addr")) }
)

private fun voters(n: Int) = makeConfiguration(*(1..n).map { ServerId("s$it") })

val SERVER_1 = ServerId("s1")
val SERVER_2 = ServerId("s2")
val SERVER_3 = ServerId("s3")
val SERVER_4 = ServerId("s4")
val SERVER_A = ServerId("a")
val SERVER_B = ServerId("b")
val SERVER_C = ServerId("c")
val SERVER_D = ServerId("d")
val SERVER_E = ServerId("e")

internal class CommitmentTest {
    // Tests setVoters() keeps matchIndexes where possible.
    @Test
    fun setVoters() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, makeConfiguration(listOf(SERVER_A, SERVER_B, SERVER_C)), 0)
        c.match(SERVER_A, 10)
        c.match(SERVER_B, 20)
        c.match(SERVER_C, 30)
        c.getCommitIndex() shouldBe 20
        commitCh.assertAndDrain("commit notify")
        // c: 30, d: 0, e: 0
        c.setConfiguration(makeConfiguration(listOf(SERVER_C, SERVER_D, SERVER_E)))
        c.match(SERVER_E, 40)
        c.getCommitIndex() shouldBe 30
        commitCh.assertAndDrain("commit notify")
    }

    // Tests match() being called with smaller index than before.
    @Test
    fun matchMax() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, voters(5), 4)
        c.match(SERVER_1, 8)
        c.match(SERVER_2, 8)
        c.match(SERVER_2, 1)
        c.match(SERVER_3, 8)

        withClue("calling match with an earlier index should be ignored") { c.getCommitIndex() shouldBe 8 }
    }

    // Tests match() being called with non-voters.
    @Test
    fun matchNonVoting() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, voters(5), 4)

        c.match(SERVER_1, 8)
        c.match(SERVER_2, 8)
        c.match(SERVER_3, 8)

        commitCh.assertAndDrain("commit notify")

        c.match(SERVER_A, 10)
        c.match(SERVER_B, 10)
        c.match(SERVER_C, 10)

        withClue("non-voting servers shouldn't be able to commit") { c.getCommitIndex() shouldBe 8 }
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }
    }

    // Tests recalculate() algorithm.
    @Test
    fun recalculate() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, voters(5), 0)

        c.match(SERVER_1, 30)
        c.match(SERVER_2, 20)

        withClue("shouldn't commit after two of five servers") {
            c.getCommitIndex() shouldBe 0
        }

        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }

        c.match(SERVER_3, 10)
        c.getCommitIndex() shouldBe 10
        commitCh.assertAndDrain("commit notify")

        c.match(SERVER_4, 15)
        c.getCommitIndex() shouldBe 15
        commitCh.assertAndDrain("commit notify")

        c.setConfiguration(voters(3))
        // s1: 30, s2: 20, s3: 10
        c.getCommitIndex() shouldBe 20
        commitCh.assertAndDrain("commit notify")

        c.setConfiguration(voters(4))
        // s1: 30, s2: 20, s3: 10, s4: 0
        c.match(SERVER_2, 25)
        c.getCommitIndex() shouldBe 20
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }

        c.match(SERVER_4, 23)
        c.getCommitIndex() shouldBe 23

        commitCh.assertAndDrain("commit notify")
    }

    // Tests recalculate() respecting startIndex.
    @Test
    fun recalculateStartIndex() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, voters(5), 4)

        c.match(SERVER_1, 3)
        c.match(SERVER_2, 3)
        c.match(SERVER_3, 3)

        withClue("can't commit until startIndex is replicated to a quorum") { c.getCommitIndex() shouldBe 0 }
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }

        c.match(SERVER_1, 4)
        c.match(SERVER_2, 4)
        c.match(SERVER_3, 4)

        withClue("should be able to commit startIndex once replicated to a quorum") { c.getCommitIndex() shouldBe 4 }
        commitCh.assertAndDrain("commit notify")
    }

    // With no voting members in the cluster, the most sane behavior is probably to not mark anything committed.
    @Test
    fun noVoterSanity() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, makeConfiguration(), 4)
        c.match(SERVER_1, 10)
        c.setConfiguration(makeConfiguration())
        c.match(SERVER_1, 10)
        withClue("no voting servers: shouldn't be able to commit") { c.getCommitIndex() shouldBe 0 }
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }

        // add a voter so we can commit something and then remove it
        c.setConfiguration(voters(1))
        c.match(SERVER_1, 10)
        c.getCommitIndex() shouldBe 10
        commitCh.assertAndDrain("commit notify")

        c.setConfiguration(makeConfiguration())
        c.match(SERVER_1, 20)
        c.getCommitIndex() shouldBe 10
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }
    }

    // Single voter commits immediately.
    @Test
    fun singleVoter() = runBlockingTest {
        val commitCh = Channel<EmptyMessage>(1)
        val c = Commitment(commitCh, voters(1), 4)

        c.match(SERVER_1, 10)
        c.getCommitIndex() shouldBe 10
        commitCh.assertAndDrain("commit notify")
        c.setConfiguration(voters(1))
        withClue("unexpected commit notify") { commitCh.isEmpty shouldBe true }
        c.match(SERVER_1, 12)
        c.getCommitIndex() shouldBe 12
        commitCh.assertAndDrain("commit notify")
    }
}

