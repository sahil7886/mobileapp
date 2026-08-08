package io.rebble.libpebblecommon.datalogging

import io.rebble.libpebblecommon.database.entity.GranularHeartRateEntity

/**
 * Wire contract for PebbleOS's built-in Workout service.
 *
 * The firmware's normal activity-session DataLogging record remains the authoritative workout
 * summary. This companion record carries the detailed heart-rate points and shares that session's
 * UTC start timestamp as [GranularHeartRateEntity.workoutId].
 */
object BuiltinWorkoutHeartRateProtocol {
    /** UUID_SYSTEM / DlsSystemTagWorkoutHeartRate in PebbleOS. */
    const val DATA_LOGGING_TAG: UInt = 88u
    const val RECORD_VERSION = 1
    const val RECORD_SIZE_BYTES = 16

    const val FLAG_WORKOUT_ACTIVE = 1 shl 0
    /** A zero-BPM terminal record whose timestamp is the exact time the workout was stopped. */
    const val FLAG_WORKOUT_COMPLETE = 1 shl 1

    private const val RECORD_ID_PREFIX = "builtin-workout-v1"
    private const val QUALITY_SHIFT = 8

    fun isBuiltinWorkoutRecord(recordId: String): Boolean =
        recordId.startsWith("$RECORD_ID_PREFIX:")

    /** Signed [HRMQuality] saved in the upper byte of [GranularHeartRateEntity.flags]. */
    fun sensorQuality(record: GranularHeartRateEntity): Int? =
        if (isBuiltinWorkoutRecord(record.recordId)) (record.flags shr QUALITY_SHIFT).toByte().toInt() else null

    /**
     * Decode one fixed-size little-endian record:
     * - bytes 0..3: corresponding ActivitySession start UTC / workout ID
     * - bytes 4..7: monotonic sample sequence
     * - bytes 8..11: sample UTC
     * - byte 12: BPM (zero only for a terminal record)
     * - byte 13: signed Pebble HRM quality
     * - byte 14: flags
     * - byte 15: schema version
     */
    fun decode(record: ByteArray, receivedAtEpochSeconds: Long): GranularHeartRateEntity? {
        if (record.size != RECORD_SIZE_BYTES || record[15].unsigned() != RECORD_VERSION) return null

        val workoutId = record.readUInt32LE(0)
        val sequence = record.readUInt32LE(4)
        val timestamp = record.readUInt32LE(8)
        val bpm = record[12].unsigned()
        val quality = record[13].toInt()
        val flags = record[14].unsigned()
        val complete = flags and FLAG_WORKOUT_COMPLETE != 0
        if (
            workoutId == 0L ||
            timestamp == 0L ||
            (complete && bpm != 0) ||
            (!complete && bpm !in 1..300)
        ) return null

        return GranularHeartRateEntity(
            recordId = "$RECORD_ID_PREFIX:$workoutId:$sequence",
            workoutId = workoutId,
            sequence = sequence,
            timestampEpochSeconds = timestamp,
            // The built-in Workout service receives the one-second sensor reading. The existing
            // entity's filtered field is the Apple Health-eligible BPM column, not a claim that
            // Pebble applied a new filter here.
            filteredBpm = bpm,
            rawBpm = bpm,
            // Keep the on-watch flags in the low byte and preserve the signed sensor quality in
            // the high byte without changing the existing Room schema used by Health Capture.
            flags = flags or ((quality and 0xff) shl QUALITY_SHIFT),
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
