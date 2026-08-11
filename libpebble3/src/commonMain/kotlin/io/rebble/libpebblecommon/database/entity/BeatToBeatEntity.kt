package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One accepted peak-to-peak interval (PPI) reported by the Time 2's HRV algorithm.
 *
 * This deliberately preserves the on-watch interval and quality exactly as received. A PPI is
 * raw input, not an Apple Health HRV value. [recordId] remains stable when DataLogging retries.
 */
@Entity(
    tableName = "beat_to_beat",
    indices = [
        Index(value = ["timestampEpochSeconds"]),
        Index(value = ["workoutId", "sequence"]),
    ],
)
data class BeatToBeatEntity(
    @PrimaryKey val recordId: String,
    /** Built-in Workout start UTC; ties this interval to its normal ActivitySession. */
    val workoutId: Long,
    /** Monotonic watch sequence. Multiple intervals can share a UTC second. */
    val sequence: Long,
    /** UTC second when PebbleOS delivered the accepted interval to the Workout service. */
    val timestampEpochSeconds: Long,
    /** Accepted PPI/RR interval from the Goodix HRV algorithm, in milliseconds. */
    val intervalMs: Int,
    /** Signed Pebble HRM quality reported alongside the interval. */
    val quality: Int,
    /** Low-nibble protocol flags; includes active and terminal session records. */
    val flags: Int,
    val receivedAtEpochSeconds: Long,
) {
    companion object {
        fun recordId(workoutId: Long, sequence: Long): String =
            "builtin-workout-ppi-v1:$workoutId:$sequence"
    }
}
