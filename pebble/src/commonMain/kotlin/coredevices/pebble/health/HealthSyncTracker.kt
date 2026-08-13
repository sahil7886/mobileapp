package coredevices.pebble.health

import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State shown in the export screen and retained across background retries. */
data class HealthExportStatus(
    val healthPlatformAvailable: Boolean,
    val heartRateAuthorization: HealthWriteAuthorization,
    val workoutAuthorization: HealthWriteAuthorization,
    val hrvAuthorization: HealthWriteAuthorization,
    val lastSuccessfulSyncEpochSeconds: Long,
    val pendingHeartRateRecords: Int,
    val pendingGranularHeartRateRecords: Int,
    val storedBeatToBeatRecords: Int,
    val storedSleepCaptureRecords: Int,
    val pendingOvernightHrvRecords: Int,
    val exportedOvernightHrvRecords: Int,
    val failedHeartRateRecords: Int,
    val failedOvernightHrvRecords: Int,
    val lastError: String?,
    /**
     * The vertical slice asks only for write access.  HealthKit deliberately hides other sources
     * unless the user separately authorizes reading, so a conflict count must remain uninspected.
     */
    val dataSourceConflicts: String = "Not checked (read access is not requested)",
)

/**
 * Tracks the last-synced timestamps per data type and the observable export state.  A checkpoint
 * advances only after the corresponding destination accepted a complete batch.  On iOS, retries
 * are additionally protected by HealthKit sync identifiers in [NativeHeartRateExporter].
 */
class HealthSyncTracker(private val settings: Settings) {

    companion object {
        private const val KEY_ENABLED = "health_platform_sync_enabled"
        private const val KEY_LAST_SYNCED_STEPS = "health_sync_last_steps_timestamp"
        private const val KEY_LAST_SYNCED_HEART_RATE = "health_sync_last_heart_rate_timestamp"
        private const val KEY_LAST_SYNCED_OVERLAY = "health_sync_last_overlay_timestamp"
        private const val KEY_LAST_SUCCESSFUL_EXPORT = "health_sync_last_successful_export_timestamp"
        private const val KEY_FAILED_HEART_RATE_RECORDS = "health_sync_failed_heart_rate_records"
        private const val KEY_FAILED_OVERNIGHT_HRV_RECORDS = "health_sync_failed_overnight_hrv_records"
        private const val KEY_LAST_ERROR = "health_sync_last_error"
        private const val KEY_WORKOUT_END_OVERRIDE_PREFIX = "health_sync_workout_end_override_"
        private const val KEY_WORKOUT_EXPORT_VERSION_PREFIX = "health_sync_workout_export_version_"
        private const val KEY_NATIVE_WORKOUT_EXPORTED_PREFIX = "health_sync_native_workout_exported_"
        private const val KEY_LEGACY_SLEEP_CAPTURE_RECOVERY_CUTOFF =
            "health_sync_legacy_sleep_capture_recovery_cutoff"
    }

    private val _enabled = MutableStateFlow<Boolean>(settings.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _status = MutableStateFlow(snapshot())
    val status: StateFlow<HealthExportStatus> = _status.asStateFlow()

    fun setEnabled(newEnabled: Boolean) {
        _enabled.value = newEnabled
        settings[KEY_ENABLED] = newEnabled
    }

    var lastSyncedStepsTimestamp: Long
        get() = settings.getLong(KEY_LAST_SYNCED_STEPS, 0L)
        set(value) {
            settings[KEY_LAST_SYNCED_STEPS] = value
            publish()
        }

    var lastSyncedHeartRateTimestamp: Long
        get() = settings.getLong(KEY_LAST_SYNCED_HEART_RATE, 0L)
        set(value) {
            settings[KEY_LAST_SYNCED_HEART_RATE] = value
            publish()
        }

    var lastSyncedOverlayTimestamp: Long
        get() = settings.getLong(KEY_LAST_SYNCED_OVERLAY, 0L)
        set(value) {
            settings[KEY_LAST_SYNCED_OVERLAY] = value
            publish()
        }

    fun workoutEndOverride(workoutId: Long): Long? =
        settings.getLong("$KEY_WORKOUT_END_OVERRIDE_PREFIX$workoutId", 0L).takeIf { it > workoutId }

    /**
     * Stores a user correction separately from the raw watch records. Bumping the HealthKit
     * sync version replaces this app's earlier copy of the workout instead of duplicating it.
     */
    fun setWorkoutEndOverride(workoutId: Long, endEpochSeconds: Long) {
        require(endEpochSeconds > workoutId)
        val endKey = "$KEY_WORKOUT_END_OVERRIDE_PREFIX$workoutId"
        if (settings.getLong(endKey, 0L) == endEpochSeconds) return
        settings[endKey] = endEpochSeconds
        val versionKey = "$KEY_WORKOUT_EXPORT_VERSION_PREFIX$workoutId"
        settings[versionKey] = settings.getInt(versionKey, 1) + 1
    }

    fun workoutExportVersion(workoutId: Long): Int =
        settings.getInt("$KEY_WORKOUT_EXPORT_VERSION_PREFIX$workoutId", 1)

    fun hasNativeWorkoutExport(workoutId: Long): Boolean =
        settings.getBoolean("$KEY_NATIVE_WORKOUT_EXPORTED_PREFIX$workoutId", false)

    fun markNativeWorkoutExported(workoutId: Long) {
        settings["$KEY_NATIVE_WORKOUT_EXPORTED_PREFIX$workoutId"] = true
    }

    /**
     * Claims the one-time boundary for recovering streams produced by firmware that could lose
     * their terminal DataLogging marker. Only records already on the phone before this app build
     * are eligible, so an active or future sleep capture can never be completed speculatively.
     */
    fun claimLegacySleepCaptureRecoveryCutoff(nowEpochSeconds: Long): Long? {
        require(nowEpochSeconds > 0)
        if (settings.getLong(KEY_LEGACY_SLEEP_CAPTURE_RECOVERY_CUTOFF, 0L) != 0L) return null
        settings[KEY_LEGACY_SLEEP_CAPTURE_RECOVERY_CUTOFF] = nowEpochSeconds
        return nowEpochSeconds
    }

    fun updateExportStatus(
        healthPlatformAvailable: Boolean = _status.value.healthPlatformAvailable,
        heartRateAuthorization: HealthWriteAuthorization = _status.value.heartRateAuthorization,
        workoutAuthorization: HealthWriteAuthorization = _status.value.workoutAuthorization,
        hrvAuthorization: HealthWriteAuthorization = _status.value.hrvAuthorization,
        pendingHeartRateRecords: Int = _status.value.pendingHeartRateRecords,
        pendingGranularHeartRateRecords: Int = _status.value.pendingGranularHeartRateRecords,
        storedBeatToBeatRecords: Int = _status.value.storedBeatToBeatRecords,
        storedSleepCaptureRecords: Int = _status.value.storedSleepCaptureRecords,
        pendingOvernightHrvRecords: Int = _status.value.pendingOvernightHrvRecords,
        exportedOvernightHrvRecords: Int = _status.value.exportedOvernightHrvRecords,
    ) {
        _status.value = snapshot(
            healthPlatformAvailable = healthPlatformAvailable,
            heartRateAuthorization = heartRateAuthorization,
            workoutAuthorization = workoutAuthorization,
            hrvAuthorization = hrvAuthorization,
            pendingHeartRateRecords = pendingHeartRateRecords,
            pendingGranularHeartRateRecords = pendingGranularHeartRateRecords,
            storedBeatToBeatRecords = storedBeatToBeatRecords,
            storedSleepCaptureRecords = storedSleepCaptureRecords,
            pendingOvernightHrvRecords = pendingOvernightHrvRecords,
            exportedOvernightHrvRecords = exportedOvernightHrvRecords,
        )
    }

    fun recordSuccessfulHeartRateExport(lastTimestamp: Long) {
        settings[KEY_LAST_SUCCESSFUL_EXPORT] = lastTimestamp
        settings[KEY_FAILED_HEART_RATE_RECORDS] = 0
        settings.remove(KEY_LAST_ERROR)
        publish()
    }

    fun recordHeartRateExportFailure(recordCount: Int, error: Throwable) {
        settings[KEY_FAILED_HEART_RATE_RECORDS] =
            settings.getInt(KEY_FAILED_HEART_RATE_RECORDS, 0) + recordCount
        settings[KEY_LAST_ERROR] = error.message ?: error::class.simpleName.orEmpty()
        publish()
    }

    fun recordSuccessfulOvernightHrvExport(lastTimestamp: Long) {
        settings[KEY_LAST_SUCCESSFUL_EXPORT] = lastTimestamp
        settings[KEY_FAILED_OVERNIGHT_HRV_RECORDS] = 0
        settings.remove(KEY_LAST_ERROR)
        publish()
    }

    fun recordOvernightHrvExportFailure(recordCount: Int, error: Throwable) {
        settings[KEY_FAILED_OVERNIGHT_HRV_RECORDS] =
            settings.getInt(KEY_FAILED_OVERNIGHT_HRV_RECORDS, 0) + recordCount
        settings[KEY_LAST_ERROR] = error.message ?: error::class.simpleName.orEmpty()
        publish()
    }

    private fun publish() {
        _status.value = snapshot(
            healthPlatformAvailable = _status.value.healthPlatformAvailable,
            heartRateAuthorization = _status.value.heartRateAuthorization,
            workoutAuthorization = _status.value.workoutAuthorization,
            hrvAuthorization = _status.value.hrvAuthorization,
            pendingHeartRateRecords = _status.value.pendingHeartRateRecords,
            pendingGranularHeartRateRecords = _status.value.pendingGranularHeartRateRecords,
            storedBeatToBeatRecords = _status.value.storedBeatToBeatRecords,
            storedSleepCaptureRecords = _status.value.storedSleepCaptureRecords,
            pendingOvernightHrvRecords = _status.value.pendingOvernightHrvRecords,
            exportedOvernightHrvRecords = _status.value.exportedOvernightHrvRecords,
        )
    }

    private fun snapshot(
        healthPlatformAvailable: Boolean = false,
        heartRateAuthorization: HealthWriteAuthorization = HealthWriteAuthorization.NotDetermined,
        workoutAuthorization: HealthWriteAuthorization = HealthWriteAuthorization.NotDetermined,
        hrvAuthorization: HealthWriteAuthorization = HealthWriteAuthorization.NotDetermined,
        pendingHeartRateRecords: Int = 0,
        pendingGranularHeartRateRecords: Int = 0,
        storedBeatToBeatRecords: Int = 0,
        storedSleepCaptureRecords: Int = 0,
        pendingOvernightHrvRecords: Int = 0,
        exportedOvernightHrvRecords: Int = 0,
    ): HealthExportStatus = HealthExportStatus(
        healthPlatformAvailable = healthPlatformAvailable,
        heartRateAuthorization = heartRateAuthorization,
        workoutAuthorization = workoutAuthorization,
        hrvAuthorization = hrvAuthorization,
        lastSuccessfulSyncEpochSeconds = settings.getLong(KEY_LAST_SUCCESSFUL_EXPORT, 0L),
        pendingHeartRateRecords = pendingHeartRateRecords,
        pendingGranularHeartRateRecords = pendingGranularHeartRateRecords,
        storedBeatToBeatRecords = storedBeatToBeatRecords,
        storedSleepCaptureRecords = storedSleepCaptureRecords,
        pendingOvernightHrvRecords = pendingOvernightHrvRecords,
        exportedOvernightHrvRecords = exportedOvernightHrvRecords,
        failedHeartRateRecords = settings.getInt(KEY_FAILED_HEART_RATE_RECORDS, 0),
        failedOvernightHrvRecords = settings.getInt(KEY_FAILED_OVERNIGHT_HRV_RECORDS, 0),
        lastError = settings.getStringOrNull(KEY_LAST_ERROR),
    )
}
