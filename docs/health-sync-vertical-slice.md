# Pebble Time 2 → Apple Health: heart-rate export and workout capture

## Scope

This slice uses the existing Core Devices mobile app Bluetooth connection and libpebble3 health
Datalogging. It does not open a second Bluetooth connection and it does not depend on a phone
being connected when the watch collects data.

The first, minute-level path uses the watch firmware's system-health stream:

1. The watch persists minute health data and delivers it through Datalogging when the existing
   companion connection synchronizes.
2. `HealthDataProcessor` parses and deduplicates that data into the mobile Room database.
3. `PlatformHealthSync` independently checkpoints valid heart-rate points.
4. On iOS, `NativeHeartRateExporter` writes one `HKQuantitySample` per point to Apple Health.

The optional `watchapps/health-capture` Emery watch app adds a separate, high-resolution workout
path. Starting a workout in its foreground app launches a worker that requests a 1, 5, or 15
second rate (5 seconds by default), captures the HealthService readings delivered to apps, and
writes versioned 16-byte records using DataLogging. The worker has no Bluetooth connection of its
own. The companion persists each received packet before ACKing it, then exports filtered samples to
Apple Health in batches of 100. Neither path uses AppMessage for sensor data.

The Pebble SDK does not promise that a `HealthEventHeartRateUpdate` corresponds to every internal
sensor measurement. “High-resolution” here means every API-visible event, bounded by the selected
recording interval; it must be verified on real Time 2 hardware.

## HealthKit contract

| Field | Value |
| --- | --- |
| Type | `HKQuantityTypeIdentifierHeartRate` |
| Unit | `HKUnit.countUnit() / HKUnit.minuteUnit()` (`count/min`) |
| Timestamp | The Pebble minute-record or worker-record UTC timestamp; point samples use identical start/end dates |
| Source | Set automatically by HealthKit to this mobile app's `HKSourceRevision` |
| Sync identity | `HKSyncIdentifier=coredevices.pebble.health.hr.v1.<source record ID>` |
| Sync version | `HKSyncVersion=1` |
| Extra metadata | External UUID plus Pebble sequence timestamp and device label |
| Duplicate behavior | Minute records retain their timestamp identity; worker records use persistent workout + sequence IDs. A retry uses the same sync identifier/version. |

The app requests only sharing permission for the vertical-slice heart-rate writer. Therefore it
does not inspect other Apple Health sources and the export screen truthfully reports conflicts as
“Not checked”.

## Local Pebble data export

**Health → Export Pebble health data** creates and opens an iOS share sheet for a local ZIP. It
does not upload health data. Choose a rolling **7-day**, **30-day**, or **six-month (180-day)** UTC
window. The ZIP is deliberately split into small, interoperable CSVs instead of one wide file:

| File | Contents |
| --- | --- |
| `minute_health.csv` | Every persisted system-health minute record: movement, calories, distance, and any stored HR/zone values. A zero HR means no HR value was stored for that minute. |
| `workout_heart_rate.csv` | Every received Health Capture worker record, including filtered BPM, raw diagnostic BPM, flags, UTC times, persistent workout/sequence IDs, and Apple Health export state. |
| `sleep_and_activity.csv` | Every persisted overlay interval overlapping the range: sleep, deep sleep, naps, walking, running, and open workouts. |
| `beat_to_beat.csv` | Header-only placeholder. The current worker does **not** collect PPI/IBI/RR beat-to-beat intervals. Raw BPM is not beat-to-beat data. |
| `manifest.json` / `README.txt` | Record counts, exact UTC bounds, field definitions, and availability caveats. |

The archive represents data that the phone received from Pebble and stored locally. It is not an
Apple Health export and cannot include watch-sensor values that Pebble's public API never exposed.

## Manual test on Pebble Time 2 and iPhone

1. On macOS, follow the repository's iOS build setup, set a development signing team, and run the
   app on a physical iPhone. The HealthKit entitlement and usage descriptions are in the project.
2. Pair the Pebble Time 2 with this fork. Do not allow another companion app to own the connection
   while testing.
3. Install the `watchapps/health-capture` `.pbw` built with the Emery-capable Pebble SDK. On the
   watch, open **Health Capture**, choose 5 seconds with Up/Down, and press Select to start a
   workout. Approve it as the Background App if Pebble asks. Do not start another worker while it
   is active.
4. Wear the watch for at least five minutes, stop the workout with Select, then allow the normal
   companion DataLogging sync to complete. Records may transfer while the session is active or as
   a batch afterwards; either way, no phone connection is required for the watch to retain them.
5. Open **Health → Apple Health export status**, choose **Allow Apple Health export**, and grant
   Heart Rate sharing. Press **Sync now**.
6. Check the status page: platform is available, permission is allowed, **Pending workout HR
   records** becomes zero, failed records is zero, and the last successful sync advances.
7. Open **Health → Export Pebble health data**, select **Past 7 days**, and save or share the ZIP.
   Confirm that it includes the six files described above; confirm `workout_heart_rate.csv` has
   the received workout rows and `beat_to_beat.csv` is header-only. Repeat with 30 days and six
   months to check the requested range and record counts in `manifest.json`.
8. In Apple Health, inspect Heart Rate and confirm filtered points at the Pebble timestamps with
   this app as source. Measure their spacing and check for repeated/stale values; do not assume the
   requested 5-second cadence was delivered. Press **Sync now** a second time and confirm that
   Apple Health does not gain duplicate points.
9. Only after step 8 succeeds, open Bevel and check whether those Apple Health records are visible.
   Record the iOS version, app build, timestamp, Apple Health screenshot, and Bevel result. A
   missing Bevel display is a test failure/compatibility finding, not evidence that the export
   succeeded.

## Sampling and HRV gates

The Pebble SDK documents a default 10-minute adaptive HR sample period and permits a watch app to
*request* 1–600 seconds, without guaranteeing the actual period. The worker's 5-second default is
a conservative experiment, not a proven production recommendation. Run 1-, 5-, and 15-second
trials; capture actual timestamp spacing, battery loss per hour, sensor quality, dropped-log counts,
and watch temperature. Select the lowest-impact cadence that still meets workout-chart requirements.
The 1-second option matches Pebble's built-in workout request but must not be described as
high-quality or battery-safe until this validation is complete.

Apple Health's HRV type is SDNN in milliseconds. Current PebbleOS source contains an opt-in PPI
(peak-to-peak interval) API, but this worker does not yet collect it. First verify that the shipped
Time 2 firmware exposes it, capture enough quality-controlled overnight intervals, and validate the
SDNN algorithm. Do not write RMSSD values into the SDNN type or claim Bevel compatibility until a
physical Apple Health + Bevel test passes.

## Confirmed HealthKit mapping for later slices

| Metric | HealthKit type | Unit / bounds | Export condition |
| --- | --- | --- | --- |
| Heart rate | `HKQuantityTypeIdentifierHeartRate` | `count/min`, point time | Implemented in this slice |
| Resting heart rate | `HKQuantityTypeIdentifierRestingHeartRate` | `count/min`, point time | Export only an algorithmically stable, sleep-bounded value |
| HRV | `HKQuantityTypeIdentifierHeartRateVariabilitySDNN` | milliseconds, point time | Export SDNN only; RMSSD is not an interchangeable value |
| Sleep | `HKCategoryTypeIdentifierSleepAnalysis` | interval category samples | Preserve session/stage boundaries and attach time-zone metadata |
| Workout | `HKWorkout` | bounded start/end interval | Add its HR samples with `HKHealthStore.add(_:to:completion:)` |

HealthKit sets the app source when an object is saved; clients must not attempt to invent a source.
For a point sample its end date equals its start date. The relevant primary documentation is:

- https://developer.apple.com/documentation/healthkit/about-the-healthkit-framework
- https://developer.apple.com/documentation/healthkit/hkquantitytypeidentifier/heartratevariabilitysdnn
- https://developer.apple.com/documentation/healthkit/adding-samples-to-a-workout
- https://developer.apple.com/documentation/healthkit/hkmetadatakeysyncidentifier

## Worker implementation status

The watch source is in `watchapps/health-capture`. Its binary record format, DataLogging receiver,
Room deduplication, and Apple Health write path are implemented in this fork. They have not yet
been compiled with the current Emery SDK or exercised on a physical Time 2/iPhone, so cadence,
battery behavior, and Bevel visibility remain validation gates rather than completion claims.
