#include <pebble.h>
#include <stdio.h>
#include <stdint.h>
#include <time.h>

// Shared with worker_src/worker.c. Keep these values in sync with the mobile decoder.
#define HC_PERSIST_ACTIVE 100
#define HC_PERSIST_PERIOD_SECONDS 101
#define HC_PERSIST_WORKOUT_ID 102
#define HC_PERSIST_NEXT_WORKOUT_ID 104

#define HC_MESSAGE_START 1
#define HC_MESSAGE_STOP 2

// 1 second matches Pebble's built-in workout request. Five seconds is the initial
// default until a physical Time 2 battery/cadence test establishes a production value.
static const uint16_t s_period_options[] = { 1, 5, 15 };
static const uint8_t s_period_option_count = sizeof(s_period_options) / sizeof(s_period_options[0]);

static Window *s_window;
static TextLayer *s_text;
static uint8_t s_period_index = 1;  // Five seconds is the initial default.
static char s_status[44] = "Ready";

static uint16_t prv_selected_period(void) {
  return s_period_options[s_period_index];
}

static void prv_refresh(void) {
  const bool active = persist_exists(HC_PERSIST_ACTIVE) && persist_read_bool(HC_PERSIST_ACTIVE);
  static char text[180];
  snprintf(text, sizeof(text),
      "Health Capture\n\n"
      "%s\n"
      "Workout HR: every ~%us\n\n"
      "SELECT: %s\n"
      "UP/DOWN: change rate\n\n"
      "%s",
      active ? "WORKOUT ACTIVE" : "Ready to start",
      (unsigned int)prv_selected_period(),
      active ? "stop" : "start",
      s_status);
  text_layer_set_text(s_text, text);
}

static void prv_set_status(const char *status) {
  snprintf(s_status, sizeof(s_status), "%s", status);
  prv_refresh();
}

static void prv_send_worker_message(uint8_t type, uint16_t period) {
  AppWorkerMessage message = {
    .data0 = period,
  };
  app_worker_send_message(type, &message);
}

static uint32_t prv_read_u32(int key) {
  uint32_t value = 0;
  return persist_read_data(key, &value, sizeof(value)) == sizeof(value) ? value : 0;
}

static void prv_write_u32(int key, uint32_t value) {
  persist_write_data(key, &value, sizeof(value));
}

// A persisted counter gives each manual session a unique identity even if a user starts two
// workouts during the same clock second. The per-record UTC timestamp remains the source of time.
static uint32_t prv_next_workout_id(void) {
  uint32_t workout_id = prv_read_u32(HC_PERSIST_NEXT_WORKOUT_ID);
  ++workout_id;
  if (workout_id == 0) ++workout_id;
  prv_write_u32(HC_PERSIST_NEXT_WORKOUT_ID, workout_id);
  return workout_id;
}

static void prv_start_workout(void) {
  const time_t now = time(NULL);
  if (now <= 0) {
    prv_set_status("Wait for watch time sync");
    return;
  }

  const uint16_t period = prv_selected_period();
  persist_write_bool(HC_PERSIST_ACTIVE, true);
  persist_write_int(HC_PERSIST_PERIOD_SECONDS, period);
  prv_write_u32(HC_PERSIST_WORKOUT_ID, prv_next_workout_id());

  // Storage is written before launch: if the worker begins later, it still has its full state.
  const AppWorkerResult result = app_worker_launch();
  if (result == APP_WORKER_RESULT_SUCCESS || result == APP_WORKER_RESULT_ALREADY_RUNNING) {
    prv_send_worker_message(HC_MESSAGE_START, period);
    prv_set_status("Capturing locally");
  } else if (result == APP_WORKER_RESULT_ASKING_CONFIRMATION) {
    prv_set_status("Allow Background App on watch");
  } else {
    persist_write_bool(HC_PERSIST_ACTIVE, false);
    prv_set_status("Worker could not start");
  }
}

static void prv_stop_workout(void) {
  persist_write_bool(HC_PERSIST_ACTIVE, false);
  prv_send_worker_message(HC_MESSAGE_STOP, 0);

  // Deinitialisation resets the requested HR period and closes the DataLogging session.
  const AppWorkerResult result = app_worker_kill();
  if (result == APP_WORKER_RESULT_SUCCESS || result == APP_WORKER_RESULT_NOT_RUNNING) {
    prv_set_status("Saved; waiting for phone sync");
  } else if (result == APP_WORKER_RESULT_DIFFERENT_APP) {
    prv_set_status("Another background app is active");
  } else {
    prv_set_status("Stop requested");
  }
}

static void prv_select_click(ClickRecognizerRef recognizer, void *context) {
  const bool active = persist_exists(HC_PERSIST_ACTIVE) && persist_read_bool(HC_PERSIST_ACTIVE);
  if (active) {
    prv_stop_workout();
  } else {
    prv_start_workout();
  }
}

static void prv_up_click(ClickRecognizerRef recognizer, void *context) {
  s_period_index = (s_period_index + s_period_option_count - 1) % s_period_option_count;
  persist_write_int(HC_PERSIST_PERIOD_SECONDS, prv_selected_period());
  prv_set_status("Rate selected for next workout");
}

static void prv_down_click(ClickRecognizerRef recognizer, void *context) {
  s_period_index = (s_period_index + 1) % s_period_option_count;
  persist_write_int(HC_PERSIST_PERIOD_SECONDS, prv_selected_period());
  prv_set_status("Rate selected for next workout");
}

static void prv_click_config_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click);
  window_single_click_subscribe(BUTTON_ID_UP, prv_up_click);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_down_click);
}

static void prv_window_load(Window *window) {
  const Layer *root = window_get_root_layer(window);
  const GRect bounds = layer_get_bounds(root);
  s_text = text_layer_create(bounds);
  text_layer_set_text_alignment(s_text, GTextAlignmentCenter);
  text_layer_set_font(s_text, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_background_color(s_text, GColorClear);
  layer_add_child(root, text_layer_get_layer(s_text));
  prv_refresh();
}

static void prv_window_unload(Window *window) {
  text_layer_destroy(s_text);
}

static void prv_init(void) {
  const int stored_period = persist_exists(HC_PERSIST_PERIOD_SECONDS)
      ? persist_read_int(HC_PERSIST_PERIOD_SECONDS) : 5;
  for (uint8_t i = 0; i < s_period_option_count; ++i) {
    if (s_period_options[i] == stored_period) {
      s_period_index = i;
      break;
    }
  }

  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  window_set_click_config_provider(s_window, prv_click_config_provider);
  window_stack_push(s_window, true);
}

static void prv_deinit(void) {
  window_destroy(s_window);
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
