package coredevices.pebble.health

import io.rebble.libpebblecommon.database.entity.GranularHeartRateEntity
import io.rebble.libpebblecommon.database.entity.BeatToBeatEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.database.entity.OvernightHrvEntity
import io.rebble.libpebblecommon.database.entity.SleepCaptureSampleEntity
import io.rebble.libpebblecommon.datalogging.BuiltinWorkoutHeartRateProtocol
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HealthDataExportCsvTest {
    @Test
    fun csvEscapesCommasQuotesAndLineBreaks() {
        assertEquals(
            "plain,\"comma,value\",\"quote \"\"value\"\"\",\"line\nbreak\"",
            HealthDataExportCsv.csvRow("plain", "comma,value", "quote \"value\"", "line\nbreak"),
        )
    }

    @Test
    fun granularRowsKeepRawDiagnosticsAndStableRecordId() {
        val row = HealthDataExportCsv.granularHeartRateRow(
            GranularHeartRateEntity(
                recordId = "health-capture-v1:7:11",
                workoutId = 7,
                sequence = 11,
                timestampEpochSeconds = 1_725_000_005,
                filteredBpm = 144,
                rawBpm = 147,
                flags = 7,
                receivedAtEpochSeconds = 1_725_000_100,
                exportedToAppleHealth = false,
            ),
        )

        assertContains(row, ",144,147,7,")
        assertContains(row, ",false,health-capture-v1:7:11")
    }

    @Test
    fun builtInWorkoutRowsExposeTheRecordedSensorQuality() {
        val row = HealthDataExportCsv.granularHeartRateRow(
            GranularHeartRateEntity(
                recordId = "builtin-workout-v1:1700000000:7",
                workoutId = 1_700_000_000,
                sequence = 7,
                timestampEpochSeconds = 1_700_000_007,
                filteredBpm = 132,
                rawBpm = 132,
                flags = BuiltinWorkoutHeartRateProtocol.FLAG_WORKOUT_ACTIVE or (3 shl 8),
                receivedAtEpochSeconds = 1_700_000_100,
            ),
        )

        assertContains(HealthDataExportCsv.GRANULAR_HEART_RATE_HEADER, "sensor_quality")
        assertContains(row, ",132,132,1,3,")
    }

    @Test
    fun ppiRowsPreserveIntervalQualityAndStableIdentity() {
        val row = HealthDataExportCsv.beatToBeatRow(
            BeatToBeatEntity(
                recordId = "builtin-workout-ppi-v1:1700000000:8",
                workoutId = 1_700_000_000,
                sequence = 8,
                timestampEpochSeconds = 1_700_000_007,
                intervalMs = 888,
                quality = 3,
                flags = 1,
                receivedAtEpochSeconds = 1_700_000_100,
            ),
        )

        assertContains(HealthDataExportCsv.BEAT_TO_BEAT_HEADER, "interval_ms,sensor_quality")
        assertContains(row, ",1700000000,8,888,3,1,")
        assertContains(row, "builtin-workout-ppi-v1:1700000000:8,builtin_workout_ppi")
    }

    @Test
    fun sleepCaptureRowsKeepTypeQualityAndStableIdentity() {
        val row = HealthDataExportCsv.sleepCaptureRow(
            SleepCaptureSampleEntity(
                recordId = "sleep-capture-v1:1700000000:8",
                sessionId = 1_700_000_000,
                sequence = 8,
                timestampEpochSeconds = 1_700_000_007,
                value = 888,
                quality = 3,
                sampleType = 1,
                flags = 0,
                receivedAtEpochSeconds = 1_700_000_100,
            ),
        )

        assertContains(HealthDataExportCsv.SLEEP_CAPTURE_HEADER, "capture_session_id,sequence,sample_type")
        assertContains(row, ",1700000000,8,ppi,888,3,0,")
        assertContains(row, "sleep-capture-v1:1700000000:8,system_activity_sleep_capture")
    }

    @Test
    fun overnightHrvRowsKeepSdnnAndCalculationProvenance() {
        val row = HealthDataExportCsv.overnightHrvRow(
            OvernightHrvEntity(
                recordId = "overnight-hrv-sdnn:7:1700000100",
                sessionId = 7,
                windowStartEpochSeconds = 1_700_000_100,
                windowEndEpochSeconds = 1_700_000_400,
                sdnnMilliseconds = 42.5,
                sourcePpiSampleCount = 300,
                qualityAcceptedSampleCount = 294,
                artifactRejectedSampleCount = 2,
                qualityCoveragePercent = 98,
                temporalCoveragePercent = 96,
                algorithmVersion = 1,
                calculatedAtEpochSeconds = 1_700_001_000,
                exportedToAppleHealth = true,
            ),
        )

        assertContains(HealthDataExportCsv.OVERNIGHT_HRV_HEADER, "sdnn_milliseconds")
        assertContains(row, ",7,42.5,300,294,2,98,96,1,")
        assertContains(row, ",true,overnight-hrv-sdnn:7:1700000100")
    }

    @Test
    fun unknownOverlayTypesRemainExportable() {
        val row = HealthDataExportCsv.overlayRow(
            OverlayDataEntity(
                startTime = 1_725_000_000,
                duration = 90,
                type = 99,
                steps = 10,
                restingKiloCalories = 1,
                activeKiloCalories = 2,
                distanceCm = 3,
                offsetUTC = 0,
            ),
        )

        assertContains(row, ",99,Unknown(99),")
    }

    @Test
    fun sixMonthPeriodUsesAStableRollingWindow() {
        assertEquals(0L, HealthDataExportPeriod.Past6Months.startEpochSeconds(100))
        assertEquals(
            1_000_000L - 180L * 24L * 60L * 60L,
            HealthDataExportPeriod.Past6Months.startEpochSeconds(1_000_000L),
        )
    }
}
