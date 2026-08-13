package coredevices.pebble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coredevices.pebble.health.HealthExportStatus
import coredevices.pebble.health.HealthSyncTracker
import coredevices.pebble.health.PlatformHealthSync
import coredevices.pebble.health.BuiltinWorkoutCorrection
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

/**
 * User-visible accounting for local Pebble health data headed to the platform store.  This is
 * deliberately a status view, not a promise that a third-party app has interpreted the data.
 */
@Composable
fun HealthExportStatusScreen(topBarParams: TopBarParams) {
    val sync: PlatformHealthSync = koinInject()
    val tracker: HealthSyncTracker = koinInject()
    val status by tracker.status.collectAsState()
    val scope = rememberCoroutineScope()
    var workouts by remember { mutableStateOf<List<BuiltinWorkoutCorrection>>(emptyList()) }
    var showWorkoutPicker by remember { mutableStateOf(false) }
    var selectedWorkout by remember { mutableStateOf<BuiltinWorkoutCorrection?>(null) }

    LaunchedEffect(Unit) {
        topBarParams.title("Apple Health Export")
        topBarParams.actions {}
        topBarParams.searchAvailable(null)
        sync.refreshExportStatus()
        workouts = sync.recentBuiltinWorkouts()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pebble health export", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Heart-rate points are buffered on the watch, received through the existing Pebble " +
                "connection, then written to Apple Health with replay-safe identifiers.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Completed overnight PPI sessions are quality-filtered on this phone into five-minute " +
                "SDNN values. Raw intervals remain local and are never written to Apple Health.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        HealthExportStatusRows(status)
        status.lastError?.let { error ->
            Text("Last error: $error", color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = { scope.launch { sync.requestPermissions(); sync.refreshExportStatus() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Allow Apple Health export")
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    sync.sync()
                    sync.refreshExportStatus()
                    workouts = sync.recentBuiltinWorkouts()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sync now")
        }
        if (workouts.isNotEmpty()) {
            OutlinedButton(
                onClick = { showWorkoutPicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Correct a workout end time")
            }
            Text(
                "Choose the actual end time if you stopped exercising before you stopped the watch. " +
                    "The extra raw records stay on this phone but are left out of Apple Health.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Apple Health acceptance is tracked per record. Bevel verification remains pending; " +
                "this screen does not claim that a third-party app displays the data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    selectedWorkout?.let { workout ->
        WorkoutEndCorrectionDialog(
            workout = workout,
            onDismiss = { selectedWorkout = null },
            onConfirm = { endEpochSeconds ->
                scope.launch {
                    sync.correctBuiltinWorkoutEnd(workout.workoutId, endEpochSeconds)
                    sync.refreshExportStatus()
                    workouts = sync.recentBuiltinWorkouts()
                    selectedWorkout = null
                }
            },
        )
    }

    if (showWorkoutPicker) {
        AlertDialog(
            onDismissRequest = { showWorkoutPicker = false },
            title = { Text("Choose workout") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    workouts.forEach { workout ->
                        TextButton(
                            onClick = {
                                showWorkoutPicker = false
                                selectedWorkout = workout
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${workout.workoutId.toDisplayTime()} · " +
                                    "${(workout.effectiveEndEpochSeconds - workout.workoutId) / 60} min",
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showWorkoutPicker = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WorkoutEndCorrectionDialog(
    workout: BuiltinWorkoutCorrection,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var endMinutes by remember(workout) {
        mutableStateOf(((workout.effectiveEndEpochSeconds - workout.workoutId) / 60).toString())
    }
    val requestedMinutes = endMinutes.toLongOrNull()
    val maxMinutes = (workout.recordedEndEpochSeconds - workout.workoutId) / 60
    val valid = requestedMinutes != null && requestedMinutes in 1..maxMinutes
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct workout end") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Started ${workout.workoutId.toDisplayTime()}")
                Text("Watch stopped ${workout.recordedEndEpochSeconds.toDisplayTime()}")
                OutlinedTextField(
                    value = endMinutes,
                    onValueChange = { endMinutes = it.filter(Char::isDigit) },
                    label = { Text("Actual duration (minutes)") },
                    isError = endMinutes.isNotEmpty() && !valid,
                    singleLine = true,
                )
                Text(
                    "Enter 1–$maxMinutes minutes. Apple Health will be updated to this duration.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(workout.workoutId + requireNotNull(requestedMinutes) * 60) },
            ) { Text("Update Apple Health") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HealthExportStatusRows(status: HealthExportStatus) {
    StatusRow("Health platform", if (status.healthPlatformAvailable) "Available" else "Unavailable")
    StatusRow("Heart-rate permission", status.heartRateAuthorization.toDisplayName())
    StatusRow("Workout permission", status.workoutAuthorization.toDisplayName())
    StatusRow("Overnight HRV permission", status.hrvAuthorization.toDisplayName())
    StatusRow("Last successful sync", status.lastSuccessfulSyncEpochSeconds.toDisplayTime())
    StatusRow("Pending heart-rate records", status.pendingHeartRateRecords.toString())
    StatusRow("Pending workout HR records", status.pendingGranularHeartRateRecords.toString())
    StatusRow("Raw PPI records on phone", status.storedBeatToBeatRecords.toString())
    StatusRow("Overnight classifier inputs", status.storedSleepCaptureRecords.toString())
    StatusRow("Pending overnight SDNN records", status.pendingOvernightHrvRecords.toString())
    StatusRow("Apple Health SDNN records", status.exportedOvernightHrvRecords.toString())
    StatusRow("Failed heart-rate records", status.failedHeartRateRecords.toString())
    StatusRow("Failed overnight HRV records", status.failedOvernightHrvRecords.toString())
    StatusRow("Data-source conflicts", status.dataSourceConflicts)
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Long.toDisplayTime(): String {
    if (this <= 0) return "Never"
    return Instant.fromEpochSeconds(this)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .toString()
}

private fun coredevices.pebble.health.HealthWriteAuthorization.toDisplayName(): String = when (this) {
    coredevices.pebble.health.HealthWriteAuthorization.NotApplicable -> "Managed by the platform health exporter"
    coredevices.pebble.health.HealthWriteAuthorization.Unavailable -> "Unavailable"
    coredevices.pebble.health.HealthWriteAuthorization.NotDetermined -> "Not requested"
    coredevices.pebble.health.HealthWriteAuthorization.Denied -> "Denied"
    coredevices.pebble.health.HealthWriteAuthorization.Authorized -> "Allowed"
}
