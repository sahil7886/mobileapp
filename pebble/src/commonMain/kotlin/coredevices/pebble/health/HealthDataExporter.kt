package coredevices.pebble.health

import co.touchlab.kermit.Logger
import com.oldguy.common.io.File
import com.oldguy.common.io.FileMode
import com.oldguy.common.io.ZipEntry
import com.oldguy.common.io.ZipFile
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.BeatToBeatEntity
import io.rebble.libpebblecommon.database.entity.GranularHeartRateEntity
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.database.entity.SleepCaptureSampleEntity
import io.rebble.libpebblecommon.datalogging.BuiltinWorkoutHeartRateProtocol
import io.rebble.libpebblecommon.health.OverlayType
import io.rebble.libpebblecommon.util.getTempFilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Instant

/** The three supported, rolling UTC windows for a local Pebble health export. */
enum class HealthDataExportPeriod(
    val displayName: String,
    val fileToken: String,
    private val rollingDays: Long,
) {
    Past7Days("Past 7 days", "7d", 7),
    Past30Days("Past 30 days", "30d", 30),
    // A fixed 180-day window makes the range unambiguous and independent of the phone's timezone.
    Past6Months("Past 6 months", "6mo", 180),
    ;

    fun startEpochSeconds(endEpochSeconds: Long): Long =
        (endEpochSeconds - rollingDays * SECONDS_PER_DAY).coerceAtLeast(0)

    companion object {
        private const val SECONDS_PER_DAY = 24L * 60L * 60L
    }
}

/** A shareable, local-only archive produced by [HealthDataExporter]. */
data class HealthDataExport(
    val fileName: String,
    val file: Path,
    val period: HealthDataExportPeriod,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val minuteHealthRecords: Int,
    val granularHeartRateRecords: Int,
    val beatToBeatRecords: Int,
    val sleepCaptureRecords: Int,
    val overlayRecords: Int,
)

/**
 * Creates a portable ZIP without uploading any health data.  Each CSV is written to the ZIP in
 * small text blocks; the archive never builds one giant CSV string in memory.
 *
 * The local database intentionally stores only data received from Pebble.  It therefore does not
 * export a second copy of Apple Health data. PPI values are emitted only when the Time 2 firmware
 * has supplied accepted intervals; the exporter never derives them from BPM.
 */
class HealthDataExporter(
    private val libPebble: LibPebble,
    private val appContext: AppContext,
    private val clock: Clock,
) {
    private val logger = Logger.withTag("HealthDataExport")

    suspend fun export(period: HealthDataExportPeriod): HealthDataExport =
        withContext(Dispatchers.Default) {
            try {
                val endEpochSeconds = clock.now().epochSeconds
                val startEpochSeconds = period.startEpochSeconds(endEpochSeconds)

                // Fetch all three persisted Pebble health streams.  Raw-only workout records are
                // included even though the Apple Health writer quite correctly excludes them.
                val minuteHealth = libPebble.getHealthDataForRange(startEpochSeconds, endEpochSeconds)
                val granularHeartRate = libPebble.getGranularHeartRateForRange(startEpochSeconds, endEpochSeconds)
                val beatToBeat = libPebble.getBeatToBeatForRange(startEpochSeconds, endEpochSeconds)
                val sleepCapture = libPebble.getSleepCaptureSamplesForRange(startEpochSeconds, endEpochSeconds)
                val overlays = libPebble.getOverlayEntriesForRange(startEpochSeconds, endEpochSeconds)

                val fileName = "pebble-health-${clock.now().toEpochMilliseconds()}-${period.fileToken}.zip"
                val destination = getTempFilePath(appContext, fileName, "health-exports")

                logger.i {
                    "HEALTH_DATA_EXPORT creating period=${period.fileToken} start=$startEpochSeconds " +
                        "end=$endEpochSeconds minute=${minuteHealth.size} granular=${granularHeartRate.size} " +
                        "ppi=${beatToBeat.size} sleepCapture=${sleepCapture.size} overlays=${overlays.size}"
                }

                ZipFile(File(destination.toString()), mode = FileMode.Write).use { zip ->
                    zip.addCsvEntry(
                        name = MINUTE_HEALTH_FILE,
                        header = HealthDataExportCsv.MINUTE_HEALTH_HEADER,
                        rows = minuteHealth.iterator(),
                        formatter = HealthDataExportCsv::minuteHealthRow,
                    )
                    zip.addCsvEntry(
                        name = GRANULAR_HEART_RATE_FILE,
                        header = HealthDataExportCsv.GRANULAR_HEART_RATE_HEADER,
                        rows = granularHeartRate.iterator(),
                        formatter = HealthDataExportCsv::granularHeartRateRow,
                    )
                    zip.addCsvEntry(
                        name = OVERLAYS_FILE,
                        header = HealthDataExportCsv.OVERLAYS_HEADER,
                        rows = overlays.iterator(),
                        formatter = HealthDataExportCsv::overlayRow,
                    )
                    zip.addCsvEntry(
                        name = BEAT_TO_BEAT_FILE,
                        header = HealthDataExportCsv.BEAT_TO_BEAT_HEADER,
                        rows = beatToBeat.iterator(),
                        formatter = HealthDataExportCsv::beatToBeatRow,
                    )
                    zip.addCsvEntry(
                        name = SLEEP_CAPTURE_FILE,
                        header = HealthDataExportCsv.SLEEP_CAPTURE_HEADER,
                        rows = sleepCapture.iterator(),
                        formatter = HealthDataExportCsv::sleepCaptureRow,
                    )
                    zip.addSingleTextEntry(
                        MANIFEST_FILE,
                        HealthDataExportCsv.manifest(
                            period = period,
                            startEpochSeconds = startEpochSeconds,
                            endEpochSeconds = endEpochSeconds,
                            minuteHealthRecords = minuteHealth.size,
                            granularHeartRateRecords = granularHeartRate.size,
                            beatToBeatRecords = beatToBeat.size,
                            sleepCaptureRecords = sleepCapture.size,
                            overlayRecords = overlays.size,
                        ),
                    )
                    zip.addSingleTextEntry(
                        README_FILE,
                        HealthDataExportCsv.readme(
                            period = period,
                            startEpochSeconds = startEpochSeconds,
                            endEpochSeconds = endEpochSeconds,
                        ),
                    )
                }

                logger.i { "HEALTH_DATA_EXPORT ready file=$fileName" }
                HealthDataExport(
                    fileName = fileName,
                    file = destination,
                    period = period,
                    startEpochSeconds = startEpochSeconds,
                    endEpochSeconds = endEpochSeconds,
                    minuteHealthRecords = minuteHealth.size,
                    granularHeartRateRecords = granularHeartRate.size,
                    beatToBeatRecords = beatToBeat.size,
                    sleepCaptureRecords = sleepCapture.size,
                    overlayRecords = overlays.size,
                )
            } catch (error: Throwable) {
                logger.e(error) { "HEALTH_DATA_EXPORT failed period=${period.fileToken}" }
                throw error
            }
        }

    /** Streams a header and a bounded number of complete records on every ZIP callback. */
    private suspend fun <T> ZipFile.addCsvEntry(
        name: String,
        header: String,
        rows: Iterator<T>,
        formatter: (T) -> String,
    ) {
        var sendHeader = true
        addTextEntry(ZipEntry(name), appendEol = false) {
            when {
                sendHeader -> {
                    sendHeader = false
                    "$header\n"
                }

                !rows.hasNext() -> ""

                else -> buildString {
                    repeat(CSV_ROWS_PER_BLOCK) {
                        if (!rows.hasNext()) return@buildString
                        append(formatter(rows.next()))
                        append('\n')
                    }
                }
            }
        }
    }

    private suspend fun ZipFile.addSingleTextEntry(name: String, content: String) {
        var sent = false
        addTextEntry(ZipEntry(name), appendEol = false) {
            if (sent) "" else content.also { sent = true }
        }
    }

    private companion object {
        const val CSV_ROWS_PER_BLOCK = 128
        const val MINUTE_HEALTH_FILE = "minute_health.csv"
        const val GRANULAR_HEART_RATE_FILE = "workout_heart_rate.csv"
        const val OVERLAYS_FILE = "sleep_and_activity.csv"
        const val BEAT_TO_BEAT_FILE = "beat_to_beat.csv"
        const val SLEEP_CAPTURE_FILE = "sleep_capture.csv"
        const val MANIFEST_FILE = "manifest.json"
        const val README_FILE = "README.txt"
    }
}

/** CSV/manifest rendering is kept separate so column names and escaping can be unit-tested. */
internal object HealthDataExportCsv {
    const val MINUTE_HEALTH_HEADER =
        "timestamp_utc,epoch_seconds,steps,orientation,intensity,light_intensity,active_minutes," +
            "resting_gram_calories,active_gram_calories,distance_cm,heart_rate_bpm," +
            "heart_rate_zone,heart_rate_weight"
    const val GRANULAR_HEART_RATE_HEADER =
        "timestamp_utc,epoch_seconds,workout_id,sequence,filtered_bpm,raw_bpm,flags,sensor_quality," +
            "received_at_utc,received_at_epoch_seconds,exported_to_apple_health,record_id"
    const val OVERLAYS_HEADER =
        "start_utc,start_epoch_seconds,end_utc,end_epoch_seconds,duration_seconds,type,type_name," +
            "offset_utc_seconds,steps,resting_kilocalories,active_kilocalories,distance_cm"
    const val BEAT_TO_BEAT_HEADER =
        "timestamp_utc,epoch_seconds,workout_id,sequence,interval_ms,sensor_quality,flags," +
            "received_at_utc,received_at_epoch_seconds,record_id,source"
    const val SLEEP_CAPTURE_HEADER =
        "timestamp_utc,epoch_seconds,capture_session_id,sequence,sample_type,value,quality,flags," +
            "received_at_utc,received_at_epoch_seconds,record_id,source"

    fun minuteHealthRow(row: HealthDataEntity): String = csvRow(
        timestamp(row.timestamp), row.timestamp, row.steps, row.orientation, row.intensity,
        row.lightIntensity, row.activeMinutes, row.restingGramCalories, row.activeGramCalories,
        row.distanceCm, row.heartRate, row.heartRateZone, row.heartRateWeight,
    )

    fun granularHeartRateRow(row: GranularHeartRateEntity): String = csvRow(
        timestamp(row.timestampEpochSeconds), row.timestampEpochSeconds, row.workoutId, row.sequence,
        row.filteredBpm, row.rawBpm, row.flags and 0xff,
        BuiltinWorkoutHeartRateProtocol.sensorQuality(row) ?: "", timestamp(row.receivedAtEpochSeconds),
        row.receivedAtEpochSeconds, row.exportedToAppleHealth, row.recordId,
    )

    fun beatToBeatRow(row: BeatToBeatEntity): String = csvRow(
        timestamp(row.timestampEpochSeconds), row.timestampEpochSeconds, row.workoutId, row.sequence,
        row.intervalMs, row.quality, row.flags, timestamp(row.receivedAtEpochSeconds),
        row.receivedAtEpochSeconds, row.recordId, "builtin_workout_ppi",
    )

    fun sleepCaptureRow(row: SleepCaptureSampleEntity): String = csvRow(
        timestamp(row.timestampEpochSeconds), row.timestampEpochSeconds, row.sessionId, row.sequence,
        sleepCaptureTypeName(row.sampleType), row.value, row.quality, row.flags,
        timestamp(row.receivedAtEpochSeconds), row.receivedAtEpochSeconds, row.recordId,
        "system_activity_sleep_capture",
    )

    fun overlayRow(row: OverlayDataEntity): String {
        val end = row.startTime + row.duration
        return csvRow(
            timestamp(row.startTime), row.startTime, timestamp(end), end, row.duration, row.type,
            OverlayType.fromValue(row.type)?.name ?: "Unknown(${row.type})", row.offsetUTC,
            row.steps, row.restingKiloCalories, row.activeKiloCalories, row.distanceCm,
        )
    }

    fun manifest(
        period: HealthDataExportPeriod,
        startEpochSeconds: Long,
        endEpochSeconds: Long,
        minuteHealthRecords: Int,
        granularHeartRateRecords: Int,
        beatToBeatRecords: Int,
        sleepCaptureRecords: Int,
        overlayRecords: Int,
    ): String = """
        {
          "format_version": 1,
          "period": "${period.fileToken}",
          "range_start_utc": "${timestamp(startEpochSeconds)}",
          "range_end_utc_exclusive": "${timestamp(endEpochSeconds)}",
          "minute_health_records": $minuteHealthRecords,
          "workout_heart_rate_records": $granularHeartRateRecords,
          "sleep_and_activity_records": $overlayRecords,
          "beat_to_beat_records": $beatToBeatRecords,
          "beat_to_beat_status": "accepted_ppi_from_builtin_workout",
          "sleep_capture_records": $sleepCaptureRecords,
          "sleep_capture_status": "raw_ppi_bpm_quality_and_30_second_motion_inputs"
        }
    """.trimIndent() + "\n"

    fun readme(
        period: HealthDataExportPeriod,
        startEpochSeconds: Long,
        endEpochSeconds: Long,
    ): String = """
        Pebble Health Data Export
        =========================

        This archive was created locally on this phone. No health data was uploaded to create it.

        Selected period: ${period.displayName} (rolling UTC range)
        Range start (inclusive): ${timestamp(startEpochSeconds)}
        Range end (exclusive): ${timestamp(endEpochSeconds)}

        Files
        -----
        minute_health.csv
          One persisted Pebble system-health minute record per row. Heart-rate values can be zero
          when no system-health HR reading was stored for that minute; rows do not invent samples.

        workout_heart_rate.csv
          High-resolution records received from Health Capture or the built-in Workout service. It
          preserves the watch workout ID, persistent sequence, BPM, flags, sensor quality when
          supplied by the built-in service, receipt time, database ID, and Apple Health export
          status. Raw BPM is a diagnostic sensor field, not a beat-to-beat interval and not a
          clinical measurement.

        sleep_and_activity.csv
          All persisted Pebble overlay intervals that overlap the selected range, including sleep,
          deep sleep, naps, walking, running, and open workouts. An interval may start before the
          selected range when it continues into it (for example, an overnight sleep session).

        beat_to_beat.csv
          Every accepted PPI/RR interval received from the Time 2 during a built-in Workout. The
          interval is in milliseconds and preserves the watch timestamp, stable sequence, and
          sensor quality. It is wrist-PPG algorithm output, not ECG data, and a raw PPI value is
          not itself an Apple Health SDNN result.

        sleep_capture.csv
          Raw overnight classifier inputs from the system Activity service: accepted wrist-PPG PPI,
          BPM/quality snapshots every 30 seconds, 30-second motion-energy summaries, and session completion/drop
          diagnostics. PPI timestamps are watch receipt seconds, not invented millisecond beats.
          This file is local raw data; it does not assert sleep stages or write PPI directly to
          Apple Health.

        manifest.json
          Machine-readable export range, record counts, and beat-to-beat availability.

        All timestamps are UTC ISO-8601 with their original epoch-second value in the adjacent
        column. This export represents health data received from Pebble and stored by this app; it
        is not an export of Apple Health's records or a claim that every sensor-internal reading
        was made available by the watch API.
    """.trimIndent() + "\n"

    internal fun csvRow(vararg values: Any?): String = values.joinToString(",") { value ->
        val text = value?.toString().orEmpty()
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${text.replace("\"", "\"\"")}\""
        } else {
            text
        }
    }

    private fun timestamp(epochSeconds: Long): String = Instant.fromEpochSeconds(epochSeconds).toString()

    private fun sleepCaptureTypeName(type: Int): String = when (type) {
        1 -> "ppi"
        2 -> "bpm"
        3 -> "motion_epoch"
        4 -> "session"
        else -> "unknown($type)"
    }
}
