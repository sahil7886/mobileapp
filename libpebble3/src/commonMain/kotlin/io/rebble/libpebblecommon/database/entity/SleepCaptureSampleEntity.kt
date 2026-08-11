package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One locally buffered overnight classifier input emitted by the system Activity service.
 *
 * Values are deliberately stored in the original compact wire form. PPI is wrist-PPG input, not
 * a precomputed Apple Health HRV value; motion is a 30-second movement-energy summary. A stable
 * record ID makes DataLogging retries harmless and retains sequence gaps for diagnostics.
 */
@Entity(
    tableName = "sleep_capture_sample",
    indices = [
        Index(value = ["timestampEpochSeconds"]),
        Index(value = ["sessionId", "sequence"]),
        Index(value = ["sampleType"]),
    ],
)
data class SleepCaptureSampleEntity(
    @PrimaryKey val recordId: String,
    /** Identifier of one uninterrupted watch-side capture segment. */
    val sessionId: Long,
    /** Monotonic within [sessionId], including observable gaps after watch-buffer failures. */
    val sequence: Long,
    /** UTC second at which PebbleOS received/formed this sample or epoch. */
    val timestampEpochSeconds: Long,
    /** PPI milliseconds, BPM, motion-energy summary, or terminal dropped-record count. */
    val value: Int,
    /** Pebble HRM quality for PPI/BPM; 0..100 epoch completeness for motion. */
    val quality: Int,
    val sampleType: Int,
    val flags: Int,
    val receivedAtEpochSeconds: Long,
)
