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
    val lastSuccessfulSyncEpochSeconds: Long,
    val pendingHeartRateRecords: Int,
    val failedHeartRateRecords: Int,
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
        private const val KEY_LAST_ERROR = "health_sync_last_error"
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

    fun updateExportStatus(
        healthPlatformAvailable: Boolean = _status.value.healthPlatformAvailable,
        heartRateAuthorization: HealthWriteAuthorization = _status.value.heartRateAuthorization,
        pendingHeartRateRecords: Int = _status.value.pendingHeartRateRecords,
    ) {
        _status.value = snapshot(
            healthPlatformAvailable = healthPlatformAvailable,
            heartRateAuthorization = heartRateAuthorization,
            pendingHeartRateRecords = pendingHeartRateRecords,
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

    private fun publish() {
        _status.value = snapshot(
            healthPlatformAvailable = _status.value.healthPlatformAvailable,
            heartRateAuthorization = _status.value.heartRateAuthorization,
            pendingHeartRateRecords = _status.value.pendingHeartRateRecords,
        )
    }

    private fun snapshot(
        healthPlatformAvailable: Boolean = false,
        heartRateAuthorization: HealthWriteAuthorization = HealthWriteAuthorization.NotDetermined,
        pendingHeartRateRecords: Int = 0,
    ): HealthExportStatus = HealthExportStatus(
        healthPlatformAvailable = healthPlatformAvailable,
        heartRateAuthorization = heartRateAuthorization,
        lastSuccessfulSyncEpochSeconds = settings.getLong(KEY_LAST_SUCCESSFUL_EXPORT, 0L),
        pendingHeartRateRecords = pendingHeartRateRecords,
        failedHeartRateRecords = settings.getInt(KEY_FAILED_HEART_RATE_RECORDS, 0),
        lastError = settings.getStringOrNull(KEY_LAST_ERROR),
    )
}
