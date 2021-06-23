package dev.svilenivanov.raftkt.core2

import kotlinx.coroutines.flow.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker

class FsmApply(
    private val marker: Marker,
    private val fsm: Fsm,
) {
    private val logger: Logger = LoggerFactory.getLogger(FsmApply::class.java)

    suspend fun processLogs(consensus: Consensus, idx: Long): List<CommandResponse> = with(consensus) {
        if (idx <= volatile.lastApplied) {
            logger.warn(
                marker,
                "skipping application of old log at index {} (lastApplied is {})",
                idx,
                volatile.lastApplied
            )
        }
        val result = ((volatile.lastApplied + 1)..idx).asFlow().map {
            persistent.log.getLog(it) ?: throw IllegalStateException("Cannot find log entry at index $it")
        }.buffer().mapNotNull { log ->
            if (log.data is Log.Data.CommandData) {
                log.data.command
            } else {
                logger.debug(marker, "Ignoring {} log entry", log.data::class.simpleName)
                null
            }
        }.map { command -> fsm.apply(command) }.toList()
        volatile.lastApplied = idx
        return result
    }


}
