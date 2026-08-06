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

## Partial / requires physical validation

- [~] Heart rate during activities and time in zones: the existing firmware already exposes
  activity HR tracking and per-minute zones. Verify it on Pebble Time 2, then associate exported
  heart-rate points with workout records in the native HealthKit writer.
- [~] Sleep detection: existing sleep/deep-sleep overlays export through the cross-platform path;
  validate boundaries and quality before changing the algorithm.
- [~] Workout detection: existing walk/run/open-workout overlays export as workouts. Detection
  quality, HR associations, and duplicate workout handling still require device testing.
- [~] Resting heart rate: the app calculates a sleep-based value locally, but does not yet export
  `HKQuantityTypeIdentifierRestingHeartRate`.
- [~] Completeness: the first slice exports heart rate to Apple Health. Steps, sleep, and workouts
  retain their existing HealthKMP export path and need end-to-end Apple Health validation.

## Blocked / do not claim until verified

- [ ] More-frequent background HR sampling: current firmware exposes 10-minute, 30-minute, and
  hourly preferences, while the SDK lets an app request 1-600 second sampling only as a battery-
  and quality-dependent suggestion. Determine a Pebble Time 2 battery budget with a real watch
  before selecting a production cadence.
- [ ] Dedicated Pebble watch app/worker: design it around HealthService + Datalogging with local
  sequence IDs and storage checkpoints. Do not use AppMessage for its raw HR stream.
- [ ] Overnight HRV: Pebble's documented HealthService gives BPM and minute history, not beat-to-
  beat intervals. A valid SDNN computation needs an experimentally verified interval source.
- [ ] Bevel HRV: Apple Health requires HRV as SDNN milliseconds. Direct overnight SDNN export and
  Bevel display remain unverified; do not claim either until tested in Apple Health and Bevel.
- [ ] Bevel heart-rate display: Apple Health acceptance is implemented, but appearance in Bevel is
  not yet verified on a physical iPhone.
