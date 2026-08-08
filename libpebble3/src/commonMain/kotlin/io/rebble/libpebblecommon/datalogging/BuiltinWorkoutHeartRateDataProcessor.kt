package io.rebble.libpebblecommon.datalogging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.dao.HealthDao
import kotlin.time.Clock

/** Persists built-in Workout HR packets before DataLogging ACKs them to the watch. */
class BuiltinWorkoutHeartRateDataProcessor(
    private val healthDao: HealthDao,
    private val healthDataProcessor: HealthDataProcessor,
) {
    private val logger = Logger.withTag("BuiltinWorkoutHeartRate")

    suspend fun process(payload: ByteArray, itemSize: UShort): Boolean {
        val expectedSize = BuiltinWorkoutHeartRateProtocol.RECORD_SIZE_BYTES
        if (itemSize.toInt() != expectedSize || payload.size % expectedSize != 0) {
            logger.e {
                "BUILTIN_WORKOUT_HR incompatible packet itemSize=$itemSize payload=${payload.size}; retaining"
            }
            return false
        }

        val receivedAt = Clock.System.now().epochSeconds
        val decoded = payload
            .asList()
            .chunked(expectedSize)
            .map { BuiltinWorkoutHeartRateProtocol.decode(it.toByteArray(), receivedAt) }
        if (decoded.any { it == null }) {
            logger.e { "BUILTIN_WORKOUT_HR corrupt record; retaining packet" }
            return false
        }

        val records = decoded.filterNotNull()
        if (records.isEmpty()) return true
        val insertResults = healthDao.insertGranularHeartRate(records)
        val inserted = insertResults.count { it != -1L }
        if (inserted > 0) healthDataProcessor.emitHealthDataUpdated()
        logger.d {
            "BUILTIN_WORKOUT_HR persisted=$inserted duplicates=${records.size - inserted} " +
                "workout=${records.first().workoutId}"
        }
        return true
    }
}
