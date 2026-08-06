@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package coredevices.pebble.health

import co.touchlab.kermit.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.toNSDate
import platform.Foundation.NSError
import platform.HealthKit.HKAuthorizationStatusNotDetermined
import platform.HealthKit.HKAuthorizationStatusSharingAuthorized
import platform.HealthKit.HKAuthorizationStatusSharingDenied
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierHeartRate
import platform.HealthKit.HKUnit
import platform.HealthKit.countUnit
import platform.HealthKit.minuteUnit
import platform.HealthKit.unitDividedByUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Instant

/**
 * Writes one discrete HKQuantitySample per Pebble sample.  HealthKit automatically attributes
 * each saved object to this app's HKSourceRevision; a source cannot be assigned by a client.
 *
 * The sync identifier/version pair is intentionally attached to *every* sample.  Retrying a
 * batch after a Bluetooth retry or app termination is therefore an update/no-op rather than a
 * second heart-rate point in Apple Health.
 */
internal actual class NativeHeartRateExporter {
    private val logger = Logger.withTag("HealthKitHeartRate")
    private val healthStore = HKHealthStore()
    private val heartRateType = HKQuantityType.quantityTypeForIdentifier(
        HKQuantityTypeIdentifierHeartRate,
    )

    actual val isActive: Boolean = true

    actual fun isAvailable(): Boolean =
        HKHealthStore.isHealthDataAvailable() && heartRateType != null

    actual fun authorization(): HealthWriteAuthorization {
        val type = heartRateType ?: return HealthWriteAuthorization.Unavailable
        if (!HKHealthStore.isHealthDataAvailable()) return HealthWriteAuthorization.Unavailable
        return when (healthStore.authorizationStatusForType(type)) {
            HKAuthorizationStatusSharingAuthorized -> HealthWriteAuthorization.Authorized
            HKAuthorizationStatusSharingDenied -> HealthWriteAuthorization.Denied
            HKAuthorizationStatusNotDetermined -> HealthWriteAuthorization.NotDetermined
            else -> HealthWriteAuthorization.NotDetermined
        }
    }

    actual suspend fun requestAuthorization(): Result<Boolean> = runCatching {
        val type = heartRateType ?: error("Heart-rate HealthKit type is unavailable")
        if (!HKHealthStore.isHealthDataAvailable()) error("HealthKit is unavailable")

        suspendCancellableCoroutine { continuation ->
            healthStore.requestAuthorizationToShareTypes(
                typesToShare = setOf(type),
                readTypes = emptySet(),
            ) { _, error ->
                if (continuation.isCancelled) return@requestAuthorizationToShareTypes
                if (error != null) {
                    continuation.resumeWithException(error.asException())
                } else {
                    continuation.resume(authorization() == HealthWriteAuthorization.Authorized)
                }
            }
        }
    }

    actual suspend fun write(
        samples: List<HeartRateExportSample>,
    ): Result<HeartRateExportWriteResult> = runCatching {
        require(isAvailable()) { "HealthKit heart-rate export is unavailable" }
        require(authorization() == HealthWriteAuthorization.Authorized) {
            "Apple Health heart-rate sharing is not authorized"
        }

        samples.chunked(MAX_RECORDS_PER_SAVE).forEach { batch ->
            val objects = batch.map(::toHealthKitSample)
            save(objects)
            logger.d {
                "HEALTHKIT_HR saved=${objects.size} first=${batch.first().timestampSeconds} " +
                    "last=${batch.last().timestampSeconds}"
            }
        }
        HeartRateExportWriteResult(writtenRecords = samples.size)
    }

    private fun toHealthKitSample(sample: HeartRateExportSample): HKQuantitySample {
        val type = requireNotNull(heartRateType)
        val time = Instant.fromEpochSeconds(sample.timestampSeconds).toNSDate()
        return HKQuantitySample.quantitySampleWithType(
            quantityType = type,
            quantity = HKQuantity.quantityWithUnit(
                unit = HKUnit.countUnit().unitDividedByUnit(HKUnit.minuteUnit()),
                doubleValue = sample.beatsPerMinute.toDouble(),
            ),
            // Heart rate is a point sample, so start and end are deliberately identical.
            startDate = time,
            endDate = time,
            metadata = mapOf(
                HK_METADATA_SYNC_IDENTIFIER to sample.syncIdentifier,
                HK_METADATA_SYNC_VERSION to SYNC_VERSION,
                HK_METADATA_WAS_USER_ENTERED to false,
                HK_METADATA_EXTERNAL_UUID to sample.syncIdentifier,
                METADATA_PEBBLE_SEQUENCE to sample.sourceRecordId,
                METADATA_PEBBLE_DEVICE to "Pebble Time 2",
            ),
        )
    }

    private suspend fun save(objects: List<HKQuantitySample>) {
        if (objects.isEmpty()) return
        suspendCancellableCoroutine<Unit> { continuation ->
            healthStore.saveObjects(objects) { success, error ->
                if (continuation.isCancelled) return@saveObjects
                when {
                    error != null -> continuation.resumeWithException(error.asException())
                    !success -> continuation.resumeWithException(
                        IllegalStateException("HealthKit declined the heart-rate save without an error"),
                    )
                    else -> continuation.resume(Unit)
                }
            }
        }
    }

    private fun NSError.asException(): IllegalStateException = IllegalStateException(toString())

    private companion object {
        // Keep batches small enough that a transient HealthKit failure can be retried cheaply.
        const val MAX_RECORDS_PER_SAVE = 100
        const val SYNC_VERSION = 1

        // Use the documented HealthKit keys directly.  String constants keep this source
        // compatible with the Kotlin/Native SDKs that predate generated symbol bindings.
        const val HK_METADATA_SYNC_IDENTIFIER = "HKSyncIdentifier"
        const val HK_METADATA_SYNC_VERSION = "HKSyncVersion"
        const val HK_METADATA_WAS_USER_ENTERED = "HKWasUserEntered"
        const val HK_METADATA_EXTERNAL_UUID = "HKExternalUUID"
        const val METADATA_PEBBLE_SEQUENCE = "com.coredevices.pebble.health.sequence"
        const val METADATA_PEBBLE_DEVICE = "com.coredevices.pebble.health.device"
    }
}
