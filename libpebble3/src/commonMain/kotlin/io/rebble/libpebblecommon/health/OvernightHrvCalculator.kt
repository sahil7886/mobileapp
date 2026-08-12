package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.database.entity.SleepCaptureSampleEntity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Conservative, deterministic conversion from Pebble's overnight wrist-PPG PPI stream to SDNN.
 *
 * HealthKit's HRV type is SDNN, not raw beat-to-beat input. We therefore retain raw intervals
 * locally and emit an aggregate only after the watch marks the capture session complete. This is
 * intentionally a quality gate, not a medical rhythm classifier: it rejects off-wrist/poor sensor
 * quality, physiologically implausible intervals and abrupt isolated PPG artifacts.
 */
object OvernightHrvCalculator {
    const val ALGORITHM_VERSION = 1
    const val WINDOW_SECONDS = 5 * 60L

    private const val PPI_SAMPLE_TYPE = 1
    // PebbleOS HRMQuality: Worst=0, Poor=1, Acceptable=2, Good=3, Excellent=4.
    private const val MIN_ACCEPTABLE_QUALITY = 2
    private const val MIN_INTERVAL_MS = 300
    private const val MAX_INTERVAL_MS = 2_000
    private const val MIN_ACCEPTED_INTERVALS = 180
    private const val MIN_QUALITY_COVERAGE_PERCENT = 80
    private const val MIN_TEMPORAL_COVERAGE_PERCENT = 75
    private const val MAX_RELATIVE_ADJACENT_CHANGE = 0.20

    data class Calculation(
        val recordId: String,
        val sessionId: Long,
        val windowStartEpochSeconds: Long,
        val windowEndEpochSeconds: Long,
        val sdnnMilliseconds: Double,
        val sourcePpiSampleCount: Int,
        val qualityAcceptedSampleCount: Int,
        val artifactRejectedSampleCount: Int,
        val qualityCoveragePercent: Int,
        val temporalCoveragePercent: Int,
        val algorithmVersion: Int = ALGORITHM_VERSION,
    )

    /**
     * Returns only complete, five-minute windows that have enough usable PPI coverage to make a
     * defensible SDNN estimate.  The caller is responsible for passing one *completed* session.
     */
    fun calculate(sessionId: Long, samples: List<SleepCaptureSampleEntity>): List<Calculation> {
        val ppi = samples.asSequence()
            .filter { it.sessionId == sessionId && it.sampleType == PPI_SAMPLE_TYPE }
            .sortedWith(compareBy<SleepCaptureSampleEntity>(
                { it.timestampEpochSeconds },
                { it.sequence },
                { it.recordId },
            ))
            .toList()
        if (ppi.isEmpty()) return emptyList()

        val firstWindow = floorToWindow(ppi.first().timestampEpochSeconds)
        val lastWindow = floorToWindow(ppi.last().timestampEpochSeconds)
        return generateSequence(firstWindow) { it + WINDOW_SECONDS }
            .takeWhile { it <= lastWindow }
            .mapNotNull { windowStart -> calculateWindow(sessionId, windowStart, ppi) }
            .toList()
    }

    private fun calculateWindow(
        sessionId: Long,
        windowStart: Long,
        allPpi: List<SleepCaptureSampleEntity>,
    ): Calculation? {
        val windowEnd = windowStart + WINDOW_SECONDS
        val source = allPpi.filter {
            it.timestampEpochSeconds >= windowStart && it.timestampEpochSeconds < windowEnd
        }
        if (source.isEmpty()) return null

        val qualityAccepted = source.filter {
            it.quality >= MIN_ACCEPTABLE_QUALITY && it.value in MIN_INTERVAL_MS..MAX_INTERVAL_MS
        }
        val qualityCoverage = percentage(qualityAccepted.size.toLong(), source.size.toLong())
        if (
            !meetsMinimumPercentage(
                qualityAccepted.size.toLong(),
                source.size.toLong(),
                MIN_QUALITY_COVERAGE_PERCENT,
            )
        ) return null

        val accepted = ArrayList<Int>(qualityAccepted.size)
        var artifactRejected = 0
        qualityAccepted.forEach { sample ->
            val previous = accepted.lastOrNull()
            if (previous != null &&
                abs(sample.value - previous).toDouble() / previous > MAX_RELATIVE_ADJACENT_CHANGE
            ) {
                artifactRejected++
            } else {
                accepted += sample.value
            }
        }
        if (accepted.size < MIN_ACCEPTED_INTERVALS) return null

        val acceptedDurationMs = accepted.sum().toLong()
        val temporalCoverage = percentage(acceptedDurationMs, WINDOW_SECONDS * 1_000)
        if (
            !meetsMinimumPercentage(
                acceptedDurationMs,
                WINDOW_SECONDS * 1_000,
                MIN_TEMPORAL_COVERAGE_PERCENT,
            )
        ) return null

        val mean = accepted.average()
        val sdnn = sqrt(accepted.sumOf { value ->
            val deviation = value - mean
            deviation * deviation
        } / accepted.size)
        if (!sdnn.isFinite()) return null

        return Calculation(
            recordId = recordId(sessionId, windowStart),
            sessionId = sessionId,
            windowStartEpochSeconds = windowStart,
            windowEndEpochSeconds = windowEnd,
            sdnnMilliseconds = sdnn,
            sourcePpiSampleCount = source.size,
            qualityAcceptedSampleCount = qualityAccepted.size,
            artifactRejectedSampleCount = artifactRejected,
            qualityCoveragePercent = qualityCoverage,
            temporalCoveragePercent = temporalCoverage,
        )
    }

    fun recordId(sessionId: Long, windowStartEpochSeconds: Long): String =
        "overnight-hrv-sdnn:$sessionId:$windowStartEpochSeconds"

    private fun floorToWindow(timestampSeconds: Long): Long =
        timestampSeconds - timestampSeconds.mod(WINDOW_SECONDS)

    private fun percentage(numerator: Long, denominator: Long): Int {
        if (denominator <= 0L) return 0
        return ((numerator.toDouble() * 100.0) / denominator).roundToInt().coerceIn(0, 100)
    }

    private fun meetsMinimumPercentage(numerator: Long, denominator: Long, minimum: Int): Boolean =
        denominator > 0L && numerator * 100L >= denominator * minimum
}
