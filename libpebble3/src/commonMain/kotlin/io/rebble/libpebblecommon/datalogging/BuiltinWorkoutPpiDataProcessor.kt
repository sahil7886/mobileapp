package io.rebble.libpebblecommon.datalogging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.dao.HealthDao
import kotlin.time.Clock

/** Persists every complete PPI packet before its DataLogging ACK reaches the watch. */
class BuiltinWorkoutPpiDataProcessor(
    private val healthDao: HealthDao,
    private val healthDataProcessor: HealthDataProcessor,
) {
    private val logger = Logger.withTag("BuiltinWorkoutPpi")

    suspend fun process(payload: ByteArray, itemSize: UShort): Boolean {
        val expectedSize = BuiltinWorkoutPpiProtocol.RECORD_SIZE_BYTES
        if (itemSize.toInt() != expectedSize || payload.size % expectedSize != 0) {
            logger.e {
                "BUILTIN_WORKOUT_PPI incompatible packet itemSize=$itemSize payload=${payload.size}; retaining"
            }
            return false
        }
        val receivedAt = Clock.System.now().epochSeconds
        val decoded = payload.asList().chunked(expectedSize).map {
            BuiltinWorkoutPpiProtocol.decode(it.toByteArray(), receivedAt)
        }
        if (decoded.any { it == null }) {
            logger.e { "BUILTIN_WORKOUT_PPI corrupt record; retaining packet" }
            return false
        }
        val records = decoded.filterNotNull()
        if (records.isEmpty()) return true
        val insertResults = healthDao.insertBeatToBeat(records)
        val inserted = insertResults.count { it != -1L }
        if (inserted > 0) healthDataProcessor.emitHealthDataUpdated()
        logger.d {
            "BUILTIN_WORKOUT_PPI persisted=$inserted duplicates=${records.size - inserted} " +
                "workout=${records.first().workoutId}"
        }
        return true
    }
}
