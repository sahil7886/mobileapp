package coredevices.pebble.health

/** Android's Health Connect path remains implemented by HealthKMP in [PlatformHealthSync]. */
internal actual class NativeHeartRateExporter {
    actual val isActive: Boolean = false

    actual fun isAvailable(): Boolean = false

    actual fun authorization(): HealthWriteAuthorization = HealthWriteAuthorization.NotApplicable

    actual fun workoutAuthorization(): HealthWriteAuthorization = HealthWriteAuthorization.NotApplicable

    actual suspend fun requestAuthorization(): Result<Boolean> = Result.success(true)

    actual suspend fun write(samples: List<HeartRateExportSample>): Result<HeartRateExportWriteResult> =
        Result.failure(UnsupportedOperationException("Native HealthKit exporter is only used on iOS"))

    actual suspend fun writeWorkout(
        workout: WorkoutHeartRateExport,
    ): Result<WorkoutHeartRateExportWriteResult> =
        Result.failure(UnsupportedOperationException("Native HealthKit exporter is only used on iOS"))
}
