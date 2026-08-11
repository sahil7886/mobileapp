package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.BeatToBeatEntity
import io.rebble.libpebblecommon.database.entity.GranularHeartRateEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHealthData(data: List<HealthDataEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverlayData(data: List<OverlayDataEntity>)

    /**
     * DataLogging retries can replay an already persisted batch.  IGNORE makes that harmless while
     * preserving the original record and its Apple Health export state.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGranularHeartRate(data: List<GranularHeartRateEntity>): List<Long>

    /** Raw accepted PPI values stay locally available; Apple Health never receives them directly. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBeatToBeat(data: List<BeatToBeatEntity>): List<Long>

    @Query("""
        SELECT * FROM beat_to_beat
        WHERE timestampEpochSeconds >= :start AND timestampEpochSeconds < :end
        ORDER BY timestampEpochSeconds ASC, sequence ASC, recordId ASC
        """)
    suspend fun getBeatToBeatForRange(start: Long, end: Long): List<BeatToBeatEntity>

    @Query("SELECT COUNT(*) FROM beat_to_beat WHERE intervalMs > 0")
    suspend fun countBeatToBeat(): Int

    @Query("""
        SELECT * FROM granular_heart_rate
        WHERE exportedToAppleHealth = 0 AND filteredBpm BETWEEN 1 AND 300
        ORDER BY timestampEpochSeconds ASC, sequence ASC
        LIMIT :limit
        """)
    suspend fun getPendingGranularHeartRate(limit: Int): List<GranularHeartRateEntity>

    /**
     * Includes both filtered and raw-only worker records.  The latter cannot be written to
     * HealthKit as a heart-rate measurement, but must remain available to a user data export.
     */
    @Query("""
        SELECT * FROM granular_heart_rate
        WHERE timestampEpochSeconds >= :start AND timestampEpochSeconds < :end
        ORDER BY timestampEpochSeconds ASC, sequence ASC, recordId ASC
        """)
    suspend fun getGranularHeartRateForRange(start: Long, end: Long): List<GranularHeartRateEntity>

    @Query("""
        UPDATE granular_heart_rate
        SET exportedToAppleHealth = 1
        WHERE recordId IN (:recordIds)
        """)
    suspend fun markGranularHeartRateExported(recordIds: List<String>): Int

    @Query("""
        SELECT COUNT(*) FROM granular_heart_rate
        WHERE exportedToAppleHealth = 0 AND filteredBpm BETWEEN 1 AND 300
        """)
    suspend fun countPendingGranularHeartRate(): Int

    @Query("SELECT * FROM health_data WHERE timestamp >= :start AND timestamp <= :end ORDER BY timestamp ASC")
    fun getHealthData(start: Long, end: Long): Flow<List<HealthDataEntity>>

    @Query("SELECT * FROM overlay_data WHERE startTime >= :start AND startTime <= :end ORDER BY startTime ASC")
    fun getOverlayData(start: Long, end: Long): Flow<List<OverlayDataEntity>>

    @Query("SELECT SUM(steps) FROM health_data WHERE timestamp >= :start AND timestamp <= :end")
    suspend fun getTotalSteps(start: Long, end: Long): Int?

    @Query("""
        SELECT 
            SUM(steps) AS steps,
            SUM(activeGramCalories) AS activeGramCalories,
            SUM(restingGramCalories) AS restingGramCalories,
            SUM(activeMinutes) AS activeMinutes,
            SUM(distanceCm) AS distanceCm
        FROM health_data
        WHERE timestamp >= :start AND timestamp < :end
        """)
    suspend fun getAggregatedHealthData(start: Long, end: Long): HealthAggregates?

    @Query("SELECT SUM(steps) FROM health_data WHERE timestamp >= :start AND timestamp < :end")
    suspend fun getTotalStepsExclusiveEnd(start: Long, end: Long): Long?

    @Query("SELECT AVG(steps) FROM health_data WHERE timestamp >= :start AND timestamp <= :end")
    suspend fun getAverageSteps(start: Long, end: Long): Double?

    @Query("SELECT AVG(heartRate) FROM health_data WHERE timestamp >= :start AND timestamp < :end AND heartRate > 0")
    suspend fun getAverageHeartRate(start: Long, end: Long): Double?

    @Query("SELECT COUNT(*) FROM health_data WHERE timestamp >= :start AND timestamp <= :end")
    suspend fun hasDataForRange(start: Long, end: Long): Int

    @Query("SELECT COUNT(*) FROM health_data") suspend fun hasAnyHealthData(): Int

    @Query("SELECT MAX(timestamp) FROM health_data") suspend fun getLatestTimestamp(): Long?

    @Query("SELECT * FROM health_data WHERE timestamp = :timestamp")
    suspend fun getDataAtTimestamp(timestamp: Long): HealthDataEntity?

    @Query("SELECT * FROM health_data WHERE heartRate > 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestHeartRateReading(): HealthDataEntity?

    @Query("""
        SELECT heartRateZone, COUNT(*) as minutes
        FROM health_data
        WHERE timestamp >= :start AND timestamp < :end AND heartRate > 0
        GROUP BY heartRateZone
        """)
    suspend fun getHeartRateZoneMinutes(start: Long, end: Long): List<HRZoneCount>

    @Query("SELECT SUM(duration) FROM overlay_data WHERE startTime >= :start AND startTime < :end AND type = :type")
    suspend fun getOverlayDuration(start: Long, end: Long, type: Int): Long?

    @Query("""
        SELECT * FROM overlay_data
        WHERE startTime >= :start AND startTime < :end AND type IN (:types)
        """)
    suspend fun getOverlayEntries(
            start: Long,
            end: Long,
            types: List<Int>
    ): List<OverlayDataEntity>

    /**
     * All overlay kinds whose recorded interval intersects the requested half-open range.
     * This deliberately retains a sleep or workout that began before the range but ended within
     * it, so a local export does not silently lose the beginning of an overnight session.
     */
    @Query("""
        SELECT * FROM overlay_data
        WHERE (startTime >= :start AND startTime < :end)
           OR (startTime < :start AND startTime + duration > :start)
        ORDER BY startTime ASC, type ASC
        """)
    suspend fun getOverlayEntriesForRange(start: Long, end: Long): List<OverlayDataEntity>

    @Query("SELECT * FROM overlay_data ORDER BY startTime ASC")
    suspend fun getAllOverlayEntries(): List<OverlayDataEntity>

    @Query("SELECT * FROM overlay_data WHERE startTime = :startTime AND type = :type")
    suspend fun getOverlayAtStartTimeAndType(startTime: Long, type: Int): OverlayDataEntity?

    @Query("SELECT * FROM health_data WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    suspend fun getHealthDataForRange(start: Long, end: Long): List<HealthDataEntity>

    @Query("SELECT * FROM health_data WHERE timestamp > :afterTimestamp ORDER BY timestamp ASC")
    suspend fun getHealthDataAfter(afterTimestamp: Long): List<HealthDataEntity>

    @Query("SELECT * FROM overlay_data WHERE startTime > :afterTimestamp AND type IN (:types) ORDER BY startTime ASC")
    suspend fun getOverlayEntriesAfter(afterTimestamp: Long, types: List<Int>): List<OverlayDataEntity>

    @Query("""
        SELECT SUM(duration) / 60 FROM overlay_data
        WHERE startTime >= :start AND startTime < :end AND type = 1
        """)
    suspend fun getTotalSleepMinutes(start: Long, end: Long): Long?

    @Query("""
        SELECT SUM(duration) / 60 FROM overlay_data
        WHERE startTime >= :start AND startTime < :end AND type = 2
        """)
    suspend fun getDeepSleepMinutes(start: Long, end: Long): Long?

    @Query("""
        SELECT COUNT(DISTINCT DATE(startTime, 'unixepoch', 'localtime')) FROM overlay_data
        WHERE startTime >= :start AND startTime < :end AND type IN (1, 2) AND duration > 0
        """)
    suspend fun getDaysWithSleepData(start: Long, end: Long): Int

    @Query("""
        SELECT COUNT(DISTINCT DATE(timestamp, 'unixepoch', 'localtime')) FROM health_data
        WHERE timestamp >= :start AND timestamp < :end AND steps > 0
        """)
    suspend fun getDaysWithStepsData(start: Long, end: Long): Int

    @Query("""
        SELECT
            date(timestamp, 'unixepoch', 'localtime') as day,
            SUM(steps) AS steps,
            SUM(activeGramCalories) AS activeGramCalories,
            SUM(restingGramCalories) AS restingGramCalories,
            SUM(activeMinutes) AS activeMinutes,
            SUM(distanceCm) AS distanceCm
        FROM health_data
        WHERE timestamp >= :start AND timestamp < :end
        GROUP BY day
        """)
    suspend fun getDailyMovementAggregates(start: Long, end: Long): List<DailyMovementAggregate>

    @Query("DELETE FROM health_data WHERE timestamp < :expirationTimestamp")
    suspend fun deleteExpiredHealthData(expirationTimestamp: Long): Int

    @Query("DELETE FROM overlay_data WHERE startTime < :expirationTimestamp")
    suspend fun deleteExpiredOverlayData(expirationTimestamp: Long): Int
}

data class HealthAggregates(
    val steps: Long?,
    val activeGramCalories: Long?,
    val restingGramCalories: Long?,
    val activeMinutes: Long?,
    val distanceCm: Long?,
)

data class DailyMovementAggregate(
    val day: String, // YYYY-MM-DD
    val steps: Long?,
    val activeGramCalories: Long?,
    val restingGramCalories: Long?,
    val activeMinutes: Long?,
    val distanceCm: Long?
)

data class HRZoneCount(
    val heartRateZone: Int,
    val minutes: Long,
)
