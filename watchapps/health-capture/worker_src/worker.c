#include <pebble_worker.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <time.h>

// Must stay in sync with src/main.c and HealthCaptureProtocol.kt in the mobile app.
#define HC_PERSIST_ACTIVE 100
#define HC_PERSIST_PERIOD_SECONDS 101
#define HC_PERSIST_WORKOUT_ID 102
#define HC_PERSIST_SEQUENCE 103
// Key 104 is the foreground app's monotonically increasing workout-session counter.

#define HC_MESSAGE_START 1
#define HC_MESSAGE_STOP 2

#define HC_RECORD_TAG 0x48524331UL  // ASCII HRC1
#define HC_RECORD_VERSION 1
#define HC_DEFAULT_PERIOD_SECONDS 5

#define HC_FLAG_WORKOUT_ACTIVE (1 << 0)
#define HC_FLAG_FILTERED_AVAILABLE (1 << 1)
#define HC_FLAG_RAW_AVAILABLE (1 << 2)

typedef struct __attribute__((__packed__)) {
  uint32_t workout_id;
  uint32_t sequence;
  uint32_t timestamp;
  uint8_t filtered_bpm;
  uint8_t raw_bpm;
  uint8_t flags;
  uint8_t version;
} HealthCaptureRecord;

typedef char HealthCaptureRecordMustBe16Bytes[
    sizeof(HealthCaptureRecord) == 16 ? 1 : -1];

static DataLoggingSessionRef s_log_session;
static bool s_workout_active;
static uint16_t s_sample_period_seconds;
static uint32_t s_workout_id;
static uint32_t s_sequence;
static time_t s_last_logged_at;
static bool s_health_subscribed;

static uint16_t prv_read_period(void) {
  const int stored = persist_exists(HC_PERSIST_PERIOD_SECONDS)
      ? persist_read_int(HC_PERSIST_PERIOD_SECONDS) : HC_DEFAULT_PERIOD_SECONDS;
  // The supported range is 1..600. The foreground app starts at a conservative 5 s.
  return (stored >= 1 && stored <= 600) ? (uint16_t)stored : HC_DEFAULT_PERIOD_SECONDS;
}

static uint32_t prv_read_sequence(void) {
  uint32_t value = 0;
  return persist_read_data(HC_PERSIST_SEQUENCE, &value, sizeof(value)) == sizeof(value) ? value : 0;
}

static void prv_store_sequence(uint32_t sequence) {
  persist_write_data(HC_PERSIST_SEQUENCE, &sequence, sizeof(sequence));
}

static uint32_t prv_read_workout_id(void) {
  uint32_t value = 0;
  return persist_read_data(HC_PERSIST_WORKOUT_ID, &value, sizeof(value)) == sizeof(value) ? value : 0;
}

static void prv_store_workout_id(uint32_t workout_id) {
  persist_write_data(HC_PERSIST_WORKOUT_ID, &workout_id, sizeof(workout_id));
}

static void prv_set_sampling(bool enabled) {
  if (enabled) {
    const bool accepted = health_service_set_heart_rate_sample_period(s_sample_period_seconds);
    APP_LOG(APP_LOG_LEVEL_INFO, "HC sampler request=%u accepted=%d", s_sample_period_seconds, accepted);
  } else {
    health_service_set_heart_rate_sample_period(0);
  }
}

static uint8_t prv_bpm_or_zero(HealthMetric metric) {
  const HealthValue value = health_service_peek_current_value(metric);
  return (value > 0 && value <= UINT8_MAX) ? (uint8_t)value : 0;
}

static void prv_log_heart_rate(void) {
  if (!s_workout_active || !s_log_session) return;

  const time_t now = time(NULL);
  if (now <= 0) return;
  // A HealthEvent is not guaranteed to be one sensor sample. Bound data/battery use regardless.
  if (s_last_logged_at > 0 && now - s_last_logged_at < (time_t)s_sample_period_seconds) return;

  const uint8_t filtered = prv_bpm_or_zero(HealthMetricHeartRateBPM);
  const uint8_t raw = prv_bpm_or_zero(HealthMetricHeartRateRawBPM);
  if (filtered == 0 && raw == 0) return;

  // Persist the next sequence before logging. Gaps are safe; reusing an ID after a partial write is not.
  ++s_sequence;
  if (s_sequence == 0) ++s_sequence;
  prv_store_sequence(s_sequence);

  HealthCaptureRecord record = {
    .workout_id = s_workout_id,
    .sequence = s_sequence,
    .timestamp = (uint32_t)now,
    .filtered_bpm = filtered,
    .raw_bpm = raw,
    .flags = HC_FLAG_WORKOUT_ACTIVE |
        (filtered > 0 ? HC_FLAG_FILTERED_AVAILABLE : 0) |
        (raw > 0 ? HC_FLAG_RAW_AVAILABLE : 0),
    .version = HC_RECORD_VERSION,
  };

  const DataLoggingResult result = data_logging_log(s_log_session, &record, 1);
  if (result == DATA_LOGGING_SUCCESS) {
    s_last_logged_at = now;
  } else {
    APP_LOG(APP_LOG_LEVEL_ERROR, "HC log failed=%d seq=%lu", result, (unsigned long)s_sequence);
  }
}

static void prv_health_event(HealthEventType event, void *context) {
  if (event == HealthEventHeartRateUpdate) {
    prv_log_heart_rate();
  }
}

static void prv_worker_message(uint16_t type, AppWorkerMessage *message) {
  if (type == HC_MESSAGE_START) {
    s_sample_period_seconds = message->data0 >= 1 && message->data0 <= 600
        ? message->data0 : HC_DEFAULT_PERIOD_SECONDS;
    // AppWorkerMessage fields are 16-bit; the foreground app persists the full 32-bit workout ID.
    s_workout_id = prv_read_workout_id();
    s_workout_active = s_workout_id != 0;
    persist_write_bool(HC_PERSIST_ACTIVE, s_workout_active);
    persist_write_int(HC_PERSIST_PERIOD_SECONDS, s_sample_period_seconds);
    prv_store_workout_id(s_workout_id);
    s_last_logged_at = 0;
    prv_set_sampling(s_workout_active);
  } else if (type == HC_MESSAGE_STOP) {
    s_workout_active = false;
    persist_write_bool(HC_PERSIST_ACTIVE, false);
    prv_set_sampling(false);
  }
}

static void prv_init(void) {
  s_sample_period_seconds = prv_read_period();
  s_workout_active = persist_exists(HC_PERSIST_ACTIVE) && persist_read_bool(HC_PERSIST_ACTIVE);
  s_workout_id = prv_read_workout_id();
  s_sequence = prv_read_sequence();

  s_log_session = data_logging_create(
      HC_RECORD_TAG,
      DATA_LOGGING_BYTE_ARRAY,
      sizeof(HealthCaptureRecord),
      true);
  if (!s_log_session) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "HC could not open data log");
  }

  s_health_subscribed = health_service_events_subscribe(prv_health_event, NULL);
  if (!s_health_subscribed) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "HC HealthService unavailable");
  }
  app_worker_message_subscribe(prv_worker_message);

  if (s_workout_active && s_workout_id != 0) {
    prv_set_sampling(true);
  } else {
    // A worker launched from Settings must never accidentally leave high-rate sampling enabled.
    s_workout_active = false;
    persist_write_bool(HC_PERSIST_ACTIVE, false);
  }
}

static void prv_deinit(void) {
  // Always undo the requested sample period, including replacement by another background worker.
  prv_set_sampling(false);
  if (s_health_subscribed) health_service_events_unsubscribe();
  if (s_log_session) data_logging_finish(s_log_session);
}

int main(void) {
  prv_init();
  worker_event_loop();
  prv_deinit();
}
