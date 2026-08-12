package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One conservative SDNN value calculated on the phone from a completed overnight PPI window.
 *
 * This deliberately retains the calculation provenance beside the value.  Raw PPI values stay
 * in [SleepCaptureSampleEntity]; only this aggregate is eligible for Apple Health export.
 */
@Entity(
    tableName = "overnight_hrv",
    indices = [
        Index(value = ["sessionId", "windowStartEpochSeconds"]),
        Index(value = ["exportedToAppleHealth"]),
    ],
)
data class OvernightHrvEntity(
    /** Stable logical window identity used for Room and HealthKit replay protection. */
    @PrimaryKey val recordId: String,
    val sessionId: Long,
    val windowStartEpochSeconds: Long,
    val windowEndEpochSeconds: Long,
    /** SDNN, in milliseconds, calculated from quality-filtered PPI intervals. */
    val sdnnMilliseconds: Double,
    val sourcePpiSampleCount: Int,
    val qualityAcceptedSampleCount: Int,
    val artifactRejectedSampleCount: Int,
    /** Percentage of source PPI samples that passed the watch-quality gate. */
    val qualityCoveragePercent: Int,
    /** Percentage of the five-minute calculation window represented by accepted intervals. */
    val temporalCoveragePercent: Int,
    val algorithmVersion: Int,
    val calculatedAtEpochSeconds: Long,
    val exportedToAppleHealth: Boolean = false,
)
