package dev.svilenivanov.raftkt

import dev.svilenivanov.raftkt.ConfigurationChangeCommand.*
import dev.svilenivanov.raftkt.ServerSuffrage.*
import io.kotest.assertions.forEachAsClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowMessage
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test


internal class ConfigurationTest {

    private val sampleConfiguration = Configuration(
        setOf(
            Server(NON_VOTER, ServerId("id0"), ServerAddress(("addr0"))),
            Server(VOTER, ServerId("id1"), ServerAddress(("addr1"))),
            Server(STAGING, ServerId("id2"), ServerAddress(("addr2"))),
        )
    )
    private val singleServer = Configuration(setOf(Server(VOTER, ServerId("id1"), ServerAddress(("addr1x")))))
    private val oneOfEach = Configuration(
        setOf(
            Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
            Server(STAGING, ServerId("id2"), ServerAddress(("addr2x"))),
            Server(NON_VOTER, ServerId("id3"), ServerAddress(("addr3x"))),
        )
    )
    private val voterPair = Configuration(
        setOf(
            Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
            Server(VOTER, ServerId("id2"), ServerAddress(("addr2x"))),
        )
    )

    private data class NextConfigurationTest(
        val testName: String,
        val current: Configuration,
        val command: ConfigurationChangeCommand,
        val serverId: Int,
        val next: Configuration
    )

    private val emptyConfiguration = Configuration(emptySet())
    private val nextConfigurationTests = listOf(
        // 1
        NextConfigurationTest(
            "ADD_STAGING: was missing.",
            emptyConfiguration,
            ADD_STAGING,
            1,
            Configuration(setOf(Server(VOTER, ServerId("id1"), ServerAddress(("addr1")))))
        ),
        // 2
        NextConfigurationTest(
            "ADD_STAGING: Add another",
            singleServer,
            ADD_STAGING,
            2,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(VOTER, ServerId("id2"), ServerAddress(("addr2")))
                )
            )
        ),
        // 3
        NextConfigurationTest(
            "ADD_STAGING: was Voter.",
            singleServer,
            ADD_STAGING,
            1,
            Configuration(setOf(Server(VOTER, ServerId("id1"), ServerAddress(("addr1")))))
        ),
        // 4
        NextConfigurationTest(
            "ADD_STAGING: was Staging.",
            oneOfEach,
            ADD_STAGING,
            2,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(VOTER, ServerId("id2"), ServerAddress(("addr2"))),
                    Server(NON_VOTER, ServerId("id3"), ServerAddress(("addr3x")))
                )
            )
        ),
        // 5
        NextConfigurationTest(
            "ADD_STAGING: was Nonvoter.",
            oneOfEach,
            ADD_STAGING,
            3,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(STAGING, ServerId("id2"), ServerAddress(("addr2x"))),
                    Server(VOTER, ServerId("id3"), ServerAddress(("addr3")))
                )
            )
        ),
        // 6
        NextConfigurationTest(
            "ADD_NONVOTER: was missing.",
            singleServer,
            ADD_NONVOTER,
            2,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(NON_VOTER, ServerId("id2"), ServerAddress(("addr2")))
                )
            )
        ),
        // 7
        NextConfigurationTest(
            "ADD_NONVOTER: was Voter.",
            singleServer,
            ADD_NONVOTER,
            1,
            Configuration(setOf(Server(VOTER, ServerId("id1"), ServerAddress(("addr1")))))
        ),
        // 8
        NextConfigurationTest(
            "ADD_NONVOTER: was Staging.",
            oneOfEach,
            ADD_NONVOTER,
            2,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(STAGING, ServerId("id2"), ServerAddress(("addr2"))),
                    Server(NON_VOTER, ServerId("id3"), ServerAddress(("addr3x")))
                )
            )
        ),
        // 9
        NextConfigurationTest(
            "ADD_NONVOTER: was Nonvoter.",
            oneOfEach,
            ADD_NONVOTER,
            3,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(STAGING, ServerId("id2"), ServerAddress(("addr2x"))),
                    Server(NON_VOTER, ServerId("id3"), ServerAddress(("addr3")))
                )
            )
        ),
        // 10
        NextConfigurationTest(
            "DemoteVoter: was missing.",
            singleServer,
            DEMOTE_VOTER,
            2,
            Configuration(setOf(Server(VOTER, ServerId("id1"), ServerAddress(("addr1x")))))
        ),
        // 11
        NextConfigurationTest(
            "DEMOTE_VOTER: was Voter.",
            voterPair,
            DEMOTE_VOTER,
            2,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(NON_VOTER, ServerId("id2"), ServerAddress(("addr2x")))
                )
            )
        ),
        // 12
        NextConfigurationTest(
            "DEMOTE_VOTER: was Staging.",
            oneOfEach,
            DEMOTE_VOTER,
            2,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(NON_VOTER, ServerId("id2"), ServerAddress(("addr2x"))),
                    Server(NON_VOTER, ServerId("id3"), ServerAddress(("addr3x")))
                )
            )
        ),
        // 13
        NextConfigurationTest(
            "DEMOTE_VOTER: was Nonvoter.",
            oneOfEach,
            DEMOTE_VOTER,
            3,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(STAGING, ServerId("id2"), ServerAddress(("addr2x"))),
                    Server(NON_VOTER, ServerId("id3"), ServerAddress(("addr3x")))
                )
            )
        ),
        // 14
        NextConfigurationTest(
            "RemoveServer: was missing.",
            singleServer,
            REMOVE_SERVER,
            2,
            Configuration(setOf(Server(VOTER, ServerId("id1"), ServerAddress(("addr1x")))))
        ),
        // 15
        NextConfigurationTest(
            "REMOVE_SERVER: was Voter.",
            voterPair,
            REMOVE_SERVER,
            2,
            Configuration(setOf(Server(VOTER, ServerId("id1"), ServerAddress(("addr1x")))))
        ),
        // 16
        NextConfigurationTest(
            "REMOVE_SERVER: was Staging.",
            oneOfEach,
            REMOVE_SERVER,
            2,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(NON_VOTER, ServerId("id3"), ServerAddress(("addr3x")))
                )
            )
        ),
        // 17
        NextConfigurationTest(
            "REMOVE_SERVER: was Nonvoter.",
            oneOfEach,
            REMOVE_SERVER,
            3,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(STAGING, ServerId("id2"), ServerAddress(("addr2x")))
                )
            )
        ),
        // 18
        NextConfigurationTest(
            "Promote: was missing.",
            singleServer,
            PROMOTE,
            2,
            Configuration(setOf(Server(VOTER, ServerId("id1"), ServerAddress(("addr1x")))))
        ),
        // 19
        NextConfigurationTest(
            "PROMOTE: was Voter.",
            singleServer,
            PROMOTE,
            1,
            Configuration(setOf(Server(VOTER, ServerId("id1"), ServerAddress(("addr1x")))))
        ),
        // 20
        NextConfigurationTest(
            "PROMOTE: was Staging.",
            oneOfEach,
            PROMOTE,
            2,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(VOTER, ServerId("id2"), ServerAddress(("addr2x"))),
                    Server(NON_VOTER, ServerId("id3"), ServerAddress(("addr3x")))
                )
            ),
        ),
        // 21
        NextConfigurationTest(
            "PROMOTE: was Nonvoter.",
            oneOfEach,
            PROMOTE,
            3,
            Configuration(
                setOf(
                    Server(VOTER, ServerId("id1"), ServerAddress(("addr1x"))),
                    Server(STAGING, ServerId("id2"), ServerAddress(("addr2x"))),
                    Server(NON_VOTER, ServerId("id3"), ServerAddress(("addr3x")))
                )
            ),
        )
    )

    @Test
    fun hasVote() {
        sampleConfiguration.hasVote(ServerId("id0")) shouldBe false
        sampleConfiguration.hasVote(ServerId("id1")) shouldBe true
        sampleConfiguration.hasVote(ServerId("id3")) shouldBe false
        sampleConfiguration.hasVote(ServerId("someotherid")) shouldBe false
    }

    @Test
    fun checkEmpty() {
        val c = Configuration(emptySet())
        shouldThrowMessage("need at least one voter in configuration: $c") { c.check() }
    }

    @Test
    fun checkNonVoter() {
        val c = Configuration(setOf(Server(NON_VOTER, ServerId("id0"), ServerAddress(("addr0")))))
        shouldThrowMessage("need at least one voter in configuration: $c") { c.check() }
    }

    @Test
    fun checkVoter() {
        val c = Configuration(
            setOf(
                Server(NON_VOTER, ServerId("id0"), ServerAddress(("addr0"))),
                Server(VOTER, ServerId("id1"), ServerAddress(("addr1")))
            )
        )
        c.check()
    }

    @Test
    fun checkDuplicateId() {
        val c = Configuration(
            setOf(
                Server(NON_VOTER, ServerId("id0"), ServerAddress(("addr0"))),
                Server(VOTER, ServerId("id0"), ServerAddress(("addr1")))
            )
        )
        shouldThrowMessage("found duplicate ID in configuration: ${c.servers.pluck(1)}") { c.check() }
    }

    @Test
    fun checkDuplicateAddress() {
        val c = Configuration(
            setOf(
                Server(NON_VOTER, ServerId("id0"), ServerAddress(("addr0"))),
                Server(VOTER, ServerId("id1"), ServerAddress(("addr0")))
            )
        )
        shouldThrowMessage("found duplicate address in configuration: ${c.servers.pluck(1)}") { c.check() }
    }

    @Test
    fun nextConfigurationTest() {
        nextConfigurationTests.forEachIndexed { i, (testName, current, command, serverId, expectedNext) ->
            val clue = "Test #${i + 1}: $testName"
            withClue(clue) {
                val next = current.next(
                    1, ConfigurationChangeRequest(
                        command,
                        ServerId("id${serverId}"),
                        ServerAddress("addr${serverId}"),
                        0
                    )
                )
                next shouldBe expectedNext
            }
        }
    }

    @Test
    fun nextConfigurationStalePrevIndex() {
        // Stale prevIndex.
        val req = ConfigurationChangeRequest(
            command = ADD_STAGING,
            serverId = ServerId("id1"),
            serverAddress = ServerAddress("addr1"),
            prevIndex = 1,
        )
        val exception = shouldThrow<IllegalStateException> { singleServer.next(2, req) }
        exception.message shouldContain "changed"
    }

    @Test
    fun nextConfigurationCurrentPrevIndex() {
        val req = ConfigurationChangeRequest(
            command = ADD_STAGING,
            serverId = ServerId("id2"),
            serverAddress = ServerAddress("addr2"),
            prevIndex = 2,
        )
        singleServer.next(2, req)
    }

    @Test
    fun nextConfigurationZeroPrevIndex() {
        // Zero prevIndex.
        val req = ConfigurationChangeRequest(
            command = ADD_STAGING,
            serverId = ServerId("id3"),
            serverAddress = ServerAddress("addr3"),
            prevIndex = 0,
        )
        singleServer.next(2, req)
    }

    @Test
    fun nextConfigurationCheck() {
        // Zero prevIndex.
        val req = ConfigurationChangeRequest(
            command = ADD_NONVOTER,
            serverId = ServerId("id1"),
            serverAddress = ServerAddress("addr1"),
        )
        val exception = shouldThrow<IllegalStateException> { emptyConfiguration.next(1, req) }
        exception.message shouldContain "at least one voter"
    }

    @Test
    fun serializationDeserialization() {
        listOf(
            sampleConfiguration,
            singleServer,
            oneOfEach,
            emptyConfiguration,
            voterPair,
        ).forEachAsClue {
            val str = Json.encodeToString(it)
            val toObj = Json.decodeFromString<Configuration>(str)
            toObj shouldBe it
        }
    }


    /**
     * Picks [n]-th element from the collection
     */
    private fun <T> Collection<T>.pluck(n: Int): T {
        return asSequence().drop(n).first()
    }
}


