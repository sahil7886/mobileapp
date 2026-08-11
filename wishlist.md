# Pebble Time 2 health-sync wishlist

## iPhone-only fork foundation

- [x] Make the active Gradle graph and CI iOS-only; Android SDK/NDK and Android signing are no
  longer build requirements.
- [x] Remove Firebase, Google Sign-In, Mixpanel, and Firebase Crashlytics from the active iOS
  dependency graph. Local SQLite, file diagnostics, and the Xcode device console remain.
- [x] Keep the public app/watchface catalog and normal Bluetooth installation path. Store installed
  apps in `libpebble3`'s local SQLite Locker instead of a cloud Locker mirror.
- [~] Retain native Sign in with Apple as a device-local identity marker. Validate it with a signed
  iPhone build; it intentionally does not restore data from another device.
- [~] Retain native APNs registration and receipt without Firebase. A direct APNs provider has not
  been implemented, so do not claim server-driven push delivery yet.
- [ ] Cloud-only features whose existing servers require the removed account token (Locker backup,
  support inbox, developer contact, cloud battery dashboard, and cloud transcription) need a
  separate direct account-service design before they can return.

## Completed in the first vertical slice

- [x] Preserve the Core Devices mobile app as the only Bluetooth connection owner; health data
  continues to arrive via libpebble3's existing system-health Datalogging path.
- [x] Export buffered Pebble heart-rate points from the mobile database to Apple Health on iOS.
- [x] Use point timestamps, `count/min` units, HealthKit source attribution, sync identifiers,
  sync versions, sequence metadata, and a separate durable heart-rate checkpoint.
- [x] Make phone-side heart-rate export retryable and idempotent after a failed or interrupted
  write; a checkpoint advances only after HealthKit accepts the batch.
- [x] Add Apple Health export status for availability, write permission, last successful sync,
  pending records, failures, last error, and the intentionally uninspected source-conflict state.
- [x] Add diagnostics for HealthKit writes and retained checkpoints after failures.
- [x] Add an Emery Health Capture watch app + background worker that records filtered/raw workout
  HR with UTC timestamps and persistent sequence IDs through local-first Datalogging.
- [x] Persist worker records before DataLogging ACK, deduplicate them by workout + sequence ID, and
  export filtered points through replay-safe HealthKit sync identifiers.
- [~] Extend PebbleOS's built-in Workout service with a buffered one-second heart-rate stream,
  persistent sequence IDs, sensor-quality diagnostics, and an exact terminal timestamp. The phone
  pairs it with the existing ActivitySession and creates one native Apple Health workout with its
  BPM samples attached. Build/flash and physical iPhone validation remain outstanding.
- [~] Add Time 2 built-in Workout PPI/RR capture: the firmware requests the optional HRV feature
  only while a manual Workout is active, buffers every accepted PPI with quality and stable IDs,
  and the phone stores/deduplicates it before DataLogging ACK. The ZIP export now includes raw
  `beat_to_beat.csv` rows. Firmware builds pass; physical transfer, battery, and interval-quality
  validation remain outstanding.
- [x] Add an on-device, shareable ZIP export for rolling 7-day, 30-day, and six-month windows.
  It contains separate CSVs for all locally persisted minute health, received workout HR (filtered
  and raw diagnostic BPM), and sleep/activity overlays, plus a manifest and field documentation.
  It includes a raw beat-to-beat CSV when the HRV-enabled built-in Workout stream has collected
  accepted PPI/RR values.

## Partial / requires physical validation

- [~] Heart rate during activities and time in zones: the built-in Workout requests one-second
  readings and already calculates zones on-watch; the new stream persists its API-visible BPM
  points and the iOS exporter attaches them to the matching `HKWorkout`. Verify Time 2 event
  frequency, battery impact, sensor quality, Apple Health association, and retry behavior.
- [~] Sleep detection: existing sleep/deep-sleep overlays export through the cross-platform path;
  validate boundaries and quality before changing the algorithm.
- [~] Workout detection: existing walk/run/open-workout overlays export as workouts. Manual
  built-in Workout HR association is implemented; automatic-detection quality and duplicate
  handling still require device testing.
- [~] Resting heart rate: the app calculates a sleep-based value locally, but does not yet export
  `HKQuantityTypeIdentifierRestingHeartRate`.
- [~] Completeness: the first slice exports heart rate to Apple Health. Steps, sleep, and workouts
  retain their existing HealthKMP export path and need end-to-end Apple Health validation.

## Blocked / do not claim until verified

- [ ] Production workout HR cadence: the worker can request 1/5/15 seconds, but the SDK treats
  the value as a battery- and quality-dependent suggestion. Determine a Time 2 battery budget and
  observed timestamp spacing before choosing a default.
- [ ] Overnight HRV: PPI is now captured only during a manually started built-in Workout. Add a
  low-power sleep-specific capture policy, validate interval quality, and validate a SDNN
  algorithm before writing `HKQuantityTypeIdentifierHeartRateVariabilitySDNN`.
- [ ] Bevel HRV: Apple Health requires HRV as SDNN milliseconds. Direct overnight SDNN export and
  Bevel display remain unverified; do not claim either until tested in Apple Health and Bevel.
- [ ] Bevel heart-rate display: Apple Health acceptance is implemented, but appearance in Bevel is
  not yet verified on a physical iPhone.
