package io.rebble.libpebblecommon.datalogging

import io.rebble.libpebblecommon.database.entity.GranularHeartRateEntity
import kotlin.uuid.Uuid

/**
 * Wire contract shared with watchapps/health-capture.
 *
 * This is deliberately a fixed-size, versioned little-endian record.  The mobile app rejects an
 * unknown version instead of silently misinterpreting health data, leaving the watch's buffered
 * log intact until the matching companion build is installed.
 */
object HealthCaptureProtocol {
    const val APPLICATION_UUID_STRING = "4bb21e88-26a9-4039-9d04-082bff71073e"
    val APPLICATION_UUID: Uuid = Uuid.parse(APPLICATION_UUID_STRING)

    /** ASCII `HRC1`, scoped to this application UUID by the DataLogging protocol. */
    const val HEART_RATE_RECORD_TAG: UInt = 0x48524331u
    const val RECORD_VERSION: Int = 1
    const val RECORD_SIZE_BYTES: Int = 16

    const val FLAG_WORKOUT_ACTIVE: Int = 1 shl 0
    const val FLAG_FILTERED_AVAILABLE: Int = 1 shl 1
    const val FLAG_RAW_AVAILABLE: Int = 1 shl 2

    /**
     * Decode a single Health Capture record:
     * - bytes 0..3: workout ID (uint32 LE)
     * - bytes 4..7: persistent sequence ID (uint32 LE)
     * - bytes 8..11: UTC epoch seconds (uint32 LE)
     * - bytes 12..15: filtered BPM, raw BPM, flags, schema version
     */
    fun decode(record: ByteArray, receivedAtEpochSeconds: Long): GranularHeartRateEntity? {
        if (record.size != RECORD_SIZE_BYTES || record[15].unsigned() != RECORD_VERSION) return null

        val workoutId = record.readUInt32LE(0)
        val sequence = record.readUInt32LE(4)
        val timestamp = record.readUInt32LE(8)
        if (workoutId == 0L || timestamp == 0L) return null

        return GranularHeartRateEntity(
            recordId = GranularHeartRateEntity.recordId(workoutId, sequence),
            workoutId = workoutId,
            sequence = sequence,
            timestampEpochSeconds = timestamp,
            filteredBpm = record[12].unsigned(),
            rawBpm = record[13].unsigned(),
            flags = record[14].unsigned(),
            receivedAtEpochSeconds = receivedAtEpochSeconds,
        )
    }

    private fun ByteArray.readUInt32LE(offset: Int): Long =
        (this[offset].unsigned().toLong()) or
            (this[offset + 1].unsigned().toLong() shl 8) or
            (this[offset + 2].unsigned().toLong() shl 16) or
            (this[offset + 3].unsigned().toLong() shl 24)

    private fun Byte.unsigned(): Int = toInt() and 0xff
}
