package coredevices.pebble.services

import coredevices.database.BatteryHistoryDao
import coredevices.database.BatteryHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

private const val SAMPLE_INTERVAL_MS = 6 * 60 * 60 * 1000L
private const val RETENTION_MS = 90 * 24 * 60 * 60 * 1000L
private const val CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000L

class BatteryHistoryRepository(
    private val dao: BatteryHistoryDao,
    private val clock: Clock,
) {
    private val latestSamples = mutableMapOf<String, BatteryHistoryEntry>()
    private var lastCleanupAt: Long? = null

    fun observe(serial: String): Flow<List<BatteryHistoryEntry>> = dao.observeForSerial(serial)

    suspend fun record(serial: String, batteryLevel: Int) {
        if (batteryLevel !in 0..100) return

        val now = clock.now().toEpochMilliseconds()
        val previous = latestSamples[serial] ?: dao.getLatestForSerial(serial)
        if (previous != null && previous.batteryLevel == batteryLevel && now - previous.recordedAt < SAMPLE_INTERVAL_MS) {
            return
        }

        val sample = BatteryHistoryEntry(
            serial = serial,
            batteryLevel = batteryLevel,
            recordedAt = now,
        )
        dao.insert(sample)
        latestSamples[serial] = sample

        val lastCleanup = lastCleanupAt
        if (lastCleanup == null || now - lastCleanup >= CLEANUP_INTERVAL_MS) {
            dao.deleteBefore(now - RETENTION_MS)
            lastCleanupAt = now
        }
    }
}

internal data class BatteryUsageSummary(
    val drainPerDay: Double,
    val estimatedHoursRemaining: Double?,
)

internal fun calculateBatteryUsageSummary(
    samples: List<BatteryHistoryEntry>,
    currentBatteryLevel: Int?,
): BatteryUsageSummary? {
    var drainedPercent = 0
    var drainingMillis = 0L

    samples.zipWithNext().forEach { (start, end) ->
        val drop = start.batteryLevel - end.batteryLevel
        val elapsed = end.recordedAt - start.recordedAt
        if (drop > 0 && elapsed > 0) {
            drainedPercent += drop
            drainingMillis += elapsed
        }
    }

    if (drainedPercent < 2 || drainingMillis <= 0) return null
    val drainPerDay = drainedPercent.toDouble() / drainingMillis * 24 * 60 * 60 * 1000
    if (drainPerDay <= 0.0) return null

    return BatteryUsageSummary(
        drainPerDay = drainPerDay,
        estimatedHoursRemaining = currentBatteryLevel?.toDouble()?.div(drainPerDay)?.times(24),
    )
}
