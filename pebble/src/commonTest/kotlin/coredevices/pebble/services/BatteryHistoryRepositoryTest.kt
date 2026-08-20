package coredevices.pebble.services

import coredevices.database.BatteryHistoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatteryHistoryRepositoryTest {
    @Test
    fun calculatesDailyDrainAndRemainingTime() {
        val summary = calculateBatteryUsageSummary(
            samples = listOf(
                sample(level = 90, hour = 0),
                sample(level = 80, hour = 24),
            ),
            currentBatteryLevel = 80,
        )

        requireNotNull(summary)
        assertEquals(10.0, summary.drainPerDay)
        assertEquals(192.0, summary.estimatedHoursRemaining)
    }

    @Test
    fun doesNotCountChargingAsDrain() {
        val summary = calculateBatteryUsageSummary(
            samples = listOf(
                sample(level = 70, hour = 0),
                sample(level = 90, hour = 4),
                sample(level = 80, hour = 16),
            ),
            currentBatteryLevel = 80,
        )

        requireNotNull(summary)
        assertEquals(20.0, summary.drainPerDay)
    }

    @Test
    fun waitsForEnoughDischargeData() {
        val summary = calculateBatteryUsageSummary(
            samples = listOf(
                sample(level = 80, hour = 0),
                sample(level = 79, hour = 24),
            ),
            currentBatteryLevel = 79,
        )

        assertNull(summary)
    }

    private fun sample(level: Int, hour: Long) = BatteryHistoryEntry(
        serial = "watch",
        batteryLevel = level,
        recordedAt = hour * 60 * 60 * 1000,
    )
}
