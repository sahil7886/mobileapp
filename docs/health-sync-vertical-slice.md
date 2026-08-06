# Pebble Time 2 → Apple Health: first vertical slice

## Scope

This slice uses the existing Core Devices mobile app Bluetooth connection and libpebble3 health
Datalogging. It does not open a second Bluetooth connection and it does not depend on a phone
being connected when the watch collects data.

The watch firmware's system-health stream is the source for this slice:

1. The watch persists minute health data and delivers it through Datalogging when the existing
   companion connection synchronizes.
2. `HealthDataProcessor` parses and deduplicates that data into the mobile Room database.
3. `PlatformHealthSync` independently checkpoints valid heart-rate points.
4. On iOS, `NativeHeartRateExporter` writes one `HKQuantitySample` per point to Apple Health.

No high-rate raw stream uses AppMessage.

## HealthKit contract

| Field | Value |
| --- | --- |
| Type | `HKQuantityTypeIdentifierHeartRate` |
| Unit | `HKUnit.countUnit() / HKUnit.minuteUnit()` (`count/min`) |
| Timestamp | The Pebble minute-record UTC timestamp; point samples use identical start/end dates |
| Source | Set automatically by HealthKit to this mobile app's `HKSourceRevision` |
| Sync identity | `HKSyncIdentifier=coredevices.pebble.health.hr.v1.<timestamp>` |
| Sync version | `HKSyncVersion=1` |
| Extra metadata | External UUID plus Pebble sequence timestamp and device label |
| Duplicate behavior | Retry has the same sync identifier/version; HealthKit treats it as the same synchronized object |

The app requests only sharing permission for the vertical-slice heart-rate writer. Therefore it
does not inspect other Apple Health sources and the export screen truthfully reports conflicts as
“Not checked”.

## Manual test on Pebble Time 2 and iPhone

1. On macOS, follow the repository's iOS build setup, set a development signing team, and run the
   app on a physical iPhone. The HealthKit entitlement and usage descriptions are in the project.
2. Pair the Pebble Time 2 with this fork. Do not allow another companion app to own the connection
   while testing.
3. In the app's Health settings, enable Health Tracking and Heart Rate Monitor. For an activity
   test, enable **HR During Activities**. Keep the default background cadence initially.
4. Wear the watch until it records a heart-rate sample, then let the normal health sync complete.
   The existing health debug action can request a full history sync when needed.
5. Open **Health → Apple Health export status**, choose **Allow Apple Health export**, and grant
   Heart Rate sharing. Press **Sync now**.
6. Check the status page: platform is available, permission is allowed, pending records becomes
   zero, failed records is zero, and the last successful sync advances.
7. In Apple Health, inspect Heart Rate and confirm a point at the Pebble timestamp with this app as
   source. Press **Sync now** a second time and confirm that Apple Health does not gain a duplicate
   point.
8. Only after step 7 succeeds, open Bevel and check whether that Apple Health record is visible.
   Record the iOS version, app build, timestamp, Apple Health screenshot, and Bevel result. A
   missing Bevel display is a test failure/compatibility finding, not evidence that the export
   succeeded.

## Sampling and HRV gates

The Pebble SDK documents a default 10-minute adaptive HR sample period and permits a watch app to
*request* 1–600 seconds, without guaranteeing the actual period. The practical Time 2 cadence and
battery impact must be measured on hardware before changing firmware defaults. Start a workout
trial at 5 seconds, 15 seconds, 30 seconds, and 60 seconds; capture actual timestamp spacing,
battery loss per hour, sensor quality, and watch temperature. Select the lowest-impact cadence that
still meets workout-chart requirements.

Apple Health's HRV type is SDNN in milliseconds. The documented Pebble Health API supplies BPM and
minute history, not RR intervals, so this slice cannot honestly calculate or export overnight HRV.
Do not write RMSSD values into the SDNN type and do not claim Bevel compatibility until a physical
Apple Health + Bevel test passes.

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

## Next watch-side implementation

The dedicated workout worker should declare the `health` capability, use `HealthService`, use
`DataLogging` for local-first batched transfer, and persist a monotonically increasing sequence ID
plus an acknowledged export checkpoint. It must reset any elevated
`health_service_set_heart_rate_sample_period` request when the workout ends or the worker exits.
