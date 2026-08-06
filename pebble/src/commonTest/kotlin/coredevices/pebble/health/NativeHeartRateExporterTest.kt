package coredevices.pebble.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeHeartRateExporterTest {
    @Test
    fun syncIdentifier_isStableForTheStoredPebbleSequence() {
        val firstAttempt = HeartRateExportSample(timestampSeconds = 1_725_000_000, beatsPerMinute = 61)
        val replay = HeartRateExportSample(timestampSeconds = 1_725_000_000, beatsPerMinute = 61)

        assertEquals(firstAttempt.syncIdentifier, replay.syncIdentifier)
        assertEquals(
            "coredevices.pebble.health.hr.v1.1725000000",
            firstAttempt.syncIdentifier,
        )
    }

    @Test
    fun rejectsInvalidSensorValuesBeforeTheyReachHealthKit() {
        assertFailsWith<IllegalArgumentException> {
            HeartRateExportSample(timestampSeconds = 1, beatsPerMinute = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            HeartRateExportSample(timestampSeconds = 1, beatsPerMinute = 301)
        }
        assertFailsWith<IllegalArgumentException> {
            HeartRateExportSample(timestampSeconds = 0, beatsPerMinute = 60)
        }
    }

    @Test
    fun workerRecordsUseTheirPersistentWorkoutAndSequenceIdentity() {
        val sample = HeartRateExportSample(
            timestampSeconds = 1_725_000_005,
            beatsPerMinute = 145,
            sourceRecordId = "health-capture-v1:1725000000:39",
        )

        assertEquals(
            "coredevices.pebble.health.hr.v1.health-capture-v1:1725000000:39",
            sample.syncIdentifier,
        )
    }
}
