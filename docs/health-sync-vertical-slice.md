# Pebble Time 2 → Apple Health: heart rate, workouts, and overnight SDNN

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

The production high-resolution workout path is in the PebbleOS built-in **Workout** service. It
already requests one-second BPM updates while a normal workout is active. This fork retains its
ordinary `ActivitySession` summary (the record that creates the normal Walk/Run/Workout in the
app), and adds a buffered, versioned 16-byte DataLogging stream keyed by that exact session start
time. At stop it appends a zero-BPM terminal record containing the exact stop time, finishes the
local log, and transfers through the existing companion connection. The phone persists packets
before ACKing, waits for the matching standard session, then creates one native `HKWorkout` with
those heart-rate samples attached. Neither path uses AppMessage for sensor data.

For a Time 2 firmware build with `CONFIG_HRM_HRV=y`, the same built-in Workout subscription adds
the HRV feature only while a manual Workout is running. Every accepted PPI/RR interval then goes
to a second buffered, versioned 16-byte DataLogging session. It retains its own sequence because
more than one interval can arrive in a UTC second; its zero-interval terminal record is a transfer
commit marker, not a heartbeat. The phone stores it idempotently in `beat_to_beat` and never
fabricates PPI from BPM.

The system Activity service now also captures an overnight classifier-input stream. Between **21:00
and 12:00 local time**, it activates only while the existing Pebble motion-derived sleep state is
active and the existing heart-rate setting is enabled. It requests the HRM manager's continuous
PPI path; the hardware still decides which readings it can accept. Every accepted API-visible PPI
is retained with its reported quality, while BPM/quality is recorded once per 30 seconds to keep a
full phone-disconnected night within the watch's shared DataLogging storage. The Activity service
already owns a 25 Hz accelerometer subscription, so the capture adds no second motion subscription:
it writes a compact movement-energy summary for each 30-second epoch. All four record kinds
(session, PPI, BPM, motion) use one compact 14-byte, versioned DataLogging format with session IDs,
sequence IDs, completion records, and an explicit dropped-record count.

The existing Pebble motion-derived sleep/deep-sleep overlays are intentionally retained. The new
stream is raw classifier input and does not replace those labels or create new Apple Health sleep
stages. Once a capture session carries its terminal completion record, the phone quality-filters
the stored PPI intervals into non-overlapping five-minute **SDNN** windows and writes only those
aggregates to Apple Health. Raw PPI remains local for export and diagnostics.

`watchapps/health-capture` remains a separate prototype and diagnostic tool. It is not required for
the built-in Workout path and does not create the normal Pebble activity session.

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
| Sync identity | Every HR point has `HKSyncIdentifier=coredevices.pebble.health.hr.v1.<source record ID>`; a built-in workout has its own `coredevices.pebble.health.workout.v1.<workout ID>` identity |
| Sync version | `HKSyncVersion=1` |
| Extra metadata | External UUID plus Pebble sequence timestamp and device label |
| Duplicate behavior | Minute records retain their timestamp identity; detailed rows use persistent workout + sequence IDs. The native workout and every point carry stable sync metadata; validate an interrupted retry on a physical iPhone before treating it as proven. |

For overnight HRV, the writer uses `HKQuantityTypeIdentifierHeartRateVariabilitySDNN` with
`HKUnit.secondUnitWithMetricPrefix(HKMetricPrefixMilli)` and a five-minute start/end interval.
Every aggregate carries an algorithm version plus an `HKSyncIdentifier`/`HKSyncVersion` pair, so
HealthKit can reconcile a replay by stable identity. HealthKit assigns this app as the source; the
client does not claim to be an Apple Watch. The local `overnight_hrv` row retains the PPI count,
quality coverage, artifact rejects, temporal coverage, calculation window, and algorithm version.

The app requests only sharing permission for the heart-rate, workout, and HRV writers. Therefore
it does not inspect other Apple Health sources and the export screen truthfully reports conflicts
as “Not checked”.

## Local Pebble data export

**Health → Export Pebble health data** creates and opens an iOS share sheet for a local ZIP. It
does not upload health data. Choose a rolling **7-day**, **30-day**, or **six-month (180-day)** UTC
window. The ZIP is deliberately split into small, interoperable CSVs instead of one wide file:

| File | Contents |
| --- | --- |
| `minute_health.csv` | Every persisted system-health minute record: movement, calories, distance, and any stored HR/zone values. A zero HR means no HR value was stored for that minute. |
| `workout_heart_rate.csv` | Every received Health Capture or built-in Workout record, including BPM, flags, built-in sensor quality where available, UTC times, persistent workout/sequence IDs, and Apple Health export state. A built-in terminal row has `filtered_bpm=0` and records the precise workout end. |
| `sleep_and_activity.csv` | Every persisted overlay interval overlapping the range: sleep, deep sleep, naps, walking, running, and open workouts. |
| `beat_to_beat.csv` | Every accepted PPI/RR interval received from the built-in Workout, including Workout/sequence ID, timestamp, milliseconds, sensor quality, flags, and receipt time. A final zero-interval row is the exact Workout-stop commit marker. |
| `sleep_capture.csv` | Overnight system Activity capture records: PPI, BPM and quality, 30-second movement energy, and session/drop diagnostics. PPI timestamps have the watch API's second precision; the file does not claim millisecond beat timestamps or sleep stages. |
| `overnight_hrv_sdnn.csv` | Quality-filtered, five-minute overnight SDNN calculations, including source session, PPI/quality coverage, artifact rejects, algorithm version, and Apple Health export state. It never contains RMSSD. |
| `manifest.json` / `README.txt` | Record counts, exact UTC bounds, field definitions, and availability caveats. |

The archive represents data that the phone received from Pebble and stored locally. It is not an
Apple Health export and cannot include watch-sensor values that Pebble's public API never exposed.

## Manual test on Pebble Time 2 and iPhone

1. On macOS, follow the repository's iOS build setup, set a development signing team, and run the
   app on a physical iPhone. The HealthKit entitlement and usage descriptions are in the project.
2. Pair the Pebble Time 2 with this fork. Do not allow another companion app to own the connection
   while testing.
3. Build and flash this PebbleOS fork for the board revision of the physical Time 2. The supported
   Time 2 board variants are listed in `../PebbleOS/docs/development/options.md`; do not flash a
   firmware image for a different hardware revision. This fork has passed a release `obelix@pvt`
   build with `CONFIG_HRM_HRV=y`, but has not been flashed in this environment. No additional
   watch app is installed for this test.
4. On the watch, start an ordinary **Workout** and wear it for more than one minute—the existing
   session workflow intentionally does not save sub-minute workouts. Stop it normally. The watch
   retains the detailed log locally whether or not the phone was connected.
5. Reconnect the companion and allow DataLogging to complete. Open **Health → Apple Health export
   status**, choose **Allow Apple Health export**, and grant Heart Rate, Workout, and **Heart Rate
   Variability** sharing. Press **Sync now**.
6. Check the status page: platform is available, permission is allowed, **Pending workout HR
   records** becomes zero, failed records is zero, and the last successful sync advances.
7. In Apple Health, confirm one Pebble-sourced Workout at the correct start/end times, then inspect
   its heart-rate chart. Confirm the points are approximately one second apart where the sensor
   delivered them. Repeat Sync now after a forced app close/reopen and confirm no duplicate
   workout or point is created.
8. Open **Health → Export Pebble health data**, select **Past 7 days**, and save or share the ZIP.
   Confirm that `workout_heart_rate.csv` contains `builtin-workout-v1` IDs, BPM rows, and a final
   zero-BPM completion row at the actual stop time. Confirm `beat_to_beat.csv` contains
   `builtin-workout-ppi-v1` rows, each nonzero interval in milliseconds, and a final zero-interval
   completion row. Re-run sync or force-close/reopen the phone app and verify the row IDs are not
   duplicated.
9. Only after step 7 succeeds, open Bevel and check whether those Apple Health records are visible.
   Record the iOS version, app build, timestamp, Apple Health screenshot, and Bevel result. A
   missing Bevel display is a test failure/compatibility finding, not evidence that the export
   succeeded.

## Sampling and HRV gates

The Pebble SDK documents a default 10-minute adaptive HR sample period and permits a watch app to
*request* 1–600 seconds, without guaranteeing the actual period. The built-in Workout service
requests one-second BPM updates; the new log records no more than one accepted API event per UTC
second. Measure actual timestamp spacing, battery loss per hour, sensor quality, dropped-log
counts, and watch temperature before calling one-second sampling production-safe.

Apple Health's HRV type is SDNN in milliseconds. The Time 2 PPI stream is **not** written to Apple
Health directly: raw PPI is an input series, while HealthKit accepts an SDNN aggregate. The phone
now calculates algorithm-versioned five-minute SDNN windows only from *completed* overnight
sessions. A window needs at least 180 intervals, at least 80% watch-quality/range acceptance, at
least 75% interval-time coverage, and rejects an interval that jumps more than 20% from the last
accepted interval. The standard deviation uses the accepted-window population denominator. This
is a conservative PPG-derived estimate, not an ECG rhythm classifier. Do not write RMSSD values
into the SDNN type or claim Bevel compatibility until a physical Apple Health + Bevel test passes.

### Overnight capture test for tonight

1. Before bed, charge the Time 2 to a recorded percentage and ensure **Settings → Health → Heart
   Rate** remains enabled. Do not start Health Capture or a Workout; this is the normal background
   sleep path.
2. Wear the watch normally through the night. Capture begins after the existing Pebble
   motion-derived sleep detector enters light or restful sleep, and ends when it returns awake
   (within the 21:00–12:00 local window). This keeps the full detected sleep interval in the
   watch's shared local log while the phone is unavailable.
3. Record the battery percentage immediately before sleep and after waking. Keep the companion
   disconnected for part of the night if desired; the watch must retain data locally either way.
4. After noon, reconnect the iPhone fork. In **Health → Apple Health export status**, refresh the
   status and note **Overnight classifier inputs**, **Pending overnight SDNN records**, and
   **Apple Health SDNN records**. Then use the 7-day ZIP export and inspect `sleep_capture.csv`:
   it should contain `session`, `ppi`, `bpm`, and `motion_epoch` rows. If the quality/coverage
   gates accepted a window, `overnight_hrv_sdnn.csv` will contain an SDNN aggregate. A terminal
   session record that reports dropped watch-buffer rows intentionally suppresses HRV export.
5. In Apple Health, check Browse → Heart → Heart Rate Variability and confirm the Pebble-sourced
   SDNN sample has the correct five-minute window and millisecond unit. Re-run **Sync now** after
   force-closing the app and confirm it does not create a second record. Only then set Bevel to
   Apple Health SDNN and test its display; this repository does not claim Bevel compatibility
   until that physical test succeeds.
6. Compare the same date's existing `sleep_and_activity.csv` motion-derived deep/light labels with
   the new raw inputs. For this first test, compare capture coverage and battery use—not stage
   accuracy. A stage classifier has not yet been trained or validated.

## Confirmed HealthKit mapping for later slices

| Metric | HealthKit type | Unit / bounds | Export condition |
| --- | --- | --- | --- |
| Heart rate | `HKQuantityTypeIdentifierHeartRate` | `count/min`, point time | Implemented in this slice |
| Resting heart rate | `HKQuantityTypeIdentifierRestingHeartRate` | `count/min`, point time | Export only an algorithmically stable, sleep-bounded value |
| HRV | `HKQuantityTypeIdentifierHeartRateVariabilitySDNN` | milliseconds, five-minute interval | Implemented for completed, quality-gated overnight SDNN only; RMSSD is not interchangeable and Bevel validation remains required |
| Sleep | `HKCategoryTypeIdentifierSleepAnalysis` | interval category samples | Preserve session/stage boundaries and attach time-zone metadata |
| Workout | `HKWorkout` | built-in Workout start + terminal stop timestamp | Implemented with `HKWorkoutBuilder` and detailed BPM samples; physical retry/Bevel validation remains required |

HealthKit sets the app source when an object is saved; clients must not attempt to invent a source.
For a point sample its end date equals its start date. The relevant primary documentation is:

- https://developer.apple.com/documentation/healthkit/about-the-healthkit-framework
- https://developer.apple.com/documentation/healthkit/hkquantitytypeidentifier/heartratevariabilitysdnn
- https://developer.apple.com/documentation/healthkit/adding-samples-to-a-workout
- https://developer.apple.com/documentation/healthkit/hkmetadatakeysyncidentifier

## Built-in Workout implementation status

The PebbleOS built-in Workout source, phone Datalogging receiver, and iOS `HKWorkoutBuilder` path
are implemented in this fork. Release builds passed for `qemu_emery` and HRV-enabled `obelix@pvt`.
They have not been flashed or exercised on a physical Time 2/iPhone in this environment. Treat
sampling cadence, battery impact, PPI quality, Apple Health association, retry behavior, and Bevel
display as validation gates, not completed claims.

## Health Capture prototype status

The source is in `watchapps/health-capture`. Its binary record format, DataLogging receiver, Room
deduplication, and standalone Apple Health point writer remain available for protocol experiments.
It is not the built-in workout implementation.
