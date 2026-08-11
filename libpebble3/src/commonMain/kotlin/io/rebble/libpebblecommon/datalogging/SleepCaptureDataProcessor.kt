package io.rebble.libpebblecommon.datalogging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.dao.HealthDao
import kotlin.time.Clock

/** Durably stores raw overnight classifier inputs before DataLogging ACKs the watch packet. */
class SleepCaptureDataProcessor(
    private val healthDao: HealthDao,
    private val healthDataProcessor: HealthDataProcessor,
) {
    private val logger = Logger.withTag("SleepCapture")

    suspend fun process(payload: ByteArray, itemSize: UShort): Boolean {
        val expectedSize = SleepCaptureProtocol.RECORD_SIZE_BYTES
        if (itemSize.toInt() != expectedSize || payload.size % expectedSize != 0) {
            logger.e {
                "SLEEP_CAPTURE incompatible packet itemSize=$itemSize payload=${payload.size}; retaining"
            }
            return false
        }
        val receivedAt = Clock.System.now().epochSeconds
        val records = payload.asList().chunked(expectedSize).map {
            SleepCaptureProtocol.decode(it.toByteArray(), receivedAt)
        }
        if (records.any { it == null }) {
            logger.e { "SLEEP_CAPTURE corrupt record; retaining packet" }
            return false
        }
        val decoded = records.filterNotNull()
        if (decoded.isEmpty()) return true
        val insertResults = healthDao.insertSleepCaptureSamples(decoded)
        val inserted = insertResults.count { it != -1L }
        if (inserted > 0) healthDataProcessor.emitHealthDataUpdated()
        logger.d {
            "SLEEP_CAPTURE persisted=$inserted duplicates=${decoded.size - inserted} " +
                "session=${decoded.first().sessionId}"
        }
        return true
    }
}
