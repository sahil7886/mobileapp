package coredevices.pebble.health

/**
 * A heart-rate point as it was stored by the watch-health Datalogging stream.
 *
 * [timestampSeconds] is also the sequence/checkpoint used by the current Pebble system-health
 * stream: its Room table has one canonical record per timestamp.  The stable [syncIdentifier]
 * makes a replay after a process death safe on platforms that support native sync identifiers.
 */
data class HeartRateExportSample(
    val timestampSeconds: Long,
    val beatsPerMinute: Int,
    /** Stable source identity; minute health records retain their historic timestamp-only value. */
    val sourceRecordId: String = timestampSeconds.toString(),
    /** Increments only when this exact logical sample needs to replace an earlier export. */
    val syncVersion: Int = 1,
) {
    init {
        require(timestampSeconds > 0) { "Heart-rate timestamp must be positive" }
        require(beatsPerMinute in 1..300) { "Heart rate must be between 1 and 300 bpm" }
        require(sourceRecordId.isNotBlank()) { "Heart-rate source record ID must not be blank" }
        require(syncVersion >= 1) { "Heart-rate sync version must be positive" }
    }

    val syncIdentifier: String
        get() = "coredevices.pebble.health.hr.v1.$sourceRecordId"
}

enum class HealthWriteAuthorization {
    /** This platform uses the existing cross-platform health writer. */
    NotApplicable,
    /** HealthKit is not available on this device. */
    Unavailable,
    /** The user has not yet made a sharing decision. */
    NotDetermined,
    /** The user denied permission for this app to save heart-rate samples. */
    Denied,
    /** The user allowed this app to save heart-rate samples. */
    Authorized,
}

data class HeartRateExportWriteResult(
    val writtenRecords: Int,
)

enum class WorkoutHeartRateExportType {
    Walking,
    Running,
    Other,
}

/** A completed Pebble Workout plus its one-second heart-rate samples. */
data class WorkoutHeartRateExport(
    /** Stable identity shared by the PebbleOS session, local database, and HealthKit metadata. */
    val sourceRecordId: String,
    val type: WorkoutHeartRateExportType,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val heartRateSamples: List<HeartRateExportSample>,
    /** HealthKit replaces an earlier workout with the same identity when this version rises. */
    val syncVersion: Int = 1,
) {
    init {
        require(sourceRecordId.isNotBlank())
        require(startEpochSeconds > 0)
        require(endEpochSeconds > startEpochSeconds)
        require(heartRateSamples.all {
            it.timestampSeconds in startEpochSeconds..endEpochSeconds
        }) { "Workout heart-rate samples must be within the workout bounds" }
        require(syncVersion >= 1) { "Workout sync version must be positive" }
    }

    val syncIdentifier: String
        get() = "coredevices.pebble.health.workout.v1.$sourceRecordId"
}

data class WorkoutHeartRateExportWriteResult(
    val writtenHeartRateRecords: Int,
)

/** One quality-filtered, five-minute SDNN aggregate from an overnight Pebble PPI session. */
data class OvernightHrvExport(
    /** Stable local identity, persisted with the calculation provenance. */
    val sourceRecordId: String,
    val windowStartEpochSeconds: Long,
    val windowEndEpochSeconds: Long,
    /** HealthKit's HRV unit: milliseconds. This is SDNN, never RMSSD. */
    val sdnnMilliseconds: Double,
    val algorithmVersion: Int,
) {
    init {
        require(sourceRecordId.isNotBlank())
        require(windowStartEpochSeconds > 0)
        require(windowEndEpochSeconds > windowStartEpochSeconds)
        require(sdnnMilliseconds.isFinite() && sdnnMilliseconds >= 0.0)
        require(algorithmVersion > 0)
    }

    val syncIdentifier: String
        get() = "coredevices.pebble.health.hrv.sdnn.v1.$sourceRecordId"
}

data class OvernightHrvExportWriteResult(
    val writtenRecords: Int,
)

/**
 * Platform-native writer for the small set of records where the platform API has guarantees the
 * KMP abstraction cannot express.  On iOS this writes point samples with HealthKit's sync
 * identifier/version metadata; Android continues through HealthKMP/Health Connect.
 */
internal expect class NativeHeartRateExporter() {
    val isActive: Boolean

    fun isAvailable(): Boolean

    fun authorization(): HealthWriteAuthorization

    /** Apple Health sharing state for workouts that contain detailed heart-rate samples. */
    fun workoutAuthorization(): HealthWriteAuthorization

    /** Apple Health sharing state for derived SDNN HRV values. */
    fun hrvAuthorization(): HealthWriteAuthorization

    suspend fun requestAuthorization(): Result<Boolean>

    suspend fun write(samples: List<HeartRateExportSample>): Result<HeartRateExportWriteResult>

    /** Creates one native workout with its detailed samples attached where the platform supports it. */
    suspend fun writeWorkout(
        workout: WorkoutHeartRateExport,
    ): Result<WorkoutHeartRateExportWriteResult>

    /** Writes quality-filtered SDNN aggregates, never raw PPI/RR intervals. */
    suspend fun writeOvernightHrv(
        samples: List<OvernightHrvExport>,
    ): Result<OvernightHrvExportWriteResult>
}
