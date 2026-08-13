package coredevices.pebble.health

import co.touchlab.kermit.Logger
import com.viktormykhailiv.kmp.health.HealthDataType
import com.viktormykhailiv.kmp.health.HealthManager
import com.viktormykhailiv.kmp.health.HealthRecord
import com.viktormykhailiv.kmp.health.records.ExerciseSessionRecord
import com.viktormykhailiv.kmp.health.records.ExerciseType
import com.viktormykhailiv.kmp.health.records.HeartRateRecord
import com.viktormykhailiv.kmp.health.records.SleepSessionRecord
import com.viktormykhailiv.kmp.health.records.SleepStageType
import com.viktormykhailiv.kmp.health.records.StepsRecord
import com.viktormykhailiv.kmp.health.records.metadata.Device
import com.viktormykhailiv.kmp.health.records.metadata.DeviceType
import com.viktormykhailiv.kmp.health.records.metadata.Metadata
import coredevices.util.AppResumed
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.database.entity.OvernightHrvEntity
import io.rebble.libpebblecommon.datalogging.BuiltinWorkoutHeartRateProtocol
import io.rebble.libpebblecommon.health.OverlayType
import io.rebble.libpebblecommon.health.OvernightHrvCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant

internal expect fun exerciseWriteTypes(): List<HealthDataType>

internal expect fun supportsSleepWriting(): Boolean

class PlatformHealthSync(
    private val libPebble: LibPebble,
    private val tracker: HealthSyncTracker,
    private val appResumed: AppResumed,
    private val healthManager: HealthManager,
    private val healthDataApi: HealthDataApi,
) {
    private val logger = Logger.withTag("PlatformHealthSync")
    private val nativeHeartRateExporter = NativeHeartRateExporter()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** Start observing health data updates and app foreground events, auto-syncing to the platform. */
    fun startAutoSync(scope: CoroutineScope) {
        scope.launch {
            libPebble.healthDataUpdated.collect {
                sync()
            }
        }
        scope.launch {
            sync()
            appResumed.appResumed.collect {
                sync()
            }
        }
    }

    companion object {
        /** Bound a background pass; remaining records stay durably pending for the next sync. */
        private const val MAX_GRANULAR_BATCHES_PER_SYNC = 10
        private const val MAX_GRANULAR_RECORDS_PER_BATCH = 100
        private const val MAX_OVERNIGHT_HRV_BATCHES_PER_SYNC = 10
        private const val MAX_OVERNIGHT_HRV_RECORDS_PER_BATCH = 100
        private const val BUILTIN_WORKOUT_LOOKUP_WINDOW_SECONDS = 24 * 60 * 60L
        private const val MAX_WORKOUT_END_CORRECTION_SECONDS = 12 * 60 * 60L
        private val BUILTIN_WORKOUT_OVERLAY_TYPES = setOf(
            OverlayType.Walk.value,
            OverlayType.Run.value,
            OverlayType.OpenWorkout.value,
        )

        val RequestedReadTypes = emptyList<HealthDataType>()
        val RequestedWriteTypes = listOf(
            HealthDataType.Steps,
            HealthDataType.HeartRate,
            HealthDataType.Sleep,
        ) + exerciseWriteTypes()
    }

    /** Check if the health platform is available on this device. */
    fun isAvailable(): Boolean {
        val genericPlatformAvailable = healthManager.isAvailable().getOrDefault(false)
        return genericPlatformAvailable &&
            (!nativeHeartRateExporter.isActive || nativeHeartRateExporter.isAvailable())
    }

    /** Request write permissions. Returns true if granted. */
    suspend fun requestPermissions(): Boolean {
        val genericResult = try {
            healthManager.requestAuthorization(
                readTypes = RequestedReadTypes,
                writeTypes = RequestedWriteTypes,
            )
        } catch (e: Exception) {
            logger.w(e) { "Health platform doesn't support requested types" }
            tracker.setEnabled(false)
            return false
        }
        val nativeResult = if (nativeHeartRateExporter.isActive) {
            nativeHeartRateExporter.requestAuthorization()
        } else {
            Result.success(true)
        }
        val success = genericResult.getOrDefault(false) && nativeResult.getOrDefault(false)
        tracker.updateExportStatus(
            healthPlatformAvailable = isAvailable(),
            heartRateAuthorization = nativeHeartRateExporter.authorization(),
            workoutAuthorization = nativeHeartRateExporter.workoutAuthorization(),
            hrvAuthorization = nativeHeartRateExporter.hrvAuthorization(),
        )
        logger.v { "requestPermissions success=$success" }
        tracker.setEnabled(success)
        GlobalScope.launch {
            sync()
        }
        return success
    }

    suspend fun hasPermission(): Boolean {
        val genericResult = try {
            healthManager.isAuthorized(
                readTypes = RequestedReadTypes,
                writeTypes = RequestedWriteTypes,
            )
        } catch (e: Exception) {
            logger.w(e) { "Health platform doesn't support requested types" }
            return false
        }
        val nativeAuthorized = !nativeHeartRateExporter.isActive ||
            (
                nativeHeartRateExporter.authorization() == HealthWriteAuthorization.Authorized &&
                    nativeHeartRateExporter.workoutAuthorization() == HealthWriteAuthorization.Authorized
                )
        val authorized = genericResult.getOrDefault(false) && nativeAuthorized
        tracker.updateExportStatus(
            healthPlatformAvailable = isAvailable(),
            heartRateAuthorization = nativeHeartRateExporter.authorization(),
            workoutAuthorization = nativeHeartRateExporter.workoutAuthorization(),
            hrvAuthorization = nativeHeartRateExporter.hrvAuthorization(),
        )
        logger.v { "hasPermission: generic=$genericResult native=$nativeAuthorized" }
        return authorized
    }

    /** Run a sync: query new data from Room DB, map to HealthKMP records, write. */
    suspend fun sync() {
        if (!tracker.enabled.value) return
        if (!_syncing.compareAndSet(expect = false, update = true)) return
        try {
            if (!hasPermission()) {
                logger.w { "No health sync permission during sync attempt!" }
                tracker.setEnabled(false)
                return
            }
            syncSteps()
            syncHeartRate()
            syncGranularWorkoutHeartRate()
            syncOvernightHrv()
            syncOverlays()
            refreshExportStatus()
            logger.d { "Health platform sync completed" }
        } catch (e: Exception) {
            logger.e(e) { "Health platform sync failed" }
        } finally {
            _syncing.value = false
        }
    }

    /** Refreshes the persistent state rendered by the Apple Health export status screen. */
    suspend fun refreshExportStatus() {
        val pendingMinuteRecords = healthDataApi.getHealthDataAfter(tracker.lastSyncedHeartRateTimestamp)
            .count { it.heartRate in 1..300 }
        val pendingGranularRecords = healthDataApi.countPendingGranularHeartRate()
        val storedBeatToBeatRecords = healthDataApi.countBeatToBeat()
        val storedSleepCaptureRecords = healthDataApi.countSleepCaptureSamples()
        val pendingOvernightHrvRecords = healthDataApi.countPendingOvernightHrv()
        val exportedOvernightHrvRecords = healthDataApi.countExportedOvernightHrv()
        tracker.updateExportStatus(
            healthPlatformAvailable = isAvailable(),
            heartRateAuthorization = nativeHeartRateExporter.authorization(),
            workoutAuthorization = nativeHeartRateExporter.workoutAuthorization(),
            hrvAuthorization = nativeHeartRateExporter.hrvAuthorization(),
            pendingHeartRateRecords = pendingMinuteRecords,
            pendingGranularHeartRateRecords = pendingGranularRecords,
            storedBeatToBeatRecords = storedBeatToBeatRecords,
            storedSleepCaptureRecords = storedSleepCaptureRecords,
            pendingOvernightHrvRecords = pendingOvernightHrvRecords,
            exportedOvernightHrvRecords = exportedOvernightHrvRecords,
        )
    }

    /** Recent watch workouts that can have an accidental late stop corrected before export. */
    suspend fun recentBuiltinWorkouts(): List<BuiltinWorkoutCorrection> =
        healthDataApi.getRecentBuiltinWorkoutSummaries(limit = 12)
            .mapNotNull { summary ->
                val recordedEnd = summary.terminalEpochSeconds.takeIf { it > summary.workoutId }
                    ?: summary.lastSampleEpochSeconds.takeIf { it > summary.workoutId }
                    ?: return@mapNotNull null
                BuiltinWorkoutCorrection(
                    workoutId = summary.workoutId,
                    recordedEndEpochSeconds = recordedEnd,
                    correctedEndEpochSeconds = tracker.workoutEndOverride(summary.workoutId),
                    recordCount = summary.recordCount,
                    pendingRecordCount = summary.pendingRecordCount,
                )
            }

    /**
     * Uses an earlier, user-confirmed finish time when rebuilding the app-owned HealthKit workout.
     * Raw Pebble records remain locally available; only the Apple Health workout is trimmed.
     */
    suspend fun correctBuiltinWorkoutEnd(workoutId: Long, endEpochSeconds: Long): Result<Unit> =
        runCatching {
            val workout = recentBuiltinWorkouts().firstOrNull { it.workoutId == workoutId }
                ?: error("This workout is no longer available on the phone")
            require(endEpochSeconds > workoutId) { "End time must be after the workout started" }
            require(endEpochSeconds <= workout.recordedEndEpochSeconds) {
                "End time cannot be after the watch's recorded stop"
            }
            require(endEpochSeconds - workoutId <= MAX_WORKOUT_END_CORRECTION_SECONDS) {
                "Workout correction is outside the supported range"
            }
            tracker.setWorkoutEndOverride(workoutId, endEpochSeconds)
            healthDataApi.markBuiltinWorkoutPending(workoutId)
            sync()
            refreshExportStatus()
        }

    /**
     * Calculates only after PebbleOS delivered a terminal sleep-capture record, then sends
     * quality-filtered SDNN aggregates to HealthKit. Raw PPI stays on the phone for export and
     * diagnostics; it is never presented to HealthKit as HRV.
     */
    private suspend fun syncOvernightHrv() {
        val uncalculatedSessions = healthDataApi.getCompletedSleepCaptureSessionIdsWithoutHrv(
            OvernightHrvCalculator.ALGORITHM_VERSION,
        )
        uncalculatedSessions.forEach { sessionId ->
            val calculations = OvernightHrvCalculator.calculate(
                sessionId = sessionId,
                samples = healthDataApi.getSleepCaptureSamplesForSession(sessionId),
            )
            val calculatedAt = Clock.System.now().epochSeconds
            val inserted = healthDataApi.insertOvernightHrv(calculations.map { calculation ->
                OvernightHrvEntity(
                    recordId = calculation.recordId,
                    sessionId = calculation.sessionId,
                    windowStartEpochSeconds = calculation.windowStartEpochSeconds,
                    windowEndEpochSeconds = calculation.windowEndEpochSeconds,
                    sdnnMilliseconds = calculation.sdnnMilliseconds,
                    sourcePpiSampleCount = calculation.sourcePpiSampleCount,
                    qualityAcceptedSampleCount = calculation.qualityAcceptedSampleCount,
                    artifactRejectedSampleCount = calculation.artifactRejectedSampleCount,
                    qualityCoveragePercent = calculation.qualityCoveragePercent,
                    temporalCoveragePercent = calculation.temporalCoveragePercent,
                    algorithmVersion = calculation.algorithmVersion,
                    calculatedAtEpochSeconds = calculatedAt,
                )
            })
            logger.d {
                "HEALTH_HRV_CALC session=$sessionId windows=${calculations.size} inserted=$inserted " +
                    "algorithm=${OvernightHrvCalculator.ALGORITHM_VERSION}"
            }
        }

        if (!nativeHeartRateExporter.isActive) return
        if (nativeHeartRateExporter.hrvAuthorization() != HealthWriteAuthorization.Authorized) {
            logger.d { "HEALTH_HRV waiting for Apple Health HRV permission" }
            return
        }

        var batches = 0
        while (batches < MAX_OVERNIGHT_HRV_BATCHES_PER_SYNC) {
            val pending = healthDataApi.getPendingOvernightHrv(MAX_OVERNIGHT_HRV_RECORDS_PER_BATCH)
            if (pending.isEmpty()) return

            val result = nativeHeartRateExporter.writeOvernightHrv(pending.map { record ->
                OvernightHrvExport(
                    sourceRecordId = record.recordId,
                    windowStartEpochSeconds = record.windowStartEpochSeconds,
                    windowEndEpochSeconds = record.windowEndEpochSeconds,
                    sdnnMilliseconds = record.sdnnMilliseconds,
                    algorithmVersion = record.algorithmVersion,
                )
            })
            result.fold(
                onSuccess = { written ->
                    healthDataApi.markOvernightHrvExported(pending.map { it.recordId })
                    tracker.recordSuccessfulOvernightHrvExport(
                        pending.maxOf { it.windowEndEpochSeconds },
                    )
                    logger.d {
                        "HEALTH_HRV_EXPORT saved=${written.writtenRecords} " +
                            "last=${pending.maxOf { it.windowEndEpochSeconds }}"
                    }
                },
                onFailure = { error ->
                    tracker.recordOvernightHrvExportFailure(pending.size, error)
                    logger.e(error) {
                        "HEALTH_HRV_EXPORT failed=${pending.size}; rows retained for retry"
                    }
                    return
                },
            )
            batches++
        }
    }

    private suspend fun syncSteps() {
        val lastTimestamp = tracker.lastSyncedStepsTimestamp
        val latestTimestamp = healthDataApi.getLatestTimestamp() ?: return
        if (latestTimestamp <= lastTimestamp) return

        val records = healthDataApi.getHealthDataAfter(lastTimestamp)
        if (records.isEmpty()) return

        val healthRecords = mutableListOf<StepsRecord>()

        for (entity in records) {
            val startTime = Instant.fromEpochSeconds(entity.timestamp)
            val endTime = startTime + 1.minutes

            // Steps
            if (entity.steps > 0) {
                healthRecords += StepsRecord(
                    startTime = startTime,
                    endTime = endTime,
                    count = entity.steps,
                    metadata = createMetadata(entity.timestamp, "steps"),
                )
            }
        }

        if (healthRecords.isNotEmpty()) {
            val result = healthManager.writeData(healthRecords)
            if (result.isSuccess) {
                tracker.lastSyncedStepsTimestamp = records.last().timestamp
                logger.d { "Synced ${healthRecords.size} step records" }
            } else {
                logger.e { "Failed to write step records: ${result.exceptionOrNull()}" }
            }
        } else {
            tracker.lastSyncedStepsTimestamp = records.last().timestamp
        }
    }

    /**
     * Writes raw watch readings independently from step totals.  The checkpoint is deliberately
     * advanced only after a complete destination write; the native iOS writer also gives every
     * point a HealthKit sync identifier, so an interrupted retry cannot create duplicates.
     */
    private suspend fun syncHeartRate() {
        val checkpoint = tracker.lastSyncedHeartRateTimestamp
        val records = healthDataApi.getHealthDataAfter(checkpoint)
        if (records.isEmpty()) {
            tracker.updateExportStatus(
                healthPlatformAvailable = isAvailable(),
                heartRateAuthorization = nativeHeartRateExporter.authorization(),
                workoutAuthorization = nativeHeartRateExporter.workoutAuthorization(),
                pendingHeartRateRecords = 0,
            )
            return
        }

        val samples = records.mapNotNull { entity ->
            entity.heartRate.takeIf { it in 1..300 }?.let {
                HeartRateExportSample(entity.timestamp, it)
            }
        }
        tracker.updateExportStatus(
            healthPlatformAvailable = isAvailable(),
            heartRateAuthorization = nativeHeartRateExporter.authorization(),
            workoutAuthorization = nativeHeartRateExporter.workoutAuthorization(),
            pendingHeartRateRecords = samples.size,
        )

        if (samples.isEmpty()) {
            tracker.lastSyncedHeartRateTimestamp = records.last().timestamp
            return
        }

        val result = writeHeartRateSamples(samples)

        result.onSuccess { written ->
            tracker.lastSyncedHeartRateTimestamp = records.last().timestamp
            tracker.recordSuccessfulHeartRateExport(records.last().timestamp)
            tracker.updateExportStatus(pendingHeartRateRecords = 0)
            logger.d {
                "HEALTH_EXPORT_HR synced=${written.writtenRecords} checkpoint=${records.last().timestamp}"
            }
        }.onFailure { error ->
            tracker.recordHeartRateExportFailure(samples.size, error)
            logger.e(error) {
                "HEALTH_EXPORT_HR failed=${samples.size} checkpoint=$checkpoint; checkpoint retained"
            }
        }
    }

    /**
     * Writes high-resolution workout readings. Health Capture records retain the historic
     * standalone path; built-in Workout records wait for their matching normal ActivitySession
     * and become one native workout with associated heart-rate samples on iOS.
     */
    private suspend fun syncGranularWorkoutHeartRate() {
        var batches = 0
        while (batches < MAX_GRANULAR_BATCHES_PER_SYNC) {
            val records = healthDataApi.getPendingGranularHeartRate(MAX_GRANULAR_RECORDS_PER_BATCH)
            if (records.isEmpty()) {
                tracker.updateExportStatus(pendingGranularHeartRateRecords = 0)
                return
            }

            tracker.updateExportStatus(pendingGranularHeartRateRecords = records.size)
            var madeProgress = false

            val standalone = records.filterNot {
                BuiltinWorkoutHeartRateProtocol.isBuiltinWorkoutRecord(it.recordId)
            }
            if (standalone.isNotEmpty()) {
                val samples = standalone.map { record ->
                    HeartRateExportSample(
                        timestampSeconds = record.timestampEpochSeconds,
                        beatsPerMinute = record.filteredBpm,
                        sourceRecordId = record.recordId,
                    )
                }
                val written = writeHeartRateSamples(samples).getOrElse { error ->
                    tracker.recordHeartRateExportFailure(standalone.size, error)
                    logger.e(error) {
                        "HEALTH_EXPORT_WORKOUT_HR failed=${standalone.size} " +
                            "first=${standalone.first().recordId}; rows retained"
                    }
                    return
                }
                healthDataApi.markGranularHeartRateExported(standalone.map { it.recordId })
                tracker.recordSuccessfulHeartRateExport(standalone.maxOf { it.timestampEpochSeconds })
                logger.d { "HEALTH_EXPORT_WORKOUT_HR synced=${written.writtenRecords}" }
                madeProgress = true
            }

            val builtInWorkoutIds = records
                .asSequence()
                .filter { BuiltinWorkoutHeartRateProtocol.isBuiltinWorkoutRecord(it.recordId) }
                .map { it.workoutId }
                .distinct()
                .toList()
            for (workoutId in builtInWorkoutIds) {
                if (syncBuiltInWorkoutHeartRate(workoutId)) madeProgress = true
            }

            if (!madeProgress) {
                logger.d { "HEALTH_EXPORT_BUILTIN_WORKOUT waiting for its session or completion record" }
                refreshExportStatus()
                return
            }
            batches++
        }
        refreshExportStatus()
    }

    /** Returns true only when this call durably exported a completed built-in workout. */
    private suspend fun syncBuiltInWorkoutHeartRate(workoutId: Long): Boolean {
        // A built-in Workout runs for at most an hour on the current firmware. The terminal
        // record gives the exact stop timestamp; the wider query also tolerates a delayed DLS
        // packet without trusting the minute-rounded ActivitySession duration as the end bound.
        val detail = healthDataApi.getGranularHeartRateForRange(
            workoutId,
            workoutId + BUILTIN_WORKOUT_LOOKUP_WINDOW_SECONDS,
        ).filter {
            it.workoutId == workoutId &&
                BuiltinWorkoutHeartRateProtocol.isBuiltinWorkoutRecord(it.recordId)
        }
        val terminal = detail
            .filter { it.flags and BuiltinWorkoutHeartRateProtocol.FLAG_WORKOUT_COMPLETE != 0 }
            .maxByOrNull { it.timestampEpochSeconds }
        val recordedEndEpochSeconds = terminal?.timestampEpochSeconds
            ?: detail.maxOfOrNull { it.timestampEpochSeconds }
            ?: return false
        val overlay = healthDataApi.getOverlayEntriesForRange(workoutId, workoutId + 1)
            .firstOrNull {
                it.startTime == workoutId && it.type in BUILTIN_WORKOUT_OVERLAY_TYPES
            }
        if (overlay == null && tracker.workoutEndOverride(workoutId) == null) return false
        if (recordedEndEpochSeconds <= workoutId) return false
        val endEpochSeconds = tracker.workoutEndOverride(workoutId)
            ?.takeIf { it <= recordedEndEpochSeconds }
            ?: terminal?.timestampEpochSeconds
            ?: return false
        if (endEpochSeconds <= workoutId) return false

        val samples = detail
            .asSequence()
            .filter { it.filteredBpm in 1..300 }
            .filter {
                it.timestampEpochSeconds >= workoutId &&
                    it.timestampEpochSeconds < endEpochSeconds
            }
            .sortedWith(compareBy({ it.timestampEpochSeconds }, { it.sequence }))
            .map {
                HeartRateExportSample(
                    timestampSeconds = it.timestampEpochSeconds,
                    beatsPerMinute = it.filteredBpm,
                    sourceRecordId = it.recordId,
                    syncVersion = tracker.workoutExportVersion(workoutId),
                )
            }.toList()
        if (samples.isEmpty()) return false

        // The original, summary-only exporter may already have committed this overlay when the
        // detailed Datalogging session arrived late or could not be completed. Preserve that
        // normal Pebble workout and export the retained HR points by themselves instead of
        // creating a second HealthKit workout for the same activity.
        if (
            overlay != null &&
                overlay.startTime <= tracker.lastSyncedOverlayTimestamp &&
                tracker.workoutEndOverride(workoutId) == null &&
                !tracker.hasNativeWorkoutExport(workoutId)
        ) {
            val written = writeHeartRateSamples(samples).getOrElse { error ->
                tracker.recordHeartRateExportFailure(samples.size, error)
                logger.e(error) {
                    "HEALTH_EXPORT_BUILTIN_WORKOUT fallback HR failed workout=$workoutId; rows retained"
                }
                return false
            }
            healthDataApi.markGranularHeartRateExported(detail.map { it.recordId })
            tracker.recordSuccessfulHeartRateExport(endEpochSeconds)
            logger.w {
                "HEALTH_EXPORT_BUILTIN_WORKOUT fallback standalone-HR saved=" +
                    "${written.writtenRecords} workout=$workoutId"
            }
            return true
        }

        if (!nativeHeartRateExporter.isActive) {
            val written = writeHeartRateSamples(samples).getOrElse { error ->
                tracker.recordHeartRateExportFailure(samples.size, error)
                logger.e(error) { "HEALTH_EXPORT_BUILTIN_WORKOUT_HR failed; rows retained" }
                return false
            }
            healthDataApi.markGranularHeartRateExported(detail.map { it.recordId })
            tracker.recordSuccessfulHeartRateExport(endEpochSeconds)
            logger.d {
                "HEALTH_EXPORT_BUILTIN_WORKOUT_HR synced=${written.writtenRecords} workout=$workoutId"
            }
            return true
        }

        val result = nativeHeartRateExporter.writeWorkout(
            WorkoutHeartRateExport(
                sourceRecordId = "builtin-workout-v1:$workoutId:${overlay?.type ?: OverlayType.OpenWorkout.value}",
                type = when (overlay?.let { OverlayType.fromValue(it.type) }) {
                    OverlayType.Walk -> WorkoutHeartRateExportType.Walking
                    OverlayType.Run -> WorkoutHeartRateExportType.Running
                    OverlayType.OpenWorkout -> WorkoutHeartRateExportType.Other
                    null -> WorkoutHeartRateExportType.Other
                    else -> return false
                },
                startEpochSeconds = workoutId,
                endEpochSeconds = endEpochSeconds,
                heartRateSamples = samples,
                syncVersion = tracker.workoutExportVersion(workoutId),
            ),
        )
        return result.fold(
            onSuccess = { written ->
                healthDataApi.markGranularHeartRateExported(detail.map { it.recordId })
                tracker.recordSuccessfulHeartRateExport(endEpochSeconds)
                tracker.markNativeWorkoutExported(workoutId)
                logger.d {
                    "HEALTH_EXPORT_BUILTIN_WORKOUT saved=${written.writtenHeartRateRecords} " +
                    "workout=$workoutId end=$endEpochSeconds"
                }
                true
            },
            onFailure = { error ->
                tracker.recordHeartRateExportFailure(samples.size, error)
                logger.e(error) { "HEALTH_EXPORT_BUILTIN_WORKOUT failed workout=$workoutId; rows retained" }
                false
            },
        )
    }

    private suspend fun writeHeartRateSamples(
        samples: List<HeartRateExportSample>,
    ): Result<HeartRateExportWriteResult> = if (nativeHeartRateExporter.isActive) {
        nativeHeartRateExporter.write(samples)
    } else {
        healthManager.writeData(samples.map { sample ->
            val time = Instant.fromEpochSeconds(sample.timestampSeconds)
            HeartRateRecord(
                startTime = time,
                endTime = time,
                samples = listOf(HeartRateRecord.Sample(time, sample.beatsPerMinute)),
                metadata = createMetadata(sample.timestampSeconds, "hr-${sample.sourceRecordId}"),
            )
        }).map { HeartRateExportWriteResult(samples.size) }
    }

    private suspend fun syncOverlays() {
        val lastTimestamp = tracker.lastSyncedOverlayTimestamp
        val sleepTypes = listOf(
            OverlayType.Sleep.value,
            OverlayType.DeepSleep.value,
            OverlayType.Nap.value,
            OverlayType.DeepNap.value,
        )
        val exerciseTypes = listOf(
            OverlayType.Walk.value,
            OverlayType.Run.value,
            OverlayType.OpenWorkout.value,
        )
        val allTypes = sleepTypes + exerciseTypes

        val overlays = healthDataApi.getOverlayEntriesAfter(lastTimestamp, allTypes)
        if (overlays.isEmpty()) return

        val sleepOverlays = overlays.filter { it.type in sleepTypes }
        val exerciseOverlays = overlays.filter { it.type in exerciseTypes }

        // A completed built-in Workout is already represented by the native HealthKit workout
        // writer above. Do not send a second generic exercise session for the same overlay.
        // If its high-resolution rows are still pending, retain the standard overlay checkpoint
        // too, so a failed native write cannot later turn into an unassociated duplicate.
        val nativeManagedExerciseOverlays = if (nativeHeartRateExporter.isActive) {
            exerciseOverlays.filter { overlay ->
                val detail = healthDataApi.getGranularHeartRateForRange(
                    overlay.startTime,
                    overlay.startTime + BUILTIN_WORKOUT_LOOKUP_WINDOW_SECONDS,
                ).filter {
                    it.workoutId == overlay.startTime &&
                        BuiltinWorkoutHeartRateProtocol.isBuiltinWorkoutRecord(it.recordId)
                }
                detail.hasCompletedExportableBuiltinWorkout() ||
                    tracker.workoutEndOverride(overlay.startTime) != null
            }
        } else {
            emptyList()
        }
        val pendingNativeWorkout = nativeManagedExerciseOverlays.firstOrNull { overlay ->
            healthDataApi.getGranularHeartRateForRange(
                overlay.startTime,
                overlay.startTime + BUILTIN_WORKOUT_LOOKUP_WINDOW_SECONDS,
            ).any {
                it.workoutId == overlay.startTime &&
                    BuiltinWorkoutHeartRateProtocol.isBuiltinWorkoutRecord(it.recordId) &&
                    !it.exportedToAppleHealth
            }
        }
        if (pendingNativeWorkout != null) {
            logger.d {
                "HEALTH_EXPORT_BUILTIN_WORKOUT awaiting native export " +
                    "workout=${pendingNativeWorkout.startTime}"
            }
            return
        }
        val genericExerciseOverlays = exerciseOverlays - nativeManagedExerciseOverlays

        var maxSyncedTimestamp = lastTimestamp

        // Write sleep sessions separately so exercise failures don't block sleep
        val sleepRecords = if (supportsSleepWriting()) buildSleepSessions(sleepOverlays) else emptyList()
        if (sleepRecords.isNotEmpty()) {
            logger.v { "Writing ${sleepRecords.size} sleep sessions to health platform" }
            val result = healthManager.writeData(sleepRecords)
            if (result.isSuccess) {
                maxSyncedTimestamp = maxOf(maxSyncedTimestamp, sleepOverlays.maxOf { it.startTime })
                logger.d { "Synced ${sleepRecords.size} sleep records" }
            } else {
                logger.e { "Failed to write sleep records: ${result.exceptionOrNull()}" }
            }
        } else if (sleepOverlays.isNotEmpty()) {
            maxSyncedTimestamp = maxOf(maxSyncedTimestamp, sleepOverlays.maxOf { it.startTime })
        }

        // Write exercise records separately
        val exerciseRecords = mutableListOf<HealthRecord>()
        for (overlay in genericExerciseOverlays) {
            if (overlay.duration <= 0) continue
            val startTime = Instant.fromEpochSeconds(overlay.startTime)
            val endTime = startTime + overlay.duration.seconds

            val overlayType = OverlayType.fromValue(overlay.type) ?: continue
            val exerciseType = when (overlayType) {
                OverlayType.Walk -> ExerciseType.Walking
                OverlayType.Run -> ExerciseType.Running
                OverlayType.OpenWorkout -> ExerciseType.OtherWorkout
                else -> continue
            }

            exerciseRecords += ExerciseSessionRecord(
                startTime = startTime,
                endTime = endTime,
                exerciseType = exerciseType,
                title = when (overlayType) {
                    OverlayType.Walk -> "Walk"
                    OverlayType.Run -> "Run"
                    OverlayType.OpenWorkout -> "Workout"
                    else -> null
                },
                exerciseRoute = null,
                metadata = createMetadata(overlay.startTime, "exercise"),
            )
        }
        if (exerciseRecords.isNotEmpty()) {
            val result = healthManager.writeData(exerciseRecords)
            if (result.isSuccess) {
                maxSyncedTimestamp = maxOf(maxSyncedTimestamp, genericExerciseOverlays.maxOf { it.startTime })
                logger.d { "Synced ${exerciseRecords.size} exercise records" }
            } else {
                logger.e { "Failed to write exercise records: ${result.exceptionOrNull()}" }
            }
        } else if (genericExerciseOverlays.isNotEmpty()) {
            maxSyncedTimestamp = maxOf(maxSyncedTimestamp, genericExerciseOverlays.maxOf { it.startTime })
        }
        if (nativeManagedExerciseOverlays.isNotEmpty()) {
            maxSyncedTimestamp = maxOf(
                maxSyncedTimestamp,
                nativeManagedExerciseOverlays.maxOf { it.startTime },
            )
        }

        if (maxSyncedTimestamp > lastTimestamp) {
            tracker.lastSyncedOverlayTimestamp = maxSyncedTimestamp
        }
    }

    private fun List<io.rebble.libpebblecommon.database.entity.GranularHeartRateEntity>
        .hasCompletedExportableBuiltinWorkout(): Boolean =
        any { it.filteredBpm in 1..300 } &&
            any { it.flags and BuiltinWorkoutHeartRateProtocol.FLAG_WORKOUT_COMPLETE != 0 }

    private fun buildSleepSessions(overlays: List<OverlayDataEntity>): List<SleepSessionRecord> {
        if (overlays.isEmpty()) return emptyList()

        // Filter to only overlays with positive duration before grouping
        val valid = overlays.filter { it.duration > 0 }.sortedBy { it.startTime }
        if (valid.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<OverlayDataEntity>>()
        var currentGroup = mutableListOf(valid.first())

        for (i in 1 until valid.size) {
            val prev = currentGroup.last()
            val prevEnd = prev.startTime + prev.duration
            val curr = valid[i]

            // Group overlays within 2 hours of each other into one session
            if (curr.startTime - prevEnd <= 2 * 3600) {
                currentGroup.add(curr)
            } else {
                groups += currentGroup
                currentGroup = mutableListOf(curr)
            }
        }
        groups += currentGroup

        return groups.mapNotNull { group ->
            try {
                createSleepSession(group)
            } catch (e: Exception) {
                logger.e(e) {
                    "Failed to create sleep session from ${group.size} overlays: " +
                            group.joinToString { "type=${it.type},start=${it.startTime},dur=${it.duration}" }
                }
                null
            }
        }
    }

    private fun createSleepSession(overlays: List<OverlayDataEntity>): SleepSessionRecord {
        val sessionStart = Instant.fromEpochSeconds(overlays.minOf { it.startTime })
        val sessionEnd = Instant.fromEpochSeconds(overlays.maxOf { it.startTime + it.duration })
        val stages = computeSleepStageIntervals(overlays).map { interval ->
            SleepSessionRecord.Stage(
                startTime = Instant.fromEpochSeconds(interval.startSec),
                endTime = Instant.fromEpochSeconds(interval.endSec),
                type = if (interval.isDeep) SleepStageType.Deep else SleepStageType.Light,
            )
        }
        logger.d { "Sleep session: ${stages.size} stages, start=$sessionStart, end=$sessionEnd" }
        return SleepSessionRecord(
            startTime = sessionStart,
            endTime = sessionEnd,
            stages = stages,
            metadata = createMetadata(overlays.first().startTime, "sleep"),
        )
    }

    private fun createMetadata(timestamp: Long, prefix: String): Metadata {
        return Metadata.autoRecorded(
            id = "pebble-$prefix-$timestamp",
            device = Device(type = DeviceType.Watch),
        )
    }

}

internal data class SleepStageInterval(val startSec: Long, val endSec: Long, val isDeep: Boolean)

/** A watch workout available for an optional, local Apple Health end-time correction. */
data class BuiltinWorkoutCorrection(
    val workoutId: Long,
    val recordedEndEpochSeconds: Long,
    val correctedEndEpochSeconds: Long?,
    val recordCount: Int,
    val pendingRecordCount: Int,
) {
    val effectiveEndEpochSeconds: Long get() = correctedEndEpochSeconds ?: recordedEndEpochSeconds
}

// Pebble's overlay model: Sleep/Nap are container overlays spanning the whole session with
// DeepSleep/DeepNap sub-overlays nested inside them. Carve the Deep periods out of each Light
// container so both stage types reach Health Connect.
internal fun computeSleepStageIntervals(overlays: List<OverlayDataEntity>): List<SleepStageInterval> {
    val (deepOverlays, lightOverlays) = overlays.partition {
        when (OverlayType.fromValue(it.type)) {
            OverlayType.DeepSleep, OverlayType.DeepNap -> true
            else -> false
        }
    }
    val deepRanges = deepOverlays
        .map { it.startTime to it.startTime + it.duration }
        .sortedBy { it.first }

    val intervals = mutableListOf<SleepStageInterval>()
    deepRanges.forEach { (s, e) ->
        intervals += SleepStageInterval(s, e, isDeep = true)
    }
    lightOverlays.forEach { container ->
        val containerEnd = container.startTime + container.duration
        var cursor = container.startTime
        for ((deepStart, deepEnd) in deepRanges) {
            if (deepEnd <= cursor) continue
            if (deepStart >= containerEnd) break
            if (cursor < deepStart) {
                intervals += SleepStageInterval(cursor, deepStart, isDeep = false)
            }
            cursor = maxOf(cursor, deepEnd)
        }
        if (cursor < containerEnd) {
            intervals += SleepStageInterval(cursor, containerEnd, isDeep = false)
        }
    }
    intervals.sortBy { it.startSec }
    return intervals
}
