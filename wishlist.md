# Pebble Time 2 health-sync wishlist

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

## Partial / requires physical validation

- [~] Heart rate during activities and time in zones: the worker records more granular filtered
  readings during manually started workouts. Verify Time 2 event frequency, battery impact, sensor
  quality, and Apple Health export; HR samples are not yet associated with `HKWorkout` records.
- [~] Sleep detection: existing sleep/deep-sleep overlays export through the cross-platform path;
  validate boundaries and quality before changing the algorithm.
- [~] Workout detection: existing walk/run/open-workout overlays export as workouts. Detection
  quality, HR associations, and duplicate workout handling still require device testing.
- [~] Resting heart rate: the app calculates a sleep-based value locally, but does not yet export
  `HKQuantityTypeIdentifierRestingHeartRate`.
- [~] Completeness: the first slice exports heart rate to Apple Health. Steps, sleep, and workouts
  retain their existing HealthKMP export path and need end-to-end Apple Health validation.

## Blocked / do not claim until verified

- [ ] Production workout HR cadence: the worker can request 1/5/15 seconds, but the SDK treats
  the value as a battery- and quality-dependent suggestion. Determine a Time 2 battery budget and
  observed timestamp spacing before choosing a default.
- [ ] Overnight HRV: current PebbleOS source contains an opt-in PPI (peak-to-peak interval) API,
  but the worker does not yet collect it. Verify Time 2 firmware support, interval quality, and a
  valid SDNN algorithm before writing `HKQuantityTypeIdentifierHeartRateVariabilitySDNN`.
- [ ] Bevel HRV: Apple Health requires HRV as SDNN milliseconds. Direct overnight SDNN export and
  Bevel display remain unverified; do not claim either until tested in Apple Health and Bevel.
- [ ] Bevel heart-rate display: Apple Health acceptance is implemented, but appearance in Bevel is
  not yet verified on a physical iPhone.
