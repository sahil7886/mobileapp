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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coredevices.pebble.health.HealthExportStatus
import coredevices.pebble.health.HealthSyncTracker
import coredevices.pebble.health.PlatformHealthSync
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

    LaunchedEffect(Unit) {
        topBarParams.title("Apple Health Export")
        topBarParams.actions {}
        topBarParams.searchAvailable(null)
        sync.refreshExportStatus()
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
            "Raw PPI intervals are retained locally for export. They are not written directly " +
                "to Apple Health because HealthKit HRV expects an SDNN aggregate, not intervals.",
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
            onClick = { scope.launch { sync.sync(); sync.refreshExportStatus() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sync now")
        }
        Text(
            "Bevel verification is pending. This screen confirms that Apple Health accepted the " +
                "export; it does not claim that any third-party app displays it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HealthExportStatusRows(status: HealthExportStatus) {
    StatusRow("Health platform", if (status.healthPlatformAvailable) "Available" else "Unavailable")
    StatusRow("Heart-rate permission", status.heartRateAuthorization.toDisplayName())
    StatusRow("Workout permission", status.workoutAuthorization.toDisplayName())
    StatusRow("Last successful sync", status.lastSuccessfulSyncEpochSeconds.toDisplayTime())
    StatusRow("Pending heart-rate records", status.pendingHeartRateRecords.toString())
    StatusRow("Pending workout HR records", status.pendingGranularHeartRateRecords.toString())
    StatusRow("Raw PPI records on phone", status.storedBeatToBeatRecords.toString())
    StatusRow("Overnight classifier inputs", status.storedSleepCaptureRecords.toString())
    StatusRow("Failed heart-rate records", status.failedHeartRateRecords.toString())
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
