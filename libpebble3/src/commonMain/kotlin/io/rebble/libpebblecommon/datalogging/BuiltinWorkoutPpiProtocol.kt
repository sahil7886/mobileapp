package io.rebble.libpebblecommon.datalogging

import io.rebble.libpebblecommon.database.entity.BeatToBeatEntity

/**
 * Compact, replay-safe contract for accepted Time 2 PPI/RR intervals during a built-in Workout.
 * It is separate from BPM because several PPI events can occur in a single UTC second.
 */
object BuiltinWorkoutPpiProtocol {
    /** UUID_SYSTEM / DlsSystemTagWorkoutPpi in PebbleOS. */
    const val DATA_LOGGING_TAG: UInt = 89u
    const val RECORD_SIZE_BYTES = 16

    const val FLAG_WORKOUT_ACTIVE = 1 shl 0
    /** Zero-interval commit record whose time is the exact Workout stop time. */
    const val FLAG_WORKOUT_COMPLETE = 1 shl 1

    private const val RECORD_VERSION = 1
    private const val VERSION_SHIFT = 4
    private const val FLAGS_MASK = 0x0f
    private const val RECORD_ID_PREFIX = "builtin-workout-ppi-v1"

    fun isBuiltinWorkoutPpiRecord(recordId: String): Boolean =
        recordId.startsWith("$RECORD_ID_PREFIX:")

    /**
     * Bytes: workout id (0..3), sequence (4..7), UTC timestamp (8..11), PPI ms (12..13),
     * signed quality (14), and version/flags (15). PPI zero is valid only for a terminal record.
     */
    fun decode(record: ByteArray, receivedAtEpochSeconds: Long): BeatToBeatEntity? {
        if (record.size != RECORD_SIZE_BYTES) return null
        val flagsAndVersion = record[15].unsigned()
        if ((flagsAndVersion ushr VERSION_SHIFT) != RECORD_VERSION) return null

        val workoutId = record.readUInt32LE(0)
        val sequence = record.readUInt32LE(4)
        val timestamp = record.readUInt32LE(8)
        val intervalMs = record.readUInt16LE(12)
        val quality = record[14].toInt()
        val flags = flagsAndVersion and FLAGS_MASK
        val complete = flags and FLAG_WORKOUT_COMPLETE != 0
        if (workoutId == 0L || timestamp == 0L || (complete && intervalMs != 0) ||
            (!complete && intervalMs == 0)
        ) return null

        return BeatToBeatEntity(
            recordId = "$RECORD_ID_PREFIX:$workoutId:$sequence",
            workoutId = workoutId,
            sequence = sequence,
            timestampEpochSeconds = timestamp,
            intervalMs = intervalMs,
            quality = quality,
            flags = flags,
            receivedAtEpochSeconds = receivedAtEpochSeconds,
        )
    }

    private fun ByteArray.readUInt16LE(offset: Int): Int =
        this[offset].unsigned() or (this[offset + 1].unsigned() shl 8)

    private fun ByteArray.readUInt32LE(offset: Int): Long =
        (this[offset].unsigned().toLong()) or
            (this[offset + 1].unsigned().toLong() shl 8) or
            (this[offset + 2].unsigned().toLong() shl 16) or
            (this[offset + 3].unsigned().toLong() shl 24)

    private fun Byte.unsigned(): Int = toInt() and 0xff
}
