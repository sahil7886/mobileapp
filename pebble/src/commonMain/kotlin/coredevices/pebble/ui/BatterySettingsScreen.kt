package coredevices.pebble.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.database.BatteryHistoryEntry
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.services.BatteryHistoryRepository
import coredevices.pebble.services.calculateBatteryUsageSummary
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt
import org.koin.compose.koinInject

@Composable
fun BatterySettingsScreen(
    topBarParams: TopBarParams,
    serial: String?,
    watchName: String?,
) {
    val libPebble = rememberLibPebble()
    val repository: BatteryHistoryRepository = koinInject()
    val watches by libPebble.watches.collectAsState()
    val selectedWatch = remember(watches, serial) {
        val knownWatches = watches.filterIsInstance<KnownPebbleDevice>()
        serial?.let { selectedSerial -> knownWatches.firstOrNull { it.serial == selectedSerial } }
            ?: knownWatches.sortedWith(PebbleDeviceComparator).firstOrNull()
    }
    val selectedSerial = serial ?: selectedWatch?.serial
    val historyFlow = remember(selectedSerial) {
        selectedSerial?.let { repository.observe(it) } ?: flowOf(emptyList())
    }
    val samples by historyFlow.collectAsState(emptyList())
    val currentBatteryLevel = (selectedWatch as? ConnectedPebble.Battery)?.batteryLevel
    val summary = remember(samples, currentBatteryLevel) {
        calculateBatteryUsageSummary(samples, currentBatteryLevel)
    }

    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.title("Battery")
        topBarParams.actions { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = watchName ?: selectedWatch?.name ?: "Battery life",
            style = MaterialTheme.typography.titleLarge,
        )

        CurrentBatteryCard(currentBatteryLevel, samples.lastOrNull())

        if (selectedSerial == null) {
            EmptyBatteryHistory("Connect a watch to start recording battery history.")
        } else if (samples.isEmpty()) {
            EmptyBatteryHistory("Battery history starts after this version of the app connects to your watch.")
        } else {
            Text("History", style = MaterialTheme.typography.titleMedium)
            BatteryHistoryChart(samples)
            Text(
                text = "${samples.size} local readings from this watch",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            summary?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BatteryStat(
                        label = "Average drain",
                        value = "${formatDrainPerDay(it.drainPerDay)}% / day",
                        modifier = Modifier.weight(1f),
                    )
                    it.estimatedHoursRemaining?.let { hours ->
                        BatteryStat(
                            label = "Estimated remaining",
                            value = formatRemainingTime(hours),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } ?: Text(
                text = "More discharge data is needed before an estimate can be shown.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "Battery history stays on this device for up to 90 days. It is not uploaded and does not require a Pebble account or diagnostic telemetry.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CurrentBatteryCard(currentBatteryLevel: Int?, latestSample: BatteryHistoryEntry?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = if (currentBatteryLevel == null) "Last recorded level" else "Current battery",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = currentBatteryLevel?.let { "$it%" }
                    ?: latestSample?.let { "${it.batteryLevel}%" }
                    ?: "—",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (currentBatteryLevel == null) {
                Text(
                    text = "Reconnect the watch to update this reading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun BatteryHistoryChart(samples: List<BatteryHistoryEntry>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val chartSamples = remember(samples) { samples.downsampleForChart() }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        drawRoundRect(
            color = background,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
        )
        listOf(25, 50, 75).forEach { level ->
            val y = size.height * (1f - level / 100f)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }

        if (chartSamples.size == 1) {
            val y = size.height * (1f - chartSamples.first().batteryLevel / 100f)
            drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset(size.width / 2, y))
            return@Canvas
        }

        val firstTime = chartSamples.first().recordedAt
        val timeRange = (chartSamples.last().recordedAt - firstTime).coerceAtLeast(1L)
        val path = Path()
        chartSamples.forEachIndexed { index, sample ->
            val x = (sample.recordedAt - firstTime).toFloat() / timeRange * size.width
            val y = size.height * (1f - sample.batteryLevel / 100f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun BatteryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun EmptyBatteryHistory(message: String) {
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun List<BatteryHistoryEntry>.downsampleForChart(maxPoints: Int = 120): List<BatteryHistoryEntry> {
    if (size <= maxPoints) return this
    val step = (size - 1).toDouble() / (maxPoints - 1)
    return List(maxPoints) { index -> this[(index * step).roundToInt()] }
}

private fun formatDrainPerDay(value: Double): String = (value * 10).roundToInt().div(10.0).toString()

private fun formatRemainingTime(hours: Double): String {
    val roundedHours = hours.roundToInt().coerceAtLeast(0)
    val days = roundedHours / 24
    val remainingHours = roundedHours % 24
    return if (days > 0) "$days d $remainingHours h" else "$remainingHours h"
}
