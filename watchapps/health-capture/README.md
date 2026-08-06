# Health Capture worker

`Health Capture` is a Pebble Time 2 (Emery) watch app plus background worker for locally buffered
workout heart-rate collection. It deliberately uses the existing companion app's DataLogging
connection; it does not open a second Bluetooth connection and does not use AppMessage for sensor
data.

## What it records

Starting a workout from the foreground app starts the worker and requests a 1, 5, or 15 second
heart-rate sampling period. The default is 5 seconds until battery and quality trials establish a
better production value. The 1-second setting is a controlled test mode, not a battery-life claim.
Pebble treats this period as a request, not a guarantee.

For each available heart-rate update the worker records one 16-byte, little-endian item:

| Bytes | Value |
| --- | --- |
| `0..3` | persisted, monotonically increasing workout-session ID |
| `4..7` | persistent, monotonically increasing sequence ID |
| `8..11` | sample UTC epoch second |
| `12` | filtered BPM |
| `13` | raw BPM (diagnostics only) |
| `14` | availability/workout flags |
| `15` | record format version (`1`) |

The mobile app only exports valid **filtered** BPM values to Apple Health. Raw BPM is retained for
diagnostics and is not treated as a clinical-quality measurement. The Pebble API can deliver an
event before a newly filtered value is available, so physical testing must check timestamps and
repeated values before interpreting this stream as a high-quality workout trace.

## Safety and limits

- The worker requests faster sampling only while a manually started workout is active and resets
  the request whenever it stops or exits.
- It uses a resumable Datalogging session, so records stay on the watch until the phone's existing
  companion connection successfully persists them. Mobile retries use the workout + sequence ID
  both for database deduplication and Apple Health idempotency.
- Pebble permits only one background worker. Starting this worker can replace another app's worker
  only after the user approves it on the watch.
- The worker does not yet calculate or export HRV. Current PebbleOS source includes a newer,
  opt-in PPI (peak-to-peak interval) API, but the shipped Time 2 firmware, data quality, valid
  SDNN calculation, and Apple Health/Bevel behaviour must be validated before it is enabled.

## Build and install

Install the current Pebble SDK with Emery support, then run `pebble build` in this directory. The
resulting `.pbw` must be installed on the Time 2 through the same forked mobile companion app that
contains the matching `HealthCaptureProtocol` receiver.

Use a physical Time 2 for testing: current emulator/simulator environments cannot establish the
sensor and DataLogging behavior needed to validate this worker.
