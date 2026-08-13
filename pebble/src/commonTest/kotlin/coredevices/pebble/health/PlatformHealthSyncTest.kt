package coredevices.pebble.health

import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.database.entity.SleepCaptureSampleEntity
import io.rebble.libpebblecommon.datalogging.SleepCaptureProtocol
import io.rebble.libpebblecommon.health.OverlayType
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformHealthSyncTest {

    @Test
    fun correctedWorkout_exportsBeforeOlderIncompleteWorkout() {
        val olderIncompleteWorkout = 100L
        val correctedLaterWorkout = 200L

        assertEquals(
            listOf(correctedLaterWorkout, olderIncompleteWorkout),
            prioritizeBuiltinWorkoutExports(
                workoutIds = listOf(olderIncompleteWorkout, correctedLaterWorkout),
                hasEndCorrection = { it == correctedLaterWorkout },
            ),
        )
    }

    @Test
    fun activitySession_recoversMissingCompletionMarker() {
        assertEquals(
            BuiltinWorkoutEnd(
                epochSeconds = 1_000 + 35 * 60,
                source = BuiltinWorkoutEndSource.ActivitySession,
            ),
            resolveBuiltinWorkoutEnd(
                workoutId = 1_000,
                recordedEndEpochSeconds = 1_000 + 90 * 60,
                terminalEndEpochSeconds = null,
                correctionEndEpochSeconds = null,
                activitySessionDurationSeconds = 35 * 60,
            ),
        )
    }

    @Test
    fun finalHeartRateSample_recoversWhenNoOtherCompletionSourceExists() {
        assertEquals(
            BuiltinWorkoutEnd(
                epochSeconds = 1_000 + 90 * 60 + 1,
                source = BuiltinWorkoutEndSource.FinalHeartRateSample,
            ),
            resolveBuiltinWorkoutEnd(
                workoutId = 1_000,
                recordedEndEpochSeconds = 1_000 + 90 * 60,
                terminalEndEpochSeconds = null,
                correctionEndEpochSeconds = null,
                activitySessionDurationSeconds = null,
            ),
        )
    }

    @Test
    fun gapFreeLegacySleepCapture_recoversCompletionAfterFinalPpi() {
        val markers = legacySleepCaptureCompletionMarkers(
            samples = listOf(
                sleepCapture(sequence = 0, timestamp = 100, type = SleepCaptureProtocol.TYPE_SESSION),
                sleepCapture(sequence = 1, timestamp = 101, type = SleepCaptureProtocol.TYPE_PPI, value = 900),
                sleepCapture(sequence = 2, timestamp = 102, type = SleepCaptureProtocol.TYPE_PPI, value = 920),
            ),
            receivedBeforeEpochSeconds = 200,
            recoveredAtEpochSeconds = 300,
        )

        assertEquals(1, markers.size)
        assertEquals(3, markers.single().sequence)
        assertEquals(103, markers.single().timestampEpochSeconds)
        assertEquals(SleepCaptureProtocol.FLAG_COMPLETE, markers.single().flags)
    }

    @Test
    fun legacySleepCaptureWithSequenceGap_isNotRecovered() {
        assertEquals(
            emptyList(),
            legacySleepCaptureCompletionMarkers(
                samples = listOf(
                    sleepCapture(sequence = 0, timestamp = 100, type = SleepCaptureProtocol.TYPE_SESSION),
                    sleepCapture(sequence = 2, timestamp = 102, type = SleepCaptureProtocol.TYPE_PPI, value = 920),
                ),
                receivedBeforeEpochSeconds = 200,
                recoveredAtEpochSeconds = 300,
            ),
        )
    }

    @Test
    fun sleepCaptureReceivedAfterRecoveryBoundary_isNotRecovered() {
        assertEquals(
            emptyList(),
            legacySleepCaptureCompletionMarkers(
                samples = listOf(
                    sleepCapture(
                        sequence = 0,
                        timestamp = 100,
                        type = SleepCaptureProtocol.TYPE_SESSION,
                        receivedAt = 201,
                    ),
                    sleepCapture(
                        sequence = 1,
                        timestamp = 101,
                        type = SleepCaptureProtocol.TYPE_PPI,
                        value = 900,
                        receivedAt = 201,
                    ),
                ),
                receivedBeforeEpochSeconds = 200,
                recoveredAtEpochSeconds = 300,
            ),
        )
    }

    @Test
    fun sleepContainerWithDeepSubintervals_emitsAlternatingLightAndDeep() {
        // 8h Sleep container with two DeepSleep periods nested inside (the reporter scenario).
        val intervals = computeSleepStageIntervals(
            listOf(
                overlay(start = 0, duration = 28800, type = OverlayType.Sleep),
                overlay(start = 3600, duration = 1800, type = OverlayType.DeepSleep),
                overlay(start = 14400, duration = 3600, type = OverlayType.DeepSleep),
            )
        )
        assertEquals(
            listOf(
                light(0, 3600),
                deep(3600, 5400),
                light(5400, 14400),
                deep(14400, 18000),
                light(18000, 28800),
            ),
            intervals,
        )
    }

    @Test
    fun sleepContainerWithNoDeeps_emitsSingleLight() {
        val intervals = computeSleepStageIntervals(
            listOf(overlay(start = 100, duration = 3600, type = OverlayType.Sleep))
        )
        assertEquals(listOf(light(100, 3700)), intervals)
    }

    @Test
    fun deepFlushWithContainerStart_noEmptyLeadingLight() {
        val intervals = computeSleepStageIntervals(
            listOf(
                overlay(start = 0, duration = 3600, type = OverlayType.Sleep),
                overlay(start = 0, duration = 600, type = OverlayType.DeepSleep),
            )
        )
        assertEquals(listOf(deep(0, 600), light(600, 3600)), intervals)
    }

    @Test
    fun deepFlushWithContainerEnd_noEmptyTrailingLight() {
        val intervals = computeSleepStageIntervals(
            listOf(
                overlay(start = 0, duration = 3600, type = OverlayType.Sleep),
                overlay(start = 3000, duration = 600, type = OverlayType.DeepSleep),
            )
        )
        assertEquals(listOf(light(0, 3000), deep(3000, 3600)), intervals)
    }

    @Test
    fun splitSleep_twoContainers_eachCarvesItsOwnDeep() {
        val intervals = computeSleepStageIntervals(
            listOf(
                overlay(start = 0, duration = 3600, type = OverlayType.Sleep),
                overlay(start = 1000, duration = 500, type = OverlayType.DeepSleep),
                overlay(start = 7200, duration = 3600, type = OverlayType.Sleep),
                overlay(start = 8000, duration = 600, type = OverlayType.DeepSleep),
            )
        )
        assertEquals(
            listOf(
                light(0, 1000),
                deep(1000, 1500),
                light(1500, 3600),
                light(7200, 8000),
                deep(8000, 8600),
                light(8600, 10800),
            ),
            intervals,
        )
    }

    @Test
    fun napContainerWithDeepNap_carvesOutDeep() {
        val intervals = computeSleepStageIntervals(
            listOf(
                overlay(start = 0, duration = 1800, type = OverlayType.Nap),
                overlay(start = 600, duration = 300, type = OverlayType.DeepNap),
            )
        )
        assertEquals(
            listOf(light(0, 600), deep(600, 900), light(900, 1800)),
            intervals,
        )
    }
}

private fun overlay(start: Long, duration: Long, type: OverlayType) = OverlayDataEntity(
    startTime = start,
    duration = duration,
    type = type.value,
    steps = 0,
    restingKiloCalories = 0,
    activeKiloCalories = 0,
    distanceCm = 0,
    offsetUTC = 0,
)

private fun light(start: Long, end: Long) = SleepStageInterval(start, end, isDeep = false)
private fun deep(start: Long, end: Long) = SleepStageInterval(start, end, isDeep = true)

private fun sleepCapture(
    sequence: Long,
    timestamp: Long,
    type: Int,
    value: Int = 0,
    receivedAt: Long = 150,
): SleepCaptureSampleEntity = SleepCaptureSampleEntity(
    recordId = "test:$sequence",
    sessionId = 42,
    sequence = sequence,
    timestampEpochSeconds = timestamp,
    value = value,
    quality = if (type == SleepCaptureProtocol.TYPE_PPI) 4 else -128,
    sampleType = type,
    flags = 0,
    receivedAtEpochSeconds = receivedAt,
)
