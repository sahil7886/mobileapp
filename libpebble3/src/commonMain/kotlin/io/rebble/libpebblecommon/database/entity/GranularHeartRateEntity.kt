package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A high-resolution heart-rate record produced by the optional Health Capture watch worker.
 *
 * The watch writes a persistent workout ID and monotonically increasing sequence number into
 * every record.  [recordId] is therefore stable when DataLogging retries a disconnected or
 * interrupted transfer and doubles as the idempotency key used by the HealthKit exporter.
 */
@Entity(
    tableName = "granular_heart_rate",
    indices = [
        Index(value = ["timestampEpochSeconds"]),
        Index(value = ["exportedToAppleHealth"]),
    ],
)
data class GranularHeartRateEntity(
    @PrimaryKey
    val recordId: String,
    val workoutId: Long,
    val sequence: Long,
    val timestampEpochSeconds: Long,
    /** Filtered BPM supplied by Pebble Health. Zero means that it was unavailable. */
    val filteredBpm: Int,
    /** Raw BPM is retained only for diagnostics; it is never exported as a health measurement. */
    val rawBpm: Int,
    val flags: Int,
    val receivedAtEpochSeconds: Long,
    val exportedToAppleHealth: Boolean = false,
) {
    companion object {
        fun recordId(workoutId: Long, sequence: Long): String =
            "health-capture-v1:$workoutId:$sequence"
    }
}
