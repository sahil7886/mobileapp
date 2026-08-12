package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.database.entity.SleepCaptureSampleEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OvernightHrvCalculatorTest {
    @Test
    fun `calculates SDNN only from accepted five minute PPI windows`() {
        val sessionId = 44L
        val start = 1_700_000_100L
        val samples = (0 until 300).map { index ->
            ppi(
                sessionId = sessionId,
                sequence = index.toLong(),
                timestamp = start + index,
                intervalMs = if (index % 2 == 0) 950 else 1_050,
                quality = 3,
            )
        }

        val result = OvernightHrvCalculator.calculate(sessionId, samples)

        assertEquals(1, result.size)
        assertEquals("overnight-hrv-sdnn:44:1700000100", result.single().recordId)
        assertEquals(50.0, result.single().sdnnMilliseconds, absoluteTolerance = 0.0001)
        assertEquals(100, result.single().qualityCoveragePercent)
        assertEquals(100, result.single().temporalCoveragePercent)
        assertEquals(300, result.single().qualityAcceptedSampleCount)
    }

    @Test
    fun `rejects poor quality and isolated PPG artifacts`() {
        val sessionId = 55L
        val start = 1_700_001_000L
        val samples = (0 until 300).map { index ->
            ppi(
                sessionId = sessionId,
                sequence = index.toLong(),
                timestamp = start + index,
                intervalMs = when (index) {
                    100 -> 1_800 // Fails the adjacent-change PPG artifact check.
                    else -> 1_000
                },
                quality = if (index == 20) 1 else 3,
            )
        }

        val result = OvernightHrvCalculator.calculate(sessionId, samples)

        assertEquals(1, result.size)
        assertEquals(1, result.single().artifactRejectedSampleCount)
        assertEquals(0.0, result.single().sdnnMilliseconds, absoluteTolerance = 0.0001)
        assertTrue(result.single().qualityCoveragePercent >= 80)
    }

    @Test
    fun `does not create an SDNN value from sparse data`() {
        val sessionId = 66L
        val samples = (0 until 100).map { index ->
            ppi(sessionId, index.toLong(), 1_700_002_000L + index, 1_000, 3)
        }

        assertTrue(OvernightHrvCalculator.calculate(sessionId, samples).isEmpty())
    }

    @Test
    fun `does not round a subthreshold quality ratio up to acceptance`() {
        val sessionId = 77L
        val start = 1_700_003_100L
        val samples = (0 until 300).map { index ->
            ppi(
                sessionId = sessionId,
                sequence = index.toLong(),
                timestamp = start + index,
                intervalMs = 1_000,
                quality = if (index < 239) 3 else 1,
            )
        }

        // 239 / 300 is 79.67%; a rounded display percentage would be 80, but it is insufficient.
        assertTrue(OvernightHrvCalculator.calculate(sessionId, samples).isEmpty())
    }

    private fun ppi(
        sessionId: Long,
        sequence: Long,
        timestamp: Long,
        intervalMs: Int,
        quality: Int,
    ) = SleepCaptureSampleEntity(
        recordId = "$sessionId:$sequence",
        sessionId = sessionId,
        sequence = sequence,
        timestampEpochSeconds = timestamp,
        value = intervalMs,
        quality = quality,
        sampleType = 1,
        flags = 0,
        receivedAtEpochSeconds = timestamp,
    )
}
