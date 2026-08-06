package io.rebble.libpebblecommon.datalogging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.dao.HealthDao
import kotlin.time.Clock

/** Persists high-resolution worker records before DataLogging acknowledges their packet. */
class HealthCaptureDataProcessor(
    private val healthDao: HealthDao,
    private val healthDataProcessor: HealthDataProcessor,
) {
    private val logger = Logger.withTag("HealthCaptureDataProcessor")

    /**
     * Returns false only when the packet cannot safely be understood.  The caller responds with a
     * DataLogging NACK in that case, so the watch retains its local buffer for a compatible app.
     */
    suspend fun process(payload: ByteArray, itemSize: UShort): Boolean {
        val expectedSize = HealthCaptureProtocol.RECORD_SIZE_BYTES
        if (itemSize.toInt() != expectedSize || payload.size % expectedSize != 0) {
            logger.e {
                "HEALTH_CAPTURE_DATALOG incompatible packet itemSize=$itemSize payload=${payload.size}; retaining"
            }
            return false
        }

        val receivedAt = Clock.System.now().epochSeconds
        val records = payload
            .asList()
            .chunked(expectedSize)
            .map { bytes -> HealthCaptureProtocol.decode(bytes.toByteArray(), receivedAt) }

        if (records.any { it == null }) {
            logger.e { "HEALTH_CAPTURE_DATALOG unknown/corrupt record; retaining packet" }
            return false
        }

        val decoded = records.filterNotNull()
        if (decoded.isEmpty()) return true

        val insertResults = healthDao.insertGranularHeartRate(decoded)
        val inserted = insertResults.count { it != -1L }
        val duplicates = decoded.size - inserted
        if (inserted > 0) healthDataProcessor.emitHealthDataUpdated()
        logger.d {
            "HEALTH_CAPTURE_DATALOG persisted=$inserted duplicates=$duplicates " +
                "first=${decoded.first().recordId} last=${decoded.last().recordId}"
        }
        return true
    }
}
