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
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierHeartRate
import platform.HealthKit.HKSampleType
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkoutActivityTypeOther
import platform.HealthKit.HKWorkoutActivityTypeRunning
import platform.HealthKit.HKWorkoutActivityTypeWalking
import platform.HealthKit.HKWorkoutBuilder
import platform.HealthKit.HKWorkoutConfiguration
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
    private val workoutType = HKObjectType.workoutType()

    actual val isActive: Boolean = true

    actual fun isAvailable(): Boolean =
        HKHealthStore.isHealthDataAvailable() && heartRateType != null

    actual fun authorization(): HealthWriteAuthorization {
        val type = heartRateType ?: return HealthWriteAuthorization.Unavailable
        if (!HKHealthStore.isHealthDataAvailable()) return HealthWriteAuthorization.Unavailable
        return authorizationFor(type)
    }

    actual fun workoutAuthorization(): HealthWriteAuthorization {
        if (!HKHealthStore.isHealthDataAvailable()) return HealthWriteAuthorization.Unavailable
        return authorizationFor(workoutType)
    }

    private fun authorizationFor(type: HKObjectType): HealthWriteAuthorization =
        when (healthStore.authorizationStatusForType(type)) {
            HKAuthorizationStatusSharingAuthorized -> HealthWriteAuthorization.Authorized
            HKAuthorizationStatusSharingDenied -> HealthWriteAuthorization.Denied
            HKAuthorizationStatusNotDetermined -> HealthWriteAuthorization.NotDetermined
            else -> HealthWriteAuthorization.NotDetermined
        }

    actual suspend fun requestAuthorization(): Result<Boolean> = runCatching {
        val type = heartRateType ?: error("Heart-rate HealthKit type is unavailable")
        if (!HKHealthStore.isHealthDataAvailable()) error("HealthKit is unavailable")

        suspendCancellableCoroutine { continuation ->
            healthStore.requestAuthorizationToShareTypes(
                typesToShare = setOf<HKSampleType>(type, workoutType),
                readTypes = emptySet<HKObjectType>(),
            ) { _, error ->
                if (continuation.isCancelled) return@requestAuthorizationToShareTypes
                if (error != null) {
                    continuation.resumeWithException(error.asException())
                } else {
                    continuation.resume(
                        authorization() == HealthWriteAuthorization.Authorized &&
                            workoutAuthorization() == HealthWriteAuthorization.Authorized,
                    )
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

    actual suspend fun writeWorkout(
        workout: WorkoutHeartRateExport,
    ): Result<WorkoutHeartRateExportWriteResult> = runCatching {
        require(isAvailable()) { "HealthKit workout export is unavailable" }
        require(authorization() == HealthWriteAuthorization.Authorized) {
            "Apple Health heart-rate sharing is not authorized"
        }
        require(workoutAuthorization() == HealthWriteAuthorization.Authorized) {
            "Apple Health workout sharing is not authorized"
        }

        val configuration = HKWorkoutConfiguration().apply {
            activityType = when (workout.type) {
                WorkoutHeartRateExportType.Walking -> HKWorkoutActivityTypeWalking
                WorkoutHeartRateExportType.Running -> HKWorkoutActivityTypeRunning
                WorkoutHeartRateExportType.Other -> HKWorkoutActivityTypeOther
            }
        }
        val builder = HKWorkoutBuilder(
            healthStore = healthStore,
            configuration = configuration,
            device = null,
        )
        val start = Instant.fromEpochSeconds(workout.startEpochSeconds).toNSDate()
        val end = Instant.fromEpochSeconds(workout.endEpochSeconds).toNSDate()

        beginCollection(builder, start)
        addMetadata(builder, workout)
        workout.heartRateSamples.chunked(MAX_RECORDS_PER_SAVE).forEach { batch ->
            addSamples(builder, batch.map(::toHealthKitSample))
        }
        endCollection(builder, end)
        finishWorkout(builder)

        logger.d {
            "HEALTHKIT_WORKOUT saved=${workout.heartRateSamples.size} " +
                "id=${workout.sourceRecordId} start=${workout.startEpochSeconds} " +
                "end=${workout.endEpochSeconds}"
        }
        WorkoutHeartRateExportWriteResult(workout.heartRateSamples.size)
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

    private suspend fun beginCollection(builder: HKWorkoutBuilder, start: platform.Foundation.NSDate) {
        suspendCancellableCoroutine<Unit> { continuation ->
            builder.beginCollectionWithStartDate(start) { success, error ->
                continuation.resumeHealthKitOperation(success, error)
            }
        }
    }

    private suspend fun addMetadata(builder: HKWorkoutBuilder, workout: WorkoutHeartRateExport) {
        suspendCancellableCoroutine<Unit> { continuation ->
            builder.addMetadata(
                metadata = mapOf(
                    HK_METADATA_SYNC_IDENTIFIER to workout.syncIdentifier,
                    HK_METADATA_SYNC_VERSION to SYNC_VERSION,
                    HK_METADATA_WAS_USER_ENTERED to false,
                    HK_METADATA_EXTERNAL_UUID to workout.syncIdentifier,
                    METADATA_PEBBLE_WORKOUT_ID to workout.sourceRecordId,
                    METADATA_PEBBLE_DEVICE to "Pebble Time 2",
                ),
            ) { success, error ->
                continuation.resumeHealthKitOperation(success, error)
            }
        }
    }

    private suspend fun addSamples(builder: HKWorkoutBuilder, samples: List<HKQuantitySample>) {
        if (samples.isEmpty()) return
        suspendCancellableCoroutine<Unit> { continuation ->
            builder.addSamples(samples) { success, error ->
                continuation.resumeHealthKitOperation(success, error)
            }
        }
    }

    private suspend fun endCollection(builder: HKWorkoutBuilder, end: platform.Foundation.NSDate) {
        suspendCancellableCoroutine<Unit> { continuation ->
            builder.endCollectionWithEndDate(end) { success, error ->
                continuation.resumeHealthKitOperation(success, error)
            }
        }
    }

    private suspend fun finishWorkout(builder: HKWorkoutBuilder) {
        suspendCancellableCoroutine<Unit> { continuation ->
            builder.finishWorkoutWithCompletion { workout, error ->
                if (continuation.isCancelled) return@finishWorkoutWithCompletion
                when {
                    error != null -> continuation.resumeWithException(error.asException())
                    workout == null -> continuation.resumeWithException(
                        IllegalStateException("HealthKit finished a workout without returning it"),
                    )
                    else -> continuation.resume(Unit)
                }
            }
        }
    }

    private fun kotlinx.coroutines.CancellableContinuation<Unit>.resumeHealthKitOperation(
        success: Boolean,
        error: NSError?,
    ) {
        if (isCancelled) return
        when {
            error != null -> resumeWithException(error.asException())
            !success -> resumeWithException(
                IllegalStateException("HealthKit declined the workout operation without an error"),
            )
            else -> resume(Unit)
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
        const val METADATA_PEBBLE_WORKOUT_ID = "com.coredevices.pebble.health.workout_id"
        const val METADATA_PEBBLE_DEVICE = "com.coredevices.pebble.health.device"
    }
}
