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
) {
    init {
        require(timestampSeconds > 0) { "Heart-rate timestamp must be positive" }
        require(beatsPerMinute in 1..300) { "Heart rate must be between 1 and 300 bpm" }
        require(sourceRecordId.isNotBlank()) { "Heart-rate source record ID must not be blank" }
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

/**
 * Platform-native writer for the small set of records where the platform API has guarantees the
 * KMP abstraction cannot express.  On iOS this writes point samples with HealthKit's sync
 * identifier/version metadata; Android continues through HealthKMP/Health Connect.
 */
internal expect class NativeHeartRateExporter() {
    val isActive: Boolean

    fun isAvailable(): Boolean

    fun authorization(): HealthWriteAuthorization

    suspend fun requestAuthorization(): Result<Boolean>

    suspend fun write(samples: List<HeartRateExportSample>): Result<HeartRateExportWriteResult>
}
